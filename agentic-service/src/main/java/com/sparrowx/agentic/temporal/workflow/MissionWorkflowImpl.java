package com.sparrowx.agentic.temporal.workflow;

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
 * Durable recovery and human-wait shell around Embabel execution.
 *
 * Temporal does not own Embabel planning or blackboard state.
 *
 * HITL can occur at two boundaries:
 *
 * 1. preflight:
 *    the submitted mission explicitly requires human review;
 *
 * 2. action-level:
 *    Embabel reaches a protected action and runMission returns
 *    APPROVAL_REQUIRED.
 *
 * Approved gate IDs are passed back into the next Embabel invocation so the
 * protected action can proceed without reopening the same gate.
 */
public final class MissionWorkflowImpl
        implements MissionWorkflow {

    private static final Duration GATE_TIMEOUT =
            Duration.ofDays(7);

    private final MissionActivities activities =
            Workflow.newActivityStub(
                    MissionActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(
                                    Duration.ofMinutes(30)
                            )
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(2))
                                            .setBackoffCoefficient(2.0)
                                            .setMaximumInterval(Duration.ofMinutes(2))
                                            .setMaximumAttempts(1)
                                            .build()).build()
            );

    private MissionWorkflowInput input;
    private MissionWorkflowState workflowState;
    private CancellationScope embabelScope;

    @Override
    public MissionWorkflowOutcome run(
            MissionWorkflowInput workflowInput,
            MissionWorkflowState continuedState
    ) {
        input = Objects.requireNonNull(workflowInput, "workflowInput must not be null");
        workflowState = continuedState == null
                        ? MissionWorkflowState.initial(
                        input, now()) : continuedState;

        /*
         * Mission-level HITL.
         *
         * This is deliberately separate from FAST/RESEARCH.
         * It creates one approval before Embabel execution begins.
         */
        if (input.requiresHumanReview() && workflowState.approvedGateIds().isEmpty()
                && !workflowState.terminal()) {
            waitForPreflightApproval();
        }

        if (!workflowState.terminal()) {
            workflowState = workflowState.running();
            embabelScope = Workflow.newCancellationScope(this::invokeEmbabel);

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

    /**
     * Executes Embabel until:
     *
     * - a terminal MissionResult is produced; or
     * - execution reaches an action-level human gate.
     *
     * After an approval the loop invokes Embabel again with the newly approved
     * gate ID.
     */
    private void invokeEmbabel() {

        while (!workflowState.terminal()) {
            workflowState = workflowState.running();

            MissionActivities.RunMissionResult result =
                    activities.runMission(
                            new MissionActivities.RunMissionRequest(input, workflowState.approvedGateIds(),
                                    workflowState.startedAt()
                            )
                    );

            if (workflowState.status() == MissionStatus.CANCELLED) {
                return;
            }

            switch (result.disposition()) {
                case COMPLETED -> {
                    workflowState =
                            workflowState.completed(
                                    Objects.requireNonNull(result.resultRef(),
                                            "completed run requires resultRef"),
                                    Objects.requireNonNull(result.completedAt(),
                                            "completed run requires completedAt")
                            );
                    return;
                }

                case APPROVAL_REQUIRED -> {

                    waitForActionApproval(
                            Objects.requireNonNull(result.approvalRequired(),
                                    "approvalRequired must not be null")
                    );


                }
            }
        }
    }

    /**
     * Mission-wide approval requested explicitly by the mission constraints.
     *
     * This is a single preflight gate and is not generated once per action.
     */
    private void waitForPreflightApproval() {
        Instant createdAt = now();
        Instant expiresAt = createdAt.plus(GATE_TIMEOUT);
        String gateId = input.missionId() + ":gate:preflight";

        PendingGate gate =
                new PendingGate(
                        gateId,
                        "Approve mission execution",
                        "Human approval is required before mission execution",
                        Set.of("MISSION_REVIEWER"), createdAt, expiresAt
                );

        workflowState = workflowState.waitingFor(gate);
        activities.openGate(
                new MissionActivities.OpenGateRequest("open:" + gateId,

                        new ApprovalService.OpenRequest(
                                gateId,
                                input.tenantId(),
                                input.missionId(),
                                gate.title(),
                                gate.reason(),
                                List.copyOf(gate.requiredReviewerRoles()),
                                Map.of("gateType", "preflight",
                                        "path", input.selectedPath().name(),
                                        "requestId", input.requestId(),
                                        "frozenVersionRef", input.frozenVersionRef()),
                                createdAt,
                                expiresAt
                        )
                )
        );

        boolean decided = Workflow.await(GATE_TIMEOUT, () -> workflowState.pendingGate() == null
                        || workflowState.terminal());

        if (!decided && !workflowState.terminal()) {
            expireGate(gateId, expiresAt);
            workflowState = workflowState.timedOut("Mission approval expired", now());
            cancelPersistedMission();
        }
    }


    private void waitForActionApproval(MissionActivities.ApprovalRequired approval
    ) {
        Objects.requireNonNull(approval, "approval must not be null");
        PendingGate gate = new PendingGate(
                        approval.gateId(),
                        approval.title(),
                        approval.reason(),
                        approval.requiredReviewerRoles(),
                        approval.createdAt(),
                        approval.expiresAt()
                );

        workflowState = workflowState.waitingFor(gate);

        activities.openGate(
                new MissionActivities.OpenGateRequest("open:" + gate.gateId(),

                        new ApprovalService.OpenRequest(
                                gate.gateId(),
                                input.tenantId(),
                                input.missionId(),
                                gate.title(),
                                gate.reason(),
                                List.copyOf(gate.requiredReviewerRoles()),
                                approval.reviewPayload(),
                                approval.createdAt(),
                                approval.expiresAt())));

        Duration remaining = Duration.between(now(), approval.expiresAt());

        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }

        boolean decided = Workflow.await(remaining, () ->
                                workflowState.pendingGate() == null || workflowState.terminal());

        if (!decided && !workflowState.terminal()) {
            expireGate(approval.gateId(), approval.expiresAt()
            );

            workflowState = workflowState.timedOut("Human approval expired", now());
            cancelPersistedMission();
        }
    }

    /**
     * Persists the timeout into HumanGateStore.
     *
     * ApprovalService.expire requires the expiration timestamp to be at or
     * after the gate's configured expiry.
     */
    private void expireGate(String gateId, Instant expiresAt
    ) {
        Instant expiredAt = now();
        if (expiredAt.isBefore(expiresAt)) {
            expiredAt = expiresAt;
        }

        activities.expireGate(
                new MissionActivities.ExpireGateRequest("expire:" + gateId,

                        new ApprovalService.ExpiryRequest(
                                input.tenantId(),
                                input.missionId(), gateId, expiredAt))
        );
    }

    private void failPersistedMission(ActivityFailure failure
    ) {
        String errorReference = "activity:" + input.missionId() + ":RunEmbabelMission";

        String message =
                failure.getMessage() == null || failure.getMessage().isBlank()
                        ? "Embabel mission execution failed" : failure.getMessage();

        MissionActivities.FailMissionResult result =
                activities.failMission(
                        new MissionActivities.FailMissionRequest(
                                input.tenantId(),
                                input.missionId(),
                                "EMBABEL_EXECUTION_FAILED", message, errorReference));

        workflowState = workflowState.failed(result.errorReference(), result.completedAt());
    }

    @Override
    public MissionWorkflowState approve(MissionWorkflowCommand command
    ) {
        awaitInitialized();
        requireType(command, MissionWorkflowCommand.CommandType.APPROVE);

        if (workflowState.alreadyProcessed(command
        )) {
            return workflowState;
        }
        workflowState.requirePendingGate(command
        );

        Instant decidedAt = now();

        activities.recordGateDecision(
                new MissionActivities.GateDecisionRequest(
                        command.updateId(), command, decidedAt));

        workflowState = workflowState.approved(command, decidedAt);
        return workflowState;
    }

    @Override
    public MissionWorkflowState reject(MissionWorkflowCommand command
    ) {
        awaitInitialized();
        requireType(command, MissionWorkflowCommand.CommandType.REJECT);

        if (workflowState.alreadyProcessed(command)) {
            return workflowState;
        }
        workflowState.requirePendingGate(command
        );
        Instant decidedAt = now();

        activities.recordGateDecision(new MissionActivities.GateDecisionRequest(
                        command.updateId(), command, decidedAt));

        workflowState = workflowState.rejected(command, decidedAt);
        cancelPersistedMission();
        return workflowState;
    }

    @Override
    public MissionWorkflowState cancel(MissionWorkflowCommand command) {
        awaitInitialized();
        requireType(command, MissionWorkflowCommand.CommandType.CANCEL);

        if (workflowState.alreadyProcessed(command
        )) {
            return workflowState;
        }

        workflowState = workflowState.cancelled(command, now());
        cancelPersistedMission();

        if (embabelScope != null) {
            embabelScope.cancel(
                    "Mission cancellation requested");}
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

        Instant completed =
                activities.cancelMission(
                        new MissionActivities.CancelMissionRequest(
                                input.tenantId(),
                                input.missionId(),
                                workflowState.cancellationReason().isBlank()
                                        ? "Mission cancelled" : workflowState
                                        .cancellationReason()));

        if (workflowState.completedAt() == null) {

            workflowState =
                    workflowState.timedOut(
                            workflowState
                                    .cancellationReason()
                                    .isBlank() ? "Mission cancelled"
                                    : workflowState.cancellationReason(), completed);
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
                workflowState.status() == MissionStatus.COMPLETED ? workflowState.resultRef() : null,
                workflowState.status() == MissionStatus.FAILED_TERMINAL ? workflowState.errorReference() : "",
                workflowState.startedAt(),
                Objects.requireNonNull(workflowState.completedAt(),
                        "terminal state requires completedAt"),
                workflowState.status() == MissionStatus.CANCELLED ? workflowState.cancelledAt() : null
        );
    }

    private void awaitInitialized() {
        Workflow.await(() -> workflowState != null
        );
    }

    private static void requireType(MissionWorkflowCommand command,
            MissionWorkflowCommand.CommandType expected
    ) {
        Objects.requireNonNull(command, "command must not be null");

        if (command.type() != expected) {
            throw new IllegalArgumentException("Workflow Update command type mismatch");
        }
    }

    private static Instant now() {
        return Instant.ofEpochMilli(
                Workflow.currentTimeMillis()
        );
    }
}