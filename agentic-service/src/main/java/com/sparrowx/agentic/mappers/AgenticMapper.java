package com.sparrowx.agentic.mappers;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.sparrowx.agentic.features.approvemissiongate.ApproveMissionGateCommand;
import com.sparrowx.agentic.features.approvemissiongate.ApproveMissionGateResult;
import com.sparrowx.agentic.features.cancelmission.CancelMissionCommand;
import com.sparrowx.agentic.features.cancelmission.CancelMissionResult;
import com.sparrowx.agentic.features.getmissionresult.GetMissionResultQuery;
import com.sparrowx.agentic.features.getmissionresult.GetMissionResultView;
import com.sparrowx.agentic.features.rejectmissiongate.RejectMissionGateCommand;
import com.sparrowx.agentic.features.rejectmissiongate.RejectMissionGateResult;
import com.sparrowx.agentic.features.streammissionprogress.StreamMissionProgressQuery;
import com.sparrowx.agentic.features.submitmission.SubmitMissionCommand;
import com.sparrowx.agentic.features.submitmission.SubmitMissionResult;
import com.sparrowx.agentic.governance.model.GovernanceDecision;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.*;
import com.sparrowx.agentic.mission.model.Finding;
import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.mission.model.MissionResult;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.mission.model.Recommendation;
import com.sparrowx.agentic.mission.model.ResultSection;
import com.sparrowx.agentic.proto.*;
import com.sparrowx.agentic.proto.InputArtifact.ContentCase;
import com.sparrowx.agentic.util.ProtoTimestamps;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.Objects;

@Component
public final class AgenticMapper {

    private final RequestContextGrpcMapper requestContextGrpcMapper;
    private final MissionEventGrpcMapper missionEventGrpcMapper;
    private final ErrorGrpcMapper errorGrpcMapper;

    public AgenticMapper(
            RequestContextGrpcMapper requestContextGrpcMapper,
            MissionEventGrpcMapper missionEventGrpcMapper,
            ErrorGrpcMapper errorGrpcMapper
    ) {
        this.requestContextGrpcMapper = Objects.requireNonNull(
                requestContextGrpcMapper,
                "requestContextGrpcMapper must not be null"
        );
        this.missionEventGrpcMapper = Objects.requireNonNull(
                missionEventGrpcMapper,
                "missionEventGrpcMapper must not be null"
        );
        this.errorGrpcMapper = Objects.requireNonNull(
                errorGrpcMapper,
                "errorGrpcMapper must not be null"
        );
    }

    public SubmitMissionCommand toSubmitMissionCommand(
            SubmitMissionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        RequestContextGrpcMapper.RequestContextView context =
                requestContextGrpcMapper.toView(
                        request.getContext()
                );

        return new SubmitMissionCommand(
                context.requestId(),
                context.tenantId(),
                context.userId(),
                context.username(),
                context.projectId(),
                context.teamId(),
                context.traceId(),
                context.callerService(),
                context.sessionId(),
                context.conversationId(),
                context.clientChannel(),
                request.getQuery(),
                request.getInputArtifactsList()
                        .stream()
                        .map(this::toInputArtifactInput)
                        .toList(),
                toConstraintsInput(request.getConstraints()),
                toBudgetInput(request.getBudget()),
                context.metadata()
        );
    }

    public SubmitMissionResponse toSubmitMissionResponse(
            SubmitMissionResult result
    ) {
        Objects.requireNonNull(result, "result must not be null");

        return SubmitMissionResponse.newBuilder()
                .setMissionId(result.missionId())
                .setStatus(toProtoStatus(result.status()))
                .setSelectedPath(toProtoPath(result.selectedPath()))
                .setSubmittedAt(
                        ProtoTimestamps.toProto(result.submittedAt())
                )
                .build();
    }

    public GetMissionResultQuery toGetMissionResultQuery(
            GetMissionResultRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        RequestContextGrpcMapper.RequestContextView context =
                requestContextGrpcMapper.toView(
                        request.getContext()
                );

        return new GetMissionResultQuery(
                context.requestId(),
                context.tenantId(),
                context.userId(),
                request.getMissionId()
        );
    }

