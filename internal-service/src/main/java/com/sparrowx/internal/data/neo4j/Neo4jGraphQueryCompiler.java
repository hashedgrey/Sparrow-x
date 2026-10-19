package com.sparrowx.internal.data.neo4j;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.internal.graph.GraphQuerySchema;

import java.util.*;
import java.util.stream.Collectors;

/** Schema-validated, parameterized Cypher compiler; all relationships/types come from metadata. */
public final class Neo4jGraphQueryCompiler {
    public record Compiled(String cypher, Map<String, Object> parameters) {
        public Compiled { parameters = Map.copyOf(parameters); }
    }
    private record Binding(String symbol, String type, Set<String> fields, boolean node) {}
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "__rootEntityId", "__nodes", "__relationships", "rootEntityId", "nodes", "relationships");

    public Compiled compile(String tenantId, GraphQueryRequest request, GraphQuerySchema schema) {
        Neo4jGraphSessions.requireText(tenantId, "tenantId");
        Objects.requireNonNull(request);
        Objects.requireNonNull(schema);
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tenant", tenantId);
        parameters.put("roots", request.rootIds().stream().sorted().toList());
        parameters.put("rootType", request.rootType());
        parameters.put("fetch", request.maxRows() + 1);
        bindings.put("root", new Binding("n0", request.rootType(), nodeFields(schema, request.rootType()), true));
        StringBuilder match = new StringBuilder("MATCH (n0:CompanyNode)\n");
        List<String> where = new ArrayList<>(List.of("n0.tenantId=$tenant",
                "n0.nodeType=$rootType", "n0.entityId IN $roots"));
        int nextNode = 1;
        int nextEdge = 0;
        for (var traversal : request.traversals()) {
            Binding source = requireBinding(bindings, traversal.source());
            if (!source.node()) throw new IllegalArgumentException("Traversal source must be a node");
            if (bindings.containsKey(traversal.relationshipVariable()) ||
                    traversal.relationshipVariable().equals(traversal.target()))
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
            bindings.put(traversal.relationshipVariable(),
                    new Binding(edge, traversal.relationshipType(), edgeFields, false));
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
                case EQ -> "=";
                case NE -> "<>";
                case CONTAINS -> "CONTAINS";
                case IN -> "IN";
                case GT -> ">";
                case GE -> ">=";
                case LT -> "<";
                case LE -> "<=";
            };
            where.add(field + " " + operator + " $" + parameter);
        }
        // Schema-validated property-to-property comparisons. Both endpoints must
        // refer to bound variables and fields declared for those variable types.
        for (var comparison : request.fieldComparisons()) {
            String left = field(bindings, comparison.leftVariable(), comparison.leftField());
            String right = field(bindings, comparison.rightVariable(), comparison.rightField());
            String operator = switch (comparison.compare()) {
                case EQ -> "=";
                case NE -> "<>";
                default -> throw new IllegalArgumentException("Field comparison requires EQ or NE");
            };
            where.add(left + " " + operator + " " + right);
        }
        // Correlated anti-joins: one-hop negative existence with no projected
        // variables. Both the relationship and target node must be in the
        // authenticated tenant. Relationship type and target type are validated
        // against the observed tenant ontology before they reach Cypher.
        int absenceIndex = 0;
        for (var absence : request.negatedTraversals()) {
            Binding source = requireBinding(bindings, absence.source());
            if (!source.node()) throw new IllegalArgumentException("Negated traversal source must be a node");
            var relation = absence.direction() == GraphQueryRequest.Direction.OUT
                    ? new GraphQuerySchema.Relation(source.type(), absence.relationshipType(), absence.targetType())
                    : new GraphQuerySchema.Relation(absence.targetType(), absence.relationshipType(), source.type());
            if (!schema.relationshipFields().containsKey(relation))
                throw new IllegalArgumentException("Undeclared negated relationship triple: " + relation);
            nodeFields(schema, absence.targetType());
            int idx = absenceIndex++;
            String edge = "absEdge" + idx;
            String target = "absNode" + idx;
            String typeParameter = "absType" + idx;
            parameters.put(typeParameter, absence.targetType());
            String pattern = "(" + source.symbol() + ")"
                    + (absence.direction() == GraphQueryRequest.Direction.IN ? "<-[" : "-[")
                    + edge + ":" + quote(absence.relationshipType()) + "]"
                    + (absence.direction() == GraphQueryRequest.Direction.IN ? "-" : "->")
                    + "(" + target + ":CompanyNode)";
            where.add("NOT EXISTS { MATCH " + pattern + " WHERE "
                    + edge + ".tenantId=$tenant AND "
                    + target + ".tenantId=$tenant AND "
                    + target + ".nodeType=$" + typeParameter + " }");
        }
        List<String> outputs = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> protectedAliases = new HashSet<>(RESERVED_ALIASES);
        for (var projection : request.projections()) {
            if (projection.alias().startsWith("__") || !protectedAliases.add(projection.alias()))
                throw new IllegalArgumentException("Duplicate/reserved projection alias");
            String expression = field(bindings, projection.variable(), projection.field());
            outputs.add(expression + " AS " + quote(projection.alias()));
            aliases.put(projection.alias(), expression);
        }
        outputs.add("n0.entityId AS __rootEntityId");
        outputs.add(provenance(bindings, true) + " AS __nodes");
        outputs.add(provenance(bindings, false) + " AS __relationships");

        String rowOrder = bindings.values().stream()
                .map(b -> b.symbol() + (b.node() ? ".nodeKey" : ".relationshipId"))
                .collect(Collectors.joining(", "));
        String matchAndWhere = match + "WHERE " + String.join(" AND ", where) + "\n";
        GraphQueryRequest.Selection selection = request.selection();
        if (selection == null) {
            return new Compiled(matchAndWhere + "RETURN " + String.join(", ", outputs)
                    + "\nORDER BY " + rowOrder + "\nLIMIT $fetch", parameters);
        }
        for (String alias : selection.onePerGroupBy()) requireProjection(aliases, alias);
        for (var sort : selection.orderBy()) requireProjection(aliases, sort.alias());
        if (selection.limit() > 0) parameters.put("selectionLimit", selection.limit());

        String order;
        if (selection.random()) {
            order = "rand()";
        } else if (!selection.orderBy().isEmpty()) {
            order = selection.orderBy().stream().map(o ->
                            quote(o.alias()) + (o.descending() ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", ")) + ", __rootEntityId";
        } else {
            order = "__rootEntityId";
        }
        String limit = selection.limit() > 0 ? "$selectionLimit" : "$fetch";

        if (selection.onePerGroupBy().isEmpty()) {
            // Ordinary top-N/random selection happens at the database. Sorting
            // aliases after RETURN cannot inject Cypher or use unknown fields.
            return new Compiled(matchAndWhere + "RETURN " + String.join(", ", outputs)
                    + "\nORDER BY " + order + "\nLIMIT " + limit, parameters);
        }

        // Grouping is done over schema-checked projected expressions. Each group
        // chooses one random/ordered row, then user-directed order/limit chooses
        // groups. Provenance survives as values inside __candidate.
        String group = selection.onePerGroupBy().stream()
                .map(aliases::get).collect(Collectors.joining(", ", "[", "]"));
        // Construct a candidate from validated aliases directly. Parsing rendered
        // "expr AS alias" strings fails for quoted aliases containing backticks.
        List<String> candidateFields = new ArrayList<>();
        aliases.forEach((alias, expression) ->
                candidateFields.add(quote(alias) + ": " + expression));
        candidateFields.add("`__rootEntityId`: n0.entityId");
        candidateFields.add("`__nodes`: " + provenance(bindings, true));
        candidateFields.add("`__relationships`: " + provenance(bindings, false));
        String candidate = "{" + String.join(", ", candidateFields) + "}";
        // Order candidates BEFORE collect(), so the chosen representative obeys
        // the same ranking requested for the final groups. Stable tie-breaking.
        String insideOrder;
        if (selection.random()) {
            insideOrder = "rand()";
        } else if (!selection.orderBy().isEmpty()) {
            insideOrder = selection.orderBy().stream()
                    .map(sort -> "__candidate[" + quoteAsString(sort.alias()) + "]"
                            + (sort.descending() ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", ")) + ", __candidate.__rootEntityId";
        } else {
            insideOrder = "__candidate.__rootEntityId";
        }
        StringBuilder cypher = new StringBuilder(matchAndWhere)
                .append("WITH ").append(group).append(" AS __group, ")
                .append(candidate).append(" AS __candidate\n")
                .append("ORDER BY ").append(insideOrder)
                .append("\nWITH __group, head(collect(__candidate)) AS __chosen\n")
                .append("RETURN ");
        List<String> selectedOutput = new ArrayList<>();
        for (String alias : aliases.keySet())
            selectedOutput.add("__chosen[" + quoteAsString(alias) + "] AS " + quote(alias));
        for (String alias : List.of("__rootEntityId", "__nodes", "__relationships"))
            selectedOutput.add("__chosen[" + quoteAsString(alias) + "] AS " + alias);
        String groupedOrder = selection.random() ? "rand()" :
                selection.orderBy().isEmpty() ? "__rootEntityId" :
                selection.orderBy().stream()
                        .map(o -> quote(o.alias()) + (o.descending() ? " DESC" : " ASC"))
                        .collect(Collectors.joining(", ")) + ", __rootEntityId";
        cypher.append(String.join(", ", selectedOutput)).append("\nORDER BY ")
                .append(groupedOrder)
                .append("\nLIMIT ").append(limit);
        return new Compiled(cypher.toString(), parameters);
    }

    private static void requireProjection(Map<String, String> aliases, String alias) {
        if (!aliases.containsKey(alias)) throw new IllegalArgumentException("Unprojected selection alias: " + alias);
    }

    private static String quoteAsString(String identifier) {
        // Non-user-controlled string literal, derived from a validated projection alias.
        return "'" + identifier.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String provenance(Map<String, Binding> bindings, boolean nodes) {
        return "{" + bindings.entrySet().stream().filter(e -> e.getValue().node() == nodes)
                .map(e -> quote(e.getKey()) + ": " + e.getValue().symbol()
                        + (nodes ? ".nodeKey" : ".relationshipId"))
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
        if (!binding.fields().contains(field))
            throw new IllegalArgumentException("Undeclared field: " + variable + "." + field);
        return binding.symbol() + "." + quote(field);
    }

    private static String quote(String identifier) {
        if (identifier.indexOf('\\') >= 0 || identifier.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Backslashes/control characters forbidden in identifiers");
        return "`" + identifier.replace("`", "``") + "`";
    }
}
