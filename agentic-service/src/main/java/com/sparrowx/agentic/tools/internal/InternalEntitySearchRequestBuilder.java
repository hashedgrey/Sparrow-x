package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.adapters.internal.InternalClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.SearchInternalEntitiesRequest;
import java.util.*;

/** Dynamic root search: no protobuf entity-type enums in mission dataflow. */
public final class InternalEntitySearchRequestBuilder {
    private final InternalClientMapper clientMapper;
    public InternalEntitySearchRequestBuilder(InternalClientMapper mapper) {
        clientMapper=Objects.requireNonNull(mapper);
    }
    public SearchInternalEntitiesRequest build(MissionContext context, SearchSpec spec) {
        Objects.requireNonNull(context); Objects.requireNonNull(spec);
        return SearchInternalEntitiesRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context,spec.requestId()))
                .setQuery(spec.query())
                .addAllAllowedNodeTypeNames(spec.allowedNodeTypes())
                .setRootEntityId(spec.rootEntityId())
                .setRootNodeTypeName(spec.rootNodeType())
                .setDepth(spec.depth()).setLimit(spec.limit())
                .setIncludeFuzzyMatches(spec.includeFuzzyMatches())
                .putAllFilters(spec.filters()).build();
    }
    public record SearchSpec(String requestId,String query,List<String> allowedNodeTypes,
                             String rootEntityId,String rootNodeType,int depth,int limit,
                             boolean includeFuzzyMatches,Map<String,String> filters) {
        public SearchSpec {
            requestId=require(requestId,"requestId"); query=require(query,"query");
            allowedNodeTypes=List.copyOf(Objects.requireNonNull(allowedNodeTypes));
            allowedNodeTypes.forEach(t->require(t,"allowed node type"));
            rootEntityId=rootEntityId==null ? "" : rootEntityId;
            rootNodeType=rootNodeType==null ? "" : rootNodeType;
            if (!rootEntityId.isBlank()) require(rootNodeType,"root node type");
            if (depth<0 || limit<1) throw new IllegalArgumentException("Invalid search bounds");
            filters=filters==null?Map.of():Map.copyOf(filters);
        }
    }
    private static String require(String s,String name) {
        if (s==null || s.isBlank()) throw new IllegalArgumentException(name+" required");
        return s.trim();
    }
}
