package com.sparrowx.document.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@ConfigurationProperties(prefix = "sparrowx.document.qdrant")
public class QdrantProperties {

    private boolean enabled = false;
    private String url = "http://localhost:6333";
    private String collectionName = "sparrowx_document_chunks";
    private String distance = "Cosine";

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url;
    }

    public String collectionName() {
        return collectionName;
    }

    public String distance() {
        return distance;
    }
}