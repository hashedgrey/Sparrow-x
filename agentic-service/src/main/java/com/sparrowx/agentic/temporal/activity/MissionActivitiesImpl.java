package com.sparrowx.agentic.temporal.activity;

import com.sparrowx.agentic.agents.EmbabelMissionRunner;
import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.exceptions.MissionNotFoundException;
import com.sparrowx.agentic.mission.MissionLifecycleService;
import com.sparrowx.agentic.mission.Terminalizer;
import com.sparrowx.agentic.mission.artifact.ArtifactPreparationResult;
import com.sparrowx.agentic.mission.model.Mission;
import com.sparrowx.agentic.mission.model.MissionFailure;
import com.sparrowx.agentic.mission.model.MissionFailureReason;
import com.sparrowx.agentic.mission.model.MissionRequest;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.mission.store.MissionStore;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointRef;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointSerializer;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointSnapshot;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointStore;
import com.sparrowx.agentic.runtime.gate.ApprovalService;
import com.sparrowx.agentic.runtime.gate.HumanApprovalRequiredException;
import com.sparrowx.agentic.temporal.model.MissionWorkflowCommand;
import com.sparrowx.agentic.temporal.model.MissionWorkflowInput;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationToken;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Durable Temporal execution boundary around one Embabel process invocation.
 *
 * Temporal hydrates frozen SparrowX business inputs and delegates planning and
 * action selection to Embabel.
 *
 * MissionIntent is resolved before this Activity starts and is treated as
 * immutable execution input.
 *
 * Human approval is represented as an APPROVAL_REQUIRED Activity result rather
 * than an Activity failure.
 */
