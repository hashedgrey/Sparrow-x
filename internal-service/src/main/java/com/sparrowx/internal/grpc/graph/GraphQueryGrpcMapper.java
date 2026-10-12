package com.sparrowx.internal.grpc.graph;

import buildingblocks.contracts.GraphQueryRequest;
import buildingblocks.contracts.GraphQueryResult;
import com.google.protobuf.Timestamp;
import com.sparrowx.internal.grpc.*;

import java.util.*;

/** Converts the wire contract without interpreting user supplied Cypher. */
public final class GraphQueryGrpcMapper {
    private GraphQueryGrpcMapper() { }

    public static GraphQueryRequest toQuery(ExecuteInternalCompanyGraphQueryRequest wire) {
        List<GraphQueryRequest.Traverse> traversals = wire.getTraversalsList().stream().map(t ->
                new GraphQueryRequest.Traverse(t.getSource(), t.getRelationshipVariable(),
                        t.getRelationshipType(), direction(t.getDirection()), t.getTarget(), t.getTargetType())).toList();
        List<GraphQueryRequest.Predicate> predicates = wire.getPredicatesList().stream().map(p ->
                new GraphQueryRequest.Predicate(p.getVariable(), p.getField(), compare(p.getCompare()),
                        value(p.getValue()))).toList();
        List<GraphQueryRequest.Projection> projections = wire.getProjectionsList().stream().map(p ->
                new GraphQueryRequest.Projection(p.getVariable(), p.getField(), p.getAlias())).toList();
        if (wire.getRootEntityIdsCount() != new HashSet<>(wire.getRootEntityIdsList()).size())
            throw new IllegalArgumentException("Duplicate root entity ID");
        return new GraphQueryRequest(wire.getRootType(), Set.copyOf(wire.getRootEntityIdsList()),
                traversals, predicates, projections, wire.getMaxRows(), wire.getRequireComplete());
    }

    public static ExecuteInternalCompanyGraphQueryResponse toResponse(GraphQueryResult result) {
        ExecuteInternalCompanyGraphQueryResponse.Builder wire = ExecuteInternalCompanyGraphQueryResponse.newBuilder()
                .setTruncated(result.truncated())
                .addAllEvaluatedRootEntityIds(result.evaluatedRootIds().stream().sorted().toList())
                .setRetrievedAt(Timestamp.newBuilder().setSeconds(result.retrievedAt().getEpochSecond())
                        .setNanos(result.retrievedAt().getNano()));
        for (Map<String, Object> row : result.rows()) {
            GraphQueryRow.Builder output = GraphQueryRow.newBuilder();
            Object root = row.get("__rootEntityId");
            if (!(root instanceof String id) || id.isBlank() || !result.evaluatedRootIds().contains(id))
                throw new IllegalStateException("Query returned an unscoped root");
            output.setRootEntityId(id);
            copyIds(row.get("__nodes"), output::putNodes);
            copyIds(row.get("__relationships"), output::putRelationships);
            for (var entry : row.entrySet()) {
                if (entry.getKey().startsWith("__")) continue;
                output.putValues(entry.getKey(), toValue(entry.getValue()));
            }
            wire.addRows(output);
        }
        return wire.build();
    }

    private static void copyIds(Object raw, java.util.function.BiConsumer<String, String> sink) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("Missing query provenance");
        map.forEach((key, value) -> {
            if (!(key instanceof String k) || !(value instanceof String v) || v.isBlank())
                throw new IllegalStateException("Invalid query provenance");
            sink.accept(k, v);
        });
    }

    private static GraphQueryValue toValue(Object value) {
        GraphQueryValue.Builder builder = GraphQueryValue.newBuilder();
        if (value == null) return builder.build();
        if (value instanceof String s) return builder.setText(s).build();
        if (value instanceof Integer n) return builder.setInteger(n).build();
        if (value instanceof Long n) return builder.setInteger(n).build();
        if (value instanceof Float n) return builder.setDecimal(n).build();
        if (value instanceof Double n) return builder.setDecimal(n).build();
        if (value instanceof Boolean b) return builder.setBoolean(b).build();
        throw new IllegalStateException("Unsupported graph result scalar: " + value.getClass());
    }

    private static Object value(GraphQueryValue value) {
        return switch (value.getKindCase()) {
            case TEXT -> value.getText();
            case INTEGER -> value.getInteger();
            case DECIMAL -> value.getDecimal();
            case BOOLEAN -> value.getBoolean();
            case ITEMS -> value.getItems().getValuesList().stream().map(GraphQueryGrpcMapper::value).toList();
            case KIND_NOT_SET -> throw new IllegalArgumentException("Predicate value is missing");
        };
    }
    private static GraphQueryRequest.Direction direction(GraphQueryDirection direction) {
        return switch (direction) {
            case GRAPH_QUERY_DIRECTION_IN -> GraphQueryRequest.Direction.IN;
            case GRAPH_QUERY_DIRECTION_OUT -> GraphQueryRequest.Direction.OUT;
            default -> throw new IllegalArgumentException("Unspecified graph direction");
        };
    }
    private static GraphQueryRequest.Compare compare(GraphQueryCompare compare) {
        return switch (compare) {
            case GRAPH_QUERY_COMPARE_EQ -> GraphQueryRequest.Compare.EQ;
            case GRAPH_QUERY_COMPARE_NE -> GraphQueryRequest.Compare.NE;
            case GRAPH_QUERY_COMPARE_CONTAINS -> GraphQueryRequest.Compare.CONTAINS;
            case GRAPH_QUERY_COMPARE_IN -> GraphQueryRequest.Compare.IN;
            case GRAPH_QUERY_COMPARE_GT -> GraphQueryRequest.Compare.GT;
            case GRAPH_QUERY_COMPARE_GE -> GraphQueryRequest.Compare.GE;
            case GRAPH_QUERY_COMPARE_LT -> GraphQueryRequest.Compare.LT;
            case GRAPH_QUERY_COMPARE_LE -> GraphQueryRequest.Compare.LE;
            default -> throw new IllegalArgumentException("Unspecified graph comparison");
        };
    }
}
