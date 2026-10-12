package com.sparrowx.agentic.steps;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.components.GraphEvidenceGoalResolver;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.graph.GraphRootScope;
import com.sparrowx.agentic.tools.internal.GraphEvidenceProjector;
import com.sparrowx.agentic.tools.internal.InternalContextTool;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import org.springframework.stereotype.Component;

import java.util.*;

/** Compiles one semantic query per root type and lets IntSvc evaluate each complete root set. */
@Component
public final class GoalDirectedCompanyGraphStep {
    private final GraphEvidenceGoalResolver goalResolver;
    private final InternalGraphRequestBuilder requests;
    private final InternalContextTool tool;

    public GoalDirectedCompanyGraphStep(GraphEvidenceGoalResolver goalResolver,
            InternalGraphRequestBuilder requests, InternalContextTool tool) {
        this.goalResolver = Objects.requireNonNull(goalResolver);
        this.requests = Objects.requireNonNull(requests);
        this.tool = Objects.requireNonNull(tool);
    }

    public record SelectedRoot(String entityId, InternalGraphNodeType nodeType) {
        public SelectedRoot {
            if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("Root ID required");
            Objects.requireNonNull(nodeType);
        }
    }
    public record Prepared(Map<String, InternalGraphNodeType> selectedRoots,
            Map<InternalGraphNodeType, GraphEvidenceGoalResolver.Resolution> compiledGoals, String query) {
        public Prepared {
            selectedRoots = Map.copyOf(selectedRoots);
            compiledGoals = Map.copyOf(compiledGoals);
            if (selectedRoots.isEmpty() || compiledGoals.isEmpty())
                throw new IllegalArgumentException("Empty graph query plan");
        }
    }
    public record Result(GraphResolutionStatus status, List<EvidenceRef> evidenceRefs, List<String> warnings,
            String summary, Map<String, Object> attributes) {
        public Result {
            evidenceRefs = List.copyOf(evidenceRefs);
            warnings = List.copyOf(warnings);
            attributes = Map.copyOf(attributes);
        }
    }

    public Prepared prepare(MissionContext context, String requestId, List<SelectedRoot> roots,
            MissionIntent intent, String query) {
        Objects.requireNonNull(context); Objects.requireNonNull(roots); Objects.requireNonNull(intent);
        if (requestId == null || requestId.isBlank() || query == null || query.isBlank() || roots.isEmpty())
            throw new IllegalArgumentException("Batch ID, question and roots are required");
        Map<String, InternalGraphNodeType> selected = new LinkedHashMap<>();
        Map<InternalGraphNodeType, Set<String>> groups = new LinkedHashMap<>();
        for (SelectedRoot root : roots) {
            if (selected.putIfAbsent(root.entityId(), root.nodeType()) != null)
                throw new IllegalArgumentException("Duplicate root ID");
            groups.computeIfAbsent(root.nodeType(), unused -> new LinkedHashSet<>()).add(root.entityId());
        }
        Map<InternalGraphNodeType, GraphEvidenceGoalResolver.Resolution> compiled = new LinkedHashMap<>();
        groups.forEach((type, ids) -> compiled.put(type,
                goalResolver.compile(context, intent, query,
                        new GraphRootScope(nodeTypeName(type), ids), requestId + ":goal:" + type.name())));
        return new Prepared(selected, compiled, query);
    }

    public Result executeBatch(MissionContext context, String requestId, Prepared prepared,
            InternalGraphNodeType type) {
        Objects.requireNonNull(prepared);
        var resolution = Objects.requireNonNull(prepared.compiledGoals().get(type), "Unknown root type");
        if (!resolution.ready()) return failed(resolution.status(), resolution.reason());
        GraphQueryRequest query = resolution.query();
        Set<String> selected = new HashSet<>();
        prepared.selectedRoots().forEach((id, nodeType) -> {
            if (nodeType == type) selected.add(id);
        });
        if (!query.rootType().equals(nodeTypeName(type)) || !query.rootIds().equals(selected))
            throw new IllegalArgumentException("Compiled query does not match the resolved root set");
        try {
            var response = tool.executeCompanyGraphQuery(context,
                    requests.buildCompanyGraphQuery(context, requestId, query));
            if (response.getTruncated() || !Set.copyOf(response.getEvaluatedRootEntityIdsList()).equals(selected))
                return failed(GraphResolutionStatus.INSUFFICIENT, "Graph query did not exhaust the resolved root set");
            var rows = GraphEvidenceProjector.project(response);
            var refs = GraphEvidenceProjector.evidence(response, requestId, selected);
            return new Result(GraphResolutionStatus.COMPLETE, refs, List.of(),
                    rows.isEmpty() ? "Exhaustive graph query found no matching rows"
                            : "Resolved " + selected.size() + " graph roots",
                    Map.of("graphResolutionStatus", GraphResolutionStatus.COMPLETE.name(),
                            "validatedGraphAnswerRows", rows, "evaluatedRoots", selected.size(),
                            "matchingRowCount", rows.size()));
        } catch (RuntimeException e) {
            return failed(GraphResolutionStatus.INSUFFICIENT, "IntSvc graph query failed: " + e.getClass().getSimpleName());
        }
    }

    private static Result failed(GraphResolutionStatus status, String reason) {
        return new Result(status, List.of(), List.of(reason), reason,
                Map.of("graphResolutionStatus", status.name(), "graphResolutionReason", reason));
    }
    private static String nodeTypeName(InternalGraphNodeType type) {
        String prefix = "INTERNAL_GRAPH_NODE_TYPE_";
        if (type == null || type == InternalGraphNodeType.UNRECOGNIZED
                || type == InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                || !type.name().startsWith(prefix))
            throw new IllegalArgumentException("Invalid graph root node type");
        return type.name().substring(prefix.length());
    }
}
