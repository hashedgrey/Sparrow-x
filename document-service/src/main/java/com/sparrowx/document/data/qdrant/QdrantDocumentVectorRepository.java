package com.sparrowx.document.data.qdrant;

import com.sparrowx.document.config.QdrantProperties;
import com.sparrowx.document.domain.valueobjects.ChunkId;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.ProjectId;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import com.sparrowx.document.domain.valueobjects.TeamId;
import com.sparrowx.document.domain.valueobjects.TenantId;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.exceptions.RetrievalFailedException;
import com.sparrowx.document.ingestion.indexing.EmbeddingService;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class QdrantDocumentVectorRepository {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final QdrantProperties properties;
    private final RestTemplate qdrantRestTemplate;
    private final EmbeddingService embeddingService;

    public QdrantDocumentVectorRepository(
            QdrantProperties properties,
            RestTemplate qdrantRestTemplate,
            EmbeddingService embeddingService
    ) {
        this.properties = properties;
        this.qdrantRestTemplate = qdrantRestTemplate;
        this.embeddingService = embeddingService;
    }

    public List<VectorSearchHit> search(
            TenantId tenantId,
            SearchQueryText query,
            int limit,
            Set<DocumentId> documentIds
    ) {
        validate(tenantId, query);

        if (!properties.enabled()) {
            return List.of();
        }

        try {
            int safeLimit = normalizeLimit(limit);
            List<Float> queryVector = embeddingService.embedQuery(query.value());

            if (queryVector == null || queryVector.isEmpty()) {
                return List.of();
            }

            String url = properties.url()
                    + "/collections/"
                    + properties.collectionName()
                    + "/points/search";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryVector);
            body.put("limit", safeLimit);
            body.put("with_payload", true);
            body.put("with_vector", false);
            body.put("filter", buildFilter(tenantId, documentIds));

            Map<?, ?> response = qdrantRestTemplate.postForObject(
                    url,
                    body,
                    Map.class
            );

            return parseHits(response);

        } catch (RuntimeException exception) {
            if (exception instanceof InvalidDocumentException || exception instanceof RetrievalFailedException) {
                throw exception;
            }

            throw new RetrievalFailedException("Qdrant vector retrieval failed", exception);
        }
    }

    private Map<String, Object> buildFilter(
            TenantId tenantId,
            Set<DocumentId> documentIds
    ) {
        List<Map<String, Object>> must = new ArrayList<>();

        must.add(Map.of(
                "key", "tenant_id",
                "match", Map.of("value", tenantId.value())
        ));

        if (documentIds != null && !documentIds.isEmpty()) {
            List<String> ids = documentIds.stream()
                    .filter(documentId -> documentId != null && documentId.value() != null && !documentId.value().isBlank())
                    .map(DocumentId::value)
                    .toList();

            if (!ids.isEmpty()) {
                must.add(Map.of(
                        "key", "document_id",
                        "match", Map.of("any", ids)
                ));
            }
        }

        return Map.of("must", must);
    }

    private List<VectorSearchHit> parseHits(Map<?, ?> response) {
        Object result = response == null ? null : response.get("result");

        if (!(result instanceof List<?> results)) {
            return List.of();
        }

        List<VectorSearchHit> hits = new ArrayList<>();

        for (Object item : results) {
            VectorSearchHit hit = toHitOrNull(item);

            if (hit != null) {
                hits.add(hit);
            }
        }

        return hits;
    }

    private VectorSearchHit toHitOrNull(Object item) {
        if (!(item instanceof Map<?, ?> raw)) {
            return null;
        }

        Object payloadObject = raw.get("payload");

        if (!(payloadObject instanceof Map<?, ?> payload)) {
            return null;
        }

        String tenantId = stringValue(payload, "tenant_id");
        String documentId = stringValue(payload, "document_id");
        String chunkId = stringValue(payload, "chunk_id");
        String text = stringValue(payload, "text");

        if (tenantId.isBlank() || documentId.isBlank() || chunkId.isBlank() || text.isBlank()) {
            return null;
        }

        return new VectorSearchHit(
                TenantId.of(tenantId),
                ProjectId.of(stringValue(payload, "project_id")),
                TeamId.of(stringValue(payload, "team_id")),
                DocumentId.of(documentId),
                ChunkId.of(chunkId),
                text,
                intValue(payload.get("chunk_index")),
                intValue(payload.get("page_start")),
                intValue(payload.get("page_end")),
                doubleValue(raw.get("score"))
        );
    }

    private void validate(TenantId tenantId, SearchQueryText query) {
        if (tenantId == null) {
            throw InvalidDocumentException.blankField("tenantId");
        }

        if (query == null || query.value() == null || query.value().isBlank()) {
            throw InvalidDocumentException.blankField("query");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private String stringValue(Map<?, ?> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value == null || value.toString().isBlank()) {
            return 0;
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value == null || value.toString().isBlank()) {
            return 0.0;
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public record VectorSearchHit(
            TenantId tenantId,
            ProjectId projectId,
            TeamId teamId,
            DocumentId documentId,
            ChunkId chunkId,
            String text,
            int chunkIndex,
            int pageStart,
            int pageEnd,
            double score
    ) {
    }
}