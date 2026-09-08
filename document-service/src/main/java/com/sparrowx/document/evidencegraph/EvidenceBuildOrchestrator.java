package com.sparrowx.document.evidencegraph;

import com.sparrowx.document.domain.models.DocumentEvidenceEdge;
import com.sparrowx.document.domain.models.DocumentEvidenceGraph;
import com.sparrowx.document.domain.models.DocumentEvidenceNode;
import com.sparrowx.document.domain.models.RetrievalEvidence;
import com.sparrowx.document.domain.models.SourceSpan;
import com.sparrowx.document.domain.valueobjects.RetrievalMode;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import com.sparrowx.document.evidencegraph.dice.DiceEvidenceMapper;
import com.sparrowx.document.evidencegraph.dice.DiceEvidenceRetriever;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;
import com.sparrowx.document.retrieval.ClaimCacheRetriever;
import com.sparrowx.document.retrieval.HybridDocumentRetriever;
import com.sparrowx.document.retrieval.SourceSpanBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class EvidenceBuildOrchestrator {

    private static final int DEFAULT_LIMIT = 10;

    private final HybridDocumentRetriever hybridDocumentRetriever;
    private final SourceSpanBuilder sourceSpanBuilder;
    private final ClaimCacheRetriever claimCacheRetriever;

    private final DiceEvidenceRetriever diceEvidenceRetriever;
    private final DiceEvidenceMapper diceEvidenceMapper;

    /*
     * Conservative fallback path.
     */
    private final EvidenceNormalizer evidenceNormalizer;
    private final EvidenceRelationLinker evidenceRelationLinker;

    private final EvidenceGraphBuilder evidenceGraphBuilder;
    private final EvidenceSchemaValidator evidenceSchemaValidator;
    private final EvidenceGraphPolicy evidenceGraphPolicy;

    public EvidenceBuildOrchestrator(
            HybridDocumentRetriever hybridDocumentRetriever,
            SourceSpanBuilder sourceSpanBuilder,
            ClaimCacheRetriever claimCacheRetriever,
            DiceEvidenceRetriever diceEvidenceRetriever,
            DiceEvidenceMapper diceEvidenceMapper,
            EvidenceNormalizer evidenceNormalizer,
            EvidenceRelationLinker evidenceRelationLinker,
            EvidenceGraphBuilder evidenceGraphBuilder,
            EvidenceSchemaValidator evidenceSchemaValidator,
            EvidenceGraphPolicy evidenceGraphPolicy
    ) {
        this.hybridDocumentRetriever =
                hybridDocumentRetriever;

        this.sourceSpanBuilder =
                sourceSpanBuilder;

        this.claimCacheRetriever =
                claimCacheRetriever;

        this.diceEvidenceRetriever =
                diceEvidenceRetriever;

        this.diceEvidenceMapper =
                diceEvidenceMapper;

        this.evidenceNormalizer =
                evidenceNormalizer;

        this.evidenceRelationLinker =
                evidenceRelationLinker;

        this.evidenceGraphBuilder =
                evidenceGraphBuilder;

        this.evidenceSchemaValidator =
                evidenceSchemaValidator;

        this.evidenceGraphPolicy =
                evidenceGraphPolicy;
    }

    public EvidenceBuildOrchestrationResult build(
            BuildDocumentEvidenceCommand command
    ) {
        validate(command);

        List<String> warnings =
                new ArrayList<>();

        List<SourceSpan> sourcePool =
                new ArrayList<>();

        boolean usedClaimCache = false;
        boolean usedChunkRetrieval = false;

        if (command.allowClaimCache()) {

            ClaimCacheRetriever.ClaimCacheResult
                    claimCacheResult =
                    claimCacheRetriever.retrieve(
                            command
                    );

            sourcePool.addAll(
                    claimCacheResult.spans()
            );

            warnings.addAll(
                    claimCacheResult.warnings()
            );

            usedClaimCache =
                    !claimCacheResult
                            .spans()
                            .isEmpty();
        }

        if (sourcePool.isEmpty()
                || shouldUseChunkRetrieval(command)) {

            List<SourceSpan> retrievedSpans =
                    retrieveSourceSpans(
                            command
                    );

            sourcePool.addAll(
                    retrievedSpans
            );

            usedChunkRetrieval =
                    !retrievedSpans.isEmpty();
        }

        if (sourcePool.isEmpty()) {
            warnings.add(
                    "No source spans found for evidence build."
            );
        }

        /*
         * ---------------------------------------------------------
         * DICE semantic read path
         * ---------------------------------------------------------
         *
         * SourceSpan chunk IDs are already document scoped and
         * relevance ranked by ES/Qdrant.
         *
         * We use those chunk IDs to retrieve only propositions
         * grounded in the selected source material.
         */
        DiceEvidenceRetriever.RetrievalResult
                diceRetrieval =
                diceEvidenceRetriever.retrieve(
                        command,
                        sourcePool
                );

        warnings.addAll(
                diceRetrieval.warnings()
        );

        DiceEvidenceMapper.MappingResult
                diceMapping =
                diceEvidenceMapper.map(
                        command,
                        sourcePool,
                        diceRetrieval
                );

        warnings.addAll(
                diceMapping.warnings()
        );

        List<DocumentEvidenceNode> nodes;
        List<DocumentEvidenceEdge> edges;

        if (diceMapping.hasSemanticEvidence()) {

            nodes =
                    new ArrayList<>(
                            diceMapping.nodes()
                    );

            edges =
                    new ArrayList<>(
                            diceMapping.edges()
                    );

        } else {

            /*
             * -----------------------------------------------------
             * Conservative fallback
             * -----------------------------------------------------
             *
             * Keep the existing SourceSpan normalization so a
             * repository/projection outage does not make ordinary
             * document retrieval unusable.
             */
            warnings.add(
                    "DICE semantic evidence was unavailable; using grounded source-span fallback."
            );

            EvidenceNormalizer.NormalizationResult
                    normalizationResult =
                    evidenceNormalizer.normalize(
                            command,
                            sourcePool
                    );

            warnings.addAll(
                    normalizationResult.warnings()
            );

            nodes =
                    new ArrayList<>(
                            normalizationResult.nodes()
                    );

            EvidenceRelationLinker.LinkResult
                    linkResult =
                    evidenceRelationLinker.link(
                            command,
                            nodes
                    );

            edges =
                    new ArrayList<>(
                            linkResult.edges()
                    );

            warnings.addAll(
                    linkResult.warnings()
            );
        }

        DocumentEvidenceGraph graph =
                evidenceGraphBuilder.build(
                        command,
                        nodes,
                        edges,
                        sourcePool,
                        warnings
                );

        /*
         * Existing deterministic validation/policy tail remains
         * unchanged.
         */
        EvidenceSchemaValidator.ValidationResult
                schemaValidation =
                evidenceSchemaValidator.validate(
                        graph
                );

        if (!schemaValidation.valid()) {
            warnings.addAll(
                    schemaValidation.errors()
            );
        }

        EvidenceGraphPolicy.PolicyResult
                policyResult =
                evidenceGraphPolicy.evaluate(
                        command,
                        graph
                );

        warnings.addAll(
                policyResult.warnings()
        );

        if (!policyResult.acceptable()) {
            warnings.add(
                    "Evidence graph policy marked graph as not acceptable."
            );
        }

        graph =
                new DocumentEvidenceGraph(
                        graph.graphId(),
                        graph.goal(),
                        graph.customGoal(),
                        graph.nodes(),
                        graph.edges(),
                        graph.sourcePool(),
                        graph.verificationStatus(),
                        graph.confidence(),
                        graph.coverageScore(),
                        warnings,
                        graph.missingNodeTypes(),
                        graph.outputSchemaRef(),
                        graph.outputSchemaVersion(),
                        graph.createdAt()
                );

        return new EvidenceBuildOrchestrationResult(
                graph,
                usedChunkRetrieval,
                usedClaimCache,
                warnings
        );
    }

    private List<SourceSpan> retrieveSourceSpans(
            BuildDocumentEvidenceCommand command
    ) {
        SearchQueryText retrievalQuery =
                SearchQueryText.of(
                        buildRetrievalQuery(command)
                );

        int limit =
                normalizeLimit(
                        command.limit()
                );

        RetrievalMode retrievalMode =
                command.retrievalMode() == null
                        ? RetrievalMode.HYBRID
                        : command.retrievalMode();

        List<RetrievalEvidence> evidence =
                hybridDocumentRetriever.retrieve(
                        new HybridDocumentRetriever
                                .RetrieveDocumentsRequest(
                                command.tenantId(),
                                command.userId(),
                                command.projectId(),
                                command.teamId(),
                                retrievalQuery,
                                limit,
                                retrievalMode,
                                Set.copyOf(
                                        command
                                                .scope()
                                                .documentIds()
                                )
                        )
                );

        /*
         * Verification requires the actual source text.
         */
        boolean includeExcerpts =
                command.includeExcerpts()
                        || command.requireVerification();

        return evidence
                .stream()
                .map(item ->
                        sourceSpanBuilder
                                .fromRetrievalEvidence(
                                        item,
                                        includeExcerpts
                                )
                )
                .toList();
    }

    private boolean shouldUseChunkRetrieval(
            BuildDocumentEvidenceCommand command
    ) {
        return command.requireVerification()
                || !command
                .buildContext()
                .retrievalHint()
                .isBlank()
                || !command
                .buildContext()
                .topics()
                .isEmpty()
                || !command
                .buildContext()
                .entityNames()
                .isEmpty()
                || !command
                .buildContext()
                .keywords()
                .isEmpty();
    }

    private String buildRetrievalQuery(
            BuildDocumentEvidenceCommand command
    ) {
        List<String> parts =
                new ArrayList<>();

        String retrievalHint =
                command
                        .buildContext()
                        .retrievalHint();

        if (!retrievalHint.isBlank()) {
            parts.add(
                    retrievalHint
            );
        }

        parts.addAll(
                command
                        .buildContext()
                        .entityNames()
        );

        parts.addAll(
                command
                        .buildContext()
                        .topics()
        );

        parts.addAll(
                command
                        .buildContext()
                        .keywords()
        );

        String query =
                String.join(
                        " ",
                        parts
                ).trim();

        if (!query.isBlank()) {
            return query;
        }

        /*
         * debugTaskInstruction remains tracing-only.
         */
        throw InvalidDocumentException.blankField(
                "retrievalHint/topics/entityNames/keywords"
        );
    }

    private int normalizeLimit(
            int limit
    ) {
        return limit <= 0
                ? DEFAULT_LIMIT
                : limit;
    }

    private void validate(
            BuildDocumentEvidenceCommand command
    ) {
        if (command == null) {
            throw InvalidDocumentException.nullQuery(
                    "BuildDocumentEvidenceCommand"
            );
        }

        if (command.tenantId() == null) {
            throw InvalidDocumentException.blankField(
                    "tenantId"
            );
        }

        if (command.userId() == null) {
            throw InvalidDocumentException.blankField(
                    "userId"
            );
        }

        if (command.scope() == null) {
            throw InvalidDocumentException.blankField(
                    "scope"
            );
        }

        if (command.spec() == null) {
            throw InvalidDocumentException.blankField(
                    "spec"
            );
        }

        if (command.buildContext() == null) {
            throw InvalidDocumentException.blankField(
                    "buildContext"
            );
        }
    }

    public record EvidenceBuildOrchestrationResult(
            DocumentEvidenceGraph graph,
            boolean usedChunkRetrieval,
            boolean usedClaimCache,
            List<String> warnings
    ) {
        public EvidenceBuildOrchestrationResult {
            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(warnings);
        }
    }
}