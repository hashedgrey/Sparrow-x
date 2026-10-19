package com.sparrowx.agentic.components;

import com.sparrowx.agentic.decision.LlmDecisionEngine;
import com.sparrowx.agentic.decision.SemanticDecisionService;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.InternalRootSelection.*;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Compiles natural-language root requirements into ontology-bound INTSVC searches.
 * JEV alone chooses bounded selection semantics and ontology types. DeepSeek may
 * generate only literal search arguments; it never chooses modes or capabilities.
 * There are no question-specific names, instruction-verb lexicons or name regexes.
 */
@Component
public final class InternalSearchSpecResolver {
    private static final Logger LOG = LoggerFactory.getLogger(InternalSearchSpecResolver.class);
    private static final double JEV_CONFIDENCE = 0.75d;
    private static final int MAX_GENERATION_CORRECTIONS = 2;

    private final SemanticDecisionService semantic;
    private final GraphOntologyProvider ontologyProvider;
    private final int maxQueries;
    private final int searchLimit;

    public InternalSearchSpecResolver(
            SemanticDecisionService semantic,
            GraphOntologyProvider ontologyProvider,
            @Value("${sparrowx.graph.max-root-queries:32}") int maxQueries,
            @Value("${sparrowx.graph.root-search-limit:100}") int searchLimit) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.ontologyProvider = Objects.requireNonNull(ontologyProvider, "ontologyProvider");
        if (maxQueries < 1 || searchLimit < 2 || searchLimit > 100)
            throw new IllegalArgumentException("Invalid root search budgets");
        this.maxQueries = maxQueries;
        this.searchLimit = searchLimit;
    }

    public Resolution resolve(Request request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        var ontology = ontologyProvider.describe(request.context());
        List<String> types = ontology.fields().keySet().stream().sorted().toList();
        if (types.isEmpty()) throw new IllegalStateException("Tenant graph has no node types");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("originalMission", request.query());
        state.put("availableNodeTypes", types);
        state.put("schemaVersion", ontology.schemaVersion());
        state.put("relationshipTraversalRequired", request.relationshipTraversalRequired());

        // Cardinality is a JEV decision independent of any specific root
        // literal. Asking for an individual name in this question makes
        // EXPLICIT_SET artificially ambiguous.
        ChoiceQuestion modeQuestion = new ChoiceQuestion(
                "Choose only the independently selected STARTING root set. "
                + "Ignore related entities and requested output fields. "
                + "A named list is EXPLICIT_SET, a partial-name/substring lookup "
                + "is ALL_MATCHES, an unnamed exhaustive ontology type is TYPE_SCAN.",
                Map.of(
                        "UNIQUE", "One independently named starting entity",
                        "ALL_MATCHES", "All entities satisfying one text search expression",
                        "EXPLICIT_SET", "Two or more individually named starting entities",
                        "TYPE_SCAN", "All entities of one ontology-defined starting type",
                        "MULTI_TYPE_SCAN", "Independent exhaustive scans over multiple starting types"));
        Mode mode = Mode.valueOf(choose(request, state, "selectionMode", modeQuestion));

        List<RootQuery> roots;
        boolean generated = false;
        int generationCalls = 0;
        if (mode == Mode.TYPE_SCAN) {
            roots = List.of(new RootQuery("*", selectType(request, state, types, "scan"),
                    MatchOperator.EXACT));
        } else if (mode == Mode.MULTI_TYPE_SCAN) {
            List<String> selected = selectScanTypes(request, state, types);
            roots = selected.stream().map(type ->
                    new RootQuery("*", type, MatchOperator.EXACT)).toList();
        } else {
            // The match operator and types remain JEV decisions. Literal spans
            // are unbounded text and therefore belong to the LLM generator.
            MatchOperator operator = MatchOperator.valueOf(choose(request, state,
                    "matchOperator", new ChoiceQuestion(
                            "Which lexical operator applies to starting root names? "
                            + "Use CONTAINS only for an explicitly partial/substring "
                            + "search, EXACT for full named entities, and RELEVANT "
                            + "for open-ended approximate descriptions.",
                            Map.of("EXACT", "Full proper name or identifier",
                                    "CONTAINS", "Specified substring of a name/label",
                                    "RELEVANT", "Approximate or semantic similarity"))));
            String commonType = mode == Mode.EXPLICIT_SET ? null
                    : selectType(request, state, types, "starting-root");
            RootLiteralResult literals = generateLiterals(request, state, mode, operator);
            List<String> names = literals.queries();
            generationCalls = literals.attempts();
            generated = true;
            List<RootQuery> selected = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String type = commonType == null
                        ? selectType(request, state, types, "named-root-" + i, name)
                        : commonType;
                selected.add(new RootQuery(name, type, operator));
            }
            roots = List.copyOf(selected);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("selectionMode", mode.name());
        output.put("roots", roots.stream().map(root -> Map.<String, Object>of(
                "rootEntityQuery", root.query(), "rootEntityType", root.type(),
                "matchOperator", root.matchOperator().name())).toList());
        output.put("perRootGraphQuestion", request.query());
        Plan plan = parsePlan(output, types, request.query()).plan();

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("parameterRoute", generated ? "jev-control-llm-literal-generation" : "jev-only-scan");
        debug.put("rootSelectionMode", plan.mode().name());
        debug.put("rootQueryCount", plan.queries().size());
        debug.put("rootPlanGenerationAttempts", generationCalls);
        debug.put("ontologyVersion", ontology.schemaVersion());
        debug.put("parameterElapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        LOG.info("internal.root-selection.valid missionId={} mode={} roots={} generation={} elapsedMs={}",
                request.missionId(), plan.mode(), plan.queries().size(), generated,
                debug.get("parameterElapsedMs"));
        return new Resolution(plan, Map.copyOf(debug));
    }

    private String choose(Request request, Map<String, Object> state,
                          String field, ChoiceQuestion question) {
        String operation = request.requestId() + ":" + field;
        if (state.get("rootSelectionStep") instanceof String step)
            operation += ":" + step;
        Optional<com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse> answer =
                semantic.tryBounded(Map.copyOf(state), Map.of(field, question),
                        JEV_CONFIDENCE, operation);
        if (answer.isEmpty()) {
            // A second smaller bounded question can resolve a crowded state;
            // unlike LLM classification, it cannot silently override JEV.
            Map<String, Object> reduced = Map.of(
                    "mission", request.query(), "allowedOntologyTypes", state.get("availableNodeTypes"));
            answer = semantic.tryBounded(reduced, Map.of(field, question),
                    JEV_CONFIDENCE, operation + ":retry");
        }
        if (answer.isEmpty() || !(answer.get().answers().get(field) instanceof ChoiceAnswer chosen))
            throw new IllegalStateException("JEV could not certify bounded root decision: " + field);
        return chosen.choice();
    }

    private String selectType(Request request, Map<String, Object> state,
                              List<String> types, String operation) {
        return selectType(request, state, types, operation, null);
    }

    private String selectType(Request request, Map<String, Object> state,
                              List<String> types, String operation, String literal) {
        Map<String, Object> scoped = new LinkedHashMap<>(state);
        if (literal != null) scoped.put("namedStartingRoot", literal);
        scoped.put("rootSelectionStep", operation);
        Map<String, String> options = new LinkedHashMap<>();
        for (String type : types) options.put(type, "Tenant-ontology node type " + type);
        String result = choose(request, scoped, "rootType",
                new ChoiceQuestion("Select the ONTOLOGY TYPE of the STARTING root "
                        + (literal == null ? "requested in the mission" : "'" + literal + "'")
                        + ". Never choose an intermediate entity solely because "
                        + "it appears in the requested OUTPUT.", options));
        if (!types.contains(result)) throw new IllegalStateException("JEV chose unregistered root type");
        return result;
    }

    private List<String> selectScanTypes(Request request, Map<String, Object> state, List<String> types) {
        if (types.size() < 2 || types.size() > 32)
            throw new IllegalStateException("Invalid ontology type count for multi-scan");
        List<String> selected = new ArrayList<>();
        for (String type : types) {
            var answer = semantic.tryBounded(Map.copyOf(state), Map.of(
                    "include", new NoulQuestion(
                            "Must ontology type '" + type + "' be independently exhaustively SCANNED "
                            + "to start this mission (not merely traversed to return its fields)?",
                            "Independently selected complete starting root type",
                            "Only an output, comparison field, or related type")),
                    JEV_CONFIDENCE, request.requestId() + ":scan-type:" + type);
            if (answer.isEmpty()) throw new IllegalStateException(
                    "JEV cannot certify multi-type scan membership for " + type);
            var vote = answer.get().answers().get("include");
            if (vote instanceof NoulAnswer n && n.probability() >= 0.5d)
                selected.add(type);
        }
        if (selected.size() < 2) throw new IllegalStateException(
                "MULTI_TYPE_SCAN requires at least two independently authorized starting types");
        return List.copyOf(selected);
    }

    /** Generate only quoted/explicit literal text, never a mode or tool decision. */
    private RootLiteralResult generateLiterals(Request request, Map<String, Object> state,
                                          Mode mode, MatchOperator operator) {
        Map<String, Object> generationState = new LinkedHashMap<>(state);
        generationState.put("jevSelectionMode", mode.name());
        generationState.put("jevMatchOperator", operator.name());
        int expectedMin = mode == Mode.EXPLICIT_SET ? 2 : 1;
        int expectedMax = mode == Mode.EXPLICIT_SET ? maxQueries : 1;
        String feedback = "";
        for (int attempt = 0; attempt <= MAX_GENERATION_CORRECTIONS; attempt++) {
            if (!feedback.isEmpty()) generationState.put("validationFeedback", feedback);
            var response = semantic.escalate(new LlmDecisionEngine.Request(
                    request.missionId(), request.requestId() + ":root-literals:" + attempt,
                    request.requestId() + ":root-literals:" + attempt,
                    "Extract ONLY the starting entity name or text-search literals "
                    + "directly written by the user. The JEV selection mode and "
                    + "operator are fixed and MUST NOT be modified. "
                    + "Return exactly one literal for UNIQUE or ALL_MATCHES and "
                    + "at least two separately named literals for EXPLICIT_SET. "
                    + "Use exact substrings of the original mission: never invent "
                    + "names, append sentence-final punctuation to unquoted names, "
                    + "include descriptions of relations, infer identities or "
                    + "include any result/output entity unless it is a starting root.",
                    Map.copyOf(generationState), Set.of("queries"),
                    Map.of("type", "object", "properties", Map.of("queries", Map.of(
                                    "type", "array", "items", Map.of("type", "string"),
                                    "minItems", expectedMin, "maxItems", expectedMax)),
                            "required", List.of("queries"), "additionalProperties", false),
                    900, 0.0d, Map.of("component", "internal-root-literal-generation")));
            try {
                Object raw = response.output() == null ? null : response.output().get("queries");
                if (!(raw instanceof List<?> queries)
                        || queries.size() < expectedMin || queries.size() > expectedMax)
                    throw new IllegalArgumentException("INVALID_ROOT_LITERAL_COUNT");
                Set<String> unique = new LinkedHashSet<>();
                List<String> validated = new ArrayList<>();
                for (Object item : queries) {
                    if (!(item instanceof String value))
                        throw new IllegalArgumentException("INVALID_ROOT_LITERAL");
                    String clean = normalizedLiteral(request.query(), value);
                    if (!unique.add(clean.toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("DUPLICATED_STARTING_ROOT");
                    validated.add(clean);
                }
                if (validated.size() < expectedMin)
                    throw new IllegalArgumentException("MISSING_STARTING_ROOTS");
                return new RootLiteralResult(List.copyOf(validated), attempt + 1);
            } catch (IllegalArgumentException malformed) {
                feedback = malformed.getMessage();
                LOG.warn("internal.root-literal.invalid missionId={} attempt={} reason={}",
                        request.missionId(), attempt, feedback);
            }
        }
        throw new IllegalStateException("LLM could not produce source-grounded root literals");
    }

    private record RootLiteralResult(List<String> queries, int attempts) {}

    /** Lexical normalization only; no interpretation of task-specific vocabulary. */
    private static String normalizedLiteral(String objective, String raw) {
        String value = raw.trim();
        if (value.isEmpty() || "*".equals(value))
            throw new IllegalArgumentException("EMPTY_ROOT_LITERAL");
        // Preserve punctuation inside user-quoted names; otherwise strip a
        // trailing sentence period that would change exact/contains matching.
        boolean explicitlyQuoted = objective.contains("\"" + value + "\"")
                || objective.contains("'" + value + "'")
                || objective.contains("“" + value + "”");
        if (!explicitlyQuoted && value.endsWith("."))
            value = value.substring(0, value.length() - 1).trim();
        if (value.isEmpty() || !occursAsLiteral(objective, value))
            throw new IllegalArgumentException("ROOT_LITERAL_NOT_IN_ORIGINAL_MISSION");
        return value;
    }

    private static boolean occursAsLiteral(String objective, String value) {
        String haystack = objective.toLowerCase(Locale.ROOT);
        String needle = value.toLowerCase(Locale.ROOT);
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + 1)) {
            int after = at + needle.length();
            boolean left = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1))
                    || !Character.isLetterOrDigit(needle.charAt(0));
            boolean right = after == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(after))
                    || !Character.isLetterOrDigit(needle.charAt(needle.length() - 1));
            if (left && right) return true;
        }
        return false;
    }

    private ParsedPlan parsePlan(Map<String, Object> output, List<String> types, String objective) {
        Mode mode;
        try {
            mode = Mode.valueOf(require(output.get("selectionMode"), "selectionMode"));
        } catch (IllegalArgumentException invalid) {
            throw invalid("selection-mode", "INVALID_SELECTION_MODE");
        }
        Mode proposedMode = mode;

        if (!(output.get("roots") instanceof List<?> rawRoots)
                || rawRoots.isEmpty() || rawRoots.size() > maxQueries) {
            throw invalid("root-count", "INVALID_ROOT_COUNT");
        }
        List<RootQuery> roots = new ArrayList<>(rawRoots.size());
        for (Object raw : rawRoots) {
            roots.add(parseRoot(raw, types));
        }

        // Approximate matching cannot certify ALL_MATCHES exhaustiveness.
        // Never silently escalate the search to a tenant-wide TYPE_SCAN.
        if (mode == Mode.ALL_MATCHES && roots.size() == 1
                && roots.getFirst().matchOperator() == MatchOperator.RELEVANT) {
            throw invalid("root-exhaustiveness", "APPROXIMATE_MATCH_CANNOT_CERTIFY_ALL_MATCHES");
        }

        if (mode == Mode.TYPE_SCAN || mode == Mode.MULTI_TYPE_SCAN) {
            if (mode == Mode.TYPE_SCAN && roots.size() != 1) {
                throw invalid("mode-cardinality", "TYPE_SCAN_CARDINALITY");
            }
            if (mode == Mode.MULTI_TYPE_SCAN && roots.size() < 2) {
                throw invalid("mode-cardinality", "MULTI_TYPE_SCAN_CARDINALITY");
            }
            Set<String> uniqueTypes = new LinkedHashSet<>();
            for (RootQuery root : roots) {
                if (!uniqueTypes.add(root.type())) {
                    throw invalid("mode-cardinality", "DUPLICATE_SCAN_TYPE");
                }
            }
            roots = uniqueTypes.stream()
                    .map(type -> new RootQuery("*", type, MatchOperator.EXACT)).toList();
        } else if (mode == Mode.EXPLICIT_SET) {
            if (roots.size() < 2) {
                throw invalid("mode-cardinality", "EXPLICIT_SET_CARDINALITY");
            }
            if (roots.stream().anyMatch(root -> root.query().equals("*"))) {
                throw invalid("root-fields", "EXPLICIT_SET_REQUIRES_NAMES");
            }
        } else if (roots.size() != 1) {
            throw invalid("mode-cardinality", "SINGLE_ROOT_CARDINALITY");
        }

        // The original objective is the authoritative graph question when the
        // roots are supplied through type scanning or a single named entity.
        String graphQuestion = switch (mode) {
            case UNIQUE, TYPE_SCAN, MULTI_TYPE_SCAN -> objective;
            case ALL_MATCHES, EXPLICIT_SET -> {
                try {
                    yield require(output.get("perRootGraphQuestion"), "perRootGraphQuestion");
                } catch (IllegalArgumentException invalid) {
                    throw invalid("graph-question", "MISSING_GRAPH_QUESTION");
                }
            }
        };
        return new ParsedPlan(new Plan(mode, roots, graphQuestion), proposedMode);
    }

    private static RootQuery parseRoot(Object raw, List<String> types) {
        if (!(raw instanceof Map<?, ?> item)) {
            throw invalid("root-fields", "INVALID_ROOT_OBJECT");
        }
        String type;
        String query;
        MatchOperator operator;
        try {
            type = require(item.get("rootEntityType"), "rootEntityType");
            query = require(item.get("rootEntityQuery"), "rootEntityQuery");
            operator = MatchOperator.valueOf(require(item.get("matchOperator"), "matchOperator"));
        } catch (IllegalArgumentException invalid) {
            throw invalid("root-fields", "INVALID_ROOT_FIELDS");
        }
        if (!types.contains(type)) {
            throw invalid("root-fields", "UNDECLARED_ROOT_TYPE");
        }
        return new RootQuery(query, type, operator);
    }

    private static InvalidRootPlanException invalid(String stage, String code) {
        return new InvalidRootPlanException(stage, code);
    }

    private static String safeMode(Object raw) {
        if (!(raw instanceof String text)) return "<missing>";
        for (Mode mode : Mode.values()) {
            if (mode.name().equals(text)) return text;
        }
        return "<unrecognized>";
    }

    public SearchSpec searchSpec(String requestId, RootQuery root) {
        if (root.query().equals("*")) throw new IllegalStateException("Use typeScanSpec");
        return new SearchSpec(requestId, root.query(), List.of(root.type()), "", "", 0,
                searchLimit, root.matchOperator() == MatchOperator.RELEVANT,
                Map.of("rootDiscoveryMode", "GRAPH_SCHEMA"));
    }

    public SearchSpec typeScanSpec(String requestId, String type) {
        return new SearchSpec(requestId, "*", List.of(type), "", "", 0,
                searchLimit, false, Map.of("rootDiscoveryMode", "TYPE_SCAN"));
    }

    private Map<String, Object> outputSchema(List<String> types) {
        var string = Map.<String, Object>of("type", "string");
        var root = object(Map.of(
                "rootEntityQuery", string,
                "rootEntityType", Map.of("type", "string", "enum", types),
                "matchOperator", Map.of("type", "string", "enum",
                        List.of("EXACT", "CONTAINS", "RELEVANT"))));
        return object(Map.of(
                "selectionMode", Map.of("type", "string", "enum", List.of(
                        "UNIQUE", "ALL_MATCHES", "EXPLICIT_SET", "TYPE_SCAN", "MULTI_TYPE_SCAN")),
                "roots", Map.of("type", "array", "items", root,
                        "minItems", 1, "maxItems", maxQueries),
                "perRootGraphQuestion", string));
    }

    private static Map<String, Object> object(Map<String, Object> props) {
        return Map.of("type", "object", "properties", props,
                "required", props.keySet().stream().sorted().toList(),
                "additionalProperties", false);
    }

    private static String require(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return text.trim();
    }

    public record Request(MissionContext context, String missionId, String requestId,
                          String query, boolean relationshipTraversalRequired) {
        public Request {
            Objects.requireNonNull(context, "context");
            missionId = require(missionId, "missionId");
            requestId = require(requestId, "requestId");
            query = require(query, "query");
        }
    }

    public record Resolution(Plan plan, Map<String, Object> debug) {
        public Resolution {
            Objects.requireNonNull(plan, "plan");
            debug = Map.copyOf(debug);
        }
    }

    private record ParsedPlan(Plan plan, Mode proposedMode) { }

    private static final class InvalidRootPlanException extends IllegalArgumentException {
        private final String stage;
        private final String code;

        private InvalidRootPlanException(String stage, String code) {
            super(code);
            this.stage = stage;
            this.code = code;
        }
        String stage() { return stage; }
        String code() { return code; }
    }

}
