package com.sparrowx.agentic.agents;

import static com.sparrowx.agentic.components.MissionGoalReviewComponent.EVIDENCE_ACTION_COST;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.sparrowx.agentic.components.MissionActionPolicy;
import com.sparrowx.agentic.components.MissionActionPolicy.ActionPlan;
import com.sparrowx.agentic.components.MissionGoalReviewComponent;
import com.sparrowx.agentic.components.MissionGoalReviewComponent.Decision;
import com.sparrowx.agentic.components.MissionGoalReviewComponent.Status;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.trace.MissionTracePublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GOAP/HYBRID share the same evidence-driven Embabel state machine.
 * JEV evaluates bounded goal status, independent of tool selection.
 * Embabel chooses among authorized, prerequisite-satisfied actions using
 * blended JEV marginal relevance and structural utility from MissionActionPolicy.
 * JEV cannot prune the authorized candidate set. An LLM generates
 * distinct follow-up queries only AFTER Embabel has selected an action.
 * The next state reevaluates the original mission goal.
 * Only the terminal state exposes @AchievesGoal. No query-specific Java workflow.
 */
// UTILITY mode registers the Nirvana goal required by Hybrid's utility-style
// planning. Invocations select GOAP or HYBRID via ProcessOptions. Nirvana may
// produce "unobtanium" diagnostics from the A* goal probe under GOAP; do not
// remove this goal without regression-testing the HYBRID execution path.
@Agent(description = "SparrowX goal-directed enterprise mission", planner = PlannerType.UTILITY)
public final class MissionAgent {

    private static final Logger LOG = LoggerFactory.getLogger(MissionAgent.class);
    // A review projection budget, not a limit on persisted evidence or synthesis input.
    private static final int MAX_GRAPH_ROWS_PER_REVIEW = 100;

    /**
     * Frozen mission requirements and authorization are independent of the
     * goal reviewer's semantic status. Only frozen intent and pure action
     * policy can make a capability executable.
     */
    private static boolean requiredAndAuthorized(MissionRunInput input, String capability) {
        if (input == null || !MissionCapabilities.allows(input.intent(), capability)) return false;
        if (DOCUMENT.equals(capability)) return input.intent().requiresDocumentEvidence();
        if (SEARCH.equals(capability)) return input.intent().requiresInternalContext();
        if (GRAPH.equals(capability)) return input.intent().requiresCompanyGraph();
        return false;
    }

    public static final String DOCUMENT = MissionCapabilities.DOCUMENT_EVIDENCE;
    public static final String SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    public static final String GRAPH = MissionCapabilities.COMPANY_GRAPH;

    /*
     * Conditions MUST be declared on the @Agent instance, not inside @State.
     * Embabel's state action reader registers @Action methods from @State
     * classes, but does not register their @Condition / @Cost methods.
     * A top-level condition receives the active state from the blackboard.
     */
    @Condition(name = "missionDocumentRecommended")
    public boolean missionDocumentRecommended(ExecuteState state) {
        return state.documentRecommended();
    }

    @Condition(name = "missionSearchRecommended")
    public boolean missionSearchRecommended(ExecuteState state) {
        return state.searchRecommended();
    }

    @Condition(name = "missionGraphRecommended")
    public boolean missionGraphRecommended(ExecuteState state) {
        return state.graphRecommended();
    }

    /*
     * Embabel 1.5.2 ignores value/valueMethod for actions declared inside
     * @State. Register the evidence actions on the @Agent so their dynamic
     * utility is available to HYBRID, while the state objects continue to
     * hold the current action plan, evidence and execution implementation.
     *
     * @Cost is evaluated during planning, not during action execution. It
     * must be side-effect free, null-safe and must not invoke Jev/the LLM.
     */
    @Cost(name = "documentEvidenceValue")
    public double documentEvidenceValue(ExecuteState state) {
        return proposalValue(state, DOCUMENT);
    }

