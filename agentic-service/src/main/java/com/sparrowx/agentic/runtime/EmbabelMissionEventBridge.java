package com.sparrowx.agentic.runtime;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.core.ActionStatusCode;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.mission.model.*;
import com.sparrowx.agentic.runtime.model.StepStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public final class EmbabelMissionEventBridge
        implements AgenticEventListener {

    private final MissionEventPublisher eventPublisher;

    public EmbabelMissionEventBridge(MissionEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    @Override
    public void onProcessEvent(
            AgentProcessEvent event
    ) {

        if (event instanceof LlmInvocationEvent llm) {
            publishLlmUsage(llm);
            return;
        }

        if (event instanceof ActionExecutionStartEvent started) {
            publishStarted(started);
            return;
        }

        if (event instanceof ActionExecutionResultEvent completed) {
            publishCompleted(completed);
        }
    }

    private void publishLlmUsage(LlmInvocationEvent event) {
        MissionRunInput input = missionInput(event);

        if (input == null) {
            return;
        }

        LlmInvocation invocation = event.getInvocation();
        if (invocation == null) {
            return;
        }

        Usage usage = invocation.getUsage();
        if (usage == null) {
            return;
        }

        String operationId = normalize(event.getInteractionId());

        if (operationId.isBlank()) {
            operationId =
                    "llm:"
                            + event.getAgentProcess().getId()
                            + ":"
                            + invocation
                            .getTimestamp()
                            .toEpochMilli();
        }

        UsageProjection projection = usageProjection(operationId);

        long inputTokens = tokenCount(usage.getPromptTokens());

        long outputTokens = tokenCount(usage.getCompletionTokens());

        long totalTokens = tokenCount(usage.getTotalTokens());

        if (totalTokens == 0L) {totalTokens =
                inputTokens + outputTokens;
        }

        long durationMs = Math.max(0L, invocation.getRunningTime().toMillis());

        Instant emittedAt =
                invocation
                        .getTimestamp()
                        .plus(invocation.getRunningTime());

        eventPublisher.publish(
                input.tenantId(),
                new MissionStreamEvent.Usage(
                        input.missionId(),

                        operationId,

                        MissionStreamEvent.UsageKind.LLM,

                        projection.componentId(),

                        invocation
                                .getLlmMetadata()
                                .getName(),

                        inputTokens,

                        /*
                         * Embabel's generic Usage currently exposes prompt and
                         * completion tokens, but not cached prompt tokens as a
                         * first-class field.
                         */
                        0L,

                        outputTokens,

                        totalTokens,

                        0L,

                        0L,

                        durationMs,

                        usageResumeToken(
                                input.missionId(),
                                operationId
                        ),

                        emittedAt
                )
        );
    }

    private static MissionRunInput missionInput(
            LlmInvocationEvent event
    ) {
        return event
                .getAgentProcess()
                .last(MissionRunInput.class);
    }

    private void publishStarted(
            ActionExecutionStartEvent event
    ) {
        MissionRunInput input = missionInput(event);

        if (input == null) {
            return;
        }

        ActionProjection projection = projection(event.getAction().getName());

        if (projection == null) {
            System.out.println(
                    ">>> SP PROGRESS SKIPPED unknown action=["
                            + event.getAction().getName()
                            + "]"
            );
            return;
        }

        Instant now = Instant.now();


        MissionProgressEvent published =
                eventPublisher.publish(
                        input.tenantId(),
                        new MissionProgressEvent(
                                input.missionId(),
                                MissionStatus.RUNNING,
                                projection.stageId(),
                                projection.stageName(),
                                projection.stepId(),
                                projection.stepName(),
                                StepStatus.RUNNING,
                                new ComponentInvocation(
                                        projection.componentId(),
                                        projection.componentKind(),
                                        projection.componentName(),
                                        Map.of(),
                                        Map.of(),
                                        now,
                                        null
                                ),
                                projection.startMessage(),
                                projection.startPercent(),
                                resumeToken(
                                        input.missionId(),
                                        projection.stepId(),
                                        "started"
                                ),
                                now,
                                Map.of(
                                        "embabelProcessId",
                                        event.getProcessId(),
                                        "embabelAction",
                                        event.getAction().getName()
                                )
                        )
                );

    }

    private void publishCompleted(
            ActionExecutionResultEvent event
    ) {
        MissionRunInput input = missionInput(event);

        if (input == null) {
            return;
        }

        ActionProjection projection =
                projection(
                        event.getAction().getName()
                );

        if (projection == null) {
            System.out.println(
                    ">>> SP PROGRESS SKIPPED unknown action=["
                            + event.getAction().getName()
                            + "]"
            );
            return;
        }

        ActionStatusCode status = event.getActionStatus().getStatus();

        StepStatus stepStatus =
                switch (status) {
                    case SUCCEEDED -> StepStatus.SUCCEEDED;

                    case WAITING, PAUSED -> StepStatus.PAUSED;

                    default -> StepStatus.FAILED_TERMINAL;
                };

        String message =
                status == ActionStatusCode.SUCCEEDED
                        ? projection.completeMessage()
                        : projection.stepName()
                        + " ended with "
                        + status.name();

        Instant now = Instant.now();


        MissionProgressEvent published =
                eventPublisher.publish(
                        input.tenantId(),
                        new MissionProgressEvent(
                                input.missionId(),
                                MissionStatus.RUNNING,
                                projection.stageId(),
                                projection.stageName(),
                                projection.stepId(),
                                projection.stepName(),
                                stepStatus,
                                new ComponentInvocation(
                                        projection.componentId(),
                                        projection.componentKind(),
                                        projection.componentName(),
                                        Map.of(),
                                        Map.of(
                                                "embabelStatus",
                                                status.name()
                                        ),
                                        now.minus(
                                                event.getRunningTime()
                                        ),
                                        now
                                ),
                                message,
                                projection.completePercent(),
                                resumeToken(
                                        input.missionId(),
                                        projection.stepId(),
                                        "completed"
                                ),
                                now,
                                Map.of(
                                        "embabelProcessId",
                                        event.getProcessId(),
                                        "embabelAction",
                                        event.getAction().getName(),
                                        "embabelStatus",
                                        status.name()
                                )
                        )
                );

    }

    private static MissionRunInput missionInput(
            ActionExecutionStartEvent event
    ) {
        return event.getAgentProcess()
                .last(MissionRunInput.class);
    }

    private static MissionRunInput missionInput(
            ActionExecutionResultEvent event
    ) {
        return event.getAgentProcess().last(MissionRunInput.class);
    }

    private static ActionProjection projection(
            String actionName
    ) {
        if (actionName == null
                || actionName.isBlank()) {
            return null;
        }

        String shortName =
                actionName.contains(".")
                        ? actionName.substring(
                        actionName.lastIndexOf('.') + 1
                )
                        : actionName;

        return switch (shortName) {

            case "interpret" ->
                    new ActionProjection(
                            "interpret",
                            "Intent interpretation",
                            "interpret",
                            "Interpret mission request",
                            "intent-component",
                            ComponentKind.INTENT_INTERPRETATION,
                            "IntentComponent",
                            "Interpreting mission intent",
                            "Mission intent interpreted",
                            10.0,
                            20.0
                    );

            case "plan" ->
                    new ActionProjection(
                            "planning",
                            "Mission planning",
                            "plan",
                            "Create mission plan",
                            "planning-component",
                            ComponentKind.PLANNING,
                            "PlanningComponent",
                            "Planning mission execution",
                            "Mission plan created",
                            30.0,
                            40.0
                    );

            case "collectEvidence" ->
                    new ActionProjection(
                            "evidence",
                            "Evidence collection",
                            "collect-evidence",
                            "Collect mission evidence",
                            "evidence-service",
                            ComponentKind.CUSTOM,
                            "MissionEvidenceService",
                            "Collecting mission evidence",
                            "Mission evidence collected",
                            50.0,
                            70.0
                    );

            case "complete" ->
                    new ActionProjection(
                            "synthesis",
                            "Response synthesis",
                            "synthesize",
                            "Synthesize mission result",
                            "synthesis-component",
                            ComponentKind.SYNTHESIS,
                            "SynthesisComponent",
                            "Synthesizing grounded response",
                            "Grounded response synthesized",
                            80.0,
                            90.0
                    );

            default -> null;
        };
    }

    private static String resumeToken(
            String missionId,
            String stepId,
            String state
    ) {
        return "progress:" + missionId + ":" + stepId + ":" + state;
    }

    private record ActionProjection(
            String stageId,
            String stageName,
            String stepId,
            String stepName,
            String componentId,
            ComponentKind componentKind,
            String componentName,
            String startMessage,
            String completeMessage,
            double startPercent,
            double completePercent
    ) {
    }

    private static UsageProjection usageProjection(
            String interactionId
    ) {
        String normalized =
                normalize(interactionId)
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalized.contains(".interpret-")) {
            return new UsageProjection(
                    "intent-component"
            );
        }

        if (normalized.contains(".plan-")) {
            return new UsageProjection(
                    "planning-component"
            );
        }

        if (normalized.contains(".complete-")) {
            return new UsageProjection(
                    "synthesis-component"
            );
        }

        return new UsageProjection(
                "agentic-llm"
        );
    }

    private static long tokenCount(
            Integer value
    ) {
        return value == null
                ? 0L
                : Math.max(
                0L,
                value.longValue()
        );
    }

    private static String usageResumeToken(
            String missionId,
            String operationId
    ) {
        UUID deterministicId =
                UUID.nameUUIDFromBytes(
                        operationId.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        return "usage:"
                + missionId
                + ":"
                + deterministicId;
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }

    private record UsageProjection(
            String componentId
    ) {
    }
}