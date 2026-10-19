"use client";

import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import { traceSummary } from "./stage-layout";

type Props = { trace: SparrowTrace; all: SparrowTrace[]; onClose: () => void;
  group?: string; grouping?: "anchor" | "parent" | "sequence" };

const LABELS: Record<string, string> = {
  JEV_VOTE: "Jev bounded decision", JEV_ACCEPTED: "Jev accepted",
  JEV_UNRESOLVED: "Jev unresolved", JEV_UNAVAILABLE: "Jev unavailable",
  LLM_ESCALATION: "LLM escalation", LLM_CONTINUATION: "Generative continuation",
  LLM_GENERATION: "LLM generation", LLM_PROPOSAL: "Structured model proposal",
  JAVA_VALIDATION: "Java evidence gate", BLACKBOARD_SNAPSHOT: "Blackboard snapshot",
  REASON_LOOP: "Review loop", CAPABILITY_ATTEMPT: "Tool execution",
  INTENT_SNAPSHOT: "Frozen intent / planner mode",
  GOAP_RECOMMENDATION: "Recommended next action",
  GOAL_REGISTERED: "Registered mission goal", GOAL_EVALUATED: "Goal evaluation",
  GOAL_SATISFIED: "Goal satisfied", GOAL_BLOCKED: "Goal blocked",
  MISSION_FINISH: "Mission finished", BLACKBOARD_DELTA: "Evidence ledger change",
  GRAPH_QUERY_BOUND: "Validated graph query shape", GRAPH_QUERY_RESULT: "Graph query evidence",
  LLM_RESULT: "Structured LLM result",
};
const GROUPS = [
  { title: "Jev · decision parameters", keys: ["field", "vote", "choice", "confidence", "threshold", "decision", "provider"] },
  { title: "Model routing & escalation", keys: ["route", "model", "reasonCode", "escalation", "fieldCount", "escalatedCount"] },
  { title: "Goal & GOAP", keys: ["goal", "planIteration", "plannerRequested", "plannerActual", "plannedActions", "goalStatus", "nextAction", "alternativeCount", "validation", "missingCount", "replanning"] },
  { title: "Blackboard & evidence ledger", keys: ["observationCount", "evidenceStateCount", "evidenceCount", "addedObservations", "addedEvidence", "attemptCount"] },
  { title: "Execution & result", keys: ["capability", "attempt", "rootCount", "rowCount", "resultCount", "selectionMode", "resolutionStatus", "rpcStatus", "verification", "truncated", "complete", "traversalCount", "predicateCount", "projectionCount", "negationCount", "comparisonCount", "selectionLimit", "schemaVersion"] },
  { title: "Intent constraints", keys: ["requiresInternalContext", "requiresCompanyGraph", "requiresDocumentEvidence", "requiresVerification"] },
] as const;
const safeText = (s?: string) => s && s !== "unknown" ? s : "—";

function getLoop(trace: SparrowTrace, all: SparrowTrace[]): SparrowTrace | undefined {
  const byId = new Map(all.map((item) => [item.id, item]));
  const seen = new Set<string>();
  let current: SparrowTrace | undefined = trace;
  while (current && !seen.has(current.id)) {
    seen.add(current.id);
    if (current.kind === "REASON_LOOP") return current;
    current = current.parentId ? byId.get(current.parentId) : undefined;
  }
  return undefined;
}

function DecisionParameters({ attributes }: { attributes: Record<string, string> }) {
  const known: ReadonlySet<string> = new Set(GROUPS.flatMap((group) => [...group.keys]));
  const sections = [
    ...GROUPS.map((group) => ({
      title: group.title,
      pairs: group.keys.filter((key) => attributes[key] !== undefined).map((key) => [key, attributes[key]!] as const),
    })),
    { title: "Additional safe attributes", pairs: Object.entries(attributes).filter(([key]) => !known.has(key)) },
  ];
  return <div className="mt-3 grid gap-2 md:grid-cols-2">
    {sections.filter((section) => section.pairs.length > 0).map((section) =>
      <section key={section.title} className="min-w-0 rounded-lg border border-zinc-200 bg-white p-3 dark:border-zinc-700 dark:bg-zinc-900">
        <h4 className="mb-2 text-[11px] font-semibold text-zinc-700 dark:text-zinc-200">{section.title}</h4>
        <dl className="grid grid-cols-[minmax(100px,auto)_1fr] gap-x-3 gap-y-1.5 text-[11px]">
          {section.pairs.map(([key, value]) => <div key={key} className="contents">
            <dt className="text-zinc-500">{key}</dt><dd className="break-words font-mono text-zinc-700 dark:text-zinc-200">{value}</dd>
          </div>)}
        </dl>
      </section>)}
  </div>;
}

