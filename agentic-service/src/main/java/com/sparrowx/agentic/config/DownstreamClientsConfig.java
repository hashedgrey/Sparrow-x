package com.sparrowx.agentic.config;

import com.sparrowx.agentic.adapters.document.DocumentClientResiliencePolicy;
import com.sparrowx.agentic.adapters.internal.InternalClientResiliencePolicy;
import com.sparrowx.document.proto.DocumentServiceGrpc;
import com.sparrowx.internal.grpc.InternalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Configures typed Document and Internal Service gRPC transports.
 *
 * Channels own transport security and message limits only. Downstream adapter
 * policies apply per-call deadlines and failure classification. Neither the
 * channel nor adapters implement an application retry loop.
 */
@Configuration(proxyBeanMethods = false)
public final class DownstreamClientsConfig {

    @Bean
    public InternalClientResiliencePolicy internalClientResiliencePolicy(
            @Value("${sparrowx.internal.deadlines.search:3s}")
            Duration searchDeadline,

            @Value("${sparrowx.internal.deadlines.company-graph:5s}")
            Duration companyGraphDeadline,

            @Value("${sparrowx.internal.deadlines.ontology:5s}")
            Duration ontologyDeadline
    ) {
        return new InternalClientResiliencePolicy(
                Map.of(
                        InternalClientResiliencePolicy.Operation
                                .SEARCH_INTERNAL_ENTITIES,
                        searchDeadline,

                        InternalClientResiliencePolicy.Operation
                                .EXECUTE_INTERNAL_GRAPH_QUERY,
                        companyGraphDeadline,

                        InternalClientResiliencePolicy.Operation
                                .DESCRIBE_INTERNAL_COMPANY_GRAPH,
                        ontologyDeadline
                )
        );
    }

    @Bean(
            name = "documentManagedChannel",
            destroyMethod = "shutdownNow"
    )
    public ManagedChannel documentManagedChannel(
            @Value("${sparrowx.agentic.downstream.document.host}")
            String host,
            @Value("${sparrowx.agentic.downstream.document.port}")
            int port,
            @Value(
                    "${sparrowx.agentic.downstream.document.plaintext:false}"
            )
            boolean plaintext,
            @Value(
                    "${sparrowx.agentic.downstream.document.max-inbound-message-bytes:16777216}"
            )
            int maxInboundMessageBytes
    ) {
        return channel(
                "Document Service",
                host,
                port,
                plaintext,
                maxInboundMessageBytes
        );
    }

    @Bean
    public DocumentServiceGrpc.DocumentServiceBlockingStub
    documentServiceBlockingStub(
            @Qualifier("documentManagedChannel")
            ManagedChannel channel
    ) {
        return DocumentServiceGrpc.newBlockingStub(channel);
    }

    @Bean
    public DocumentClientResiliencePolicy documentClientResiliencePolicy() {
        return new DocumentClientResiliencePolicy(Map.of(
                DocumentClientResiliencePolicy.Operation.UPLOAD_DOCUMENT,
                Duration.ofSeconds(30),

                DocumentClientResiliencePolicy.Operation.GET_DOCUMENT,
                Duration.ofSeconds(10),

                DocumentClientResiliencePolicy.Operation.GET_INGESTION_JOB,
                Duration.ofSeconds(10),

                DocumentClientResiliencePolicy.Operation.SEARCH_DOCUMENT_SPANS,
                Duration.ofSeconds(15),

                DocumentClientResiliencePolicy.Operation.BUILD_DOCUMENT_EVIDENCE,
                Duration.ofSeconds(60),

                DocumentClientResiliencePolicy.Operation.VERIFY_EVIDENCE_GRAPH,
                Duration.ofSeconds(20)
        ));
    }

    @Bean(
            name = "internalManagedChannel",
            destroyMethod = "shutdownNow"
    )
    public ManagedChannel internalManagedChannel(
            @Value("${sparrowx.agentic.downstream.internal.host}")
            String host,
            @Value("${sparrowx.agentic.downstream.internal.port}")
            int port,
            @Value(
                    "${sparrowx.agentic.downstream.internal.plaintext:false}"
            )
            boolean plaintext,
            @Value(
                    "${sparrowx.agentic.downstream.internal.max-inbound-message-bytes:16777216}"
            )
            int maxInboundMessageBytes
    ) {
        return channel(
                "Internal Service",
                host,
                port,
                plaintext,
                maxInboundMessageBytes
        );
    }

    @Bean
    public InternalServiceGrpc.InternalServiceBlockingStub
    internalServiceBlockingStub(
            @Qualifier("internalManagedChannel")
            ManagedChannel channel
    ) {
        return InternalServiceGrpc.newBlockingStub(channel);
    }

    private static ManagedChannel channel(
            String serviceName,
            String host,
            int port,
            boolean plaintext,
            int maxInboundMessageBytes
    ) {
        String normalizedHost = requireHost(serviceName, host);
        requirePort(serviceName, port);
        requireMessageSize(serviceName, maxInboundMessageBytes);

        ManagedChannelBuilder<?> builder =
                ManagedChannelBuilder.forAddress(normalizedHost, port)
                        .maxInboundMessageSize(maxInboundMessageBytes)
                        .disableRetry();

        if (plaintext) {
            builder.usePlaintext();
        } else {
            builder.useTransportSecurity();
        }

        return builder.build();
    }

    private static String requireHost(
            String serviceName,
            String host
    ) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    serviceName + " host must not be blank"
            );
        }

        return host.trim();
    }

    private static void requirePort(
            String serviceName,
            int port
    ) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    serviceName
                            + " port must be between 1 and 65535"
            );
        }
    }

    private static void requireMessageSize(
            String serviceName,
            int maxInboundMessageBytes
    ) {
        if (maxInboundMessageBytes < 1) {
            throw new IllegalArgumentException(
                    serviceName
                            + " max inbound message size must be positive"
            );
        }
    }
}