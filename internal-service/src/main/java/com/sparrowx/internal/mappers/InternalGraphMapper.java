package com.sparrowx.internal.mappers;

import com.sparrowx.internal.features.getinternalgraphcontext.GetInternalGraphContextQuery;
import com.sparrowx.internal.features.getinternalgraphcontext.GetInternalGraphContextResult;
import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.InternalGraphNode;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import com.sparrowx.internal.grpc.InternalGraphRelationship;
import com.sparrowx.internal.grpc.InternalGraphRelationshipType;
import com.sparrowx.internal.grpc.InternalGraphType;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphResponse;

/** Only company-directory graph RPC conversion remains. */
public final class InternalGraphMapper {
    private InternalGraphMapper() {}

    public static GetInternalGraphContextQuery toReadInternalCompanyGraphQuery(
            ReadInternalCompanyGraphRequest request) {
        return new GetInternalGraphContextQuery(
                InternalMapper.tenantId(request.getContext()),
                InternalMapper.actorId(request.getContext()),
                InternalMapper.requestId(request.getContext()),
                InternalGraphType.INTERNAL_GRAPH_TYPE_COMPANY.name(),
                request.getRootEntityId(),
                request.getRootNodeType().name(),
                request.getDepth(),
                request.getLimit()
        );
    }

    public static ReadInternalCompanyGraphResponse toReadInternalCompanyGraphResponse(
            GetInternalGraphContextResult result) {
        return ReadInternalCompanyGraphResponse.newBuilder()
                .setGraph(toProto(result.graph()))
                .build();
    }

    public static InternalGraph toProto(com.sparrowx.internal.models.InternalGraphContext graph) {
        var builder = InternalGraph.newBuilder()
                .setGraphId(graph.graphId())
                .setGraphType(toProtoGraphType(graph.graphType()))
                .setReadAt(InternalMapper.toTimestamp(graph.readAt()));
        graph.nodes().forEach(node -> builder.addNodes(toProto(node)));
        graph.relationships().forEach(rel -> builder.addRelationships(toProto(rel)));
        return builder.build();
    }

    private static InternalGraphNode toProto(com.sparrowx.internal.models.InternalGraphNode node) {
        return InternalGraphNode.newBuilder()
                .setNodeId(node.nodeId())
                .setNodeType(toProtoNodeType(node.nodeType()))
                .setLabel(node.label())
                .setSummary(node.summary())
                .setEntityId(node.entityId())
                .putAllAttributes(node.attributes())
                .build();
    }

    private static InternalGraphRelationship toProto(
            com.sparrowx.internal.models.InternalGraphRelationship rel) {
        return InternalGraphRelationship.newBuilder()
                .setRelationshipId(rel.relationshipId())
                .setFromNodeId(rel.fromNodeId())
                .setToNodeId(rel.toNodeId())
                .setRelationshipType(toProtoRelationshipType(rel.relationshipType()))
                .setSummary(rel.summary())
                .build();
    }

    private static InternalGraphType toProtoGraphType(Object type) {
        if (type == null) return InternalGraphType.INTERNAL_GRAPH_TYPE_UNSPECIFIED;
        return switch (type.toString()) {
            case "COMPANY", "INTERNAL_GRAPH_TYPE_COMPANY" -> InternalGraphType.INTERNAL_GRAPH_TYPE_COMPANY;
            default -> InternalGraphType.INTERNAL_GRAPH_TYPE_UNSPECIFIED;
        };
    }

    private static InternalGraphNodeType toProtoNodeType(Object type) {
        if (type == null) return InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
        return switch (type.toString()) {
            case "COMPANY", "INTERNAL_GRAPH_NODE_TYPE_COMPANY" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_COMPANY;
            case "ENGINEER", "INTERNAL_GRAPH_NODE_TYPE_ENGINEER" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_ENGINEER;
            case "TEAM", "INTERNAL_GRAPH_NODE_TYPE_TEAM" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_TEAM;
            case "MODULE", "INTERNAL_GRAPH_NODE_TYPE_MODULE" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_MODULE;
            case "REPOSITORY", "INTERNAL_GRAPH_NODE_TYPE_REPOSITORY" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_REPOSITORY;
            case "DOCUMENT", "INTERNAL_GRAPH_NODE_TYPE_DOCUMENT" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_DOCUMENT;
            case "RUNBOOK", "INTERNAL_GRAPH_NODE_TYPE_RUNBOOK" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_RUNBOOK;
            case "PERMISSION", "INTERNAL_GRAPH_NODE_TYPE_PERMISSION" -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_PERMISSION;
            default -> InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
        };
    }

    private static InternalGraphRelationshipType toProtoRelationshipType(Object type) {
        if (type == null) return InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_UNSPECIFIED;
        return switch (type.toString()) {
            case "MEMBER_OF", "INTERNAL_GRAPH_RELATIONSHIP_TYPE_MEMBER_OF" -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_MEMBER_OF;
            case "OWNS", "INTERNAL_GRAPH_RELATIONSHIP_TYPE_OWNS" -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_OWNS;
            case "BELONGS_TO", "INTERNAL_GRAPH_RELATIONSHIP_TYPE_BELONGS_TO" -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_BELONGS_TO;
            case "DOCUMENTS", "INTERNAL_GRAPH_RELATIONSHIP_TYPE_DOCUMENTS" -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_DOCUMENTS;
            case "HAS_RUNBOOK", "INTERNAL_GRAPH_RELATIONSHIP_TYPE_HAS_RUNBOOK" -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_HAS_RUNBOOK;
            default -> InternalGraphRelationshipType.INTERNAL_GRAPH_RELATIONSHIP_TYPE_UNSPECIFIED;
        };
    }
}
