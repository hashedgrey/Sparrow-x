package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.PropositionResults;
import com.embabel.dice.projection.graph.GraphProjectionService;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.provenance.UriLocator;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.FileName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DiceDocumentEnricher {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    DiceDocumentEnricher.class
            );

    private final PropositionPipeline propositionPipeline;
    private final PropositionRepository propositionRepository;
    private final NamedEntityDataRepository namedEntityDataRepository;
    private final GraphProjectionService graphProjectionService;
    private final DataDictionary dataDictionary;
    private final EntityResolver entityResolver;
    private final Relations relations;

    public DiceDocumentEnricher(
            PropositionPipeline propositionPipeline,
            PropositionRepository propositionRepository,
            NamedEntityDataRepository namedEntityDataRepository,
            GraphProjectionService graphProjectionService,
            @Qualifier("diceDataDictionary")
            DataDictionary dataDictionary,
            EntityResolver entityResolver,
            Relations relations
    ) {
        this.propositionPipeline =
                Objects.requireNonNull(propositionPipeline, "propositionPipeline must not be null");
        this.propositionRepository =
                Objects.requireNonNull(propositionRepository, "propositionRepository must not be null");
        this.namedEntityDataRepository =
                Objects.requireNonNull(namedEntityDataRepository, "namedEntityDataRepository must not be null");
        this.graphProjectionService =
                Objects.requireNonNull(graphProjectionService, "graphProjectionService must not be null");
        this.dataDictionary =
                Objects.requireNonNull(dataDictionary, "dataDictionary must not be null");
        this.entityResolver =
                Objects.requireNonNull(entityResolver, "entityResolver must not be null");
        this.relations =
                Objects.requireNonNull(relations, "relations must not be null");
    }

    public DiceEnrichmentResult enrich(
            String tenantId,
            DocumentId documentId,
            FileName fileName,
            List<Chunk> chunks
    ) {
        requireNonBlank(tenantId, "tenantId");

        Objects.requireNonNull(documentId, "documentId must not be null");

        Objects.requireNonNull(fileName, "fileName must not be null");

        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }

        logger.info(
                "Starting DICE enrichment tenantId={} documentId={} chunks={}",
                tenantId,
                documentId.value(),
                chunks.size()
        );

        SourceAnalysisContext context =
                SourceAnalysisContext
                        .withContextId(tenantId)
                        .withEntityResolver(entityResolver)
                        .withSchema(dataDictionary)
                        .withRelations(relations)
                        .withSourceLocator(
                                new UriLocator(
                                        "sparrowx://document/"
                                                + documentId.value(),
                                        fileName.value()
                                )
                        )
                        .withMintNewEntities(true)
                        .withMintedEntityProperties(
                                Map.of(
                                        "tenant_id",
                                        tenantId,
                                        "document_id",
                                        documentId.value(),
                                        "file_name",
                                        fileName.value()
                                )
                        );

        PropositionResults results =
                propositionPipeline.process(
                        chunks,
                        context
                );

        /*
         * PropositionPipeline intentionally isolates individual chunk
         * failures. SparrowX uses stricter synchronous ingestion semantics:
         * any failed semantic chunk means the document does not become READY.
         */
        if (!results.getFailedChunkIds().isEmpty()) {
            throw new IllegalStateException(
                    "DICE proposition extraction failed for document "
                            + documentId.value()
                            + " on chunks "
                            + results.getFailedChunkIds()
            );
        }

        /*
         * The DICE pipeline returns unsaved results.
         *
         * Persist now while the ingestion job is still running.
         *
         * This stores:
         *   propositions
         *   resolved/new entities
         *   proposition/entity structural relationships
         *   grounding relationships
         */
        repairMissingUpdatedEntities(results, namedEntityDataRepository);
        results.persist(propositionRepository, namedEntityDataRepository);

        var graphResult = graphProjectionService.projectAndPersist(
                results.propositionsToPersist()
        );



        var projectionResults = graphResult.getFirst();

        var relationshipPersistence = graphResult.getSecond();

        if (relationshipPersistence.getFailedCount() > 0) {
            throw new IllegalStateException(
                    "DICE graph persistence failed for document "
                            + documentId.value()
                            + ": "
                            + relationshipPersistence.getErrors()
            );
        }

        int relationshipCount = projectionResults
                .getProjected()
                .size();

        DiceEnrichmentResult enrichmentResult =
                new DiceEnrichmentResult(
                        results.getTotalPropositions(),
                        results.getFullyResolvedCount(),
                        results.getPartiallyResolvedCount(),
                        results.getUnresolvedCount(),
                        relationshipCount
                );

        logger.info(
                "Completed DICE enrichment tenantId={} documentId={} propositions={} relationships={} fullyResolved={} partiallyResolved={} unresolved={}",
                tenantId,
                documentId.value(),
                enrichmentResult.propositionCount(),
                enrichmentResult.relationshipCount(),
                enrichmentResult.fullyResolvedCount(),
                enrichmentResult.partiallyResolvedCount(),
                enrichmentResult.unresolvedCount()
        );

        return enrichmentResult;
    }

    private void repairMissingUpdatedEntities(
            PropositionResults results,
            NamedEntityDataRepository namedEntityDataRepository
    ) {
        int missing = 0;

        for (var entity : results.updatedEntities()) {

            if (namedEntityDataRepository.findById(entity.getId()) != null) {
                continue;
            }

            missing++;

            logger.warn(
                    "DICE classified entity as existing but repository no longer contains it; " +
                            "materializing before strict persist entityId={} name={}",
                    entity.getId(),
                    entity.getName()
            );

            /*
             * NamedEntityDataRepository.save() is an upsert.
             *
             * DICE PersistablePropositions.persist() will subsequently call
             * update(entity). Pre-materializing the missing entity here allows
             * that strict update to retain its normal semantics.
             */
            namedEntityDataRepository.save(entity);
        }

        logger.info(
                "DICE entity persistence preflight newEntities={} updatedEntities={} missingUpdatedEntities={}",
                results.newEntities().size(),
                results.updatedEntities().size(),
                missing
        );
    }

    private void requireNonBlank(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
    }

    public record DiceEnrichmentResult(
            int propositionCount,
            int fullyResolvedCount,
            int partiallyResolvedCount,
            int unresolvedCount,
            int relationshipCount
    ) {
    }
}