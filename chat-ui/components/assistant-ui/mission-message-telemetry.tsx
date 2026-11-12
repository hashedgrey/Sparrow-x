"use client";

import { useMemo, type FC } from "react";
import { useSparrowMetadata } from "./sparrow-metadata";
import { usePublishMissionSummary, type MissionHeaderSnapshot } from "./mission-workspace";

/** Publish actual message metadata without rendering metrics or a graph in Chat. */
export const MissionMessageTelemetry: FC = () => {
  const metadata = useSparrowMetadata();
  const summary = useMemo<MissionHeaderSnapshot | null>(() => {
    if (!metadata?.missionId) return null;
    const traces = metadata.traces ?? [];
    const startTimes = traces.map(t => t.startedAt).filter(
      (v): v is number => typeof v === "number" && Number.isFinite(v),
    );
    const firstIntent = traces.find(t => t.kind === "INTENT_SNAPSHOT");
    const latestPlan = [...traces].reverse().find(t => t.kind === "EMBABEL_PLAN_FORMULATED");
    return {
      metadata,
      missionId: metadata.missionId,
      status: metadata.missionStatus || (metadata.complete ? "COMPLETED" : "RUNNING"),
      planner: latestPlan?.attributes?.plannerActual || firstIntent?.attributes?.plannerActual || "—",
      totalMs: metadata.timing?.totalMs,
      failedSteps: traces.filter(t => t.status === "failed").length,
      eventCount: traces.length,
      startedAt: startTimes.length ? Math.min(...startTimes) : 0,
      traces,
    };
  }, [metadata]);
  usePublishMissionSummary(summary);
  return null;
};
