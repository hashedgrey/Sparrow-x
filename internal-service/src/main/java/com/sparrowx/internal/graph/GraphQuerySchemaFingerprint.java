package com.sparrowx.internal.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Stable tenant-scoped observed-schema fingerprint; no business type constants. */
public final class GraphQuerySchemaFingerprint {
    private GraphQuerySchemaFingerprint() {}

    public static String of(GraphQuerySchema schema) {
        TreeMap<String, java.util.Set<String>> nodeFields = new TreeMap<>(schema.nodeFields());
        String nodes = nodeFields.entrySet().stream()
                .map(entry -> entry.getKey() + "\u0000" + entry.getValue().stream().sorted()
                        .collect(Collectors.joining("\u0000")))
                .collect(Collectors.joining("\u0001"));
        String relationships = schema.relationshipFields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator
                        .comparing(GraphQuerySchema.Relation::source)
                        .thenComparing(GraphQuerySchema.Relation::type)
                        .thenComparing(GraphQuerySchema.Relation::target)))
                .map(entry -> entry.getKey().source() + "\u0000" + entry.getKey().type()
                        + "\u0000" + entry.getKey().target() + "\u0000"
                        + entry.getValue().stream().sorted().collect(Collectors.joining("\u0000")))
                .collect(Collectors.joining("\u0001"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((nodes + "\u0002" + relationships).getBytes(StandardCharsets.UTF_8));
            return "observed-v1-" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM missing SHA-256", e);
        }
    }
}
