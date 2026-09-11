package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.projection.graph.GraphProjector;
import com.embabel.dice.projection.graph.ProjectedRelationship;
import com.embabel.dice.proposition.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HybridGraphProjector implements GraphProjector {

    private static final Logger log =
            LoggerFactory.getLogger(HybridGraphProjector.class);

    private final GraphProjector deterministicProjector;
    private final GraphProjector llmFallbackProjector;

    public HybridGraphProjector(
            GraphProjector deterministicProjector,
            GraphProjector llmFallbackProjector
    ) {
        this.deterministicProjector =
                Objects.requireNonNull(
                        deterministicProjector,
                        "deterministicProjector must not be null"
                );

        this.llmFallbackProjector =
                Objects.requireNonNull(
                        llmFallbackProjector,
                        "llmFallbackProjector must not be null"
                );
    }

    @Override
    public ProjectionResult<ProjectedRelationship> project(
            Proposition proposition,
            DataDictionary schema
    ) {
        ProjectionResult<ProjectedRelationship> deterministic =
                deterministicProjector.project(
                        proposition,
                        schema
                );

        if (shouldFallback(deterministic)) {
            return llmFallbackProjector.project(
                    proposition,
                    schema
            );
        }

        return deterministic;
    }

    @Override
    public ProjectionResults<ProjectedRelationship> projectAll(
            List<Proposition> propositions,
            DataDictionary schema
    ) {
        /*
         * Keep one result slot per original proposition.
         *
         * This lets us run the LLM fallback as one bounded-parallel
         * batch while restoring results into the original ordering.
         */
        List<ProjectionResult<ProjectedRelationship>> merged =
                new ArrayList<>(propositions.size());

        for (int i = 0; i < propositions.size(); i++) {
            merged.add(null);
        }

        List<Proposition> fallbackPropositions =
                new ArrayList<>();

        List<Integer> fallbackPositions =
                new ArrayList<>();

        int deterministicSuccesses = 0;
        int deterministicSkipped = 0;
        int deterministicNoMatch  = 0;

        /*
         * Pass 1:
         *
         * Try the zero-LLM deterministic projector first.
         */
        for (int i = 0; i < propositions.size(); i++) {

            Proposition proposition =
                    propositions.get(i);

            ProjectionResult<ProjectedRelationship> result =
                    deterministicProjector.project(
                            proposition,
                            schema
                    );

            Object raw = result;

            if (raw instanceof ProjectionSkipped) {
                deterministicSkipped++;
            } else if (raw instanceof ProjectionFailed) {
                deterministicNoMatch ++;
            } else {
                deterministicSuccesses++;
            }

            if (shouldFallback(result)) {

                fallbackPositions.add(i);
                fallbackPropositions.add(proposition);

            } else {

                merged.set(i, result);
            }
        }

        /*
         * Pass 2:
         *
         * Only predicates that the deterministic projector does
         * not recognize are sent through LLM graph projection.
         *
         * llmFallbackProjector.projectAll() is expected to be the
         * BoundedParallelGraphProjector, so these calls remain
         * concurrency-limited.
         */
        if (!fallbackPropositions.isEmpty()) {

            ProjectionResults<ProjectedRelationship> fallback =
                    llmFallbackProjector.projectAll(
                            fallbackPropositions,
                            schema
                    );

            List<ProjectionResult<ProjectedRelationship>> fallbackResults =
                    fallback.getResults();

            if (fallbackResults.size() != fallbackPositions.size()) {
                throw new IllegalStateException(
                        "LLM graph fallback returned "
                                + fallbackResults.size()
                                + " results for "
                                + fallbackPositions.size()
                                + " propositions"
                );
            }

            for (int i = 0; i < fallbackResults.size(); i++) {

                int originalPosition =
                        fallbackPositions.get(i);

                merged.set(
                        originalPosition,
                        fallbackResults.get(i)
                );
            }
        }

        log.info(
                "Hybrid graph projection complete propositions={} deterministicSuccesses={} deterministicSkipped={} deterministicNoMatch ={} llmFallbacks={}",
                propositions.size(),
                deterministicSuccesses,
                deterministicSkipped,
                deterministicNoMatch ,
                fallbackPropositions.size()
        );

        return new ProjectionResults<>(
                merged
        );
    }

    private boolean shouldFallback(
            ProjectionResult<ProjectedRelationship> result
        ) {
        Object raw = result;

        if (raw instanceof ProjectionFailed failed) {
            ProjectionFailureReason reason = failed.getStructuredReason();
            return reason instanceof ProjectionFailureReason.NoMatchingPredicate;
        }

        if (raw instanceof ProjectionSkipped skipped) {
            ProjectionFailureReason reason = skipped.getStructuredReason();
            return reason instanceof ProjectionFailureReason.NoMatchingPredicate;
        }

        return false;
    }
}