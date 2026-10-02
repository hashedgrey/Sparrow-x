package com.sparrowx.agentic.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;
import com.embabel.agent.core.Blackboard;
import com.sparrowx.agentic.actions.synthesis.BuildCitationsAction;
import com.sparrowx.agentic.components.SystemOneUtilityComponent;
import com.sparrowx.agentic.components.SystemOneUtilityComponent.UtilityAssessment;
import com.sparrowx.agentic.components.SystemOneUtilityComponent.UtilityRequest;
import com.sparrowx.agentic.components.SynthesisComponent;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisDraft;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisRequest;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.mission.artifact.PreparedArtifact;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.steps.BuildDocumentEvidenceStep;
import com.sparrowx.agentic.steps.ResolveInternalContextStep;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder.BuildSpec;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder.Scope;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.GraphSpec;
import com.sparrowx.document.proto.DocumentEvidenceGraphProto;
import com.sparrowx.document.proto.EvidenceGoalProto;
import com.sparrowx.document.proto.RetrievalModeProto;
import com.sparrowx.internal.grpc.InternalGraphNodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SparrowX mission agent.
 *
 * Execution model:
 *
 * frozen MissionRunInput
 *      -> Embabel GOAP or HYBRID planning
 *      -> evidence-producing actions
 *      -> System-1 utility reassessment after each evidence action
 *      -> grounded terminal synthesis
 *      -> MissionResult
 *
 * MissionIntent is resolved before this process starts.
 * This agent never reclassifies the mission intent.
 */
@Agent(
        description = "Execute a grounded SparrowX enterprise mission",
        planner = PlannerType.UTILITY,
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE
)
public final class MissionAgent {

    private static final String DOCUMENT_BUILD = "document.evidence.build";
    private static final String INTERNAL_SEARCH = "internal.entities.search";
    private static final String COMPANY_GRAPH = "internal.company-graph.read";
    private static final String LEARNING_GRAPH = "internal.learning-graph.read";

    private final SystemOneUtilityComponent utilityComponent;
    private final BuildDocumentEvidenceStep documentStep;
    private final ResolveInternalContextStep internalStep;
    private final BuildCitationsAction citationsAction;
    private final SynthesisComponent synthesisComponent;
    private final MissionEventPublisher eventPublisher;

    public MissionAgent(
            SystemOneUtilityComponent utilityComponent,
            BuildDocumentEvidenceStep documentStep,
            ResolveInternalContextStep internalStep,
            BuildCitationsAction citationsAction,
            SynthesisComponent synthesisComponent,
            MissionEventPublisher eventPublisher
    ) {
        this.utilityComponent = Objects.requireNonNull(utilityComponent,
                "utilityComponent must not be null");

        this.documentStep = Objects.requireNonNull(documentStep, "documentStep must not be null");
        this.internalStep = Objects.requireNonNull(internalStep, "internalStep must not be null");
        this.citationsAction = Objects.requireNonNull(citationsAction, "citationsAction must not be null");

        this.synthesisComponent = Objects.requireNonNull(synthesisComponent,
                "synthesisComponent must not be null");

        this.eventPublisher = Objects.requireNonNull(eventPublisher,
                "eventPublisher must not be null"
        );
    }

    /*
     * ------------------------------------------------------------------
     * DOCUMENT EVIDENCE
     * ------------------------------------------------------------------
     */

    /**
     * Keeps HYBRID in its utility-selection loop while evidence-producing
     * actions remain useful. The real terminal goal is MissionResult.
     */

    @Action(
            description = "Build grounded evidence from relevant documents",
            readOnly = true,
            cost = 0.15,
            valueMethod = "documentEvidenceValue"
    )
    public DocumentEvidenceState buildDocumentEvidence(
            MissionRunInput input,
            OperationContext context
    ) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");

        MissionIntent intent = input.intent();
        DocumentScope scope = documentScope(input, intent);
        if (scope.empty()) {
            String summary = "No document scope was available";

            Map<String, Object> attributes = Map.of("noDocumentScope", true);

            UtilityAssessment utility =
                    reassess(
                            input,
                            context,
                            DOCUMENT_BUILD,
                            summary,
                            attributes,
                            List.of(),
                            "",
                            ""
                    );

            return new DocumentEvidenceState(
                    false,
                    null,
                    List.of(),
                    List.of("Document evidence was requested but no "
                                    + "document scope could be resolved."
                    ),
                    summary,
                    attributes,
                    utility
            );
        }

