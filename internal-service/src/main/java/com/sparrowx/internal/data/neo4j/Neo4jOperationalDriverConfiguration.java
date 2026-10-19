package com.sparrowx.internal.data.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** Operational Neo4j connection; distinct from the graph-query (possibly read-only) connection. */
@Configuration(proxyBeanMethods = false)
public class Neo4jOperationalDriverConfiguration {
    /** Retain the existing bean name and property keys for caller compatibility. */
    @Bean(name = "neo4jDriver", destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${sparrowx.internal.neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${sparrowx.internal.neo4j.username:neo4j}") String username,
            @Value("${sparrowx.internal.neo4j.password:password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password),
                org.neo4j.driver.Config.builder()
                        .withConnectionTimeout(5, TimeUnit.SECONDS)
                        .withMaxConnectionPoolSize(20)
                        .withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
                        .build());
    }
}
