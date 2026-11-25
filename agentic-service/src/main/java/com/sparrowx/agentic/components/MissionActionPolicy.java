package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent next-action policy for Embabel. Authorization, prerequisites,
 * budgets and the eligible candidate pool are determined by trusted Java state,
 * NEVER by JEV. For competing candidates, JEV supplies optional bounded semantic
 * marginal-value estimates, blended with deterministic evidence-gap/retry scores.
 * Embabel alone selects an action from the frozen ActionPlan. @Cost methods read
 * that plan without inference; a generative LLM creates follow-up queries only
 * AFTER Embabel has selected the action.
 */
@Component
public final class MissionActionPolicy {
    private static final String DOCUMENT = MissionCapabilities.DOCUMENT_EVIDENCE;
    private static final String SEARCH = MissionCapabilities.INTERNAL_SEARCH;
    private static final String GRAPH = MissionCapabilities.COMPANY_GRAPH;
    private static final Set<String> CAPABILITIES = Set.of(DOCUMENT, SEARCH, GRAPH);
    private static final double ACTION_COST = MissionGoalReviewComponent.EVIDENCE_ACTION_COST;

    private final SemanticDecisionService semantic;
    private final int maxEvidenceActions;
    private final int maxCallsPerCapability;

    @Value("${sparrowx.mission.reasoning.action-confidence:0.80}")
    private double actionConfidence = 0.80d;

    @Value("${sparrowx.mission.reasoning.semantic-utility-weight:0.55}")
    private double semanticUtilityWeight = 0.55d;