@Component
public final class MissionActivitiesImpl
        implements MissionActivities {

    private static final long CANCELLATION_POLL_MILLIS =
            100L;

    private final MissionStore missionStore;
    private final MissionLifecycleService lifecycleService;
    private final CheckpointStore checkpointStore;
    private final CheckpointSerializer checkpointSerializer;
    private final EmbabelMissionRunner missionRunner;
    private final ApprovalService approvalService;
    private final Terminalizer terminalizer;

    public MissionActivitiesImpl(
            MissionStore missionStore,
            MissionLifecycleService lifecycleService,
            CheckpointStore checkpointStore,
            CheckpointSerializer checkpointSerializer,
            EmbabelMissionRunner missionRunner,
            ApprovalService approvalService,
            Terminalizer terminalizer
    ) {
        this.missionStore =
                Objects.requireNonNull(
                        missionStore,
                        "missionStore must not be null"
                );

        this.lifecycleService =
                Objects.requireNonNull(
                        lifecycleService,
                        "lifecycleService must not be null"
                );

        this.checkpointStore =
                Objects.requireNonNull(
                        checkpointStore,
                        "checkpointStore must not be null"
                );

        this.checkpointSerializer =
                Objects.requireNonNull(
                        checkpointSerializer,
                        "checkpointSerializer must not be null"
                );

        this.missionRunner =
                Objects.requireNonNull(
                        missionRunner,
                        "missionRunner must not be null"
                );

        this.approvalService =
                Objects.requireNonNull(
                        approvalService,
                        "approvalService must not be null"
                );

        this.terminalizer =
                Objects.requireNonNull(
                        terminalizer,
                        "terminalizer must not be null"
                );
    }

    @Override
    public RunMissionResult runMission(
            RunMissionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        MissionWorkflowInput workflow =
                request.workflowInput();

        ActivityCancellationToken cancellation =
                Activity
                        .getExecutionContext()
                        .getCancellationToken();

        requireNotCancelled(
                cancellation
        );

        /*
         * Hydrate frozen business inputs.
         *
         * MissionIntent is no longer generated by MissionAgent.
         */
        MissionRequest missionRequest =
                deserialize(
                        workflow.missionInputRef(),
                        MissionRequest.class
                );

        MissionIntent intent =
                deserialize(
                        workflow.missionIntentRef(),
                        MissionIntent.class
                );

        ArtifactPreparationResult artifacts =
                deserialize(
                        workflow.preparedArtifactsRef(),
                        ArtifactPreparationResult.class
                );

        validateHydratedInput(
                workflow,
                missionRequest,
                intent
        );

        Mission existingMission =
                missionStore.findById(
                        workflow.tenantId(),
                        workflow.missionId()
                ).orElseThrow(
                        () -> new MissionNotFoundException(
                                workflow.tenantId(),
                                workflow.missionId()
                        )
                );

        if (existingMission.status()
                == MissionStatus.COMPLETED) {

            return resumeCompletedMission(
                    workflow
            );
        }

        if (existingMission.status()
                == MissionStatus.CANCELLED) {

            throwActivityCancelled();
        }

        ensureRunning(
                workflow,
                request.startedAt()
        );

        requireNotCancelled(
                cancellation
        );

        MissionRunInput runInput =
                new MissionRunInput(
                        workflow.missionId(),
                        missionRequest,
                        intent,
                        artifacts,
                        request.approvedGateIds(),
                        request.startedAt()
                );

        MissionResult result;

        try {
            result =
                    runEmbabelCancellable(
                            runInput,
                            cancellation
                    );

        } catch (HumanApprovalRequiredException required) {

            return approvalRequired(
                    workflow,
                    required
            );
        }

        requireNotCancelled(
                cancellation
        );

        Mission latestMission =
                missionStore.findById(
                        workflow.tenantId(),
                        workflow.missionId()
                ).orElseThrow(
                        () -> new MissionNotFoundException(
                                workflow.tenantId(),
                                workflow.missionId()
                        )
                );

        if (latestMission.status()
                == MissionStatus.CANCELLED) {

            throwActivityCancelled();
        }

        if (latestMission.status()
                != MissionStatus.RUNNING) {

            throw new IllegalStateException(
                    "Cannot complete mission from "
                            + latestMission.status()
            );
        }

        /*
         * Only a genuinely completed Embabel mission crosses the
         * terminalization boundary.
         *
         * APPROVAL_REQUIRED returns earlier.
         */
        Mission terminalMission =
                terminalizer.complete(
                        workflow.tenantId(),
                        workflow.missionId(),
                        result
                );

        CheckpointRef resultRef =
                checkpointStore.findLatest(
                                workflow.tenantId(),
                                workflow.missionId(),
                                CheckpointRef.CheckpointType.MISSION_RESULT
                        )
                        .map(
                                CheckpointSnapshot::reference
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "terminal result checkpoint "
                                                + "was not persisted"
                                )
                        );

        return RunMissionResult.completed(
                resultRef,
                Objects.requireNonNull(
                        terminalMission.completedAt(),
                        "terminal mission must have completedAt"
                )
        );
    }

    /**
     * Converts an action-level HITL control signal into a normal Temporal
     * Activity result.
     */
    private RunMissionResult approvalRequired(
            MissionWorkflowInput workflow,
            HumanApprovalRequiredException required
    ) {
        HumanApprovalRequiredException.GateRequest gate =
                Objects.requireNonNull(
                        required.gate(),
                        "approval gate must not be null"
                );

        if (!workflow.missionId()
                .equals(gate.missionId())) {

            throw new IllegalStateException(
                    "approval gate belongs to another mission"
            );
        }

        Instant createdAt =
                Instant.now();

        Instant expiresAt =
                createdAt.plus(
                        gate.ttl()
                );

        Map<String, Object> reviewPayload =
                new LinkedHashMap<>();

        reviewPayload.put(
                "action",
                gate.actionName()
        );

        reviewPayload.put(
                "parameterFingerprint",
                gate.parameterFingerprint()
        );

        reviewPayload.put(
                "metadata",
                gate.metadata()
        );

        return RunMissionResult.approvalRequired(
                new ApprovalRequired(
                        gate.gateId(),
                        gate.title(),
                        gate.reason(),
                        gate.requiredReviewerRoles(),
                        Map.copyOf(
                                reviewPayload
                        ),
                        createdAt,
                        expiresAt
                )
        );
    }

    /**
     * Validates that all independently persisted execution inputs describe the
     * same mission.
     */
    private static void validateHydratedInput(
            MissionWorkflowInput workflow,
            MissionRequest missionRequest,
            MissionIntent intent
    ) {
        if (!workflow.tenantId()
                .equals(
                        missionRequest
                                .context()
                                .tenantId()
                )) {

            throw new IllegalStateException(
                    "hydrated mission input belongs to another tenant"
            );
        }

        if (!workflow.missionId()
                .equals(intent.missionId())) {

            throw new IllegalStateException(
                    "hydrated MissionIntent belongs to another mission"
            );
        }

        if (workflow.selectedPath()
                != intent.selectedPath()) {

            throw new IllegalStateException(
                    "workflow path does not match frozen MissionIntent"
            );
        }

        if (workflow.requiresHumanReview()
                != intent.requiresHumanReview()) {

            throw new IllegalStateException(
                    "workflow HITL requirement does not match frozen MissionIntent"
            );
        }
    }

    /**
     * Runs Embabel on a separate virtual thread while the Temporal Activity
     * thread remains able to observe cancellation.
     */
    private MissionResult runEmbabelCancellable(
            MissionRunInput input,
            ActivityCancellationToken cancellation
    ) {
        ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

        Future<MissionResult> future =
                executor.submit(
                        () -> missionRunner.run(
                                input
                        )
                );

        try {
            while (true) {

                requireNotCancelled(
                        cancellation
                );

                try {
                    MissionResult result =
                            future.get(
                                    CANCELLATION_POLL_MILLIS,
                                    TimeUnit.MILLISECONDS
                            );

                    /*
                     * Cancellation may arrive between Future completion and
                     * returning the Activity result.
                     */
                    requireNotCancelled(
                            cancellation
                    );

                    return result;

                } catch (TimeoutException ignored) {

                    /*
                     * Embabel is still executing.
                     * Recheck Temporal cancellation.
                     */
                }
            }

        } catch (ActivityCompletionException cancelled) {

            future.cancel(
                    true
            );

            throw cancelled;

        } catch (InterruptedException interrupted) {

            future.cancel(
                    true
            );

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "RunEmbabelMission activity interrupted",
                    interrupted
            );

        } catch (ExecutionException failure) {

            Throwable cause =
                    failure.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(
                    "Embabel mission execution failed",
                    cause
            );

        } finally {

            executor.shutdownNow();
        }
    }

    @Override
    public FailMissionResult failMission(
            FailMissionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        MissionFailure failure =
                new MissionFailure(
                        request.code(),
                        request.message(),
                        MissionFailureReason.UNSPECIFIED,
                        false,
                        "mission-agent",
                        "runMission",
                        "embabel",
                        Map.of(
                                "errorReference",
                                request.errorReference()
                        )
                );

        Mission terminalMission =
                terminalizer.failTerminal(
                        request.tenantId(),
                        request.missionId(),
                        failure
                );

        return new FailMissionResult(
                Objects.requireNonNull(
                        terminalMission.completedAt(),
                        "failed mission must have completedAt"
                ),
                request.errorReference()
        );
    }

    @Override
    public void openGate(
            OpenGateRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        approvalService.open(
                request.openRequest()
        );
    }

    @Override
    public void recordGateDecision(
            GateDecisionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        MissionWorkflowCommand command =
                request.command();

        ApprovalService.DecisionRequest decision =
                new ApprovalService.DecisionRequest(
                        command.tenantId(),
                        command.missionId(),
                        command.gateId(),
                        command.actorUserId(),
                        command.actorRoles(),
                        command.reason(),
                        request.decidedAt()
                );

        switch (command.type()) {

            case APPROVE ->
                    approvalService.approve(
                            decision
                    );

            case REJECT ->
                    approvalService.reject(
                            decision
                    );

            case CANCEL ->
                    throw new IllegalArgumentException(
                            "cancel is not a gate decision"
                    );
        }
    }

    @Override
    public void expireGate(
            ExpireGateRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        approvalService.expire(
                request.expiryRequest()
        );
    }

    @Override
    public Instant cancelMission(
            CancelMissionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        Mission mission =
                terminalizer.cancel(
                        request.tenantId(),
                        request.missionId(),
                        request.reason()
                );

        return Objects.requireNonNull(
                mission.completedAt(),
                "cancelled mission must have completedAt"
        );
    }

    private Mission ensureRunning(
            MissionWorkflowInput workflow,
            Instant startedAt
    ) {
        Mission mission =
                missionStore.findById(
                        workflow.tenantId(),
                        workflow.missionId()
                ).orElseThrow(
                        () -> new MissionNotFoundException(
                                workflow.tenantId(),
                                workflow.missionId()
                        )
                );

        if (mission.status()
                == MissionStatus.RUNNING) {

            return mission;
        }

        if (mission.status()
                != MissionStatus.SUBMITTED) {

            throw new IllegalStateException(
                    "Cannot start mission from "
                            + mission.status()
            );
        }

        return lifecycleService.transition(
                mission,
                MissionStatus.RUNNING,
                null,
                null,
                Objects.requireNonNull(
                        startedAt,
                        "startedAt must not be null"
                )
        );
    }

    private RunMissionResult resumeCompletedMission(
            MissionWorkflowInput workflow
    ) {
        CheckpointSnapshot snapshot =
                checkpointStore.findLatest(
                                workflow.tenantId(),
                                workflow.missionId(),
                                CheckpointRef.CheckpointType.MISSION_RESULT
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "completed mission has no result checkpoint"
                                )
                        );

        MissionResult result =
                checkpointSerializer.deserialize(
                        snapshot,
                        MissionResult.class
                );

        Mission terminalMission =
                terminalizer.complete(
                        workflow.tenantId(),
                        workflow.missionId(),
                        result
                );

        CheckpointSnapshot persistedResult =
                checkpointStore.findLatest(
                                workflow.tenantId(),
                                workflow.missionId(),
                                CheckpointRef.CheckpointType.MISSION_RESULT
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "terminal result checkpoint was not persisted"
                                )
                        );

        return RunMissionResult.completed(
                persistedResult.reference(),
                Objects.requireNonNull(
                        terminalMission.completedAt(),
                        "completed mission must have completedAt"
                )
        );
    }

    private <T> T deserialize(
            CheckpointRef reference,
            Class<T> type
    ) {
        CheckpointSnapshot snapshot =
                checkpointStore.findById(
                                reference.tenantId(),
                                reference.missionId(),
                                reference.checkpointId()
                        )
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "checkpoint not found: "
                                                + reference.checkpointId()
                                )
                        );

        if (!reference.equals(
                snapshot.reference()
        )) {

            throw new IllegalStateException(
                    "checkpoint reference mismatch: "
                            + reference.checkpointId()
            );
        }

        return checkpointSerializer.deserialize(
                snapshot,
                type
        );
    }

    private static void requireNotCancelled(
            ActivityCancellationToken cancellation
    ) {
        if (!cancellation.isCancellationRequested()) {
            return;
        }

        throwActivityCancelled();
    }

    private static void throwActivityCancelled() {

        throw new ActivityCanceledException(
                Activity
                        .getExecutionContext()
                        .getInfo()
        );
    }
}