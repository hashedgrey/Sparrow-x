package com.sparrowx.agentic.actions.synthesis;

import com.embabel.agent.api.annotation.Action;
import com.sparrowx.agentic.mission.evidence.Citation;
import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildCitationsAction {

    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)\\]");

    @Action
    public Result execute(BuildSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");

        // Caller order is the evidence-selection priority. Select the budget
        // BEFORE sorting for stable labels; hashes/IDs are not relevance ranks.
        Map<String, EvidenceRef> rankedUnique = new LinkedHashMap<>();
        for (EvidenceRef ref : spec.evidenceRefs()) {
            rankedUnique.putIfAbsent(stableEvidenceIdentity(ref), ref);
        }

        EvidenceRegistry registry = new EvidenceRegistry();
        rankedUnique.values().stream()
                .limit(spec.maxCitations())
                .sorted(Comparator.comparing(BuildCitationsAction::stableEvidenceIdentity)
                        .thenComparing(ref -> nullToEmpty(ref.evidenceId())))
                .forEach(registry::register);

        List<EvidenceRef> evidence = registry.snapshot().stream()
                .sorted(Comparator.comparing(EvidenceRef::evidenceId))
                .toList();

        List<Citation> citations = java.util.stream.IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    EvidenceRef ref = evidence.get(index);
                    if (ref.evidenceId() == null || ref.evidenceId().isBlank()) {
                        throw new IllegalStateException(
                                "registered evidence must have a stable evidenceId");
                    }
                    String excerpt = spec.excerptsByEvidenceId().get(ref.evidenceId());
                    if (excerpt == null) {
                        // Registry normalization may assign a canonical ID.
                        EvidenceRef original = rankedUnique.get(stableEvidenceIdentity(ref));
                        excerpt = original == null || original.evidenceId() == null ? "" : spec.excerptsByEvidenceId()
                                .getOrDefault(original.evidenceId(), "");
                    }
                    return new Citation(citationId(ref.evidenceId()),
                            "[" + (index + 1) + "]", ref.evidenceId(), excerpt);
                })
                .toList();

        return new Result(citations, evidence);
    }

    /**
     * Accepts the complete, unfiltered citation list returned by execute().
     * Its positions define labels [1]..[N], as assigned above. This avoids
     * imposing a new accessor or changing the existing Citation record.
     * Labels remain unchanged because the answer has already streamed.
     * This validates label membership, not semantic claim support.
     */
    public static List<Citation> retainUsedCitations(
            List<Citation> candidates, String... renderedTexts
    ) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(renderedTexts, "renderedTexts must not be null");
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Citation> byLabel = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            byLabel.put("[" + (index + 1) + "]", candidates.get(index));
        }

        Set<String> used = new LinkedHashSet<>();
        for (String text : renderedTexts) {
            Matcher matcher = CITATION_MARKER.matcher(nullToEmpty(text));
            while (matcher.find()) {
                String label = matcher.group();
                if (!byLabel.containsKey(label)) {
                    throw new IllegalStateException("Synthesis used unknown citation " + label);
                }
                used.add(label);
            }
        }

        List<Citation> selected = new ArrayList<>();
        for (Map.Entry<String, Citation> entry : byLabel.entrySet()) {
            if (used.contains(entry.getKey())) {
                selected.add(entry.getValue());
            }
        }
        return List.copyOf(selected);
    }

    private static String citationId(String evidenceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(evidenceId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                hex.append("%02x".formatted(digest[index] & 0xff));
            }
            return "citation-" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String stableEvidenceIdentity(EvidenceRef evidence) {
        Objects.requireNonNull(evidence, "evidenceRefs must not contain null");
        return String.join("\u001f",
                evidence.sourceType().name(),
                nullToEmpty(evidence.sourceService()),
                nullToEmpty(evidence.sourceId()),
                nullToEmpty(evidence.sourceUri()),
                nullToEmpty(evidence.artifactId()),
                nullToEmpty(evidence.objectId()),
                nullToEmpty(evidence.parentObjectId()),
                nullToEmpty(evidence.locationLabel()),
                Integer.toString(evidence.pageStart()),
                Integer.toString(evidence.pageEnd()),
                nullToEmpty(evidence.section()),
                nullToEmpty(evidence.chunkId()),
                nullToEmpty(evidence.sha256()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record BuildSpec(
            List<EvidenceRef> evidenceRefs,
            Map<String, String> excerptsByEvidenceId,
            int maxCitations
    ) {
        private static final int DEFAULT_MAX_CITATIONS = 8;

        public BuildSpec(List<EvidenceRef> evidenceRefs, Map<String, String> excerptsByEvidenceId) {
            this(evidenceRefs, excerptsByEvidenceId, DEFAULT_MAX_CITATIONS);
        }

        public BuildSpec {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            excerptsByEvidenceId = immutableExcerpts(excerptsByEvidenceId);
            if (maxCitations <= 0) {
                throw new IllegalArgumentException("maxCitations must be positive");
            }
        }
    }

    public record Result(List<Citation> citations, List<EvidenceRef> evidenceRefs) {
        public Result {
            citations = citations == null ? List.of() : List.copyOf(citations);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    private static Map<String, String> immutableExcerpts(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.put(Objects.requireNonNull(entry.getKey(),
                        "excerpt evidenceId must not be null"), nullToEmpty(entry.getValue())));
        return Map.copyOf(result);
    }
}
