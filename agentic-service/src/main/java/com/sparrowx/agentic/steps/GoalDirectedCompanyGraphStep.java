package com.sparrowx.agentic.steps;

import com.sparrowx.agentic.components.GraphEvidenceGoalResolver;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.GraphTraversal;
import com.sparrowx.agentic.planning.GraphTraversal.*;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.ExpansionSpec;
import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.InternalGraphNode;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import com.sparrowx.internal.grpc.InternalGraphRelationship;
import com.sparrowx.internal.grpc.InternalGraphRelationshipType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class GoalDirectedCompanyGraphStep {

    private final ResolveInternalContextStep internalStep;
    private final GraphEvidenceGoalResolver goalResolver;
    private final SystemOneDecisionClient systemOne;
    private final Limits limits;

    public GoalDirectedCompanyGraphStep(
            ResolveInternalContextStep internalStep,
            GraphEvidenceGoalResolver goalResolver,
            SystemOneDecisionClient systemOne,

            @Value("${sparrowx.graph.max-hops:8}")
            int maxHops,

            @Value("${sparrowx.graph.max-steps:16}")
            int maxSteps,

            @Value("${sparrowx.graph.max-nodes:150}")
            int maxNodes,

            @Value("${sparrowx.graph.max-edges:300}")
            int maxEdges,

            @Value("${sparrowx.graph.max-expansions:32}")
            int maxExpansions,

            @Value("${sparrowx.graph.max-bindings:500}")
            int maxBindings,

            @Value("${sparrowx.graph.max-traversal-millis:30000}")
            long maxTraversalMillis
    ) {
        this.internalStep = Objects.requireNonNull(internalStep);
        this.goalResolver = Objects.requireNonNull(goalResolver);
        this.systemOne = Objects.requireNonNull(systemOne);

        this.limits = new Limits(
                maxHops,
                maxSteps,
                maxNodes,
                maxEdges,
                maxExpansions,
                maxBindings,
                maxTraversalMillis
        );
    }

    public Result execute(
            MissionContext context,
            String requestId,
            String rootEntityId,
            InternalGraphNodeType rootNodeType,
            MissionIntent intent,
            String query
    ) {
        var seed = internalStep.readCompanyGraphRoot(
                context,
                requestId + ":root",
                rootEntityId,
                rootNodeType
        );

        InternalGraphNode rootProto =
                seed.graph().getNodes(0);

        Node root = node(rootProto);

        var resolution = goalResolver.resolve(
                context,
                intent,
                query,
                root,
                requestId + ":goal"
        );

        if (!resolution.ready()) {
            return new Result(
                    resolution.status(),
                    List.of(),
                    List.of(resolution.reason()),
                    resolution.reason(),
                    Map.of(
                            "graphResolutionStatus",
                            resolution.status().name(),
                            "graphResolutionReason",
                            resolution.reason()
                    )
            );
        }

        Map<String, InternalGraphNode> protoNodes =
                new LinkedHashMap<>();

        Map<String, InternalGraphRelationship> protoEdges =
                new LinkedHashMap<>();

        protoNodes.put(rootProto.getNodeId(), rootProto);

        GraphTraversal traversal =
                new GraphTraversal(limits);

        GraphTraversal.Result traversed = traversal.execute(
                resolution.goal(),
                root,
                (nodeId, relationship, edgeLimit, round) -> {
                    InternalGraphRelationshipType relationshipType =
                            InternalGraphRelationshipType.valueOf(
                                    "INTERNAL_GRAPH_RELATIONSHIP_TYPE_"
                                            + relationship
                            );

                    var expanded = internalStep.expandCompanyGraph(
                            context,
                            new ExpansionSpec(
                                    requestId + ":expand:" + round,
                                    Set.of(nodeId),
                                    Set.of(relationshipType),
                                    edgeLimit
                            )
                    );

                    InternalGraph graph = expanded.graph();

                    graph.getNodesList().forEach(value ->
                            protoNodes.put(value.getNodeId(), value)
                    );

                    graph.getRelationshipsList().forEach(value ->
                            protoEdges.put(
                                    value.getRelationshipId(),
                                    value
                            )
                    );

                    return new Slice(
                            graph.getNodesList()
                                    .stream()
                                    .map(GoalDirectedCompanyGraphStep::node)
                                    .toList(),

                            graph.getRelationshipsList()
                                    .stream()
                                    .map(GoalDirectedCompanyGraphStep::edge)
                                    .toList(),

                            expanded.truncated()
                    );
                },
                (eligible, completedSteps, bindingCount) ->
                        selectStep(
                                query,
                                eligible,
                                completedSteps,
                                bindingCount,
                                protoNodes.values()
                        )
        );

        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put(
                "graphResolutionStatus",
                traversed.status().name()
        );

        attributes.put(
                "graphResolutionReason",
                traversed.reason()
        );

        attributes.put(
                "expansionCount",
                traversed.expansions()
        );

        attributes.put(
                "actualHops",
                traversed.hops()
        );

        attributes.put(
                "requestedGraphOutputs",
                resolution.goal().outputs()
                        .stream()
                        .map(output -> Map.of(
                                "variable", output.variable(),
                                "field", output.field()
                        ))
                        .toList()
        );

        attributes.put(
                "validatedGraphAnswerRows",
                traversed.answers()
        );

        if (traversed.status() != Status.COMPLETE) {
            return new Result(
                    traversed.status(),
                    List.of(),
                    List.of(traversed.reason()),
                    traversed.reason(),
                    Map.copyOf(attributes)
            );
        }

        /*
         * Preserve original protobuf node attributes and relationship
         * metadata, but include only the proofs supporting final bindings.
         */
        InternalGraph.Builder merged = seed.graph()
                .toBuilder()
                .clearNodes()
                .clearRelationships()
                .setGraphId(requestId + ":resolved");

        for (Node selected : traversed.nodes()) {
            merged.addNodes(
                    Objects.requireNonNull(
                            protoNodes.get(selected.id())
                    )
            );
        }

        for (Edge selected : traversed.edges()) {
            merged.addRelationships(
                    Objects.requireNonNull(
                            protoEdges.get(selected.id())
                    )
            );
        }

        var evidence = internalStep.mapMergedCompanyGraph(
                context,
                requestId + ":merged",
                merged.build()
        );

        attributes.put(
                "serializedSizeBytes",
                evidence.graph().getSerializedSize()
        );

        return new Result(
                Status.COMPLETE,
                evidence.evidenceRefs(),
                List.of(),
                traversed.reason(),
                Map.copyOf(attributes)
        );
    }

    private Step selectStep(
            String query,
            List<Step> eligible,
            int completedSteps,
            int bindingCount,
            Collection<InternalGraphNode> collectedNodes
    ) {
        Map<String, String> options = new LinkedHashMap<>();

        for (int i = 0; i < eligible.size(); i++) {
            options.put(
                    "step_" + i,
                    eligible.get(i).toString()
            );
        }

        List<Map<String, Object>> evidence =
                collectedNodes.stream()
                        .map(value -> Map.<String, Object>of(
                                "nodeId", value.getNodeId(),
                                "nodeType", value.getNodeType().name(),
                                "label", value.getLabel(),
                                "attributes", value.getAttributesMap()
                        ))
                        .toList();

        var decision = systemOne.evaluate(
                Map.of(
                        "query", query,
                        "completedSteps", completedSteps,
                        "bindingCount", bindingCount,
                        "collectedNodes", evidence
                ),
                Map.of(
                        "next",
                        new ChoiceQuestion(
                                """
                                Choose the next eligible graph requirement
                                most likely to reduce remaining work.

                                Every option is a mandatory requirement.
                                Prefer selective filters when useful.
                                Do not declare completion or invent steps.
                                """,
                                options
                        )
                )
        );

        String selected = decision.choiceValue("next");

        if (!options.containsKey(selected)) {
            throw new IllegalStateException(
                    "System-1 selected an unknown graph step"
            );
        }

        int index = Integer.parseInt(
                selected.substring("step_".length())
        );

        return eligible.get(index);
    }

    private static Node node(
            InternalGraphNode node
    ) {
        return new Node(
                node.getNodeId(),
                node.getNodeType().name().substring(
                        "INTERNAL_GRAPH_NODE_TYPE_".length()
                ),
                node.getEntityId(),
                node.getLabel(),
                node.getSummary(),
                node.getAttributesMap()
        );
    }

    private static Edge edge(
            InternalGraphRelationship edge
    ) {
        return new Edge(
                edge.getRelationshipId(),
                edge.getFromNodeId(),
                edge.getToNodeId(),
                edge.getRelationshipType().name().substring(
                        "INTERNAL_GRAPH_RELATIONSHIP_TYPE_".length()
                )
        );
    }

    public record Result(
            Status status,
            List<EvidenceRef> evidenceRefs,
            List<String> warnings,
            String summary,
            Map<String, Object> attributes
    ) {
        public Result {
            Objects.requireNonNull(status);
            evidenceRefs = List.copyOf(evidenceRefs);
            warnings = List.copyOf(warnings);
            summary = Objects.requireNonNull(summary);
            attributes = Map.copyOf(attributes);
        }
    }
}