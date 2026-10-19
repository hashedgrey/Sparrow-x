"use client";

import { useMemo, useState, type ChangeEvent, type ReactNode } from "react";
import {
  Activity, ArrowLeft, ArrowRight, BookOpenText, BrainCircuit,
  Check, CheckCircle2, ChevronRight, Clipboard, Code2, Database,
  GitBranch, Layers3, Search, ShieldAlert, Sparkles, Terminal,
  Workflow,
} from "lucide-react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import type { TraceStage } from "./stage-layout";

/** Mission Trace is deliberately a projection of observed AG-UI spans.
 * It does not manufacture prompts, evidence, identities, or unseen GOAP actions.
 */
type Props = {
  stages: TraceStage[];
  traces: SparrowTrace[];
  metadata?: SparrowMetadata;
  selectedStageId: string | null;
  onSelectStage: (id: string) => void;
  onSelectTrace: (id: string) => void;
};

const pane = "min-w-0 rounded-xl border border-slate-700/70 bg-[#111d2f] shadow-[0_1px_0_rgba(255,255,255,.04)]";
const muted = "text-slate-400";
const tone: Record<SparrowTrace["status"], string> = {
  running: "text-sky-300 border-sky-700/60 bg-sky-500/10",
  succeeded: "text-emerald-300 border-emerald-800 bg-emerald-500/10",
  failed: "text-rose-300 border-rose-800 bg-rose-500/10",
  paused: "text-amber-300 border-amber-800 bg-amber-500/10",
};

function codeObject(a: Record<string, string>): string {
  return Object.keys(a).length ? JSON.stringify(a, null, 2) : "No structured parameters in this event.";
}
function last<T>(values: T[]): T | undefined { return values[values.length - 1]; }
function isJev(t: SparrowTrace) { return (t.kind ?? "").toUpperCase().startsWith("JEV"); }
function isLlm(t: SparrowTrace) { return (t.kind ?? "").toUpperCase().startsWith("LLM"); }
function firstDefined(a: Record<string, string>, ...keys: string[]): string | undefined {
  return keys.map((key) => a[key]).find((value) => typeof value === "string" && value.trim().length > 0);
}
function parseItems(value?: string): string[] {
  if (!value) return [];
  try {
    const json: unknown = JSON.parse(value);
    if (Array.isArray(json)) return json.map((item) => typeof item === "string" ? item : JSON.stringify(item));
  } catch { /* Plain text field is also valid. */ }
  return value.trim() ? [value] : [];
}
function formatTime(value?: number): string {
  return value !== undefined && Number.isFinite(value)
    ? new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    : "—";
}
function InfoPanel({ title, icon, children, className = "" }: {
  title: string; icon: ReactNode; children: ReactNode; className?: string;
}) {
  return <section className={`${pane} p-3.5 ${className}`}>
    <h3 className="mb-3 flex items-center gap-2 text-[12px] font-semibold text-slate-100">
      <span className="text-sky-400">{icon}</span>{title}
    </h3>
    {children}
  </section>;
}
function Empty({ children }: { children: ReactNode }) {
  return <p className="text-[11px] leading-5 text-slate-500">{children}</p>;
}
function DataPanel({ title, model, value, note, icon }: {
  title: string; model?: SparrowTrace; value?: string; note: string; icon: ReactNode;
}) {
  const [copied, setCopied] = useState(false);
  const content = value ?? note;
  return <section className={`${pane} flex min-h-[256px] min-w-0 flex-col overflow-hidden`}>
    <header className="flex items-start justify-between gap-2 border-b border-slate-700/70 px-3 py-3">
      <div className="flex min-w-0 items-center gap-2">
        <span className="shrink-0 text-violet-400">{icon}</span>
        <span className="min-w-0">
          <span className="block text-xs font-semibold text-slate-100">{title}</span>
          <span className="block truncate text-[10px] text-slate-400">{model ? `${model.kind ?? model.title} · ${formatTime(model.startedAt)}` : "Not observed yet"}</span>
        </span>
      </div>
      <button type="button" onClick={() => {
        if (typeof navigator !== "undefined" && navigator.clipboard) {
          void navigator.clipboard.writeText(content).then(() => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1400);
          }).catch(() => undefined);
        }
      }} aria-label={`Copy ${title}`} title={`Copy ${title}`}
        className="flex shrink-0 items-center gap-1 rounded border border-slate-600 px-1.5 py-1 text-[10px] text-slate-300 hover:bg-slate-700">
        {copied ? <Check className="size-3" /> : <Clipboard className="size-3" />}
        {copied ? "Copied" : "Copy"}
      </button>
    </header>
    <pre tabIndex={0} className="max-h-[310px] min-h-[190px] flex-1 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-[1.6] text-slate-300">{content}</pre>
    <footer className="border-t border-slate-700/60 px-3 py-1.5 text-[10px] text-slate-500">{value ? "Observed captured content" : "Not captured in the current trace"}</footer>
  </section>;
}

