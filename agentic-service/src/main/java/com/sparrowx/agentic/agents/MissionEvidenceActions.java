package com.sparrowx.agentic.agents;

import com.embabel.agent.api.common.OperationContext;
import com.sparrowx.agentic.actions.synthesis.BuildCitationsAction;
import com.sparrowx.agentic.components.InternalSearchSpecResolver;
import com.sparrowx.agentic.components.SystemOneUtilityComponent.UtilityAssessment;
import com.sparrowx.agentic.components.SynthesisComponent;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisDraft;
import com.sparrowx.agentic.components.SynthesisComponent.SynthesisRequest;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.trace.MissionTracePublisher;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.mission.artifact.PreparedArtifact;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.planning.InternalRootSelection;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import org.springframework.beans.factory.annotation.Value;
import com.sparrowx.agentic.steps.BuildDocumentEvidenceStep;
import com.sparrowx.agentic.steps.GoalDirectedCompanyGraphStep;
import com.sparrowx.agentic.steps.ResolveInternalEntitiesStep;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder.BuildSpec;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder.Scope;
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
 * Enterprise capability adapter. Embabel state actions own control flow.
 * Execution model:
 * Business graph/document adapters only; no @Action or @AchievesGoal.
 * MissionIntent is resolved before this process starts.
 * This agent never reclassifies the mission intent.
 */
