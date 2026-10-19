package com.sparrowx.agentic.steps;

import buildingblocks.contracts.GraphQueryRequest;
import com.sparrowx.agentic.components.GraphEvidenceGoalResolver;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.trace.MissionDecisionTraceContext;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;
import com.sparrowx.agentic.planning.graph.GraphRootScope;
import com.sparrowx.agentic.tools.internal.GraphEvidenceProjector;
import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.agentic.adapters.internal.GraphQueryRequestMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Typed, batch-executed graph goals for arbitrary tenant ontology node types. */
@Component
public final class GoalDirectedCompanyGraphStep {
    private static final Logger LOG = LoggerFactory.getLogger(GoalDirectedCompanyGraphStep.class);
    private final GraphEvidenceGoalResolver resolver;
    private final GraphQueryRequestMapper requests;
    private final InternalGrpcClient client;
    public GoalDirectedCompanyGraphStep(GraphEvidenceGoalResolver resolver,
                                        GraphQueryRequestMapper requests, InternalGrpcClient client) {
        this.resolver=Objects.requireNonNull(resolver);
        this.requests=Objects.requireNonNull(requests);
        this.client=Objects.requireNonNull(client);
    }
    public record SelectedRoot(String entityId,String nodeType) {
        public SelectedRoot {
            if (entityId==null || entityId.isBlank() || nodeType==null || nodeType.isBlank())
                throw new IllegalArgumentException("Resolved root identity/type required");
        }
    }
    public record Prepared(Map<String,Set<String>> rootsByType,
                           Map<String,GraphEvidenceGoalResolver.Resolution> compiledGoals,String query) {
        public Prepared {
            Map<String,Set<String>> safe=new LinkedHashMap<>();
            rootsByType.forEach((type,ids)->safe.put(type,Set.copyOf(ids)));
            rootsByType=Map.copyOf(safe);
            compiledGoals=Map.copyOf(compiledGoals);
            if (rootsByType.isEmpty() || compiledGoals.isEmpty())
                throw new IllegalArgumentException("Empty graph query plan");
        }
    }
    public record Result(GraphResolutionStatus status,List<EvidenceRef> evidenceRefs,
                         List<String> warnings,String summary,Map<String,Object> attributes) {
        public Result {
            evidenceRefs=List.copyOf(evidenceRefs);warnings=List.copyOf(warnings);
            attributes=Map.copyOf(attributes);
        }
    }
    public Prepared prepare(MissionContext context,String requestId,List<SelectedRoot> roots,
                            MissionIntent intent,String query) {
        Objects.requireNonNull(context); Objects.requireNonNull(roots); Objects.requireNonNull(intent);
        if (requestId==null || requestId.isBlank() || query==null || query.isBlank() || roots.isEmpty())
            throw new IllegalArgumentException("Request ID, question and roots required");
        Map<String,Set<String>> grouped=new LinkedHashMap<>();
        Set<String> identities=new HashSet<>();
        for (SelectedRoot root:roots) {
            if (!identities.add(root.nodeType()+":"+root.entityId()))
                throw new IllegalArgumentException("Duplicate root identity");
            grouped.computeIfAbsent(root.nodeType(),v->new LinkedHashSet<>()).add(root.entityId());
        }
        Map<String,GraphEvidenceGoalResolver.Resolution> compiled=new LinkedHashMap<>();
        grouped.forEach((type,ids)->compiled.put(type,resolver.compile(context,intent,query,
                new GraphRootScope(type,ids),requestId+":goal:"+type)));
        return new Prepared(grouped,compiled,query);
    }
    public Result executeBatch(MissionContext context,String requestId,Prepared prepared,String type) {
        Objects.requireNonNull(prepared);
        var resolution=Objects.requireNonNull(prepared.compiledGoals().get(type),"Unknown root type");
        if (!resolution.ready()) return failed(resolution.status(),resolution.reason());
        GraphQueryRequest query=resolution.query();
        Set<String> selected=Objects.requireNonNull(prepared.rootsByType().get(type));
        if (!query.rootType().equals(type) || !query.rootIds().equals(selected))
            throw new IllegalArgumentException("Compiled graph query root scope differs from verified roots");
        try {
            var response=client.executeInternalCompanyGraphQuery(context,
                    requests.toProto(context,requestId,query));
            if (response.getTruncated() || !Set.copyOf(response.getEvaluatedRootEntityIdsList()).equals(selected))
                return failed(GraphResolutionStatus.INSUFFICIENT,
                        "Graph query did not exhaust verified root set");
            var rows=GraphEvidenceProjector.project(response);
            var refs=GraphEvidenceProjector.evidence(response,requestId,selected,query);
            MissionDecisionTraceContext.event("GRAPH_QUERY_RESULT", "Verified graph query result",
                    Map.of("rootType", type, "rootCount", Integer.toString(selected.size()),
                            "resultCount", Integer.toString(rows.size()),
                            "complete", "true", "truncated", "false"));
            return new Result(GraphResolutionStatus.COMPLETE,refs,List.of(),
                    rows.isEmpty()?"Exhaustive query found no matching rows":"Resolved graph roots",
                    Map.of("graphResolutionStatus",GraphResolutionStatus.COMPLETE.name(),
                            "validatedGraphAnswerRows",rows,"evaluatedRoots",selected.size(),
                            "matchingRowCount",rows.size()));
        } catch (SecurityException | IllegalArgumentException error) {
            // Do not relabel authorization and programming defects as a
            // recoverable evidence shortage.
            throw error;
        } catch (RuntimeException error) {
            LOG.warn("Graph query execution failed requestId={} rootType={} exception={}",
                    requestId, type, error.getClass().getName(), error);
            return failed(GraphResolutionStatus.INSUFFICIENT,
                    "Graph query execution failed; inspect mission trace");
        }
    }
    private static Result failed(GraphResolutionStatus status,String reason) {
        return new Result(status,List.of(),List.of(reason),reason,
                Map.of("graphResolutionStatus",status.name(),"graphResolutionReason",reason));
    }
}
