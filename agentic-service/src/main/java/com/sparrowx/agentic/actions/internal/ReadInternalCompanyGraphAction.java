package com.sparrowx.agentic.actions.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalContextMapper;
import com.sparrowx.agentic.tools.internal.InternalContextTool;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.GraphSpec;
import com.sparrowx.internal.grpc.*;

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

        System.out.printf(
                "COMPANY GRAPH root=%s depth=%d nodes=%d edges=%d%n",
                spec.rootEntityId(),
                spec.depth(),
                response.getGraph().getNodesCount(),
                response.getGraph().getRelationshipsCount()
        );

        response.getGraph().getNodesList().forEach(node ->
                System.out.printf(
                        "GRAPH NODE id=%s type=%s label=%s summary=%s%n",
                        node.getEntityId(),
                        node.getNodeType(),
                        node.getLabel(),
                        node.getSummary()
                )
        );

        response.getGraph().getRelationshipsList().forEach(edge ->
                System.out.printf(
                        "GRAPH EDGE %s -> %s relation=%s%n",
                        edge.getFromNodeId(),
                        edge.getToNodeId(),
                        edge.getRelationshipType()
                )
        );

        if (!response.hasGraph()) {
            throw new IllegalStateException("Internal Service returned no company graph");
        }


// ADD DIAGNOSTIC HERE
        response.getGraph().getNodesList().stream()
                .filter(node -> node.getNodeType() ==
                        InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_ENGINEER)
                .forEach(node ->
                        System.out.printf(
                                "ENGINEER id=%s name=%s role=%s attributes=%s%n",
                                node.getEntityId(),
                                node.getLabel(),
                                node.getAttributesOrDefault("role", "<missing>"),
                                node.getAttributesMap()
                        )
                );

// Existing mapping/return logic follows.
        return new Result(
                response.getGraph(),
                contextMapper.fromCompanyGraph(response)
        );

    }

    public ExpansionResult expand(
            MissionContext context,
            InternalGraphRequestBuilder.ExpansionSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        ExpandInternalCompanyGraphRequest request =
                requestBuilder.buildGraphExpansion(context, spec);

        ExpandInternalCompanyGraphResponse response =
                contextTool.expandCompanyGraph(context, request);

        if (!response.hasGraph()) {
            throw new IllegalStateException(
                    "Internal Service returned no graph for expansion"
            );
        }

        Result mapped = mapGraph(response.getGraph());

        return new ExpansionResult(
                mapped.graph(),
                mapped.evidenceRefs(),
                response.getTruncated()
        );
    }

    /*
     * Use this after the traversal controller merges all graph slices.
     * Mapping the merged graph regenerates display facts across the
     * complete collected evidence.
     */
    public Result mapGraph(InternalGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");

        ReadInternalCompanyGraphResponse envelope =
                ReadInternalCompanyGraphResponse.newBuilder()
                        .setGraph(graph)
                        .build();

        return new Result(
                graph,
                contextMapper.fromCompanyGraph(envelope)
        );
    }

    public record ExpansionResult(
            InternalGraph graph,
            List<EvidenceRef> evidenceRefs,
            boolean truncated
    ) {
        public ExpansionResult {
            graph = Objects.requireNonNull(
                    graph,
                    "graph must not be null"
            );

            evidenceRefs = List.copyOf(
                    Objects.requireNonNull(
                            evidenceRefs,
                            "evidenceRefs must not be null"
                    )
            );
        }
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