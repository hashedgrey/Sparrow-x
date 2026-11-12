package com.sparrowx.agentic.adapters.internal;

import com.sparrowx.agentic.adapters.internal.InternalClientResiliencePolicy.Operation;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.internal.grpc.*;
import io.grpc.StatusRuntimeException;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
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
        return executeInternalCompanyGraphQuery(context, request, null);
    }
    public ExecuteInternalCompanyGraphQueryResponse executeInternalCompanyGraphQuery(MissionContext context,
                                                                                     ExecuteInternalCompanyGraphQueryRequest request,
                                                                                     Deadline batchDeadline) {
        String id=requestId(request.getContext().getRequestId());
        var effective=request.toBuilder().setContext(mapper.toRequestContext(context,id)).build();
        return invoke(context,id,Operation.EXECUTE_INTERNAL_GRAPH_QUERY,batchDeadline,
                stub->stub.executeInternalCompanyGraphQuery(effective));
    }
    private <T> T invoke(MissionContext context,String requestId,Operation operation,
                         Function<InternalServiceGrpc.InternalServiceBlockingStub,T> call) {
        return invoke(context, requestId, operation, null, call);
    }
    private <T> T invoke(MissionContext context,String requestId,Operation operation,Deadline batchDeadline,
                         Function<InternalServiceGrpc.InternalServiceBlockingStub,T> call) {
        Duration configured=resiliencePolicy.deadlineFor(operation);
        Deadline deadline=Deadline.after(configured.toNanos(),TimeUnit.NANOSECONDS);
        if (batchDeadline != null) deadline=deadline.minimum(batchDeadline);
        if (deadline.isExpired()) throw resiliencePolicy.translate(operation,
                Status.DEADLINE_EXCEEDED.withDescription("Graph batch deadline exhausted").asRuntimeException());
        var stub=baseStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                        mapper.toMetadata(context,requestId)))
                .withDeadline(deadline);
        try { return call.apply(stub); }
        catch(StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED)
                throw new SecurityException("Internal service authorization was denied", ex);
            if (code == Status.Code.CANCELLED) {
                CancellationException cancelled = new CancellationException("Internal service call was cancelled");
                cancelled.initCause(ex);
                throw cancelled;
            }
            throw resiliencePolicy.translate(operation,ex);
        }
    }
    private static String requestId(String id) {
        if (id==null || id.isBlank()) throw new IllegalArgumentException("Graph requestId is required");
        return id;
    }
}
