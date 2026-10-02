package com.sparrowx.agentic.steps;

import com.sparrowx.agentic.governance.HumanApprovalPolicy;
import com.sparrowx.agentic.governance.HumanApprovalPolicy.ApprovalContext;
import com.sparrowx.agentic.governance.HumanApprovalPolicy.ApprovalRules;
import com.sparrowx.agentic.governance.model.GovernanceDecision;
import com.sparrowx.agentic.governance.model.GovernanceDecisionType;
import com.sparrowx.agentic.runtime.gate.HumanApprovalRequiredException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Action-boundary HITL guard.
 *
 * This component does not persist or wait for a gate.
 * It evaluates policy and stops execution when approval is required.
 *
 * Temporal owns durable gate creation/wait/resume.
 */
@Component
public final class RequestHumanApprovalStep {

    private static final Duration DEFAULT_GATE_TTL =
            Duration.ofDays(7);

    private final HumanApprovalPolicy approvalPolicy;

    public RequestHumanApprovalStep(
            HumanApprovalPolicy approvalPolicy
    ) {
        this.approvalPolicy =
                Objects.requireNonNull(
                        approvalPolicy,
                        "approvalPolicy must not be null"
                );
    }

    public GovernanceDecision requireApproved(
            ApprovalRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        GovernanceDecision decision =
                approvalPolicy.evaluate(request.decisionId(), request.rules(), request.context());
        if (decision.decision() != GovernanceDecisionType.REQUIRES_HUMAN_REVIEW) {

            return decision;
        }

        /*
         * Gate IDs MUST be deterministic.
         *
         * When Temporal resumes and Embabel re-enters the action,
         * the same action instance must calculate the same gate ID.
         */
        String gateId = gateId(request.missionId(), request.actionName(), request.parameterFingerprint());

        if (request.approvedGateIds().contains(gateId)) {
            return decision;
        }

        throw new HumanApprovalRequiredException(
                new HumanApprovalRequiredException.GateRequest(
                        gateId,
                        request.missionId(),
                        request.actionName(),
                        request.title(),
                        decision.reason(),
                        request.requiredReviewerRoles(),
                        request.parameterFingerprint(),
                        request.metadata(),
                        request.gateTtl()
                )
        );
    }

    private static String gateId(
            String missionId,
            String actionName,
            String parameterFingerprint
    ) {
        return requireText(missionId, "missionId")
                + ":gate:"
                + canonical(actionName)
                + ":"
                + requireText(
                parameterFingerprint,
                "parameterFingerprint"
        );
    }

    private static String canonical(
            String value
    ) {
        return requireText(
                value,
                "actionName"
        )
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-");
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }

    public record ApprovalRequest(
            String decisionId,
            String missionId,
            String actionName,
            String parameterFingerprint,
            String title,
            ApprovalRules rules,
            ApprovalContext context,
            Set<String> approvedGateIds,
            Set<String> requiredReviewerRoles,
            Map<String, String> metadata,
            Duration gateTtl
    ) {

        public ApprovalRequest {
            decisionId =
                    requireText(
                            decisionId,
                            "decisionId"
                    );

            missionId =
                    requireText(
                            missionId,
                            "missionId"
                    );

            actionName =
                    requireText(
                            actionName,
                            "actionName"
                    );

            parameterFingerprint =
                    requireText(
                            parameterFingerprint,
                            "parameterFingerprint"
                    );

            title =
                    requireText(
                            title,
                            "title"
                    );

            rules =
                    Objects.requireNonNull(
                            rules,
                            "rules must not be null"
                    );

            context =
                    Objects.requireNonNull(
                            context,
                            "context must not be null"
                    );

            approvedGateIds =
                    approvedGateIds == null
                            ? Set.of()
                            : Set.copyOf(approvedGateIds);

            requiredReviewerRoles =
                    requiredReviewerRoles == null
                            ? Set.of()
                            : Set.copyOf(requiredReviewerRoles);

            metadata =
                    metadata == null
                            ? Map.of()
                            : Map.copyOf(metadata);

            gateTtl =
                    gateTtl == null
                            ? DEFAULT_GATE_TTL
                            : gateTtl;

            if (gateTtl.isZero()
                    || gateTtl.isNegative()) {

                throw new IllegalArgumentException(
                        "gateTtl must be positive"
                );
            }
        }
    }
}