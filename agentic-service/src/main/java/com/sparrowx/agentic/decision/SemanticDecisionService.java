package com.sparrowx.agentic.decision;

import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ScoreAnswer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic routing layer.
 *
 * System-1 is the default for bounded typed decisions.
 *
 * The structured LLM is used only when:
 *
 * - a bounded answer is insufficiently confident; or
 * - a field explicitly requires open-ended semantic extraction.
 */
public final class SemanticDecisionService {

    private final SystemOneDecisionEngine systemOne;
    private final LlmDecisionEngine llm;

    public SemanticDecisionService(
            SystemOneDecisionEngine systemOne,
            LlmDecisionEngine llm
    ) {
        this.systemOne =
                Objects.requireNonNull(
                        systemOne,
                        "systemOne must not be null"
                );

        this.llm =
                Objects.requireNonNull(
                        llm,
                        "llm must not be null"
                );
    }

    public Result evaluate(
            Request request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        DecisionResponse bounded =
                systemOne.evaluate(
                        request.state(),
                        request.questions()
                );

        Set<String> fieldsToEscalate =
                escalationFields(
                        request,
                        bounded
                );

        if (fieldsToEscalate.isEmpty()) {

            return Result.systemOneOnly(
                    bounded
            );
        }

        Escalation escalation =
                request.escalation();

        if (escalation == null) {
            throw new IllegalStateException(
                    "Semantic fields require LLM escalation but "
                            + "no escalation configuration was supplied: "
                            + fieldsToEscalate
            );
        }

        LlmDecisionEngine.Result llmResult =
                llm.evaluate(
                        new LlmDecisionEngine.Request(
                                escalation.missionId(),
                                escalation.operationId(),
                                escalation.idempotencyKey(),
                                escalation.task(),
                                request.state(),
                                fieldsToEscalate,
                                escalation.outputSchema(),
                                escalation.maxOutputTokens(),
                                escalation.temperature(),
                                escalation.metadata()
                        )
                );

        return new Result(
                bounded,
                fieldsToEscalate,
                llmResult.output(),
                llmResult.routeKey(),
                llmResult.response()
                        .provider(),
                llmResult.response()
                        .model()
        );
    }

    private static Set<String> escalationFields(
            Request request,
            DecisionResponse response
    ) {
        Set<String> result =
                new LinkedHashSet<>(
                        request.llmOnlyFields()
                );

        for (String name :
                request.questions().keySet()) {

            DecisionAnswer answer =
                    response.answers()
                            .get(
                                    name
                            );

            /*
             * Missing bounded output cannot be silently treated as false.
             */
            if (answer == null) {
                result.add(
                        name
                );

                continue;
            }

            double confidence =
                    confidence(
                            answer
                    );

            if (confidence
                    < request.minimumSystemOneConfidence()) {

                result.add(
                        name
                );
            }
        }

        return Set.copyOf(
                result
        );
    }

    /**
     * Normalizes all current SystemOne answer forms into [0,1] confidence.
     *
     * For noul:
     *
     * probability=0.50 -> confidence 0.50
     * probability=0.90 -> confidence 0.90
     * probability=0.10 -> confidence 0.90
     */
    private static double confidence(
            DecisionAnswer answer
    ) {
        if (answer instanceof NoulAnswer noul) {

            return Math.max(
                    noul.probability(),
                    1.0d
                            - noul.probability()
            );
        }

        if (answer instanceof ChoiceAnswer choice) {
            return choice.confidence();
        }

        if (answer instanceof ScoreAnswer score) {
            return score.confidence();
        }

        throw new IllegalStateException(
                "Unsupported System-1 answer type: "
                        + answer.getClass()
                        .getName()
        );
    }

    public record Request(
            Map<String, Object> state,
            Map<String, DecisionQuestion> questions,
            double minimumSystemOneConfidence,
            Set<String> llmOnlyFields,
            Escalation escalation
    ) {

        public Request {
            state =
                    state == null
                            ? Map.of()
                            : Map.copyOf(
                            state
                    );

            questions =
                    questions == null
                            ? Map.of()
                            : Map.copyOf(
                            questions
                    );

            if (questions.isEmpty()) {
                throw new IllegalArgumentException(
                        "questions must not be empty"
                );
            }

            if (!Double.isFinite(
                    minimumSystemOneConfidence
            )
                    || minimumSystemOneConfidence < 0.5d
                    || minimumSystemOneConfidence > 1.0d) {

                throw new IllegalArgumentException(
                        "minimumSystemOneConfidence must be between 0.5 and 1.0"
                );
            }

            llmOnlyFields =
                    llmOnlyFields == null
                            ? Set.of()
                            : Set.copyOf(
                            llmOnlyFields
                    );
        }
    }

