package com.sparrowx.agentic.planning.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A queryable, bounded, immutable view of the tenant's declared graph schema.
 * All entity types and relationship names come from IntSvc metadata, not AGSvc constants.
 * A generated model never supplies an executable edge: it selects an option ID returned here.
 */
public final class GraphSchemaCatalog {
    public enum Direction { IN, OUT }

    public record Relation(String source, String name, String target) {
        public Relation {
            source = nonblank(source, "relation.source");
            name = nonblank(name, "relation.name");
            target = nonblank(target, "relation.target");
        }
    }

    public record Hop(String fromType, String relation, Direction direction, String toType) {
        public Hop {
            fromType = nonblank(fromType, "hop.fromType");
            relation = nonblank(relation, "hop.relation");
            direction = Objects.requireNonNull(direction, "hop.direction");
            toType = nonblank(toType, "hop.toType");
        }
        String signature() {
            return fromType + ":" + direction + ":" + relation + ":" + toType;
        }
    }

    public record PathOption(String id, String fromType, String toType, List<Hop> hops) {
        public PathOption {
            id = nonblank(id, "path.id");
            fromType = nonblank(fromType, "path.fromType");
            toType = nonblank(toType, "path.toType");
            hops = List.copyOf(hops);
        }
        public String description() {
            return hops.isEmpty() ? fromType + " (same node)" :
                    fromType + hops.stream().map(h -> " -[" + h.relation() + "/" + h.direction() + "]-> " + h.toType())
                            .collect(Collectors.joining());
        }
    }

    public record Options(List<PathOption> paths, boolean exhaustive) {
        public Options {
            paths = List.copyOf(paths);
        }
        /** Does not silently select the shortest among multiple distinct semantic paths. */
        public PathOption uniqueOrThrow() {
            if (!exhaustive) throw new IllegalStateException("Path catalog was truncated");
            if (paths.size() != 1) throw new IllegalStateException(
                    "Expected a unique semantic path, found " + paths.size() + " options");
            return paths.getFirst();
        }
        /** Use only a path ID obtained from this catalog; never accept arbitrary generated edges. */
        public PathOption select(String pathId) {
            if (!exhaustive) throw new IllegalStateException("Path catalog was truncated");
            return paths.stream().filter(p -> p.id().equals(pathId)).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Unknown schema path ID: " + pathId));
        }
    }

    private record State(String type, List<Hop> hops, Set<String> visitedTypes) { }
    private final Map<String, List<Hop>> outgoing;
    private final Set<String> nodeTypes;

    public GraphSchemaCatalog(List<Relation> relations, Set<String> nodeTypes) {
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(nodeTypes, "nodeTypes");
        Set<String> types = new HashSet<>();
        for (String type : nodeTypes) types.add(nonblank(type, "node type"));
        this.nodeTypes = Set.copyOf(types);
        Map<String, List<Hop>> adjacency = new HashMap<>();
        for (Relation r : relations) {
            if (!types.contains(r.source()) || !types.contains(r.target())) {
                throw new IllegalArgumentException("Schema relation references undeclared node type: " + r);
            }
            adjacency.computeIfAbsent(r.source(), k -> new ArrayList<>())
                    .add(new Hop(r.source(), r.name(), Direction.OUT, r.target()));
            adjacency.computeIfAbsent(r.target(), k -> new ArrayList<>())
                    .add(new Hop(r.target(), r.name(), Direction.IN, r.source()));
        }
        Map<String, List<Hop>> copy = new HashMap<>();
        adjacency.forEach((k,v) -> copy.put(k, v.stream().distinct()
                .sorted(Comparator.comparing(Hop::signature)).toList()));
        this.outgoing = Map.copyOf(copy);
    }

    /**
     * Lists candidate *schema* paths (not graph results). Does not interpret English semantics.
     * Allows revisiting a node TYPE only at the final hop, e.g. ENGINEER->TEAM->ENGINEER.
     * A non-exhaustive result must not be used for semantic selection.
     */
    public Options paths(String fromType, String toType, int maxHops, int maxOptions) {
        fromType = nonblank(fromType, "fromType");
        toType = nonblank(toType, "toType");
        if (!nodeTypes.contains(fromType) || !nodeTypes.contains(toType))
            throw new IllegalArgumentException("Unknown schema type");
        if (maxHops < 0 || maxHops > 32 || maxOptions < 1)
            throw new IllegalArgumentException("Invalid schema search bounds");

        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(new State(fromType, List.of(), Set.of(fromType)));
        Map<String, PathOption> found = new LinkedHashMap<>();
        // Bound search effort even when the schema has many cycles/branches but no target.
        long statesBudget = Math.max(1_000L, Math.multiplyExact((long) maxOptions, 64L * Math.max(1, maxHops)));
        long visitedStates = 0;
        while (!queue.isEmpty()) {
            if (++visitedStates > statesBudget)
                return new Options(List.copyOf(found.values()), false);
            State current = queue.removeFirst();
            if (current.type().equals(toType)) {
                PathOption option = option(fromType, toType, current.hops());
                found.putIfAbsent(option.id(), option);
                if (found.size() > maxOptions)
                    return new Options(found.values().stream().limit(maxOptions).toList(), false);
                // Continue exploring nonzero paths from the original root to support
                // returning other nodes of the same type; otherwise stop at target.
                if (!current.hops().isEmpty()) continue;
            }
            if (current.hops().size() == maxHops) continue;
            for (Hop hop : outgoing.getOrDefault(current.type(), List.of())) {
                boolean repeated = current.visitedTypes().contains(hop.toType());
                if (repeated && !hop.toType().equals(toType)) continue;
                // Prevent unbounded schema cycles, even when source == target.
                List<Hop> nextHops = new ArrayList<>(current.hops());
                nextHops.add(hop);
                Set<String> nextVisited = new HashSet<>(current.visitedTypes());
                nextVisited.add(hop.toType());
                queue.addLast(new State(hop.toType(), List.copyOf(nextHops), Set.copyOf(nextVisited)));
            }
        }
        return new Options(List.copyOf(found.values()), true);
    }

    /** Validate an opaque choice against the same tenant schema snapshot. */
    public PathOption requirePath(String fromType, String toType, int maxHops, int maxOptions, String selectedId) {
        return paths(fromType, toType, maxHops, maxOptions).select(selectedId);
    }

    private static PathOption option(String from, String to, List<Hop> hops) {
        String canonical = from + "|" + to + "|" + hops.stream().map(Hop::signature).collect(Collectors.joining("|"));
        return new PathOption("path_" + hexSha256(canonical).substring(0, 20), from, to, hops);
    }
    private static String hexSha256(String input) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM missing SHA-256", e);
        }
    }
    private static String nonblank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value.trim();
    }
}
