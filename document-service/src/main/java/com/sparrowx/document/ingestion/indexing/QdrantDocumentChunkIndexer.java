package com.sparrowx.document.ingestion.indexing;

import com.sparrowx.document.data.qdrant.QdrantChunkIndexer;
import com.sparrowx.document.exceptions.DocumentIndexingException;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.ingestion.chunking.DocumentChunkDraft;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class QdrantDocumentChunkIndexer {

    private final QdrantChunkIndexer qdrantChunkIndexer;
    private final EmbeddingService embeddingService;

    public QdrantDocumentChunkIndexer(
            QdrantChunkIndexer qdrantChunkIndexer,
            EmbeddingService embeddingService
    ) {
        this.qdrantChunkIndexer = qdrantChunkIndexer;
        this.embeddingService = embeddingService;
    }

    public int index(DocumentChunkIndexRequest request) {
        validate(request);

        try {
            List<DocumentChunkDraft> chunks =
                    request.chunks();

            if (chunks.isEmpty()) {
                return 0;
            }

            List<String> texts = chunks.stream()
                    .map(DocumentChunkDraft::text)
                    .toList();

            List<List<Float>> vectors = embeddingService.embedDocuments(texts);

            validateEmbeddingResult(chunks, vectors);

            for (int i = 0; i < chunks.size(); i++) {

                DocumentChunkDraft chunk = chunks.get(i);

                List<Float> vector = vectors.get(i);

                qdrantChunkIndexer.indexChunk(
                        request.tenantId(),
                        request.projectId(),
                        request.teamId(),
                        request.documentId(),
                        chunk.chunkId(),
                        chunk.text(),
                        vector,
                        chunk.chunkIndex(),
                        chunk.pageStart(),
                        chunk.pageEnd(),
                        chunk.metadata()
                );
            }

            return chunks.size();

        } catch (DocumentIndexingException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new DocumentIndexingException(
                    "Failed to index chunks into Qdrant for documentId="
                            + request.documentId().value(),
                    exception
            );
        }
    }

    private void validateEmbeddingResult(
            List<DocumentChunkDraft> chunks,
            List<List<Float>> vectors
    ) {

        if (vectors == null) {
            throw new DocumentIndexingException("Embedding service returned null", null);
        }

        if (vectors.size() != chunks.size()) {
            throw new DocumentIndexingException(
                    "Embedding result count mismatch. Expected "
                            + chunks.size()
                            + " vectors but received "
                            + vectors.size(),
                    null
            );
        }

        for (int i = 0; i < vectors.size(); i++) {
            List<Float> vector = vectors.get(i);

            if (vector == null || vector.isEmpty()) {
                throw new DocumentIndexingException(
                        "Embedding service returned an empty vector for chunk index "
                                + i,
                        null
                );
            }
        }
    }

    private void validate(DocumentChunkIndexRequest request) {

        if (request == null) {
            throw InvalidDocumentException.nullCommand(
                    "DocumentChunkIndexRequest"
            );
        }

        Objects.requireNonNull(request.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(request.documentId(), "documentId must not be null");
        Objects.requireNonNull(request.chunks(), "chunks must not be null");
    }
}