    @Cost(name = "internalSearchValue")
    public double internalSearchValue(ExecuteState state) {
        return proposalValue(state, SEARCH);
    }

    @Cost(name = "companyGraphValue")
    public double companyGraphValue(ExecuteState state) {
        if (state == null || state.progress().latestRoots() == null) return 0.0;
        return proposalValue(state, GRAPH);
    }

    private static double proposalValue(ExecuteState state, String capability) {
        if (state == null || !state.eligibleFor(capability)) return 0.0;
        return state.plan().utility(capability);
    }

    @Action(pre = "missionDocumentRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "documentEvidenceValue", cost = EVIDENCE_ACTION_COST,
            description = "Retrieve authorized document evidence for the current subgoal")
    public ReasonState retrieveDocuments(ExecuteState state,
                                         @Provided MissionEvidenceActions capabilities,
                                         @Provided MissionTracePublisher tracePublisher,
                                         @Provided MissionActionPolicy actionPolicy,
                                         OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .retrieveDocuments(capabilities, tracePublisher, actionPolicy, context);
    }

    @Action(pre = "missionSearchRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "internalSearchValue", cost = EVIDENCE_ACTION_COST,
            description = "Discover graph roots for the current subgoal")
    public ReasonState searchEntities(ExecuteState state,
                                      @Provided MissionEvidenceActions capabilities,
                                      @Provided MissionTracePublisher tracePublisher,
                                      @Provided MissionActionPolicy actionPolicy,
                                      OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .searchEntities(capabilities, tracePublisher, actionPolicy, context);
    }

    @Action(pre = "missionGraphRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "companyGraphValue", cost = EVIDENCE_ACTION_COST,
            description = "Query the tenant graph for the current subgoal")
    public ReasonState queryGraph(ExecuteState state,
                                  @Provided MissionEvidenceActions capabilities,
                                  @Provided MissionTracePublisher tracePublisher,
                                  @Provided MissionActionPolicy actionPolicy,
                                  OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .queryGraph(capabilities, tracePublisher, actionPolicy, context);
    }

    @Action(value = 1.0, description = "Start a mission with an empty evidence ledger")
    public ReasonState start(MissionRunInput input, @Provided MissionTracePublisher tracePublisher) {
        MissionTracePublisher.Span intent = tracePublisher.start(input, null,
                "decision", "Decisions & evidence", "INTENT_SNAPSHOT", "Frozen mission interpretation",
                Map.of("plannerRequested", input.intent().plannerMode().name(),
                        "requiresCompanyGraph", Boolean.toString(input.intent().requiresCompanyGraph()),
                        "requiresInternalContext", Boolean.toString(input.intent().requiresInternalContext()),
                        "requiresDocumentEvidence", Boolean.toString(input.intent().requiresDocumentEvidence()),
                        "requiresVerification", Boolean.toString(input.intent().requiresVerification())));
        intent.finish("SUCCEEDED", "");
        // The terminal Embabel goal is singular. Do not fabricate a task-specific
        // GOAP goal for each capability in the UI.
        tracePublisher.observe(input, null, "decision", "Decisions & evidence",
                "GOAL_REGISTERED", "Mission goal registered",
                Map.of("goal", "FinishState.complete", "goalStatus", "ACTIVE",
                        "plannerRequested", input.intent().plannerMode().name()), () -> null);
        return new ReasonState(input, Progress.empty());
    }

    @State
    public sealed interface Stage permits ReasonState, ExecuteState, FinishState { }

    public record ReasonState(MissionRunInput input, Progress progress) implements Stage {
        public ReasonState {
            Objects.requireNonNull(input);
            Objects.requireNonNull(progress);
        }

