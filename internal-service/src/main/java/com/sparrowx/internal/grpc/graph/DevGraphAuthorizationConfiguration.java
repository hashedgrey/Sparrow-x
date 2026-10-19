package com.sparrowx.internal.grpc.graph;

import buildingblocks.infrastructure.grpc.interceptors.GrpcAuthInterceptor;
import buildingblocks.shared.context.AuthContext;
import com.sparrowx.internal.data.neo4j.Neo4jGraphQueryClient;
import io.grpc.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Local development bridge for the existing header-based gRPC context. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty(name = "internal.graph.neo4j.dev-insecure-auth", havingValue = "true")
public class DevGraphAuthorizationConfiguration {

    @Bean
    AuthenticatedGraphCaller devAuthenticatedGraphCaller() {
        return declared -> {
            if (declared == null) {
                throw Status.UNAUTHENTICATED.withDescription("Graph request context is required")
                        .asRuntimeException();
            }
            AuthContext context = currentContext();
            requireSame(context.getTenantId(), declared.getTenantId(), "tenant");
            requireSame(context.getUserId(), declared.getActorId(), "actor");
            return new AuthenticatedGraphCaller.Identity(context.getTenantId(), context.getUserId());
        };
    }

    @Bean
    Neo4jGraphQueryClient.Authorization devGraphQueryAuthorization() {
        return (tenantId, actorId) -> {
            AuthContext context = currentContext();
            requireSame(context.getTenantId(), tenantId, "tenant");
            requireSame(context.getUserId(), actorId, "actor");
        };
    }

    private static AuthContext currentContext() {
        AuthContext context = GrpcAuthInterceptor.REQUEST_CTX_KEY.get();
        if (context == null) {
            throw Status.UNAUTHENTICATED.withDescription("gRPC identity is unavailable")
                    .asRuntimeException();
        }
        return context;
    }

    private static void requireSame(String actual, String declared, String name) {
        if (actual == null || actual.isBlank() || declared == null || declared.isBlank()
                || !actual.equals(declared)) {
            throw Status.PERMISSION_DENIED.withDescription("Graph " + name + " mismatch")
                    .asRuntimeException();
        }
    }
}
