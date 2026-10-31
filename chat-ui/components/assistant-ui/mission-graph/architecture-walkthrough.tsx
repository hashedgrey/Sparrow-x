"use client";

import { useCallback, useEffect, useMemo, useState, type CSSProperties, type FC } from "react";
import {
  Background, Controls, Handle, MarkerType, MiniMap, Position, ReactFlow,
  ReactFlowProvider, useReactFlow, type Edge, type Node, type NodeProps,
} from "@xyflow/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
  Activity, CheckCircle2, ChevronLeft, ChevronRight, ChevronDown, ChevronUp,
  ChevronsLeft, ChevronsRight, CircleHelp, Copy, GitBranch, Layers3,
  LoaderCircle, Pause, Play, Route, ArrowRight, ArrowDownToLine, ArrowUpFromLine,
} from "lucide-react";
import type { SparrowMetadata } from "@/lib/sparrowx-chat-adapter";
import {
  projectObservations, type Observation, type WalkthroughCall, type WalkthroughGraph,
  type WalkthroughSource, type WalkthroughStage, type WalkthroughStageFile,
} from "@/lib/walkthrough/types";
import "@xyflow/react/dist/style.css";
import "./architecture-walkthrough.css";

/* One canonical source node per class, regardless of how many stages reference it.
 * A playback tick reveals exactly ONE new class in Architecture mode. */
type Step = { fileId: string; stageId: string };
type StageLayout = { stage: WalkthroughStage; fileIds: string[]; x: number; y: number; width: number; height: number; anchorStep: number };
type Blueprint = { steps: Step[]; layouts: StageLayout[]; owner: Map<string, string>; positions: Map<string, { x: number; y: number }> };
type SourceData = Record<string, unknown> & {
  source: WalkthroughSource; stageName: string; relationship: string;
  active: boolean; selected: boolean; related: boolean; choose: () => void;
};
type FrameData = Record<string, unknown> & {
  stage: WalkthroughStage; index: number | null; observed: number;
  active: boolean; optional: boolean;
};
type SourceNode = Node<SourceData, "source">;
type FrameNode = Node<FrameData, "stageFrame">;

const CARD_WIDTH = 246;
const CARD_HEIGHT = 106;
const BOX_WIDTH = 571;
const GROUP_GAP_X = 98;
const GROUP_GAP_Y = 54;
const GROUP_PADDING_TOP = 62;
const GROUP_PADDING_BOTTOM = 26;
const FILE_GAP_X = 25;
const FILE_GAP_Y = 24;
const PRIMARY_COLUMNS = 2;
const PLAY_INTERVAL_MS = 1150;

function basename(path: string): string { return path.replaceAll("\\", "/").split("/").at(-1) ?? path; }
function label(path: string): string { return basename(path).replace(/\.(java|tsx?|jsx?)$/i, ""); }

function StageFrame({ data }: NodeProps<FrameNode>) {
  return <div className={`sp-prog-stage ${data.active ? "sp-prog-stage-current" : ""} ${data.optional ? "sp-prog-stage-optional" : ""}`}>
    <div className="sp-prog-stage-header">
      <span className="sp-prog-stage-index">{data.index === null ? <GitBranch size={12}/> : String(data.index).padStart(2, "0")}</span>
      <div><strong>{data.stage.name}</strong><small>{data.optional ? "Optional / supporting path" : data.stage.lane}</small></div>
      {data.observed > 0 && <span className="sp-prog-stage-observed" title="Runtime events mapped to this stage"><Activity size={12}/>{data.observed}</span>}
    </div>
  </div>;
}

function SourceBox({ data }: NodeProps<SourceNode>) {
  const reduced = useReducedMotion();
  return <motion.div className={`sp-prog-source ${data.active ? "sp-prog-source-current" : ""} ${data.selected ? "sp-prog-source-selected" : ""} ${data.related ? "sp-prog-source-related" : ""}`}
    initial={reduced ? false : { opacity: 0, y: 12, scale: .96 }} animate={{ opacity: 1, y: 0, scale: 1 }}
    transition={{ duration: reduced ? 0 : .28, ease: "easeOut" }}>
    <Handle id="left" type="target" position={Position.Left} isConnectable={false} className="sp-prog-handle"/>
    <Handle id="top" type="target" position={Position.Top} isConnectable={false} className="sp-prog-handle"/>
    <button type="button" className="sp-prog-source-button nodrag nopan" onClick={data.choose}
      aria-label={`Inspect ${label(data.source.path)}`} aria-pressed={data.selected}>
      <span className="sp-prog-source-meta"><span>{data.relationship}</span><span className={data.source.inspected ? "sp-prog-verified" : "sp-prog-inferred"}>{data.source.inspected ? "Inspected" : "Inferred"}</span></span>
      <strong title={data.source.path}>{label(data.source.path)}</strong>
      <small>{data.source.responsibility || "Implementation boundary"}</small>
    </button>
    <Handle id="right" type="source" position={Position.Right} isConnectable={false} className="sp-prog-handle"/>
    <Handle id="bottom" type="source" position={Position.Bottom} isConnectable={false} className="sp-prog-handle"/>
  </motion.div>;
}
const nodeTypes = { source: SourceBox, stageFrame: StageFrame };

