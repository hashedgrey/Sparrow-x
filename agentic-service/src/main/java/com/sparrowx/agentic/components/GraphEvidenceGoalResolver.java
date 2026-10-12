package com.sparrowx.agentic.components;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.*;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.planning.graph.GraphSchemaCatalog;
import com.sparrowx.agentic.planning.graph.GraphRootScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts a small semantic selection into a validated executable goal.
 * Relationship syntax, directions, variable bindings and allowed fields are CODE,
 * derived from the current tenant's IntSvc ontology.
 */
@Component
public final class GraphEvidenceGoalResolver {
    private static final Logger LOG = LoggerFactory.getLogger(GraphEvidenceGoalResolver.class);
    // No instructions describing schema rules or particular business relationships.
    private static final String TASK = """
            Select the required graph paths, output fields, predicates, and query operators.
            The root entities have already been selected and verified by an upstream
            search; the executor anchors this query using their exact root IDs.
            Do not add root label or entityId equality filters just to identify
            those already-selected roots. Their names and IDs are root-search
            constraints, not graph relationship predicates.
            Preserve additional user-requested constraints on root or joined nodes.
            Explicit symbolic values in the question (for example, STAFF_ENGINEER)
            must be enforced by the selected graph relationship or an equality
            filter, rather than merely projected, described, or ignored.
            When filtering on a symbolic value, project the filtered field as
            evidence of the constraint when that field is declared by the schema.
            Never remove a genuine additional predicate to make a query executable.
            """;

    private final SemanticDecisionService semantic;
    private final GraphOntologyProvider ontologyProvider;
    private final ObjectMapper mapper;

    @Value("${sparrowx.graph.goal.max-schema-hops:6}")
    private int maxSchemaHops = 6;

    @Value("${sparrowx.graph.goal.max-schema-paths:384}")
    private int maxSchemaPaths = 384;

