package com.sparrowx.document.ingestion.indexing;

import java.util.List;

public interface EmbeddingService {

    List<Float> embedDocument(String text);

    List<Float> embedQuery(String text);

    default List<List<Float>> embedDocuments(
            List<String> texts
    ) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        return texts.stream()
                .map(this::embedDocument)
                .toList();
    }
}