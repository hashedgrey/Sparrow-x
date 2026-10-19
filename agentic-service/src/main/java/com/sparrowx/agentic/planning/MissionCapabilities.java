package com.sparrowx.agentic.planning;

/** Stable names of mission tool capabilities; these are API identifiers, not reasoning heuristics. */
public final class MissionCapabilities {
    public static final String DOCUMENT_EVIDENCE = "document.evidence.build";
    public static final String INTERNAL_SEARCH = "internal.entities.search";
    public static final String COMPANY_GRAPH = "internal.company-graph.read";
    public static final String NONE = "NONE";

    private MissionCapabilities() { }
}
