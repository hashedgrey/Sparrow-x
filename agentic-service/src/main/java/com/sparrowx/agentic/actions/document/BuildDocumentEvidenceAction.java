package com.sparrowx.agentic.actions.document;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.document.DocumentEvidenceMapper;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder.BuildSpec;
import com.sparrowx.agentic.tools.document.DocumentTool;
import com.sparrowx.document.proto.BuildDocumentEvidenceRequest;
import com.sparrowx.document.proto.BuildDocumentEvidenceResponse;
import com.sparrowx.document.proto.DocumentEvidenceGraphProto;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic Document Service capability executor.
 *
 * Embabel planning happens above this class.
 */
public final class BuildDocumentEvidenceAction {

    private final DocumentEvidenceRequestBuilder requestBuilder;
    private final DocumentTool documentTool;
    private final DocumentEvidenceMapper evidenceMapper;

    public BuildDocumentEvidenceAction(
            DocumentEvidenceRequestBuilder requestBuilder,
            DocumentTool documentTool,
            DocumentEvidenceMapper evidenceMapper
    ) {
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder must not be null"
        );

        this.documentTool = Objects.requireNonNull(documentTool, "documentTool must not be null"
        );

        this.evidenceMapper = Objects.requireNonNull(evidenceMapper, "evidenceMapper must not be null"
        );
    }

    public Result execute(MissionContext context, BuildSpec spec
    ) {
        BuildDocumentEvidenceRequest request = requestBuilder.build(context, spec);
        BuildDocumentEvidenceResponse response = documentTool.buildEvidence(context, request);

        if (!response.hasGraph()) {
            throw new IllegalStateException("Document Service returned no evidence graph");
        }

        return new Result(
                response.getGraph(),
                evidenceMapper.fromBuild(response),
                response.getUsedChunkRetrieval(),
                response.getUsedClaimCache(),
                response.getCoverageScore(),
                response.getWarningsList()
        );
    }

    public record Result(
            DocumentEvidenceGraphProto graph,
            List<EvidenceRef> evidenceRefs,
            boolean usedChunkRetrieval,
            boolean usedClaimCache,
            double coverageScore,
            List<String> warnings
    ) {

        public Result {
            graph = Objects.requireNonNull(graph, "graph must not be null");
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}