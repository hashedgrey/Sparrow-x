package buildingblocks.contracts;

import java.util.*;

/** Portable, tenant-validated query contract anchored in verified root IDs. */
public record GraphQueryRequest(
        String rootType,
        Set<String> rootIds,
        List<Traverse> traversals,
        List<Predicate> predicates,
        List<Projection> projections,
        int maxRows,
        boolean requireComplete,
        String schemaVersion,
        List<FieldComparison> fieldComparisons,
        List<NegatedTraversal> negatedTraversals,
        Selection selection) {

    public enum Direction { IN, OUT }
    public enum Compare { EQ, NE, CONTAINS, IN, GT, GE, LT, LE }

    /** Source-compatible constructor for legacy non-versioned call sites. */
    public GraphQueryRequest(String rootType, Set<String> rootIds, List<Traverse> traversals,
                             List<Predicate> predicates, List<Projection> projections,
                             int maxRows, boolean requireComplete) {
        this(rootType, rootIds, traversals, predicates, projections, maxRows, requireComplete, "", List.of(), List.of(), null);
    }

    /** Existing ontology-versioned call sites remain source-compatible. */
    public GraphQueryRequest(String rootType, Set<String> rootIds, List<Traverse> traversals,
                             List<Predicate> predicates, List<Projection> projections,
                             int maxRows, boolean requireComplete, String schemaVersion) {
        this(rootType, rootIds, traversals, predicates, projections, maxRows,
                requireComplete, schemaVersion, List.of(), List.of(), null);
    }

    /** Existing field-comparison call sites remain source-compatible. */
    public GraphQueryRequest(String rootType, Set<String> rootIds, List<Traverse> traversals,
                             List<Predicate> predicates, List<Projection> projections,
                             int maxRows, boolean requireComplete, String schemaVersion,
                             List<FieldComparison> fieldComparisons) {
        this(rootType, rootIds, traversals, predicates, projections, maxRows,
                requireComplete, schemaVersion, fieldComparisons, List.of(), null);
    }

    /** Source-compatible constructor for existing ontology-query calls. */
    public GraphQueryRequest(String rootType, Set<String> rootIds, List<Traverse> traversals,
                             List<Predicate> predicates, List<Projection> projections,
                             int maxRows, boolean requireComplete, String schemaVersion,
                             List<FieldComparison> fieldComparisons,
                             List<NegatedTraversal> negatedTraversals) {
        this(rootType, rootIds, traversals, predicates, projections, maxRows,
                requireComplete, schemaVersion, fieldComparisons, negatedTraversals, null);
    }

    /** Alias-based selection, all aliases must be projected and validated by IntSvc. */
    public record Sort(String alias, boolean descending) {
        public Sort { text(alias); }
    }

    /**
     * onePerGroupBy selects at most one query row per distinct tuple of projected
     * aliases. random chooses candidate rows and selected groups at random.
     * limit is a user-requested top-N, distinct from maxRows (resource guard).
     * This is NOT a guarantee of unique values across unrelated columns.
     */
    public record Selection(List<String> onePerGroupBy, List<Sort> orderBy,
                            boolean random, int limit) {
        public Selection {
            onePerGroupBy = List.copyOf(Objects.requireNonNull(onePerGroupBy));
            orderBy = List.copyOf(Objects.requireNonNull(orderBy));
            onePerGroupBy.forEach(GraphQueryRequest::text);
            if (onePerGroupBy.size() > 8 || new HashSet<>(onePerGroupBy).size() != onePerGroupBy.size())
                throw new IllegalArgumentException("Invalid one-per-group alias set");
            if (orderBy.size() > 8 || (random && !orderBy.isEmpty()))
                throw new IllegalArgumentException("Invalid sort specification");
            if (limit < 0 || limit > 10000)
                throw new IllegalArgumentException("Requested limit must be 0..10000");
        }
    }

    /** One-hop NOT EXISTS on an already bound source variable. Does not bind a target. */
    public record NegatedTraversal(String source, String relationshipType,
                                   Direction direction, String targetType) {
        public NegatedTraversal {
            text(source); text(relationshipType); Objects.requireNonNull(direction); text(targetType);
        }
    }

    /** Reuse a previously bound target variable for a join to that exact node. */
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

    public record FieldComparison(String leftVariable, String leftField, Compare compare,
                                  String rightVariable, String rightField) {
        public FieldComparison {
            text(leftVariable); text(leftField); Objects.requireNonNull(compare);
            if (compare != Compare.EQ && compare != Compare.NE)
                throw new IllegalArgumentException("Field comparison requires EQ or NE");
            text(rightVariable); text(rightField);
        }
    }

    public GraphQueryRequest {
        text(rootType);
        rootIds = Set.copyOf(Objects.requireNonNull(rootIds));
        if (rootIds.isEmpty() || rootIds.size() > 1000)
            throw new IllegalArgumentException("Resolve 1..1000 roots");
        rootIds.forEach(GraphQueryRequest::text);
        traversals = List.copyOf(Objects.requireNonNull(traversals));
        predicates = List.copyOf(Objects.requireNonNull(predicates));
        projections = List.copyOf(Objects.requireNonNull(projections));
        fieldComparisons = List.copyOf(Objects.requireNonNull(fieldComparisons, "fieldComparisons"));
        negatedTraversals = List.copyOf(Objects.requireNonNull(negatedTraversals, "negatedTraversals"));
        if (traversals.size() + negatedTraversals.size() > 16 || predicates.size() + fieldComparisons.size() > 64
                || projections.isEmpty() || projections.size() > 64)
            throw new IllegalArgumentException("Query exceeds shape bounds");
        if (maxRows < 1 || maxRows > 10000)
            throw new IllegalArgumentException("maxRows must be 1..10000");
        if (selection != null && selection.limit() > maxRows)
            throw new IllegalArgumentException("Requested limit exceeds resource row budget");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion.length() > 256)
            throw new IllegalArgumentException("Invalid graph schema version");
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
