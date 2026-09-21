package com.sparrowx.agentic.http;

import buildingblocks.core.commands.CommandBus;
import buildingblocks.core.queries.QueryBus;
import com.sparrowx.agentic.features.streammissionprogress.MissionEventCursor;
import com.sparrowx.agentic.mappers.AgenticMapper;
import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.proto.GetMissionResultRequest;
import com.sparrowx.agentic.proto.MissionConstraints;
import com.sparrowx.agentic.proto.MissionResultResponse;
import com.sparrowx.agentic.proto.RequestContext;
import com.sparrowx.agentic.proto.StreamMissionProgressRequest;
import com.sparrowx.agentic.proto.SubmitMissionRequest;
import com.sparrowx.agentic.proto.SubmitMissionResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.sparrowx.agentic.proto.CancelMissionRequest;
import com.sparrowx.agentic.proto.CancelMissionResponse;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/assistant")
public final class AssistantMissionSseController {

    private static final Duration HEARTBEAT_WAIT =
            Duration.ofSeconds(10);

    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final AgenticMapper agenticMapper;

    public AssistantMissionSseController(
            CommandBus commandBus,
            QueryBus queryBus,
            AgenticMapper agenticMapper
    ) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
        this.agenticMapper = agenticMapper;
    }

    @PostMapping(
            value = "/missions/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamMission(
            @RequestBody AssistantMissionRequest body
    ) {

        RequestContext context =
                RequestContext
                        .newBuilder()
                        .setRequestId(
                                UUID.randomUUID().toString()
                        )
                        .setTraceId(
                                UUID.randomUUID().toString()
                        )
                        .setTenantId(
                                valueOr(
                                        body.tenantId(),
                                        "tenant-test"
                                )
                        )
                        .setUserId(
                                valueOr(
                                        body.userId(),
                                        "assistant-ui-user"
                                )
                        )
                        .setUsername(
                                "assistant-ui"
                        )
                        .setCallerService(
                                "assistant-ui"
                        )
                        .setClientChannel(
                                "web"
                        )
                        .setConversationId(
                                valueOr(
                                        body.conversationId(),
                                        UUID.randomUUID().toString()
                                )
                        )
                        .build();

        SubmitMissionRequest submitRequest =
                SubmitMissionRequest
                        .newBuilder()
                        .setContext(context)
                        .setQuery(body.query())
                        .setConstraints(
                                MissionConstraints
                                        .newBuilder()
                                        .setRequireCitations(true)
                                        .build()
                        )
                        .build();

        SubmitMissionResponse submitted =
                agenticMapper.toSubmitMissionResponse(
                        commandBus.dispatch(
                                agenticMapper.toSubmitMissionCommand(
                                        submitRequest
                                )
                        )
                );

        StreamMissionProgressRequest progressRequest =
                StreamMissionProgressRequest
                        .newBuilder()
                        .setContext(context)
                        .setMissionId(
                                submitted.getMissionId()
                        )
                        .build();

        MissionEventCursor cursor =
                queryBus.dispatch(
                        agenticMapper.toStreamMissionProgressQuery(
                                progressRequest
                        )
                );

        SseEmitter emitter =
                new SseEmitter(0L);

        AtomicBoolean stopped =
                new AtomicBoolean(false);

        emitter.onCompletion(
                () -> stopped.set(true)
        );

        emitter.onTimeout(
                () -> stopped.set(true)
        );

        emitter.onError(
                error -> stopped.set(true)
        );

        Thread.ofVirtual()
                .name(
                        "assistant-sse-"
                                + submitted.getMissionId()
                )
                .start(
                        () -> stream(
                                context,
                                submitted,
                                cursor,
                                emitter,
                                stopped
                        )
                );

        return emitter;
    }

    @PostMapping(
            value = "/missions/{missionId}/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> cancelMission(
            @PathVariable String missionId,
            @RequestBody AssistantMissionCancelRequest body
    ) {

        RequestContext context =
                RequestContext
                        .newBuilder()
                        .setRequestId(
                                UUID.randomUUID().toString()
                        )
                        .setTraceId(
                                UUID.randomUUID().toString()
                        )
                        .setTenantId(
                                valueOr(
                                        body.tenantId(),
                                        "tenant-test"
                                )
                        )
                        .setUserId(
                                valueOr(
                                        body.userId(),
                                        "assistant-ui-user"
                                )
                        )
                        .setUsername(
                                "assistant-ui"
                        )
                        .setCallerService(
                                "assistant-ui"
                        )
                        .setClientChannel(
                                "web"
                        )
                        .build();

        CancelMissionRequest cancelRequest =
                CancelMissionRequest
                        .newBuilder()
                        .setContext(context)
                        .setMissionId(missionId)
                        .setReason(
                                valueOr(
                                        body.reason(),
                                        "Stopped by user"
                                )
                        )
                        .build();

        CancelMissionResponse cancelled =
                agenticMapper.toCancelMissionResponse(
                        commandBus.dispatch(
                                agenticMapper
                                        .toCancelMissionCommand(
                                                cancelRequest
                                        )
                        )
                );

        return Map.of(
                "missionId",
                cancelled.getMissionId(),
                "status",
                cancelled.getStatus().name()
        );
    }

    private void stream(
            RequestContext context,
            SubmitMissionResponse submitted,
            MissionEventCursor cursor,
            SseEmitter emitter,
            AtomicBoolean stopped
    ) {

        try (cursor) {

            emitter.send(
                    SseEmitter
                            .event()
                            .name("mission")
                            .data(
                                    Map.of(
                                            "missionId",
                                            submitted.getMissionId(),

                                            "status",
                                            submitted
                                                    .getStatus()
                                                    .name(),

                                            "path",
                                            submitted
                                                    .getSelectedPath()
                                                    .name()
                                    )
                            )
            );

            while (!stopped.get()
                    && !cursor.closed()) {

                Optional<MissionStreamEvent> next =
                        cursor.next(
                                HEARTBEAT_WAIT
                        );

                if (next.isPresent()) {

                    sendStreamEvent(
                            next.orElseThrow(),
                            emitter
                    );

                } else {

                    emitter.send(
                            SseEmitter
                                    .event()
                                    .name("heartbeat")
                                    .data(
                                            Map.of(
                                                    "missionId",
                                                    submitted.getMissionId()
                                            )
                                    )
                    );
                }

                if (cursor.terminal()) {

                    emitTerminalResult(context, submitted, emitter);
                    emitter.complete();
                    return;
                }
            }

        } catch (IOException ignored) {
            // Browser disconnected.
        } catch (Throwable throwable) {

            if (!stopped.get()) {
                emitter.completeWithError(
                        throwable
                );
            }
        }
    }

    private static void sendStreamEvent(
            MissionStreamEvent event,
            SseEmitter emitter
    ) throws IOException {

        if (event instanceof MissionProgressEvent progress) {

            emitter.send(
                    SseEmitter
                            .event()
                            .name("progress")
                            .id(progress.resumeToken())
                            .data(
                                    toProgressData(
                                            progress
                                    )
                            )
            );

            return;
        }

        if (event instanceof MissionStreamEvent.AnswerDelta delta) {

            emitter.send(
                    SseEmitter
                            .event()
                            .name("answer_delta")
                            .id(delta.resumeToken())
                            .data(
                                    Map.of(
                                            "missionId",
                                            delta.missionId(),

                                            "sequence",
                                            delta.sequence(),

                                            "text",
                                            delta.text(),

                                            "emittedAt",
                                            delta.emittedAt().toString()
                                    )
                            )
            );

            return;
        }

        if (event instanceof MissionStreamEvent.Usage usage) {

            emitter.send(
                    SseEmitter
                            .event()
                            .name("usage")
                            .id(usage.resumeToken())
                            .data(
                                    toUsageData(
                                            usage
                                    )
                            )
            );

            return;
        }

        throw new IllegalStateException(
                "Unsupported mission stream event type: "
                        + event.getClass().getName()
        );
    }

    private void emitTerminalResult(
            RequestContext context,
            SubmitMissionResponse submitted,
            SseEmitter emitter
    ) throws IOException {

        GetMissionResultRequest resultRequest =
                GetMissionResultRequest
                        .newBuilder()
                        .setContext(context)
                        .setMissionId(
                                submitted.getMissionId()
                        )
                        .build();

        MissionResultResponse resultResponse =
                agenticMapper.toMissionResultResponse(
                        queryBus.dispatch(
                                agenticMapper
                                        .toGetMissionResultQuery(
                                                resultRequest
                                        )
                        )
                );

        /*
         * Mission failed.
         */
        if (resultResponse.hasError()) {

            var error =
                    resultResponse.getError();

            emitter.send(
                    SseEmitter
                            .event()
                            .name("error")
                            .data(
                                    Map.of(
                                            "missionId",
                                            submitted.getMissionId(),

                                            "code",
                                            error.getCode(),

                                            "message",
                                            safeErrorMessage(
                                                    error.getCode()
                                            ),

                                            "retryable",
                                            error.getRetryable()
                                    )
                            )
            );

            return;
        }

        /*
         * Mission succeeded.
         */
        if (resultResponse.hasResult()) {

            var result =
                    resultResponse.getResult();

            emitter.send(
                    SseEmitter
                            .event()
                            .name("result")
                            .data(
                                    Map.of(
                                            "missionId",
                                            submitted.getMissionId(),

                                            "executiveSummary",
                                            result.getExecutiveSummary(),

                                            "finalAnswer",
                                            result.getFinalAnswer(),

                                            "citations",
                                            result
                                                    .getCitationsList()
                                                    .stream()
                                                    .map(
                                                            citation ->
                                                                    Map.of(
                                                                            "id",
                                                                            citation.getCitationId(),

                                                                            "label",
                                                                            citation.getLabel(),

                                                                            "evidenceId",
                                                                            citation.getEvidenceId(),

                                                                            "excerpt",
                                                                            citation.getExcerpt()
                                                                    )
                                                    )
                                                    .toList()
                                    )
                            )
            );

            emitter.send(
                    SseEmitter
                            .event()
                            .name("complete")
                            .data(
                                    Map.of(
                                            "missionId",
                                            submitted.getMissionId()
                                    )
                            )
            );

            return;
        }

        /*
         * Defensive fallback:
         * terminal mission but neither result nor error exists.
         */
        emitter.send(
                SseEmitter
                        .event()
                        .name("error")
                        .data(
                                Map.of(
                                        "missionId",
                                        submitted.getMissionId(),

                                        "code",
                                        "MISSION_TERMINATED_WITHOUT_RESULT",

                                        "message",
                                        "SparrowX could not complete the request.",

                                        "retryable",
                                        true
                                )
                        )
        );
    }

    private static String safeErrorMessage(
            String code
    ) {

        if (code == null
                || code.isBlank()) {

            return "SparrowX could not complete the request.";
        }

        return switch (code) {

            case "INTERNAL_SERVICE_UNAVAILABLE" ->
                    "The internal knowledge service is currently unavailable.";

            case "DOCUMENT_SERVICE_UNAVAILABLE" ->
                    "The document service is currently unavailable.";

            case "MISSION_FAILED_RETRYABLE" ->
                    "Sparrow could not complete the request. Please try again.";

            case "MISSION_FAILED_TERMINAL" ->
                    "Sparrow could not complete the request.";

            default ->
                    "Sparrow could not complete the request.";
        };
    }
    private static Map<String, Object> toProgressData(
            MissionProgressEvent event
    ) {

        Map<String, Object> data = new LinkedHashMap<>();

        data.put("missionId", event.missionId());
        data.put("status", event.status().name());
        data.put("stageId", event.stageId());
        data.put("stageName", event.stageName());
        data.put("stepId", event.stepId());
        data.put("stepName", event.stepName());
        data.put("stepStatus", event.stepStatus().name());
        data.put("message", event.message());
        data.put("progressPercent", event.progressPercent());
        data.put("resumeToken", event.resumeToken());
        data.put("emittedAt", event.emittedAt().toString());

        if (event.currentComponent() != null) {

            var component =
                    event.currentComponent();

            data.put(
                    "componentId",
                    component.componentId()
            );

            data.put(
                    "componentKind",
                    component.componentKind().name()
            );

            data.put(
                    "componentName",
                    component.componentName()
            );
        }

        return Map.copyOf(data);
    }

    private static Map<String, Object> toUsageData(
            MissionStreamEvent.Usage usage
    ) {

        Map<String, Object> data =
                new LinkedHashMap<>();

        data.put(
                "missionId",
                usage.missionId()
        );

        data.put(
                "operationId",
                usage.operationId()
        );

        data.put(
                "kind",
                usage.kind().name()
        );

        data.put(
                "componentId",
                usage.componentId()
        );

        data.put(
                "model",
                usage.model()
        );

        data.put(
                "inputTokens",
                usage.inputTokens()
        );

        data.put(
                "cachedInputTokens",
                usage.cachedInputTokens()
        );

        data.put(
                "outputTokens",
                usage.outputTokens()
        );

        data.put(
                "totalTokens",
                usage.totalTokens()
        );

        data.put(
                "embeddingTokens",
                usage.embeddingTokens()
        );

        data.put(
                "vectorsGenerated",
                usage.vectorsGenerated()
        );

        data.put(
                "durationMs",
                usage.durationMs()
        );

        data.put(
                "emittedAt",
                usage.emittedAt().toString()
        );

        return Map.copyOf(data);
    }

    private static String valueOr(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value;
    }

    public record AssistantMissionRequest(
            String query,
            String tenantId,
            String userId,
            String conversationId
    ) {
    }

    public record AssistantMissionCancelRequest(
            String tenantId,
            String userId,
            String reason
    ) {
    }
}