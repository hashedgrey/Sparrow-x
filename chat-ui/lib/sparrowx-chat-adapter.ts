"use client";

import type { ChatModelAdapter } from "@assistant-ui/react";
import { parseSseBlock } from "@/lib/ag-ui/bridge";

export type SparrowProgress = {
  missionId: string; status: string; stageId: string; stageName: string;
  stepId: string; stepName: string; stepStatus: string; message: string;
  progressPercent: number; resumeToken: string;
  componentId?: string; componentKind?: string; componentName?: string;
  traceId?: string; occurredAt?: number;
};
export type SparrowCitation = { id: string; label: string; evidenceId: string; excerpt: string };
export type SparrowTiming = { firstTokenMs?: number; totalMs?: number; chunks: number; tokensPerSecond?: number };
export type SparrowTokenUsage = {
  llmCalls: number; embeddingCalls: number; inputTokens: number; cachedInputTokens: number;
  outputTokens: number; totalTokens: number; embeddingTokens: number; vectorsGenerated: number;
  contextWindowTokens?: number;
};
export type SparrowTrace = {
  id: string; sectorId: string; sectorName: string; title: string; status: "running" | "succeeded" | "failed" | "paused";
  message?: string; startedAt?: number; endedAt?: number; durationMs?: number; componentName?: string;
  parentId?: string; kind?: string; source?: string;
  attributes?: Record<string, string>;
};
export type SparrowMetadata = {
  missionId?: string; missionStatus?: string; missionPath?: string;
  progress?: SparrowProgress; traces: SparrowTrace[];
  executiveSummary?: string; citations: SparrowCitation[]; complete: boolean;
  timing: SparrowTiming; usage?: SparrowTokenUsage;
};

type AgUiEvent = { type: string; name?: string; value?: Record<string, unknown>; delta?: string; message?: string; code?: string; runId?: string };
type SparrowResult = { missionId?: string; executiveSummary?: string; finalAnswer?: string; citations?: SparrowCitation[] };
type SparrowUsageEvent = { missionId: string; operationId: string; kind: "LLM" | "EMBEDDING"; componentId: string; model: string;
  inputTokens: number; cachedInputTokens: number; outputTokens: number; totalTokens: number;
  embeddingTokens: number; vectorsGenerated: number; durationMs: number; emittedAt: string };

function statusForProgress(stepStatus: string): SparrowTrace["status"] {
  const value = stepStatus?.toUpperCase() ?? "RUNNING";
  if (value.includes("FAIL") || value === "ERROR") return "failed";
  if (value.includes("PAUSE") || value.includes("WAIT")) return "paused";
  if (value === "SUCCEEDED" || value === "COMPLETED" || value === "SUCCESS") return "succeeded";
  return "running";
}

function withProgress(traces: SparrowTrace[], progress: SparrowProgress): SparrowTrace[] {
  const id = progress.traceId || `${progress.missionId}:${progress.stepId}`;
  const status = statusForProgress(progress.stepStatus);
  const when = progress.occurredAt || Date.now();
  const index = traces.findIndex((trace) => trace.id === id);
  const earlier = index >= 0 ? traces[index] : undefined;
  const sectorId = progress.stageId || progress.componentKind || "mission";
  const trace: SparrowTrace = {
    id, sectorId, sectorName: progress.stageName || sectorId,
    title: progress.stepName || progress.componentName || progress.stepId || "Step",
    status, message: progress.message,
    startedAt: earlier?.startedAt ?? when,
    durationMs: status !== "running" ? Math.max(0, when - (earlier?.startedAt ?? when)) : undefined,
    componentName: progress.componentName,
  };
  if (index < 0) return [...traces, trace];
  const result = traces.slice(); result[index] = trace; return result;
}

