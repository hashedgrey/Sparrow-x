package com.sparrowx.agentic.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.actions.document.BuildDocumentEvidenceAction;
import com.sparrowx.agentic.actions.document.VerifyEvidenceGraphAction;
import com.sparrowx.agentic.actions.synthesis.BuildCitationsAction;
import com.sparrowx.agentic.components.PlanningComponent.Observation;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.MissionPlan;
import com.sparrowx.agentic.planning.PlannedStep;
import com.sparrowx.agentic.planning.StepKind;
import com.sparrowx.agentic.steps.BuildDocumentEvidenceStep;
import com.sparrowx.agentic.steps.ResolveInternalContextStep;
import com.sparrowx.agentic.tools.document.DocumentEvidenceMapper;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder.BuildSpec;
import com.sparrowx.document.proto.DocumentEvidenceGraphProto;
import com.sparrowx.document.proto.VerificationStatusProto;
import org.springframework.stereotype.Component;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder.Scope;

import com.sparrowx.document.proto.EvidenceGoalProto;
import com.sparrowx.document.proto.RetrievalModeProto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the enterprise capability portion of an Embabel action.
 *
 * The MissionPlan argument maps are converted to the existing typed request
 * records. This keeps all gRPC, validation, policy and resilience code behind
 * the existing steps while removing Temporal from agentic step selection.
 */
