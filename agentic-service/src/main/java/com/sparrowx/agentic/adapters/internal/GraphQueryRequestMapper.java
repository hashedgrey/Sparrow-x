package com.sparrowx.agentic.adapters.internal;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.*;

import java.util.*;

public final class GraphQueryRequestMapper {
    private final InternalClientMapper clientMapper;

    public GraphQueryRequestMapper(InternalClientMapper clientMapper) {
        this.clientMapper = Objects.requireNonNull(clientMapper);
    }

    public ExecuteInternalCompanyGraphQueryRequest toProto(
            MissionContext context, String requestId, GraphQueryRequest plan) {
        Objects.requireNonNull(plan);
        if (plan.schemaVersion().isBlank())
            throw new IllegalArgumentException("Company graph query is missing schema version");
        ExecuteInternalCompanyGraphQueryRequest.Builder builder =
                ExecuteInternalCompanyGraphQueryRequest.newBuilder()
                        .setContext(clientMapper.toRequestContext(context, requireText(requestId, "requestId")))
                        .setRootType(plan.rootType())
                        .addAllRootEntityIds(plan.rootIds().stream().sorted().toList())
                        .setSchemaVersion(plan.schemaVersion())
                        .setMaxRows(plan.maxRows())
                        .setRequireComplete(plan.requireComplete());
        for (var step : plan.traversals()) {
            builder.addTraversals(GraphQueryTraverse.newBuilder()
                    .setSource(step.source())
                    .setRelationshipVariable(step.relationshipVariable())
                    .setRelationshipType(step.relationshipType())
                    .setDirection(step.direction() == GraphQueryRequest.Direction.IN
                            ? GraphQueryDirection.GRAPH_QUERY_DIRECTION_IN
                            : GraphQueryDirection.GRAPH_QUERY_DIRECTION_OUT)
                    .setTarget(step.target()).setTargetType(step.targetType()));
        }
        for (var predicate : plan.predicates()) {
            builder.addPredicates(GraphQueryPredicate.newBuilder()
                    .setVariable(predicate.variable()).setField(predicate.field())
                    .setCompare(GraphQueryCompare.valueOf("GRAPH_QUERY_COMPARE_" + predicate.compare().name()))
                    .setValue(scalar(predicate.value())));
        }
        for (var comparison : plan.fieldComparisons()) {
            builder.addFieldComparisons(GraphQueryFieldComparison.newBuilder()
                    .setLeftVariable(comparison.leftVariable())
                    .setLeftField(comparison.leftField())
                    .setCompare(GraphQueryCompare.valueOf(
                            "GRAPH_QUERY_COMPARE_" + comparison.compare().name()))
                    .setRightVariable(comparison.rightVariable())
                    .setRightField(comparison.rightField()));
        }
        for (var absence : plan.negatedTraversals()) {
            builder.addNegatedTraversals(GraphQueryNegatedTraversal.newBuilder()
                    .setSource(absence.source())
                    .setRelationshipType(absence.relationshipType())
                    .setDirection(absence.direction() == GraphQueryRequest.Direction.IN
                            ? GraphQueryDirection.GRAPH_QUERY_DIRECTION_IN
                            : GraphQueryDirection.GRAPH_QUERY_DIRECTION_OUT)
                    .setTargetType(absence.targetType()));
        }
        for (var projection : plan.projections()) {
            builder.addProjections(GraphQueryProjection.newBuilder()
                    .setVariable(projection.variable()).setField(projection.field()).setAlias(projection.alias()));
        }
        if (plan.selection() != null) {
            var chosen = GraphQuerySelection.newBuilder()
                    .addAllOnePerGroupBy(plan.selection().onePerGroupBy())
                    .setRandom(plan.selection().random())
                    .setLimit(plan.selection().limit());
            for (var order : plan.selection().orderBy()) {
                chosen.addOrderBy(GraphQuerySort.newBuilder()
                        .setAlias(order.alias()).setDescending(order.descending()));
            }
            builder.setSelection(chosen);
        }
        return builder.build();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static GraphQueryValue scalar(Object value) {
        GraphQueryValue.Builder out = GraphQueryValue.newBuilder();
        if (value instanceof String s) return out.setText(s).build();
        if (value instanceof Integer i) return out.setInteger(i).build();
        if (value instanceof Long l) return out.setInteger(l).build();
        if (value instanceof Double d) return out.setDecimal(d).build();
        if (value instanceof Boolean b) return out.setBoolean(b).build();
        if (value instanceof List<?> items) {
            GraphQueryValues.Builder list = GraphQueryValues.newBuilder();
            items.forEach(item -> list.addValues(scalar(item)));
            return out.setItems(list).build();
        }
        throw new IllegalArgumentException("Unsupported graph predicate value");
    }

}
