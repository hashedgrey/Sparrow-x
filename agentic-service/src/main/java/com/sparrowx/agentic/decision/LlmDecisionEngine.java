package com.sparrowx.agentic.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sparrowx.agentic.adapters.llm.StructuredLlmClient;
import com.sparrowx.agentic.adapters.llm.StructuredLlmResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured LLM escalation boundary for semantic decisions that cannot be
 * resolved safely by bounded System-1 classification alone.
 *
 * This class performs exactly one model invocation.
 *
 * It does not:
 *
 * - retry;
 * - checkpoint;
 * - own Temporal behavior;
 * - select GOAP/HYBRID;
 * - generate the final user answer.
 */
public final class LlmDecisionEngine {

    private final StructuredLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmDecisionEngine(
            StructuredLlmClient llmClient,
            ObjectMapper objectMapper
    ) {
        this.llmClient =
                Objects.requireNonNull(
                        llmClient,
                        "llmClient must not be null"
                );

        this.objectMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                );
    }

    public Result evaluate(
            Request request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        String userPrompt = serializePrompt(request);

        StructuredLlmResponse response =
                llmClient.complete(
                        new StructuredLlmClient.Request(
                                request.missionId(),
                                request.operationId(),
                                request.idempotencyKey(),
                                SYSTEM_PROMPT,
                                userPrompt(request),
                                request.outputSchema(),
                                request.maxOutputTokens(),
                                request.temperature(),
                                request.metadata()
                        )
                );

        return new Result(
                response.provider() + "/" + response.model(),
                response
        );
    }

    private String userPrompt(
            Request request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        try {
            return objectMapper.writeValueAsString(
                    Map.of(
                            "task", "Resolve only the requested semantic fields.",
                            "state", request.state(),
                            "fieldsToResolve", request.fieldsToResolve()
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize LLM decision request", exception);
        }
    }

    private String serializePrompt(
            Request request
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", request.task());
        payload.put("state", request.state());
        payload.put("fieldsToResolve", request.fieldsToResolve());

        try {
            return objectMapper.writeValueAsString(payload);

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException("Unable to serialize semantic LLM decision input", exception
            );
        }
    }

    private static final String SYSTEM_PROMPT =
            """
            You are a structured semantic decision stage inside SparrowX.

            Resolve only the fields listed in fieldsToResolve.

            Requirements:
            - Use only the supplied state.
            - Do not answer the user's original question.
            - Do not perform the final SparrowX task.
            - Do not invent unavailable enterprise or document facts.
            - Return only data matching the supplied output schema.
            - Boolean/probability classifications must represent the semantic
              requirement being asked about, not whether you personally know
              an answer.
            - Preserve uncertainty rather than fabricating missing targets.
            """;

    public record Request(
            String missionId,
            String operationId,
            String idempotencyKey,
            String task,
            Map<String, Object> state,
            Set<String> fieldsToResolve,
            Map<String, Object> outputSchema,
            int maxOutputTokens,
            double temperature,
            Map<String, String> metadata
    ) {

        public Request {
            missionId = requireText(missionId, "missionId");
            operationId = requireText(operationId, "operationId");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            task = requireText(task, "task");
            state = state == null ? Map.of() : Map.copyOf(state);
            fieldsToResolve = fieldsToResolve == null ? Set.of() : Set.copyOf(fieldsToResolve);
            if (fieldsToResolve.isEmpty()) {
                throw new IllegalArgumentException("fieldsToResolve must not be empty");
            }
            outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
            if (outputSchema.isEmpty()) {
                throw new IllegalArgumentException("outputSchema must not be empty");
            }
            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
            if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Result(String routeKey, StructuredLlmResponse response) {

        public Result {
            routeKey = requireText(routeKey, "routeKey");
            response = Objects.requireNonNull(response, "response must not be null");
        }

        public Map<String, Object> output() {
            return response.parsedOutput();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }

        return value.trim();
    }
}