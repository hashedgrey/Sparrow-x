package com.sparrowx.document.evidencegraph.dice;

import com.sparrowx.document.domain.models.DocumentEvidenceEdge;
import com.sparrowx.document.domain.models.DocumentEvidenceNode;
import com.sparrowx.document.domain.models.SourceSpan;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;

import java.util.List;

public interface DiceEvidenceProjectionService {

    ProjectionResult project(
            BuildDocumentEvidenceCommand command,
            List<SourceSpan> sourcePool,
            String retrievalQuery
    );

    record ProjectionResult(
            List<DocumentEvidenceNode> nodes,
            List<DocumentEvidenceEdge> edges,
            List<String> warnings
    ) {
        public ProjectionResult {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean hasSemanticEvidence() {
            return !nodes.isEmpty();
        }
    }
}