function withTypedTrace(traces: SparrowTrace[], payload: Record<string, unknown>): SparrowTrace[] {
  const id = typeof payload.id === "string" ? payload.id : "";
  const sectorId = typeof payload.sectorId === "string" ? payload.sectorId : "";
  if (!id || !sectorId) return traces;
  const index = traces.findIndex((item) => item.id === id);
  const previous = index === -1 ? undefined : traces[index];
  const statusValue = String(payload.status ?? "RUNNING").toUpperCase();
  const status: SparrowTrace["status"] = statusValue === "SUCCEEDED" ? "succeeded"
    : statusValue === "FAILED" ? "failed" : statusValue === "PAUSED" ? "paused" : "running";
  const parsedAt = Date.parse(String(payload.emittedAt ?? ""));
  const when = Number.isFinite(parsedAt) ? parsedAt : Date.now();
  const startedAt = previous?.startedAt ?? (typeof payload.startedAt === "string"
    ? Date.parse(payload.startedAt) : when);
  const endedAt = status === "running" ? undefined : when;
  const safeAttributes: Record<string, string> = {};
  if (payload.attributes && typeof payload.attributes === "object") {
    for (const [key, value] of Object.entries(payload.attributes as Record<string, unknown>)) {
      if (typeof value === "string" && key.length <= 64 && value.length <= 240) safeAttributes[key] = value;
    }
  }
  const span: SparrowTrace = {
    id, sectorId, sectorName: String(payload.sectorName || sectorId),
    title: String(payload.title || payload.kind || "Operation"),
    status, message: String(payload.message || "") || undefined,
    kind: String(payload.kind || "OPERATION"),
    source: String(payload.source || "agsvc"),
    parentId: typeof payload.parentId === "string" ? payload.parentId : undefined,
    startedAt: Number.isFinite(startedAt) ? startedAt : when,
    endedAt,
    durationMs: status === "running" ? undefined : Math.max(0, when - (Number.isFinite(startedAt) ? startedAt : when)),
    attributes: Object.keys(safeAttributes).length ? safeAttributes : previous?.attributes,
  };
  if (index === -1) return [...traces, span];
  const updated = traces.slice(); updated[index] = span; return updated;
}

/**
 * Runs through an AG-UI-compatible SSE endpoint while preserving Arrow's
 * SparrowX-specific citations, metrics, progress state and cancellation.
 */
