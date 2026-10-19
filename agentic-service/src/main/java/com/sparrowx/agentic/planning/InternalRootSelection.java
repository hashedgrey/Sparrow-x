package com.sparrowx.agentic.planning;

import com.sparrowx.internal.grpc.InternalEntitySearchResult;
import java.util.*;

/** Verified, query-level root selection; node types are exact ontology strings. */
public final class InternalRootSelection {
    private InternalRootSelection() {}
    public enum Mode { UNIQUE, ALL_MATCHES, EXPLICIT_SET, TYPE_SCAN, MULTI_TYPE_SCAN }
    public enum MatchOperator { EXACT, CONTAINS, RELEVANT }
    public enum Outcome { RESOLVED, EMPTY, AMBIGUOUS, INCOMPLETE }

    public record RootQuery(String query, String type, MatchOperator matchOperator) {
        public RootQuery {
            query = require(query, "root query"); type = require(type, "root type");
            Objects.requireNonNull(matchOperator);
        }
    }
    public record Plan(Mode mode, List<RootQuery> queries, String perRootGraphQuestion) {
        public Plan {
            Objects.requireNonNull(mode); queries = List.copyOf(Objects.requireNonNull(queries));
            if (queries.isEmpty()
                    || ((mode != Mode.EXPLICIT_SET && mode != Mode.MULTI_TYPE_SCAN) && queries.size() != 1)
                    || (mode == Mode.EXPLICIT_SET && queries.size() < 2)
                    || (mode == Mode.MULTI_TYPE_SCAN && queries.size() < 2))
                throw new IllegalArgumentException("Invalid root count for selection mode");
            if (mode == Mode.MULTI_TYPE_SCAN) {
                if (queries.stream().anyMatch(q -> !"*".equals(q.query()) || q.matchOperator() != MatchOperator.EXACT)
                        || queries.stream().map(RootQuery::type).distinct().count() != queries.size())
                    throw new IllegalArgumentException("MULTI_TYPE_SCAN requires distinct ontology type scans");
            }
            perRootGraphQuestion = require(perRootGraphQuestion, "graph question");
        }
    }
    public record Candidate(String entityId, String nodeType, String label, String slug,
                            String summary, double score, Map<String,String> attributes) {
        public Candidate {
            entityId = require(entityId,"candidate entityId");
            nodeType = require(nodeType,"candidate nodeType");
            Objects.requireNonNull(label); Objects.requireNonNull(slug);
            Objects.requireNonNull(summary);
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid candidate score");
            attributes = Map.copyOf(Objects.requireNonNull(attributes));
        }
        public static Candidate fromProto(InternalEntitySearchResult proto) {
            String type = proto.getNodeTypeName();
            if (type.isBlank()) throw new IllegalStateException(
                    "IntSvc did not supply authoritative dynamic graph node type");
            return new Candidate(proto.getEntityId(), type, proto.getLabel(), proto.getSlug(),
                    proto.getSummary(), proto.getScore(), proto.getAttributesMap());
        }
        public String identity() { return nodeType + ":" + entityId; }
    }
    public record RootResult(RootQuery query, List<Candidate> matched, Outcome outcome, String detail) {
        public RootResult {
            Objects.requireNonNull(query); matched = List.copyOf(Objects.requireNonNull(matched));
            Objects.requireNonNull(outcome); Objects.requireNonNull(detail);
            if (outcome == Outcome.RESOLVED && matched.isEmpty())
                throw new IllegalArgumentException("Resolved root has no candidates");
        }
    }
    public record Selection(Plan plan, List<RootResult> roots) {
        public Selection {
            Objects.requireNonNull(plan); roots = List.copyOf(Objects.requireNonNull(roots));
            if (roots.size() != plan.queries().size())
                throw new IllegalArgumentException("Root result count differs from plan");
            for (int i=0;i<roots.size();i++)
                if (!roots.get(i).query().equals(plan.queries().get(i)))
                    throw new IllegalArgumentException("Root result order differs from plan");
        }
        public boolean ready() {
            if (!roots.stream().allMatch(r -> r.outcome() == Outcome.RESOLVED ||
                    ((plan.mode() == Mode.ALL_MATCHES || plan.mode() == Mode.TYPE_SCAN
                            || plan.mode() == Mode.MULTI_TYPE_SCAN)
                            && r.outcome() == Outcome.EMPTY))) return false;
            if (plan.mode() == Mode.EXPLICIT_SET) {
                long unique = roots.stream().flatMap(r->r.matched().stream())
                        .map(Candidate::identity).distinct().count();
                return unique == roots.size();
            }
            return true;
        }
        public List<Candidate> selected() {
            if (!ready()) return List.of();
            Map<String,Candidate> unique = new LinkedHashMap<>();
            roots.forEach(r->r.matched().forEach(c->unique.putIfAbsent(c.identity(),c)));
            return List.copyOf(unique.values());
        }
        public String explanation() {
            if (ready()) return "All requested root expressions resolved";
            return roots.stream().filter(r->r.outcome() != Outcome.RESOLVED)
                    .map(r->r.query().query()+": "+r.detail())
                    .reduce((a,b)->a+"; "+b).orElse("Root resolution incomplete");
        }
    }
    public static RootResult evaluate(Mode mode, RootQuery query, List<Candidate> returned,
                                      boolean backendAmbiguous, boolean exhaustive) {
        Objects.requireNonNull(mode); Objects.requireNonNull(query); Objects.requireNonNull(returned);
        if (!exhaustive) return new RootResult(query, List.of(), Outcome.INCOMPLETE,
                "Search result set is incomplete; use pagination or increase the authorized limit");
        List<Candidate> matching = returned.stream().filter(c->c.nodeType().equals(query.type()))
                .filter(c->(mode == Mode.TYPE_SCAN || mode == Mode.MULTI_TYPE_SCAN) || matches(query,c))
                .distinct().toList();
        if (mode == Mode.ALL_MATCHES || mode == Mode.TYPE_SCAN || mode == Mode.MULTI_TYPE_SCAN) {
            if (matching.isEmpty()) return new RootResult(query, List.of(), Outcome.EMPTY, "No matching entities");
            return new RootResult(query, matching, Outcome.RESOLVED, "All matching entities retained");
        }
        if (matching.isEmpty()) return new RootResult(query, List.of(), Outcome.EMPTY, "No matching entity");
        if (query.matchOperator() == MatchOperator.RELEVANT) {
            if (backendAmbiguous || matching.size() != 1)
                return new RootResult(query, matching, Outcome.AMBIGUOUS,
                        "Relevance-only root selection is not independently verified as unique");
            return new RootResult(query, matching, Outcome.RESOLVED, "Unique relevant root");
        }
        if (matching.size() != 1)
            return new RootResult(query, matching, Outcome.AMBIGUOUS,
                    "More than one entity satisfies the root predicate");
        return new RootResult(query, matching, Outcome.RESOLVED, "One matching root resolved");
    }
    /**
     * Reconcile a model's RELEVANT classification with independently verified
     * entity identity. The model can extract a literal entity name but select
     * RELEVANT in error. A unique exact label/slug/ID match is sufficient only
     * when that expression occurs verbatim in the original user request.
     *
     * Never rank away competing exact matches; never bypass search completeness.
     * This is generic identity verification, not a business-type heuristic.
     */
    public static RootResult evaluate(Mode mode, RootQuery query, List<Candidate> returned,
                                      boolean backendAmbiguous, boolean exhaustive,
                                      String originalMission) {
        RootResult ordinary = evaluate(mode, query, returned, backendAmbiguous, exhaustive);
        if (ordinary.outcome() != Outcome.AMBIGUOUS
                || query.matchOperator() != MatchOperator.RELEVANT
                || (mode != Mode.UNIQUE && mode != Mode.EXPLICIT_SET)
                || !literalMention(originalMission, query.query())) {
            return ordinary;
        }
        List<Candidate> exact = returned.stream()
                .filter(c -> c.nodeType().equals(query.type()))
                .filter(c -> exactIdentity(query.query(), c))
                .distinct().toList();
        if (exact.size() == 1) {
            return new RootResult(query, exact, Outcome.RESOLVED,
                    "One exact label/slug/ID identity verified against literal user mention");
        }
        if (exact.size() > 1) {
            return new RootResult(query, exact, Outcome.AMBIGUOUS,
                    "Multiple entities share the exact stated identity");
        }
        return ordinary;
    }