function ModelPanels({ preceding }: { preceding: SparrowTrace[] }) {
  const jev = last(preceding.filter(isJev));
  const llm = last(preceding.filter(isLlm));
  const ja = jev?.attributes ?? {};
  const la = llm?.attributes ?? {};
  const jevInput = firstDefined(ja, "jevInput", "inputText", "requestText", "input", "prompt");
  const jevResponse = firstDefined(ja, "jevResponse", "responseText", "response", "output") ??
    (jev && Object.keys(ja).length ? codeObject(ja) : undefined);
  const llmInput = firstDefined(la, "llmInput", "inputText", "messages", "prompt", "input");
  const llmResponse = firstDefined(la, "llmResponse", "responseText", "response", "output", "completion") ??
    (llm && Object.keys(la).length ? codeObject(la) : undefined);
  return <section aria-label="JEV and LLM input/output as of selected step" className="grid gap-2 lg:grid-cols-2 2xl:grid-cols-4">
    <DataPanel title="JEV Input" icon={<BrainCircuit className="size-4" />} model={jev} value={jevInput}
      note={jev ? "Exact JEV request text was not emitted by the backend. The JEV event's decision parameters are in the response panel." : "No JEV event has been observed at this step."} />
    <DataPanel title="JEV Response" icon={<Code2 className="size-4" />} model={jev} value={jevResponse}
      note="No JEV response fields have been captured. A JEV event can contain structured choices without a raw text response." />
    <DataPanel title="LLM Input" icon={<Terminal className="size-4" />} model={llm} value={llmInput}
      note={llm ? "Exact system/user prompt messages were not emitted by the backend. Prompt capture must be authorized and redacted server-side." : "No LLM call has been observed at this step."} />
    <DataPanel title="LLM Response" icon={<Sparkles className="size-4" />} model={llm} value={llmResponse}
      note="No LLM response fields have been captured. The trace may expose routing, model and proposal metadata only." />
  </section>;
}

