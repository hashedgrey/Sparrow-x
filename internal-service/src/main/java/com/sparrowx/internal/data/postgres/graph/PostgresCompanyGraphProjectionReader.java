package com.sparrowx.internal.data.postgres.graph;

import org.springframework.beans.factory.annotation.Value;
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
    private static final String EXPAND_SQL = """
        SELECT relationship_id,
               from_node_key,
               to_node_key,
               relationship_type
        FROM intsvc_graph_edges
        WHERE tenant_id = :tenant
          AND relationship_type IN (:types)
          AND (
              from_node_key IN (:frontier)
              OR to_node_key IN (:frontier)
          )
        ORDER BY relationship_id
        LIMIT :edgeLimit
        """;

    @Value("${sparrowx.graph.expansion.max-frontier-nodes:500}")
    private int maxExpansionFrontierNodes;

    @Value("${sparrowx.graph.expansion.max-edges:5000}")
    private int maxExpansionEdges;

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


    public ExpansionSlice expand(
            String tenantId,
            Set<String> frontierKeys,
            Set<String> allowedRelationshipTypes,
            int maxEdges
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }

        if (maxExpansionFrontierNodes < 1 || maxExpansionEdges < 1) {
            throw new IllegalStateException(
                    "Graph expansion limits must be positive"
            );
        }

        Set<String> frontier = requiredValues(
                frontierKeys,
                "frontierKeys"
        );

        Set<String> relationshipTypes = requiredValues(
                allowedRelationshipTypes,
                "allowedRelationshipTypes"
        );

        if (frontier.size() > maxExpansionFrontierNodes) {
            throw new IllegalArgumentException(
                    "frontierKeys exceeds configured maximum of "
                            + maxExpansionFrontierNodes
            );
        }

        if (maxEdges < 1 || maxEdges > maxExpansionEdges) {
            throw new IllegalArgumentException(
                    "maxEdges must be between 1 and "
                            + maxExpansionEdges
            );
        }

        /*
         * The expansion contract uses domain relationship names:
         * MEMBER_OF, OWNS, BELONGS_TO, etc.
         *
         * Validate against the existing domain enum rather than
         * maintaining another hardcoded relationship list.
         */
        for (String relationshipType : relationshipTypes) {
            if ("UNSPECIFIED".equals(relationshipType)) {
                throw new IllegalArgumentException(
                        "A concrete relationship type is required"
                );
            }

            com.sparrowx.internal.valueobjects.InternalGraphRelationshipType
                    .valueOf(relationshipType);
        }

        /*
         * Validate every frontier node within this tenant.
         * Unknown nodes must not look like valid nodes with no neighbors.
         */
        var frontierArgs = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("keys", List.copyOf(frontier));

        List<Node> frontierNodes = jdbc.query(
                NODES_SQL,
                frontierArgs,
                (rs, rowNum) -> node(rs)
        );

        Set<String> foundFrontierKeys = new LinkedHashSet<>();

        for (Node frontierNode : frontierNodes) {
            foundFrontierKeys.add(frontierNode.nodeKey());
        }

        if (!foundFrontierKeys.equals(frontier)) {
            throw new IllegalArgumentException(
                    "One or more frontier nodes are unavailable in this tenant"
            );
        }

        /*
         * Fetch one extra edge to distinguish an exhaustive adjacency
         * result from a result cut short by the requested edge budget.
         */
        var edgeArgs = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("frontier", List.copyOf(frontier))
                .addValue("types", List.copyOf(relationshipTypes))
                .addValue("edgeLimit", (long) maxEdges + 1L);

        List<Edge> candidates = jdbc.query(
                EXPAND_SQL,
                edgeArgs,
                (rs, rowNum) -> new Edge(
                        rs.getString("relationship_id"),
                        rs.getString("from_node_key"),
                        rs.getString("to_node_key"),
                        rs.getString("relationship_type")
                )
        );

        boolean truncated = candidates.size() > maxEdges;

        List<Edge> edges = truncated
                ? List.copyOf(candidates.subList(0, maxEdges))
                : List.copyOf(candidates);

        /*
         * Include the frontier even when it has no matching edges.
         * Include both endpoints of every retained edge.
         */
        Set<String> selectedKeys = new LinkedHashSet<>(frontier);

        for (Edge edge : edges) {
            selectedKeys.add(edge.fromKey());
            selectedKeys.add(edge.toKey());
        }

        var nodeArgs = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("keys", List.copyOf(selectedKeys));

        List<Node> nodes = jdbc.query(
                NODES_SQL,
                nodeArgs,
                (rs, rowNum) -> node(rs)
        );

        Set<String> returnedKeys = new LinkedHashSet<>();

        for (Node returnedNode : nodes) {
            returnedKeys.add(returnedNode.nodeKey());
        }

        if (!returnedKeys.equals(selectedKeys)) {
            throw new IllegalStateException(
                    "Company graph contains missing or cross-tenant endpoints"
            );
        }

        return new ExpansionSlice(
                nodes,
                edges,
                truncated
        );
    }

    private static Set<String> requiredValues(
            Set<String> values,
            String fieldName
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be empty"
            );
        }

        Set<String> copy = new LinkedHashSet<>();

        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        fieldName + " must not contain blank values"
                );
            }

            copy.add(value);
        }

        return Set.copyOf(copy);
    }
    public record ExpansionSlice(
            List<Node> nodes,
            List<Edge> edges,
            boolean truncated
    ) {
        public ExpansionSlice {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    private static Node node(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Node(rs.getString("node_key"), rs.getString("node_type"),
                rs.getString("entity_id"), rs.getString("label"),
                rs.getString("summary"), rs.getString("attributes_json"));
    }
}
