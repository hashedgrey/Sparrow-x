package com.sparrowx.agentic.planning.graph;

import java.util.Objects;
import java.util.Set;

/**
 * Logical root selection provided by the verified internal entity search.
 * This is NOT a predicate inferred by the goal-generation model.
 * The executor checks each returned IntSvc root against this scope at read time.
 */
public record GraphRootScope(String nodeType, Set<String> entityIds) {
    public GraphRootScope {
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("Selected root type is required");
        }
        entityIds = Set.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (entityIds.isEmpty() || entityIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("A graph scope needs resolved entity IDs");
        }
    }
}
