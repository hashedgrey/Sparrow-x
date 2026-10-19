import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import type { TraceStatus } from "./types";

/** Presentation groups are NOT synthetic agent actions or inferred GOAP plans. */
export type StageIcon = "intent" | "decision" | "execution" | "synthesis" | "other";
export type TraceAssociation = "anchor" | "parent" | "sequence";
export type TraceStage = {
  id: string;
  title: string;
  subtitle: string;
  icon: StageIcon;
  traces: SparrowTrace[];
  status: TraceStatus;
  order: number;
  associations: Record<string, TraceAssociation>;
};
export const TRACE_PAGE_SIZE = 10;

const SEVERITY: Record<TraceStatus, number> = { succeeded: 0, paused: 1, running: 2, failed: 3 };
const intentKind = (t: SparrowTrace) => t.kind === "INTENT_SNAPSHOT";
const reviewKind = (t: SparrowTrace) => t.kind === "REASON_LOOP";
const actionKind = (t: SparrowTrace) => t.kind === "CAPABILITY_ATTEMPT";
const finishKind = (t: SparrowTrace) => {
  const sector = (t.sectorId || "").toLowerCase();
  const kind = (t.kind || "").toUpperCase();
  return sector.includes("synth") || sector.includes("answer") || kind.startsWith("SYNTHES") || kind === "RESPONSE_GENERATION";
};

/**
 * Chronological decision/execution spine. Every source trace is included exactly
 * once, and original IDs/parent IDs are retained. Explicit ancestry takes
 * precedence over fallback association by first-seen stream order.
 *
 * The adapter updates spans in place, so the array index represents their
 * initial arrival order. Avoid sorting by end time: a long running operation
 * can finish after several later operations and scramble the timeline.
 */