export function DecisionTraceDetails({ trace, all, onClose, group, grouping }: Props) {
  const attributes = trace.attributes ?? {};
  const loop = getLoop(trace, all);
  const related = loop && ["JAVA_VALIDATION", "LLM_PROPOSAL"].includes(trace.kind ?? "")
    ? all.find((item) => item.id !== trace.id
      && item.kind === (trace.kind === "LLM_PROPOSAL" ? "JAVA_VALIDATION" : "LLM_PROPOSAL")
      && getLoop(item, all)?.id === loop.id)
    : undefined;
  const confidence = Number(attributes.confidence);
  const threshold = Number(attributes.threshold);
  const hasConfidence = attributes.confidence !== undefined && Number.isFinite(confidence);
  const hasThreshold = attributes.threshold !== undefined && Number.isFinite(threshold);
  const score = hasConfidence ? Math.max(0, Math.min(100, confidence * 100)) : 0;

  return <aside aria-label="Selected trace parameters" className="max-h-[390px] overflow-y-auto border-t border-zinc-200 px-4 py-3 text-xs text-zinc-700 dark:border-zinc-700 dark:text-zinc-200" aria-live="polite">
    <div className="flex items-start justify-between gap-2">
      <div className="min-w-0">
        <h3 className="text-sm font-semibold">{LABELS[trace.kind ?? ""] ?? trace.title}</h3>
        <p className="mt-0.5 break-words text-[11px] text-zinc-500">{trace.title} · {trace.status}{trace.durationMs !== undefined ? ` · ${Math.round(trace.durationMs)}ms` : ""}</p>
        <p className="mt-1 break-words font-mono text-[11px]">{traceSummary(trace)}</p>
        {trace.message && <p className="mt-1 text-[11px] text-zinc-500">{trace.message}</p>}
        {group && <p className="mt-1 text-[11px] text-zinc-500">Execution timeline: {group}</p>}
        {loop && <p className="mt-1 text-[11px] text-zinc-500">Review context: {loop.title} · {loop.attributes?.goal ?? "Goal not supplied"}</p>}
      </div>
      <button type="button" onClick={onClose} className="rounded border border-zinc-200 px-2 py-1 text-[11px] hover:bg-zinc-200 dark:border-zinc-700 dark:hover:bg-zinc-700" aria-label="Close trace details">Close</button>
    </div>
    {hasConfidence && <section aria-label="Jev confidence compared with threshold" className="mt-3 max-w-lg">
      <div className="mb-1 flex justify-between text-[11px]"><span>Confidence {Math.round(score)}%</span><span>Threshold {hasThreshold ? `${Math.round(threshold * 100)}%` : "—"}</span></div>
      <div className="relative h-1.5 overflow-hidden rounded bg-zinc-200 dark:bg-zinc-700">
        <div className="h-full rounded bg-blue-600" style={{ width: `${score}%` }} />
        {hasThreshold && <div className="absolute inset-y-0 w-0.5 bg-red-500" style={{ left: `${Math.max(0, Math.min(100, threshold * 100))}%` }} />}
      </div>
    </section>}
    <DecisionParameters attributes={attributes} />
    {related && <p className="mt-3 rounded-lg border border-zinc-200 p-2 text-[11px] text-zinc-600 dark:border-zinc-700 dark:text-zinc-300">
      Related {related.kind === "LLM_PROPOSAL" ? "LLM proposal" : "Java validation"}: {safeText(related.attributes?.goalStatus)} / {safeText(related.attributes?.nextAction)}
    </p>}
    <div className="mt-3 border-t border-zinc-200 pt-2 text-[10px] text-zinc-500 dark:border-zinc-700">
      <p>Trace ID: <span className="break-all font-mono">{trace.id}</span></p>
      {trace.parentId && <p>Parent: <span className="break-all font-mono">{trace.parentId}</span></p>}
      {trace.source && <p>Source: {trace.source}</p>}
      {grouping === "sequence" && <p className="mt-1">Grouped by observed stream order, not a verified parent span. Exact causal association requires backend parentId propagation.</p>}
      <p className="mt-1">Only structured, allowlisted parameters are displayed; model scratchpads and raw evidence payloads are excluded.</p>
    </div>
  </aside>;
}
