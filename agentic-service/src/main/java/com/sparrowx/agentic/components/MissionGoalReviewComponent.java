package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JEV evaluates mission status and the marginal utility of eligible evidence
 * capabilities; Embabel chooses the next executable action using those values.
 * Java enforces authorization, graph prerequisites and evidence budgets.
 * The generative LLM only creates distinct follow-up subqueries.
 */
@Component
public final class MissionGoalReviewComponent {
    private static final Logger LOG = LoggerFactory.getLogger(MissionGoalReviewComponent.class);
    public static final String DOCUMENT = MissionCapabilities.DOCUMENT_EVIDENCE;
    public static final String SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    public static final String GRAPH = MissionCapabilities.COMPANY_GRAPH;
    public static final String NONE = MissionCapabilities.NONE;
    private static final Set<String> CAPABILITIES = Set.of(DOCUMENT, SEARCH, GRAPH);
    public static final double EVIDENCE_ACTION_COST = 0.05d;
    private static final double MIN_EXECUTABLE_UTILITY = 0.06d;
    private static final Comparator<Proposal> PREVIEW_ORDER =
            Comparator.comparingDouble(Proposal::netUtility).reversed().thenComparing(Proposal::action);
    // A mandatory source may not be discarded merely because JEV abstained
    // or under-ranked it; source necessity is frozen by MissionIntent.
    private static final double REQUIRED_SOURCE_UTILITY_FLOOR = 0.10d;

