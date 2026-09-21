package com.sparrowx.agentic.components;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.spi.LlmService;
import com.sparrowx.agentic.components.PlanningComponent.Observation;
import com.sparrowx.agentic.governance.model.GovernanceDecision;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.Finding;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.mission.model.Recommendation;
import com.sparrowx.agentic.mission.model.ResultSection;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.MissionPlan;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Produces the final grounded mission answer using the Embabel AI runtime.
 *
 * Synthesis streams the user-facing final answer while still returning one
 * complete SynthesisDraft for the authoritative MissionResult.
 */
public final class SynthesisComponent {

    private static final Duration SYNTHESIS_TIMEOUT =
            Duration.ofMinutes(5);

    private static final String FINAL_OPEN =
            "<final_answer>";

    private static final String FINAL_CLOSE =
            "</final_answer>";

    private static final String SUMMARY_OPEN =
            "<executive_summary>";

    private static final String SUMMARY_CLOSE =
            "</executive_summary>";

    private final SynthesisStreamingLlmSupport streamingLlmSupport;

    public SynthesisComponent(
            SynthesisStreamingLlmSupport streamingLlmSupport
    ) {
        this.streamingLlmSupport =
                Objects.requireNonNull(
                        streamingLlmSupport,
                        "streamingLlmSupport must not be null"
                );
    }

    public SynthesisDraft synthesize(
            SynthesisRequest request,
            OperationContext context,
            Consumer<MissionStreamEvent> streamEventSink
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        Objects.requireNonNull(
                streamEventSink,
                "streamEventSink must not be null"
        );

        if (!request.reactorComplete()) {
            throw new IllegalStateException(
                    "synthesis is allowed only after the reactor completes"
            );
        }

        String operationId =
                "synthesis:"
                        + UUID.randomUUID();

        LlmService<?> synthesisLlm =
                streamingLlmSupport.create(
                        usage ->
                                streamEventSink.accept(
                                        usageEvent(
                                                request.missionId(),
                                                operationId,
                                                usage
                                        )
                                )
                );

        PromptRunner runner =
                context.ai()
                        .withLlmService(
                                synthesisLlm
                        );

        Flux<String> stream =
                new StreamingPromptRunnerBuilder(runner)
                        .streaming()
                        .withPrompt(
                                synthesisPrompt(request)
                        )
                        .generateStream();

        StringBuilder raw =
                new StringBuilder();

        AtomicLong sequence =
                new AtomicLong(0L);

        FinalAnswerProjector projector =
                new FinalAnswerProjector();

        stream.doOnNext(chunk -> {

                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }

                    raw.append(chunk);

                    projector.project(
                            raw,
                            text -> {
                                if (text.isEmpty()) {
                                    return;
                                }

                                long nextSequence =
                                        sequence.incrementAndGet();

                                streamEventSink.accept(
                                        new MissionStreamEvent.AnswerDelta(
                                                request.missionId(),
                                                nextSequence,
                                                text,
                                                answerResumeToken(
                                                        request.missionId(),
                                                        nextSequence
                                                ),
                                                Instant.now()
                                        )
                                );
                            }
                    );
                })
                .blockLast(
                        SYNTHESIS_TIMEOUT
                );

        SynthesisProjection projection =
                parseProjection(
                        raw.toString()
                );

