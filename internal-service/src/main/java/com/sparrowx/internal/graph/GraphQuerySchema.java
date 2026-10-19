package com.sparrowx.internal.graph;

import java.util.*;

/** Backend-owned validation metadata; no LLM-authored schema authority. */
public record GraphQuerySchema(Map<String, Set<String>> nodeFields,
                               Map<Relation, Set<String>> relationshipFields) {
    public record Relation(String source, String type, String target) {}
    public GraphQuerySchema {
        nodeFields = copy(nodeFields);
        relationshipFields = copy(relationshipFields);
    }
    public List<Relation> relations() {
        return relationshipFields.keySet().stream()
                .sorted(Comparator.comparing(Relation::source)
                        .thenComparing(Relation::type).thenComparing(Relation::target))
                .toList();
    }
    private static <K> Map<K, Set<String>> copy(Map<K, Set<String>> source) {
        Map<K, Set<String>> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(Objects.requireNonNull(k), Set.copyOf(v)));
        return Collections.unmodifiableMap(result);
    }
}
