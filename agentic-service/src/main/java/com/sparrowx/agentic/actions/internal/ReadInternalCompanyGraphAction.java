package com.sparrowx.agentic.actions.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalContextMapper;
import com.sparrowx.agentic.tools.internal.InternalContextTool;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.GraphSpec;
import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphRequest;
import com.sparrowx.internal.grpc.ReadInternalCompanyGraphResponse;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic Internal Service company-graph executor.
 *
 * Embabel planning happens above this class.
 */
public final class ReadInternalCompanyGraphAction {

    private final InternalGraphRequestBuilder requestBuilder;
    private final InternalContextTool contextTool;
    private final InternalContextMapper contextMapper;

    public ReadInternalCompanyGraphAction(
            InternalGraphRequestBuilder requestBuilder,
            InternalContextTool contextTool,
            InternalContextMapper contextMapper
    ) {
        this.requestBuilder = Objects.requireNonNull(
                requestBuilder,
                "requestBuilder must not be null"
        );

        this.contextTool = Objects.requireNonNull(
                contextTool,
                "contextTool must not be null"
        );

        this.contextMapper = Objects.requireNonNull(
                contextMapper,
                "contextMapper must not be null"
        );
    }

    public Result execute(
            MissionContext context,
            GraphSpec spec
    ) {
        ReadInternalCompanyGraphRequest request =
                requestBuilder.buildCompanyGraph(context, spec);

        ReadInternalCompanyGraphResponse response =
                contextTool.readCompanyGraph(context, request);

        if (!response.hasGraph()) {
            throw new IllegalStateException(
                    "Internal Service returned no company graph"
            );
        }

        return new Result(
                response.getGraph(),
                contextMapper.fromCompanyGraph(response)
        );
    }

    public record Result(
            InternalGraph graph,
            List<EvidenceRef> evidenceRefs
    ) {

        public Result {
            graph = Objects.requireNonNull(
                    graph,
                    "graph must not be null"
            );

            evidenceRefs = evidenceRefs == null
                    ? List.of()
                    : List.copyOf(evidenceRefs);
        }
    }
}