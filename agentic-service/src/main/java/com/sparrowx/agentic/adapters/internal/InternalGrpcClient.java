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

    public InternalGrpcClient(
            InternalServiceGrpc.InternalServiceBlockingStub baseStub,
            InternalClientMapper mapper,
            InternalClientResiliencePolicy resiliencePolicy) {
        this.baseStub = Objects.requireNonNull(baseStub, "baseStub must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.resiliencePolicy = Objects.requireNonNull(
                resiliencePolicy,
                "resiliencePolicy must not be null");
    }

    public SearchInternalEntitiesResponse searchInternalEntities(
            MissionContext context,
            SearchInternalEntitiesRequest request) {
        SearchInternalEntitiesRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context))
                .build();
        return invoke(
                context,
                Operation.SEARCH_INTERNAL_ENTITIES,
                stub -> stub.searchInternalEntities(effectiveRequest));
    }

    public ReadInternalCompanyGraphResponse readInternalCompanyGraph(
            MissionContext context,
            ReadInternalCompanyGraphRequest request) {
        ReadInternalCompanyGraphRequest effectiveRequest = Objects.requireNonNull(request, "request must not be null")
                .toBuilder()
                .setContext(mapper.toRequestContext(context))
                .build();
        return invoke(
                context,
                Operation.READ_INTERNAL_COMPANY_GRAPH,
                stub -> stub.readInternalCompanyGraph(effectiveRequest));
    }


    public ExpandInternalCompanyGraphResponse expandInternalCompanyGraph(
            MissionContext context,
            ExpandInternalCompanyGraphRequest request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String requestId = request.getContext().getRequestId();

        if (requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Expansion requestId must not be blank"
            );
        }

        /*
         * Tenant and actor come from the trusted mission context.
         * Preserve the individual expansion round's request ID.
         */
        ExpandInternalCompanyGraphRequest effectiveRequest =
                request.toBuilder()
                        .setContext(
                                mapper.toRequestContext(context, requestId)
                        )
                        .build();

        return invoke(
                context,
                Operation.EXPAND_INTERNAL_COMPANY_GRAPH,
                stub -> stub.expandInternalCompanyGraph(effectiveRequest)
        );
    }
    private <T> T invoke(
            MissionContext context,
            Operation operation,
            Function<InternalServiceGrpc.InternalServiceBlockingStub, T> invocation) {
        Duration deadline = resiliencePolicy.deadlineFor(operation);
        InternalServiceGrpc.InternalServiceBlockingStub callStub = baseStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(mapper.toMetadata(context)))
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);

        try {
            return invocation.apply(callStub);
        } catch (StatusRuntimeException exception) {
            throw resiliencePolicy.translate(operation, exception);
        }
    }

    public DescribeInternalCompanyGraphResponse describeInternalCompanyGraph(
            MissionContext context,
            DescribeInternalCompanyGraphRequest request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String requestId = request.getContext().getRequestId();

        DescribeInternalCompanyGraphRequest effectiveRequest =
                request.toBuilder()
                        .setContext(requestId.isBlank()
                                ? mapper.toRequestContext(context)
                                : mapper.toRequestContext(context, requestId))
                        .build();

        return invoke(
                context,
                Operation.DESCRIBE_INTERNAL_COMPANY_GRAPH,
                stub -> stub.describeInternalCompanyGraph(effectiveRequest)
        );
    }

}
