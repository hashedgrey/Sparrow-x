package com.sparrowx.agentic.planning;

import java.util.*;
import java.util.concurrent.CancellationException;

public final class GraphTraversal {

    public enum Status {
        COMPLETE,
        INSUFFICIENT,
        AMBIGUOUS,
        UNSUPPORTED,
        BUDGET_EXHAUSTED
    }

    public enum Direction {
        OUT,
        IN
    }

    public record Filter(String field, String value
    ) {
        public Filter {field = fieldName(field);value = text(value);}
    }

    public record Output(String variable, String field) {
        public Output {variable = text(variable);field = fieldName(field);
        }
    }

    public record Step(
            String fromVariable,
            String toVariable,
            String relationship,
            Direction direction,
            String targetType,
            List<Filter> filters
    ) {
        public Step {
            fromVariable = text(fromVariable);
            toVariable = text(toVariable);
            relationship = text(relationship);
            targetType = text(targetType);

            Objects.requireNonNull(direction, "direction is required");
            filters = List.copyOf(filters);
        }
    }

    public record Goal(
            List<Filter> rootFilters,
            List<Step> steps,
            List<Output> outputs
    ) {
        public Goal {
            rootFilters = List.copyOf(rootFilters);
            steps = List.copyOf(steps);
            outputs = List.copyOf(outputs);

            if (outputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "Requested outputs are required"
                );
            }

            Set<String> bound = new HashSet<>(Set.of("root"));

            for (Step step : steps) {
                if (!bound.contains(step.fromVariable())
                        || !bound.add(step.toVariable())) {
                    throw new IllegalArgumentException(
                            "Each step must introduce a unique variable "
                                    + "from an earlier variable"
                    );
                }
            }

            for (Output output : outputs) {
                if (!bound.contains(output.variable())) {
                    throw new IllegalArgumentException("Unknown output variable: " + output.variable());
                }
            }
        }
    }

    public record Node(
            String id,
            String type,
            String entityId,
            String label,
            String summary,
            Map<String, String> attributes
    ) {
        public Node {
            id = text(id);
            type = text(type);
            entityId = text(entityId);
            label = label == null ? "" : label;
            summary = summary == null ? "" : summary;
            attributes = Map.copyOf(attributes);
        }

        public String field(String name) {
            return switch (name) {
                case "label" -> label;
                case "summary" -> summary;
                case "entityId" -> entityId;
                default -> attributes.get(
                        name.substring("attributes.".length())
                );
            };
        }
    }

    public record Edge(
            String id,
            String from,
            String to,
            String type
    ) {
        public Edge {
            id = text(id);
            from = text(from);
            to = text(to);
            type = text(type);
        }
    }

    public record Slice(
            List<Node> nodes,
            List<Edge> edges,
            boolean truncated
    ) {
        public Slice {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    public record Limits(
            int maxHops,
            int maxSteps,
            int maxNodes,
            int maxEdges,
            int maxExpansions,
            int maxBindings,
            long maxTraversalMillis
    ) {
        public Limits {
            if (maxHops < 1
                    || maxSteps < 1
                    || maxNodes < 1
                    || maxEdges < 1
                    || maxExpansions < 1
                    || maxBindings < 1
                    || maxTraversalMillis < 1) {
                throw new IllegalArgumentException(
                        "All traversal limits must be positive"
                );
            }
        }
    }

    public record Result(
            Status status,
            String reason,
            List<Node> nodes,
            List<Edge> edges,
            List<Map<String, String>> answers,
            int expansions,
            int hops
    ) {
        public Result {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            answers = answers.stream().map(Map::copyOf).toList();
        }
    }

    @FunctionalInterface
    public interface Expander {
        Slice expand(
                String nodeId,
                String relationship,
                int maxEdges,
                int round
        );
    }

    @FunctionalInterface
    public interface Selector {
        Step select(
                List<Step> eligible,
                int completedSteps,
                int bindingCount
        );
    }

    private record Coverage(
            String nodeId,
            String relationship
    ) {
    }

    private record Binding(
            Map<String, String> variables,
            Set<String> edgeIds
    ) {
    }

    private static final class Halt extends RuntimeException {
        private final Status status;

        private Halt(Status status, String reason) {
            super(reason);
            this.status = status;
        }
    }

    private final Limits limits;

    public GraphTraversal(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public Result execute(
            Goal goal,
            Node root,
            Expander expander,
            Selector selector
    ) {
        return new Run(goal, root, expander, selector).execute();
    }

    private final class Run {

        private final Goal goal;
        private final Node root;
        private final Expander expander;
        private final Selector selector;

        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Edge> edges = new LinkedHashMap<>();
        private final Set<Coverage> covered = new HashSet<>();

        private final long started = System.nanoTime();

        private int expansions;
        private int hops;

        private Run(
                Goal goal,
                Node root,
                Expander expander,
                Selector selector
        ) {
            this.goal = Objects.requireNonNull(goal);
            this.root = Objects.requireNonNull(root);
            this.expander = Objects.requireNonNull(expander);
            this.selector = Objects.requireNonNull(selector);

            nodes.put(root.id(), root);
        }

        private Result execute() {
            try {
                check();

                if (goal.steps().size() > limits.maxSteps()) {
                    budget("Goal exceeds step budget");
                }

                Map<String, Integer> depths =
                        new HashMap<>(Map.of("root", 0));

                for (Step step : goal.steps()) {
                    int depth = depths.get(step.fromVariable()) + 1;

                    if (depth > limits.maxHops()) {
                        budget("Goal exceeds hop budget");
                    }

                    depths.put(step.toVariable(), depth);
                }

                if (!matches(root, goal.rootFilters())) {
                    insufficient("Root does not meet required filters");
                }

                List<Binding> rows = List.of(
                        new Binding(
                                Map.of("root", root.id()),
                                Set.of()
                        )
                );

                List<Step> remaining = new ArrayList<>(goal.steps());
                int completed = 0;

                while (!remaining.isEmpty()) {
                    check();

                    Set<String> bound =
                            rows.get(0).variables().keySet();

                    List<Step> eligible = remaining.stream()
                            .filter(step ->
                                    bound.contains(step.fromVariable()))
                            .toList();

                    Step step = eligible.size() == 1
                            ? eligible.get(0)
                            : selector.select(
                            eligible,
                            completed,
                            rows.size()
                    );

                    check();

                    if (!eligible.contains(step)) {
                        throw new IllegalStateException(
                                "Selector returned an ineligible step"
                        );
                    }

                    Set<String> frontier = new TreeSet<>();

                    for (Binding row : rows) {
                        frontier.add(
                                row.variables().get(step.fromVariable())
                        );
                    }

                    for (String nodeId : frontier) {
                        Coverage coverage = new Coverage(
                                nodeId,
                                step.relationship()
                        );

                        if (covered.contains(coverage)) {
                            continue;
                        }

                        check();

                        if (expansions >= limits.maxExpansions()) {
                            budget("Expansion round budget exhausted");
                        }

                        int allowance =
                                limits.maxEdges() - edges.size();

                        if (allowance <= 0) {
                            budget(
                                    "Edge budget exhausted before "
                                            + "adjacency was covered"
                            );
                        }

                        Slice slice = expander.expand(
                                nodeId,
                                step.relationship(),
                                allowance,
                                ++expansions
                        );

                        check();

                        if (slice.truncated()) {
                            budget(
                                    "IntSvc truncated the requested adjacency"
                            );
                        }

                        merge(
                                slice,
                                nodeId,
                                step.relationship(),
                                allowance
                        );

                        covered.add(coverage);
                    }

                    Set<Binding> next = new LinkedHashSet<>();

                    for (Binding row : rows) {
                        String source =
                                row.variables().get(step.fromVariable());

                        for (Edge edge : edges.values()) {
                            check();

                            if (!edge.type().equals(step.relationship())) {
                                continue;
                            }

                            String target =
                                    step.direction() == Direction.OUT
                                            ? edge.from().equals(source)
                                            ? edge.to()
                                            : null
                                            : edge.to().equals(source)
                                            ? edge.from()
                                            : null;

                            if (target == null) {
                                continue;
                            }

                            Node node = nodes.get(target);

                            if (!node.type().equals(step.targetType())
                                    || !matches(node, step.filters())) {
                                continue;
                            }

                            Map<String, String> variables =
                                    new LinkedHashMap<>(row.variables());

                            variables.put(step.toVariable(), target);

                            Set<String> proof =
                                    new LinkedHashSet<>(row.edgeIds());

                            proof.add(edge.id());

                            next.add(
                                    new Binding(
                                            Map.copyOf(variables),
                                            Set.copyOf(proof)
                                    )
                            );

                            if (next.size() > limits.maxBindings()) {
                                budget("Binding budget exhausted");
                            }
                        }
                    }

                    if (next.isEmpty()) {
                        insufficient(
                                "No complete match for variable "
                                        + step.toVariable()
                        );
                    }

                    rows = List.copyOf(next);
                    remaining.remove(step);

                    hops = Math.max(
                            hops,
                            depths.get(step.toVariable())
                    );

                    completed++;
                }

                Set<Map<String, String>> answers =
                        new LinkedHashSet<>();

                Set<String> selectedNodes = new LinkedHashSet<>();
                Set<String> selectedEdges = new LinkedHashSet<>();

                for (Binding row : rows) {
                    check();

                    Map<String, String> values =
                            new LinkedHashMap<>();

                    for (Output output : goal.outputs()) {
                        Node node = nodes.get(
                                row.variables().get(output.variable())
                        );

                        String value = node.field(output.field());

                        if (value == null || value.isBlank()) {
                            insufficient(
                                    "Missing output field "
                                            + output.variable()
                                            + "."
                                            + output.field()
                            );
                        }

                        values.put(
                                output.variable() + "." + output.field(),
                                value
                        );
                    }

                    answers.add(Map.copyOf(values));
                    selectedNodes.addAll(row.variables().values());
                    selectedEdges.addAll(row.edgeIds());
                }

                return new Result(
                        Status.COMPLETE,
                        "All graph requirements and output fields established",
                        selectedNodes.stream().map(nodes::get).toList(),
                        selectedEdges.stream().map(edges::get).toList(),
                        List.copyOf(answers),
                        expansions,
                        hops
                );

            } catch (Halt halt) {
                return new Result(
                        halt.status,
                        halt.getMessage(),
                        List.of(),
                        List.of(),
                        List.of(),
                        expansions,
                        hops
                );
            }
        }

        private void merge(
                Slice slice,
                String frontier,
                String relationship,
                int allowance
        ) {
            if (slice.edges().size() > allowance) {
                throw new IllegalStateException(
                        "IntSvc exceeded requested edge limit"
                );
            }

            Map<String, Node> incoming = new LinkedHashMap<>();

            for (Node node : slice.nodes()) {
                Node duplicate =
                        incoming.putIfAbsent(node.id(), node);

                if (duplicate != null) {
                    throw new IllegalStateException(
                            "Duplicate node in expansion response"
                    );
                }

                Node previous = nodes.get(node.id());

                if (previous != null && !previous.equals(node)) {
                    insufficient(
                            "Graph changed during traversal; retry the mission"
                    );
                }
            }

            if (!incoming.containsKey(frontier)) {
                throw new IllegalStateException(
                        "Expansion omitted frontier node"
                );
            }

            Set<String> edgeIds = new HashSet<>();
            Set<String> endpoints = new HashSet<>(Set.of(frontier));

            for (Edge edge : slice.edges()) {
                if (!edgeIds.add(edge.id())
                        || !edge.type().equals(relationship)
                        || !(edge.from().equals(frontier)
                        || edge.to().equals(frontier))
                        || !incoming.containsKey(edge.from())
                        || !incoming.containsKey(edge.to())) {

                    throw new IllegalStateException(
                            "Invalid expansion graph"
                    );
                }

                endpoints.add(edge.from());
                endpoints.add(edge.to());

                Edge previous = edges.get(edge.id());

                if (previous != null && !previous.equals(edge)) {
                    insufficient(
                            "Graph relationships changed during traversal"
                    );
                }
            }

            if (!endpoints.equals(incoming.keySet())) {
                throw new IllegalStateException(
                        "Expansion returned unrelated nodes"
                );
            }

            long newNodes = incoming.keySet().stream()
                    .filter(id -> !nodes.containsKey(id))
                    .count();

            if (nodes.size() + newNodes > limits.maxNodes()) {
                budget("Node budget exhausted");
            }

            nodes.putAll(incoming);

            for (Edge edge : slice.edges()) {
                edges.putIfAbsent(edge.id(), edge);
            }
        }

        private boolean matches(
                Node node,
                List<Filter> filters
        ) {
            boolean missing = false;

            for (Filter filter : filters) {
                String value = node.field(filter.field());

                if (value == null || value.isBlank()) {
                    missing = true;
                } else if (!value.trim().equalsIgnoreCase(
                        filter.value().trim()
                )) {
                    return false;
                }
            }

            if (missing) {
                insufficient(
                        "A candidate lacks a required filter field"
                );
            }

            return true;
        }

        private void check() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "Graph traversal interrupted"
                );
            }

            long elapsedMillis =
                    (System.nanoTime() - started) / 1_000_000;

            if (elapsedMillis >= limits.maxTraversalMillis()) {
                budget("Traversal time budget exhausted");
            }
        }
    }

    private static void budget(String reason) {
        throw new Halt(Status.BUDGET_EXHAUSTED, reason);
    }

    private static void insufficient(String reason) {
        throw new Halt(Status.INSUFFICIENT, reason);
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Nonblank value required"
            );
        }

        return value.trim();
    }

    private static String fieldName(String value) {
        value = text(value);

        if (!Set.of("label", "summary", "entityId").contains(value)
                && !value.matches(
                "attributes\\.[A-Za-z][A-Za-z0-9_]*"
        )) {
            throw new IllegalArgumentException(
                    "Unsupported graph field: " + value
            );
        }

        return value;
    }
}