    public MissionResultResponse toMissionResultResponse(
            GetMissionResultView view
    ) {
        Objects.requireNonNull(view, "view must not be null");

        MissionResultResponse.Builder builder =
                MissionResultResponse.newBuilder()
                        .setMissionId(view.missionId())
                        .setStatus(toProtoStatus(view.status()));

        if (view.result() != null) {
            builder.setResult(
                    toProtoMissionResult(view.result())
            );
        }

        if (view.error() != null) {
            builder.setError(
                    errorGrpcMapper.toProto(view.error())
            );
        }

        if (view.waitState() != null) {
            builder.setWaitState(
                    toProtoHumanGate(view.waitState())
            );
        }

        if (view.completedAt() != null) {
            builder.setCompletedAt(
                    ProtoTimestamps.toProto(view.completedAt())
            );
        }

        return builder.build();
    }

    public StreamMissionProgressQuery
    toStreamMissionProgressQuery(
            StreamMissionProgressRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        RequestContextGrpcMapper.RequestContextView context =
                requestContextGrpcMapper.toView(
                        request.getContext()
                );

        return new StreamMissionProgressQuery(
                context.requestId(),
                context.tenantId(),
                context.userId(),
                request.getMissionId(),
                request.getResumeToken()
        );
    }

    public HumanGate toProtoHumanGate(
            HumanGateState state
    ) {
        Objects.requireNonNull(state, "state must not be null");

        HumanGate.Builder builder =
                HumanGate.newBuilder()
                        .setGateId(state.gateId())
                        .setMissionId(state.missionId())
                        .setTitle(state.title())
                        .setReason(state.reason())
                        .addAllRequiredReviewerRoles(
                                state.requiredReviewerRoles()
                        )
                        .setReviewPayload(
                                toStruct(state.reviewPayload())
                        );

        if (state.createdAt() != null) {
            builder.setCreatedAt(
                    ProtoTimestamps.toProto(state.createdAt())
            );
        }

        if (state.expiresAt() != null) {
            builder.setExpiresAt(
                    ProtoTimestamps.toProto(state.expiresAt())
            );
        }

        return builder.build();
    }

    public CancelMissionCommand toCancelMissionCommand(
            CancelMissionRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return new CancelMissionCommand(
                requestContextGrpcMapper.toDomain(
                        request.getContext()
                ),
                request.getMissionId(),
                request.getReason()
        );
    }

    public CancelMissionResponse toCancelMissionResponse(
            CancelMissionResult result
    ) {
        Objects.requireNonNull(result, "result must not be null");

        return CancelMissionResponse.newBuilder()
                .setMissionId(result.missionId())
                .setStatus(toProtoStatus(result.status()))
                .setCancelledAt(
                        ProtoTimestamps.toProto(result.cancelledAt())
                )
                .build();
    }

    public ApproveMissionGateCommand
    toApproveMissionGateCommand(
            ApproveMissionGateRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return new ApproveMissionGateCommand(
                requestContextGrpcMapper.toDomain(
                        request.getContext()
                ),
                request.getMissionId(),
                request.getGateId(),
                request.getNote()
        );
    }

    public ApproveMissionGateResponse
    toApproveMissionGateResponse(
            ApproveMissionGateResult result
    ) {
        Objects.requireNonNull(result, "result must not be null");

        return ApproveMissionGateResponse.newBuilder()
                .setMissionId(result.missionId())
                .setStatus(toProtoStatus(result.status()))
                .setApprovedAt(
                        ProtoTimestamps.toProto(result.approvedAt())
                )
                .build();
    }

    public RejectMissionGateCommand
    toRejectMissionGateCommand(
            RejectMissionGateRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return new RejectMissionGateCommand(
                requestContextGrpcMapper.toDomain(
                        request.getContext()
                ),
                request.getMissionId(),
                request.getGateId(),
                request.getReason()
        );
    }

    public RejectMissionGateResponse
    toRejectMissionGateResponse(
            RejectMissionGateResult result
    ) {
        Objects.requireNonNull(result, "result must not be null");

        return RejectMissionGateResponse.newBuilder()
                .setMissionId(result.missionId())
                .setStatus(toProtoStatus(result.status()))
                .setRejectedAt(
                        ProtoTimestamps.toProto(result.rejectedAt())
                )
                .build();
    }

