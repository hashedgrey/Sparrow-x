package com.sparrowx.agentic.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/** Only forwards allowlisted trace fields from durable mission progress metadata. */
public final class TraceSseProjection {
    private TraceSseProjection() { }
    private static final java.util.Set<String> FIELDS = java.util.Set.of(
            "id", "parentId", "sectorId", "sectorName", "kind", "title",
            "status", "source", "startedAt", "endedAt", "message");
    public static Map<String, Object> fromMetadata(Map<String, String> metadata) {
        if (metadata == null || !metadata.containsKey("trace.id")) return Map.of();
        Map<String, Object> envelope = new LinkedHashMap<>();
        for (String field : FIELDS) {
            String value = metadata.get("trace." + field);
            if (value != null && !value.isBlank()) envelope.put(field, value);
        }
        String duration = metadata.get("trace.durationMs");
        if (duration != null) {
            try { envelope.put("durationMs", Math.max(0L, Long.parseLong(duration))); }
            catch (NumberFormatException ignored) { /* malformed diagnostic telemetry */ }
        }
        String version = metadata.get("trace.schemaVersion");
        if ("2".equals(version)) envelope.put("schemaVersion", 2);
        Map<String, String> attrs = new LinkedHashMap<>();
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("trace.attr.") && attrs.size() < 24 && entry.getValue() != null)
                attrs.put(key.substring("trace.attr.".length()), entry.getValue());
        }
        if (!attrs.isEmpty()) envelope.put("attributes", Map.copyOf(attrs));
        return Map.copyOf(envelope);
    }
}
