package com.sparrowx.agentic.adapters.llm;

import com.embabel.agent.core.Usage;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StructuredLlmClient {

    private final LlmService<?> llmService;
    private final ObjectMapper objectMapper;

    public StructuredLlmClient(
            LlmService<?> llmService,
            ObjectMapper objectMapper
    ) {
        this.llmService =
                Objects.requireNonNull(
                        llmService,
                        "llmService must not be null"
                );

        this.objectMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                );
    }

    public StructuredLlmResponse complete(
            Request request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        LlmOptions options =
                LlmOptions.withDefaults()
                        .withMaxTokens(
                                request.maxOutputTokens()
                        )
                        .withTemperature(
                                request.temperature()
                        );

        LlmMessageSender sender =
                llmService.createMessageSender(
                        options
                );

        String schema =
                writeJson(
                        request.outputSchema()
                );

        String systemPrompt =
                request.systemPrompt()
                        + """



                        Return exactly one JSON object.

                        The JSON must satisfy this schema:

                        """
                        + schema
                        + """



                        Do not include markdown fences.
                        Do not include prose before or after the JSON.
                        """;

        LlmMessageResponse response =
                sender.call(
                        List.of(
                                new SystemMessage(
                                        systemPrompt
                                ),
                                new UserMessage(
                                        request.userPrompt()
                                )
                        ),
                        List.of()
                );

        String rawText =
                requireText(
                        response.getTextContent(),
                        "LLM response text"
                );

        Map<String, Object> parsedOutput =
                parseObject(
                        rawText
                );

        Usage usage =
                response.getUsage();

        long inputTokens =
                usage == null
                        || usage.getPromptTokens() == null
                        ? 0L
                        : usage.getPromptTokens();

        long outputTokens =
                usage == null
                        || usage.getCompletionTokens() == null
                        ? 0L
                        : usage.getCompletionTokens();

        Map<String, String> metadata =
                new LinkedHashMap<>(
                        request.metadata()
                );

        metadata.put(
                "missionId",
                request.missionId()
        );

        metadata.put(
                "operationId",
                request.operationId()
        );

        metadata.put(
                "idempotencyKey",
                request.idempotencyKey()
        );

        return new StructuredLlmResponse(
                parsedOutput,
                rawText,
                llmService.getProvider(),
                llmService.getName(),
                "UNKNOWN",
                inputTokens,
                outputTokens,
                0L,
                Map.copyOf(metadata)
        );
    }

    private Map<String, Object> parseObject(
            String raw
    ) {
        String json =
                normalizeJson(
                        raw
                );

        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(
                            json,
                            new TypeReference<>() {
                            }
                    );

            if (parsed == null) {
                throw new IllegalStateException(
                        "LLM returned null structured output"
                );
            }

            return Map.copyOf(
                    parsed
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to parse structured LLM response",
                    exception
            );
        }
    }

    private String writeJson(
            Object value
    ) {
        try {
            return objectMapper.writeValueAsString(
                    value
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to serialize structured-output schema",
                    exception
            );
        }
    }

    private static String normalizeJson(
            String raw
    ) {
        String value =
                requireText(
                        raw,
                        "raw"
                ).trim();

        if (value.startsWith("```")) {
            int newline =
                    value.indexOf('\n');

            if (newline >= 0) {
                value =
                        value.substring(
                                newline + 1
                        );
            }

            int closingFence =
                    value.lastIndexOf(
                            "```"
                    );

            if (closingFence >= 0) {
                value =
                        value.substring(
                                0,
                                closingFence
                        );
            }

            value =
                    value.trim();
        }

        int firstObject =
                value.indexOf('{');

        int lastObject =
                value.lastIndexOf('}');

        if (firstObject >= 0
                && lastObject >= firstObject) {

            value =
                    value.substring(
                            firstObject,
                            lastObject + 1
                    );
        }

        return value;
    }

    public record Request(
            String missionId,
            String operationId,
            String idempotencyKey,
            String systemPrompt,
            String userPrompt,
            Map<String, Object> outputSchema,
            int maxOutputTokens,
            double temperature,
            Map<String, String> metadata
    ) {

        public Request {
            missionId =
                    requireText(
                            missionId,
                            "missionId"
                    );

            operationId =
                    requireText(
                            operationId,
                            "operationId"
                    );

            idempotencyKey =
                    requireText(
                            idempotencyKey,
                            "idempotencyKey"
                    );

            systemPrompt =
                    requireText(
                            systemPrompt,
                            "systemPrompt"
                    );

            userPrompt =
                    requireText(
                            userPrompt,
                            "userPrompt"
                    );

            outputSchema =
                    outputSchema == null
                            ? Map.of()
                            : Map.copyOf(
                            outputSchema
                    );

            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException(
                        "maxOutputTokens must be positive"
                );
            }

            if (!Double.isFinite(temperature)
                    || temperature < 0.0
                    || temperature > 2.0) {

                throw new IllegalArgumentException(
                        "temperature must be between 0.0 and 2.0"
                );
            }

            metadata =
                    metadata == null
                            ? Map.of()
                            : Map.copyOf(
                            metadata
                    );
        }
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }
}