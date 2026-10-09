package com.sparrowx.agentic.planning;

import com.sparrowx.internal.grpc.InternalEntitySearchResult;
import com.sparrowx.internal.grpc.InternalGraphNodeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable, typed query-level root selection and verified search outcomes. */
public final class InternalRootSelection {
    private InternalRootSelection() {}

    public enum Mode { UNIQUE, ALL_MATCHES, EXPLICIT_SET }
    public enum MatchOperator { EXACT, CONTAINS, RELEVANT }
    public enum Outcome { RESOLVED, EMPTY, AMBIGUOUS, INCOMPLETE }

    public record RootQuery(String query, InternalGraphNodeType type, MatchOperator matchOperator) {
        public RootQuery {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("Root query is blank");
            query = query.trim();
            type = Objects.requireNonNull(type, "type");
            if (type == InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    || type == InternalGraphNodeType.UNRECOGNIZED)
                throw new IllegalArgumentException("Root node type is unspecified");
            matchOperator = Objects.requireNonNull(matchOperator, "matchOperator");
        }
    }

    public record Plan(Mode mode, List<RootQuery> queries, String perRootGraphQuestion) {
        public Plan {
            mode = Objects.requireNonNull(mode, "mode");
            queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
            if (queries.isEmpty()) throw new IllegalArgumentException("Root queries must not be empty");
            if (mode != Mode.EXPLICIT_SET && queries.size() != 1)
                throw new IllegalArgumentException(mode + " expects one search expression");
            if (perRootGraphQuestion == null || perRootGraphQuestion.isBlank())
                throw new IllegalArgumentException("perRootGraphQuestion is required");
            perRootGraphQuestion = perRootGraphQuestion.trim();
        }
    }

    public record Candidate(
            String entityId,
            InternalGraphNodeType nodeType,
            String label,
            String slug,
            String summary,
            double score,
            Map<String, String> attributes
    ) {
        public Candidate {
            if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("Candidate ID is blank");
            nodeType = Objects.requireNonNull(nodeType, "nodeType");
            if (nodeType == InternalGraphNodeType.INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED
                    || nodeType == InternalGraphNodeType.UNRECOGNIZED)
                throw new IllegalArgumentException("Candidate type is unspecified");
            label = Objects.requireNonNull(label, "label");
            slug = Objects.requireNonNull(slug, "slug");
            summary = Objects.requireNonNull(summary, "summary");
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid candidate score");
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }

        public static Candidate fromProto(InternalEntitySearchResult source) {
            return new Candidate(source.getEntityId(), source.getNodeType(), source.getLabel(),
                    source.getSlug(), source.getSummary(), source.getScore(), source.getAttributesMap());
        }

        public String identity() { return nodeType.name() + ":" + entityId; }
    }

    public record RootResult(RootQuery query, List<Candidate> matched, Outcome outcome, String detail) {
        public RootResult {
            query = Objects.requireNonNull(query, "query");
            matched = List.copyOf(Objects.requireNonNull(matched, "matched"));
            outcome = Objects.requireNonNull(outcome, "outcome");
            detail = Objects.requireNonNull(detail, "detail");
            if (outcome == Outcome.RESOLVED && matched.isEmpty())
                throw new IllegalArgumentException("Resolved result has no candidates");
        }
    }

    public record Selection(Plan plan, List<RootResult> roots) {
        public Selection {
            plan = Objects.requireNonNull(plan, "plan");
            roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
            if (roots.size() != plan.queries().size())
                throw new IllegalArgumentException("Every root expression must have a search outcome");
            for (int i = 0; i < roots.size(); i++) {
                if (!roots.get(i).query().equals(plan.queries().get(i)))
                    throw new IllegalArgumentException("Root search order does not match plan");
            }
        }

        public boolean ready() {
            if (!roots.stream().allMatch(r -> r.outcome() == Outcome.RESOLVED
                    || (plan.mode() == Mode.ALL_MATCHES && r.outcome() == Outcome.EMPTY))) {
                return false;
            }
            // An explicitly enumerated root is a separate obligation. Do not
            // silently remove duplicates and still claim every root was resolved.
            if (plan.mode() == Mode.EXPLICIT_SET) {
                long distinctIdentities = roots.stream()
                        .flatMap(r -> r.matched().stream())
                        .map(Candidate::identity)
                        .distinct().count();
                return distinctIdentities == roots.size();
            }
            return true;
        }

        public List<Candidate> selected() {
            if (!ready()) return List.of();
            Map<String, Candidate> unique = new LinkedHashMap<>();
            roots.forEach(r -> r.matched().forEach(c -> unique.putIfAbsent(c.identity(), c)));
            return List.copyOf(unique.values());
        }

        public String explanation() {
            if (ready()) return "All requested root expressions resolved";
            return roots.stream().filter(r -> r.outcome() != Outcome.RESOLVED)
                    .map(r -> r.query().query() + ": " + r.detail())
                    .reduce((a, b) -> a + "; " + b).orElse("Root resolution incomplete");
        }
    }

    /** Resolve from a complete search response; never silently choose the first ambiguous candidate. */
    public static RootResult evaluate(
            Mode mode,
            RootQuery query,
            List<Candidate> returned,
            boolean backendAmbiguous,
            boolean exhaustive
    ) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(returned, "returned");
        if (!exhaustive)
            return new RootResult(query, List.of(), Outcome.INCOMPLETE,
                    "Search reached its result budget; pagination/exhaustive search required");

        List<Candidate> matching = returned.stream()
                .filter(c -> c.nodeType() == query.type())
                .filter(c -> matches(query, c))
                .distinct()
                .toList();

        if (mode == Mode.ALL_MATCHES) {
            if (matching.isEmpty()) return new RootResult(query, List.of(), Outcome.EMPTY, "No matching entities");
            return new RootResult(query, matching, Outcome.RESOLVED, "All matching entities retained");
        }

        if (matching.isEmpty()) return new RootResult(query, List.of(), Outcome.EMPTY, "No matching entity");
        if (query.matchOperator() == MatchOperator.RELEVANT) {
            if (backendAmbiguous) return new RootResult(query, matching, Outcome.AMBIGUOUS,
                    "Search did not identify a unique high-confidence entity");
            // IntSvc ranks relevant hits by score, but only its non-ambiguous verdict
            // authorizes selecting the top result for an open-ended description.
            return new RootResult(query, List.of(matching.getFirst()), Outcome.RESOLVED, "One ranked entity resolved");
        }
        if (matching.size() != 1) return new RootResult(query, matching, Outcome.AMBIGUOUS,
                "More than one entity satisfies the explicit root predicate");
        return new RootResult(query, matching, Outcome.RESOLVED, "One exact root resolved");
    }

    private static boolean matches(RootQuery query, Candidate candidate) {
        if (query.matchOperator() == MatchOperator.RELEVANT) return true;
        String q = query.query().toLowerCase(Locale.ROOT);
        String label = candidate.label().toLowerCase(Locale.ROOT);
        return switch (query.matchOperator()) {
            case EXACT -> q.equals(label)
                    || q.equals(candidate.slug().toLowerCase(Locale.ROOT))
                    || q.equals(candidate.entityId().toLowerCase(Locale.ROOT));
            case CONTAINS -> label.contains(q);
            case RELEVANT -> true;
        };
    }
}
