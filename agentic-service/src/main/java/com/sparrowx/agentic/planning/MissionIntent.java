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
 * Hard semantic requirements belong here so utility planning cannot bypass them.
 */
@JsonIgnoreProperties(
        value = {"governed", "retrieval", "internalTraversal"},
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
        boolean requiresCompanyGraph,
        boolean requiresHumanReview,
        boolean requiresCitations,
        boolean requiresVerification,
        boolean allowsExternalSources,
        Set<String> allowedTools,
        Set<String> allowedSourceServices,
        Map<String, Object> attributes
) {

    public MissionIntent {
        missionId = requireText(missionId, "missionId");
        objective = requireText(objective, "objective");
        selectedPath = Objects.requireNonNull(selectedPath, "selectedPath must not be null");
        plannerMode = Objects.requireNonNull(plannerMode, "plannerMode must not be null");

        // Graph traversal is an internal-data requirement by definition.
        if (requiresCompanyGraph) {
            requiresInternalContext = true;
        }

        targetEntities = immutableSet(targetEntities);
        topics = immutableSet(topics);
        requiredOutputSections = immutableList(requiredOutputSections);
        allowedTools = immutableSet(allowedTools);
        allowedSourceServices = immutableSet(allowedSourceServices);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Backward-compatible constructor for existing call sites that predate
     * the explicit company-graph requirement.
     */
    public MissionIntent(
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
        this(
                missionId,
                objective,
                selectedPath,
                plannerMode,
                targetEntities,
                topics,
                requiredOutputSections,
                requiresDocumentEvidence,
                requiresInternalContext,
                false,
                requiresHumanReview,
                requiresCitations,
                requiresVerification,
                allowsExternalSources,
                allowedTools,
                allowedSourceServices,
                attributes
        );
    }

    @JsonIgnore
    public boolean requiresRetrieval() {
        return requiresDocumentEvidence || requiresInternalContext;
    }

    @JsonIgnore
    public boolean requiresInternalTraversal() {
        return requiresCompanyGraph;
    }

    @JsonIgnore
    public boolean isGoverned() {
        return requiresHumanReview || requiresVerification || requiresCitations;
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
