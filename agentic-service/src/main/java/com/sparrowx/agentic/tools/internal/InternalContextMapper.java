package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.internal.grpc.InternalEntitySearchResult;
import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.InternalGraphNode;
import com.sparrowx.internal.grpc.InternalGraphRelationship;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphResponse;
import com.sparrowx.internal.grpc.SearchInternalEntitiesResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalContextMapper {

    private static final String INTERNAL_SERVICE = "internal-service";

    public List<EvidenceRef> fromSearch(
            SearchInternalEntitiesResponse response) {

        if (response == null) {
            throw new IllegalArgumentException(
                    "response must not be null");
        }

        return deduplicate(
                response.getResultsList()
                        .stream()
                        .map(this::fromEntity)
                        .toList());
    }

    public List<EvidenceRef> fromCompanyGraph(
            ReadInternalCompanyGraphResponse response) {

        if (response == null || !response.hasGraph()) {
            throw new IllegalArgumentException(
                    "company graph response must contain a graph");
        }

        return fromGraph(response.getGraph());
    }


    public EvidenceRef fromEntity(InternalEntitySearchResult entity) {
        if (entity == null || entity.getEntityId().isBlank()) {
            throw new IllegalArgumentException(
                    "entity id must not be blank");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.putAll(entity.getAttributesMap());
        attributes.put("nodeType", entity.getNodeType().name());
        attributes.put("slug", entity.getSlug());
        attributes.put("summary", entity.getSummary());
        attributes.put("score", entity.getScore());
        attributes.put("matchReason", entity.getMatchReason());
        attributes.put(
                "parentNodeType",
                entity.getParentNodeType().name());

        return new EvidenceRef(
                "internal-entity:" + entity.getEntityId(),
                EvidenceSourceType.INTERNAL_ENTITY,
                INTERNAL_SERVICE,
                entity.getEntityId(),
                firstPresent(
                        entity.getAttributesMap(),
                        "document_external_ref",
                        "repository_url",
                        "uri"),
                "",
                entity.getEntityId(),
                entity.getParentEntityId(),
                entity.getLabel(),
                0,
                0,
                "",
                "",
                "",
                attributes);
    }

    public List<EvidenceRef> fromGraph(InternalGraph graph) {
        if (graph == null || graph.getGraphId().isBlank()) {
            throw new IllegalArgumentException(
                    "graph id must not be blank");
        }

        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(graphEvidence(graph));

        graph.getNodesList()
                .stream()
                .map(node -> nodeEvidence(node, graph.getGraphId()))
                .forEach(evidence::add);

        return deduplicate(evidence);
    }

    private EvidenceRef graphEvidence(InternalGraph graph) {
        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put("graphType", graph.getGraphType().name());
        attributes.put(
                "readAtEpochSecond",
                graph.getReadAt().getSeconds());
        attributes.put(
                "readAtNano",
                graph.getReadAt().getNanos());
        attributes.put(
                "nodes",
                graph.getNodesList()
                        .stream()
                        .map(this::nodeAttributes)
                        .toList());
        attributes.put(
                "relationships",
                graph.getRelationshipsList()
                        .stream()
                        .map(this::relationshipAttributes)
                        .toList());
        attributes.put(
                "displayFacts",
                GraphEvidenceProjector.project(graph)
        );

        return new EvidenceRef(
                "internal-graph:" + graph.getGraphId(),
                EvidenceSourceType.INTERNAL_GRAPH,
                INTERNAL_SERVICE,
                graph.getGraphId(),
                "",
                "",
                graph.getGraphId(),
                "",
                graph.getGraphType().name(),
                0,
                0,
                "",
                "",
                "",
                attributes);
    }

    private EvidenceRef nodeEvidence(
            InternalGraphNode node,
            String graphId) {

        return new EvidenceRef(
                "internal-entity:" + node.getEntityId(),
                EvidenceSourceType.INTERNAL_ENTITY,
                INTERNAL_SERVICE,
                node.getEntityId(),
                "",
                "",
                node.getEntityId(),
                graphId,
                node.getLabel(),
                0,
                0,
                "",
                "",
                "",
                nodeAttributes(node));
    }

    private Map<String, Object> nodeAttributes(InternalGraphNode node) {
        Map<String, Object> attributes = new LinkedHashMap<>();

        // Preserve structured PostgreSQL graph metadata.
        attributes.putAll(node.getAttributesMap());

        attributes.put("nodeId", node.getNodeId());
        attributes.put("entityId", node.getEntityId());
        attributes.put("nodeType", node.getNodeType().name());
        attributes.put("label", node.getLabel());
        attributes.put("summary", node.getSummary());


        return Map.copyOf(attributes);
    }

    private Map<String, Object> relationshipAttributes(
            InternalGraphRelationship relationship) {

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(
                "relationshipId",
                relationship.getRelationshipId());
        attributes.put(
                "fromNodeId",
                relationship.getFromNodeId());
        attributes.put(
                "toNodeId",
                relationship.getToNodeId());
        attributes.put(
                "relationshipType",
                relationship.getRelationshipType().name());
        attributes.put("summary", relationship.getSummary());

        return Map.copyOf(attributes);
    }

    private static List<EvidenceRef> deduplicate(
            List<EvidenceRef> evidence) {

        Map<String, EvidenceRef> byId = new LinkedHashMap<>();

        evidence.forEach(item ->
                byId.putIfAbsent(item.evidenceId(), item));

        return List.copyOf(byId.values());
    }

    private static String firstPresent(
            Map<String, String> values,
            String... keys) {

        for (String key : keys) {
            String value = values.get(key);

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "";
    }
}