    public GraphEvidenceGoalResolver(
            SemanticDecisionService semantic,
            ObjectMapper mapper,
            GraphOntologyProvider ontologyProvider
    ) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ontologyProvider = Objects.requireNonNull(ontologyProvider, "ontologyProvider");
    }

    /** Compile a reusable logical goal for the selected root scope. */
    public Resolution compile(MissionContext context, MissionIntent intent,
                              String query, GraphRootScope roots, String operationId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(roots, "roots");
        requireText(query, "query");
        requireText(operationId, "operationId");
        String rootType = roots.nodeType();

        long started = System.nanoTime();
        Ontology ontology = Objects.requireNonNull(ontologyProvider.describe(context), "ontology");
        LOG.info("graph.goal.ontology operation={} elapsedMs={}", operationId, elapsed(started));
        if (!ontology.fields().containsKey(rootType)) {
            return new Resolution(GraphResolutionStatus.UNSUPPORTED, "Graph schema does not declare the root type", null);
        }

        final Map<String, GraphSchemaCatalog.PathOption> paths;
        try {
            paths = catalog(rootType, ontology);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warn("Graph schema options unavailable: operation={} reason={}", operationId, e.getMessage());
            return new Resolution(GraphResolutionStatus.UNSUPPORTED, "Schema-path enumeration: " + safe(e), null);
        }

        // Prefer a bounded System-1 path choice before serializing a potentially
        // large schema catalogue into an LLM request.
        // Small bounded questions can be compiled from a single schema path.
        // When not losslessly representable, use the complete structured LLM schema.
        Optional<Resolution> bounded = trySimpleGoal(query, rootType, roots, paths, ontology, operationId);
        if (bounded.isPresent()) return bounded.get();

        // Ontology metadata is immutable for this compilation; model can reference only
        // path IDs and field names supplied here, never arbitrary graph edges.
        List<Map<String, Object>> options = new ArrayList<>();
        paths.values().stream().sorted(Comparator.comparing(GraphSchemaCatalog.PathOption::id)).forEach(path -> {
            List<Map<String, Object>> hops = new ArrayList<>();
            hops.add(Map.of("hop", 0, "type", rootType, "fields", ontology.fields().get(rootType)));
            int i = 0;
            for (GraphSchemaCatalog.Hop hop : path.hops()) {
                ++i;
                hops.add(Map.of(
                        "hop", i,
                        "type", hop.toType(),
                        "via", hop.relation(),
                        "direction", hop.direction().name(),
                        "fields", ontology.fields().get(hop.toType())
                ));
            }
            options.add(Map.of("pathId", path.id(), "hops", List.copyOf(hops)));
        });

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("rootType", rootType);
        input.put("selectedRootCount", roots.entityIds().size());
        // Root labels belong to the upstream typed search result, not goal selection.
        // A shared query is compiled only once for all roots of this type.
        input.put("pathOptions", List.copyOf(options));

        // No raw relationship generation; no model-authored executable graph steps.
        long llmStarted = System.nanoTime();
        LlmDecisionEngine.Result response = semantic.escalate(
                new LlmDecisionEngine.Request(
                        intent.missionId(), operationId, operationId,
                        TASK, Map.copyOf(input),
                        Set.of("status", "reason", "paths", "operators"),
                        outputSchema(paths.keySet(), ontology),
                        2000, 0.0,
                        Map.of("component", "schema-bound-graph-goal")
                )
        );

        LOG.info("graph.goal.llm.complete operation={} llmElapsedMs={} totalElapsedMs={}",
                operationId, elapsed(llmStarted), elapsed(started));
        Map<String, Object> output = response.output();
        if (output == null) return new Resolution(GraphResolutionStatus.UNSUPPORTED, "No structured goal selection", null);
        String status = Objects.toString(output.get("status"), "");
        String reason = Objects.toString(output.get("reason"), "");
        if ("AMBIGUOUS".equals(status)) return new Resolution(GraphResolutionStatus.AMBIGUOUS, reason, null);
        if ("UNSUPPORTED".equals(status)) return new Resolution(GraphResolutionStatus.UNSUPPORTED, reason, null);
        if (!"READY".equals(status)) return new Resolution(GraphResolutionStatus.UNSUPPORTED, "Invalid goal selection status", null);

        try {
            // An unsupported semantic operation is never dropped to force a READY graph.
            requireSupported(readOperators(output.get("operators")));
            GraphQueryRequest request = bind(rootType, ontology.fields(), paths,
                    readPaths(output.get("paths")), roots, query);
            LOG.info("graph.goal.bound operation={} roots={} traversals={} predicates={} projections={} totalElapsedMs={}",
                    operationId, request.rootIds().size(), request.traversals().size(),
                    request.predicates().size(), request.projections().size(), elapsed(started));
            return new Resolution(GraphResolutionStatus.COMPLETE, reason, request);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException | UnsupportedOperationException e) {
            LOG.warn("Graph binding rejected: operation={} rootType={} reason={}",
                    operationId, rootType, e.getMessage(), e);
            return new Resolution(GraphResolutionStatus.UNSUPPORTED, "Graph binding rejected: " + safe(e), null);
        }
    }


    /**
     * Jev only picks from ontology-enumerated one-hop paths. This is safe to
     * compile only when every requested fact is a terminal node's display name
     * and the query requires no predicates, aggregation or second branch.
     */
    private Optional<Resolution> trySimpleGoal(
            String query, String rootType, GraphRootScope roots,
            Map<String, GraphSchemaCatalog.PathOption> paths,
            Ontology ontology, String operationId
    ) {
        String normalized = query.strip();
        if (!normalized.matches("(?is)^(who|what|which)\\b.*\\?$"))
            return Optional.empty();
        if (normalized.matches("(?is).*\\b(and|or|without|except|whose|with|that|where|count|number|how many|average|between|not|more|less|all|each|every)\\b.*"))
            return Optional.empty();

        record Candidate(GraphSchemaCatalog.PathOption path, String field) {}
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (var path : paths.values()) {
            if (path.hops().size() != 1) continue;
            var hop = path.hops().getFirst();
            List<String> fieldNames = ontology.fields().getOrDefault(hop.toType(), List.of());
            String field = List.of("label", "name", "displayName").stream()
                    .filter(fieldNames::contains).findFirst().orElse(null);
            if (field == null) continue;
            String key = "p" + candidates.size();
            candidates.put(key, new Candidate(path, field));
            labels.put(key, hop.relation() + " (" + hop.direction() + ") -> "
                    + hop.toType() + "." + field);
            if (candidates.size() > 32) return Optional.empty();
        }
        if (candidates.isEmpty()) return Optional.empty();

        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        questions.put("simple", NoulQuestion.of("""
                Can this entire question be answered by projecting ONLY the name
                of the node at the end of ONE directly connected schema relationship?
                Answer false if any constraint/filter, intermediate output, count,
                multi-hop, multiple branch, comparison or additional fact is needed.
                Answer false if the requested output is not a node name.
                """));
        questions.put("path", new ChoiceQuestion("""
                Which SINGLE schema relationship answers the question?
                Choose only an option supplied, based on relation meaning and direction.
                """, labels));
        var state = Map.<String, Object>of(
                "query", query, "rootType", rootType,
                "selectedRootCount", roots.entityIds().size(),
                "allowedPathChoices", labels);

        long started = System.nanoTime();
        Optional<DecisionResponse> response = semantic.tryBounded(
                state, questions, 0.85d, operationId + ":single-hop");
        if (response.isEmpty()) return Optional.empty();
        if (response.get().noulValue("simple") < 0.85d) return Optional.empty();
        Candidate selected = candidates.get(response.get().choiceValue("path"));
        if (selected == null) return Optional.empty();
        try {
            var chosen = new PathChoice(selected.path().id(),
                    List.of(new FieldChoice(1, selected.field())), List.of());
            GraphQueryRequest request = bind(rootType, ontology.fields(), paths,
                    List.of(chosen), roots, query);
            LOG.info("graph.goal.system1.selected operation={} path={} elapsedMs={}",
                    operationId, selected.path().id(), elapsed(started));
            return Optional.of(new Resolution(GraphResolutionStatus.COMPLETE,
                    "Schema-bound single-hop selection", request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            LOG.warn("graph.goal.system1.bindingRejected operation={} type={}",
                    operationId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static long elapsed(long start) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Enumerate ONLY executable tenant-schema paths, including zero-hop root. */
    private Map<String, GraphSchemaCatalog.PathOption> catalog(String rootType, Ontology ontology) {
        GraphSchemaCatalog schema = new GraphSchemaCatalog(
                ontology.relations().stream()
                        .map(r -> new GraphSchemaCatalog.Relation(r.source(), r.type(), r.target()))
                        .toList(),
                ontology.fields().keySet()
        );
        Map<String, GraphSchemaCatalog.PathOption> paths = new LinkedHashMap<>();
        for (String target : ontology.fields().keySet().stream().sorted().toList()) {
            GraphSchemaCatalog.Options found = schema.paths(
                    rootType, target, maxSchemaHops, maxSchemaPaths);
            if (!found.exhaustive()) throw new IllegalStateException("Tenant schema paths exceeded configured budget");
            for (GraphSchemaCatalog.PathOption path : found.paths()) {
                paths.putIfAbsent(path.id(), path);
                if (paths.size() > maxSchemaPaths)
                    throw new IllegalStateException("Tenant schema path option budget exhausted");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(paths));
    }

    private static void requireSupported(List<String> operators) {
        Set<String> supported = Set.of("PROJECT", "TRAVERSE", "EQ", "AND");
        for (String operator : operators) {
            if (!supported.contains(operator))
                throw new UnsupportedOperationException("IntSvc query planner does not support " + operator);
        }
    }
    private static List<String> readOperators(Object value) {
        return checkedList(value).stream().map(GraphEvidenceGoalResolver::str).toList();
    }
    private record FieldChoice(int hop, String field) { }
    private record EqualityChoice(int hop, String field, String value) { }
    private record PathChoice(String pathId, List<FieldChoice> outputs, List<EqualityChoice> filters) { }
    private static List<PathChoice> readPaths(Object value) {
        List<PathChoice> choices = new ArrayList<>();
        for (Object entry : checkedList(value)) {
            Map<String, ?> path = checkedMap(entry);
            List<FieldChoice> outputs = new ArrayList<>();
            for (Object item : checkedList(path.get("outputs"))) {
                Map<String, ?> field = checkedMap(item);
                outputs.add(new FieldChoice(number(field.get("hop")), str(field.get("field"))));
            }
            List<EqualityChoice> filters = new ArrayList<>();
            for (Object item : checkedList(path.get("filters"))) {
                Map<String, ?> predicate = checkedMap(item);
                filters.add(new EqualityChoice(number(predicate.get("hop")),
                        str(predicate.get("field")), str(predicate.get("value"))));
            }
            choices.add(new PathChoice(str(path.get("pathId")), outputs, filters));
        }
        return choices;
    }

    private record Branch(String variable, String parent, GraphSchemaCatalog.Hop hop) { }
    private static GraphQueryRequest bind(String rootType, Map<String, List<String>> fieldsByType,
                                          Map<String, GraphSchemaCatalog.PathOption> catalog, List<PathChoice> choices,
                                          GraphRootScope roots, String originalQuery) {
        if (choices.isEmpty()) throw new IllegalArgumentException("No path selected");
        Map<List<GraphSchemaCatalog.Hop>, Branch> prefixes = new LinkedHashMap<>();
        Map<String, String> filters = new LinkedHashMap<>();
        Map<String, GraphQueryRequest.Projection> outputs = new LinkedHashMap<>();
        Set<String> requiredSymbols = explicitSymbolicLiterals(originalQuery);
        int nextVariable = 0;
        for (PathChoice selected : choices) {
            GraphSchemaCatalog.PathOption path = catalog.get(selected.pathId());
            if (path == null || !path.fromType().equals(rootType))
                throw new IllegalArgumentException("Unknown selected schema path");
            List<String> variables = new ArrayList<>(List.of("root"));
            List<String> types = new ArrayList<>(List.of(rootType));
            for (int i = 0; i < path.hops().size(); i++) {
                var hop = path.hops().get(i);
                if (!hop.fromType().equals(types.getLast()))
                    throw new IllegalArgumentException("Inconsistent path types");
                var prefix = List.copyOf(path.hops().subList(0, i + 1));
                Branch branch = prefixes.get(prefix);
                if (branch == null) {
                    branch = new Branch("v" + (++nextVariable), variables.getLast(), hop);
                    prefixes.put(prefix, branch);
                }
                variables.add(branch.variable());
                types.add(hop.toType());
            }
            for (FieldChoice output : selected.outputs()) {
                checkField(types, output.hop(), output.field(), fieldsByType);
                String variable = variables.get(output.hop());
                String key = variable + ":" + output.field();
                outputs.putIfAbsent(key, new GraphQueryRequest.Projection(variable, output.field(), key));
            }
            for (EqualityChoice filter : selected.filters()) {
                checkField(types, filter.hop(), filter.field(), fieldsByType);
                // The verified root scope is ALWAYS enforced by GraphQueryRequest.rootIds.
                // Additional equality filters are executable predicates, including
                // root.label and root.entityId; never silently drop them. An exact
                // root entityId predicate must not refer to an unverified root.
                if (filter.hop() == 0 && "entityId".equals(filter.field())
                        && !roots.entityIds().contains(filter.value())) {
                    throw new IllegalArgumentException(
                            "Root entityId equality conflicts with verified root scope");
                }
                String variable = variables.get(filter.hop());
                String key = variable + ":" + filter.field();
                String prior = filters.putIfAbsent(key, filter.value());
                if (prior != null && !prior.equals(filter.value()))
                    throw new IllegalArgumentException("Conflicting equality filters");
                // Preserve proof of a code-like constraint in the validated rows
                // so downstream synthesis does not claim the role was unavailable.
                if (requiredSymbols.contains(filter.value())) {
                    outputs.putIfAbsent(key, new GraphQueryRequest.Projection(
                            variable, filter.field(), key));
                }
            }
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("A query needs an output field");
        List<GraphQueryRequest.Traverse> traversals = prefixes.values().stream().map(branch ->
                new GraphQueryRequest.Traverse(branch.parent(), "edge_" + branch.variable(),
                        branch.hop().relation(),
                        branch.hop().direction() == GraphSchemaCatalog.Direction.OUT
                                ? GraphQueryRequest.Direction.OUT : GraphQueryRequest.Direction.IN,
                        branch.variable(), branch.hop().toType())).toList();
        List<GraphQueryRequest.Predicate> predicates = filters.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split(":", 2);
            return new GraphQueryRequest.Predicate(parts[0], parts[1], GraphQueryRequest.Compare.EQ,
                    entry.getValue());
        }).toList();
        GraphQueryRequest result = new GraphQueryRequest(rootType, roots.entityIds(),
                traversals, predicates, List.copyOf(outputs.values()), 500, true);
        requireSymbolicConstraints(requiredSymbols, result);
        return result;
    }

    // Only check explicit all-caps identifiers in user text, not ordinary prose.
    // This is a fail-closed guard for roles, states and other symbolic enum-like
    // values. It does not replace semantic validation of natural-language constraints.
    private static Set<String> explicitSymbolicLiterals(String query) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?<![A-Za-z0-9_])([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)(?![A-Za-z0-9_])")
                .matcher(Objects.requireNonNull(query, "query"));
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) result.add(matcher.group(1));
        return Set.copyOf(result);
    }

    private static void requireSymbolicConstraints(
            Set<String> symbols, GraphQueryRequest request) {
        for (String symbol : symbols) {
            boolean predicateBound = request.predicates().stream().anyMatch(predicate ->
                    predicate.compare() == GraphQueryRequest.Compare.EQ
                            && symbol.equals(predicate.value()));
            boolean schemaBound = symbol.equals(request.rootType())
                    || request.traversals().stream().anyMatch(traversal ->
                    symbol.equals(traversal.relationshipType())
                            || symbol.equals(traversal.targetType()));
            if (!predicateBound && !schemaBound) {
                throw new IllegalArgumentException(
                        "Explicit requested constraint missing from graph query: " + symbol);
            }
        }
    }

    private static void checkField(List<String> types, int hop, String field,
                                   Map<String, List<String>> fieldsByType) {
        if (hop < 0 || hop >= types.size() || !fieldsByType.getOrDefault(types.get(hop), List.of()).contains(field))
            throw new IllegalArgumentException("Undeclared field or path hop: " + field);
    }
    private static Map<String, ?> checkedMap(Object item) {
        if (!(item instanceof Map<?,?> values)) throw new IllegalArgumentException("Expected object selection");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var pair : values.entrySet()) {
            if (!(pair.getKey() instanceof String key)) throw new IllegalArgumentException("Nonstring selection key");
            result.put(key, pair.getValue());
        }
        return result;
    }
    private static List<?> checkedList(Object item) {
        if (!(item instanceof List<?> list)) throw new IllegalArgumentException("Expected array selection");
        return list;
    }
    private static int number(Object item) {
        if (!(item instanceof Number n) || n.intValue() != n.doubleValue())
            throw new IllegalArgumentException("Hop index must be an integer");
        return n.intValue();
    }
    private static String str(Object item) {
        if (!(item instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("Expected nonempty string");
        return value;
    }
    private static String safe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "Invalid typed graph selection";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static Map<String, Object> outputSchema(Set<String> pathIds, Ontology ontology) {
        Map<String, Object> hop = Map.of("type", "integer", "minimum", 0);
        List<String> fields = ontology.fields().values().stream().flatMap(List::stream).distinct().sorted().toList();
        Map<String, Object> fieldChoice = obj(Map.of(
                "hop", hop, "field", enumText(fields)));
        Map<String, Object> equality = obj(Map.of(
                "hop", hop, "field", enumText(fields), "value", Map.of("type", "string")));
        Map<String, Object> path = obj(Map.of(
                "pathId", enumText(pathIds.stream().sorted().toList()),
                "outputs", arr(fieldChoice), "filters", arr(equality)));
        return obj(Map.of(
                "status", enumText(List.of("READY", "AMBIGUOUS", "UNSUPPORTED")),
                "reason", Map.of("type", "string"),
                "paths", arr(path),
                "operators", arr(enumText(List.of("PROJECT", "TRAVERSE", "EQ", "AND", "NOT_EXISTS", "GROUP_BY", "COUNT",
                        "OR", "OPTIONAL", "ORDER_BY", "LIMIT")))
        ));
    }
    private static Map<String, Object> enumText(List<String> allowed) {
        return Map.of("type", "string", "enum", allowed);
    }
    private static Map<String, Object> obj(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties,
                "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }
    private static Map<String, Object> arr(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    public record Relation(String source, String type, String target) {
        public Relation { requireText(source,"source"); requireText(type,"type"); requireText(target,"target"); }
    }
    public record Ontology(List<Relation> relations,
                           Map<String, List<String>> fields,
                           Map<String, List<String>> values) {
        public Ontology {
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            fields = immutableLists(Objects.requireNonNull(fields,"fields"));
            values = immutableLists(Objects.requireNonNull(values,"values"));
        }
        private static Map<String,List<String>> immutableLists(Map<String,List<String>> source) {
            Map<String,List<String>> values = new LinkedHashMap<>();
            source.forEach((key,list) -> values.put(key, List.copyOf(list)));
            return Map.copyOf(values);
        }
    }
    public record Resolution(GraphResolutionStatus status, String reason, GraphQueryRequest query) {
        public boolean ready() { return status == GraphResolutionStatus.COMPLETE && query != null; }
    }
}

