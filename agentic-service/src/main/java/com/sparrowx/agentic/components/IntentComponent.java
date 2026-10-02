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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the frozen semantic interpretation of a mission before Embabel
 * execution begins.
 *
 * Bounded classification defaults to System-1.
 *
 * The structured LLM is used only for:
 *
 * - uncertain bounded semantic decisions;
 * - open-ended extraction that cannot be expressed reliably as a bounded
 *   System-1 question.
 */
@Component
public final class IntentComponent {

    private static final double YES_THRESHOLD =
            0.55d;

    private static final double RESEARCH_THRESHOLD =
            0.60d;

    private static final double HYBRID_THRESHOLD =
            0.60d;

    /*
     * Values close to 0.5 are ambiguous.
     *
     * For a noul probability:
     *
     * 0.95 -> confidence .95
     * 0.05 -> confidence .95
     * 0.52 -> confidence .52
     */
    private static final double MIN_SYSTEM_ONE_CONFIDENCE =
            0.75d;

    private static final String DOCUMENT =
            "requires_document_evidence";

    private static final String INTERNAL =
            "requires_internal_context";

    private static final String VERIFICATION =
            "requires_verification";

    private static final String RESEARCH =
            "requires_research";

    private static final String HYBRID =
            "requires_hybrid_planning";

    private static final String TARGET_ENTITIES =
            "target_entities";

    private static final Pattern FILE_REFERENCE =
            Pattern.compile(
                    "(?i)\\b[\\p{L}\\p{N}_-]+"
                            + "(?:\\.[\\p{L}\\p{N}_-]+)*"
                            + "\\.(?:pdf|docx?|md|txt|html?)\\b"
            );

    private static final Pattern EXPLICIT_DOCUMENT_REQUEST =
            Pattern.compile(
                    "(?i)\\b("
                            + "document|paper|article|publication|report|"
                            + "pdf|file"
                            + ")\\b"
            );

    private static final Pattern EXPLICIT_INTERNAL_REQUEST =
            Pattern.compile(
                    "(?i)\\b("
                            + "service|team|engineer|owner|ownership|"
                            + "repository|repo|runbook|onboarding|"
                            + "internal system|internal service"
                            + ")\\b"
            );

    private static final Pattern DOCUMENT_TITLE =
            Pattern.compile("(?i)\\b(?:document|paper|article|publication|report)\\s+(.+?)\\s*$");

    private final SemanticDecisionService decisions;

    public IntentComponent(
            SemanticDecisionService decisions
    ) {
        this.decisions =
                Objects.requireNonNull(
                        decisions,
                        "decisions must not be null"
                );
    }

