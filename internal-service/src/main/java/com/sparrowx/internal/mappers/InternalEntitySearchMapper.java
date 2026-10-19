package com.sparrowx.internal.mappers;

import com.sparrowx.internal.features.searchinternalentities.SearchInternalEntitiesQuery;
import com.sparrowx.internal.features.searchinternalentities.SearchInternalEntitiesResult;
import com.sparrowx.internal.grpc.*;
import java.util.List;

/** Dynamic graph names are authoritative; enum fields exist for old consumers only. */
public final class InternalEntitySearchMapper {
    private InternalEntitySearchMapper() {}
    public static SearchInternalEntitiesQuery toSearchInternalEntitiesQuery(SearchInternalEntitiesRequest request) {
        List<String> allowed = request.getAllowedNodeTypeNamesCount() > 0
                ? request.getAllowedNodeTypeNamesList()
                : request.getAllowedNodeTypesList().stream()
                     .map(Enum::name).map(InternalEntitySearchMapper::legacyName)
                     .filter(v -> !v.isBlank()).toList();
        String rootType = !request.getRootNodeTypeName().isBlank()
                ? request.getRootNodeTypeName() : legacyName(request.getRootNodeType().name());
        return new SearchInternalEntitiesQuery(
                InternalMapper.tenantId(request.getContext()),
                InternalMapper.actorId(request.getContext()),
                InternalMapper.requestId(request.getContext()),
                request.getQuery(), allowed, request.getRootEntityId(), rootType,
                request.getDepth(), request.getLimit(), request.getIncludeFuzzyMatches(),
                request.getFiltersMap());
    }
    public static SearchInternalEntitiesResponse toSearchInternalEntitiesResponse(SearchInternalEntitiesResult result) {
        if (result == null) throw new IllegalArgumentException("Missing search result");
        var wire = SearchInternalEntitiesResponse.newBuilder()
                .setAmbiguous(result.ambiguous()).setExhaustive(result.exhaustive());
        result.results().forEach(r -> wire.addResults(toProto(r)));
        wire.addAllWarnings(result.warnings());
        return wire.build();
    }
    private static InternalEntitySearchResult toProto(
            com.sparrowx.internal.features.searchinternalentities.InternalEntitySearchResult result) {
        var builder = InternalEntitySearchResult.newBuilder()
                .setEntityId(nullable(result.entityId()))
                .setNodeTypeName(nullable(result.nodeType()))
                .setNodeType(legacyEnum(result.nodeType()))
                .setLabel(nullable(result.label()))
                .setSlug(nullable(result.slug()))
                .setSummary(nullable(result.summary()))
                .setScore(result.score())
                .setMatchReason(nullable(result.matchReason()))
                .setParentEntityId(nullable(result.parentEntityId()))
                .setParentNodeTypeName(nullable(result.parentNodeType()))
                .setParentNodeType(legacyEnum(result.parentNodeType()));
        if (result.attributes() != null) builder.putAllAttributes(result.attributes());
        return builder.build();
    }
    private static String legacyName(String wire) {
        String prefix = "INTERNAL_GRAPH_NODE_TYPE_";
        return wire == null || wire.endsWith("_UNSPECIFIED") || wire.equals("UNRECOGNIZED")
                ? "" : wire.startsWith(prefix) ? wire.substring(prefix.length()) : wire;
    }
    private static InternalGraphNodeType legacyEnum(String type) {
        if (type == null || type.isBlank()) return InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
        try {
            String normalized = type.startsWith("INTERNAL_GRAPH_NODE_TYPE_")
                    ? type : "INTERNAL_GRAPH_NODE_TYPE_" + type;
            return InternalGraphNodeType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // An unfamiliar business type must survive via node_type_name, never be renamed.
            return InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
        }
    }
    private static String nullable(String value) { return value == null ? "" : value; }
}
