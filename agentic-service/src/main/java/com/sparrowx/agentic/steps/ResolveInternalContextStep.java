package com.sparrowx.agentic.steps;

import com.sparrowx.agentic.actions.internal.ReadInternalCompanyGraphAction;
import com.sparrowx.agentic.actions.internal.SearchInternalEntitiesAction;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.InternalRootSelection.Candidate;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.GraphSpec;
import com.sparrowx.agentic.validation.DownstreamResponseValidator;
import com.sparrowx.agentic.validation.DownstreamResponseValidator.ResponseMetadata;
import com.sparrowx.internal.grpc.InternalGraph;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated Internal Service execution boundary.
 *
 * Hybrid planning belongs in MissionAgent/Embabel.
 * This class only executes already-selected internal capabilities.
 */
@Component
public final class ResolveInternalContextStep {

    private static final int DEFAULT_GRAPH_DEPTH = 1;
    private static final int DEFAULT_GRAPH_LIMIT = 50;

    private final SearchInternalEntitiesAction searchAction;
    private final ReadInternalCompanyGraphAction companyGraphAction;
    private final DownstreamResponseValidator responseValidator;

    public ResolveInternalContextStep(
            SearchInternalEntitiesAction searchAction,
            ReadInternalCompanyGraphAction companyGraphAction,
            DownstreamResponseValidator responseValidator
    ) {
        this.searchAction = Objects.requireNonNull(
                searchAction,
                "searchAction must not be null"
        );

        this.companyGraphAction = Objects.requireNonNull(
                companyGraphAction,
                "companyGraphAction must not be null"
        );


        this.responseValidator = Objects.requireNonNull(
                responseValidator,
                "responseValidator must not be null"
        );
    }

    /**
     * Temporary compatibility entrypoint.
     *
     * Remove this when DefaultMissionEvidenceService and MissionPlan
     * are removed.
     */
    public Result execute(
            MissionContext context,
            Request request
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(request, "request must not be null");

        return switch (request.operation()) {
            case SEARCH_ENTITIES ->
                    search(
                            context,
                            requireSearchSpec(request.searchSpec())
                    );

            case READ_COMPANY_GRAPH ->
                    readCompanyGraph(
                            context,
                            toGraphSpec(request.graphSpec())
                    );

        };
    }

    /**
     * Direct capability used by the Hybrid planner.
     */
    public Result search(
            MissionContext context,
            SearchSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        SearchInternalEntitiesAction.Result result =
                searchAction.execute(context, spec);

        responseValidator.validateInternal(
                "search-internal-entities",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        spec.requestId(),
                        spec.requestId() + ":internal-entities",
                        value.candidates().size(),
                        value.candidates().stream()
                                .mapToLong(candidate ->
                                        candidate.getSerializedSize())
                                .sum(),
                        value.evidenceRefs()
                )
        );

        // Return typed candidates; never collapse a result set into its first hit.
        List<Candidate> candidates = result.candidates().stream()
                .map(Candidate::fromProto)
                .toList();

        // Current IntSvc search RPC exposes no pagination token. Treat hitting
        // the requested limit as incomplete rather than claiming exhaustive results.
        boolean exhaustive = candidates.size() < spec.limit();
        List<String> warnings = new java.util.ArrayList<>(result.warnings());
        if (!exhaustive) warnings.add(
                "Root search may be truncated; increase the limit or implement pagination");

