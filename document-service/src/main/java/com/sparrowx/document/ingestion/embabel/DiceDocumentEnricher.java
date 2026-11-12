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
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.provenance.UriLocator;
import com.sparrowx.document.config.DiceIngestionProperties;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.FileName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.*;

@Component
public class DiceDocumentEnricher {

    private static final Logger logger =
            LoggerFactory.getLogger(DiceDocumentEnricher.class);


    private final PropositionPipeline propositionPipeline;
    private final PropositionRepository propositionRepository;
    private final NamedEntityDataRepository namedEntityDataRepository;
    private final GraphProjectionService graphProjectionService;
    private final DataDictionary dataDictionary;
    private final EntityResolver entityResolver;
    private final Relations relations;
    private final ExecutorService propositionPersistenceExecutor;
    private final DiceIngestionProperties properties;
    private final DiceResolutionLookupCache resolverLookupCache;
    private final DiceBatchEmbeddingCoordinator batchEmbeddingCoordinator;

    public DiceDocumentEnricher(
            PropositionPipeline propositionPipeline,
            PropositionRepository propositionRepository,
            NamedEntityDataRepository namedEntityDataRepository,
            GraphProjectionService graphProjectionService,
            @Qualifier("diceDataDictionary")
            DataDictionary dataDictionary,
            EntityResolver entityResolver,
            Relations relations,
            @Qualifier("dicePropositionPersistenceExecutor")
            ExecutorService propositionPersistenceExecutor,
            DiceIngestionProperties properties,
            DiceResolutionLookupCache resolverLookupCache,
            DiceBatchEmbeddingCoordinator batchEmbeddingCoordinator
    ) {
        this.propositionPipeline =
                Objects.requireNonNull(
                        propositionPipeline,
                        "propositionPipeline must not be null"
                );

        this.propositionRepository =
                Objects.requireNonNull(
                        propositionRepository,
                        "propositionRepository must not be null"
                );

        this.namedEntityDataRepository =
                Objects.requireNonNull(
                        namedEntityDataRepository,
                        "namedEntityDataRepository must not be null"
                );

        this.graphProjectionService =
                Objects.requireNonNull(
                        graphProjectionService,
                        "graphProjectionService must not be null"
                );

        this.dataDictionary =
                Objects.requireNonNull(
                        dataDictionary,
                        "dataDictionary must not be null"
                );

        this.entityResolver =
                Objects.requireNonNull(
                        entityResolver,
                        "entityResolver must not be null"
                );

        this.relations =
                Objects.requireNonNull(
                        relations,
                        "relations must not be null"
                );

        this.propositionPersistenceExecutor = Objects.requireNonNull(
                propositionPersistenceExecutor, "propositionPersistenceExecutor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.resolverLookupCache = Objects.requireNonNull(resolverLookupCache, "resolverLookupCache");
        this.batchEmbeddingCoordinator = Objects.requireNonNull(batchEmbeddingCoordinator, "batchEmbeddingCoordinator");
    }

    public DiceEnrichmentResult enrich(
            String tenantId,
            DocumentId documentId,
            FileName fileName,
            List<Chunk> chunks
    ) {
        requireNonBlank(tenantId, "tenantId");

        Objects.requireNonNull(
                documentId,
                "documentId must not be null"
        );

        Objects.requireNonNull(
                fileName,
                "fileName must not be null"
        );

        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "chunks must not be empty"
            );
        }

