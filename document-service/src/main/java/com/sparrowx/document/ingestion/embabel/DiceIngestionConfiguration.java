package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.SchemaAdherence;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.projection.graph.GraphProjectionService;
import com.embabel.dice.projection.graph.GraphProjector;
import com.embabel.dice.projection.graph.GraphRelationshipPersister;
import com.embabel.dice.projection.graph.LlmGraphProjector;
import com.embabel.dice.projection.graph.NamedEntityDataRepositoryGraphRelationshipPersister;
import com.embabel.dice.projection.lineage.ProjectionRecordStore;
import com.embabel.dice.projection.lineage.RepositoryBackedReconciler;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

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
            LlmPropositionExtractor dicePropositionExtractor
    ) {
        return PropositionPipeline.withExtractor(
                dicePropositionExtractor
        );
    }

    @Bean
    public GraphProjector diceGraphProjector(
            Ai ai,
            Relations diceRelations
    ) {
        /*
         * Second and final generative operation in document-service.
         *
         * Still ingestion-time only:
         *
         * Proposition
         *      ↓
         * classify relationship
         *      ↓
         * persisted semantic graph edge
         */
        return LlmGraphProjector
                .withLlm(
                        LlmOptions.withDefaultLlm()
                )
                .withAi(ai)
                .withRelations(
                        diceRelations
                )
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