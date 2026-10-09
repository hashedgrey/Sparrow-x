package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.planning.InternalRootSelection;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles a mission into a typed root-selection plan, not a single scalar root. */
@Component
public final class InternalSearchSpecResolver {

    private static final List<String> NODE_TYPES = Arrays.stream(InternalGraphNodeType.values())
            .filter(t -> t != InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    && t != InternalGraphNodeType.UNRECOGNIZED)
            .map(Enum::name).toList();

    private final LlmDecisionEngine llm;
    private final int maxQueries;
    private final int searchLimit;

    public InternalSearchSpecResolver(
            LlmDecisionEngine llm,
            @Value("${sparrowx.graph.max-root-queries:32}") int maxQueries,
            @Value("${sparrowx.graph.root-search-limit:100}") int searchLimit
    ) {
        this.llm = Objects.requireNonNull(llm, "llm");
        if (maxQueries < 1 || searchLimit < 2) throw new IllegalArgumentException("Invalid root search limits");
        this.maxQueries = maxQueries;
        this.searchLimit = searchLimit;
    }

    public Resolution resolve(Request request) {
        Objects.requireNonNull(request, "request");
        var decision = llm.evaluate(new LlmDecisionEngine.Request(
                request.missionId(),
                "resolve-internal-root-set",
                request.requestId() + ":root-selection:v4",
                TASK,
                Map.of("mission", request.query(),
                        "relationshipTraversalRequired", request.relationshipTraversalRequired()),
                Set.of("selectionMode", "roots", "perRootGraphQuestion"),
                outputSchema(),
                1000, 0.0,
                Map.of("component", "internal-search-spec-resolver", "purpose", "typed-root-selection")
        ));
        Map<String, Object> output = decision.output();
        if (output == null) throw new IllegalStateException("Root-selection LLM returned no output");
        Mode mode = Mode.valueOf(requireText(output.get("selectionMode"), "selectionMode"));
        Object rootsValue = output.get("roots");
        if (!(rootsValue instanceof List<?> rootValues))
            throw new IllegalStateException("roots must be an array");
        if (rootValues.isEmpty() || rootValues.size() > maxQueries)
            throw new IllegalStateException("Root query count exceeds permitted range: " + rootValues.size());

        List<RootQuery> roots = rootValues.stream().map(this::parseRoot).toList();
        Plan plan = new Plan(mode, roots,
                requireText(output.get("perRootGraphQuestion"), "perRootGraphQuestion"));
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("rootSelectionMode", mode.name());
        diagnostics.put("rootQueryCount", roots.size());
        diagnostics.put("parameterLlmRoute", decision.routeKey());
        diagnostics.put("parameterLlmProvider", decision.response().provider());
        diagnostics.put("parameterLlmModel", decision.response().model());
        return new Resolution(plan, Map.copyOf(diagnostics));
    }

    private RootQuery parseRoot(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Root entry is not an object");
        String rawType = requireText(map.get("rootEntityType"), "rootEntityType");
        InternalGraphNodeType type = InternalGraphNodeType.valueOf(rawType);
        if (!NODE_TYPES.contains(type.name())) throw new IllegalStateException("Invalid root type " + rawType);
        return new RootQuery(
                requireText(map.get("rootEntityQuery"), "rootEntityQuery"),
                type,
                MatchOperator.valueOf(requireText(map.get("matchOperator"), "matchOperator"))
        );
    }

    public SearchSpec searchSpec(String requestId, RootQuery root) {
        Objects.requireNonNull(root, "root");
        return new SearchSpec(
                requireText(requestId, "requestId"),
                root.query(),
                List.of(root.type()),
                "",
                InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED,
                0,
                searchLimit,
                root.matchOperator() == MatchOperator.RELEVANT,
                Map.of()
        );
    }

    private static Map<String, Object> outputSchema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> root = Map.of(
                "type", "object",
                "properties", Map.of(
                        "rootEntityQuery", string,
                        "rootEntityType", Map.of("type", "string", "enum", NODE_TYPES),
                        "matchOperator", Map.of("type", "string", "enum", List.of("EXACT", "CONTAINS", "RELEVANT"))
                ),
                "required", List.of("rootEntityQuery", "rootEntityType", "matchOperator"),
                "additionalProperties", false
        );
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "selectionMode", Map.of("type", "string", "enum", List.of("UNIQUE", "ALL_MATCHES", "EXPLICIT_SET")),
                        "roots", Map.of("type", "array", "items", root),
                        "perRootGraphQuestion", string
                ),
                "required", List.of("selectionMode", "roots", "perRootGraphQuestion"),
                "additionalProperties", false
        );
    }

    private static String requireText(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank())
            throw new IllegalStateException(field + " must be non-blank");
        return s.trim();
    }

    public record Request(String missionId, String requestId,
                          String query, boolean relationshipTraversalRequired) {
        public Request {
            missionId = requireText(missionId, "missionId");
            requestId = requireText(requestId, "requestId");
            query = requireText(query, "query");
        }
    }

    public record Resolution(Plan plan, Map<String, Object> debug) {
        public Resolution {
            plan = Objects.requireNonNull(plan, "plan");
            debug = Map.copyOf(Objects.requireNonNull(debug, "debug"));
        }
    }

    private static final String TASK = """
            Compile the user request into a typed plan for finding graph root entities.
            You are extracting which entities are GIVEN by the user, not answering the mission.
            Use selectionMode:
            - UNIQUE when one given root must be resolved to one entity.
            - ALL_MATCHES when the user requests every entity satisfying a search expression.
            - EXPLICIT_SET when the user gives multiple named/identified root entities.
            In EXPLICIT_SET, return one root entry per explicitly specified root.
            The search expression must contain only the given root's searchable name,
            identifier, or fragment, never a relationship to be traversed.
            Choose matchOperator EXACT for an explicit label/slug/ID,
            CONTAINS for a substring-of-name request, and RELEVANT only when
            the root is described semantically without an exact identifier.
            Use rootEntityType only from the supplied enum.
            For perRootGraphQuestion, describe every requested output and relationship
            predicate PER selected root (including intermediate outputs).
            Omit only root-search constraints already enforced by the search plan.
            E.g. 'names contain X' is applied during search, not a graph equality filter.
            For comparing two names, describe the same requested fields for each
            root independently; the executor preserves both roots and their labels.
            Do not drop requested graph constraints or invent facts. Do not answer the query.
            """;
}