export const sparrowxChatAdapter: ChatModelAdapter = {
  async *run({ messages, abortSignal, unstable_threadId }) {
    const latest = [...messages].reverse().find((m) => m.role === "user");
    const query = latest?.content.map((p) => p.type === "text" ? p.text : "").filter(Boolean).join("\n").trim();
    if (!query) throw new Error("SparrowX requires a non-empty user message.");

    let missionId: string | undefined;
    let missionStatus: string | undefined;
    let missionPath: string | undefined;
    let progress: SparrowProgress | undefined;
    let traces: SparrowTrace[] = [];
    let executiveSummary = "";
    let citations: SparrowCitation[] = [];
    let streamedAnswer = "";
    let finalAnswer = "";
    let complete = false;
    let firstTokenMs: number | undefined;
    let totalMs: number | undefined;
    let chunks = 0;
    const start = performance.now();
    const usage: SparrowTokenUsage = { llmCalls: 0, embeddingCalls: 0, inputTokens: 0,
      cachedInputTokens: 0, outputTokens: 0, totalTokens: 0, embeddingTokens: 0, vectorsGenerated: 0 };

    const cancelMission = () => {
      if (!missionId) return;
      void fetch("/api/chat/cancel", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ missionId, reason: "Stopped by user" }), keepalive: true,
      }).catch(() => undefined);
    };
    abortSignal.addEventListener("abort", cancelMission, { once: true });
    const currentResult = () => ({
      content: (finalAnswer || streamedAnswer) ? [{ type: "text" as const, text: finalAnswer || streamedAnswer }] : [],
      metadata: { custom: { sparrowx: {
        missionId, missionStatus, missionPath, progress, traces, executiveSummary: executiveSummary || undefined,
        citations, complete,
        timing: { firstTokenMs, totalMs, chunks,
          tokensPerSecond: totalMs && usage.outputTokens ? usage.outputTokens / (totalMs / 1000) : undefined },
        usage,
      } satisfies SparrowMetadata } },
    });

    try {
      const response = await fetch("/api/ag-ui", {
        method: "POST", headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({ query, threadId: unstable_threadId }), signal: abortSignal,
      });
      if (!response.ok) throw new Error(await response.text() || `AG-UI endpoint returned HTTP ${response.status}`);
      if (!response.body) throw new Error("AG-UI endpoint returned no stream");

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      try {
        while (true) {
          const { done, value } = await reader.read();
          buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
          buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
          let boundary = buffer.indexOf("\n\n");
          while (boundary !== -1) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const parsed = parseSseBlock(block);
            boundary = buffer.indexOf("\n\n");
            if (!parsed) continue;
            let event: AgUiEvent;
            try { event = JSON.parse(parsed.data) as AgUiEvent; } catch { continue; }
            chunks++;
            switch (event.type) {
              case "RUN_STARTED": missionStatus = "RUNNING"; break;
              case "CUSTOM": {
                const data = event.value ?? {};
                if (event.name === "sparrowx.mission") {
                  missionId = String(data.missionId || "") || missionId;
                  missionStatus = String(data.status || "RUNNING");
                  missionPath = String(data.path || "") || missionPath;
                } else if (event.name === "sparrowx.progress") {
                  progress = data as unknown as SparrowProgress;
                  missionId = progress.missionId || missionId;
                  missionStatus = progress.status || missionStatus;
                  traces = withProgress(traces, progress);
                } else if (event.name === "sparrowx.trace") {
                  traces = withTypedTrace(traces, data);
                } else if (event.name === "sparrowx.usage") {
                  const item = data as unknown as SparrowUsageEvent;
                  if (item.kind === "LLM") {
                    usage.llmCalls++; usage.inputTokens += item.inputTokens ?? 0;
                    usage.cachedInputTokens += item.cachedInputTokens ?? 0;
                    usage.outputTokens += item.outputTokens ?? 0; usage.totalTokens += item.totalTokens ?? 0;
                    // Model calls are real execution subtraces; don't invent internal Jev calls.
                    const id = `model:${item.operationId}`;
                    if (!traces.some((t) => t.id === id)) traces = [...traces, {
                      id, sectorId: "model-activity", sectorName: "Model calls", title: item.model || item.componentId || "LLM",
                      status: "succeeded", message: item.componentId, durationMs: item.durationMs,
                    }];
                  } else if (item.kind === "EMBEDDING") {
                    usage.embeddingCalls++; usage.embeddingTokens += item.embeddingTokens ?? 0;
                    usage.vectorsGenerated += item.vectorsGenerated ?? 0;
                  }
                } else if (event.name === "sparrowx.result") {
                  const result = data as unknown as SparrowResult;
                  missionId = result.missionId || missionId;
                  executiveSummary = result.executiveSummary ?? "";
                  citations = result.citations ?? [];
                  finalAnswer = result.finalAnswer ?? streamedAnswer;
                  if (firstTokenMs === undefined && finalAnswer) firstTokenMs = performance.now() - start;
                }
                break;
              }
              case "TEXT_MESSAGE_CONTENT": {
                if (event.delta) {
                  if (firstTokenMs === undefined) firstTokenMs = performance.now() - start;
                  streamedAnswer += event.delta;
                }
                break;
              }
              case "RUN_FINISHED": complete = true; missionStatus = "COMPLETED"; totalMs = performance.now() - start; break;
              case "RUN_ERROR": throw new Error(event.message || "SparrowX mission failed");
            }
            yield currentResult();
            if (complete) return;
          }
          if (done) break;
        }
      } finally { reader.releaseLock(); }
      if (!complete) throw new Error("AG-UI stream ended before RUN_FINISHED");
    } finally { abortSignal.removeEventListener("abort", cancelMission); }
  },
};
