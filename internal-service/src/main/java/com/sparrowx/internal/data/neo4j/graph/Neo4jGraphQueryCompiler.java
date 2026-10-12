package com.sparrowx.internal.data.neo4j.graph;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.internal.graph.GraphQuerySchema;

import java.util.*;
import java.util.stream.Collectors;

/** Validates typed requests against IntSvc schema and compiles one parameterized MATCH query. */
public final class Neo4jGraphQueryCompiler {
    public record Compiled(String cypher, Map<String, Object> parameters) {
        public Compiled { parameters = Map.copyOf(parameters); }
    }
    private record Binding(String symbol, String type, Set<String> fields, boolean node) { }

    public Compiled compile(String tenantId, GraphQueryRequest request, GraphQuerySchema schema) {
        Neo4jGraphSessions.requireText(tenantId, "tenantId");
        Objects.requireNonNull(request); Objects.requireNonNull(schema);
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tenant", tenantId);
        parameters.put("roots", request.rootIds().stream().sorted().toList());
        parameters.put("rootType", request.rootType());
        parameters.put("fetch", request.maxRows() + 1);
        bindings.put("root", new Binding("n0", request.rootType(), nodeFields(schema, request.rootType()), true));
        StringBuilder match = new StringBuilder("MATCH (n0:CompanyNode)\n");
        List<String> where = new ArrayList<>(List.of("n0.tenantId=$tenant", "n0.nodeType=$rootType", "n0.entityId IN $roots"));
        int nextNode = 1, nextEdge = 0;
        for (var traversal : request.traversals()) {
            Binding source = requireBinding(bindings, traversal.source());
            if (!source.node()) throw new IllegalArgumentException("Traversal source must be a node");
            if (bindings.containsKey(traversal.relationshipVariable())
                    || traversal.relationshipVariable().equals(traversal.target()))
                throw new IllegalArgumentException("Relationship variable must be fresh");
            var relation = traversal.direction() == GraphQueryRequest.Direction.OUT
                    ? new GraphQuerySchema.Relation(source.type(), traversal.relationshipType(), traversal.targetType())
                    : new GraphQuerySchema.Relation(traversal.targetType(), traversal.relationshipType(), source.type());
            Set<String> edgeFields = schema.relationshipFields().get(relation);
            if (edgeFields == null) throw new IllegalArgumentException("Undeclared relationship triple: " + relation);
            Binding target = bindings.get(traversal.target());
            if (target == null) {
                String symbol = "n" + nextNode++;
                target = new Binding(symbol, traversal.targetType(), nodeFields(schema, traversal.targetType()), true);
                bindings.put(traversal.target(), target);
                where.add(symbol + ".tenantId=$tenant");
                String parameter = "type" + nextNode;
                parameters.put(parameter, traversal.targetType());
                where.add(symbol + ".nodeType=$" + parameter);
            } else if (!target.node() || !target.type().equals(traversal.targetType())) {
                throw new IllegalArgumentException("Incompatible join target");
            }
            String edge = "e" + nextEdge++;
            bindings.put(traversal.relationshipVariable(), new Binding(edge, traversal.relationshipType(), edgeFields, false));
            where.add(edge + ".tenantId=$tenant");
            match.append("MATCH (").append(source.symbol()).append(")")
                    .append(traversal.direction() == GraphQueryRequest.Direction.IN ? "<-[" : "-[")
                    .append(edge).append(":").append(quote(relation.type())).append("]")
                    .append(traversal.direction() == GraphQueryRequest.Direction.IN ? "-" : "->")
                    .append("(").append(target.symbol()).append(":CompanyNode)\n");
        }
        int predicateIndex = 0;
        for (var predicate : request.predicates()) {
            String field = field(bindings, predicate.variable(), predicate.field());
            String parameter = "value" + predicateIndex++;
            parameters.put(parameter, predicate.value());
            String operator = switch (predicate.compare()) {
                case EQ -> "="; case NE -> "<>"; case CONTAINS -> "CONTAINS";
                case IN -> "IN"; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<=";
            };
            where.add(field + " " + operator + " $" + parameter);
        }
        List<String> outputs = new ArrayList<>();
        Set<String> aliases = new HashSet<>(Set.of("__rootEntityId", "__nodes", "__relationships"));
        for (var projection : request.projections()) {
            if (!aliases.add(projection.alias())) throw new IllegalArgumentException("Duplicate/reserved projection alias");
            outputs.add(field(bindings, projection.variable(), projection.field()) + " AS " + quote(projection.alias()));
        }
        // Stable domain identifiers accompany every binding, including intermediate nodes/edges.
        outputs.add("n0.entityId AS __rootEntityId");
        outputs.add(provenance(bindings, true) + " AS __nodes");
        outputs.add(provenance(bindings, false) + " AS __relationships");
        String order = bindings.values().stream()
                .map(b -> b.symbol() + (b.node() ? ".nodeKey" : ".relationshipId"))
                .collect(Collectors.joining(", "));
        return new Compiled(match + "WHERE " + String.join(" AND ", where)
                + "\nRETURN " + String.join(", ", outputs) + "\nORDER BY " + order + "\nLIMIT $fetch", parameters);
    }

    private static String provenance(Map<String, Binding> bindings, boolean nodes) {
        return "{" + bindings.entrySet().stream().filter(e -> e.getValue().node() == nodes)
                .map(e -> quote(e.getKey()) + ": " + e.getValue().symbol() + (nodes ? ".nodeKey" : ".relationshipId"))
                .collect(Collectors.joining(", ")) + "}";
    }
    private static Set<String> nodeFields(GraphQuerySchema schema, String type) {
        Set<String> fields = schema.nodeFields().get(type);
        if (fields == null) throw new IllegalArgumentException("Undeclared node type: " + type);
        return fields;
    }
    private static Binding requireBinding(Map<String, Binding> bindings, String name) {
        Binding binding = bindings.get(name);
        if (binding == null) throw new IllegalArgumentException("Unbound variable: " + name);
        return binding;
    }
    private static String field(Map<String, Binding> bindings, String variable, String field) {
        Binding binding = requireBinding(bindings, variable);
        if (!binding.fields().contains(field)) throw new IllegalArgumentException("Undeclared field: " + variable + "." + field);
        return binding.symbol() + "." + quote(field);
    }
    private static String quote(String identifier) {
        if (identifier.indexOf('\\') >= 0 || identifier.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Backslashes and control characters are not allowed in identifiers");
        // Names are schema-validated or result aliases; doubling backticks preserves a single identifier.
        return "`" + identifier.replace("`", "``") + "`";
    }
}
