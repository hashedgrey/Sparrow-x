package com.sparrowx.document.ingestion.pipeline;

import com.sparrowx.document.data.minio.DocumentStorage;
import com.sparrowx.document.data.postgres.entities.DocumentChunkEntity;
import com.sparrowx.document.data.postgres.repositories.DocumentChunkRepository;
import com.sparrowx.document.domain.valueobjects.*;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.ingestion.chunking.DocumentChunkDraft;
import com.sparrowx.document.ingestion.embabel.DiceDocumentEnricher;
import com.sparrowx.document.ingestion.embabel.EmbabelRagIngestionAdapter;
import com.sparrowx.document.ingestion.indexing.DocumentChunkIndexRequest;
import com.sparrowx.document.ingestion.indexing.DocumentChunkIndexResult;
import com.sparrowx.document.ingestion.indexing.DocumentChunkIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class IngestionPipeline {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    IngestionPipeline.class
            );

    private final DocumentStorage documentStorage;
    private final EmbabelRagIngestionAdapter embabelRagIngestionAdapter;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentChunkIndexer documentChunkIndexer;
    private final DiceDocumentEnricher diceDocumentEnricher;

    public IngestionPipeline(
            DocumentStorage documentStorage,
            EmbabelRagIngestionAdapter embabelRagIngestionAdapter,
            DiceDocumentEnricher diceDocumentEnricher,
            DocumentChunkRepository documentChunkRepository,
            DocumentChunkIndexer documentChunkIndexer
    ) {
        this.documentStorage =
                Objects.requireNonNull(
                        documentStorage,
                        "documentStorage must not be null"
                );

        this.embabelRagIngestionAdapter =
                Objects.requireNonNull(
                        embabelRagIngestionAdapter,
                        "embabelRagIngestionAdapter must not be null"
                );

        this.diceDocumentEnricher =
                Objects.requireNonNull(
                        diceDocumentEnricher,
                        "diceDocumentEnricher must not be null"
                );

        this.documentChunkRepository =
                Objects.requireNonNull(
                        documentChunkRepository,
                        "documentChunkRepository must not be null"
                );

        this.documentChunkIndexer =
                Objects.requireNonNull(
                        documentChunkIndexer,
                        "documentChunkIndexer must not be null"
                );
    }

    public IngestionPipelineResult execute(
            IngestionPipelineRequest request
    ) {
        validate(request);

        List<IngestionPipelineStep> completedSteps =
                new ArrayList<>();

        byte[] content =
                documentStorage.read(
                        request.objectKey().value()
                );

        completedSteps.add(
                IngestionPipelineStep.READ_OBJECT
        );

        EmbabelRagIngestionAdapter.EmbabelRagIngestionResult ingestion =
                embabelRagIngestionAdapter.ingest(
                        request.documentId(),
                        request.objectKey(),
                        request.fileName(),
                        request.mimeType(),
                        content
                );

        completedSteps.add(
                IngestionPipelineStep.EXTRACT_TEXT
        );

        completedSteps.add(
                IngestionPipelineStep.CHUNK_TEXT
        );

        List<DocumentChunkDraft> chunkDrafts =
                ingestion.chunks();

        List<DocumentChunkEntity> chunkEntities =
                chunkDrafts.stream()
                        .map(
                                chunkDraft ->
                                        toEntity(
                                                request,
                                                chunkDraft
                                        )
                        )
                        .toList();

        /*
         * Core RAG persistence/indexing comes first.
         *
         * DICE is semantic graph enrichment. It must not prevent
         * the ordinary document chunks from being persisted and indexed
         * before enrichment begins.
         */
        documentChunkRepository.saveAll(
                chunkEntities
        );

        completedSteps.add(
                IngestionPipelineStep.PERSIST_CHUNKS
        );

        DocumentChunkIndexResult indexResult =
                documentChunkIndexer.index(
                        new DocumentChunkIndexRequest(
                                request.ingestionJobId(),
                                request.tenantId(),
                                request.projectId(),
                                request.teamId(),
                                request.documentId(),
                                chunkDrafts
                        )
                );

        completedSteps.add(
                IngestionPipelineStep.INDEX_CHUNKS
        );

        logger.info(
                "Core RAG indexing complete documentId={} chunksIndexed={}",
                request.documentId().value(),
                indexResult.chunksIndexed()
        );

        /*
         * Semantic graph enrichment happens after the document is already
         * persisted and indexed for ordinary RAG retrieval.
         *
         * It remains synchronous for now so its success/failure is still
         * represented honestly by the existing ingestion job lifecycle.
         *
         * Once the job model has an explicit enrichment state, this stage
         * can move to a separate asynchronous/durable enrichment job.
         */
        DiceDocumentEnricher.DiceEnrichmentResult diceEnrichment =
                diceDocumentEnricher.enrich(
                        request.tenantId().value(),
                        request.documentId(),
                        request.fileName(),
                        ingestion.embabelChunks()
                );

        if (diceEnrichment.partial()) {
            logger.warn(
                    "DICE enrichment completed partially "
                            + "documentId={} propositions={} relationships={} "
                            + "successfulChunks={} failedChunks={} totalChunks={}",
                    request.documentId().value(),
                    diceEnrichment.propositionCount(),
                    diceEnrichment.relationshipCount(),
                    diceEnrichment.successfulChunkCount(),
                    diceEnrichment.failedChunkCount(),
                    diceEnrichment.totalChunkCount()
            );
        } else {
            logger.info(
                    "DICE enrichment complete "
                            + "documentId={} propositions={} relationships={}",
                    request.documentId().value(),
                    diceEnrichment.propositionCount(),
                    diceEnrichment.relationshipCount()
            );
        }

        completedSteps.add(
                IngestionPipelineStep.COMPLETED
        );

        String text =
                ingestion.extractedText();

        return new IngestionPipelineResult(
                request.ingestionJobId(),
                request.documentId(),
                text,
                text.length(),
                ingestion.pageCount(),
                chunkDrafts.size(),
                indexResult.chunksIndexed(),
                List.copyOf(completedSteps)
        );
    }

    private DocumentChunkEntity toEntity(
            IngestionPipelineRequest request,
            DocumentChunkDraft chunkDraft
    ) {
        DocumentChunkEntity entity =
                new DocumentChunkEntity();

        entity.setChunkId(
                chunkDraft.chunkId().value()
        );

        entity.setDocumentId(
                request.documentId().value()
        );

        entity.setTenantId(
                request.tenantId().value()
        );

        entity.setProjectId(
                request.projectId() == null
                        ? null
                        : request.projectId().value()
        );

        entity.setTeamId(
                request.teamId() == null
                        ? null
                        : request.teamId().value()
        );

        entity.setText(
                chunkDraft.text()
        );

        entity.setContentHash(
                ContentHash
                        .sha256(
                                chunkDraft.text()
                        )
                        .value()
        );

        entity.setChunkIndex(
                chunkDraft.chunkIndex()
        );

        entity.setPageStart(
                chunkDraft.pageStart()
        );

        entity.setPageEnd(
                chunkDraft.pageEnd()
        );

        entity.setCreatedAt(
                Instant.now()
        );

        return entity;
    }

    private void validate(
            IngestionPipelineRequest request
    ) {
        if (request == null) {
            throw InvalidDocumentException.nullCommand(
                    "IngestionPipelineRequest"
            );
        }

        Objects.requireNonNull(
                request.ingestionJobId(),
                "ingestionJobId must not be null"
        );

        Objects.requireNonNull(
                request.documentId(),
                "documentId must not be null"
        );

        Objects.requireNonNull(
                request.tenantId(),
                "tenantId must not be null"
        );

        Objects.requireNonNull(
                request.objectKey(),
                "objectKey must not be null"
        );

        Objects.requireNonNull(
                request.fileName(),
                "fileName must not be null"
        );

        Objects.requireNonNull(
                request.mimeType(),
                "mimeType must not be null"
        );
    }

    public record IngestionPipelineRequest(
            IngestionJobId ingestionJobId,
            DocumentId documentId,
            TenantId tenantId,
            ProjectId projectId,
            TeamId teamId,
            ObjectKey objectKey,
            FileName fileName,
            MimeType mimeType
    ) {
    }
}