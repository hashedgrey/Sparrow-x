package com.sparrowx.internal.mappers;

import com.sparrowx.internal.features.describeinternalcompanygraph.DescribeInternalCompanyGraphQuery;
import com.sparrowx.internal.features.describeinternalcompanygraph.DescribeInternalCompanyGraphResult;
import com.sparrowx.internal.grpc.DescribeInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.DescribeInternalCompanyGraphResponse;

import java.util.Objects;

public final class InternalGraphOntologyMapper {

    private InternalGraphOntologyMapper() {
    }

    public static DescribeInternalCompanyGraphQuery toQuery(
            DescribeInternalCompanyGraphRequest request
    ) {
        Objects.requireNonNull(request, "request is required");

        return new DescribeInternalCompanyGraphQuery(
                InternalMapper.tenantId(request.getContext()),
                InternalMapper.actorId(request.getContext()),
                InternalMapper.requestId(request.getContext())
        );
    }

    public static DescribeInternalCompanyGraphResponse toResponse(
            DescribeInternalCompanyGraphResult result
    ) {
        Objects.requireNonNull(result, "result is required");

        return DescribeInternalCompanyGraphResponse.newBuilder()
                .setOntologyJson(result.ontologyJson())
                .setSchemaVersion(result.schemaVersion())
                .build();
    }
}