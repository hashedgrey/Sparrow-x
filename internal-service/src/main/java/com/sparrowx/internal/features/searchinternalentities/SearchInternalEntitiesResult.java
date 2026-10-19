package com.sparrowx.internal.features.searchinternalentities;
import java.util.List;
import java.util.Objects;

public record SearchInternalEntitiesResult(List<InternalEntitySearchResult> results,
                                           boolean ambiguous, List<String> warnings,
                                           boolean exhaustive) {
    public SearchInternalEntitiesResult {
        results = List.copyOf(Objects.requireNonNull(results));
        warnings = List.copyOf(Objects.requireNonNull(warnings));
    }
    /** Conservative compatibility: unpaginated legacy search cannot prove completeness. */
    public SearchInternalEntitiesResult(List<InternalEntitySearchResult> results,
                                       boolean ambiguous, List<String> warnings) {
        this(results, ambiguous, warnings, false);
    }
}
