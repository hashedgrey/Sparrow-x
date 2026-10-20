import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";

/** View model built only from emitted sparrowx.trace events; no inferred model I/O. */
export type WalkStep = {
  id: string;
  label: string;
  at: number;
  anchor: SparrowTrace;
  traces: SparrowTrace[];
};

export type EntitySummary = {
  type?: string;
  roots?: number;
  results?: number;
  names: string[];
  evidence?: number;
  searchSeen: boolean;
  /** Cumulative capability attempts, including attempts with no service result event. */
  attempts: number;
  failedAttempts: number;
  state: "not-started" | "failed" | "attempted" | "empty" | "found";
  failureDetail?: string;
};

export type SourceSummary = {
  /** Tool labels are derived from actual trace capabilities, never guessed providers. */
  tools: string[];
  attempts: number;
  failures: number;
  observedResults: number;
};

export const isFailureStatus = (raw?: string): boolean =>
  /^(FAILED|FAILED_TERMINAL|ERROR|BLOCKED|REJECTED)$/.test(String(raw || "").toUpperCase());


export const attrs = (t?: SparrowTrace): Record<string, string> =>
  (t?.attributes ?? {}) as Record<string, string>;

export function eventTime(t?: SparrowTrace): number {
  if (!t) return 0;
  const raw: unknown = t.startedAt;
  if (typeof raw === "number") return Number.isFinite(raw) ? raw : 0;
  if (typeof raw === "string") return Date.parse(raw) || 0;
  return 0;
}

function lastUpdate(t: SparrowTrace): number {
  const raw: unknown = t.endedAt;
  if (typeof raw === "number") return raw;
  if (typeof raw === "string") return Date.parse(raw) || eventTime(t);
  return eventTime(t);
}

function statusRank(t: SparrowTrace): number {
  switch (String(t.status ?? "").toUpperCase()) {
    case "RUNNING": return 0;
    default: return 1;
  }
}

export function normalizedTraces(traces: SparrowTrace[]): SparrowTrace[] {
  const byId = new Map<string, SparrowTrace>();
  for (const trace of traces) {
    if (!trace?.id) continue;
    const previous = byId.get(trace.id);
    if (!previous || statusRank(trace) > statusRank(previous) ||
      (statusRank(trace) === statusRank(previous) && lastUpdate(trace) >= lastUpdate(previous))) {
      byId.set(trace.id, trace);
    }
  }
  return [...byId.values()].sort((a, b) => eventTime(a) - eventTime(b) || a.id.localeCompare(b.id));
}

const ANCHORS = new Set([
  "INTENT_SNAPSHOT", "REASON_LOOP", "CAPABILITY_ATTEMPT", "RESPONSE_SYNTHESIS", "MISSION_FINISH",
]);

function stepLabel(trace: SparrowTrace): string {
  const a = attrs(trace);
  switch (trace.kind) {
    case "INTENT_SNAPSHOT": return "Start";
    case "REASON_LOOP": return `Review ${a.reviewIteration || a.planIteration || ""}`.trim();
    case "CAPABILITY_ATTEMPT": {
      if (a.capability === "internal.entities.search") return "Find entities";
      if (a.capability === "internal.company-graph.read") return "Query graph";
      if (a.capability === "document.evidence.build") return "Get documents";
      return a.capability || "Use capability";
    }
    case "RESPONSE_SYNTHESIS": return "Synthesize";
    case "MISSION_FINISH": return "Complete";
    default: return trace.title || "Mission";
  }
}

/**
 * Anchors represent actual mission phases, not arbitrary sectors or "hops".
 * A plan belongs to the NEXT action it proposes; normal events belong to the
 * latest started phase. All raw trace IDs remain available in that phase.
 */
export function buildWalkthrough(raw: SparrowTrace[]): WalkStep[] {
  const events = normalizedTraces(raw);
  if (!events.length) return [];
  const anchorEvents = events.filter(t => ANCHORS.has(t.kind || ""));
  if (!anchorEvents.length) anchorEvents.push(events[0]!);
  const steps: WalkStep[] = anchorEvents.map(t => ({
    id: t.id, label: stepLabel(t), at: eventTime(t), anchor: t, traces: [],
  }));
  for (const event of events) {
    let index = 0;
    if (event.kind === "EMBABEL_PLAN_FORMULATED" || event.kind === "EMBABEL_PLANNING_READY") {
      // A plan is formulated before the selected action executes.
      const next = steps.findIndex(s => s.at >= eventTime(event));
      index = next < 0 ? steps.length - 1 : next;
    } else {
      for (let i = 1; i < steps.length; ++i) {
        if (steps[i]!.at <= eventTime(event)) index = i;
        else break;
      }
    }
    steps[index]!.traces.push(event);
  }
  return steps;
}