        String requestId = effectId(input, "document-evidence");

        BuildSpec spec =
                new BuildSpec(requestId,
                        new Scope(
                                scope.documentIds(), scope.fileNames(),
                                List.of(), List.of(),
                                Map.of()),

                        EvidenceGoalProto.EVIDENCE_GOAL_CUSTOM,
                        intent.objective(),
                        List.of(),
                        List.of(),
                        "",
                        "",
                        Map.of(),
                        retrievalHint(input, intent),
                        List.copyOf(intent.topics()),
                        List.copyOf(intent.targetEntities()),
                        List.of(),
                        Map.of(),
                        "",
                        RetrievalModeProto.RETRIEVAL_MODE_HYBRID,
                        20,
                        true,
                        true,
                        intent.requiresVerification()
                );

        var result = documentStep.execute(input.request().context(), spec);

        boolean noRelevantEvidence = result.coverageScore() <= 0.0d || result.evidenceRefs().isEmpty();

        if (noRelevantEvidence) {
            String summary = "No relevant document evidence found";

            Map<String, Object> attributes =
                    Map.of(
                            "coverageScore",
                            result.coverageScore(),

                            "usedChunkRetrieval",
                            result.usedChunkRetrieval(),

                            "usedClaimCache",
                            result.usedClaimCache(),

                            "noRelevantEvidence",
                            true
                    );

            UtilityAssessment utility =
                    reassess(
                            input,
                            context,
                            DOCUMENT_BUILD,
                            summary,
                            attributes,
                            result.evidenceRefs(),
                            "",
                            ""
                    );

            return new DocumentEvidenceState(
                    true,
                    null,
                    result.evidenceRefs(),
                    result.warnings(),
                    summary,
                    attributes,
                    utility
            );
        }

        String summary = "Built document evidence; coverage=" + result.coverageScore();

        Map<String, Object> attributes =
                Map.of(
                        "coverageScore",
                        result.coverageScore(),

                        "usedChunkRetrieval",
                        result.usedChunkRetrieval(),

                        "usedClaimCache",
                        result.usedClaimCache(),

                        "noRelevantEvidence",
                        false,

                        "evidenceCount",
                        result.evidenceRefs()
                                .size()
                );

        UtilityAssessment utility =
                reassess(
                        input,
                        context,
                        DOCUMENT_BUILD,
                        summary,
                        attributes,
                        result.evidenceRefs(),
                        "",
                        ""
                );

