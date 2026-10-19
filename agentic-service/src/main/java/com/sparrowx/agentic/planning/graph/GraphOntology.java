package com.sparrowx.agentic.planning.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observed tenant ontology supplied by IntSvc, not authored by the model. */
public record GraphOntology(List<Relation> relations, Map<String, List<String>> fields,
                            Map<String, List<String>> values, String schemaVersion) {
    public record Relation(String source, String type, String target, List<String> fields) {
        public Relation(String source, String type, String target) {
            this(source, type, target, List.of());
        }
        public Relation {
            requireText(source, "source");
            requireText(type, "type");
            requireText(target, "target");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            fields.forEach(field -> requireText(field, "relationship field"));
        }
    }

    public GraphOntology {
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        fields = copy(Objects.requireNonNull(fields, "fields"));
        values = copy(Objects.requireNonNull(values, "values"));
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, items) -> result.put(key, List.copyOf(items)));
        return Map.copyOf(result);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
    }
}
