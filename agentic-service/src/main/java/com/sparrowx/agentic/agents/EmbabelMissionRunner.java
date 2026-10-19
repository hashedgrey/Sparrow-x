package com.sparrowx.agentic.agents;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.PlannerMode;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Focused programmatic entrypoint into the Embabel platform.
 *
 * Planner choice is frozen in MissionIntent before this process begins.
 * ProcessOptions is authoritative for this invocation.
 */
public final class EmbabelMissionRunner {
    private static final Logger LOG = LoggerFactory.getLogger(EmbabelMissionRunner.class);
    private final AgentPlatform agentPlatform;
    public EmbabelMissionRunner(
            AgentPlatform agentPlatform
    ) {
        this.agentPlatform = Objects.requireNonNull(agentPlatform, "agentPlatform must not be null");
    }

    public MissionResult run(
            MissionRunInput input
    ) {
        Objects.requireNonNull(input, "input must not be null");

        PlannerType plannerType = plannerType(input.intent().plannerMode());
        LOG.info("mission.planner.requested missionId={} mode={} embabelPlanner={}",
                input.missionId(), input.intent().plannerMode(), plannerType);

        // Work with the process status before extracting its typed result. The
        // typed invoke() shortcut calls last(MissionResult.class) even when
        // Embabel has stopped at STUCK, resulting in a misleading NPE.
        AgentInvocation<MissionResult> invocation =
                AgentInvocation.create(agentPlatform, MissionResult.class)
                        .withProcessOptions(ProcessOptions.DEFAULT.withPlannerType(plannerType));

        AgentProcess process;
        try {
            // Use interruptible get(): Temporal cancels this virtual thread by
            // interruption when the parent Activity is cancelled.
            process = invocation.runAsync(input).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embabel mission invocation interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Embabel mission invocation failed", cause);
        }

        if (process.getStatus() != AgentProcessStatusCode.COMPLETED) {
            throw new IllegalStateException(
                    "Embabel mission " + input.missionId() + " ended in " +
                    process.getStatus() + " without a completed mission result");
        }

        // Preserve TypedInvocation's original typed-result extraction semantics.
        MissionResult result = process.last(MissionResult.class);
        if (result == null) {
            throw new IllegalStateException(
                    "Embabel completed mission " + input.missionId() +
                    " without publishing a MissionResult");
        }

        if (!input.missionId().equals(result.missionId())) {
            throw new IllegalStateException("Embabel returned a result for another mission");
        }

        LOG.info("mission.planner.completed missionId={} requested={} status={}",
                input.missionId(), plannerType, process.getStatus());
        return result;
    }

    private static PlannerType plannerType(
            PlannerMode mode
    ) {
        Objects.requireNonNull(mode, "plannerMode must not be null"
        );

        return switch (mode) {
            case GOAP -> PlannerType.GOAP;
            case HYBRID -> PlannerType.HYBRID;
        };
    }
}