package com.sparrowx.document.ingestion.indexing;

import com.sparrowx.document.exceptions.DocumentIndexingException;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import org.springframework.stereotype.Component;

import java.util.Objects;


@Component
public class DualDocumentChunkIndexer implements DocumentChunkIndexer {

    private final ElasticsearchDocumentChunkIndexer elasticsearchDocumentChunkIndexer;
    private final QdrantDocumentChunkIndexer qdrantDocumentChunkIndexer;

    public DualDocumentChunkIndexer(
            ElasticsearchDocumentChunkIndexer elasticsearchDocumentChunkIndexer,
            QdrantDocumentChunkIndexer qdrantDocumentChunkIndexer
    ) {
        this.elasticsearchDocumentChunkIndexer = elasticsearchDocumentChunkIndexer;
        this.qdrantDocumentChunkIndexer = qdrantDocumentChunkIndexer;
    }

    @Override
    public DocumentChunkIndexResult index(DocumentChunkIndexRequest request) {
        validate(request);

        try {
            int chunksRequested = request.chunks().size();

            int elasticsearchIndexed = elasticsearchDocumentChunkIndexer.index(request);
            int qdrantIndexed = qdrantDocumentChunkIndexer.index(request);

            int chunksIndexed = Math.min(elasticsearchIndexed, qdrantIndexed);

            return new DocumentChunkIndexResult(
                    request.ingestionJobId(),
                    request.documentId(),
                    chunksRequested,
                    chunksIndexed
            );
        } catch (RuntimeException exception) {
            if (exception instanceof DocumentIndexingException) {
                throw exception;
            }

            throw new DocumentIndexingException(
                    "Failed to index document chunks for documentId="
                            + request.documentId().value(),
                    exception
            );
        }
    }

    private void validate(DocumentChunkIndexRequest request) {
        if (request == null) {
            throw InvalidDocumentException.nullCommand("DocumentChunkIndexRequest");
        }

        Objects.requireNonNull(request.ingestionJobId(), "ingestionJobId must not be null");
        Objects.requireNonNull(request.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(request.documentId(), "documentId must not be null");
        Objects.requireNonNull(request.chunks(), "chunks must not be null");
    }
}