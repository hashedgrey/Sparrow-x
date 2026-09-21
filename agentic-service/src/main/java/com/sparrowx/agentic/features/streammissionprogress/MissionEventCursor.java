package com.sparrowx.agentic.features.streammissionprogress;

import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.runtime.store.RuntimeEventStore;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MissionEventCursor
        implements AutoCloseable {

    private final Deque<MissionStreamEvent> replayEvents;

    private final RuntimeEventStore.EventSubscription subscription;

    private final AtomicBoolean closed;

    private volatile String resumeToken;
    private volatile boolean terminal;

    MissionEventCursor(
            Collection<MissionStreamEvent> replayEvents,
            RuntimeEventStore.EventSubscription subscription,
            String initialResumeToken
    ) {
        this.replayEvents =
                new ArrayDeque<>();

        this.subscription =
                subscription;

        this.closed =
                new AtomicBoolean(false);

        this.resumeToken =
                normalize(initialResumeToken);

        if (replayEvents != null) {

            for (MissionStreamEvent event : replayEvents) {

                if (event == null) {
                    continue;
                }

                this.replayEvents.addLast(event);

                if (!normalize(
                        event.resumeToken()
                ).isBlank()) {

                    this.resumeToken =
                            normalize(
                                    event.resumeToken()
                            );
                }

                this.terminal =
                        this.terminal
                                || isTerminal(event);
            }
        }
    }

    public Optional<MissionStreamEvent> next(
            Duration waitTimeout
    ) {
        Objects.requireNonNull(
                waitTimeout,
                "waitTimeout"
        );

        if (waitTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Wait timeout must not be negative."
            );
        }

        MissionStreamEvent replay =
                replayEvents.pollFirst();

        if (replay != null) {

            updatePosition(replay);

            if (isTerminal(replay)) {
                closeSubscription();
            }

            return Optional.of(replay);
        }

        if (closed.get()
                || terminal
                || subscription == null) {
            return Optional.empty();
        }

        Optional<MissionStreamEvent> event =
                subscription.next(
                        waitTimeout
                );

        if (event.isEmpty()) {
            return Optional.empty();
        }

        MissionStreamEvent received =
                event.orElseThrow();

        updatePosition(received);

        if (isTerminal(received)) {
            terminal = true;
            closeSubscription();
        }

        return Optional.of(received);
    }

    public String resumeToken() {

        String subscriptionToken =
                subscription == null
                        ? ""
                        : normalize(
                        subscription.resumeToken()
                );

        return subscriptionToken.isBlank()
                ? resumeToken
                : subscriptionToken;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        replayEvents.clear();
        closeSubscription();
    }

    private void updatePosition(
            MissionStreamEvent event
    ) {
        String eventToken =
                normalize(
                        event.resumeToken()
                );

        if (!eventToken.isBlank()) {
            resumeToken = eventToken;
        }

        terminal =
                terminal
                        || isTerminal(event);
    }

    private void closeSubscription() {
        if (closed.compareAndSet(
                false,
                true
        )
                && subscription != null) {

            subscription.close();
        }
    }

    static boolean isTerminal(
            MissionStreamEvent event
    ) {
        if (event instanceof MissionProgressEvent progress) {
            return isTerminal(
                    progress.status()
            );
        }

        return false;
    }

    static boolean isTerminal(
            MissionStatus status
    ) {
        return status == MissionStatus.COMPLETED
                || status == MissionStatus.FAILED_TERMINAL
                || status == MissionStatus.CANCELLED;
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }
}