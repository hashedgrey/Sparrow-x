package com.sparrowx.agentic.runtime.store;

import com.sparrowx.agentic.mission.model.MissionStreamEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped persistence and tailing port for durable public mission
 * stream events.
 *
 * Events include progress updates, answer deltas and aggregate usage.
 */
public interface RuntimeEventStore {

    /**
     * Idempotently appends an event by stable resume token.
     */
    MissionStreamEvent append(
            String tenantId,
            MissionStreamEvent event
    );

    /**
     * Reads strictly after the supplied token.
     *
     * An empty token starts at the earliest retained mission event.
     */
    List<MissionStreamEvent> readAfter(
            String tenantId,
            String missionId,
            String resumeToken,
            int limit
    );

    /**
     * Creates a durable cursor that backfills events committed between replay
     * completion and subscription creation.
     */
    EventSubscription subscribeAfter(
            String tenantId,
            String missionId,
            String resumeToken
    );

    interface EventSubscription extends AutoCloseable {

        Optional<MissionStreamEvent> next(
                Duration waitTimeout
        );

        String resumeToken();

        @Override
        void close();
    }
}