    public MissionIntent interpret(
            IntentRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        Set<String> deterministicTargets =
                targetEntities(
                        request
                );

        boolean explicitDocumentRequest =
                !request.artifacts()
                        .isEmpty()
                        || explicitDocumentRequest(
                        request.query()
                );

        boolean explicitInternalRequest =
                explicitInternalRequest(
                        request.query()
                );

        /*
         * Publication titles such as:
         *
         * "Explain the document A Wandering Mind Is an Unhappy Mind"
         *
         * are not filenames and cannot be extracted by FILE_REFERENCE.
         *
         * Only those cases require open-ended target extraction.
         */
        Set<String> llmOnlyFields =
                explicitDocumentRequest
                        && deterministicTargets.isEmpty()
                        && request.artifacts()
                        .isEmpty()
                        ? Set.of(
                        TARGET_ENTITIES
                )
                        : Set.of();

        double minimumConfidence =
                explicitDocumentRequest && !deterministicTargets.isEmpty()
                        && !explicitInternalRequest ? 0.50d : MIN_SYSTEM_ONE_CONFIDENCE;
        Result semantic =
                decisions.evaluate(
                        new SemanticDecisionService.Request(
                                state(request),
                                questions(),
                                minimumConfidence,
                                llmOnlyFields,
                                escalation(request)
                        )
                );

        double documentUtility =
                semantic.probability(
                        DOCUMENT
                );

        double internalUtility =
                semantic.probability(
                        INTERNAL
                );

        double verificationUtility =
                semantic.probability(
                        VERIFICATION
                );

        double researchUtility =
                semantic.probability(
                        RESEARCH
                );

        double hybridUtility =
                semantic.probability(
                        HYBRID
                );

        /*
         * Explicit request structure can only strengthen hard requirements.
         *
         * System-1/LLM cannot override an uploaded artifact or explicit
         * document request back to false.
         */
        boolean requiresDocumentEvidence =
                explicitDocumentRequest
                        || documentUtility
                        >= YES_THRESHOLD;

        /*
         * A document title should not accidentally trigger enterprise entity
         * lookup just because it resembles a named object.
         */
        boolean requiresInternalContext =
                (!explicitDocumentRequest
                        || explicitInternalRequest)
                        && internalUtility
                        >= YES_THRESHOLD;

        boolean requiresVerification =
                verificationUtility
                        >= YES_THRESHOLD;

        MissionPath selectedPath =
                selectPath(
                        request,
                        researchUtility
                );

        PlannerMode plannerMode =
                selectPlanner(
                        requiresDocumentEvidence,
                        requiresInternalContext,
                        hybridUtility
                );

        Set<String> targets =
                mergeTargets(
                        deterministicTargets,
                        semantic.stringList(
                                TARGET_ENTITIES
                        )
                );

        Map<String, Object> attributes =
                new LinkedHashMap<>(
                        semantic.metadata()
                );

        attributes.put(
                "intentEngine",
                "semantic-decision-service"
        );

        attributes.put(
                "documentUtility",
                documentUtility
        );

        attributes.put(
                "internalUtility",
                internalUtility
        );

        attributes.put(
                "verificationUtility",
                verificationUtility
        );

        attributes.put(
                "researchUtility",
                researchUtility
        );

        attributes.put(
                "hybridUtility",
                hybridUtility
        );

        attributes.put(
                "preparedArtifactCount",
                request.artifacts()
                        .size()
        );

        attributes.put(
                "explicitDocumentRequest",
                explicitDocumentRequest
        );

        attributes.put(
                "explicitInternalRequest",
                explicitInternalRequest
        );

        attributes.put(
                "resolvedTargetCount",
                targets.size()
        );

        return new MissionIntent(
                request.missionId(),

                request.query(),

                selectedPath,

                plannerMode,

                targets,

                Set.of(),

                request.requiredOutputSections(),

                requiresDocumentEvidence,

                requiresInternalContext,

                request.requireHumanReview(),

                request.requireCitations(),

                requiresVerification,

                request.allowExternalSources(),

                request.allowedTools(),

                request.allowedSourceServices(),

                Map.copyOf(
                        attributes
                )
        );
    }

    /*
     * ------------------------------------------------------------------
     * SYSTEM-1 STATE
     * ------------------------------------------------------------------
     */

    private static Map<String, Object> state(
            IntentRequest request
    ) {
        Map<String, Object> state =
                new LinkedHashMap<>();

        state.put(
                "application",
                "SparrowX enterprise engineering assistant"
        );

        state.put(
                "mission",
                request.query()
        );

        state.put(
                "preferredPath",
                request.preferredPath()
                        .name()
        );

        state.put(
                "artifacts",
                request.artifacts()
                        .stream()
                        .map(
                                IntentComponent::artifactState
                        )
                        .toList()
        );

        state.put(
                "allowedTools",
                request.allowedTools()
        );

        state.put(
                "allowedSourceServices",
                request.allowedSourceServices()
        );

        state.put(
                "requiredOutputSections",
                request.requiredOutputSections()
        );

        state.put(
                "requireCitations",
                request.requireCitations()
        );

        state.put(
                "requireHumanReview",
                request.requireHumanReview()
        );

        state.put(
                "allowExternalSources",
                request.allowExternalSources()
        );

        state.put(
                "attributes",
                request.attributes()
        );

        return Map.copyOf(
                state
        );
    }

    private static Map<String, Object> artifactState(
            PreparedArtifact artifact
    ) {
        Map<String, Object> state =
                new LinkedHashMap<>();

        state.put(
                "filename",
                safe(
                        artifact.filename()
                )
        );

        state.put(
                "documentId",
                safe(
                        artifact.documentId()
                )
        );

        return Map.copyOf(
                state
        );
    }

    /*
     * ------------------------------------------------------------------
     * BOUNDED QUESTIONS
     * ------------------------------------------------------------------
     */