        return new Result(
                Operation.SEARCH_ENTITIES,
                result.evidenceRefs(),
                "Found " + candidates.size() + " candidate roots",
                List.copyOf(warnings),
                Map.of("candidateCount", candidates.size(),
                        "backendAmbiguous", result.ambiguous(),
                        "exhaustive", exhaustive),
                candidates,
                result.ambiguous(),
                exhaustive
        );
    }

    /**
     * Direct capability used by the Hybrid planner after entity resolution.
     */
    public Result readCompanyGraph(
            MissionContext context,
            GraphSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        ReadInternalCompanyGraphAction.Result result =
                companyGraphAction.execute(
                        context,
                        spec
                );

        responseValidator.validateInternal(
                "read-internal-company-graph",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        spec.requestId(),
                        spec.requestId()
                                + ":company-graph",
                        value.evidenceRefs().size(),
                        value.graph().getSerializedSize(),
                        value.evidenceRefs()
                )
        );

        return new Result(
                Operation.READ_COMPANY_GRAPH,
                result.evidenceRefs(),
                "Loaded internal company graph context",
                List.of(),
                Map.of("serializedSizeBytes", result.graph().getSerializedSize()),
                List.of(),
                false,
                true
        );
    }

    public ReadInternalCompanyGraphAction.ExpansionResult expandCompanyGraph(
            MissionContext context,
            InternalGraphRequestBuilder.ExpansionSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        ReadInternalCompanyGraphAction.ExpansionResult result =
                companyGraphAction.expand(context, spec);

        responseValidator.validateInternal(
                "expand-internal-company-graph",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        spec.requestId(),
                        spec.requestId() + ":company-graph-expansion",
                        value.evidenceRefs().size(),
                        value.graph().getSerializedSize(),
                        value.evidenceRefs()
                )
        );

        return result;
    }

    public ReadInternalCompanyGraphAction.Result mapMergedCompanyGraph(
            MissionContext context,
            String requestId,
            InternalGraph graph
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(graph, "graph must not be null");

        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "requestId must not be blank"
            );
        }

        ReadInternalCompanyGraphAction.Result result =
                companyGraphAction.mapGraph(graph);

        responseValidator.validateInternal(
                "merged-internal-company-graph",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        requestId,
                        requestId + ":merged-company-graph",
                        value.evidenceRefs().size(),
                        value.graph().getSerializedSize(),
                        value.evidenceRefs()
                )
        );

        return result;
    }

    public ReadInternalCompanyGraphAction.Result readCompanyGraphRoot(
            MissionContext context,
            String requestId,
            String rootEntityId,
            InternalGraphNodeType rootNodeType
    ) {
        Objects.requireNonNull(context, "context must not be null");

        GraphSpec spec = new GraphSpec(
                requestId,
                rootEntityId,
                rootNodeType,
                0,
                1
        );

        ReadInternalCompanyGraphAction.Result result =
                companyGraphAction.execute(context, spec);

        responseValidator.validateInternal(
                "read-company-graph-root",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        requestId,
                        requestId + ":root",
                        value.evidenceRefs().size(),
                        value.graph().getSerializedSize(),
                        value.evidenceRefs()
                )
        );

        if (result.graph().getNodesCount() != 1
                || result.graph().getRelationshipsCount() != 0) {
            throw new IllegalStateException(
                    "Root-only graph read returned additional graph content"
            );
        }

        var root = result.graph().getNodes(0);

        if (!root.getEntityId().equals(rootEntityId)
                || root.getNodeType() != rootNodeType) {
            throw new IllegalStateException(
                    "Root-only graph read returned a different entity"
            );
        }

        return result;
    }

    public record Result(
            Operation operation,
            List<EvidenceRef> evidenceRefs,
            String summary,
            List<String> warnings,
            Map<String, Object> attributes,
            List<Candidate> candidates,
            boolean backendAmbiguous,
            boolean exhaustive
    ) {
        public Result {
            operation = Objects.requireNonNull(operation, "operation");
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            summary = Objects.requireNonNull(summary, "summary");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (operation != Operation.SEARCH_ENTITIES && !candidates.isEmpty())
                throw new IllegalArgumentException("Only entity-search results may carry root candidates");
        }
    }

    public enum Operation {
        SEARCH_ENTITIES,
        READ_COMPANY_GRAPH
    }

    /*
     * ------------------------------------------------------------------
     * LEGACY PLANNER ADAPTER
     *
     * Delete everything below when MissionPlan /
     * DefaultMissionEvidenceService are removed.
     * ------------------------------------------------------------------
     */

    @Deprecated
    public record Request(
            Operation operation,
            SearchSpec searchSpec,
            GraphInput graphSpec
    ) {
        public Request {
            operation = Objects.requireNonNull(
                    operation,
                    "operation must not be null"
            );

            if (operation == Operation.SEARCH_ENTITIES
                    && searchSpec == null) {
                throw new IllegalArgumentException(
                        "searchSpec is required for SEARCH_ENTITIES"
                );
            }

            if (operation == Operation.READ_COMPANY_GRAPH && graphSpec == null) {
                throw new IllegalArgumentException(
                        "graphSpec is required for graph operations"
                );
            }
        }
    }

    @Deprecated
    public record GraphInput(
            String requestId,
            String rootEntityId,
            InternalGraphNodeType rootNodeType,
            Integer depth,
            Integer limit
    ) {
    }

    private static SearchSpec requireSearchSpec(
            SearchSpec spec
    ) {
        return Objects.requireNonNull(
                spec,
                "searchSpec is required for SEARCH_ENTITIES"
        );
    }

    private static GraphSpec toGraphSpec(
            GraphInput input
    ) {
        Objects.requireNonNull(
                input,
                "graphSpec is required for graph operations"
        );

        return new GraphSpec(
                input.requestId(),
                input.rootEntityId(),
                input.rootNodeType(),
                input.depth() == null
                        ? DEFAULT_GRAPH_DEPTH
                        : input.depth(),
                input.limit() == null
                        ? DEFAULT_GRAPH_LIMIT
                        : input.limit()
        );
    }
}
