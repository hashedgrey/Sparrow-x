package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.BatchedExtractionStrategy;
import com.embabel.dice.pipeline.ExtractionExecutionStrategy;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.projection.graph.GraphProjectionService;
import com.embabel.dice.projection.graph.GraphProjector;
import com.embabel.dice.projection.graph.GraphRelationshipPersister;
import com.embabel.dice.projection.graph.NamedEntityDataRepositoryGraphRelationshipPersister;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.projection.lineage.ProjectionRecordStore;
import com.embabel.dice.projection.lineage.RepositoryBackedReconciler;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.sparrowx.document.config.DiceIngestionProperties;
import com.sparrowx.document.observability.ContextPropagatingExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(
        DiceIngestionProperties.class
)
public class DiceIngestionConfiguration {

    @Bean
    public DataDictionary diceDataDictionary() {

        return DataDictionary.fromDomainTypes(
                "sparrowx-document",
                Collections.emptyList()
        );
    }

    @Bean(
            name = "diceExtractionExecutor",
            destroyMethod = "shutdown"
    )
    public ExecutorService diceExtractionExecutor(
            DiceIngestionProperties properties
    ) {
        return new ContextPropagatingExecutorService(
                Executors.newFixedThreadPool(
                        properties.extractionThreads()
                )
        );
    }

    @Bean(destroyMethod = "close")
    public BatchedExtractionStrategy diceExtractionExecutionStrategy(
            @Qualifier("diceExtractionExecutor")
            ExecutorService diceExtractionExecutor,
            DiceIngestionProperties properties
    ) {
        return new BatchedExtractionStrategy(
                properties.extractionBatchSize(),
                diceExtractionExecutor
        );
    }

    @Bean
    public Relations diceRelations() {

        return Relations.empty()
                .withSemantic(
                        "depends on",
                        "one entity depends on another"
                )
                .withSemantic(
                        "part of",
                        "one entity is a component or member of another"
                )
                .withSemantic(
                        "uses",
                        "one entity uses another"
                )
                .withSemantic(
                        "requires",
                        "one entity requires another"
                )
                .withSemantic(
                        "implements",
                        "one entity implements another concept, interface, process or requirement"
                )
                .withSemantic(
                        "owns",
                        "one entity owns or is responsible for another"
                )
                .withSemantic(
                        "produces",
                        "one entity produces another"
                )
                .withSemantic(
                        "causes",
                        "one entity causes or contributes to another"
                )
                .withSemantic(
                        "mitigates",
                        "one entity mitigates or reduces another"
                )
                .withSemantic(
                        "references",
                        "one entity references or points to another"
                )
                .withSemantic(
                        "runs on",
                        "one entity runs or executes on another"
                )
                .withSemantic(
                        "related to",
                        "the entities have a meaningful relationship not covered by a more specific relation"
                )
                .withSemantic(
                        "contains",
                        "one entity contains another as a component, member, field, variable or subpart"
                );
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
            PropositionRepository propositionRepository,
            DiceIngestionProperties properties
    ) {

        return LlmPropositionExtractor
                .withLlm(
                        LlmOptions.withDefaultLlm()
                )
                .withAi(ai)
                .withSchemaAdherence(
                        properties.schemaAdherence()
                )
                .withTemplate(
                        "extract_propositions"
                )
                .withPropositionRepository(
                        propositionRepository
                )
                .withExistingPropositionsToShow(
                        properties.existingPropositionsToShow()
                );
    }

    @Bean
    public PropositionPipeline dicePropositionPipeline(
            LlmPropositionExtractor dicePropositionExtractor,
            ExtractionExecutionStrategy diceExtractionExecutionStrategy
    ) {

        return PropositionPipeline
                .withExtractor(
                        dicePropositionExtractor
                )
                .withExecutionStrategy(
                        diceExtractionExecutionStrategy
                );
    }

    /*
     * Graph projection is deliberately deterministic.
     *
     * Proposition extraction is the semantic/LLM-heavy stage.
     *
     * Do not send every unmatched proposition through another LLM call.
     * RelationBasedGraphProjector matches the proposition text against
     * SparrowX's known relation vocabulary and skips relationships for
     * which no deterministic predicate exists.
     *
     * This removes the promote-relationship LLM amplification that was
     * causing a six-page document to generate many additional LLM calls.
     */
    @Bean
    public GraphProjector diceGraphProjector(
            Relations diceRelations
    ) {

        return RelationBasedGraphProjector
                .from(diceRelations)
                .withLenientPolicy();
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