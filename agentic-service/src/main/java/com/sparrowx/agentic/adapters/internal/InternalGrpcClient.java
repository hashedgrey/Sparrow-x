package com.sparrowx.agentic.adapters.internal;

import com.sparrowx.agentic.adapters.internal.InternalClientResiliencePolicy.Operation;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.*;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class InternalGrpcClient {
    private final InternalServiceGrpc.InternalServiceBlockingStub baseStub;
    private final InternalClientMapper mapper;
    private final InternalClientResiliencePolicy resiliencePolicy;

    public InternalGrpcClient(InternalServiceGrpc.InternalServiceBlockingStub baseStub,
                              InternalClientMapper mapper, InternalClientResiliencePolicy resiliencePolicy) {
        this.baseStub = Objects.requireNonNull(baseStub);
        this.mapper = Objects.requireNonNull(mapper);
        this.resiliencePolicy = Objects.requireNonNull(resiliencePolicy);
    }

    public SearchInternalEntitiesResponse searchInternalEntities(MissionContext context,
                                                                 SearchInternalEntitiesRequest request) {
        var effective = Objects.requireNonNull(request).toBuilder()
                .setContext(mapper.toRequestContext(context)).build();
        return invoke(context, Operation.SEARCH_INTERNAL_ENTITIES, stub -> stub.searchInternalEntities(effective));
    }

    public ReadInternalCompanyGraphResponse readInternalCompanyGraph(MissionContext context,
                                                                     ReadInternalCompanyGraphRequest request) {
        var effective = Objects.requireNonNull(request).toBuilder()
                .setContext(mapper.toRequestContext(context)).build();
        return invoke(context, Operation.READ_INTERNAL_COMPANY_GRAPH, stub -> stub.readInternalCompanyGraph(effective));
    }

    public ExpandInternalCompanyGraphResponse expandInternalCompanyGraph(MissionContext context,
                                                                         ExpandInternalCompanyGraphRequest request) {
        String id = requireRequestId(request.getContext().getRequestId());
        var effective = request.toBuilder().setContext(mapper.toRequestContext(context, id)).build();
        return invoke(context, Operation.EXPAND_INTERNAL_COMPANY_GRAPH,
                stub -> stub.expandInternalCompanyGraph(effective));
    }

    public DescribeInternalCompanyGraphResponse describeInternalCompanyGraph(MissionContext context,
                                                                             DescribeInternalCompanyGraphRequest request) {
        Objects.requireNonNull(request);
        String id = request.getContext().getRequestId();
        var effective = request.toBuilder().setContext(id.isBlank()
                ? mapper.toRequestContext(context) : mapper.toRequestContext(context, id)).build();
        return invoke(context, Operation.DESCRIBE_INTERNAL_COMPANY_GRAPH,
                stub -> stub.describeInternalCompanyGraph(effective));
    }

    public ExecuteInternalCompanyGraphQueryResponse executeInternalCompanyGraphQuery(MissionContext context,
                                                                                     ExecuteInternalCompanyGraphQueryRequest request) {
        Objects.requireNonNull(request);
        String id = requireRequestId(request.getContext().getRequestId());
        var effective = request.toBuilder().setContext(mapper.toRequestContext(context, id)).build();
        return invoke(context, Operation.READ_INTERNAL_COMPANY_GRAPH,
                stub -> stub.executeInternalCompanyGraphQuery(effective));
    }

    private static String requireRequestId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Graph requestId is required");
        return id;
    }

    private <T> T invoke(MissionContext context, Operation operation,
                         Function<InternalServiceGrpc.InternalServiceBlockingStub, T> invocation) {
        Duration deadline = resiliencePolicy.deadlineFor(operation);
        var stub = baseStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(mapper.toMetadata(context)))
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return invocation.apply(stub);
        } catch (StatusRuntimeException exception) {
            throw resiliencePolicy.translate(operation, exception);
        }
    }
}
