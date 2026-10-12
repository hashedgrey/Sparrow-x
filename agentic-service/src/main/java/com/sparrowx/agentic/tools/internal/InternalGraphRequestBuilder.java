package com.sparrowx.agentic.tools.internal;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.adapters.internal.InternalClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.*;

import java.util.*;

public final class InternalGraphRequestBuilder {
    private final InternalClientMapper clientMapper;
    public InternalGraphRequestBuilder(InternalClientMapper clientMapper) {
        this.clientMapper = Objects.requireNonNull(clientMapper);
    }

    public ExecuteInternalCompanyGraphQueryRequest buildCompanyGraphQuery(
            MissionContext context, String requestId, GraphQueryRequest plan) {
        Objects.requireNonNull(plan);
        ExecuteInternalCompanyGraphQueryRequest.Builder builder = ExecuteInternalCompanyGraphQueryRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context, requireText(requestId, "requestId")))
                .setRootType(plan.rootType())
                .addAllRootEntityIds(plan.rootIds().stream().sorted().toList())
                .setMaxRows(plan.maxRows()).setRequireComplete(plan.requireComplete());
        for (var step : plan.traversals()) {
            builder.addTraversals(GraphQueryTraverse.newBuilder()
                    .setSource(step.source()).setRelationshipVariable(step.relationshipVariable())
                    .setRelationshipType(step.relationshipType())
                    .setDirection(step.direction() == GraphQueryRequest.Direction.IN
                            ? GraphQueryDirection.GRAPH_QUERY_DIRECTION_IN : GraphQueryDirection.GRAPH_QUERY_DIRECTION_OUT)
                    .setTarget(step.target()).setTargetType(step.targetType()));
        }
        for (var predicate : plan.predicates()) {
            builder.addPredicates(GraphQueryPredicate.newBuilder()
                    .setVariable(predicate.variable()).setField(predicate.field())
                    .setCompare(GraphQueryCompare.valueOf("GRAPH_QUERY_COMPARE_" + predicate.compare().name()))
                    .setValue(scalar(predicate.value())));
        }
        for (var projection : plan.projections()) {
            builder.addProjections(GraphQueryProjection.newBuilder()
                    .setVariable(projection.variable()).setField(projection.field()).setAlias(projection.alias()));
        }
        return builder.build();
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

    // Legacy interactive graph reads remain for callers outside the goal-directed query path.
    public ReadInternalCompanyGraphRequest buildCompanyGraph(MissionContext context, GraphSpec spec) {
        Objects.requireNonNull(spec);
        return ReadInternalCompanyGraphRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context, spec.requestId()))
                .setRootEntityId(spec.rootEntityId()).setRootNodeType(spec.rootNodeType())
                .setDepth(spec.depth()).setLimit(spec.limit()).build();
    }

    public ExpandInternalCompanyGraphRequest buildGraphExpansion(MissionContext context, ExpansionSpec spec) {
        Objects.requireNonNull(spec);
        return ExpandInternalCompanyGraphRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context, spec.requestId()))
                .addAllFrontierNodeIds(spec.frontierNodeIds().stream().sorted().toList())
                .addAllRelationshipTypes(spec.relationshipTypes().stream().map(InternalGraphRequestBuilder::relationshipName)
                        .sorted().toList()).setLimit(spec.maxEdges()).build();
    }

    public record ExpansionSpec(String requestId, Set<String> frontierNodeIds,
            Set<InternalGraphRelationshipType> relationshipTypes, int maxEdges) {
        public ExpansionSpec {
            requireText(requestId, "requestId");
            frontierNodeIds = Set.copyOf(Objects.requireNonNull(frontierNodeIds));
            relationshipTypes = Set.copyOf(Objects.requireNonNull(relationshipTypes));
            if (frontierNodeIds.isEmpty() || relationshipTypes.isEmpty() || maxEdges < 1)
                throw new IllegalArgumentException("Expansion needs frontier, relationship types and positive edge limit");
            frontierNodeIds.forEach(id -> requireText(id, "frontierNodeId"));
            relationshipTypes.forEach(InternalGraphRequestBuilder::relationshipName);
        }
    }
    public record GraphSpec(String requestId, String rootEntityId, InternalGraphNodeType rootNodeType,
            int depth, int limit) {
        public GraphSpec {
            requireText(requestId, "requestId"); requireText(rootEntityId, "rootEntityId");
            Objects.requireNonNull(rootNodeType);
            if (rootNodeType == InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    || rootNodeType == InternalGraphNodeType.UNRECOGNIZED || depth < 0 || limit < 1)
                throw new IllegalArgumentException("Invalid graph read bounds or node type");
        }
    }
    private static String relationshipName(InternalGraphRelationshipType type) {
        Objects.requireNonNull(type);
        String prefix = "INTERNAL_GRAPH_RELATIONSHIP_TYPE_";
        if (type == InternalGraphRelationshipType.UNRECOGNIZED || !type.name().startsWith(prefix)
                || type == InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_UNSPECIFIED)
            throw new IllegalArgumentException("Invalid relationship type");
        return type.name().substring(prefix.length());
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
