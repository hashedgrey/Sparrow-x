package com.sparrowx.agentic.planning;

import java.util.Objects;
import java.util.Set;

/** Stable names of mission tool capabilities; these are API identifiers, not reasoning heuristics. */
public final class MissionCapabilities {
    public static final String DOCUMENT_EVIDENCE = "document.evidence.build";
    public static final String INTERNAL_SEARCH = "internal.entities.search";
    public static final String COMPANY_GRAPH = "internal.company-graph.read";
    public static final String NONE = "NONE";

    /** Frozen allowlists contain effective grants; an empty set grants nothing. */
    public static boolean allows(Set<String> tools, String capability) {
        return tools != null && tools.contains(capability);
    }

    public static String sourceFor(String capability) {
        return switch (Objects.requireNonNull(capability, "capability")) {
            case DOCUMENT_EVIDENCE -> "document-service";
            case INTERNAL_SEARCH, COMPANY_GRAPH -> "internal-service";
            default -> throw new IllegalArgumentException("Unknown mission capability: " + capability);
        };
    }

    public static boolean allows(MissionIntent intent, String capability) {
        Objects.requireNonNull(intent, "intent");
        return allows(intent.allowedTools(), capability)
                && intent.allowedSourceServices().contains(sourceFor(capability));
    }

    private MissionCapabilities() { }
}
