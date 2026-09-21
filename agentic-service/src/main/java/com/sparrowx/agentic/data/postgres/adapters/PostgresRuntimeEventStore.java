package com.sparrowx.agentic.data.postgres.adapters;

import com.sparrowx.agentic.data.postgres.entities.RuntimeEventEntity;
import com.sparrowx.agentic.data.postgres.mappers.RuntimeEventEntityMapper;
import com.sparrowx.agentic.data.postgres.repositories.RuntimeEventJpaRepository;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.runtime.store.RuntimeEventStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * PostgreSQL-backed replay and tailing store for public mission stream events.
 *
 * The store persists progress, answer-delta and usage events in one ordered
 * mission stream.
 *
 * Empty resume tokens start before the earliest retained event. Temporal
 * history remains the execution authority; these rows exist only for the
 * durable public streaming projection.
 */
@Component
public final class PostgresRuntimeEventStore
        implements RuntimeEventStore {

    private static final Duration POLL_INTERVAL =
            Duration.ofMillis(100);

    private final RuntimeEventJpaRepository repository;
    private final RuntimeEventEntityMapper mapper;

    public PostgresRuntimeEventStore(
            RuntimeEventJpaRepository repository,
            RuntimeEventEntityMapper mapper
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository must not be null"
        );

        this.mapper = Objects.requireNonNull(
                mapper,
                "mapper must not be null"
        );
    }

    @Override
    public MissionStreamEvent append(
            String tenantId,
            MissionStreamEvent event
    ) {
        tenantId = requireText(
                tenantId,
                "tenantId"
        );

        Objects.requireNonNull(
                event,
                "event must not be null"
        );

        if (event.resumeToken() == null
                || event.resumeToken().isBlank()) {
            throw new IllegalArgumentException(
                    "event resumeToken must not be blank"
            );
        }

        Optional<RuntimeEventEntity> existing =
                repository
                        .findByTenantIdAndMissionIdAndResumeToken(
                                tenantId,
                                event.missionId(),
                                event.resumeToken()
                        );

        if (existing.isPresent()) {
            return requireIdempotentEvent(
                    event,
                    existing.get()
            );
        }

        try {
            RuntimeEventEntity saved =
                    repository.saveAndFlush(
                            mapper.toEntity(
                                    tenantId,
                                    event
                            )
                    );

            return mapper.toDomain(saved);

        } catch (DataIntegrityViolationException exception) {

            RuntimeEventEntity concurrent =
                    repository
                            .findByTenantIdAndMissionIdAndResumeToken(
                                    tenantId,
                                    event.missionId(),
                                    event.resumeToken()
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Runtime event write conflicted "
                                                    + "without a persisted row",
                                            exception
                                    )
                            );

            return requireIdempotentEvent(
                    event,
                    concurrent
            );
        }
    }

    @Override
    public List<MissionStreamEvent> readAfter(
            String tenantId,
            String missionId,
            String resumeToken,
            int limit
    ) {
        tenantId = requireText(
                tenantId,
                "tenantId"
        );

        missionId = requireText(
                missionId,
                "missionId"
        );

        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit must be positive"
            );
        }

        long afterId = resolveCursorId(
                tenantId,
                missionId,
                resumeToken
        );

        return repository
                .findByTenantIdAndMissionIdAndIdGreaterThanOrderByIdAsc(
                        tenantId,
                        missionId,
                        afterId,
                        PageRequest.of(
                                0,
                                limit
                        )
                )
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public EventSubscription subscribeAfter(
            String tenantId,
            String missionId,
            String resumeToken
    ) {
        tenantId = requireText(
                tenantId,
                "tenantId"
        );

        missionId = requireText(
                missionId,
                "missionId"
        );

        long afterId = resolveCursorId(
                tenantId,
                missionId,
                resumeToken
        );

        return new PostgresEventSubscription(
                tenantId,
                missionId,
                afterId,
                normalize(resumeToken)
        );
    }

    private long resolveCursorId(
            String tenantId,
            String missionId,
            String resumeToken
    ) {
        String normalized =
                normalize(resumeToken);

        if (normalized.isEmpty()) {
            return 0L;
        }

        return repository
                .findByTenantIdAndMissionIdAndResumeToken(
                        tenantId,
                        missionId,
                        normalized
                )
                .map(RuntimeEventEntity::getId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown or expired resume token"
                        )
                );
    }

    private MissionStreamEvent requireIdempotentEvent(
            MissionStreamEvent requested,
            RuntimeEventEntity entity
    ) {
        MissionStreamEvent existing =
                mapper.toDomain(entity);

        if (!existing.equals(requested)) {
            throw new IllegalStateException(
                    "Resume token already exists with "
                            + "different event content: "
                            + requested.resumeToken()
            );
        }

        return existing;
    }

    private final class PostgresEventSubscription
            implements EventSubscription {

        private final String tenantId;
        private final String missionId;

        private final AtomicBoolean closed =
                new AtomicBoolean(false);

        private volatile long cursorId;
        private volatile String resumeToken;

        private PostgresEventSubscription(
                String tenantId,
                String missionId,
                long cursorId,
                String resumeToken
        ) {
            this.tenantId = tenantId;
            this.missionId = missionId;
            this.cursorId = cursorId;
            this.resumeToken = resumeToken;
        }

        @Override
        public Optional<MissionStreamEvent> next(
                Duration waitTimeout
        ) {
            Objects.requireNonNull(
                    waitTimeout,
                    "waitTimeout must not be null"
            );

            if (waitTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "waitTimeout must not be negative"
                );
            }

            if (closed.get()) {
                return Optional.empty();
            }

            long timeoutNanos =
                    waitTimeout.toNanos();

            long deadline =
                    System.nanoTime() + timeoutNanos;

            while (!closed.get()) {

                Optional<RuntimeEventEntity> next =
                        repository
                                .findByTenantIdAndMissionIdAndIdGreaterThanOrderByIdAsc(
                                        tenantId,
                                        missionId,
                                        cursorId,
                                        PageRequest.of(
                                                0,
                                                1
                                        )
                                )
                                .stream()
                                .findFirst();

                if (next.isPresent()) {

                    RuntimeEventEntity entity =
                            next.get();

                    cursorId =
                            entity.getId();

                    resumeToken =
                            entity.getResumeToken();

                    return Optional.of(
                            mapper.toDomain(entity)
                    );
                }

                if (timeoutNanos == 0L) {
                    return Optional.empty();
                }

                long remaining =
                        deadline - System.nanoTime();

                if (remaining <= 0L) {
                    return Optional.empty();
                }

                LockSupport.parkNanos(
                        Math.min(
                                remaining,
                                POLL_INTERVAL.toNanos()
                        )
                );

                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }

            return Optional.empty();
        }

        @Override
        public String resumeToken() {
            return resumeToken;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static String requireText(
            String value,
            String field
    ) {
        String normalized =
                normalize(value);

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return normalized;
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }
}