    public MissionActionPolicy(SemanticDecisionService semantic,
            @Value("${sparrowx.mission.reasoning.max-evidence-actions:10}") int maxEvidenceActions,
            @Value("${sparrowx.mission.reasoning.max-per-capability:3}") int maxCallsPerCapability) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        validateBudgets(maxEvidenceActions, maxCallsPerCapability);
        this.maxEvidenceActions = maxEvidenceActions;
        this.maxCallsPerCapability = maxCallsPerCapability;
    }

    /** Authoritative eligibility shared with goal review for budget fallback. */
    static Set<String> eligible(MissionGoalReviewComponent.Request request,
                                int maxEvidenceActions, int maxCallsPerCapability) {
        int total = request.attempts().values().stream().mapToInt(Integer::intValue).sum();
        if (total >= maxEvidenceActions) return Set.of();
        boolean rootsReady = hasExhaustiveRoots(request.observations());
        boolean emptyRoots = hasExhaustiveEmptyRoots(request.observations());
        boolean graphAttempted = request.attempts().getOrDefault(GRAPH, 0) > 0;
        boolean completeGraph = hasCurrentCompleteGraph(request.observations());
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String capability : CAPABILITIES) {
            if (!MissionCapabilities.allows(request.allowedTools(), capability)
                    || request.attempts().getOrDefault(capability, 0) >= maxCallsPerCapability) continue;
            if (DOCUMENT.equals(capability) && !request.requiresDocumentEvidence()) continue;
            if (SEARCH.equals(capability) && !request.requiresInternalContext()) continue;
            if (GRAPH.equals(capability)
                    && (!request.requiresCompanyGraph() || !rootsReady || emptyRoots)) continue;
            if (SEARCH.equals(capability) && emptyRoots) continue;
            // Don't rediscover a known graph scope before its first traversal.
            if (SEARCH.equals(capability) && request.requiresCompanyGraph() && rootsReady
                    && (!graphAttempted || completeGraph)) continue;
            allowed.add(capability);
        }
        return Set.copyOf(allowed);
    }

    /**
     * Build a complete, authorized candidate set independently of JEV.
     * JEV may change the ORDER through semantic utility, not membership.
     * All JEV evaluation happens exactly once before Embabel planning.
     */
    public ActionPlan plan(MissionIntent intent, MissionGoalReviewComponent.Request request,
                           boolean latestRootsAvailable) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(request, "request");
        if (!Double.isFinite(actionConfidence) || actionConfidence < 0.5 || actionConfidence > 1
                || !Double.isFinite(semanticUtilityWeight) || semanticUtilityWeight < 0
                || semanticUtilityWeight > 1) {
            throw new IllegalStateException("Invalid semantic utility configuration");
        }
        List<String> candidates = eligible(request, maxEvidenceActions, maxCallsPerCapability)
                .stream()
                .filter(capability -> MissionCapabilities.allows(intent, capability))
                .filter(capability -> !GRAPH.equals(capability) || latestRootsAvailable)
                // A failed original query may be retried once, not indefinitely.
                .filter(capability -> failedOnlyAttempts(
                        request.observations(), capability, request.objective()) < 2)
                .sorted().toList();
        if (candidates.isEmpty()) return ActionPlan.empty();

        // This call returns only independently validated, confident JEV answers.
        // Abstentions, unavailable JEV and low-confidence votes are NOT negative
        // utility votes; those capabilities retain their deterministic value.
        Map<String, Double> semanticScores = scoreSemanticMarginalValue(request, candidates);
        Map<String, Double> scores = new TreeMap<>();
        for (String capability : candidates) {
            double heuristic = utility(request, capability);
            Double semanticScore = semanticScores.get(capability);
            double combined = blendedUtility(heuristic, semanticScore, semanticUtilityWeight);
            // Every authorized, eligible capability must remain selectable.
            // No JEV vote may silently remove an executable action.
            scores.put(capability, combined);
            Map<String, String> trace = new LinkedHashMap<>();
            trace.put("capability", capability);
            trace.put("utility", Double.toString(combined));
            trace.put("deterministicUtility", Double.toString(heuristic));
            trace.put("actionCost", Double.toString(ACTION_COST));
            trace.put("scorer", semanticScore == null ? "DETERMINISTIC_FALLBACK" : "BLENDED_JEV_UTILITY");
            if (semanticScore != null) trace.put("jevMarginalUtility", Double.toString(semanticScore));
            MissionDecisionTraceContext.event("MISSION_ACTION_UTILITY",
                    "Independent capability utility for Embabel", Map.copyOf(trace));
        }
        return new ActionPlan(scores);
    }

    /**
     * Bounded semantic relevance is advisory, NOT an action proposal. With one
     * candidate there is nothing to rank, so avoid a needless JEV request.
     */
    private Map<String, Double> scoreSemanticMarginalValue(
            MissionGoalReviewComponent.Request request, List<String> candidates) {
        if (candidates.size() < 2 || semanticUtilityWeight == 0) return Map.of();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("originalMission", request.objective());
        state.put("latestCapability", request.latestCapability());
        state.put("observations", request.observations());
        state.put("attemptsByCapability", request.attempts());
        state.put("requiresDocumentEvidence", request.requiresDocumentEvidence());
        state.put("requiresInternalContext", request.requiresInternalContext());
        state.put("requiresCompanyGraph", request.requiresCompanyGraph());
        state.put("permittedNextCapabilities", candidates);
        state.put("remainingEvidenceActions", Math.max(0,
                maxEvidenceActions - request.attempts().values().stream().mapToInt(Integer::intValue).sum()));
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (String capability : candidates) {
            String purpose = switch (capability) {
                case DOCUMENT -> "retrieve NEW source-backed document evidence";
                case SEARCH -> "identify or correct internal entities and graph roots";
                case GRAPH -> "retrieve NEW verified relationships for the currently selected graph roots";
                default -> throw new IllegalArgumentException("Unknown evidence capability: " + capability);
            };
            questions.put(capability, new NoulQuestion(
                    "Considering the ORIGINAL mission, all prior observations and missing facts, "
                            + "how likely is this action to " + purpose + "? "
                            + "Assess NEW marginal evidence value; do not select or exclude actions. "
                            + "Do not treat a successful tool call as evidence of mission completion.",
                    "Useful new evidence for an unresolved requirement",
                    "Redundant or irrelevant for current evidence gaps"));
        }
        var votes = semantic.tryIndependentBounded(Map.copyOf(state), questions,
                actionConfidence, request.operationId() + ":marginal-utilities");
        Map<String, Double> result = new LinkedHashMap<>();
        for (String capability : candidates) {
            if (votes.get(capability) instanceof NoulAnswer answer
                    && Double.isFinite(answer.probability())
                    && answer.probability() >= 0.0 && answer.probability() <= 1.0) {
                result.put(capability, answer.probability());
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Combine independent semantic relevance with structural evidence utility.
     * The floor is above action cost, ensuring ranking can never revoke an
     * eligibility decision. Null = abstention/unavailable, NOT a score of zero.
     */
    static double blendedUtility(double heuristic, Double semantic, double weight) {
        if (!Double.isFinite(heuristic) || heuristic < 0 || heuristic > 1
                || !Double.isFinite(weight) || weight < 0 || weight > 1
                || (semantic != null && (!Double.isFinite(semantic) || semantic < 0 || semantic > 1))) {
            throw new IllegalArgumentException("Invalid utility input");
        }
        double blended = semantic == null ? heuristic
                : (1.0 - weight) * heuristic + weight * semantic;
        return Math.min(1.0, Math.max(ACTION_COST + 0.01d, blended));
    }

    /**
     * Deterministic baseline: missing evidence, graph dependencies, corrective
     * root discovery and retry penalties, never a semantic inference result.
     */
    static double utility(MissionGoalReviewComponent.Request request, String capability) {
        var observations = request.observations();
        boolean roots = hasExhaustiveRoots(observations);
        boolean graphComplete = hasCurrentCompleteGraph(observations);
        boolean docsPresent = observations.stream().anyMatch(o -> DOCUMENT.equals(o.get("action")) && hasEvidence(o));
        boolean correctiveRoots = needsCorrectiveRootDiscovery(observations);
        double base = switch (capability) {
            case SEARCH -> correctiveRoots ? 0.96 : (!roots ? 0.90 : 0.36);
            case GRAPH -> correctiveRoots ? 0.44 : (!graphComplete ? 0.94 : 0.37);
            case DOCUMENT -> !docsPresent ? 0.87 : 0.38;
            default -> 0.0;
        };
        int attempts = request.attempts().getOrDefault(capability, 0);
        return Math.max(0.0, Math.min(1.0, base - 0.12 * attempts));
    }

    /** Called only for the capability Embabel actually selected. */
    public String resolveSelectedQuery(String missionId, String operationId, String objective,
            List<Map<String, Object>> observations, String capability) {
        Objects.requireNonNull(missionId, "missionId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(observations, "observations");
        if (!CAPABILITIES.contains(capability)) throw new IllegalArgumentException("Unknown capability");

        boolean previouslyAttempted = observations.stream().anyMatch(o -> capability.equals(o.get("action"))
                && sameQuery(objective, Objects.toString(o.get("query"), "")));
        if (!previouslyAttempted) return objective;
        // A corrected root scope changes the graph query's evaluated inputs.
        // Re-run the original objective once against these new authoritative roots.
        if (GRAPH.equals(capability) && latestRootSelectionAfterLatestGraph(observations))
            return objective;
        int failures = failedOnlyAttempts(observations, capability, objective);
        if (failures >= 2) return "";
        if (failures == 1) return objective;

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("originalMission", objective);
        state.put("observations", observations);
        state.put("authorizedCapability", capability);
        state.put("correctiveRootDiscovery", SEARCH.equals(capability)
                && needsCorrectiveRootDiscovery(observations));
        String generated;
        try {
            generated = semantic.escalate(new LlmDecisionEngine.Request(
                    missionId, operationId, operationId,
                    "Generate ONE new evidence subquery for the ALREADY-SELECTED capability. "
                            + "Preserve the original mission, named constraints and required outputs. "
                            + "Target a missing fact, avoid previously executed subqueries, "
                            + "and do not change the capability or decide mission completion.",
                    Map.copyOf(state), Set.of("nextQuery"),
                    Map.of("type", "object", "properties", Map.of("nextQuery", Map.of("type", "string")),
                            "required", List.of("nextQuery"), "additionalProperties", false),
                    650, 0.0d, Map.of("component", "selected-capability-query-generation")))
                    .output().get("nextQuery") instanceof String q ? q.trim() : "";
        } catch (RuntimeException exception) {
            MissionDecisionTraceContext.event("ACTION_QUERY_UNAVAILABLE", "Selected action query generation failed",
                    Map.of("capability", capability, "errorType", exception.getClass().getSimpleName()));
            return "";
        }
        if (generated.isBlank()) return "";
        // Corrective root queries must retain the exact frozen objective.
        if (SEARCH.equals(capability) && needsCorrectiveRootDiscovery(observations)
                && !generated.contains(objective)) {
            generated = objective + "\nCorrective evidence focus: " + generated;
        }
        String candidate = generated;
        if (observations.stream().anyMatch(o -> capability.equals(o.get("action"))
                && sameQuery(candidate, Objects.toString(o.get("query"), "")))) return "";
        return candidate;
    }

    static int failedOnlyAttempts(List<Map<String, Object>> observations, String capability, String query) {
        int failures = 0;
        for (Map<String, Object> observation : observations) {
            if (!capability.equals(observation.get("action"))
                    || !sameQuery(query, Objects.toString(observation.get("query"), ""))) continue;
            if (!(observation.get("attributes") instanceof Map<?, ?> attrs)
                    || !Boolean.TRUE.equals(attrs.get("executionFailed"))) return 0;
            failures++;
        }
        return failures;
    }

    private static boolean sameQuery(String a, String b) { return a.trim().equalsIgnoreCase(b.trim()); }

    private static boolean hasEvidence(Map<String, Object> o) {
        if (o.get("evidenceCount") instanceof Number n && n.intValue() > 0) return true;
        return o.get("attributes") instanceof Map<?, ?> m && m.get("matchingRowCount") instanceof Number n
                && n.intValue() > 0;
    }

    private static boolean hasExhaustiveRoots(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            var o = observations.get(i);
            if (!SEARCH.equals(o.get("action"))) continue;
            if (!(o.get("attributes") instanceof Map<?, ?> m)) return false;
            return Boolean.TRUE.equals(m.get("rootResolutionReady"))
                    && Boolean.TRUE.equals(m.get("rootScopeExhaustive"))
                    && m.get("selectedRootCount") instanceof Number n && n.intValue() > 0;
        }
        return false;
    }

    private static boolean hasExhaustiveEmptyRoots(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            var o = observations.get(i);
            if (!SEARCH.equals(o.get("action"))) continue;
            if (!(o.get("attributes") instanceof Map<?, ?> m)) return false;
            return Boolean.TRUE.equals(m.get("rootResolutionReady"))
                    && Boolean.TRUE.equals(m.get("rootScopeExhaustive"))
                    && m.get("selectedRootCount") instanceof Number n && n.intValue() == 0;
        }
        return false;
    }

    private static boolean hasCurrentCompleteGraph(List<Map<String, Object>> observations) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            var o = observations.get(i);
            if (SEARCH.equals(o.get("action"))) return false;
            if (GRAPH.equals(o.get("action"))) {
                return o.get("attributes") instanceof Map<?, ?> m
                        && "COMPLETE".equals(m.get("graphResolutionStatus"));
            }
        }
        return false;
    }

    private static boolean latestRootSelectionAfterLatestGraph(List<Map<String, Object>> observations) {
        int lastSearch = -1, lastGraph = -1;
        for (int i = 0; i < observations.size(); i++) {
            var action = observations.get(i).get("action");
            if (SEARCH.equals(action)) lastSearch = i;
            if (GRAPH.equals(action)) lastGraph = i;
        }
        return lastSearch > lastGraph && lastGraph >= 0;
    }

    private static boolean needsCorrectiveRootDiscovery(List<Map<String, Object>> observations) {
        // A newer root selection has already corrected the previous graph scope.
        if (latestRootSelectionAfterLatestGraph(observations)) return false;
        Map<String, Object> latestSearch = null, latestGraph = null;
        for (Map<String, Object> o : observations) {
            if (SEARCH.equals(o.get("action"))) latestSearch = o;
            if (GRAPH.equals(o.get("action"))) latestGraph = o;
        }
        if (latestSearch == null || latestGraph == null) return false;
        if (!(latestSearch.get("attributes") instanceof Map<?, ?> search)
                || !(latestGraph.get("attributes") instanceof Map<?, ?> graph)) return false;
        String mode = Objects.toString(search.get("rootSelectionMode"), "");
        return ("TYPE_SCAN".equals(mode) || "MULTI_TYPE_SCAN".equals(mode))
                && "AMBIGUOUS".equals(graph.get("graphResolutionStatus"));
    }

    private static void validateBudgets(int total, int each) {
        if (total < 1 || total > 100 || each < 1 || each > total)
            throw new IllegalArgumentException("Invalid evidence iteration budget");
    }

    /** Immutable, serializable-in-state candidate set. Each utility is planning-time only. */
    public record ActionPlan(Map<String, Double> utilityByCapability) {
        public ActionPlan {
            utilityByCapability = Map.copyOf(Objects.requireNonNull(utilityByCapability));
            for (var entry : utilityByCapability.entrySet()) {
                if (!CAPABILITIES.contains(entry.getKey()) || !Double.isFinite(entry.getValue())
                        || entry.getValue() <= ACTION_COST || entry.getValue() > 1.0)
                    throw new IllegalArgumentException("Invalid capability utility: " + entry);
            }
        }
        public static ActionPlan empty() { return new ActionPlan(Map.of()); }
        public boolean eligible(String capability) { return utilityByCapability.containsKey(capability); }
        public double utility(String capability) { return utilityByCapability.getOrDefault(capability, 0.0); }
        public int size() { return utilityByCapability.size(); }
    }
}
