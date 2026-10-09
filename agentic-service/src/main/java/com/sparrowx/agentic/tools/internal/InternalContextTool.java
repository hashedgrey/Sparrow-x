package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.*;

import java.util.Objects;

public final class InternalContextTool {

    private final InternalGrpcClient client;

    public InternalContextTool(InternalGrpcClient client) {
        this.client = Objects.requireNonNull(
                client,
                "client must not be null");
    }

    public SearchInternalEntitiesResponse searchEntities(
            MissionContext context,
            SearchInternalEntitiesRequest request) {

        return client.searchInternalEntities(context, request);
    }

    public ReadInternalCompanyGraphResponse readCompanyGraph(
            MissionContext context,
            ReadInternalCompanyGraphRequest request) {

        return client.readInternalCompanyGraph(context, request);
    }

    public ExpandInternalCompanyGraphResponse expandCompanyGraph(
            MissionContext context,
            ExpandInternalCompanyGraphRequest request
    ) {
        return client.expandInternalCompanyGraph(context, request);
    }

    public DescribeInternalCompanyGraphResponse describeCompanyGraph(
            MissionContext context,
            DescribeInternalCompanyGraphRequest request
    ) {
        return client.describeInternalCompanyGraph(context, request);
    }


}