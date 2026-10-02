package com.sparrowx.agentic.planning;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowx.agentic.mission.model.MissionPath;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen semantic interpretation of one mission.
 *
 * MissionPath controls durability characteristics.
 * PlannerMode controls Embabel planning behavior.
 */
@JsonIgnoreProperties(
        value = {"governed", "retrieval"},
        ignoreUnknown = true
)

public record MissionIntent(
        String missionId,
        String objective,
        MissionPath selectedPath,
        PlannerMode plannerMode,
        Set<String> targetEntities,
        Set<String> topics,
        List<String> requiredOutputSections,
        boolean requiresDocumentEvidence,
        boolean requiresInternalContext,
        boolean requiresHumanReview,
        boolean requiresCitations,
        boolean requiresVerification,
        boolean allowsExternalSources,
        Set<String> allowedTools,
        Set<String> allowedSourceServices,
        Map<String, Object> attributes
) {

    public MissionIntent {
        missionId =
                requireText(
                        missionId,
                        "missionId"
                );

        objective =
                requireText(
                        objective,
                        "objective"
                );

        selectedPath =
                Objects.requireNonNull(
                        selectedPath,
                        "selectedPath must not be null"
                );

        plannerMode =
                Objects.requireNonNull(
                        plannerMode,
                        "plannerMode must not be null"
                );

        targetEntities =
                targetEntities == null
                        ? Set.of()
                        : Set.copyOf(
                        targetEntities
                );

        topics =
                topics == null
                        ? Set.of()
                        : Set.copyOf(
                        topics
                );

        requiredOutputSections =
                requiredOutputSections == null
                        ? List.of()
                        : List.copyOf(
                        requiredOutputSections
                );

        allowedTools =
                allowedTools == null
                        ? Set.of()
                        : Set.copyOf(
                        allowedTools
                );

        allowedSourceServices =
                allowedSourceServices == null
                        ? Set.of()
                        : Set.copyOf(
                        allowedSourceServices
                );

        attributes =
                attributes == null
                        ? Map.of()
                        : Map.copyOf(
                        attributes
                );
    }

    @JsonIgnore
    public boolean requiresRetrieval() {
        return requiresDocumentEvidence || requiresInternalContext;
    }

    @JsonIgnore
    public boolean isGoverned() {
        return requiresHumanReview || requiresVerification || requiresCitations;
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }
}