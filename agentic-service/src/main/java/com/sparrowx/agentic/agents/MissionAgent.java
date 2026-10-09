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
import com.sparrowx.agentic.components.InternalSearchSpecResolver;
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
import com.sparrowx.agentic.planning.GraphTraversal;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.InternalRootSelection;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import org.springframework.beans.factory.annotation.Value;
import com.sparrowx.agentic.steps.BuildDocumentEvidenceStep;
import com.sparrowx.agentic.steps.GoalDirectedCompanyGraphStep;
import com.sparrowx.agentic.steps.ResolveInternalContextStep;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder.BuildSpec;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder.Scope;
import com.sparrowx.agentic.planning.GraphTraversal.Status;
import com.sparrowx.document.proto.DocumentEvidenceGraphProto;
import com.sparrowx.document.proto.EvidenceGoalProto;
import com.sparrowx.document.proto.RetrievalModeProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(MissionAgent.class);

    private static final String DOCUMENT_BUILD = "document.evidence.build";
    private static final String INTERNAL_SEARCH = "internal.entities.search";
    private static final String COMPANY_GRAPH = "internal.company-graph.read";
    private static final String LEARNING_GRAPH = "internal.learning-graph.read";

    private final SystemOneUtilityComponent utilityComponent;
    private final BuildDocumentEvidenceStep documentStep;
    private final ResolveInternalContextStep internalStep;
    private final GoalDirectedCompanyGraphStep graphTraversalStep;
    private final BuildCitationsAction citationsAction;
    private final SynthesisComponent synthesisComponent;
    private final MissionEventPublisher eventPublisher;
    private final InternalSearchSpecResolver internalSearchSpecResolver;
    private final int maxGraphRoots;
    private final long maxGraphBatchMillis;

    public MissionAgent(
            SystemOneUtilityComponent utilityComponent,
            BuildDocumentEvidenceStep documentStep,
            ResolveInternalContextStep internalStep,
            GoalDirectedCompanyGraphStep graphTraversalStep,
            InternalSearchSpecResolver internalSearchSpecResolver,
            BuildCitationsAction citationsAction,
            SynthesisComponent synthesisComponent,
            MissionEventPublisher eventPublisher,
            @Value("${sparrowx.graph.max-graph-roots:32}") int maxGraphRoots,
            @Value("${sparrowx.graph.max-graph-batch-millis:120000}") long maxGraphBatchMillis
    ) {
        this.utilityComponent =
                Objects.requireNonNull(utilityComponent, "utilityComponent must not be null");
        this.documentStep = Objects.requireNonNull(documentStep, "documentStep must not be null");
        this.internalStep = Objects.requireNonNull(internalStep, "internalStep must not be null");

        this.internalSearchSpecResolver =
                Objects.requireNonNull(internalSearchSpecResolver, "internalSearchSpecResolver must not be null");

        this.graphTraversalStep = Objects.requireNonNull(
                graphTraversalStep,
                "graphTraversalStep must not be null"
        );
        this.citationsAction = Objects.requireNonNull(citationsAction, "citationsAction must not be null");
        this.synthesisComponent = Objects.requireNonNull(synthesisComponent, "synthesisComponent must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        if (maxGraphRoots < 1 || maxGraphBatchMillis < 1) {
            throw new IllegalArgumentException("Graph root/batch budgets must be positive");
        }
        this.maxGraphRoots = maxGraphRoots;
        this.maxGraphBatchMillis = maxGraphBatchMillis;
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
        if (input == null
                || !capabilityAllowed(
                input,
                DOCUMENT_BUILD
        )) {

            return 0.0d;
        }
        if (blackboard != null
                && blackboard.last(MissionResult.class) != null) {
            return 0.0d;
        }

        MissionIntent intent = input.intent();

        if (intent.requiresDocumentEvidence() && (blackboard == null || blackboard.last(DocumentEvidenceState.class
        ) == null)) {

            return 1.0d;
        }

        UtilityAssessment latest =
                latestUtility(
                        blackboard
                );

        /*
         * After evidence exists, Hybrid/System-1 can dynamically unlock
         * document retrieval if collected evidence exposed a document need.
         */
        if (latest != null) {
            if (!intent.requiresDocumentEvidence()
                    && !hasDocumentDiscoverySignal(
                    blackboard
            )) {

                return 0.0d;
            }

            return clamp(
                    latest.documentUtility()
            );
        }

        /*
         * Before any evidence exists, frozen intent is authoritative.
         */
        if (!intent.requiresDocumentEvidence()) {
            return 0.0d;
        }

        Object initial =
                intent.attributes()
                        .get("documentUtility");

        if (initial instanceof Number value) {
            return clamp(
                    value.doubleValue()
            );
        }

        return 0.95d;
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
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");

        var extraction = internalSearchSpecResolver.resolve(
                new InternalSearchSpecResolver.Request(
                        input.missionId(),
                        effectId(input, "internal-search"),
                        input.request().query(),
                        input.intent().requiresCompanyGraph()
                )
        );
        Plan plan = extraction.plan();
        List<RootResult> rootResults = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < plan.queries().size(); i++) {
            RootQuery rootQuery = plan.queries().get(i);
            var spec = internalSearchSpecResolver.searchSpec(
                    effectId(input, "internal-search") + ":root:" + i,
                    rootQuery
            );
            ResolveInternalContextStep.Result result = internalStep.search(
                    input.request().context(), spec
            );

            RootResult rootResult = InternalRootSelection.evaluate(
                    plan.mode(), rootQuery, result.candidates(),
                    result.backendAmbiguous(), result.exhaustive()
            );
            rootResults.add(rootResult);
            refs.addAll(result.evidenceRefs());
            warnings.addAll(result.warnings());
            if (rootResult.outcome() != Outcome.RESOLVED
                    && rootResult.outcome() != Outcome.EMPTY) {
                warnings.add(rootResult.detail());
            }
            LOGGER.info(
                    "Internal root mode={} query=[{}] type={} outcome={} matched={} exhaustive={}",
                    plan.mode(), rootQuery.query(), rootQuery.type(),
                    rootResult.outcome(), rootResult.matched().size(), result.exhaustive()
            );
        }

        Selection selection = new Selection(plan, rootResults);
        Map<String, Object> attributes = new LinkedHashMap<>(extraction.debug());
        attributes.put("rootSelectionMode", plan.mode().name());
        attributes.put("rootQueryCount", plan.queries().size());
        attributes.put("selectedRootCount", selection.selected().size());
        attributes.put("rootResolutionReady", selection.ready());
        attributes.put("rootResolutionSummary", selection.explanation());
        Map<String, Object> debug = Map.copyOf(attributes);
        List<EvidenceRef> evidenceRefs = refs.stream().distinct().toList();
        String summary = selection.ready()
                ? "Resolved " + selection.selected().size() + " root entities using " + plan.mode()
                : "Incomplete root resolution: " + selection.explanation();

        // Reassessment ranks actions; it does not override typed root correctness.
        Candidate unique = plan.mode() == Mode.UNIQUE && selection.selected().size() == 1
                ? selection.selected().getFirst() : null;
        UtilityAssessment utility = reassess(
                input, context, INTERNAL_SEARCH, summary, debug, evidenceRefs,
                unique == null ? "" : unique.entityId(),
                unique == null ? "" : unique.nodeType().name()
        );
        return new InternalSearchState(
                true, evidenceRefs, warnings.stream().distinct().toList(),
                summary, debug, selection, utility
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

            return 0.0d;
        }
        if (blackboard != null
                && blackboard.last(MissionResult.class) != null) {
            return 0.0d;
        }

        MissionIntent intent = input.intent();

        /*
         * Required internal context is a hard execution requirement.
         */
        if (intent.requiresInternalContext()
                && (blackboard == null
                || blackboard.last(
                InternalSearchState.class
        ) == null)) {

            return 1.0d;
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

        /*
         * Before any evidence exists, frozen intent is authoritative.
         */
        if (!intent.requiresInternalContext()) {
            return 0.0d;
        }

        Object initial =
                intent.attributes()
                        .get(
                                "internalUtility"
                        );

        if (initial instanceof Number value) {
            return clamp(
                    value.doubleValue()
            );
        }

        return 0.90d;
    }

    /*
     * ------------------------------------------------------------------
     * COMPANY GRAPH
     * ------------------------------------------------------------------
     */

    @Action(
            description =
                    "Resolve required company graph relationships and output fields",
            readOnly = true,
            cost = 0.15,
            valueMethod = "companyGraphValue"
    )
    public CompanyGraphState readCompanyGraph(
            MissionRunInput input,
            InternalSearchState searchState,
            OperationContext context
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(searchState, "searchState");
        Objects.requireNonNull(context, "context");

        if (!capabilityAllowed(input, COMPANY_GRAPH)) {
            return CompanyGraphState.skipped("Company graph is not allowed", searchState.utility());
        }
        Selection selection = searchState.selection();
        if (!selection.ready()) {
            return CompanyGraphState.skipped("Unresolved root set: " + selection.explanation(),
                    searchState.utility());
        }
        List<Candidate> candidates = selection.selected();
        if (candidates.isEmpty()) {
            return CompanyGraphState.skipped("Exhaustive search returned no matching roots",
                    searchState.utility());
        }
        if (candidates.size() > maxGraphRoots) {
            return CompanyGraphState.skipped("Graph root count exceeds configured limit",
                    searchState.utility());
        }

        long deadline = System.nanoTime() + maxGraphBatchMillis * 1_000_000L;
        List<RootGraphOutcome> outcomes = new ArrayList<>();
        List<EvidenceRef> allRefs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> verifiedRows = new ArrayList<>();
        Status overall = Status.COMPLETE;

        for (int i = 0; i < candidates.size(); i++) {
            if (System.nanoTime() >= deadline) {
                overall = Status.INSUFFICIENT;
                warnings.add("Graph batch exceeded its global time budget; no partial answer is complete");
                break;
            }
            Candidate candidate = candidates.get(i);
            var result = graphTraversalStep.execute(
                    input.request().context(),
                    effectId(input, "company-graph") + ":root:" + i,
                    candidate.entityId(), candidate.nodeType(), input.intent(),
                    selection.plan().perRootGraphQuestion()
            );
            outcomes.add(new RootGraphOutcome(candidate, result.status(), result.evidenceRefs(),
                    result.summary(), result.attributes()));
            warnings.addAll(result.warnings());
            if (result.status() != Status.COMPLETE) {
                overall = result.status();
                warnings.add("Root '" + candidate.label() + "' failed: " + result.summary());
                break; // Never advertise an incomplete batch as a complete answer.
            }
            allRefs.addAll(result.evidenceRefs());
            Object rows = result.attributes().get("validatedGraphAnswerRows");
            if (rows instanceof List<?> verified) {
                for (Object row : verified) {
                    verifiedRows.add(Map.of(
                            "rootEntityId", candidate.entityId(),
                            "rootNodeType", candidate.nodeType().name(),
                            "rootLabel", candidate.label(),
                            "verifiedAnswer", row
                    ));
                }
            }
        }

        if (outcomes.size() != candidates.size() && overall == Status.COMPLETE) {
            overall = Status.INSUFFICIENT;
            warnings.add("Not all required roots were processed");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("graphResolutionStatus", overall.name());
        attributes.put("rootSelectionMode", selection.plan().mode().name());
        attributes.put("requestedRoots", candidates.size());
        attributes.put("processedRoots", outcomes.size());
        attributes.put("validatedGraphAnswerRows", List.copyOf(verifiedRows));
        String summary = overall == Status.COMPLETE
                ? "Resolved all " + candidates.size() + " graph roots"
                : "Company graph requirements incomplete: " + String.join("; ", warnings);
        List<EvidenceRef> evidenceRefs = overall == Status.COMPLETE
                ? allRefs.stream().distinct().toList() : List.of();
        Map<String, Object> debug = Map.copyOf(attributes);
        UtilityAssessment utility = overall == Status.COMPLETE
                ? reassess(input, context, COMPANY_GRAPH, summary, debug, evidenceRefs, "", "")
                : searchState.utility();
        return new CompanyGraphState(
                true, overall, evidenceRefs, warnings.stream().distinct().toList(),
                summary, debug, List.copyOf(outcomes), utility
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
                || !searchState.selection().ready()
                || searchState.selection().selected().isEmpty()
                || !capabilityAllowed(
                input,
                COMPANY_GRAPH
        )) {

            return 0.0;
        }
        if (blackboard != null
                && blackboard.last(MissionResult.class) != null) {
            return 0.0d;
        }

        if (input.intent().requiresCompanyGraph()
                && (blackboard == null
                || blackboard.last(CompanyGraphState.class) == null)) {
            return 1.0d;
        }

        UtilityAssessment latest = latestUtility(blackboard);

        return latest == null
                ? 0.60d
                : clamp(latest.companyGraphUtility());
    }

    /*
     * ------------------------------------------------------------------
     * LEARNING GRAPH
     * ------------------------------------------------------------------
     */





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

        // Exhaustive ALL_MATCHES with zero roots is a grounded negative result,
        // not an ambiguous search and not permission for model-memory synthesis.
        MissionResult emptyRoots = exhaustiveEmptyRootsResult(input, evidenceStates);
        if (emptyRoots != null) return emptyRoots;

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

        List<EvidenceState> groundingStates =
                selectGroundingStates(
                        input.intent(),
                        evidenceStates
                );

        List<EvidenceRef> rawEvidence =
                groundingStates.stream()
                        .flatMap(state ->
                                state.evidenceRefs()
                                        .stream()
                        )
                        .toList();

        List<EvidenceRef> selectedEvidence =
                selectGroundingEvidence(
                        input.intent(),
                        rawEvidence
                );

        List<EvidenceRef> evidenceRefs;
        List<Citation> citations;

        if (selectedEvidence.isEmpty()) {
            evidenceRefs =
                    List.of();

            citations =
                    List.of();

        } else {
            /*
             * BuildCitationsAction remains a deterministic formatter. It does
             * not decide semantic relevance. MissionAgent selects the
             * authoritative grounding set first, then BuildSpec bounds the
             * candidate set and assigns stable citation labels.
             */
            BuildCitationsAction.Result normalized =
                    citationsAction.execute(
                            new BuildCitationsAction.BuildSpec(
                                    selectedEvidence, excerptsByEvidenceId(selectedEvidence), 8)
                    );

            evidenceRefs = normalized.evidenceRefs();
            citations =
                    input.intent().requiresCitations() ? normalized.citations() : List.of();
        }

        List<String> warnings =
                groundingStates.stream()
                        .flatMap(state -> state.warnings().stream())
                        .distinct()
                        .toList();

        List<Map<String, Object>> observations =
                groundingStates.stream()
                        .filter(EvidenceState::performed)
                        .map(MissionAgent::observation)
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
                        input.intent().requiredOutputSections(),

                        Map.of(
                                "query", input.request().query(),
                                "warnings", warnings,
                                "selectedPath", input.intent().selectedPath().name(),
                                "plannerMode", input.intent().plannerMode().name()
                        )
                );

        SynthesisDraft draft =
                synthesisComponent.synthesize(
                        request,
                        context,
                        delta ->
                                eventPublisher.publish(input.tenantId(), delta)
                );

        // Labels have already streamed to the UI. Filter source entries but
        // never relabel the completed answer independently of that stream.
        List<Citation> usedCitations = BuildCitationsAction.retainUsedCitations(
                citations,
                draft.finalAnswer(),
                draft.executiveSummary()
        );

        Map<String, Object> debug = new LinkedHashMap<>(draft.debugSummary());
        debug.put("candidateCitationCount", citations.size());
        debug.put("usedCitationCount", usedCitations.size());
        debug.put("intentEngine", "system-one");
        debug.put("embabelPlanner", input.intent().plannerMode().name());

        debug.put(
                "embabelGraph",
                List.of(
                        "MissionRunInput",
                        "MissionIntent",
                        "EvidenceActions",
                        "MissionResult"
                )
        );

        debug.put(
                "executedEvidenceActions",
                evidenceStates.stream()
                        .filter(EvidenceState::performed)
                        .map(MissionAgent::actionName)
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
                            "document", latest.documentUtility(),
                            "internal", latest.internalUtility(),
                            "companyGraph", latest.companyGraphUtility(),
                            "synthesis", latest.synthesisUtility()
                    )
            );

            debug.put("systemOneUtilityModel", latest.model());
            debug.put("systemOneUtilityRequestId", latest.requestId());
        }
        debug.put("warnings", warnings);

        return new MissionResult(
                input.missionId(),
                draft.executiveSummary(),
                draft.finalAnswer(),
                draft.sections(),
                draft.findings(),
                draft.recommendations(),
                evidenceRefs,
                usedCitations,
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
            return 0.0d;
        }

        if (blackboard != null
                && blackboard.last(MissionResult.class) != null) {
            return 0.0d;
        }

        MissionIntent intent = input.intent();

        if (intent.requiresDocumentEvidence()
                && (blackboard == null
                || blackboard.last(DocumentEvidenceState.class) == null)) {
            return 0.0d;
        }

        InternalSearchState internalState =
                blackboard == null
                        ? null
                        : blackboard.last(InternalSearchState.class);

        if (intent.requiresInternalContext() && internalState == null) {
            return 0.0d;
        }

        /*
         * Hard semantic prerequisite: when the frozen intent requires a
         * company-graph relationship, utility may rank actions only after the
         * required traversal has had a chance to run. If entity resolution
         * failed or is ambiguous, completion is allowed so it can terminate
         * deterministically with insufficient grounded evidence.
         */
        if (intent.requiresCompanyGraph()
                && internalState != null
                && internalState.selection().ready()
                && !internalState.selection().selected().isEmpty()
                && capabilityAllowed(input, COMPANY_GRAPH)
                && blackboard.last(CompanyGraphState.class) == null) {
            return 0.0d;
        }
        CompanyGraphState graphState =
                blackboard == null
                        ? null
                        : blackboard.last(CompanyGraphState.class);

        if (graphState != null
                && graphState.resolutionStatus() != GraphTraversal.Status.COMPLETE) {
            /*
             * complete() will return the deterministic unresolved result.
             * A low synthesis utility must not leave the mission stuck.
             */
            return 1.0d;
        }

        if (!intent.requiresRetrieval()) {
            return 0.95d;
        }

        UtilityAssessment latest = latestUtility(blackboard);

        return latest == null
                ? 0.50d
                : clamp(latest.synthesisUtility());
    }

    /*
     * ------------------------------------------------------------------
     * BLACKBOARD STATE
     * ------------------------------------------------------------------
     */

    public sealed interface EvidenceState
            permits DocumentEvidenceState,
            InternalSearchState,
            CompanyGraphState{

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

            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            summary = summary == null ? "" : summary;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            utility = Objects.requireNonNull(utility, "utility must not be null");
        }
    }

    public record InternalSearchState(
            boolean performed,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            Selection selection,
            UtilityAssessment utility
    ) implements EvidenceState {
        public InternalSearchState {
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            summary = Objects.requireNonNull(summary, "summary");
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
            selection = Objects.requireNonNull(selection, "selection");
            utility = Objects.requireNonNull(utility, "utility");
        }

        public List<Candidate> candidateRoots() {
            return selection.selected();
        }
    }

    public record RootGraphOutcome(
            Candidate root,
            Status status,
            List<EvidenceRef> evidenceRefs,
            String summary,
            Map<String, Object> attributes
    ) {
        public RootGraphOutcome {
            root = Objects.requireNonNull(root, "root");
            status = Objects.requireNonNull(status, "status");
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            summary = Objects.requireNonNull(summary, "summary");
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }
    }

    public record CompanyGraphState(
            boolean performed,
            Status resolutionStatus,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            List<RootGraphOutcome> rootOutcomes,
            UtilityAssessment utility
    ) implements EvidenceState {

        public CompanyGraphState {
            resolutionStatus = Objects.requireNonNull(
                    resolutionStatus,
                    "resolutionStatus must not be null"
            );

            evidenceRefs = evidenceRefs == null
                    ? List.of()
                    : List.copyOf(evidenceRefs);

            warnings = warnings == null
                    ? List.of()
                    : List.copyOf(warnings);

            summary = summary == null ? "" : summary;

            attributes = attributes == null
                    ? Map.of()
                    : Map.copyOf(attributes);

            rootOutcomes = List.copyOf(Objects.requireNonNull(rootOutcomes, "rootOutcomes"));
            utility = Objects.requireNonNull(
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
                    GraphTraversal.Status.INSUFFICIENT,
                    List.of(),
                    List.of(summary),
                    summary,
                    Map.of(
                            "skipped", true,
                            "graphResolutionStatus",
                            GraphTraversal.Status.INSUFFICIENT.name()
                    ),
                    List.of(),
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
        List<Map<String, Object>> evidence = new ArrayList<>();
        Set<String> executed = new LinkedHashSet<>();
        for (EvidenceState state : context.objectsOfType(EvidenceState.class)) {

            evidence.add(
                    utilityEvidence(actionName(state),
                            state.summary(),
                            state.attributes(),
                            state.evidenceRefs()
                    )
            );
            executed.add(actionName(state));
        }

        /*
         * Current action has not yet been written to the blackboard.
         */
        evidence.add(
                utilityEvidence(latestAction, latestSummary, latestAttributes, latestEvidenceRefs));

        executed.add(latestAction);

        return utilityComponent.evaluate(
                new UtilityRequest(
                        input.request().query(),
                        input.intent().selectedPath(),
                        latestAction,
                        List.copyOf(evidence),
                        nullToEmpty(resolvedEntityId),
                        nullToEmpty(resolvedEntityType),
                        Set.copyOf(executed),
                        input.intent().allowedTools(),
                        input.intent().requiresDocumentEvidence(),
                        input.intent().requiresInternalContext(),
                        input.intent().requiresCitations(),
                        input.intent().requiresVerification()
                )
        );
    }

    private static Map<String, Object> utilityEvidence(
            String action,
            String summary,
            Map<String, Object> attributes,
            List<EvidenceRef> evidenceRefs
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("action", nullToEmpty(action));
        state.put("summary", nullToEmpty(summary));
        state.put("attributes", attributes == null ? Map.of() : attributes);

        List<EvidenceRef> refs = evidenceRefs == null ? List.of() : evidenceRefs;

        state.put("evidenceCount", refs.size());

        state.put("signals", refs.stream().limit(25)
                .map(MissionAgent::evidenceSignal)
                .toList()
        );

        return Map.copyOf(state);
    }

    private static Map<String, Object> evidenceSignal(
            EvidenceRef ref
    ) {
        if (ref == null) {
            return Map.of();
        }

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("sourceType", ref.sourceType().name());
        signal.put("sourceService", nullToEmpty(ref.sourceService()));
        signal.put("objectId", nullToEmpty(ref.objectId()));
        signal.put("parentObjectId", nullToEmpty(ref.parentObjectId()));
        signal.put("locationLabel", nullToEmpty(ref.locationLabel()));
        signal.put("section", nullToEmpty(ref.section()));
        signal.put("attributes", ref.attributes() == null ? Map.of() : ref.attributes());

        return Map.copyOf(signal);
    }

    private static UtilityAssessment latestUtility(
            Blackboard blackboard
    ) {
        if (blackboard == null) {
            return null;
        }
        EvidenceState latest = blackboard.last(EvidenceState.class);
        return latest == null ? null : latest.utility();
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
    private static MissionResult exhaustiveEmptyRootsResult(
            MissionRunInput input, List<EvidenceState> evidenceStates
    ) {
        InternalSearchState search = evidenceStates.stream()
                .filter(InternalSearchState.class::isInstance)
                .map(InternalSearchState.class::cast)
                .findFirst().orElse(null);
        if (search == null
                || search.selection().plan().mode() != Mode.ALL_MATCHES
                || !search.selection().ready()
                || !search.selection().selected().isEmpty()) {
            return null;
        }
        return new MissionResult(
                input.missionId(),
                "No matching internal entities",
                "No internal entities matched the requested criteria in the selected tenant.",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of("grounded", true, "result", "EXHAUSTIVE_EMPTY_ROOT_SET"),
                Map.of("rootSelectionMode", Mode.ALL_MATCHES.name(), "rootCount", 0)
        );
    }

    private static MissionResult insufficientEvidenceResult(
            MissionRunInput input,
            List<EvidenceState> evidenceStates
    ) {
        MissionIntent intent = input.intent();
        DocumentEvidenceState documentState =
                evidenceStates.stream()
                        .filter(DocumentEvidenceState.class::isInstance)
                        .map(DocumentEvidenceState.class::cast)
                        .findFirst()
                        .orElse(null);

        InternalSearchState internalState =
                evidenceStates.stream()
                        .filter(InternalSearchState.class::isInstance)
                        .map(InternalSearchState.class::cast)
                        .findFirst()
                        .orElse(null);

        CompanyGraphState companyGraphState =
                evidenceStates.stream()
                        .filter(CompanyGraphState.class::isInstance)
                        .map(CompanyGraphState.class::cast)
                        .findFirst()
                        .orElse(null);

        List<String> missing = new ArrayList<>();

        if (intent.requiresDocumentEvidence()
                && (documentState == null
                || documentState
                .evidenceRefs()
                .isEmpty())) {

            missing.add(
                    "document evidence"
            );
        }

        if (intent.requiresInternalContext()
                && (internalState == null
                || !internalState.selection().ready()
                || (internalState.evidenceRefs().isEmpty()
                    && !internalState.selection().selected().isEmpty()))) {
            missing.add(internalState == null ? "internal context" :
                    "internal root selection: " + internalState.selection().explanation());
        }

        if (intent.requiresCompanyGraph()) {
            if (internalState == null || !internalState.selection().ready()) {
                missing.add("resolved internal root set" +
                        (internalState == null ? "" : ": " + internalState.selection().explanation()));
            } else if (companyGraphState == null) {
                missing.add("company graph execution");
            }
        }

        if (companyGraphState != null) {
            if (companyGraphState.resolutionStatus() != Status.COMPLETE) {
                missing.add(
                        "company graph requirements ["
                                + companyGraphState.resolutionStatus().name()
                                + "]: "
                                + companyGraphState.summary()
                );

            } else if (companyGraphState.evidenceRefs().isEmpty()) {
                missing.add("company graph evidence");
            }
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
                "SparrowX could not establish all required evidence: "
                        + requirement
                        + ".";

        List<String> warnings =
                evidenceStates.stream()
                        .flatMap(state -> state.warnings().stream())
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
                        "grounded", false,
                        "reason", "INSUFFICIENT_REQUIRED_EVIDENCE",
                        "missing",
                        List.copyOf(missing)
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
        Set<String> documentIds = new LinkedHashSet<>();
        Set<String> fileNames = new LinkedHashSet<>();
        for (PreparedArtifact artifact : input.preparedArtifacts().preparedArtifacts()) {

            String documentId = artifact.documentId();
            if (documentId != null && !documentId.isBlank()) {

                documentIds.add(documentId.trim());
            }
            String fileName = artifact.filename();
            if (fileName != null && !fileName.isBlank()) {
                fileNames.add(fileName.trim());
            }
        }

        /*
         * Only explicit document requests may interpret targetEntities as
         * document names/titles.
         *
         * A semantic SparrowX knowledge request such as
         * "explain the agentic architecture" deliberately leaves the scope
         * empty so docsvc can perform corpus-wide semantic retrieval.
         */
        boolean explicitDocumentRequest =
                Boolean.TRUE.equals(
                        intent.attributes()
                                .get(
                                        "explicitDocumentRequest"
                                )
                );

        if (documentIds.isEmpty()
                && fileNames.isEmpty()
                && intent.requiresDocumentEvidence()
                && explicitDocumentRequest) {

            for (String target :
                    intent.targetEntities()) {

                if (target != null
                        && !target.isBlank()) {

                    fileNames.add(
                            target.trim()
                    );
                }
            }
        }

        return new DocumentScope(
                List.copyOf(documentIds),
                List.copyOf(fileNames)
        );
    }

    private static List<EvidenceState> selectGroundingStates(
            MissionIntent intent,
            List<EvidenceState> evidenceStates
    ) {
        if (evidenceStates == null
                || evidenceStates.isEmpty()) {

            return List.of();
        }

        boolean internalOnly =
                intent.requiresInternalContext()
                        && !intent.requiresDocumentEvidence();

        boolean documentOnly =
                intent.requiresDocumentEvidence()
                        && !intent.requiresInternalContext();

        if (internalOnly) {
            return evidenceStates.stream()
                    .filter(state ->
                            !(state instanceof DocumentEvidenceState)
                    )
                    .toList();
        }

        if (documentOnly) {
            return evidenceStates.stream()
                    .filter(DocumentEvidenceState.class::isInstance)
                    .toList();
        }

        return List.copyOf(
                evidenceStates
        );
    }

    /**
     * Selects the evidence that is authoritative for the frozen mission
     * intent before citation labels are created or synthesis sees the data.
     *
     * Internal-only missions prefer the internal search root plus compact
     * graph evidence. The graph EvidenceRef already carries its nodes and
     * relationships, so citing every graph node would be redundant.
     *
     * Document-only missions keep the strongest document evidence, bounded
     * to avoid flooding synthesis/citations with weak retrieval candidates.
     * Mixed missions retain both source families.
     */
    private static List<EvidenceRef> selectGroundingEvidence(
            MissionIntent intent,
            List<EvidenceRef> rawEvidence
    ) {
        if (rawEvidence == null
                || rawEvidence.isEmpty()) {

            return List.of();
        }

        Map<String, EvidenceRef> unique =
                new LinkedHashMap<>();

        for (EvidenceRef ref : rawEvidence) {
            if (ref == null
                    || ref.evidenceId() == null
                    || ref.evidenceId().isBlank()) {

                continue;
            }

            unique.putIfAbsent(
                    ref.evidenceId(),
                    ref
            );
        }

        List<EvidenceRef> deduplicated =
                List.copyOf(
                        unique.values()
                );

        boolean internalOnly =
                intent.requiresInternalContext()
                        && !intent.requiresDocumentEvidence();

        boolean documentOnly =
                intent.requiresDocumentEvidence()
                        && !intent.requiresInternalContext();

        if (internalOnly) {
            return selectInternalGroundingEvidence(
                    deduplicated
            );
        }

        if (documentOnly) {
            return selectDocumentGroundingEvidence(
                    deduplicated
            );
        }

        // Retain both source families for mixed missions within the existing
        // eight-candidate budget. Internal graphs precede search candidates.
        List<EvidenceRef> internal = selectInternalGroundingEvidence(deduplicated);
        List<EvidenceRef> documents = selectDocumentGroundingEvidence(deduplicated);
        Map<String, EvidenceRef> selected = new LinkedHashMap<>();
        internal.stream().limit(4).forEach(ref -> selected.putIfAbsent(ref.evidenceId(), ref));
        documents.stream().limit(4).forEach(ref -> selected.putIfAbsent(ref.evidenceId(), ref));
        for (List<EvidenceRef> family : List.of(internal, documents, deduplicated)) {
            for (EvidenceRef ref : family) {
                if (selected.size() >= 8) {
                    break;
                }
                selected.putIfAbsent(ref.evidenceId(), ref);
            }
        }
        return List.copyOf(selected.values());
    }

    private static List<EvidenceRef> selectInternalGroundingEvidence(
            List<EvidenceRef> evidence
    ) {
        Map<String, EvidenceRef> selected =
                new LinkedHashMap<>();

        // Keep relationship-bearing graph evidence before search candidates
        // so a candidate cap cannot discard the graph behind broad matches.
        for (EvidenceRef ref : evidence) {
            if (isInternalGraphEvidence(ref)) {
                selected.putIfAbsent(ref.evidenceId(), ref);
            }
        }

        /*
         * Keep the resolved search entity: search evidence includes score and
         * matchReason, unlike graph-expanded entity nodes.
         */
        for (EvidenceRef ref : evidence) {
            if (!isInternalEvidence(ref)) {
                continue;
            }

            Map<String, Object> attributes =
                    ref.attributes() == null
                            ? Map.of()
                            : ref.attributes();

            if (attributes.containsKey("matchReason")
                    || attributes.containsKey("score")) {

                selected.putIfAbsent(
                        ref.evidenceId(),
                        ref
                );
            }
        }

        if (!selected.isEmpty()) {
            return selected.values()
                    .stream()
                    .limit(8)
                    .toList();
        }

        /*
         * Search-only internal missions have no graph object. Keep a small,
         * deterministic result set rather than every candidate.
         */
        return evidence.stream()
                .filter(MissionAgent::isInternalEvidence)
                .limit(8)
                .toList();
    }

    private static List<EvidenceRef> selectDocumentGroundingEvidence(
            List<EvidenceRef> evidence
    ) {
        return evidence.stream()
                .filter(MissionAgent::isDocumentEvidence)
                .sorted(
                        (left, right) -> {
                            int score =
                                    Double.compare(
                                            evidenceScore(right),
                                            evidenceScore(left)
                                    );

                            if (score != 0) {
                                return score;
                            }

                            return nullToEmpty(
                                    left.evidenceId()
                            ).compareTo(
                                    nullToEmpty(
                                            right.evidenceId()
                                    )
                            );
                        }
                )
                .limit(8)
                .toList();
    }

    private static boolean isInternalEvidence(
            EvidenceRef ref
    ) {
        return ref != null
                && "internal-service".equals(
                nullToEmpty(
                        ref.sourceService()
                )
        );
    }

    private static boolean isInternalGraphEvidence(
            EvidenceRef ref
    ) {
        if (!isInternalEvidence(ref)) {
            return false;
        }

        String sourceType =
                ref.sourceType() == null
                        ? ""
                        : ref.sourceType().name();

        return sourceType.contains("INTERNAL_GRAPH")
                || nullToEmpty(
                ref.evidenceId()
        ).startsWith("internal-graph:");
    }

    private static boolean isDocumentEvidence(
            EvidenceRef ref
    ) {
        if (ref == null) {
            return false;
        }

        if ("document-service".equals(
                nullToEmpty(
                        ref.sourceService()
                )
        )) {
            return true;
        }

        return ref.artifactId() != null
                && !ref.artifactId().isBlank();
    }

    private static double evidenceScore(
            EvidenceRef ref
    ) {
        if (ref == null
                || ref.attributes() == null
                || ref.attributes().isEmpty()) {

            return 0.0d;
        }

        String[] keys = {
                "relevanceScore",
                "relevance_score",
                "score",
                "confidence"
        };

        for (String key : keys) {
            Object value =
                    ref.attributes()
                            .get(key);

            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }

        return 0.0d;
    }

    /**
     * Allows HYBRID to cross from internal evidence to docsvc only when the
     * retrieved internal evidence explicitly points at a document. A high
     * System-1 document utility by itself is not enough to violate frozen
     * intent.
     */
    private static boolean hasDocumentDiscoverySignal(
            Blackboard blackboard
    ) {
        if (blackboard == null) {
            return false;
        }

        InternalSearchState search = blackboard.last(InternalSearchState.class);

        if (search != null && hasDocumentDiscoverySignal(search.evidenceRefs())) {
            return true;
        }

        CompanyGraphState company = blackboard.last(CompanyGraphState.class);

        return company != null && hasDocumentDiscoverySignal(company.evidenceRefs());
    }

    private static boolean hasDocumentDiscoverySignal(
            List<EvidenceRef> evidence
    ) {
        if (evidence == null
                || evidence.isEmpty()) {

            return false;
        }

        for (EvidenceRef ref : evidence) {
            if (ref == null) {
                continue;
            }

            Map<String, Object> attributes =
                    ref.attributes() == null
                            ? Map.of()
                            : ref.attributes();

            if (hasNonBlankAttribute(
                    attributes,
                    "document_external_ref",
                    "documentExternalRef",
                    "document_id",
                    "documentId",
                    "file_name",
                    "fileName"
            )) {
                return true;
            }

            String uri =
                    nullToEmpty(
                            ref.sourceUri()
                    ).toLowerCase(
                            Locale.ROOT
                    );

            if (uri.endsWith(".pdf")
                    || uri.endsWith(".md")
                    || uri.endsWith(".txt")
                    || uri.endsWith(".doc")
                    || uri.endsWith(".docx")) {

                return true;
            }
        }

        return false;
    }

    private static boolean hasNonBlankAttribute(
            Map<String, Object> attributes,
            String... keys
    ) {
        for (String key : keys) {
            Object value =
                    attributes.get(key);

            if (value instanceof String text
                    && !text.isBlank()) {

                return true;
            }
        }

        return false;
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
