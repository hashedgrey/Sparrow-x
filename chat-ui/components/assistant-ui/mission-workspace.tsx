"use client";

import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
  type FC, type ReactNode,
} from "react";
import { Activity, Clock3, Route } from "lucide-react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";

/** The workspace header gets factual run data from the mission cards mounted in messages. */
type MissionHeaderSnapshot = {
  metadata?: SparrowMetadata;
  missionId: string;
  planner: string;
  status: string;
  totalMs?: number;
  failedSteps: number;
  eventCount: number;
  startedAt: number;
  traces: SparrowTrace[];
};

type MissionWorkspaceContextValue = {
  mission: MissionHeaderSnapshot | null;
  publish: (snapshot: MissionHeaderSnapshot) => void;
  reset: () => void;
};
const MissionWorkspaceContext = createContext<MissionWorkspaceContextValue | null>(null);

export const MissionWorkspaceProvider: FC<{ children?: ReactNode }> = ({ children }) => {
  const [mission, setMission] = useState<MissionHeaderSnapshot | null>(null);
  const publish = useCallback((next: MissionHeaderSnapshot) => {
    setMission(prev => {
      // Multiple prior message dashboards can be mounted simultaneously. The newest run wins.
      if (prev && prev.missionId !== next.missionId && prev.startedAt > next.startedAt) return prev;
      if (prev && prev.missionId === next.missionId && prev.totalMs === next.totalMs &&
          prev.eventCount === next.eventCount && prev.status === next.status &&
          prev.planner === next.planner && prev.failedSteps === next.failedSteps &&
          prev.traces === next.traces && prev.metadata === next.metadata) return prev;
      return next;
    });
  }, []);
  const reset = useCallback(() => setMission(null), []);
  const value = useMemo(() => ({ mission, publish, reset }), [mission, publish, reset]);
  return <MissionWorkspaceContext.Provider value={value}>{children}</MissionWorkspaceContext.Provider>;
};

export function useMissionWorkspace(): { mission: MissionHeaderSnapshot | null } {
  const context = useContext(MissionWorkspaceContext);
  return { mission: context?.mission ?? null };
}

export function usePublishMissionSummary(summary: MissionHeaderSnapshot | null): void {
  const context = useContext(MissionWorkspaceContext);
  const publish = context?.publish;
  useEffect(() => { if (summary) publish?.(summary); }, [publish, summary]);
}

/** Keep the empty-thread dashboard free of stale stats from a previous thread. */
export const ResetMissionHeader: FC = () => {
  const context = useContext(MissionWorkspaceContext);
  const reset = context?.reset;
  useEffect(() => { reset?.(); }, [reset]);
  return null;
};

// Do not manufacture token counts: absent metrics render as em-dash, while an actual 0 stays 0.
function numberAt(raw: unknown, ...paths: string[]): number | undefined {
  for (const path of paths) {
    let node: unknown = raw;
    for (const key of path.split(".")) {
      if (node === null || typeof node !== "object" || !(key in node)) { node = undefined; break; }
      node = (node as Record<string, unknown>)[key];
    }
    if (typeof node === "number" && Number.isFinite(node) && node >= 0) return node;
    if (typeof node === "string" && node.trim() && Number.isFinite(Number(node)) && Number(node) >= 0) return Number(node);
  }
  return undefined;
}
function metricSeconds(ms?: number): string {
  return ms === undefined ? "—" : `${(ms / 1000).toFixed(2)}s`;
}
function count(n?: number): string { return n === undefined ? "—" : n.toLocaleString(); }

export const MissionWorkspaceHeader: FC<{ composer: ReactNode }> = ({ composer }) => {
  const context = useContext(MissionWorkspaceContext);
  const mission = context?.mission;
  const [metricsOpen, setMetricsOpen] = useState(false);
  const metricRows = useMemo(() => {
    const raw = mission?.metadata;
    const first = numberAt(raw, "timing.firstResponseMs", "timing.timeToFirstTokenMs", "timing.ttftMs", "metrics.firstResponseMs");
    const total = mission?.totalMs;
    const speed = numberAt(raw, "timing.tokensPerSecond", "metrics.tokensPerSecond", "usage.tokensPerSecond");
    const input = numberAt(raw, "tokens.input", "usage.inputTokens", "usage.promptTokens", "metrics.inputTokens", "tokenUsage.inputTokens");
    const cached = numberAt(raw, "tokens.cachedInput", "usage.cachedInputTokens", "usage.promptCachedTokens", "metrics.cachedInputTokens");
    const output = numberAt(raw, "tokens.output", "usage.outputTokens", "usage.completionTokens", "metrics.outputTokens", "tokenUsage.outputTokens");
    const all = numberAt(raw, "tokens.total", "usage.totalTokens", "metrics.totalTokens", "tokenUsage.totalTokens") ??
      (input !== undefined && output !== undefined ? input + output : undefined);
    return [
      ["First response", metricSeconds(first)],
      ["Total", metricSeconds(total)],
      ["Speed", speed === undefined ? "—" : `${speed.toFixed(1)} tok/s`],
      ["Events", mission ? count(mission.eventCount) : "—"],
      ["Input", count(input)],
      ["Cached input", count(cached)],
      ["Output", count(output)],
      ["Total tokens", count(all)],
    ] as const;
  }, [mission]);
  const displayStatus = mission?.status || "Ready";
  return (
    <header className="sp-workspace-toolbar" aria-label="Mission workspace toolbar">
      <div className="sp-workspace-identity">
        <span className="sp-workspace-logo" aria-hidden="true"><Route size={19} /></span>
        <div className="sp-workspace-identity-text">
          <div className="sp-workspace-title-line">
            <strong>Mission walkthrough</strong>
            {mission?.planner && mission.planner !== "—" && <span className="sp-workspace-planner">{mission.planner}</span>}
          </div>
          <span className="sp-workspace-mission-id" title={mission?.missionId || ""}>
            {mission?.missionId || "No mission started"}
          </span>
        </div>
      </div>
      <div className="sp-workspace-composer" aria-label="Ask a question or start another mission">
        {composer}
      </div>
      <div className="sp-workspace-right">
        <div className="sp-metrics-wrap" onMouseLeave={() => setMetricsOpen(false)}>
          <button type="button" className="sp-metrics-trigger" aria-label="Execution metrics" aria-expanded={metricsOpen}
            aria-haspopup="true" onClick={() => setMetricsOpen(x => !x)}>
            <Clock3 size={16} aria-hidden="true" />
            <span>{metricSeconds(mission?.totalMs)}</span>
          </button>
          <div className={`sp-metrics-popover ${metricsOpen ? "sp-metrics-visible" : ""}`} role="group" aria-label="Execution metrics breakdown">
            <div className="sp-metrics-popover-heading"><Activity size={14} /> Execution metrics</div>
            {metricRows.map(([label, value]) => <div className="sp-metrics-row" key={label}>
              <span>{label}</span><strong>{value}</strong>
            </div>)}
          </div>
        </div>
        <span className={`sp-workspace-state ${/FAIL|BLOCK/i.test(displayStatus) ? "sp-workspace-state-fail" : ""}`}>
          {displayStatus.toLowerCase() === "completed" ? "Done" : displayStatus}
        </span>
      </div>
    </header>
  );
};

export type { MissionHeaderSnapshot };
