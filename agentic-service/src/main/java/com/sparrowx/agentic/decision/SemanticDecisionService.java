package com.sparrowx.agentic.decision;

import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.trace.MissionTracePublisher;
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
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(SemanticDecisionService.class);

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

        // No model-generated placeholder decisions. If System-1 is unavailable,
        // escalate every requested bounded field using the provided schema.
        DecisionResponse bounded;
        boolean systemOneUnavailable = false;
        long started = System.nanoTime();
        try {
            bounded = systemOne.evaluate(request.state(), request.questions());
            LOG.info("semantic.system1.complete fields={} elapsedMs={}",
                    request.questions().size(), elapsed(started));
        } catch (RuntimeException ex) {
            if (request.escalation() == null) throw ex;
            LOG.warn("semantic.system1.unavailable elapsedMs={} type={}",
                    elapsed(started), ex.getClass().getSimpleName());
            // Represents the failed call, with zero answers. It cannot resolve a field.
            bounded = new DecisionResponse("system-one-unavailable", "", "", Map.of());
            systemOneUnavailable = true;
        }

        MissionDecisionTraceContext.votes(bounded, request.questions(), request.minimumSystemOneConfidence());
        Set<String> fieldsToEscalate = escalationFields(request, bounded);
        if (systemOneUnavailable) {
            fieldsToEscalate = new LinkedHashSet<>(fieldsToEscalate);
            fieldsToEscalate.addAll(request.questions().keySet());
            fieldsToEscalate = Set.copyOf(fieldsToEscalate);
        }

        if (fieldsToEscalate.isEmpty()) {
            MissionDecisionTraceContext.event("JEV_ACCEPTED", "Jev resolved bounded fields",
                    Map.of("fieldCount", Integer.toString(request.questions().size()), "decision", "ACCEPTED"));
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

        MissionDecisionTraceContext.event("LLM_ESCALATION", "Escalate unresolved fields",
                Map.of("reasonCode", systemOneUnavailable ? "JEV_UNAVAILABLE" : "LOW_CONFIDENCE_OR_UNRESOLVED",
                        "escalatedCount", Integer.toString(fieldsToEscalate.size())));
        LlmDecisionEngine.Result llmResult =
                escalate(
                        new LlmDecisionEngine.Request(
                                escalation.missionId(),
                                escalation.operationId(),
                                escalation.idempotencyKey(),
                                escalation.task(),
                                request.state(),
                                fieldsToEscalate,
                                scopedSchema(escalation.outputSchema(), fieldsToEscalate),
                                escalation.maxOutputTokens(),
                                escalation.temperature(),
                                escalation.metadata()
                        )
                );

        Map<String, Object> structured = llmResult.output();
        if (structured == null || !structured.keySet().containsAll(fieldsToEscalate)) {
            throw new IllegalStateException("LLM omitted escalated semantic fields: " + fieldsToEscalate);
        }
        return new Result(
                bounded,
                fieldsToEscalate,
                structured,
                llmResult.routeKey(),
                llmResult.response()
                        .provider(),
                llmResult.response()
                        .model()
        );
    }


    /**
     * Use System-1 only for closed-form questions. Invalid or unavailable answers
     * return empty so callers can escalate the entire semantic operation.
     * No synthetic answers are ever treated as successful evidence.
     */
    public Optional<DecisionResponse> tryBounded(
            Map<String, Object> state,
            Map<String, ? extends DecisionQuestion> questions,
            double minimumConfidence,
            String operation
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) return Optional.empty();
        if (!Double.isFinite(minimumConfidence)
                || minimumConfidence < 0.5d || minimumConfidence > 1.0d)
            throw new IllegalArgumentException("Invalid System-1 confidence threshold");

        long started = System.nanoTime();
        try {
            DecisionResponse response = systemOne.evaluate(state, questions);
            MissionDecisionTraceContext.votes(response, questions, minimumConfidence);
            for (var entry : questions.entrySet()) {
                DecisionAnswer answer = response.answers().get(entry.getKey());
                DecisionQuestion question = entry.getValue();
                if (answer == null || !validAnswer(question, answer)
                        || !Double.isFinite(confidence(answer))
                        || confidence(answer) < minimumConfidence) {
                    LOG.warn(
                            "JEV rejected operation={} field={} answer={} threshold={}",
                            operation,
                            entry.getKey(),
                            answer,
                            minimumConfidence
                    );
                    LOG.info("semantic.system1.unresolved operation={} field={} elapsedMs={}",
                            operation, entry.getKey(), elapsed(started));
                    MissionDecisionTraceContext.event("JEV_UNRESOLVED", "Jev bounded check unresolved",
                            Map.of("field", entry.getKey(), "threshold", Double.toString(minimumConfidence),
                                    "decision", "UNRESOLVED"));
                    return Optional.empty();
                }
            }

            LOG.info("semantic.system1.resolved operation={} provider={} elapsedMs={}",
                    operation, response.provider(), elapsed(started));
            MissionDecisionTraceContext.event("JEV_ACCEPTED", "Jev bounded check accepted",
                    Map.of("fieldCount", Integer.toString(questions.size()), "decision", "ACCEPTED"));
            return Optional.of(response);
        } catch (RuntimeException ex) {
            LOG.warn("semantic.system1.failed operation={} elapsedMs={} type={}",
                    operation, elapsed(started), ex.getClass().getSimpleName());
            MissionDecisionTraceContext.event("JEV_UNAVAILABLE", "Jev execution unavailable",
                    Map.of("reasonCode", ex.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    public LlmDecisionEngine.Result escalate(LlmDecisionEngine.Request request) {
        long started = System.nanoTime();
        MissionTracePublisher.Span llmSpan = MissionDecisionTraceContext.begin(
                "LLM_GENERATION", "Generate structured LLM decision",
                Map.of("fieldCount", Integer.toString(request.fieldsToResolve().size()),
                        "stage", request.metadata().getOrDefault("component", "semantic")));
        try (var child = MissionDecisionTraceContext.parent(llmSpan == null ? null : llmSpan.id())) {
            LlmDecisionEngine.Result result = llm.evaluate(request);
            // Safe output summary for UI inspection. Full prompts and generated JSON
            // must remain behind a separate permission-controlled debug endpoint.
            MissionDecisionTraceContext.event("LLM_RESULT", "Structured LLM response received",
                    Map.of("provider", result.response().provider(),
                            "model", result.response().model(),
                            "route", result.routeKey(),
                            "fieldCount", Integer.toString(request.fieldsToResolve().size()),
                            "outcome", result.output() == null ? "MISSING" : "RECEIVED"));
            LOG.info("semantic.llm.complete operation={} route={} elapsedMs={}",
                    request.operationId(), result.routeKey(), elapsed(started));
            if (result.output() != null) {
                MissionDecisionTraceContext.proposal(result.output().get("status"), result.output().get("nextAction"));
            }
            if (llmSpan != null) llmSpan.finish("SUCCEEDED", "");
            return result;
        } catch (RuntimeException e) {
            if (llmSpan != null) llmSpan.finish("FAILED", e.getClass().getSimpleName());
            LOG.warn("semantic.llm.failed operation={} elapsedMs={} type={}",
                    request.operationId(), elapsed(started), e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Jev-first routing for STRUCTURED decisions with a mixture of bounded and
     * generative fields.  The current SystemOneDecisionClient supports noul,
     * choice, and score, not arbitrary JSON structures.  Jev therefore judges
     * bounded enum fields first; the LLM supplies any open-text, arrays, nested
     * graph joins, and other fields the System-1 protocol cannot produce.
     *
     * If Jev fails, times out, returns malformed choices or lacks confidence,
     * tryBounded returns empty and the LLM receives the original request.
     * The proposal is advice, not authorization or verified evidence.
     */
    public LlmDecisionEngine.Result escalateAfterJev(LlmDecisionEngine.Request request) {
        Objects.requireNonNull(request, "request");
        Map<String, DecisionQuestion> boundedQuestions = enumQuestions(request);
        if (boundedQuestions.isEmpty()) {
            LOG.info("semantic.jev.unexpressible operation={} reason=no-bounded-schema-fields",
                    request.operationId());
            MissionDecisionTraceContext.event("LLM_ESCALATION", "LLM needed for unbounded output",
                    Map.of("reasonCode", "NO_BOUNDED_FIELDS"));
            return escalate(request);
        }
        Map<String, Object> state = new LinkedHashMap<>(request.state());
        Optional<DecisionResponse> answer = tryBounded(
                state, boundedQuestions, 0.80d, request.operationId() + ":jev-preflight");
        if (answer.isEmpty()) {
            LOG.info("semantic.jev.escalation operation={} reason=unavailable-or-uncertain",
                    request.operationId());
            MissionDecisionTraceContext.event("LLM_ESCALATION", "Jev requires LLM fallback",
                    Map.of("reasonCode", "UNAVAILABLE_OR_UNCERTAIN"));
            return escalate(request);
        }
        Map<String, String> choices = new LinkedHashMap<>();
        for (String field : boundedQuestions.keySet()) {
            DecisionAnswer resolved = answer.get().answers().get(field);
            if (resolved instanceof ChoiceAnswer choice) {
                choices.put(field, choice.choice());
            }
        }
        if (choices.isEmpty()) return escalate(request);
        // Do not force the generative LLM to copy a wrong Jev suggestion.
        // The consuming component still validates the complete output.
        state.put("jevFirstPass", Map.copyOf(choices));
        state.put("jevFirstPassInstruction", "Jev made the following bounded suggestions. "
                + "Use them if consistent with the complete task and evidence; "
                + "otherwise correct them. Produce ALL required fields, "
                + "including unbounded structures that Jev cannot generate.");
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("routing", "jev-first-llm-for-generative-fields");
        LOG.info("semantic.jev.resolved operation={} fields={} provider={} continuing=generative-llm",
                request.operationId(), choices.keySet(), answer.get().provider());
        MissionDecisionTraceContext.event("LLM_CONTINUATION", "Jev resolved choices; LLM fills generative fields",
                Map.of("reasonCode", "GENERATIVE_FIELDS_REQUIRED", "fieldCount", Integer.toString(choices.size())));
        return escalate(new LlmDecisionEngine.Request(
                request.missionId(), request.operationId(), request.idempotencyKey(),
                request.task(), Map.copyOf(state), request.fieldsToResolve(),
                request.outputSchema(), request.maxOutputTokens(), request.temperature(),
                Map.copyOf(metadata)));
    }

    /** Only ask Jev closed-form questions it actually supports. */
    private static Map<String, DecisionQuestion> enumQuestions(LlmDecisionEngine.Request request) {
        Object raw = request.outputSchema().get("properties");
        if (!(raw instanceof Map<?, ?> properties)) return Map.of();
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (String field : request.fieldsToResolve().stream().sorted().toList()) {
            Object shape = properties.get(field);
            if (!(shape instanceof Map<?, ?> property) || !(property.get("enum") instanceof List<?> values)
                    || values.size() < 2 || values.size() > 24
                    || !"string".equals(property.get("type"))) continue;
            Map<String, String> options = new LinkedHashMap<>();
            boolean valid = true;
            for (Object value : values) {
                if (!(value instanceof String str) || str.isBlank() || options.containsKey(str)) {
                    valid = false;
                    break;
                }
                options.put(str, str);
            }
            if (valid) {
                questions.put(field,
                        new com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion(
                                "For the supplied task and state, choose the most appropriate value of '"
                                        + field + "'. Do not guess when the evidence is insufficient. "
                                        + request.task(), Map.copyOf(options)));
            }
        }
        return Map.copyOf(questions);
    }

    private static boolean validAnswer(DecisionQuestion question, DecisionAnswer answer) {
        if (question instanceof com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion)
            return answer instanceof NoulAnswer n && Double.isFinite(n.probability())
                    && n.probability() >= 0 && n.probability() <= 1;
        if (question instanceof com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion q)
            return answer instanceof ChoiceAnswer a
                    && q.options().containsKey(a.choice())
                    && Double.isFinite(a.confidence())
                    && a.confidence() >= 0 && a.confidence() <= 1;
        if (question instanceof com.sparrowx.agentic.systemone.SystemOneDecisionClient.ScoreQuestion)
            return answer instanceof ScoreAnswer a && Double.isFinite(a.score())
                    && Double.isFinite(a.confidence()) && a.confidence() >= 0 && a.confidence() <= 1;
        return false;
    }

    private static long elapsed(long since) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    /** Prevent required fields that System-1 already resolved from being regenerated. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopedSchema(Map<String, Object> schema, Set<String> fields) {
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties))
            throw new IllegalArgumentException("Escalation schema has no object properties");
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : fields) {
            if (!properties.containsKey(field))
                throw new IllegalArgumentException("Schema does not declare escalation field: " + field);
            selected.put(field, properties.get(field));
        }
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("properties", Map.copyOf(selected));
        result.put("required", List.copyOf(selected.keySet()));
        result.put("additionalProperties", false);
        return Map.copyOf(result);
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
            if (answer != null && validAnswer(request.questions().get(name), answer)) {
                double confidence = confidence(answer);

                if (confidence < request.minimumSystemOneConfidence()) {
                    LOG.info(
                            "semantic.system1.escalation field={} confidence={} threshold={}",
                            name,
                            confidence,
                            request.minimumSystemOneConfidence()
                    );
                }
            }
            if (answer == null) {
                result.add(name);

                continue;
            }

            if (!validAnswer(request.questions().get(name), answer)
                    || !Double.isFinite(confidence(answer)) || confidence(answer) < request.minimumSystemOneConfidence()) {

                result.add(name);
            }
        }

        return Set.copyOf(result);
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
