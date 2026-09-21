package com.sparrowx.agentic.mission;

import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.runtime.store.RuntimeEventStore;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class MissionEventPublisher {

    private final RuntimeEventStore eventStore;

    public MissionEventPublisher(
            RuntimeEventStore eventStore
    ) {
        this.eventStore = Objects.requireNonNull(
                eventStore,
                "eventStore"
        );
    }

    public <T extends MissionStreamEvent> T publish(
            String tenantId,
            T event
    ) {
        validateCommon(
                tenantId,
                event
        );

        validatePayload(event);

        MissionStreamEvent stored =
                eventStore.append(
                        tenantId,
                        event
                );

        if (!event.getClass().equals(
                stored.getClass()
        )) {
            throw new IllegalStateException(
                    "Runtime event store returned a different "
                            + "stream event type"
            );
        }

        @SuppressWarnings("unchecked")
        T typed = (T) stored;

        return typed;
    }

    private static void validateCommon(
            String tenantId,
            MissionStreamEvent event
    ) {
        Objects.requireNonNull(
                event,
                "event"
        );

        if (tenantId == null
                || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId must not be blank"
            );
        }

        if (event.missionId() == null
                || event.missionId().isBlank()) {
            throw new IllegalArgumentException(
                    "missionId must not be blank"
            );
        }

        if (event.resumeToken() == null
                || event.resumeToken().isBlank()) {
            throw new IllegalArgumentException(
                    "resumeToken must not be blank"
            );
        }

        Objects.requireNonNull(
                event.emittedAt(),
                "emittedAt must not be null"
        );
    }

    private static void validatePayload(
            MissionStreamEvent event
    ) {
        if (event instanceof MissionProgressEvent progress) {
            validateProgress(progress);
            return;
        }

        if (event instanceof MissionStreamEvent.AnswerDelta delta) {
            validateAnswerDelta(delta);
            return;
        }

        if (event instanceof MissionStreamEvent.Usage usage) {
            validateUsage(usage);
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported mission stream event type: "
                        + event.getClass().getName()
        );
    }

    private static void validateProgress(
            MissionProgressEvent progress
    ) {
        if (!Double.isFinite(
                progress.progressPercent()
        )
                || progress.progressPercent() < 0.0
                || progress.progressPercent() > 100.0) {

            throw new IllegalArgumentException(
                    "progressPercent must be between 0 and 100"
            );
        }
    }

    private static void validateAnswerDelta(
            MissionStreamEvent.AnswerDelta delta
    ) {
        if (delta.sequence() < 1L) {
            throw new IllegalArgumentException(
                    "answer delta sequence must be positive"
            );
        }

        if (delta.text() == null
                || delta.text().isEmpty()) {
            throw new IllegalArgumentException(
                    "answer delta text must not be empty"
            );
        }
    }

    private static void validateUsage(
            MissionStreamEvent.Usage usage
    ) {
        Objects.requireNonNull(
                usage.kind(),
                "usage kind must not be null"
        );

        if (usage.operationId().isBlank()) {
            throw new IllegalArgumentException(
                    "usage operationId must not be blank"
            );
        }

        if (usage.componentId().isBlank()) {
            throw new IllegalArgumentException(
                    "usage componentId must not be blank"
            );
        }

        if (usage.model().isBlank()) {
            throw new IllegalArgumentException(
                    "usage model must not be blank"
            );
        }

        requireNonNegative(
                usage.inputTokens(),
                "inputTokens"
        );

        requireNonNegative(
                usage.cachedInputTokens(),
                "cachedInputTokens"
        );

        requireNonNegative(
                usage.outputTokens(),
                "outputTokens"
        );

        requireNonNegative(
                usage.totalTokens(),
                "totalTokens"
        );

        requireNonNegative(
                usage.embeddingTokens(),
                "embeddingTokens"
        );

        requireNonNegative(
                usage.vectorsGenerated(),
                "vectorsGenerated"
        );

        requireNonNegative(
                usage.durationMs(),
                "durationMs"
        );
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