/** Translate SparrowX mission SSE events to AG-UI 1.0 without inventing tool calls. */
export type RawSse = { event: string; data: string };
export type AgUiWireEvent = { type: string; [key: string]: unknown };

export function parseSseBlock(block: string): RawSse | undefined {
  let event = "message";
  const data: string[] = [];
  for (const l of block.split("\n")) {
    const line = l.endsWith("\r") ? l.slice(0, -1) : l;
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  return data.length ? { event, data: data.join("\n") } : undefined;
}

export class SparrowAgUiBridge {
  private started = false;
  private textStarted = false;
  private text = "";
  private done = false;
  private readonly counts = new Map<string, number>();
  private readonly openedSteps = new Map<string, string>();
  private missionId = "";
  readonly messageId: string;

  constructor(readonly threadId: string, readonly runId: string) {
    this.messageId = `${runId}:answer`;
  }

  get finished() { return this.done; }
  get hasMission() { return Boolean(this.missionId); }

  private startRun(): AgUiWireEvent[] {
    if (this.started) return [];
    this.started = true;
    return [{ type: "RUN_STARTED", threadId: this.threadId, runId: this.runId, protocolVersion: "1.0" }];
  }

  private openText(): AgUiWireEvent[] {
    if (this.textStarted) return [];
    this.textStarted = true;
    return [{ type: "TEXT_MESSAGE_START", messageId: this.messageId, role: "assistant" }];
  }

  private appendText(value: string): AgUiWireEvent[] {
    if (!value) return [];
    this.text += value;
    return [...this.openText(), { type: "TEXT_MESSAGE_CONTENT", messageId: this.messageId, delta: value }];
  }

  private endText(): AgUiWireEvent[] {
    if (!this.textStarted) return [];
    this.textStarted = false;
    return [{ type: "TEXT_MESSAGE_END", messageId: this.messageId }];
  }

  convert(sse: RawSse): AgUiWireEvent[] {
    const payload: Record<string, unknown> = JSON.parse(sse.data);
    if (sse.event === "mission") {
      this.missionId = String(payload.missionId ?? "");
      return [...this.startRun(), { type: "CUSTOM", name: "sparrowx.mission", value: payload }];
    }
    const start = this.startRun();
    if (sse.event === "progress") {
      // Typed backend trace envelope is carried inside durable progress metadata.
      // Do not duplicate it as a synthetic STEP_* milestone.
      const rawTrace = payload.trace;
      if (rawTrace && typeof rawTrace === "object") {
        return [...start, { type: "CUSTOM", name: "sparrowx.trace", value: {
          ...(rawTrace as Record<string, unknown>), missionId: payload.missionId,
          emittedAt: payload.emittedAt,
        } }];
      }
      const stepKey = String(payload.stepId || payload.stepName || "step");
      const stepStatus = String(payload.stepStatus ?? "RUNNING").toUpperCase();
      const isStart = stepStatus === "RUNNING" || stepStatus === "STARTED";
      let stepName: string;
      let traceId: string;
      if (isStart) {
        const attempt = (this.counts.get(stepKey) ?? 0) + 1;
        this.counts.set(stepKey, attempt);
        stepName = `${stepKey}#${attempt}`;
        this.openedSteps.set(stepKey, stepName);
      } else {
        stepName = this.openedSteps.get(stepKey) ?? `${stepKey}#${this.counts.get(stepKey) ?? 1}`;
        this.openedSteps.delete(stepKey);
      }
      traceId = `${this.runId}:${stepName}`;
      const detail = { ...payload, traceId, occurredAt: Date.now() };
      return [
        ...start,
        { type: isStart ? "STEP_STARTED" : "STEP_FINISHED", stepName },
        { type: "CUSTOM", name: "sparrowx.progress", value: detail },
      ];
    }
    if (sse.event === "usage") return [...start, { type: "CUSTOM", name: "sparrowx.usage", value: payload }];
    if (sse.event === "answer_delta") return [...start, ...this.appendText(String(payload.text ?? ""))];
    if (sse.event === "result") {
      const finalAnswer = String(payload.finalAnswer ?? "");
      // Avoid echoing the whole answer after text deltas. Backend result normally equals delta accumulation.
      const remainder = finalAnswer.startsWith(this.text) ? finalAnswer.slice(this.text.length) : this.text ? "" : finalAnswer;
      return [...start, ...this.appendText(remainder), { type: "CUSTOM", name: "sparrowx.result", value: payload }];
    }
    if (sse.event === "complete") {
      this.done = true;
      return [...start, ...this.endText(), { type: "RUN_FINISHED", threadId: this.threadId, runId: this.runId }];
    }
    if (sse.event === "error") {
      this.done = true;
      return [...start, ...this.endText(), { type: "RUN_ERROR", message: String(payload.message ?? "SparrowX mission failed"), code: String(payload.code ?? "MISSION_FAILED") }];
    }
    return [];
  }

  unexpectedEnd(): AgUiWireEvent[] {
    if (this.done) return [];
    this.done = true;
    return [...this.startRun(), ...this.endText(), { type: "RUN_ERROR", message: "Mission stream ended without a completion event", code: "STREAM_CLOSED" }];
  }
}