        return toDraft(projection);
    }

    private static MissionStreamEvent.Usage usageEvent(
            String missionId,
            String operationId,
            SynthesisStreamingLlmSupport.UsageSnapshot usage
    ) {
        return new MissionStreamEvent.Usage(
                missionId,

                operationId,

                MissionStreamEvent.UsageKind.LLM,

                "synthesis-component",

                usage.model(),

                usage.inputTokens(),

                /*
                 * Spring AI's generic Usage object does not expose
                 * cached-input tokens as a portable first-class field.
                 */
                0L,

                usage.outputTokens(),

                usage.totalTokens(),

                0L,

                0L,

                usage.durationMs(),

                usageResumeToken(
                        missionId,
                        operationId
                ),

                usage.emittedAt()
        );
    }

    private static String usageResumeToken(
            String missionId,
            String operationId
    ) {
        return "usage:"
                + missionId
                + ":"
                + operationId;
    }

    /**
     * Final answer is deliberately first so user-visible content can begin
     * streaming before the executive summary has been generated.
     */
    private static String synthesisPrompt(
            SynthesisRequest request
    ) {
        return """
            You are the final synthesis stage of SparrowX.
            Produce a grounded final response for the mission below.

            <mission_id>
            %s
            </mission_id>

            <mission_intent>
            %s
            </mission_intent>

            <final_plan>
            %s
            </final_plan>

            <observations>
            %s
            </observations>

            <evidence_references>
            %s
            </evidence_references>

            <governance_decisions>
            %s
            </governance_decisions>

            <required_sections>
            %s
            </required_sections>

            <mission_context>
            %s
            </mission_context>

            <citations>
            %s
            </citations>

            Requirements:

            - Answer the user's original objective directly.
            - Use only the supplied mission state, observations and evidence.
            - Do not invent facts, entities, relationships or evidence.
            - Preserve uncertainty when the evidence is incomplete.
            - Respect warnings and governance decisions present in the context.
            - Do not claim that evidence proves something it does not support.
            - The final answer must contain the complete user-facing answer.
            - The executive summary must briefly state the important result.
            - Preserve Markdown headings and lists inside the final answer when useful.
            - Do not include implementation or debug commentary.

            Output protocol:

            - Output exactly the following two tagged sections.
            - Output final_answer FIRST.
            - Do not emit any text before <final_answer>.
            - Do not emit any text after </executive_summary>.
            - Do not wrap the response in Markdown code fences.
            - Do not omit or rename the tags.

            <final_answer>
            complete user-facing answer
            </final_answer>
            <executive_summary>
            concise summary
            </executive_summary>
            """.formatted(
                request.missionId(),
                request.intent(),
                request.finalPlan(),
                request.observations(),
                request.evidenceRefs(),
                request.governanceDecisions(),
                request.requiredSections(),
                request.context(),
                request.citations()
        );
    }

    private static SynthesisProjection parseProjection(
            String raw
    ) {
        String finalAnswer =
                extractRequired(
                        raw,
                        FINAL_OPEN,
                        FINAL_CLOSE,
                        "finalAnswer"
                ).strip();

        String executiveSummary =
                extractRequired(
                        raw,
                        SUMMARY_OPEN,
                        SUMMARY_CLOSE,
                        "executiveSummary"
                ).strip();

        return new SynthesisProjection(
                executiveSummary,
                finalAnswer
        );
    }

    private static String extractRequired(
            String raw,
            String open,
            String close,
            String field
    ) {
        int start =
                raw.indexOf(open);

        if (start < 0) {
            throw new IllegalStateException(
                    "Streaming synthesis omitted "
                            + open
            );
        }

        start += open.length();

        int end =
                raw.indexOf(
                        close,
                        start
                );

        if (end < 0) {
            throw new IllegalStateException(
                    "Streaming synthesis omitted "
                            + close
            );
        }

        String value =
                raw.substring(
                        start,
                        end
                );

        if (value.isBlank()) {
            throw new IllegalStateException(
                    field + " was blank"
            );
        }

        return value;
    }

    private static SynthesisDraft toDraft(
            SynthesisProjection projection
    ) {
        Objects.requireNonNull(
                projection,
                "synthesis projection must not be null"
        );

        return new SynthesisDraft(
                projection.executiveSummary(),
                projection.finalAnswer(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(
                        "synthesisEngine",
                        "embabel-streaming-operation-context"
                )
        );
    }

    private static String answerResumeToken(
            String missionId,
            long sequence
    ) {
        return "answer:"
                + missionId
                + ":"
                + sequence;
    }

    /**
     * Emits only content inside final_answer.
     *
     * It retains any suffix that might be the beginning of the closing
     * marker so tag fragments are never sent to the UI.
     */
    private static final class FinalAnswerProjector {

        private int contentStart = -1;
        private int nextIndex = -1;
        private boolean closed;

        private void project(
                StringBuilder raw,
                Consumer<String> sink
        ) {
            if (closed) {
                return;
            }

            if (contentStart < 0) {

                int open =
                        raw.indexOf(
                                FINAL_OPEN
                        );

                if (open < 0) {
                    return;
                }

                contentStart =
                        open + FINAL_OPEN.length();

                nextIndex =
                        contentStart;
            }

            int close =
                    raw.indexOf(
                            FINAL_CLOSE,
                            contentStart
                    );

            int safeEnd;

            if (close >= 0) {
                safeEnd = close;
            } else {
                safeEnd =
                        raw.length()
                                - partialMarkerSuffixLength(
                                raw,
                                FINAL_CLOSE,
                                contentStart
                        );
            }

            if (nextIndex == contentStart) {
                while (nextIndex < safeEnd) {

                    char current =
                            raw.charAt(nextIndex);

                    if (current != '\n'
                            && current != '\r') {
                        break;
                    }

                    nextIndex++;
                }
            }

            if (safeEnd > nextIndex) {

                String delta =
                        raw.substring(
                                nextIndex,
                                safeEnd
                        );

                nextIndex =
                        safeEnd;

                sink.accept(delta);
            }

            if (close >= 0) {
                closed = true;
            }
        }

        private static int partialMarkerSuffixLength(
                StringBuilder raw,
                String marker,
                int minimumIndex
        ) {
            int available =
                    raw.length()
                            - minimumIndex;

            int max =
                    Math.min(
                            marker.length() - 1,
                            available
                    );

            for (int length = max;
                 length > 0;
                 length--) {

                int rawStart =
                        raw.length() - length;

                boolean match = true;

                for (int index = 0;
                     index < length;
                     index++) {

                    if (raw.charAt(
                            rawStart + index
                    ) != marker.charAt(index)) {

                        match = false;
                        break;
                    }
                }

                if (match) {
                    return length;
                }
            }

            return 0;
        }
    }

    public record SynthesisProjection(
            String executiveSummary,
            String finalAnswer
    ) {
    }

    public record SynthesisRequest(
            String missionId,
            boolean reactorComplete,
            MissionIntent intent,
            MissionPlan finalPlan,
            List<Observation> observations,
            List<EvidenceRef> evidenceRefs,
            List<Citation> citations,
            List<GovernanceDecision> governanceDecisions,
            List<String> requiredSections,
            Map<String, Object> context
    ) {
        public SynthesisRequest {
            missionId =
                    requireText(
                            missionId,
                            "missionId"
                    );

            intent =
                    Objects.requireNonNull(
                            intent,
                            "intent must not be null"
                    );

            finalPlan =
                    Objects.requireNonNull(
                            finalPlan,
                            "finalPlan must not be null"
                    );

            observations =
                    observations == null
                            ? List.of()
                            : List.copyOf(
                            observations
                    );

            evidenceRefs =
                    evidenceRefs == null
                            ? List.of()
                            : List.copyOf(
                            evidenceRefs
                    );

            citations =
                    citations == null
                            ? List.of()
                            : List.copyOf(
                            citations
                    );

            governanceDecisions =
                    governanceDecisions == null
                            ? List.of()
                            : List.copyOf(
                            governanceDecisions
                    );

            requiredSections =
                    requiredSections == null
                            ? List.of()
                            : List.copyOf(
                            requiredSections
                    );

            context =
                    context == null
                            ? Map.of()
                            : Map.copyOf(
                            context
                    );
        }
    }

    public record SynthesisDraft(
            String executiveSummary,
            String finalAnswer,
            List<ResultSection> sections,
            List<Finding> findings,
            List<Recommendation> recommendations,
            Map<String, Object> structuredOutput,
            Map<String, Object> debugSummary
    ) {
        public SynthesisDraft {
            executiveSummary =
                    executiveSummary == null
                            ? ""
                            : executiveSummary;

            finalAnswer =
                    requireText(
                            finalAnswer,
                            "finalAnswer"
                    );

            sections =
                    sections == null
                            ? List.of()
                            : List.copyOf(
                            sections
                    );

            findings =
                    findings == null
                            ? List.of()
                            : List.copyOf(
                            findings
                    );

            recommendations =
                    recommendations == null
                            ? List.of()
                            : List.copyOf(
                            recommendations
                    );

            structuredOutput =
                    structuredOutput == null
                            ? Map.of()
                            : Map.copyOf(
                            structuredOutput
                    );

            debugSummary =
                    debugSummary == null
                            ? Map.of()
                            : Map.copyOf(
                            debugSummary
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

        return value;
    }
}