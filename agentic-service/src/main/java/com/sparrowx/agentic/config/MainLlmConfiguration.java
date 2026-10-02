package com.sparrowx.agentic.config;

import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.CredentialLlmServiceFactory;
import com.embabel.common.ai.model.ProviderCredential;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        MainLlmConfiguration.MainLlmProperties.class
)
public class MainLlmConfiguration {

    @Bean
    public LlmService<?> activeMainLlm(
            List<CredentialLlmServiceFactory> factories,
            MainLlmProperties properties
    ) {
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(properties, "properties must not be null");

        String provider = requireText(properties.provider(), "sparrowx.agentic.main-llm.provider");
        String model = requireText(properties.model(), "sparrowx.agentic.main-llm.model");
        String apiKey = requireText(properties.apiKey(), "sparrowx.agentic.main-llm.api-key");
        ProviderCredential credential = new ProviderCredential(provider, apiKey);
        for (CredentialLlmServiceFactory factory : factories) {

            LlmService<?> service = factory.createLlmService(credential, model);

            if (service != null) {
                return service;
            }
        }

        throw new IllegalStateException("No Embabel LLM factory supports provider '" + provider + "'");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank"
           );
        }

        return value.trim();
    }

    @ConfigurationProperties(prefix = "sparrowx.agentic.main-llm"
    )
    public static class MainLlmProperties {

        private String provider = "";
        private String model = "";
        private String apiKey = "";

        public String provider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null ? "" : provider.trim();
        }

        public String model() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null ? "" : model.trim();
        }

        public String apiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }
    }
}