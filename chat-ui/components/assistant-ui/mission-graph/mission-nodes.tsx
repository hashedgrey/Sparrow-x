"use client";

import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import {
  Activity, BrainCircuit, BookOpenText, CheckCircle2, ChevronDown, ChevronLeft,
  ChevronRight, FolderClosed, FolderOpen, Layers3, LoaderCircle, Network,
  PauseCircle, PlayCircle, Scale, ShieldCheck, Sparkles, Workflow, XCircle,
} from "lucide-react";
import type { TraceStatus } from "./types";
import type { StageIcon } from "./stage-layout";

export type SectorData = Record<string, unknown> & {
  label: string; subtitle: string; count: number; visibleCount: number; status: TraceStatus; expanded: boolean; icon: StageIcon;
  onToggle: () => void; page: number; pages: number; from: number; to: number; following: boolean;
  onPrevious: () => void; onNext: () => void; onFollow: () => void;
};
export type TraceData = Record<string, unknown> & {
  label: string; status: TraceStatus; durationMs?: number; kind?: string;
  summary: string; highlights: [string, string]; context?: string; depth: number; childCount: number;
  expanded: boolean; selected: boolean; onToggle: () => void; onSelect: () => void;
};
export type SectorFlowNode = Node<SectorData, "sector">;
export type TraceFlowNode = Node<TraceData, "trace">;

const statusClasses: Record<TraceStatus, string> = {
  running: "text-blue-700 dark:text-blue-300",
  succeeded: "text-emerald-700 dark:text-emerald-300",
  failed: "text-rose-700 dark:text-rose-300",
  paused: "text-amber-700 dark:text-amber-300",
};
const icons = {
  intent: BrainCircuit, decision: Scale, execution: PlayCircle,
  synthesis: Sparkles, other: Activity,
} satisfies Record<StageIcon, typeof Activity>;
const accent = {
  intent: "text-violet-600 dark:text-violet-300",
  decision: "text-indigo-600 dark:text-indigo-300",
  execution: "text-blue-700 dark:text-blue-300",
  synthesis: "text-emerald-700 dark:text-emerald-300",
  other: "text-zinc-600 dark:text-zinc-300",
} satisfies Record<StageIcon, string>;

/** Every recorded operation retains its own icon inside a decision/execution. */
function traceIcon(kind: string | undefined, label: string) {
  const k = (kind ?? "").toUpperCase();
  const text = label.toLowerCase();
  if (k.startsWith("JEV")) return BrainCircuit;
  if (k.startsWith("LLM")) return Sparkles;
  if (k.includes("GOAP") || k === "REASON_LOOP") return Workflow;
  if (k === "JAVA_VALIDATION" || k.includes("COVERAGE")) return ShieldCheck;
  if (k === "BLACKBOARD_SNAPSHOT") return Layers3;
  if (k.includes("DOCSVC") || text.includes("document")) return BookOpenText;
  if (k.includes("INTSVC") || text.includes("graph") || text.includes("root")) return Network;
  if (k === "CAPABILITY_ATTEMPT") return PlayCircle;
  if (k.startsWith("SYNTHES")) return Sparkles;
  return Activity;
}

export function StatusGlyph({ status }: { status: TraceStatus }) {
  const cn = `size-3.5 shrink-0 ${statusClasses[status]}`;
  if (status === "running") return <LoaderCircle className={`${cn} animate-spin`} aria-label="Running" />;
  if (status === "succeeded") return <CheckCircle2 className={cn} aria-label="Completed" />;
  if (status === "failed") return <XCircle className={cn} aria-label="Failed" />;
  return <PauseCircle className={cn} aria-label="Paused" />;
}

function stageLabel(kind?: string): string {
  if (!kind) return "STEP";
  if (kind.startsWith("JEV")) return "JEV";
  if (kind.startsWith("LLM")) return "LLM";
  if (kind === "BLACKBOARD_SNAPSHOT") return "BOARD";
  if (kind === "JAVA_VALIDATION") return "GATE";
  if (kind === "GOAP_RECOMMENDATION") return "GOAP";
  if (kind === "REASON_LOOP") return "LOOP";
  if (kind === "CAPABILITY_ATTEMPT") return "ACTION";
  if (kind === "INTENT_SNAPSHOT") return "INTENT";
  if (kind === "INTSVC_SEARCH" || kind === "INTSVC_GRAPH") return "INTSVC";
  if (kind === "DOCSVC") return "DOCSVC";
  return kind.length > 13 ? kind.slice(0, 11) + "…" : kind;
}

