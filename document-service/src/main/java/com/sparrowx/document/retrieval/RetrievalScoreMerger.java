package com.sparrowx.document.retrieval;

import org.springframework.stereotype.Component;

/**
 * Rank-based fusion avoids mixing Elasticsearch BM25 scores with vector scores.
 * Ranks are 1-based; null means the chunk was not returned by that engine.
 * The [0,1] scaling keeps downstream relevanceScore consumers compatible but
 * represents neither confidence nor probability of relevance.
 */
@Component
public class RetrievalScoreMerger {

    private static final int RRF_K = 60;
    private static final double KEYWORD_WEIGHT = 0.45;
    private static final double VECTOR_WEIGHT = 0.55;

    public double mergeRanks(Integer keywordRank, Integer vectorRank) {
        double score = 0.0;
        if (keywordRank != null && keywordRank > 0) {
            score += KEYWORD_WEIGHT / (RRF_K + keywordRank);
        }
        if (vectorRank != null && vectorRank > 0) {
            score += VECTOR_WEIGHT / (RRF_K + vectorRank);
        }
        // A chunk ranked first in both lists receives 1.0.
        return (RRF_K + 1) * score;
    }
}
