package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.internal.grpc.InternalGraphNodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root selection is never a graph answer. Exact user-supplied typed roots
 * are parsed deterministically; free-form root extraction escalates to LLM.
 * No guessed root is accepted as a completed selection.
 */
@Component
public final class InternalSearchSpecResolver {
    private static final Logger LOG = LoggerFactory.getLogger(InternalSearchSpecResolver.class);
    private static final String TYPE_PREFIX = "INTERNAL_GRAPH_NODE_TYPE_";

    private static final List<String> NODE_TYPES = Arrays.stream(InternalGraphNodeType.values())
            .filter(t -> t != InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    && t != InternalGraphNodeType.UNRECOGNIZED)
            .map(Enum::name).toList();

    private final SemanticDecisionService semantic;
    private final int maxQueries;
    private final int searchLimit;

    public InternalSearchSpecResolver(
            SemanticDecisionService semantic,
            @Value("${sparrowx.graph.max-root-queries:32}") int maxQueries,
            @Value("${sparrowx.graph.root-search-limit:100}") int searchLimit
    ) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        if (maxQueries < 1 || searchLimit < 2) throw new IllegalArgumentException("Invalid root search limits");
        this.maxQueries = maxQueries;
        this.searchLimit = searchLimit;
    }

    public Resolution resolve(Request request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        Optional<RootQuery> explicit = parseExplicitTypedRoot(request.query());
        if (explicit.isPresent()) {
            Plan plan = new Plan(Mode.UNIQUE, List.of(explicit.get()), request.query());
            LOG.info("internal.root.deterministic elapsedMs={}", elapsed(started));
            return new Resolution(plan, Map.of(
                    "rootSelectionMode", "UNIQUE", "rootQueryCount", 1,
                    "parameterRoute", "deterministic", "parameterElapsedMs", elapsed(started)));
        }

        // Multi-root selection, implied types, and open-ended entity names are not
        // represented by Jev's noul/choice/score response schema; escalate directly.
        LlmDecisionEngine.Result decision = semantic.escalate(new LlmDecisionEngine.Request(
                request.missionId(), "resolve-internal-root-set",
                request.requestId() + ":root-selection:v5",
                TASK,
                Map.of("mission", request.query(),
                        "relationshipTraversalRequired", request.relationshipTraversalRequired()),
                Set.of("selectionMode", "roots", "perRootGraphQuestion"),
                outputSchema(), 1000, 0.0,
                Map.of("component", "internal-search-spec-resolver", "purpose", "typed-root-selection")
        ));

        Map<String, Object> output = decision.output();
        if (output == null) throw new IllegalStateException("Root-selection LLM returned no output");
        Mode mode = Mode.valueOf(requireText(output.get("selectionMode"), "selectionMode"));
        if (!(output.get("roots") instanceof List<?> values))
            throw new IllegalStateException("roots must be an array");
        if (values.isEmpty() || values.size() > maxQueries)
            throw new IllegalStateException("Root query count exceeds permitted range: " + values.size());

        List<RootQuery> roots = values.stream().map(this::parseRoot).toList();
        if (mode == Mode.UNIQUE && roots.size() != 1)
            throw new IllegalStateException("UNIQUE root selection requires exactly one root");
        if (mode == Mode.EXPLICIT_SET && roots.size() < 2)
            throw new IllegalStateException("EXPLICIT_SET requires multiple roots");
        // A root-selection LLM is not authorized to narrow the user's graph question.
        // With one verified root the original question is safe to retain: the
        // GraphQueryRequest rootIds still anchor execution to that resolved entity.
        // In particular, do not lose role/status/equality qualifiers while
        // paraphrasing a request into perRootGraphQuestion.
        String normalizedGraphQuestion =
                requireText(output.get("perRootGraphQuestion"), "perRootGraphQuestion");
        String graphQuestion = mode == Mode.UNIQUE
                ? request.query()
                : normalizedGraphQuestion;
        Plan plan = new Plan(mode, roots, graphQuestion);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("rootSelectionMode", mode.name());
        diagnostics.put("rootQueryCount", roots.size());
        diagnostics.put("parameterRoute", "llm");
        diagnostics.put("parameterLlmRoute", decision.routeKey());
        diagnostics.put("parameterLlmProvider", decision.response().provider());
        diagnostics.put("parameterLlmModel", decision.response().model());
        diagnostics.put("parameterElapsedMs", elapsed(started));
        return new Resolution(plan, Map.copyOf(diagnostics));
    }

    /**
     * Accept only a literal single name followed by an actual protobuf node-type
     * name, e.g. "the Agentic Service module". Otherwise defer to semantic
     * extraction rather than guessing where the name begins or ends.
     */
    private static Optional<RootQuery> parseExplicitTypedRoot(String query) {
        String text = query.strip();
        if (text.contains(";") || text.contains("\n")) return Optional.empty();
        // A substring match must never turn a comparison or an explicit set
        // into a UNIQUE root. Let structured extraction preserve cardinality.
        if (Pattern.compile("(?i)\\b(and|or|versus|vs|compare|between|each|every|all|both)\\b")
                .matcher(text).find()) return Optional.empty();
        for (String enumName : NODE_TYPES) {
            if (!enumName.startsWith(TYPE_PREFIX)) continue;
            String suffix = enumName.substring(TYPE_PREFIX.length())
                    .toLowerCase(Locale.ROOT).replace('_', ' ');
            String end = "\\s+" + Pattern.quote(suffix) + "\\s*\\??$";
            // Explicitly delimited root label; only the suffix is type-derived.
            Pattern quoted = Pattern.compile("(?:\\\"([^\\\"]+)\\\"|'([^']+)')" + end,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher qm = quoted.matcher(text);
            if (qm.find()) {
                String label = qm.group(1) != null ? qm.group(1) : qm.group(2);
                if (!label.isBlank()) return Optional.of(new RootQuery(label.strip(),
                        InternalGraphNodeType.valueOf(enumName), MatchOperator.EXACT));
            }

            // Conservative simple singular grammar. Extra postmodifiers do not match.
            Pattern named = Pattern.compile("\\b(?:the|named|called)\\s+"
                            + "([\\p{L}\\p{N}][\\p{L}\\p{N} ._/-]{0,120}?)" + end,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher nm = named.matcher(text);
            if (nm.find()) {
                String label = nm.group(1).strip();
                if (label.isBlank() || label.matches("(?i)(all|every|any|each|some|a|an)\\b.*"))
                    continue;
                // Disallow conjunctions that likely describe multiple roots.
                if (label.matches("(?i).*\\b(and|or|versus|vs)\\b.*")) continue;
                return Optional.of(new RootQuery(label,
                        InternalGraphNodeType.valueOf(enumName), MatchOperator.EXACT));
            }
        }
        return Optional.empty();
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
                root.query(), List.of(root.type()), "",
                InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED,
                0, searchLimit, root.matchOperator() == MatchOperator.RELEVANT,
                Map.of());
    }

    private static Map<String, Object> outputSchema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> root = Map.of(
                "type", "object", "properties", Map.of(
                        "rootEntityQuery", string,
                        "rootEntityType", Map.of("type", "string", "enum", NODE_TYPES),
                        "matchOperator", Map.of("type", "string", "enum", List.of("EXACT", "CONTAINS", "RELEVANT"))),
                "required", List.of("rootEntityQuery", "rootEntityType", "matchOperator"),
                "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of(
                        "selectionMode", Map.of("type", "string", "enum", List.of("UNIQUE", "ALL_MATCHES", "EXPLICIT_SET")),
                        "roots", Map.of("type", "array", "items", root),
                        "perRootGraphQuestion", string),
                "required", List.of("selectionMode", "roots", "perRootGraphQuestion"),
                "additionalProperties", false);
    }

    private static String requireText(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank())
            throw new IllegalStateException(field + " must be non-blank");
        return s.trim();
    }

    private static long elapsed(long start) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
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
