package com.sparrowx.document.config;

import com.embabel.dice.common.SchemaAdherence;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Locale;

@Setter
@Validated
@ConfigurationProperties(
        prefix = "sparrowx.document.dice.ingestion"
)
public class DiceIngestionProperties {

    @Min(1)
    private int extractionBatchSize = 2;

    @Min(1)
    private int extractionThreads = 2;

    @Min(1)
    private int projectionParallelism = 2;

    // Fixed pool for independent DICE repository save() calls.
    // Keep small to avoid graph lock/transaction contention.
    @Min(1)
    private int propositionPersistenceThreads = 3;

    // Cache only exact duplicate resolver requests during one ingestion.
    private boolean entityResolutionCacheEnabled = true;

    // 0 disables caching; entries are never shared across documents or tenants.
    @Min(0)
    private int entityResolutionCacheEntries = 512;

    // Default true preserves existing vector-assisted matching; disable only for an A/B run.
    private boolean entityVectorSearchEnabled = true;

    // Restrict DICE resolution candidate lookups to tenant-owned graph entities.
    // Legacy entities lacking tenant_id intentionally do not match when enabled.
    private boolean entityTenantFilterEnabled = true;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maxFailedChunkRatio = 0.25d;

    @Min(0)
    private int existingPropositionsToShow = 25;

    @NotBlank
    private String schemaAdherence = "RELAXED";

    public int extractionBatchSize() {
        return extractionBatchSize;
    }

    public int extractionThreads() {
        return extractionThreads;
    }

    public int projectionParallelism() {
        return projectionParallelism;
    }

    public int propositionPersistenceThreads() {
        return propositionPersistenceThreads;
    }

    public double maxFailedChunkRatio() {
        return maxFailedChunkRatio;
    }

    public boolean entityResolutionCacheEnabled() {
        return entityResolutionCacheEnabled;
    }

    public int entityResolutionCacheEntries() {
        return entityResolutionCacheEntries;
    }

    public boolean entityVectorSearchEnabled() {
        return entityVectorSearchEnabled;
    }

    public boolean entityTenantFilterEnabled() {
        return entityTenantFilterEnabled;
    }

    public int existingPropositionsToShow() {
        return existingPropositionsToShow;
    }

    public SchemaAdherence schemaAdherence() {
        return switch (
                schemaAdherence.trim().toUpperCase(Locale.ROOT)
                ) {
            case "STRICT" -> SchemaAdherence.STRICT;
            case "DEFAULT" -> SchemaAdherence.DEFAULT;
            case "RELAXED" -> SchemaAdherence.RELAXED;

            default -> throw new IllegalStateException(
                    "Unsupported DICE schema adherence: "
                            + schemaAdherence
                            + ". Expected STRICT, DEFAULT, or RELAXED."
            );
        };
    }
}