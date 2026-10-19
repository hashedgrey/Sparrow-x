package com.sparrowx.agentic.steps;

import com.sparrowx.agentic.actions.internal.SearchInternalEntitiesAction;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.InternalRootSelection.Candidate;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder.SearchSpec;
import com.sparrowx.agentic.validation.DownstreamResponseValidator;
import com.sparrowx.agentic.validation.DownstreamResponseValidator.ResponseMetadata;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Execute and validate tenant-scoped entity-root discovery. Graph queries run elsewhere. */
@Component
public final class ResolveInternalEntitiesStep {
    private final SearchInternalEntitiesAction searchAction;
    private final DownstreamResponseValidator responseValidator;

    public ResolveInternalEntitiesStep(SearchInternalEntitiesAction searchAction,
                                       DownstreamResponseValidator responseValidator) {
        this.searchAction = Objects.requireNonNull(searchAction);
        this.responseValidator = Objects.requireNonNull(responseValidator);
    }

    public Result search(
            MissionContext context,
            SearchSpec spec
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        SearchInternalEntitiesAction.Result result =
                searchAction.execute(context, spec);

        responseValidator.validateInternal(
                "search-internal-entities",
                context.tenantId(),
                result,
                value -> new ResponseMetadata(
                        context.tenantId(),
                        spec.requestId(),
                        spec.requestId() + ":internal-entities",
                        value.candidates().size(),
                        value.candidates().stream()
                                .mapToLong(candidate ->
                                        candidate.getSerializedSize())
                                .sum(),
                        value.evidenceRefs()
                )
        );

        // Return typed candidates; never collapse a result set into its first hit.
        List<Candidate> candidates = result.candidates().stream()
                .map(Candidate::fromProto)
                .toList();

        // The tenant graph scanner fetches limit+1 and reports completeness explicitly.
        // Never infer exhaustiveness from the response row count.
        boolean exhaustive = result.exhaustive();
        List<String> warnings = new java.util.ArrayList<>(result.warnings());
        if (!exhaustive) warnings.add(
                "Root search incomplete; increase the limit or implement pagination");

        return new Result(
                result.evidenceRefs(),
                "Found " + candidates.size() + " candidate roots",
                List.copyOf(warnings),
                Map.of("candidateCount", candidates.size(),
                        "backendAmbiguous", result.ambiguous(),
                        "exhaustive", exhaustive),
                candidates,
                result.ambiguous(),
                exhaustive
        );
    }


    public record Result(
            List<EvidenceRef> evidenceRefs,
            String summary,
            List<String> warnings,
            Map<String, Object> attributes,
            List<Candidate> candidates,
            boolean backendAmbiguous,
            boolean exhaustive
    ) {
        public Result {
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
            summary = Objects.requireNonNull(summary, "summary");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

}
