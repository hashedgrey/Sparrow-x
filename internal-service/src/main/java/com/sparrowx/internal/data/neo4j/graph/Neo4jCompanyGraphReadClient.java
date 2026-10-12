package com.sparrowx.internal.data.neo4j.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.internal.models.InternalGraphContext;
import com.sparrowx.internal.models.InternalGraphNode;
import com.sparrowx.internal.models.InternalGraphRelationship;
import com.sparrowx.internal.valueobjects.InternalGraphNodeType;
import com.sparrowx.internal.valueobjects.InternalGraphRelationshipType;
import com.sparrowx.internal.valueobjects.InternalGraphType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Compatibility adapter for existing read/expand RPCs; use the typed query client for answers. */
@Component
public final class Neo4jCompanyGraphReadClient {
    private final Neo4jCompanyGraphProjectionReader reader;
    private final ObjectMapper json;

    public Neo4jCompanyGraphReadClient(Neo4jCompanyGraphProjectionReader reader, ObjectMapper json) {
        this.reader = Objects.requireNonNull(reader);
        this.json = Objects.requireNonNull(json);
    }

    public InternalGraphContext readCompanyGraph(String tenantId, String rootEntityId,
                                                 InternalGraphNodeType rootNodeType, int depth, int limit) {
        Objects.requireNonNull(rootNodeType, "rootNodeType");
        return toGraph(reader.read(tenantId, rootNodeType.name(), rootEntityId, depth, limit));
    }

    public ExpansionResult expandCompanyGraph(String tenantId, Set<String> frontierNodeIds,
                                              Set<String> relationshipTypes, int maxEdges) {
        var slice = reader.expand(tenantId, frontierNodeIds, relationshipTypes, maxEdges);
        return new ExpansionResult(toGraph(slice), slice.truncated());
    }

    private InternalGraphContext toGraph(Neo4jCompanyGraphProjectionReader.Slice slice) {
        var nodes = slice.nodes().stream().map(node -> new InternalGraphNode(node.nodeKey(),
                InternalGraphNodeType.from("INTERNAL_GRAPH_NODE_TYPE_" + node.type()),
                node.label(), node.summary(), node.entityId(), attributes(node.attributes()))).toList();
        // The current domain/proto relationship contract has no properties map.
        // The raw reader retains properties; typed query projections can read them.
        var edges = slice.edges().stream().map(edge -> new InternalGraphRelationship(edge.relationshipId(),
                edge.fromKey(), edge.toKey(), InternalGraphRelationshipType.valueOf(edge.type()), "")).toList();
        return new InternalGraphContext(UUID.randomUUID().toString(), InternalGraphType.COMPANY, nodes, edges, Instant.now());
    }

    private Map<String, String> attributes(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                result.put(key, value.toString());
            } else {
                try { result.put(key, json.writeValueAsString(value)); }
                catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode graph attribute", e); }
            }
        });
        return Map.copyOf(result);
    }

    public record ExpansionResult(InternalGraphContext graph, boolean truncated) {
        public ExpansionResult { Objects.requireNonNull(graph); }
    }
}
