package com.sparrowx.internal.data.neo4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class Neo4jCompanyGraphConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper companyGraphObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(name = "companyGraphDriver", destroyMethod = "close")
    Driver companyGraphDriver(@Value("${internal.graph.neo4j.uri}") String uri,
                              @Value("${internal.graph.neo4j.username}") String username,
                              @Value("${internal.graph.neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Bean
    Neo4jGraphSessions companyGraphSessions(@Qualifier("companyGraphDriver") Driver driver,
                                            @Value("${internal.graph.neo4j.database:neo4j}") String database,
                                            @Value("${internal.graph.neo4j.query-timeout-seconds:10}") long timeoutSeconds) {
        return new Neo4jGraphSessions(driver, database, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    Neo4jCompanyGraphProjectionReader companyGraphProjectionReader(Neo4jGraphSessions sessions,
                                                                   @Value("${internal.graph.neo4j.max-nodes:10000}") int maxNodes,
                                                                   @Value("${internal.graph.neo4j.max-edges:20000}") int maxEdges) {
        return new Neo4jCompanyGraphProjectionReader(sessions, maxNodes, maxEdges);
    }

    @Bean
    Neo4jCompanyGraphOntologyReader companyGraphOntologyReader(Neo4jGraphSessions sessions, ObjectMapper json) {
        return new Neo4jCompanyGraphOntologyReader(sessions, json);
    }

    @Bean
    Neo4jCompanyGraphRootScanner companyGraphRootScanner(Neo4jGraphSessions sessions,
                                                          Neo4jGraphQueryClient authorizedQueries) {
        return new Neo4jCompanyGraphRootScanner(sessions, authorizedQueries);
    }

    @Bean
    Neo4jGraphQueryClient companyGraphQueries(Neo4jGraphSessions sessions,
                                              Neo4jGraphQueryClient.Authorization authorization) {
        return new Neo4jGraphQueryClient(sessions, authorization);
    }

    // Supply Authorization from the real actor-aware InternalAccessPolicy.
    // There is deliberately no permissive fallback bean.
}
