package com.sparrowx.agentic.agents;

import com.embabel.agent.api.common.OperationContext;
import io.grpc.Deadline;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowx.agentic.actions.synthesis.BuildCitationsAction;
import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.components.InternalSearchSpecResolver;
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
import com.sparrowx.agentic.planning.RootNameGrounding;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
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
     * Returns evidence and observations. Action values are computed by the
     * goal reviewer and supplied to Embabel before the action is executed.
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

            return new DocumentEvidenceState(
                    true,
                    null,
                    List.of(),
                    result.warnings(),
                    summary,
                    attributes
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

        return new DocumentEvidenceState(
                true,
                result.graph(),
                result.evidenceRefs(),
                result.warnings(),
                summary,
                attributes
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
        List<RootQuery> verifiedQueries = new ArrayList<>(plan.queries());
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
                    result.backendAmbiguous(), result.exhaustive(), question);
            allRootQueriesExhaustive &= result.exhaustive();
            refs.addAll(result.evidenceRefs());
            warnings.addAll(result.warnings());

            // A model can extract "Orion Gateway module" for an entity whose
            // canonical label is "Orion Gateway". When its lexical search is
            // exhaustively empty, discover candidates with IntSvc's authorized
            // TYPE_SCAN, but admit ONLY a single verified label actually present
            // in the ORIGINAL user request. Re-run EXACT search on that label.
            // This discovery scan never becomes the answer's root scope.
            if (rootResult.outcome() == Outcome.EMPTY && result.exhaustive()
                    && (plan.mode() == Mode.UNIQUE || plan.mode() == Mode.EXPLICIT_SET)
                    && (rootQuery.matchOperator() == MatchOperator.EXACT
                    || rootQuery.matchOperator() == MatchOperator.RELEVANT)) {
                rootResult = recoverNamedRoot(input, question, rootQuery, plan.mode(),
                        effectId(input, "internal-search:" + queryFingerprint(question))
                                + ":recover:" + i, refs, warnings);
            }

            // A lexical exact lookup may be empty because JEV picked the
            // ANSWER type rather than the type of the explicitly named root.
            // Recover across ontology types only through exhaustive IntSvc scans
            // and a second exact lookup of a single original-query literal.
            if (rootResult.outcome() == Outcome.EMPTY && result.exhaustive()
                    && (plan.mode() == Mode.UNIQUE || plan.mode() == Mode.EXPLICIT_SET)
                    && rootQuery.matchOperator() == MatchOperator.EXACT) {
                rootResult = recoverNamedRootAcrossTypes(input, rootQuery,
                        effectId(input, "internal-search:" + queryFingerprint(question))
                                + ":type-recovery:" + i, refs, warnings);
            }

            // TYPE_SCAN is authorized to answer genuinely exhaustive questions,
            // not to replace a failed lookup of an explicitly named entity.
            // When labels in its candidate set are literally named by the user,
            // fail closed instead of silently broadening to all graph nodes.
            if ((plan.mode() == Mode.TYPE_SCAN || plan.mode() == Mode.MULTI_TYPE_SCAN)
                    && rootResult.outcome() == Outcome.RESOLVED) {
                List<String> named = RootNameGrounding.maximalMentions(
                        input.request().query(), result.candidates().stream()
                                .map(Candidate::label).toList());
                if (!named.isEmpty()) {
                    rootResult = new RootResult(rootQuery, List.of(), Outcome.INCOMPLETE,
                            "TYPE_SCAN may broaden explicitly named roots " + named
                                    + "; confirm starting scope before graph execution");
                }
            }
            verifiedQueries.set(i, rootResult.query());
            rootResults.add(rootResult);
            if (rootResult.outcome() != Outcome.RESOLVED
                    && rootResult.outcome() != Outcome.EMPTY) {
                warnings.add(rootResult.detail());
            }
            LOGGER.info(
                    "Internal root mode={} query=[{}] type={} operator={} outcome={} matched={} exhaustive={} verification={}",
                    plan.mode(), rootResult.query().query(), rootResult.query().type(),
                    rootResult.query().matchOperator(), rootResult.outcome(), rootResult.matched().size(), result.exhaustive(), rootResult.detail()
            );
        }

        // RootResult.query must exactly correspond to Plan.queries after
        // canonical recovery; otherwise Selection rightly rejects the ledger.
        Plan verifiedPlan = new Plan(plan.mode(), verifiedQueries, plan.perRootGraphQuestion());
        Selection selection = new Selection(verifiedPlan, rootResults);
        Map<String, Object> attributes = new LinkedHashMap<>(extraction.debug());
        attributes.put("question", question);
        attributes.put("rootSelectionMode", verifiedPlan.mode().name());
        attributes.put("rootQueryCount", verifiedPlan.queries().size());
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

        return new InternalSearchState(
                true, evidenceRefs, warnings.stream().distinct().toList(),
                summary, debug, selection
        );
    }

    /**
     * Recover a wrong surface literal using ONLY a complete, authorized scan of
     * the previously selected ontology type. A recovery does not change type,
     * add roots, invent an entity, or trust an unverified LLM-generated label.
     */
    private RootResult recoverNamedRoot(MissionRunInput input, String question,
                                        RootQuery original, Mode mode, String requestId,
                                        List<EvidenceRef> refs, List<String> warnings) {
        var scanSpec = internalSearchSpecResolver.typeScanSpec(requestId + ":candidate-scan", original.type());
        ResolveInternalEntitiesStep.Result scanned = traceCall(input, "internal", "Internal discovery",
                "INTSVC_ROOT_RECOVERY_SCAN", "Inspect possible canonical root labels",
                Map.of("service", "internal-service", "rootType", original.type()),
                () -> internalStep.search(input.request().context(), scanSpec));
        // A recovery scan is discovery, not answer evidence. Only the
        // subsequent exact verification may contribute final evidence refs.
        warnings.addAll(scanned.warnings());
        if (!scanned.exhaustive()) return new RootResult(original, List.of(), Outcome.INCOMPLETE,
                "Canonical root recovery scan was truncated; cannot certify root identity");

        List<Candidate> candidates = scanned.candidates().stream()
                .filter(c -> original.type().equals(c.nodeType())).toList();
        List<String> names = candidates.stream().map(Candidate::label).toList();
        List<String> anchored = RootNameGrounding.recoveryCandidates(
                input.request().query(), original.query(), names, mode == Mode.UNIQUE);
        if (anchored.size() > 1) return new RootResult(original, List.of(), Outcome.AMBIGUOUS,
                "More than one canonical root name occurs in the user's question");
        if (anchored.isEmpty()) return new RootResult(original, List.of(), Outcome.EMPTY,
                "No uniquely grounded canonical label found for the selected root type");

        String canonical = anchored.getFirst();
        RootQuery corrected = new RootQuery(canonical, original.type(), MatchOperator.EXACT);
        var exactSpec = internalSearchSpecResolver.searchSpec(requestId + ":verified-exact", corrected);
        ResolveInternalEntitiesStep.Result matched = traceCall(input, "internal", "Internal discovery",
                "INTSVC_ROOT_RECOVERY_VERIFY", "Verify canonical root identity",
                Map.of("service", "internal-service", "rootType", original.type()),
                () -> internalStep.search(input.request().context(), exactSpec));
        refs.addAll(matched.evidenceRefs());
        warnings.addAll(matched.warnings());
        RootResult verified = InternalRootSelection.evaluate(mode, corrected, matched.candidates(),
                matched.backendAmbiguous(), matched.exhaustive(), input.request().query());
        if (verified.outcome() == Outcome.RESOLVED) {
            // The candidate must be the one seen in the exhaustive scan; a
            // changed projection cannot silently rebind the root by name.
            Set<String> scannedIds = candidates.stream()
                    .filter(c -> canonical.equalsIgnoreCase(c.label()))
                    .map(Candidate::identity).collect(java.util.stream.Collectors.toSet());
            if (!scannedIds.contains(verified.matched().getFirst().identity()))
                return new RootResult(corrected, List.of(), Outcome.INCOMPLETE,
                        "Canonical entity changed between discovery and verification");
            LOGGER.info("internal.root-recovery.verified missionId={} original=[{}] canonical=[{}] type={}",
                    input.missionId(), original.query(), canonical, original.type());
        }
        return verified;
    }

    /**
     * Recover a misclassified named anchor type without trusting model-supplied
     * node labels. Only ontology-declared, exhaustive backend scans can propose
     * identities; original literal equality and a fresh exact search certify one.
     * Ambiguity or truncation fails closed. No type is hardcoded.
     */
    private RootResult recoverNamedRootAcrossTypes(MissionRunInput input,
                                                   RootQuery original, String requestId,
                                                   List<EvidenceRef> refs, List<String> warnings) {
        List<String> types = internalSearchSpecResolver.declaredTypes(input.request().context());
        if (types.size() > 32) return new RootResult(original, List.of(), Outcome.INCOMPLETE,
                "Ontology too large for bounded cross-type identity recovery");
        List<Candidate> matches = new ArrayList<>();
        for (String type : types) {
            if (type.equals(original.type())) continue; // already exhaustively checked
            var scan = internalSearchSpecResolver.typeScanSpec(requestId + ":scan:" + type, type);
            ResolveInternalEntitiesStep.Result candidates = traceCall(input, "internal", "Internal discovery",
                    "INTSVC_ROOT_TYPE_SCAN", "Verify named root against ontology types",
                    Map.of("service", "internal-service", "rootType", type),
                    () -> internalStep.search(input.request().context(), scan));
            // Do not promote every scanned entity to answer evidence.
            warnings.addAll(candidates.warnings());
            if (!candidates.exhaustive()) return new RootResult(original, List.of(), Outcome.INCOMPLETE,
                    "Cannot verify named root uniqueness: " + type + " scan was truncated");
            candidates.candidates().stream()
                    .filter(c -> type.equals(c.nodeType()))
                    .filter(c -> original.query().equalsIgnoreCase(c.label())
                            || original.query().equalsIgnoreCase(c.slug())
                            || original.query().equalsIgnoreCase(c.entityId()))
                    .forEach(matches::add);
        }
        List<Candidate> distinct = matches.stream()
                .collect(java.util.stream.Collectors.toMap(Candidate::identity, c -> c, (a, b) -> a,
                        LinkedHashMap::new)).values().stream().toList();
        if (distinct.isEmpty()) return new RootResult(original, List.of(), Outcome.EMPTY,
                "No verified ontology identity matched the named qualifier");
        if (distinct.size() != 1) return new RootResult(original, List.of(), Outcome.AMBIGUOUS,
                "Named qualifier matches multiple ontology entities/types");
        Candidate canonical = distinct.getFirst();
        RootQuery corrected = new RootQuery(original.query(), canonical.nodeType(), MatchOperator.EXACT);
        var exactSpec = internalSearchSpecResolver.searchSpec(requestId + ":verify-exact", corrected);
        ResolveInternalEntitiesStep.Result verified = traceCall(input, "internal", "Internal discovery",
                "INTSVC_ROOT_TYPE_VERIFY", "Verify named root with exact type",
                Map.of("service", "internal-service", "rootType", canonical.nodeType()),
                () -> internalStep.search(input.request().context(), exactSpec));
        refs.addAll(verified.evidenceRefs());
        warnings.addAll(verified.warnings());
        RootResult result = InternalRootSelection.evaluate(Mode.UNIQUE, corrected,
                verified.candidates(), verified.backendAmbiguous(), verified.exhaustive(),
                input.request().query());
        if (result.outcome() != Outcome.RESOLVED) return result;
        if (!canonical.identity().equals(result.matched().getFirst().identity()))
            return new RootResult(corrected, List.of(), Outcome.INCOMPLETE,
                    "Named qualifier identity changed between discovery and verification");
        LOGGER.info("internal.root-type-recovery.verified missionId={} type={} literal=[{}]",
                input.missionId(), corrected.type(), corrected.query());
        return result;
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
                    "Unresolved root set: " + selection.explanation());
        }

        List<Candidate> candidates = selection.selected();
        if (candidates.isEmpty()) {
            return CompanyGraphState.skipped(
                    "Exhaustive search returned no matching roots");
        }
        if (candidates.size() > maxGraphRoots) {
            return CompanyGraphState.skipped(
                    "Graph root count exceeds configured limit");
        }

        Deadline deadline = Deadline.after(maxGraphBatchMillis, TimeUnit.MILLISECONDS);
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
            if (deadline.isExpired()) {
                overall = GraphResolutionStatus.INSUFFICIENT;
                warnings.add("Graph batch exceeded its global time budget");
                break;
            }

            String batchEffectId = effectId(input, "company-graph:" + queryFingerprint(question + selection.selected().toString())) + ":batch:" + batch++;
            var result = traceCall(input, "internal", "Company graph", "INTSVC_GRAPH", "Execute graph batch",
                    Map.of("service", "internal-service", "rootType", group.getKey(), "rootCount", Integer.toString(group.getValue().size())),
                    () -> graphTraversalStep.executeBatch(input.request().context(), batchEffectId, prepared, group.getKey(), deadline));
            warnings.addAll(result.warnings());

            boolean timedOut = deadline.isExpired();
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
                List<EvidenceRef> rootRefs = result.evidenceRefs().stream()
                        .filter(ref -> MissionEvidenceGrounding.graphRefMatches(ref, candidate))
                        .toList();
                GraphResolutionStatus rootStatus = rootRefs.isEmpty()
                        ? GraphResolutionStatus.INSUFFICIENT : GraphResolutionStatus.COMPLETE;
                if (rootRefs.isEmpty()) {
                    overall = GraphResolutionStatus.INSUFFICIENT;
                    warnings.add("Missing validated query proof for root " + candidate.identity());
                }
                Map<String, Object> rootAttributes = new LinkedHashMap<>();
                rootAttributes.put("rootEntityId", candidate.entityId());
                rootAttributes.put("rootNodeType", candidate.nodeType());
                rootAttributes.put("validatedGraphAnswerRows", forRoot);
                rootAttributes.put("emptyQueryResult", forRoot.isEmpty());
                outcomes.add(new RootGraphOutcome(candidate, rootStatus, rootRefs,
                        rootRefs.isEmpty() ? "Root proof unavailable"
                                : forRoot.isEmpty() ? "No rows in the verified batch query result"
                                : "Verified root query rows", Map.copyOf(rootAttributes)));
                allRefs.addAll(rootRefs);
            }
        }

        long processedRoots = outcomes.stream()
                .filter(outcome -> outcome.status() == GraphResolutionStatus.COMPLETE).count();
        if (processedRoots != candidates.size() && overall == GraphResolutionStatus.COMPLETE) {
            overall = GraphResolutionStatus.INSUFFICIENT;
            warnings.add("Not all required roots were processed");
        }

        Set<String> recordedRoots = outcomes.stream().map(outcome -> outcome.root().identity())
                .collect(java.util.stream.Collectors.toSet());
        for (Candidate candidate : candidates) {
            if (!recordedRoots.contains(candidate.identity()))
                outcomes.add(new RootGraphOutcome(candidate, overall, List.of(),
                        "Root did not produce a complete query proof", Map.of("notProcessed", true)));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("question", question);
        attributes.put("graphResolutionStatus", overall.name());
        attributes.put("rootSelectionMode", selection.plan().mode().name());
        attributes.put("requestedRoots", candidates.size());
        attributes.put("compiledGraphGoals", prepared.compiledGoals().size());
        attributes.put("processedRoots", processedRoots);
        attributes.put("validatedGraphAnswerRows", List.copyOf(verifiedRows));

        String summary = overall == GraphResolutionStatus.COMPLETE
                ? "Resolved all " + candidates.size() + " graph roots"
                : "Company graph requirements incomplete: "
                + String.join("; ", warnings);
        // Each returned ref is independently validated by IntSvc. A later
        // failed batch must not erase proof from earlier successful batches.
        List<EvidenceRef> evidenceRefs = allRefs.stream().distinct().toList();
        return new CompanyGraphState(
                true, overall, evidenceRefs, warnings.stream().distinct().toList(),
                summary, Map.copyOf(attributes),
                List.copyOf(outcomes)
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
        return complete(input, context, evidenceStates, false);
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



        evidenceStates = MissionEvidenceGrounding.select(input.intent(),
                Objects.requireNonNull(evidenceStates, "evidenceStates"));

        // Exhaustive ALL_MATCHES with zero roots is a grounded negative result,
        // not an ambiguous search and not permission for model-memory synthesis.
        MissionResult emptyRoots = exhaustiveEmptyRootsResult(input, evidenceStates, goalCertified);
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
                .filter(EvidenceState::performed).anyMatch(state -> !state.evidenceRefs().isEmpty());
        if (insufficient != null && (goalCertified || !hasVerifiedEvidence)) {
            return insufficient;
        }
        List<String> unresolvedRequirements = missingRequiredEvidence(input, evidenceStates);

        List<EvidenceState> groundingStates = evidenceStates;

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
             * admitted grounding set first. Retain every admitted source here:
             * a fixed citation cutoff can erase a mandatory root or document.
             * The formatter deduplicates and assigns stable citation labels.
             */
            BuildCitationsAction.Result normalized =
                    citationsAction.execute(
                            new BuildCitationsAction.BuildSpec(
                                    selectedEvidence, excerptsByEvidenceId(selectedEvidence),
                                    Math.max(1, selectedEvidence.size()))
                    );

            evidenceRefs = normalized.evidenceRefs();
            citations = input.intent().requiresCitations() ? normalized.citations() : List.of();
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

    }

    @JsonIgnoreProperties("utility") // Accept pre-migration checkpoints containing the removed field.
    public record DocumentEvidenceState(
            boolean performed,
            DocumentEvidenceGraphProto graph,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes
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
        }
    }

    @JsonIgnoreProperties("utility") // Accept pre-migration checkpoints containing the removed field.
    public record InternalSearchState(
            boolean performed,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            Selection selection
    ) implements EvidenceState {
        public InternalSearchState {
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            summary = Objects.requireNonNull(summary, "summary");
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
            selection = Objects.requireNonNull(selection, "selection");
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

    @JsonIgnoreProperties("utility") // Accept pre-migration checkpoints containing the removed field.
    public record CompanyGraphState(
            boolean performed,
            GraphResolutionStatus resolutionStatus,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes,
            List<RootGraphOutcome> rootOutcomes
    ) implements EvidenceState {

        public CompanyGraphState {
            resolutionStatus = Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            summary = summary == null ? "" : summary;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            rootOutcomes = List.copyOf(Objects.requireNonNull(rootOutcomes, "rootOutcomes"));
        }

        public static CompanyGraphState skipped(String summary) {
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
                    List.of()
            );
        }
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
            MissionRunInput input, List<EvidenceState> evidenceStates, boolean goalCertified
    ) {
        InternalSearchState search = latestState(evidenceStates, InternalSearchState.class);
        if (input.intent().requiresDocumentEvidence() || search == null
                || !search.performed()
                || !Boolean.TRUE.equals(search.attributes().get("rootScopeExhaustive"))
                || !input.request().query().strip().equalsIgnoreCase(
                        Objects.toString(search.attributes().get("question"), "").strip())
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
                Map.of("rootSelectionMode", search.selection().plan().mode().name(), "rootCount", 0,
                        "goalCertified", goalCertified)
        );
    }

    private static List<String> missingRequiredEvidence(
            MissionRunInput input, List<EvidenceState> evidenceStates) {
        MissionIntent intent = input.intent();
        boolean hasDocumentEvidence = evidenceStates.stream()
                .filter(DocumentEvidenceState.class::isInstance)
                .anyMatch(state -> state.performed() && !state.evidenceRefs().isEmpty());
        InternalSearchState internalState = latestState(evidenceStates, InternalSearchState.class);
        CompanyGraphState companyGraphState = latestState(evidenceStates, CompanyGraphState.class);

        List<String> missing = new ArrayList<>();

        if (intent.requiresDocumentEvidence() && !hasDocumentEvidence) {
            missing.add("document evidence");
        }

        if (intent.requiresInternalContext()
                && (internalState == null
                || !internalState.performed()
                || !internalState.selection().ready()
                || !Boolean.TRUE.equals(internalState.attributes().get("rootScopeExhaustive"))
                || internalState.selection().selected().stream().anyMatch(root ->
                        internalState.evidenceRefs().stream().noneMatch(ref ->
                                MissionEvidenceGrounding.entityRefMatches(ref, root))))) {
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

        if (intent.requiresCompanyGraph() && companyGraphState != null) {
            if (companyGraphState.resolutionStatus() != GraphResolutionStatus.COMPLETE) {
                missing.add(
                        "company graph requirements ["
                                + companyGraphState.resolutionStatus().name()
                                + "]: "
                                + companyGraphState.summary()
                );

            } else if (!companyGraphState.performed() || companyGraphState.evidenceRefs().isEmpty()) {
                missing.add("company graph evidence");
            } else if (internalState != null && internalState.selection().ready()) {
                Set<String> requiredRoots = internalState.selection().selected().stream()
                        .map(Candidate::identity).collect(java.util.stream.Collectors.toSet());
                Set<String> provenRoots = companyGraphState.rootOutcomes().stream()
                        .filter(root -> root.status() == GraphResolutionStatus.COMPLETE
                                && !root.evidenceRefs().isEmpty())
                        .map(root -> root.root().identity()).collect(java.util.stream.Collectors.toSet());
                if (!provenRoots.equals(requiredRoots)) missing.add("graph proof for the current root scope");
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

                        "goalCertified", false,

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

    private static <T extends EvidenceState> T latestState(List<EvidenceState> states, Class<T> type) {
        for (int i = states.size() - 1; i >= 0; --i)
            if (type.isInstance(states.get(i))) return type.cast(states.get(i));
        return null;
    }

    private static List<EvidenceRef> selectGroundingEvidence(
            MissionIntent intent, List<EvidenceRef> rawEvidence) {
        Map<String, EvidenceRef> unique = new LinkedHashMap<>();
        if (rawEvidence != null) {
            for (EvidenceRef ref : rawEvidence) {
                if (ref != null && ref.evidenceId() != null && !ref.evidenceId().isBlank())
                    unique.put(ref.evidenceId(), ref); // Prefer the latest validated observation of this identity.
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
        return MissionCapabilities.allows(input.intent(), capability);
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