export function SectorNode({ data }: NodeProps<SectorFlowNode>) {
  const Icon = icons[data.icon] ?? Activity;
  return <div className="relative h-full overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-sm dark:border-zinc-700 dark:bg-[#25262b]">
    <Handle type="target" position={Position.Left} className="!size-2 !border-0 !bg-zinc-400" />
    <button type="button" onClick={data.onToggle} aria-expanded={data.expanded}
      aria-label={`${data.expanded ? "Collapse" : "Expand"} ${data.label} directory`}
      className="nodrag nopan flex h-[78px] w-full items-center gap-2.5 border-b border-zinc-100 px-3 text-left hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800">
      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-zinc-100 dark:bg-zinc-800">
        <Icon
            className={`size-[19px] ${accent[data.icon] ?? accent.other}`}
            aria-hidden="true"
        />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">{data.label}</span>
        <span className="mt-0.5 block truncate text-[10px] text-zinc-500 dark:text-zinc-400" title={data.subtitle}>{data.subtitle}</span>
        <span className="mt-0.5 block text-[9px] text-zinc-400 dark:text-zinc-500">{data.count} recorded subtraces</span>
      </span>
      <StatusGlyph status={data.status} />
      {data.expanded ? <FolderOpen className="size-4 text-zinc-500" /> : <FolderClosed className="size-4 text-zinc-500" />}
      {data.expanded ? <ChevronDown className="size-3.5 text-zinc-500" /> : <ChevronRight className="size-3.5 text-zinc-500" />}
    </button>
    {!data.expanded && data.count > 0 &&
      <div className="mx-3 flex items-center gap-1 border-t border-zinc-100 py-2 dark:border-zinc-700">
        {Array.from({ length: Math.min(10, data.count) }, (_, i) => <span key={i} className="size-2 rounded-sm bg-blue-200 dark:bg-blue-900" />)}
        {data.count > 10 && <span className="ml-1 text-[10px] text-zinc-500">+{data.count - 10}</span>}
      </div>}
    {data.expanded && data.count > 0 &&
      <div className="nodrag nopan absolute inset-x-2 bottom-2 flex h-8 items-center justify-between rounded-md border border-zinc-100 bg-zinc-50 px-2 text-[10px] text-zinc-500 dark:border-zinc-700 dark:bg-zinc-800">
        <span>{data.from}–{data.to} of {data.visibleCount}</span>
        <span className="flex items-center gap-1">
          <button type="button" onClick={data.onFollow} aria-label={`Follow live events in ${data.label}`}
            title="Jump to newest events and follow live updates" className={`nodrag nopan rounded px-1 py-0.5 ${data.following ? "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300" : "hover:bg-zinc-200 dark:hover:bg-zinc-700"}`}>Live</button>
          <button type="button" title="Previous 10 subtraces" aria-label={`Previous page of ${data.label}`} disabled={data.page === 0}
            onClick={data.onPrevious} className="nodrag nopan rounded p-1 enabled:hover:bg-zinc-200 disabled:opacity-30 dark:enabled:hover:bg-zinc-700"><ChevronLeft className="size-3.5" /></button>
          <span className="min-w-9 text-center">{data.page + 1}/{data.pages}</span>
          <button type="button" title="Next 10 subtraces" aria-label={`Next page of ${data.label}`} disabled={data.page >= data.pages - 1}
            onClick={data.onNext} className="nodrag nopan rounded p-1 enabled:hover:bg-zinc-200 disabled:opacity-30 dark:enabled:hover:bg-zinc-700"><ChevronRight className="size-3.5" /></button>
        </span>
      </div>}
    <Handle type="source" position={Position.Right} className="!size-2 !border-0 !bg-zinc-400" />
  </div>;
}

export function TraceNode({ data }: NodeProps<TraceFlowNode>) {
  const EventIcon = traceIcon(data.kind, data.label);
  return <div className={`flex h-full items-center rounded-lg border bg-zinc-50 text-left shadow-[0_1px_2px_rgba(0,0,0,0.03)] dark:bg-zinc-800/70 ${data.selected ? "border-blue-500 ring-1 ring-blue-200" : "border-zinc-200 dark:border-zinc-700"}`}>
    <span className="h-full shrink-0 border-r border-zinc-200/80 dark:border-zinc-700" style={{ width: 5 + Math.min(data.depth, 3) * 9 }} aria-hidden="true" />
    {data.childCount > 0 && <button type="button" onClick={data.onToggle} className="nodrag nopan rounded p-1 text-blue-600" aria-label={data.expanded ? "Collapse child traces" : "Expand child traces"}>{data.expanded ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}</button>}
    <button type="button" onClick={data.onSelect} className="nodrag nopan flex h-full min-w-0 flex-1 flex-col justify-center gap-1 px-2 text-left" title={`${data.label}\n${data.summary}${data.context ? `\n${data.context}` : ""}`}>
      <span className="flex w-full min-w-0 items-center gap-1.5">
        <StatusGlyph status={data.status} />
        <EventIcon aria-hidden="true" className="size-3.5 shrink-0 text-zinc-600 dark:text-zinc-300" />
        <span className="shrink-0 rounded bg-blue-50 px-1 py-0.5 text-[9px] font-semibold text-blue-800 dark:bg-blue-950 dark:text-blue-200">{stageLabel(data.kind)}</span>
        <span className="min-w-0 flex-1 truncate text-[11px] font-semibold text-zinc-800 dark:text-zinc-100">{data.label}</span>
        {data.durationMs !== undefined && <span className="shrink-0 font-mono text-[9px] text-zinc-500">{data.durationMs < 1000 ? `${Math.round(data.durationMs)}ms` : `${(data.durationMs / 1000).toFixed(1)}s`}</span>}
      </span>
      <span className="block w-full truncate font-mono text-[10px] leading-4 text-zinc-600 dark:text-zinc-300">{data.highlights[0]}</span>
      <span className="block w-full truncate font-mono text-[10px] leading-4 text-zinc-500 dark:text-zinc-400">{data.highlights[1]}</span>

    </button>
  </div>;
}
export const missionNodeTypes = { sector: SectorNode, trace: TraceNode };
