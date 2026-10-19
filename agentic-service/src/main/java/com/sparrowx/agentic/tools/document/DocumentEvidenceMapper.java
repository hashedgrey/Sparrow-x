package com.sparrowx.agentic.tools.document;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.document.proto.BuildDocumentEvidenceResponse;
import com.sparrowx.document.proto.DocumentEvidenceGraphProto;
import com.sparrowx.document.proto.DocumentEvidenceNodeProto;
import com.sparrowx.document.proto.DocumentEvidenceEdgeProto;
import com.sparrowx.document.proto.SearchDocumentSpansResponse;
import com.sparrowx.document.proto.SourceKindProto;
import com.sparrowx.document.proto.SourceSpanProto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentEvidenceMapper {

    private static final String DOCUMENT_SERVICE = "document-service";

    public List<EvidenceRef> fromSearch(SearchDocumentSpansResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        return deduplicate(response.getSpansList().stream().map(this::fromSpan).toList());
    }

    public List<EvidenceRef> fromBuild(BuildDocumentEvidenceResponse response) {
        if (response == null || !response.hasGraph()) {
            throw new IllegalArgumentException("document evidence response must contain a graph");
        }

        return fromGraphEvidence(response.getGraph());
    }

    public List<EvidenceRef> fromGraphEvidence(DocumentEvidenceGraphProto graph) {
        if (graph == null || graph.getGraphId().isBlank()) {
            throw new IllegalArgumentException("graph id must not be blank");
        }

        // An empty evidence-graph envelope is an observation, not proof of any
        // document content. Do not let it satisfy mandatory source coverage.
        // DocumentService must return attributable source spans for citation.
        if (graph.getSourcePoolCount() == 0) {
            return List.of();
        }

        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(fromGraph(graph));
        graph.getSourcePoolList().stream().map(this::fromSpan).forEach(evidence::add);
        return deduplicate(evidence);
    }

    public EvidenceRef fromSpan(SourceSpanProto span) {
        if (span == null || span.getSourceSpanId().isBlank()) {
            throw new IllegalArgumentException("source span id must not be blank");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.putAll(span.getMetadataMap());
        attributes.put("sourceKind", span.getSourceKind().name());
        attributes.put("claimId", span.getClaimId());
        attributes.put("title", span.getTitle());
        attributes.put("fileName", span.getFileName());
        attributes.put("excerpt", span.getExcerpt());
        attributes.put("relevanceScore", span.getRelevanceScore());

        return new EvidenceRef(
                "document-span:" + span.getSourceSpanId(),
                sourceType(span.getSourceKind()),
                DOCUMENT_SERVICE,
                span.getSourceSpanId(),
                "", // No authoritative URI field in SourceSpanProto; raw metadata remains in attributes.
                "",
                span.getDocumentId(),
                "",
                span.getCitation(), // A document title is not a source citation.
                span.getPageStart(),
                span.getPageEnd(),
                "",
                span.getChunkId(),
                "",
                attributes);
    }

    public EvidenceRef fromGraph(DocumentEvidenceGraphProto graph) {
        if (graph == null || graph.getGraphId().isBlank()) {
            throw new IllegalArgumentException("graph id must not be blank");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("goal", graph.getGoal().name());
        attributes.put("verificationStatus", graph.getVerificationStatus().name());
        attributes.put("confidence", graph.getConfidence());
        attributes.put("coverageScore", graph.getCoverageScore());
        attributes.put("warnings", List.copyOf(graph.getWarningsList()));
        attributes.put("missingNodeTypes", List.copyOf(graph.getMissingNodeTypesList()));
        attributes.put("outputSchemaRef", graph.getOutputSchemaRef());
        attributes.put("outputSchemaVersion", graph.getOutputSchemaVersion());
        // Preserve the substantive evidence graph. IDs alone cannot support
        // claim comparison or contradiction detection in downstream synthesis.
        // Keep all values grounded in DocSvc's typed response; never infer nodes.
        attributes.put("nodes", graph.getNodesList().stream().map(
                DocumentEvidenceMapper::nodeEvidence).toList());
        attributes.put("edges", graph.getEdgesList().stream().map(
                DocumentEvidenceMapper::edgeEvidence).toList());
        attributes.put("sourceSpanIds", graph.getSourcePoolList().stream()
                .map(SourceSpanProto::getSourceSpanId).toList());

        return new EvidenceRef(
                "document-graph:" + graph.getGraphId(),
                EvidenceSourceType.TOOL_RESULT,
                DOCUMENT_SERVICE,
                graph.getGraphId(),
                "",
                "",
                graph.getGraphId(),
                "",
                "Document evidence graph",
                0,
                0,
                "",
                "",
                "",
                attributes);
    }

    private static Map<String, Object> nodeEvidence(DocumentEvidenceNodeProto node) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nodeId", node.getNodeId());
        details.put("nodeType", node.getNodeType().name());
        details.put("customNodeType", node.getCustomNodeType());
        details.put("title", node.getTitle());
        details.put("summary", node.getSummary());
        details.put("normalizedText", node.getNormalizedText());
        details.put("sourceSpanIds", List.copyOf(node.getSourceSpanIdsList()));
        details.put("verificationStatus", node.getVerificationStatus().name());
        details.put("confidence", node.getConfidence());
        details.put("coverageScore", node.getCoverageScore());
        details.put("requiresSourceContext", node.getRequiresSourceContext());
        details.put("tags", List.copyOf(node.getTagsList()));
        details.put("warnings", List.copyOf(node.getWarningsList()));
        details.put("attributes", Map.copyOf(node.getAttributesMap()));
        return Map.copyOf(details);
    }

    private static Map<String, Object> edgeEvidence(DocumentEvidenceEdgeProto edge) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("edgeId", edge.getEdgeId());
        details.put("fromNodeId", edge.getFromNodeId());
        details.put("toNodeId", edge.getToNodeId());
        details.put("relationType", edge.getRelationType().name());
        details.put("customRelationType", edge.getCustomRelationType());
        details.put("rationale", edge.getRationale());
        details.put("sourceSpanIds", List.copyOf(edge.getSourceSpanIdsList()));
        details.put("confidence", edge.getConfidence());
        details.put("warnings", List.copyOf(edge.getWarningsList()));
        details.put("attributes", Map.copyOf(edge.getAttributesMap()));
        return Map.copyOf(details);
    }

    private static EvidenceSourceType sourceType(SourceKindProto sourceKind) {
        return switch (sourceKind) {
            case SOURCE_KIND_DOCUMENT_METADATA -> EvidenceSourceType.DOCUMENT;
            case SOURCE_KIND_CHUNK, SOURCE_KIND_CLAIM,
                 SOURCE_KIND_UNSPECIFIED, UNRECOGNIZED -> EvidenceSourceType.DOCUMENT_SPAN;
        };
    }

    private static List<EvidenceRef> deduplicate(List<EvidenceRef> evidence) {
        Map<String, EvidenceRef> byId = new LinkedHashMap<>();
        evidence.forEach(item -> byId.putIfAbsent(item.evidenceId(), item));
        return List.copyOf(byId.values());
    }

}
