import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";

export type TraceStatus = SparrowTrace["status"];
export type Sector = {
  id: string;
  title: string;
  traces: SparrowTrace[];
  status: TraceStatus;
};

const rank: Record<TraceStatus, number> = {
  failed: 4,
  running: 3,
  paused: 2,
  succeeded: 1,
};

export function sectorsFromTraces(traces: SparrowTrace[]): Sector[] {
  const groups = new Map<string, Sector>();
  for (const trace of traces) {
    const key = trace.sectorId || "other";
    const group = groups.get(key) ?? {
      id: key,
      title: trace.sectorName || key,
      traces: [],
      status: "succeeded" as TraceStatus,
    };
    group.traces.push(trace);
    if (rank[trace.status] > rank[group.status]) group.status = trace.status;
    groups.set(key, group);
  }
  return [...groups.values()];
}
