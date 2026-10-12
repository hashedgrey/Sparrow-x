package com.sparrowx.agentic.planning.graph;

/** Semantic and execution outcome of a complete graph query. */
public enum GraphResolutionStatus {
    COMPLETE,
    INSUFFICIENT,
    AMBIGUOUS,
    UNSUPPORTED,
    BUDGET_EXHAUSTED
}