/* Ordering comes from SQLite stage membership and verified call edges, not alphabetical
 * filenames or a guessed execution order. Cycles fall back to a deterministic order. */
function orderFiles(ids: string[], calls: WalkthroughCall[]): string[] {
  const set = new Set(ids);
  const indegree = new Map(ids.map(id => [id, 0]));
  const outgoing = new Map(ids.map(id => [id, [] as string[]]));
  for (const call of calls) {
    if (!call.confirmed || call.relation === "conceptual" || !set.has(call.caller_file) || !set.has(call.callee_file) || call.caller_file === call.callee_file) continue;
    outgoing.get(call.caller_file)!.push(call.callee_file);
    indegree.set(call.callee_file, (indegree.get(call.callee_file) ?? 0) + 1);
  }
  const sorted = [...ids].sort();
  const result: string[] = [];
  while (result.length < sorted.length) {
    const next = sorted.find(id => !result.includes(id) && indegree.get(id) === 0);
    if (!next) break;
    result.push(next);
    for (const target of outgoing.get(next) ?? []) indegree.set(target, (indegree.get(target) ?? 1) - 1);
  }
  return [...result, ...sorted.filter(id => !result.includes(id))];
}
function groupHeight(fileCount: number): number {
  return GROUP_PADDING_TOP + Math.ceil(Math.max(1, fileCount) / 2) * (CARD_HEIGHT + FILE_GAP_Y) - FILE_GAP_Y + GROUP_PADDING_BOTTOM;
}
function buildBlueprint(graph: WalkthroughGraph): Blueprint {
  const sources = new Set(graph.sourceFiles.map(file => file.id));
  const primary = graph.stages.filter(s => s.kind === "primary").sort((a, b) => a.sort_order - b.sort_order);
  const auxiliary = graph.stages.filter(s => s.kind !== "primary").sort((a, b) => a.sort_order - b.sort_order);
  const owner = new Map<string, string>();
  const assigned = new Map<string, string[]>();
  const ordered = [...primary, ...auxiliary];
  for (const stage of ordered) {
    const unique = [...new Set(graph.stageFiles.filter(f => f.stage_id === stage.id).map(f => f.file_id))]
      .filter(id => sources.has(id) && !owner.has(id));
    const files = orderFiles(unique, graph.callEdges);
    files.forEach(id => owner.set(id, stage.id));
    assigned.set(stage.id, files);
  }
  const steps = primary.flatMap(stage => (assigned.get(stage.id) ?? []).map(fileId => ({ fileId, stageId: stage.id })));
  const primaryIndex = new Map(primary.map((stage, i) => [stage.id, i]));
  const anchorByStage = new Map<string, number>();
  for (const stage of auxiliary) {
    const related = graph.transitions.filter(t => t.from_stage === stage.id || t.to_stage === stage.id)
      .flatMap(t => [primaryIndex.get(t.from_stage), primaryIndex.get(t.to_stage)])
      .filter((i): i is number => i !== undefined);
    anchorByStage.set(stage.id, related.length ? Math.min(...related) : primary.length - 1);
  }
  const maxPrimaryHeights: number[] = [];
  for (let row = 0; row < Math.ceil(primary.length / PRIMARY_COLUMNS); row++) {
    const rowStages = primary.slice(row * PRIMARY_COLUMNS, (row + 1) * PRIMARY_COLUMNS);
    maxPrimaryHeights.push(Math.max(...rowStages.map(s => groupHeight((assigned.get(s.id) ?? []).length))));
  }
  const primaryY: number[] = [];
  let y = 35;
  for (const height of maxPrimaryHeights) { primaryY.push(y); y += height + GROUP_GAP_Y; }
  const layouts: StageLayout[] = [];
  for (const [i, stage] of primary.entries()) {
    const fileIds = assigned.get(stage.id) ?? [];
    const anchorStep = steps.findLastIndex(s => s.stageId === stage.id) + 1;
    layouts.push({ stage, fileIds, x: 35 + (i % PRIMARY_COLUMNS) * (BOX_WIDTH + GROUP_GAP_X),
      y: primaryY[Math.floor(i / PRIMARY_COLUMNS)], width: BOX_WIDTH, height: groupHeight(fileIds.length), anchorStep });
  }
  let extraY = 35;
  for (const stage of auxiliary) {
    const fileIds = assigned.get(stage.id) ?? [];
    if (fileIds.length === 0) continue; // Shared files remain in their original, canonical boxes.
    const anchor = anchorByStage.get(stage.id) ?? primary.length - 1;
    const anchorStage = primary[anchor]?.id;
    const anchorStep = anchorStage ? steps.findLastIndex(s => s.stageId === anchorStage) + 1 : steps.length;
    layouts.push({ stage, fileIds, x: 35 + PRIMARY_COLUMNS * (BOX_WIDTH + GROUP_GAP_X),
      y: extraY, width: BOX_WIDTH, height: groupHeight(fileIds.length), anchorStep });
    extraY += groupHeight(fileIds.length) + GROUP_GAP_Y;
  }
  const positions = new Map<string, { x: number; y: number }>();
  for (const stage of layouts) stage.fileIds.forEach((id, i) => positions.set(id, {
    x: stage.x + 22 + (i % 2) * (CARD_WIDTH + FILE_GAP_X),
    y: stage.y + GROUP_PADDING_TOP + Math.floor(i / 2) * (CARD_HEIGHT + FILE_GAP_Y),
  }));
  return { steps, layouts, owner, positions };
}

