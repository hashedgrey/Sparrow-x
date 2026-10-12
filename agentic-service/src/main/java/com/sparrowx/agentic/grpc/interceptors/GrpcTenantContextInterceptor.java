package com.sparrowx.agentic.grpc.interceptors;

import buildingblocks.infrastructure.grpc.interceptors.GrpcAuthInterceptor;
import buildingblocks.shared.context.AuthContext;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/** Copies the authenticated tenant into AGSvc's request context. */
public final class GrpcTenantContextInterceptor implements ServerInterceptor {
    public static final Context.Key<String> TENANT_ID = Context.key("sparrowx-agentic-tenant-id");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        AuthContext authContext = GrpcAuthInterceptor.REQUEST_CTX_KEY.get();
        if (authContext == null) {
            return reject(call, Status.UNAUTHENTICATED.withDescription(
                    "Authentication context is unavailable"));
        }
        String tenantId = normalize(authContext.getTenantId());
        if (tenantId.isEmpty()) {
            return reject(call, Status.INVALID_ARGUMENT.withDescription("x-tenant-id is required"));
        }
        return Contexts.interceptCall(Context.current().withValue(TENANT_ID, tenantId),
                call, headers, next);
    }

    public static String currentTenantId() {
        String tenantId = currentTenantIdOrNull();
        if (tenantId == null) throw new SecurityException("Tenant context is unavailable");
        return tenantId;
    }
    public static String currentTenantIdOrNull() {
        String tenantId = TENANT_ID.get();
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
    private static <T> ServerCall.Listener<T> reject(ServerCall<?, ?> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() { };
    }
}
