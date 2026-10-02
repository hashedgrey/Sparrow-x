package com.sparrowx.agentic.systemone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface SystemOneDecisionClient {

    DecisionResponse evaluate(
            Map<String, ?> state,
            Map<String, ? extends DecisionQuestion> questions
    );

    String provider();

    /*
     * ------------------------------------------------------------------
     * QUESTIONS
     * ------------------------------------------------------------------
     */

    sealed interface DecisionQuestion
            permits NoulQuestion,
            ChoiceQuestion,
            ScoreQuestion {

        String type();

        String instructions();

        Object criteria();

        default Map<String, Object> toWire() {
            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "type",
                    type()
            );

            result.put(
                    "instructions",
                    instructions()
            );

            Object criteria =
                    criteria();

            if (criteria != null) {
                result.put(
                        "criteria",
                        criteria
                );
            }

            return Map.copyOf(result);
        }
    }

    /**
     * Binary System-1 judgement.
     *
     * probability:
     * 0.0 = false
     * 1.0 = true
     */
    record NoulQuestion(
            String instructions,
            String whenTrue,
            String whenFalse
    ) implements DecisionQuestion {

        public NoulQuestion {
            instructions =
                    requireText(
                            instructions,
                            "instructions"
                    );

            whenTrue =
                    clean(
                            whenTrue
                    );

            whenFalse =
                    clean(
                            whenFalse
                    );
        }

        public static NoulQuestion of(
                String instructions
        ) {
            return new NoulQuestion(
                    instructions,
                    "",
                    ""
            );
        }

        @Override
        public String type() {
            return "noul";
        }

        @Override
        public Object criteria() {
            if (whenTrue.isBlank()
                    && whenFalse.isBlank()) {

                return null;
            }

            Map<String, String> criteria =
                    new LinkedHashMap<>();

            if (!whenTrue.isBlank()) {
                criteria.put(
                        "true",
                        whenTrue
                );
            }

            if (!whenFalse.isBlank()) {
                criteria.put(
                        "false",
                        whenFalse
                );
            }

            return Map.copyOf(criteria);
        }
    }

    /**
     * Select exactly one bounded option.
     */
    record ChoiceQuestion(
            String instructions,
            Map<String, String> options
    ) implements DecisionQuestion {

        public ChoiceQuestion {
            instructions =
                    requireText(
                            instructions,
                            "instructions"
                    );

            if (options == null
                    || options.isEmpty()) {

                throw new IllegalArgumentException(
                        "options must not be empty"
                );
            }

            Map<String, String> normalized =
                    new LinkedHashMap<>();

            options.forEach(
                    (label, description) -> {
                        String key =
                                requireText(
                                        label,
                                        "option label"
                                );

                        normalized.put(
                                key,
                                clean(description)
                        );
                    }
            );

            options =
                    Map.copyOf(
                            normalized
                    );
        }

        @Override
        public String type() {
            return "choice";
        }

        @Override
        public Object criteria() {
            return options;
        }
    }

    /**
     * Ordered rubric.
     */
    record ScoreQuestion(
            String instructions,
            List<String> levels
    ) implements DecisionQuestion {

        public ScoreQuestion {
            instructions =
                    requireText(
                            instructions,
                            "instructions"
                    );

            if (levels == null
                    || levels.size() < 2) {

                throw new IllegalArgumentException(
                        "score requires at least two levels"
                );
            }

            levels =
                    levels.stream()
                            .map(level ->
                                    requireText(
                                            level,
                                            "score level"
                                    )
                            )
                            .toList();
        }

        @Override
        public String type() {
            return "score";
        }

        @Override
        public Object criteria() {
            return levels;
        }
    }

    /*
     * ------------------------------------------------------------------
     * ANSWERS
     * ------------------------------------------------------------------
     */

    sealed interface DecisionAnswer
            permits NoulAnswer,
            ChoiceAnswer,
            ScoreAnswer {

        String type();
    }

    record NoulAnswer(
            double probability,
            boolean verdict
    ) implements DecisionAnswer {

        public NoulAnswer {
            requireProbability(
                    probability,
                    "probability"
            );
        }

        @Override
        public String type() {
            return "noul";
        }
    }

    record ChoiceAnswer(
            String choice,
            Map<String, Double> probabilities,
            double confidence
    ) implements DecisionAnswer {

        public ChoiceAnswer {
            choice =
                    requireText(
                            choice,
                            "choice"
                    );

            probabilities =
                    probabilities == null
                            ? Map.of()
                            : Map.copyOf(
                            probabilities
                    );

            requireProbability(
                    confidence,
                    "confidence"
            );
        }

        @Override
        public String type() {
            return "choice";
        }
    }

    record ScoreAnswer(
            double score,
            Map<String, Double> probabilities,
            double confidence
    ) implements DecisionAnswer {

        public ScoreAnswer {
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException(
                        "score must be finite"
                );
            }

            probabilities =
                    probabilities == null
                            ? Map.of()
                            : Map.copyOf(
                            probabilities
                    );

            requireProbability(
                    confidence,
                    "confidence"
            );
        }

        @Override
        public String type() {
            return "score";
        }
    }

    /*
     * ------------------------------------------------------------------
     * RESPONSE
     * ------------------------------------------------------------------
     */

    record DecisionResponse(
            String provider,
            String model,
            String requestId,
            Map<String, DecisionAnswer> answers
    ) {
        public DecisionResponse {
            provider =
                    requireText(
                            provider,
                            "provider"
                    );

            model =
                    clean(
                            model
                    );

            requestId =
                    clean(
                            requestId
                    );

            answers =
                    answers == null
                            ? Map.of()
                            : Map.copyOf(
                            answers
                    );
        }

        public DecisionAnswer answer(
                String name
        ) {
            DecisionAnswer answer =
                    answers.get(name);

            if (answer == null) {
                throw new IllegalStateException(
                        "System-1 response is missing answer: "
                                + name
                );
            }

            return answer;
        }

        public NoulAnswer noul(
                String name
        ) {
            DecisionAnswer answer =
                    answer(name);

            if (!(answer instanceof NoulAnswer noul)) {
                throw new IllegalStateException(
                        "Expected noul answer for "
                                + name
                                + " but received "
                                + answer.type()
                );
            }

            return noul;
        }

        public double noulValue(
                String name
        ) {
            return noul(name)
                    .probability();
        }

        public ChoiceAnswer choice(
                String name
        ) {
            DecisionAnswer answer =
                    answer(name);

            if (!(answer instanceof ChoiceAnswer choice)) {
                throw new IllegalStateException(
                        "Expected choice answer for "
                                + name
                                + " but received "
                                + answer.type()
                );
            }

            return choice;
        }

        public String choiceValue(
                String name
        ) {
            return choice(name)
                    .choice();
        }

        public ScoreAnswer score(
                String name
        ) {
            DecisionAnswer answer =
                    answer(name);

            if (!(answer instanceof ScoreAnswer score)) {
                throw new IllegalStateException(
                        "Expected score answer for "
                                + name
                                + " but received "
                                + answer.type()
                );
            }

            return score;
        }

        public double scoreValue(
                String name
        ) {
            return score(name)
                    .score();
        }
    }

    /*
     * ------------------------------------------------------------------
     * VALIDATION
     * ------------------------------------------------------------------
     */

    private static void requireProbability(
            double value,
            String field
    ) {
        if (!Double.isFinite(value)
                || value < 0.0
                || value > 1.0) {

            throw new IllegalArgumentException(
                    field
                            + " must be between 0.0 and 1.0"
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
                    field
                            + " must not be blank"
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