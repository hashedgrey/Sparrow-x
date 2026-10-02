package com.sparrowx.agentic.agents;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessOptions;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.PlannerMode;

import java.util.Objects;

/**
 * Focused programmatic entrypoint into the Embabel platform.
 *
 * Planner choice is frozen in MissionIntent before this process begins.
 * ProcessOptions is authoritative for this invocation.
 */
public final class EmbabelMissionRunner {

    private final AgentPlatform agentPlatform;

    public EmbabelMissionRunner(
            AgentPlatform agentPlatform
    ) {
        this.agentPlatform = Objects.requireNonNull(
                agentPlatform,
                "agentPlatform must not be null"
        );
    }

    public MissionResult run(
            MissionRunInput input
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        PlannerType plannerType =
                plannerType(
                        input.intent()
                                .plannerMode()
                );

        MissionResult result =
                AgentInvocation
                        .create(
                                agentPlatform,
                                MissionResult.class
                        )
                        .withProcessOptions(
                                ProcessOptions.DEFAULT
                                        .withPlannerType(
                                                plannerType
                                        )
                        )
                        .invoke(
                                input
                        );

        if (!input.missionId()
                .equals(result.missionId())) {

            throw new IllegalStateException(
                    "Embabel returned a result for another mission"
            );
        }

        return result;
    }

    private static PlannerType plannerType(
            PlannerMode mode
    ) {
        Objects.requireNonNull(
                mode,
                "plannerMode must not be null"
        );

        return switch (mode) {
            case GOAP -> PlannerType.GOAP;
            case HYBRID -> PlannerType.HYBRID;
        };
    }
}