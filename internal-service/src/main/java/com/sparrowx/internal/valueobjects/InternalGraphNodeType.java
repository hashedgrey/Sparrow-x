package com.sparrowx.internal.valueobjects;

import java.util.Locale;

public enum InternalGraphNodeType {
    COMPANY, ENGINEER, TEAM, MODULE, REPOSITORY, DOCUMENT, RUNBOOK,
    ONBOARDING_PATH, ONBOARDING_TASK, ONBOARDING_ASSIGNMENT, PERMISSION,
    LEARNING_TOPIC, LEARNING_OBJECTIVE;

    /** Unknown graph types must never be misrepresented as MODULE. */
    public static InternalGraphNodeType from(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Graph node type is required");
        String canonical = value.trim().toUpperCase(Locale.ROOT);
        String wirePrefix = "INTERNAL_GRAPH_NODE_TYPE_";
        if (canonical.startsWith(wirePrefix)) canonical = canonical.substring(wirePrefix.length());
        try {
            return valueOf(canonical);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown graph node type: " + value, invalid);
        }
    }
}
