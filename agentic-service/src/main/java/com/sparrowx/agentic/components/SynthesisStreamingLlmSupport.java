package com.sparrowx.agentic.components;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.OptionsConverter;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Creates a synthesis-only view of SparrowX's default Embabel LLM.
 *
 * The underlying model remains Embabel's configured model. The only changes are:
 *
 * 1. streaming usage metadata is requested from the OpenAI-compatible API;
 * 2. ChatResponse usage metadata is observed before Embabel converts the
 *    response stream into Flux<String>.
 *
 * This avoids a second model invocation and avoids estimating token counts.
 */
@Component
public final class SynthesisStreamingLlmSupport {

    private final ModelProvider modelProvider;

    public SynthesisStreamingLlmSupport(
            ModelProvider modelProvider
    ) {
        this.modelProvider =
                Objects.requireNonNull(
                        modelProvider,
                        "modelProvider must not be null"
                );
    }

    public LlmService<?> create(
            Consumer<UsageSnapshot> usageSink
    ) {
        Objects.requireNonNull(
                usageSink,
                "usageSink must not be null"
        );

        LlmService<?> selected =
                modelProvider.getLlm(
                        DefaultModelSelectionCriteria.INSTANCE
                );

        if (!(selected
                instanceof SpringAiLlmService springAi)) {

            throw new IllegalStateException(
                    "Streaming synthesis usage requires "
                            + "a SpringAiLlmService, but default LLM is "
                            + selected.getClass().getName()
            );
        }

        UsageCapturingChatModel chatModel =
                new UsageCapturingChatModel(
                        springAi.getChatModel(),
                        springAi.getName(),
                        usageSink
                );

        OptionsConverter<OpenAiChatOptions> optionsConverter =
                SynthesisStreamingLlmSupport::streamingOptions;

        /*
         * SpringAiLlmService is a Kotlin data class.
         *
         * copy(...) preserves all Embabel metadata/configuration while replacing
         * only the ChatModel and options converter for this synthesis call.
         */
        return springAi.copy(
                springAi.getName(),
                springAi.getProvider(),
                chatModel,
                optionsConverter,
                springAi.getKnowledgeCutoffDate(),
                springAi.getPromptContributors(),
                springAi.getPricingModel(),
                springAi.getThinkingSupported(),
                springAi.getToolResponseContentAdapter(),
                springAi.getNativeStructuredOutputConfigurer(),
                springAi.getNativeSupport()
        );
    }

    /**
     * Mirrors Embabel 1.0.0's OpenAI-compatible options conversion,
     * with streaming usage explicitly enabled.
     */
    private static OpenAiChatOptions streamingOptions(
            LlmOptions options
    ) {
        Objects.requireNonNull(
                options,
                "options must not be null"
        );

        return OpenAiChatOptions.builder()
                .temperature(
                        options.getTemperature()
                )
                .topP(
                        options.getTopP()
                )
                .maxTokens(
                        options.getMaxTokens()
                )
                .presencePenalty(
                        options.getPresencePenalty()
                )
                .frequencyPenalty(
                        options.getFrequencyPenalty()
                )
                .streamUsage(true)
                .build();
    }

    /**
     * Provider-authoritative usage from the streaming response.
     */
    public record UsageSnapshot(
            String model,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long durationMs,
            Instant emittedAt
    ) {
        public UsageSnapshot {
            model =
                    requireText(
                            model,
                            "model"
                    );

            if (inputTokens < 0L) {
                throw new IllegalArgumentException(
                        "inputTokens must not be negative"
                );
            }

            if (outputTokens < 0L) {
                throw new IllegalArgumentException(
                        "outputTokens must not be negative"
                );
            }

            if (totalTokens < 0L) {
                throw new IllegalArgumentException(
                        "totalTokens must not be negative"
                );
            }

            if (durationMs < 0L) {
                throw new IllegalArgumentException(
                        "durationMs must not be negative"
                );
            }

            emittedAt =
                    Objects.requireNonNull(
                            emittedAt,
                            "emittedAt must not be null"
                    );
        }
    }

