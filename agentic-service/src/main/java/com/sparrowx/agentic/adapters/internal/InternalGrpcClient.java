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

/** Preserves caller request IDs and authenticated metadata on all graph calls. */
public final class InternalGrpcClient {
    private final InternalServiceGrpc.InternalServiceBlockingStub baseStub;
    private final InternalClientMapper mapper;
    private final InternalClientResiliencePolicy resiliencePolicy;
    public InternalGrpcClient(InternalServiceGrpc.InternalServiceBlockingStub stub,
                              InternalClientMapper mapper,InternalClientResiliencePolicy policy) {
        this.baseStub=Objects.requireNonNull(stub);
        this.mapper=Objects.requireNonNull(mapper);
        this.resiliencePolicy=Objects.requireNonNull(policy);
    }
    public SearchInternalEntitiesResponse searchInternalEntities(MissionContext context,
                                                                  SearchInternalEntitiesRequest request) {
        String id=requestId(request.getContext().getRequestId());
        var effective=request.toBuilder().setContext(mapper.toRequestContext(context,id)).build();
        return invoke(context,id,Operation.SEARCH_INTERNAL_ENTITIES,
                stub->stub.searchInternalEntities(effective));
    }
    public DescribeInternalCompanyGraphResponse describeInternalCompanyGraph(MissionContext context,
                                                                             DescribeInternalCompanyGraphRequest request) {
        Objects.requireNonNull(request);
        String raw=request.getContext().getRequestId();
        String id=raw.isBlank()?requestId(context.requestId()):raw;
        var effective=request.toBuilder().setContext(mapper.toRequestContext(context,id)).build();
        return invoke(context,id,Operation.DESCRIBE_INTERNAL_COMPANY_GRAPH,
                stub->stub.describeInternalCompanyGraph(effective));
    }
    public ExecuteInternalCompanyGraphQueryResponse executeInternalCompanyGraphQuery(MissionContext context,
                                                                                     ExecuteInternalCompanyGraphQueryRequest request) {
        String id=requestId(request.getContext().getRequestId());
        var effective=request.toBuilder().setContext(mapper.toRequestContext(context,id)).build();
        return invoke(context,id,Operation.EXECUTE_INTERNAL_GRAPH_QUERY,
                stub->stub.executeInternalCompanyGraphQuery(effective));
    }
    private <T> T invoke(MissionContext context,String requestId,Operation operation,
                         Function<InternalServiceGrpc.InternalServiceBlockingStub,T> call) {
        Duration deadline=resiliencePolicy.deadlineFor(operation);
        var stub=baseStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                        mapper.toMetadata(context,requestId)))
                .withDeadlineAfter(deadline.toMillis(),TimeUnit.MILLISECONDS);
        try { return call.apply(stub); }
        catch(StatusRuntimeException ex) { throw resiliencePolicy.translate(operation,ex); }
    }
    private static String requestId(String id) {
        if (id==null || id.isBlank()) throw new IllegalArgumentException("Graph requestId is required");
        return id;
    }
}
