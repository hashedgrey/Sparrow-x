package com.sparrowx.document.retrieval;

import com.sparrowx.document.data.elasticsearch.ElasticsearchDocumentKeywordRepository;
import com.sparrowx.document.domain.models.RetrievalEvidence;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.ProjectId;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import com.sparrowx.document.domain.valueobjects.TeamId;
import com.sparrowx.document.domain.valueobjects.TenantId;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.exceptions.RetrievalFailedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class KeywordDocumentSearcher {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ElasticsearchDocumentKeywordRepository keywordRepository;
    private final RetrievalEvidenceBuilder retrievalEvidenceBuilder;

    public KeywordDocumentSearcher(
            ElasticsearchDocumentKeywordRepository keywordRepository,
            RetrievalEvidenceBuilder retrievalEvidenceBuilder
    ) {
        this.keywordRepository = keywordRepository;
        this.retrievalEvidenceBuilder = retrievalEvidenceBuilder;
    }

    public List<RetrievalEvidence> search(SearchRequest request) {
        validate(request);

        try {
            int safeLimit = normalizeLimit(request.limit());

            return keywordRepository.search(
                            request.tenantId(),
                            request.projectId(),
                            request.teamId(),
                            request.query(),
                            safeLimit,
                            request.documentIds() == null ? Set.of() : request.documentIds()
                    )
                    .stream()
                    .filter(hit -> hit != null)
                    .filter(hit -> hit.documentId() != null)
                    .filter(hit -> hit.chunkId() != null)
                    .filter(hit -> hit.text() != null && !hit.text().isBlank())
                    .map(hit -> retrievalEvidenceBuilder.build(
                            hit.documentId(),
                            hit.chunkId(),
                            null,
                            null,
                            hit.text(),
                            hit.pageStart(),
                            hit.pageEnd(),
                            hit.score()
                    ))
                    .toList();

        } catch (RuntimeException exception) {
            if (exception instanceof RetrievalFailedException) {
                throw exception;
            }

            throw new RetrievalFailedException(
                    "Keyword document search failed",
                    exception
            );
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private void validate(SearchRequest request) {
        if (request == null) {
            throw InvalidDocumentException.nullQuery("KeywordDocumentSearcher.SearchRequest");
        }

        if (request.tenantId() == null) {
            throw InvalidDocumentException.blankField("tenantId");
        }

        if (request.query() == null || request.query().value() == null || request.query().value().isBlank()) {
            throw InvalidDocumentException.blankField("query");
        }
    }

    public record SearchRequest(
            TenantId tenantId,
            ProjectId projectId,
            TeamId teamId,
            SearchQueryText query,
            int limit,
            Set<DocumentId> documentIds
    ) {
    }
}