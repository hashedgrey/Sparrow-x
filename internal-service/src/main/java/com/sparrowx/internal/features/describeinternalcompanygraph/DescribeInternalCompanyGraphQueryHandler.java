package com.sparrowx.internal.features.describeinternalcompanygraph;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.internal.data.neo4j.Neo4jCompanyGraphOntologyReader;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public final class DescribeInternalCompanyGraphQueryHandler
        implements QueryHandler<DescribeInternalCompanyGraphQuery, DescribeInternalCompanyGraphResult> {
    private final Neo4jCompanyGraphOntologyReader reader;
    public DescribeInternalCompanyGraphQueryHandler(Neo4jCompanyGraphOntologyReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }
    @Override
    public DescribeInternalCompanyGraphResult handle(DescribeInternalCompanyGraphQuery query) {
        Objects.requireNonNull(query);
        // Ontology JSON and fingerprint MUST derive from the same snapshot.
        var snapshot = reader.describeSnapshot(query.tenantId());
        return new DescribeInternalCompanyGraphResult(snapshot.ontologyJson(), snapshot.schemaVersion());
    }
}