    public com.sparrowx.agentic.proto.MissionResult
    toProtoMissionResult(MissionResult result) {
        Objects.requireNonNull(result, "result must not be null");

        var builder =
                com.sparrowx.agentic.proto.MissionResult
                        .newBuilder()
                        .setMissionId(result.missionId())
                        .setExecutiveSummary(
                                result.executiveSummary()
                        )
                        .setFinalAnswer(result.finalAnswer())
                        .setStructuredOutput(
                                toStruct(result.structuredOutput())
                        )
                        .setDebugSummary(
                                toStruct(result.debugSummary())
                        );

        result.sections().stream()
                .map(this::toProtoResultSection)
                .forEach(builder::addSections);

        result.findings().stream()
                .map(this::toProtoFinding)
                .forEach(builder::addFindings);

        result.recommendations().stream()
                .map(this::toProtoRecommendation)
                .forEach(builder::addRecommendations);

        result.evidenceRefs().stream()
                .map(this::toProtoEvidenceRef)
                .forEach(builder::addEvidenceRefs);

        result.citations().stream()
                .map(this::toProtoCitation)
                .forEach(builder::addCitations);

        result.governanceDecisions().stream()
                .map(this::toProtoGovernanceDecision)
                .forEach(builder::addGovernanceDecisions);

        return builder.build();
    }

    public com.sparrowx.agentic.proto.ResultSection
    toProtoResultSection(ResultSection section) {
        Objects.requireNonNull(section, "section must not be null");

        return com.sparrowx.agentic.proto.ResultSection
                .newBuilder()
                .setSectionId(section.sectionId())
                .setTitle(section.title())
                .setBody(section.body())
                .setOrder(section.order())
                .addAllFindingIds(section.findingIds())
                .addAllRecommendationIds(
                        section.recommendationIds()
                )
                .addAllEvidenceIds(section.evidenceIds())
                .setAttributes(toStruct(section.attributes()))
                .build();
    }

    public com.sparrowx.agentic.proto.Finding
    toProtoFinding(Finding finding) {
        Objects.requireNonNull(finding, "finding must not be null");

        return com.sparrowx.agentic.proto.Finding
                .newBuilder()
                .setFindingId(finding.findingId())
                .setTitle(finding.title())
                .setSummary(finding.summary())
                .setType(toProtoFindingType(finding.type()))
                .setConfidenceScore(finding.confidenceScore())
                .setSeverityScore(finding.severityScore())
                .setPriorityScore(finding.priorityScore())
                .addAllRelatedFindingIds(
                        finding.relatedFindingIds()
                )
                .addAllEvidenceIds(finding.evidenceIds())
                .setAttributes(toStruct(finding.attributes()))
                .build();
    }

    public com.sparrowx.agentic.proto.Recommendation
    toProtoRecommendation(Recommendation recommendation) {
        Objects.requireNonNull(
                recommendation,
                "recommendation must not be null"
        );

        return com.sparrowx.agentic.proto.Recommendation
                .newBuilder()
                .setRecommendationId(
                        recommendation.recommendationId()
                )
                .setTitle(recommendation.title())
                .setRecommendation(
                        recommendation.recommendation()
                )
                .setOwner(recommendation.owner())
                .setPriority(recommendation.priority())
                .setConfidenceScore(
                        recommendation.confidenceScore()
                )
                .setPriorityScore(
                        recommendation.priorityScore()
                )
                .addAllLinkedFindingIds(
                        recommendation.linkedFindingIds()
                )
                .addAllEvidenceIds(
                        recommendation.evidenceIds()
                )
                .setAttributes(
                        toStruct(recommendation.attributes())
                )
                .build();
    }

    public com.sparrowx.agentic.proto.EvidenceRef
    toProtoEvidenceRef(EvidenceRef evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");

        return com.sparrowx.agentic.proto.EvidenceRef
                .newBuilder()
                .setEvidenceId(evidence.evidenceId())
                .setSourceType(
                        toProtoEvidenceSourceType(
                                evidence.sourceType()
                        )
                )
                .setSourceService(evidence.sourceService())
                .setSourceId(evidence.sourceId())
                .setSourceUri(evidence.sourceUri())
                .setArtifactId(evidence.artifactId())
                .setObjectId(evidence.objectId())
                .setParentObjectId(evidence.parentObjectId())
                .setLocationLabel(evidence.locationLabel())
                .setPageStart(evidence.pageStart())
                .setPageEnd(evidence.pageEnd())
                .setSection(evidence.section())
                .setChunkId(evidence.chunkId())
                .setSha256(evidence.sha256())
                .setAttributes(toStruct(evidence.attributes()))
                .build();
    }

    public com.sparrowx.agentic.proto.Citation
    toProtoCitation(Citation citation) {
        Objects.requireNonNull(citation, "citation must not be null");

        return com.sparrowx.agentic.proto.Citation
                .newBuilder()
                .setCitationId(citation.citationId())
                .setLabel(citation.label())
                .setEvidenceId(citation.evidenceId())
                .setExcerpt(citation.excerpt())
                .build();
    }

