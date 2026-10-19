package com.sparrowx.agentic.tools.document;

import com.sparrowx.agentic.adapters.document.DocumentClientMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.document.proto.DocumentScopeProto;
import com.sparrowx.document.proto.RetrievalModeProto;
import com.sparrowx.document.proto.SearchDocumentSpansRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DocumentSpanSearchRequestBuilder {

    private final DocumentClientMapper clientMapper;

    public DocumentSpanSearchRequestBuilder(DocumentClientMapper clientMapper) {
        this.clientMapper = Objects.requireNonNull(clientMapper, "clientMapper must not be null");
    }

    public SearchDocumentSpansRequest build(MissionContext context, SearchSpec spec) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        return SearchDocumentSpansRequest.newBuilder()
                .setContext(clientMapper.toRequestContext(context, spec.requestId()))
                .setQuery(spec.query())
                .setRetrievalMode(spec.retrievalMode())
                .setScope(spec.scope().toProto())
                .setLimit(spec.limit())
                .setIncludeExcerpts(spec.includeExcerpts())
                .build();
    }

    public record SearchSpec(
            String requestId,
            String query,
            RetrievalModeProto retrievalMode,
            Scope scope,
            int limit,
            boolean includeExcerpts) {

        public SearchSpec {
            requestId = requireText(requestId, "requestId");
            query = requireText(query, "query");
            retrievalMode = Objects.requireNonNull(retrievalMode, "retrievalMode must not be null");
            if (retrievalMode == RetrievalModeProto.RETRIEVAL_MODE_UNSPECIFIED
                    || retrievalMode == RetrievalModeProto.UNRECOGNIZED) {
                throw new IllegalArgumentException("retrievalMode must be specified");
            }
            scope = Objects.requireNonNull(scope, "scope must not be null");
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    public record Scope(
            List<String> documentIds,
            List<String> fileNames,
            List<String> collectionIds,
            List<String> tags,
            Map<String, String> metadataFilters) {

        public Scope {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            fileNames = fileNames == null ? List.of() : List.copyOf(fileNames);
            collectionIds = collectionIds == null ? List.of() : List.copyOf(collectionIds);
            tags = tags == null ? List.of() : List.copyOf(tags);
            metadataFilters = metadataFilters == null ? Map.of() : Map.copyOf(metadataFilters);
            requireNonBlankValues(documentIds, "documentIds");
            requireNonBlankValues(fileNames, "fileNames");
            requireNonBlankValues(collectionIds, "collectionIds");
            requireNonBlankValues(tags, "tags");
            metadataFilters.forEach((key, value) -> {
                requireText(key, "metadata filter key");
                requireText(value, "metadata filter value");
            });
        }

        public boolean hasConstraints() {
            return !documentIds.isEmpty() || !fileNames.isEmpty()
                    || !collectionIds.isEmpty() || !tags.isEmpty()
                    || !metadataFilters.isEmpty();
        }

        private static void requireNonBlankValues(List<String> values, String field) {
            for (String value : values) {
                requireText(value, field + " entry");
            }
        }

        public DocumentScopeProto toProto() {
            return DocumentScopeProto.newBuilder()
                    .addAllDocumentIds(documentIds)
                    .addAllFileNames(fileNames)
                    .addAllCollectionIds(collectionIds)
                    .addAllTags(tags)
                    .putAllMetadataFilters(metadataFilters)
                    .build();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
