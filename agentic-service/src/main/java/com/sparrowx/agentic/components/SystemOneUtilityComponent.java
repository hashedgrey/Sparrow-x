package com.sparrowx.agentic.components;

import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.decision.SemanticDecisionService.Escalation;
import com.sparrowx.agentic.decision.SemanticDecisionService.Request;
import com.sparrowx.agentic.decision.SemanticDecisionService.Result;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evidence-aware System-1 utility assessment.
 *
 * The configured provider may be local Laya, hosted Jev, or another
 * SystemOneDecisionClient implementation.
 *
 * This component never generates actions or plans. It only scores known
 * SparrowX capabilities against the current mission state.
 */
@Component
public final class SystemOneUtilityComponent {

    public static final String DOCUMENT =
            "document_evidence";

    public static final String INTERNAL =
            "internal_search";

    public static final String COMPANY_GRAPH =
            "company_graph";

    public static final String LEARNING_GRAPH =
            "learning_graph";

    public static final String SYNTHESIZE =
            "synthesize";

    private static final Logger LOG = LoggerFactory.getLogger(SystemOneUtilityComponent.class);

    private static final String DOCUMENT_ACTION = "document.evidence.build";
    private static final String INTERNAL_ACTION = "internal.entities.search";
    private static final String COMPANY_GRAPH_ACTION = "internal.company-graph.read";

    private final SemanticDecisionService semantic;

    public SystemOneUtilityComponent(
            SemanticDecisionService semantic
    ) {
        this.semantic = Objects.requireNonNull(semantic, "semantic must not be null");
    }

    /**
     * Call from the mission with stable mission/request IDs so any LLM escalation
     * is scoped to the actual mission rather than an invented identity.
     */
    public UtilityAssessment evaluate(
            UtilityRequest request, String missionId, String requestId
    ) {
        Objects.requireNonNull(request, "request");
        if (missionId == null || missionId.isBlank() || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Mission and request IDs are required for escalation");
        }

        Map<String, DecisionQuestion> questions = activeQuestions(request);
        LOG.info("utility.system1.scope active={} skipped={}",
                questions.keySet(), skippedCapabilities(questions));
        Map<String, Object> state = state(request);
        Map<String, Object> number = Map.of("type", "number", "minimum", 0, "maximum", 1);
        Map<String, Object> properties = new LinkedHashMap<>();
        questions.keySet().forEach(field -> properties.put(field, number));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.copyOf(properties),
                "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);

        Result result = semantic.evaluate(new Request(
                state, questions, 0.80d, Set.of(),
                new Escalation(
                        missionId, "assess-action-utility",
                        requestId + ":utility:" + request.latestAction(),
                        "Assess marginal utility of only the named available capabilities "
                                + "given the evidence supplied. Return numerical probabilities "
                                + "from 0 to 1. Do not invent enterprise facts or new actions.",
                        schema, 300, 0.0,
                        Map.of("component", "system-one-utility", "purpose", "evidence-aware-ranking"))
        ));
        LOG.info("utility.system1.result escalatedFields={}", result.escalatedFields());
        return assessment(request,
                probabilityOrZero(result, questions, DOCUMENT),
                probabilityOrZero(result, questions, INTERNAL),
                probabilityOrZero(result, questions, COMPANY_GRAPH),
                probabilityOrZero(result, questions, LEARNING_GRAPH),
                probabilityOrZero(result, questions, SYNTHESIZE),
                result.escalated() ? result.llmProvider() : result.systemOne().provider(),
                result.escalated() ? result.llmModel() : result.systemOne().model(),
                safe(result.systemOne().requestId()));
    }

