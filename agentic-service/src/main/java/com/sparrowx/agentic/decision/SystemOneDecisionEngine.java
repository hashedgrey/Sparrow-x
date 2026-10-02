package com.sparrowx.agentic.decision;

import com.sparrowx.agentic.systemone.SystemOneDecisionClient;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded System-1 decision boundary.
 *
 * Laya/JEV answer known typed questions over supplied state.
 *
 * This engine does not:
 *
 * - generate free-form mission output;
 * - perform planning;
 * - select Embabel actions;
 * - own fallback to an LLM;
 * - know about Temporal.
 */
public final class SystemOneDecisionEngine {

    private final SystemOneDecisionClient client;

    public SystemOneDecisionEngine(
            SystemOneDecisionClient client
    ) {
        this.client =
                Objects.requireNonNull(
                        client,
                        "client must not be null"
                );
    }

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

        return Objects.requireNonNull(
                client.evaluate(
                        state,
                        questions
                ),
                "System-1 client returned null"
        );
    }

    public String provider() {
        return client.provider();
    }
}