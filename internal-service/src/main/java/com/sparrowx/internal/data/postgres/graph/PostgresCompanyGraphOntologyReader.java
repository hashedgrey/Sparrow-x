package com.sparrowx.internal.data.postgres.graph;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Objects;

@Repository
public class PostgresCompanyGraphOntologyReader {

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresCompanyGraphOntologyReader(
            NamedParameterJdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public String describe(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }

        String json = jdbc.queryForObject(
                "SELECT intsvc_describe_company_graph(:tenant)::text",
                Map.of("tenant", tenantId),
                String.class
        );

        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "Graph ontology discovery returned no payload"
            );
        }

        return json;
    }
}