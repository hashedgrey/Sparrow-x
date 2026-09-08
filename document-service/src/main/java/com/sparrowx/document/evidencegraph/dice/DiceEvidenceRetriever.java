package com.sparrowx.document.evidencegraph.dice;

import com.embabel.dice.projection.lineage.ProjectionLifecycle;
import com.embabel.dice.projection.lineage.ProjectionRecord;
import com.embabel.dice.projection.lineage.ProjectionRecordStore;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.sparrowx.document.domain.models.SourceSpan;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DiceEvidenceRetriever {

    private static final int DEFAULT_PROPOSITION_LIMIT = 10;

    private final PropositionRepository propositionRepository;
    private final ProjectionRecordStore projectionRecordStore;

    public DiceEvidenceRetriever(
            PropositionRepository propositionRepository,
            ProjectionRecordStore projectionRecordStore
    ) {
        this.propositionRepository =
                Objects.requireNonNull(
                        propositionRepository,
                        "propositionRepository must not be null"
                );

        this.projectionRecordStore =
                Objects.requireNonNull(
                        projectionRecordStore,
                        "projectionRecordStore must not be null"
                );
    }

    public RetrievalResult retrieve(
            BuildDocumentEvidenceCommand command,
            List<SourceSpan> sourcePool
    ) {
        if (command == null
                || command.tenantId() == null
                || sourcePool == null
                || sourcePool.isEmpty()) {

            return new RetrievalResult(
                    List.of(),
                    Map.of(),
                    List.of()
            );
        }

        String tenantId = command.tenantId().value();

        /*
         * Preserve SourceSpan retrieval order.
         *
         * The source pool is already:
         *   tenant scoped
         *   document scoped
         *   ranked by ES/Qdrant relevance
         *
         * Therefore the chunk grounding lookup becomes the safe bridge from
         * lexical/vector retrieval into persisted DICE semantics.
         */
        Map<String, Proposition> propositionsById =
                new LinkedHashMap<>();

        sourcePool.stream()
                .filter(Objects::nonNull)
                .map(SourceSpan::chunkId)
                .filter(Objects::nonNull)
                .map(chunkId -> chunkId.value())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .forEach(chunkId ->
                        propositionRepository
                                .findByGrounding(chunkId)
                                .stream()
                                .filter(Objects::nonNull)
                                .filter(proposition ->
                                        tenantId.equals(
                                                proposition.getContextIdValue()
                                        )
                                )
                                .filter(this::usable)
                                .forEach(proposition ->
                                        propositionsById.putIfAbsent(
                                                proposition.getId(),
                                                proposition
                                        )
                                )
                );

        int propositionLimit =
                normalizeLimit(command.limit());

        List<Proposition> propositions =
                propositionsById
                        .values()
                        .stream()
                        .limit(propositionLimit)
                        .toList();

        if (propositions.isEmpty()) {
            return new RetrievalResult(
                    List.of(),
                    Map.of(),
                    List.of(
                            "No persisted DICE propositions matched the retrieved source chunks."
                    )
            );
        }

        Map<String, List<ProjectionRecord>> lineageByProposition =
                new LinkedHashMap<>();

        int propositionsWithoutGraphProjection = 0;

        for (Proposition proposition : propositions) {

            List<ProjectionRecord> records =
                    projectionRecordStore
                            .findByProposition(proposition.getId())
                            .stream()
                            .filter(Objects::nonNull)
                            .filter(record ->
                                    tenantId.equals(record.getContextId())
                            )
                            .filter(record ->
                                    "neo4j".equalsIgnoreCase(
                                            record.getTarget()
                                    )
                            )
                            .filter(this::usableProjection)
                            .filter(record ->
                                    record.getTargetRef() != null
                                            && !record.getTargetRef().isBlank()
                            )
                            /*
                             * Re-ingestion/recovery may have generated more
                             * than one lineage row for the same edge.
                             */
                            .collect(
                                    LinkedHashMap<String, ProjectionRecord>::new,
                                    (map, record) ->
                                            map.putIfAbsent(
                                                    record.getTargetRef(),
                                                    record
                                            ),
                                    LinkedHashMap::putAll
                            )
                            .values()
                            .stream()
                            .toList();

            if (records.isEmpty()) {
                propositionsWithoutGraphProjection++;
            }

            lineageByProposition.put(
                    proposition.getId(),
                    records
            );
        }

        List<String> warnings = new ArrayList<>();

        if (propositionsWithoutGraphProjection > 0) {
            warnings.add(
                    "DICE propositions without persisted Neo4j projection lineage: "
                            + propositionsWithoutGraphProjection
            );
        }

        return new RetrievalResult(
                propositions,
                lineageByProposition,
                warnings
        );
    }

    private boolean usable(Proposition proposition) {
        if (proposition == null || proposition.getStatus() == null) {
            return false;
        }

        return proposition.getStatus() == PropositionStatus.ACTIVE
                || proposition.getStatus() == PropositionStatus.PROMOTED;
    }

    private boolean usableProjection(ProjectionRecord record) {
        if (record == null || record.getLifecycle() == null) {
            return false;
        }

        return record.getLifecycle() == ProjectionLifecycle.PROJECTED
                || record.getLifecycle() == ProjectionLifecycle.ADOPTED;
    }

    private int normalizeLimit(int limit) {
        return limit <= 0
                ? DEFAULT_PROPOSITION_LIMIT
                : Math.max(1, limit);
    }

    public record RetrievalResult(
            List<Proposition> propositions,
            Map<String, List<ProjectionRecord>>
            projectionRecordsByProposition,
            List<String> warnings
    ) {
        public RetrievalResult {
            propositions =
                    propositions == null
                            ? List.of()
                            : List.copyOf(propositions);

            Map<String, List<ProjectionRecord>> safeRecords =
                    new LinkedHashMap<>();

            if (projectionRecordsByProposition != null) {
                projectionRecordsByProposition.forEach(
                        (key, value) ->
                                safeRecords.put(
                                        key,
                                        value == null
                                                ? List.of()
                                                : List.copyOf(value)
                                )
                );
            }

            projectionRecordsByProposition =
                    Map.copyOf(safeRecords);

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(warnings);
        }
    }
}