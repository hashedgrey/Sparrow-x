package com.sparrowx.agentic.agents;

import com.sparrowx.agentic.agents.MissionEvidenceActions.*;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.agentic.planning.InternalRootSelection.Candidate;
import com.sparrowx.agentic.planning.MissionCapabilities;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.planning.graph.GraphResolutionStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Admission of backend-validated evidence, separate from semantic goal certification. */
final class MissionEvidenceGrounding {
    private MissionEvidenceGrounding() { }

    static boolean hasEvidence(MissionIntent intent, List<EvidenceState> states) {
        return select(intent, states).stream().anyMatch(state -> !state.evidenceRefs().isEmpty());
    }

    static List<EvidenceState> select(MissionIntent intent, List<EvidenceState> states) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(states, "states");
        List<EvidenceState> admitted = new ArrayList<>();
        for (EvidenceState state : states) {
            Objects.requireNonNull(state, "states must not contain null");
            if (state instanceof DocumentEvidenceState document) {
                if (!intent.requiresDocumentEvidence()
                        || !MissionCapabilities.allows(intent, MissionCapabilities.DOCUMENT_EVIDENCE)) continue;
                List<EvidenceRef> refs = document.performed() && document.graph() != null
                        && document.graph().getSourcePoolCount() > 0
                        ? document.evidenceRefs().stream().filter(MissionEvidenceGrounding::documentRef).toList()
                        : List.of();
                admitted.add(new DocumentEvidenceState(document.performed(), document.graph(), refs,
                        document.warnings(), document.summary(), document.attributes()));
            } else if (state instanceof InternalSearchState search) {
                if (!intent.requiresInternalContext()
                        || !MissionCapabilities.allows(intent, MissionCapabilities.INTERNAL_SEARCH)) continue;
                List<EvidenceRef> refs = search.performed() && search.selection().ready()
                        ? search.evidenceRefs().stream().filter(ref -> search.selection().selected().stream()
                                .anyMatch(root -> entityRefMatches(ref, root))).toList() : List.of();
                // Keep the unresolved observation so requirement checking cannot
                // fall back to an older successful search. Its candidates are not evidence.
                admitted.add(new InternalSearchState(search.performed(), refs, search.warnings(),
                        search.summary(), search.attributes(), search.selection()));
            } else if (state instanceof CompanyGraphState graph) {
                if (!intent.requiresCompanyGraph()
                        || !MissionCapabilities.allows(intent, MissionCapabilities.COMPANY_GRAPH)) continue;
                admitted.add(admitGraph(graph));
            }
        }
        return List.copyOf(admitted);
    }

    private static CompanyGraphState admitGraph(CompanyGraphState graph) {
        List<RootGraphOutcome> outcomes = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RootGraphOutcome outcome : graph.rootOutcomes()) {
            List<EvidenceRef> rootRefs = graph.performed() && outcome.status() == GraphResolutionStatus.COMPLETE
                    ? outcome.evidenceRefs().stream()
                            .filter(graph.evidenceRefs()::contains)
                            .filter(ref -> graphRefMatches(ref, outcome.root())).toList() : List.of();
            GraphResolutionStatus status = rootRefs.isEmpty()
                    ? GraphResolutionStatus.INSUFFICIENT : GraphResolutionStatus.COMPLETE;
            List<Object> rootRows = new ArrayList<>();
            for (EvidenceRef ref : rootRefs) {
                List<?> verified = ((List<?>) ref.attributes().get("validatedGraphAnswerRows")).stream()
                        .filter(item -> outcome.root().entityId().equals(((Map<?, ?>) item).get("rootEntityId")))
                        .toList();
                rootRows.addAll(verified);
                for (Object row : verified) rows.add(Map.of(
                        "rootEntityId", outcome.root().entityId(),
                        "rootNodeType", outcome.root().nodeType(),
                        "rootLabel", outcome.root().label(), "verifiedAnswer", row));
            }
            outcomes.add(new RootGraphOutcome(outcome.root(), status, rootRefs, outcome.summary(),
                    Map.of("validatedGraphAnswerRows", List.copyOf(rootRows),
                            "emptyQueryResult", !rootRefs.isEmpty() && rootRows.isEmpty())));
            refs.addAll(rootRefs);
        }
        long processed = outcomes.stream().filter(root -> root.status() == GraphResolutionStatus.COMPLETE).count();
        boolean complete = graph.performed() && !outcomes.isEmpty() && processed == outcomes.size();
        GraphResolutionStatus status = graph.resolutionStatus() == GraphResolutionStatus.COMPLETE && !complete
                ? GraphResolutionStatus.INSUFFICIENT : graph.resolutionStatus();
        Map<String, Object> attributes = new LinkedHashMap<>(graph.attributes());
        attributes.put("graphResolutionStatus", status.name());
        attributes.put("processedRoots", processed);
        attributes.put("validatedGraphAnswerRows", List.copyOf(rows));
        return new CompanyGraphState(graph.performed(), status, refs.stream().distinct().toList(),
                graph.warnings(), graph.summary(), Map.copyOf(attributes), outcomes);
    }

    private static boolean documentRef(EvidenceRef ref) {
        return usableRef(ref) && "document-service".equals(ref.sourceService())
                && (ref.sourceType() == EvidenceSourceType.DOCUMENT
                || ref.sourceType() == EvidenceSourceType.DOCUMENT_SPAN);
    }

    static boolean entityRefMatches(EvidenceRef ref, Candidate root) {
        return usableRef(ref) && ref.sourceType() == EvidenceSourceType.INTERNAL_ENTITY
                && "internal-service".equals(ref.sourceService())
                && root.entityId().equals(ref.sourceId())
                && root.nodeType().equals(ref.attributes().get("nodeType"));
    }

    static boolean graphRefMatches(EvidenceRef ref, Candidate root) {
        if (!usableRef(ref) || ref.sourceType() != EvidenceSourceType.INTERNAL_GRAPH
                || !"internal-service".equals(ref.sourceService())) return false;
        Map<String, Object> attributes = ref.attributes();
        if (!Boolean.FALSE.equals(attributes.get("truncated"))
                || !(attributes.get("rootEntityIds") instanceof Collection<?> ids)
                || !ids.contains(root.entityId())
                || !(attributes.get("validatedGraphQuery") instanceof Map<?, ?> query)
                || !root.nodeType().equals(query.get("rootType"))
                || !(query.get("traversals") instanceof List<?>)
                || !(query.get("predicates") instanceof List<?>)
                || !(query.get("projections") instanceof List<?>)
                || !(attributes.get("validatedGraphAnswerRows") instanceof List<?> rows)) return false;
        if (ids.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank())) return false;
        if ("ROOT_SLICE_OF_BATCH".equals(attributes.get("resultScope"))) {
            if (ids.size() != 1 || !root.nodeType().equals(attributes.get("rootNodeType"))
                    || !(attributes.get("queryRootEntityIds") instanceof Collection<?> queryRoots)
                    || !queryRoots.containsAll(ids)
                    || queryRoots.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank()))
                return false;
        }
        // Accept old complete batch proofs from checkpoints without relabeling
        // them as per-root slices. Their full evaluated scope remains explicit.
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row) || !ids.contains(row.get("rootEntityId"))
                    || !(row.get("nodes") instanceof Map<?, ?>)
                    || !(row.get("relationships") instanceof Map<?, ?>)) return false;
        }
        return true;
    }

    private static boolean usableRef(EvidenceRef ref) {
        return ref != null && !ref.evidenceId().isBlank() && !ref.sourceId().isBlank();
    }
}
