package com.sparrowx.document.evidencegraph;

import com.sparrowx.document.domain.models.DocumentEvidenceNode;
import com.sparrowx.document.domain.models.SourceSpan;
import com.sparrowx.document.domain.valueobjects.VerificationStatus;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EvidenceNormalizer {

    private static final int DEFAULT_MAX_NODES = 8;


    public NormalizationResult normalize(
            BuildDocumentEvidenceCommand command,
            List<SourceSpan> sourceSpans
    ) {
        List<String> warnings = new ArrayList<>();

        if (sourceSpans == null || sourceSpans.isEmpty()) {
            return new NormalizationResult(
                    List.of(),
                    List.of("Evidence normalization skipped because sourceSpans is empty.")
            );
        }

        List<DocumentEvidenceNode> nodes =
                sourceSpans.stream()
                        .limit(normalizeLimit(command))
                        .map(span -> toNode(
                                command,
                                span,
                                DocumentEvidenceNode.EvidenceNodeType.UNSPECIFIED
                        ))
                        .toList();

        warnings.add(
                "Used grounded source-span nodes without semantic classification."
        );

        List<DocumentEvidenceNode.EvidenceNodeType> missingTypes =
                missingRequestedTypes(command, nodes);

        if (!missingTypes.isEmpty()) {
            warnings.add(
                    "Requested semantic evidence node types were not inferred by document-service: "
                            + missingTypes
            );
        }

        nodes = deduplicateNodes(nodes);
        nodes = nodes.stream()
                .limit(normalizeLimit(command))
                .toList();

        return new NormalizationResult(nodes, warnings);
    }


    private DocumentEvidenceNode toNode(
            BuildDocumentEvidenceCommand command,
            SourceSpan span,
            DocumentEvidenceNode.EvidenceNodeType nodeType
    ) {
        String excerpt = span.excerpt();

        return new DocumentEvidenceNode(
                UUID.randomUUID().toString(),
                nodeType,
                nodeType == DocumentEvidenceNode.EvidenceNodeType.CUSTOM ? "custom_evidence" : "",
                titleFor(nodeType, span),
                summarize(excerpt),
                truncate(normalizeWhitespace(excerpt), 260),
                List.of(span.sourceSpanId()),
                VerificationStatus.UNVERIFIED,
                bounded(span.relevanceScore()),
                excerpt.isBlank() ? 0.0 : 1.0,
                excerpt.isBlank(),
                buildTags(command, nodeType),
                List.of("Grounded source-span fallback node; semantic type not inferred."),
                Map.of(
                        "document_id", span.documentId() == null ? "" : span.documentId().value(),
                        "chunk_id", span.chunkId() == null ? "" : span.chunkId().value(),
                        "citation", span.citation(),
                        "normalization_source", "source_span_fallback"
                )
        );
    }

    private String titleFor(
            DocumentEvidenceNode.EvidenceNodeType nodeType,
            SourceSpan span
    ) {
        String base = firstNonBlank(span.title(), span.fileName(), "Document evidence");

        return switch (nodeType) {
            case CLAIM -> "Source Claim - " + truncate(base, 70);
            case METRIC -> "Source Metric - " + truncate(base, 70);
            case FRAMEWORK -> "Source Framework - " + truncate(base, 70);
            case ENTITY -> "Source Entity - " + truncate(base, 70);
            case OBLIGATION -> "Source Obligation - " + truncate(base, 70);
            case CUSTOM -> "Custom Evidence - " + truncate(base, 70);
            case UNSPECIFIED -> truncate(base, 90);
        };
    }





    private List<DocumentEvidenceNode.EvidenceNodeType> missingRequestedTypes(
            BuildDocumentEvidenceCommand command,
            List<DocumentEvidenceNode> nodes
    ) {
        if (command == null || command.spec() == null || command.spec().requestedNodeTypes().isEmpty()) {
            return List.of();
        }

        EnumSet<DocumentEvidenceNode.EvidenceNodeType> requested =
                EnumSet.noneOf(DocumentEvidenceNode.EvidenceNodeType.class);

        command.spec().requestedNodeTypes()
                .stream()
                .filter(type -> type != null && type != DocumentEvidenceNode.EvidenceNodeType.UNSPECIFIED)
                .forEach(requested::add);

        if (requested.isEmpty()) {
            return List.of();
        }

        EnumSet<DocumentEvidenceNode.EvidenceNodeType> present =
                EnumSet.noneOf(DocumentEvidenceNode.EvidenceNodeType.class);

        if (nodes != null) {
            nodes.stream()
                    .filter(node -> node != null && node.nodeType() != null)
                    .map(DocumentEvidenceNode::nodeType)
                    .forEach(present::add);
        }

        requested.removeAll(present);

        return requested.stream().toList();
    }



    private int normalizeLimit(BuildDocumentEvidenceCommand command) {
        if (command == null || command.limit() <= 0) {
            return DEFAULT_MAX_NODES;
        }

        return Math.max(1, command.limit());
    }

    private List<DocumentEvidenceNode> deduplicateNodes(List<DocumentEvidenceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        Map<String, DocumentEvidenceNode> deduplicated = new LinkedHashMap<>();

        for (DocumentEvidenceNode node : nodes) {
            if (node == null) {
                continue;
            }

            String key = node.nodeType() + "::" + normalizeWhitespace(node.normalizedText()).toLowerCase();

            if (key.isBlank() || key.equals(node.nodeType() + "::")) {
                key = node.nodeType() + "::" + normalizeWhitespace(node.title()).toLowerCase();
            }

            deduplicated.putIfAbsent(key, node);
        }

        return new ArrayList<>(deduplicated.values());
    }

    private List<String> buildTags(
            BuildDocumentEvidenceCommand command,
            DocumentEvidenceNode.EvidenceNodeType nodeType
    ) {
        List<String> tags = new ArrayList<>();

        if (command != null && command.buildContext() != null) {
            tags.addAll(command.buildContext().topics());
            tags.addAll(command.buildContext().keywords());
        }

        tags.add(nodeType.name().toLowerCase());

        return tags.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private String summarize(String excerpt) {
        String normalized = normalizeWhitespace(excerpt);

        if (normalized.length() <= 220) {
            return normalized;
        }

        return normalized.substring(0, 220) + "...";
    }

    private String truncate(String value, int maxChars) {
        String normalized = normalizeWhitespace(value);

        if (normalized.length() <= maxChars) {
            return normalized;
        }

        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "";
    }

    private double bounded(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            return 0.0;
        }

        return Math.min(1.0, value);
    }

    public record NormalizationResult(
            List<DocumentEvidenceNode> nodes,
            List<String> warnings
    ) {
        public NormalizationResult {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}