package com.sparrowx.agentic.components;

import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import org.springframework.stereotype.Component;

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

    private final SystemOneDecisionClient decisionClient;

    public SystemOneUtilityComponent(
            SystemOneDecisionClient decisionClient
    ) {
        this.decisionClient = Objects.requireNonNull(decisionClient, "decisionClient must not be null");
    }

    public UtilityAssessment evaluate(
            UtilityRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        DecisionResponse response =
                decisionClient.evaluate(
                        state(request),
                        questions()
                );

        return new UtilityAssessment(
                response.noulValue(DOCUMENT),
                response.noulValue(INTERNAL),
                response.noulValue(COMPANY_GRAPH),
                response.noulValue(LEARNING_GRAPH),
                response.noulValue(SYNTHESIZE),
                decisionClient.provider(),
                safe(response.model()),
                safe(response.requestId())
        );
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
            boolean requireCitations,
            boolean requireVerification
    ) {
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
            String requestId
    ) {
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