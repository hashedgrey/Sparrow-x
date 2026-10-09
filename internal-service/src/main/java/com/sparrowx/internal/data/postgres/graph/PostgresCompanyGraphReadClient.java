package com.sparrowx.internal.data.postgres.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public final class PostgresCompanyGraphReadClient {

    private final PostgresCompanyGraphProjectionReader reader;
    private final ObjectMapper json = new ObjectMapper();

    public PostgresCompanyGraphReadClient(
            PostgresCompanyGraphProjectionReader reader
    ) {
        this.reader = Objects.requireNonNull(
                reader,
                "reader is required"
        );
    }

    public InternalGraphContext readCompanyGraph(
            String tenantId,
            String rootEntityId,
            InternalGraphNodeType rootNodeType,
            int depth,
            int limit
    ) {
        if (rootNodeType == null) {
            throw new IllegalArgumentException(
                    "rootNodeType is required"
            );
        }

        var slice = reader.read(
                tenantId,
                rootNodeType.name(),
                rootEntityId,
                depth,
                limit
        );

        return toGraph(
                slice.nodes(),
                slice.edges()
        );
    }

    public ExpansionResult expandCompanyGraph(
            String tenantId,
            Set<String> frontierNodeIds,
            Set<String> relationshipTypes,
            int maxEdges
    ) {
        var slice = reader.expand(
                tenantId,
                frontierNodeIds,
                relationshipTypes,
                maxEdges
        );

        return new ExpansionResult(
                toGraph(slice.nodes(), slice.edges()),
                slice.truncated()
        );
    }

    private InternalGraphContext toGraph(
            List<PostgresCompanyGraphProjectionReader.Node> sourceNodes,
            List<PostgresCompanyGraphProjectionReader.Edge> sourceEdges
    ) {
        List<InternalGraphNode> nodes = sourceNodes.stream()
                .map(node -> new InternalGraphNode(
                        node.nodeKey(),
                        InternalGraphNodeType.from(
                                "INTERNAL_GRAPH_NODE_TYPE_" + node.type()
                        ),
                        node.label(),
                        node.summary(),
                        node.entityId(),
                        attributes(node.attributesJson())
                ))
                .toList();

        List<InternalGraphRelationship> relationships =
                sourceEdges.stream()
                        .map(edge -> new InternalGraphRelationship(
                                edge.relationshipId(),
                                edge.fromKey(),
                                edge.toKey(),
                                InternalGraphRelationshipType.valueOf(
                                        edge.type()
                                ),
                                ""
                        ))
                        .toList();

        return new InternalGraphContext(
                UUID.randomUUID().toString(),
                InternalGraphType.COMPANY,
                nodes,
                relationships,
                Instant.now()
        );
    }

    private Map<String, String> attributes(String rawJson) {
        try {
            JsonNode root = json.readTree(
                    rawJson == null ? "{}" : rawJson
            );

            if (root == null || !root.isObject()) {
                throw new IllegalStateException(
                        "Invalid node attributes JSON"
                );
            }

            Map<String, String> values = new LinkedHashMap<>();

            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();

                if (!value.isNull()) {
                    values.put(
                            entry.getKey(),
                            value.isValueNode()
                                    ? value.asText()
                                    : value.toString()
                    );
                }
            });

            return Map.copyOf(values);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid PostgreSQL graph node attributes",
                    exception
            );
        }
    }

    public record ExpansionResult(
            InternalGraphContext graph,
            boolean truncated
    ) {
        public ExpansionResult {
            Objects.requireNonNull(graph, "graph is required");
        }
    }
}