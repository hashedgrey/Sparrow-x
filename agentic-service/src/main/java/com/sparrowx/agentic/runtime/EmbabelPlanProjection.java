package com.sparrowx.agentic.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stable, deterministic display projection for registered Embabel action identifiers.
 * Never asks an LLM to paraphrase an execution plan or invents an action.
 * The compact sequence preserves action order, including repeated planning actions.
 */
public final class EmbabelPlanProjection {
    private static final int MAX_ACTIONS = 20;
    private static final int MAX_RAW_CHARS = 2048;
    private static final int MAX_UNKNOWN_IDENTIFIER = 32;

    private EmbabelPlanProjection() { }

    public static Projection from(List<String> actionNames) {
        Objects.requireNonNull(actionNames, "actionNames");
        List<String> raw = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String name : actionNames) {
            if (raw.size() == MAX_ACTIONS) break;
            String value = Objects.toString(name, "");
            raw.add(value);
            ids.add(shortIdentifier(value));
        }
        String completeRaw = String.join(" -> ", raw);
        boolean rawTruncated = completeRaw.length() > MAX_RAW_CHARS;
        // Original qualified identifiers remain available as a bounded legacy/debug field.
        String rawPlan = rawTruncated ? completeRaw.substring(0, MAX_RAW_CHARS) : completeRaw;
        return new Projection(String.join(" > ", ids), rawPlan, actionNames.size(),
                actionNames.size() > ids.size(), rawTruncated);
    }

    static String shortIdentifier(String raw) {
        String value = raw == null ? "" : raw.trim();
        String name = value.substring(value.lastIndexOf('.') + 1);
        // Embabel's registered @Action method names are finite per deployment.
        // Keep the ID, not an LLM-generated English description.
        return switch (name) {
            case "start", "reason", "searchEntities", "queryGraph", "retrieveDocuments",
                 "complete", "interpret", "plan", "collectEvidence" -> name;
            default -> {
                // Unrecognized plugin actions stay visible (never represented as a known action).
                String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
                yield safe.isEmpty() ? "unknown" :
                        safe.substring(0, Math.min(safe.length(), MAX_UNKNOWN_IDENTIFIER));
            }
        };
    }

    public record Projection(String actionIds, String rawPlan, int actionCount,
                             boolean actionsTruncated, boolean rawTruncated) { }
}
