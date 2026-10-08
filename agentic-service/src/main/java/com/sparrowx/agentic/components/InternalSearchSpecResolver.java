package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


@Component
public final class InternalSearchSpecResolver {

    private static final int DEFAULT_LIMIT = 20;

    private static final List<String> SUPPORTED_NODE_TYPES = List.of(
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_MODULE.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_TEAM.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_ENGINEER.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_REPOSITORY.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_DOCUMENT.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_RUNBOOK.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_ONBOARDING_PATH.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_ONBOARDING_TASK.name(),
            InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_PERMISSION.name()
    );

    private final LlmDecisionEngine llm;

    public InternalSearchSpecResolver(
            LlmDecisionEngine llm
    ) {
        this.llm = Objects.requireNonNull(llm, "llm must not be null");
    }

    public Resolution resolve(
            Request request
    ) {
        Objects.requireNonNull(request, "request must not be null");

        LlmDecisionEngine.Result result =
                llm.evaluate(
                        new LlmDecisionEngine.Request(
                                request.missionId(),
                                "resolve-internal-search-parameters",
                                request.requestId()
                                        + ":internal-search-parameters:v3",
                                """
                                Identify the single internal entity explicitly named or described in the mission
                                that must be resolved before any relationship traversal can occur.
                                
                                Return:
                                - rootEntityQuery: only the entity's searchable name or identifier.
                                - rootEntityType: the type of that entity.
                                
                                Do not return:
                                - the relationship being requested,
                                - the entity expected as the answer,
                                - words such as ownership, owner, belongs to, member of, dependency,
                                - a related entity that should instead be discovered by graph traversal.
                                
                                For a mission such as "Who owns the Agentic Service?",
                                the root entity is "Agentic Service" and its type is MODULE.
                                
                                Do not answer the mission.
                                """,
                                extractionState(request),
                                Set.of("rootEntityQuery", "rootEntityType"),
                                outputSchema(),
                                200,
                                0.0d,
                                Map.of("component", "internal-search-spec-resolver", "purpose",
                                        "root-entity-parameter-extraction"
                                )
                        )
                );

        Map<String, Object> output = result.output();
        String searchQuery = requiredString(output.get("rootEntityQuery"), "rootEntityQuery");
        InternalGraphNodeType nodeType = parseNodeType(output.get("rootEntityType"));
        SearchSpec spec = searchSpec(request.requestId(), searchQuery, nodeType);
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("parameterResolution", "llm");
        debug.put("resolvedSearchQuery", searchQuery);
        debug.put("resolvedNodeType", nodeType.name());
        debug.put("relationshipTraversalRequired", request.relationshipTraversalRequired());
        debug.put("parameterLlmRoute", result.routeKey());
        debug.put("parameterLlmProvider", result.response().provider());
        debug.put("parameterLlmModel", result.response().model());
        return new Resolution(spec, Strategy.LLM, Map.copyOf(debug));
    }

    private static Map<String, Object> extractionState(Request request) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mission", request.query());
        state.put("relationshipTraversalRequired", request.relationshipTraversalRequired());
        state.put("task", "resolve-root-entity-only");
        return Map.copyOf(state);
    }

    private static SearchSpec searchSpec(
            String requestId,
            String query,
            InternalGraphNodeType nodeType
    ) {
        return new SearchSpec(
                requestId,
                query,
                List.of(nodeType),
                "",
                InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED, 0, DEFAULT_LIMIT,

                /*
                 * The first search establishes the graph root.
                 *
                 * Prefer a precise entity match here. Fuzzy/disambiguation
                 * behavior should be an explicit later path rather than
                 * silently broadening every root search.
                 */
                false,
                Map.of()
        );
    }

    private static InternalGraphNodeType parseNodeType(
            Object value
    ) {
        if (!(value instanceof String text) || text.isBlank()) {

            throw new IllegalStateException("LLM parameter extraction did not produce nodeType");
        }

        final InternalGraphNodeType type;

        try {
            type = InternalGraphNodeType.valueOf(text.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LLM parameter extraction returned unsupported node type: "
                    + text,
                    exception
            );
        }

        if (type
                == InternalGraphNodeType
                .INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                || type == InternalGraphNodeType.UNRECOGNIZED
                || !SUPPORTED_NODE_TYPES.contains(type.name())) {

            throw new IllegalStateException(
                    "LLM parameter extraction returned unsupported node type: "
                            + text
            );
        }

        return type;
    }

    private static Map<String, Object> outputSchema() {
        return Map.of(
                "type",
                "object",

                "properties",
                Map.of(
                        "rootEntityQuery",
                        Map.of(
                                "type",
                                "string"
                        ),

                        "rootEntityType",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                SUPPORTED_NODE_TYPES
                        )
                ),

                "required",
                List.of(
                        "rootEntityQuery",
                        "rootEntityType"
                ),

                "additionalProperties",
                false
        );
    }
    public record Request(
            String missionId,
            String requestId,
            String query,
            boolean relationshipTraversalRequired
    ) {

        public Request {
            missionId =
                    requireText(
                            missionId,
                            "missionId"
                    );

            requestId =
                    requireText(
                            requestId,
                            "requestId"
                    );

            query =
                    requireText(
                            query,
                            "query"
                    );
        }
    }

    public record Resolution(
            SearchSpec spec,
            Strategy strategy,
            Map<String, Object> debug
    ) {

        public Resolution {
            spec =
                    Objects.requireNonNull(
                            spec,
                            "spec must not be null"
                    );

            strategy =
                    Objects.requireNonNull(
                            strategy,
                            "strategy must not be null"
                    );

            debug =
                    debug == null
                            ? Map.of()
                            : Map.copyOf(debug);
        }
    }

    public enum Strategy {
        LLM
    }

    private static String requiredString(
            Object value,
            String field
    ) {
        if (!(value instanceof String text)
                || text.isBlank()) {

            throw new IllegalStateException(
                    "LLM parameter extraction did not produce "
                            + field
            );
        }

        return text.trim();
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

        return value.trim();
    }
}