    /**
     * Compatibility entry point without mission identifiers: System-1 only.
     * Use the three-argument overload for mission execution and LLM fallback.
     */
    public UtilityAssessment evaluate(UtilityRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, DecisionQuestion> questions = activeQuestions(request);
        DecisionResponse bounded = semantic.tryBounded(
                state(request), questions, 0.5d, "unscoped-utility")
                .orElseThrow(() -> new IllegalStateException(
                        "Unscoped System-1 utility failed; pass missionId and requestId for escalation"));
        return assessment(request,
                boundedProbabilityOrZero(bounded, questions, DOCUMENT),
                boundedProbabilityOrZero(bounded, questions, INTERNAL),
                boundedProbabilityOrZero(bounded, questions, COMPANY_GRAPH),
                boundedProbabilityOrZero(bounded, questions, LEARNING_GRAPH),
                boundedProbabilityOrZero(bounded, questions, SYNTHESIZE),
                bounded.provider(), bounded.model(), bounded.requestId());
    }

    /**
     * Allow re-evaluation of prior capabilities after new observations.
     * Repetition limits are enforced by MissionGoalReviewComponent and agent
     * value methods, not by suppressing every previously executed capability.
     *
     * Always retain SYNTHESIZE so an empty decision request is never sent.
     */
    private static Map<String, DecisionQuestion> activeQuestions(UtilityRequest request) {
        Map<String, DecisionQuestion> active = new LinkedHashMap<>(questions());
        if (!request.requiresDocumentEvidence()
                && !hasDocumentDiscoverySignal(request.evidence())) {
            active.remove(DOCUMENT);
        }

        if (!request.requiresInternalContext()
                && !hasInternalDiscoverySignal(request.evidence())) {
            active.remove(INTERNAL);
        }

        if (request.resolvedEntityId().isBlank()) {
            active.remove(COMPANY_GRAPH);
            active.remove(LEARNING_GRAPH);
        }

        return active;
    }

    private static Set<String> skippedCapabilities(Map<String, DecisionQuestion> active) {
        java.util.Set<String> skipped = new java.util.LinkedHashSet<>(
                List.of(DOCUMENT, INTERNAL, COMPANY_GRAPH, LEARNING_GRAPH, SYNTHESIZE));
        skipped.removeAll(active.keySet());
        return Set.copyOf(skipped);
    }

    private static double probabilityOrZero(
            Result result, Map<String, DecisionQuestion> active, String capability) {
        return active.containsKey(capability) ? result.probability(capability) : 0.0d;
    }

    private static double boundedProbabilityOrZero(
            DecisionResponse response, Map<String, DecisionQuestion> active, String capability) {
        return active.containsKey(capability) ? response.noulValue(capability) : 0.0d;
    }

    private UtilityAssessment assessment(
            UtilityRequest request,
            double documentUtility, double internalUtility, double companyGraphUtility,
            double learningGraphUtility, double synthesisUtility,
            String provider, String model, String requestId
    ) {
        // Policy/capability boundaries take precedence over model scores.
        if (!request.requiresDocumentEvidence() && !hasDocumentDiscoverySignal(request.evidence())) {
            documentUtility = 0.0d;
        }
        if (!request.requiresInternalContext() && !hasInternalDiscoverySignal(request.evidence())) {
            internalUtility = 0.0d;
        }
        if (request.resolvedEntityId().isBlank()) {
            companyGraphUtility = 0.0d;
            learningGraphUtility = 0.0d;
        }
        return new UtilityAssessment(documentUtility, internalUtility,
                companyGraphUtility, learningGraphUtility, synthesisUtility,
                safe(provider), safe(model), safe(requestId));
    }

    private static boolean hasDocumentDiscoverySignal(
            List<Map<String, Object>> evidence
    ) {
        return hasSourceSignal(
                evidence,
                "DOCUMENT"
        );
    }

    private static boolean hasInternalDiscoverySignal(
            List<Map<String, Object>> evidence
    ) {
        return hasSourceSignal(
                evidence,
                "INTERNAL"
        );
    }