type StoryProps = {
  graph: WalkthroughGraph;
  source: WalkthroughSource | undefined;
  stage: WalkthroughStage | undefined;
  observation: Observation | undefined;
  mode: "architecture" | "live";
  selectedFromClick: boolean;
  cursor: number;
  total: number;
  previous: WalkthroughSource | undefined;
  upcoming: WalkthroughSource | undefined;
  visibleIds: Set<string>;
  onSelectFile: (id: string | null) => void;
  reduced: boolean;
};
/** Narrative describes the architectural role, not an assertion that this file ran.
 * Live mode only asserts that the mapped SSE stage was observed. */
function StoryPanel({ graph, source, stage, observation, mode, selectedFromClick, cursor,
  total, previous, upcoming, visibleIds, onSelectFile, reduced }: StoryProps) {
  const [expanded, setExpanded] = useState(false);
  const narrative = graph.narratives?.find(item => item.file_id === source?.id);
  const inbound = source ? graph.callEdges.filter(call => call.relation !== "conceptual" && call.callee_file === source.id && call.caller_file !== source.id) : [];
  const outbound = source ? graph.callEdges.filter(call => call.relation !== "conceptual" && call.caller_file === source.id && call.callee_file !== source.id) : [];
  const byId = new Map(graph.sourceFiles.map(item => [item.id, item]));
  const spans = source ? graph.sourceSpans.filter(item => item.file_id === source.id) : [];
  const sourceRoles = source ? graph.stageFiles.filter(item => item.file_id === source.id) : [];
  const linked = (calls: WalkthroughCall[], direction: "in" | "out") => calls.slice(0, 5).map(call => {
    const id = direction === "in" ? call.caller_file : call.callee_file;
    const target = byId.get(id);
    const available = visibleIds.has(id);
    return <button type="button" className="sp-prog-linked" key={call.id} disabled={!available}
      title={available ? `Inspect ${target?.path ?? id}` : "Component not yet revealed"}
      onClick={() => onSelectFile(id)}>
      <span className="sp-prog-linked-icon">{direction === "in" ? <ArrowDownToLine size={13}/> : <ArrowUpFromLine size={13}/>}</span>
      <span><strong>{target ? label(target.path) : id}</strong><small>{call.method_or_boundary}</small></span>
      <em>{call.confirmed ? "verified call" : "inferred"}</em>
    </button>;
  });
  return <motion.aside className="sp-prog-story" aria-label="Walkthrough component explanation"
    initial={reduced ? false : { opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }}
    exit={reduced ? undefined : { opacity: 0, x: -8 }} transition={{ duration: reduced ? 0 : .22 }}>
    <div className="sp-prog-story-top">
      <span className="sp-prog-story-eyebrow">{mode === "architecture" ? "ARCHITECTURE EXPLANATION" : "OBSERVED STAGE + ARCHITECTURE"}</span>
      <span className="sp-prog-story-index">{cursor} / {total}</span>
    </div>
    {source ? <>
      <div className="sp-prog-story-heading">
        <div><span>{stage?.name ?? "Supporting component"}</span><h3>{label(source.path)}</h3></div>
        {selectedFromClick && <button type="button" className="sp-prog-back-to-step" onClick={() => onSelectFile(null)} title="Return to current playback step">Current step</button>}
      </div>
      <p className="sp-prog-story-intro">{narrative?.contribution || source.responsibility}</p>
      <div className="sp-prog-story-chain" aria-label="Transformation through this component">
        <div className="sp-prog-story-part"><span>01 · RECEIVES</span><p>{narrative?.receives || "Inputs are defined by the source's incoming call boundaries."}</p></div>
        <div className="sp-prog-chain-arrow"><ArrowRight size={15}/></div>
        <div className="sp-prog-story-part sp-prog-story-part-main"><span>02 · CONTRIBUTES</span><p>{narrative?.contribution || source.responsibility}</p></div>
        <div className="sp-prog-chain-arrow"><ArrowRight size={15}/></div>
        <div className="sp-prog-story-part"><span>03 · PRODUCES</span><p>{narrative?.produces || "Outputs/side effects are determined by the referenced method boundaries."}</p></div>
      </div>
      <div className="sp-prog-story-why"><span>WHY IT MATTERS NEXT</span><p>{narrative?.enables || stage?.summary || "Supports one or more later execution boundaries."}</p></div>
      {narrative?.caution && <div className="sp-prog-story-caution"><CircleHelp size={15}/><p>{narrative.caution}</p></div>}
      <div className="sp-prog-story-wiring">
        <h4>Connected architecture</h4>
        <small>Actual call boundaries, not necessarily adjacent reveal steps.</small>
        {inbound.length > 0 && <div className="sp-prog-story-links"><span>Used by / upstream ({inbound.length})</span>{linked(inbound,"in")}</div>}
        {outbound.length > 0 && <div className="sp-prog-story-links"><span>Calls / downstream ({outbound.length})</span>{linked(outbound,"out")}</div>}
        {!inbound.length && !outbound.length && <p className="sp-prog-story-muted">No direct call edge is recorded for this component.</p>}
      </div>
      {mode === "architecture" && !selectedFromClick && <div className="sp-prog-story-sequence">
        <span>Reveal order · not guaranteed call order</span>
        <div><small>{previous ? `Previous: ${label(previous.path)}` : "First component"}</small><ArrowRight size={13}/><small>{upcoming ? `Next: ${label(upcoming.path)}` : "End of primary graph"}</small></div>
      </div>}
      <div className="sp-prog-story-source">
        <button type="button" className="sp-prog-story-expand" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
          <span>Source &amp; evidence <small>{source.inspected ? "Inspected" : "Inferred boundary"} · {sourceRoles.length} roles · {spans.length} spans</small></span>
          {expanded ? <ChevronUp size={16}/> : <ChevronDown size={16}/>}
        </button>
        {expanded && <div className="sp-prog-story-source-body">
          <div className="sp-prog-path"><code>{source.path}</code><button type="button" title="Copy source path" aria-label="Copy source path" onClick={() => void navigator.clipboard?.writeText(source.path)}><Copy size={15}/></button></div>
          {sourceRoles.map((role,i) => <p key={`${role.stage_id}:${i}`}><strong>{graph.stages.find(item => item.id === role.stage_id)?.name ?? role.stage_id}:</strong> {role.relationship}</p>)}
          {spans.slice(0,8).map(span => <p key={span.id}><code>{span.symbol} · L{span.start_line}{span.end_line ? `–${span.end_line}` : ""}</code> {span.note}</p>)}
        </div>}
      </div>
    </> : mode === "live" && stage ? <>
      <div className="sp-prog-story-heading"><div><span>Matched progress event</span><h3>{stage.name}</h3></div></div>
      <p className="sp-prog-story-intro">{stage.summary}</p>
      <div className="sp-prog-story-why"><span>OBSERVED SIGNAL</span><p>{observation?.rule.semantics ?? "SSE event matched this stage."}</p></div>
      <div className="sp-prog-story-caution"><CircleHelp size={15}/><p>The trace confirms this mapped stage was observed, not that every source file associated with the stage executed. Select a box to inspect its architectural contribution.</p></div>
    </> : <div className="sp-prog-story-empty"><Layers3 size={24}/><h3>From mission request to grounded result</h3>
      <p>{mode === "architecture" ? "Press Next to uncover the first component. Each reveal explains what enters, what changes, what comes out, and why later components need it." : "No stage is selected. Use Next to review an observed mission event; only recorded SSE signals count as executed stages."}</p>
      <div className="sp-prog-story-empty-hint">Click any revealed component to inspect it. The graph retains previously unveiled boxes and verified wiring.</div>
    </div>}
    <div className="sp-prog-story-foot">{mode === "live" ? "Observed events are runtime evidence; component descriptions are architectural context." : "Explanations are sourced from SQLite architecture mapping; arrows indicate documented/inferred calls."}</div>
  </motion.aside>;
}

