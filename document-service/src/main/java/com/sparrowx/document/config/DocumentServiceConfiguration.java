package com.sparrowx.document.config;

import com.sparrowx.document.ingestion.IngestionWorkerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        IngestionWorkerProperties.class,
        MinioConfig.class,
        ElasticsearchConfig.ElasticsearchProperties.class,
        QdrantProperties.class,
        EmbeddingConfig.EmbeddingProperties.class,
        DiceIngestionProperties.class
})
public class DocumentServiceConfiguration {
}