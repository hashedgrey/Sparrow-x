package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.internal.grpc.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GraphEvidenceProjector {
    private GraphEvidenceProjector() { }

    /** Rows are already validated and evaluated by IntSvc; preserve null/missing values. */
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
                if (cell.getKey().startsWith("__") || output.containsKey(cell.getKey()))
                    throw new IllegalStateException("Reserved or duplicate graph output alias");
                output.put(cell.getKey(), value(cell.getValue()));
            }
            output.put("nodes", Map.copyOf(row.getNodesMap()));
            output.put("relationships", Map.copyOf(row.getRelationshipsMap()));
            projected.add(Collections.unmodifiableMap(output));
        }
        return List.copyOf(projected);
    }

    /** One proof covers the evaluated root set, including an exhaustive zero-row result. */
    public static List<EvidenceRef> evidence(ExecuteInternalCompanyGraphQueryResponse response,
            String requestId, Set<String> requestedRoots) {
        if (response.getTruncated() || !Set.copyOf(response.getEvaluatedRootEntityIdsList()).equals(requestedRoots))
            throw new IllegalStateException("Incomplete graph query cannot be cited as evidence");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("rootEntityIds", requestedRoots.stream().sorted().toList());
        attributes.put("validatedGraphAnswerRows", project(response));
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
            case ITEMS -> throw new IllegalStateException("Query result rows must contain scalar fields");
        };
    }

    /** Legacy explorer projection; unrelated callers can continue to use it. */
    public static List<Map<String, Object>> project(InternalGraph graph) {
        Map<String, InternalGraphNode> nodes = graph.getNodesList().stream()
                .collect(Collectors.toMap(InternalGraphNode::getNodeId, Function.identity()));
        return graph.getRelationshipsList().stream().map(edge -> {
            InternalGraphNode from = nodes.get(edge.getFromNodeId());
            InternalGraphNode to = nodes.get(edge.getToNodeId());
            if (from == null || to == null)
                throw new IllegalStateException("Graph relationship references an unresolved node: "
                        + edge.getRelationshipId());
            return Map.<String, Object>of("subjectName", from.getLabel(),
                    "subjectType", from.getNodeType().name(), "subjectAttributes", from.getAttributesMap(),
                    "relationship", edge.getRelationshipType().name(), "objectName", to.getLabel(),
                    "objectType", to.getNodeType().name(), "objectAttributes", to.getAttributesMap());
        }).toList();
    }
}