type CanvasProps = {
  graph: WalkthroughGraph; blueprint: Blueprint;
  observations: Observation[]; mode: "architecture" | "live"; cursor: number;
  showOptional: boolean; viewAll: boolean; selectedFile: string | null;
  onSelectFile: (id: string | null) => void; reduced: boolean;
};
function ProgressiveCanvas({ graph, blueprint, observations, mode, cursor, showOptional, viewAll,
  selectedFile, onSelectFile, reduced }: CanvasProps) {
  const flow = useReactFlow();
  const stageMap = useMemo(() => new Map(graph.stages.map(stage => [stage.id, stage])), [graph]);
  const sources = useMemo(() => new Map(graph.sourceFiles.map(source => [source.id, source])), [graph]);
  const stageRefs = useMemo(() => new Map<string, WalkthroughStageFile[]>(graph.stages.map(stage =>
    [stage.id, graph.stageFiles.filter(ref => ref.stage_id === stage.id)])), [graph]);
  const activeStep = mode === "architecture" ? blueprint.steps[cursor - 1] : undefined;
  const activeObservation = mode === "live" ? observations[cursor - 1] : undefined;
  const activeStageId = activeStep?.stageId ?? activeObservation?.stageId;
  const activeFileId = activeStep?.fileId ?? null;
  const counts = useMemo(() => {
    const result = new Map<string, number>();
    observations.forEach(o => result.set(o.stageId, (result.get(o.stageId) ?? 0) + 1));
    return result;
  }, [observations]);
  const visible = useMemo(() => {
    const ids = new Set<string>();
    if (mode === "architecture") {
      blueprint.steps.slice(0, cursor).forEach(step => ids.add(step.fileId));
      if (showOptional) blueprint.layouts.filter(l => l.stage.kind !== "primary" && l.anchorStep <= cursor)
        .forEach(layout => layout.fileIds.forEach(id => ids.add(id)));
    } else {
      const observedStages = new Set(observations.slice(0, cursor).map(o => o.stageId));
      graph.stageFiles.filter(ref => observedStages.has(ref.stage_id)).forEach(ref => ids.add(ref.file_id));
      if (showOptional) for (const stageId of observedStages) {
        // The supplementary source nodes show context only: a matched stage does not
        // prove that every class attached to that stage executed.
        const layout = blueprint.layouts.find(l => l.stage.id === stageId);
        layout?.fileIds.forEach(id => ids.add(id));
      }
    }
    return ids;
  }, [mode, blueprint, cursor, showOptional, observations, graph.stageFiles]);
  const focusedFileId = selectedFile && (mode === "architecture" || visible.has(selectedFile)) ? selectedFile : activeFileId;
  const relatedFileIds = new Set(graph.callEdges.filter(call => call.relation !== "conceptual" &&
    (call.caller_file === focusedFileId || call.callee_file === focusedFileId))
    .flatMap(call => [call.caller_file, call.callee_file]));

  const { nodes, edges } = useMemo(() => {
    const frames: (SourceNode | FrameNode)[] = [];
    for (const layout of blueprint.layouts) {
      const visibleIds = layout.fileIds.filter(id => visible.has(id));
      if (!visibleIds.length) continue;
      frames.push({ id: `frame:${layout.stage.id}`, type: "stageFrame", position: { x: layout.x, y: layout.y },
        data: { stage: layout.stage, index: layout.stage.kind === "primary" ? layout.stage.sort_order : null,
          active: activeStageId === layout.stage.id, observed: counts.get(layout.stage.id) ?? 0, optional: layout.stage.kind !== "primary" },
        style: { width: layout.width, height: layout.height }, draggable: false, selectable: false,
        connectable: false, zIndex: 0,
      });
      for (const id of visibleIds) {
        const source = sources.get(id);
        if (!source) continue;
        const index = layout.fileIds.indexOf(id);
        const role = stageRefs.get(layout.stage.id)?.find(ref => ref.file_id === id)?.relationship ?? "component";
        frames.push({ id, parentId: `frame:${layout.stage.id}`, extent: "parent", type: "source",
          position: { x: 22 + (index % 2) * (CARD_WIDTH + FILE_GAP_X),
            y: GROUP_PADDING_TOP + Math.floor(index / 2) * (CARD_HEIGHT + FILE_GAP_Y) },
          style: { width: CARD_WIDTH, height: CARD_HEIGHT }, draggable: false, selectable: true, connectable: false, zIndex: 2,
          data: { source, stageName: layout.stage.name, relationship: role, active: activeFileId === id,
            selected: selectedFile === id, related: relatedFileIds.has(id), choose: () => onSelectFile(id) },
        });
      }
    }
    const wires: Edge[] = graph.callEdges.filter(call => call.relation !== "conceptual" && call.caller_file !== call.callee_file &&
      visible.has(call.caller_file) && visible.has(call.callee_file)).map(call => {
      const from = blueprint.positions.get(call.caller_file);
      const to = blueprint.positions.get(call.callee_file);
      const horizontal = from && to && Math.abs(from.y - to.y) < 90 && from.x < to.x;
      const emphasized = Boolean(focusedFileId && (call.caller_file === focusedFileId || call.callee_file === focusedFileId));
      const animated = !reduced && (activeFileId === call.callee_file || (mode === "live" && activeStageId &&
        blueprint.owner.get(call.callee_file) === activeStageId));
      return { id: `call:${call.id}`, source: call.caller_file, target: call.callee_file,
        sourceHandle: horizontal ? "right" : "bottom", targetHandle: horizontal ? "left" : "top",
        type: "smoothstep", animated, zIndex: 1,
        className: `${animated ? "sp-prog-wire-active" : ""} ${emphasized ? "sp-prog-wire-related" : ""}`.trim(),
        label: emphasized ? call.method_or_boundary : undefined,
        labelStyle: { fontSize: 9, fill: "#3e648c", fontWeight: 600 },
        labelBgStyle: { fill: "#f8fbff", fillOpacity: .98 },
        labelBgPadding: [4, 4], labelBgBorderRadius: 4,
        style: { stroke: call.confirmed ? "#7c96b2" : "#aab7c7", strokeWidth: emphasized ? 2.7 : animated ? 2.2 : 1.45,
          strokeDasharray: call.confirmed ? undefined : "5 5" },
        markerEnd: { type: MarkerType.ArrowClosed, color: call.confirmed ? "#7c96b2" : "#aab7c7", width: 13, height: 13 },
      } satisfies Edge;
    });
    return { nodes: frames, edges: wires };
  }, [blueprint, graph.callEdges, visible, activeStageId, activeFileId, focusedFileId, counts, sources, stageRefs, selectedFile, onSelectFile, mode, reduced]);

  // Positions come from the COMPLETE architecture, not from currently visible nodes;
  // hiding/revealing components must never reflow earlier boxes.
  useEffect(() => {
    if (nodes.length === 0) return;
    const frame = requestAnimationFrame(() => {
      if (viewAll) {
        void flow.fitView({ duration: reduced ? 0 : 400, padding: .12, minZoom: .18, maxZoom: 1.05 });
        return;
      }
      const position = focusedFileId ? blueprint.positions.get(focusedFileId) : undefined;
      const layout = blueprint.layouts.find(l => l.stage.id === activeStageId);
      if (position) {
        void flow.setCenter(position.x + CARD_WIDTH / 2, position.y + CARD_HEIGHT / 2,
          { zoom: .9, duration: reduced ? 0 : 420 });
      } else if (layout) {
        void flow.setCenter(layout.x + layout.width / 2, layout.y + layout.height / 2,
          { zoom: .82, duration: reduced ? 0 : 420 });
      }
    });
    return () => cancelAnimationFrame(frame);
  // nodes.length is intentionally the dependency, not 'nodes': data updates shouldn't pan.
  }, [flow, blueprint, focusedFileId, activeStageId, viewAll, reduced, nodes.length]);

  const selected = selectedFile && visible.has(selectedFile) ? sources.get(selectedFile) : undefined;
  const focusSource = selected ?? (mode === "architecture" && activeFileId ? sources.get(activeFileId) : undefined);
  const activeStage = activeStageId ? stageMap.get(activeStageId) : undefined;
  const focusStageId = focusSource ? blueprint.owner.get(focusSource.id) : activeStageId;
  const focusStage = focusStageId ? stageMap.get(focusStageId) : undefined;
  const previousId = mode === "architecture" && cursor > 1 ? blueprint.steps[cursor - 2]?.fileId : undefined;
  const upcomingId = mode === "architecture" ? blueprint.steps[cursor]?.fileId : undefined;
  const previous = previousId ? sources.get(previousId) : undefined;
  const upcoming = upcomingId ? sources.get(upcomingId) : undefined;
  return <div className="sp-prog-workspace">
    <div className="sp-prog-canvas" role="region" aria-label="Cumulative component execution graph">
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView
        minZoom={.16} maxZoom={1.7} nodesDraggable={false} nodesConnectable={false} elementsSelectable={true}
        onNodeClick={(_event, node) => { if (node.type === "source") onSelectFile(node.id); }}
        proOptions={{ hideAttribution: true }}>
        <Background gap={23} size={1} color="#e5ebf3"/>
        <Controls position="top-right" showInteractive={false}/>
        <MiniMap  style={{ width: 120, height: 75 }} position="bottom-right" pannable zoomable maskColor="rgba(242,246,251,.72)" nodeColor={n => n.type === "stageFrame" ? "#e2eaf4" : "#6e9bcf"}/>
      </ReactFlow>
      {cursor === 0 && <div className="sp-prog-empty" role="status">
        <Layers3 size={26}/><strong>{mode === "architecture" ? "Ready to explore the execution path" : "Ready to replay observed events"}</strong>
        <span>{mode === "architecture" ? "Choose Next to reveal the first Java component." : observations.length ? "Choose Next to reveal the first observed stage." : "No matching live events are available for this mission."}</span>
      </div>}
      <AnimatePresence>
        {activeStage && <motion.div className="sp-prog-current-caption" key={`${mode}:${activeStage.id}:${cursor}`}
          initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: .2 }}>
          <strong>{activeStage.name}</strong>
          <span>{mode === "live" ? `${activeObservation?.signal.kind ?? activeObservation?.signal.event ?? "Event"} · observed #${activeObservation?.signal.seq}`
            : activeStep ? `${label(sources.get(activeStep.fileId)?.path ?? activeStep.fileId)} · revealed component ${cursor} of ${blueprint.steps.length}` : "Architecture"}</span>
        </motion.div>}
      </AnimatePresence>
      <div className="sp-prog-legend"><span><i className="sp-prog-line-solid"/> Verified calls</span><span><i className="sp-prog-line-inferred"/> Inferred calls</span>
        <span><i className="sp-prog-line-active"/> Illustrative motion</span></div>
    </div>
    <AnimatePresence mode="wait" initial={false}>
      <StoryPanel key={`${mode}:${focusSource?.id ?? focusStage?.id ?? "start"}:${selected ? "selected" : "current"}`}
        graph={graph} source={focusSource} stage={focusStage} observation={activeObservation}
        mode={mode} selectedFromClick={Boolean(selected)} cursor={cursor}
        total={mode === "architecture" ? blueprint.steps.length : observations.length}
        previous={previous} upcoming={upcoming} visibleIds={visible}
        onSelectFile={onSelectFile} reduced={reduced}/>
    </AnimatePresence>
  </div>;
}

