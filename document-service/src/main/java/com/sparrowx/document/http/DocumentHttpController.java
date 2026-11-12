package com.sparrowx.document.http;

import buildingblocks.core.commands.CommandBus;
import buildingblocks.core.queries.QueryBus;
import com.google.protobuf.ByteString;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.exceptions.IngestionJobNotFoundException;
import com.sparrowx.document.features.getingestionjob.GetIngestionJobResult;
import com.sparrowx.document.features.uploaddocument.UploadDocumentResult;
import com.sparrowx.document.mappers.DocumentMapper;
import com.sparrowx.document.proto.GetIngestionJobRequest;
import com.sparrowx.document.proto.IngestionJobProto;
import com.sparrowx.document.proto.RequestContext;
import com.sparrowx.document.proto.UploadDocumentRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP transport for the existing document commands and queries.
 * Does not call gRPC or duplicate ingestion logic.
 *
 * This first integration uses a server-configured tenant and the authenticated
 * principal for its user. For multi-tenant deployment, replace the configured
 * tenant with verified authorization claims (never arbitrary request headers).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentHttpController {
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final DocumentMapper mapper;
    private final String tenantId;
    private final String projectId;
    private final String teamId;

    public DocumentHttpController(
            CommandBus commandBus,
            QueryBus queryBus,
            DocumentMapper mapper,
            @Value("${document.http.tenant-id:}") String tenantId,
            @Value("${document.http.project-id:}") String projectId,
            @Value("${document.http.team-id:}") String teamId
    ) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
        this.mapper = mapper;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.teamId = teamId;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadAccepted> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            Authentication authentication
    ) throws IOException {
        requireContext(authentication);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds 20 MB");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String mimeType = mimeType(file.getContentType(), fileName);

        UploadDocumentRequest request = UploadDocumentRequest.newBuilder()
                .setContext(context(authentication))
                .setFileName(fileName)
                .setMimeType(mimeType)
                .setTitle(title == null || title.isBlank() ? fileName : title.trim())
                .setContent(ByteString.copyFrom(file.getBytes()))
                .build();

        UploadDocumentResult result = commandBus.dispatch(mapper.toUploadDocumentCommand(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new UploadAccepted(
                result.documentId().value(),
                result.ingestionJobId().value(),
                result.status().name()
        ));
    }

    @GetMapping("/jobs/{jobId}")
    public IngestionJobView ingestionJob(@PathVariable String jobId, Authentication authentication) {
        requireContext(authentication);
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        GetIngestionJobRequest request = GetIngestionJobRequest.newBuilder()
                .setContext(context(authentication))
                .setIngestionJobId(jobId)
                .build();
        GetIngestionJobResult result = queryBus.dispatch(mapper.toGetIngestionJobQuery(request));
        IngestionJobProto job = mapper.toGetIngestionJobResponse(result).getJob();
        return new IngestionJobView(
                job.getIngestionJobId(),
                job.getDocumentId(),
                job.getStatus().name().replace("INGESTION_STATUS_", ""),
                job.getChunksCreated(),
                job.getChunksIndexed(),
                job.getFailureReason()
        );
    }

    private RequestContext context(Authentication authentication) {
        String requestId = UUID.randomUUID().toString();
        return RequestContext.newBuilder()
                .setRequestId(requestId)
                .setTenantId(tenantId)
                .setUserId(authentication.getName())
                .setProjectId(projectId)
                .setTeamId(teamId)
                .setTraceId(requestId)
                .setCallerService("chat-ui-http")
                .build();
    }

    private void requireContext(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() ||
                authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "document HTTP tenant is not configured");
        }
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename is required");
        }
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename is required");
        }
        return name;
    }

    private String mimeType(String value, String fileName) {
        if (value != null && !value.isBlank() && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(value)) {
            return value;
        }
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    @ExceptionHandler({InvalidDocumentException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorView> invalidRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(new ErrorView(exception.getMessage()));
    }

    @ExceptionHandler(IngestionJobNotFoundException.class)
    public ResponseEntity<ErrorView> missingJob(IngestionJobNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorView("ingestion job not found"));
    }

    public record UploadAccepted(String documentId, String ingestionJobId, String status) { }
    public record IngestionJobView(String ingestionJobId, String documentId, String status,
                                   int chunksCreated, int chunksIndexed, String failureReason) { }
    public record ErrorView(String error) { }
}
