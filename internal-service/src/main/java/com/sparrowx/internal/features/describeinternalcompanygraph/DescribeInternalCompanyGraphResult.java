package com.sparrowx.internal.features.describeinternalcompanygraph;

public record DescribeInternalCompanyGraphResult(
        String ontologyJson,
        String schemaVersion
) {
    public DescribeInternalCompanyGraphResult {
        if (ontologyJson == null || ontologyJson.isBlank()) {
            throw new IllegalArgumentException("ontologyJson is required");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
    }
}