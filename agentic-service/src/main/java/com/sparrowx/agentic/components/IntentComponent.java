package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.mission.artifact.PreparedArtifact;
import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.PlannerMode;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the frozen semantic interpretation of a mission before execution.
 *
 * Bounded source decisions are evaluated by JEV. On abstention, a declared
 * conservative policy includes uncertain source requirements rather than
 * misrepresenting them as accepted JEV votes or failing mission submission.
 * Company-graph requirements imply internal context. Planner decisions are
 * JEV-first, with an explicitly marked HYBRID policy fallback on abstention.
 * An LLM never selects either source policy or planner mode.
 * Open-ended tool argument generation remains in dedicated resolvers.
 */
@Component
public final class IntentComponent {

    private static final Logger LOG = LoggerFactory.getLogger(IntentComponent.class);
    // Policy thresholds are configurable; they are not semantic keyword rules.
    @org.springframework.beans.factory.annotation.Value("${sparrowx.intent.source-threshold:0.55}")
    private double sourceThreshold = 0.55d;
    @org.springframework.beans.factory.annotation.Value("${sparrowx.intent.research-threshold:0.60}")
    private double researchThreshold = 0.60d;
    @org.springframework.beans.factory.annotation.Value("${sparrowx.intent.hybrid-threshold:0.60}")
    private double hybridThreshold = 0.60d;
    @org.springframework.beans.factory.annotation.Value("${sparrowx.intent.min-decision-confidence:0.75}")
    private double minDecisionConfidence = 0.75d;

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
            Structured entity/relationship sources answer identity and graph
            relationship questions. Document sources answer claims requiring
            source-document contents and document-to-document comparisons.
            Do not infer one source from topic words or entity names. An
            attachment alone does not require retrieval of its contents.
            A mission may require both graph relationships and document content.
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
        for (double threshold : new double[]{sourceThreshold, researchThreshold,
                hybridThreshold, minDecisionConfidence}) {
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
                throw new IllegalStateException("Invalid semantic policy threshold");
            }
        }

        // Keep source requirements and planner strategy independent. The old
        // six-field batch discarded *all* valid JEV votes when one field (often
        // hybrid planning on an investigation) abstained.
        Map<String, Object> context = state(request);
        Map<String, DecisionQuestion> definitions = questions();
        Map<String, DecisionQuestion> sourceQuestions = new LinkedHashMap<>(definitions);
        sourceQuestions.remove(HYBRID);
        SourceResolution source = sourceProbabilities(context, sourceQuestions, request.missionId());

        double documentUtility = source.observedUtility(DOCUMENT);
        double internalUtility = source.observedUtility(INTERNAL);
        double companyGraphUtility = source.observedUtility(COMPANY_GRAPH);
        double verificationUtility = source.observedUtility(VERIFICATION);
        double researchUtility = source.observedUtility(RESEARCH);

        Optional<DecisionResponse> plannerVote = decisions.tryBounded(
                context, Map.of(HYBRID, definitions.get(HYBRID)),
                minDecisionConfidence, request.missionId() + ":planner");
        // This is an explicit, conservative EXECUTION POLICY when JEV abstains,
        // not a claim that JEV selected HYBRID. It keeps a user request from
        // returning HTTP 500 solely because the optional planner preference is
        // uncertain. Authorization and evidence requirements remain unchanged.
        boolean plannerPolicyFallback = plannerVote.isEmpty();
        double hybridUtility = plannerVote.map(v -> boundedProbability(v, HYBRID))
                .orElse(Double.NaN);
        boolean requiresDocumentEvidence = source.required(DOCUMENT, sourceThreshold);
        boolean requiresCompanyGraph = source.required(COMPANY_GRAPH, sourceThreshold);

        // A graph traversal cannot occur without first resolving internal context.
        boolean requiresInternalContext =
                requiresCompanyGraph || source.required(INTERNAL, sourceThreshold);

        boolean requiresVerification = source.required(VERIFICATION, sourceThreshold);

        // On uncertainty prefer durable execution, unless the caller explicitly
        // selected a path. No fabricated research probability is recorded.
        MissionPath selectedPath = source.abstainedFields().contains(RESEARCH)
                ? (request.preferredPath() == MissionPath.UNSPECIFIED
                    ? MissionPath.RESEARCH : request.preferredPath())
                : selectPath(request, researchUtility, researchThreshold);
        // Source selection and planning strategy are independent decisions.
        // Typed multi-hop graph queries remain GOAP-eligible.
        PlannerMode plannerMode = plannerPolicyFallback
                ? PlannerMode.HYBRID : selectPlanner(hybridUtility, hybridThreshold);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("intentEngine", "jev-first");
        attributes.put("sourceDecisionProvider", source.abstainedFields().isEmpty() ? "jev" : "jev-with-policy");
        attributes.put("sourceAbstainedFields", String.join(",", source.abstainedFields().stream().sorted().toList()));
        attributes.put("sourceDerivedFields", String.join(",", source.derivedFields().stream().sorted().toList()));
        attributes.put("sourceAbstentionPolicy", "REQUIRE_UNKNOWN_SOURCES");
        attributes.put("plannerDecisionSource", plannerPolicyFallback
                ? "POLICY_ON_JEV_ABSTENTION" : "JEV");
        attributes.put("documentUtility", documentUtility);
        attributes.put("internalUtility", internalUtility);
        attributes.put("companyGraphUtility", companyGraphUtility);
        attributes.put("verificationUtility", verificationUtility);
        attributes.put("researchUtility", researchUtility);
        attributes.put("hybridUtility", Double.isFinite(hybridUtility) ? hybridUtility : -1.0d);
        if (plannerPolicyFallback) {
            LOG.warn("mission.planner.jev-abstained missionId={} policyFallback=HYBRID",
                    request.missionId());
        }
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

    /**
     * Accepted probabilities and policy decisions are kept separate. A -1
     * utility means JEV did not certify that field, never "JEV voted true".
     * This is deliberately not an LLM fallback or a lower JEV threshold.
     */
    private record SourceResolution(
            Map<String, Double> utilities,
            Set<String> abstainedFields,
            Set<String> derivedFields) {

        double observedUtility(String field) {
            return utilities.getOrDefault(field, -1.0d);
        }

        boolean required(String field, double threshold) {
            return derivedFields.contains(field) || abstainedFields.contains(field)
                    || observedUtility(field) >= threshold;
        }
    }

    private SourceResolution sourceProbabilities(
            Map<String, Object> context,
            Map<String, DecisionQuestion> questions,
            String missionId) {
        Optional<DecisionResponse> group = decisions.tryBounded(
                context, questions, minDecisionConfidence, missionId + ":sources");
        Map<String, Double> values = new LinkedHashMap<>();
        if (group.isPresent()) {
            for (String field : questions.keySet())
                values.put(field, boundedProbability(group.get(), field));
            return new SourceResolution(Map.copyOf(values), Set.of(), Set.of());
        }

        // Resolve other source votes independently. Handle COMPANY_GRAPH before
        // INTERNAL because requiring the graph logically requires internal context.
        // A confidently required graph makes a separate INTERNAL JEV vote redundant.
        Set<String> abstained = new LinkedHashSet<>();
        Set<String> derived = new LinkedHashSet<>();
        for (var entry : questions.entrySet()) {
            String field = entry.getKey();
            if (INTERNAL.equals(field)) continue;
            resolveSourceVote(context, missionId, entry.getKey(), entry.getValue(), values, abstained);
        }
        if (questions.containsKey(INTERNAL)) {
            boolean needsGraph = abstained.contains(COMPANY_GRAPH)
                    || values.getOrDefault(COMPANY_GRAPH, -1.0d) >= sourceThreshold;
            if (needsGraph) {
                derived.add(INTERNAL);
                values.put(INTERNAL, -1.0d);
            } else {
                resolveSourceVote(context, missionId, INTERNAL, questions.get(INTERNAL), values, abstained);
            }
        }
        if (!abstained.isEmpty()) {
            LOG.warn("mission.intent.source.jev-abstained missionId={} fields={} policy=REQUIRE_UNKNOWN_SOURCES",
                    missionId, abstained);
        }
        return new SourceResolution(Map.copyOf(values), Set.copyOf(abstained), Set.copyOf(derived));
    }

    private void resolveSourceVote(
            Map<String, Object> context,
            String missionId,
            String field,
            DecisionQuestion question,
            Map<String, Double> values,
            Set<String> abstained) {
        Optional<DecisionResponse> vote = decisions.tryBounded(context,
                Map.of(field, question), minDecisionConfidence, missionId + ":source:" + field);
        if (vote.isPresent()) {
            values.put(field, boundedProbability(vote.get(), field));
        } else {
            // Fail closed on evidence completeness, NOT by throwing HTTP 500.
            // The policy may retrieve more than required; it never removes an
            // uncertain source, verification step, or durable research need.
            abstained.add(field);
            values.put(field, -1.0d);
        }
    }

    private static Map<String, Object> state(IntentRequest request) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("application", "enterprise assistant");
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
                         "Does this mission require enterprise-internal data?",
                         "The answer depends on internal entities or enterprise context.",
                         "The mission does not require internal enterprise data."
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
                        "Should this mission use HYBRID instead of GOAP planning? "
                        + "Choose based on whether the evidence changes which capability to execute next, "
                        + "not merely the number of graph hops or joins inside one graph query.",
                        "The next capability may depend on discoveries, failures or competing evidence sources.",
                        "A predictable capability sequence can be planned by GOAP; "
                        + "multi-hop graph joins alone are not a reason to choose HYBRID."
                )
        );

        return Map.copyOf(questions);
    }

    private static double boundedProbability(DecisionResponse response, String field) {
        if (!(response.answers().get(field) instanceof NoulAnswer answer))
            throw new IllegalStateException("JEV did not resolve required intent decision: " + field);
        return answer.probability();
    }

    private static MissionPath selectPath(
            IntentRequest request,
            double researchUtility,
            double threshold
    ) {
        MissionPath preferred = request.preferredPath();
        if (preferred != MissionPath.UNSPECIFIED) {
            return preferred;
        }

        return researchUtility >= threshold
                ? MissionPath.RESEARCH
                : MissionPath.FAST;
    }

    static PlannerMode selectPlanner(double adaptivePlanningProbability) {
        return selectPlanner(adaptivePlanningProbability, 0.60d);
    }

    private static PlannerMode selectPlanner(double adaptivePlanningProbability, double threshold) {
        if (!Double.isFinite(adaptivePlanningProbability)
                || adaptivePlanningProbability < 0.0d
                || adaptivePlanningProbability > 1.0d) {
            throw new IllegalArgumentException("Invalid adaptive planning probability");
        }
        return adaptivePlanningProbability >= threshold
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
