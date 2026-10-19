package com.sparrowx.agentic.actions.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalContextMapper;
import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.internal.grpc.InternalEntitySearchResult;
import com.sparrowx.internal.grpc.SearchInternalEntitiesResponse;
import java.util.*;

/** Deterministic graph root-search executor; semantics remain above this layer. */
public final class SearchInternalEntitiesAction {
    private final InternalEntitySearchRequestBuilder requestBuilder;
    private final InternalGrpcClient client;
    private final InternalContextMapper contextMapper;
    public SearchInternalEntitiesAction(InternalEntitySearchRequestBuilder requests,
                                        InternalGrpcClient client,InternalContextMapper mapper) {
        requestBuilder=Objects.requireNonNull(requests);
        this.client=Objects.requireNonNull(client);contextMapper=Objects.requireNonNull(mapper);
    }
    public Result execute(MissionContext context,SearchSpec spec) {
        var response=client.searchInternalEntities(context,requestBuilder.build(context,spec));
        return from(response,contextMapper);
    }
    private static Result from(SearchInternalEntitiesResponse response,InternalContextMapper mapper) {
        Objects.requireNonNull(response);
        return new Result(response.getResultsList(),mapper.fromSearch(response),
                response.getAmbiguous(),response.getWarningsList(),response.getExhaustive());
    }
    public record Result(List<InternalEntitySearchResult> candidates,List<EvidenceRef> evidenceRefs,
                         boolean ambiguous,List<String> warnings,boolean exhaustive) {
        public Result {
            candidates=List.copyOf(candidates); evidenceRefs=List.copyOf(evidenceRefs);
            warnings=List.copyOf(warnings);
        }
    }
}
