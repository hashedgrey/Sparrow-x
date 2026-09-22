package com.sparrowx.document.data.qdrant;

import com.sparrowx.document.config.EmbeddingConfig;
import com.sparrowx.document.config.QdrantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Configuration
public class QdrantCollectionInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    @Bean
    ApplicationRunner ensureQdrantCollection(
            QdrantProperties qdrantProperties,
            EmbeddingConfig.EmbeddingProperties embeddingProperties,
            RestTemplate qdrantRestTemplate
    ) {
        return args -> {

            if (!qdrantProperties.enabled()) {
                log.info(
                        "Qdrant is disabled. Skipping collection initialization."
                );
                return;
            }

            int expectedDimension =
                    embeddingProperties.dimension();

            String collectionUrl =
                    qdrantProperties.url()
                            + "/collections/"
                            + qdrantProperties.collectionName();

            Map<?, ?> existingCollection =
                    getCollection(
                            collectionUrl,
                            qdrantRestTemplate
                    );

            if (existingCollection != null) {

                verifyExistingCollection(
                        existingCollection,
                        expectedDimension,
                        qdrantProperties.collectionName()
                );

                log.info(
                        "Qdrant collection ready: {} dimension={}",
                        qdrantProperties.collectionName(),
                        expectedDimension
                );

                return;
            }

            Map<String, Object> body = Map.of(
                    "vectors",
                    Map.of(
                            "size", expectedDimension,
                            "distance", qdrantProperties.distance()
                    )
            );

            try {

                qdrantRestTemplate.put(
                        collectionUrl,
                        body
                );

                log.info(
                        "Created Qdrant collection: {} dimension={} distance={}",
                        qdrantProperties.collectionName(),
                        expectedDimension,
                        qdrantProperties.distance()
                );

            } catch (HttpClientErrorException.Conflict exception) {

                /*
                 * Another service instance may have created the
                 * collection between our existence check and PUT.
                 *
                 * Fetch it again and verify that it is compatible.
                 */
                Map<?, ?> createdCollection =
                        getCollection(
                                collectionUrl,
                                qdrantRestTemplate
                        );

                if (createdCollection == null) {
                    throw exception;
                }

                verifyExistingCollection(
                        createdCollection,
                        expectedDimension,
                        qdrantProperties.collectionName()
                );

                log.info(
                        "Qdrant collection already existed after create attempt: {}",
                        qdrantProperties.collectionName()
                );
            }
        };
    }

    private Map<?, ?> getCollection(
            String collectionUrl,
            RestTemplate qdrantRestTemplate
    ) {
        try {

            return qdrantRestTemplate.getForObject(
                    collectionUrl,
                    Map.class
            );

        } catch (HttpClientErrorException.NotFound exception) {
            return null;
        }
    }

    private void verifyExistingCollection(
            Map<?, ?> response,
            int expectedDimension,
            String collectionName
    ) {

        Integer actualDimension =
                extractVectorDimension(response);

        if (actualDimension == null) {

            log.warn(
                    "Unable to determine vector dimension for existing Qdrant collection: {}",
                    collectionName
            );

            return;
        }

        if (actualDimension != expectedDimension) {

            throw new IllegalStateException(
                    "Qdrant collection '"
                            + collectionName
                            + "' uses vector dimension "
                            + actualDimension
                            + " but the configured embedding dimension is "
                            + expectedDimension
                            + ". Use a compatible embedding model or reindex into a new collection."
            );
        }
    }

    private Integer extractVectorDimension(
            Map<?, ?> response
    ) {

        Object resultObject = response.get("result");

        if (!(resultObject instanceof Map<?, ?> result)) {
            return null;
        }

        Object configObject = result.get("config");

        if (!(configObject instanceof Map<?, ?> config)) {
            return null;
        }

        Object paramsObject = config.get("params");

        if (!(paramsObject instanceof Map<?, ?> params)) {
            return null;
        }

        Object vectorsObject = params.get("vectors");

        if (!(vectorsObject instanceof Map<?, ?> vectors)) {
            return null;
        }

        Object sizeObject = vectors.get("size");

        if (!(sizeObject instanceof Number size)) {
            return null;
        }

        return size.intValue();
    }
}