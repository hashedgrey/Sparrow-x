package com.sparrowx.document.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

public final class EmbeddingConfig {

    private EmbeddingConfig() {
    }

    @Setter
    @ConfigurationProperties(
            prefix = "sparrowx.document.embedding"
    )
    public static class EmbeddingProperties {

        private String provider = "deterministic";
        private int dimension = 768;
        private boolean validateDimension = true;

        public String provider() {
            return provider;
        }

        public int dimension() {
            return dimension;
        }

        public boolean validateDimension() {
            return validateDimension;
        }
    }
}