package com.sparrowx.agentic.runtime.checkpoint;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * Reference to a payload persisted outside Temporal workflow history.
 */
public record CheckpointRef(
        String checkpointId,
        String tenantId,
        String missionId,
        CheckpointType checkpointType,
        int schemaVersion,
        String contentType,
        String sha256,
        long sizeBytes,
        Instant createdAt,
        Map<String, String> metadata
) {

    public CheckpointRef {
        checkpointId = nullToEmpty(checkpointId);
        tenantId = nullToEmpty(tenantId);
        missionId = nullToEmpty(missionId);

        checkpointType = checkpointType == null
                ? CheckpointType.UNSPECIFIED
                : checkpointType;

        contentType = nullToEmpty(contentType);
        sha256 = nullToEmpty(sha256);

        createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt"
        ).truncatedTo(ChronoUnit.MICROS);

        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(metadata);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public enum CheckpointType {
        UNSPECIFIED,
        MISSION_INPUT,
        MISSION_INTENT,
        PREPARED_ARTIFACTS,
        OBSERVATION,
        MISSION_RESULT
    }
}