package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.decision.SemanticDecisionService.Escalation;
import com.sparrowx.agentic.decision.SemanticDecisionService.Result;
import com.sparrowx.agentic.mission.artifact.PreparedArtifact;
import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.PlannerMode;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the frozen semantic interpretation of a mission before execution.
 *
 * Bounded semantic decisions are evaluated by System-1. SemanticDecisionService
 * escalates uncertain decisions to the structured LLM. Open-ended tool
 * parameter extraction is intentionally left to the relevant tool resolver.
 */
@Component
public final class IntentComponent {

    private static final double YES_THRESHOLD = 0.55d;
    private static final double RESEARCH_THRESHOLD = 0.60d;
    private static final double HYBRID_THRESHOLD = 0.60d;
    private static final double MIN_SYSTEM_ONE_CONFIDENCE = 0.75d;

    private static final String DOCUMENT = "requires_document_evidence";
    private static final String INTERNAL = "requires_internal_context";
    private static final String COMPANY_GRAPH = "requires_company_graph";
    private static final String VERIFICATION = "requires_verification";
    private static final String RESEARCH = "requires_research";
    private static final String HYBRID = "requires_hybrid_planning";

    private static final String SOURCE_RULES = """
            Classify the sources needed for the mission, not every source that
            might mention its topic. Citations can cite internal graph evidence;
            requireCitations does not imply document retrieval or verification.
            InternalService supplies structured entities and relationships:
            service ownership, team membership, repositories and dependencies.
            A current ownership lookup normally needs internal context and a
            company graph, without documents or explicit verification.
            DocumentService supplies document content. Require it for questions
            that depend on document contents, requested document analysis, or
            comparison with a document. An available attachment alone does not
            make its contents necessary. Mixed requests may need both services.
            Explicit verification means a requested validation or cross-check;
            ordinary evidence-grounded answering alone does not require it.
            Classify source requirements separately from planning and durability.
            """;

    private final SemanticDecisionService decisions;

    public IntentComponent(SemanticDecisionService decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
    }

    public MissionIntent interpret(IntentRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Result semantic = decisions.evaluate(
                new SemanticDecisionService.Request(
                        state(request),
                        questions(),
                        MIN_SYSTEM_ONE_CONFIDENCE,
                        Set.of(),
                        escalation(request)
                )
        );

        double documentUtility = semantic.probability(DOCUMENT);
        double internalUtility = semantic.probability(INTERNAL);
        double companyGraphUtility = semantic.probability(COMPANY_GRAPH);
        double verificationUtility = semantic.probability(VERIFICATION);
        double researchUtility = semantic.probability(RESEARCH);
        double hybridUtility = semantic.probability(HYBRID);

        boolean requiresDocumentEvidence = documentUtility >= YES_THRESHOLD;
        boolean requiresCompanyGraph = companyGraphUtility >= YES_THRESHOLD;

        // A graph traversal cannot occur without first resolving internal context.
        boolean requiresInternalContext =
                internalUtility >= YES_THRESHOLD || requiresCompanyGraph;

        boolean requiresVerification = verificationUtility >= YES_THRESHOLD;

        MissionPath selectedPath = selectPath(request, researchUtility);
        PlannerMode plannerMode = selectPlanner(
                requiresDocumentEvidence,
                requiresInternalContext,
                hybridUtility
        );

        Map<String, Object> attributes = new LinkedHashMap<>(semantic.metadata());
        attributes.put("intentEngine", "semantic-decision-service");
        attributes.put("documentUtility", documentUtility);
        attributes.put("internalUtility", internalUtility);
        attributes.put("companyGraphUtility", companyGraphUtility);
        attributes.put("verificationUtility", verificationUtility);
        attributes.put("researchUtility", researchUtility);
        attributes.put("hybridUtility", hybridUtility);
        attributes.put("preparedArtifactCount", request.artifacts().size());

        return new MissionIntent(
                request.missionId(),
                request.query(),
                selectedPath,
                plannerMode,
                Set.of(),
                Set.of(),
                request.requiredOutputSections(),
                requiresDocumentEvidence,
                requiresInternalContext,
                requiresCompanyGraph,
                request.requireHumanReview(),
                request.requireCitations(),
                requiresVerification,
                request.allowExternalSources(),
                request.allowedTools(),
                request.allowedSourceServices(),
                Map.copyOf(attributes)
        );
    }

