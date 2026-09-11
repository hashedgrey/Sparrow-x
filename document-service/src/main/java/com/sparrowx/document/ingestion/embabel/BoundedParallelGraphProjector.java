package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.projection.graph.GraphProjector;
import com.embabel.dice.projection.graph.ProjectedRelationship;
import com.embabel.dice.proposition.ProjectionResult;
import com.embabel.dice.proposition.ProjectionResults;
import com.embabel.dice.proposition.Proposition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public final class BoundedParallelGraphProjector
        implements GraphProjector {

    private final GraphProjector delegate;
    private final ExecutorService executor;
    private final int batchSize;

    public BoundedParallelGraphProjector(
            GraphProjector delegate,
            ExecutorService executor,
            int batchSize
    ) {
        this.delegate =
                Objects.requireNonNull(delegate, "delegate must not be null");

        this.executor =
                Objects.requireNonNull(executor, "executor must not be null");

        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize must be positive"
            );
        }

        this.batchSize = batchSize;
    }

    @Override
    public ProjectionResult<ProjectedRelationship> project(
            Proposition proposition,
            DataDictionary schema
    ) {
        return delegate.project(
                proposition,
                schema
        );
    }

    @Override
    public ProjectionResults<ProjectedRelationship> projectAll(
            List<Proposition> propositions,
            DataDictionary schema
    ) {
        List<ProjectionResult<ProjectedRelationship>> results =
                new ArrayList<>(propositions.size());

        for (int start = 0;
             start < propositions.size();
             start += batchSize) {

            int end =
                    Math.min(
                            start + batchSize,
                            propositions.size()
                    );

            List<CompletableFuture<ProjectionResult<ProjectedRelationship>>> futures =
                    propositions
                            .subList(start, end)
                            .stream()
                            .map(proposition ->
                                    CompletableFuture.supplyAsync(
                                            () -> delegate.project(
                                                    proposition,
                                                    schema
                                            ),
                                            executor
                                    )
                            )
                            .toList();

            /*
             * Join in proposition order so ProjectionResults retains
             * deterministic ordering even though calls execute concurrently.
             */
            for (var future : futures) {
                results.add(
                        future.join()
                );
            }
        }

        return new ProjectionResults<>(
                results
        );
    }
}