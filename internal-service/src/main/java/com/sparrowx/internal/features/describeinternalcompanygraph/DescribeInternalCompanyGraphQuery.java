package com.sparrowx.internal.features.describeinternalcompanygraph;

import buildingblocks.core.queries.Query;
import com.sparrowx.internal.exceptions.InternalValidationException;

public record DescribeInternalCompanyGraphQuery(
        String tenantId,
        String actorId,
        String requestId
) implements Query<DescribeInternalCompanyGraphResult> {

    public DescribeInternalCompanyGraphQuery {
        requireText(tenantId, "tenantId");
        requireText(actorId, "actorId");
        requireText(requestId, "requestId");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InternalValidationException(fieldName + " is required");
        }
    }
}