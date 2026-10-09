package com.sparrowx.internal.mappers;

import com.sparrowx.internal.features.expandinternalcompanygraph.ExpandInternalCompanyGraphQuery;
import com.sparrowx.internal.features.expandinternalcompanygraph.ExpandInternalCompanyGraphResult;
import com.sparrowx.internal.grpc.ExpandInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.ExpandInternalCompanyGraphResponse;

import java.util.LinkedHashSet;
import java.util.Objects;

public final class InternalGraphExpansionMapper {

    private InternalGraphExpansionMapper() {
    }

    public static ExpandInternalCompanyGraphQuery toQuery(
            ExpandInternalCompanyGraphRequest request
    ) {
        Objects.requireNonNull(request, "request is required");

        return new ExpandInternalCompanyGraphQuery(
                InternalMapper.tenantId(request.getContext()),
                InternalMapper.actorId(request.getContext()),
                InternalMapper.requestId(request.getContext()),
                new LinkedHashSet<>(
                        request.getFrontierNodeIdsList()
                ),
                new LinkedHashSet<>(
                        request.getRelationshipTypesList()
                ),
                request.getLimit()
        );
    }

    public static ExpandInternalCompanyGraphResponse toResponse(
            ExpandInternalCompanyGraphResult result
    ) {
        Objects.requireNonNull(result, "result is required");

        return ExpandInternalCompanyGraphResponse.newBuilder()
                .setGraph(
                        InternalGraphMapper.toProto(result.graph())
                )
                .setTruncated(result.truncated())
                .build();
    }
}