    private static Map<String, DecisionQuestion> questions() {

        Map<String, DecisionQuestion> questions =
                new LinkedHashMap<>();

        questions.put(
                DOCUMENT,

                new NoulQuestion(
                        """
                        Would SparrowX's document evidence capability materially
                        help answer this mission?
                        """,

                        """
                        The mission explicitly asks about a document, article,
                        paper, publication or report, or depends on facts,
                        explanations, procedures or claims contained in
                        document content.
                        """,

                        """
                        The mission can be answered without consulting
                        document content.
                        """
                )
        );

        questions.put(
                INTERNAL,

                new NoulQuestion(
                        """
                        Would SparrowX's internal entity capability materially
                        help answer this mission?
                        """,

                        """
                        The mission depends on enterprise services, teams,
                        engineers, ownership, repositories, runbooks,
                        onboarding or internal relationships.
                        """,

                        """
                        The mission does not require SparrowX enterprise data.
                        A publication or document title alone is not an
                        enterprise entity.
                        """
                )
        );

        questions.put(
                VERIFICATION,

                new NoulQuestion(
                        """
                        Does this mission require explicit verification of
                        retrieved evidence before terminal synthesis?
                        """,

                        """
                        The mission asks for validation, confirmation,
                        cross-checking, consistency checking or otherwise
                        requires stronger verification than normal retrieval.
                        """,

                        """
                        Ordinary grounded retrieval and synthesis are
                        sufficient.
                        """
                )
        );

        questions.put(
                RESEARCH,

                new NoulQuestion(
                        """
                        Does this mission require durable multi-step research
                        rather than a short execution?
                        """,

                        """
                        The mission requires substantial investigation,
                        comparison, multiple pieces of evidence or a longer
                        execution where meaningful intermediate progress
                        should be recoverable.
                        """,

                        """
                        The mission is short enough that lifecycle and final
                        result durability are sufficient.
                        """
                )
        );

        questions.put(
                HYBRID,

                new NoulQuestion(
                        """
                        Does this mission require adaptive action selection,
                        where useful follow-up work may only become known
                        after earlier evidence is retrieved?
                        """,

                        """
                        New evidence may reveal entities, relationships,
                        information gaps or useful follow-up actions and the
                        execution should reconsider which SparrowX action is
                        most useful next.
                        """,

                        """
                        The mission follows a sufficiently predictable typed
                        action path with known preconditions.
                        """
                )
        );

        return Map.copyOf(
                questions
        );
    }

    /*
     * ------------------------------------------------------------------
     * LLM ESCALATION
     * ------------------------------------------------------------------
     */

    private static Escalation escalation(
            IntentRequest request
    ) {
        return new Escalation(
                request.missionId(),

                "resolve-mission-semantics",

                request.missionId()
                        + ":semantic-intent:v1",

                """
                Resolve only the semantic fields requested.
    
                For bounded requires_* fields:
                return a probability from 0.0 to 1.0 indicating whether that
                SparrowX capability or behavior is required.
    
                For target_entities:
                extract explicit document names, publication titles, files,
                enterprise entities or other named targets the mission asks
                SparrowX to operate on.
    
                Do not answer the mission itself.
                """,

                escalationSchema(),
                800,
                0.0d,
                Map.of(
                        "component", "intent",
                        "purpose", "semantic-escalation"
                )
        );
    }

    private static Map<String, Object> escalationSchema() {

        Map<String, Object> probability =
                Map.of(
                        "type",
                        "number",

                        "minimum",
                        0.0,

                        "maximum",
                        1.0
                );

        Map<String, Object> properties =
                new LinkedHashMap<>();

        properties.put(
                DOCUMENT,
                probability
        );

        properties.put(
                INTERNAL,
                probability
        );

        properties.put(
                VERIFICATION,
                probability
        );

        properties.put(
                RESEARCH,
                probability
        );

        properties.put(
                HYBRID,
                probability
        );

        properties.put(
                TARGET_ENTITIES,
                Map.of(
                        "type",
                        "array",

                        "items",
                        Map.of(
                                "type",
                                "string"
                        )
                )
        );

        return Map.of(
                "type",
                "object",

                "properties",
                Map.copyOf(
                        properties
                ),

                "additionalProperties",
                false
        );
    }

    /*
     * ------------------------------------------------------------------
     * EXECUTION POLICY
     * ------------------------------------------------------------------
     */

