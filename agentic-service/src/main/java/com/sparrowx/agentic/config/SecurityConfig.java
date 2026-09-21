package com.sparrowx.agentic.config;

import buildingblocks.infrastructure.grpc.interceptors.GrpcAuthInterceptor;
import buildingblocks.shared.context.AuthContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Configures tenant isolation and human-review authorization.
 *
 * Authentication material is established at the gRPC boundary. Domain and
 * Activity code receives only the normalized caller identity.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        SecurityConfig.AgenticSecurityProperties.class
)
public final class SecurityConfig {


    @FunctionalInterface
    public interface CallerIdentityProvider {

        CallerIdentity currentIdentity();
    }

    @Bean
    public TenantIsolationPolicy tenantIsolationPolicy(
            AgenticSecurityProperties properties
    ) {
        return (identity, requestedTenantId) -> {
            Objects.requireNonNull(
                    identity,
                    "identity must not be null"
            );

            String tenantId = requireText(
                    requestedTenantId,
                    "requestedTenantId"
            );

            if (!properties.isRequireTenantClaim()) {
                return;
            }

            if (!identity.tenantIds().contains(tenantId)) {
                throw new SecurityException(
                        "Caller is not authorized for tenant "
                                + tenantId
                );
            }
        };
    }

    @Bean
    public SecurityFilterChain httpSecurityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/assistant/**")
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/assistant/**"
                        )
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                )
                .httpBasic(withDefaults())
                .formLogin(withDefaults());

        return http.build();
    }

    @Bean
    public CallerIdentityProvider callerIdentityProvider() {
        return () -> {
            AuthContext context =
                    GrpcAuthInterceptor.REQUEST_CTX_KEY.get();

            if (context == null) {
                throw new SecurityException(
                        "Authenticated caller context is unavailable"
                );
            }

            String userId = requireText(
                    context.getUserId(),
                    "authenticated userId"
            );

            String tenantId = requireText(
                    context.getTenantId(),
                    "authenticated tenantId"
            );

            return new CallerIdentity(
                    userId,
                    Set.of(tenantId),
                    context.getRoles()
            );
        };
    }

    @Bean
    public ReviewerAuthorizationPolicy
    reviewerAuthorizationPolicy(
            AgenticSecurityProperties properties
    ) {
        Set<String> allowedRoles =
                normalizeRoles(properties.getReviewerRoles());

        return identity -> {
            Objects.requireNonNull(
                    identity,
                    "identity must not be null"
            );

            boolean authorized = identity.roles().stream()
                    .map(SecurityConfig::normalizeRole)
                    .anyMatch(allowedRoles::contains);

            if (!authorized) {
                throw new SecurityException(
                        "Caller is not authorized to review missions"
                );
            }
        };
    }

    @FunctionalInterface
    public interface TenantIsolationPolicy {

        void requireTenantAccess(
                CallerIdentity identity,
                String requestedTenantId
        );
    }

    @FunctionalInterface
    public interface ReviewerAuthorizationPolicy {

        void requireReviewer(CallerIdentity identity);
    }

    public record CallerIdentity(
            String subject,
            Set<String> tenantIds,
            Set<String> roles
    ) {

        public CallerIdentity {
            subject = requireText(subject, "subject");

            tenantIds = tenantIds == null
                    ? Set.of()
                    : Set.copyOf(tenantIds);

            roles = roles == null
                    ? Set.of()
                    : Set.copyOf(roles);
        }
    }

    @ConfigurationProperties(
            prefix = "sparrowx.agentic.security"
    )
    public static final class AgenticSecurityProperties {

        private boolean requireTenantClaim = true;

        private Set<String> reviewerRoles =
                new LinkedHashSet<>(
                        Set.of("REVIEWER", "ADMIN")
                );

        public boolean isRequireTenantClaim() {
            return requireTenantClaim;
        }

        public void setRequireTenantClaim(
                boolean requireTenantClaim
        ) {
            this.requireTenantClaim = requireTenantClaim;
        }

        public Set<String> getReviewerRoles() {
            return reviewerRoles;
        }

        public void setReviewerRoles(
                Set<String> reviewerRoles
        ) {
            this.reviewerRoles =
                    reviewerRoles == null
                            ? new LinkedHashSet<>()
                            : new LinkedHashSet<>(reviewerRoles);
        }
    }

    private static Set<String> normalizeRoles(
            Set<String> roles
    ) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one reviewer role must be configured"
            );
        }

        Set<String> normalized = new LinkedHashSet<>();

        for (String role : roles) {
            normalized.add(normalizeRole(role));
        }

        return Set.copyOf(normalized);
    }

    private static String normalizeRole(String role) {
        String normalized = requireText(role, "role")
                .trim()
                .toUpperCase();

        return normalized.startsWith("ROLE_")
                ? normalized.substring("ROLE_".length())
                : normalized;
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value;
    }
}