package com.sparrowx.internal.data.neo4j;

import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.types.Relationship;

import java.util.*;

/** Compatibility graph slices. Traversal runs in Neo4j, never as a Java BFS. */
public final class Neo4jCompanyGraphProjectionReader {
    private final Neo4jGraphSessions sessions;
    private final int maxNodes;
    private final int maxEdges;

    public Neo4jCompanyGraphProjectionReader(Neo4jGraphSessions sessions, int maxNodes, int maxEdges) {
        this.sessions = Objects.requireNonNull(sessions);
        if (maxNodes < 1 || maxEdges < 1 || maxNodes == Integer.MAX_VALUE || maxEdges == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid graph bounds");
        this.maxNodes = maxNodes;
        this.maxEdges = maxEdges;
    }

    public Slice read(String tenantId, String nodeType, String entityId, int depth, int limit) {
        Neo4jGraphSessions.requireText(tenantId, "tenantId");
        Neo4jGraphSessions.requireText(nodeType, "nodeType");
        Neo4jGraphSessions.requireText(entityId, "entityId");
        if (depth < 0 || depth > 8) throw new IllegalArgumentException("depth must be between 0 and 8");
        if (limit < 1 || limit > maxNodes) throw new IllegalArgumentException("Invalid node limit");
        // Only a validated integer is interpolated; Cypher path bounds are syntax.
        String cypher = "MATCH p=(root:CompanyNode {tenantId:$tenant,nodeType:$type,entityId:$id})"
                + "-[*0.." + depth + "]-(n:CompanyNode) "
                + "WHERE all(x IN nodes(p) WHERE x:CompanyNode AND x.tenantId=$tenant) "
                + "AND all(r IN relationships(p) WHERE r.tenantId=$tenant) "
                + "RETURN DISTINCT n ORDER BY n.nodeKey LIMIT $fetch";
        return sessions.read(tx -> {
            List<Node> nodes = tx.run(cypher, Map.of("tenant", tenantId, "type", nodeType,
                    "id", entityId, "fetch", limit + 1)).list(r -> node(r.get("n").asNode()));
            // The legacy read response has no completeness flag. Fail rather than hide truncation.
            if (nodes.size() > limit) throw new IncompleteGraphException("Node budget exceeded");
            List<String> keys = nodes.stream().map(Node::nodeKey).toList();
            List<Edge> edges = tx.run("""
                    MATCH (a:CompanyNode)-[r]->(b:CompanyNode)
                    WHERE a.tenantId=$tenant AND b.tenantId=$tenant AND r.tenantId=$tenant
                      AND a.nodeKey IN $keys AND b.nodeKey IN $keys
                    RETURN r, a.nodeKey AS source, b.nodeKey AS target
                    ORDER BY r.relationshipId LIMIT $fetch
                    """, Map.of("tenant", tenantId, "keys", keys, "fetch", maxEdges + 1))
                    .list(r -> edge(r.get("r").asRelationship(), r.get("source").asString(), r.get("target").asString()));
            if (edges.size() > maxEdges) throw new IncompleteGraphException("Edge budget exceeded");
            return new Slice(nodes, edges, false);
        });
    }

    public Slice expand(String tenantId, Set<String> frontierNodeIds, Set<String> relationshipTypes, int limit) {
        Neo4jGraphSessions.requireText(tenantId, "tenantId");
        Set<String> frontier = checkedSet(frontierNodeIds, "frontierNodeIds");
        Set<String> types = checkedSet(relationshipTypes, "relationshipTypes");
        if (frontier.size() > maxNodes || types.size() > 256)
            throw new IllegalArgumentException("Expansion request is too large");
        if (limit < 1 || limit > maxEdges) throw new IllegalArgumentException("Invalid edge limit");
        return sessions.read(tx -> {
            var rows = tx.run("""
                    MATCH (a:CompanyNode)-[r]-(b:CompanyNode)
                    WHERE a.tenantId=$tenant AND b.tenantId=$tenant AND r.tenantId=$tenant
                      AND a.nodeKey IN $frontier AND type(r) IN $types
                    WITH DISTINCT r
                    RETURN r, startNode(r).nodeKey AS source, endNode(r).nodeKey AS target
                    ORDER BY r.relationshipId LIMIT $fetch
                    """, Map.of("tenant", tenantId, "frontier", new ArrayList<>(frontier),
                    "types", new ArrayList<>(types), "fetch", limit + 1));
            List<Edge> edges = rows.list(r -> edge(r.get("r").asRelationship(),
                    r.get("source").asString(), r.get("target").asString()));
            boolean truncated = edges.size() > limit;
            if (truncated) edges = List.copyOf(edges.subList(0, limit));
            Set<String> keys = new LinkedHashSet<>(frontier);
            edges.forEach(e -> { keys.add(e.fromKey()); keys.add(e.toKey()); });
            List<Node> nodes = loadNodes(tx, tenantId, keys);
            Set<String> present = new HashSet<>(nodes.stream().map(Node::nodeKey).toList());
            if (edges.stream().anyMatch(e -> !present.contains(e.fromKey()) || !present.contains(e.toKey())))
                throw new IncompleteGraphException("An expansion endpoint became unavailable");
            return new Slice(nodes, edges, truncated);
        });
    }

    private List<Node> loadNodes(TransactionContext tx, String tenant, Set<String> keys) {
        // At most frontier + 2*edge limit keys, all already bounded at the service boundary.
        return tx.run("""
                MATCH (n:CompanyNode) WHERE n.tenantId=$tenant AND n.nodeKey IN $keys
                RETURN n ORDER BY n.nodeKey
                """, Map.of("tenant", tenant, "keys", new ArrayList<>(keys)))
                .list(r -> node(r.get("n").asNode()));
    }

    private static Set<String> checkedSet(Set<String> values, String name) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException(name + " is required");
        values.forEach(v -> Neo4jGraphSessions.requireText(v, name));
        return Set.copyOf(values);
    }

    private static Node node(org.neo4j.driver.types.Node n) {
        Map<String, Object> attributes = new TreeMap<>();
        n.asMap().forEach((key, value) -> {
            if (key.startsWith("attributes.")) attributes.put(key.substring(11), value);
        });
        return new Node(n.get("nodeKey").asString(), n.get("nodeType").asString(),
                n.get("entityId").asString(), n.get("label").asString(), n.get("summary").asString(), attributes);
    }

    private static Edge edge(Relationship r, String source, String target) {
        Map<String, Object> properties = new TreeMap<>(r.asMap());
        properties.remove("tenantId");
        properties.remove("relationshipId");
        return new Edge(r.get("relationshipId").asString(), source, target, r.type(), properties);
    }

    public record Node(String nodeKey, String type, String entityId, String label,
                       String summary, Map<String, Object> attributes) {
        public Node { attributes = Map.copyOf(attributes); }
    }
    public record Edge(String relationshipId, String fromKey, String toKey, String type,
                       Map<String, Object> properties) {
        public Edge { properties = Map.copyOf(properties); }
    }
    public record Slice(List<Node> nodes, List<Edge> edges, boolean truncated) {
        public Slice { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    }
    public static final class IncompleteGraphException extends IllegalStateException {
        public IncompleteGraphException(String message) { super(message); }
    }
}
