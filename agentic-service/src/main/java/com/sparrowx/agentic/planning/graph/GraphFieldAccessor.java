package com.sparrowx.agentic.planning.graph;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves a validated ontology field path against a graph node without per-domain field switches. */
public final class GraphFieldAccessor {
    private GraphFieldAccessor() { }

    public record NodeData(String nodeType, String entityId, String label,
                           String summary, Map<String, String> attributes) {
        public NodeData {
            Objects.requireNonNull(nodeType); Objects.requireNonNull(entityId);
            Objects.requireNonNull(label); Objects.requireNonNull(summary);
            attributes = Map.copyOf(Objects.requireNonNull(attributes));
        }
    }

    /**
     * Distinguishes absent value (Optional.empty) from undeclared field (exception).
     * A missing declared attribute must not be silently treated as the empty string.
     */
    public static Optional<String> read(NodeData node, String field,
                                        Map<String, List<String>> ontologyFields) {
        Objects.requireNonNull(node); Objects.requireNonNull(field);
        Objects.requireNonNull(ontologyFields);
        if (!ontologyFields.getOrDefault(node.nodeType(), List.of()).contains(field))
            throw new IllegalArgumentException("Undeclared field " + node.nodeType() + "." + field);
        return switch (field) {
            case "label" -> Optional.of(node.label());
            case "summary" -> Optional.of(node.summary());
            case "entityId" -> Optional.of(node.entityId());
            default -> {
                if (!field.startsWith("attributes.") || field.length() <= "attributes.".length())
                    throw new IllegalArgumentException("Unsupported structural field: " + field);
                yield Optional.ofNullable(node.attributes().get(field.substring("attributes.".length())));
            }
        };
    }
}