    public com.sparrowx.agentic.proto.GovernanceDecision
    toProtoGovernanceDecision(GovernanceDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");

        return com.sparrowx.agentic.proto.GovernanceDecision
                .newBuilder()
                .setDecisionId(decision.decisionId())
                .setPolicyName(decision.policyName())
                .setDecision(
                        toProtoGovernanceDecisionType(
                                decision.decision()
                        )
                )
                .setReason(decision.reason())
                .setAttributes(toStruct(decision.attributes()))
                .build();
    }

    public com.sparrowx.agentic.proto.MissionStatus
    toProtoStatus(MissionStatus status) {
        String value = enumName(status);

        return switch (value) {
            case "SUBMITTED" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_SUBMITTED;
            case "RUNNING" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_RUNNING;
            case "WAITING_APPROVAL" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_WAITING_APPROVAL;
            case "FAILED_RETRYABLE" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_FAILED_RETRYABLE;
            case "FAILED_TERMINAL" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_FAILED_TERMINAL;
            case "COMPLETED" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_COMPLETED;
            case "CANCELLED" ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_CANCELLED;
            default ->
                    com.sparrowx.agentic.proto.MissionStatus
                            .MISSION_STATUS_UNSPECIFIED;
        };
    }

    public com.sparrowx.agentic.proto.MissionPath
    toProtoPath(MissionPath path) {
        String value = enumName(path);

        return switch (value) {
            case "FAST" ->
                    com.sparrowx.agentic.proto.MissionPath
                            .MISSION_PATH_FAST;
            case "RESEARCH" ->
                    com.sparrowx.agentic.proto.MissionPath
                            .MISSION_PATH_RESEARCH;
            case "GOVERNED" ->
                    com.sparrowx.agentic.proto.MissionPath
                            .MISSION_PATH_GOVERNED;
            default ->
                    com.sparrowx.agentic.proto.MissionPath
                            .MISSION_PATH_UNSPECIFIED;
        };
    }

    public StatusRuntimeException toGrpcException(
            Throwable throwable
    ) {
        return errorGrpcMapper.toException(throwable);
    }

    private SubmitMissionCommand.InputArtifactInput
    toInputArtifactInput(
            com.sparrowx.agentic.proto.InputArtifact artifact
    ) {
        byte[] inlineBytes = null;
        String objectUri = "";
        String externalUri = "";
        String inlineText = "";

        ContentCase contentCase = artifact.getContentCase();

        switch (contentCase) {
            case OBJECT_URI ->
                    objectUri = artifact.getObjectUri();
            case INLINE_BYTES ->
                    inlineBytes =
                            artifact.getInlineBytes().toByteArray();
            case EXTERNAL_URI ->
                    externalUri = artifact.getExternalUri();
            case INLINE_TEXT ->
                    inlineText = artifact.getInlineText();
            case CONTENT_NOT_SET -> {
            }
        }

        return new SubmitMissionCommand.InputArtifactInput(
                artifact.getArtifactId(),
                artifact.getType().name(),
                objectUri,
                inlineBytes,
                externalUri,
                inlineText,
                artifact.getFilename(),
                artifact.getContentType(),
                artifact.getSha256(),
                artifact.getMetadataMap()
        );
    }

    private SubmitMissionCommand.MissionConstraintsInput
    toConstraintsInput(
            com.sparrowx.agentic.proto.MissionConstraints
                    constraints
    ) {
        return new SubmitMissionCommand.MissionConstraintsInput(
                constraints.getPreferredPath().name(),
                constraints.getAllowedToolsList(),
                constraints.getAllowedSourceServicesList(),
                constraints.getRequiredOutputSectionsList(),
                constraints.getRequireCitations(),
                constraints.getRequireHumanReview(),
                constraints.getAllowExternalSources(),
                constraints.getMaxRuntime().getSeconds(),
                constraints.getPolicyHintsMap()
        );
    }

    private SubmitMissionCommand.MissionBudgetInput
    toBudgetInput(
            com.sparrowx.agentic.proto.MissionBudget budget
    ) {
        return new SubmitMissionCommand.MissionBudgetInput(
                budget.getMaxLlmCalls(),
                budget.getMaxToolCalls(),
                budget.getMaxRetrievalQueries(),
                budget.getMaxItemsToHydrate(),
                budget.getMaxInputTokens(),
                budget.getMaxOutputTokens(),
                budget.getMaxCostMicros()
        );
    }