    private static Map<String, Object> state(IntentRequest request) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("application", "SparrowX enterprise engineering assistant");
        state.put("mission", request.query());
        state.put("sourceSelectionRules", SOURCE_RULES);
        state.put("preferredPath", request.preferredPath().name());
        state.put(
                "artifacts",
                request.artifacts().stream()
                        .map(IntentComponent::artifactState)
                        .toList()
        );
        state.put("allowedTools", request.allowedTools());
        state.put("allowedSourceServices", request.allowedSourceServices());
        state.put("requiredOutputSections", request.requiredOutputSections());
        state.put("requireCitations", request.requireCitations());
        state.put("requireHumanReview", request.requireHumanReview());
        state.put("allowExternalSources", request.allowExternalSources());
        state.put("attributes", request.attributes());
        return Map.copyOf(state);
    }

    private static Map<String, Object> artifactState(PreparedArtifact artifact) {
        return Map.of(
                "filename", safe(artifact.filename()),
                "documentId", safe(artifact.documentId())
        );
    }

    private static Map<String, DecisionQuestion> questions() {
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();

        questions.put(
                DOCUMENT,
                new NoulQuestion(
                        "Does answering this mission require document contents, beyond structured internal entities and relationships?",
                        "The requested answer depends on document contents, document analysis, or comparison with documents.",
                        "Structured internal data suffices; ownership, membership, repository and dependency lookups do not need documents merely because documents may mention them."
                )
        );

        questions.put(
                INTERNAL,
                new NoulQuestion(
                        "Does this mission require SparrowX internal data?",
                        "The answer depends on internal entities or enterprise context.",
                        "The mission does not require SparrowX internal data."
                )
        );

        questions.put(
                COMPANY_GRAPH,
                new NoulQuestion(
                        "Does this mission require relationships around an internal entity?",
                        "The answer depends on a relationship such as ownership, membership, association or dependency.",
                        "Resolving the internal entity itself is sufficient."
                )
        );

        questions.put(
                VERIFICATION,
                new NoulQuestion(
                        "Does this mission require explicit evidence verification?",
                        "The user explicitly requests evidence validation or cross-checking beyond ordinary retrieval.",
                        "Ordinary grounded retrieval and citations suffice; an ordinary factual lookup is not a verification request."
                )
        );

        questions.put(
                RESEARCH,
                new NoulQuestion(
                        "Does this mission require durable multi-step research?",
                        "The mission needs substantial multi-step investigation or recoverable intermediate work.",
                        "The mission is short enough for normal execution."
                )
        );

        questions.put(
                HYBRID,
                new NoulQuestion(
                        "Does this mission require adaptive action selection?",
                        "Useful next actions may become known only after earlier evidence is retrieved.",
                        "The required action path is predictable before execution."
                )
        );

        return Map.copyOf(questions);
    }

    private static Escalation escalation(IntentRequest request) {
        return new Escalation(
                request.missionId(),
                "resolve-mission-semantics",
                request.missionId() + ":semantic-intent:v3",
                """
                Resolve only the requested semantic fields.
                Return probabilities from 0.0 to 1.0 for each requires_* field.
                Do not answer the mission.
                """ + SOURCE_RULES,
                escalationSchema(),
                500,
                0.0d,
                Map.of(
                        "component", "intent",
                        "purpose", "semantic-escalation"
                )
        );
    }

    private static Map<String, Object> escalationSchema() {
        Map<String, Object> probability = Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(DOCUMENT, probability);
        properties.put(INTERNAL, probability);
        properties.put(COMPANY_GRAPH, probability);
        properties.put(VERIFICATION, probability);
        properties.put(RESEARCH, probability);
        properties.put(HYBRID, probability);

        return Map.of(
                "type", "object",
                "properties", Map.copyOf(properties),
                "additionalProperties", false
        );
    }

    private static MissionPath selectPath(
            IntentRequest request,
            double researchUtility
    ) {
        MissionPath preferred = request.preferredPath();
        if (preferred != MissionPath.UNSPECIFIED) {
            return preferred;
        }

        return researchUtility >= RESEARCH_THRESHOLD
                ? MissionPath.RESEARCH
                : MissionPath.FAST;
    }

    private static PlannerMode selectPlanner(
            boolean requiresDocumentEvidence,
            boolean requiresInternalContext,
            double hybridUtility
    ) {
        if (requiresDocumentEvidence || requiresInternalContext) {
            return PlannerMode.HYBRID;
        }

        return hybridUtility >= HYBRID_THRESHOLD
                ? PlannerMode.HYBRID
                : PlannerMode.GOAP;
    }

    public record IntentRequest(
            String missionId,
            String query,
            List<PreparedArtifact> artifacts,
            MissionPath preferredPath,
            Set<String> allowedTools,
            Set<String> allowedSourceServices,
            List<String> requiredOutputSections,
            boolean requireCitations,
            boolean requireHumanReview,
            boolean allowExternalSources,
            Map<String, Object> attributes
    ) {

        public IntentRequest {
            missionId = requireText(missionId, "missionId");
            query = requireText(query, "query");
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            preferredPath = Objects.requireNonNull(preferredPath, "preferredPath must not be null");
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            allowedSourceServices = allowedSourceServices == null ? Set.of() : Set.copyOf(allowedSourceServices);
            requiredOutputSections = requiredOutputSections == null ? List.of() : List.copyOf(requiredOutputSections);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
