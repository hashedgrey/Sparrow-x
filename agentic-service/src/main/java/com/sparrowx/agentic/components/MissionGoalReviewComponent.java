package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JEV evaluates semantic goal status and evidence sufficiency, but never
 * selects or excludes candidate actions. MissionActionPolicy independently
 * owns eligibility and blends optional bounded JEV marginal-value signals
 * with deterministic utility. Queries are generated only for the selected action.
 * Java retains authoritative evidence and budget guards.
 */
@Component
public final class MissionGoalReviewComponent {
    private static final Logger LOG = LoggerFactory.getLogger(MissionGoalReviewComponent.class);
    public static final String DOCUMENT = MissionCapabilities.DOCUMENT_EVIDENCE;
    public static final String SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    public static final String GRAPH = MissionCapabilities.COMPANY_GRAPH;
    public static final String NONE = MissionCapabilities.NONE;
    public static final double EVIDENCE_ACTION_COST = 0.05d;

    private final SemanticDecisionService semantic;
    private final int maxEvidenceActions;
    private final int maxCallsPerCapability;
    @Value("${sparrowx.mission.reasoning.status-confidence:0.80}")
    private double statusConfidence = 0.80d;
    @Value("${sparrowx.mission.reasoning.graph-coverage-confidence:0.90}")
    private double graphCoverageConfidence = 0.90d;

