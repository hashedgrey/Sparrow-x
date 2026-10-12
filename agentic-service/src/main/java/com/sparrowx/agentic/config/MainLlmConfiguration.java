package com.sparrowx.agentic.config;

import com.embabel.agent.config.models.deepseek.DeepSeekOptionsConverter;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.CredentialLlmServiceFactory;
import com.embabel.common.ai.model.ProviderCredential;

import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MainLlmConfiguration.MainLlmProperties.class)
public class MainLlmConfiguration {

    @Bean("activeMainLlm")
    public LlmService<?> activeMainLlm(
            List<CredentialLlmServiceFactory> factories,
            @Qualifier("deepSeekV4Flash") ObjectProvider<SpringAiLlmService> nativeFlash,
            @Qualifier("deepSeekV4Pro") ObjectProvider<SpringAiLlmService> nativePro,
            MainLlmProperties properties
    ) {
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(properties, "properties must not be null");

        String provider = requireText(properties.provider(), "sparrowx.agentic.main-llm.provider");
        String model = requireText(properties.model(), "sparrowx.agentic.main-llm.model");

        // Native DeepSeek has its own ChatModel and ChatOptions types. Never attach
        // DeepSeekChatOptions to an OpenAiChatModel produced by the BYOK factory.
        if ("Deepseek".equalsIgnoreCase(provider)) {
            SpringAiLlmService selected = switch (model) {
                case "deepseek-v4-flash" -> nativeFlash.getIfAvailable();
                case "deepseek-v4-pro" -> nativePro.getIfAvailable();
                default -> throw new IllegalStateException(
                        "Native Embabel 1.5.2 DeepSeek starter does not register model '" + model
                                + "'. Use deepseek-v4-flash or deepseek-v4-pro."
                );
            };

            if (selected == null) {
                throw new IllegalStateException(
                        "Missing native DeepSeek model bean for '" + model
                                + "'. Add embabel-agent-starter-deepseek and configure DEEPSEEK_API_KEY."
                );
            }
            if (!(selected.getChatModel() instanceof DeepSeekChatModel)) {
                throw new IllegalStateException(
                        "Expected native DeepSeekChatModel for '" + model + "', but got "
                                + selected.getChatModel().getClass().getName()
                );
            }

            return selected.withOptionsConverter((options, selectedModel) -> {
                DeepSeekChatOptions nativeOptions = (DeepSeekChatOptions)
                        DeepSeekOptionsConverter.INSTANCE.convertOptions(options, selectedModel);
                return nativeOptions.mutate()
                        .disableThinking()
                        .build();
            });
        }

        // Preserve the existing credential-based selection for GoogleGenAI
        // and other non-DeepSeek providers.
        String apiKey = requireText(properties.apiKey(), "sparrowx.agentic.main-llm.api-key");
        ProviderCredential credential = new ProviderCredential(provider, apiKey);
        for (CredentialLlmServiceFactory factory : factories) {
            LlmService<?> service = factory.createLlmService(credential, model);
            if (service != null) {
                return service;
            }
        }

        throw new IllegalStateException(
                "No Embabel LLM factory supports provider '" + provider + "'"
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }

    @ConfigurationProperties(prefix = "sparrowx.agentic.main-llm")
    public static class MainLlmProperties {
        private String provider = "";
        private String model = "";
        private String apiKey = "";

        public String provider() { return provider; }
        public void setProvider(String provider) {
            this.provider = provider == null ? "" : provider.trim();
        }

        public String model() { return model; }
        public void setModel(String model) {
            this.model = model == null ? "" : model.trim();
        }

        public String apiKey() { return apiKey; }
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }
    }
}
