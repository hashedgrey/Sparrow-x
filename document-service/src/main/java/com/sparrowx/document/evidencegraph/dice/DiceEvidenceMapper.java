package com.sparrowx.document.evidencegraph.dice;

import com.embabel.dice.projection.lineage.ProjectionRecord;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.Proposition;
import com.sparrowx.document.domain.models.DocumentEvidenceEdge;
import com.sparrowx.document.domain.models.DocumentEvidenceNode;
import com.sparrowx.document.domain.models.SourceSpan;
import com.sparrowx.document.domain.valueobjects.VerificationStatus;
import com.sparrowx.document.features.builddocumentevidence.BuildDocumentEvidenceCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class DiceEvidenceMapper {

    public MappingResult map(
            BuildDocumentEvidenceCommand command,
            List<SourceSpan> sourcePool,
            DiceEvidenceRetriever.RetrievalResult retrievalResult
    ) {
        if (retrievalResult == null
                || retrievalResult.propositions().isEmpty()) {

            return new MappingResult(
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        Map<String, List<SourceSpan>> spansByChunkId =
                indexSourceSpans(sourcePool);

        List<DocumentEvidenceNode> claimNodes =
                new ArrayList<>();

        Map<String, EntityAccumulator> entityAccumulators =
                new LinkedHashMap<>();

        Map<String, List<String>> sourceSpanIdsByProposition =
                new LinkedHashMap<>();

        /*
         * Pass 1:
         *   proposition -> CLAIM
         *   resolved mentions -> accumulated ENTITY nodes
         */
        for (Proposition proposition :
                retrievalResult.propositions()) {

            List<String> sourceSpanIds =
                    sourceSpanIds(
                            proposition,
                            spansByChunkId
                    );

            sourceSpanIdsByProposition.put(
                    proposition.getId(),
                    sourceSpanIds
            );

            claimNodes.add(
                    propositionNode(
                            command,
                            proposition,
                            sourceSpanIds
                    )
            );

            for (EntityMention mention :
                    proposition.getMentions()) {

                if (!resolved(mention)) {
                    continue;
                }

                entityAccumulators
                        .computeIfAbsent(
                                mention.getResolvedId(),
                                EntityAccumulator::new
                        )
                        .accept(
                                mention,
                                sourceSpanIds,
                                proposition.getConfidence()
                        );
            }
        }

        List<DocumentEvidenceNode> entityNodes =
                entityAccumulators
                        .values()
                        .stream()
                        .map(accumulator ->
                                accumulator.toNode(command)
                        )
                        .toList();

        List<DocumentEvidenceNode> nodes =
                new ArrayList<>(
                        claimNodes.size()
                                + entityNodes.size()
                );

        nodes.addAll(claimNodes);
        nodes.addAll(entityNodes);

        Set<String> availableEntityIds =
                new LinkedHashSet<>(
                        entityAccumulators.keySet()
                );

        /*
         * Pass 2:
         *   persisted GraphProjectionService lineage
         *   -> exact DICE entity relationship
         */
        Map<String, DocumentEvidenceEdge> edgesByKey =
                new LinkedHashMap<>();

        int malformedProjectionRefs = 0;
        int projectionsMissingEndpoints = 0;

        for (Proposition proposition :
                retrievalResult.propositions()) {

            List<ProjectionRecord> records =
                    retrievalResult
                            .projectionRecordsByProposition()
                            .getOrDefault(
                                    proposition.getId(),
                                    List.of()
                            );

            List<String> sourceSpanIds =
                    sourceSpanIdsByProposition
                            .getOrDefault(
                                    proposition.getId(),
                                    List.of()
                            );

            for (ProjectionRecord record : records) {

                ParsedEdge parsed =
                        parseEdgeRef(
                                record.getTargetRef()
                        );

                if (parsed == null) {
                    malformedProjectionRefs++;
                    continue;
                }

                if (!availableEntityIds.contains(parsed.sourceId())
                        || !availableEntityIds.contains(parsed.targetId())) {

                    projectionsMissingEndpoints++;
                    continue;
                }

                RelationMapping relationMapping =
                        mapRelation(parsed.type());

                if (!relationAllowed(
                        command,
                        relationMapping.relationType()
                )) {
                    continue;
                }

                DocumentEvidenceEdge edge =
                        new DocumentEvidenceEdge(
                                stableEdgeId(
                                        proposition.getId(),
                                        parsed
                                ),
                                entityNodeId(
                                        parsed.sourceId()
                                ),
                                entityNodeId(
                                        parsed.targetId()
                                ),
                                relationMapping.relationType(),
                                relationMapping.customRelationType(),
                                truncate(
                                        normalizeWhitespace(
                                                proposition.getText()
                                        ),
                                        260
                                ),
                                sourceSpanIds,
                                bounded(
                                        proposition.getConfidence()
                                ),
                                List.of(),
                                Map.of(
                                        "dice_proposition_id",
                                        proposition.getId(),
                                        "dice_relation_type",
                                        parsed.type(),
                                        "projection_source",
                                        "dice",
                                        "projection_target",
                                        "neo4j"
                                )
                        );

                String key =
                        parsed.sourceId()
                                + "::"
                                + parsed.type()
                                + "::"
                                + parsed.targetId();

                edgesByKey.putIfAbsent(
                        key,
                        edge
                );
            }
        }

        List<String> warnings =
                new ArrayList<>();

        warnings.add(
                "Used persisted DICE propositions and graph projection lineage for semantic evidence."
        );

        if (malformedProjectionRefs > 0) {
            warnings.add(
                    "Ignored malformed DICE graph projection references: "
                            + malformedProjectionRefs
            );
        }

        if (projectionsMissingEndpoints > 0) {
            warnings.add(
                    "Ignored DICE graph projections whose entity endpoints were not present in selected propositions: "
                            + projectionsMissingEndpoints
            );
        }

        if (edgesByKey.isEmpty()) {
            warnings.add(
                    "DICE propositions were found, but no usable persisted semantic relationship edges were mapped."
            );
        }

        return new MappingResult(
                nodes,
                new ArrayList<>(
                        edgesByKey.values()
                ),
                warnings
        );
    }

    private DocumentEvidenceNode propositionNode(
            BuildDocumentEvidenceCommand command,
            Proposition proposition,
            List<String> sourceSpanIds
    ) {
        String text =
                normalizeWhitespace(
                        proposition.getText()
                );

        return new DocumentEvidenceNode(
                propositionNodeId(
                        proposition.getId()
                ),
                DocumentEvidenceNode.EvidenceNodeType.CLAIM,
                "",
                truncate(text, 90),
                truncate(text, 220),
                truncate(text, 260),
                sourceSpanIds,
                VerificationStatus.UNVERIFIED,
                bounded(
                        proposition.getConfidence()
                ),
                sourceSpanIds.isEmpty()
                        ? 0.0
                        : 1.0,
                sourceSpanIds.isEmpty(),
                propositionTags(
                        command,
                        proposition
                ),
                List.of(),
                Map.of(
                        "dice_proposition_id",
                        proposition.getId(),
                        "dice_context_id",
                        proposition.getContextIdValue(),
                        "dice_status",
                        proposition.getStatus().name(),
                        "normalization_source",
                        "dice_proposition"
                )
        );
    }

    private List<String> propositionTags(
            BuildDocumentEvidenceCommand command,
            Proposition proposition
    ) {
        Stream<String> base =
                Stream.of(
                        "dice",
                        "proposition",
                        "claim"
                );

        Stream<String> mentionTypes =
                proposition
                        .getMentions()
                        .stream()
                        .map(EntityMention::getType);

        Stream<String> buildContextTags =
                command == null
                        || command.buildContext() == null
                        ? Stream.empty()
                        : Stream.concat(
                        command.buildContext()
                                .topics()
                                .stream(),
                        command.buildContext()
                                .keywords()
                                .stream()
                );

        return Stream
                .of(
                        base,
                        mentionTypes,
                        buildContextTags
                )
                .flatMap(stream -> stream)
                .filter(value ->
                        value != null
                                && !value.isBlank()
                )
                .map(value ->
                        value
                                .trim()
                                .toLowerCase(Locale.ROOT)
                )
                .distinct()
                .limit(12)
                .toList();
    }

    private Map<String, List<SourceSpan>>
    indexSourceSpans(
            List<SourceSpan> sourcePool
    ) {
        Map<String, List<SourceSpan>> result =
                new LinkedHashMap<>();

        if (sourcePool == null) {
            return result;
        }

        for (SourceSpan span : sourcePool) {

            if (span == null
                    || span.chunkId() == null
                    || span.chunkId().value() == null
                    || span.chunkId().value().isBlank()) {

                continue;
            }

            result.computeIfAbsent(
                            span.chunkId().value(),
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(span);
        }

        return result;
    }

    private List<String> sourceSpanIds(
            Proposition proposition,
            Map<String, List<SourceSpan>>
                    spansByChunkId
    ) {
        if (proposition == null
                || proposition.getGrounding() == null
                || proposition.getGrounding().isEmpty()) {

            return List.of();
        }

        return proposition
                .getGrounding()
                .stream()
                .flatMap(chunkId ->
                        spansByChunkId
                                .getOrDefault(
                                        chunkId,
                                        List.of()
                                )
                                .stream()
                )
                .map(SourceSpan::sourceSpanId)
                .filter(value ->
                        value != null
                                && !value.isBlank()
                )
                .distinct()
                .toList();
    }

    private boolean resolved(
            EntityMention mention
    ) {
        return mention != null
                && mention.getResolvedId() != null
                && !mention.getResolvedId().isBlank();
    }

    /*
     * DICE GraphProjectionService stores:
     *
     *     sourceId-[RELATION_TYPE]->targetId
     *
     * as ProjectionRecord.targetRef.
     */
    private ParsedEdge parseEdgeRef(
            String edgeRef
    ) {
        if (edgeRef == null || edgeRef.isBlank()) {
            return null;
        }

        int relationStart =
                edgeRef.indexOf("-[");

        if (relationStart <= 0) {
            return null;
        }

        int relationEnd =
                edgeRef.indexOf(
                        "]->",
                        relationStart + 2
                );

        if (relationEnd < 0) {
            return null;
        }

        String sourceId =
                edgeRef
                        .substring(
                                0,
                                relationStart
                        )
                        .trim();

        String type =
                edgeRef
                        .substring(
                                relationStart + 2,
                                relationEnd
                        )
                        .trim();

        String targetId =
                edgeRef
                        .substring(
                                relationEnd + 3
                        )
                        .trim();

        if (sourceId.isBlank()
                || type.isBlank()
                || targetId.isBlank()) {

            return null;
        }

        return new ParsedEdge(
                sourceId,
                type,
                targetId
        );
    }

    /*
     * SUPPORTS / CONTRADICTS / MODIFIES are evidence judgments.
     *
     * A DICE domain relationship such as USES or PART_OF must not be
     * silently converted into one of those evidence semantics.
     */
    private RelationMapping mapRelation(
            String diceType
    ) {
        String normalized =
                normalizeRelationType(diceType);

        if ("DEPENDS_ON".equals(normalized)) {
            return new RelationMapping(
                    DocumentEvidenceEdge
                            .EvidenceRelationType
                            .DEPENDS_ON,
                    ""
            );
        }

        if ("SIMILAR_TO".equals(normalized)) {
            return new RelationMapping(
                    DocumentEvidenceEdge
                            .EvidenceRelationType
                            .SIMILAR_TO,
                    ""
            );
        }

        return new RelationMapping(
                DocumentEvidenceEdge
                        .EvidenceRelationType
                        .CUSTOM,
                normalized.isBlank()
                        ? "DICE_RELATION"
                        : normalized
        );
    }

    private boolean relationAllowed(
            BuildDocumentEvidenceCommand command,
            DocumentEvidenceEdge.EvidenceRelationType relationType
    ) {
        if (relationType == null
                || relationType
                == DocumentEvidenceEdge
                .EvidenceRelationType
                .UNSPECIFIED) {

            return false;
        }

        if (command == null
                || command.spec() == null
                || command.spec()
                .requestedRelationTypes() == null) {

            return true;
        }

        List<DocumentEvidenceEdge.EvidenceRelationType>
                requested =
                command.spec()
                        .requestedRelationTypes();

        return requested.isEmpty()
                || requested.contains(relationType);
    }

    private String normalizeRelationType(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String propositionNodeId(
            String propositionId
    ) {
        return "dice-proposition:"
                + propositionId;
    }

    private static String entityNodeId(
            String entityId
    ) {
        return "dice-entity:"
                + entityId;
    }

    private String stableEdgeId(
            String propositionId,
            ParsedEdge edge
    ) {
        String identity =
                propositionId
                        + "::"
                        + edge.sourceId()
                        + "::"
                        + edge.type()
                        + "::"
                        + edge.targetId();

        return "dice-edge:"
                + UUID.nameUUIDFromBytes(
                identity.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    private String truncate(
            String value,
            int maxChars
    ) {
        String normalized =
                normalizeWhitespace(value);

        if (normalized.length() <= maxChars) {
            return normalized;
        }

        return normalized.substring(
                0,
                Math.max(
                        0,
                        maxChars - 3
                )
        ) + "...";
    }

    private String normalizeWhitespace(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double bounded(
            double value
    ) {
        if (Double.isNaN(value)
                || Double.isInfinite(value)
                || value < 0.0) {

            return 0.0;
        }

        return Math.min(
                1.0,
                value
        );
    }

    public record MappingResult(
            List<DocumentEvidenceNode> nodes,
            List<DocumentEvidenceEdge> edges,
            List<String> warnings
    ) {
        public MappingResult {
            nodes =
                    nodes == null
                            ? List.of()
                            : List.copyOf(nodes);

            edges =
                    edges == null
                            ? List.of()
                            : List.copyOf(edges);

            warnings =
                    warnings == null
                            ? List.of()
                            : List.copyOf(warnings);
        }

        public boolean hasSemanticEvidence() {
            return !nodes.isEmpty();
        }
    }

    private record ParsedEdge(
            String sourceId,
            String type,
            String targetId
    ) {
    }

    private record RelationMapping(
            DocumentEvidenceEdge.EvidenceRelationType
            relationType,
            String customRelationType
    ) {
    }

    private static final class EntityAccumulator {

        private final String entityId;

        private String name = "";
        private String type = "";

        private double confidence = 0.0;

        private final Set<String> sourceSpanIds =
                new LinkedHashSet<>();

        private EntityAccumulator(
                String entityId
        ) {
            this.entityId = entityId;
        }

        private void accept(
                EntityMention mention,
                List<String> spanIds,
                double propositionConfidence
        ) {
            if (name.isBlank()
                    && mention.getSpan() != null
                    && !mention.getSpan().isBlank()) {

                name = mention.getSpan().trim();
            }

            if (type.isBlank()
                    && mention.getType() != null
                    && !mention.getType().isBlank()) {

                type = mention.getType().trim();
            }

            if (spanIds != null) {
                spanIds.stream()
                        .filter(value ->
                                value != null
                                        && !value.isBlank()
                        )
                        .forEach(
                                sourceSpanIds::add
                        );
            }

            confidence =
                    Math.max(
                            confidence,
                            boundedStatic(
                                    propositionConfidence
                            )
                    );
        }

        private DocumentEvidenceNode toNode(
                BuildDocumentEvidenceCommand command
        ) {
            String displayName =
                    name.isBlank()
                            ? entityId
                            : name;

            List<String> tags =
                    Stream.concat(
                                    Stream.of(
                                            "dice",
                                            "entity",
                                            type
                                    ),
                                    command == null
                                            || command.buildContext() == null
                                            ? Stream.empty()
                                            : command
                                            .buildContext()
                                            .topics()
                                            .stream()
                            )
                            .filter(value ->
                                    value != null
                                            && !value.isBlank()
                            )
                            .map(value ->
                                    value
                                            .trim()
                                            .toLowerCase(
                                                    Locale.ROOT
                                            )
                            )
                            .distinct()
                            .limit(12)
                            .toList();

            return new DocumentEvidenceNode(
                    entityNodeId(entityId),
                    DocumentEvidenceNode
                            .EvidenceNodeType
                            .ENTITY,
                    "",
                    displayName,
                    displayName,
                    displayName,
                    new ArrayList<>(
                            sourceSpanIds
                    ),
                    VerificationStatus.UNVERIFIED,
                    boundedStatic(confidence),
                    sourceSpanIds.isEmpty()
                            ? 0.0
                            : 1.0,
                    sourceSpanIds.isEmpty(),
                    tags,
                    List.of(),
                    Map.of(
                            "dice_entity_id",
                            entityId,
                            "dice_entity_type",
                            type,
                            "normalization_source",
                            "dice_entity"
                    )
            );
        }

        private static double boundedStatic(
                double value
        ) {
            if (Double.isNaN(value)
                    || Double.isInfinite(value)
                    || value < 0.0) {

                return 0.0;
            }

            return Math.min(
                    1.0,
                    value
            );
        }
    }
}