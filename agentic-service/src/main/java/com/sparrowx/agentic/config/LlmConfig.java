package com.sparrowx.agentic.config;

import com.embabel.agent.spi.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.adapters.llm.StructuredLlmClient;
import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.decision.SystemOneDecisionEngine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public final class LlmConfig {

    @Bean
    @ConditionalOnMissingBean(StructuredLlmClient.class)
    public StructuredLlmClient structuredLlmClient(
            @Qualifier("activeMainLlm")
            LlmService<?> activeMainLlm,
            ObjectMapper objectMapper
    ) {
        return new StructuredLlmClient(
                activeMainLlm,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(LlmDecisionEngine.class)
    public LlmDecisionEngine llmDecisionEngine(
            StructuredLlmClient llmClient,
            ObjectMapper objectMapper
    ) {
        return new LlmDecisionEngine(
                llmClient,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(SemanticDecisionService.class)
    public SemanticDecisionService semanticDecisionService(
            SystemOneDecisionEngine systemOneDecisionEngine,
            LlmDecisionEngine llmDecisionEngine
    ) {
        return new SemanticDecisionService(
                systemOneDecisionEngine,
                llmDecisionEngine
        );
    }
}