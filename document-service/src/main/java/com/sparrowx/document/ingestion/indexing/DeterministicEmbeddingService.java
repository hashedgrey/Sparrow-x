package com.sparrowx.document.ingestion.indexing;

import com.sparrowx.document.config.EmbeddingConfig;
import com.sparrowx.document.exceptions.DocumentIndexingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "sparrowx.document.embedding",
        name = "provider",
        havingValue = "deterministic"
)
public class DeterministicEmbeddingService implements EmbeddingService {

    private final EmbeddingConfig.EmbeddingProperties properties;

    public DeterministicEmbeddingService(
            EmbeddingConfig.EmbeddingProperties properties
    ) {
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
    public List<List<Float>> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        return texts.stream()
                .map(this::embedDocument)
                .toList();
    }

    private List<Float> embed(String text) {

        if (text == null || text.isBlank()) {
            throw new DocumentIndexingException(
                    "Cannot embed blank text",
                    null
            );
        }

        try {
            int dimension = properties.dimension();

            List<Float> vector = new ArrayList<>(dimension);

            String seed = sha256(text);

            for (int i = 0; i < dimension; i++) {
                int charIndex = i % seed.length();
                int value = Character.digit(
                        seed.charAt(charIndex),
                        16
                );

                vector.add(value / 15.0f);
            }

            return List.copyOf(vector);

        } catch (RuntimeException exception) {
            throw new DocumentIndexingException(
                    "Failed to create deterministic placeholder embedding",
                    exception
            );
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    text.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }
}