    /**
     * Observes the Spring AI ChatResponse stream before Embabel reduces it
     * to text chunks.
     */
    private static final class UsageCapturingChatModel
            implements ChatModel {

        private final ChatModel delegate;
        private final String model;
        private final Consumer<UsageSnapshot> usageSink;

        private UsageCapturingChatModel(
                ChatModel delegate,
                String model,
                Consumer<UsageSnapshot> usageSink
        ) {
            this.delegate =
                    Objects.requireNonNull(
                            delegate,
                            "delegate must not be null"
                    );

            this.model =
                    requireText(
                            model,
                            "model"
                    );

            this.usageSink =
                    Objects.requireNonNull(
                            usageSink,
                            "usageSink must not be null"
                    );
        }

        @Override
        public ChatResponse call(
                Prompt prompt
        ) {
            return delegate.call(prompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        @Override
        public Flux<ChatResponse> stream(
                Prompt prompt
        ) {
            Objects.requireNonNull(
                    prompt,
                    "prompt must not be null"
            );

            long startedNanos =
                    System.nanoTime();

            AtomicLong inputTokens =
                    new AtomicLong(0L);

            AtomicLong outputTokens =
                    new AtomicLong(0L);

            AtomicLong totalTokens =
                    new AtomicLong(0L);

            AtomicBoolean usageObserved =
                    new AtomicBoolean(false);

            return delegate
                    .stream(prompt)
                    .doOnNext(response ->
                            captureUsage(
                                    response,
                                    inputTokens,
                                    outputTokens,
                                    totalTokens,
                                    usageObserved
                            )
                    )
                    .doOnComplete(() -> {

                        if (!usageObserved.get()) {
                            return;
                        }

                        long input =
                                inputTokens.get();

                        long output =
                                outputTokens.get();

                        long total =
                                totalTokens.get();

                        if (total == 0L) {
                            total =
                                    input + output;
                        }

                        long durationMs =
                                Math.max(
                                        0L,
                                        Duration.ofNanos(
                                                System.nanoTime()
                                                        - startedNanos
                                        ).toMillis()
                                );

                        usageSink.accept(
                                new UsageSnapshot(
                                        model,
                                        input,
                                        output,
                                        total,
                                        durationMs,
                                        Instant.now()
                                )
                        );
                    });
        }

        private static void captureUsage(
                ChatResponse response,
                AtomicLong inputTokens,
                AtomicLong outputTokens,
                AtomicLong totalTokens,
                AtomicBoolean usageObserved
        ) {
            if (response == null
                    || response.getMetadata() == null) {
                return;
            }

            Usage usage =
                    response
                            .getMetadata()
                            .getUsage();

            if (usage == null) {
                return;
            }

            long input =
                    tokenCount(
                            usage.getPromptTokens()
                    );

            long output =
                    tokenCount(
                            usage.getCompletionTokens()
                    );

            long total =
                    tokenCount(
                            usage.getTotalTokens()
                    );

            if (input == 0L
                    && output == 0L
                    && total == 0L) {
                return;
            }

            /*
             * Streaming providers normally emit cumulative usage on the
             * terminal chunk. max(...) is safer than summing because some
             * providers repeat cumulative usage metadata.
             */
            inputTokens.accumulateAndGet(
                    input,
                    Math::max
            );

            outputTokens.accumulateAndGet(
                    output,
                    Math::max
            );

            totalTokens.accumulateAndGet(
                    total,
                    Math::max
            );

            usageObserved.set(true);
        }
    }

    private static long tokenCount(
            Number value
    ) {
        if (value == null) {
            return 0L;
        }

        return Math.max(
                0L,
                value.longValue()
        );
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

        return value;
    }
}