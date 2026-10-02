package com.sparrowx.agentic.runtime.gate;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Control-flow signal indicating that execution reached an action
 * requiring durable human approval.
 *
 * MissionActivitiesImpl catches this exception and returns the gate
 * to the Temporal workflow. It must not become a terminal mission failure.
 */
public final class HumanApprovalRequiredException
        extends RuntimeException {

    private final GateRequest gate;

    public HumanApprovalRequiredException(GateRequest gate
    ) {
        super(
                "Human approval required for action: "
                        + Objects.requireNonNull(gate, "gate must not be null").actionName()
        );
        this.gate = gate;
    }

    public GateRequest gate() {
        return gate;
    }

    public record GateRequest(
            String gateId,
            String missionId,
            String actionName,
            String title,
            String reason,
            Set<String> requiredReviewerRoles,
            String parameterFingerprint,
            Map<String, String> metadata,
            Duration ttl
    ) {

        public GateRequest {
            gateId = requireText(gateId, "gateId");
            missionId = requireText(missionId, "missionId");
            actionName = requireText(actionName, "actionName");
            title = requireText(title, "title");
            reason = requireText(reason, "reason");

            requiredReviewerRoles = requiredReviewerRoles == null ? Set.of() : Set.copyOf(requiredReviewerRoles);

            parameterFingerprint =
                    requireText(parameterFingerprint, "parameterFingerprint");

            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            ttl = Objects.requireNonNull(ttl, "ttl must not be null");

            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("ttl must be positive");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }

        return value.trim();
    }
}