package com.sparrowx.internal.models;

import com.sparrowx.internal.valueobjects.InternalGraphNodeType;
import java.util.Map;

/** Backwards compatible five-argument constructor; new metadata supports role/URL filters. */
public record InternalGraphNode(
        String nodeId,
        InternalGraphNodeType nodeType,
        String label,
        String summary,
        String entityId,
        Map<String, String> attributes
) {
    public InternalGraphNode {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        if (nodeType == null) throw new IllegalArgumentException("nodeType is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("entityId is required");
        summary = summary == null ? "" : summary.trim();
        nodeId = nodeId.trim();
        label = label.trim();
        entityId = entityId.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public InternalGraphNode(String nodeId, InternalGraphNodeType nodeType,
                             String label, String summary, String entityId) {
        this(nodeId, nodeType, label, summary, entityId, Map.of());
    }
}
