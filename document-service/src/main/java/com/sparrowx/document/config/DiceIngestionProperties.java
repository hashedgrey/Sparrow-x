package com.sparrowx.document.config;

import com.embabel.dice.common.SchemaAdherence;
import jakarta.validation.constraints.Min;
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