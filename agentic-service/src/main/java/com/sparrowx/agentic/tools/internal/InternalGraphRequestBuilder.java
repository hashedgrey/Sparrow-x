package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.adapters.internal.InternalClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.ExpandInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import com.sparrowx.internal.grpc.InternalGraphRelationshipType;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphRequest;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class InternalGraphRequestBuilder {

    private final InternalClientMapper clientMapper;

    public InternalGraphRequestBuilder(
            InternalClientMapper clientMapper) {

        this.clientMapper = Objects.requireNonNull(
                clientMapper,
                "clientMapper must not be null");
    }

    public ReadInternalCompanyGraphRequest buildCompanyGraph(
            MissionContext context,
            GraphSpec spec) {

        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        return ReadInternalCompanyGraphRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(
                        context,
                        spec.requestId()))
                .setRootEntityId(spec.rootEntityId())
                .setRootNodeType(spec.rootNodeType())
                .setDepth(spec.depth())
                .setLimit(spec.limit())
                .build();
    }

    public ExpandInternalCompanyGraphRequest buildGraphExpansion(
            MissionContext context,
            ExpansionSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        List<String> frontierIds = spec.frontierNodeIds()
                .stream()
                .sorted()
                .toList();

        List<String> relationshipNames = spec.relationshipTypes()
                .stream()
                .map(InternalGraphRequestBuilder::domainRelationshipName)
                .sorted()
                .toList();

        return ExpandInternalCompanyGraphRequest.newBuilder()
                .setContext(
                        clientMapper.toRequestContext(
                                context,
                                spec.requestId()
                        )
                )
                .addAllFrontierNodeIds(frontierIds)
                .addAllRelationshipTypes(relationshipNames)
                .setLimit(spec.maxEdges())
                .build();
    }

    public record ExpansionSpec(
            String requestId,
            Set<String> frontierNodeIds,
            Set<InternalGraphRelationshipType> relationshipTypes,
            int maxEdges
    ) {
        public ExpansionSpec {
            requestId = requireText(requestId, "requestId");

            if (frontierNodeIds == null || frontierNodeIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "frontierNodeIds must not be empty"
                );
            }

            for (String nodeId : frontierNodeIds) {
                requireText(nodeId, "frontierNodeId");
            }

            frontierNodeIds = Set.copyOf(frontierNodeIds);

            if (relationshipTypes == null || relationshipTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "relationshipTypes must not be empty"
                );
            }

            for (InternalGraphRelationshipType type : relationshipTypes) {
                domainRelationshipName(type);
            }

            relationshipTypes = Set.copyOf(relationshipTypes);

            if (maxEdges <= 0) {
                throw new IllegalArgumentException(
                        "maxEdges must be positive"
                );
            }
        }
    }

    private static String domainRelationshipName(
            InternalGraphRelationshipType type
    ) {
        Objects.requireNonNull(
                type,
                "relationshipType must not be null"
        );

        if (type == InternalGraphRelationshipType
                .INTERNAL_GRAPH_RELATIONSHIP_TYPE_UNSPECIFIED
                || type == InternalGraphRelationshipType.UNRECOGNIZED) {

            throw new IllegalArgumentException(
                    "relationshipType must be specified"
            );
        }

        String prefix = "INTERNAL_GRAPH_RELATIONSHIP_TYPE_";
        String name = type.name();

        if (!name.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "Unsupported relationship enum name: " + name
            );
        }

        return name.substring(prefix.length());
    }

    public record GraphSpec(
            String requestId,
            String rootEntityId,
            InternalGraphNodeType rootNodeType,
            int depth,
            int limit) {

        public GraphSpec {
            requestId = requireText(requestId, "requestId");
            rootEntityId = requireText(
                    rootEntityId,
                    "rootEntityId");

            rootNodeType = Objects.requireNonNull(
                    rootNodeType,
                    "rootNodeType must not be null");

            if (rootNodeType == InternalGraphNodeType
                    .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    || rootNodeType
                    == InternalGraphNodeType.UNRECOGNIZED) {

                throw new IllegalArgumentException(
                        "rootNodeType must be specified");
            }

            if (depth < 0) {
                throw new IllegalArgumentException(
                        "depth must not be negative");
            }

            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "limit must be positive");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }

        return value;
    }
}