package com.sparrowx.internal.data.postgres.mappers;

import com.sparrowx.internal.models.Permission;
import com.sparrowx.internal.valueobjects.CreatedAt;
import com.sparrowx.internal.valueobjects.PermissionId;
import com.sparrowx.internal.valueobjects.TenantId;
import com.sparrowx.internal.valueobjects.UpdatedAt;

public final class PermissionPersistenceMapper {

    private PermissionPersistenceMapper() {
    }

    public static PermissionEntity toEntity(
            Permission permission
    ) {
        return new PermissionEntity(
                permission.permissionId().value(),
                permission.tenantId().value(),
                permission.name(),
                permission.description(),
                permission.createdAt().value(),
                permission.updatedAt().value()
        );
    }

    public static Permission toDomain(
            PermissionEntity entity
    ) {
        return new Permission(
                PermissionId.of(entity.getPermissionId()),
                TenantId.of(entity.getTenantId()),
                entity.getName(),
                entity.getDescription(),
                CreatedAt.of(entity.getCreatedAt()),
                UpdatedAt.of(entity.getUpdatedAt())
        );
    }
}