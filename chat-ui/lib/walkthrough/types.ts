import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";

export type WalkthroughStage = {
  id: string; sort_order: number; name: string; lane: string; kind: string;
  summary: string; confirmed: number; evidence: string;
};
export type WalkthroughTransition = {
  id: number; from_stage: string; to_stage: string; condition: string;
  edge_kind: string; confirmed: number; rationale: string;
};
export type WalkthroughSource = {
  id: string; path: string; inspected: number; responsibility: string;
};
/** Explanation text curated from inspected execution boundaries. Not runtime evidence. */
export type WalkthroughNarrative = {
  file_id: string; receives: string; contribution: string;
  produces: string; enables: string; caution: string;
};
export type WalkthroughStageFile = {
  stage_id: string; file_id: string; relationship: string; evidence: string;
};
export type WalkthroughSpan = {
  id: number; stage_id: string; file_id: string; symbol: string;
  start_line: number; end_line: number | null; note: string;
};
export type WalkthroughRule = {
  id: string; priority: number; sse_event: string; predicate_json: string;
  stage_id: string; semantics: string; confirmed: number; source: string;
};
export type WalkthroughCall = {
  id: number; caller_file: string; callee_file: string;
  method_or_boundary: string; relation: string; confirmed: number; evidence: string;
};
export type WalkthroughGraph = {
  stages: WalkthroughStage[];
  transitions: WalkthroughTransition[];
  sourceFiles: WalkthroughSource[];
  stageFiles: WalkthroughStageFile[];
  sourceSpans: WalkthroughSpan[];
  runtimeRules: WalkthroughRule[];
  callEdges: WalkthroughCall[];
  narratives?: WalkthroughNarrative[];
};

export type WalkthroughSignal = {
  /** Monotonic within this one streamed mission; repeated actions remain distinct. */
  seq: number;
  event: string;
  stepId?: string;
  stageId?: string;
  kind?: string;
  traceId?: string;
  status?: string;
};
export type Observation = { stageId: string; signal: WalkthroughSignal; rule: WalkthroughRule };

/** Never infer a phase as executed just because a preceding phase completed. */
export function projectObservations(graph: WalkthroughGraph, metadata?: SparrowMetadata): Observation[] {
  if (!metadata) return [];
  const signals = metadata.walkthroughSignals ?? [];
  const seen = new Set<string>();
  const results: Observation[] = [];
  for (const signal of signals) {
    for (const rule of graph.runtimeRules) {
      if (!rule.confirmed || signal.event !== rule.sse_event) continue;
      // Usage emitted by unrelated LLM calls does NOT establish synthesis.
      // Only a synthesis-specific trace or answer text can do that.
      if (signal.event === "usage") continue;
      let predicate: Record<string, string>;
      try { predicate = JSON.parse(rule.predicate_json) as Record<string, string>; } catch { continue; }
      const match = Object.entries(predicate).every(([key, value]) => {
        if (key === "trace.kind") return signal.kind === value;
        if (key === "stepId") return signal.stepId === value;
        if (key === "stageId") return signal.stageId === value;
        return false; // Unknown predicates must not produce false positives.
      });
      if (!match) continue;
      // Most-specific rule wins for one event; repeated events are retained.
      const marker = `${signal.seq}:${rule.stage_id}`;
      if (!seen.has(marker)) {
        results.push({ stageId: rule.stage_id, signal, rule });
        seen.add(marker);
      }
      if (Object.keys(predicate).length) break;
    }
  }
  return results;
}

/** Live trace spans can be updated in place; keep their kind even if trace attributes change. */
export function liveTraceForStage(observations: Observation[], traces: SparrowTrace[], stageId: string): SparrowTrace | undefined {
  const ids = observations.filter(o => o.stageId === stageId && o.signal.traceId).map(o => o.signal.traceId!);
  return traces.findLast(trace => ids.includes(trace.id));
}