export const ArchitectureWalkthrough: FC<{ metadata?: SparrowMetadata }> = ({ metadata }) => {
  const [graph, setGraph] = useState<WalkthroughGraph | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<"architecture" | "live">("architecture");
  const [architectureCursor, setArchitectureCursor] = useState(0);
  const [liveCursor, setLiveCursor] = useState(0);
  const [followLive, setFollowLive] = useState(true);
  const [playing, setPlaying] = useState(false);
  const [viewAll, setViewAll] = useState(false);
  const [showOptional, setShowOptional] = useState(false);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const reduced = Boolean(useReducedMotion());
  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/walkthrough", { cache: "no-store", signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error(`Architecture unavailable (HTTP ${response.status})`); return await response.json() as WalkthroughGraph; })
      .then(data => { setGraph(data); setError(null); })
      .catch(e => { if (!controller.signal.aborted) setError(String(e)); });
    return () => controller.abort();
  }, []);
  const blueprint = useMemo(() => graph ? buildBlueprint(graph) : null, [graph]);
  const observations = useMemo(() => graph ? [...projectObservations(graph, metadata)].sort((a, b) => a.signal.seq - b.signal.seq) : [], [graph, metadata]);
  const max = mode === "architecture" ? (blueprint?.steps.length ?? 0) : observations.length;
  const cursor = Math.min(mode === "architecture" ? architectureCursor : liveCursor, max);
  const activeStageId = mode === "architecture" ? blueprint?.steps[cursor - 1]?.stageId : observations[cursor - 1]?.stageId;

  const changeCursor = useCallback((next: number, all = false) => {
    const limit = mode === "architecture" ? (blueprint?.steps.length ?? 0) : observations.length;
    const bounded = Math.min(limit, Math.max(0, next));
    if (mode === "architecture") setArchitectureCursor(bounded);
    else { setLiveCursor(bounded); setFollowLive(false); }
    setSelectedFile(null);
    setViewAll(all);
  }, [mode, blueprint, observations.length]);
  useEffect(() => {
    if (mode === "live" && followLive) { setLiveCursor(observations.length); setViewAll(false); }
  }, [mode, followLive, observations.length]);
  useEffect(() => {
    if (!playing) return;
    if (cursor >= max) { setPlaying(false); return; }
    const timeout = window.setTimeout(() => {
      if (mode === "architecture") setArchitectureCursor(v => Math.min(max, v + 1));
      else { setFollowLive(false); setLiveCursor(v => Math.min(max, v + 1)); }
      setViewAll(false);
    }, PLAY_INTERVAL_MS);
    return () => window.clearTimeout(timeout);
  }, [playing, cursor, max, mode]);
  // Playing the architecture and viewing a real mission are different claims.
  const switchMode = (next: "architecture" | "live") => {
    setPlaying(false); setMode(next); setSelectedFile(null); setViewAll(false);
    if (next === "live") { setLiveCursor(observations.length); setFollowLive(true); }
  };
  const progress = max ? Math.round(cursor / max * 100) : 0;
  return <section className="sp-prog-root" aria-label="Progressive mission architecture walkthrough">
    <header className="sp-prog-header">
      <div className="sp-prog-identity"><span className="sp-prog-logo"><Route size={19}/></span>
        <div><h2>Mission execution</h2><p>One graph · reveal components as execution progresses</p></div></div>
      <div className="sp-prog-top-actions">
        <div className="sp-prog-mode" role="group" aria-label="Walkthrough playback mode">
          <button type="button" className={mode === "architecture" ? "sp-prog-mode-active" : ""} aria-pressed={mode === "architecture"} onClick={() => switchMode("architecture")}><Layers3 size={14}/> Architecture</button>
          <button type="button" className={mode === "live" ? "sp-prog-mode-active" : ""} aria-pressed={mode === "live"} onClick={() => switchMode("live")}><Activity size={14}/> Live replay {observations.length ? `(${observations.length})` : ""}</button>
        </div>
        <label className="sp-prog-optional"><input type="checkbox" checked={showOptional} onChange={e => setShowOptional(e.target.checked)}/><GitBranch size={14}/> Optional internals</label>
        {mode === "live" && <button type="button" className={`sp-prog-follow ${followLive ? "sp-prog-follow-active" : ""}`}
          onClick={() => { setFollowLive(true); setLiveCursor(observations.length); setPlaying(false); setViewAll(false); }}><Activity size={14}/> Follow live</button>}
      </div>
    </header>
    {error ? <div className="sp-prog-state" role="alert">{error}. Verify the SQLite path configured on the Next.js server.</div>
      : !graph || !blueprint ? <div className="sp-prog-state" role="status"><LoaderCircle className="sp-prog-spin"/> Loading execution graph…</div>
      : <>
        <div className="sp-prog-summary">
          <div><span className="sp-prog-summary-kicker">{mode === "architecture" ? "ARCHITECTURE PROGRESSION" : "OBSERVED EVENT REPLAY"}</span>
            <strong>{activeStageId ? graph.stages.find(s => s.id === activeStageId)?.name ?? activeStageId : "Execution entry"}</strong></div>
          <span>{mode === "architecture" ? `${cursor} of ${blueprint.steps.length} components` : `${cursor} of ${observations.length} matched signals`}</span>
        </div>
        <ReactFlowProvider><ProgressiveCanvas graph={graph} blueprint={blueprint} observations={observations}
          mode={mode} cursor={cursor} showOptional={showOptional} viewAll={viewAll} selectedFile={selectedFile} onSelectFile={setSelectedFile} reduced={reduced}/></ReactFlowProvider>
        <footer className="sp-prog-transport" aria-label="Walkthrough playback controls">
          <div className="sp-prog-controls">
            <button type="button" aria-label="Start" title="Start" disabled={cursor === 0} onClick={() => { setPlaying(false); changeCursor(0); }}><ChevronsLeft size={18}/></button>
            <button type="button" aria-label="Previous component or event" title="Previous" disabled={cursor === 0} onClick={() => { setPlaying(false); changeCursor(cursor - 1); }}><ChevronLeft size={18}/></button>
            <button type="button" className="sp-prog-play" aria-label={playing ? "Pause playback" : "Play walkthrough"} title={playing ? "Pause" : "Play"}
              disabled={max === 0 || (cursor === max && !playing)} onClick={() => { if (mode === "live") setFollowLive(false); setPlaying(v => !v); setViewAll(false); }}>
              {playing ? <Pause size={17} fill="currentColor"/> : <Play size={17} fill="currentColor"/>}</button>
            <button type="button" aria-label="Next component or event" title="Next" disabled={cursor >= max} onClick={() => { setPlaying(false); changeCursor(cursor + 1); }}><ChevronRight size={18}/></button>
            <button type="button" aria-label="End" title="End and fit all revealed components" disabled={cursor >= max && viewAll}
              onClick={() => { setPlaying(false); changeCursor(max, true); }}><ChevronsRight size={18}/></button>
          </div>
          <div className="sp-prog-scrub"><input type="range" min={0} max={Math.max(1,max)} step={1} value={cursor} disabled={max === 0}
            onChange={e => { setPlaying(false); changeCursor(Number(e.target.value)); }}
            aria-label={mode === "architecture" ? "Reveal component position" : "Replay event position"}
            style={{ "--sp-prog-progress": `${progress}%` } as CSSProperties}/>
            <div><span>{mode === "architecture" ? "Component" : "Event"} {cursor} / {max}</span><span>{progress}% revealed</span></div></div>
          <span className="sp-prog-runtime-status" title="Live observations are mapped from the existing SSE metadata; they do not prove every displayed class executed">
            {mode === "live" ? <><Activity size={14}/>{metadata?.missionId ? `Mission ${metadata.missionId.slice(0, 16)}…` : "No mission"}</> : <><CheckCircle2 size={14}/> SQLite architecture</>}
          </span>
        </footer>
        <p className="sp-prog-disclaimer">Architecture mode animates documented call relationships, not actual method execution. Live replay reveals contextual components for observed SSE stages; repeated events reuse existing boxes.</p>
      </>}
  </section>;
};
