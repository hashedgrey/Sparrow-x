import type { ChatModelAdapter } from "@assistant-ui/react";

type SparrowMissionEvent = {
    missionId?: string;
    status?: string;
    path?: string;
};

export type SparrowProgress = {
    missionId: string;
    status: string;
    stageId: string;
    stageName: string;
    stepId: string;
    stepName: string;
    stepStatus: string;
    message: string;
    progressPercent: number;
    resumeToken: string;
    componentId?: string;
    componentKind?: string;
    componentName?: string;
};

export type SparrowCitation = {
    id: string;
    label: string;
    evidenceId: string;
    excerpt: string;
};

type SparrowResult = {
    missionId: string;
    executiveSummary: string;
    finalAnswer: string;
    citations: SparrowCitation[];
};

type ParsedSseEvent = {
    event: string;
    data: string;
    id?: string;
};

export type SparrowTiming = {
    firstTokenMs?: number;
    totalMs?: number;
    chunks: number;
    tokensPerSecond?: number;
};

export type SparrowTokenUsage = {
    llmCalls: number;
    embeddingCalls: number;

    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
    totalTokens: number;

    embeddingTokens: number;
    vectorsGenerated: number;

    contextWindowTokens?: number;
};

type SparrowUsageEvent = {
    missionId: string;
    operationId: string;
    kind: "LLM" | "EMBEDDING";
    componentId: string;
    model: string;

    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
    totalTokens: number;

    embeddingTokens: number;
    vectorsGenerated: number;

    durationMs: number;
    emittedAt: string;
};

export type SparrowMetadata = {
    missionId?: string;
    missionStatus?: string;
    missionPath?: string;
    progress?: SparrowProgress;
    executiveSummary?: string;
    citations: SparrowCitation[];
    complete: boolean;

    timing: SparrowTiming;
    usage?: SparrowTokenUsage;
};

type SparrowAnswerDelta = {
    text: string;
};

type SparrowErrorEvent = {
    missionId?: string;
    code: string;
    message: string;
    retryable: boolean;
};

function parseSseBlock(block: string): ParsedSseEvent | null {
    let event = "message";
    let id: string | undefined;

    const dataLines: string[] = [];

    for (const rawLine of block.split("\n")) {
        const line = rawLine.endsWith("\r")
            ? rawLine.slice(0, -1)
            : rawLine;

        if (!line || line.startsWith(":")) {
            continue;
        }

        if (line.startsWith("event:")) {
            event = line.slice("event:".length).trim();
            continue;
        }

        if (line.startsWith("id:")) {
            id = line.slice("id:".length).trim();
            continue;
        }

        if (line.startsWith("data:")) {
            dataLines.push(
                line.slice("data:".length).trimStart(),
            );
        }
    }

    if (dataLines.length === 0) {
        return null;
    }

    return {
        event,
        data: dataLines.join("\n"),
        id,
    };
}

async function* readSse(
    body: ReadableStream<Uint8Array>,
): AsyncGenerator<ParsedSseEvent> {
    const reader = body.getReader();
    const decoder = new TextDecoder();

    let buffer = "";

    try {
        while (true) {
            const { done, value } = await reader.read();

            if (done) {
                buffer += decoder.decode();

                const finalBlock = buffer.trim();

                if (finalBlock) {
                    const parsed = parseSseBlock(finalBlock);

                    if (parsed) {
                        yield parsed;
                    }
                }

                return;
            }

            buffer += decoder.decode(value, {
                stream: true,
            });

            buffer = buffer.replace(/\r\n/g, "\n");

            while (true) {
                const boundary = buffer.indexOf("\n\n");

                if (boundary < 0) {
                    break;
                }

                const block = buffer.slice(0, boundary);

                buffer = buffer.slice(boundary + 2);

                const parsed = parseSseBlock(block);

                if (parsed) {
                    yield parsed;
                }
            }
        }
    } finally {
        reader.releaseLock();
    }
}

function metadataResult(
    metadata: SparrowMetadata,
) {
    return {
        metadata: {
            custom: {
                sparrowx: metadata,
            },
        },
    };
}

