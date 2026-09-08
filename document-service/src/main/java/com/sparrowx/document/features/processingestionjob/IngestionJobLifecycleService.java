package com.sparrowx.document.features.processingestionjob;

import com.sparrowx.document.data.postgres.entities.DocumentEntity;
import com.sparrowx.document.data.postgres.entities.IngestionJobEntity;
import com.sparrowx.document.data.postgres.repositories.DocumentRepository;
import com.sparrowx.document.data.postgres.repositories.IngestionJobRepository;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.DocumentStatus;
import com.sparrowx.document.domain.valueobjects.IngestionJobId;
import com.sparrowx.document.domain.valueobjects.IngestionStatus;
import com.sparrowx.document.domain.valueobjects.TenantId;
import com.sparrowx.document.exceptions.DocumentNotFoundException;
import com.sparrowx.document.exceptions.IngestionJobNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class IngestionJobLifecycleService {

    private static final int MAX_FAILURE_REASON_LENGTH = 4000;

    private final DocumentRepository documentRepository;
    private final IngestionJobRepository ingestionJobRepository;

    public IngestionJobLifecycleService(
            DocumentRepository documentRepository,
            IngestionJobRepository ingestionJobRepository
    ) {
        this.documentRepository = documentRepository;
        this.ingestionJobRepository = ingestionJobRepository;
    }

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW
    )
    public StartState start(
            TenantId tenantId,
            DocumentId documentId,
            IngestionJobId ingestionJobId
    ) {
        IngestionJobEntity job = findJob(tenantId, ingestionJobId);
        DocumentEntity document = findDocument(tenantId, documentId);

        validateJobDocument(job, documentId);

        if (job.getStatus() == IngestionStatus.COMPLETED) {
            return new StartState(
                    true,
                    job.getStatus(),
                    job.getChunksCreated(),
                    job.getChunksIndexed()
            );
        }

        job.setStatus(IngestionStatus.EXTRACTING);
        job.setFailureReason(null);
        job.setCompletedAt(null);

        document.setStatus(DocumentStatus.INGESTING);
        document.setUpdatedAt(Instant.now());

        ingestionJobRepository.save(job);
        documentRepository.save(document);

        return new StartState(
                false,
                IngestionStatus.EXTRACTING,
                job.getChunksCreated(),
                job.getChunksIndexed()
        );
    }

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW
    )    public void complete(
            TenantId tenantId,
            DocumentId documentId,
            IngestionJobId ingestionJobId,
            int chunksCreated,
            int chunksIndexed
    ) {
        IngestionJobEntity job = findJob(tenantId, ingestionJobId);
        DocumentEntity document = findDocument(tenantId, documentId);

        validateJobDocument(job, documentId);

        Instant now = Instant.now();

        job.setStatus(IngestionStatus.COMPLETED);
        job.setChunksCreated(chunksCreated);
        job.setChunksIndexed(chunksIndexed);
        job.setFailureReason(null);
        job.setCompletedAt(now);

        document.setStatus(DocumentStatus.READY);
        document.setUpdatedAt(now);

        ingestionJobRepository.save(job);
        documentRepository.save(document);
    }

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW
    )    public void fail(
            TenantId tenantId,
            DocumentId documentId,
            IngestionJobId ingestionJobId,
            String failureReason
    ) {
        IngestionJobEntity job = findJob(tenantId, ingestionJobId);
        DocumentEntity document = findDocument(tenantId, documentId);

        validateJobDocument(job, documentId);

        if (job.getStatus() == IngestionStatus.COMPLETED) {
            return;
        }

        Instant now = Instant.now();

        job.setStatus(IngestionStatus.FAILED);
        job.setFailureReason(normalizeFailureReason(failureReason));
        job.setCompletedAt(now);

        document.setStatus(DocumentStatus.FAILED);
        document.setUpdatedAt(now);

        ingestionJobRepository.save(job);
        documentRepository.save(document);
    }

    private IngestionJobEntity findJob(
            TenantId tenantId,
            IngestionJobId ingestionJobId
    ) {
        return ingestionJobRepository
                .findByIngestionJobIdAndTenantId(ingestionJobId.value(), tenantId.value())
                .orElseThrow(() -> new IngestionJobNotFoundException(ingestionJobId.value()));
    }

    private DocumentEntity findDocument(
            TenantId tenantId,
            DocumentId documentId
    ) {
        return documentRepository
                .findByDocumentIdAndTenantId(documentId.value(), tenantId.value())
                .orElseThrow(() -> new DocumentNotFoundException(documentId.value(), tenantId.value()));
    }

    private void validateJobDocument(
            IngestionJobEntity job,
            DocumentId documentId
    ) {
        if (!documentId.value().equals(job.getDocumentId())) {
            throw new IllegalStateException(
                    "Ingestion job " + job.getIngestionJobId()
                            + " belongs to document " + job.getDocumentId()
                            + ", not " + documentId.value()
            );
        }
    }

    private String normalizeFailureReason(String failureReason) {
        String value = failureReason == null || failureReason.isBlank()
                ? "Document ingestion failed"
                : failureReason.trim();

        if (value.length() <= MAX_FAILURE_REASON_LENGTH) {
            return value;
        }

        return value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    public record StartState(
            boolean alreadyCompleted,
            IngestionStatus status,
            int chunksCreated,
            int chunksIndexed
    ) {
    }
}