package com.sparrowx.agentic.components;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import com.sparrowx.agentic.planning.graph.GraphRootScope;
import com.sparrowx.agentic.planning.graph.GraphOntology;
import com.sparrowx.agentic.planning.graph.GraphOntology.Relation;
import com.sparrowx.agentic.planning.graph.GraphQuerySelectionBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a schema-checked logical graph query from IntSvc's tenant ontology.
 * No graph path enumeration, business node types, relationship names, or
 * preferred field names are coded into AGSvc. IntSvc remains the sole Cypher
 * compiler, graph executor and authorization boundary.
 *
 * Targets the provided GraphQueryRequest contract: conjunctive joins,
 * literal/field comparisons, direct one-hop negation and projected-alias
 * selection (sorting, one-per-group, top-N, random).
 * GraphQueryRequest also permits typed scalar and IN predicates. This
 * resolver currently generates only text predicates until IntSvc's mapper
 * and the ontology's field type metadata can be verified. Do not confuse
 * that adapter limitation with a limitation of the portable contract.
 * Aggregations, disjunction and optional joins are not simulated.
 */
@Component
public final class GraphEvidenceGoalResolver {
    private static final Logger LOG = LoggerFactory.getLogger(GraphEvidenceGoalResolver.class);

    private static final String TASK = """
            Construct a schema-checked logical query for the GRAPH-ANSWERABLE
            requirements of the user's question. Document content, text analysis,
            and comparisons between document bodies are outside this capability.
            Preserve every graph requirement; do not silently drop any relation,
            constraint, negated relationship or requested graph output.
            The root nodes have already been resolved; they are anchored by ID
            outside your output. Do not repeat those identity constraints.

            Select joins using edgeId values from schemaEdges ONLY. Each join
            gives a source node variable, a target node variable, an edgeId,
            and a fresh relationshipVariable (e.g. membershipEdge).
            The first bound variable is 'root'. A join's source must be bound
            by an earlier join. Its target can be new or an existing variable
            of the indicated node type (for cycles/converging branches).

            A relationshipVariable may be used in outputs/filters/comparisons
            ONLY with properties listed for that edge in schemaEdges.fields.
            Do not invent relationship attributes. Relationships have no nodeType.

            Use filters for comparisons against LITERAL values. Use
            fieldComparisons for comparisons between properties on two bound
            node or relationship variables, e.g. equality of attributes or
            inequality of entity IDs to exclude the resolved root itself.
            Field comparisons support EQ and NE only. Never substitute a
            guessed literal for another node's attribute or silently drop
            cross-variable constraints.

            Use negatedJoins ONLY for direct, one-edge nonexistence requirements
            ("no directly linked X"). Each entry has source (an already bound
            variable) and edgeId (from schemaEdges). A negated join does NOT
            create a target variable, add a displayed result, or count as a
            positive join. Choose the exact ontology relationship direction.
            Never reinterpret a multi-hop negative condition as a direct edge.
            Do not use negatedJoins for OPTIONAL joins, counting or general NOT.

            ALWAYS include the selection object. Use empty onePerGroupBy and
            orderBy arrays, random=false, limit=0 when no selection is needed.
            When the user asks for top-N, ordering, random selection, or one
            representative per group, populate the selection object.
            onePerGroupBy and orderBy MUST reference aliases in outputs, not
            graph variable names or raw properties. A group is a distinct
            tuple of projected aliases; this is NOT COUNT, SUM, or aggregation.
            The optional selection.limit is the user-requested top-N count,
            not a request to enlarge the execution budget. Use limit=0 when
            no user-level limit is requested. Never silently drop sorting or grouping.

            Select projected fields and comparisons by variable. Node fields
            MUST exist in fieldsByType for that node type; relationship fields
            MUST exist in schemaEdges.fields for the selected edge. When the
            user requests a predicate-bearing attribute as an answer column,
            also project that attribute; a filter alone is not a displayed
            attribute. Do not disclose unrelated filter fields. Physical
            Neo4j property names are exact strings, including dotted names;
            do not strip, prefix or fabricate field names.

            Query capabilities and per-type property constraints are authoritative;
            never associate a field with a node solely because another type has it.
            The complete request must be expressible using conjunctions of
            joins, projections, literal filters, field-to-field comparisons,
            and direct one-hop negated existence checks.
            If the GRAPH-ANSWERABLE part requires an operation absent from
            queryCapabilities, return UNSUPPORTED and name the missing graph
            requirements. Do not mark unrelated document-only work as an
            unsupported graph requirement. If multiple distinct
            meanings cannot be resolved, return AMBIGUOUS. Never drop any
            requested condition or requested fact to produce READY.

            Choose variable names that identify each node's purpose, including
            intermediate nodes. Use ALL requested branches and output fields.
            When READY, unsupportedRequirements must be empty.
            """;

