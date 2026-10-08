package com.sparrowx.internal.features.getinternalgraphcontext;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.internal.data.postgres.graph.PostgresCompanyGraphReadClient;
import com.sparrowx.internal.valueobjects.InternalGraphNodeType;
import com.sparrowx.internal.valueobjects.InternalGraphType;
import org.springframework.stereotype.Component;

/** IntSvc company-directory queries use PostgreSQL. */
@Component
public final class GetInternalGraphContextQueryHandler
        implements QueryHandler<GetInternalGraphContextQuery, GetInternalGraphContextResult> {
    private final PostgresCompanyGraphReadClient graphReadClient;
    private final GetInternalGraphContextQueryValidator validator;

    public GetInternalGraphContextQueryHandler(
            PostgresCompanyGraphReadClient graphReadClient,
            GetInternalGraphContextQueryValidator validator) {
        this.graphReadClient = graphReadClient;
        this.validator = validator;
    }

    @Override
    public GetInternalGraphContextResult handle(GetInternalGraphContextQuery query) {
        validator.validate(query);
        var graphType = InternalGraphType.from(query.graphType());
        if (graphType != InternalGraphType.COMPANY) {
            throw new IllegalArgumentException(
                    "IntSvc now supports only the company-directory graph; requested " + graphType);
        }
        var rootType = InternalGraphNodeType.from(query.rootNodeType());
        var graph = graphReadClient.readCompanyGraph(
                query.tenantId(), query.rootEntityId(), rootType,
                normalizeDepth(query.depth()), normalizeLimit(query.limit()));
        return new GetInternalGraphContextResult(graph);
    }

    private static int normalizeDepth(int depth) {
        return depth <= 0 ? 1 : Math.min(depth, 8);
    }

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, 500);
    }
}
