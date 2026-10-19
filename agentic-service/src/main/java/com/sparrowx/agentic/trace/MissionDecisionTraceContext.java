package com.sparrowx.agentic.trace;

import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ChoiceQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ScoreQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionQuestion;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.DecisionResponse;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.NoulAnswer;
import com.sparrowx.agentic.systemone.SystemOneDecisionClient.ScoreAnswer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit mission-scoped instrumentation for synchronous Embabel actions.
 * Not an identity propagation mechanism for Temporal or gRPC: those boundaries
 * must pass authenticated mission and trace IDs explicitly.
 */
public final class MissionDecisionTraceContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private MissionDecisionTraceContext() { }

    private static final class Context {
        final MissionRunInput input;
        final MissionTracePublisher publisher;
        final String parent;
        String proposalStatus;
        String proposalAction;
        Context(MissionRunInput input, MissionTracePublisher publisher, String parent) {
            this.input = input; this.publisher = publisher; this.parent = parent;
        }
    }

    public static Scope open(MissionRunInput input, MissionTracePublisher publisher) {
        Objects.requireNonNull(input); Objects.requireNonNull(publisher);
        return replace(new Context(input, publisher, null));
    }

    public static Scope parent(String traceId) {
        Context ctx = CURRENT.get();
        if (ctx == null) return () -> { };
        Context child = new Context(ctx.input, ctx.publisher, traceId);
        child.proposalAction = ctx.proposalAction;
        child.proposalStatus = ctx.proposalStatus;
        Context previous = CURRENT.get();
        CURRENT.set(child);
        return () -> {
            if (previous != null) {
                previous.proposalStatus = child.proposalStatus;
                previous.proposalAction = child.proposalAction;
                CURRENT.set(previous);
            } else CURRENT.remove();
        };
    }

    private static Scope replace(Context next) {
        Context previous = CURRENT.get();
        CURRENT.set(next);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public static String parentIdOr(String fallback) {
        Context ctx = CURRENT.get();
        return ctx == null || ctx.parent == null ? fallback : ctx.parent;
    }

    public static MissionTracePublisher.Span begin(String kind, String title, Map<String, String> attrs) {
        Context ctx = CURRENT.get();
        return ctx == null ? null : ctx.publisher.start(ctx.input, ctx.parent,
                "decision", "Decisions & evidence", kind, title, attrs);
    }

    public static void event(String kind, String title, Map<String, String> attrs) {
        MissionTracePublisher.Span span = begin(kind, title, attrs);
        if (span != null) span.finish("SUCCEEDED", "");
    }

    public static void snapshot(String stage, int observations, int evidenceStates,
                                int evidenceRefs, int attempts, int addedObservations, int addedEvidence) {
        event("MISSION_EVIDENCE_SNAPSHOT", stage, Map.of(
                "observationCount", Integer.toString(observations),
                "evidenceCount", Integer.toString(evidenceRefs),
                "evidenceStateCount", Integer.toString(evidenceStates),
                "attemptCount", Integer.toString(attempts),
                "addedObservations", Integer.toString(addedObservations),
                "addedEvidence", Integer.toString(addedEvidence)));
    }

    public static void votes(DecisionResponse response,
                             Map<String, ? extends DecisionQuestion> questions,
                             double minimumConfidence) {
        if (CURRENT.get() == null || response == null) return;
        for (var entry : questions.entrySet()) {
            String field = safeIdentifier(entry.getKey());
            DecisionAnswer value = response.answers().get(entry.getKey());
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("field", field);
            attrs.put("threshold", fmt(minimumConfidence));
            attrs.put("provider", safeIdentifier(response.provider()));
            String verdict = "MISSING";
            if (value instanceof ChoiceAnswer choice) {
                attrs.put("choice", safeIdentifier(choice.choice()));
                attrs.put("confidence", fmt(choice.confidence()));
                verdict = entry.getValue() instanceof ChoiceQuestion q
                        && q.options().containsKey(choice.choice())
                        && Double.isFinite(choice.confidence())
                        && choice.confidence() >= 0.0 && choice.confidence() <= 1.0
                        ? confidenceVerdict(choice.confidence(), minimumConfidence)
                        : "INVALID";
            } else if (value instanceof NoulAnswer noul) {
                attrs.put("vote", fmt(noul.probability()));
                attrs.put("confidence", fmt(Math.max(noul.probability(), 1 - noul.probability())));
                verdict = entry.getValue() instanceof NoulQuestion
                        && Double.isFinite(noul.probability())
                        && noul.probability() >= 0.0 && noul.probability() <= 1.0
                        ? confidenceVerdict(Math.max(noul.probability(), 1 - noul.probability()), minimumConfidence)
                        : "INVALID";
            } else if (value instanceof ScoreAnswer score) {
                attrs.put("vote", fmt(score.score()));
                attrs.put("confidence", fmt(score.confidence()));
                verdict = entry.getValue() instanceof ScoreQuestion
                        && Double.isFinite(score.score())
                        && Double.isFinite(score.confidence())
                        && score.confidence() >= 0.0 && score.confidence() <= 1.0
                        ? confidenceVerdict(score.confidence(), minimumConfidence)
                        : "INVALID";
            }
            attrs.put("decision", verdict);
            event("JEV_VOTE", "Jev · " + field, attrs);
        }
    }

    public static void proposal(Object rawStatus, Object rawAction) {
        Context ctx = CURRENT.get();
        if (ctx == null) return;
        ctx.proposalStatus = safeIdentifier(Objects.toString(rawStatus, ""));
        ctx.proposalAction = safeIdentifier(Objects.toString(rawAction, ""));
        if (ctx.proposalStatus.isEmpty() && ctx.proposalAction.isEmpty()) return;
        event("LLM_PROPOSAL", "LLM structured proposal", Map.of(
                "goalStatus", ctx.proposalStatus, "nextAction", ctx.proposalAction));
    }

    public static void validated(String actualStatus, String actualAction, int missing, int alternatives) {
        Context ctx = CURRENT.get();
        if (ctx == null) return;
        String status = safeIdentifier(actualStatus);
        String action = safeIdentifier(actualAction);
        boolean proposed = ctx.proposalStatus != null;
        String validation = !proposed ? "FAST_PATH_OR_NO_LLM" :
                ctx.proposalStatus.equals(status) && ctx.proposalAction.equals(action) ? "ACCEPTED" : "OVERRIDDEN";
        event("JAVA_VALIDATION", "AGSvc validated decision", Map.of(
                "goalStatus", status, "nextAction", action, "missingCount", Integer.toString(missing),
                "alternativeCount", Integer.toString(alternatives), "validation", validation));
    }

    private static String confidenceVerdict(double value, double minimum) {
        return Double.isFinite(value) && value >= minimum ? "ACCEPTED" : "BELOW_THRESHOLD";
    }
    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3f", value) : "unknown";
    }
    private static String safeIdentifier(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[^a-zA-Z0-9_.:-]", "_");
        return clean.substring(0, Math.min(72, clean.length()));
    }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
