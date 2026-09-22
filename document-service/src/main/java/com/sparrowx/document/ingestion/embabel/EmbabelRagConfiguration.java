package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.ingestion.InMemoryContentChunker;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(
        EmbabelRagConfiguration.RagProperties.class
)
public class EmbabelRagConfiguration {

    @Bean
    public TikaHierarchicalContentReader tikaHierarchicalContentReader() {
        return new TikaHierarchicalContentReader();
    }

    @Bean
    public ContentChunker embabelContentChunker(
            RagProperties properties
    ) {

        validate(properties);

        ContentChunker.Config config =
                new ContentChunker.Config(
                        properties.maxChunkSize(),
                        properties.overlapSize(),
                        properties.embeddingBatchSize()
                );

        return new InMemoryContentChunker(
                config,
                ChunkTransformer.NO_OP
        );
    }

    private void validate(RagProperties properties) {

        if (properties.maxChunkSize() <= 0) {
            throw new IllegalStateException(
                    "sparrowx.document.rag.max-chunk-size must be greater than zero"
            );
        }

        if (properties.overlapSize() < 0) {
            throw new IllegalStateException(
                    "sparrowx.document.rag.overlap-size cannot be negative"
            );
        }

        if (properties.overlapSize() >= properties.maxChunkSize()) {
            throw new IllegalStateException(
                    "sparrowx.document.rag.overlap-size must be smaller than max-chunk-size"
            );
        }

        if (properties.embeddingBatchSize() <= 0) {
            throw new IllegalStateException(
                    "sparrowx.document.rag.embedding-batch-size must be greater than zero"
            );
        }
    }

    @Setter
    @ConfigurationProperties(prefix = "sparrowx.document.rag")
    public static class RagProperties {

        public int maxChunkSize() {
            return 1_500;
        }

        public int overlapSize() {
            return 200;
        }

        public int embeddingBatchSize() {
            return 100;
        }
    }
}