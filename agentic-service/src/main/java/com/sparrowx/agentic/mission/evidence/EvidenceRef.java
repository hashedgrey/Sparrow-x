package com.sparrowx.agentic.mission.evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalized source provenance included in a mission result.
 */
public record EvidenceRef(
        String evidenceId,
        EvidenceSourceType sourceType,
        String sourceService,
        String sourceId,
        String sourceUri,
        String artifactId,
        String objectId,
        String parentObjectId,
        String locationLabel,
        int pageStart,
        int pageEnd,
        String section,
        String chunkId,
        String sha256,
        Map<String, Object> attributes
) {

    public EvidenceRef {
        evidenceId = nullToEmpty(evidenceId);
        sourceType = sourceType == null ? EvidenceSourceType.UNSPECIFIED : sourceType;
        sourceService = nullToEmpty(sourceService);
        sourceId = nullToEmpty(sourceId);
        sourceUri = nullToEmpty(sourceUri);
        artifactId = nullToEmpty(artifactId);
        objectId = nullToEmpty(objectId);
        parentObjectId = nullToEmpty(parentObjectId);
        locationLabel = nullToEmpty(locationLabel);
        section = nullToEmpty(section);
        chunkId = nullToEmpty(chunkId);
        sha256 = nullToEmpty(sha256);
        attributes = immutableStruct(attributes);
    }

    public EvidenceRef withEvidenceId(String assignedEvidenceId) {
        return new EvidenceRef(
                assignedEvidenceId,
                sourceType,
                sourceService,
                sourceId,
                sourceUri,
                artifactId,
                objectId,
                parentObjectId,
                locationLabel,
                pageStart,
                pageEnd,
                section,
                chunkId,
                sha256,
                attributes
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Prevent mutation of nested evidence after provenance has been recorded. */
    private static Map<String, Object> immutableStruct(Map<String, Object> value) {
        if (value == null) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(key, immutableValue(item)));
        // Unlike Map.copyOf, permits legitimate null projected graph values.
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            values.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> values) {
            Set<Object> copy = new LinkedHashSet<>();
            values.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }
}
