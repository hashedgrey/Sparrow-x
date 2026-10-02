package com.sparrowx.agentic.systemone;

import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ScoreAnswer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class HttpSystemOneDecisionClient
        implements SystemOneDecisionClient {

    public enum Protocol {
        LAYA(
                "/predict",
                false
        ),

        JEV(
                "/v1/systemone",
                true
        );

        private final String path;
        private final boolean sendModel;

        Protocol(
                String path,
                boolean sendModel
        ) {
            this.path = path;
            this.sendModel = sendModel;
        }

        public String path() {
            return path;
        }

        public boolean sendModel() {
            return sendModel;
        }
    }

    private final String provider;
    private final Protocol protocol;
    private final String model;
    private final RestClient restClient;

    public HttpSystemOneDecisionClient(
            String provider,
            Protocol protocol,
            String baseUrl,
            String apiKey,
            String model
    ) {
        this.provider =
                requireText(
                        provider,
                        "provider"
                );

        this.protocol =
                Objects.requireNonNull(
                        protocol,
                        "protocol must not be null"
                );

        this.model =
                clean(
                        model
                );

        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl(
                                requireText(
                                        baseUrl,
                                        "baseUrl"
                                )
                        );

        String normalizedKey =
                clean(
                        apiKey
                );

        if (!normalizedKey.isBlank()) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                            + normalizedKey
            );
        }

        this.restClient =
                builder.build();
    }

    @Override
    public DecisionResponse evaluate(
            Map<String, ?> state,
            Map<String, ? extends DecisionQuestion> questions
    ) {
        Objects.requireNonNull(
                state,
                "state must not be null"
        );

        Objects.requireNonNull(
                questions,
                "questions must not be null"
        );

        if (questions.isEmpty()) {
            throw new IllegalArgumentException(
                    "questions must not be empty"
            );
        }

        Map<String, Object> wireQuestions =
                new LinkedHashMap<>();

        questions.forEach(
                (name, question) -> {
                    if (name == null
                            || name.isBlank()) {

                        throw new IllegalArgumentException(
                                "question name must not be blank"
                        );
                    }

                    wireQuestions.put(
                            name,
                            Objects.requireNonNull(
                                    question,
                                    "question must not be null"
                            ).toWire()
                    );
                }
        );

        Map<String, Object> request =
                new LinkedHashMap<>();

        request.put(
                "state",
                state
        );

        request.put(
                "questions",
                Map.copyOf(
                        wireQuestions
                )
        );

        /*
         * Laya's running model is selected by the server/container.
         *
         * Jev accepts a model on each System One request.
         */
        if (protocol.sendModel()
                && !model.isBlank()) {

            request.put(
                    "model",
                    model
            );
        }

        try {
            ResponseEntity<Map> response =
                    restClient.post()
                            .uri(
                                    protocol.path()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(
                                    Map.copyOf(request)
                            )
                            .retrieve()
                            .toEntity(
                                    Map.class
                            );

            Map<?, ?> body =
                    response.getBody();

            if (body == null) {
                throw new IllegalStateException(
                        provider
                                + " returned an empty System-1 response"
                );
            }

            String requestId =
                    clean(
                            response.getHeaders()
                                    .getFirst(
                                            "x-typesafe-request-id"
                                    )
                    );

            return parseResponse(
                    body,
                    requestId
            );

        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    provider
                            + " System-1 request failed with HTTP "
                            + exception.getStatusCode()
                            + ": "
                            + exception.getResponseBodyAsString(),
                    exception
            );
        }
    }

    @Override
    public String provider() {
        return provider;
    }

    private DecisionResponse parseResponse(
            Map<?, ?> body,
            String requestId
    ) {
        Object answersValue =
                body.get(
                        "answers"
                );

        if (!(answersValue instanceof Map<?, ?> answersMap)) {
            throw new IllegalStateException(
                    provider
                            + " response does not contain an answers object"
            );
        }

        Map<String, DecisionAnswer> answers =
                new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry :
                answersMap.entrySet()) {

            String name =
                    Objects.toString(
                            entry.getKey(),
                            ""
                    );

            if (name.isBlank()) {
                continue;
            }

            if (!(entry.getValue()
                    instanceof Map<?, ?> rawAnswer)) {

                throw new IllegalStateException(
                        "Invalid System-1 answer for "
                                + name
                );
            }

            answers.put(
                    name,
                    parseAnswer(
                            name,
                            rawAnswer
                    )
            );
        }

        /*
         * Jev returns "model".
         * Your local Laya server returns "engine": "laya-hf".
         */
        String responseModel =
                stringValue(
                        body.get(
                                "model"
                        )
                );

        if (responseModel.isBlank()) {
            responseModel =
                    stringValue(
                            body.get(
                                    "engine"
                            )
                    );
        }

        if (responseModel.isBlank()) {
            responseModel =
                    model;
        }

        return new DecisionResponse(
                provider,
                responseModel,
                requestId,
                Map.copyOf(
                        answers
                )
        );
    }

    private DecisionAnswer parseAnswer(
            String name,
            Map<?, ?> answer
    ) {
        String type =
                stringValue(
                        answer.get(
                                "type"
                        )
                );

        if (type.isBlank()) {
            type =
                    inferType(
                            answer
                    );
        }

        return switch (
                type.toLowerCase()
                ) {
            case "noul" ->
                    parseNoul(
                            name,
                            answer
                    );

            case "choice" ->
                    parseChoice(
                            name,
                            answer
                    );

            case "score" ->
                    parseScore(
                            name,
                            answer
                    );

            default ->
                    throw new IllegalStateException(
                            "Unsupported System-1 answer type '"
                                    + type
                                    + "' for "
                                    + name
                    );
        };
    }

    private static NoulAnswer parseNoul(
            String name,
            Map<?, ?> answer
    ) {
        /*
         * Local Laya:
         *
         * {
         *   "type": "noul",
         *   "probability": 0.6147,
         *   "verdict": true
         * }
         *
         * Jev:
         *
         * {
         *   "type": "noul",
         *   "noul": 0.6147
         * }
         */
        double probability =
                firstNumber(
                        answer,
                        "probability",
                        "noul",
                        "value"
                );

        requireProbability(
                probability,
                "noul answer "
                        + name
        );

        Object verdictValue =
                answer.get(
                        "verdict"
                );

        boolean verdict =
                verdictValue instanceof Boolean booleanValue
                        ? booleanValue
                        : probability >= 0.5d;

        return new NoulAnswer(
                probability,
                verdict
        );
    }

    private static ChoiceAnswer parseChoice(
            String name,
            Map<?, ?> answer
    ) {
        String choice =
                stringValue(
                        answer.get(
                                "choice"
                        )
                );

        if (choice.isBlank()) {
            choice =
                    stringValue(
                            answer.get(
                                    "value"
                            )
                    );
        }

        if (choice.isBlank()) {
            throw new IllegalStateException(
                    "Choice answer "
                            + name
                            + " has no selected choice"
            );
        }

        double confidence =
                optionalNumber(
                        answer.get(
                                "confidence"
                        ),
                        0.0d
                );

        requireProbability(
                confidence,
                "choice confidence "
                        + name
        );

        return new ChoiceAnswer(
                choice,
                probabilityMap(
                        answer.get(
                                "probabilities"
                        )
                ),
                confidence
        );
    }

    private static ScoreAnswer parseScore(
            String name,
            Map<?, ?> answer
    ) {
        double score =
                firstNumber(
                        answer,
                        "score",
                        "value"
                );

        if (!Double.isFinite(score)) {
            throw new IllegalStateException(
                    "Invalid score answer for "
                            + name
            );
        }

        double confidence =
                optionalNumber(
                        answer.get(
                                "confidence"
                        ),
                        0.0d
                );

        requireProbability(
                confidence,
                "score confidence "
                        + name
        );

        return new ScoreAnswer(
                score,
                probabilityMap(
                        answer.get(
                                "probabilities"
                        )
                ),
                confidence
        );
    }

    private static String inferType(
            Map<?, ?> answer
    ) {
        if (answer.containsKey("probability")
                || answer.containsKey("noul")
                || answer.containsKey("verdict")) {

            return "noul";
        }

        if (answer.containsKey("choice")) {
            return "choice";
        }

        if (answer.containsKey("score")) {
            return "score";
        }

        return "";
    }

    private static Map<String, Double> probabilityMap(
            Object value
    ) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }

        Map<String, Double> result =
                new LinkedHashMap<>();

        map.forEach(
                (key, item) -> {
                    if (item instanceof Number number) {
                        result.put(
                                Objects.toString(
                                        key,
                                        ""
                                ),
                                number.doubleValue()
                        );
                    }
                }
        );

        return Map.copyOf(
                result
        );
    }

    private static double firstNumber(
            Map<?, ?> values,
            String... keys
    ) {
        for (String key : keys) {
            Object value =
                    values.get(
                            key
                    );

            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }

        return Double.NaN;
    }

    private static double optionalNumber(
            Object value,
            double defaultValue
    ) {
        return value instanceof Number number
                ? number.doubleValue()
                : defaultValue;
    }

    private static void requireProbability(
            double value,
            String field
    ) {
        if (!Double.isFinite(value)
                || value < 0.0
                || value > 1.0) {

            throw new IllegalStateException(
                    field
                            + " must be between 0.0 and 1.0"
            );
        }
    }

    private static String stringValue(
            Object value
    ) {
        return value == null
                ? ""
                : value.toString()
                .trim();
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
            throw new IllegalArgumentException(field + " must not be blank");
        }

        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}