"use client";

import { ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import { useMemo, type FC } from "react";
import { CloneThreadShell } from "./clone-thread-shell";
import { Composer } from "./composer";
import { ChatMessage } from "./message";
import { MissionTraceDashboard } from "./mission-graph/mission-trace-dashboard";
import { MissionWorkspaceHeader, MissionWorkspaceProvider, ResetMissionHeader, useMissionWorkspace } from "./mission-workspace";
import "./mission-workspace.css";

/**
 * Use the actual chat message renderer inside the Result step, rather than
 * converting responses to strings. This preserves the same message content,
 * attachments and assistant-ui actions (feedback, copy, regenerate) that the
 * original chat displays, for every message in the current thread.
 */
const ResultConversation: FC = () => (
  <ThreadPrimitive.Viewport
    turnAnchor="top"
    autoScroll={false}
    scrollToBottomOnRunStart={false}
    className="sp-result-chat-viewport"
    aria-label="Conversation history"
  >
    <ThreadPrimitive.Messages components={{ Message: ChatMessage }} />
  </ThreadPrimitive.Viewport>
);

const WorkspaceScreen: FC = () => {
  const messages = useAuiState(s => s.thread.messages);
  const { mission } = useMissionWorkspace();
  const lastAssistantIndex = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i]?.role === "assistant") return i;
    }
    return -1;
  }, [messages]);

  return <>
    <MissionWorkspaceHeader composer={<Composer />} />
    {messages.length === 0 && <ResetMissionHeader />}
    <main className="sp-workspace-view" aria-label="Mission workspace">
      <MissionTraceDashboard
        metadata={mission?.metadata}
        traces={mission?.traces ?? []}
        conversation={messages.length > 0 ? <ResultConversation /> : undefined}
        hasConversation={messages.length > 0}
        showEmpty
        publishSummary={false}
      />
    </main>
    {/* The latest ChatMessage stays mounted so existing trace/metadata emission
        continues publishing run state even when the Result tab isn't selected. */}
    {lastAssistantIndex >= 0 && (
      <ThreadPrimitive.Viewport
        autoScroll={false}
        className="sp-workspace-message-source"
        aria-hidden="true"
      >
        <ThreadPrimitive.MessageByIndex
          index={lastAssistantIndex}
          components={{ Message: ChatMessage }}
        />
      </ThreadPrimitive.Viewport>
    )}
  </>;
};

export const Arrow: FC = () => (
  <CloneThreadShell>
    <ThreadPrimitive.Root className="sp-mission-shell flex h-full min-h-0 flex-col overflow-hidden">
      <MissionWorkspaceProvider>
        <WorkspaceScreen />
      </MissionWorkspaceProvider>
    </ThreadPrimitive.Root>
  </CloneThreadShell>
);
