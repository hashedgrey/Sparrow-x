package com.sparrowx.internal.data.neo4j.graph;

import buildingblocks.contracts.GraphQueryRequest;
import buildingblocks.contracts.GraphQueryResult;

import java.time.Instant;
import java.util.*;

/** One backend call for a complete logical query; no AGSvc expansion loop. */
public final class Neo4jGraphQueryClient {
    @FunctionalInterface
    public interface Authorization {
        /** Must reject unless this authenticated actor may read the WHOLE tenant graph. */
        void requireTenantGraphRead(String tenantId, String actorId);
    }
    private final Neo4jGraphSessions sessions;
    private final Authorization authorization;
    private final Neo4jGraphQueryCompiler compiler = new Neo4jGraphQueryCompiler();

    public Neo4jGraphQueryClient(Neo4jGraphSessions sessions, Authorization authorization) {
        this.sessions = Objects.requireNonNull(sessions);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public GraphQueryResult execute(String authenticatedTenantId, String authenticatedActorId, GraphQueryRequest request) {
        Neo4jGraphSessions.requireText(authenticatedTenantId, "tenantId");
        Neo4jGraphSessions.requireText(authenticatedActorId, "actorId");
        Objects.requireNonNull(request);
        authorization.requireTenantGraphRead(authenticatedTenantId, authenticatedActorId);
        return sessions.read(tx -> {
            Set<String> present = new HashSet<>(tx.run("""
                    MATCH (n:CompanyNode {tenantId:$tenant,nodeType:$type})
                    WHERE n.entityId IN $ids RETURN n.entityId AS id
                    """, Map.of("tenant", authenticatedTenantId, "type", request.rootType(),
                    "ids", new ArrayList<>(request.rootIds()))).list(row -> row.get("id").asString()));
            if (!present.equals(request.rootIds()))
                throw new IllegalStateException("Some resolved roots are unavailable; repeat root resolution");
            var compiled = compiler.compile(authenticatedTenantId, request,
                    Neo4jCompanyGraphOntologyReader.schema(tx, authenticatedTenantId));
            List<Map<String, Object>> rows = tx.run(compiled.cypher(), compiled.parameters()).list(row -> row.asMap());
            boolean truncated = rows.size() > request.maxRows();
            if (truncated && request.requireComplete())
                throw new Neo4jCompanyGraphProjectionReader.IncompleteGraphException("Query row budget exceeded");
            if (truncated) rows = rows.subList(0, request.maxRows());
            return new GraphQueryResult(rows, truncated, present, Instant.now());
        });
    }
}
