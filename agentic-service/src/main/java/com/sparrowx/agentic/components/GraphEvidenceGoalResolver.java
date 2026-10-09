package com.sparrowx.agentic.components;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.GraphTraversal.*;
import com.sparrowx.agentic.planning.graph.GraphGoalBinder;
import com.sparrowx.agentic.planning.graph.GraphQueryCapabilities;
import com.sparrowx.agentic.planning.graph.GraphSchemaCatalog;
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
    private static final String TASK =
            "Select the required graph paths, output fields, predicates, and query operators.";

    private final LlmDecisionEngine llm;
    private final GraphOntologyProvider ontologyProvider;
    private final ObjectMapper mapper;

    @Value("${sparrowx.graph.goal.max-schema-hops:6}")
    private int maxSchemaHops = 6;

    @Value("${sparrowx.graph.goal.max-schema-paths:384}")
    private int maxSchemaPaths = 384;

    public GraphEvidenceGoalResolver(
            LlmDecisionEngine llm,
            ObjectMapper mapper,
            GraphOntologyProvider ontologyProvider
    ) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ontologyProvider = Objects.requireNonNull(ontologyProvider, "ontologyProvider");
    }

    public Resolution resolve(MissionContext context, MissionIntent intent,
                              String query, Node root, String operationId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(root, "root");
        requireText(query, "query");
        requireText(operationId, "operationId");

        Ontology ontology = Objects.requireNonNull(ontologyProvider.describe(context), "ontology");
        if (!ontology.fields().containsKey(root.type())) {
            return new Resolution(Status.UNSUPPORTED, "Graph schema does not declare the root type", null);
        }

        final Map<String, GraphSchemaCatalog.PathOption> paths;
        try {
            paths = catalog(root.type(), ontology);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warn("Graph schema options unavailable: operation={} reason={}", operationId, e.getMessage());
            return new Resolution(Status.UNSUPPORTED, "Schema-path enumeration: " + safe(e), null);
        }

        // Ontology metadata is immutable for this compilation; model can reference only
        // path IDs and field names supplied here, never arbitrary graph edges.
        List<Map<String, Object>> options = new ArrayList<>();
        paths.values().stream().sorted(Comparator.comparing(GraphSchemaCatalog.PathOption::id)).forEach(path -> {
            List<Map<String, Object>> hops = new ArrayList<>();
            hops.add(Map.of("hop", 0, "type", root.type(), "fields", ontology.fields().get(root.type())));
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
        input.put("rootType", root.type());
        input.put("rootLabel", root.label());
        input.put("pathOptions", List.copyOf(options));
        // No raw relationship generation; no model-authored executable graph steps.
        LlmDecisionEngine.Result response = llm.evaluate(
                new LlmDecisionEngine.Request(
                        intent.missionId(), operationId, operationId,
                        TASK, Map.copyOf(input),
                        Set.of("status", "reason", "paths", "operators"),
                        outputSchema(paths.keySet(), ontology),
                        2000, 0.0,
                        Map.of("component", "schema-bound-graph-goal")
                )
        );

        Map<String, Object> output = response.output();
        if (output == null) return new Resolution(Status.UNSUPPORTED, "No structured goal selection", null);
        String status = Objects.toString(output.get("status"), "");
        String reason = Objects.toString(output.get("reason"), "");
        if ("AMBIGUOUS".equals(status)) return new Resolution(Status.AMBIGUOUS, reason, null);
        if ("UNSUPPORTED".equals(status)) return new Resolution(Status.UNSUPPORTED, reason, null);
        if (!"READY".equals(status)) return new Resolution(Status.UNSUPPORTED, "Invalid goal selection status", null);

        try {
            // An unsupported semantic operation is never dropped to force a READY graph.
            GraphQueryCapabilities.requireSupported(readOperators(output.get("operators")));
            GraphGoalBinder.Choice choice = new GraphGoalBinder.Choice(readPaths(output.get("paths")));
            Goal goal = GraphGoalBinder.compile(root.type(), ontology.fields(), paths, choice);
            return new Resolution(Status.COMPLETE, reason, goal);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException | UnsupportedOperationException e) {
            LOG.warn("Graph binding rejected: operation={} rootType={} reason={}",
                    operationId, root.type(), e.getMessage(), e);
            return new Resolution(Status.UNSUPPORTED, "Graph binding rejected: " + safe(e), null);
        }
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

    private static List<GraphQueryCapabilities.Operator> readOperators(Object value) {
        List<GraphQueryCapabilities.Operator> operators = new ArrayList<>();
        for (Object op : checkedList(value)) {
            try {
                operators.add(GraphQueryCapabilities.Operator.valueOf(str(op)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown graph query operator: " + op, exception);
            }
        }
        return operators;
    }

    private static List<GraphGoalBinder.PathChoice> readPaths(Object value) {
        if (!(value instanceof List<?> entries))
            throw new IllegalArgumentException("paths array is required");
        List<GraphGoalBinder.PathChoice> choices = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, ?> path = checkedMap(entry);
            List<GraphGoalBinder.FieldChoice> outputs = new ArrayList<>();
            for (Object item : checkedList(path.get("outputs"))) {
                Map<String, ?> field = checkedMap(item);
                outputs.add(new GraphGoalBinder.FieldChoice(
                        number(field.get("hop")), str(field.get("field"))));
            }
            List<GraphGoalBinder.EqualityChoice> filters = new ArrayList<>();
            for (Object item : checkedList(path.get("filters"))) {
                Map<String, ?> predicate = checkedMap(item);
                filters.add(new GraphGoalBinder.EqualityChoice(
                        number(predicate.get("hop")),
                        str(predicate.get("field")), str(predicate.get("value"))));
            }
            choices.add(new GraphGoalBinder.PathChoice(
                    str(path.get("pathId")), outputs, filters));
        }
        return choices;
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
                "operators", arr(enumText(Arrays.stream(GraphQueryCapabilities.Operator.values())
                        .map(Enum::name).toList()))
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
    public record Resolution(Status status, String reason, Goal goal) {
        public boolean ready() { return status == Status.COMPLETE && goal != null; }
    }
}
