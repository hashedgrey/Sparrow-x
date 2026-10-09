package com.sparrowx.internal.features.expandinternalcompanygraph;

import buildingblocks.core.queries.Query;
import com.sparrowx.internal.exceptions.InternalValidationException;

import java.util.Set;

public record ExpandInternalCompanyGraphQuery(
        String tenantId,
        String actorId,
        String requestId,
        Set<String> frontierNodeIds,
        Set<String> relationshipTypes,
        int limit
) implements Query<ExpandInternalCompanyGraphResult> {

    public ExpandInternalCompanyGraphQuery {
        requireText(tenantId, "tenantId");
        requireText(actorId, "actorId");
        requireText(requestId, "requestId");

        frontierNodeIds = requiredSet(
                frontierNodeIds,
                "frontierNodeIds"
        );

        relationshipTypes = requiredSet(
                relationshipTypes,
                "relationshipTypes"
        );

        if (limit < 1) {
            throw new InternalValidationException(
                    "limit must be positive"
            );
        }
    }

    private static void requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new InternalValidationException(
                    fieldName + " is required"
            );
        }
    }

    private static Set<String> requiredSet(
            Set<String> values,
            String fieldName
    ) {
        if (values == null || values.isEmpty()) {
            throw new InternalValidationException(
                    fieldName + " must not be empty"
            );
        }

        for (String value : values) {
            requireText(value, fieldName + " entry");
        }

        return Set.copyOf(values);
    }
}