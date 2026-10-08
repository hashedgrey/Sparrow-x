package com.sparrowx.agentic.tools.internal;

import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.InternalGraphNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GraphEvidenceProjector {

    private GraphEvidenceProjector() {}

    public static List<Map<String, Object>> project(
            InternalGraph graph
    ) {
        Map<String, InternalGraphNode> nodes =
                graph.getNodesList().stream()
                        .collect(Collectors.toMap(
                                InternalGraphNode::getNodeId,
                                Function.identity()
                        ));

        return graph.getRelationshipsList().stream()
                .map(edge -> {
                    InternalGraphNode from =
                            nodes.get(edge.getFromNodeId());

                    InternalGraphNode to =
                            nodes.get(edge.getToNodeId());

                    if (from == null || to == null) {
                        throw new IllegalStateException(
                                "Graph relationship references an unresolved node: "
                                        + edge.getRelationshipId()
                        );
                    }

                    return Map.<String, Object>of(
                            "subjectName", from.getLabel(),
                            "subjectType", from.getNodeType().name(),
                            "subjectAttributes", from.getAttributesMap(),

                            "relationship", edge.getRelationshipType().name(),

                            "objectName", to.getLabel(),
                            "objectType", to.getNodeType().name(),
                            "objectAttributes", to.getAttributesMap()
                    );
                })
                .toList();
    }
}