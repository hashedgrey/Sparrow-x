package com.sparrowx.internal.data.neo4j;

import com.sparrowx.internal.features.searchinternalentities.InternalEntitySearchResult;
import java.util.*;

/**
 * Tenant-authorized and schema-validated root discovery over CompanyNode.
 * No entity-specific repositories, type names, or graph relationships.
 * Free-form RELEVANT search is LEXICAL, not embedding/semantic retrieval.
 */
public final class Neo4jCompanyGraphRootScanner {
    public record Discovery(List<InternalEntitySearchResult> results, boolean exhaustive) {
        public Discovery { results = List.copyOf(results); }
    }
    private final Neo4jGraphSessions sessions;
    private final Neo4jGraphQueryClient authorizedQueries;

    public Neo4jCompanyGraphRootScanner(Neo4jGraphSessions sessions,
                                       Neo4jGraphQueryClient authorizedQueries) {
        this.sessions = Objects.requireNonNull(sessions);
        this.authorizedQueries = Objects.requireNonNull(authorizedQueries);
    }

    public Discovery typeScan(String tenantId, String actorId, String nodeType, int limit) {
        return find(tenantId, actorId, Set.of(requireText(nodeType, "nodeType")), "", limit, true);
    }

    public Discovery search(String tenantId, String actorId, Set<String> nodeTypes,
                            String query, int limit, boolean includeFuzzyMatches) {
        String term = requireText(query, "query").trim();
        return find(tenantId, actorId, nodeTypes, term, limit, includeFuzzyMatches);
    }

    private Discovery find(String tenantId, String actorId, Set<String> nodeTypes,
                           String term, int limit, boolean fuzzy) {
        requireText(tenantId, "tenantId"); requireText(actorId, "actorId");
        Objects.requireNonNull(nodeTypes, "nodeTypes");
        nodeTypes.forEach(t -> requireText(t, "nodeType"));
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Root search limit must be 1..100");
        authorizedQueries.requireTenantGraphRead(tenantId, actorId);
        return sessions.read(tx -> {
            var schema = Neo4jCompanyGraphOntologyReader.schema(tx, tenantId);
            if (!schema.nodeFields().keySet().containsAll(nodeTypes))
                throw new IllegalArgumentException("Root search includes an undeclared tenant graph node type");
            // Scores are generic search ranking only. The caller independently
            // evaluates EXACT/CONTAINS/RELEVANT and cardinality before accepting roots.
            var rows = tx.run("""
                    MATCH (n:CompanyNode {tenantId:$tenant})
                    WHERE ($anyType OR n.nodeType IN $types)
                    WITH n, toLower($term) AS term,
                         [k IN keys(n) WHERE k STARTS WITH 'attributes.' AND n[k] IS NOT NULL
                            | toLower(toString(n[k]))] AS extra
                    WITH n, term,
                         CASE
                           WHEN term = '' THEN 1.0
                           WHEN toLower(n.label) = term OR toLower(n.entityId) = term
                                OR any(v IN extra WHERE v = term) THEN 1.0
                           WHEN toLower(n.label) CONTAINS term OR toLower(n.entityId) CONTAINS term
                                OR any(v IN extra WHERE v CONTAINS term) THEN 0.85
                           WHEN toLower(coalesce(n.summary,'')) CONTAINS term THEN 0.65
                           ELSE 0.0
                         END AS score
                    WHERE score > 0.0 AND ($fuzzy OR score >= 0.85)
                    RETURN n, score ORDER BY score DESC, n.nodeType, n.entityId LIMIT $fetch
                    """, Map.of("tenant", tenantId, "types", new ArrayList<>(nodeTypes),
                            "anyType", nodeTypes.isEmpty(), "term", term.toLowerCase(Locale.ROOT),
                            "fuzzy", fuzzy, "fetch", limit + 1)).list();
            boolean exhaustive = rows.size() <= limit;
            List<InternalEntitySearchResult> selected = rows.stream().limit(limit).map(row -> {
                var n = row.get("n").asNode();
                Map<String,String> attrs = new LinkedHashMap<>();
                n.asMap().forEach((key,value) -> {
                    if (key.startsWith("attributes.") && value != null)
                        attrs.put(key.substring("attributes.".length()), value.toString());
                });
                return new InternalEntitySearchResult(
                        n.get("entityId").asString(), n.get("nodeType").asString(),
                        n.get("label").asString(""), attrs.getOrDefault("slug", ""),
                        n.get("summary").asString(""), row.get("score").asDouble(),
                        term.isEmpty() ? "graph type scan" : "tenant graph lexical match",
                        "", "", Map.copyOf(attrs));
            }).toList();
            return new Discovery(selected, exhaustive);
        });
    }
    private static String requireText(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is required");
        return v;
    }
}
