package com.sparrowx.document.ingestion.indexing;

import com.embabel.agent.api.common.Ai;
import com.sparrowx.document.config.EmbeddingConfig;
import com.sparrowx.document.exceptions.DocumentIndexingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(
        prefix = "sparrowx.document.embedding",
        name = "provider",
        havingValue = "embabel"
)
public class EmbabelEmbeddingServiceAdapter
        implements EmbeddingService {

    private final Ai ai;
    private final EmbeddingConfig.EmbeddingProperties properties;

    public EmbabelEmbeddingServiceAdapter(Ai ai, EmbeddingConfig.EmbeddingProperties properties) {
        this.ai = ai;
        this.properties = properties;
    }

    @Override
    public List<Float> embedDocument(String text) {
        return embed(text);
    }

    @Override
    public List<Float> embedQuery(String text) {
        return embed(text);
    }

    @Override
    public List<List<Float>> embedDocuments(
            List<String> texts
    ) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        if (texts.stream().anyMatch(text -> text == null || text.isBlank()
        )) {
            throw new DocumentIndexingException("Cannot embed blank document text", null);
        }

        try {
            var embeddingService = ai.withDefaultEmbeddingService();
            List<float[]> vectors = embeddingService.embed(texts);
            if (vectors == null || vectors.size() != texts.size()) {

                throw new DocumentIndexingException(
                        "Embedding result count mismatch. Expected "
                                + texts.size()
                                + " but received "
                                + (vectors == null
                                ? 0
                                : vectors.size()),
                        null
                );
            }

            return vectors.stream()
                    .map(this::toFloatList)
                    .toList();

        } catch (DocumentIndexingException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new DocumentIndexingException(
                    "Embedding batch request failed",
                    exception
            );
        }
    }

    private List<Float> embed(
            String text
    ) {

        if (text == null || text.isBlank()) {
            throw new DocumentIndexingException("Cannot embed blank text", null);
        }

        try {
            var embeddingService = ai.withDefaultEmbeddingService();
            float[] vector = embeddingService.embed(text);
            return toFloatList(vector);

        } catch (DocumentIndexingException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new DocumentIndexingException(
                    "Embedding request failed",
                    exception
            );
        }
    }

    private List<Float> toFloatList(
            float[] vector
    ) {

        if (vector == null || vector.length == 0) {
            throw new DocumentIndexingException(
                    "Embedding service returned an empty vector",
                    null
            );
        }
        validateDimension(vector);
        List<Float> result = new ArrayList<>(vector.length);

        for (float value : vector) {
            result.add(value);
        }

        return List.copyOf(result);
    }

    private void validateDimension(float[] vector) {

        if (!properties.validateDimension()) {
            return;
        }

        if (vector.length != properties.dimension()) {
            throw new DocumentIndexingException(
                    "Embedding dimension mismatch. Expected "
                            + properties.dimension()
                            + " but received "
                            + vector.length,
                    null
            );
        }
    }
}