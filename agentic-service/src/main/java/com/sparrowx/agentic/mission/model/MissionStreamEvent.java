package com.sparrowx.agentic.mission.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable public mission-stream event.
 *
 * Every event has a stable resume token so the runtime event store can
 * replay the stream and resume clients after disconnection.
 *
 * Stream event kinds:
 *
 * - MissionProgressEvent: mission/action lifecycle progress
 * - AnswerDelta: incremental user-facing synthesis text
 * - Usage: one concrete model operation and its usage
 */
public sealed interface MissionStreamEvent
        permits MissionProgressEvent,
        MissionStreamEvent.AnswerDelta,
        MissionStreamEvent.Usage {

    String missionId();

    String resumeToken();

    Instant emittedAt();

    /**
     * Incremental user-facing answer content.
     *
     * sequence is monotonically increasing for one synthesis stream.
     */
    record AnswerDelta(
            String missionId,
            long sequence,
            String text,
            String resumeToken,
            Instant emittedAt
    ) implements MissionStreamEvent {

        public AnswerDelta {
            missionId = requireText(
                    missionId,
                    "missionId"
            );

            if (sequence < 1L) {
                throw new IllegalArgumentException(
                        "sequence must be positive"
                );
            }

            /*
             * Do not use isBlank().
             *
             * Whitespace can be a legitimate streamed LLM chunk.
             */
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException(
                        "text must not be empty"
                );
            }

            resumeToken = requireText(
                    resumeToken,
                    "resumeToken"
            );

            emittedAt = Objects.requireNonNull(
                    emittedAt,
                    "emittedAt must not be null"
            );
        }
    }

    /**
     * Usage produced by exactly one model operation.
     *
     * This is intentionally not a cumulative mission total.
     *
     * Mission-level totals are derived by aggregating Usage events.
     */
    record Usage(
            String missionId,
            String operationId,
            UsageKind kind,
            String componentId,
            String model,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            long embeddingTokens,
            long vectorsGenerated,
            long durationMs,
            String resumeToken,
            Instant emittedAt
    ) implements MissionStreamEvent {

        public Usage {
            missionId = requireText(
                    missionId,
                    "missionId"
            );

            operationId = requireText(
                    operationId,
                    "operationId"
            );

            kind = Objects.requireNonNull(
                    kind,
                    "kind must not be null"
            );

            componentId = requireText(
                    componentId,
                    "componentId"
            );

            model = requireText(
                    model,
                    "model"
            );

            requireNonNegative(
                    inputTokens,
                    "inputTokens"
            );

            requireNonNegative(
                    cachedInputTokens,
                    "cachedInputTokens"
            );

            requireNonNegative(
                    outputTokens,
                    "outputTokens"
            );

            requireNonNegative(
                    totalTokens,
                    "totalTokens"
            );

            requireNonNegative(
                    embeddingTokens,
                    "embeddingTokens"
            );

            requireNonNegative(
                    vectorsGenerated,
                    "vectorsGenerated"
            );

            requireNonNegative(
                    durationMs,
                    "durationMs"
            );

            resumeToken = requireText(
                    resumeToken,
                    "resumeToken"
            );

            emittedAt = Objects.requireNonNull(
                    emittedAt,
                    "emittedAt must not be null"
            );
        }
    }

    enum UsageKind {
        LLM,
        EMBEDDING
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }

    private static void requireNonNegative(
            long value,
            String field
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    field + " must not be negative"
            );
        }
    }
}