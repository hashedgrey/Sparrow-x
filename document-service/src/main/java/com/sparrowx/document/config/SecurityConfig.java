package com.sparrowx.document.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /*
     * gRPC security is handled separately by gRPC interceptors.
     *
     * This HTTP security config exists mainly for:
     * - actuator endpoints
     * - future REST/admin endpoints
     */
    @Bean
    public SecurityFilterChain documentHttpSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // HTTP service account for the local Next.js server proxy.
                // Replace with your JWT/session authentication before multi-tenant production use.
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}