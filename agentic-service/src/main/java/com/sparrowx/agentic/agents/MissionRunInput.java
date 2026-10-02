package com.sparrowx.agentic.agents;

import com.sparrowx.agentic.mission.artifact.ArtifactPreparationResult;
import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.mission.model.MissionRequest;
import com.sparrowx.agentic.planning.MissionIntent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable business input supplied to one Embabel process.
 *
 * MissionIntent has already been resolved and frozen before Embabel starts.
 * Embabel owns goals, actions, planning and blackboard state from this point.
 */
public record MissionRunInput(
        String missionId,
        MissionRequest request,
        MissionIntent intent,
        ArtifactPreparationResult preparedArtifacts,
        Set<String> approvedGateIds,
        Instant startedAt
) {

    public MissionRunInput {
        missionId = requireText(
                missionId,
                "missionId"
        );

        request = Objects.requireNonNull(
                request,
                "request must not be null"
        );

        intent = Objects.requireNonNull(
                intent,
                "intent must not be null"
        );

        preparedArtifacts = Objects.requireNonNull(
                preparedArtifacts,
                "preparedArtifacts must not be null"
        );

        approvedGateIds = approvedGateIds == null
                ? Set.of()
                : Set.copyOf(approvedGateIds);

        startedAt = Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );

        if (request.context().tenantId().isBlank()) {
            throw new IllegalArgumentException(
                    "request.context.tenantId must not be blank"
            );
        }

        if (!missionId.equals(intent.missionId())) {
            throw new IllegalArgumentException(
                    "intent belongs to another mission"
            );
        }

        if (intent.selectedPath() == MissionPath.UNSPECIFIED) {
            throw new IllegalArgumentException(
                    "intent.selectedPath must be resolved"
            );
        }
    }

    public String tenantId() {
        return request.context().tenantId();
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
}