    private static boolean exactIdentity(String phrase, Candidate c) {
        String normalized = phrase.strip();
        return normalized.equalsIgnoreCase(c.label().strip())
                || normalized.equalsIgnoreCase(c.slug().strip())
                || normalized.equalsIgnoreCase(c.entityId().strip());
    }

    private static boolean literalMention(String mission, String phrase) {
        if (mission == null || mission.isBlank() || phrase == null || phrase.isBlank()) return false;
        // Word boundaries exclude embedded matches such as "Service" in "Microservice".
        var pattern = java.util.regex.Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(phrase.strip())
                        + "(?![\\p{L}\\p{N}])");
        return pattern.matcher(mission).find();
    }

    private static boolean matches(RootQuery q, Candidate c) {
        if (q.matchOperator() == MatchOperator.RELEVANT) return true;
        String term=q.query().toLowerCase(Locale.ROOT);
        String label=c.label().toLowerCase(Locale.ROOT);
        return switch (q.matchOperator()) {
            case EXACT -> term.equals(label) || term.equals(c.slug().toLowerCase(Locale.ROOT))
                    || term.equals(c.entityId().toLowerCase(Locale.ROOT));
            case CONTAINS -> label.contains(term);
            case RELEVANT -> true;
        };
    }
    private static String require(String s,String what) {
        if (s==null || s.isBlank()) throw new IllegalArgumentException(what+" is required");
        return s.trim();
    }
}
