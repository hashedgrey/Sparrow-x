package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
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
 * JEV chooses bounded mission status and next evidence capability; Java enforces
 * authorization and evidence budgets. The LLM is only used to generate a fresh
 * subquery after the original query has already been attempted.
 */
@Component
public final class MissionGoalReviewComponent {
    private static final Logger LOG = LoggerFactory.getLogger(MissionGoalReviewComponent.class);
    public static final String DOCUMENT = MissionCapabilities.DOCUMENT_EVIDENCE;
    public static final String SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    public static final String GRAPH = MissionCapabilities.COMPANY_GRAPH;
    public static final String NONE = MissionCapabilities.NONE;
    private static final Set<String> CAPABILITIES = Set.of(DOCUMENT, SEARCH, GRAPH);

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
        String proposed = NONE;
        String reason = "JEV reviewed mission evidence: " + status.name();
        String query = "";
        List<String> missing = List.of();
        List<Proposal> alternatives = List.of();

        if (status == Status.NEEDS_EVIDENCE) {
            if (eligible.isEmpty()) {
                return new Decision(Status.BLOCKED, NONE, "",
                        "No authorized evidence action remains", List.of());
            }
            Set<String> unmet = unmetMandatoryCapabilities(request, eligible);
            Set<String> choices = unmet.isEmpty() ? eligible : unmet;
            if (choices.size() == 1) {
                // Only one possible action remains after checking mandatory
                // prerequisites; no probabilistic decision is necessary.
                proposed = choices.iterator().next();
                reason = "Unique eligible evidence capability selected by prerequisites";
            } else {
                Map<String, String> actionChoices = new LinkedHashMap<>();
                for (String capability : choices.stream().sorted().toList())
                    actionChoices.put(capability, "Execute authorized capability " + capability);
                var actionVote = semantic.tryBounded(input, Map.of(
                        "nextAction", new ChoiceQuestion(
                                "Select the next capability to resolve an uncovered requirement. "
                                + "Do not repeat completed operations without a distinct evidence gap.",
                                Map.copyOf(actionChoices))),
                        actionConfidence, request.operationId() + ":next-action");
                if (actionVote.isPresent()
                        && actionVote.get().answers().get("nextAction") instanceof ChoiceAnswer selected
                        && choices.contains(selected.choice())) {
                    proposed = selected.choice();
                }
            }
        }
        if (status == Status.NEEDS_EVIDENCE) {
            if (!eligible.contains(proposed)) {
                return new Decision(Status.BLOCKED, NONE, "",
                        "JEV requested evidence without selecting an eligible capability", List.of());
            }
            query = request.objective();
            // First attempt preserves the full objective. If the identical query
            // was already attempted, only open-ended subquery generation may use LLM.
            final String selectedAction = proposed;
            boolean previouslyAttempted = request.observations().stream().anyMatch(o ->
                    selectedAction.equals(o.get("action")) &&
                    queryEquals(request.objective(), Objects.toString(o.get("query"), "")));
            if (previouslyAttempted) {
                query = generateFollowUpQuery(request, input, proposed);
                final String generated = query;
                if (generated.isBlank() || request.observations().stream().anyMatch(o ->
                        selectedAction.equals(o.get("action")) &&
                        queryEquals(generated, Objects.toString(o.get("query"), "")))) {
                    return new Decision(Status.BLOCKED, NONE, "",
                            "No distinct evidence subquery could be generated", List.of());
                }
                reason = "Authorized capability selected; LLM generated a distinct evidence subquery";
            }
        } else {
            proposed = NONE;
        }

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
        if (status != Status.NEEDS_EVIDENCE) {
            proposed = NONE;
            query = "";
            alternatives = List.of();
        }
        return new Decision(status, proposed, query, reason, missing, alternatives);
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
        java.util.Set<String> covered = new java.util.HashSet<>();
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

    private static boolean queryEquals(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /** The LLM is permitted to GENERATE a new query, never to select the action. */
    private String generateFollowUpQuery(Request request, Map<String, Object> reviewState,
                                         String authorizedCapability) {
        Map<String, Object> input = new LinkedHashMap<>(reviewState);
        input.put("authorizedCapability", authorizedCapability);
        var generated = semantic.escalate(new LlmDecisionEngine.Request(
                request.missionId(), request.operationId() + ":subquery-generation",
                request.operationId() + ":subquery-generation",
                "Generate one NEW evidence subquery for the already selected capability. "
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

    private static boolean hasCompleteGraphObservation(List<Map<String, Object>> observations) {
        return observations.stream().anyMatch(MissionGoalReviewComponent::hasCompleteGraph);
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
        boolean completeGraph = request.observations().stream()
                .anyMatch(MissionGoalReviewComponent::hasCompleteGraph);
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String capability : CAPABILITIES) {
            if (!request.allowedTools().isEmpty() && !request.allowedTools().contains(capability)) continue;
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
        java.util.Set<String> coveredRoots = new java.util.HashSet<>();
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
        return observations.stream().filter(o -> SEARCH.equals(o.get("action"))).anyMatch(o -> {
            Object attrs = o.get("attributes");
            if (!(attrs instanceof Map<?, ?> m)) return false;
            return Boolean.TRUE.equals(m.get("rootResolutionReady"))
                    && m.get("selectedRootCount") instanceof Number n && n.intValue() > 0;
        });
    }

    private static boolean hasExhaustiveEmptyRoots(List<Map<String, Object>> observations) {
        return observations.stream().filter(o -> SEARCH.equals(o.get("action"))).anyMatch(o -> {
            Object attrs = o.get("attributes");
            if (!(attrs instanceof Map<?, ?> m)) return false;
            return Boolean.TRUE.equals(m.get("rootResolutionReady"))
                    && Boolean.TRUE.equals(m.get("rootScopeExhaustive"))
                    && m.get("selectedRootCount") instanceof Number n && n.intValue() == 0;
        });
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
            action = text(action, "action"); query = text(query, "query");
            if (!Double.isFinite(utility) || utility < 0 || utility > 1)
                throw new IllegalArgumentException("Invalid proposal utility");
        }
    }

    public record Decision(Status status, String nextAction, String nextQuery,
                           String reason, List<String> missingFacts, List<Proposal> alternatives) {
        public Decision {
            Objects.requireNonNull(status, "status");
            nextAction = Objects.requireNonNull(nextAction, "nextAction");
            nextQuery = nextQuery == null ? "" : nextQuery;
            reason = Objects.requireNonNull(reason, "reason");
            missingFacts = List.copyOf(Objects.requireNonNull(missingFacts));
            alternatives = List.copyOf(Objects.requireNonNull(alternatives));
        }
        public Decision(Status status, String nextAction, String nextQuery,
                        String reason, List<String> missingFacts) {
            this(status, nextAction, nextQuery, reason, missingFacts, List.of());
        }
        public java.util.Optional<Proposal> proposalFor(String action) {
            if (status != Status.NEEDS_EVIDENCE) return java.util.Optional.empty();
            if (nextAction.equals(action)) return java.util.Optional.of(new Proposal(action, nextQuery, 1.0d));
            return alternatives.stream().filter(p -> p.action().equals(action)).findFirst();
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