export const sparrowxChatAdapter: ChatModelAdapter = {
    async *run({
                   messages,
                   abortSignal,
                   unstable_threadId,
               }) {
        const latestUserMessage = [...messages]
            .reverse()
            .find((message) => message.role === "user");

        if (!latestUserMessage) {
            throw new Error(
                "SparrowX requires a user message.",
            );
        }

        const query = latestUserMessage.content
            .map((part) =>
                part.type === "text"
                    ? part.text
                    : "",
            )
            .filter(Boolean)
            .join("\n")
            .trim();

        if (!query) {
            throw new Error(
                "SparrowX requires non-empty text.",
            );
        }

        let missionId: string | undefined;

        const cancelMission = () => {
            if (!missionId) {
                return;
            }

            void fetch(
                "/api/chat/cancel",
                {
                    method: "POST",

                    headers: {
                        "Content-Type":
                            "application/json",
                    },

                    body: JSON.stringify({
                        missionId,
                        reason: "Stopped by user",
                    }),

                    /*
                     * This request must survive the main
                     * SSE request being aborted.
                     */
                    keepalive: true,
                },
            ).catch(() => {
                /*
                 * Do not turn a user cancellation into
                 * an assistant message error.
                 *
                 * Backend logging will expose an actual
                 * cancellation transport failure.
                 */
            });
        };

        abortSignal.addEventListener(
            "abort",
            cancelMission,
            {
                once: true,
            },
        );
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "text/event-stream",
            },
            body: JSON.stringify({
                query,
                conversationId:
                    unstable_threadId ?? undefined,
            }),
            signal: abortSignal,
        });

        if (!response.ok) {
            const detail = await response.text();

            throw new Error(
                detail ||
                `SparrowX request failed with HTTP ${response.status}`,
            );
        }

        if (!response.body) {
            throw new Error(
                "SparrowX returned no response stream.",
            );
        }

        let missionStatus: string | undefined;
        let missionPath: string | undefined;
        let progress: SparrowProgress | undefined;

        let executiveSummary = "";
        let citations: SparrowCitation[] = [];
        let streamedAnswer = "";
        let finalAnswer = "";
        let completed = false;
        const startedAt = performance.now();

        let firstTokenMs: number | undefined;
        let totalMs: number | undefined;
        let chunks = 0;

        let usage: SparrowTokenUsage = {
            llmCalls: 0,
            embeddingCalls: 0,

            inputTokens: 0,
            cachedInputTokens: 0,
            outputTokens: 0,
            totalTokens: 0,

            embeddingTokens: 0,
            vectorsGenerated: 0,
        };

        const currentMetadata = (): SparrowMetadata => ({
            missionId,
            missionStatus,
            missionPath,
            progress,
            executiveSummary: executiveSummary || undefined,
            citations,
            complete: completed,

            timing: {
                firstTokenMs,
                totalMs,
                chunks,
                tokensPerSecond:
                    usage?.outputTokens && totalMs
                        ? usage.outputTokens / (totalMs / 1000)
                        : undefined,
            },

            usage,
        });

        const currentContent = () => {
            const text =
                finalAnswer || streamedAnswer;

            return text
                ? [
                    {
                        type: "text" as const,
                        text,
                    },
                ]
                : [];
        };

        const currentResult = () => ({
            content: currentContent(),
            ...metadataResult(
                currentMetadata(),
            ),
        });

        for await (const item of readSse(response.body)) {
            abortSignal.throwIfAborted();

            chunks++;
            switch (item.event) {
                case "mission": {
                    const mission =
                        JSON.parse(
                            item.data,
                        ) as SparrowMissionEvent;

                    missionId = mission.missionId;
                    missionStatus = mission.status;
                    missionPath = mission.path;

                    yield currentResult();

                    break;
                }

                case "answer_delta": {
                    const delta =
                        JSON.parse(item.data) as SparrowAnswerDelta;

                    if (!delta.text) {
                        break;
                    }

                    if (firstTokenMs === undefined) {
                        firstTokenMs =
                            performance.now() - startedAt;
                    }

                    streamedAnswer += delta.text;

                    yield {
                        content: [
                            {
                                type: "text",
                                text: streamedAnswer,
                            },
                        ],
                        ...metadataResult(
                            currentMetadata(),
                        ),
                    };

                    break;
                }

                case "progress": {
                    progress =
                        JSON.parse(
                            item.data,
                        ) as SparrowProgress;

                    missionId =
                        progress.missionId || missionId;

                    missionStatus =
                        progress.status || missionStatus;

                    yield currentResult();

                    break;
                }

                case "usage": {
                    const event =
                        JSON.parse(
                            item.data,
                        ) as SparrowUsageEvent;

                    missionId =
                        event.missionId || missionId;

                    if (event.kind === "LLM") {
                        usage.llmCalls++;

                        usage.inputTokens +=
                            event.inputTokens ?? 0;

                        usage.cachedInputTokens +=
                            event.cachedInputTokens ?? 0;

                        usage.outputTokens +=
                            event.outputTokens ?? 0;

                        usage.totalTokens +=
                            event.totalTokens ?? 0;
                    }

                    if (event.kind === "EMBEDDING") {
                        usage.embeddingCalls++;

                        usage.embeddingTokens +=
                            event.embeddingTokens ?? 0;

                        usage.vectorsGenerated +=
                            event.vectorsGenerated ?? 0;
                    }

                    yield currentResult();

                    break;
                }

                case "heartbeat": {
                    // Transport keepalive only.
                    break;
                }

                case "error": {
                    const error =
                        JSON.parse(
                            item.data,
                        ) as SparrowErrorEvent;

                    missionId =
                        error.missionId || missionId;

                    throw new Error(
                        error.message ||
                        "SparrowX could not complete the request.",
                    );
                }

                case "result": {
                    const result =
                        JSON.parse(
                            item.data,
                        ) as SparrowResult;

                    missionId =
                        result.missionId || missionId;

                    executiveSummary =
                        result.executiveSummary ?? "";

                    citations =
                        result.citations ?? [];

                    finalAnswer =
                        result.finalAnswer
                        ?? streamedAnswer;

                    if (
                        firstTokenMs === undefined &&
                        finalAnswer
                    ) {
                        firstTokenMs =
                            performance.now()
                            - startedAt;
                    }

                    yield currentResult();

                    break;
                }

                case "complete": {
                    completed = true;

                    totalMs =
                        performance.now()
                        - startedAt;

                    yield currentResult();

                    return;
                }

                default:
                    break;
            }
        }

        if (!finalAnswer) {
            throw new Error(
                "SparrowX stream ended before a mission result was received.",
            );
        }
    },
};