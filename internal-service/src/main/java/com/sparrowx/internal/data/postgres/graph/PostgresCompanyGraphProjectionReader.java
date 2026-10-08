package com.sparrowx.internal.data.postgres.graph;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded breadth-first traversal over PostgreSQL company-directory graph views. */
@Repository
public class PostgresCompanyGraphProjectionReader {
    public record Node(String nodeKey, String type, String entityId,
                       String label, String summary, String attributesJson) {}
    public record Edge(String relationshipId, String fromKey, String toKey, String type) {}
    public record Slice(List<Node> nodes, List<Edge> edges) {}

    private static final String ROOT_SQL = """
            SELECT node_key, node_type, entity_id, label, summary, attributes::text AS attributes_json
            FROM intsvc_graph_nodes
            WHERE tenant_id = :tenant AND node_type = :rootType AND entity_id = :rootId
            """;
    private static final String NODES_SQL = """
            SELECT node_key, node_type, entity_id, label, summary, attributes::text AS attributes_json
            FROM intsvc_graph_nodes
            WHERE tenant_id = :tenant AND node_key IN (:keys)
            ORDER BY node_key
            """;
    private static final String NEIGHBORS_SQL = """
            SELECT relationship_id, from_node_key, to_node_key, relationship_type
            FROM intsvc_graph_edges
            WHERE tenant_id = :tenant
              AND (from_node_key IN (:frontier) OR to_node_key IN (:frontier))
            ORDER BY relationship_id
            LIMIT :edgeLimit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresCompanyGraphProjectionReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public Slice read(String tenantId, String rootType, String rootEntityId,
                      int depth, int limit) {
        if (tenantId == null || tenantId.isBlank() || rootType == null || rootType.isBlank()
                || rootEntityId == null || rootEntityId.isBlank()) {
            throw new IllegalArgumentException("tenant, root type and root entity are required");
        }
        if (depth < 0 || depth > 8 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Unsupported company graph depth or limit");
        }

        var rootArgs = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("rootType", rootType)
                .addValue("rootId", rootEntityId);
        List<Node> roots = jdbc.query(ROOT_SQL, rootArgs, (rs, i) -> node(rs));
        if (roots.size() != 1) {
            throw new IllegalArgumentException(
                    "Company-directory root not found or nonunique for tenant: " + rootType + "/" + rootEntityId);
        }

        // Root must exist. The limit bounds the number of distinct graph nodes returned.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(roots.getFirst().nodeKey());
        Set<String> frontier = Set.of(roots.getFirst().nodeKey());
        LinkedHashMap<String, Edge> retainedEdges = new LinkedHashMap<>();

        for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
            // +1 detects truncation rather than returning a silently incomplete graph.
            int edgeLimit = Math.max(100, limit * 12) + 1;
            var args = new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("frontier", List.copyOf(frontier))
                    .addValue("edgeLimit", edgeLimit);
            List<Edge> edges = jdbc.query(NEIGHBORS_SQL, args, (rs, i) ->
                    new Edge(rs.getString("relationship_id"),
                            rs.getString("from_node_key"),
                            rs.getString("to_node_key"),
                            rs.getString("relationship_type")));
            if (edges.size() >= edgeLimit) {
                throw new IllegalStateException("Company graph neighbor set exceeded safe query budget; narrow the root/scope");
            }
            LinkedHashSet<String> nextFrontier = new LinkedHashSet<>();
            for (Edge edge : edges) {
                boolean fromIn = frontier.contains(edge.fromKey());
                boolean toIn = frontier.contains(edge.toKey());
                if (!fromIn && !toIn) continue;
                String neighbor = fromIn ? edge.toKey() : edge.fromKey();
                if (!seen.contains(neighbor) && seen.size() >= limit) {
                    throw new IllegalStateException("Company graph result exceeds node limit; request a narrower traversal");
                }
                retainedEdges.putIfAbsent(edge.relationshipId(), edge);
                if (seen.add(neighbor)) nextFrontier.add(neighbor);
            }
            frontier = nextFrontier;
        }

        var selected = new MapSqlParameterSource()
                .addValue("tenant", tenantId).addValue("keys", List.copyOf(seen));
        List<Node> nodes = jdbc.query(NODES_SQL, selected, (rs, i) -> node(rs));
        if (nodes.size() != seen.size()) {
            throw new IllegalStateException("Company graph contained references to absent/other-tenant nodes");
        }
        // Retain only edges with both endpoint nodes in this result.
        List<Edge> edges = retainedEdges.values().stream()
                .filter(e -> seen.contains(e.fromKey()) && seen.contains(e.toKey()))
                .toList();
        return new Slice(List.copyOf(nodes), edges);
    }

    private static Node node(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Node(rs.getString("node_key"), rs.getString("node_type"),
                rs.getString("entity_id"), rs.getString("label"),
                rs.getString("summary"), rs.getString("attributes_json"));
    }
}
