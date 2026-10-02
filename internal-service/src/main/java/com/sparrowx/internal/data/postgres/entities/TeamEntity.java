package com.sparrowx.internal.data.postgres.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(
        name = "teams",
        indexes = {
                @Index(
                        name = "idx_teams_tenant_slug",
                        columnList = "tenant_id,slug",
                        unique = true
                ),
                @Index(
                        name = "idx_teams_tenant_name",
                        columnList = "tenant_id,name"
                )
        }
)
public class TeamEntity {

    @Id
    @Column(name = "team_id", nullable = false, updatable = false)
    private String teamId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamEntity() {
    }

    public TeamEntity(
            String teamId,
            String tenantId,
            String name,
            String slug,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.teamId = teamId;
        this.tenantId = tenantId;
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

}