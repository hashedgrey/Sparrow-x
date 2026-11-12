package com.sparrowx.document.retrieval;

import com.sparrowx.document.domain.models.RetrievalEvidence;
import com.sparrowx.document.domain.valueobjects.ChunkId;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.ProjectId;
import com.sparrowx.document.domain.valueobjects.RetrievalMode;
import com.sparrowx.document.domain.valueobjects.SearchQueryText;
import com.sparrowx.document.domain.valueobjects.TeamId;
import com.sparrowx.document.domain.valueobjects.TenantId;
import com.sparrowx.document.domain.valueobjects.UserId;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.exceptions.RetrievalFailedException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HybridDocumentRetriever {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int HYBRID_OVERFETCH_FACTOR = 3;

    private final KeywordDocumentSearcher keywordDocumentSearcher;
    private final VectorDocumentSearcher vectorDocumentSearcher;
    private final RetrievalScoreMerger retrievalScoreMerger;
    private final RetrievalEvidenceBuilder retrievalEvidenceBuilder;
    private final RetrievalDeduplicator retrievalDeduplicator;
    private final ContextAccessFilter contextAccessFilter;
    private final RetrievalPermissionFilter retrievalPermissionFilter;
    private final DocumentReranker documentReranker;
    private final DocumentMetadataLookup documentMetadataLookup;

    public HybridDocumentRetriever(
            KeywordDocumentSearcher keywordDocumentSearcher,
            VectorDocumentSearcher vectorDocumentSearcher,
            RetrievalScoreMerger retrievalScoreMerger,
            RetrievalEvidenceBuilder retrievalEvidenceBuilder,
            RetrievalDeduplicator retrievalDeduplicator,
            ContextAccessFilter contextAccessFilter,
            RetrievalPermissionFilter retrievalPermissionFilter,
            DocumentReranker documentReranker,
            DocumentMetadataLookup documentMetadataLookup
    ) {
        this.keywordDocumentSearcher = keywordDocumentSearcher;
        this.vectorDocumentSearcher = vectorDocumentSearcher;
        this.retrievalScoreMerger = retrievalScoreMerger;
        this.retrievalEvidenceBuilder = retrievalEvidenceBuilder;
        this.retrievalDeduplicator = retrievalDeduplicator;
        this.contextAccessFilter = contextAccessFilter;
        this.retrievalPermissionFilter = retrievalPermissionFilter;
        this.documentReranker = documentReranker;
        this.documentMetadataLookup = documentMetadataLookup;
    }

    public List<RetrievalEvidence> retrieve(RetrieveDocumentsRequest request) {
        validate(request);

        try {
            RetrievalMode mode = normalizeMode(request.mode());
            int limit = normalizeLimit(request.limit());
            Set<DocumentId> scopedDocumentIds = normalizeDocumentIds(request.documentIds());

            RetrieveDocumentsRequest normalizedRequest = new RetrieveDocumentsRequest(
                    request.tenantId(),
                    request.userId(),
                    request.projectId(),
                    request.teamId(),
                    request.query(),
                    limit,
                    mode,
                    scopedDocumentIds
            );

            List<RetrievalEvidence> evidence = switch (mode) {
                case KEYWORD -> keywordOnly(normalizedRequest, limit);
                case VECTOR -> vectorOnly(normalizedRequest, limit);
                case HYBRID -> hybrid(normalizedRequest, limit);
            };

            evidence = contextAccessFilter.filterByDocumentIds(evidence, scopedDocumentIds);

            RetrievalPolicy policy = new RetrievalPolicy(
                    request.tenantId(),
                    request.userId(),
                    request.projectId(),
                    request.teamId(),
                    scopedDocumentIds
            );

            evidence = retrievalPermissionFilter.filter(policy, evidence);
            evidence = retrievalDeduplicator.deduplicateByChunkId(evidence);
            evidence = hydrateDocumentMetadata(request.tenantId(), evidence);

            return documentReranker.rerank(
                    request.query(),
                    evidence,
                    limit
            );

        } catch (RuntimeException exception) {
            if (exception instanceof RetrievalFailedException) {
                throw exception;
            }

            throw new RetrievalFailedException(
                    "Failed to retrieve document evidence",
                    exception
            );
        }
    }

    private List<RetrievalEvidence> keywordOnly(
            RetrieveDocumentsRequest request,
            int limit
    ) {
        return keywordDocumentSearcher.search(
                new KeywordDocumentSearcher.SearchRequest(
                        request.tenantId(),
                        request.projectId(),
                        request.teamId(),
                        request.query(),
                        limit,
                        request.documentIds()
                )
        );
    }

    private List<RetrievalEvidence> vectorOnly(
            RetrieveDocumentsRequest request,
            int limit
    ) {
        return vectorDocumentSearcher.search(
                new VectorDocumentSearcher.SearchRequest(
                        request.tenantId(),
                        request.projectId(),
                        request.teamId(),
                        request.query(),
                        limit,
                        request.documentIds()
                )
        );
    }

    private List<RetrievalEvidence> hybrid(
            RetrieveDocumentsRequest request,
            int limit
    ) {
        // Retrieve more candidates per engine so fusion has a useful candidate pool.
        // Repository/searcher limits remain capped at 50.
        int candidateLimit = Math.min(MAX_LIMIT, limit * HYBRID_OVERFETCH_FACTOR);
        List<RetrievalEvidence> keywordEvidence = keywordOnly(request, candidateLimit);
        List<RetrievalEvidence> vectorEvidence = vectorOnly(request, candidateLimit);

        Map<String, HybridCandidate> candidatesByChunkId = new HashMap<>();

        // Search results are already ranked. Use each engine's original result
        // position (1-based) rather than incompatible raw similarity/BM25 scores.
        for (int i = 0; i < keywordEvidence.size(); i++) {
            RetrievalEvidence evidence = keywordEvidence.get(i);
            if (evidence == null || evidence.chunkId() == null) {
                continue;
            }
            candidatesByChunkId.putIfAbsent(
                    evidence.chunkId().value(),
                    HybridCandidate.fromKeyword(evidence, i + 1)
            );
        }

        for (int i = 0; i < vectorEvidence.size(); i++) {
            RetrievalEvidence evidence = vectorEvidence.get(i);
            if (evidence == null || evidence.chunkId() == null) {
                continue;
            }
            candidatesByChunkId.merge(
                    evidence.chunkId().value(),
                    HybridCandidate.fromVector(evidence, i + 1),
                    HybridCandidate::merge
            );
        }

        return candidatesByChunkId.values()
                .stream()
                .map(candidate -> retrievalEvidenceBuilder.build(
                        candidate.documentId(),
                        candidate.chunkId(),
                        candidate.title(),
                        candidate.fileName(),
                        candidate.text(),
                        candidate.pageStart(),
                        candidate.pageEnd(),
                        retrievalScoreMerger.mergeRanks(
                                candidate.keywordRank(),
                                candidate.vectorRank()
                        )
                ))
                .toList();
    }

    private List<RetrievalEvidence> hydrateDocumentMetadata(
            TenantId tenantId,
            List<RetrievalEvidence> evidence
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        Set<DocumentId> documentIds = new HashSet<>();

        for (RetrievalEvidence item : evidence) {
            if (item != null && item.documentId() != null) {
                documentIds.add(item.documentId());
            }
        }

        if (documentIds.isEmpty()) {
            return evidence;
        }

        Map<DocumentId, DocumentMetadataLookup.DocumentMetadata> metadataByDocumentId =
                documentMetadataLookup.findByTenantIdAndDocumentIds(
                        tenantId,
                        documentIds
                );

        if (metadataByDocumentId == null || metadataByDocumentId.isEmpty()) {
            return evidence;
        }

        return evidence.stream()
                .map(item -> hydrateOne(item, metadataByDocumentId))
                .toList();
    }

    private RetrievalEvidence hydrateOne(
            RetrievalEvidence evidence,
            Map<DocumentId, DocumentMetadataLookup.DocumentMetadata> metadataByDocumentId
    ) {
        if (evidence == null || evidence.documentId() == null) {
            return evidence;
        }

        DocumentMetadataLookup.DocumentMetadata metadata =
                metadataByDocumentId.get(evidence.documentId());

        if (metadata == null) {
            return evidence;
        }

        String title = firstNonBlank(evidence.title(), metadata.title());
        String fileName = firstNonBlank(evidence.fileName(), metadata.fileName());

        return new RetrievalEvidence(
                evidence.evidenceId(),
                evidence.documentId(),
                evidence.chunkId(),
                title,
                fileName,
                evidence.text(),
                evidence.pageStart(),
                evidence.pageEnd(),
                evidence.relevanceScore(),
                evidence.citation()
        );
    }

    private RetrievalMode normalizeMode(RetrievalMode mode) {
        return mode == null ? RetrievalMode.HYBRID : mode;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private Set<DocumentId> normalizeDocumentIds(Set<DocumentId> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Set.of();
        }

        return Set.copyOf(documentIds);
    }

    private void validate(RetrieveDocumentsRequest request) {
        if (request == null) {
            throw InvalidDocumentException.nullQuery("RetrieveDocumentsRequest");
        }

        if (request.tenantId() == null) {
            throw InvalidDocumentException.blankField("tenantId");
        }

        if (request.userId() == null) {
            throw InvalidDocumentException.blankField("userId");
        }

        if (request.query() == null || request.query().value() == null || request.query().value().isBlank()) {
            throw InvalidDocumentException.blankField("query");
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }

        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }

        return "";
    }

    public record RetrieveDocumentsRequest(
            TenantId tenantId,
            UserId userId,
            ProjectId projectId,
            TeamId teamId,
            SearchQueryText query,
            int limit,
            RetrievalMode mode,
            Set<DocumentId> documentIds
    ) {
    }

    private record HybridCandidate(
            DocumentId documentId,
            ChunkId chunkId,
            String title,
            String fileName,
            String text,
            int pageStart,
            int pageEnd,
            Integer keywordRank,
            Integer vectorRank
    ) {

        private static HybridCandidate fromKeyword(RetrievalEvidence evidence, int rank) {
            return new HybridCandidate(
                    evidence.documentId(),
                    evidence.chunkId(),
                    evidence.title(),
                    evidence.fileName(),
                    evidence.text(),
                    evidence.pageStart(),
                    evidence.pageEnd(),
                    rank,
                    null
            );
        }

        private static HybridCandidate fromVector(RetrievalEvidence evidence, int rank) {
            return new HybridCandidate(
                    evidence.documentId(),
                    evidence.chunkId(),
                    evidence.title(),
                    evidence.fileName(),
                    evidence.text(),
                    evidence.pageStart(),
                    evidence.pageEnd(),
                    null,
                    rank
            );
        }

        private HybridCandidate merge(HybridCandidate other) {
            return new HybridCandidate(
                    documentId != null ? documentId : other.documentId,
                    chunkId != null ? chunkId : other.chunkId,
                    firstNonBlank(title, other.title),
                    firstNonBlank(fileName, other.fileName),
                    firstNonBlank(text, other.text),
                    pageStart > 0 ? pageStart : other.pageStart,
                    pageEnd > 0 ? pageEnd : other.pageEnd,
                    keywordRank != null ? keywordRank : other.keywordRank,
                    vectorRank != null ? vectorRank : other.vectorRank
            );
        }
    }
}