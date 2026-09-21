package com.sparrowx.agentic.data.postgres.entities;

import com.sparrowx.agentic.mission.model.MissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Getter
@Table(
        name = "agentic_runtime_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_agentic_runtime_event_resume",
                        columnNames = {
                                "tenant_id",
                                "mission_id",
                                "resume_token"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_agentic_runtime_event_replay",
                        columnList =
                                "tenant_id, mission_id, id"
                ),
                @Index(
                        name = "idx_agentic_runtime_event_emitted",
                        columnList =
                                "tenant_id, mission_id, emitted_at"
                )
        }
)
public class RuntimeEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "tenant_id",
            nullable = false,
            updatable = false,
            length = 128
    )
    private String tenantId;

    @Column(
            name = "mission_id",
            nullable = false,
            updatable = false,
            length = 128
    )
    private String missionId;

    @Column(
            name = "resume_token",
            nullable = false,
            updatable = false,
            length = 512
    )
    private String resumeToken;

    /**
     * Durable stream payload discriminator.
     *
     * Current values:
     * - PROGRESS
     * - ANSWER_DELTA
     * - USAGE
     */
    @Column(
            name = "event_kind",
            nullable = false,
            updatable = false,
            length = 32
    )
    private String eventKind;

    /**
     * Projection used only for progress events.
     *
     * Answer deltas and usage events do not carry mission status directly.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "mission_status",
            nullable = true,
            updatable = false,
            length = 32
    )
    private MissionStatus missionStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "event_payload",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private Map<String, Object> eventPayload;

    @Column(
            name = "emitted_at",
            nullable = false,
            updatable = false
    )
    private Instant emittedAt;

    protected RuntimeEventEntity() {
    }

    public RuntimeEventEntity(
            String tenantId,
            String missionId,
            String resumeToken,
            String eventKind,
            MissionStatus missionStatus,
            Map<String, Object> eventPayload,
            Instant emittedAt
    ) {
        this.tenantId = tenantId;
        this.missionId = missionId;
        this.resumeToken = resumeToken;
        this.eventKind = eventKind;
        this.missionStatus = missionStatus;
        this.eventPayload = eventPayload == null
                ? Map.of()
                : Map.copyOf(eventPayload);
        this.emittedAt = emittedAt;
    }

    public Map<String, Object> getEventPayload() {
        return eventPayload == null
                ? Map.of()
                : Map.copyOf(eventPayload);
    }
}