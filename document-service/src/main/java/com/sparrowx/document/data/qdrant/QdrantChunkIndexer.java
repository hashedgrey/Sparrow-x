package com.sparrowx.document.data.qdrant;

import com.sparrowx.document.config.EmbeddingConfig;
import com.sparrowx.document.config.QdrantProperties;
import com.sparrowx.document.domain.valueobjects.*;
import com.sparrowx.document.exceptions.DocumentIndexingException;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class QdrantChunkIndexer {

    private final QdrantProperties properties;
    private final EmbeddingConfig.EmbeddingProperties embeddingProperties;
    private final RestTemplate qdrantRestTemplate;

    public QdrantChunkIndexer(
            QdrantProperties properties,
            EmbeddingConfig.EmbeddingProperties embeddingProperties,
            RestTemplate qdrantRestTemplate
    ) {
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
        this.qdrantRestTemplate = qdrantRestTemplate;
    }

    public void indexChunk(
            TenantId tenantId,
            ProjectId projectId,
            TeamId teamId,
            DocumentId documentId,
            ChunkId chunkId,
            String text,
            List<Float> vector,
            int chunkIndex,
            int pageStart,
            int pageEnd,
            Map<String, String> metadata
    ) {
        validate(
                tenantId,
                documentId,
                chunkId,
                text,
                vector
        );

        if (!properties.enabled()) {
            return;
        }

        try {
            String url = properties.url()
                    + "/collections/"
                    + properties.collectionName()
                    + "/points?wait=true";

            Map<String, Object> payload =
                    new LinkedHashMap<>();

            payload.put("tenant_id", tenantId.value());

            payload.put(
                    "project_id",
                    projectId == null
                            ? ""
                            : projectId.value()
            );

            payload.put(
                    "team_id",
                    teamId == null
                            ? ""
                            : teamId.value()
            );

            payload.put(
                    "document_id",
                    documentId.value()
            );

            payload.put(
                    "chunk_id",
                    chunkId.value()
            );

            payload.put("text", text);
            payload.put("chunk_index", chunkIndex);
            payload.put("page_start", pageStart);
            payload.put("page_end", pageEnd);

            payload.put(
                    "metadata",
                    metadata == null
                            ? Map.<String, String>of()
                            : Map.copyOf(metadata)
            );

            payload.put(
                    "indexed_at",
                    Instant.now().toString()
            );

            Map<String, Object> point =
                    new LinkedHashMap<>();

            point.put(
                    "id",
                    stablePointId(chunkId.value())
            );

            point.put("vector", vector);
            point.put("payload", payload);

            Map<String, Object> body =
                    new LinkedHashMap<>();

            body.put(
                    "points",
                    List.of(point)
            );

            qdrantRestTemplate.put(
                    url,
                    body
            );

        } catch (RuntimeException exception) {

            if (exception instanceof DocumentIndexingException) {
                throw exception;
            }

            throw new DocumentIndexingException(
                    "Failed to index chunk into Qdrant: chunkId="
                            + chunkId.value(),
                    exception
            );
        }
    }

    private String stablePointId(
            String chunkId
    ) {
        try {
            return UUID.fromString(
                    chunkId
            ).toString();

        } catch (IllegalArgumentException ignored) {

            return UUID.nameUUIDFromBytes(
                    chunkId.getBytes(
                            StandardCharsets.UTF_8
                    )
            ).toString();
        }
    }

    private void validate(
            TenantId tenantId,
            DocumentId documentId,
            ChunkId chunkId,
            String text,
            List<Float> vector
    ) {
        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        Objects.requireNonNull(
                documentId,
                "documentId must not be null"
        );

        Objects.requireNonNull(
                chunkId,
                "chunkId must not be null"
        );

        if (text == null || text.isBlank()) {
            throw InvalidDocumentException.blankField(
                    "text"
            );
        }

        if (vector == null || vector.isEmpty()) {
            throw InvalidDocumentException.blankField(
                    "vector"
            );
        }

        if (embeddingProperties.validateDimension()
                && vector.size()
                != embeddingProperties.dimension()) {

            throw new DocumentIndexingException(
                    "Qdrant vector dimension mismatch. expected="
                            + embeddingProperties.dimension()
                            + ", actual="
                            + vector.size(),
                    null
            );
        }
    }
}