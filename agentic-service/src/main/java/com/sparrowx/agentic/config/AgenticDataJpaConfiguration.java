package com.sparrowx.agentic.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@EntityScan(
        basePackages = "com.sparrowx.agentic.data.postgres.entities"
)
@EnableJpaRepositories(
        basePackages = "com.sparrowx.agentic.data.postgres.repositories"
)
public final class AgenticDataJpaConfiguration {
}