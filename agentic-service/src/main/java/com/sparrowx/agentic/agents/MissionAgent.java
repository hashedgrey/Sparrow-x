package com.sparrowx.agentic.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.ActionRetryPolicy;
import com.sparrowx.agentic.components.IntentComponent;
import com.sparrowx.agentic.components.IntentComponent.IntentRequest;
import com.sparrowx.agentic.components.PlanningComponent;
import com.sparrowx.agentic.components.PlanningComponent.PlanningRequest;
import com.sparrowx.agentic.components.SynthesisComponent;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisDraft;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisRequest;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.mission.model.MissionConstraints;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.MissionPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Complete SparrowX Embabel graph.
 *
 * Embabel derives the route from action types:
 * MissionRunInput -> IntentState -> PlanState -> MissionEvidence -> MissionResult.
 *
 * Execution/progress events are observed externally through
 * Embabel's AgenticEventListener rather than published by the
 * actions themselves.
 */
@Agent(
        description = "Plans and executes a grounded SparrowX enterprise mission",
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE
)
public final class MissionAgent {

    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
            "document.evidence.build",
            "document.evidence.verify",
            "internal.entities.search",
            "internal.company-graph.read",
            "internal.learning-graph.read",
            "governance.redaction.apply",
            "governance.grounding.check",
            "governance.human-approval.request",
            "synthesis.citations.build",
            "synthesis.answer.compose"
    );

    private final IntentComponent intentComponent;
    private final PlanningComponent planningComponent;
    private final MissionEvidenceService evidenceService;
    private final SynthesisComponent synthesisComponent;
    private final MissionEventPublisher eventPublisher;

    public MissionAgent(
            IntentComponent intentComponent,
            PlanningComponent planningComponent,
            MissionEvidenceService evidenceService,
            SynthesisComponent synthesisComponent,
            MissionEventPublisher eventPublisher

    ) {
        this.intentComponent = Objects.requireNonNull(
                intentComponent,
                "intentComponent must not be null"
        );

        this.eventPublisher = Objects.requireNonNull(
                eventPublisher,
                "eventPublisher must not be null"
        );

        this.planningComponent = Objects.requireNonNull(
                planningComponent,
                "planningComponent must not be null"
        );

        this.evidenceService = Objects.requireNonNull(
                evidenceService,
                "evidenceService must not be null"
        );

        this.synthesisComponent = Objects.requireNonNull(
                synthesisComponent,
                "synthesisComponent must not be null"
        );
    }

    @Action(description = "Interpret the normalized mission request")
    public IntentState interpret(
            MissionRunInput input,
            OperationContext context
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        MissionConstraints constraints =
                input.request().constraints();

        IntentRequest request = new IntentRequest(
                input.missionId(),
                input.request().query(),
                input.preparedArtifacts().preparedArtifacts(),
                constraints.preferredPath(),
                setOf(constraints.allowedTools()),
                setOf(constraints.allowedSourceServices()),
                listOf(constraints.requiredOutputSections()),
                constraints.requireCitations(),
                constraints.requireHumanReview(),
                constraints.allowExternalSources(),
                Map.of(
                        "tenantId",
                        input.tenantId(),

                        "requestId",
                        input.request()
                                .context()
                                .requestId()
                )
        );

        return new IntentState(
                intentComponent.interpret(
                        request,
                        context
                )
        );
    }

    @Action(description = "Create a policy-constrained mission plan")
    public PlanState plan(
            MissionRunInput input,
            IntentState intentState,
            OperationContext context
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                intentState,
                "intentState must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        Set<String> allowedCapabilities =
                effectiveCapabilities(
                        input.request()
                                .constraints()
                                .allowedTools()
                );

        PlanningRequest request = new PlanningRequest(
                input.missionId(),
                intentState.intent(),
                null,
                List.of(),
                Set.of(),
                allowedCapabilities,
                input.request()
                        .budget()
                        .maxToolCalls(),
                input.request()
                        .budget()
                        .maxLlmCalls(),
                Map.of(
                        "approvedGateIds",
                        input.approvedGateIds(),

                        "preparedArtifactCount",
                        input.preparedArtifacts()
                                .preparedArtifacts()
                                .size()
                )
        );

        return new PlanState(
                planningComponent.plan(
                        request,
                        context
                )
        );
    }

    @Action(
            description =
                    "Execute authorized document and internal capabilities"
    )
    public MissionEvidence collectEvidence(
            MissionRunInput input,
            IntentState intentState,
            PlanState planState
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                intentState,
                "intentState must not be null"
        );

        Objects.requireNonNull(
                planState,
                "planState must not be null"
        );

        return evidenceService.collect(
                input,
                intentState.intent(),
                planState.plan()
        );
    }

    @AchievesGoal(
            description =
                    "Return the grounded SparrowX mission result"
    )
    @Action(
            description =
                    "Synthesize citations and the final mission result"
    )
    public MissionResult complete(
            MissionRunInput input,
            IntentState intentState,
            PlanState planState,
            MissionEvidence evidence,
            OperationContext context
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                intentState,
                "intentState must not be null"
        );

        Objects.requireNonNull(
                planState,
                "planState must not be null"
        );

        Objects.requireNonNull(
                evidence,
                "evidence must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        SynthesisRequest request = new SynthesisRequest(
                input.missionId(),
                true,
                intentState.intent(),
                planState.plan(),
                evidence.observations(),
                evidence.evidenceRefs(),
                evidence.citations(),
                List.of(),
                intentState.intent()
                        .requiredOutputSections(),
                Map.of(
                        "query",
                        input.request().query(),

                        "warnings",
                        evidence.warnings()
                )
        );

        SynthesisDraft draft =
                synthesisComponent.synthesize(request, context,
                        delta -> eventPublisher.publish(input.tenantId(), delta));

        Map<String, Object> debug =
                new LinkedHashMap<>(
                        draft.debugSummary()
                );

        debug.put(
                "embabelGraph",
                List.of(
                        "MissionRunInput",
                        "IntentState",
                        "PlanState",
                        "MissionEvidence",
                        "MissionResult"
                )
        );

        debug.put(
                "warnings",
                evidence.warnings()
        );

        return new MissionResult(
                input.missionId(),
                draft.executiveSummary(),
                draft.finalAnswer(),
                draft.sections(),
                draft.findings(),
                draft.recommendations(),
                evidence.evidenceRefs(),
                evidence.citations(),
                request.governanceDecisions(),
                draft.structuredOutput(),
                Map.copyOf(debug)
        );
    }

    private static Set<String> effectiveCapabilities(
            List<String> requested
    ) {
        Set<String> normalized =
                setOf(requested);

        if (normalized.isEmpty()) {
            return SUPPORTED_CAPABILITIES;
        }

        Set<String> intersection =
                normalized.stream()
                        .filter(
                                SUPPORTED_CAPABILITIES::contains
                        )
                        .collect(
                                Collectors.toUnmodifiableSet()
                        );

        if (intersection.isEmpty()) {
            throw new IllegalArgumentException(
                    "mission allows no supported SparrowX capability"
            );
        }

        return intersection;
    }

    private static Set<String> setOf(
            List<String> values
    ) {
        return values == null
                ? Set.of()
                : Set.copyOf(values);
    }

    private static List<String> listOf(
            List<String> values
    ) {
        return values == null
                ? List.of()
                : List.copyOf(values);
    }

    public record IntentState(
            MissionIntent intent
    ) {

        public IntentState {
            intent = Objects.requireNonNull(
                    intent,
                    "intent must not be null"
            );
        }
    }

    public record PlanState(
            MissionPlan plan
    ) {

        public PlanState {
            plan = Objects.requireNonNull(
                    plan,
                    "plan must not be null"
            );
        }
    }
}