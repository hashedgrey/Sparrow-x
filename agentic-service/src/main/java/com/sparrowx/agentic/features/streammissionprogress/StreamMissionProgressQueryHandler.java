package com.sparrowx.agentic.features.streammissionprogress;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.agentic.exceptions.AgenticServiceException;
import com.sparrowx.agentic.exceptions.MissionNotFoundException;
import com.sparrowx.agentic.mission.model.Mission;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.mission.store.MissionStore;
import com.sparrowx.agentic.runtime.store.RuntimeEventStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class StreamMissionProgressQueryHandler
        implements QueryHandler<
        StreamMissionProgressQuery,
        MissionEventCursor
        > {

    private static final int REPLAY_PAGE_SIZE =
            500;

    private final StreamMissionProgressQueryValidator validator;
    private final MissionStore missionStore;
    private final RuntimeEventStore runtimeEventStore;

    public StreamMissionProgressQueryHandler(
            StreamMissionProgressQueryValidator validator,
            MissionStore missionStore,
            RuntimeEventStore runtimeEventStore
    ) {
        this.validator = validator;
        this.missionStore = missionStore;
        this.runtimeEventStore = runtimeEventStore;
    }

    @Override
    public MissionEventCursor handle(
            StreamMissionProgressQuery query
    ) {
        validator.validate(query);

        Mission mission =
                missionStore
                        .findById(
                                query.tenantId(),
                                query.missionId()
                        )
                        .orElseThrow(() ->
                                new MissionNotFoundException(
                                        query.tenantId(),
                                        query.missionId()
                                )
                        );

        if (!query.tenantId().equals(
                mission.tenantId()
        )
                || !query.missionId().equals(
                mission.missionId()
        )) {

            throw new AgenticServiceException(
                    "Mission lookup returned cross-scoped state."
            );
        }

        List<MissionStreamEvent> replay =
                new ArrayList<>();

        String cursorToken =
                query.resumeToken();

        boolean terminalEventSeen =
                false;

        while (!terminalEventSeen) {

            List<MissionStreamEvent> page =
                    runtimeEventStore.readAfter(
                            query.tenantId(),
                            query.missionId(),
                            cursorToken,
                            REPLAY_PAGE_SIZE
                    );

            if (page == null) {
                throw new AgenticServiceException(
                        "Runtime event store returned a null replay page."
                );
            }

            if (page.isEmpty()) {
                break;
            }

            String previousToken =
                    cursorToken;

            for (MissionStreamEvent event : page) {

                validateEventScope(
                        query,
                        event
                );

                replay.add(event);

                if (event.resumeToken() != null
                        && !event.resumeToken().isBlank()) {

                    cursorToken =
                            event.resumeToken().trim();
                }

                if (MissionEventCursor.isTerminal(event)) {
                    terminalEventSeen = true;
                    break;
                }
            }

            if (terminalEventSeen
                    || page.size() < REPLAY_PAGE_SIZE
                    || cursorToken.equals(previousToken)) {

                break;
            }
        }

        boolean durableMissionIsTerminal =
                MissionEventCursor.isTerminal(
                        mission.status()
                );

        RuntimeEventStore.EventSubscription subscription =
                null;

        if (!terminalEventSeen
                && !durableMissionIsTerminal) {

            subscription =
                    runtimeEventStore.subscribeAfter(
                            query.tenantId(),
                            query.missionId(),
                            cursorToken
                    );

            if (subscription == null) {
                throw new AgenticServiceException(
                        "Runtime event store returned no event subscription."
                );
            }
        }

        return new MissionEventCursor(
                replay,
                subscription,
                cursorToken
        );
    }

    private static void validateEventScope(
            StreamMissionProgressQuery query,
            MissionStreamEvent event
    ) {
        if (event == null) {
            throw new AgenticServiceException(
                    "Runtime event store returned a null event."
            );
        }

        if (!query.missionId().equals(
                event.missionId()
        )) {
            throw new AgenticServiceException(
                    "Runtime event belongs to a different mission."
            );
        }
    }
}