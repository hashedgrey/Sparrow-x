"use client";

import { ComposerPrimitive, MessagePrimitive, ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import { useCallback, useEffect, useMemo, useRef, useState, type FC } from "react";
import { CloneThreadShell } from "./clone-thread-shell";
import { Composer } from "./composer";
import { ChatMessage } from "./message";
import { MissionTraceDashboard } from "./mission-graph/mission-trace-dashboard";
import { ArchitectureWalkthrough } from "./mission-graph/architecture-walkthrough";
import {
  MissionWorkspaceHeader,
  MissionWorkspaceProvider,
  ResetMissionHeader,
  useMissionWorkspace,
  type MissionHeaderSnapshot,
  type WorkspaceMode,
} from "./mission-workspace";
import "./mission-workspace.css";

/** The existing user-message pencil enters assistant-ui's scoped edit composer.
 * Saving replaces the selected message and regenerates its subsequent answer.
 */
const ChatMessageEditor: FC = () => (
  <MessagePrimitive.Root className="mx-auto mb-7 flex w-full max-w-3xl flex-col px-4">
    <ComposerPrimitive.Root className="ml-auto flex w-full max-w-[85%] flex-col gap-3 rounded-2xl border border-blue-200 bg-white p-4 shadow-sm">
      <ComposerPrimitive.Input
        autoFocus
        rows={3}
        aria-label="Edit your message"
        className="min-h-20 w-full resize-y bg-transparent text-sm leading-6 text-slate-800 outline-none"
      />
      <div className="flex items-center justify-end gap-2">
        <ComposerPrimitive.Cancel className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50">
          Cancel
        </ComposerPrimitive.Cancel>
        <ComposerPrimitive.Send className="rounded-lg bg-blue-700 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-800 disabled:opacity-40">
          Save &amp; rerun
        </ComposerPrimitive.Send>
      </div>
    </ComposerPrimitive.Root>
  </MessagePrimitive.Root>
);

/** Leave the existing ChatMessage renderer unchanged for all other messages. */
const EditableChatMessage: FC = () => {
  const editing = useAuiState(s => s.message.role === "user" && s.message.composer.isEditing);
  return editing ? <ChatMessageEditor /> : <ChatMessage />;
};

/** Keep the assistant-ui conversation mounted while watching execution steps. */
const ChatConversation: FC = () => (
  <ThreadPrimitive.Viewport
    turnAnchor="top"
    autoScroll
    scrollToBottomOnRunStart
    className="sp-chat-viewport"
    aria-label="Conversation history"
  >
    <ThreadPrimitive.Messages components={{ Message: EditableChatMessage }} />
  </ThreadPrimitive.Viewport>
);

type RunPhase = "awaiting" | "streaming" | "finishing" | "finished" | "no-stream";
type RunWatch = {
  userKey: string;
  baselineMissionId: string | undefined;
  baselineTraces: MissionHeaderSnapshot["traces"];
  explicitlyNavigated: boolean;
  phase: RunPhase;
};

/** An appended user turn or a placeholder assistant message is not an SSE event. */
function streamedAnswerText(message: unknown): boolean {
  if (message === null || typeof message !== "object") return false;
  const m = message as { role?: string; content?: unknown };
  if (m.role !== "assistant" || !Array.isArray(m.content)) return false;
  return m.content.some(part => {
    if (part === null || typeof part !== "object") return false;
    const p = part as { type?: string; text?: unknown };
    return p.type === "text" && typeof p.text === "string" && p.text.length > 0;
  });
}

/** Compare actual events, not React object references or a transport-start flag. */
function receivedNewTrace(snapshot: MissionHeaderSnapshot | null, run: RunWatch): boolean {
  if (!snapshot || snapshot.traces.length === 0) return false;
  if (snapshot.missionId !== run.baselineMissionId) return true;
  if (snapshot.traces.length !== run.baselineTraces.length) return snapshot.traces.length > run.baselineTraces.length;
  return snapshot.traces.some((trace, i) => {
    const prior = run.baselineTraces[i];
    return !prior || trace.id !== prior.id || trace.status !== prior.status ||
      trace.kind !== prior.kind || trace.message !== prior.message ||
      trace.startedAt !== prior.startedAt || trace.endedAt !== prior.endedAt ||
      trace.durationMs !== prior.durationMs;
  });
}

const WorkspaceScreen: FC = () => {
  const messages = useAuiState(s => s.thread.messages);
  const isRunning = useAuiState(s => s.thread.isRunning);
  const { mission } = useMissionWorkspace();
  const [mode, setMode] = useState<WorkspaceMode>("studio");
  const [studioView, setStudioView] = useState<"chat" | "metrics">("chat");
  const [autoShowSteps, setAutoShowSteps] = useState(true);
  const [chatReady, setChatReady] = useState(false);
  const [awaitingStream, setAwaitingStream] = useState(false);

  const run = useRef<RunWatch | null>(null);
  const previousRunning = useRef(isRunning);
  const latestMission = useRef(mission);
  latestMission.current = mission;

  const lastUserIndex = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i]?.role === "user") return i;
    }
    return -1;
  }, [messages]);
  const lastUser = lastUserIndex >= 0 ? messages[lastUserIndex] : undefined;
  const userKey = lastUser ? String(lastUser.id || `user-${lastUserIndex}`) : null;
  const previousUserKey = useRef(userKey);
  const assistantTextReceived = useMemo(
    () => messages.slice(lastUserIndex + 1).some(streamedAnswerText),
    [messages, lastUserIndex],
  );

  // isRunning means the request was submitted. Keep Chat and its waiting dots
  // visible; actual streamed trace/text determines when the view transitions.
  useEffect(() => {
    const started = isRunning && !previousRunning.current;
    const newUser = userKey !== previousUserKey.current;
    if (userKey && isRunning && (started || newUser)) {
      const before = latestMission.current;
      run.current = {
        userKey,
        baselineMissionId: before?.missionId,
        baselineTraces: before?.traces.map(t => ({ ...t })) ?? [],
        explicitlyNavigated: false,
        phase: "awaiting",
      };
      setMode("studio");
      setStudioView("chat");
      setChatReady(false);
      setAwaitingStream(true);
    }
    previousRunning.current = isRunning;
    previousUserKey.current = userKey;
  }, [isRunning, userKey]);

  // A real SSE progress/trace update (or nonempty assistant token) begins the
  // stream. The optional auto-switch happens exactly once per run.
  useEffect(() => {
    const current = run.current;
    if (!current || current.userKey !== userKey) return;
    let switchedToSteps = false;
    if (current.phase === "awaiting") {
      if (receivedNewTrace(mission, current) || assistantTextReceived) {
        current.phase = "streaming";
        setAwaitingStream(false);
        if (autoShowSteps && !current.explicitlyNavigated) {
          setStudioView("metrics");
          switchedToSteps = true;
        }
      } else if (!isRunning) {
        current.phase = "no-stream";
        setAwaitingStream(false);
      }
    }
    if (current.phase === "streaming" && !isRunning) current.phase = "finishing";
    if (current.phase === "finishing") {
      // Some adapters publish the terminal metadata one render after isRunning
      // becomes false. Do not lose the notification by finalizing too early.
      const status = String(mission?.metadata?.missionStatus || mission?.status || "").toUpperCase();
      const succeeded = (mission?.metadata?.complete === true && /^(COMPLETED|SUCCEEDED)$/.test(status)) || assistantTextReceived;
      const failed = /^(FAILED|FAILED_TERMINAL|BLOCKED|CANCELLED|CANCELED|ERROR)$/.test(status);
      if (succeeded || failed) {
        current.phase = "finished";
        if (succeeded && (studioView !== "chat" || switchedToSteps)) setChatReady(true);
      }
    }
  }, [isRunning, userKey, mission, assistantTextReceived, studioView, autoShowSteps]);

  // Reset when the thread becomes empty (new conversation), not on every
  // assistant message or metric update.
  useEffect(() => {
    if (messages.length > 0 || isRunning) return;
    run.current = null;
    setMode("studio");
    setStudioView("chat");
    setChatReady(false);
    setAwaitingStream(false);
  }, [messages.length, isRunning]);

  const chooseStudioView = useCallback((view: "chat" | "metrics") => {
    if (run.current && (run.current.phase === "awaiting" || run.current.phase === "streaming")) {
      run.current.explicitlyNavigated = true;
    }
    setStudioView(view);
    if (view === "chat") setChatReady(false);
  }, []);

  const hasConversation = messages.length > 0;
  return <>
    <MissionWorkspaceHeader
      mode={mode}
      onModeChange={setMode}
      isRunning={isRunning}
      autoShowSteps={autoShowSteps}
      onAutoShowStepsChange={setAutoShowSteps}
    />
    {!hasConversation && <ResetMissionHeader />}

    <main className="sp-workspace-view" aria-label="Query Studio workspace">
      <section id="sp-studio-panel" className="sp-studio-panel" hidden={mode !== "studio"} aria-label="Query Studio">
        <MissionTraceDashboard
          metadata={mission?.metadata}
          traces={mission?.traces ?? []}
          activeView={studioView}
          onViewChange={chooseStudioView}
          hasConversation={hasConversation}
          conversation={<ChatConversation />}
          composer={<Composer />}
          chatNeedsAttention={chatReady}
          awaitingStream={awaitingStream}
          chatLoading={isRunning}
          showEmpty
          publishSummary={false}
        />
      </section>

      <section id="sp-walkthrough-panel" className="sp-architecture-panel" hidden={mode !== "walkthrough"}
        aria-label="SQLite architecture walkthrough">
        {mode === "walkthrough" && <ArchitectureWalkthrough metadata={mission?.metadata} />}
      </section>
    </main>

    <span className="sr-only" role="status" aria-live="polite">
      {chatReady ? "Mission finished. Chat result available in Step history." : ""}
    </span>
  </>;
};

export const Arrow: FC = () => (
  <CloneThreadShell>
    <ThreadPrimitive.Root className="sp-mission-shell flex h-full min-h-0 flex-col overflow-hidden">
      <MissionWorkspaceProvider><WorkspaceScreen /></MissionWorkspaceProvider>
    </ThreadPrimitive.Root>
  </CloneThreadShell>
);
