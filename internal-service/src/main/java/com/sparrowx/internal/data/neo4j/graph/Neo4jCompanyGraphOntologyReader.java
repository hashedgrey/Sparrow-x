package com.sparrowx.internal.data.neo4j.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.internal.graph.GraphQuerySchema;
import org.neo4j.driver.TransactionContext;

import java.util.*;

/** Preserves the current observed-ontology JSON shape: relations, fields, values. */
public final class Neo4jCompanyGraphOntologyReader {
    private final Neo4jGraphSessions sessions;
    private final ObjectMapper json;

    public Neo4jCompanyGraphOntologyReader(Neo4jGraphSessions sessions, ObjectMapper json) {
        this.sessions = Objects.requireNonNull(sessions);
        this.json = Objects.requireNonNull(json);
    }

    public String describe(String tenantId) {
        Neo4jGraphSessions.requireText(tenantId, "tenantId");
        return sessions.read(tx -> {
            GraphQuerySchema schema = schema(tx, tenantId);
            List<Map<String, String>> relations = schema.relations().stream()
                    .map(r -> {
                        Map<String, String> item = new TreeMap<>();
                        item.put("source", r.source()); item.put("type", r.type()); item.put("target", r.target());
                        return item;
                    }).toList();
            Map<String, Object> document = new TreeMap<>();
            document.put("relations", relations);
            Map<String, List<String>> fields = new TreeMap<>();
            schema.nodeFields().forEach((type, names) -> fields.put(type, names.stream().sorted().toList()));
            document.put("fields", fields);
            document.put("values", Map.of());
            try { return json.writeValueAsString(document); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode graph ontology", e); }
        });
    }

    /** Run inside the caller's read transaction. This is observed, not declared, schema. */
    public static GraphQuerySchema schema(TransactionContext tx, String tenantId) {
        Map<String, Set<String>> nodeFields = new TreeMap<>();
        tx.run("""
                MATCH (n:CompanyNode {tenantId:$tenant})
                UNWIND ['label','summary','entityId'] + [k IN keys(n) WHERE k STARTS WITH 'attributes.'] AS field
                RETURN DISTINCT n.nodeType AS type, field ORDER BY type, field
                """, Map.of("tenant", tenantId)).forEachRemaining(r ->
                nodeFields.computeIfAbsent(r.get("type").asString(), ignored -> new TreeSet<>())
                        .add(r.get("field").asString()));
        Map<GraphQuerySchema.Relation, Set<String>> relationships = new LinkedHashMap<>();
        tx.run("""
                MATCH (a:CompanyNode)-[r]->(b:CompanyNode)
                WHERE a.tenantId=$tenant AND b.tenantId=$tenant AND r.tenantId=$tenant
                RETURN DISTINCT a.nodeType AS source, type(r) AS type, b.nodeType AS target,
                       keys(r) AS fields ORDER BY source, type, target
                """, Map.of("tenant", tenantId)).forEachRemaining(row -> {
            var relation = new GraphQuerySchema.Relation(row.get("source").asString(),
                    row.get("type").asString(), row.get("target").asString());
            Set<String> names = relationships.computeIfAbsent(relation, ignored -> new TreeSet<>());
            row.get("fields").asList(v -> v.asString()).stream()
                    .filter(k -> !k.equals("tenantId"))
                    .forEach(names::add);
        });
        return new GraphQuerySchema(nodeFields, relationships);
    }
}
