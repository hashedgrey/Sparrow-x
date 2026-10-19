import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";

/** Observed mission actions, not inferred Embabel GOAP goals. */
export type GoalPhaseKind = "intent" | "goal" | "review" | "action" | "synthesis" | "outcome" | "finish" | "unlinked";
export type GoalPhase = {
  id: string;
  kind: GoalPhaseKind;
  trace?: SparrowTrace; // undefined only for the presentation-only unlinked bucket
  children: SparrowTrace[];
};

const PHASE_KINDS: Readonly<Record<string, GoalPhaseKind>> = {
  INTENT_SNAPSHOT: "intent",
  GOAL_REGISTERED: "goal",
  REASON_LOOP: "review",
  CAPABILITY_ATTEMPT: "action",
  RESPONSE_SYNTHESIS: "synthesis",
  GOAL_SATISFIED: "outcome",
  GOAL_BLOCKED: "outcome",
  MISSION_FINISH: "finish",
};
export const GOAL_EVENTS = new Set(["GOAL_REGISTERED", "GOAL_EVALUATED", "GOAL_SATISFIED", "GOAL_BLOCKED"]);

/** Preserve first observed order. Updates to the same span are not new calls. */
export function normalizeTraces(traces: readonly SparrowTrace[]): SparrowTrace[] {
  const byId = new Map<string, SparrowTrace>();
  for (const trace of traces) {
    if (!trace?.id) continue;
    const previous = byId.get(trace.id);
    if (!previous) {
      byId.set(trace.id, trace);
    } else {
      byId.set(trace.id, {
        ...previous, ...trace,
        startedAt: previous.startedAt ?? trace.startedAt,
        endedAt: trace.endedAt ?? previous.endedAt,
        attributes: { ...previous.attributes, ...trace.attributes },
      });
    }
  }
  return [...byId.values()];
}

/**
 * A call belongs to its closest explicitly linked phase ancestor. Missing parent
 * IDs are NOT repaired using timestamp or textual-title guesses; such calls go
 * to an explicit 'unlinked' group until backend correlation is available.
 */
export function makeGoalPhases(input: readonly SparrowTrace[]): GoalPhase[] {
  const traces = normalizeTraces(input);
  const byId = new Map(traces.map(t => [t.id, t]));
  const phases: GoalPhase[] = [];
  const byPhaseId = new Map<string, GoalPhase>();
  for (const trace of traces) {
    const kind = PHASE_KINDS[trace.kind ?? ""];
    if (!kind) continue;
    const phase: GoalPhase = { id: trace.id, kind, trace, children: [] };
    phases.push(phase);
    byPhaseId.set(trace.id, phase);
  }
  const unlinked: SparrowTrace[] = [];
  for (const trace of traces) {
    if (byPhaseId.has(trace.id)) continue;
    const visited = new Set([trace.id]);
    let parentId = trace.parentId;
    let owner: GoalPhase | undefined;
    while (parentId && !visited.has(parentId)) {
      visited.add(parentId);
      owner = byPhaseId.get(parentId);
      if (owner) break;
      parentId = byId.get(parentId)?.parentId;
    }
    if (owner) owner.children.push(trace);
    else unlinked.push(trace);
  }
  if (unlinked.length) phases.push({ id: "unlinked", kind: "unlinked", children: unlinked });
  return phases;
}

export function recentBoard(traces: readonly SparrowTrace[]): SparrowTrace | undefined {
  return [...traces].reverse().find(t => t.kind === "BLACKBOARD_SNAPSHOT");
}

export function mostRecentGoal(traces: readonly SparrowTrace[]): SparrowTrace | undefined {
  return [...traces].reverse().find(t => GOAL_EVENTS.has(t.kind ?? ""));
}

/** Observed snapshots are counts and deltas, NOT complete Embabel blackboard dumps. */
export function boardFields(board?: SparrowTrace): Array<[string, string]> {
  if (!board) return [];
  const keys = ["observationCount", "evidenceStateCount", "evidenceCount", "attemptCount", "addedObservations", "addedEvidence"];
  return keys.filter(k => board.attributes?.[k] !== undefined)
    .map(k => [k, board.attributes![k]!] as [string, string]);
}

/** Within a phase, real parent IDs are kept for drawing nested call edges. */
export function displayedCalls(phase: GoalPhase, page: number, perPage = 8) {
  const pages = Math.max(1, Math.ceil(phase.children.length / perPage));
  const current = Math.min(pages - 1, Math.max(0, page));
  return { items: phase.children.slice(current * perPage, (current + 1) * perPage), page: current, pages };
}
