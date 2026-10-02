package com.sparrowx.internal.models;

import com.sparrowx.internal.valueobjects.CreatedAt;
import com.sparrowx.internal.valueobjects.EmailAddress;
import com.sparrowx.internal.valueobjects.EngineerId;
import com.sparrowx.internal.valueobjects.EngineerRole;
import com.sparrowx.internal.valueobjects.TenantId;
import com.sparrowx.internal.valueobjects.UpdatedAt;

public record Engineer(
        EngineerId engineerId,
        TenantId tenantId,
        String fullName,
        EmailAddress email,
        EngineerRole role,
        CreatedAt createdAt,
        UpdatedAt updatedAt
) {
    public Engineer {
        if (engineerId == null) {
            throw new IllegalArgumentException("engineerId is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }

        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName is required");
        }

        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }

        if (role == null) {
            role = EngineerRole.INTERN;
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }

        fullName = fullName.trim();
    }

    public static Engineer create(
            EngineerId engineerId,
            TenantId tenantId,
            String fullName,
            EmailAddress email,
            EngineerRole role,
            CreatedAt createdAt,
            UpdatedAt updatedAt
    ) {
        return new Engineer(
                engineerId,
                tenantId,
                fullName,
                email,
                role,
                createdAt,
                updatedAt
        );
    }
}