export function stagesFromTraces(traces: SparrowTrace[]): TraceStage[] {
  if (!traces.length) return [];
  const byId = new Map(traces.map((trace) => [trace.id, trace]));
  type Anchor = { id: string; index: number; icon: StageIcon; title: string; subtitle: string; traceId?: string };
  const anchors: Anchor[] = [];
  const anchorForId = new Map<string, string>();
  let decisions = 0;
  let executions = 0;
  let intentAnchor: Anchor | undefined;
  let completionAnchor: Anchor | undefined;

  // Real typed action spans are the normal boundaries. Progress-only fallbacks
  // are used only on streams lacking those action-span types entirely.
  const typedReviews = traces.some(reviewKind);
  const typedActions = traces.some(actionKind);
  const hasSynthesis = traces.some(finishKind);
  const hasIntent = traces.some(intentKind);

  for (let index = 0; index < traces.length; index++) {
    const trace = traces[index]!;
    let anchor: Anchor | undefined;
    if (intentKind(trace)) {
      if (!intentAnchor) {
        intentAnchor = { id: "intent", index, icon: "intent", title: "Intent & routing", subtitle: "Mission classification and constraints", traceId: trace.id };
        anchors.push(intentAnchor);
      }
      anchorForId.set(trace.id, intentAnchor.id);
      continue;
    }
    if (reviewKind(trace)) {
      decisions++;
      anchor = { id: `decision:${trace.id}`, index, icon: "decision", title: `Decision ${decisions}`,
        subtitle: `Loop ${trace.attributes?.planIteration || decisions} · goal review`, traceId: trace.id };
    } else if (actionKind(trace)) {
      executions++;
      const capability = trace.attributes?.capability || trace.title.replace(/^Execute\s+/i, "") || "capability";
      const attempt = trace.attributes?.attempt ? ` · attempt ${trace.attributes.attempt}` : "";
      anchor = { id: `execution:${trace.id}`, index, icon: "execution", title: `Execution ${executions}`,
        subtitle: `${capability}${attempt}`, traceId: trace.id };
    } else if (finishKind(trace)) {
      if (!completionAnchor) {
        completionAnchor = { id: "completion", index, icon: "synthesis", title: "Completion & synthesis",
          subtitle: "Grounded answer and citations", traceId: trace.id };
        anchors.push(completionAnchor);
      }
      anchorForId.set(trace.id, completionAnchor.id);
      continue;
    } else if (!typedReviews && isReviewMilestone(trace)) {
      decisions++;
      anchor = { id: `decision:${trace.id}`, index, icon: "decision", title: `Decision ${decisions}`,
        subtitle: "Observed reason/review action", traceId: trace.id };
    } else if (!typedActions && isExecutionMilestone(trace)) {
      executions++;
      anchor = { id: `execution:${trace.id}`, index, icon: "execution", title: `Execution ${executions}`,
        subtitle: trace.title, traceId: trace.id };
    }
    if (anchor) {
      anchors.push(anchor);
      anchorForId.set(trace.id, anchor.id);
    }
  }

  const groups = new Map<string, TraceStage>();
  const addGroup = (anchor: Anchor): TraceStage => {
    let group = groups.get(anchor.id);
    if (!group) {
      group = { id: anchor.id, title: anchor.title, subtitle: anchor.subtitle, icon: anchor.icon,
        traces: [], order: anchor.index, status: "succeeded", associations: {} };
      groups.set(anchor.id, group);
    }
    return group;
  };
  for (const anchor of anchors) addGroup(anchor);

  const findOwnerByParent = (trace: SparrowTrace): string | undefined => {
    let cursor: SparrowTrace | undefined = trace;
    const seen = new Set<string>();
    while (cursor && !seen.has(cursor.id)) {
      seen.add(cursor.id);
      const owner = anchorForId.get(cursor.id);
      if (owner) return owner;
      cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
    }
    return undefined;
  };

  const closestPrecedingAnchor = (index: number): Anchor | undefined => {
    let found: Anchor | undefined;
    for (const anchor of anchors) {
      if (anchor.index <= index && (!found || anchor.index > found.index)) found = anchor;
    }
    return found;
  };
  const closestUpcoming = (index: number, icon: StageIcon): Anchor | undefined =>
    anchors.find((anchor) => anchor.index > index && anchor.icon === icon);

  for (let index = 0; index < traces.length; index++) {
    const trace = traces[index]!;
    const ancestor = findOwnerByParent(trace);
    let owner = ancestor;
    let association: TraceAssociation = ancestor === anchorForId.get(trace.id) ? "anchor" : "parent";
    if (!owner && intentKind(trace)) owner = "intent";
    if (!owner && finishKind(trace)) owner = "completion";

    // Embabel milestone start events often arrive one event before the typed
    // reason/action span itself. Associate the real milestone with that span.
    // Never create a new execution or alter its original parentId.
    if (!owner && typedReviews && isReviewMilestone(trace)) {
      const next = closestUpcoming(index, "decision");
      const preceding = closestPrecedingAnchor(index);
      if (next && (!preceding || preceding.icon !== "decision")) owner = next.id;
    }
    if (!owner && typedActions && isExecutionMilestone(trace)) {
      const next = closestUpcoming(index, "execution");
      const preceding = closestPrecedingAnchor(index);
      if (next && (!preceding || preceding.icon !== "execution")) owner = next.id;
    }
    if (!owner && !hasIntent && !closestPrecedingAnchor(index)) {
      owner = "prelude";
    }
    if (!owner) owner = closestPrecedingAnchor(index)?.id;
    if (!owner) owner = intentAnchor?.id || anchors[0]?.id || "prelude";
    if (!ancestor) association = "sequence";

    const stage = groups.get(owner) ?? addGroup({
      id: owner, index, icon: "other", title: "Pre-mission events", subtitle: "Observed before the first decision/execution",
    });
    stage.order = Math.min(stage.order, index);
    // Reassign presentation sector only, so trace-tree retains parent links
    // within the decision/execution group. The backend trace is never mutated.
    stage.traces.push({ ...trace, sectorId: stage.id, sectorName: stage.title });
    stage.associations[trace.id] = association;
    if (SEVERITY[trace.status] > SEVERITY[stage.status]) stage.status = trace.status;
  }

  // Group timeline order follows its own real anchor, not a child span that
  // started earlier (e.g. the Embabel action-start milestone). The groups array
  // itself is never rebuilt in a fixed intent/decision/execution ordering.
  const anchorIndex = new Map(anchors.map((anchor) => [anchor.id, anchor.index]));
  return [...groups.values()].sort((a, b) =>
    (anchorIndex.get(a.id) ?? a.order) - (anchorIndex.get(b.id) ?? b.order) || a.id.localeCompare(b.id));
}

function isReviewMilestone(trace: SparrowTrace): boolean {
  if (trace.kind) return false;
  return /(?:^|[.\s_-])reason(?:state)?(?:[.\s_-]|$)|goal.review/i.test(trace.title);
}
function isExecutionMilestone(trace: SparrowTrace): boolean {
  if (trace.kind) return false;
  return /searchEntities|queryGraph|retrieveDocuments|collectEvidence/i.test(trace.title);
}

