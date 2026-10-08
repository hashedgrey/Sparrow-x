package com.sparrowx.agentic.actions.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalContextMapper;
import com.sparrowx.agentic.tools.internal.InternalContextTool;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.internal.grpc.InternalEntitySearchResult;
import com.sparrowx.internal.grpc.SearchInternalEntitiesRequest;
import com.sparrowx.internal.grpc.SearchInternalEntitiesResponse;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic Internal Service entity-search executor.
 * Embabel planning and semantic parameter resolution happen above this class.
 */
public final class SearchInternalEntitiesAction {

    private final InternalEntitySearchRequestBuilder requestBuilder;
    private final InternalContextTool contextTool;
    private final InternalContextMapper contextMapper;

    public SearchInternalEntitiesAction(
            InternalEntitySearchRequestBuilder requestBuilder,
            InternalContextTool contextTool,
            InternalContextMapper contextMapper
    ) {
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder must not be null");
        this.contextTool = Objects.requireNonNull(contextTool, "contextTool must not be null");
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper must not be null");
    }

    public Result execute(MissionContext context, SearchSpec spec) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        SearchInternalEntitiesRequest request = requestBuilder.build(context, spec);
        SearchInternalEntitiesResponse response = contextTool.searchEntities(context, request);

        return Result.from(response, contextMapper);
    }

    public record Result(
            List<InternalEntitySearchResult> candidates,
            List<EvidenceRef> evidenceRefs,
            boolean ambiguous,
            List<String> warnings
    ) {
        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        private static Result from(
                SearchInternalEntitiesResponse response,
                InternalContextMapper mapper
        ) {
            Objects.requireNonNull(response, "response must not be null");
            Objects.requireNonNull(mapper, "mapper must not be null");

            return new Result(
                    response.getResultsList(),
                    mapper.fromSearch(response),
                    response.getAmbiguous(),
                    response.getWarningsList()
            );
        }
    }
}