export function firstKind(events: SparrowTrace[], kind: string): SparrowTrace | undefined {
  return events.find(t => t.kind === kind);
}

export function lastKind(events: SparrowTrace[], kind: string): SparrowTrace | undefined {
  return events.findLast(t => t.kind === kind);
}

export function shortAction(name: string): string {
  return name.trim().split(".").at(-1) || name;
}

/** Stable UI labels for known Embabel @Action methods (not model-generated prose). */
const EMBABEL_ACTION_LABELS: Record<string, string> = {
  start: "Initialize",
  reason: "Review",
  searchEntities: "Search entities",
  queryGraph: "Query graph",
  retrieveDocuments: "Get documents",
  complete: "Finalize",
  interpret: "Interpret request",
  plan: "Plan mission",
  collectEvidence: "Collect evidence",
};

export type EmbabelPlanView = {
  preview: string;
  actions: { id: string; label: string }[];
  total: number;
  truncated: boolean;
  rawPlan?: string;
};

/**
 * Compact IDs take precedence over the old, potentially long qualified-name payload.
 * Never collapse repeated actions: repeated review/replanning is real execution history.
 * Plans containing more than the backend's emission limit explicitly report truncation.
 */
export function embabelPlanView(attributes: Record<string, string>): EmbabelPlanView {
  const compact = attributes.planActionIds;
  const raw = attributes.plannedActions;
  const ids = compact ? compact.split(" > ").map(s => s.trim()).filter(Boolean) :
    raw ? raw.split(" -> ").map(s => shortAction(s)).filter(Boolean) : [];
  const actions = ids.slice(0, 20).map(id => ({
    id,
    label: EMBABEL_ACTION_LABELS[id] || id.replace(/([a-z])([A-Z])/g, "$1 $2"),
  }));
  const parsedTotal = Number(attributes.planActionCount);
  const total = attributes.planActionCount && Number.isSafeInteger(parsedTotal) && parsedTotal >= actions.length
    ? parsedTotal : actions.length;
  const truncated = attributes.planTruncated === "true" || total > actions.length;
  const previewItems = actions.slice(0, 4).map(a => a.label);
  const hidden = total - previewItems.length;
  const preview = previewItems.length
    ? previewItems.join(" → ") + (hidden > 0 ? ` → +${hidden} more` : "")
    : "No Embabel plan recorded for this step";
  return { preview, actions, total, truncated, rawPlan: raw };
}

function numberField(value?: string): number | undefined {
  if (value == null || value.trim() === "") return undefined;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : undefined;
}

function parseNames(raw?: string): string[] {
  if (!raw || /^\d+$/.test(raw.trim())) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.filter((n): n is string => typeof n === "string" && !!n.trim()).slice(0, 6);
  } catch { /* Comma- or semicolon-separated legacy metadata. */ }
  return raw.split(/[;,|]/).map(s => s.trim()).filter(s => s && !/^\d+$/.test(s)).slice(0, 6);
}

/** Only verified counts/names from SSE are presented as results.
 * A CAPABILITY_ATTEMPT is an attempt, never proof of successful retrieval.
 * Evidence snapshots are absolute; subsequent deltas augment that snapshot.
 */
