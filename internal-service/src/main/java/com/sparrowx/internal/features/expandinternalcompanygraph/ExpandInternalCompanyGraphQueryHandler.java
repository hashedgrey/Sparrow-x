package com.sparrowx.internal.features.expandinternalcompanygraph;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.internal.data.postgres.graph.PostgresCompanyGraphReadClient;
import com.sparrowx.internal.exceptions.InternalValidationException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ExpandInternalCompanyGraphQueryHandler
        implements QueryHandler<
        ExpandInternalCompanyGraphQuery,
        ExpandInternalCompanyGraphResult
        > {

    private final PostgresCompanyGraphReadClient graphReadClient;

    public ExpandInternalCompanyGraphQueryHandler(
            PostgresCompanyGraphReadClient graphReadClient
    ) {
        this.graphReadClient = Objects.requireNonNull(
                graphReadClient,
                "graphReadClient is required"
        );
    }

    @Override
    public ExpandInternalCompanyGraphResult handle(
            ExpandInternalCompanyGraphQuery query
    ) {
        Objects.requireNonNull(query, "query is required");

        try {
            var result = graphReadClient.expandCompanyGraph(
                    query.tenantId(),
                    query.frontierNodeIds(),
                    query.relationshipTypes(),
                    query.limit()
            );

            return new ExpandInternalCompanyGraphResult(
                    result.graph(),
                    result.truncated()
            );

        } catch (IllegalArgumentException exception) {
            throw new InternalValidationException(
                    exception.getMessage()
            );
        }
    }
}