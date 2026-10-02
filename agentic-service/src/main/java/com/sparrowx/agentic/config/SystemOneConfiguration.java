package com.sparrowx.agentic.config;

import com.sparrowx.agentic.decision.SystemOneDecisionEngine;
import com.sparrowx.agentic.systemone.HttpSystemOneDecisionClient;
import com.sparrowx.agentic.systemone.HttpSystemOneDecisionClient.Protocol;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public final class SystemOneConfiguration {

    @Bean
    public SystemOneDecisionClient systemOneDecisionClient(
            Environment environment
    ) {
        String provider =
                environment.getProperty(
                        "sparrowx.agentic.system-one.provider",
                        "laya"
                );

        return switch (
                provider.toLowerCase(
                        Locale.ROOT
                )
                ) {
            case "laya" ->
                    laya(
                            environment
                    );

            case "jev" ->
                    jev(
                            environment
                    );

            default ->
                    throw new IllegalStateException(
                            "Unsupported System One provider: "
                                    + provider
                    );
        };
    }

    @Bean
    public SystemOneDecisionEngine systemOneDecisionEngine(
            SystemOneDecisionClient client
    ) {
        return new SystemOneDecisionEngine(client);
    }

    private static SystemOneDecisionClient laya(
            Environment environment
    ) {
        String baseUrl =
                environment.getProperty(
                        "sparrowx.agentic.system-one.laya.base-url",
                        "http://localhost:8088"
                );

        String apiKey =
                environment.getProperty(
                        "sparrowx.agentic.system-one.laya.api-key",
                        ""
                );

        /*
         * The current Laya container owns model selection.
         * This value is therefore metadata/fallback only and is not
         * sent in the /predict request.
         */
        String model =
                environment.getProperty(
                        "sparrowx.agentic.system-one.laya.model",
                        "convaiinnovations/laya-typed-decisions"
                );

        return new HttpSystemOneDecisionClient(
                "laya",
                Protocol.LAYA,
                baseUrl,
                apiKey,
                model
        );
    }

    private static SystemOneDecisionClient jev(
            Environment environment
    ) {
        String baseUrl =
                environment.getProperty(
                        "sparrowx.agentic.system-one.jev.base-url",
                        "https://api.typesafe.ai"
                );

        String apiKey =
                required(
                        environment,
                        "sparrowx.agentic.system-one.jev.api-key"
                );

        String model =
                environment.getProperty(
                        "sparrowx.agentic.system-one.jev.model",
                        "jev-latest"
                );

        return new HttpSystemOneDecisionClient(
                "jev",
                Protocol.JEV,
                baseUrl,
                apiKey,
                model
        );
    }

    private static String required(
            Environment environment,
            String property
    ) {
        String value =
                environment.getProperty(
                        property
                );

        if (value == null
                || value.isBlank()) {

            throw new IllegalStateException(
                    "Missing required property: "
                            + property
            );
        }

        return value.trim();
    }
}