package com.sparrowx.agentic.temporal.workflow;

import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.runtime.gate.ApprovalService;
import com.sparrowx.agentic.temporal.activity.MissionActivities;
import com.sparrowx.agentic.temporal.model.MissionWorkflowCommand;
import com.sparrowx.agentic.temporal.model.MissionWorkflowInput;
import com.sparrowx.agentic.temporal.model.MissionWorkflowOutcome;
import com.sparrowx.agentic.temporal.model.MissionWorkflowState;
import com.sparrowx.agentic.temporal.model.MissionWorkflowState.PendingGate;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Durable recovery shell around one complete Embabel execution.
 */
public final class MissionWorkflowImpl implements MissionWorkflow {

    private static final Duration GATE_TIMEOUT = Duration.ofDays(7);

    private final MissionActivities activities = Workflow.newActivityStub(
            MissionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(2))
                            .setMaximumAttempts(1)
                            .build())
                    .build()
    );

    private MissionWorkflowInput input;
    private MissionWorkflowState workflowState;
    private CancellationScope embabelScope;

    @Override
    public MissionWorkflowOutcome run(
            MissionWorkflowInput workflowInput,
            MissionWorkflowState continuedState
    ) {
        input = Objects.requireNonNull(
                workflowInput,
                "workflowInput must not be null"
        );
        workflowState = continuedState == null
                ? MissionWorkflowState.initial(input, now())
                : continuedState;

        if (input.selectedPath() == MissionPath.GOVERNED
                && workflowState.approvedGateIds().isEmpty()
                && !workflowState.terminal()) {
            waitForPreflightApproval();
        }

        if (!workflowState.terminal()) {
            workflowState = workflowState.running();
            embabelScope = Workflow.newCancellationScope(
                    this::invokeEmbabel
            );
            try {
                embabelScope.run();

            } catch (CanceledFailure cancelled) {
                cancelPersistedMission();
            } catch (ActivityFailure failure) {

                if (workflowState.status() == MissionStatus.CANCELLED) {
                    cancelPersistedMission();
                    return terminalOutcome();
                }
                failPersistedMission(failure);
            }
        }

        return terminalOutcome();
    }

    private void invokeEmbabel() {

        MissionActivities.RunMissionResult result =
                activities.runMission(
                        new MissionActivities.RunMissionRequest(input, workflowState.approvedGateIds(),
                                workflowState.startedAt()
                        )
                );

        if (workflowState.status() == MissionStatus.CANCELLED) {
            return;
        }

        if (workflowState.terminal()) {
            return;
        }

        workflowState = workflowState.completed(result.resultRef(), result.completedAt());
    }

    private void waitForPreflightApproval() {
        Instant createdAt = now();
        Instant expiresAt = createdAt.plus(GATE_TIMEOUT);
        String gateId = input.missionId() + ":gate:preflight";
        PendingGate gate = new PendingGate(
                gateId,
                "Approve governed mission",
                "Authorize the governed Embabel mission before execution",
                Set.of("MISSION_REVIEWER"),
                createdAt,
                expiresAt
        );
        workflowState = workflowState.waitingFor(gate);

        activities.openGate(new MissionActivities.OpenGateRequest(
                gateId,
                new ApprovalService.OpenRequest(
                        gateId,
                        input.tenantId(),
                        input.missionId(),
                        gate.title(),
                        gate.reason(),
                        List.copyOf(gate.requiredReviewerRoles()),
                        Map.of(
                                "path", input.selectedPath().name(),
                                "requestId", input.requestId(),
                                "frozenVersionRef", input.frozenVersionRef()
                        ),
                        createdAt,
                        expiresAt
                )
        ));

        boolean decided = Workflow.await(
                GATE_TIMEOUT,
                () -> workflowState.pendingGate() == null
                        || workflowState.terminal()
        );
        if (!decided && !workflowState.terminal()) {
            workflowState = workflowState.timedOut(
                    "Mission approval expired",
                    now()
            );
            cancelPersistedMission();
        }
    }

    private void failPersistedMission(
            ActivityFailure failure
    ) {
        String errorReference =
                "activity:"
                        + input.missionId()
                        + ":RunEmbabelMission";

        String message =
                failure.getMessage() == null
                        || failure.getMessage().isBlank()
                        ? "Embabel mission execution failed"
                        : failure.getMessage();

        MissionActivities.FailMissionResult result =
                activities.failMission(
                        new MissionActivities.FailMissionRequest(
                                input.tenantId(),
                                input.missionId(),
                                "EMBABEL_EXECUTION_FAILED",
                                message,
                                errorReference
                        )
                );

        workflowState = workflowState.failed(
                result.errorReference(),
                result.completedAt()
        );
    }
    @Override
    public MissionWorkflowState approve(
            MissionWorkflowCommand command
    ) {
        awaitInitialized();
        requireType(command, MissionWorkflowCommand.CommandType.APPROVE);
        if (workflowState.alreadyProcessed(command)) {
            return workflowState;
        }
        workflowState.requirePendingGate(command);
        Instant decidedAt = now();
        activities.recordGateDecision(
                new MissionActivities.GateDecisionRequest(
                        command.updateId(),
                        command,
                        decidedAt
                )
        );
        workflowState = workflowState.approved(command, decidedAt);
        return workflowState;
    }

    @Override
    public MissionWorkflowState reject(
            MissionWorkflowCommand command
    ) {
        awaitInitialized();
        requireType(command, MissionWorkflowCommand.CommandType.REJECT);
        if (workflowState.alreadyProcessed(command)) {
            return workflowState;
        }
        workflowState.requirePendingGate(command);
        Instant decidedAt = now();
        activities.recordGateDecision(
                new MissionActivities.GateDecisionRequest(
                        command.updateId(),
                        command,
                        decidedAt
                )
        );
        workflowState = workflowState.rejected(command, decidedAt);
        cancelPersistedMission();
        return workflowState;
    }

    @Override
    public MissionWorkflowState cancel(MissionWorkflowCommand command) {
        awaitInitialized();

        requireType(command, MissionWorkflowCommand.CommandType.CANCEL);

        if (workflowState.alreadyProcessed(command)) {
            return workflowState;
        }

        workflowState = workflowState.cancelled(command, now());
        cancelPersistedMission();
        if (embabelScope != null) {
            embabelScope.cancel("Mission cancellation requested");
        }

        return workflowState;
    }

    @Override
    public MissionWorkflowState state() {
        awaitInitialized();
        return workflowState;
    }

    private void cancelPersistedMission() {
        if (workflowState.status() != MissionStatus.CANCELLED) {
            return;
        }
        Instant completed = activities.cancelMission(
                new MissionActivities.CancelMissionRequest(
                        input.tenantId(),
                        input.missionId(),
                        workflowState.cancellationReason().isBlank()
                                ? "Mission cancelled"
                                : workflowState.cancellationReason()
                )
        );
        if (workflowState.completedAt() == null) {
            workflowState = workflowState.timedOut(
                    workflowState.cancellationReason().isBlank()
                            ? "Mission cancelled"
                            : workflowState.cancellationReason(),
                    completed
            );
        }
    }

    private MissionWorkflowOutcome terminalOutcome() {
        if (!workflowState.terminal()) {
            throw new IllegalStateException(
                    "Workflow outcome requires terminal state"
            );
        }

        return new MissionWorkflowOutcome(
                input.missionId(),
                input.tenantId(),
                workflowState.status(),
                workflowState.status() == MissionStatus.COMPLETED
                        ? workflowState.resultRef()
                        : null,
                workflowState.status() == MissionStatus.FAILED_TERMINAL
                        ? workflowState.errorReference()
                        : "",
                workflowState.startedAt(),
                Objects.requireNonNull(
                        workflowState.completedAt(),
                        "terminal state requires completedAt"
                ),
                workflowState.status() == MissionStatus.CANCELLED
                        ? workflowState.cancelledAt()
                        : null
        );
    }

    private void awaitInitialized() {
        Workflow.await(() -> workflowState != null);
    }

    private static void requireType(
            MissionWorkflowCommand command,
            MissionWorkflowCommand.CommandType expected
    ) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.type() != expected) {
            throw new IllegalArgumentException(
                    "Workflow Update command type mismatch"
            );
        }
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
}
