package com.sparrowx.document.mappers;

import com.google.protobuf.Timestamp;
import com.sparrowx.document.domain.models.Document;
import com.sparrowx.document.domain.models.IngestionJob;
import com.sparrowx.document.domain.valueobjects.*;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceResult;
import com.sparrowx.document.features.getdocument.GetDocumentQuery;
import com.sparrowx.document.features.getdocument.GetDocumentResult;
import com.sparrowx.document.features.getingestionjob.GetIngestionJobQuery;
import com.sparrowx.document.features.getingestionjob.GetIngestionJobResult;
import com.sparrowx.document.features.searchdocumentspans.SearchDocumentSpansQuery;
import com.sparrowx.document.features.searchdocumentspans.SearchDocumentSpansResult;
import com.sparrowx.document.features.uploaddocument.UploadDocumentCommand;
import com.sparrowx.document.features.uploaddocument.UploadDocumentResult;
import com.sparrowx.document.features.verifyevidencegraph.VerifyEvidenceGraphQuery;
import com.sparrowx.document.features.verifyevidencegraph.VerifyEvidenceGraphResult;
import com.sparrowx.document.proto.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class DocumentMapper {

    private final EvidenceGraphMapper evidenceGraphMapper;

    public DocumentMapper(EvidenceGraphMapper evidenceGraphMapper) {
        this.evidenceGraphMapper = evidenceGraphMapper;
    }

    public UploadDocumentCommand toUploadDocumentCommand(UploadDocumentRequest request) {
        RequestContext context = request.getContext();

        return new UploadDocumentCommand(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                FileName.of(request.getFileName()),
                MimeType.of(request.getMimeType()),
                request.getContent().toByteArray(),
                DocumentTitle.of(request.getTitle())
        );
    }

    public UploadDocumentResponse toUploadDocumentResponse(UploadDocumentResult result) {
        return UploadDocumentResponse.newBuilder()
                .setDocumentId(value(result.documentId()))
                .setIngestionJobId(value(result.ingestionJobId()))
                .setStatus(toProto(result.status()))
                .build();
    }

    public GetDocumentQuery toGetDocumentQuery(GetDocumentRequest request) {
        RequestContext context = request.getContext();

        return new GetDocumentQuery(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                DocumentId.of(request.getDocumentId())
        );
    }

    public GetDocumentResponse toGetDocumentResponse(GetDocumentResult result) {
        return GetDocumentResponse.newBuilder()
                .setDocument(toProto(result.document()))
                .build();
    }

    public GetIngestionJobQuery toGetIngestionJobQuery(GetIngestionJobRequest request) {
        RequestContext context = request.getContext();

        return new GetIngestionJobQuery(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                IngestionJobId.of(request.getIngestionJobId())
        );
    }

    public GetIngestionJobResponse toGetIngestionJobResponse(GetIngestionJobResult result) {
        return GetIngestionJobResponse.newBuilder()
                .setJob(toProto(result.job()))
                .build();
    }

    public SearchDocumentSpansQuery toSearchDocumentSpansQuery(SearchDocumentSpansRequest request) {
        RequestContext context = request.getContext();
        DocumentScopeProto scope = request.hasScope()
                ? request.getScope()
                : DocumentScopeProto.getDefaultInstance();

        return new SearchDocumentSpansQuery(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                SearchQueryText.of(request.getQuery()),
                toDomain(request.getRetrievalMode()),
                toSearchDocumentScope(scope),
                request.getLimit(),
                request.getIncludeExcerpts()
        );
    }

    public SearchDocumentSpansResponse toSearchDocumentSpansResponse(SearchDocumentSpansResult result) {
        return SearchDocumentSpansResponse.newBuilder()
                .addAllSpans(result.spans()
                        .stream()
                        .map(evidenceGraphMapper::toProto)
                        .toList())
                .setCoverageScore(result.coverageScore())
                .addAllWarnings(result.warnings())
                .build();
    }

    public BuildDocumentEvidenceCommand toBuildDocumentEvidenceCommand(BuildDocumentEvidenceRequest request) {
        RequestContext context = request.getContext();

        DocumentScopeProto scope = request.hasScope()
                ? request.getScope()
                : DocumentScopeProto.getDefaultInstance();

        EvidenceBuildSpecProto spec = request.hasSpec()
                ? request.getSpec()
                : EvidenceBuildSpecProto.getDefaultInstance();

        EvidenceBuildContextProto buildContext = request.hasBuildContext()
                ? request.getBuildContext()
                : EvidenceBuildContextProto.getDefaultInstance();

        return new BuildDocumentEvidenceCommand(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                toBuildDocumentScope(scope),
                toBuildSpec(spec),
                toBuildContext(buildContext),
                toDomain(request.getRetrievalMode()),
                request.getLimit(),
                request.getIncludeExcerpts(),
                request.getAllowClaimCache(),
                request.getRequireVerification()
        );
    }

    public BuildDocumentEvidenceResponse toBuildDocumentEvidenceResponse(BuildDocumentEvidenceResult result) {
        return BuildDocumentEvidenceResponse.newBuilder()
                .setGraph(evidenceGraphMapper.toProto(result.graph()))
                .setUsedChunkRetrieval(result.usedChunkRetrieval())
                .setUsedClaimCache(result.usedClaimCache())
                .setCoverageScore(result.coverageScore())
                .addAllWarnings(result.warnings())
                .build();
    }

    public VerifyEvidenceGraphQuery toVerifyEvidenceGraphQuery(VerifyEvidenceGraphRequest request) {
        RequestContext context = request.getContext();

        return new VerifyEvidenceGraphQuery(
                requestId(context.getRequestId()),
                TenantId.of(context.getTenantId()),
                UserId.of(context.getUserId()),
                ProjectId.of(context.getProjectId()),
                TeamId.of(context.getTeamId()),
                TraceId.of(context.getTraceId()),
                CallerService.of(context.getCallerService()),
                evidenceGraphMapper.toDomain(request.getGraph()),
                request.getRequireAllNodesSupported(),
                request.getRequireAllEdgesSupported()
        );
    }

    public VerifyEvidenceGraphResponse toVerifyEvidenceGraphResponse(VerifyEvidenceGraphResult result) {
        return VerifyEvidenceGraphResponse.newBuilder()
                .setSupported(result.supported())
                .setVerificationStatus(evidenceGraphMapper.toProto(result.verificationStatus()))
                .setConfidence(result.confidence())
                .setCoverageScore(result.coverageScore())
                .setVerifiedGraph(evidenceGraphMapper.toProto(result.verifiedGraph()))
                .addAllUnsupportedNodeIds(result.unsupportedNodeIds())
                .addAllUnsupportedEdgeIds(result.unsupportedEdgeIds())
                .addAllWarnings(result.warnings())
                .setExplanation(nullToEmpty(result.explanation()))
                .build();
    }

    private SearchDocumentSpansQuery.DocumentScope toSearchDocumentScope(DocumentScopeProto scope) {
        return new SearchDocumentSpansQuery.DocumentScope(
                toDocumentIds(scope.getDocumentIdsList()),
                scope.getFileNamesList(),
                scope.getCollectionIdsList(),
                scope.getTagsList(),
                scope.getMetadataFiltersMap()
        );
    }

    private BuildDocumentEvidenceCommand.DocumentScope toBuildDocumentScope(DocumentScopeProto scope) {
        return new BuildDocumentEvidenceCommand.DocumentScope(
                toDocumentIds(scope.getDocumentIdsList()),
                scope.getFileNamesList(),
                scope.getCollectionIdsList(),
                scope.getTagsList(),
                scope.getMetadataFiltersMap()
        );
    }

    private List<DocumentId> toDocumentIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(DocumentId::of)
                .toList();
    }

    private BuildDocumentEvidenceCommand.EvidenceBuildSpec toBuildSpec(EvidenceBuildSpecProto spec) {
        return new BuildDocumentEvidenceCommand.EvidenceBuildSpec(
                evidenceGraphMapper.toDomain(spec.getGoal()),
                spec.getCustomGoal(),
                spec.getRequestedNodeTypesList()
                        .stream()
                        .map(evidenceGraphMapper::toDomain)
                        .toList(),
                spec.getRequestedRelationTypesList()
                        .stream()
                        .map(evidenceGraphMapper::toDomain)
                        .toList(),
                spec.getOutputSchemaRef(),
                spec.getOutputSchemaVersion(),
                spec.getOptionsMap()
        );
    }

    private BuildDocumentEvidenceCommand.EvidenceBuildContext toBuildContext(
            EvidenceBuildContextProto buildContext
    ) {
        return new BuildDocumentEvidenceCommand.EvidenceBuildContext(
                buildContext.getRetrievalHint(),
                buildContext.getTopicsList(),
                buildContext.getEntityNamesList(),
                buildContext.getKeywordsList(),
                buildContext.getMetadataFiltersMap(),
                buildContext.getDebugTaskInstruction()
        );
    }

    private DocumentProto toProto(Document document) {
        if (document == null) {
            return DocumentProto.getDefaultInstance();
        }

        return DocumentProto.newBuilder()
                .setDocumentId(value(document.documentId()))
                .setTenantId(value(document.tenantId()))
                .setProjectId(value(document.projectId()))
                .setTeamId(value(document.teamId()))
                .setTitle(value(document.title()))
                .setFileName(value(document.fileName()))
                .setMimeType(value(document.mimeType()))
                .setSizeBytes(document.sizeBytes())
                .setObjectKey(value(document.objectKey()))
                .setContentHash(value(document.contentHash()))
                .setStatus(toProto(document.status()))
                .setCreatedAt(toTimestamp(document.createdAt()))
                .setUpdatedAt(toTimestamp(document.updatedAt()))
                .setCreatedByUserId(value(document.createdByUserId()))
                .build();
    }

    private IngestionJobProto toProto(IngestionJob job) {
        if (job == null) {
            return IngestionJobProto.getDefaultInstance();
        }

        IngestionJobProto.Builder builder = IngestionJobProto.newBuilder()
                .setIngestionJobId(value(job.ingestionJobId()))
                .setDocumentId(value(job.documentId()))
                .setStatus(toProto(job.status()))
                .setFailureReason(nullToEmpty(job.failureReason()))
                .setChunksCreated(job.chunksCreated())
                .setChunksIndexed(job.chunksIndexed())
                .setCreatedAt(toTimestamp(job.createdAt()));

        if (job.completedAt() != null) {
            builder.setCompletedAt(toTimestamp(job.completedAt()));
        }

        return builder.build();
    }

    private DocumentStatusProto toProto(DocumentStatus status) {
        if (status == null) {
            return DocumentStatusProto.DOCUMENT_STATUS_UNSPECIFIED;
        }

        return switch (status) {
            case UPLOADED -> DocumentStatusProto.DOCUMENT_STATUS_UPLOADED;
            case INGESTING -> DocumentStatusProto.DOCUMENT_STATUS_INGESTING;
            case READY -> DocumentStatusProto.DOCUMENT_STATUS_READY;
            case FAILED -> DocumentStatusProto.DOCUMENT_STATUS_FAILED;
        };
    }

    private IngestionStatusProto toProto(IngestionStatus status) {
        if (status == null) {
            return IngestionStatusProto.INGESTION_STATUS_UNSPECIFIED;
        }

        return switch (status) {
            case QUEUED -> IngestionStatusProto.INGESTION_STATUS_QUEUED;
            case EXTRACTING -> IngestionStatusProto.INGESTION_STATUS_EXTRACTING;
            case CHUNKING -> IngestionStatusProto.INGESTION_STATUS_CHUNKING;
            case EMBEDDING -> IngestionStatusProto.INGESTION_STATUS_EMBEDDING;
            case INDEXING -> IngestionStatusProto.INGESTION_STATUS_INDEXING;
            case COMPLETED -> IngestionStatusProto.INGESTION_STATUS_COMPLETED;
            case FAILED -> IngestionStatusProto.INGESTION_STATUS_FAILED;
        };
    }

    private RetrievalMode toDomain(RetrievalModeProto modeProto) {
        return switch (modeProto) {
            case RETRIEVAL_MODE_VECTOR -> RetrievalMode.VECTOR;
            case RETRIEVAL_MODE_KEYWORD -> RetrievalMode.KEYWORD;
            case RETRIEVAL_MODE_HYBRID -> RetrievalMode.HYBRID;
            case RETRIEVAL_MODE_UNSPECIFIED, UNRECOGNIZED -> RetrievalMode.HYBRID;
        };
    }

    private RequestId requestId(String value) {
        if (value == null || value.isBlank()) {
            return RequestId.newId();
        }

        return RequestId.of(value);
    }

    private Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }

        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String value(DocumentId value) {
        return value == null ? "" : value.value();
    }

    private String value(TenantId value) {
        return value == null ? "" : value.value();
    }

    private String value(ProjectId value) {
        return value == null ? "" : nullToEmpty(value.value());
    }

    private String value(TeamId value) {
        return value == null ? "" : nullToEmpty(value.value());
    }

    private String value(DocumentTitle value) {
        return value == null ? "" : nullToEmpty(value.value());
    }

    private String value(FileName value) {
        return value == null ? "" : value.value();
    }

    private String value(MimeType value) {
        return value == null ? "" : value.value();
    }

    private String value(ObjectKey value) {
        return value == null ? "" : value.value();
    }

    private String value(ContentHash value) {
        return value == null ? "" : value.value();
    }

    private String value(UserId value) {
        return value == null ? "" : value.value();
    }

    private String value(IngestionJobId value) {
        return value == null ? "" : value.value();
    }
}