    private static com.sparrowx.agentic.proto.FindingType
    toProtoFindingType(Object type) {
        return switch (enumName(type)) {
            case "THEME" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_THEME;
            case "SIGNAL" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_SIGNAL;
            case "RISK" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_RISK;
            case "GAP" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_GAP;
            case "OPPORTUNITY" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_OPPORTUNITY;
            case "CONTRADICTION" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_CONTRADICTION;
            case "COMPLIANCE_RESULT" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_COMPLIANCE_RESULT;
            case "OPERATIONAL_STATUS" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_OPERATIONAL_STATUS;
            case "DECISION_POINT" ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_DECISION_POINT;
            default ->
                    com.sparrowx.agentic.proto.FindingType
                            .FINDING_TYPE_UNSPECIFIED;
        };
    }

    private static com.sparrowx.agentic.proto.EvidenceSourceType
    toProtoEvidenceSourceType(Object type) {
        return switch (enumName(type)) {
            case "INPUT_ARTIFACT" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_INPUT_ARTIFACT;
            case "DOCUMENT" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_DOCUMENT;
            case "SPAN", "DOCUMENT_SPAN" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_DOCUMENT_SPAN;
            case "INTERNAL_ENTITY" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_INTERNAL_ENTITY;
            case "INTERNAL_GRAPH" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_INTERNAL_GRAPH;
            case "TOOL_RESULT" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_TOOL_RESULT;
            case "AGENTIC_CHECKPOINT" ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_AGENTIC_CHECKPOINT;
            default ->
                    com.sparrowx.agentic.proto.EvidenceSourceType
                            .EVIDENCE_SOURCE_TYPE_UNSPECIFIED;
        };
    }

    private static
    com.sparrowx.agentic.proto.GovernanceDecisionType
    toProtoGovernanceDecisionType(Object type) {
        return switch (enumName(type)) {
            case "ALLOWED" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_ALLOWED;
            case "DENIED" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_DENIED;
            case "REDACTED" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_REDACTED;
            case "HUMAN_REVIEW_REQUIRED",
                 "REQUIRES_HUMAN_REVIEW" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_REQUIRES_HUMAN_REVIEW;
            case "APPROVED", "APPROVED_BY_HUMAN" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_APPROVED_BY_HUMAN;
            case "REJECTED", "REJECTED_BY_HUMAN" ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_REJECTED_BY_HUMAN;
            default ->
                    com.sparrowx.agentic.proto
                            .GovernanceDecisionType
                            .GOVERNANCE_DECISION_TYPE_UNSPECIFIED;
        };
    }

    private static Struct toStruct(Map<String, ?> source) {
        Struct.Builder builder = Struct.newBuilder();

        if (source == null) {
            return builder.build();
        }

        source.forEach((key, value) -> {
            if (key != null) {
                builder.putFields(key, toValue(value));
            }
        });

        return builder.build();
    }

    private static Value toValue(Object value) {
        Value.Builder builder = Value.newBuilder();

        if (value == null) {
            return builder
                    .setNullValue(NullValue.NULL_VALUE)
                    .build();
        }

        if (value instanceof Boolean booleanValue) {
            return builder
                    .setBoolValue(booleanValue)
                    .build();
        }

        if (value instanceof Number numberValue) {
            return builder
                    .setNumberValue(numberValue.doubleValue())
                    .build();
        }

        if (value instanceof Map<?, ?> mapValue) {
            Struct.Builder struct = Struct.newBuilder();

            mapValue.forEach((key, nestedValue) -> {
                if (key != null) {
                    struct.putFields(
                            String.valueOf(key),
                            toValue(nestedValue)
                    );
                }
            });

            return builder.setStructValue(struct).build();
        }

        if (value instanceof Iterable<?> iterable) {
            ListValue.Builder list = ListValue.newBuilder();

            iterable.forEach(item ->
                    list.addValues(toValue(item))
            );

            return builder.setListValue(list).build();
        }

        if (value.getClass().isArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            int length = Array.getLength(value);

            for (int index = 0; index < length; index++) {
                list.addValues(
                        toValue(Array.get(value, index))
                );
            }

            return builder.setListValue(list).build();
        }

        return builder
                .setStringValue(
                        value instanceof Enum<?> enumValue
                                ? enumValue.name()
                                : String.valueOf(value)
                )
                .build();
    }

    private static String enumName(Object value) {
        if (value == null) {
            return "UNSPECIFIED";
        }

        return value instanceof Enum<?> enumValue
                ? enumValue.name()
                : value.toString();
    }
}