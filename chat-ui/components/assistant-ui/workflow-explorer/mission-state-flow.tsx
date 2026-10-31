"use client";

import { useMemo, type FC } from "react";
import { Background, Controls, MarkerType, Position, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { buildMissionStateModel } from "@/lib/mission-state-model";
import type { AgentWorkflow, WorkflowStep } from "@/lib/workflow-catalog";

export const MissionStateFlow: FC<{ agent: AgentWorkflow; selectedNodeId: string | null; onSelectNode: (id: string | null) => void }> = ({ agent, selectedNodeId, onSelectNode }) => {
  const model = useMemo(() => buildMissionStateModel(agent), [agent]);

  const nodes = useMemo<Node[]>(() => model?.nodes.map(node => ({
    id: node.id, position: { x: node.x, y: node.y },
    data: { label: node.label },
    className: `sp-state-node sp-state-node-${node.kind}${selectedNodeId === node.id ? " sp-state-node-selected" : ""}`,
    sourcePosition: Position.Right, targetPosition: Position.Left,
    draggable: false, selectable: true,
  })) ?? [], [model, selectedNodeId]);
  const edges = useMemo<Edge[]>(() => model?.edges.map((edge, index) => ({
    id: `state-edge:${index}`, source: edge.from, target: edge.to,
    type: edge.feedback ? "bezier" : "smoothstep",
    label: edge.label,
    labelStyle: { fontSize: 10, fill: "#516984" },
    labelBgPadding: [5, 3], labelBgBorderRadius: 4,
    labelBgStyle: { fill: "#fff", fillOpacity: .9 },
    style: { stroke: edge.feedback ? "#7094bd" : "#91a7c1", strokeWidth: 1.5,
      strokeDasharray: edge.feedback ? "5 5" : undefined },
    markerEnd: { type: MarkerType.ArrowClosed, color: edge.feedback ? "#7094bd" : "#91a7c1" },
    zIndex: edge.feedback ? 0 : 1,
  })) ?? [], [model]);

  if (!model) return <div className="sp-workflow-state sp-workflow-no-flow">
    <strong>State-flow contract not available</strong>
    <p>This source-derived view is supported only for the verified SparrowX MissionAgent action signatures. The deployed catalog differs or another agent is selected. Use Declared Workflow for authoritative catalog data.</p>
  </div>;

  return <section className="sp-state-view" aria-label="MissionAgent documented state machine">
    <div className="sp-state-intro">
      <strong>MissionAgent · source-derived state transitions</strong>
      <p>From MissionAgent.java (29 Oct 2026), checked against the deployed action signatures. This is a documentation view, not an observed run or an Embabel-planned route.</p>
    </div>
    <div className="sp-workflow-flow sp-state-flow" aria-label="MissionAgent state transition graph">
      <ReactFlow key={agent.className} nodes={nodes} edges={edges} fitView fitViewOptions={{padding:.14}}
        minZoom={.25} maxZoom={1.7} nodesDraggable={false} nodesConnectable={false}
        onNodeClick={(_, node) => onSelectNode(node.id)} onPaneClick={() => onSelectNode(null)}>
        <Background gap={20} size={1} color="#e5ecf5" />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
    <div className="sp-state-footnote">Dashed arrows return updated ReasonState for review. Evidence actions are alternatives selected by the reviewer; this does not assert that every action runs.</div>
  </section>;
};

export const MissionStateInspector: FC<{ agent: AgentWorkflow; selectedNodeId: string | null }> = ({ agent, selectedNodeId }) => {
  const model = buildMissionStateModel(agent);
  const chosen = model?.nodes.find(node => node.id === selectedNodeId) ?? null;
  const step: WorkflowStep | undefined = chosen?.stepName
    ? agent.steps.find(s => s.name === chosen.stepName) : undefined;
  return <aside className="sp-workflow-inspector" aria-label="Dynamic state flow inspector">
    <div className="sp-workflow-subhead">Inspector</div>
    {!chosen ? <>
      <strong className="sp-workflow-detail-title">Mission state model</strong>
      <p className="sp-workflow-muted">Select a state or action to inspect its purpose. Execution data belongs in Mission Walkthrough.</p>
      <p className="sp-workflow-muted">Documented from MissionAgent.java (29 Oct 2026); gated by the deployed catalog's method signatures.</p>
    </> : <>
      <span className="sp-workflow-type">{chosen.kind === "decision" ? "State transition" : chosen.kind}</span>
      <strong className="sp-workflow-detail-title">{chosen.label}</strong>
      <p className="sp-workflow-muted">{chosen.detail}</p>
      <dl className="sp-workflow-details">
        <div className="sp-workflow-detail-row"><dt>Model</dt><dd>Documented state transition, not live execution</dd></div>
        {step?.method && <div className="sp-workflow-detail-row"><dt>Java method</dt><dd>{step.method}</dd></div>}
        {Boolean(step?.inputs?.length) && <div className="sp-workflow-detail-row"><dt>Declared inputs</dt><dd>{step!.inputs!.join(", ")}</dd></div>}
        {step?.output && <div className="sp-workflow-detail-row"><dt>Declared output</dt><dd>{step.output}</dd></div>}
        {Boolean(step?.pre?.length) && <div className="sp-workflow-detail-row"><dt>Requires</dt><dd>{step!.pre!.join(", ")}</dd></div>}
        {step?.valueMethod && <div className="sp-workflow-detail-row"><dt>Dynamic value method</dt><dd>{step.valueMethod}</dd></div>}
        {step?.cost != null && <div className="sp-workflow-detail-row"><dt>Static cost</dt><dd>{step.cost}</dd></div>}
      </dl>
    </>}
  </aside>;
};
