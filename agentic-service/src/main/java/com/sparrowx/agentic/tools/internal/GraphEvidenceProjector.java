package com.sparrowx.agentic.tools.internal;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.internal.grpc.*;

import java.util.*;

public final class GraphEvidenceProjector {
    private GraphEvidenceProjector() {}

    private static final Set<String> RESERVED = Set.of("rootEntityId", "nodes", "relationships");

    /** Do not overwrite or hide model-selected outputs with provenance metadata. */
    public static List<Map<String, Object>> project(ExecuteInternalCompanyGraphQueryResponse response) {
        Objects.requireNonNull(response);
        List<Map<String, Object>> projected = new ArrayList<>();
        Set<String> roots = Set.copyOf(response.getEvaluatedRootEntityIdsList());
        for (GraphQueryRow row : response.getRowsList()) {
            if (!roots.contains(row.getRootEntityId()))
                throw new IllegalStateException("Graph query returned a row outside the resolved root set");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("rootEntityId", row.getRootEntityId());
            for (var cell : row.getValuesMap().entrySet()) {
                if (cell.getKey().startsWith("__") || RESERVED.contains(cell.getKey())
                        || output.containsKey(cell.getKey()))
                    throw new IllegalStateException("Reserved or duplicate graph output alias");
                output.put(cell.getKey(), value(cell.getValue()));
            }
            output.put("nodes", Map.copyOf(row.getNodesMap()));
            output.put("relationships", Map.copyOf(row.getRelationshipsMap()));
            projected.add(Collections.unmodifiableMap(output));
        }
        return List.copyOf(projected);
    }

    /**
     * Provenance includes the constrained, backend-validated query semantics.
     * A non-truncated result with an EQ predicate proves returned rows satisfy
     * that predicate even when the matching field was not selected as output.
     * This does NOT assert that all tenant entities were searched: only the
     * verified root IDs were evaluated.
     */
    public static List<EvidenceRef> evidence(ExecuteInternalCompanyGraphQueryResponse response,
                                             String requestId, Set<String> requestedRoots,
                                             GraphQueryRequest executedQuery) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(requestedRoots, "requestedRoots");
        Objects.requireNonNull(executedQuery, "executedQuery");
        if (!executedQuery.requireComplete() ||
                !executedQuery.rootIds().equals(requestedRoots) ||
                response.getTruncated() ||
                !Set.copyOf(response.getEvaluatedRootEntityIdsList()).equals(requestedRoots))
            throw new IllegalStateException("Incomplete or mismatched graph query cannot be cited as evidence");

        // Check result values against the exact compiled output contract. The
        // backend may omit a null projection but must not introduce new fields.
        Set<String> projectedAliases = new HashSet<>();
        executedQuery.projections().forEach(p -> projectedAliases.add(p.alias()));
        for (GraphQueryRow row : response.getRowsList()) {
            if (!projectedAliases.containsAll(row.getValuesMap().keySet())) {
                throw new IllegalStateException("IntSvc returned undeclared projection aliases");
            }
        }

        List<Map<String, Object>> traversals = executedQuery.traversals().stream().map(t ->
                Map.<String, Object>of("source", t.source(), "relationshipVariable", t.relationshipVariable(),
                        "relationshipType", t.relationshipType(), "direction", t.direction().name(),
                        "target", t.target(), "targetType", t.targetType())).toList();
        List<Map<String, Object>> predicates = executedQuery.predicates().stream().map(p ->
                Map.<String, Object>of("variable", p.variable(), "field", p.field(),
                        "compare", p.compare().name(), "value", p.value())).toList();
        List<Map<String, Object>> comparisons = executedQuery.fieldComparisons().stream().map(c ->
                Map.<String, Object>of("leftVariable", c.leftVariable(), "leftField", c.leftField(),
                        "compare", c.compare().name(), "rightVariable", c.rightVariable(),
                        "rightField", c.rightField())).toList();
        List<Map<String, Object>> negated = executedQuery.negatedTraversals().stream().map(n ->
                Map.<String, Object>of("source", n.source(),
                        "relationshipType", n.relationshipType(), "direction", n.direction().name(),
                        "targetType", n.targetType(), "operator", "NOT_EXISTS_DIRECT")).toList();
        List<Map<String, Object>> projections = executedQuery.projections().stream().map(p ->
                Map.<String, Object>of("variable", p.variable(), "field", p.field(),
                        "alias", p.alias())).toList();

        Map<String, Object> queryProof = new LinkedHashMap<>();
        queryProof.put("rootType", executedQuery.rootType());
        queryProof.put("schemaVersion", executedQuery.schemaVersion());
        queryProof.put("traversals", traversals);
        queryProof.put("predicates", predicates);
        queryProof.put("fieldComparisons", comparisons);
        queryProof.put("negatedTraversals", negated);
        queryProof.put("projections", projections);
        if (executedQuery.selection() != null) {
            var selection = executedQuery.selection();
            queryProof.put("selection", Map.of(
                    "onePerGroupBy", selection.onePerGroupBy(),
                    "orderBy", selection.orderBy().stream().map(sort ->
                            Map.<String, Object>of("alias", sort.alias(), "descending", sort.descending())).toList(),
                    "random", selection.random(), "limit", selection.limit()));
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("rootEntityIds", requestedRoots.stream().sorted().toList());
        attributes.put("validatedGraphAnswerRows", project(response));
        attributes.put("validatedGraphQuery", Map.copyOf(queryProof));
        attributes.put("readAtEpochSecond", response.getRetrievedAt().getSeconds());
        attributes.put("readAtNano", response.getRetrievedAt().getNanos());
        attributes.put("truncated", false);
        return List.of(new EvidenceRef("internal-graph-query:" + requestId,
                EvidenceSourceType.INTERNAL_GRAPH, "internal-service", requestId,
                "", "", requestId, "", "COMPANY_GRAPH_QUERY", 0, 0, "", "", "",
                Map.copyOf(attributes)));
    }

    private static Object value(GraphQueryValue cell) {
        return switch (cell.getKindCase()) {
            case TEXT -> cell.getText();
            case INTEGER -> cell.getInteger();
            case DECIMAL -> cell.getDecimal();
            case BOOLEAN -> cell.getBoolean();
            case KIND_NOT_SET -> null;
            case ITEMS -> cell.getItems().getValuesList().stream()
                    .map(GraphEvidenceProjector::value).toList();
        };
    }

}