        @Action(canRerun = true, clearBlackboard = true,
                description = "Review goal sufficiency and prepare an independent Embabel action pool")
        public Stage reason(@Provided MissionGoalReviewComponent reviewer,
                            @Provided MissionActionPolicy actionPolicy,
                            @Provided MissionTracePublisher tracePublisher) {
            try (var scope = MissionDecisionTraceContext.open(input, tracePublisher)) {
                var loop = MissionDecisionTraceContext.begin("REASON_LOOP",
                        "Review loop " + (progress.observations().size() + 1),
                        Map.of("reviewIteration", Integer.toString(progress.observations().size() + 1),
                                "goal", "FinishState.complete"));
                try (var child = MissionDecisionTraceContext.parent(loop == null ? null : loop.id())) {
                    MissionDecisionTraceContext.snapshot("Mission evidence before review", progress.observations().size(),
                            progress.evidence().size(), progress.evidence().stream().mapToInt(e -> e.evidenceRefs().size()).sum(),
                            progress.attempts().values().stream().mapToInt(Integer::intValue).sum(), 0, 0);
                    // Review sees only tool grants with matching authorized sources.
                    Set<String> grantedCapabilities = java.util.stream.Stream.of(DOCUMENT, SEARCH, GRAPH)
                            .filter(capability -> MissionCapabilities.allows(input.intent(), capability))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    MissionGoalReviewComponent.Request reviewRequest = new MissionGoalReviewComponent.Request(
                            input.missionId(),
                            input.request().context().requestId() + ":goal-review:" + progress.observations().size(),
                            input.request().query(),
                            progress.observations().isEmpty() ? "START" :
                                    Objects.toString(progress.observations().getLast().get("action"), "START"),
                            progress.observations(), progress.attempts(), grantedCapabilities,
                            input.intent().requiresRetrieval(), input.intent().requiresCompanyGraph(),
                            input.intent().requiresDocumentEvidence(), input.intent().requiresInternalContext(),
                            input.intent().requiresVerification(), input.intent().requiresHumanReview());
                    Decision decision = reviewer.review(reviewRequest);
                    ActionPlan plan = decision.status() == Status.NEEDS_EVIDENCE
                            ? actionPolicy.plan(input.intent(), reviewRequest, progress.latestRoots() != null)
                            : ActionPlan.empty();
                    // A stale or impossible reviewer continuation must not get
                    // the process STUCK; finish truthfully when no action exists.
                    if (decision.status() == Status.NEEDS_EVIDENCE && plan.size() == 0) {
                        decision = new Decision(Status.BLOCKED, MissionCapabilities.NONE, "",
                                "No authorized, executable action remains within evidence policy", decision.missingFacts());
                    }
                    MissionDecisionTraceContext.event("MISSION_ACTION_RECOMMENDATION", "Embabel candidate pool prepared",
                            Map.of("goalStatus", decision.status().name(),
                                    "nextAction", MissionCapabilities.NONE,
                                    "candidateCount", Integer.toString(plan.size()),
                                    "alternativeCount", Integer.toString(Math.max(0, plan.size() - 1)),
                                    "selectionAuthority", "EMBABEL",
                                    "scoringAuthority", "MISSION_ACTION_POLICY_OPTIONAL_JEV",
                                    "goal", "FinishState.complete"));
                    // These are outcome events for the actual terminal Embabel goal,
                    // distinct from its recommended next evidence capability.
                    MissionDecisionTraceContext.event("GOAL_EVALUATED", "Mission goal evaluated",
                            Map.of("goal", "FinishState.complete", "goalStatus", decision.status().name(),
                                    "nextAction", decision.nextAction(),
                                    "missingCount", Integer.toString(decision.missingFacts().size()),
                                    "observationCount", Integer.toString(progress.observations().size()),
                                    "evidenceStateCount", Integer.toString(progress.evidence().size()),
                                    "attemptCount", Integer.toString(progress.attempts().values().stream()
                                            .mapToInt(Integer::intValue).sum())));
                    if (loop != null) loop.finish("SUCCEEDED", "");
                    return decision.status() == Status.NEEDS_EVIDENCE
                            ? new ExecuteState(input, progress, decision, plan)
                            : new FinishState(input, progress, decision);
                } catch (RuntimeException | Error e) {
                    if (loop != null) loop.finish("FAILED", e.getClass().getSimpleName());
                    throw e;
                }
            }

        }
    }