export function SparrowMissionTraceView({ stages, traces, metadata, selectedStageId, onSelectStage, onSelectTrace }: Props) {
  const stageIndex = Math.max(0, stages.findIndex((stage) => stage.id === selectedStageId));
  const currentStage = stages[stageIndex];
  // Group membership is based on observed parent/sequence association. Show the
  // snapshot as of the last event in the selected group, never future state.
  const preceding = useMemo(() => {
    if (!currentStage) return [];
    const ids = new Set(currentStage.traces.map((trace) => trace.id));
    const lastIndex = traces.reduce((index, trace, i) => ids.has(trace.id) ? Math.max(index, i) : index, -1);
    return traces.slice(0, lastIndex + 1);
  }, [currentStage, traces]);
  const snapshots = preceding.filter((trace) => trace.kind === "BLACKBOARD_SNAPSHOT");
  const board = last(snapshots);
  const beforeBoard = snapshots.length > 1 ? snapshots[snapshots.length - 2] : undefined;
  const a = board?.attributes ?? {};
  const before = beforeBoard?.attributes ?? {};
  const root = last(preceding.filter((trace) =>
    trace.attributes?.selectionMode !== undefined || trace.attributes?.rootCount !== undefined));
  const rootAttributes = root?.attributes ?? {};
  const roots = parseItems(firstDefined(a, "entities", "resolvedEntities", "entityNames"));
  const evidence = parseItems(firstDefined(a, "evidence", "evidenceRefs", "evidenceItems"));
  const gaps = parseItems(firstDefined(a, "pendingGaps", "missingRequirements", "gaps"));
  const observations = parseItems(firstDefined(a, "observations", "observationSummaries"));
  const hopSpan = last(preceding.filter((trace) =>
    trace.attributes?.hopCount !== undefined || trace.attributes?.hops !== undefined));
  const hopCount = hopSpan?.attributes?.hopCount ?? hopSpan?.attributes?.hops;
  const actionSpans = preceding.filter((trace) => trace.kind === "CAPABILITY_ATTEMPT");
  const currentActions = currentStage?.traces.filter((trace) => trace.kind === "CAPABILITY_ATTEMPT") ?? [];
  const stageStatus = currentStage?.status ?? "running";

  function go(index: number) {
    const target = stages[Math.min(stages.length - 1, Math.max(0, index))];
    if (target) onSelectStage(target.id);
  }
  const selectedCount = currentStage ? stageIndex + 1 : 0;
  return <div className="bg-[#0a1423] p-3 text-slate-100 sm:p-4" aria-label="Mission trace and blackboard timeline">
    <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2">
        <Workflow className="size-5 text-indigo-400" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-slate-100">Mission Trace</h2>
          <p className="truncate text-[10px] text-slate-400">Run {metadata?.missionId ?? "not assigned"} · {metadata?.missionPath ?? "planner not reported"}</p>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <span className={`rounded-full border px-2.5 py-1 text-[10px] font-semibold ${tone[stageStatus]}`}>{metadata?.missionStatus ?? stageStatus}</span>
        <span className="rounded-lg border border-slate-700 px-2 py-1 text-xs">Step <strong>{selectedCount}</strong> / {stages.length}</span>
      </div>
    </header>
    <div className="grid gap-3 xl:grid-cols-[220px_minmax(0,1fr)]">
      <aside className="grid content-start gap-2">
        <InfoPanel title="Hops" icon={<GitBranch className="size-4" />}>
          <p className="font-mono text-3xl font-semibold text-white">{hopCount ?? "—"}</p>
          <p className={`mt-0.5 text-[10px] ${muted}`}>{hopCount ? "Reported graph/mission hop count" : "Hop count not emitted"}</p>
          <div className="mt-3 space-y-1 border-l border-slate-700 pl-3">
            {stages.slice(Math.max(0, stageIndex - 3), stageIndex + 1).map((stage) => <button type="button" key={stage.id}
              onClick={() => onSelectStage(stage.id)} className={`flex w-full items-center gap-2 py-1 text-left text-[11px] ${stage.id === currentStage?.id ? "font-semibold text-indigo-300" : "text-slate-400 hover:text-white"}`}>
              <span className={`size-1.5 rounded-full ${stage.id === currentStage?.id ? "bg-indigo-400" : "bg-emerald-500"}`} />{stage.title}
            </button>)}
          </div>
        </InfoPanel>
        <InfoPanel title={`Actions · ${actionSpans.length}`} icon={<Activity className="size-4" />}>
          <div className="max-h-44 space-y-1 overflow-auto">
            {actionSpans.length ? actionSpans.map((trace) => <button key={trace.id} type="button" onClick={() => onSelectTrace(trace.id)}
              className="flex w-full items-center gap-2 rounded-md px-1.5 py-1 text-left text-[11px] hover:bg-slate-700/60">
              <span className={`size-2 shrink-0 rounded-full ${trace.status === "failed" ? "bg-rose-500" : trace.status === "running" ? "bg-sky-500" : "bg-emerald-500"}`} />
              <span className="truncate" title={trace.attributes?.capability || trace.title}>{trace.attributes?.capability || trace.title}</span>
              {currentActions.some((candidate) => candidate.id === trace.id) && <ChevronRight className="ml-auto size-3 text-indigo-300" />}
            </button>) : <Empty>No typed capability actions observed yet.</Empty>}
          </div>
        </InfoPanel>
        <InfoPanel title="Step History" icon={<Layers3 className="size-4" />}>
          <div className="max-h-60 space-y-1 overflow-y-auto">
            {stages.map((stage, i) => <button key={stage.id} type="button" onClick={() => onSelectStage(stage.id)}
              className={`flex w-full items-center gap-1.5 rounded-md p-1 text-left text-[10px] ${i === stageIndex ? "bg-indigo-500/20 text-indigo-200" : "text-slate-400 hover:bg-slate-700/50"}`}>
              {i <= stageIndex ? <CheckCircle2 className="size-3 shrink-0 text-emerald-400" /> : <span className="size-3 shrink-0 rounded-full border border-slate-600" />}
              <span className="w-7 shrink-0">{i + 1}</span><span className="min-w-0 flex-1 truncate" title={stage.subtitle}>{stage.title}</span>
            </button>)}
            {!stages.length && <Empty>Waiting for mission trace events.</Empty>}
          </div>
        </InfoPanel>
      </aside>
      <main className="min-w-0 space-y-3">
        <section className={`${pane} p-3`} aria-label="Blackboard snapshot at selected step">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2"><Database className="size-5 text-sky-400" /><h3 className="text-sm font-semibold">Blackboard</h3><span className="text-[10px] text-slate-400">Last observed snapshot as of this step</span></div>
            <button type="button" onClick={() => {
              const content = JSON.stringify({ sourceTrace: board?.id ?? null, attributes: a, root: rootAttributes }, null, 2);
              if (navigator.clipboard) void navigator.clipboard.writeText(content).catch(() => undefined);
            }} className="flex items-center gap-1 rounded border border-slate-600 px-2 py-1 text-[10px] text-slate-300 hover:bg-slate-700"><Code2 className="size-3" /> Copy snapshot JSON</button>
          </div>
          <div className="grid gap-2 lg:grid-cols-3">
            <InfoPanel title="Entities" icon={<Search className="size-4" />}>
              {roots.length ? <ul className="space-y-1 text-[11px]">{roots.map((item, i) => <li key={i} className="break-words rounded-md bg-sky-500/10 px-2 py-1.5 text-sky-200">{item}</li>)}</ul> : <Empty>Entity identities are not present in captured snapshots.</Empty>}
              {a.resolvedEntityId && <p className="mt-2 break-all font-mono text-[10px] text-sky-200">{a.resolvedEntityId}</p>}
            </InfoPanel>
            <InfoPanel title="Evidence" icon={<BookOpenText className="size-4" />}>
              <div className="mb-2 grid grid-cols-2 gap-2">
                {(["evidenceCount", "evidenceStateCount"] as const).map((key) => <div key={key} className={`rounded-lg border p-2 ${a[key] !== before[key] && board ? "border-emerald-700 bg-emerald-950/30" : "border-slate-700"}`}>
                  <p className="text-[9px] text-slate-400">{key}</p><p className="font-mono text-lg font-semibold">{a[key] ?? "—"}</p></div>)}
              </div>
              {evidence.length ? <ul className="space-y-1 text-[10px] text-slate-300">{evidence.map((item, i) => <li key={i} className="break-words">{item}</li>)}</ul> : <Empty>Evidence content/IDs were not included in the snapshot.</Empty>}
            </InfoPanel>
            <InfoPanel title="Root Selection Mode" icon={<GitBranch className="size-4" />}>
              <p className="inline-flex rounded-lg border border-violet-700 bg-violet-950/30 px-2 py-1 font-mono text-[11px] text-violet-200">{rootAttributes.selectionMode ?? "Not reported"}</p>
              <dl className="mt-3 space-y-2 text-[10px]">
                <div className="flex justify-between gap-2"><dt className="text-slate-400">Root count</dt><dd>{rootAttributes.rootCount ?? "—"}</dd></div>
                <div className="flex justify-between gap-2"><dt className="text-slate-400">Status</dt><dd>{rootAttributes.resolutionStatus ?? root?.status ?? "—"}</dd></div>
                <div className="flex justify-between gap-2"><dt className="text-slate-400">Source</dt><dd className="truncate">{root?.source ?? "—"}</dd></div>
              </dl>
            </InfoPanel>
          </div>
          <div className="mt-2 grid gap-2 lg:grid-cols-2">
            <InfoPanel title="Pending Gaps" icon={<ShieldAlert className="size-4" />}>
              {gaps.length ? <ul className="space-y-1 text-[11px]">{gaps.map((item, i) => <li key={i} className="rounded-md border border-amber-700/50 bg-amber-500/10 p-2 text-amber-100">{item}</li>)}</ul>
                : <Empty>Gap details were not emitted at this step.</Empty>}
            </InfoPanel>
            <InfoPanel title="Observations" icon={<Activity className="size-4" />}>
              <div className="mb-2 flex items-center gap-2 text-[10px] text-slate-400">Recorded: <strong className={`text-slate-100 ${a.observationCount !== before.observationCount ? "text-emerald-300" : ""}`}>{a.observationCount ?? "—"}</strong><span>· Added: {a.addedObservations ?? "—"}</span></div>
              {observations.length ? <ul className="list-inside list-disc space-y-1 break-words text-[11px] text-slate-300">{observations.map((item, i) => <li key={i}>{item}</li>)}</ul>
                : <Empty>Observation text not captured. Only aggregate counters may be present.</Empty>}
            </InfoPanel>
          </div>
          {!board && <p className="mt-2 text-[10px] text-amber-300">No BLACKBOARD_SNAPSHOT has arrived by this step. This view does not reconstruct unseen state.</p>}
        </section>
        <ModelPanels preceding={preceding} />
        <section className={`${pane} p-3`} aria-label="Current step parameters">
          <div className="mb-2 flex items-center justify-between"><h3 className="text-xs font-semibold">{currentStage?.title ?? "Waiting for events"}</h3><span className="text-[10px] text-slate-400">{currentStage?.traces.length ?? 0} observed events</span></div>
          <p className="mb-2 text-[11px] text-slate-400">{currentStage?.subtitle ?? "Start a mission to populate the trace."}</p>
          <div className="flex flex-wrap gap-1.5">
            {currentStage?.traces.map((t) => <button type="button" key={t.id} onClick={() => onSelectTrace(t.id)} title={t.message || t.title}
              className={`rounded-md border px-2 py-1 text-[10px] ${tone[t.status]} hover:brightness-125`}>{t.kind ?? t.title}</button>)}
          </div>
        </section>
      </main>
    </div>
    <nav aria-label="Mission trace step slider" className={`${pane} mt-3 p-3`}>
      <div className="flex items-center gap-2">
        <button type="button" disabled={stageIndex === 0 || !stages.length} onClick={() => go(stageIndex - 1)}
          className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-600 px-2.5 py-2 text-[11px] disabled:opacity-40 hover:enabled:bg-slate-700"><ArrowLeft className="size-3.5" /> Previous</button>
        <div className="min-w-0 flex-1">
          <input aria-label="Select mission step" type="range" min={1} max={Math.max(1, stages.length)} step={1} value={selectedCount || 1}
            disabled={!stages.length} onChange={(event: ChangeEvent<HTMLInputElement>) => go(Number(event.target.value) - 1)}
            className="h-1.5 w-full cursor-pointer accent-indigo-500 disabled:opacity-50" />
          <div className="mt-1.5 flex items-center justify-between gap-2 text-[10px] text-slate-400">
            <span className="max-w-[50%] truncate">{currentStage?.title ?? "—"}</span>
            <span className="max-w-[50%] truncate text-right">{selectedCount}/{stages.length} observed groups</span>
          </div>
        </div>
        <button type="button" disabled={!stages.length || stageIndex >= stages.length - 1} onClick={() => go(stageIndex + 1)}
          className="inline-flex shrink-0 items-center gap-1 rounded-lg bg-indigo-600 px-3 py-2 text-[11px] font-semibold text-white disabled:opacity-40 hover:enabled:bg-indigo-500">Next <ArrowRight className="size-3.5" /></button>
      </div>
      <p className="mt-2 text-center text-[10px] text-slate-500">The selected step updates the blackboard, latest captured JEV/LLM activity, actions, and graph selection. Empty panels mean the backend did not emit that data.</p>
    </nav>
  </div>;
}
