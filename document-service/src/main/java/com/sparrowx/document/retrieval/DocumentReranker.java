package com.sparrowx.document.retrieval;

import com.sparrowx.document.domain.models.RetrievalEvidence;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class DocumentReranker {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    // Score-based final selection only; no cross-encoder or query-aware re-scoring.
    public List<RetrievalEvidence> rerank(
            SearchQueryText query,
            List<RetrievalEvidence> evidence,
            int limit
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        int safeLimit = normalizeLimit(limit);

        return evidence.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.documentId() != null)
                .filter(item -> item.chunkId() != null)
                .sorted(Comparator.comparingDouble(RetrievalEvidence::relevanceScore)
                        .reversed()
                        .thenComparing(item -> item.chunkId().value()))
                .limit(safeLimit)
                .toList();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }
}