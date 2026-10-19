package com.sparrowx.agentic.planning.graph;

import buildingblocks.contracts.GraphQueryRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the LLM's generic query-result selection against the projected
 * output aliases. This does not contain business node/edge semantics.
 */
public final class GraphQuerySelectionBinder {
    private GraphQuerySelectionBinder() {}

    public static GraphQueryRequest.Selection bind(
            Object rawSelection, List<GraphQueryRequest.Projection> outputs, int maxRows) {
        if (rawSelection == null) return null;
        if (!(rawSelection instanceof Map<?, ?> map))
            throw new IllegalArgumentException("selection must be an object");
        List<String> groups = strings(map.get("onePerGroupBy"));
        List<GraphQueryRequest.Sort> sorts = new ArrayList<>();
        if (!(map.get("orderBy") instanceof List<?> items))
            throw new IllegalArgumentException("orderBy must be an array");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> order)
                    || !(order.get("alias") instanceof String alias)
                    || !(order.get("descending") instanceof Boolean descending))
                throw new IllegalArgumentException("Invalid selection ordering");
            sorts.add(new GraphQueryRequest.Sort(alias, descending));
        }
        Object random = map.get("random");
        if (!(random instanceof Boolean randomFlag))
            throw new IllegalArgumentException("random must be boolean");
        Object limit = map.get("limit");
        if (!(limit instanceof Number n) || n.intValue() != n.doubleValue())
            throw new IllegalArgumentException("limit must be an integer");
        int requestedLimit = n.intValue();
        Set<String> projected = new LinkedHashSet<>();
        for (GraphQueryRequest.Projection projection : outputs)
            projected.add(projection.alias());
        if (!projected.containsAll(groups) || sorts.stream().anyMatch(s -> !projected.contains(s.alias())))
            throw new IllegalArgumentException("Selection references an unprojected result alias");
        if (requestedLimit > maxRows)
            throw new IllegalArgumentException("Requested result count exceeds authorized row budget");
        if (groups.isEmpty() && sorts.isEmpty() && !randomFlag && requestedLimit == 0)
            return null; // No-op: preserve IntSvc's stable default result ordering.
        return new GraphQueryRequest.Selection(groups, List.copyOf(sorts), randomFlag, requestedLimit);
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("onePerGroupBy must be an array");
        return list.stream().map(item -> {
            if (!(item instanceof String s) || s.isBlank())
                throw new IllegalArgumentException("Invalid grouping output alias");
            return s;
        }).toList();
    }

    /** Embed as the `selection` property of the resolver's structured output schema. */
    public static Map<String, Object> outputSchema() {
        var string = Map.<String, Object>of("type", "string");
        var bool = Map.<String, Object>of("type", "boolean");
        var sortProps = Map.<String, Object>of("alias", string, "descending", bool);
        var sort = Map.<String, Object>of("type", "object", "properties", sortProps,
                "required", List.of("alias", "descending"), "additionalProperties", false);
        var props = Map.<String, Object>of(
                "onePerGroupBy", Map.of("type", "array", "items", string),
                "orderBy", Map.of("type", "array", "items", sort),
                "random", bool,
                "limit", Map.of("type", "integer", "minimum", 0, "maximum", 10000));
        return Map.of("type", "object", "properties", props,
                "required", List.of("onePerGroupBy", "orderBy", "random", "limit"),
                "additionalProperties", false);
    }
}