    private static MissionPath selectPath(
            IntentRequest request,
            double researchUtility
    ) {
        MissionPath preferred =
                request.preferredPath();

        if (preferred
                != MissionPath.UNSPECIFIED) {

            return preferred;
        }

        return researchUtility
                >= RESEARCH_THRESHOLD
                ? MissionPath.RESEARCH
                : MissionPath.FAST;
    }

    private static PlannerMode selectPlanner(
            boolean requiresDocumentEvidence,
            boolean requiresInternalContext,
            double hybridUtility
    ) {

        /*
         * Current evidence actions do not yet encode retrieval requirements
         * as typed GOAP preconditions/effects.
         *
         * Until they do, retrieval missions use Hybrid so terminal synthesis
         * cannot bypass required evidence work.
         */
        if (requiresDocumentEvidence
                || requiresInternalContext) {

            return PlannerMode.HYBRID;
        }

        if (hybridUtility
                >= HYBRID_THRESHOLD) {

            return PlannerMode.HYBRID;
        }

        return PlannerMode.GOAP;
    }

    /*
     * ------------------------------------------------------------------
     * TARGET EXTRACTION
     * ------------------------------------------------------------------
     */

    private static Set<String> targetEntities(
            IntentRequest request
    ) {
        Set<String> targets =
                new LinkedHashSet<>();

        for (PreparedArtifact artifact :
                request.artifacts()) {

            if (artifact.filename() != null
                    && !artifact.filename()
                    .isBlank()) {

                targets.add(
                        artifact.filename()
                                .trim()
                );
            }
        }

        Matcher matcher =
                FILE_REFERENCE.matcher(
                        request.query()
                );

        while (matcher.find()) {

            String value =
                    matcher.group();

            if (value != null
                    && !value.isBlank()) {

                targets.add(
                        value.trim()
                );
            }
        }
        Matcher titleMatcher =
                DOCUMENT_TITLE.matcher(
                        request.query()
                );

        if (titleMatcher.find()) {
            String title =
                    titleMatcher.group(1);

            if (title != null
                    && !title.isBlank()) {

                targets.add(
                        title.trim()
                );
            }
        }

        return Set.copyOf(
                targets
        );
    }

    private static Set<String> mergeTargets(
            Set<String> deterministic,
            List<String> semantic
    ) {
        Set<String> result =
                new LinkedHashSet<>();

        if (deterministic != null) {
            result.addAll(
                    deterministic
            );
        }

        if (semantic != null) {
            semantic.stream()
                    .filter(
                            Objects::nonNull
                    )
                    .map(
                            String::trim
                    )
                    .filter(value ->
                            !value.isBlank()
                    )
                    .forEach(
                            result::add
                    );
        }

        return Set.copyOf(
                result
        );
    }

    /*
     * ------------------------------------------------------------------
     * REQUEST
     * ------------------------------------------------------------------
     */

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
            missionId =
                    requireText(
                            missionId,
                            "missionId"
                    );

            query =
                    requireText(
                            query,
                            "query"
                    );

            artifacts =
                    artifacts == null
                            ? List.of()
                            : List.copyOf(
                            artifacts
                    );

            preferredPath =
                    Objects.requireNonNull(
                            preferredPath,
                            "preferredPath must not be null"
                    );

            allowedTools =
                    allowedTools == null
                            ? Set.of()
                            : Set.copyOf(
                            allowedTools
                    );

            allowedSourceServices =
                    allowedSourceServices == null
                            ? Set.of()
                            : Set.copyOf(
                            allowedSourceServices
                    );

            requiredOutputSections =
                    requiredOutputSections == null
                            ? List.of()
                            : List.copyOf(
                            requiredOutputSections
                    );

            attributes =
                    attributes == null
                            ? Map.of()
                            : Map.copyOf(
                            attributes
                    );
        }
    }

    private static boolean explicitDocumentRequest(
            String query
    ) {
        return query != null
                && EXPLICIT_DOCUMENT_REQUEST
                .matcher(
                        query
                )
                .find();
    }

    private static boolean explicitInternalRequest(
            String query
    ) {
        return query != null
                && EXPLICIT_INTERNAL_REQUEST
                .matcher(
                        query
                )
                .find();
    }

    private static String safe(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }
}