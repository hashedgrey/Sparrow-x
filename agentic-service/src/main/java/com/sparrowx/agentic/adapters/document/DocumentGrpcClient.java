package com.sparrowx.agentic.adapters.document;

import com.sparrowx.agentic.adapters.document.DocumentClientResiliencePolicy.Operation;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.document.proto.BuildDocumentEvidenceRequest;
import com.sparrowx.document.proto.BuildDocumentEvidenceResponse;
import com.sparrowx.document.proto.DocumentServiceGrpc;
import com.sparrowx.document.proto.GetDocumentRequest;
import com.sparrowx.document.proto.GetDocumentResponse;
import com.sparrowx.document.proto.GetIngestionJobRequest;
import com.sparrowx.document.proto.GetIngestionJobResponse;
import com.sparrowx.document.proto.SearchDocumentSpansRequest;
import com.sparrowx.document.proto.SearchDocumentSpansResponse;
import com.sparrowx.document.proto.UploadDocumentRequest;
import com.sparrowx.document.proto.UploadDocumentResponse;
import com.sparrowx.document.proto.VerifyEvidenceGraphRequest;
import com.sparrowx.document.proto.VerifyEvidenceGraphResponse;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class DocumentGrpcClient {

    private final DocumentServiceGrpc.DocumentServiceBlockingStub baseStub;
    private final DocumentClientMapper mapper;
    private final DocumentClientResiliencePolicy resiliencePolicy;

    public DocumentGrpcClient(
            DocumentServiceGrpc.DocumentServiceBlockingStub baseStub,
            DocumentClientMapper mapper,
            DocumentClientResiliencePolicy resiliencePolicy) {
        this.baseStub = Objects.requireNonNull(baseStub, "baseStub must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.resiliencePolicy = Objects.requireNonNull(
                resiliencePolicy,
                "resiliencePolicy must not be null");
    }

    public UploadDocumentResponse uploadDocument(
            MissionContext context,
            UploadDocumentRequest request) {
        UploadDocumentRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(context, effectiveRequest.getContext().getRequestId(), Operation.UPLOAD_DOCUMENT, stub -> stub.uploadDocument(effectiveRequest));
    }

    public GetDocumentResponse getDocument(MissionContext context, GetDocumentRequest request) {
        GetDocumentRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(context, effectiveRequest.getContext().getRequestId(), Operation.GET_DOCUMENT, stub -> stub.getDocument(effectiveRequest));
    }

    public GetIngestionJobResponse getIngestionJob(
            MissionContext context,
            GetIngestionJobRequest request) {
        GetIngestionJobRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(context, effectiveRequest.getContext().getRequestId(), Operation.GET_INGESTION_JOB, stub -> stub.getIngestionJob(effectiveRequest));
    }

    public SearchDocumentSpansResponse searchDocumentSpans(
            MissionContext context,
            SearchDocumentSpansRequest request) {
        SearchDocumentSpansRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(
                context,
                effectiveRequest.getContext().getRequestId(),
                Operation.SEARCH_DOCUMENT_SPANS,
                stub -> stub.searchDocumentSpans(effectiveRequest));
    }

    public BuildDocumentEvidenceResponse buildDocumentEvidence(
            MissionContext context,
            BuildDocumentEvidenceRequest request) {
        BuildDocumentEvidenceRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(
                context,
                effectiveRequest.getContext().getRequestId(),
                Operation.BUILD_DOCUMENT_EVIDENCE,
                stub -> stub.buildDocumentEvidence(effectiveRequest));
    }

    public VerifyEvidenceGraphResponse verifyEvidenceGraph(
            MissionContext context,
            VerifyEvidenceGraphRequest request) {
        VerifyEvidenceGraphRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context, operationRequestId(context, request.getContext().getRequestId())))
                .build();
        return invoke(
                context,
                effectiveRequest.getContext().getRequestId(),
                Operation.VERIFY_EVIDENCE_GRAPH,
                stub -> stub.verifyEvidenceGraph(effectiveRequest));
    }

    /** Preserve the operation's correlation ID; never trust protobuf tenant/user fields. */
    private static String operationRequestId(MissionContext context, String requestedId) {
        Objects.requireNonNull(context, "context must not be null");
        return requestedId == null || requestedId.isBlank() ? context.requestId() : requestedId;
    }

    private <T> T invoke(
            MissionContext context,
            String requestId,
            Operation operation,
            Function<DocumentServiceGrpc.DocumentServiceBlockingStub, T> invocation) {
        Duration deadline = resiliencePolicy.deadlineFor(operation);
        DocumentServiceGrpc.DocumentServiceBlockingStub callStub = baseStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(mapper.toMetadata(context, requestId)))
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);

        try {
            return invocation.apply(callStub);
        } catch (StatusRuntimeException exception) {
            throw resiliencePolicy.translate(operation, exception);
        }
    }
}
