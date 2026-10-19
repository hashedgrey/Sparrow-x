package com.sparrowx.agentic.adapters.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class InternalClientResiliencePolicy {

    private final Map<Operation, Duration> deadlines;

    public InternalClientResiliencePolicy(Map<Operation, Duration> deadlines) {
        Objects.requireNonNull(deadlines, "deadlines must not be null");

        EnumMap<Operation, Duration> configured = new EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            Duration candidate = deadlines.get(operation);

            Duration deadline = Objects.requireNonNull(
                    candidate,
                    () -> "missing deadline for " + operation
            );

            if (deadline.isZero() || deadline.isNegative()
                    || deadline.toMillis() == 0) {
                throw new IllegalArgumentException(
                        "deadline must be at least one millisecond for " + operation
                );
            }
            configured.put(operation, deadline);
        }
        this.deadlines = Map.copyOf(configured);
    }
    public Duration deadlineFor(Operation operation) {
        return deadlines.get(Objects.requireNonNull(operation, "operation must not be null"));
    }

    public Failure classify(StatusRuntimeException exception) {
        Objects.requireNonNull(exception, "exception must not be null");

        Status.Code code = exception.getStatus().getCode();
        FailureKind kind = switch (code) {
            case CANCELLED -> FailureKind.CANCELLED;
            case DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED,
                 INTERNAL, UNAVAILABLE, UNKNOWN -> FailureKind.RETRYABLE;
            default -> FailureKind.NON_RETRYABLE;
        };

        return new Failure(kind, code, safeDescription(exception));
    }

    public InternalClientException translate(Operation operation, StatusRuntimeException exception) {
        return new InternalClientException(operation, classify(exception), exception);
    }

    private static String safeDescription(StatusRuntimeException exception) {
        String description = exception.getStatus().getDescription();
        return description == null || description.isBlank()
                ? exception.getStatus().getCode().name()
                : description;
    }

    public enum Operation {
        SEARCH_INTERNAL_ENTITIES,
        DESCRIBE_INTERNAL_COMPANY_GRAPH,
        EXECUTE_INTERNAL_GRAPH_QUERY
    }

    public enum FailureKind {
        RETRYABLE,
        NON_RETRYABLE,
        CANCELLED
    }

    public record Failure(FailureKind kind, Status.Code statusCode, String description) {
        public Failure {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(statusCode, "statusCode must not be null");
            description = description == null ? "" : description;
        }

        public boolean retryable() {
            return kind == FailureKind.RETRYABLE;
        }
    }

    public static final class InternalClientException extends RuntimeException {
        private final Operation operation;
        private final Failure failure;

        private InternalClientException(
                Operation operation,
                Failure failure,
                StatusRuntimeException cause) {
            super("internal-service " + operation + " failed: " + failure.description(), cause);
            this.operation = Objects.requireNonNull(operation, "operation must not be null");
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        public Operation operation() {
            return operation;
        }

        public Failure failure() {
            return failure;
        }
    }
}
