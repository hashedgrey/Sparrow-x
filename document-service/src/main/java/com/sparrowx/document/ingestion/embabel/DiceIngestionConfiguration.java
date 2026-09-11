package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.SchemaAdherence;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.BatchedExtractionStrategy;
import com.embabel.dice.pipeline.ExtractionExecutionStrategy;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.projection.graph.*;
import com.embabel.dice.projection.lineage.ProjectionRecordStore;
import com.embabel.dice.projection.lineage.RepositoryBackedReconciler;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.sparrowx.document.observability.ContextPropagatingExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DiceIngestionConfiguration {

    @Bean
    public DataDictionary diceDataDictionary() {
        /*
         * Document Service is domain-generic.
         *
         * Entity types are discovered from uploaded company documents rather
         * than constrained to a fixed Java domain model.
         */
        return DataDictionary.fromDomainTypes(
                "sparrowx-document",
                Collections.emptyList()
        );
    }

    @Bean(
            name = "diceExtractionExecutor",
            destroyMethod = "shutdown"
    )
    public ExecutorService diceExtractionExecutor() {
        return new ContextPropagatingExecutorService(
                Executors.newFixedThreadPool(4)
        );
    }

    @Bean(destroyMethod = "close")
    public BatchedExtractionStrategy diceExtractionExecutionStrategy(
            @Qualifier("diceExtractionExecutor")
            ExecutorService diceExtractionExecutor
    ) {
        return new BatchedExtractionStrategy(
                4,
                diceExtractionExecutor
        );
    }
    @Bean
    public Relations diceRelations() {
        /*
         * Initial graph relationship vocabulary for engineering/company
         * documents.
         *
         * These are DICE relations, not SparrowX custom edge classes.
         */
        return Relations.empty()
                .withSemantic("depends on", "one entity depends on another")
                .withSemantic("part of", "one entity is a component or member of another")
                .withSemantic("uses", "one entity uses another")
                .withSemantic("requires", "one entity requires another")
                .withSemantic("implements", "one entity implements another concept, interface, process or requirement")
                .withSemantic("owns", "one entity owns or is responsible for another")
                .withSemantic("produces", "one entity produces another")
                .withSemantic("causes", "one entity causes or contributes to another")
                .withSemantic("mitigates", "one entity mitigates or reduces another")
                .withSemantic("references", "one entity references or points to another")
                .withSemantic("runs on", "one entity runs or executes on another")
                .withSemantic("related to", "the entities have a meaningful relationship not covered by a more specific relation")
                .withSemantic("contains", "one entity contains another as a component, member, field, variable or subpart");
    }

    @Bean
    public EntityResolver diceEntityResolver(
            NamedEntityDataRepository namedEntityDataRepository
    ) {
        return EscalatingEntityResolver.create(
                namedEntityDataRepository,
                null
        );
    }

    @Bean
    public LlmPropositionExtractor dicePropositionExtractor(
            Ai ai,
            PropositionRepository propositionRepository
    ) {
        return LlmPropositionExtractor.withLlm(
                LlmOptions.withDefaultLlm())
                .withAi(ai)
                .withSchemaAdherence(SchemaAdherence.RELAXED)
                .withTemplate("extract_propositions")
                .withPropositionRepository(propositionRepository)
                .withExistingPropositionsToShow(25);
    }

    @Bean
    public PropositionPipeline dicePropositionPipeline(
            LlmPropositionExtractor dicePropositionExtractor,
            ExtractionExecutionStrategy diceExtractionExecutionStrategy
    ) {
        return PropositionPipeline
                .withExtractor(dicePropositionExtractor)
                .withExecutionStrategy(
                        diceExtractionExecutionStrategy
                );
    }

    @Bean(name = "diceGraphProjectionExecutor", destroyMethod = "shutdown")
    public ExecutorService diceGraphProjectionExecutor() {
        return new ContextPropagatingExecutorService(
                Executors.newFixedThreadPool(4)
        );
    }
    @Bean
    public GraphProjector diceGraphProjector(
            Ai ai,
            Relations diceRelations,
            @Qualifier("diceGraphProjectionExecutor")
            ExecutorService diceGraphProjectionExecutor
    ) {
        GraphProjector deterministic =
                RelationBasedGraphProjector
                        .from(diceRelations)
                        .withLenientPolicy();

        GraphProjector llm =
                LlmGraphProjector.withLlm(LlmOptions.withDefaultLlm()).withAi(ai)
                        .withRelations(diceRelations)
                        .withLenientPolicy();

        GraphProjector boundedLlm =
                new BoundedParallelGraphProjector(
                        llm,
                        diceGraphProjectionExecutor,
                        4
                );

        return new HybridGraphProjector(
                deterministic,
                boundedLlm
        );
    }

    @Bean
    public GraphRelationshipPersister diceGraphRelationshipPersister(
            NamedEntityDataRepository namedEntityDataRepository
    ) {
        return new NamedEntityDataRepositoryGraphRelationshipPersister(
                namedEntityDataRepository
        );
    }

    @Bean
    public GraphProjectionService diceGraphProjectionService(
            GraphProjector diceGraphProjector,
            GraphRelationshipPersister diceGraphRelationshipPersister,
            @Qualifier("diceDataDictionary")
            DataDictionary diceDataDictionary,
            ProjectionRecordStore projectionRecordStore,
            NamedEntityDataRepository namedEntityDataRepository
    ) {
        return GraphProjectionService.create(
                diceGraphProjector,
                diceGraphRelationshipPersister,
                diceDataDictionary,
                projectionRecordStore,
                new RepositoryBackedReconciler(
                        namedEntityDataRepository
                )
        );
    }
}