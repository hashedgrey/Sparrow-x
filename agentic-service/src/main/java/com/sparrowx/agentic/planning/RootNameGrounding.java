package com.sparrowx.agentic.planning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, ontology-agnostic anchoring of graph labels to literal user text.
 * A shorter name nested inside a longer matched name is not a separate mention.
 * It never guesses missing names and never determines whether a scan is required.
 */
public final class RootNameGrounding {
    private RootNameGrounding() { }

    public static List<String> maximalMentions(String text, Collection<String> knownLabels) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(knownLabels, "knownLabels");
        List<Mention> mentions = new ArrayList<>();
        for (String label : knownLabels) {
            if (label == null || label.isBlank()) continue;
            Matcher matcher = Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(label.strip())
                            + "(?![\\p{L}\\p{N}])").matcher(text);
            while (matcher.find()) mentions.add(new Mention(label, matcher.start(), matcher.end()));
        }
        // A nested label is never treated as a separate independent root mention.
        // Two non-overlapping named entities remain distinct: never choose a
        // longer name over a second separately mentioned entity.
        Set<String> result = new LinkedHashSet<>();
        for (Mention mention : mentions) {
            boolean nested = mentions.stream().anyMatch(other -> other != mention
                    && other.start() <= mention.start() && other.end() >= mention.end()
                    && (other.start() < mention.start() || other.end() > mention.end()));
            if (!nested) result.add(mention.label());
        }
        return result.stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Return canonical labels that are explicitly mentioned in the immutable
     * original task AND overlap a proposed root expression. For a UNIQUE task,
     * a single explicitly mentioned canonical name can repair an entirely bad
     * proposed literal. Multiple independent names are never collapsed.
     */
    public static List<String> recoveryCandidates(String originalTask, String proposedLiteral,
                                                   Collection<String> knownLabels,
                                                   boolean uniqueMode) {
        List<String> original = maximalMentions(originalTask, knownLabels);
        List<String> overlapping = maximalMentions(proposedLiteral, knownLabels);
        List<String> anchored = overlapping.stream().filter(original::contains).toList();
        if (anchored.isEmpty() && uniqueMode && original.size() == 1) return original;
        return anchored;
    }

    private record Mention(String label, int start, int end) { }
}
