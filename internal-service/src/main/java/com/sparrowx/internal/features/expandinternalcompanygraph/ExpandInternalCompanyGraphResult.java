package com.sparrowx.internal.features.expandinternalcompanygraph;

import com.sparrowx.internal.models.InternalGraphContext;

import java.util.Objects;

public record ExpandInternalCompanyGraphResult(
        InternalGraphContext graph,
        boolean truncated
) {
    public ExpandInternalCompanyGraphResult {
        Objects.requireNonNull(
                graph,
                "graph is required"
        );
    }
}