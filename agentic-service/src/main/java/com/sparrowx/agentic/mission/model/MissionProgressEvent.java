package com.sparrowx.agentic.mission.model;

import com.sparrowx.agentic.runtime.model.StepStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Durable public mission progress event stored for replay and stream
 * resumption.
 */
public record MissionProgressEvent(
        String missionId,
        MissionStatus status,
        String stageId,
        String stageName,
        String stepId,
        String stepName,
        StepStatus stepStatus,
        ComponentInvocation currentComponent,
        String message,
        double progressPercent,
        String resumeToken,
        Instant emittedAt,
        Map<String, String> metadata
) implements MissionStreamEvent {

    public MissionProgressEvent {
        missionId = nullToEmpty(missionId);
        status = status == null
                ? MissionStatus.UNSPECIFIED
                : status;

        stageId = nullToEmpty(stageId);
        stageName = nullToEmpty(stageName);
        stepId = nullToEmpty(stepId);
        stepName = nullToEmpty(stepName);

        stepStatus = stepStatus == null
                ? StepStatus.UNSPECIFIED
                : stepStatus;

        message = nullToEmpty(message);
        resumeToken = nullToEmpty(resumeToken);

        emittedAt = Objects.requireNonNull(
                emittedAt,
                "emittedAt"
        );

        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(metadata);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}