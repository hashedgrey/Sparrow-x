"use client";

import { useEffect, useMemo, useState, type FC, type FormEvent } from "react";
import { Background, Controls, MarkerType, Position, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { AlertCircle, GitBranch, LockKeyhole, RefreshCw, Search, Workflow } from "lucide-react";
import type { AgentWorkflow, WorkflowCatalog, WorkflowFlowNode, WorkflowStep } from "@/lib/workflow-catalog";
import { parseWorkflowCatalog } from "@/lib/workflow-catalog";
import { layoutWorkflow } from "@/lib/workflow-graph";
import { buildMissionStateModel } from "@/lib/mission-state-model";
import { MissionStateFlow, MissionStateInspector } from "./mission-state-flow";
import "@xyflow/react/dist/style.css";
import "./workflow-explorer.css";

type LoadState = "loading" | "ready" | "unauthorized" | "error";

function Status({ value }: { value: boolean | null | undefined }) {
  return <span className={`sp-workflow-registration ${value === false ? "is-unregistered" : ""}`}>
    {value === true ? "Deployed" : value === false ? "Not in runtime" : "Registration unknown"}
  </span>;
}

function Detail({ label, value }: { label: string; value: string | number | null | undefined }) {
  if (value === null || value === undefined || value === "") return null;
  return <div className="sp-workflow-detail-row"><dt>{label}</dt><dd>{value}</dd></div>;
}

function DetailsPanel({ agent, node, step }: { agent: AgentWorkflow; node: WorkflowFlowNode | null; step: WorkflowStep | null }) {
  return <aside className="sp-workflow-inspector" aria-label="Workflow node inspector">
    <div className="sp-workflow-subhead">Inspector</div>
    {!node ? <>
      <strong className="sp-workflow-detail-title">{agent.agentName}</strong>
      <p className="sp-workflow-muted">Select a node to inspect declared inputs, outputs, conditions and planner metadata.</p>
      <dl className="sp-workflow-details">
        <Detail label="Declared planner" value={agent.plannerType}/>
        <Detail label="Class" value={agent.className}/>
        <Detail label="Provider" value={agent.provider}/>
        <Detail label="Actions" value={agent.steps.length}/>
      </dl>
    </> : <>
      <span className="sp-workflow-type">{node.kind === "DETACHED" ? "Not on derived route" : node.kind}</span>
      <strong className="sp-workflow-detail-title">{step?.name || node.label || node.kind}</strong>
      {step?.description && <p className="sp-workflow-muted">{step.description}</p>}
      <dl className="sp-workflow-details">
        <Detail label="Java method" value={step?.method}/>
        <Detail label="Step type" value={step?.type}/>
        <Detail label="Input types" value={step?.inputs?.join(", ")}/>
        <Detail label="Optional inputs" value={step?.optionalInputs?.join(", ")}/>
        <Detail label="Output" value={step?.output}/>
        <Detail label="Requires" value={step?.pre?.join(", ")}/>
        <Detail label="Produces" value={step?.post?.join(", ")}/>
        <Detail label="Static cost" value={step?.cost}/>
        <Detail label="Cost method" value={step?.costMethod}/>
        <Detail label="Value" value={step?.value}/>
        <Detail label="Goal value" value={step?.goalValue}/>
        <Detail label="Node detail" value={node.detail === "NO_GOAL_PATH" ? "Not in a statically derived minimal goal path" : node.detail}/>
        {node.kind === "DETACHED" && <div className="sp-workflow-detached-note">
          The catalog's static path enumerator did not place this step on a goal route. It may still be callable at runtime, especially when a method returns an abstract state type.
        </div>}
        {step?.plannerGenerated && step.name === "Nirvana" && <div className="sp-workflow-detached-note">
          Embabel-generated utility termination goal. Not a SparrowX business action.
        </div>}
        <Detail label="Trigger" value={step?.trigger}/>
      </dl>
      {step && <div className="sp-workflow-flags">
        {step.plannerGenerated && <span>Planner-generated</span>}
        {step.registered === false && <span>Not in runtime plan</span>}
        {step.goal && <span>Goal</span>}
        {step.canRerun && <span>Can rerun</span>}
        {step.readOnly && <span>Read-only</span>}
        {step.exportedRemote && <span>Remote MCP goal</span>}
      </div>}
    </>}
  </aside>;
}

export const WorkflowExplorer: FC = () => {
  const [catalog, setCatalog] = useState<WorkflowCatalog | null>(null);
  const [status, setStatus] = useState<LoadState>("loading");
  const [error, setError] = useState("");
  const [credentials, setCredentials] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [reload, setReload] = useState(0);
  const [search, setSearch] = useState("");
  const [selectedClass, setSelectedClass] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<number | null>(null);
  const [graphView, setGraphView] = useState<"declared" | "state">("declared");
  const [selectedStateNodeId, setSelectedStateNodeId] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      setStatus("loading");
      setError("");
      try {
        const response = await fetch("/api/workflows", {
          method: "GET", cache: "no-store", signal: controller.signal,
          headers: credentials ? { Authorization: credentials } : {},
        });
        if (response.status === 401 || response.status === 403) {
          if (!controller.signal.aborted) { setCatalog(null); setStatus("unauthorized"); setError(credentials ? "Credentials were rejected or lack permission." : "Authorized access is required for the workflow catalog."); }
          return;
        }
        const body: unknown = await response.json();
        if (!response.ok) throw new Error(
          typeof body === "object" && body !== null && "error" in body && typeof body.error === "string"
            ? body.error : `Workflow API returned HTTP ${response.status}`);
        const parsed = parseWorkflowCatalog(body);
        if (!controller.signal.aborted) {
          setCatalog(parsed);
          setSelectedClass(old => old && parsed.agents.some(a => a.className === old) ? old : parsed.agents[0]?.className ?? null);
          setStatus("ready");
        }
      } catch (cause) {
        if (!controller.signal.aborted) {
          setCatalog(null);
          setStatus("error");
          setError(cause instanceof Error ? cause.message : "Unable to read workflow catalog");
        }
      }
    }
    void load();
    return () => controller.abort();
  }, [credentials, reload]);

  const agents = catalog?.agents ?? [];
  const filteredAgents = useMemo(() => agents.filter(agent =>
    [agent.agentName, agent.className, agent.plannerType, agent.provider || ""].some(value =>
      value.toLowerCase().includes(search.trim().toLowerCase()))), [agents, search]);
  const agent = agents.find(a => a.className === selectedClass) ?? null;
  const hasStateFlow = useMemo(() => agent !== null && buildMissionStateModel(agent) !== null, [agent]);
  const isStateView = graphView === "state" && hasStateFlow;
  const graph = useMemo(() => agent?.flow ? layoutWorkflow(agent.flow) : null, [agent]);
  const currentNode = agent?.flow?.nodes.find(n => n.id === selectedNodeId) ?? null;
  const currentStep = agent?.steps.find(s => s.name === currentNode?.step) ?? null;
  const activeEdgeIds = useMemo(() => new Set(graph?.edges.flatMap((e, i) =>
    selectedPath !== null && e.paths.includes(selectedPath) ? [`${e.from}:${e.to}:${i}`] : []) ?? []), [graph, selectedPath]);
  const activeNodeIds = useMemo(() => {
    const ids = new Set<string>();
    if (selectedPath === null || !graph) return ids;
    graph.edges.forEach((e, i) => {
      if (activeEdgeIds.has(`${e.from}:${e.to}:${i}`)) { ids.add(e.from); ids.add(e.to); }
    });
    return ids;
  }, [graph, selectedPath, activeEdgeIds]);
  const nodes = useMemo<Node[]>(() => graph?.nodes.map(n => ({
    id: n.id, position: { x: n.x, y: n.y },
    data: { label: n.kind === "DETACHED" ? `${n.step || n.label || n.kind}` : n.step || n.label || (n.kind === "START" ? "Start" : n.kind === "END" ? "Goal reached" : n.kind) },
    className: `sp-workflow-node sp-workflow-node-${n.kind.toLowerCase()}${selectedPath !== null && !activeNodeIds.has(n.id) ? " sp-workflow-dim" : ""}${selectedNodeId === n.id ? " sp-workflow-selected" : ""}`,
    sourcePosition: Position.Right, targetPosition: Position.Left, selectable: true, draggable: false,
  })) ?? [], [graph, selectedNodeId, selectedPath, activeNodeIds]);
  const edges = useMemo<Edge[]>(() => graph?.edges.map((e,i) => ({
    id: `${e.from}:${e.to}:${i}`, source: e.from, target: e.to, type: "smoothstep",
    label: e.conditions.length ? e.conditions.join(", ") : e.loop ? "rerun" : undefined,
    labelStyle: { fontSize: 10 },
    animated: e.loop,
    style: { stroke: selectedPath !== null && activeEdgeIds.has(`${e.from}:${e.to}:${i}`) ? "#2469db" : "#94a9c2",
      strokeWidth: selectedPath !== null && activeEdgeIds.has(`${e.from}:${e.to}:${i}`) ? 2.6 : 1.4,
      opacity: selectedPath !== null && !activeEdgeIds.has(`${e.from}:${e.to}:${i}`) ? .22 : 1 },
    markerEnd: { type: MarkerType.ArrowClosed, color: "#94a9c2" },
  })) ?? [], [graph, activeEdgeIds, selectedPath]);

  function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!username || !password) return;
    // Kept only in this mounted React state; never written to storage or a URL.
    const bytes = new TextEncoder().encode(`${username}:${password}`);
    setCredentials(`Basic ${btoa(String.fromCharCode(...bytes))}`);
    setPassword("");
  }

  return <section className="sp-workflow-explorer" aria-label="Embabel workflow explorer">
    <div className="sp-workflow-topline">
      <div>
        <h2><Workflow size={17} aria-hidden="true" /> Workflow Explorer</h2>
        <p>Declared and deployed Embabel workflows — not execution history.</p>
      </div>
      <button type="button" className="sp-workflow-refresh" onClick={() => setReload(n => n+1)} disabled={status === "loading"}>
        <RefreshCw size={14} aria-hidden="true" /> Refresh catalog
      </button>
    </div>

    {status === "loading" && <div className="sp-workflow-state" role="status">Loading registered workflows…</div>}
    {status === "unauthorized" && <div className="sp-workflow-state" role="region" aria-label="Workflow catalog sign in">
      <LockKeyhole size={24} color="#2469db" aria-hidden="true" />
      <strong>Authentication required</strong>
      <p>{error}</p>
      <p>The catalog is protected by AGSvc HTTP security. Enter authorized HTTP Basic credentials for this session, or integrate your SP login with the proxy.</p>
      <form className="sp-workflow-login" onSubmit={connect}>
        <label>Username<input type="text" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required /></label>
        <label>Password<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
        <button type="submit">Connect</button>
      </form>
      <p className="sp-workflow-muted">Credentials stay in memory only. Use HTTPS outside localhost.</p>
    </div>}
    {status === "error" && <div className="sp-workflow-state" role="alert">
      <AlertCircle size={22} color="#a53535" aria-hidden="true" />
      <strong>Workflow catalog unavailable</strong><p>{error}</p>
      <button type="button" onClick={() => setReload(n=>n+1)}>Retry</button>
    </div>}
    {status === "ready" && catalog && <div className="sp-workflow-layout">
      <aside className="sp-workflow-sidebar" aria-label="Agent workflows">
        <div className="sp-workflow-subhead">Agents <span>{filteredAgents.length}</span></div>
        <label className="sp-workflow-search"><Search size={14} aria-hidden="true" />
          <input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Find an agent" aria-label="Filter agent workflows" />
        </label>
        <div className="sp-workflow-agent-list">
          {filteredAgents.map(a=><button type="button" key={a.className} className={`sp-workflow-agent ${selectedClass===a.className?"is-active":""}`}
            aria-pressed={selectedClass===a.className} onClick={()=>{setSelectedClass(a.className);setSelectedNodeId(null);setSelectedPath(null);setSelectedStateNodeId(null);setGraphView("declared");}}>
            <strong>{a.agentName}</strong><span>Declared: {a.plannerType} · {a.steps.length} steps</span>
          </button>)}
          {!filteredAgents.length && <p className="sp-workflow-muted">No matching agents.</p>}
        </div>
        {!isStateView && agent?.flow && <div className="sp-workflow-routes">
          <div className="sp-workflow-subhead">Derived routes <span>{agent.flow.paths.length}{agent.flow.truncated ? "+" : ""}</span></div>
          <button type="button" className={`sp-workflow-route ${selectedPath === null ? "is-active" : ""}`} onClick={()=>setSelectedPath(null)}>All routes</button>
          {agent.flow.paths.map((path, index)=><button key={index} type="button" className={`sp-workflow-route ${selectedPath===index?"is-active":""}`}
            onClick={()=>setSelectedPath(index)}>
            <strong>{index+1}. {path.goal || "Goal"}</strong>
            <span>{path.totalCost === null ? "Cost unspecified" : `Declared cost ${path.totalCost}${path.dynamicCost ? "+" : ""}`}{path.cheapest ? " · Lowest cost" : ""}</span>
          </button>)}
          {agent.flow.truncated && <p className="sp-workflow-muted">Route enumeration was truncated by the catalog.</p>}
        </div>}
      </aside>
      <div className="sp-workflow-canvas-wrap">
        <div className="sp-workflow-canvas-head">
          <div><strong>{agent?.agentName || "No agent selected"}</strong>
            {agent && <span>Declared planner: {agent.plannerType} · {agent.flow?.entryTypes.join(", ") || "No entry types declared"}</span>}</div>
          {agent && <Status value={agent.registered}/>}
        </div>
        {agent?.description && <p className="sp-workflow-description">{agent.description}</p>}
        {agent && <>
          <div className="sp-workflow-switch" role="group" aria-label="Workflow graph type">
            <button type="button" className={!isStateView ? "is-active" : ""} aria-pressed={!isStateView}
              onClick={() => {setGraphView("declared");setSelectedStateNodeId(null);}}>Declared workflow</button>
            <button type="button" className={isStateView ? "is-active" : ""} aria-pressed={isStateView}
              disabled={!hasStateFlow} title={!hasStateFlow ? "Only available for the validated MissionAgent source contract" : "Source-derived state transitions"}
              onClick={() => {setGraphView("state");setSelectedNodeId(null);setSelectedPath(null);}}>Dynamic state flow</button>
          </div>
          {!isStateView && <p className="sp-workflow-mode-caption">Catalog-derived minimal routes. "Not on derived route" does not mean the action cannot execute.</p>}
          {agent.className === "com.sparrowx.agentic.agents.MissionAgent" && <p className="sp-workflow-planner-note">
            <strong>Planner distinction:</strong> the catalog shows {agent.plannerType} for agent registration. The uploaded runner selects GOAP or HYBRID through Embabel ProcessOptions; actual selection appears in Mission Walkthrough.
          </p>}
        </>}
        {isStateView && agent ? <MissionStateFlow agent={agent} selectedNodeId={selectedStateNodeId} onSelectNode={setSelectedStateNodeId}/> : graph?.nodes.length ? <div className="sp-workflow-flow" aria-label="Declared workflow graph">
          <ReactFlow key={agent?.className} nodes={nodes} edges={edges} fitView fitViewOptions={{padding:.23}} minZoom={.2} maxZoom={1.8}
            nodesDraggable={false} nodesConnectable={false} elementsSelectable={true}
            onNodeClick={(_, n)=>setSelectedNodeId(n.id)} onPaneClick={()=>setSelectedNodeId(null)}>
            <Background gap={20} size={1} color="#dce6f3" />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div> : <div className="sp-workflow-state sp-workflow-no-flow">
          <GitBranch size={25} color="#64748b" aria-hidden="true" />
          <strong>No static flow returned</strong>
          <p>The catalog did not report a graph for this agent. Its declared steps are shown below; no relationships are inferred.</p>
        </div>}
        {!!agent && !isStateView && <div className="sp-workflow-bottom">
          <span>{agent.steps.length} declared steps</span>
          <span>{graph?.edges.length ?? 0} known connections</span>
          {!graph?.nodes.length && agent.steps.map(s=><span key={s.name} className="sp-workflow-step-pill">{s.name}</span>)}
        </div>}
      </div>
      {agent && isStateView ? <MissionStateInspector agent={agent} selectedNodeId={selectedStateNodeId}/> : agent ? <DetailsPanel agent={agent} node={currentNode} step={currentStep}/> :
        <aside className="sp-workflow-inspector"><div className="sp-workflow-subhead">Inspector</div><p className="sp-workflow-muted">No agent selected.</p></aside>}
    </div>}
    {status === "ready" && agents.length === 0 && <p className="sp-workflow-empty">No Embabel agents are registered in this AGSvc process.</p>}
  </section>;
};
