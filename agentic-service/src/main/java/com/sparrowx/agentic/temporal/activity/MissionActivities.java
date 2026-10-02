package com.sparrowx.agentic.temporal.activity;

import com.sparrowx.agentic.runtime.checkpoint.CheckpointRef;
import com.sparrowx.agentic.runtime.gate.ApprovalService;
import com.sparrowx.agentic.temporal.model.MissionWorkflowCommand;
import com.sparrowx.agentic.temporal.model.MissionWorkflowInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recovery and enterprise-wait boundaries only.
 * No Embabel intent, plan, action, goal or blackboard type crosses this API.
 */
@ActivityInterface
public interface MissionActivities {

    @ActivityMethod(name = "RunEmbabelMission")
    RunMissionResult runMission(RunMissionRequest request);

    @ActivityMethod(name = "OpenMissionPreflightGate")
    void openGate(OpenGateRequest request);

    @ActivityMethod(name = "RecordMissionGateDecision")
    void recordGateDecision(GateDecisionRequest request);

    @ActivityMethod(name = "ExpireMissionGate")
    void expireGate(ExpireGateRequest request);

    @ActivityMethod(name = "CancelMission")
    Instant cancelMission(CancelMissionRequest request);

    @ActivityMethod(name = "FailMission")
    FailMissionResult failMission(FailMissionRequest request);

    record RunMissionRequest(
            MissionWorkflowInput workflowInput,
            Set<String> approvedGateIds,
            Instant startedAt
    ) {
        public RunMissionRequest {
            workflowInput = Objects.requireNonNull(
                    workflowInput,
                    "workflowInput must not be null"
            );
            approvedGateIds = approvedGateIds == null
                    ? Set.of()
                    : Set.copyOf(approvedGateIds);
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }

    record RunMissionResult(
            RunDisposition disposition,
            CheckpointRef resultRef,
            Instant completedAt,
            ApprovalRequired approvalRequired
    ) {

        public RunMissionResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");

            switch (disposition) {
                case COMPLETED -> {
                    Objects.requireNonNull(resultRef, "COMPLETED requires resultRef");
                    Objects.requireNonNull(completedAt, "COMPLETED requires completedAt");

                    if (approvalRequired != null) {
                        throw new IllegalArgumentException("COMPLETED must not contain approvalRequired");
                    }
                }

                case APPROVAL_REQUIRED -> {
                    Objects.requireNonNull(approvalRequired,
                            "APPROVAL_REQUIRED requires approvalRequired"
                    );

                    if (resultRef != null) {
                        throw new IllegalArgumentException(
                                "APPROVAL_REQUIRED must not contain resultRef"
                        );
                    }
                    if (completedAt != null) {
                        throw new IllegalArgumentException(
                                "APPROVAL_REQUIRED must not contain completedAt"
                        );
                    }
                }
            }
        }

        public static RunMissionResult completed(CheckpointRef resultRef, Instant completedAt) {
            return new RunMissionResult(
                    RunDisposition.COMPLETED,
                    resultRef,
                    completedAt,
                    null
            );
        }

        public static RunMissionResult approvalRequired(
                ApprovalRequired approval
        ) {
            return new RunMissionResult(
                    RunDisposition.APPROVAL_REQUIRED,
                    null,
                    null,
                    Objects.requireNonNull(
                            approval,
                            "approval must not be null"
                    )
            );
        }
    }

    record OpenGateRequest(
            String effectId,
            ApprovalService.OpenRequest openRequest
    ) {
        public OpenGateRequest {
            effectId = requireText(effectId, "effectId");
            openRequest = Objects.requireNonNull(openRequest, "openRequest must not be null");
        }
    }

    record GateDecisionRequest(
            String effectId,
            MissionWorkflowCommand command,
            Instant decidedAt
    ) {
        public GateDecisionRequest {
            effectId = requireText(effectId, "effectId");
            command = Objects.requireNonNull(command, "command must not be null");
            decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        }
    }

    record ExpireGateRequest(String effectId, ApprovalService.ExpiryRequest expiryRequest) {

        public ExpireGateRequest {
            effectId = requireText(effectId, "effectId");
            expiryRequest =
                    Objects.requireNonNull(expiryRequest, "expiryRequest must not be null");
        }
    }

    record FailMissionRequest(
            String tenantId,
            String missionId,
            String code,
            String message,
            String errorReference
    ) {
        public FailMissionRequest {
            tenantId = requireText(tenantId, "tenantId");
            missionId = requireText(missionId, "missionId");
            code = requireText(code, "code");
            message = requireText(message, "message");
            errorReference = requireText(
                    errorReference,
                    "errorReference"
            );
        }
    }

    record FailMissionResult(
            Instant completedAt,
            String errorReference
    ) {
        public FailMissionResult {
            completedAt = Objects.requireNonNull(
                    completedAt,
                    "completedAt must not be null"
            );
            errorReference = requireText(
                    errorReference,
                    "errorReference"
            );
        }
    }

    record CancelMissionRequest(
            String tenantId,
            String missionId,
            String reason
    ) {
        public CancelMissionRequest {
            tenantId = requireText(tenantId, "tenantId");
            missionId = requireText(missionId, "missionId");
            reason = requireText(reason, "reason");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
        return value.trim();
    }

    enum RunDisposition {
        COMPLETED,
        APPROVAL_REQUIRED
    }

    record ApprovalRequired(
            String gateId,
            String title,
            String reason,
            Set<String> requiredReviewerRoles,
            Map<String, Object> reviewPayload,
            Instant createdAt,
            Instant expiresAt
    ) {

        public ApprovalRequired {
            gateId = requireText(gateId, "gateId");
            title = requireText(title, "title");
            reason = requireText(reason, "reason");
            requiredReviewerRoles =
                    requiredReviewerRoles == null
                            ? Set.of()
                            : Set.copyOf(
                            requiredReviewerRoles
                    );

            reviewPayload =
                    reviewPayload == null
                            ? Map.of()
                            : Map.copyOf(
                            reviewPayload
                    );

            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");

            if (!expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException(
                        "expiresAt must be after createdAt"
                );
            }
        }
    }


}
