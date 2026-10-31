package com.sparrowx.document.config;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.SpringAiEmbeddingService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Spring AI DeepInfra embedding model with Embabel's model registry. */
@Configuration(proxyBeanMethods = false)
public class DeepInfraEmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "sparrowx.document.embedding",
            name = "provider",
            havingValue = "embabel"
    )
    public EmbeddingService deepInfraEmbeddingService(
            EmbeddingModel embeddingModel,
            EmbeddingConfig.EmbeddingProperties properties,
            @Value("${DOCUMENT_EMBEDDING_MODEL:Qwen/Qwen3-Embedding-8B}")
            String modelName
    ) {
        return new SpringAiEmbeddingService(
                modelName,
                "DeepInfra",
                embeddingModel,
                properties.dimension(),
                null
        );
    }
}
