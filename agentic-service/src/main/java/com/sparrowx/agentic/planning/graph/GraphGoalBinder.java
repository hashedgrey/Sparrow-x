package com.sparrowx.agentic.planning.graph;

import com.sparrowx.agentic.planning.GraphTraversal;
import com.sparrowx.agentic.planning.GraphTraversal.*;

import java.util.*;

/**
 * Deterministic binding of selected SCHEMA PATH IDS to executable GraphTraversal.Goal.
 * The semantic model chooses IDs, fields, and equality values; it cannot create edges.
 * Rejects missing paths, wrong fields, contradictory filters, and unbound variables.
 */
public final class GraphGoalBinder {

    public record FieldChoice(int hop, String field) { }
    public record EqualityChoice(int hop, String field, String value) { }
    public record PathChoice(String pathId,
                             List<FieldChoice> outputs,
                             List<EqualityChoice> filters) {
        public PathChoice {
            Objects.requireNonNull(pathId, "pathId");
            outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        }
    }

    /** The returned model selection never contains raw edge names or traversal directions. */
    public record Choice(List<PathChoice> paths) {
        public Choice { paths = List.copyOf(Objects.requireNonNull(paths, "paths")); }
    }

    private record Branch(String variable, String parent, GraphSchemaCatalog.Hop hop) { }

    private GraphGoalBinder() { }

    public static Goal compile(
            String rootType,
            Map<String, List<String>> fieldsByType,
            Map<String, GraphSchemaCatalog.PathOption> catalog,
            Choice choice
    ) {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(fieldsByType, "fieldsByType");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(choice, "choice");
        if (choice.paths().isEmpty()) {
            throw new IllegalArgumentException("No graph path selected");
        }

        // A prefix trie shares identical schema paths across requested outputs.
        // Every prefix variable is bound exactly once, in parent-before-child order.
        Map<List<GraphSchemaCatalog.Hop>, Branch> prefixes = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, String>> predicates = new LinkedHashMap<>();
        LinkedHashMap<String, Output> projected = new LinkedHashMap<>();
        int nextVariable = 0;
        for (PathChoice selected : choice.paths()) {
            GraphSchemaCatalog.PathOption path = catalog.get(selected.pathId());
            if (path == null || !path.fromType().equals(rootType)) {
                throw new IllegalArgumentException("Unknown or inapplicable schema path ID: " + selected.pathId());
            }
            List<String> variables = new ArrayList<>(List.of("root"));
            List<String> types = new ArrayList<>(List.of(rootType));
            for (int i = 0; i < path.hops().size(); i++) {
                GraphSchemaCatalog.Hop hop = path.hops().get(i);
                String preceding = types.getLast();
                if (!hop.fromType().equals(preceding)) {
                    throw new IllegalArgumentException("Catalog path has inconsistent node types");
                }
                List<GraphSchemaCatalog.Hop> key = List.copyOf(path.hops().subList(0, i + 1));
                Branch branch = prefixes.get(key);
                if (branch == null) {
                    branch = new Branch("v" + (++nextVariable), variables.getLast(), hop);
                    prefixes.put(key, branch);
                }
                variables.add(branch.variable());
                types.add(hop.toType());
            }
            for (FieldChoice output : selected.outputs()) {
                int hop = output.hop();
                checkHop(hop, types.size());
                validateField(types.get(hop), output.field(), fieldsByType);
                Output result = new Output(variables.get(hop), output.field());
                projected.putIfAbsent(result.variable() + ":" + result.field(), result);
            }
            for (EqualityChoice filter : selected.filters()) {
                int hop = filter.hop();
                checkHop(hop, types.size());
                validateField(types.get(hop), filter.field(), fieldsByType);
                if (filter.value() == null || filter.value().isBlank()) {
                    throw new IllegalArgumentException("Equality filter value cannot be blank");
                }
                LinkedHashMap<String, String> variableFilters = predicates.computeIfAbsent(
                        variables.get(hop), ignored -> new LinkedHashMap<>());
                String previous = variableFilters.putIfAbsent(filter.field(), filter.value());
                if (previous != null && !previous.equalsIgnoreCase(filter.value())) {
                    throw new IllegalArgumentException("Conflicting filters for the same bound field");
                }
            }
        }
        if (projected.isEmpty()) {
            throw new IllegalArgumentException("Every graph goal must project at least one requested field");
        }
        List<Step> steps = new ArrayList<>();
        for (Branch b : prefixes.values()) {
            steps.add(new Step(
                    b.parent(), b.variable(), b.hop().relation(),
                    b.hop().direction() == GraphSchemaCatalog.Direction.OUT
                            ? Direction.OUT : Direction.IN,
                    b.hop().toType(), toFilters(predicates.get(b.variable()))
            ));
        }
        return new Goal(toFilters(predicates.get("root")), steps,
                List.copyOf(projected.values()));
    }

    private static List<Filter> toFilters(Map<String, String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.entrySet().stream()
                .map(entry -> new Filter(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void validateField(String nodeType, String field,
                                      Map<String, List<String>> fieldsByType) {
        List<String> fields = fieldsByType.get(nodeType);
        if (fields == null || !fields.contains(field)) {
            throw new IllegalArgumentException("Field is not declared for " + nodeType + ": " + field);
        }
    }

    private static void checkHop(int index, int size) {
        if (index < 0 || index >= size)
            throw new IllegalArgumentException("Output/filter references a missing path hop: " + index);
    }
}