    private static boolean hasSourceSignal(List<Map<String, Object>> evidence, String token) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }

        for (Map<String, Object> item : evidence) {
            Object signalsValue = item.get("signals");

            if (!(signalsValue instanceof List<?> signals)) {
                continue;
            }

            for (Object signalValue : signals) {
                if (!(signalValue instanceof Map<?, ?> signal)) {
                    continue;
                }
                Object sourceType = signal.get("sourceType");
                if (sourceType instanceof String text && text.contains(token)) {
                    return true;
                }
            }
        }

        return false;
    }

    /*
     * ------------------------------------------------------------------
     * CURRENT BLACKBOARD PROJECTION
     * ------------------------------------------------------------------
     */

    private static Map<String, Object> state(UtilityRequest request) {
        Map<String, Object> state =
                new LinkedHashMap<>();

        state.put("application", "SparrowX enterprise engineering assistant");
        state.put("mission", request.query());
        state.put("selectedPath", request.selectedPath().name());
        state.put("latestAction", request.latestAction());
        state.put("evidence", request.evidence());
        state.put("resolvedEntityId", request.resolvedEntityId());
        state.put("resolvedEntityType", request.resolvedEntityType());
        state.put("alreadyExecutedActions", request.alreadyExecutedActions());
        state.put("allowedTools", request.allowedTools());
        state.put("requireCitations", request.requireCitations());
        state.put("requireVerification", request.requireVerification());
        state.put("requiresDocumentEvidence", request.requiresDocumentEvidence());
        state.put("requiresInternalContext", request.requiresInternalContext());
        return Map.copyOf(state);
    }

    /*
     * ------------------------------------------------------------------
     * KNOWN SPARROWX CAPABILITY JUDGEMENTS
     * ------------------------------------------------------------------
     */

    private static Map<String, DecisionQuestion> questions() {
        Map<String, DecisionQuestion> questions =
                new LinkedHashMap<>();

        questions.put(
                DOCUMENT,
                new NoulQuestion(
                        """
                        Would invoking SparrowX capability
                        document.evidence.build NOW materially improve the
                        mission answer given the evidence already collected?

                        This capability retrieves relevant document content and
                        constructs grounded document evidence.
                        """,
                        """
                        Important facts needed by the mission remain available
                        from relevant documents and another document evidence
                        operation would add useful information.
                        """,
                        """
                        Document retrieval is irrelevant, unavailable,
                        redundant or current document evidence is already
                        sufficient.
                        """
                )
        );

        questions.put(
                INTERNAL,
                new NoulQuestion(
                        """
                        Would invoking SparrowX capability
                        internal.entities.search NOW materially improve the
                        mission answer?
                
                        This capability resolves SparrowX enterprise services,
                        teams, engineers, repositories, runbooks, onboarding,
                        ownership and organizational relationships.
                
                        A document, article, paper, publication or report title
                        is not an internal enterprise entity merely because it
                        has a proper name.
                        """,
                        """
                        The mission or newly collected evidence requires resolving
                        a SparrowX enterprise-specific entity or relationship.
                        """,
                        """
                        Internal entity resolution is irrelevant or redundant.
                        Requests about document or publication content alone should
                        use document evidence instead.
                        """
                )
        );

        questions.put(
                COMPANY_GRAPH,
                new NoulQuestion(
                        """
                        Would invoking SparrowX capability
                        internal.company-graph.read NOW materially improve the
                        mission answer?

                        This capability expands a resolved internal entity into
                        company relationships such as ownership, teams,
                        services, repositories and dependencies.
                        """,
                        """
                        A unique internal entity has been resolved and its
                        enterprise relationships are useful to the mission.
                        """,
                        """
                        No useful resolved entity exists, the relationships are
                        irrelevant, or company graph evidence is already
                        sufficient.
                        """
                )
        );

        questions.put(
                LEARNING_GRAPH,
                new NoulQuestion(
                        """
                        Would invoking SparrowX capability
                        internal.learning-graph.read NOW materially improve the
                        mission answer?

                        This capability expands a resolved entity into
                        onboarding, learning and knowledge relationships.
                        """,
                        """
                        Learning paths, onboarding dependencies or knowledge
                        relationships related to the resolved entity are useful
                        to the mission.
                        """,
                        """
                        Learning graph information is irrelevant, unavailable
                        or already sufficient.
                        """
                )
        );

        questions.put(
                SYNTHESIZE,
                new NoulQuestion(
                        """
                        Is the current SparrowX mission state ready for final
                        synthesis NOW?

                        Answer true only when the current evidence can support a
                        useful grounded final response and another available
                        retrieval capability is unlikely to materially improve
                        it.
                        """,
                        """
                        The mission objective can now be answered using the
                        available state and additional retrieval would mostly
                        be redundant.
                        """,
                        """
                        A meaningful information gap remains and at least one
                        available SparrowX capability is likely to improve the
                        answer.
                        """
                )
        );

        return Map.copyOf(
                questions
        );
    }

    /*
     * ------------------------------------------------------------------
     * DTOs
     * ------------------------------------------------------------------
     */

    public record UtilityRequest(
            String query,
            MissionPath selectedPath,
            String latestAction,
            List<Map<String, Object>> evidence,
            String resolvedEntityId,
            String resolvedEntityType,
            Set<String> alreadyExecutedActions,
            Set<String> allowedTools,
            boolean requiresDocumentEvidence,
            boolean requiresInternalContext,
            boolean requireCitations,
            boolean requireVerification
    )  {
        public UtilityRequest {
            query =
                    requireText(
                            query,
                            "query"
                    );

            selectedPath =
                    Objects.requireNonNull(
                            selectedPath,
                            "selectedPath must not be null"
                    );

            latestAction =
                    safe(
                            latestAction
                    );

            evidence =
                    evidence == null
                            ? List.of()
                            : List.copyOf(
                            evidence
                    );

            resolvedEntityId =
                    safe(
                            resolvedEntityId
                    );

            resolvedEntityType =
                    safe(
                            resolvedEntityType
                    );

            alreadyExecutedActions =
                    alreadyExecutedActions == null
                            ? Set.of()
                            : Set.copyOf(
                            alreadyExecutedActions
                    );

            allowedTools =
                    allowedTools == null
                            ? Set.of()
                            : Set.copyOf(
                            allowedTools
                    );
        }
    }

    public record UtilityAssessment(
            double documentUtility,
            double internalUtility,
            double companyGraphUtility,
            double learningGraphUtility,
            double synthesisUtility,
            String provider,
            String model,
            String requestId,
            MissionGoalReviewComponent.Decision goalReview
    ) {
        public UtilityAssessment(double documentUtility, double internalUtility,
                                 double companyGraphUtility, double learningGraphUtility,
                                 double synthesisUtility, String provider, String model,
                                 String requestId) {
            this(documentUtility, internalUtility, companyGraphUtility,
                    learningGraphUtility, synthesisUtility, provider, model,
                    requestId, null);
        }

        public UtilityAssessment withGoalReview(MissionGoalReviewComponent.Decision review) {
            return new UtilityAssessment(documentUtility, internalUtility,
                    companyGraphUtility, learningGraphUtility, synthesisUtility,
                    provider, model, requestId, Objects.requireNonNull(review));
        }

        public UtilityAssessment {
            requireProbability(
                    documentUtility,
                    "documentUtility"
            );

            requireProbability(
                    internalUtility,
                    "internalUtility"
            );

            requireProbability(
                    companyGraphUtility,
                    "companyGraphUtility"
            );

            requireProbability(
                    learningGraphUtility,
                    "learningGraphUtility"
            );

            requireProbability(
                    synthesisUtility,
                    "synthesisUtility"
            );

            provider =
                    safe(
                            provider
                    );

            model =
                    safe(
                            model
                    );

            requestId =
                    safe(
                            requestId
                    );
        }
    }

    private static void requireProbability(
            double value,
            String field
    ) {
        if (!Double.isFinite(value)
                || value < 0.0d
                || value > 1.0d) {

            throw new IllegalArgumentException(
                    field
                            + " must be between 0.0 and 1.0"
            );
        }
    }

    private static String requireText(
            String value,
            String field
    ) {
        String result =
                safe(
                        value
                );

        if (result.isBlank()) {
            throw new IllegalArgumentException(
                    field
                            + " must not be blank"
            );
        }

        return result;
    }

    private static String safe(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }
}
