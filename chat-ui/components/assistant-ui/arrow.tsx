"use client";

import { ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import { useMemo, useState, type FC } from "react";
import { GitBranch, MessagesSquare } from "lucide-react";
import { CloneThreadShell } from "./clone-thread-shell";
import { Composer } from "./composer";
import { ChatMessage } from "./message";
import { MissionTraceDashboard } from "./mission-graph/mission-trace-dashboard";
import { ArchitectureWalkthrough } from "./mission-graph/architecture-walkthrough";
import { MissionWorkspaceHeader, MissionWorkspaceProvider, ResetMissionHeader, useMissionWorkspace } from "./mission-workspace";
import "./mission-workspace.css";

const ResultConversation: FC = () => (
  <ThreadPrimitive.Viewport turnAnchor="top" autoScroll={false} scrollToBottomOnRunStart={false}
    className="sp-result-chat-viewport" aria-label="Conversation history">
    <ThreadPrimitive.Messages components={{ Message: ChatMessage }}/>
  </ThreadPrimitive.Viewport>
);

const WorkspaceScreen: FC = () => {
  const messages = useAuiState(s => s.thread.messages);
  const { mission } = useMissionWorkspace();
  const [tab, setTab] = useState<"mission" | "walkthrough">("mission");
  const lastAssistantIndex = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) if (messages[i]?.role === "assistant") return i;
    return -1;
  }, [messages]);

  return <>
    <MissionWorkspaceHeader />
    {messages.length === 0 && <ResetMissionHeader />}
    <nav className="sp-workspace-tabs" role="tablist" aria-label="Mission workspace views">
      <button type="button" id="sp-tab-mission" role="tab" aria-controls="sp-panel-mission"
        className={tab === "mission" ? "sp-workspace-tab-active" : ""}
        aria-selected={tab === "mission"} onClick={() => setTab("mission")}>
        <MessagesSquare size={15}/> Mission
      </button>
      <button type="button" id="sp-tab-walkthrough" role="tab" aria-controls="sp-panel-walkthrough"
        className={tab === "walkthrough" ? "sp-workspace-tab-active" : ""}
        aria-selected={tab === "walkthrough"} onClick={() => setTab("walkthrough")}>
        <GitBranch size={15}/> Walkthrough
      </button>
    </nav>
    <main className="sp-workspace-view" aria-label="Mission workspace">
      {/* Keep both mounted: hiding one tab must not reset the ongoing assistant stream. */}
      <div id="sp-panel-mission" role="tabpanel" aria-labelledby="sp-tab-mission"
        className={`sp-workspace-tab-panel ${tab === "mission" ? "sp-workspace-tab-visible" : ""}`}>
        <MissionTraceDashboard metadata={mission?.metadata} traces={mission?.traces ?? []}
          conversation={messages.length > 0 ? <ResultConversation/> : undefined}
          composer={<Composer/>} hasConversation={messages.length > 0}
          showEmpty publishSummary={false}/>
      </div>
      <div id="sp-panel-walkthrough" role="tabpanel" aria-labelledby="sp-tab-walkthrough"
        className={`sp-workspace-tab-panel ${tab === "walkthrough" ? "sp-workspace-tab-visible" : ""}`}>
        {tab === "walkthrough" ? <ArchitectureWalkthrough metadata={mission?.metadata}/> : null}
      </div>
    </main>
    {/* Existing message renderer remains mounted to publish the latest mission snapshot. */}
    {lastAssistantIndex >= 0 && <ThreadPrimitive.Viewport autoScroll={false}
      className="sp-workspace-message-source" aria-hidden="true">
      <ThreadPrimitive.MessageByIndex index={lastAssistantIndex} components={{ Message: ChatMessage }}/>
    </ThreadPrimitive.Viewport>}
  </>;
};

export const Arrow: FC = () => (
  <CloneThreadShell>
    <ThreadPrimitive.Root className="sp-mission-shell flex h-full min-h-0 flex-col overflow-hidden">
      <MissionWorkspaceProvider><WorkspaceScreen/></MissionWorkspaceProvider>
    </ThreadPrimitive.Root>
  </CloneThreadShell>
);