@org.springframework.stereotype.Component
public final class MissionEvidenceActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(MissionEvidenceActions.class);

    private static final String DOCUMENT_BUILD = MissionCapabilities.DOCUMENT_EVIDENCE;
    private static final String INTERNAL_SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    private static final String COMPANY_GRAPH = MissionCapabilities.COMPANY_GRAPH;

    private final BuildDocumentEvidenceStep documentStep;
    private final ResolveInternalEntitiesStep internalStep;
    private final GoalDirectedCompanyGraphStep graphTraversalStep;
    private final BuildCitationsAction citationsAction;
    private final SynthesisComponent synthesisComponent;
    private final MissionEventPublisher eventPublisher;
    private MissionTracePublisher tracePublisher;

    // Optional method injection preserves the public constructor and tests.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTracePublisher(MissionTracePublisher tracePublisher) {
        this.tracePublisher = tracePublisher;
    }

    private <T> T traceCall(MissionRunInput input, String sectorId, String sector,
                            String kind, String title, Map<String, String> attrs,
                            java.util.function.Supplier<T> operation) {
        return tracePublisher == null ? operation.get()
                : tracePublisher.observe(input, MissionDecisionTraceContext.parentIdOr(null), sectorId, sector, kind, title, attrs, operation);
    }

    private final InternalSearchSpecResolver internalSearchSpecResolver;
    private final int maxGraphRoots;
    private final long maxGraphBatchMillis;
    @Value("${sparrowx.documents.evidence.max-results:20}")
    private int maxDocumentEvidenceResults = 20;

    public MissionEvidenceActions(
            BuildDocumentEvidenceStep documentStep,
            ResolveInternalEntitiesStep internalStep,
            GoalDirectedCompanyGraphStep graphTraversalStep,
            InternalSearchSpecResolver internalSearchSpecResolver,
            BuildCitationsAction citationsAction,
            SynthesisComponent synthesisComponent,
            MissionEventPublisher eventPublisher,
            @Value("${sparrowx.graph.max-graph-roots:32}") int maxGraphRoots,
            @Value("${sparrowx.graph.max-graph-batch-millis:120000}") long maxGraphBatchMillis
    ) {
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

    public DocumentEvidenceState buildDocumentEvidence(MissionRunInput input, OperationContext context) {
        return buildDocumentEvidence(input, context, input.intent().objective());
    }

    public DocumentEvidenceState buildDocumentEvidence(
            MissionRunInput input,
            OperationContext context,
            String question
    ) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");

        MissionIntent intent = input.intent();
        // Frozen source requirements and tool authorization are separate checks.
        // An allowed capability must not silently expand the mission's sources.
        if (!intent.requiresDocumentEvidence()) {
            throw new IllegalStateException("Document evidence was not required by the frozen mission intent");
        }
        if (!capabilityAllowed(input, DOCUMENT_BUILD)) {
            throw new SecurityException("Document evidence capability is not authorized for this mission");
        }
        DocumentScope scope = documentScope(input, intent);
        LOGGER.info("mission.documents.scope missionId={} documentIds={} fileNames={} "
                        + "mode={} verificationRequested={}", input.missionId(),
                scope.documentIds().size(), scope.fileNames().size(),
                scope.documentIds().isEmpty() && scope.fileNames().isEmpty()
                        ? "CORPUS_WIDE" : "SCOPED", intent.requiresVerification());

        if (maxDocumentEvidenceResults < 1) {
            throw new IllegalStateException("Invalid document evidence result budget");
        }
        String requestId = effectId(input, "document-evidence:" + queryFingerprint(question));

        BuildSpec spec =
                new BuildSpec(requestId,
                        new Scope(
                                scope.documentIds(), scope.fileNames(),
                                List.of(), List.of(),
                                Map.of()),

                        EvidenceGoalProto.EVIDENCE_GOAL_CUSTOM,
                        question,
                        List.of(),
                        List.of(),
                        "",
                        "",
                        Map.of(),
                        question,
                        List.copyOf(intent.topics()),
                        List.copyOf(intent.targetEntities()),
                        List.of(),
                        Map.of(),
                        "",
                        RetrievalModeProto.RETRIEVAL_MODE_HYBRID,
                        maxDocumentEvidenceResults,
                        true,
                        true,
                        intent.requiresVerification()
                );

        var result = traceCall(input, "document", "Document evidence", "DOCSVC", "Build document evidence",
                Map.of("service", "document-service"),
                () -> documentStep.execute(input.request().context(), spec));

        LOGGER.info("mission.documents.result missionId={} evidenceRefs={} coverageScore={} "
                        + "scopeMode={} verificationRequested={}", input.missionId(),
                result.evidenceRefs().size(), result.coverageScore(),
                scope.documentIds().isEmpty() && scope.fileNames().isEmpty()
                        ? "CORPUS_WIDE" : "SCOPED", intent.requiresVerification());

        // coverageScore is a retrieval metric, not a per-document completeness
        // certificate or a replacement for actual evidence references.
        boolean noRelevantEvidence = result.evidenceRefs().isEmpty();
        if (!noRelevantEvidence && result.coverageScore() <= 0.0d) {
            LOGGER.warn("mission.documents.low-coverage-with-references missionId={} evidenceRefs={}",
                    input.missionId(), result.evidenceRefs().size());
        }

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
                            true,

                            "documentScopeMode",
                            scope.documentIds().isEmpty() && scope.fileNames().isEmpty()
                                    ? "CORPUS_WIDE" : "SCOPED",

                            "scopedDocumentCount",
                            scope.documentIds().size() + scope.fileNames().size(),

                            "verificationRequested",
                            intent.requiresVerification()
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
                    List.of(),
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
                                .size(),

                        "documentScopeMode",
                        scope.documentIds().isEmpty() && scope.fileNames().isEmpty()
                                ? "CORPUS_WIDE" : "SCOPED",

                        "scopedDocumentCount",
                        scope.documentIds().size() + scope.fileNames().size(),

                        "verificationRequested",
                        intent.requiresVerification()
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

    /*
     * ------------------------------------------------------------------
     * INTERNAL ENTITY SEARCH
     * ------------------------------------------------------------------
     */

    public InternalSearchState searchInternalEntities(MissionRunInput input, OperationContext context) {
        return searchInternalEntities(input, context, input.request().query());
    }

    public InternalSearchState searchInternalEntities(
            MissionRunInput input,
            OperationContext context,
            String question
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        if (!input.intent().requiresInternalContext()) {
            throw new IllegalStateException("Internal discovery was not required by the frozen mission intent");
        }
        if (!capabilityAllowed(input, INTERNAL_SEARCH)) {
            throw new SecurityException("Internal discovery capability is not authorized for this mission");
        }

        var extraction = traceCall(input, "internal", "Internal discovery", "ROOT_RESOLUTION", "Resolve root search plan",
                Map.of("service", "internal-service"),
                () -> internalSearchSpecResolver.resolve(new InternalSearchSpecResolver.Request(
                        input.request().context(), input.missionId(),
                        effectId(input, "internal-search:" + queryFingerprint(question)),
                        question, input.intent().requiresCompanyGraph())));
        Plan plan = extraction.plan();
        List<RootResult> rootResults = new ArrayList<>();
        boolean allRootQueriesExhaustive = true;
        List<EvidenceRef> refs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < plan.queries().size(); i++) {
            RootQuery rootQuery = plan.queries().get(i);
            var spec = (plan.mode() == Mode.TYPE_SCAN || plan.mode() == Mode.MULTI_TYPE_SCAN)
                    ? internalSearchSpecResolver.typeScanSpec(
                    effectId(input, "internal-search:" + queryFingerprint(question)) + ":root:" + i,
                    rootQuery.type())
                    : internalSearchSpecResolver.searchSpec(
                    effectId(input, "internal-search:" + queryFingerprint(question)) + ":root:" + i,
                    rootQuery
            );
            ResolveInternalEntitiesStep.Result result = traceCall(input, "internal", "Internal discovery", "INTSVC_SEARCH", "Search internal entities",
                    Map.of("service", "internal-service", "rootType", rootQuery.type()),
                    () -> internalStep.search(input.request().context(), spec));

            RootResult rootResult = InternalRootSelection.evaluate(
                    plan.mode(), rootQuery, result.candidates(),
                    result.backendAmbiguous(), result.exhaustive(),
                    question
            );
            rootResults.add(rootResult);
            allRootQueriesExhaustive &= result.exhaustive();
            refs.addAll(result.evidenceRefs());
            warnings.addAll(result.warnings());
            if (rootResult.outcome() != Outcome.RESOLVED
                    && rootResult.outcome() != Outcome.EMPTY) {
                warnings.add(rootResult.detail());
            }
            LOGGER.info(
                    "Internal root mode={} query=[{}] type={} operator={} outcome={} matched={} exhaustive={} verification={}",
                    plan.mode(), rootQuery.query(), rootQuery.type(), rootQuery.matchOperator(),
                    rootResult.outcome(), rootResult.matched().size(), result.exhaustive(), rootResult.detail()
            );
        }

        Selection selection = new Selection(plan, rootResults);
        Map<String, Object> attributes = new LinkedHashMap<>(extraction.debug());
        attributes.put("rootSelectionMode", plan.mode().name());
        attributes.put("rootQueryCount", plan.queries().size());
        attributes.put("selectedRootCount", selection.selected().size());
        attributes.put("rootResolutionReady", selection.ready());
        // Expose backend-verified root completeness to the review layer.
        // A planner may not turn a partial entity lookup into an "all teams" claim.
        attributes.put("rootScopeExhaustive", selection.ready() && allRootQueriesExhaustive);
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
                unique == null ? "" : unique.nodeType()
        );
        return new InternalSearchState(
                true, evidenceRefs, warnings.stream().distinct().toList(),
                summary, debug, selection, utility
        );
    }

    /*
     * ------------------------------------------------------------------
     * COMPANY GRAPH
     * ------------------------------------------------------------------
     */

    public CompanyGraphState readCompanyGraph(MissionRunInput input,
            InternalSearchState searchState, OperationContext context) {
        return readCompanyGraph(input, searchState, context,
                searchState.selection().plan().perRootGraphQuestion());
    }

    public CompanyGraphState readCompanyGraph(
            MissionRunInput input,
            InternalSearchState searchState,
            OperationContext context,
            String question
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(searchState, "searchState");
        Objects.requireNonNull(context, "context");

        if (!input.intent().requiresCompanyGraph()) {
            throw new IllegalStateException("Graph traversal was not required by the frozen mission intent");
        }
        if (!capabilityAllowed(input, COMPANY_GRAPH)) {
            throw new SecurityException("Company graph capability is not authorized for this mission");
        }

        Selection selection = searchState.selection();
        if (!selection.ready()) {
            return CompanyGraphState.skipped(
                    "Unresolved root set: " + selection.explanation(),
                    searchState.utility());
        }

        List<Candidate> candidates = selection.selected();
        if (candidates.isEmpty()) {
            return CompanyGraphState.skipped(
                    "Exhaustive search returned no matching roots",
                    searchState.utility());
        }
        if (candidates.size() > maxGraphRoots) {
            return CompanyGraphState.skipped(
                    "Graph root count exceeds configured limit",
                    searchState.utility());
        }

        long deadline = System.nanoTime() + maxGraphBatchMillis * 1_000_000L;
        GoalDirectedCompanyGraphStep.Prepared prepared = traceCall(input, "internal", "Company graph", "GRAPH_COMPILE", "Compile graph goal",
                Map.of("rootCount", Integer.toString(candidates.size())),
                () -> graphTraversalStep.prepare(input.request().context(),
                    effectId(input, "company-graph:" + queryFingerprint(question + selection.selected().toString())) + ":batch",
                    candidates.stream().map(candidate -> new GoalDirectedCompanyGraphStep.SelectedRoot(
                            candidate.entityId(), candidate.nodeType())).toList(),
                    input.intent(), question));

        List<RootGraphOutcome> outcomes = new ArrayList<>();
        List<EvidenceRef> allRefs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> verifiedRows = new ArrayList<>();
        GraphResolutionStatus overall = GraphResolutionStatus.COMPLETE;

        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        candidates.forEach(candidate -> groups.computeIfAbsent(
                candidate.nodeType(), ignored -> new ArrayList<>()).add(candidate));

        int batch = 0;
        for (var group : groups.entrySet()) {
            if (System.nanoTime() >= deadline) {
                overall = GraphResolutionStatus.INSUFFICIENT;
                warnings.add("Graph batch exceeded its global time budget");
                break;
            }

            String batchEffectId = effectId(input, "company-graph:" + queryFingerprint(question + selection.selected().toString())) + ":batch:" + batch++;
            var result = traceCall(input, "internal", "Company graph", "INTSVC_GRAPH", "Execute graph batch",
                    Map.of("service", "internal-service", "rootType", group.getKey(), "rootCount", Integer.toString(group.getValue().size())),
                    () -> graphTraversalStep.executeBatch(input.request().context(), batchEffectId, prepared, group.getKey()));
            warnings.addAll(result.warnings());

            boolean timedOut = System.nanoTime() >= deadline;
            if (timedOut || result.status() != GraphResolutionStatus.COMPLETE) {
                overall = timedOut ? GraphResolutionStatus.INSUFFICIENT : result.status();
                warnings.add(timedOut
                        ? "Graph batch exceeded its global time budget"
                        : "Graph query failed for root type " + group.getKey());
                break;
            }

            Object rows = result.attributes().get("validatedGraphAnswerRows");
            if (!(rows instanceof List<?> verified)) {
                overall = GraphResolutionStatus.INSUFFICIENT;
                warnings.add("Graph query returned no validated row collection");
                break;
            }

            allRefs.addAll(result.evidenceRefs());
            for (Candidate candidate : group.getValue()) {
                List<?> forRoot = verified.stream()
                        .filter(Map.class::isInstance)
                        .filter(row -> candidate.entityId().equals(
                                ((Map<?, ?>) row).get("rootEntityId")))
                        .toList();

                for (Object row : forRoot) {
                    verifiedRows.add(Map.of(
                            "rootEntityId", candidate.entityId(),
                            "rootNodeType", candidate.nodeType(),
                            "rootLabel", candidate.label(),
                            "verifiedAnswer", row
                    ));
                }
                outcomes.add(new RootGraphOutcome(
                        candidate, GraphResolutionStatus.COMPLETE, result.evidenceRefs(),
                        result.summary(), result.attributes()));
            }
        }

        if (outcomes.size() != candidates.size() && overall == GraphResolutionStatus.COMPLETE) {
            overall = GraphResolutionStatus.INSUFFICIENT;
            warnings.add("Not all required roots were processed");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("graphResolutionStatus", overall.name());
        attributes.put("rootSelectionMode", selection.plan().mode().name());
        attributes.put("requestedRoots", candidates.size());
        attributes.put("compiledGraphGoals", prepared.compiledGoals().size());
        attributes.put("processedRoots", outcomes.size());
        attributes.put("validatedGraphAnswerRows", List.copyOf(verifiedRows));

        String summary = overall == GraphResolutionStatus.COMPLETE
                ? "Resolved all " + candidates.size() + " graph roots"
                : "Company graph requirements incomplete: "
                + String.join("; ", warnings);
        // Each returned ref is independently validated by IntSvc. A later
        // failed batch must not erase proof from earlier successful batches.
        List<EvidenceRef> evidenceRefs = allRefs.stream().distinct().toList();
        Map<String, Object> debug = Map.copyOf(attributes);
        UtilityAssessment utility = overall == GraphResolutionStatus.COMPLETE
                ? reassess(input, context, COMPANY_GRAPH, summary, debug,
                evidenceRefs, "", "")
                : searchState.utility();

        return new CompanyGraphState(
                true, overall, evidenceRefs, warnings.stream().distinct().toList(),
                summary, debug,
                List.copyOf(outcomes),
                utility
        );
    }

    /*
     * ------------------------------------------------------------------
     * TERMINAL SYNTHESIS
     * ------------------------------------------------------------------
     */

    public MissionResult complete(MissionRunInput input, OperationContext context) {
        return complete(input, context, context.objectsOfType(EvidenceState.class));
    }

    public MissionResult complete(
            MissionRunInput input,
            OperationContext context,
            List<EvidenceState> evidenceStates
    ) {
        return complete(input, context, evidenceStates, true);
    }

    /** goalCertified=false permits a qualified evidence report, not a success claim. */
    public MissionResult complete(
            MissionRunInput input,
            OperationContext context,
            List<EvidenceState> evidenceStates,
            boolean goalCertified
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
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

        // Verified evidence from one source remains reportable when another
        // source is unavailable. A partial report is never goal-certified.
        boolean hasVerifiedEvidence = evidenceStates.stream()
                .anyMatch(state -> !state.evidenceRefs().isEmpty());
        if (insufficient != null && (goalCertified || !hasVerifiedEvidence)) {
            return insufficient;
        }
        List<String> unresolvedRequirements = missingRequiredEvidence(input, evidenceStates);

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
             * not decide semantic relevance. MissionEvidenceActions selects the
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
                        .map(MissionEvidenceActions::observation)
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
                                "plannerMode", input.intent().plannerMode().name(),
                                "goalCertified", goalCertified && unresolvedRequirements.isEmpty(),
                                "unresolvedRequirements", unresolvedRequirements
                        )
                );

        SynthesisDraft draft = traceCall(input, "synthesis", "Response synthesis", "SYNTHESIS", "Generate grounded answer",
                Map.of("capability", "synthesis"),
                () -> synthesisComponent.synthesize(request, context,
                        delta -> eventPublisher.publish(input.tenantId(), delta)));

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
        debug.put("intentEngine", input.intent().attributes().getOrDefault("intentEngine", "unspecified"));
        debug.put("goalCertified", goalCertified && unresolvedRequirements.isEmpty());
        debug.put("unresolvedRequirements", unresolvedRequirements);
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
                        .map(MissionEvidenceActions::actionName)
                        .toList()
        );

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
            GraphResolutionStatus status,
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
            GraphResolutionStatus resolutionStatus,
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
                    GraphResolutionStatus.INSUFFICIENT,
                    List.of(),
                    List.of(summary),
                    summary,
                    Map.of(
                            "skipped", true,
                            "graphResolutionStatus",
                            GraphResolutionStatus.INSUFFICIENT.name()
                    ),
                    List.of(),
                    utility
            );
        }
    }

    /**
     * Compatibility only: legacy evidence DTOs still carry a utility field.
     * Ranking has moved to Embabel planner selection and LLM goal review.
     * This method performs no model invocation or heuristic action selection.
     */
    private static UtilityAssessment reassess(MissionRunInput input, OperationContext context,
            String latestAction, String latestSummary, Map<String, Object> latestAttributes,
            List<EvidenceRef> latestEvidenceRefs, String resolvedEntityId, String resolvedEntityType) {
        return new UtilityAssessment(0, 0, 0, 0, 0,
                "not-ranked", "not-ranked", effectId(input, "observation:" + latestAction));
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
        if (input.intent().requiresDocumentEvidence() || search == null
                || (search.selection().plan().mode() != Mode.ALL_MATCHES
                && search.selection().plan().mode() != Mode.TYPE_SCAN
                && search.selection().plan().mode() != Mode.MULTI_TYPE_SCAN)
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
                Map.of("rootSelectionMode", search.selection().plan().mode().name(), "rootCount", 0)
        );
    }

    private static List<String> missingRequiredEvidence(
            MissionRunInput input, List<EvidenceState> evidenceStates) {
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
            if (companyGraphState.resolutionStatus() != GraphResolutionStatus.COMPLETE) {
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

        return List.copyOf(missing);
    }

    private static MissionResult insufficientEvidenceResult(
            MissionRunInput input, List<EvidenceState> evidenceStates) {
        MissionIntent intent = input.intent();
        List<String> missing = missingRequiredEvidence(input, evidenceStates);
        if (missing.isEmpty()) return null;

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
                        intent.attributes().getOrDefault("intentEngine", "unspecified"),

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

        // No guessed filename constraints from target entity names or private
        // intent attributes. DocSvc's DocumentScopeProto filters are exact:
        // only explicitly supplied artifacts are scoped at this point.
        // Graph-discovered document identities need a typed evidence handoff.

        return new DocumentScope(
                List.copyOf(documentIds),
                List.copyOf(fileNames)
        );
    }

    /**
     * Source selection was historically tied to frozen intent flags and could
     * silently discard evidence requested later by the model. Preserve all
     * authorized observations; semantic relevance is decided during review.
     */
    private static List<EvidenceState> selectGroundingStates(
            MissionIntent intent, List<EvidenceState> evidenceStates) {
        return evidenceStates == null ? List.of() : List.copyOf(evidenceStates);
    }

    private static List<EvidenceRef> selectGroundingEvidence(
            MissionIntent intent, List<EvidenceRef> rawEvidence) {
        Map<String, EvidenceRef> unique = new LinkedHashMap<>();
        if (rawEvidence != null) {
            for (EvidenceRef ref : rawEvidence) {
                if (ref != null && ref.evidenceId() != null && !ref.evidenceId().isBlank())
                    unique.putIfAbsent(ref.evidenceId(), ref);
            }
        }
        return List.copyOf(unique.values());
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

    private static String queryFingerprint(String query) {
        return java.util.UUID.nameUUIDFromBytes(
                query.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
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
