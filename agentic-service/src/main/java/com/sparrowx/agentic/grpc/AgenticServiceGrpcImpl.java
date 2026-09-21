package com.sparrowx.agentic.grpc;

import buildingblocks.core.commands.CommandBus;
import buildingblocks.core.queries.QueryBus;
import com.sparrowx.agentic.config.SecurityConfig.CallerIdentityProvider;
import com.sparrowx.agentic.config.SecurityConfig.ReviewerAuthorizationPolicy;
import com.sparrowx.agentic.features.streammissionprogress.MissionEventCursor;
import com.sparrowx.agentic.mappers.AgenticMapper;
import com.sparrowx.agentic.mappers.MissionEventGrpcMapper;
import com.sparrowx.agentic.mission.model.MissionStreamEvent;
import com.sparrowx.agentic.proto.AgenticServiceGrpc;
import com.sparrowx.agentic.proto.ApproveMissionGateRequest;
import com.sparrowx.agentic.proto.ApproveMissionGateResponse;
import com.sparrowx.agentic.proto.CancelMissionRequest;
import com.sparrowx.agentic.proto.CancelMissionResponse;
import com.sparrowx.agentic.proto.GetMissionResultRequest;
import com.sparrowx.agentic.proto.MissionResultResponse;
import com.sparrowx.agentic.proto.RejectMissionGateRequest;
import com.sparrowx.agentic.proto.RejectMissionGateResponse;
import com.sparrowx.agentic.proto.StreamMissionProgressRequest;
import com.sparrowx.agentic.proto.SubmitMissionRequest;
import com.sparrowx.agentic.proto.SubmitMissionResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@GrpcService
public final class AgenticServiceGrpcImpl
        extends AgenticServiceGrpc.AgenticServiceImplBase {

    private static final Duration STREAM_WAIT =
            Duration.ofSeconds(15);

    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final AgenticMapper agenticMapper;
    private final MissionEventGrpcMapper eventMapper;
    private final GrpcExceptionHandler exceptionHandler;
    private final ReviewerAuthorizationPolicy reviewerPolicy;
    private final CallerIdentityProvider identityProvider;

    public AgenticServiceGrpcImpl(
            CommandBus commandBus,
            QueryBus queryBus,
            AgenticMapper agenticMapper,
            MissionEventGrpcMapper eventMapper,
            GrpcExceptionHandler exceptionHandler,
            ReviewerAuthorizationPolicy reviewerPolicy,
            CallerIdentityProvider identityProvider
    ) {
        this.commandBus = Objects.requireNonNull(
                commandBus,
                "commandBus must not be null"
        );

        this.queryBus = Objects.requireNonNull(
                queryBus,
                "queryBus must not be null"
        );

        this.agenticMapper = Objects.requireNonNull(
                agenticMapper,
                "agenticMapper must not be null"
        );

        this.eventMapper = Objects.requireNonNull(
                eventMapper,
                "eventMapper must not be null"
        );

        this.exceptionHandler = Objects.requireNonNull(
                exceptionHandler,
                "exceptionHandler must not be null"
        );

        this.reviewerPolicy = Objects.requireNonNull(
                reviewerPolicy,
                "reviewerPolicy must not be null"
        );

        this.identityProvider = Objects.requireNonNull(
                identityProvider,
                "identityProvider must not be null"
        );
    }

    @Override
    public void submitMission(
            SubmitMissionRequest request,
            StreamObserver<SubmitMissionResponse> responseObserver
    ) {
        System.out.println(
                ">>> GRPC BODY requestId=["
                        + request.getContext().getRequestId()
                        + "] traceId=["
                        + request.getContext().getTraceId()
                        + "]"
        );

        unary(
                responseObserver,
                () -> agenticMapper.toSubmitMissionResponse(
                        commandBus.dispatch(
                                agenticMapper.toSubmitMissionCommand(
                                        request
                                )
                        )
                )
        );
    }

    @Override
    public void streamMissionProgress(
            StreamMissionProgressRequest request,
            StreamObserver<com.sparrowx.agentic.proto.MissionStreamEvent>
                    responseObserver
    ) {
        final MissionEventCursor cursor;

        try {
            cursor = queryBus.dispatch(
                    agenticMapper.toStreamMissionProgressQuery(
                            request
                    )
            );
        } catch (Throwable throwable) {
            responseObserver.onError(
                    exceptionHandler.toStatusRuntimeException(
                            throwable
                    )
            );

            return;
        }

        AtomicBoolean responseFinished =
                new AtomicBoolean(false);

        ServerCallStreamObserver<
                com.sparrowx.agentic.proto.MissionStreamEvent
                > serverObserver =
                asServerObserver(
                        responseObserver
                );

        if (serverObserver != null) {
            serverObserver.setOnCancelHandler(
                    cursor::close
            );
        }

        Thread.ofVirtual()
                .name(
                        "agentic-stream-"
                                + normalizeThreadName(
                                request.getMissionId()
                        )
                )
                .start(
                        () -> streamCursor(
                                cursor,
                                serverObserver,
                                responseObserver,
                                responseFinished
                        )
                );
    }

    @Override
    public void getMissionResult(
            GetMissionResultRequest request,
            StreamObserver<MissionResultResponse> responseObserver
    ) {
        unary(
                responseObserver,
                () -> agenticMapper.toMissionResultResponse(
                        queryBus.dispatch(
                                agenticMapper
                                        .toGetMissionResultQuery(
                                                request
                                        )
                        )
                )
        );
    }

    @Override
    public void cancelMission(
            CancelMissionRequest request,
            StreamObserver<CancelMissionResponse> responseObserver
    ) {
        unary(
                responseObserver,
                () -> agenticMapper.toCancelMissionResponse(
                        commandBus.dispatch(
                                agenticMapper
                                        .toCancelMissionCommand(
                                                request
                                        )
                        )
                )
        );
    }

    @Override
    public void approveMissionGate(
            ApproveMissionGateRequest request,
            StreamObserver<ApproveMissionGateResponse> responseObserver
    ) {
        unary(
                responseObserver,
                () -> {
                    reviewerPolicy.requireReviewer(
                            identityProvider.currentIdentity()
                    );

                    return agenticMapper
                            .toApproveMissionGateResponse(
                                    commandBus.dispatch(
                                            agenticMapper
                                                    .toApproveMissionGateCommand(
                                                            request
                                                    )
                                    )
                            );
                }
        );
    }

    @Override
    public void rejectMissionGate(
            RejectMissionGateRequest request,
            StreamObserver<RejectMissionGateResponse> responseObserver
    ) {
        unary(
                responseObserver,
                () -> {
                    reviewerPolicy.requireReviewer(
                            identityProvider.currentIdentity()
                    );

                    return agenticMapper
                            .toRejectMissionGateResponse(
                                    commandBus.dispatch(
                                            agenticMapper
                                                    .toRejectMissionGateCommand(
                                                            request
                                                    )
                                    )
                            );
                }
        );
    }

    private void streamCursor(
            MissionEventCursor cursor,
            ServerCallStreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    > serverObserver,
            StreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    > responseObserver,
            AtomicBoolean responseFinished
    ) {
        try (cursor) {

            while (!cursor.closed()
                    && !isCancelled(serverObserver)) {

                Optional<MissionStreamEvent> next =
                        cursor.next(
                                STREAM_WAIT
                        );

                if (next.isPresent()) {

                    responseObserver.onNext(
                            eventMapper.toProto(
                                    next.orElseThrow()
                            )
                    );
                }

                if (cursor.terminal()) {

                    complete(
                            responseObserver,
                            responseFinished,
                            serverObserver
                    );

                    return;
                }
            }

            complete(
                    responseObserver,
                    responseFinished,
                    serverObserver
            );

        } catch (Throwable throwable) {

            if (!isCancelled(serverObserver)
                    && responseFinished.compareAndSet(
                    false,
                    true
            )) {

                responseObserver.onError(
                        exceptionHandler
                                .toStatusRuntimeException(
                                        throwable
                                )
                );
            }
        }
    }

    private <T> void unary(
            StreamObserver<T> responseObserver,
            UnaryInvocation<T> invocation
    ) {
        Objects.requireNonNull(
                responseObserver,
                "responseObserver must not be null"
        );

        Objects.requireNonNull(
                invocation,
                "invocation must not be null"
        );

        try {
            T response =
                    Objects.requireNonNull(
                            invocation.invoke(),
                            "RPC handler returned null"
                    );

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Throwable throwable) {

            responseObserver.onError(
                    exceptionHandler
                            .toStatusRuntimeException(
                                    throwable
                            )
            );
        }
    }

    private static void complete(
            StreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    > responseObserver,
            AtomicBoolean responseFinished,
            ServerCallStreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    > serverObserver
    ) {
        if (!isCancelled(serverObserver)
                && responseFinished.compareAndSet(
                false,
                true
        )) {
            responseObserver.onCompleted();
        }
    }

    private static boolean isCancelled(
            ServerCallStreamObserver<?> observer
    ) {
        return observer != null
                && observer.isCancelled();
    }

    @SuppressWarnings("unchecked")
    private static ServerCallStreamObserver<
            com.sparrowx.agentic.proto.MissionStreamEvent
            > asServerObserver(
            StreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    > observer
    ) {
        if (observer
                instanceof ServerCallStreamObserver<?> server) {

            return (ServerCallStreamObserver<
                    com.sparrowx.agentic.proto.MissionStreamEvent
                    >) server;
        }

        return null;
    }

    private static String normalizeThreadName(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        return value.replaceAll(
                "[^a-zA-Z0-9._-]",
                "_"
        );
    }

    @FunctionalInterface
    private interface UnaryInvocation<T> {

        T invoke();
    }
}