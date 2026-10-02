package com.sparrowx.agentic.steps;

import com.sparrowx.agentic.actions.internal.ReadInternalCompanyGraphAction;
import com.sparrowx.agentic.actions.internal.ReadLearningGraphAction;
import com.sparrowx.agentic.actions.internal.SearchInternalEntitiesAction;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.agentic.tools.internal.InternalGraphRequestBuilder.GraphSpec;
import com.sparrowx.agentic.validation.DownstreamResponseValidator;
import com.sparrowx.agentic.validation.DownstreamResponseValidator.ResponseMetadata;
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
    private final ReadLearningGraphAction learningGraphAction;
    private final DownstreamResponseValidator responseValidator;

    public ResolveInternalContextStep(
            SearchInternalEntitiesAction searchAction,
            ReadInternalCompanyGraphAction companyGraphAction,
            ReadLearningGraphAction learningGraphAction,
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

        this.learningGraphAction = Objects.requireNonNull(
                learningGraphAction,
                "learningGraphAction must not be null"
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

            case READ_LEARNING_GRAPH ->
                    readLearningGraph(
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

        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put(
                "candidateCount",
                result.candidates().size()
        );

        attributes.put(
                "ambiguous",
                result.ambiguous()
        );

        if (!result.ambiguous()
                && !result.candidates().isEmpty()) {

            var resolved = result.candidates().getFirst();

            if (!resolved.getEntityId().isBlank()) {
                attributes.put(
                        "resolvedEntityId",
                        resolved.getEntityId()
                );

                attributes.put(
                        "resolvedNodeType",
                        resolved.getNodeType().name()
                );

                attributes.put(
                        "resolvedLabel",
                        resolved.getLabel()
                );

                attributes.put(
                        "resolvedScore",
                        resolved.getScore()
                );
            }
        }

        return new Result(
                Operation.SEARCH_ENTITIES,
                result.evidenceRefs(),
                "Resolved "
                        + result.candidates().size()
                        + " internal entity candidates",
                result.warnings(),
                Map.copyOf(attributes)
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
                Map.of(
                        "serializedSizeBytes",
                        result.graph().getSerializedSize()
                )
        );
    }

    /**
     * Direct capability used by the Hybrid planner after entity resolution.
     */
    public Result readLearningGraph(
            MissionContext context,
            GraphSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        ReadLearningGraphAction.Result result =
                learningGraphAction.execute(
                        context,
                        spec
                );

        responseValidator.validateInternal(
                "read-learning-graph",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        spec.requestId(),
                        spec.requestId()
                                + ":learning-graph",
                        value.evidenceRefs().size(),
                        value.graph().getSerializedSize(),
                        value.evidenceRefs()
                )
        );

        return new Result(
                Operation.READ_LEARNING_GRAPH,
                result.evidenceRefs(),
                "Loaded learning graph context",
                List.of(),
                Map.of(
                        "serializedSizeBytes",
                        result.graph().getSerializedSize()
                )
        );
    }

    public record Result(
            Operation operation,
            List<EvidenceRef> evidenceRefs,
            String summary,
            List<String> warnings,
            Map<String, Object> attributes
    ) {
        public Result {
            operation = Objects.requireNonNull(
                    operation,
                    "operation must not be null"
            );

            evidenceRefs = evidenceRefs == null
                    ? List.of()
                    : List.copyOf(evidenceRefs);

            summary = summary == null
                    ? ""
                    : summary;

            warnings = warnings == null
                    ? List.of()
                    : List.copyOf(warnings);

            attributes = attributes == null
                    ? Map.of()
                    : Map.copyOf(attributes);
        }

        public boolean hasResolvedEntity() {
            Object entityId =
                    attributes.get("resolvedEntityId");

            Object nodeType =
                    attributes.get("resolvedNodeType");

            return entityId instanceof String id
                    && !id.isBlank()
                    && nodeType instanceof String type
                    && !type.isBlank();
        }

        public String resolvedEntityId() {
            Object value =
                    attributes.get("resolvedEntityId");

            return value instanceof String text
                    ? text
                    : "";
        }

        public InternalGraphNodeType resolvedNodeType() {
            Object value =
                    attributes.get("resolvedNodeType");

            if (!(value instanceof String text)
                    || text.isBlank()) {
                return InternalGraphNodeType
                        .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
            }

            try {
                return InternalGraphNodeType.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return InternalGraphNodeType
                        .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED;
            }
        }
    }

    public enum Operation {
        SEARCH_ENTITIES,
        READ_COMPANY_GRAPH,
        READ_LEARNING_GRAPH
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

            if ((operation == Operation.READ_COMPANY_GRAPH
                    || operation == Operation.READ_LEARNING_GRAPH)
                    && graphSpec == null) {
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