export function entitySummary(events: SparrowTrace[]): EntitySummary {
  let type: string | undefined;
  let roots: number | undefined;
  let results: number | undefined;
  let verifiedRoots: number | undefined;
  let evidence: number | undefined;
  let searchSeen = false;
  let attempts = 0;
  let failedAttempts = 0;
  let failureDetail: string | undefined;
  const names = new Set<string>();

  for (const e of events) {
    const a = attrs(e);
    if (e.kind === "CAPABILITY_ATTEMPT" && a.capability === "internal.entities.search") {
      searchSeen = true;
      attempts++;
      if (isFailureStatus(e.status)) {
        failedAttempts++;
        failureDetail = a.error || a.failureReason || a.errorMessage || e.message || undefined;
      }
    }
    if (e.kind === "INTSVC_SEARCH") {
      searchSeen = true;
      type = a.rootType || type;
    }
    if (e.kind === "GRAPH_QUERY_BOUND" || e.kind === "GRAPH_QUERY_RESULT" || e.kind === "INTSVC_GRAPH") {
      type = a.rootType || type;
      roots = numberField(a.rootCount) ?? roots;
    }
    if (e.kind === "GRAPH_QUERY_RESULT") {
      results = numberField(a.resultCount) ?? results;
      verifiedRoots = numberField(a.rootCount) ?? verifiedRoots;
    }
    if (e.kind === "MISSION_EVIDENCE_SNAPSHOT") evidence = numberField(a.evidenceCount) ?? evidence;
    if (e.kind === "MISSION_EVIDENCE_DELTA") {
      const added = numberField(a.addedEvidence);
      if (added !== undefined) evidence = (evidence ?? 0) + added;
    }
    for (const key of ["entityNames", "resolvedEntities", "entities"]) {
      parseNames(a[key]).forEach(n => names.add(n));
    }
  }

  const found = names.size > 0 || (results ?? 0) > 0 || (verifiedRoots ?? 0) > 0;
  const state: EntitySummary["state"] = found ? "found" :
    results === 0 ? "empty" :
      attempts > 0 && failedAttempts === attempts ? "failed" :
        searchSeen || type ? "attempted" : "not-started";

  return {
    type, roots, results, evidence, searchSeen, attempts, failedAttempts,
    failureDetail, state, names: [...names].slice(0, 6),
  };
}

/** Cumulative tool usage. "Observed result" means a result event was emitted;
 * it does not imply that the tool returned a nonempty result set.
 */
export function sourceSummary(events: SparrowTrace[]): SourceSummary {
  const tools = new Set<string>();
  let attempts = 0;
  let failures = 0;
  let observedResults = 0;
  const labels: Record<string, string> = {
    "internal.entities.search": "Entity search",
    "internal.company-graph.read": "Company graph",
    "document.evidence.build": "Document evidence",
  };

  for (const trace of events) {
    const a = attrs(trace);
    if (trace.kind === "CAPABILITY_ATTEMPT") {
      attempts++;
      if (isFailureStatus(trace.status)) failures++;
      if (a.capability) tools.add(labels[a.capability] || shortAction(a.capability));
    }
    if (trace.kind === "GRAPH_QUERY_RESULT" || trace.kind === "INTSVC_SEARCH" ||
      trace.kind === "INTSVC_GRAPH" || trace.kind === "MISSION_EVIDENCE_DELTA") {
      observedResults++;
    }
  }
  return { tools: [...tools], attempts, failures, observedResults };
}

/** Short, factual per-event summaries; no "reasoning" or prompts are invented. */
export function eventDetails(t: SparrowTrace): string {
  const a = attrs(t);
  switch (t.kind) {
    case "JEV_VOTE": return `${a.field || "vote"}: ${a.choice || a.vote || "—"} · ${a.confidence || "?"}/${a.threshold || "?"} · ${a.decision || ""}`;
    case "JAVA_VALIDATION": return `${a.goalStatus || "—"} · next ${shortAction(a.nextAction || "NONE")} · ${a.validation || ""}`;
    case "GOAL_EVALUATED": return `${a.goalStatus || "—"} · next ${shortAction(a.nextAction || "NONE")}`;
    case "GRAPH_QUERY_BOUND": return `${a.rootType || "?"} · ${a.traversalCount || "0"} traversal · ${a.projectionCount || "0"} projection`;
    case "GRAPH_QUERY_RESULT": return `${a.rootCount || "?"} root · ${a.resultCount || "?"} result · ${a.complete === "true" ? "complete" : "partial"}`;
    case "INTSVC_SEARCH": return `${a.rootType || "Entity"} · internal search`;
    case "LLM_RESULT": return `${a.model || a.provider || "LLM"} · ${a.outcome || "received"}`;
    case "LLM_PROPOSAL": return `${a.goalStatus || ""}${a.nextAction ? ` · ${shortAction(a.nextAction)}` : ""}`;
    case "LLM_GENERATION": return `${a.stage || "structured output"} · ${a.fieldCount || "?"} fields`;
    case "MISSION_EVIDENCE_DELTA": return `${shortAction(a.capability || "capability")} · +${a.addedEvidence || "0"} evidence`;
    case "EMBABEL_STATE_TRANSITION": return `${a.stateFrom || "none"} → ${a.stateTo || "unknown"}`;
    case "EMBABEL_GOAL_ACHIEVED": return a.goal || "Goal achieved";
    case "ROOT_RESOLUTION": return a.service || "Root resolution";
    case "GRAPH_COMPILE": return `${a.rootCount || "?"} root(s)`;
    default: return a.capability || a.goalStatus || a.goal || t.message || "";
  }
}
