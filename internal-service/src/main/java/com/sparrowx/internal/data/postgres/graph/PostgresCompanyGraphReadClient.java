package com.sparrowx.internal.data.postgres.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.internal.models.InternalGraphContext;
import com.sparrowx.internal.models.InternalGraphNode;
import com.sparrowx.internal.models.InternalGraphRelationship;
import com.sparrowx.internal.valueobjects.InternalGraphNodeType;
import com.sparrowx.internal.valueobjects.InternalGraphRelationshipType;
import com.sparrowx.internal.valueobjects.InternalGraphType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Adapts bounded PostgreSQL graph slices into existing IntSvc domain contracts. */
@Component
public final class PostgresCompanyGraphReadClient {
    private final PostgresCompanyGraphProjectionReader reader;
    private final ObjectMapper json = new ObjectMapper();

    public PostgresCompanyGraphReadClient(PostgresCompanyGraphProjectionReader reader) {
        this.reader = reader;
    }

    public InternalGraphContext readCompanyGraph(String tenantId, String rootEntityId,
                                                  InternalGraphNodeType rootNodeType,
                                                  int depth, int limit) {
        if (rootNodeType == null) {
            throw new IllegalArgumentException("rootNodeType is required");
        }
        var slice = reader.read(tenantId, rootNodeType.name(), rootEntityId, depth, limit);
        var nodes = slice.nodes().stream().map(n -> new InternalGraphNode(
                n.nodeKey(),
                InternalGraphNodeType.from("INTERNAL_GRAPH_NODE_TYPE_" + n.type()),
                n.label(), n.summary(), n.entityId(),
                attributes(n.attributesJson()))).toList();
        var edges = slice.edges().stream().map(e -> new InternalGraphRelationship(
                e.relationshipId(), e.fromKey(), e.toKey(),
                InternalGraphRelationshipType.valueOf(e.type()), "")).toList();
        return new InternalGraphContext(UUID.randomUUID().toString(),
                InternalGraphType.COMPANY, nodes, edges, Instant.now());
    }

    private Map<String, String> attributes(String rawJson) {
        try {
            JsonNode root = json.readTree(rawJson == null ? "{}" : rawJson);
            if (!root.isObject()) throw new IllegalStateException("Invalid node attributes JSON");
            Map<String, String> values = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) values.put(e.getKey(), e.getValue().asText());
            });
            return Map.copyOf(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid PostgreSQL graph node attributes", e);
        }
    }
}