export function traceSummary(trace: SparrowTrace): string {
  const a = trace.attributes ?? {};
  const kind = trace.kind ?? "";
  const pick = (...keys: string[]) => keys.map((key) => a[key] !== undefined ? `${key}: ${a[key]}` : "").filter(Boolean);
  let facts: string[];
  if (kind === "JEV_VOTE") {
    facts = pick("field", "choice", "vote");
    if (a.confidence !== undefined) facts.push(`confidence ${a.confidence}${a.threshold ? ` / ${a.threshold}` : ""}`);
    if (a.decision) facts.push(a.decision);
  }
  else if (kind.startsWith("JEV")) facts = pick("field", "decision", "reasonCode", "provider");
  else if (kind.startsWith("LLM")) facts = pick("reasonCode", "route", "provider", "model", "goalStatus", "nextAction");
  else if (kind === "REASON_LOOP") facts = pick("goal", "planIteration");
  else if (kind === "GOAP_RECOMMENDATION") facts = pick("goalStatus", "nextAction", "alternativeCount");
  else if (kind === "JAVA_VALIDATION") facts = pick("validation", "goalStatus", "nextAction", "missingCount");
  else if (kind === "BLACKBOARD_SNAPSHOT") facts = pick("observationCount", "evidenceStateCount", "evidenceCount", "addedEvidence", "attemptCount");
  else facts = pick("capability", "attempt", "selectionMode", "rootCount", "resultCount", "rowCount", "resolutionStatus", "model", "rpcStatus");
  if (!facts.length) facts = Object.entries(a).slice(0, 3).map(([k, v]) => `${k}: ${v}`);
  return facts.slice(0, kind === "JEV_VOTE" ? 4 : 3).join(" · ") || trace.message || trace.source || "Observed operation";
}

export function visibleTracePage<T>(items: readonly T[], index: number, perPage = TRACE_PAGE_SIZE): {
  items: T[]; page: number; pages: number; start: number; end: number;
} {
  const size = Math.max(1, Math.floor(perPage));
  const pages = Math.max(1, Math.ceil(items.length / size));
  const page = Math.max(0, Math.min(pages - 1, Math.floor(index) || 0));
  const start = page * size;
  const end = Math.min(items.length, start + size);
  return { items: items.slice(start, end), page, pages, start, end };
}

/** Two compact, structured lines displayed inside each mini-node. Details shows all fields. */
export function traceHighlights(trace: SparrowTrace): [string, string] {
  const a = trace.attributes ?? {};
  const kind = trace.kind ?? "";
  const items = (...keys: string[]) => keys.filter((k) => a[k] !== undefined).map((k) => `${k}=${a[k]}`).join(" · ");
  switch (kind) {
    case "JEV_VOTE": return [items("field", "choice", "vote") || "Jev bounded choice",
      [a.confidence !== undefined ? `confidence=${a.confidence}${a.threshold ? ` / ${a.threshold}` : ""}` : "", a.decision].filter(Boolean).join(" · ") || "Decision pending"];
    case "BLACKBOARD_SNAPSHOT": return [items("observationCount", "evidenceStateCount", "evidenceCount") || "Evidence ledger",
      items("addedObservations", "addedEvidence", "attemptCount") || "Snapshot recorded"];
    case "JAVA_VALIDATION": return [items("validation", "goalStatus") || "Java evidence gate",
      items("nextAction", "missingCount", "alternativeCount") || "Validation recorded"];
    case "GOAP_RECOMMENDATION": return [items("goalStatus", "nextAction") || "Action recommended",
      items("goal", "alternativeCount") || "GOAP recommendation"];
    case "REASON_LOOP": return [items("goal") || "Goal review", items("planIteration") || "New reason iteration"];
    case "CAPABILITY_ATTEMPT": return [items("capability", "attempt") || "Capability execution", items("outcome", "rpcStatus") || "Action underway"];
    case "INTENT_SNAPSHOT": return [items("plannerActual", "plannerRequested") || "Mission intent", items("requiresCompanyGraph", "requiresDocumentEvidence") || "Intent frozen"];
    default: {
      if (kind.startsWith("LLM")) return [items("reasonCode", "route", "provider") || "Model invoked", items("model", "goalStatus", "nextAction") || "LLM operation"];
      if (kind.startsWith("JEV")) return [items("field", "decision", "reasonCode") || "Jev assessment", items("provider", "threshold", "confidence") || "Bounded decision"];
      return [items("capability", "attempt", "selectionMode", "rootCount") || traceSummary(trace), items("resultCount", "rowCount", "resolutionStatus", "rpcStatus") || trace.source || "Observed operation"];
    }
  }
}