        return new DocumentEvidenceState(
                true,
                result.graph(),
                result.evidenceRefs(),
                result.warnings(),
                summary,
                attributes,
                utility
        );
    }

    @Cost(name = "documentEvidenceValue")
    public double documentEvidenceValue(
            MissionRunInput input,
            Blackboard blackboard
    ) {
        if (input == null || !capabilityAllowed(
                input, DOCUMENT_BUILD
        )) {
            return 0.0;
        }

        /*
         * Required evidence is a hard execution requirement, not a utility
         * preference.
         */
        if (input.intent().requiresDocumentEvidence() && (blackboard == null || blackboard.last(
                DocumentEvidenceState.class
        ) == null)) {

            return 1.0;
        }

        UtilityAssessment latest = latestUtility(blackboard);

        if (latest != null) {
            return clamp(latest.documentUtility());
        }

        Object initial =
                input.intent().attributes().get("documentUtility");

        if (initial instanceof Number value) {
            return clamp(value.doubleValue()
            );
        }

        return input.intent().requiresDocumentEvidence() ? 0.95 : 0.0;
    }

    /*
     * ------------------------------------------------------------------
     * INTERNAL ENTITY SEARCH
     * ------------------------------------------------------------------
     */

    @Action(
            description =
                    "Search SparrowX internal entities for relevant context",
            readOnly = true,
            cost = 0.10,
            valueMethod = "internalSearchValue"
    )
    public InternalSearchState searchInternalEntities(
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

        SearchSpec spec =
                new SearchSpec(
                        effectId(
                                input,
                                "internal-search"
                        ),

                        input.request()
                                .query(),

                        List.of(),

                        "",

                        InternalGraphNodeType
                                .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED,

                        0,

                        20,

                        true,

                        Map.of()
                );

        ResolveInternalContextStep.Result result =
                internalStep.search(
                        input.request()
                                .context(),
                        spec
                );

        boolean ambiguous =
                Boolean.TRUE.equals(
                        result.attributes()
                                .get(
                                        "ambiguous"
                                )
                );

        UtilityAssessment utility =
                reassess(
                        input,
                        context,
                        INTERNAL_SEARCH,
                        result.summary(),
                        result.attributes(),
                        result.evidenceRefs(),
                        result.resolvedEntityId(),
                        result.resolvedNodeType()
                                .name()
                );

        return new InternalSearchState(
                true,
                result.evidenceRefs(),
                result.warnings(),
                result.summary(),
                result.attributes(),
                result.resolvedEntityId(),
                result.resolvedNodeType(),
                ambiguous,
                utility
        );
    }

    @Cost(name = "internalSearchValue")
    public double internalSearchValue(
            MissionRunInput input,
            Blackboard blackboard
    ) {
        if (input == null
                || !capabilityAllowed(
                input,
                INTERNAL_SEARCH
        )) {

            return 0.0;
        }

        /*
         * Required internal context is also a hard execution requirement.
         */
        if (input.intent()
                .requiresInternalContext()
                && (blackboard == null
                || blackboard.last(
                InternalSearchState.class
        ) == null)) {

            return 1.0;
        }

        UtilityAssessment latest =
                latestUtility(
                        blackboard
                );

        if (latest != null) {
            return clamp(
                    latest.internalUtility()
            );
        }

        Object initial =
                input.intent()
                        .attributes()
                        .get(
                                "internalUtility"
                        );

        if (initial instanceof Number value) {
            return clamp(
                    value.doubleValue()
            );
        }

        return input.intent()
                .requiresInternalContext()
                ? 0.90
                : 0.0;
    }

    /*
     * ------------------------------------------------------------------
     * COMPANY GRAPH
     * ------------------------------------------------------------------
     */

    @Action(
            description =
                    "Expand a resolved internal entity through the company graph",
            readOnly = true,
            cost = 0.15,
            valueMethod = "companyGraphValue"
    )
    public CompanyGraphState readCompanyGraph(
            MissionRunInput input,
            InternalSearchState searchState,
            OperationContext context
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                searchState,
                "searchState must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        if (!searchState.hasResolvedEntity()) {
            return CompanyGraphState.skipped(
                    "No unique internal entity was available "
                            + "for company graph expansion",
                    searchState.utility()
            );
        }

        GraphSpec spec =
                new GraphSpec(
                        effectId(
                                input,
                                "company-graph"
                        ),

                        searchState.resolvedEntityId(),

                        searchState.resolvedNodeType(),

                        1,

                        50
                );

        ResolveInternalContextStep.Result result =
                internalStep.readCompanyGraph(
                        input.request()
                                .context(),
                        spec
                );

        UtilityAssessment utility =
                reassess(
                        input,
                        context,
                        COMPANY_GRAPH,
                        result.summary(),
                        result.attributes(),
                        result.evidenceRefs(),
                        searchState.resolvedEntityId(),
                        searchState.resolvedNodeType()
                                .name()
                );

        return new CompanyGraphState(
                true,
                result.evidenceRefs(),
                result.warnings(),
                result.summary(),
                result.attributes(),
                utility
        );
    }

    @Cost(name = "companyGraphValue")
    public double companyGraphValue(
            MissionRunInput input,
            InternalSearchState searchState,
            Blackboard blackboard
    ) {
        if (input == null
                || searchState == null
                || !searchState.hasResolvedEntity()
                || !capabilityAllowed(
                input,
                COMPANY_GRAPH
        )) {

            return 0.0;
        }

        UtilityAssessment latest =
                latestUtility(
                        blackboard
                );

        return latest == null
                ? 0.60
                : clamp(
                latest.companyGraphUtility()
        );
    }

    /*
     * ------------------------------------------------------------------
     * LEARNING GRAPH
     * ------------------------------------------------------------------
     */

    @Action(
            description =
                    "Expand a resolved entity through the learning graph",
            readOnly = true,
            cost = 0.15,
            valueMethod = "learningGraphValue"
    )
    public LearningGraphState readLearningGraph(
            MissionRunInput input,
            InternalSearchState searchState,
            OperationContext context
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                searchState,
                "searchState must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        if (!searchState.hasResolvedEntity()) {
            return LearningGraphState.skipped(
                    "No unique internal entity was available "
                            + "for learning graph expansion",
                    searchState.utility()
            );
        }

        GraphSpec spec =
                new GraphSpec(
                        effectId(
                                input,
                                "learning-graph"
                        ),

                        searchState.resolvedEntityId(),

                        searchState.resolvedNodeType(),

                        1,

                        50
                );

        ResolveInternalContextStep.Result result =
                internalStep.readLearningGraph(
                        input.request()
                                .context(),
                        spec
                );

        UtilityAssessment utility =
                reassess(
                        input,
                        context,
                        LEARNING_GRAPH,
                        result.summary(),
                        result.attributes(),
                        result.evidenceRefs(),
                        searchState.resolvedEntityId(),
                        searchState.resolvedNodeType()
                                .name()
                );

        return new LearningGraphState(
                true,
                result.evidenceRefs(),
                result.warnings(),
                result.summary(),
                result.attributes(),
                utility
        );
    }

    @Cost(name = "learningGraphValue")
    public double learningGraphValue(
            MissionRunInput input,
            InternalSearchState searchState,
            Blackboard blackboard
    ) {
        if (input == null
                || searchState == null
                || !searchState.hasResolvedEntity()
                || !capabilityAllowed(
                input,
                LEARNING_GRAPH
        )) {

            return 0.0;
        }

        UtilityAssessment latest =
                latestUtility(
                        blackboard
                );

        return latest == null
                ? 0.40
                : clamp(
                latest.learningGraphUtility()
        );
    }

    /*
     * ------------------------------------------------------------------
     * TERMINAL SYNTHESIS
     * ------------------------------------------------------------------
     */

    @AchievesGoal(
            description =
                    "Return the grounded SparrowX mission result"
    )
    @Action(
            description =
                    "Synthesize the final grounded mission response",
            valueMethod = "synthesisValue",
            cost = 0.05
    )
    public MissionResult complete(
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

        List<EvidenceState> evidenceStates =
                context.objectsOfType(
                        EvidenceState.class
                );

        /*
         * Retrieval was required and was attempted, but did not produce
         * usable evidence. Never fall through to model-memory synthesis.
         */
        MissionResult insufficient =
                insufficientEvidenceResult(
                        input,
                        evidenceStates
                );

        if (insufficient != null) {
            return insufficient;
        }

        List<EvidenceRef> rawEvidence =
                evidenceStates.stream()
                        .flatMap(state ->
                                state.evidenceRefs()
                                        .stream()
                        )
                        .toList();

        List<EvidenceRef> evidenceRefs;
        List<Citation> citations;

        if (rawEvidence.isEmpty()) {
            evidenceRefs =
                    List.of();

            citations =
                    List.of();

        } else {
            BuildCitationsAction.Result normalized =
                    citationsAction.execute(
                            new BuildCitationsAction.BuildSpec(
                                    rawEvidence,
                                    excerptsByEvidenceId(
                                            rawEvidence
                                    )
                            )
                    );

            evidenceRefs =
                    normalized.evidenceRefs();

            citations =
                    input.intent()
                            .requiresCitations()
                            ? normalized.citations()
                            : List.of();
        }

        List<String> warnings =
                evidenceStates.stream()
                        .flatMap(state ->
                                state.warnings()
                                        .stream()
                        )
                        .distinct()
                        .toList();

        List<Map<String, Object>> observations =
                evidenceStates.stream()
                        .filter(
                                EvidenceState::performed
                        )
                        .map(
                                MissionAgent::observation
                        )
                        .toList();

        SynthesisRequest request =
                new SynthesisRequest(
                        input.missionId(),

                        true,

                        input.intent(),

                        observations,

                        evidenceRefs,

                        citations,

                        List.of(),

                        input.intent()
                                .requiredOutputSections(),

                        Map.of(
                                "query",
                                input.request()
                                        .query(),

                                "warnings",
                                warnings,

                                "selectedPath",
                                input.intent()
                                        .selectedPath()
                                        .name(),

                                "plannerMode",
                                input.intent()
                                        .plannerMode()
                                        .name()
                        )
                );

        SynthesisDraft draft =
                synthesisComponent.synthesize(
                        request,
                        context,
                        delta ->
                                eventPublisher.publish(
                                        input.tenantId(),
                                        delta
                                )
                );

        Map<String, Object> debug =
                new LinkedHashMap<>(
                        draft.debugSummary()
                );

        debug.put(
                "intentEngine",
                "system-one"
        );

        debug.put(
                "embabelPlanner",
                input.intent()
                        .plannerMode()
                        .name()
        );

        debug.put(
                "embabelGraph",
                List.of(
                        "MissionRunInput",
                        "FrozenMissionIntent",
                        "EvidenceActions",
                        "MissionResult"
                )
        );

        debug.put(
                "executedEvidenceActions",
                evidenceStates.stream()
                        .filter(
                                EvidenceState::performed
                        )
                        .map(
                                MissionAgent::actionName
                        )
                        .toList()
        );

        UtilityAssessment latest =
                evidenceStates.isEmpty()
                        ? null
                        : evidenceStates
                        .getLast()
                        .utility();

        if (latest != null) {
            debug.put(
                    "finalSystemOneUtility",
                    Map.of(
                            "document",
                            latest.documentUtility(),

                            "internal",
                            latest.internalUtility(),

                            "companyGraph",
                            latest.companyGraphUtility(),

                            "learningGraph",
                            latest.learningGraphUtility(),

                            "synthesis",
                            latest.synthesisUtility()
                    )
            );

            debug.put(
                    "systemOneUtilityModel",
                    latest.model()
            );

            debug.put(
                    "systemOneUtilityRequestId",
                    latest.requestId()
            );
        }

        debug.put(
                "warnings",
                warnings
        );

        return new MissionResult(
                input.missionId(),
                draft.executiveSummary(),
                draft.finalAnswer(),
                draft.sections(),
                draft.findings(),
                draft.recommendations(),
                evidenceRefs,
                citations,
                request.governanceDecisions(),
                draft.structuredOutput(),
                Map.copyOf(debug)
        );
    }

    @Cost(name = "synthesisValue")
    public double synthesisValue(
            MissionRunInput input,
            Blackboard blackboard
    ) {
        if (input == null) {
            return 0.0;
        }

        MissionIntent intent =
                input.intent();

        /*
         * Hard grounding boundary.
         *
         * A required retrieval action must have been attempted before
         * synthesis is eligible.
         */
        if (intent.requiresDocumentEvidence()
                && (blackboard == null
                || blackboard.last(
                DocumentEvidenceState.class
        ) == null)) {

            return 0.0;
        }

        if (intent.requiresInternalContext()
                && (blackboard == null
                || blackboard.last(
                InternalSearchState.class
        ) == null)) {

            return 0.0;
        }

        if (!intent.requiresRetrieval()) {
            return 0.95;
        }

        UtilityAssessment latest =
                latestUtility(
                        blackboard
                );

        return latest == null
                ? 0.50
                : clamp(
                latest.synthesisUtility()
        );
    }

    /*
     * ------------------------------------------------------------------
     * BLACKBOARD STATE
     * ------------------------------------------------------------------
     */

    public sealed interface EvidenceState
            permits DocumentEvidenceState,
            InternalSearchState,
            CompanyGraphState,
            LearningGraphState {

        boolean performed();

        List<EvidenceRef> evidenceRefs();

        List<String> warnings();

        String summary();

        Map<String, Object> attributes();

        UtilityAssessment utility();
    }

    public record DocumentEvidenceState(
            boolean performed,
            DocumentEvidenceGraphProto graph,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            UtilityAssessment utility
    ) implements EvidenceState {

        public DocumentEvidenceState {
            evidenceRefs =
                    evidenceRefs == null
                            ? List.of()
                            : List.copyOf(
                            evidenceRefs
                    );

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(
                            warnings
                    );

            summary =
                    summary == null
                            ? ""
                            : summary;

            attributes =
                    attributes == null
                            ? Map.of()
                            : Map.copyOf(
                            attributes
                    );

            utility =
                    Objects.requireNonNull(
                            utility,
                            "utility must not be null"
                    );
        }
    }

    public record InternalSearchState(
            boolean performed,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            String resolvedEntityId,
            InternalGraphNodeType resolvedNodeType,
            boolean ambiguous,
            UtilityAssessment utility
    ) implements EvidenceState {

        public InternalSearchState {
            evidenceRefs =
                    evidenceRefs == null
                            ? List.of()
                            : List.copyOf(
                            evidenceRefs
                    );

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(
                            warnings
                    );

            summary =
                    summary == null
                            ? ""
                            : summary;

            attributes =
                    attributes == null
                            ? Map.of()
                            : Map.copyOf(
                            attributes
                    );

            resolvedEntityId =
                    resolvedEntityId == null
                            ? ""
                            : resolvedEntityId;

            resolvedNodeType =
                    resolvedNodeType == null
                            ? InternalGraphNodeType
                            .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                            : resolvedNodeType;

            utility =
                    Objects.requireNonNull(
                            utility,
                            "utility must not be null"
                    );
        }

        public boolean hasResolvedEntity() {
            return !ambiguous
                    && !resolvedEntityId.isBlank()
                    && resolvedNodeType
                    != InternalGraphNodeType
                    .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    && resolvedNodeType
                    != InternalGraphNodeType.UNRECOGNIZED;
        }
    }

    public record CompanyGraphState(
            boolean performed,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            UtilityAssessment utility
    ) implements EvidenceState {

        public CompanyGraphState {
            evidenceRefs =
                    evidenceRefs == null
                            ? List.of()
                            : List.copyOf(
                            evidenceRefs
                    );

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(
                            warnings
                    );

            summary =
                    summary == null
                            ? ""
                            : summary;

            attributes =
                    attributes == null
                            ? Map.of()
                            : Map.copyOf(
                            attributes
                    );

            utility =
                    Objects.requireNonNull(
                            utility,
                            "utility must not be null"
                    );
        }

        public static CompanyGraphState skipped(
                String summary,
                UtilityAssessment utility
        ) {
            return new CompanyGraphState(
                    false,
                    List.of(),
                    List.of(),
                    summary,
                    Map.of(
                            "skipped",
                            true
                    ),
                    utility
            );
        }
    }

    public record LearningGraphState(
            boolean performed,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            UtilityAssessment utility
    ) implements EvidenceState {

        public LearningGraphState {
            evidenceRefs =
                    evidenceRefs == null
                            ? List.of()
                            : List.copyOf(
                            evidenceRefs
                    );

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(
                            warnings
                    );

            summary =
                    summary == null
                            ? ""
                            : summary;

            attributes =
                    attributes == null
                            ? Map.of()
                            : Map.copyOf(
                            attributes
                    );

            utility =
                    Objects.requireNonNull(
                            utility,
                            "utility must not be null"
                    );
        }

        public static LearningGraphState skipped(
                String summary,
                UtilityAssessment utility
        ) {
            return new LearningGraphState(
                    false,
                    List.of(),
                    List.of(),
                    summary,
                    Map.of(
                            "skipped",
                            true
                    ),
                    utility
            );
        }
    }

    /*
     * ------------------------------------------------------------------
     * SYSTEM-1 REASSESSMENT
     * ------------------------------------------------------------------
     */

    private UtilityAssessment reassess(
            MissionRunInput input,
            OperationContext context,
            String latestAction,
            String latestSummary,
            Map<String, Object> latestAttributes,
            List<EvidenceRef> latestEvidenceRefs,
            String resolvedEntityId,
            String resolvedEntityType
    ) {
        List<Map<String, Object>> evidence =
                new ArrayList<>();

        Set<String> executed =
                new LinkedHashSet<>();

        for (EvidenceState state :
                context.objectsOfType(
                        EvidenceState.class
                )) {

            evidence.add(
                    utilityEvidence(
                            actionName(
                                    state
                            ),
                            state.summary(),
                            state.attributes(),
                            state.evidenceRefs()
                    )
            );

            executed.add(
                    actionName(
                            state
                    )
            );
        }

        /*
         * Current action has not yet been written to the blackboard.
         */
        evidence.add(
                utilityEvidence(
                        latestAction,
                        latestSummary,
                        latestAttributes,
                        latestEvidenceRefs
                )
        );

        executed.add(
                latestAction
        );

        return utilityComponent.evaluate(
                new UtilityRequest(
                        input.request()
                                .query(),

                        input.intent()
                                .selectedPath(),

                        latestAction,

                        List.copyOf(
                                evidence
                        ),

                        nullToEmpty(
                                resolvedEntityId
                        ),

                        nullToEmpty(
                                resolvedEntityType
                        ),

                        Set.copyOf(
                                executed
                        ),

                        input.intent()
                                .allowedTools(),

                        input.intent()
                                .requiresCitations(),

                        input.intent()
                                .requiresVerification()
                )
        );
    }

    private static Map<String, Object> utilityEvidence(
            String action,
            String summary,
            Map<String, Object> attributes,
            List<EvidenceRef> evidenceRefs
    ) {
        Map<String, Object> state =
                new LinkedHashMap<>();

        state.put(
                "action",
                nullToEmpty(
                        action
                )
        );

        state.put(
                "summary",
                nullToEmpty(
                        summary
                )
        );

        state.put(
                "attributes",
                attributes == null
                        ? Map.of()
                        : attributes
        );

        List<EvidenceRef> refs =
                evidenceRefs == null
                        ? List.of()
                        : evidenceRefs;

        state.put(
                "evidenceCount",
                refs.size()
        );

        state.put(
                "signals",
                refs.stream()
                        .limit(
                                25
                        )
                        .map(
                                MissionAgent::evidenceSignal
                        )
                        .toList()
        );

        return Map.copyOf(
                state
        );
    }

    private static Map<String, Object> evidenceSignal(
            EvidenceRef ref
    ) {
        if (ref == null) {
            return Map.of();
        }

        Map<String, Object> signal =
                new LinkedHashMap<>();

        signal.put(
                "sourceType",
                ref.sourceType()
                        .name()
        );

        signal.put(
                "sourceService",
                nullToEmpty(
                        ref.sourceService()
                )
        );

        signal.put(
                "objectId",
                nullToEmpty(
                        ref.objectId()
                )
        );

        signal.put(
                "parentObjectId",
                nullToEmpty(
                        ref.parentObjectId()
                )
        );

        signal.put(
                "locationLabel",
                nullToEmpty(
                        ref.locationLabel()
                )
        );

        signal.put(
                "section",
                nullToEmpty(
                        ref.section()
                )
        );

        signal.put(
                "attributes",
                ref.attributes() == null
                        ? Map.of()
                        : ref.attributes()
        );

        return Map.copyOf(
                signal
        );
    }

    private static UtilityAssessment latestUtility(
            Blackboard blackboard
    ) {
        if (blackboard == null) {
            return null;
        }

        EvidenceState latest =
                blackboard.last(
                        EvidenceState.class
                );

        return latest == null
                ? null
                : latest.utility();
    }

    /*
     * ------------------------------------------------------------------
     * GROUNDING
     * ------------------------------------------------------------------
     */

    /**
     * Required retrieval that was attempted but produced no usable evidence
     * terminates deterministically.
     *
     * The synthesis LLM is not permitted to fill this gap from model memory.
     */
    private static MissionResult insufficientEvidenceResult(
            MissionRunInput input,
            List<EvidenceState> evidenceStates
    ) {
        MissionIntent intent =
                input.intent();

        DocumentEvidenceState documentState =
                evidenceStates.stream()
                        .filter(
                                DocumentEvidenceState.class::isInstance
                        )
                        .map(
                                DocumentEvidenceState.class::cast
                        )
                        .findFirst()
                        .orElse(
                                null
                        );

        InternalSearchState internalState =
                evidenceStates.stream()
                        .filter(
                                InternalSearchState.class::isInstance
                        )
                        .map(
                                InternalSearchState.class::cast
                        )
                        .findFirst()
                        .orElse(
                                null
                        );

        List<String> missing =
                new ArrayList<>();

        if (intent.requiresDocumentEvidence()
                && documentState != null
                && documentState
                .evidenceRefs()
                .isEmpty()) {

            missing.add(
                    "document evidence"
            );
        }

        if (intent.requiresInternalContext()
                && internalState != null
                && internalState
                .evidenceRefs()
                .isEmpty()) {

            missing.add(
                    "internal context"
            );
        }

        if (missing.isEmpty()) {
            return null;
        }

        String requirement =
                String.join(
                        " and ",
                        missing
                );

        String answer =
                "SparrowX could not produce a grounded answer because "
                        + "the required "
                        + requirement
                        + " was not found.";

        List<String> warnings =
                evidenceStates.stream()
                        .flatMap(state ->
                                state.warnings()
                                        .stream()
                        )
                        .distinct()
                        .toList();

        return new MissionResult(
                input.missionId(),

                "Insufficient grounded evidence",

                answer,

                List.of(),

                List.of(),

                List.of(),

                List.of(),

                List.of(),

                List.of(),

                Map.of(
                        "grounded",
                        false,

                        "reason",
                        "INSUFFICIENT_REQUIRED_EVIDENCE",

                        "missing",
                        List.copyOf(
                                missing
                        )
                ),

                Map.of(
                        "intentEngine",
                        "system-one",

                        "embabelPlanner",
                        intent.plannerMode()
                                .name(),

                        "warnings",
                        warnings
                )
        );
    }

    /*
     * ------------------------------------------------------------------
     * GENERAL HELPERS
     * ------------------------------------------------------------------
     */

    private static Map<String, Object> observation(
            EvidenceState state
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "action",
                actionName(
                        state
                )
        );

        result.put(
                "summary",
                state.summary()
        );

        result.put(
                "evidenceCount",
                state.evidenceRefs()
                        .size()
        );

        result.put(
                "attributes",
                state.attributes()
        );

        return Map.copyOf(
                result
        );
    }

    private static String actionName(
            EvidenceState state
    ) {
        if (state instanceof DocumentEvidenceState) {
            return DOCUMENT_BUILD;
        }

        if (state instanceof InternalSearchState) {
            return INTERNAL_SEARCH;
        }

        if (state instanceof CompanyGraphState) {
            return COMPANY_GRAPH;
        }

        if (state instanceof LearningGraphState) {
            return LEARNING_GRAPH;
        }

        return state.getClass()
                .getSimpleName();
    }

    private static String retrievalHint(
            MissionRunInput input,
            MissionIntent intent
    ) {
        String query =
                input.request()
                        .query();

        if (query != null
                && !query.isBlank()) {

            return query;
        }

        return intent.objective();
    }

    private static DocumentScope documentScope(
            MissionRunInput input,
            MissionIntent intent
    ) {
        List<String> documentIds =
                new ArrayList<>();

        List<String> fileNames =
                new ArrayList<>();

        for (PreparedArtifact artifact :
                input.preparedArtifacts()
                        .preparedArtifacts()) {

            if (artifact.documentId() != null
                    && !artifact.documentId()
                    .isBlank()) {

                documentIds.add(
                        artifact.documentId()
                );
            }

            if (artifact.filename() != null
                    && !artifact.filename()
                    .isBlank()) {

                fileNames.add(
                        artifact.filename()
                );
            }
        }

        /*
         * Existing indexed documents can be referenced by name in the
         * frozen intent even when no document was uploaded with this request.
         */
        if (documentIds.isEmpty()
                && fileNames.isEmpty()
                && intent.requiresDocumentEvidence()) {

            intent.targetEntities()
                    .stream()
                    .filter(
                            Objects::nonNull
                    )
                    .map(
                            String::trim
                    )
                    .filter(value ->
                            !value.isBlank()
                    )
                    .forEach(
                            fileNames::add
                    );
        }

        return new DocumentScope(
                documentIds.stream()
                        .distinct()
                        .toList(),

                fileNames.stream()
                        .distinct()
                        .toList()
        );
    }

    private static Map<String, String> excerptsByEvidenceId(
            List<EvidenceRef> evidenceRefs
    ) {
        Map<String, String> result =
                new LinkedHashMap<>();

        for (EvidenceRef ref :
                evidenceRefs) {

            if (ref == null
                    || ref.evidenceId() == null
                    || ref.evidenceId()
                    .isBlank()) {

                continue;
            }

            Object excerpt =
                    ref.attributes()
                            .get(
                                    "excerpt"
                            );

            if (excerpt instanceof String text
                    && !text.isBlank()) {

                result.put(
                        ref.evidenceId(),
                        text
                );
            }
        }

        return Map.copyOf(
                result
        );
    }

    private static boolean capabilityAllowed(
            MissionRunInput input,
            String capability
    ) {
        Set<String> allowed =
                input.intent()
                        .allowedTools();

        return allowed == null
                || allowed.isEmpty()
                || allowed.contains(
                capability
        );
    }

    private static String effectId(
            MissionRunInput input,
            String action
    ) {
        return input.request()
                .context()
                .requestId()
                + ":embabel:"
                + action;
    }

    private static double clamp(
            double value
    ) {
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        value
                )
        );
    }

    private static String nullToEmpty(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }

    private record DocumentScope(
            List<String> documentIds,
            List<String> fileNames
    ) {
        private DocumentScope {
            documentIds =
                    documentIds == null
                            ? List.of()
                            : List.copyOf(
                            documentIds
                    );

            fileNames =
                    fileNames == null
                            ? List.of()
                            : List.copyOf(
                            fileNames
                    );
        }

        private boolean empty() {
            return documentIds.isEmpty()
                    && fileNames.isEmpty();
        }
    }
}