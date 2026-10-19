package com.sparrowx.agentic.tools.document;

import com.sparrowx.agentic.adapters.document.DocumentClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder.Scope;
import com.sparrowx.document.proto.BuildDocumentEvidenceRequest;
import com.sparrowx.document.proto.DocumentScopeProto;
import com.sparrowx.document.proto.EvidenceBuildContextProto;
import com.sparrowx.document.proto.EvidenceBuildSpecProto;
import com.sparrowx.document.proto.EvidenceGoalProto;
import com.sparrowx.document.proto.EvidenceNodeTypeProto;
import com.sparrowx.document.proto.EvidenceRelationTypeProto;
import com.sparrowx.document.proto.RetrievalModeProto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DocumentEvidenceRequestBuilder {

    private final DocumentClientMapper clientMapper;

    public DocumentEvidenceRequestBuilder(DocumentClientMapper clientMapper) {
        this.clientMapper = Objects.requireNonNull(clientMapper, "clientMapper must not be null");
    }

    public BuildDocumentEvidenceRequest build(MissionContext context, BuildSpec spec) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        // A supplied exact scope is sufficient retrieval intent. Never turn
        // verified document IDs into guessed text-search terms.
        DocumentScopeProto documentScope = spec.scope().toProto();
        EvidenceBuildSpecProto evidenceSpec = EvidenceBuildSpecProto.newBuilder()
                .setGoal(spec.goal())
                .setCustomGoal(spec.customGoal())
                .addAllRequestedNodeTypes(spec.requestedNodeTypes())
                .addAllRequestedRelationTypes(spec.requestedRelationTypes())
                .setOutputSchemaRef(spec.outputSchemaRef())
                .setOutputSchemaVersion(spec.outputSchemaVersion())
                .putAllOptions(spec.options())
                .build();

        EvidenceBuildContextProto buildContext = EvidenceBuildContextProto.newBuilder()
                .setRetrievalHint(spec.retrievalHint())
                .addAllTopics(spec.topics())
                .addAllEntityNames(spec.entityNames())
                .addAllKeywords(spec.keywords())
                .putAllMetadataFilters(spec.metadataFilters())
                .setDebugTaskInstruction(spec.debugTaskInstruction())
                .build();

        return BuildDocumentEvidenceRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context, spec.requestId()))
                .setScope(documentScope)
                .setSpec(evidenceSpec)
                .setBuildContext(buildContext)
                .setRetrievalMode(spec.retrievalMode())
                .setLimit(spec.limit())
                .setIncludeExcerpts(spec.includeExcerpts())
                .setAllowClaimCache(spec.allowClaimCache())
                .setRequireVerification(spec.requireVerification())
                .build();
    }

    public record BuildSpec(
            String requestId,
            Scope scope,
            EvidenceGoalProto goal,
            String customGoal,
            List<EvidenceNodeTypeProto> requestedNodeTypes,
            List<EvidenceRelationTypeProto> requestedRelationTypes,
            String outputSchemaRef,
            String outputSchemaVersion,
            Map<String, String> options,
            String retrievalHint,
            List<String> topics,
            List<String> entityNames,
            List<String> keywords,
            Map<String, String> metadataFilters,
            String debugTaskInstruction,
            RetrievalModeProto retrievalMode,
            int limit,
            boolean includeExcerpts,
            boolean allowClaimCache,
            boolean requireVerification) {

        public BuildSpec {
            requestId = requireText(requestId, "requestId");
            scope = Objects.requireNonNull(scope, "scope must not be null");
            goal = Objects.requireNonNull(goal, "goal must not be null");
            if (goal == EvidenceGoalProto.EVIDENCE_GOAL_UNSPECIFIED
                    || goal == EvidenceGoalProto.UNRECOGNIZED) {
                throw new IllegalArgumentException("goal must be specified");
            }
            customGoal = customGoal == null ? "" : customGoal;
            if (goal == EvidenceGoalProto.EVIDENCE_GOAL_CUSTOM && customGoal.isBlank()) {
                throw new IllegalArgumentException("customGoal is required for a custom evidence goal");
            }
            requestedNodeTypes = requestedNodeTypes == null ? List.of() : List.copyOf(requestedNodeTypes);
            requestedRelationTypes = requestedRelationTypes == null
                    ? List.of()
                    : List.copyOf(requestedRelationTypes);
            outputSchemaRef = outputSchemaRef == null ? "" : outputSchemaRef;
            outputSchemaVersion = outputSchemaVersion == null ? "" : outputSchemaVersion;
            options = options == null ? Map.of() : Map.copyOf(options);
            retrievalHint = retrievalHint == null ? "" : retrievalHint;
            topics = topics == null ? List.of() : List.copyOf(topics);
            entityNames = entityNames == null ? List.of() : List.copyOf(entityNames);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);

            if (!scope.hasConstraints() && retrievalHint.isBlank()
                    && topics.isEmpty()
                    && entityNames.isEmpty()
                    && keywords.isEmpty()) {
                throw new IllegalArgumentException(
                        "at least one retrieval signal or document scope must be provided"
                );
            }
            metadataFilters = metadataFilters == null ? Map.of() : Map.copyOf(metadataFilters);
            debugTaskInstruction = debugTaskInstruction == null ? "" : debugTaskInstruction;
            retrievalMode = Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
            if (retrievalMode == RetrievalModeProto.RETRIEVAL_MODE_UNSPECIFIED
                    || retrievalMode == RetrievalModeProto.UNRECOGNIZED) {
                throw new IllegalArgumentException("retrievalMode must be specified");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
