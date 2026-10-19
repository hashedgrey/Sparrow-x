package com.sparrowx.agentic.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GOAP/HYBRID share the same evidence-driven Embabel state machine.
 * JEV selects a capability; a generative model produces only genuinely needed subqueries;
 * a state-scoped action executes
 * one authorized capability; the next state reevaluates the original goal.
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
     * hold the current proposal, evidence and execution implementation.
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
        if (state == null) return 0.0;
        return state.decision().proposalFor(capability)
                .map(MissionGoalReviewComponent.Proposal::utility)
                .orElse(0.0);
    }

    @Action(pre = "missionDocumentRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "documentEvidenceValue", cost = 0.05,
            description = "Retrieve authorized document evidence for the current subgoal")
    public ReasonState retrieveDocuments(ExecuteState state,
                                         @Provided MissionEvidenceActions capabilities,
                                         @Provided MissionTracePublisher tracePublisher,
                                         OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .retrieveDocuments(capabilities, tracePublisher, context);
    }

    @Action(pre = "missionSearchRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "internalSearchValue", cost = 0.05,
            description = "Discover graph roots for the current subgoal")
    public ReasonState searchEntities(ExecuteState state,
                                      @Provided MissionEvidenceActions capabilities,
                                      @Provided MissionTracePublisher tracePublisher,
                                      OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .searchEntities(capabilities, tracePublisher, context);
    }

    @Action(pre = "missionGraphRecommended", canRerun = true, clearBlackboard = true,
            readOnly = true, valueMethod = "companyGraphValue", cost = 0.05,
            description = "Query the tenant graph for the current subgoal")
    public ReasonState queryGraph(ExecuteState state,
                                  @Provided MissionEvidenceActions capabilities,
                                  @Provided MissionTracePublisher tracePublisher,
                                  OperationContext context) {
        return Objects.requireNonNull(state, "ExecuteState required")
                .queryGraph(capabilities, tracePublisher, context);
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
                description = "Reason over observations and recommend the next authorized action")
        public Stage reason(@Provided MissionGoalReviewComponent reviewer,
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
            Decision decision = reviewer.review(new MissionGoalReviewComponent.Request(
                    input.missionId(),
                    input.request().context().requestId() + ":goal-review:" + progress.observations().size(),
                    input.request().query(),
                    progress.observations().isEmpty() ? "START" :
                            Objects.toString(progress.observations().getLast().get("action"), "START"),
                    progress.observations(), progress.attempts(), input.intent().allowedTools(),
                    input.intent().requiresRetrieval(), input.intent().requiresCompanyGraph(),
                    input.intent().requiresDocumentEvidence(), input.intent().requiresInternalContext(),
                    input.intent().requiresVerification(), input.intent().requiresHumanReview()));
            MissionDecisionTraceContext.event("MISSION_ACTION_RECOMMENDATION", "Mission reviewer recommended action",
                    Map.of("goalStatus", decision.status().name(), "nextAction", decision.nextAction(),
                            "alternativeCount", Integer.toString(decision.alternatives().size()),
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
                    ? new ExecuteState(input, progress, decision)
                    : new FinishState(input, progress, decision);
            } catch (RuntimeException | Error e) {
                if (loop != null) loop.finish("FAILED", e.getClass().getSimpleName());
                throw e;
            }
            }

        }
    }

    /** One authorized capability per reviewer proposal; methods are called by the annotated wrappers above. */
    public record ExecuteState(MissionRunInput input, Progress progress, Decision decision)
            implements Stage {
        public ExecuteState {
            Objects.requireNonNull(input); Objects.requireNonNull(progress); Objects.requireNonNull(decision);
            if (decision.status() != Status.NEEDS_EVIDENCE)
                throw new IllegalArgumentException("Only evidence-request decisions can execute tools");
        }

        // These are plain state predicates. The @Agent-level @Condition
        // methods above expose them to Embabel's GOAP world-state determiner.
        public boolean documentRecommended() {
            return decision.proposalFor(DOCUMENT).isPresent();
        }

        public boolean searchRecommended() {
            return decision.proposalFor(SEARCH).isPresent();
        }

        public boolean graphRecommended() {
            return decision.proposalFor(GRAPH).isPresent() && progress.latestRoots() != null;
        }

        public ReasonState retrieveDocuments(@Provided MissionEvidenceActions capabilities,
                                             @Provided MissionTracePublisher tracePublisher,
                                             OperationContext context) {
            ensureAuthorized(DOCUMENT);
            return run(DOCUMENT, tracePublisher, () -> capabilities.buildDocumentEvidence(input, context, queryFor(DOCUMENT)));
        }

        public ReasonState searchEntities(@Provided MissionEvidenceActions capabilities,
                                          @Provided MissionTracePublisher tracePublisher,
                                          OperationContext context) {
            ensureAuthorized(SEARCH);
            return run(SEARCH, tracePublisher, () -> capabilities.searchInternalEntities(input, context, queryFor(SEARCH)));
        }

        public ReasonState queryGraph(@Provided MissionEvidenceActions capabilities,
                                      @Provided MissionTracePublisher tracePublisher,
                                      OperationContext context) {
            ensureAuthorized(GRAPH);
            return run(GRAPH, tracePublisher, () -> capabilities.readCompanyGraph(
                    input, Objects.requireNonNull(progress.latestRoots()), context, queryFor(GRAPH)));
        }

        private String queryFor(String action) {
            return decision.proposalFor(action).orElseThrow().query();
        }

        private void ensureAuthorized(String capability) {
            if (!input.intent().allowedTools().isEmpty()
                    && !input.intent().allowedTools().contains(capability))
                throw new SecurityException("Tool is not permitted by the mission: " + capability);
        }

        private ReasonState run(String action, MissionTracePublisher tracePublisher,
                java.util.function.Supplier<MissionEvidenceActions.EvidenceState> operation) {
            try (var scope = MissionDecisionTraceContext.open(input, tracePublisher)) {
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
                    Progress updated = progress.withEvidence(action, queryFor(action), state);
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
                } catch (SecurityException e) {
                    if (actionSpan != null) actionSpan.finish("FAILED", "SecurityException");
                    throw e;
                } catch (RuntimeException e) {
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
                    Progress updated = progress.withError(action, queryFor(action), e.getClass().getSimpleName());
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
                            && progress.evidence().stream().anyMatch(e -> !e.evidenceRefs().isEmpty());
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
                                        "missingFacts", decision.missingFacts(), "goalReviewReason", decision.reason()));
                    }
                    if (synthesis != null) synthesis.finish("SUCCEEDED", "");
                    MissionDecisionTraceContext.event(
                            decision.status() == Status.SATISFIED ? "GOAL_SATISFIED"
                                    : decision.status() == Status.READY_FOR_SYNTHESIS
                                            ? "GOAL_EVIDENCE_READY" : "GOAL_BLOCKED",
                            decision.status() == Status.SATISFIED ? "Mission goal satisfied"
                                    : decision.status() == Status.READY_FOR_SYNTHESIS
                                            ? "Grounded answer generated without JEV satisfaction certification"
                                            : "Mission goal unresolved",
                            Map.of("goal", "FinishState.complete", "goalStatus", decision.status().name()));
                    MissionDecisionTraceContext.event("MISSION_FINISH", "Mission process finished",
                            Map.of("goal", "FinishState.complete", "goalStatus", decision.status().name()));
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
                if (evidence.get(i) instanceof MissionEvidenceActions.InternalSearchState root
                        && root.selection().ready()) return root;
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
