package buildingblocks.contracts;

import java.util.*;

/** Portable query contract. All patterns are anchored in an explicit resolved root set. */
public record GraphQueryRequest(String rootType, Set<String> rootIds, List<Traverse> traversals,
                                List<Predicate> predicates, List<Projection> projections,
                                int maxRows, boolean requireComplete) {
    public enum Direction { IN, OUT }
    public enum Compare { EQ, NE, CONTAINS, IN, GT, GE, LT, LE }

    /** Reuse an existing target variable to require a join to that exact node. */
    public record Traverse(String source, String relationshipVariable, String relationshipType,
                           Direction direction, String target, String targetType) {
        public Traverse {
            text(source); text(relationshipVariable); text(relationshipType);
            Objects.requireNonNull(direction); text(target); text(targetType);
        }
    }
    public record Predicate(String variable, String field, Compare compare, Object value) {
        public Predicate {
            text(variable); text(field); Objects.requireNonNull(compare);
            if (compare == Compare.IN) {
                if (!(value instanceof List<?> list) || list.size() > 1000)
                    throw new IllegalArgumentException("IN requires a bounded list");
                value = list.stream().map(GraphQueryRequest::scalar).toList();
            } else {
                value = scalar(value);
                if (compare == Compare.CONTAINS && !(value instanceof String))
                    throw new IllegalArgumentException("CONTAINS requires a string");
            }
        }
    }
    public record Projection(String variable, String field, String alias) {
        public Projection { text(variable); text(field); text(alias); }
    }
    public GraphQueryRequest {
        text(rootType);
        rootIds = Set.copyOf(rootIds);
        if (rootIds.isEmpty() || rootIds.size() > 1000) throw new IllegalArgumentException("Resolve 1..1000 roots");
        rootIds.forEach(GraphQueryRequest::text);
        traversals = List.copyOf(traversals);
        predicates = List.copyOf(predicates);
        projections = List.copyOf(projections);
        if (traversals.size() > 16 || predicates.size() > 64 || projections.isEmpty() || projections.size() > 64)
            throw new IllegalArgumentException("Query exceeds shape bounds");
        if (maxRows < 1 || maxRows > 10000) throw new IllegalArgumentException("maxRows must be 1..10000");
    }
    private static Object scalar(Object value) {
        if (value instanceof String s && s.length() <= 16000) return s;
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) return value;
        if (value instanceof Double d && Double.isFinite(d)) return value;
        throw new IllegalArgumentException("Expected string, boolean, integer or finite double");
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 512)
            throw new IllegalArgumentException("Invalid query identifier");
    }
}
