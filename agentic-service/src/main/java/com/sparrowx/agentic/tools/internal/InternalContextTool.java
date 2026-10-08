package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphResponse;
import com.sparrowx.internal.grpc.SearchInternalEntitiesRequest;
import com.sparrowx.internal.grpc.SearchInternalEntitiesResponse;

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

}