    private final SemanticDecisionService semantic;
    private final int maxEvidenceActions;
    private final int maxCallsPerCapability;
    @Value("${sparrowx.mission.reasoning.status-confidence:0.80}")
    private double statusConfidence = 0.80d;
    @Value("${sparrowx.mission.reasoning.action-confidence:0.80}")
    private double actionConfidence = 0.80d;
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
        for (double value : new double[]{statusConfidence, actionConfidence, graphCoverageConfidence}) {
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
        // A single mandatory graph prerequisite is not a semantic choice.
        // GOAP still executes the authorized action, and JEV independently
        // evaluates verified evidence after the graph query completes.
        Decision mandatory = tryMandatoryEvidencePrerequisite(request, eligible);
        if (mandatory != null) return mandatory;
        // If an exhaustive scan supplied roots but the graph compiler reports
        // an ambiguity, another graph call with unchanged roots is not a repair.
        // Allow a distinct corrective root-discovery query first.
        Decision corrective = tryCorrectiveRootDiscovery(request, eligible);
        if (corrective != null) return corrective;

        // JEV is authoritative for bounded status and capability decisions.
        // No structured LLM call is made merely to select a next action.
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
            return proposeEvidence(request, input, eligible);
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

    private Decision proposeEvidence(Request request, Map<String, Object> reviewState, Set<String> eligible) {
        if (eligible.isEmpty()) {
            return new Decision(Status.BLOCKED, NONE, "",
                    "No authorized evidence action remains", List.of());
        }

        Set<String> required = unmetMandatoryCapabilities(request, eligible);
        List<String> choices = (required.isEmpty() ? eligible : required).stream().sorted().toList();
        Map<String, Double> values = scoreEligibleCapabilities(request, reviewState, choices, required);
        List<Proposal> candidates = new ArrayList<>();
        for (String action : choices) {
            double value = values.getOrDefault(action, 0.0d);
            if (value < MIN_EXECUTABLE_UTILITY || value <= EVIDENCE_ACTION_COST) continue;
            String query = candidateQuery(request, reviewState, action);
            if (query.isBlank()) continue;

            candidates.add(new Proposal(action, query, value));
            MissionDecisionTraceContext.event("MISSION_ACTION_UTILITY", "Eligible action scored",
                    Map.of("capability", action,
                            "utility", Double.toString(value),
                            "sourceRequired", Boolean.toString(required.contains(action)),
                            "actionCost", Double.toString(EVIDENCE_ACTION_COST)));
        }

        // The UI preview needs one candidate. Embabel receives every proposal.
        var preview = candidates.stream().min(PREVIEW_ORDER);
        if (preview.isEmpty()) {
            return new Decision(Status.BLOCKED, NONE, "",
                    "No authorized evidence action met the utility and query requirements", List.of());
        }
        Proposal best = preview.get();
        return new Decision(Status.NEEDS_EVIDENCE, best.action(), best.query(),
                "Eligible evidence actions scored for Embabel selection", List.of(), candidates);
    }

    private String candidateQuery(Request request, Map<String, Object> reviewState, String action) {
        String objective = request.objective();
        boolean previouslyAttempted = request.observations().stream().anyMatch(o ->
                action.equals(o.get("action")) && queryEquals(objective, Objects.toString(o.get("query"), "")));
        if (!previouslyAttempted) return objective;

        // A failed invocation may retry the same query once. An executed query
        // needs a distinct evidence focus before the capability can run again.
        int failures = failedOnlyAttempts(request.observations(), action, objective);
        if (failures >= 2) {
            LOG.warn("mission.goal-review.failed-capability-exhausted missionId={} capability={} attempts={}",
                    request.missionId(), action, failures);
            return "";
        }
        if (failures == 1) {
            LOG.info("mission.goal-review.retry-failed-capability missionId={} capability={} attempt=2",
                    request.missionId(), action);
            return objective;
        }

        String query;
        try {
            query = generateFollowUpQuery(request, reviewState, action);
        } catch (RuntimeException exception) {
            LOG.warn("mission.goal-review.subquery-unavailable missionId={} capability={} error={}",
                    request.missionId(), action, exception.getClass().getSimpleName());
            return "";
        }
        boolean repeated = request.observations().stream().anyMatch(o ->
                action.equals(o.get("action")) && queryEquals(query, Objects.toString(o.get("query"), "")));
        return repeated ? "" : query;
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

    /**
     * Score marginal relevance independently for each eligible capability.
     */
    private Map<String, Double> scoreEligibleCapabilities(Request request, Map<String, Object> reviewState,
                                                         List<String> choices, Set<String> required) {
        if (choices.size() == 1) {
            // No competition exists: keep the original deterministic unique
            // action policy, including the mandatory-prerequisite path.
            return Map.of(choices.getFirst(), 1.0d);
        }
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (String action : choices) {
            String capabilityDescription = switch (action) {
                case DOCUMENT -> "retrieving additional document passages and verified source references";
                case SEARCH -> "identifying internal enterprise entities and graph roots";
                case GRAPH -> "querying verified relationships for previously resolved graph roots";
                default -> throw new IllegalArgumentException("Unknown evidence capability");
            };
            questions.put(action, new NoulQuestion(
                    "Given the ORIGINAL mission, all observations, and outstanding facts, how likely is "
                            + capabilityDescription + " to provide NEW necessary evidence now? "
                            + "Rate marginal value independently of the other available capabilities. "
                            + "A successful past call alone does not imply the missing facts are resolved.",
                    "This permitted capability can advance an uncovered goal requirement",
                    "This capability would be irrelevant or redundant at this state"));
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        var answers = semantic.tryIndependentBounded(reviewState, questions,
                actionConfidence, request.operationId() + ":action-utilities");
        for (String action : choices) {
            if (answers.get(action) instanceof NoulAnswer answer) {
                scores.put(action, answer.probability());
            }
        }
        // Keep required source work available after a low score or abstention.
        for (String action : required) {
            scores.merge(action, REQUIRED_SOURCE_UTILITY_FLOOR, Math::max);
        }
        return Map.copyOf(scores);
    }

    private static boolean queryEquals(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * Counts repeated failed invocations of the same capability/query ONLY if
     * none reached the backend successfully. Returns zero if any matching
     * invocation completed (even with an empty result), which permits a
     * genuinely new evidence subquery instead of an identical retry.
     */
    static int failedOnlyAttempts(List<Map<String, Object>> observations,
                                  String capability, String query) {
        int failures = 0;
        for (Map<String, Object> observation : observations) {
            if (!capability.equals(observation.get("action"))
                    || !queryEquals(query, Objects.toString(observation.get("query"), "")))
                continue;
            if (!(observation.get("attributes") instanceof Map<?, ?> attributes)
                    || !Boolean.TRUE.equals(attributes.get("executionFailed")))
                return 0;
            failures++;
        }
        return failures;
    }

    /** Generate a query only for an eligible candidate needing a new evidence focus. */
    private String generateFollowUpQuery(Request request, Map<String, Object> reviewState,
                                         String authorizedCapability) {
        Map<String, Object> input = new LinkedHashMap<>(reviewState);
        input.put("authorizedCapability", authorizedCapability);
        LOG.info("mission.goal-review.subquery-generation.start missionId={} capability={}",
                request.missionId(), authorizedCapability);
        var generated = semantic.escalate(new LlmDecisionEngine.Request(
                request.missionId(), request.operationId() + ":subquery-generation:" + authorizedCapability,
                request.operationId() + ":subquery-generation:" + authorizedCapability,
                "Generate one NEW evidence subquery for the authorized candidate capability. "
                        + "Preserve the original mission and all constraints. The query must advance "
                        + "an unresolved fact, must not repeat a previous query, and must never "
                        + "change the authorized capability. Do not decide goal satisfaction.",
                Map.copyOf(input), Set.of("nextQuery"),
                Map.of("type", "object", "properties", Map.of("nextQuery", Map.of("type", "string")),
                        "required", List.of("nextQuery"), "additionalProperties", false),
                650, 0.0d, Map.of("component", "goal-review-query-generation")));
        Object value = generated.output() == null ? null : generated.output().get("nextQuery");
        return value instanceof String text ? text.trim() : "";
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
        return new Decision(Status.NEEDS_EVIDENCE, action, request.objective(),
                "Execute the only remaining mandatory evidence prerequisite", List.of());
    }

    private Decision tryCorrectiveRootDiscovery(Request request, Set<String> eligible) {
        if (!request.requiresCompanyGraph() || !eligible.contains(SEARCH)
                || request.attempts().getOrDefault(GRAPH, 0) < 1
                || request.attempts().getOrDefault(SEARCH, 0) != 1) return null;
        Map<String, Object> latestSearch = null;
        Map<String, Object> latestGraph = null;
        for (Map<String, Object> observation : request.observations()) {
            if (SEARCH.equals(observation.get("action"))) latestSearch = observation;
            if (GRAPH.equals(observation.get("action"))) latestGraph = observation;
        }
        if (latestSearch == null || latestGraph == null) return null;
        if (!(latestSearch.get("attributes") instanceof Map<?, ?> sa)
                || !(latestGraph.get("attributes") instanceof Map<?, ?> ga)) return null;
        String mode = Objects.toString(sa.get("rootSelectionMode"), "");
        String status = Objects.toString(ga.get("graphResolutionStatus"), "");
        if (!("TYPE_SCAN".equals(mode) || "MULTI_TYPE_SCAN".equals(mode))
                || !"AMBIGUOUS".equals(status)) return null;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("originalMission", request.objective());
        context.put("observations", request.observations());
        context.put("correctiveObjective", "Resolve literal named constraints before another graph query");
        String generated = generateFollowUpQuery(request, context, SEARCH);
        // Preserve the complete frozen objective verbatim in a corrective
        // request: generated subquestions must not delete named constraints.
        final String subquery = generated.isBlank() ? "" :
                (generated.contains(request.objective()) ? generated
                        : request.objective() + "\nCorrective evidence focus: " + generated);
        if (subquery.isBlank() || request.observations().stream().anyMatch(o ->
                SEARCH.equals(o.get("action"))
                        && queryEquals(subquery, Objects.toString(o.get("query"), ""))))
            return new Decision(Status.BLOCKED, NONE, "",
                    "No distinct corrective root query was generated", List.of());
        MissionDecisionTraceContext.event("ROOT_SCOPE_CORRECTION",
                "Retry internal entity selection after ambiguous graph scope",
                Map.of("priorRootMode", mode, "graphResolutionStatus", status));
        return new Decision(Status.NEEDS_EVIDENCE, SEARCH, subquery,
                "Re-resolve named graph constraints before repeating graph construction", List.of());
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
        int total = request.attempts().values().stream().mapToInt(Integer::intValue).sum();
        if (total >= maxEvidenceActions) return Set.of();
        // Empty internal roots preclude dependent graph traversal, not an
        // independent mandatory document lookup or report of what was absent.

        boolean graphRequired = request.requiresCompanyGraph();
        boolean rootsReady = hasUsableGraphRoots(request.observations());
        boolean graphAttempted = request.attempts().getOrDefault(GRAPH, 0) > 0;
        boolean completeGraph = hasCompleteGraphObservation(request.observations());
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String capability : CAPABILITIES) {
            if (!MissionCapabilities.allows(request.allowedTools(), capability)) continue;
            if (request.attempts().getOrDefault(capability, 0) >= maxCallsPerCapability) continue;
            if (GRAPH.equals(capability) && (!rootsReady || hasExhaustiveEmptyRoots(request.observations()))) continue;
            if (SEARCH.equals(capability) && !request.requiresInternalContext()) continue;
            if (GRAPH.equals(capability) && !request.requiresCompanyGraph()) continue;
            if (DOCUMENT.equals(capability) && !request.requiresDocumentEvidence()) continue;

            // Reuse already resolved roots for the first graph execution.
            // After observing graph output, the agent is free to re-resolve
            // if the result reveals that the original root scope was wrong.
            if (SEARCH.equals(capability) && graphRequired && rootsReady
                    && (!graphAttempted || completeGraph))
                continue;

            // Source necessity is frozen in MissionIntent. Tool authorization
            // alone never makes an otherwise irrelevant capability eligible.
            // Don't repeat initial root discovery on an unresolved graph mission
            // when authoritative roots are already known.
            allowed.add(capability);
        }
        return Set.copyOf(allowed);
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

    // `alternatives` contains all scored candidates in capability order.
    // The legacy field name and nextAction/nextQuery preview remain part of the decision contract.
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
        public java.util.Optional<Proposal> proposalFor(String action) {
            if (status != Status.NEEDS_EVIDENCE) return java.util.Optional.empty();
            // For scored reviews, these are the complete candidate proposals.
            java.util.Optional<Proposal> scored = alternatives.stream()
                    .filter(p -> p.action().equals(action)).findFirst();
            if (scored.isPresent()) return scored;
            // Compatibility with prerequisite/corrective reviews, which
            // deliberately bypass semantic scoring for a single action.
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