        logger.info(
                "Starting DICE enrichment tenantId={} documentId={} chunks={} "
                        + "entityVectorSearchEnabled={} entityTenantFilterEnabled={} "
                        + "extractionThreads={} persistenceThreads={}",
                tenantId,
                documentId.value(),
                chunks.size(),
                properties.entityVectorSearchEnabled(),
                properties.entityTenantFilterEnabled(),
                properties.extractionThreads(),
                properties.propositionPersistenceThreads()
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

        // DICE resolves entities serially after extraction on the calling thread.
        // Search cache exists for only this document/tenant and is discarded even on failure.
        // No repository writes occur in this scoped phase.
        PropositionResults results;
        long semanticStartNanos = System.nanoTime();
        try (var scope = resolverLookupCache.openScope(tenantId, documentId.value())) {
            results = propositionPipeline.process(chunks, context);
        }
        logger.info("DICE semantic pipeline complete tenantId={} documentId={} "
                        + "durationMs={} propositions={} vectorSearchEnabled={}",
                tenantId, documentId.value(),
                (System.nanoTime() - semanticStartNanos) / 1_000_000,
                results.getTotalPropositions(),
                properties.entityVectorSearchEnabled());

        List<String> failedChunkIds =
                results.getFailedChunkIds();

        int totalChunkCount =
                chunks.size();

        int failedChunkCount =
                failedChunkIds.size();

        int successfulChunkCount =
                totalChunkCount - failedChunkCount;

        double failedChunkRatio =
                (double) failedChunkCount
                        / (double) totalChunkCount;

        /*
         * DICE deliberately isolates per-chunk failures.
         *
         * SparrowX should preserve that benefit rather than converting
         * every isolated failure back into an all-or-nothing document
         * failure.
         *
         * Fail the enrichment when:
         *
         *   1. no semantic chunk succeeded, or
         *   2. too much of the document failed extraction.
         *
         * Otherwise persist the valid DICE output and report the
         * enrichment as partial.
         */
        if (successfulChunkCount <= 0
                || failedChunkRatio > properties.maxFailedChunkRatio()) {

            throw new IllegalStateException(
                    "DICE proposition extraction exceeded failure threshold "
                            + "for document "
                            + documentId.value()
                            + ". failedChunks="
                            + failedChunkCount
                            + "/"
                            + totalChunkCount
                            + ", failedChunkIds="
                            + failedChunkIds
            );
        }

        if (failedChunkCount > 0) {
            logger.warn(
                    "Continuing DICE enrichment with partial extraction "
                            + "tenantId={} documentId={} "
                            + "successfulChunks={} failedChunks={} totalChunks={} "
                            + "failedChunkRatio={} failedChunkIds={}",
                    tenantId,
                    documentId.value(),
                    successfulChunkCount,
                    failedChunkCount,
                    totalChunkCount,
                    failedChunkRatio,
                    failedChunkIds
            );
        }

        /*
         * PropositionPipeline returns unsaved results.
         *
         * Failed chunks contain no successful extraction result, while
         * propositions/entities produced by successful chunks remain
         * available here.
         */
        repairMissingUpdatedEntities(results, namedEntityDataRepository);

        /*
         * TEMPORARY PROFILING SPLIT.
         *
         * This reproduces the two parts of DICE
         * persistCanonicalPropositions() separately so we can determine
         * whether entity persistence or proposition persistence owns the latency.
         */
        var propsToSave = results.propositionsToPersist();

        Set<String> referencedEntityIds = new HashSet<>();

        for (var proposition : propsToSave) {
            for (var mention : proposition.getMentions()) {
                if (mention.getResolvedId() != null) {
                    referencedEntityIds.add(mention.getResolvedId());
                }
            }
        }


        /*
         * 1. Entity persistence
         */
        long entityPersistStart = System.nanoTime();

        for (var entity : results.newEntities()) {
            if (referencedEntityIds.contains(entity.getId())) {
                namedEntityDataRepository.save(entity);
            }
        }

        for (var entity : results.updatedEntities()) {
            if (referencedEntityIds.contains(entity.getId())) {
                namedEntityDataRepository.update(entity);
            }
        }

        long entityPersistMillis =
                (System.nanoTime() - entityPersistStart) / 1_000_000;

        logger.info(
                "DICE entity persistence complete "
                        + "documentId={} newEntities={} updatedEntities={} durationMs={}",
                documentId.value(),
                results.newEntities().size(),
                results.updatedEntities().size(),
                entityPersistMillis
        );


        /*
         * 2. Proposition persistence.
         *
         * The DICE repository embeds proposition text and performs deduplication,
         * provenance and graph writes in each save(). There is no bulk-save SPI
         * in PropositionRepository. A small dedicated pool overlaps independent
         * saves without bypassing the repository's invariants.
         *
         * Never fan out an active caller transaction across threads.
         */
        long propositionPersistStart = System.nanoTime();
        List<Proposition> canonical = persistPropositions(propsToSave, documentId.value());

        var persisted = com.embabel.dice.proposition.PropositionPersistenceResult.of(
                propsToSave, canonical);

        long propositionPersistMillis =
                (System.nanoTime() - propositionPersistStart) / 1_000_000;
        logger.info(
                "DICE proposition persistence complete documentId={} propositions={} "
                        + "durationMs={} maxConcurrency={}",
                documentId.value(), propsToSave.size(), propositionPersistMillis,
                TransactionSynchronizationManager.isActualTransactionActive()
                        ? 1 : properties.propositionPersistenceThreads());

        /*
         * 3. Structural relationships
         */
        long structuralStart = System.nanoTime();

        results.wireStructuralRelationships(
                persisted,
                namedEntityDataRepository
        );

        long structuralMillis =
                (System.nanoTime() - structuralStart) / 1_000_000;

        logger.info(
                "DICE structural relationship wiring complete "
                        + "documentId={} durationMs={}",
                documentId.value(),
                structuralMillis
        );




        long projectionStart = System.nanoTime();
        var graphResult = graphProjectionService.projectAndPersist(results.propositionsToPersist());

        long projectionMillis =
                (System.nanoTime() - projectionStart) / 1_000_000;

        logger.info(
                "DICE graph projection/persistence complete "
                        + "documentId={} durationMs={}",
                documentId.value(),
                projectionMillis
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

        int relationshipCount =
                projectionResults
                        .getProjected()
                        .size();

        DiceEnrichmentResult enrichmentResult =
                new DiceEnrichmentResult(
                        results.getTotalPropositions(),
                        results.getFullyResolvedCount(),
                        results.getPartiallyResolvedCount(),
                        results.getUnresolvedCount(),
                        relationshipCount,
                        totalChunkCount,
                        failedChunkCount
                );

        logger.info(
                "Completed DICE enrichment "
                        + "tenantId={} documentId={} "
                        + "propositions={} relationships={} "
                        + "fullyResolved={} partiallyResolved={} unresolved={} "
                        + "chunks={} failedChunks={} partial={}",
                tenantId,
                documentId.value(),
                enrichmentResult.propositionCount(),
                enrichmentResult.relationshipCount(),
                enrichmentResult.fullyResolvedCount(),
                enrichmentResult.partiallyResolvedCount(),
                enrichmentResult.unresolvedCount(),
                enrichmentResult.totalChunkCount(),
                enrichmentResult.failedChunkCount(),
                enrichmentResult.partial()
        );

        return enrichmentResult;
    }

    /**
     * Persist using the DICE SPI; preserve input order because the DICE
     * persistence result and structural edge wiring are positional.
     *
     * On normal completion or a repository error, all submitted saves are joined
     * before returning or raising a failure. On interruption, pending futures
     * are cancelled; cancellation of an in-flight database write is best effort.
     */
    private List<Proposition> persistPropositions(
            List<Proposition> propositions, String documentId) {
        if (propositions.isEmpty()) {
            return List.of();
        }

        // Precompute remote embeddings before any per-proposition Neo4j write.
        // This remains per-document, never a cross-tenant or global text cache.
        DiceBatchEmbeddingCoordinator.PreparedBatch prepared =
                properties.propositionBatchEmbeddingEnabled()
                ? batchEmbeddingCoordinator.precompute(
                        propositions, properties.propositionEmbeddingBatchSize(), documentId)
                : null;

        if (properties.propositionPersistenceThreads() == 1
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            List<Proposition> saved = new ArrayList<>(propositions.size());
            for (int i = 0; i < propositions.size(); i++) {
                long started = System.nanoTime();
                saved.add(saveProposition(propositions.get(i), prepared));
                logSlowSave(documentId, i, started);
            }
            batchEmbeddingCoordinator.logUsage(prepared, documentId);
            return saved;
        }

        List<Future<PropositionSave>> futures = new ArrayList<>(propositions.size());
        for (int i = 0; i < propositions.size(); i++) {
            final int index = i;
            final Proposition proposition = propositions.get(i);
            futures.add(propositionPersistenceExecutor.submit(() -> {
                long started = System.nanoTime();
                Proposition saved = saveProposition(proposition, prepared);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                return new PropositionSave(index, saved, elapsedMs);
            }));
        }

        // Do not fail fast: joining every task prevents writes from continuing
        // after the enclosing enrichment has already been reported as failed.
        List<Proposition> ordered = new ArrayList<>(Collections.nCopies(propositions.size(), null));
        RuntimeException firstFailure = null;
        long maxSaveMs = 0;
        long sumSaveMs = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                PropositionSave result = futures.get(i).get();
                ordered.set(result.index(), result.proposition());
                sumSaveMs += result.durationMs();
                maxSaveMs = Math.max(maxSaveMs, result.durationMs());
                if (result.durationMs() >= 3_000) {
                    logger.warn("Slow DICE proposition save documentId={} index={} durationMs={}",
                            documentId, result.index(), result.durationMs());
                }
            } catch (InterruptedException interrupted) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while persisting DICE propositions for document " + documentId,
                        interrupted);
            } catch (ExecutionException failed) {
                RuntimeException error = new IllegalStateException(
                        "DICE proposition save failed documentId=" + documentId + " index=" + i,
                        failed.getCause());
                if (firstFailure == null) {
                    firstFailure = error;
                } else {
                    firstFailure.addSuppressed(error);
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
        logger.info("DICE proposition save timing documentId={} totalSaveMs={} maxSaveMs={} count={}",
                documentId, sumSaveMs, maxSaveMs, ordered.size());
        batchEmbeddingCoordinator.logUsage(prepared, documentId);
        return ordered;
    }

    private Proposition saveProposition(Proposition proposition,
                                        DiceBatchEmbeddingCoordinator.PreparedBatch prepared) {
        if (prepared == null || proposition.getText().isBlank()) {
            return propositionRepository.save(proposition);
        }
        // DICE still receives its normal save() call. Its embeddingService's
        // embed(String) returns the vector precomputed for this ingestion.
        return batchEmbeddingCoordinator.withPrepared(prepared,
                () -> propositionRepository.save(proposition));
    }

    private void logSlowSave(String documentId, int index, long started) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMs >= 3_000) {
            logger.warn("Slow DICE proposition save documentId={} index={} durationMs={}",
                    documentId, index, elapsedMs);
        }
    }

    private record PropositionSave(int index, Proposition proposition, long durationMs) { }

    private void repairMissingUpdatedEntities(
            PropositionResults results,
            NamedEntityDataRepository namedEntityDataRepository
    ) {
        int missing = 0;
        int overlapWithNew = 0;
        int genuinelyMissing = 0;
        Set<String> newlyMintedIds = new HashSet<>();
        for (var created : results.newEntities()) {
            newlyMintedIds.add(created.getId());
        }

        for (var entity : results.updatedEntities()) {

            if (namedEntityDataRepository.findById(
                    entity.getId()
            ) != null) {
                continue;
            }

            missing++;
            boolean mintedInThisRun = newlyMintedIds.contains(entity.getId());
            if (mintedInThisRun) {
                overlapWithNew++;
            } else {
                genuinelyMissing++;
            }

            logger.warn(
                    "DICE unresolved existing-entity lookup before persist "
                            + "entityId={} name={} mintedInThisRun={}",
                    entity.getId(), entity.getName(), mintedInThisRun
            );

            /*
             * NamedEntityDataRepository.save() is an upsert.
             *
             * DICE PersistablePropositions.persist() subsequently calls
             * update(entity). Pre-materializing the missing entity allows
             * that strict update to retain its normal semantics.
             */
            namedEntityDataRepository.save(entity);
        }

        logger.info(
                "DICE entity persistence preflight "
                        + "newEntities={} updatedEntities={} "
                        + "missingUpdatedEntities={} mintedInThisRun={} genuinelyMissing={}",
                results.newEntities().size(),
                results.updatedEntities().size(),
                missing, overlapWithNew, genuinelyMissing
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
            int relationshipCount,
            int totalChunkCount,
            int failedChunkCount
    ) {

        public int successfulChunkCount() {
            return totalChunkCount - failedChunkCount;
        }

        public boolean partial() {
            return failedChunkCount > 0;
        }
    }
}