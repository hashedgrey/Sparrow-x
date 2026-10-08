package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.adapters.internal.InternalClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphRequest;

import java.util.Objects;

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