    /** Embabel selects among independently computed authorized action utilities. */
    public record ExecuteState(MissionRunInput input, Progress progress, Decision decision, ActionPlan plan)
            implements Stage {
        public ExecuteState {
            Objects.requireNonNull(input); Objects.requireNonNull(progress);
            Objects.requireNonNull(decision); Objects.requireNonNull(plan);
            if (decision.status() != Status.NEEDS_EVIDENCE || plan.size() == 0)
                throw new IllegalArgumentException("Only nonempty evidence plans can execute tools");
        }

        // Neither JEV recommendations nor LLM-generated text can grant capabilities.
        private boolean eligibleFor(String capability) {
            return requiredAndAuthorized(input, capability)
                    && plan.eligible(capability)
                    && (!GRAPH.equals(capability) || progress.latestRoots() != null);
        }

        // These state predicates are published through the agent-level
        // @Condition methods for Embabel's world-state determiner.
        public boolean documentRecommended() {
            return eligibleFor(DOCUMENT);
        }

        public boolean searchRecommended() {
            return eligibleFor(SEARCH);
        }

        public boolean graphRecommended() {
            return eligibleFor(GRAPH);
        }

        public ReasonState retrieveDocuments(@Provided MissionEvidenceActions capabilities,
                                             @Provided MissionTracePublisher tracePublisher,
                                             @Provided MissionActionPolicy actionPolicy,
                                             OperationContext context) {
            ensureAuthorized(DOCUMENT);
            String query = queryFor(actionPolicy, DOCUMENT);
            if (query.isBlank()) return unavailableQuery(DOCUMENT, tracePublisher);
            return run(DOCUMENT, query, tracePublisher,
                    () -> capabilities.buildDocumentEvidence(input, context, query));
        }

        public ReasonState searchEntities(@Provided MissionEvidenceActions capabilities,
                                          @Provided MissionTracePublisher tracePublisher,
                                          @Provided MissionActionPolicy actionPolicy,
                                          OperationContext context) {
            ensureAuthorized(SEARCH);
            String query = queryFor(actionPolicy, SEARCH);
            if (query.isBlank()) return unavailableQuery(SEARCH, tracePublisher);
            return run(SEARCH, query, tracePublisher,
                    () -> capabilities.searchInternalEntities(input, context, query));
        }

        public ReasonState queryGraph(@Provided MissionEvidenceActions capabilities,
                                      @Provided MissionTracePublisher tracePublisher,
                                      @Provided MissionActionPolicy actionPolicy,
                                      OperationContext context) {
            ensureAuthorized(GRAPH);
            String query = queryFor(actionPolicy, GRAPH);
            if (query.isBlank()) return unavailableQuery(GRAPH, tracePublisher);
            return run(GRAPH, query, tracePublisher, () -> capabilities.readCompanyGraph(
                    input, Objects.requireNonNull(progress.latestRoots()), context, query));
        }

        private String queryFor(MissionActionPolicy actionPolicy, String capability) {
            Objects.requireNonNull(actionPolicy, "actionPolicy");
            return actionPolicy.resolveSelectedQuery(input.missionId(),
                    input.request().context().requestId() + ":selected-query:" + capability
                            + ":" + progress.attempts().getOrDefault(capability, 0),
                    input.request().query(), progress.observations(), capability);
        }

        private ReasonState unavailableQuery(String action, MissionTracePublisher tracePublisher) {
            try (var scope = MissionDecisionTraceContext.open(input, tracePublisher)) {
                MissionDecisionTraceContext.event("MISSION_ACTION_QUERY_UNAVAILABLE",
                        "No distinct authorized query for selected capability",
                        Map.of("capability", action, "selectionAuthority", "EMBABEL"));
                // This consumes a bounded planning attempt but does NOT record
                // a backend tool invocation or fabricated negative evidence.
                return new ReasonState(input, progress.withQueryFailure(action, input.request().query()));
            }
        }

        private void ensureAuthorized(String capability) {
            if (!eligibleFor(capability))
                throw new SecurityException("Capability not eligible or permitted by mission: " + capability);
        }

        private ReasonState run(String action, String query, MissionTracePublisher tracePublisher,
                                java.util.function.Supplier<MissionEvidenceActions.EvidenceState> operation) {
            try (var scope = MissionDecisionTraceContext.open(input, tracePublisher)) {
                double value = plan.utility(action);
                MissionDecisionTraceContext.event("MISSION_ACTION_SELECTED", "Embabel selected eligible capability",
                        Map.of("capability", action,
                                "utility", Double.toString(value),
                                "cost", Double.toString(EVIDENCE_ACTION_COST),
                                "netUtility", Double.toString(value - EVIDENCE_ACTION_COST)));

                var actionSpan = MissionDecisionTraceContext.begin("CAPABILITY_ATTEMPT",
                        "Execute " + action, Map.of("capability", action,
                                "attempt", Integer.toString(progress.attempts().getOrDefault(action, 0) + 1)));
                try (var child = MissionDecisionTraceContext.parent(actionSpan == null ? null : actionSpan.id())) {
                    int beforeObservations = progress.observations().size();
                    int beforeEvidence = progress.evidence().size();
                    MissionDecisionTraceContext.snapshot("Mission evidence before capability", beforeObservations,
                            beforeEvidence, progress.evidence().stream().mapToInt(e -> e.evidenceRefs().size()).sum(),
                            progress.attempts().values().stream().mapToInt(Integer::intValue).sum(), 0, 0);
                    MissionEvidenceActions.EvidenceState state = operation.get();
                    Progress updated = progress.withEvidence(action, query, state);
                    MissionDecisionTraceContext.snapshot("Mission evidence after capability",
                            updated.observations().size(), updated.evidence().size(),
                            updated.evidence().stream().mapToInt(e -> e.evidenceRefs().size()).sum(),
                            updated.attempts().values().stream().mapToInt(Integer::intValue).sum(),
                            updated.observations().size() - beforeObservations,
                            updated.evidence().size() - beforeEvidence);
                    MissionDecisionTraceContext.event("MISSION_EVIDENCE_DELTA", "Evidence committed to mission state",
                            Map.of("capability", action, "outcome", "STORED",
                                    "addedObservations", Integer.toString(updated.observations().size() - beforeObservations),
                                    "addedEvidence", Integer.toString(updated.evidence().size() - beforeEvidence)));
                    if (actionSpan != null) actionSpan.finish("SUCCEEDED", "");
                    return new ReasonState(input, updated);
                } catch (SecurityException | CancellationException e) {
                    if (actionSpan != null) actionSpan.finish("FAILED", e.getClass().getSimpleName());
                    throw e;
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        if (actionSpan != null) actionSpan.finish("FAILED", "Interrupted");
                        throw e;
                    }
                    // The agent deliberately records a failed tool call as evidence and
                    // continues planning. Log the *actual* exception first: Embabel
                    // otherwise reports this wrapper action as successful, masking the
                    // underlying failure and its original throw site.
                    // Never log the original user query or retrieved document payload.
                    LOG.error("mission.capability.failed missionId={} capability={} attempt={} "
                                    + "exception={} message={}",
                            input.missionId(), action,
                            progress.attempts().getOrDefault(action, 0) + 1,
                            e.getClass().getName(), e.getMessage(), e);
                    Progress updated = progress.withError(action, query, e.getClass().getSimpleName());
                    MissionDecisionTraceContext.snapshot("Mission evidence after failed capability",
                            updated.observations().size(), updated.evidence().size(),
                            updated.evidence().stream().mapToInt(evidence -> evidence.evidenceRefs().size()).sum(),
                            updated.attempts().values().stream().mapToInt(Integer::intValue).sum(),
                            1, 0);
                    MissionDecisionTraceContext.event("MISSION_EVIDENCE_DELTA", "Capability failure recorded",
                            Map.of("capability", action, "outcome", "FAILED",
                                    "addedObservations", "1", "addedEvidence", "0"));
                    if (actionSpan != null) actionSpan.finish("FAILED", e.getClass().getSimpleName());
                    return new ReasonState(input, updated);
                }
            }
        }
    }

    public record FinishState(MissionRunInput input, Progress progress, Decision decision)
            implements Stage {
        public FinishState {
            Objects.requireNonNull(input); Objects.requireNonNull(progress); Objects.requireNonNull(decision);
            if (decision.status() == Status.NEEDS_EVIDENCE)
                throw new IllegalArgumentException("A nonterminal review cannot finish a mission");
        }

        @AchievesGoal(description = "Produce verified answer or truthful unresolved mission result")
        @Action(description = "Finalize the reviewed mission outcome")
        public MissionResult complete(@Provided MissionEvidenceActions capabilities,
                                      @Provided MissionTracePublisher tracePublisher,
                                      OperationContext context) {
            try (var scope = MissionDecisionTraceContext.open(input, tracePublisher)) {
                var synthesis = MissionDecisionTraceContext.begin("RESPONSE_SYNTHESIS",
                        "Finalize reviewed mission", Map.of("goal", "FinishState.complete",
                                "goalStatus", decision.status().name()));
                try (var child = MissionDecisionTraceContext.parent(synthesis == null ? null : synthesis.id())) {
                    MissionResult result;
                    // A graph query can be complete even when JEV abstains on
                    // semantic completeness. READY_FOR_SYNTHESIS permits only
                    // grounded final answer generation; it is NOT satisfaction.
                    boolean answerReady = decision.status() == Status.SATISFIED
                            || decision.status() == Status.READY_FOR_SYNTHESIS;
                    // A terminal review may be blocked on only part of a complex
                    // mission. Do not discard independently verified evidence.
                    // Grounded synthesis must clearly label it uncertified.
                    boolean partialReportAvailable = decision.status() == Status.BLOCKED
                            && MissionEvidenceGrounding.hasEvidence(input.intent(), progress.evidence());
                    if (answerReady || partialReportAvailable) {
                        result = capabilities.complete(input, context, progress.evidence(),
                                decision.status() == Status.SATISFIED);
                    } else {
                        String message = decision.status() == Status.AMBIGUOUS
                                ? "Clarification is required: " + decision.reason()
                                : "Could not establish the requested answer: " + decision.reason();
                        result = new MissionResult(input.missionId(), "Mission not satisfied", message,
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                Map.of("grounded", false), Map.of("goalReviewStatus", decision.status().name(),
                                "missingFacts", decision.missingFacts(), "goalReviewReason", decision.reason(),
                                "goalCertified", false));
                    }
                    if (synthesis != null) synthesis.finish("SUCCEEDED", "");
                    // Evidence admission may reject a reviewer's satisfaction
                    // claim. Trace the final result rather than the earlier vote.
                    Status finalStatus = Boolean.TRUE.equals(result.debugSummary().get("goalCertified"))
                            ? Status.SATISFIED
                            : decision.status() == Status.SATISFIED
                            ? (result.evidenceRefs().isEmpty() ? Status.BLOCKED : Status.READY_FOR_SYNTHESIS)
                            : decision.status();
                    MissionDecisionTraceContext.event(
                            finalStatus == Status.SATISFIED ? "GOAL_SATISFIED"
                                    : finalStatus == Status.READY_FOR_SYNTHESIS
                                    ? "GOAL_EVIDENCE_READY" : "GOAL_BLOCKED",
                            finalStatus == Status.SATISFIED ? "Mission goal satisfied"
                                    : finalStatus == Status.READY_FOR_SYNTHESIS
                                    ? "Grounded answer generated without goal certification"
                                    : "Mission goal unresolved",
                            Map.of("goal", "FinishState.complete", "goalStatus", finalStatus.name(),
                                    "reviewStatus", decision.status().name()));
                    MissionDecisionTraceContext.event("MISSION_FINISH", "Mission process finished",
                            Map.of("goal", "FinishState.complete", "goalStatus", finalStatus.name(),
                                    "reviewStatus", decision.status().name()));
                    return result;
                } catch (RuntimeException | Error error) {
                    if (synthesis != null) synthesis.finish("FAILED", error.getClass().getSimpleName());
                    throw error;
                }
            }
        }
    }

    /** Carries evidence across state transitions without depending on blackboard object retention. */
    public record Progress(List<Map<String, Object>> observations,
                           List<MissionEvidenceActions.EvidenceState> evidence,
                           Map<String, Integer> attempts) {
        public Progress {
            observations = List.copyOf(Objects.requireNonNull(observations));
            evidence = List.copyOf(Objects.requireNonNull(evidence));
            attempts = Map.copyOf(Objects.requireNonNull(attempts));
        }
        public static Progress empty() { return new Progress(List.of(), List.of(), Map.of()); }

        public MissionEvidenceActions.InternalSearchState latestRoots() {
            for (int i = evidence.size() - 1; i >= 0; --i)
                if (evidence.get(i) instanceof MissionEvidenceActions.InternalSearchState root)
                    return root.performed() && root.selection().ready() ? root : null;
            return null;
        }

        public Progress withEvidence(String action, String query, MissionEvidenceActions.EvidenceState state) {
            var history = new ArrayList<>(evidence);
            history.add(state);
            var attributes = new LinkedHashMap<String, Object>(state.attributes());
            // Retain all evidence in EvidenceState; this is only the bounded
            // JEV review projection. Omission explicitly disables certification.
            if (attributes.get("validatedGraphAnswerRows") instanceof List<?> rows
                    && rows.size() > MAX_GRAPH_ROWS_PER_REVIEW) {
                attributes.put("validatedGraphAnswerRows", List.copyOf(rows.subList(0, MAX_GRAPH_ROWS_PER_REVIEW)));
                attributes.put("rowsOmittedFromReview", rows.size() - MAX_GRAPH_ROWS_PER_REVIEW);
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("id", "observation-" + (observations.size() + 1));
            observation.put("action", action);
            observation.put("query", query);
            observation.put("summary", state.summary());
            observation.put("attributes", attributes);
            observation.put("evidenceCount", state.evidenceRefs().size());
            observation.put("evidenceIds", state.evidenceRefs().stream().map(EvidenceRef::evidenceId).toList());
            return append(action, history, observation);
        }

        public Progress withQueryFailure(String action, String originalObjective) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("id", "observation-" + (observations.size() + 1));
            observation.put("action", action);
            observation.put("query", originalObjective);
            observation.put("summary", "No usable distinct query for selected action");
            observation.put("attributes", Map.of("queryGenerationFailed", true));
            observation.put("evidenceCount", 0);
            observation.put("evidenceIds", List.of());
            return append(action, evidence, observation);
        }

        public Progress withError(String action, String query, String errorType) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("id", "observation-" + (observations.size() + 1));
            observation.put("action", action);
            observation.put("query", query);
            observation.put("summary", "Capability execution failed: " + errorType);
            observation.put("attributes", Map.of("executionFailed", true, "errorType", errorType));
            observation.put("evidenceCount", 0);
            observation.put("evidenceIds", List.of());
            return append(action, evidence, observation);
        }

        private Progress append(String action,
                                List<MissionEvidenceActions.EvidenceState> history,
                                Map<String, Object> observation) {
            List<Map<String, Object>> next = new ArrayList<>(observations);
            next.add(Map.copyOf(observation));
            Map<String, Integer> counts = new LinkedHashMap<>(attempts);
            counts.merge(action, 1, Integer::sum);
            return new Progress(next, history, counts);
        }
    }
}