    public record Escalation(
            String missionId,
            String operationId,
            String idempotencyKey,
            String task,
            Map<String, Object> outputSchema,
            int maxOutputTokens,
            double temperature,
            Map<String, String> metadata
    ) {

        public Escalation {
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

            task =
                    requireText(
                            task,
                            "task"
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

            if (!Double.isFinite(
                    temperature
            )
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

    public record Result(
            DecisionResponse systemOne,
            Set<String> escalatedFields,
            Map<String, Object> llmOutput,
            String llmRouteKey,
            String llmProvider,
            String llmModel
    ) {

        public Result {
            systemOne =
                    Objects.requireNonNull(
                            systemOne,
                            "systemOne must not be null"
                    );

            escalatedFields =
                    escalatedFields == null
                            ? Set.of()
                            : Set.copyOf(
                            escalatedFields
                    );

            llmOutput =
                    llmOutput == null
                            ? Map.of()
                            : Map.copyOf(
                            llmOutput
                    );

            llmRouteKey =
                    clean(
                            llmRouteKey
                    );

            llmProvider =
                    clean(
                            llmProvider
                    );

            llmModel =
                    clean(
                            llmModel
                    );
        }

        public static Result systemOneOnly(
                DecisionResponse response
        ) {
            return new Result(
                    response,
                    Set.of(),
                    Map.of(),
                    "",
                    "",
                    ""
            );
        }

        public boolean escalated() {
            return !escalatedFields.isEmpty();
        }

        /**
         * Returns the effective probability for a bounded noul field.
         *
         * If the field was escalated, the LLM output wins.
         *
         * Supported LLM forms:
         *
         * 0.0 .. 1.0
         * true / false
         */
        public double probability(
                String field
        ) {
            field =
                    requireText(
                            field,
                            "field"
                    );

            if (escalatedFields.contains(
                    field
            )) {

                Object value =
                        llmOutput.get(
                                field
                        );

                if (value instanceof Number number) {

                    double probability =
                            number.doubleValue();

                    requireProbability(
                            probability,
                            field
                    );

                    return probability;
                }

                if (value instanceof Boolean bool) {
                    return bool
                            ? 1.0d
                            : 0.0d;
                }

                throw new IllegalStateException(
                        "LLM escalation output for '"
                                + field
                                + "' must be a probability or boolean"
                );
            }

            return systemOne.noulValue(
                    field
            );
        }

        public List<String> stringList(
                String field
        ) {
            field =
                    requireText(
                            field,
                            "field"
                    );

            Object value =
                    llmOutput.get(
                            field
                    );

            if (value == null) {
                return List.of();
            }

            if (!(value instanceof List<?> values)) {
                throw new IllegalStateException(
                        "semantic field '"
                                + field
                                + "' must be an array"
                );
            }

            List<String> result =
                    new ArrayList<>();

            for (Object item :
                    values) {

                if (!(item instanceof String text)
                        || text.isBlank()) {

                    continue;
                }

                result.add(
                        text.trim()
                );
            }

            return List.copyOf(
                    result
            );
        }

        public Map<String, Object> metadata() {
            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "systemOneProvider",
                    systemOne.provider()
            );

            result.put(
                    "systemOneModel",
                    systemOne.model()
            );

            result.put(
                    "systemOneRequestId",
                    systemOne.requestId()
            );

            result.put(
                    "semanticEscalated",
                    escalated()
            );

            result.put(
                    "escalatedFields",
                    escalatedFields
            );

            if (escalated()) {
                result.put(
                        "llmRoute",
                        llmRouteKey
                );

                result.put(
                        "llmProvider",
                        llmProvider
                );

                result.put(
                        "llmModel",
                        llmModel
                );
            }

            return Map.copyOf(
                    result
            );
        }
    }

    private static void requireProbability(
            double value,
            String field
    ) {
        if (!Double.isFinite(
                value
        )
                || value < 0.0d
                || value > 1.0d) {

            throw new IllegalStateException(
                    field
                            + " probability must be between 0.0 and 1.0"
            );
        }
    }

    private static String requireText(
            String value,
            String field
    ) {
        String result =
                clean(
                        value
                );

        if (result.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return result;
    }

    private static String clean(
            String value
    ) {
        return value == null
                ? ""
                : value.trim();
    }
}