    public MissionGoalReviewComponent(
            SemanticDecisionService semantic,
            @Value("${sparrowx.mission.reasoning.max-evidence-actions:10}") int maxEvidenceActions,
            @Value("${sparrowx.mission.reasoning.max-per-capability:3}") int maxCallsPerCapability) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        if (maxEvidenceActions < 1 || maxEvidenceActions > 100
                || maxCallsPerCapability < 1 || maxCallsPerCapability > maxEvidenceActions) {
            throw new IllegalArgumentException("Invalid evidence iteration budget");
        }
        this.maxEvidenceActions = maxEvidenceActions;
        this.maxCallsPerCapability = maxCallsPerCapability;
    }

    public Decision review(Request request) {
        Decision decision = reviewInternal(request);
        MissionDecisionTraceContext.validated(decision.status().name(), decision.nextAction(),
                decision.missingFacts().size(), decision.alternatives().size());
        return decision;
    }

    private Decision reviewInternal(Request request) {
        Objects.requireNonNull(request, "request");
        for (double value : new double[]{statusConfidence, graphCoverageConfidence}) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalStateException("Invalid JEV confidence threshold");
            }
        }
        // Jev can certify bounded coverage using verified, non-truncated graph
        // observations. It cannot create facts; the graph adapter remains the
        // authority for completeness, root coverage and answer rows.
        Decision fast = tryGraphCoverageFastPath(request);
        if (fast != null) return fast;

        Set<String> eligible = eligible(request);
        // Deterministic mandatory-evidence guard avoids a needless JEV call
        // when only one required prerequisite is available. It does not choose
        // which tool Embabel should execute.
        Decision mandatory = tryMandatoryEvidencePrerequisite(request, eligible);
        if (mandatory != null) return mandatory;

        // JEV is authoritative for bounded GOAL STATUS, not action selection.
        // Action scoring and query generation are performed elsewhere.
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("originalMission", request.objective());
        input.put("latestCapability", request.latestCapability());
        input.put("observations", request.observations());
        input.put("requiresRetrieval", request.requiresRetrieval());
        input.put("requiresCompanyGraph", request.requiresCompanyGraph());
        input.put("requiresDocumentEvidence", request.requiresDocumentEvidence());
        input.put("requiresInternalContext", request.requiresInternalContext());
        input.put("requiresVerification", request.requiresVerification());
        input.put("attemptsByCapability", request.attempts());
        input.put("permittedNextCapabilities", eligible.stream().sorted().toList());
        input.put("resolvedGraphRoots", hasUsableGraphRoots(request.observations()));
        input.put("completeGraphExecutionObserved", hasCompleteGraphObservation(request.observations()));
        input.put("firstGraphAttempt", request.attempts().getOrDefault(GRAPH, 0) == 0);
        input.put("remainingEvidenceActions", Math.max(0,
                maxEvidenceActions - request.attempts().values().stream().mapToInt(Integer::intValue).sum()));

        Map<String, String> statuses = Map.of(
                "SATISFIED", "The ORIGINAL question is completely supported by verified evidence",
                "NEEDS_EVIDENCE", "Additional authorized evidence can resolve remaining requirements",
                "AMBIGUOUS", "The question or available evidence is inherently ambiguous",
                "BLOCKED", "Required evidence cannot be obtained using permitted capabilities");
        // Status and action are different decisions. A terminal review has no
        // meaningful next action; demanding both in one JEV batch caused
        // otherwise valid progress evaluations to abstain.
        var statusVote = semantic.tryBounded(input, Map.of(
                        "status", new ChoiceQuestion(
                                "Review the ORIGINAL mission against ALL verified observations. "
                                        + "NEVER equate a successful tool call with complete evidence. "
                                        + "Use SATISFIED only for established evidence coverage, "
                                        + "NEEDS_EVIDENCE for an attainable missing fact, "
                                        + "AMBIGUOUS for unavoidable ambiguity and BLOCKED for "
                                        + "unobtainable evidence.", statuses)),
                statusConfidence, request.operationId() + ":goal-status");
        boolean missingRequired = requiredEvidenceMissing(request);
        Status status;
        if (statusVote.isPresent()
                && statusVote.get().answers().get("status") instanceof ChoiceAnswer statusAnswer) {
            status = Status.valueOf(statusAnswer.choice());
        } else if (missingRequired && !eligible.isEmpty()) {
            // Structural evidence prerequisites are policy, not a JEV
            // certification. This never declares the mission satisfied.
            status = Status.NEEDS_EVIDENCE;
            MissionDecisionTraceContext.event("REQUIRED_EVIDENCE_INCOMPLETE",
                    "JEV status abstained; authorized evidence still required",
                    Map.of("goalCertified", "false"));
        } else if (!missingRequired && hasGroundedTerminalReportEvidence(request)) {
            // An abstention is not a negative fact and must not be promoted to
            // SATISFIED. But verified evidence can support a truthful, partial
            // report that explicitly discloses uncertified completeness.
            LOG.warn("mission.goal-review.uncertified-report missionId={} "
                            + "observations={} graphRequired={} documentsRequired={} verificationRequired={} "
                            + "goalCertified=false",
                    request.missionId(), request.observations().size(),
                    request.requiresCompanyGraph(), request.requiresDocumentEvidence(),
                    request.requiresVerification());
            MissionDecisionTraceContext.event("EVIDENCE_REPORT_NOT_CERTIFIED",
                    "JEV abstained; source-backed evidence is available for a qualified report",
                    Map.of("goalCertified", "false", "reportStatus", "PARTIAL_OR_UNCERTIFIED"));
            return new Decision(Status.READY_FOR_SYNTHESIS, NONE, "",
                    "JEV abstained on goal completeness; report only supported findings "
                            + "and explicitly identify any unresolved requirements", List.of());
        } else {
            LOG.warn("mission.goal-review.blocked missionId={} "
                            + "statusAbstained=true missingRequired={} eligibleActions={} "
                            + "usableRoots={} completeGraph={} usableDocuments={} "
                            + "observations={} graphAttempts={} documentAttempts={}",
                    request.missionId(), missingRequired, eligible,
                    hasUsableGraphRoots(request.observations()),
                    hasCompleteGraphObservation(request.observations()),
                    request.observations().stream().anyMatch(o -> DOCUMENT.equals(o.get("action"))
                            && hasUsableEvidence(o)),
                    request.observations().size(),
                    request.attempts().getOrDefault(GRAPH, 0),
                    request.attempts().getOrDefault(DOCUMENT, 0));
            return new Decision(Status.BLOCKED, NONE, "",
                    "JEV could not confidently certify goal status", List.of());
        }
        if (missingRequired && !eligible.isEmpty()
                && (status == Status.SATISFIED || status == Status.BLOCKED)) {
            // A semantic vote cannot mark an unattempted, permitted mandatory
            // evidence source as either satisfied or unobtainable. Reserve BLOCKED
            // for exhausted budgets, unavailable permissions or proven failures.
            MissionDecisionTraceContext.event("GOAL_REVIEW_REQUIRES_EVIDENCE",
                    "Required source is still obtainable despite terminal JEV vote",
                    Map.of("jevStatus", status.name(), "goalCertified", "false"));
            status = Status.NEEDS_EVIDENCE;
        } else if (status == Status.SATISFIED && missingRequired) {
            status = Status.BLOCKED;
        }
        if (status == Status.NEEDS_EVIDENCE) {
            return eligible.isEmpty()
                    ? new Decision(Status.BLOCKED, NONE, "",
                            "No permitted evidence action remains within budget", List.of())
                    : new Decision(Status.NEEDS_EVIDENCE, NONE, "",
                            "JEV finds remaining research necessary; Embabel decides the action", List.of());
        }

        String reason = "JEV reviewed mission evidence: " + status.name();
        // No LLM can override deterministic completeness and evidence gates.
        if (status == Status.SATISFIED && request.requiresRetrieval()
                && !hasExhaustiveEmptyRoots(request.observations())
                && request.observations().stream().noneMatch(MissionGoalReviewComponent::hasCompleteGraph)
                && request.observations().stream().noneMatch(MissionGoalReviewComponent::hasUsableEvidence)) {
            status = Status.BLOCKED;
            reason = "Goal review asserted success without usable retrieved evidence: " + reason;
        }
        if (status == Status.SATISFIED && request.requiresDocumentEvidence()
                && request.observations().stream().noneMatch(o -> DOCUMENT.equals(o.get("action"))
                && hasUsableEvidence(o))) {
            status = Status.BLOCKED;
            reason = "Goal review asserted success without required document evidence: " + reason;
        }
        if (status == Status.SATISFIED && request.requiresInternalContext()
                && !hasUsableGraphRoots(request.observations())
                && !hasExhaustiveEmptyRoots(request.observations())) {
            // A no-roots exhaustive answer is handled by MissionAgent's
            // deterministic empty-root path before terminal review.
            status = Status.BLOCKED;
            reason = "Goal review asserted success without resolved internal context: " + reason;
        }
        if (status == Status.SATISFIED && request.requiresCompanyGraph()
                && !hasExhaustiveEmptyRoots(request.observations())
                && request.observations().stream().noneMatch(MissionGoalReviewComponent::hasCompleteGraph)) {
            status = Status.BLOCKED;
            reason = "Goal review asserted success without complete graph evidence: " + reason;
        }
        if (status == Status.SATISFIED && request.observations().stream().anyMatch(o -> {
            Object attrs = o.get("attributes");
            return attrs instanceof Map<?, ?> m && m.get("rowsOmittedFromReview") instanceof Number n
                    && n.longValue() > 0;
        })) {
            status = Status.BLOCKED;
            reason = "Cannot certify exhaustive reasoning from a shortened result set: " + reason;
        }
        return new Decision(status, NONE, "", reason, List.of());
    }

    /**
     * A reportability check, never a completeness certification. It checks that
     * an authoritative, nontruncated evidence ledger exists for every mandatory
     * source. Cross-source semantic consistency, requested file coverage and
     * answer completeness remain unverified when JEV has abstained.
     */
    private static boolean hasGroundedTerminalReportEvidence(Request request) {
        // Only the mixed-source abstention case is addressed here. Graph-only
        // missions continue using their stricter graph-coverage fast path.
        if (!request.requiresCompanyGraph() || !request.requiresDocumentEvidence()
                || request.requiresHumanReview() || request.observations().isEmpty()) return false;
        var observations = request.observations();
        if (request.requiresInternalContext() && !hasExhaustiveRootSet(observations)) return false;
        if (request.requiresCompanyGraph() && !observations.stream()
                .anyMatch(MissionGoalReviewComponent::hasReportableGraphEvidence)) return false;
        if (request.requiresDocumentEvidence() && !observations.stream()
                .anyMatch(MissionGoalReviewComponent::hasReportableDocumentEvidence)) return false;
        if (request.requiresRetrieval() && observations.stream().noneMatch(
                MissionGoalReviewComponent::hasUsableEvidence) && !observations.stream()
                .anyMatch(MissionGoalReviewComponent::hasReportableGraphEvidence)) return false;
        // Do not synthesize from a mere success code or missing mandatory source.
        return request.requiresCompanyGraph() || request.requiresDocumentEvidence()
                || request.requiresInternalContext();
    }

    private static boolean hasReportableGraphEvidence(Map<String, Object> observation) {
        if (!hasCompleteGraph(observation) || !hasUsableEvidence(observation)) return false;
        if (!(observation.get("attributes") instanceof Map<?, ?> attrs)) return false;
        if (!(attrs.get("requestedRoots") instanceof Number requested)
                || !(attrs.get("processedRoots") instanceof Number processed)
                || requested.intValue() < 1 || requested.intValue() != processed.intValue())
            return false;
        if (attrs.get("rowsOmittedFromReview") instanceof Number n && n.longValue() > 0)
            return false;
        if (!(attrs.get("validatedGraphAnswerRows") instanceof List<?> rows) || rows.isEmpty())
            return false;
        Set<String> covered = new java.util.HashSet<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)
                    || !(row.get("rootEntityId") instanceof String rootId)
                    || rootId.isBlank()
                    || !(row.get("verifiedAnswer") instanceof Map<?, ?> verified)
                    || verified.isEmpty()) return false;
            covered.add(rootId);
        }
        return covered.size() == requested.intValue();
    }

    private static boolean hasReportableDocumentEvidence(Map<String, Object> observation) {
        if (!DOCUMENT.equals(observation.get("action")) || !hasUsableEvidence(observation))
            return false;
        if (!(observation.get("attributes") instanceof Map<?, ?> attrs)) return false;
        if (Boolean.TRUE.equals(attrs.get("noRelevantEvidence"))) return false;
        // DocSvc coverageScore measures its retrieval/build response, NOT
        // completeness against the full mixed-source mission.
        return attrs.get("coverageScore") instanceof Number coverage
                && Double.isFinite(coverage.doubleValue()) && coverage.doubleValue() > 0;
    }

    private static boolean requiredEvidenceMissing(Request request) {
        var observations = request.observations();
        boolean rootsMissing = request.requiresInternalContext()
                && !hasUsableGraphRoots(observations)
                && !hasExhaustiveEmptyRoots(observations);
        boolean graphMissing = request.requiresCompanyGraph()
                && !hasCompleteGraphObservation(observations)
                && !hasExhaustiveEmptyRoots(observations);
        boolean docsMissing = request.requiresDocumentEvidence()
                && observations.stream().noneMatch(o -> DOCUMENT.equals(o.get("action"))
                && hasUsableEvidence(o));
        return rootsMissing || graphMissing || docsMissing;
    }

    private static Set<String> unmetMandatoryCapabilities(Request request, Set<String> eligible) {
        var observations = request.observations();
        Set<String> unmet = new java.util.LinkedHashSet<>();
        if (request.requiresInternalContext() && !hasUsableGraphRoots(observations)
                && !hasExhaustiveEmptyRoots(observations) && eligible.contains(SEARCH)) {
            unmet.add(SEARCH);
        }
        if (request.requiresCompanyGraph() && hasUsableGraphRoots(observations)
                && !hasCompleteGraphObservation(observations) && eligible.contains(GRAPH)) {
            unmet.add(GRAPH);
        }
        if (request.requiresDocumentEvidence()
                && observations.stream().noneMatch(o -> DOCUMENT.equals(o.get("action"))
                && hasUsableEvidence(o)) && eligible.contains(DOCUMENT)) {
            unmet.add(DOCUMENT);
        }
        return Set.copyOf(unmet);
    }

    // Retained for package-level compatibility with existing retry-policy tests.
    @Deprecated
    static int failedOnlyAttempts(List<Map<String, Object>> observations,
                                  String capability, String query) {
        return MissionActionPolicy.failedOnlyAttempts(observations, capability, query);
    }

    /**
     * Avoid a generative review call only when the current evidence state leaves
     * exactly one authorized, required action. This never interprets entity
     * names, relationship semantics, or graph answer rows. Those continue to
     * require ontology-bound query generation and JEV coverage verification.
     *
     * The fallback review remains authoritative for ambiguous, incomplete,
     * mixed-source, verification, human-gated and retry scenarios.
     */
    private static Decision tryMandatoryEvidencePrerequisite(Request request, Set<String> eligible) {
        if (!requiredEvidenceMissing(request)) return null;
        Set<String> mandatory = unmetMandatoryCapabilities(request, eligible);
        if (mandatory.size() != 1) return null;
        String action = mandatory.iterator().next();
        if (request.attempts().getOrDefault(action, 0) > 0) return null;
        return new Decision(Status.NEEDS_EVIDENCE, NONE, "",
                "A mandatory evidence source remains unattempted; planner chooses the action", List.of());
    }

    private static boolean hasCompleteGraphObservation(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; --i) {
            Map<String, Object> observation = observations.get(i);
            // Resolving a newer scope requires a fresh graph proof; an old
            // successful query must not certify a corrected root selection.
            if (SEARCH.equals(observation.get("action"))) return false;
            if (GRAPH.equals(observation.get("action"))) return hasCompleteGraph(observation);
        }
        return false;
    }

    /**
     * Capability eligibility is based on observed prerequisites, not a
     * sequence of query-specific Java steps. GOAP/HYBRID still decide among
     * the eligible actions, and may replan after every observation.
     */
    private Set<String> eligible(Request request) {
        return MissionActionPolicy.eligible(request, maxEvidenceActions, maxCallsPerCapability);
    }

    /**
     * Optional zero-generative-call completion check for a full, authoritative
     * graph response. This is NOT a shortcut on GraphResolutionStatus.COMPLETE:
     * the verified query must match the original objective, all requested roots
     * must be covered by nontruncated answer rows, and Jev must independently
     * judge every fact of the objective supported by those rows.
     *
     * Returning null means normal bounded JEV status/action review continues.
     */
    private Decision tryGraphCoverageFastPath(Request request) {
        if (!request.requiresCompanyGraph() || request.requiresDocumentEvidence()
                || !request.requiresInternalContext() || request.requiresVerification()
                || request.requiresHumanReview()) return null;
        if (!hasExhaustiveRootSet(request.observations())) return null;
        if (!hasCompleteGraphObservation(request.observations())) return null;

        Map<String, Object> graph = null;
        for (int i = request.observations().size() - 1; i >= 0; i--) {
            Map<String, Object> obs = request.observations().get(i);
            if (GRAPH.equals(obs.get("action"))) { graph = obs; break; }
        }
        if (graph == null || !hasCompleteGraph(graph)
                || !request.objective().trim().equalsIgnoreCase(
                Objects.toString(graph.get("query"), "").trim())) return null;
        Object raw = graph.get("attributes");
        if (!(raw instanceof Map<?, ?> attrs)) return null;
        if (!(attrs.get("requestedRoots") instanceof Number requested)
                || !(attrs.get("processedRoots") instanceof Number processed)
                || requested.intValue() < 1
                || processed.intValue() != requested.intValue()) return null;
        if (attrs.get("rowsOmittedFromReview") instanceof Number omitted
                && omitted.longValue() > 0) return null;
        if (!(attrs.get("validatedGraphAnswerRows") instanceof List<?> rows)
                || rows.isEmpty()) return null;
        // A root with no matching row may still require investigation; don't
        // infer "each" or "all" coverage from a global COMPLETE status.
        Set<String> coveredRoots = new java.util.HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)
                    || !(row.get("rootEntityId") instanceof String rootId)
                    || rootId.isBlank()
                    || !(row.get("verifiedAnswer") instanceof Map<?, ?> answer)
                    || answer.isEmpty()) return null;
            coveredRoots.add(rootId);
        }
        if (coveredRoots.size() != requested.intValue()) return null;

        Map<String, String> options = Map.of(
                "COVERED", "Every relationship, predicate, requested column and grouping of the original question is backed by the supplied verified graph rows; no requested facts remain missing",
                "NOT_COVERED", "Any required fact, relationship, grouping, filter or output is missing, or coverage is uncertain");
        Map<String, Object> state = Map.of(
                "originalMission", request.objective(),
                "authoritativeSource", "IntSvc tenant graph",
                "graphQuestion", graph.get("query"),
                "validatedGraphAnswerRows", rows,
                "completeAndNonTruncated", true,
                "evaluatedRootCount", requested.intValue());
        var result = semantic.tryBounded(state, Map.of("coverage",
                        new ChoiceQuestion("Using only verified graph rows, determine whether the ORIGINAL question is fully answered. "
                                + "Check every requested output field, predicate and relationship. "
                                + "Do not infer uniqueness beyond the query's evaluated scope. "
                                + "Choose NOT_COVERED if any fact is missing or uncertain.", options)),
                graphCoverageConfidence, request.operationId() + ":graph-coverage");
        if (result.isPresent()) {
            // A confident NOT_COVERED answer is a real JEV decision. Never
            // override it with successful execution or a synthesis shortcut.
            if (result.get().answers().get("coverage") instanceof ChoiceAnswer answer
                    && "COVERED".equals(answer.choice())) {
                return new Decision(Status.SATISFIED, NONE, "",
                        "JEV certified complete, non-truncated IntSvc graph evidence", List.of());
            }
            return null;
        }

        // JEV abstained; that is neither BLOCKED nor proof of SATISFIED.
        // The strict gates above establish only that an original-objective
        // graph query completed, covered every verified root and supplied
        // non-truncated answer rows. Let the authorized final synthesis LLM
        // render those facts (including uncertainty), not make another
        // decision with a general-purpose LLM.
        MissionDecisionTraceContext.event("GRAPH_EVIDENCE_READY_FOR_SYNTHESIS",
                "JEV abstained; verified graph rows eligible for grounded final synthesis",
                Map.of("rootCount", Integer.toString(requested.intValue()),
                        "rowCount", Integer.toString(rows.size()),
                        "goalCertified", "false"));
        return new Decision(Status.READY_FOR_SYNTHESIS, NONE, "",
                "JEV abstained on semantic coverage; complete graph evidence is available "
                        + "for grounded answer generation without a satisfaction claim", List.of());
    }

    private static boolean hasExhaustiveRootSet(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            Map<String, Object> obs = observations.get(i);
            if (!SEARCH.equals(obs.get("action"))) continue;
            if (!(obs.get("attributes") instanceof Map<?, ?> attrs)) return false;
            return Boolean.TRUE.equals(attrs.get("rootResolutionReady"))
                    && Boolean.TRUE.equals(attrs.get("rootScopeExhaustive"))
                    && attrs.get("selectedRootCount") instanceof Number n
                    && n.intValue() > 0;
        }
        return false;
    }

    private static boolean hasUsableGraphRoots(List<Map<String, Object>> observations) {
        return hasExhaustiveRootSet(observations);
    }

    private static boolean hasExhaustiveEmptyRoots(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; --i) {
            Map<String, Object> observation = observations.get(i);
            if (!SEARCH.equals(observation.get("action"))) continue;
            if (!(observation.get("attributes") instanceof Map<?, ?> attrs)) return false;
            return Boolean.TRUE.equals(attrs.get("rootResolutionReady"))
                    && Boolean.TRUE.equals(attrs.get("rootScopeExhaustive"))
                    && attrs.get("selectedRootCount") instanceof Number n && n.intValue() == 0;
        }
        return false;
    }

    private static boolean hasUsableEvidence(Map<String, Object> observation) {
        Object count = observation.get("evidenceCount");
        if (count instanceof Number n && n.intValue() > 0) return true;
        Object attrs = observation.get("attributes");
        return attrs instanceof Map<?, ?> m
                && m.get("matchingRowCount") instanceof Number n && n.intValue() > 0;
    }

    private static boolean hasCompleteGraph(Map<String, Object> observation) {
        if (!GRAPH.equals(observation.get("action"))) return false;
        Object attrs = observation.get("attributes");
        return attrs instanceof Map<?, ?> m && "COMPLETE".equals(m.get("graphResolutionStatus"));
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing or empty goal review " + field);
        }
        return s.trim();
    }
    public enum Status { SATISFIED, READY_FOR_SYNTHESIS, NEEDS_EVIDENCE, AMBIGUOUS, BLOCKED }

    /** Legacy scored-proposal representation for existing serialized traces/callers; no longer emitted. */
    @Deprecated
    public record Proposal(String action, String query, double utility) {
        public Proposal {
            action = text(action, "action");
            query = text(query, "query");
            if (!Double.isFinite(utility) || utility < 0 || utility > 1)
                throw new IllegalArgumentException("Invalid proposal utility");
        }

        public double netUtility() {
            return utility - EVIDENCE_ACTION_COST;
        }
    }

    // Legacy decision shape is retained for compatibility, but status reviews now
    // return NONE / empty query / empty alternatives on NEEDS_EVIDENCE.
    public record Decision(Status status, String nextAction, String nextQuery,
                           String reason, List<String> missingFacts, List<Proposal> alternatives) {
        public Decision {
            Objects.requireNonNull(status, "status");
            nextAction = Objects.requireNonNull(nextAction, "nextAction");
            nextQuery = nextQuery == null ? "" : nextQuery;
            reason = Objects.requireNonNull(reason, "reason");
            missingFacts = List.copyOf(Objects.requireNonNull(missingFacts));
            alternatives = List.copyOf(Objects.requireNonNull(alternatives));
            if (alternatives.stream().map(Proposal::action).distinct().count() != alternatives.size())
                throw new IllegalArgumentException("Duplicate scored capability proposal");
            if (status != Status.NEEDS_EVIDENCE && !alternatives.isEmpty())
                throw new IllegalArgumentException("Terminal goal review cannot propose actions");
        }
        public Decision(Status status, String nextAction, String nextQuery,
                        String reason, List<String> missingFacts) {
            this(status, nextAction, nextQuery, reason, missingFacts, List.of());
        }
        @Deprecated
        public java.util.Optional<Proposal> proposalFor(String action) {
            if (status != Status.NEEDS_EVIDENCE) return java.util.Optional.empty();
            // For scored reviews, these are the complete candidate proposals.
            java.util.Optional<Proposal> scored = alternatives.stream()
                    .filter(p -> p.action().equals(action)).findFirst();
            if (scored.isPresent()) return scored;
            // Legacy fallback for older persisted reviews. The current
            // status-only reviewer does not populate nextAction.
            if (alternatives.isEmpty() && nextAction.equals(action))
                return java.util.Optional.of(new Proposal(action, nextQuery, 1.0d));
            return java.util.Optional.empty();
        }
    }

    public record Request(String missionId, String operationId, String objective,
                          String latestCapability, List<Map<String, Object>> observations,
                          Map<String, Integer> attempts, Set<String> allowedTools,
                          boolean requiresRetrieval, boolean requiresCompanyGraph,
                          boolean requiresDocumentEvidence, boolean requiresInternalContext,
                          boolean requiresVerification, boolean requiresHumanReview) {
        /** Compatibility with existing tests and callers lacking governance fields. */
        public Request(String missionId, String operationId, String objective,
                       String latestCapability, List<Map<String, Object>> observations,
                       Map<String, Integer> attempts, Set<String> allowedTools,
                       boolean requiresRetrieval, boolean requiresCompanyGraph,
                       boolean requiresDocumentEvidence, boolean requiresInternalContext) {
            this(missionId, operationId, objective, latestCapability, observations,
                    attempts, allowedTools, requiresRetrieval, requiresCompanyGraph,
                    requiresDocumentEvidence, requiresInternalContext, false, false);
        }
        public Request {
            missionId = text(missionId, "missionId");
            operationId = text(operationId, "operationId");
            objective = text(objective, "objective");
            latestCapability = text(latestCapability, "latestCapability");
            observations = List.copyOf(Objects.requireNonNull(observations));
            attempts = Map.copyOf(Objects.requireNonNull(attempts));
            allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools));
            if (attempts.values().stream().anyMatch(n -> n == null || n < 0)) {
                throw new IllegalArgumentException("Invalid capability attempt count");
            }
        }
    }
}
