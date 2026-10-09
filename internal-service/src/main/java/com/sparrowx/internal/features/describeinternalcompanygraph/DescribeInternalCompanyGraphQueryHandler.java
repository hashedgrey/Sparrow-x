package com.sparrowx.internal.features.describeinternalcompanygraph;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.internal.data.postgres.graph.PostgresCompanyGraphOntologyReader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Component
public final class DescribeInternalCompanyGraphQueryHandler
        implements QueryHandler<
        DescribeInternalCompanyGraphQuery,
        DescribeInternalCompanyGraphResult> {

    private final PostgresCompanyGraphOntologyReader reader;

    public DescribeInternalCompanyGraphQueryHandler(
            PostgresCompanyGraphOntologyReader reader
    ) {
        this.reader = Objects.requireNonNull(reader, "reader is required");
    }

    @Override
    public DescribeInternalCompanyGraphResult handle(
            DescribeInternalCompanyGraphQuery query
    ) {
        Objects.requireNonNull(query, "query is required");

        String json = reader.describe(query.tenantId());

        return new DescribeInternalCompanyGraphResult(
                json,
                sha256(json)
        );
    }

    private static String sha256(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}