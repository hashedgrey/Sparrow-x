"use client";

import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
  type FC, type ReactNode,
} from "react";
import { Activity, Clock3, GitBranch, Route } from "lucide-react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";

export type WorkspaceMode = "studio" | "walkthrough";

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
      // Historical message dashboards may also publish. The latest run wins.
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

export const ResetMissionHeader: FC = () => {
  const context = useContext(MissionWorkspaceContext);
  const reset = context?.reset;
  useEffect(() => { reset?.(); }, [reset]);
  return null;
};

export const MissionWorkspaceHeader: FC<{
  mode: WorkspaceMode;
  onModeChange: (next: WorkspaceMode) => void;
  isRunning?: boolean;
  autoShowSteps: boolean;
  onAutoShowStepsChange: (checked: boolean) => void;
}> = ({ mode, onModeChange, isRunning = false, autoShowSteps, onAutoShowStepsChange }) => {
  const context = useContext(MissionWorkspaceContext);
  const mission = context?.mission;
  const displayStatus = isRunning ? "Running" : mission?.status || "Ready";
  // Successful transport completion is not equivalent to goal certification.
  const latestFinish = mission?.traces.findLast(trace => trace.kind === "MISSION_FINISH");
  const goalStatus = latestFinish?.attributes?.goalStatus;
  const uncertified = Boolean(goalStatus && goalStatus !== "SATISFIED");
  const displayDuration = mission?.totalMs != null && Number.isFinite(mission.totalMs)
    ? `${(mission.totalMs / 1000).toFixed(2)}s` : null;

  return (
    <header className="sp-workspace-toolbar" aria-label="Query Studio toolbar">
      <div className="sp-workspace-identity">
        <span className="sp-workspace-logo" aria-hidden="true"><Route size={19} /></span>
        <div className="sp-workspace-identity-text">
          <div className="sp-workspace-title-line">
            <strong>Query Studio</strong>
            {mission?.planner && mission.planner !== "—" &&
              <span className="sp-workspace-planner">{mission.planner}</span>}
          </div>
          <span className="sp-workspace-mission-id" title={mission?.missionId || ""}>
            {mission?.missionId || "No mission started"}
          </span>
        </div>
      </div>

      <nav className="sp-workspace-modes" aria-label="Workspace mode">
        <button type="button" className={mode === "studio" ? "sp-mode-active" : ""}
          aria-current={mode === "studio" ? "page" : undefined}
          onClick={() => onModeChange("studio")}
          aria-controls="sp-studio-panel">
          <Activity size={15} aria-hidden="true" /> Query Studio
        </button>
        <button type="button" className={mode === "walkthrough" ? "sp-mode-active" : ""}
          aria-current={mode === "walkthrough" ? "page" : undefined}
          onClick={() => onModeChange("walkthrough")}
          aria-controls="sp-walkthrough-panel">
          <GitBranch size={15} aria-hidden="true" /> Walkthrough
        </button>
      </nav>

      <div className="sp-workspace-right">
        <button
          type="button"
          role="switch"
          aria-checked={autoShowSteps}
          aria-label="Automatically switch to steps when streaming begins"
          title={autoShowSteps ? "Show steps when the first stream event arrives" : "Stay in Chat while events stream"}
          className={`sp-stream-switch ${autoShowSteps ? "sp-stream-switch-on" : ""}`}
          onClick={() => onAutoShowStepsChange(!autoShowSteps)}
        >
          <span className="sp-stream-switch-label">Auto-show steps</span>
          <span className="sp-stream-switch-track" aria-hidden="true"><span className="sp-stream-switch-thumb" /></span>
        </button>
        {displayDuration && <span className="sp-workspace-duration"><Clock3 size={15} aria-hidden="true" />{displayDuration}</span>}
        <span className={`sp-workspace-state ${/FAIL|BLOCK/i.test(displayStatus) ? "sp-workspace-state-fail" : uncertified ? "sp-workspace-state-uncertified" : ""}`}
          title={goalStatus ? `User goal certification: ${goalStatus}` : "Mission lifecycle status"}>
          {displayStatus.toLowerCase() === "completed" ? "Done" : displayStatus}
          {uncertified ? ` · Goal ${goalStatus}` : ""}
        </span>
      </div>
    </header>
  );
};

export type { MissionHeaderSnapshot };
