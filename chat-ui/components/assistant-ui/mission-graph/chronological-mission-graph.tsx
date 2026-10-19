"use client";

import { useCallback, useMemo, useState, type FC } from "react";
import { ReactFlow, Background, Controls, MiniMap, type Edge } from "@xyflow/react";
import { GitBranch, ChevronsDownUp, ChevronsUpDown } from "lucide-react";
import { useAuiState } from "@assistant-ui/react";
import { useSparrowMetadata } from "../sparrow-metadata";
import { missionNodeTypes, type SectorFlowNode, type TraceFlowNode } from "./mission-nodes";
import { makeTraceTree } from "./trace-tree";
import { DecisionTraceDetails } from "./decision-trace-details";
import { sectorIsExpanded, toggleSector as toggleSectorState } from "./sector-visibility";
import { stagesFromTraces, traceSummary, traceHighlights, visibleTracePage, TRACE_PAGE_SIZE, type TraceStage } from "./stage-layout";

const WIDTH = 356;
const GAP = 72;
const ROW_HEIGHT = 88;
const ROW_GAP = 8;
type FlowNode = SectorFlowNode | TraceFlowNode;

export const SparrowChronologicalMissionGraph: FC<{ embedded?: boolean }> = ({ embedded = false }) => {
  const metadata = useSparrowMetadata();
  const running = useAuiState((s) => s.message.status?.type === "running");
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [branches, setBranches] = useState<Record<string, boolean>>({});
  const [pageIndices, setPageIndices] = useState<Record<string, number>>({});
  const [selected, setSelected] = useState<string | null>(null);
  const traces = metadata?.traces ?? [];
  const stages = useMemo(() => stagesFromTraces(traces), [traces]);

  const toggleSector = useCallback((id: string) => setCollapsed((s) => toggleSectorState(s, id)), []);
  const toggleBranch = useCallback((id: string) => setBranches((s) => ({ ...s, [id]: s[id] === false })), []);
  const expandAll = useCallback((expand: boolean) => {
    setCollapsed(Object.fromEntries(stages.map((stage) => [stage.id, !expand])));
    setBranches(Object.fromEntries(traces.map((trace) => [trace.id, expand])));
    setPageIndices({}); // Even "expand all" never renders more than 10 subnodes per stage.
  }, [stages, traces]);

  const { nodes, edges, hiddenByPage } = useMemo(() => {
    const nodes: FlowNode[] = [];
    const edges: Edge[] = [];
    let hiddenByPage = 0;
    const entries: TraceStage[] = stages.length ? stages : [{
      id: "mission", title: "Mission", subtitle: "Awaiting execution events", icon: "other" as const,
      status: running ? "running" as const : "succeeded" as const,
      traces: [], order: 1000, associations: {},
    }];
    entries.forEach((stage, index) => {
      const expanded = sectorIsExpanded(collapsed, stage.id);
      const flattened = expanded ? makeTraceTree(stage.traces, branches) : [];
      // Follow the newest page until the user explicitly pages backwards.
      // Manual page selection is preserved while more traces stream in.
      const following = pageIndices[stage.id] === undefined;
      const p = visibleTracePage(flattened, following ? Number.MAX_SAFE_INTEGER : pageIndices[stage.id]!, TRACE_PAGE_SIZE);
      hiddenByPage += Math.max(0, flattened.length - p.items.length);
      const height = expanded ? 88 + p.items.length * (ROW_HEIGHT + ROW_GAP) + (stage.traces.length ? 48 : 7)
        : 78 + (stage.traces.length ? 29 : 0);
      const id = `stage:${stage.id}`;
      nodes.push({
        id, type: "sector", position: { x: index * (WIDTH + GAP), y: 15 },
        draggable: false, selectable: false, style: { width: WIDTH, height },
        data: {
          label: stage.title, subtitle: stage.subtitle, icon: stage.icon, count: stage.traces.length, visibleCount: flattened.length,
          status: stage.status, expanded, page: p.page, pages: p.pages, following,
          from: flattened.length ? p.start + 1 : 0, to: p.end,
          onToggle: () => toggleSector(stage.id),
          onPrevious: () => setPageIndices((s) => ({ ...s, [stage.id]: Math.max(0, p.page - 1) })),
          onNext: () => setPageIndices((s) => {
            const next = { ...s };
            if (p.page + 1 >= p.pages - 1) delete next[stage.id];
            else next[stage.id] = p.page + 1;
            return next;
          }),
          onFollow: () => setPageIndices((s) => { const next = { ...s }; delete next[stage.id]; return next; }),
        },
      });
      p.items.forEach((row, ix) => {
        const parentOnPage = p.items.some((item) => item.trace.id === row.trace.parentId);
        nodes.push({
          id: `trace:${row.trace.id}`, type: "trace", parentId: id, extent: "parent",
          position: { x: 9, y: 85 + ix * (ROW_HEIGHT + ROW_GAP) },
          draggable: false, selectable: false, style: { width: WIDTH - 18, height: ROW_HEIGHT },
          data: {
            label: row.trace.title, status: row.trace.status, kind: row.trace.kind,
            depth: parentOnPage ? row.depth : 0, summary: traceSummary(row.trace), highlights: traceHighlights(row.trace),
            context: row.depth > 0 && !parentOnPage ? "Parent span is on another page" :
              stage.associations[row.trace.id] === "sequence" && row.trace.parentId ? "Associated by event order; parent span unavailable" : undefined,
            childCount: row.children, expanded: branches[row.trace.id] !== false,
            onToggle: () => toggleBranch(row.trace.id), onSelect: () => setSelected(row.trace.id),
            selected: selected === row.trace.id, durationMs: row.trace.durationMs,
          },
        });
      });
      if (index > 0) edges.push({
        id: `e:${index}`, source: `stage:${entries[index - 1]!.id}`, target: id,
        type: "smoothstep", animated: stage.status === "running",
        style: { stroke: "#94a3b8", strokeWidth: 1.5 },
      });
    });
    return { nodes, edges, hiddenByPage };
  }, [stages, collapsed, branches, pageIndices, selected, running, toggleSector, toggleBranch]);

  if (!metadata?.missionId && traces.length === 0) return null;
  const active = traces.filter((trace) => trace.status === "running").length;
  const chosen = traces.find((trace) => trace.id === selected);
  const chosenStage = stages.find((stage) => stage.traces.some((trace) => trace.id === selected));
  return (
    <section aria-label="Mission execution trace" className={embedded ? "relative w-full overflow-hidden bg-zinc-50 dark:bg-[#191a1e]" : "relative left-1/2 mb-5 w-[min(98vw,1600px)] max-w-5xl -translate-x-1/2 overflow-hidden rounded-xl border border-zinc-200 bg-zinc-50 dark:border-zinc-700 dark:bg-[#191a1e]"}>
      <header className="flex flex-wrap items-center gap-2 border-b border-zinc-200 px-3 py-2 dark:border-zinc-700">
        <GitBranch className="size-4 text-blue-700 dark:text-blue-300" aria-hidden="true" />
        <span className="text-xs font-semibold text-zinc-800 dark:text-zinc-100">Mission execution</span>
        <span className="text-[11px] text-zinc-500 dark:text-zinc-400">{stages.length} chronological groups · {traces.length} events · {TRACE_PAGE_SIZE} mini-nodes/page</span>
        <span className="ml-auto text-[11px] text-zinc-500 dark:text-zinc-400">{metadata?.complete ? "Completed" : active ? `${active} active` : running ? "Working" : "Stopped"}</span>
        <button type="button" title="Expand all directories" aria-label="Expand all directories" onClick={() => expandAll(true)} className="rounded p-1 text-zinc-500 hover:bg-zinc-200 dark:hover:bg-zinc-800"><ChevronsUpDown className="size-4" /></button>
        <button type="button" title="Collapse all directories" aria-label="Collapse all directories" onClick={() => expandAll(false)} className="rounded p-1 text-zinc-500 hover:bg-zinc-200 dark:hover:bg-zinc-800"><ChevronsDownUp className="size-4" /></button>
      </header>
      <div className="relative h-[470px] w-full md:h-[570px]" aria-label="Chronological decision and execution groups with nested trace events">
        <ReactFlow key={metadata?.missionId} nodes={nodes} edges={edges} nodeTypes={missionNodeTypes}
          nodesDraggable={false} nodesConnectable={false} elementsSelectable={false} panOnDrag
          fitView={stages.length <= 4} defaultViewport={{ x: 28, y: 25, zoom: 0.88 }}
          fitViewOptions={{ padding: 0.12, minZoom: 0.25, maxZoom: 1 }} minZoom={0.12} maxZoom={1.8}
          proOptions={{ hideAttribution: true }}>
          <Background gap={20} size={1} color="#e4e4e7" />
          <Controls showInteractive={false} position="bottom-left" className="!bottom-2 !left-2 !rounded-md !shadow-sm [&_.react-flow__controls-button]:!h-5 [&_.react-flow__controls-button]:!w-5 [&_.react-flow__controls-button_svg]:!h-3 [&_.react-flow__controls-button_svg]:!w-3" />
          {stages.length > 4 && <MiniMap pannable zoomable width={108} height={70} position="bottom-right" className="!hidden !rounded-md !border !border-zinc-200 !shadow-sm md:!block" />}
        </ReactFlow>
      </div>
      {chosen && <DecisionTraceDetails trace={chosen} all={traces}
        group={chosenStage?.title} grouping={chosenStage?.associations[chosen.id]}
        onClose={() => setSelected(null)} />}
      <div className="border-t border-zinc-200 px-2 py-1 text-[10px] text-zinc-500 dark:border-zinc-700">
        Decision and execution groups follow observed trace order. Open a group to view its Jev, LLM, GOAP, blackboard, tool and evidence events. Click a mini-node for decision parameters; page through a maximum of 10 at a time. {hiddenByPage > 0 ? `${hiddenByPage} nodes are outside the current visible pages or folded branches.` : "Only real instrumented events are shown."}
      </div>
    </section>
  );
};
