package com.sparrowx.agentic.data.postgres.mappers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.data.postgres.entities.RuntimeEventEntity;
import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Component
public final class RuntimeEventEntityMapper {

    private static final String KIND_PROGRESS =
            "PROGRESS";

    private static final String KIND_ANSWER_DELTA =
            "ANSWER_DELTA";

    private static final String KIND_USAGE =
            "USAGE";

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public RuntimeEventEntityMapper(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    public RuntimeEventEntity toEntity(
            String tenantId,
            MissionStreamEvent event
    ) {
        Objects.requireNonNull(
                tenantId,
                "tenantId"
        );

        Objects.requireNonNull(
                event,
                "event"
        );

        if (event.resumeToken() == null
                || event.resumeToken().isBlank()) {
            throw new IllegalArgumentException(
                    "A persisted stream event requires a resume token"
            );
        }

        return new RuntimeEventEntity(
                tenantId,
                event.missionId(),
                event.resumeToken(),
                eventKind(event),
                missionStatus(event),
                objectMapper.convertValue(
                        event,
                        MAP_TYPE
                ),
                event.emittedAt()
        );
    }

    public MissionStreamEvent toDomain(
            RuntimeEventEntity entity
    ) {
        Objects.requireNonNull(
                entity,
                "entity"
        );

        try {
            MissionStreamEvent event =
                    switch (entity.getEventKind()) {

                        case KIND_PROGRESS ->
                                objectMapper.convertValue(
                                        entity.getEventPayload(),
                                        MissionProgressEvent.class
                                );

                        case KIND_ANSWER_DELTA ->
                                objectMapper.convertValue(
                                        entity.getEventPayload(),
                                        MissionStreamEvent.AnswerDelta.class
                                );

                        case KIND_USAGE ->
                                objectMapper.convertValue(
                                        entity.getEventPayload(),
                                        MissionStreamEvent.Usage.class
                                );

                        default ->
                                throw new IllegalStateException(
                                        "Unknown runtime event kind: "
                                                + entity.getEventKind()
                                );
                    };

            validateProjection(
                    entity,
                    event
            );

            return event;

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid persisted runtime event: "
                            + entity.getResumeToken(),
                    exception
            );
        }
    }

    private static String eventKind(
            MissionStreamEvent event
    ) {
        if (event instanceof MissionProgressEvent) {
            return KIND_PROGRESS;
        }

        if (event instanceof MissionStreamEvent.AnswerDelta) {
            return KIND_ANSWER_DELTA;
        }

        if (event instanceof MissionStreamEvent.Usage) {
            return KIND_USAGE;
        }

        throw new IllegalArgumentException(
                "Unsupported mission stream event type: "
                        + event.getClass().getName()
        );
    }

    private static MissionStatus missionStatus(
            MissionStreamEvent event
    ) {
        if (event instanceof MissionProgressEvent progress) {
            return progress.status();
        }

        return null;
    }

    private static void validateProjection(
            RuntimeEventEntity entity,
            MissionStreamEvent event
    ) {
        if (!entity.getMissionId().equals(
                event.missionId()
        )) {
            throw projectionMismatch(entity);
        }

        if (!entity.getResumeToken().equals(
                event.resumeToken()
        )) {
            throw projectionMismatch(entity);
        }

        if (!samePersistedInstant(
                entity.getEmittedAt(),
                event.emittedAt()
        )) {
            throw projectionMismatch(entity);
        }

        if (!eventKind(event).equals(
                entity.getEventKind()
        )) {
            throw projectionMismatch(entity);
        }

        if (event instanceof MissionProgressEvent progress) {

            if (entity.getMissionStatus()
                    != progress.status()) {
                throw projectionMismatch(entity);
            }

            return;
        }

        /*
         * Non-progress stream events must not pretend to carry
         * mission lifecycle state.
         */
        if (entity.getMissionStatus() != null) {
            throw projectionMismatch(entity);
        }
    }

    private static boolean samePersistedInstant(
            Instant persisted,
            Instant payload
    ) {
        if (persisted == null || payload == null) {
            return persisted == payload;
        }

        /*
         * PostgreSQL timestamp with time zone is persisted at
         * microsecond precision, while Java Instant / JSON may
         * retain nanoseconds.
         */
        return Duration
                .between(persisted, payload)
                .abs()
                .compareTo(Duration.ofNanos(1_000L)) < 0;
    }

    private static IllegalStateException projectionMismatch(
            RuntimeEventEntity entity
    ) {
        return new IllegalStateException(
                "Runtime event payload does not match its projection: "
                        + entity.getResumeToken()
        );
    }
}