    private final SemanticDecisionService semantic;
    private final GraphOntologyProvider ontologyProvider;

    // Resource guards are intentional. They do not encode tenant schema semantics.
    @Value("${sparrowx.graph.goal.max-schema-edges:10000}")
    private int maxSchemaEdges = 10000;
    @Value("${sparrowx.graph.goal.max-joins:16}")
    private int maxJoins = 16;
    @Value("${sparrowx.graph.goal.max-query-rows:500}")
    private int maxQueryRows = 500;
    @Value("${sparrowx.graph.goal.max-repair-attempts:1}")
    private int maxRepairAttempts = 1;

    public GraphEvidenceGoalResolver(
            SemanticDecisionService semantic,
            com.fasterxml.jackson.databind.ObjectMapper unusedMapper,
            GraphOntologyProvider ontologyProvider) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        Objects.requireNonNull(unusedMapper, "mapper"); // Retains existing Spring constructor signature.
        this.ontologyProvider = Objects.requireNonNull(ontologyProvider, "ontologyProvider");
    }

    public Resolution compile(MissionContext context, MissionIntent intent,
                              String query, GraphRootScope roots, String operationId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(roots, "roots");
        requireText(query, "query");
        requireText(operationId, "operationId");
        if (maxSchemaEdges < 1 || maxJoins < 0 || maxJoins > 16 || maxQueryRows < 1 || maxQueryRows > 10000 ||
                maxRepairAttempts < 0 || maxRepairAttempts > 3) {
            throw new IllegalStateException("Invalid graph goal resource configuration");
        }

        final long started = System.nanoTime();
        final GraphOntology ontology = Objects.requireNonNull(ontologyProvider.describe(context), "ontology");
        String rootType = roots.nodeType();
        if (!ontology.fields().containsKey(rootType)) {
            return unsupported("Graph ontology does not declare root node type " + rootType);
        }

        final Map<String, EdgeOption> edges;
        try {
            edges = edges(ontology);
        } catch (IllegalArgumentException e) {
            return unsupported("Invalid tenant graph schema: " + safe(e));
        }
        if (edges.size() > maxSchemaEdges) {
            return unsupported("Tenant graph schema exceeds configured edge-option budget");
        }

        List<Map<String, Object>> edgeDescriptions = edges.values().stream()
                .sorted(Comparator.comparing(EdgeOption::id))
                .map(EdgeOption::describe).toList();
        Map<String, Object> sharedInput = new LinkedHashMap<>();
        sharedInput.put("query", query);
        sharedInput.put("rootType", rootType);
        sharedInput.put("selectedRootCount", roots.entityIds().size());
        sharedInput.put("schemaEdges", edgeDescriptions);
        sharedInput.put("fieldsByType", ontology.fields());
        sharedInput.put("schemaVersion", ontology.schemaVersion());
        sharedInput.put("queryCapabilities", Map.of(
                "shape", "conjunction of named graph joins, one-hop negated existence, node/relationship property projections, literal predicates and field comparisons",
                "comparisons", scalarComparisons().stream().map(Enum::name).toList(),
                "fieldComparisons", List.of("EQ", "NE"),
                "negatedJoins", "direct one-hop NOT EXISTS against ontology-declared relationships, sourced at bound variables",
                "selection", "optional projected-alias ORDER BY, top-N limit, random and one representative per distinct projected group",
                "unsupportedByThisResolver", List.of(
                        "aggregation (COUNT/SUM/etc)", "disjunction", "optional join",
                        "multi-hop negated existence",
                        "numeric/list predicates pending ontology property types and IntSvc mapper verification"),
                "contractCanRepresent", List.of(
                        "typed scalar predicate values", "IN with a bounded list", "GT/GE/LT/LE")));

        Map<String, Object> previous = null;
        String bindingError = null;
        for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
            Map<String, Object> input = new LinkedHashMap<>(sharedInput);
            if (bindingError != null) {
                input.put("previousInvalidSelection", previous);
                input.put("bindingError", bindingError);
                input.put("repairInstruction", "Correct the selection using declared node fields, relationship fields and schema edges; never remove required semantics.");
            }
            String attemptId = operationId + ":select:" + attempt;
            final LlmDecisionEngine.Result response = semantic.escalate(
                    new LlmDecisionEngine.Request(
                            intent.missionId(), attemptId, attemptId,
                            TASK, Map.copyOf(input),
                            Set.of("status", "reason", "joins", "negatedJoins", "outputs", "filters",
                                    "fieldComparisons", "selection", "unsupportedRequirements"),
                            outputSchema(edges.keySet(), ontology),
                            3000, 0.0,
                            Map.of("component", "ontology-bound-graph-goal")));
            Map<String, Object> output = response.output();
            if (output == null) return unsupported("Model returned no structured graph goal");
            String status = Objects.toString(output.get("status"), "");
            String reason = Objects.toString(output.get("reason"), "");
            if ("AMBIGUOUS".equals(status))
                return new Resolution(GraphResolutionStatus.AMBIGUOUS, reason, null);
            if ("UNSUPPORTED".equals(status)) return unsupported(reason);
            if (!"READY".equals(status)) return unsupported("Invalid graph goal status");

            try {
                if (!checkedList(output.get("unsupportedRequirements")).isEmpty())
                    return unsupported("Requested features unsupported by graph query contract: "
                            + checkedList(output.get("unsupportedRequirements")));
                GraphQueryRequest request = bind(rootType, roots, ontology, edges, output);
                // Observable shape, not a raw model prompt or tenant graph data dump.
                MissionDecisionTraceContext.event("GRAPH_QUERY_BOUND", "Validated graph query structure",
                        Map.of("rootType", request.rootType(),
                                "rootCount", Integer.toString(request.rootIds().size()),
                                "traversalCount", Integer.toString(request.traversals().size()),
                                "negationCount", Integer.toString(request.negatedTraversals().size()),
                                "predicateCount", Integer.toString(request.predicates().size()),
                                "comparisonCount", Integer.toString(request.fieldComparisons().size()),
                                "projectionCount", Integer.toString(request.projections().size()),
                                "selectionLimit", request.selection() == null ? "none" : Integer.toString(request.selection().limit())));
                LOG.info("graph.goal.bound operation={} attempt={} roots={} traversals={} negatedTraversals={} predicates={} fieldComparisons={} projections={} elapsedMs={}",
                        operationId, attempt, request.rootIds().size(), request.traversals().size(),
                        request.negatedTraversals().size(), request.predicates().size(),
                        request.fieldComparisons().size(), request.projections().size(),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return new Resolution(GraphResolutionStatus.COMPLETE, reason, request);
            } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
                previous = output;
                bindingError = safe(e);
                LOG.warn("graph.goal.bindingRejected operation={} attempt={} reason={}",
                        operationId, attempt, bindingError);
            }
        }
        return unsupported("Graph binding rejected after schema-bound repair: " + bindingError);
    }

    private GraphQueryRequest bind(String rootType, GraphRootScope roots, GraphOntology ontology,
                                   Map<String, EdgeOption> edges, Map<String, Object> selection) {
        Map<String, String> nodeTypes = new LinkedHashMap<>();
        nodeTypes.put("root", rootType);
        Map<String, Set<String>> relationshipFieldsByVariable = new LinkedHashMap<>();
        List<GraphQueryRequest.Traverse> traversals = new ArrayList<>();
        Set<String> usedJoinTargets = new HashSet<>();
        for (Object entry : checkedList(selection.get("joins"))) {
            if (traversals.size() >= maxJoins)
                throw new IllegalArgumentException("Graph goal exceeds configured join budget");
            Map<String, ?> item = checkedMap(entry);
            String source = variable(item.get("source"));
            String target = variable(item.get("target"));
            String id = nonblank(item.get("edgeId"));
            String relationVariable = variable(item.get("relationshipVariable"));
            EdgeOption edge = edges.get(id);
            if (edge == null) throw new IllegalArgumentException("Unknown tenant schema edge ID: " + id);
            String sourceType = nodeTypes.get(source);
            if (!edge.fromType().equals(sourceType))
                throw new IllegalArgumentException("Join source unbound or has incompatible node type: " + source);
            if (relationshipFieldsByVariable.containsKey(source) || relationshipFieldsByVariable.containsKey(target)
                    || nodeTypes.containsKey(relationVariable)
                    || relationshipFieldsByVariable.containsKey(relationVariable)
                    || relationVariable.equals(target))
                throw new IllegalArgumentException("Conflicting graph relationship variable");
            String targetType = nodeTypes.putIfAbsent(target, edge.toType());
            if (targetType != null && !targetType.equals(edge.toType()))
                throw new IllegalArgumentException("Join target has incompatible node type: " + target);
            if (!usedJoinTargets.add(source + "|" + id + "|" + target))
                throw new IllegalArgumentException("Duplicate graph join");
            relationshipFieldsByVariable.put(relationVariable, edge.fields());
            traversals.add(new GraphQueryRequest.Traverse(source, relationVariable, edge.relation(),
                    edge.direction(), target, edge.toType()));
        }

        List<GraphQueryRequest.NegatedTraversal> negated = new ArrayList<>();
        Set<String> usedAbsences = new HashSet<>();
        for (Object entry : checkedList(selection.getOrDefault("negatedJoins", List.of()))) {
            if (traversals.size() + negated.size() >= maxJoins)
                throw new IllegalArgumentException("Graph goal exceeds configured total join budget");
            Map<String, ?> item = checkedMap(entry);
            String source = variable(item.get("source"));
            String id = nonblank(item.get("edgeId"));
            EdgeOption edge = edges.get(id);
            if (edge == null) throw new IllegalArgumentException("Unknown negated tenant schema edge ID: " + id);
            if (!edge.fromType().equals(nodeTypes.get(source)))
                throw new IllegalArgumentException("Negated join source unbound or has incompatible node type: " + source);
            if (!usedAbsences.add(source + "|" + id))
                throw new IllegalArgumentException("Duplicate negated graph join");
            negated.add(new GraphQueryRequest.NegatedTraversal(source, edge.relation(),
                    edge.direction(), edge.toType()));
        }

        List<GraphQueryRequest.Projection> projections = new ArrayList<>();
        Set<String> outputAliases = new LinkedHashSet<>();
        for (Object entry : checkedList(selection.get("outputs"))) {
            Map<String, ?> item = checkedMap(entry);
            String variable = variable(item.get("variable"));
            String field = nonblank(item.get("field"));
            String alias = variable(item.get("alias"));
            if (Set.of("rootEntityId", "nodes", "relationships").contains(alias) || alias.startsWith("__"))
                throw new IllegalArgumentException("Reserved graph result projection alias: " + alias);
            requireField(variable, field, nodeTypes, relationshipFieldsByVariable, ontology);
            if (!outputAliases.add(alias)) throw new IllegalArgumentException("Duplicate output alias " + alias);
            projections.add(new GraphQueryRequest.Projection(variable, field, alias));
        }
        if (projections.isEmpty()) throw new IllegalArgumentException("No selected output fields");

        List<GraphQueryRequest.Predicate> predicates = new ArrayList<>();
        for (Object entry : checkedList(selection.get("filters"))) {
            Map<String, ?> item = checkedMap(entry);
            String variable = variable(item.get("variable"));
            String field = nonblank(item.get("field"));
            requireField(variable, field, nodeTypes, relationshipFieldsByVariable, ontology);
            GraphQueryRequest.Compare compare = GraphQueryRequest.Compare.valueOf(nonblank(item.get("compare")));
            if (!scalarComparisons().contains(compare))
                throw new IllegalArgumentException("Comparison requires a non-scalar predicate value: " + compare);
            Object rawValue = item.get("value");
            if (!(rawValue instanceof String value))
                throw new IllegalArgumentException("Current predicate contract requires a string value");
            if (variable.equals("root") && field.equals("entityId") && compare == GraphQueryRequest.Compare.EQ
                    && !roots.entityIds().contains(value)) {
                throw new IllegalArgumentException("Root predicate conflicts with verified entity IDs");
            }
            predicates.add(new GraphQueryRequest.Predicate(variable, field, compare, value));
        }

        List<GraphQueryRequest.FieldComparison> fieldComparisons = new ArrayList<>();
        for (Object entry : checkedList(selection.getOrDefault("fieldComparisons", List.of()))) {
            Map<String, ?> item = checkedMap(entry);
            String leftVariable = variable(item.get("leftVariable"));
            String leftField = nonblank(item.get("leftField"));
            String rightVariable = variable(item.get("rightVariable"));
            String rightField = nonblank(item.get("rightField"));
            requireField(leftVariable, leftField, nodeTypes, relationshipFieldsByVariable, ontology);
            requireField(rightVariable, rightField, nodeTypes, relationshipFieldsByVariable, ontology);
            GraphQueryRequest.Compare compare = GraphQueryRequest.Compare.valueOf(nonblank(item.get("compare")));
            fieldComparisons.add(new GraphQueryRequest.FieldComparison(leftVariable, leftField,
                    compare, rightVariable, rightField));
        }

        GraphQueryRequest.Selection rowSelection = GraphQuerySelectionBinder.bind(
                selection.get("selection"), projections, maxQueryRows);
        return new GraphQueryRequest(rootType, roots.entityIds(), traversals,
                predicates, projections, maxQueryRows, true, ontology.schemaVersion(),
                fieldComparisons, negated, rowSelection);
    }

    private static void requireField(String variable, String field, Map<String, String> nodeTypes,
                                     Map<String, Set<String>> relationFields, GraphOntology ontology) {
        if (nodeTypes.containsKey(variable)) {
            String type = nodeTypes.get(variable);
            if (!ontology.fields().getOrDefault(type, List.of()).contains(field))
                throw new IllegalArgumentException("Undeclared field " + type + "." + field);
        } else if (!relationFields.getOrDefault(variable, Set.of()).contains(field)) {
            throw new IllegalArgumentException("Undeclared/unbound graph relationship field " + variable + "." + field);
        }
    }

    private static Map<String, EdgeOption> edges(GraphOntology ontology) {
        Map<String, EdgeOption> options = new TreeMap<>();
        for (Relation r : ontology.relations()) {
            if (!ontology.fields().containsKey(r.source()) || !ontology.fields().containsKey(r.target()))
                throw new IllegalArgumentException("Relation references a non-declared node type: " + r);
            add(options, new EdgeOption(edgeId(r.source(), r.type(), r.target(), "OUT"),
                    r.source(), r.type(), GraphQueryRequest.Direction.OUT, r.target(), Set.copyOf(r.fields())));
            add(options, new EdgeOption(edgeId(r.target(), r.type(), r.source(), "IN"),
                    r.target(), r.type(), GraphQueryRequest.Direction.IN, r.source(), Set.copyOf(r.fields())));
        }
        return Map.copyOf(options);
    }

    private static void add(Map<String, EdgeOption> options, EdgeOption edge) {
        EdgeOption previous = options.putIfAbsent(edge.id(), edge);
        if (previous != null && !previous.equals(edge))
            throw new IllegalArgumentException("Schema edge identifier collision");
    }

    private static String edgeId(String from, String relationship, String to, String direction) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("\u0000", from, relationship, direction, to);
            return "edge_" + java.util.HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record EdgeOption(String id, String fromType, String relation,
                              GraphQueryRequest.Direction direction, String toType, Set<String> fields) {
        Map<String, Object> describe() {
            return Map.of("edgeId", id, "fromType", fromType, "relation", relation,
                    "direction", direction.name(), "toType", toType,
                    "fields", fields.stream().sorted().toList());
        }
    }

    // The portable record supports typed values. This resolver intentionally
    // restricts generation to text filters while ontology property types and
    // downstream protobuf mappings remain unverified.
    private static List<GraphQueryRequest.Compare> scalarComparisons() {
        return List.of(GraphQueryRequest.Compare.EQ, GraphQueryRequest.Compare.NE,
                GraphQueryRequest.Compare.CONTAINS);
    }

    private static Map<String, Object> outputSchema(Set<String> edgeIds, GraphOntology ontology) {
        Map<String, Object> name = Map.of("type", "string");
        Map<String, Object> join = obj(Map.of("source", name, "target", name,
                "relationshipVariable", name,
                "edgeId", enumText(edgeIds.stream().sorted().toList())));
        Map<String, Object> negatedJoin = obj(Map.of("source", name,
                "edgeId", enumText(edgeIds.stream().sorted().toList())));
        Map<String, Object> projection = obj(Map.of("variable", name, "field", name, "alias", name));
        Map<String, Object> predicate = obj(Map.of("variable", name, "field", name,
                "compare", enumText(scalarComparisons().stream().map(Enum::name).toList()),
                "value", name));
        Map<String, Object> fieldComparison = obj(Map.of(
                "leftVariable", name, "leftField", name,
                "compare", enumText(List.of("EQ", "NE")),
                "rightVariable", name, "rightField", name));
        // Include selection in both the requested output fields and the schema.
        // A no-op selection is mapped to null by GraphQuerySelectionBinder.
        Map<String, Object> properties = new LinkedHashMap<>(Map.of(
                "status", enumText(List.of("READY", "AMBIGUOUS", "UNSUPPORTED")),
                "reason", name,
                "joins", arr(join), "negatedJoins", arr(negatedJoin),
                "outputs", arr(projection), "filters", arr(predicate),
                "fieldComparisons", arr(fieldComparison),
                "unsupportedRequirements", arr(name)));
        properties.put("selection", GraphQuerySelectionBinder.outputSchema());
        return Map.of("type", "object", "properties", Map.copyOf(properties),
                "required", properties.keySet().stream().sorted().toList(),
                "additionalProperties", false);
    }

    private static Map<String, Object> enumText(List<String> choices) {
        return Map.of("type", "string", "enum", choices);
    }
    private static Map<String, Object> arr(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
    private static Map<String, Object> obj(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties,
                "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }
    private static Map<String, ?> checkedMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Expected graph selection object");
        Map<String, Object> result = new HashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Nonstring key");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static List<?> checkedList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected array in graph selection");
        return list;
    }
    private static String nonblank(Object value) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Missing required graph selection string");
        return text;
    }
    private static String variable(Object value) {
        String name = nonblank(value);
        if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || name.startsWith("_rel_"))
            throw new IllegalArgumentException("Invalid graph variable or alias: " + name);
        return name;
    }
    private static String safe(Throwable error) {
        String message = Objects.toString(error.getMessage(), "Invalid graph goal");
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
    private static Resolution unsupported(String why) {
        return new Resolution(GraphResolutionStatus.UNSUPPORTED, why, null);
    }

    public record Resolution(GraphResolutionStatus status, String reason, GraphQueryRequest query) {
        public boolean ready() { return status == GraphResolutionStatus.COMPLETE && query != null; }
    }
}
