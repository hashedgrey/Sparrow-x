package com.sparrowx.document.data.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.sparrowx.document.config.ElasticsearchConfig;
import com.sparrowx.document.domain.valueobjects.ChunkId;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.ProjectId;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import com.sparrowx.document.domain.valueobjects.TeamId;
import com.sparrowx.document.domain.valueobjects.TenantId;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.exceptions.RetrievalFailedException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class ElasticsearchDocumentKeywordRepository {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ElasticsearchClient elasticsearchClient;
    private final ElasticsearchConfig.ElasticsearchProperties properties;

    public ElasticsearchDocumentKeywordRepository(
            ElasticsearchClient elasticsearchClient,
            ElasticsearchConfig.ElasticsearchProperties properties
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
    }

    public List<KeywordSearchHit> search(
            TenantId tenantId,
            SearchQueryText query,
            int limit,
            Set<DocumentId> documentIds
    ) {
        return search(tenantId, null, null, query, limit, documentIds);
    }

    public List<KeywordSearchHit> search(
            TenantId tenantId,
            ProjectId projectId,
            TeamId teamId,
            SearchQueryText query,
            int limit,
            Set<DocumentId> documentIds
    ) {
        validate(tenantId, query);

        try {
            int safeLimit = normalizeLimit(limit);

            SearchResponse<Map> response = elasticsearchClient.search(search -> {
                search.index(properties.indexName());
                search.size(safeLimit);

                search.query(q -> q.bool(bool -> {
                    bool.must(must -> must.match(match -> match
                            .field("text")
                            .query(query.value())
                    ));

                    bool.filter(filter -> filter.term(term -> term
                            .field("tenant_id")
                            .value(tenantId.value())
                    ));

                    if (projectId != null && projectId.value() != null && !projectId.value().isBlank()) {
                        bool.filter(filter -> filter.term(term -> term
                                .field("project_id")
                                .value(projectId.value())
                        ));
                    }
                    if (teamId != null && teamId.value() != null && !teamId.value().isBlank()) {
                        bool.filter(filter -> filter.term(term -> term
                                .field("team_id")
                                .value(teamId.value())
                        ));
                    }

                    if (documentIds != null && !documentIds.isEmpty()) {
                        List<FieldValue> ids = documentIds.stream()
                                .filter(documentId -> documentId != null && documentId.value() != null && !documentId.value().isBlank())
                                .map(documentId -> FieldValue.of(documentId.value()))
                                .toList();

                        if (!ids.isEmpty()) {
                            bool.filter(filter -> filter.terms(terms -> terms
                                    .field("document_id")
                                    .terms(value -> value.value(ids))
                            ));
                        }
                    }

                    return bool;
                }));

                return search;
            }, Map.class);

            return response.hits()
                    .hits()
                    .stream()
                    .map(this::toHitOrNull)
                    .filter(hit -> hit != null)
                    .toList();

        } catch (RuntimeException exception) {
            if (exception instanceof InvalidDocumentException || exception instanceof RetrievalFailedException) {
                throw exception;
            }

            throw new RetrievalFailedException(
                    "Elasticsearch keyword retrieval failed",
                    exception
            );
        } catch (Exception exception) {
            throw new RetrievalFailedException(
                    "Elasticsearch keyword retrieval failed",
                    exception
            );
        }
    }

    private KeywordSearchHit toHitOrNull(Hit<Map> hit) {
        if (hit == null || hit.source() == null) {
            return null;
        }

        Map source = hit.source();

        String tenantId = stringValue(source, "tenant_id");
        String documentId = stringValue(source, "document_id");
        String chunkId = stringValue(source, "chunk_id");
        String text = stringValue(source, "text");

        if (tenantId.isBlank() || documentId.isBlank() || chunkId.isBlank() || text.isBlank()) {
            return null;
        }

        return new KeywordSearchHit(
                TenantId.of(tenantId),
                ProjectId.of(stringValue(source, "project_id")),
                TeamId.of(stringValue(source, "team_id")),
                DocumentId.of(documentId),
                ChunkId.of(chunkId),
                text,
                intValue(source, "chunk_index"),
                intValue(source, "page_start"),
                intValue(source, "page_end"),
                hit.score() == null ? 0.0 : hit.score()
        );
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private void validate(TenantId tenantId, SearchQueryText query) {
        if (tenantId == null) {
            throw InvalidDocumentException.blankField("tenantId");
        }

        if (query == null || query.value() == null || query.value().isBlank()) {
            throw InvalidDocumentException.blankField("query");
        }
    }

    private String stringValue(Map source, String field) {
        Object value = source.get(field);
        return value == null ? "" : value.toString();
    }

    private int intValue(Map source, String field) {
        Object value = source.get(field);

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

    public record KeywordSearchHit(
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