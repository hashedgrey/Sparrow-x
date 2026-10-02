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
        name = "engineer_team_memberships",
        indexes = {
                @Index(
                        name = "idx_engineer_team_memberships_engineer",
                        columnList = "tenant_id,engineer_id"
                ),
                @Index(
                        name = "idx_engineer_team_memberships_team",
                        columnList = "tenant_id,team_id"
                )
        }
)
public class EngineerTeamMembershipEntity {

    @Id
    @Column(
            name = "membership_id",
            nullable = false,
            updatable = false
    )
    private String membershipId;

    @Column(
            name = "tenant_id",
            nullable = false,
            updatable = false
    )
    private String tenantId;

    @Column(
            name = "engineer_id",
            nullable = false,
            updatable = false
    )
    private String engineerId;

    @Column(
            name = "team_id",
            nullable = false,
            updatable = false
    )
    private String teamId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(
            name = "joined_at",
            nullable = false,
            updatable = false
    )
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EngineerTeamMembershipEntity() {
    }

    public EngineerTeamMembershipEntity(
            String membershipId,
            String tenantId,
            String engineerId,
            String teamId,
            boolean primary,
            Instant joinedAt,
            Instant leftAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.membershipId = membershipId;
        this.tenantId = tenantId;
        this.engineerId = engineerId;
        this.teamId = teamId;
        this.primary = primary;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}