@Component
public final class DefaultMissionEvidenceService
        implements MissionEvidenceService {

    private final BuildDocumentEvidenceStep documentStep;
    private final ResolveInternalContextStep internalStep;
    private final ObjectMapper objectMapper;
    private final VerifyEvidenceGraphAction verifyEvidenceAction;
    private final BuildCitationsAction citationsAction;
    private final DocumentEvidenceMapper documentEvidenceMapper;

    public DefaultMissionEvidenceService(
            BuildDocumentEvidenceStep documentStep,
            ResolveInternalContextStep internalStep,
            VerifyEvidenceGraphAction verifyEvidenceAction,
            BuildCitationsAction citationsAction,
            DocumentEvidenceMapper documentEvidenceMapper,
            ObjectMapper objectMapper
    ) {
        this.documentStep = Objects.requireNonNull(documentStep, "documentStep must not be null");
        this.internalStep = Objects.requireNonNull(internalStep, "internalStep must not be null");
        this.verifyEvidenceAction = Objects.requireNonNull(verifyEvidenceAction, "verifyEvidenceAction must not be null");
        this.citationsAction = Objects.requireNonNull(citationsAction, "citationsAction must not be null");
        this.documentEvidenceMapper = Objects.requireNonNull(documentEvidenceMapper, "documentEvidenceMapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public MissionEvidence collect(
            MissionRunInput input,
            MissionIntent intent,
            MissionPlan plan
    ) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(plan, "plan must not be null");

        if (!input.missionId().equals(intent.missionId())
                || !input.missionId().equals(plan.missionId())) {
            throw new IllegalArgumentException(
                    "input, intent and plan must belong to one mission"
            );
        }

        List<Observation> observations = new ArrayList<>();
        Map<String, EvidenceRef> collectedEvidence = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        Map<String, StepResult> completedResults = new LinkedHashMap<>();

        List<Citation> citations = List.of();
        Set<String> verifiedEvidenceIds = new LinkedHashSet<>();

        List<PlannedStep> pending = new ArrayList<>(plan.steps());

        while (!pending.isEmpty()) {
            PlannedStep step = pending.stream()
                    .filter(candidate ->
                            candidate.dependenciesSatisfied(completed))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no executable planned step; dependency cycle "
                                    + "or missing dependency"
                    ));

            PlannedStep executableStep = hydrateDependencyArguments(step, completedResults);
            StepResult result;

            if (shouldSkipUnresolvedGraphStep(executableStep)) {
                result = skippedGraphStep(executableStep);

            } else if (shouldSkipNoDocumentEvidenceStep(executableStep, completedResults)) {

                result = skippedNoDocumentEvidenceStep(executableStep);

            } else {
                validateGraphRootResolved(executableStep);

                result = executeStep(input, executableStep, completedResults);
            }

            observations.add(new Observation(
                    step.stepId(),
                    step.kind(),
                    result.summary(),
                    "",
                    result.attributes()
            ));

            result.evidenceRefs().forEach(ref -> collectedEvidence.put(ref.evidenceId(), ref));

            if (!result.citations().isEmpty()) {
                citations = result.citations();
            }

            verifiedEvidenceIds.addAll(result.verifiedEvidenceIds());
            warnings.addAll(result.warnings());
            completedResults.put(step.stepId(), result);

            completed.add(step.stepId());
            pending.remove(step);
        }

        List<EvidenceRef> evidenceRefs = List.copyOf(collectedEvidence.values());

        /*
         * Citation generation is deterministic post-processing.
         *
         * The planner decides which evidence capabilities to execute.
         * It should not be responsible for remembering to add a
         * BUILD_CITATIONS step merely to satisfy the mission result contract.
         *
         * If a plan explicitly executed BUILD_CITATIONS, keep those citations.
         * Otherwise, build them from the final collected evidence set whenever
         * citations are required by the mission.
         */
        if (citations.isEmpty() && !evidenceRefs.isEmpty() && input.request().constraints().requireCitations()) {

            BuildCitationsAction.Result citationResult =
                    citationsAction.execute(
                            new BuildCitationsAction.BuildSpec(evidenceRefs, excerptsByEvidenceId(evidenceRefs))
                    );

            citations = citationResult.citations();
            /*
             * Use the registry-normalized evidence returned by
             * BuildCitationsAction so citation.evidenceId always refers to
             * the exact EvidenceRef included in MissionEvidence.
             */
            evidenceRefs = citationResult.evidenceRefs();
        }

        return new MissionEvidence(
                observations,
                evidenceRefs,
                citations,
                Set.copyOf(verifiedEvidenceIds),
                warnings,
                excerptsByEvidenceId(evidenceRefs)
        );

    }

    private static boolean shouldSkipUnresolvedGraphStep(PlannedStep step) {
        return isGraphStep(step) && !step.dependencyStepIds().isEmpty() && !hasExecutableGraphRoot(step);
    }

    private static boolean isGraphStep(PlannedStep step) {
        return step.kind() == StepKind.READ_INTERNAL_COMPANY_GRAPH || step.kind() == StepKind.READ_LEARNING_GRAPH;
    }

    private static boolean hasExecutableGraphRoot(PlannedStep step) {
        Map<String, Object> arguments = step.arguments() == null ? Map.of() : step.arguments();

        if (hasResolvedGraphRoot(arguments)) {
            return true;
        }

        Object entityIds = firstPresent(arguments, null, "entityIds", "entity_ids");

        if (!(entityIds instanceof List<?> values) || values.isEmpty()) {
            return false;
        }

        Object first = values.getFirst();
        return first instanceof String entityId && !entityId.isBlank() && !isPlannerReference(entityId);
    }

    private static StepResult skippedGraphStep(PlannedStep step) {
        String warning = "Skipped graph step " + step.stepId() + " because its dependency did not resolve a unique graph root.";

        return new StepResult(
                warning,
                List.of(),
                List.of(warning),
                Map.of(
                        "capability", step.capability(),
                        "skipped", true,
                        "reason", "UNRESOLVED_GRAPH_ROOT"
                )
        );
    }

    private static boolean shouldSkipNoDocumentEvidenceStep(
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {

        if (step.kind()
                != StepKind.VERIFY_DOCUMENT_EVIDENCE
                && step.kind()
                != StepKind.BUILD_CITATIONS) {

            return false;
        }

        if (step.dependencyStepIds().isEmpty()) {
            return false;
        }

        return step.dependencyStepIds()
                .stream()
                .map(completedResults::get)
                .filter(Objects::nonNull)
                .anyMatch(result ->
                        Boolean.TRUE.equals(
                                result.attributes()
                                        .get(
                                                "noRelevantEvidence"
                                        )
                        )
                );
    }

    private static StepResult skippedNoDocumentEvidenceStep(
            PlannedStep step
    ) {

        String summary =
                switch (step.kind()) {

                    case VERIFY_DOCUMENT_EVIDENCE ->
                            "Skipped document verification because no relevant document evidence was found";

                    case BUILD_CITATIONS ->
                            "Skipped citation generation because no relevant document evidence was found";

                    default ->
                            "Skipped step because no relevant document evidence was found";
                };

        return new StepResult(
                summary,
                List.of(),
                List.of(),
                Map.of(
                        "capability",
                        step.capability(),

                        "skipped",
                        true,

                        "reason",
                        "NO_RELEVANT_DOCUMENT_EVIDENCE",

                        "noRelevantEvidence",
                        true
                )
        );
    }

    private static void validateGraphRootResolved(PlannedStep step) {
        if (!isGraphStep(step) || hasExecutableGraphRoot(step)) {
            return;
        }

        throw new IllegalStateException(
                "graph step " + step.stepId() + " has no uniquely resolved graph root; its dependency did not produce resolvedEntityId and resolvedNodeType"
        );
    }

    private StepResult executeStep(
            MissionRunInput input,
            PlannedStep step,
            Map<String, StepResult> completedResults

    ) {
        if (step.requiresHumanApproval()
                && input.approvedGateIds().isEmpty()) {
            throw new IllegalStateException(
                    "planned step requires an approved enterprise gate: "
                            + step.stepId()
            );
        }

        return switch (step.kind()) {
            case BUILD_DOCUMENT_EVIDENCE -> executeDocument(input, step);

            case VERIFY_DOCUMENT_EVIDENCE -> executeDocumentVerification(input, step, completedResults);

            case BUILD_CITATIONS -> executeCitations(step, completedResults);

            case SEARCH_INTERNAL_ENTITIES,
                 READ_INTERNAL_COMPANY_GRAPH,
                 READ_LEARNING_GRAPH -> executeInternal(input, step);

            case PREPARE_INPUT_ARTIFACTS,
                 UPLOAD_DOCUMENT,
                 GET_INGESTION_JOB -> deferred(
                    step,
                    "Input artifact preparation completed before agent start"
            );

            case REQUEST_HUMAN_APPROVAL -> deferred(
                    step,
                    "Enterprise approval was resolved before agent execution"
            );

            case APPLY_REDACTION,
                 CHECK_GROUNDING,
                 COMPOSE_ANSWER,
                 SEARCH_DOCUMENT_SPANS -> deferred(
                    step,
                    "Handled by the terminal synthesis/governance boundary"
            );
        };
    }

    private StepResult executeDocumentVerification(
            MissionRunInput input,
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {
        StepResult dependency = requireDocumentGraphDependency(step, completedResults);

        VerifyEvidenceGraphAction.Result result = verifyEvidenceAction.execute(
                input.request().context(),
                new VerifyEvidenceGraphAction.VerificationSpec(
                        effectId(input, step),
                        dependency.documentGraph(),
                        true,
                        true
                )
        );

        List<EvidenceRef> verifiedEvidence = documentEvidenceMapper.fromGraphEvidence(result.verifiedGraph());

        Set<String> verifiedEvidenceIds =
                result.supported() && result.verificationStatus() == VerificationStatusProto.VERIFICATION_STATUS_SUPPORTED
                        ? verifiedEvidence.stream().map(EvidenceRef::evidenceId).collect(java.util.stream.Collectors.toUnmodifiableSet())
                        : Set.of();

        return new StepResult(
                "Verified document evidence; status=" + result.verificationStatus().name(),
                verifiedEvidence,
                result.warnings(),
                Map.of(
                        "supported", result.supported(),
                        "verificationStatus", result.verificationStatus().name(),
                        "confidence", result.confidence(),
                        "coverageScore", result.coverageScore(),
                        "unsupportedNodeIds", result.unsupportedNodeIds(),
                        "unsupportedEdgeIds", result.unsupportedEdgeIds()
                ),
                result.verifiedGraph(),
                List.of(),
                verifiedEvidenceIds
        );
    }

    private static StepResult requireDocumentGraphDependency(
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {
        return step.dependencyStepIds().stream()
                .map(completedResults::get)
                .filter(Objects::nonNull)
                .filter(result -> result.documentGraph() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "step " + step.stepId() + " requires a document evidence graph dependency"
                ));
    }

    private StepResult executeCitations(
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {
        StepResult dependency = requireEvidenceDependency(step, completedResults);

        Map<String, String> excerpts = excerptsByEvidenceId(dependency.evidenceRefs());

        BuildCitationsAction.Result result = citationsAction.execute(
                new BuildCitationsAction.BuildSpec(
                        dependency.evidenceRefs(),
                        excerpts
                )
        );

        return new StepResult(
                "Built " + result.citations().size() + " citations",
                result.evidenceRefs(),
                List.of(),
                Map.of("citationCount", result.citations().size()),
                dependency.documentGraph(),
                result.citations(),
                dependency.verifiedEvidenceIds()
        );
    }

    private static StepResult requireEvidenceDependency(
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {
        return step.dependencyStepIds().stream()
                .map(completedResults::get)
                .filter(Objects::nonNull)
                .filter(result -> !result.evidenceRefs().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "step " + step.stepId() + " requires evidence from a dependency"
                ));
    }

    private static Map<String, String> excerptsByEvidenceId(List<EvidenceRef> evidenceRefs) {
        Map<String, String> result = new LinkedHashMap<>();

        for (EvidenceRef ref : evidenceRefs) {
            if (ref == null || ref.evidenceId() == null || ref.evidenceId().isBlank()) {
                continue;
            }

            Object excerpt = ref.attributes().get("excerpt");

            if (excerpt instanceof String text && !text.isBlank()) {
                result.put(ref.evidenceId(), text);
            }
        }

        return Map.copyOf(result);
    }

    private static PlannedStep hydrateDependencyArguments(
            PlannedStep step,
            Map<String, StepResult> completedResults
    ) {
        if (step.kind() != StepKind.READ_INTERNAL_COMPANY_GRAPH
                && step.kind() != StepKind.READ_LEARNING_GRAPH) {
            return step;
        }

        Map<String, Object> arguments =
                new LinkedHashMap<>(
                        step.arguments() == null
                                ? Map.of()
                                : step.arguments()
                );

        if (hasResolvedGraphRoot(arguments)) {
            return step;
        }

        for (String dependencyStepId : step.dependencyStepIds()) {

            StepResult dependencyResult = completedResults.get(dependencyStepId);

            if (dependencyResult == null) {
                continue;
            }

            Object resolvedEntityId = dependencyResult.attributes().get("resolvedEntityId");

            Object resolvedNodeType = dependencyResult.attributes().get("resolvedNodeType");

            if (!(resolvedEntityId instanceof String entityId)
                    || entityId.isBlank()) {
                continue;
            }

            if (resolvedNodeType == null) {
                continue;
            }

            arguments.put("rootEntityId", entityId);
            arguments.remove("entityId");
            arguments.remove("entity_id");
            arguments.remove("root_entity_id");

            arguments.put("rootNodeType", resolvedNodeType);

            arguments.remove("root_node_type");

            return new PlannedStep(
                    step.stepId(),
                    step.kind(),
                    step.dependencyStepIds(),
                    step.objective(),
                    step.expectedOutput(),
                    step.requiresHumanApproval(),
                    Map.copyOf(arguments),
                    step.attributes()
            );
        }

        return step;
    }

    private static boolean hasResolvedGraphRoot(
            Map<String, Object> arguments
    ) {
        String entityId = null;

        for (String key : List.of(
                "rootEntityId",
                "root_entity_id",
                "entityId",
                "entity_id"
        )) {
            Object value = arguments.get(key);

            if (value instanceof String text
                    && !text.isBlank()
                    && !isPlannerReference(text)) {
                entityId = text;
                break;
            }
        }

        if (entityId == null) {
            return false;
        }

        Object nodeType = firstPresent(
                arguments,
                null,
                "rootNodeType",
                "root_node_type"
        );

        if (nodeType == null) {
            return false;
        }

        if (nodeType instanceof String text) {
            return !text.isBlank()
                    && !isPlannerReference(text);
        }

        // Allows the actual InternalGraphNodeType enum too.
        return true;
    }

    private static boolean isPlannerReference(String value) {
        String text = value.trim();

        return text.contains("${")
                || text.contains("{{")
                || text.contains("}}")
                || text.startsWith("steps.")
                || text.contains(".output.");
    }

    private StepResult executeDocument(
            MissionRunInput input,
            PlannedStep step
    ) {

        BuildSpec spec = buildDocumentSpec(input, step);

        BuildDocumentEvidenceAction.Result result = documentStep.execute(input.request().context(), spec);

        /*
         * Retrieval may return fallback/source-supported chunks even when
         * none of them actually answer the requested evidence intent.
         *
         * Those chunks must not become mission evidence merely because
         * they exist in the document store.
         */
        boolean noRelevantEvidence = result.coverageScore() <= 0.0d || result.evidenceRefs().isEmpty();

        if (noRelevantEvidence) {

            return new StepResult(
                    "No relevant document evidence found for the requested intent",

                    /*
                     * Critical:
                     * do not propagate unrelated retrieved chunks.
                     */
                    List.of(),

                    /*
                     * Keep only a concise mission-level warning.
                     * Do not forward all retrieval/policy diagnostics
                     * into synthesis.
                     */
                    List.of(
                            "No relevant document evidence matched the requested intent."
                    ),

                    Map.of(
                            "coverageScore", result.coverageScore(),
                            "usedChunkRetrieval", result.usedChunkRetrieval(),
                            "usedClaimCache", result.usedClaimCache(),
                            "noRelevantEvidence", true
                    ),

                    /*
                     * Critical:
                     * do not expose the irrelevant evidence graph to a
                     * later verification step.
                     */
                    null,

                    List.of(),

                    Set.of()
            );
        }

        return new StepResult(
                "Built document evidence; coverage="
                        + result.coverageScore(),

                result.evidenceRefs(),

                result.warnings(),

                Map.of(
                        "coverageScore",
                        result.coverageScore(),

                        "usedChunkRetrieval",
                        result.usedChunkRetrieval(),

                        "usedClaimCache",
                        result.usedClaimCache(),

                        "noRelevantEvidence",
                        false
                ),

                result.graph(),

                List.of(),

                Set.of()
        );
    }

    private BuildSpec buildDocumentSpec(
            MissionRunInput input,
            PlannedStep step
    ) {
        Map<String, Object> arguments =
                step.arguments() == null
                        ? Map.of()
                        : step.arguments();

        EvidenceGoalProto goal =
                enumArgument(
                        arguments,
                        EvidenceGoalProto.class,
                        "goal",
                        "evidenceGoal",
                        "evidence_goal"
                );

        String customGoal = stringArgument(arguments, "customGoal", "custom_goal");

        if (goal == null
                || goal == EvidenceGoalProto.EVIDENCE_GOAL_UNSPECIFIED
                || goal == EvidenceGoalProto.UNRECOGNIZED) {

            goal = EvidenceGoalProto.EVIDENCE_GOAL_CUSTOM;
            customGoal = step.objective();
        }

        if (goal == EvidenceGoalProto.EVIDENCE_GOAL_CUSTOM
                && (customGoal == null || customGoal.isBlank())) {
            customGoal = step.objective();
        }
        Map<String, Object> spec = new LinkedHashMap<>();

        spec.put("requestId", effectId(input, step)
        );

        spec.put("goal", goal);
        spec.put("customGoal", customGoal);
        copyFirstPresent(arguments, spec, "requestedNodeTypes", "requestedNodeTypes", "requested_node_types");

        copyFirstPresent(arguments, spec, "requestedRelationTypes", "requestedRelationTypes", "requested_relation_types");

        copyFirstPresent(
                arguments,
                spec,
                "outputSchemaRef",
                "outputSchemaRef",
                "output_schema_ref"
        );

        copyFirstPresent(
                arguments,
                spec,
                "outputSchemaVersion",
                "outputSchemaVersion",
                "output_schema_version"
        );

        copyFirstPresent(
                arguments,
                spec,
                "options",
                "options"
        );

        String retrievalHint = stringArgument(arguments, "retrievalHint", "retrieval_hint", "query");

        if (retrievalHint.isBlank()) {
            retrievalHint = step.objective();
        }

        spec.put("retrievalHint", retrievalHint);

        copyFirstPresent(
                arguments,
                spec,
                "topics",
                "topics"
        );

        copyFirstPresent(
                arguments,
                spec,
                "entityNames",
                "entityNames",
                "entity_names"
        );

        copyFirstPresent(
                arguments,
                spec,
                "keywords",
                "keywords"
        );

        copyFirstPresent(
                arguments,
                spec,
                "metadataFilters",
                "metadataFilters",
                "metadata_filters"
        );

        copyFirstPresent(
                arguments,
                spec,
                "debugTaskInstruction",
                "debugTaskInstruction",
                "debug_task_instruction"
        );

        RetrievalModeProto retrievalMode = enumArgument(
                arguments,
                RetrievalModeProto.class,
                "retrievalMode",
                "retrieval_mode"
        );

        if (retrievalMode == null
                || retrievalMode == RetrievalModeProto.RETRIEVAL_MODE_UNSPECIFIED
                || retrievalMode == RetrievalModeProto.UNRECOGNIZED) {
            retrievalMode = RetrievalModeProto.RETRIEVAL_MODE_HYBRID;
        }

        spec.put("retrievalMode", retrievalMode);

        spec.put("limit", arguments.getOrDefault("limit", 20));

        spec.put(
                "includeExcerpts",
                firstPresent(
                        arguments,
                        true,
                        "includeExcerpts",
                        "include_excerpts"
                )
        );

        spec.put(
                "allowClaimCache",
                firstPresent(
                        arguments,
                        true,
                        "allowClaimCache",
                        "allow_claim_cache"
                )
        );

        spec.put(
                "requireVerification",
                firstPresent(
                        arguments,
                        false,
                        "requireVerification",
                        "require_verification"
                )
        );

        spec.put("scope", buildDocumentScope(arguments));

        return objectMapper.convertValue(spec, BuildSpec.class);
    }

    private static Scope buildDocumentScope(
            Map<String, Object> arguments
    ) {
        Object existingScope = arguments.get("scope");

        if (existingScope instanceof Scope scope) {
            return scope;
        }

        Map<String, Object> source =
                existingScope instanceof Map<?, ?> map
                        ? castStringObjectMap(map)
                        : arguments;

        List<String> documentIds = new ArrayList<>(
                stringList(source, "documentIds", "document_ids")
        );

        String documentId = stringArgument(source, "documentId", "document_id");

        if (!documentId.isBlank()) {
            documentIds.add(documentId);
        }

        return new Scope(
                List.copyOf(documentIds),
                stringList(source, "fileNames", "file_names"),
                stringList(source, "collectionIds", "collection_ids"),
                stringList(source, "tags"),
                stringMap(source, "metadataFilters", "metadata_filters")
        );
    }

    private StepResult executeInternal(
            MissionRunInput input,
            PlannedStep step
    ) {
        String operation = switch (step.kind()) {
            case SEARCH_INTERNAL_ENTITIES -> "SEARCH_ENTITIES";
            case READ_INTERNAL_COMPANY_GRAPH -> "READ_COMPANY_GRAPH";
            case READ_LEARNING_GRAPH -> "READ_LEARNING_GRAPH";
            default -> throw new IllegalArgumentException(
                    "not an internal step: " + step.kind()
            );
        };

        Map<String, Object> spec =
                step.kind() == StepKind.SEARCH_INTERNAL_ENTITIES
                        ? buildInternalSearchSpec(input, step)
                        : buildInternalGraphSpec(input, step);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("operation", operation);
        envelope.put(
                step.kind() == StepKind.SEARCH_INTERNAL_ENTITIES
                        ? "searchSpec"
                        : "graphSpec",
                spec
        );

        ResolveInternalContextStep.Request request =
                objectMapper.convertValue(
                        envelope,
                        ResolveInternalContextStep.Request.class
                );
        ResolveInternalContextStep.Result result =
                internalStep.execute(input.request().context(), request);

        return new StepResult(
                result.summary(),
                result.evidenceRefs(),
                result.warnings(),
                result.attributes()
        );
    }

    private static StepResult deferred(
            PlannedStep step,
            String summary
    ) {
        return new StepResult(
                summary,
                List.of(),
                List.of(),
                Map.of("capability", step.capability())
        );
    }

    private static Map<String, Object> withRequestId(
            Map<String, Object> source,
            String requestId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            result.putAll(source);
        }
        result.putIfAbsent("requestId", requestId);
        return Map.copyOf(result);
    }

    private static String effectId(
            MissionRunInput input,
            PlannedStep step
    ) {
        return input.request().context().requestId()
                + ":embabel:"
                + step.stepId();
    }

    private record StepResult(
            String summary,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            Map<String, Object> attributes,
            DocumentEvidenceGraphProto documentGraph,
            List<Citation> citations,
            Set<String> verifiedEvidenceIds
    ) {
        private StepResult(
                String summary,
                List<EvidenceRef> evidenceRefs,
                List<String> warnings,
                Map<String, Object> attributes
        ) {
            this(summary, evidenceRefs, warnings, attributes, null, List.of(), Set.of());
        }

        private StepResult {
            summary = summary == null ? "" : summary;
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            citations = citations == null ? List.of() : List.copyOf(citations);
            verifiedEvidenceIds = verifiedEvidenceIds == null ? Set.of() : Set.copyOf(verifiedEvidenceIds);
        }
    }

    private static Map<String, Object> buildInternalSearchSpec(
            MissionRunInput input,
            PlannedStep step
    ) {
        Map<String, Object> arguments =
                step.arguments() == null
                        ? Map.of()
                        : step.arguments();

        Map<String, Object> spec = new LinkedHashMap<>();

        spec.put(
                "requestId",
                effectId(input, step)
        );

        Object query = arguments.get("query");


        if (query instanceof String text && !text.isBlank()) {
            spec.put("query", text);
        } else {
            spec.put("query", step.objective());
        }

        copyIfPresent(arguments, spec, "allowedNodeTypes");
        copyIfPresent(arguments, spec, "rootEntityId");
        copyIfPresent(arguments, spec, "rootNodeType");
        copyIfPresent(arguments, spec, "filters");

        spec.put("depth", arguments.getOrDefault("depth", 0));
        spec.put("limit", arguments.getOrDefault("limit", 20));
        spec.put("includeFuzzyMatches", arguments.getOrDefault(
                        "includeFuzzyMatches",
                        true
                )
        );

        return spec;
    }

    private static Map<String, Object> buildInternalGraphSpec(
            MissionRunInput input,
            PlannedStep step
    ) {
        Map<String, Object> arguments =
                step.arguments() == null
                        ? Map.of()
                        : step.arguments();

        Map<String, Object> spec = new LinkedHashMap<>();

        spec.put("requestId", effectId(input, step)
        );

        copyFirstPresent(
                arguments,
                spec,
                "rootEntityId",
                "rootEntityId",
                "root_entity_id",
                "entityId",
                "entity_id"
        );



        if (!spec.containsKey("rootEntityId")) {
            Object entityIds = arguments.get("entity_ids");

            if (entityIds instanceof List<?> values
                    && !values.isEmpty()
                    && values.getFirst() instanceof String entityId
                    && !entityId.isBlank()) {
                spec.put("rootEntityId", entityId);
            }
        }

        copyFirstPresent(
                arguments, spec, "rootNodeType", "rootNodeType", "root_node_type"
        );

        Object rootEntityId = spec.get("rootEntityId");

        if (rootEntityId instanceof String text
                && text.isBlank()) {
            spec.remove("rootEntityId");
        }

        spec.put("depth", arguments.getOrDefault("depth", 1));
        spec.put("limit", arguments.getOrDefault("limit", 50));

        return Map.copyOf(spec);
    }

    private static void copyFirstPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String targetKey,
            String... sourceKeys
    ) {
        for (String sourceKey : sourceKeys) {Object value = source.get(sourceKey);
            if (value != null) {
                target.put(targetKey, value);
                return;
            }
        }
    }

    private static Object firstPresent(
            Map<String, Object> source,
            Object defaultValue,
            String... keys
    ) {
        for (String key : keys) {
            Object value = source.get(key);

            if (value != null) {
                return value;
            }
        }

        return defaultValue;
    }

    private static List<String> stringList(
            Map<String, Object> source,
            String... keys
    ) {
        Object value = firstPresent(
                source,
                List.of(),
                keys
        );

        if (!(value instanceof List<?> values)) {
            return List.of();
        }

        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private static Map<String, String> stringMap(
            Map<String, Object> source,
            String... keys
    ) {
        Object value = firstPresent(
                source,
                Map.of(),
                keys
        );

        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();

        values.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(
                        String.valueOf(key),
                        String.valueOf(item)
                );
            }
        });

        return Map.copyOf(result);
    }

    private static boolean hasNonBlankString(Map<String, Object> source, String... keys
    ) {
        for (String key : keys) {
            Object value = source.get(key);

            if (value instanceof String text
                    && !text.isBlank()) {
                return true;
            }
        }

        return false;
    }

    private static Map<String, Object> castStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();

        source.forEach((key, value) -> {
            if (key != null) {
                result.put(
                        String.valueOf(key),
                        value
                );
            }
        });

        return result;
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key
    ) {
        Object value = source.get(key);

        if (value != null) {
            target.put(key, value);
        }
    }

    private static String stringArgument(
            Map<String, Object> source,
            String... keys
    ) {
        Object value = firstPresent(
                source,
                null,
                keys
        );

        if (value == null) {
            return "";
        }

        if (value instanceof String text) {
            return text.trim();
        }

        return String.valueOf(value).trim();
    }

    private static <E extends Enum<E>> E enumArgument(
            Map<String, Object> source,
            Class<E> enumType,
            String... keys
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(enumType, "enumType must not be null");

        Object value = firstPresent(
                source,
                null,
                keys
        );

        if (value == null) {
            return null;
        }

        if (enumType.isInstance(value)) {
            return enumType.cast(value);
        }

        String name = String.valueOf(value).trim();

        if (name.isEmpty()) {
            return null;
        }

        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
