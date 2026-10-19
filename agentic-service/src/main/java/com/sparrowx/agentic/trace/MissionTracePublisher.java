package com.sparrowx.agentic.trace;

import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.mission.model.ComponentInvocation;
import com.sparrowx.agentic.mission.model.ComponentKind;
import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.runtime.model.StepStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded, tenant-scoped trace events over the existing durable MissionProgressEvent cursor.
 * Every span is a genuine observed operation. The UI does not infer or synthesize descendants.
 * Parent IDs must be propagated explicitly over virtual threads / Temporal / gRPC boundaries.
 */
@Component
public final class MissionTracePublisher {
    private static final Set<String> SAFE_KEYS = Set.of(
            "provider", "model", "operation", "capability", "rootCount", "rowCount",
            "resultCount", "source", "resolutionStatus", "rpcStatus", "route",
            "confidenceBand", "selectionMode", "attempt", "complete", "truncated",
            "durationMs", "outcome", "verification", "escalation", "retryCount",
            "decision", "tool", "service", "rootType", "goalStatus",
            "field", "choice", "vote", "confidence", "threshold", "reasonCode",
            "fieldCount", "escalatedCount", "stage", "planIteration", "goal",
            "plannedActions", "observationCount", "evidenceCount", "attemptCount",
            "addedObservations", "addedEvidence", "evidenceStateCount", "missingCount", "alternativeCount",
            "validation", "nextAction", "proposalStatus", "replanning",
            "plannerRequested", "plannerActual", "planActionCount", "planTruncated", "reviewIteration",
            "stateFrom", "stateTo", "requiresCompanyGraph",
            "requiresInternalContext", "requiresDocumentEvidence", "requiresVerification",
            "traversalCount", "predicateCount", "projectionCount", "negationCount",
            "comparisonCount", "selectionLimit", "schemaVersion"
    );
    private final MissionEventPublisher events;
    public MissionTracePublisher(MissionEventPublisher events) { this.events = events; }

    public Span start(MissionRunInput input, String parentId, String sectorId, String sectorName,
                      String kind, String title, Map<String, String> safeAttributes) {
        String id = UUID.randomUUID().toString();
        Span span = new Span(input, id, parentId, sectorId, sectorName, kind, title,
                Instant.now(), filter(safeAttributes));
        emit(span, "RUNNING", "");
        return span;
    }

    public <T> T observe(MissionRunInput input, String parentId, String sectorId, String sectorName,
                         String kind, String title, Map<String, String> safeAttributes, Supplier<T> operation) {
        Span span = start(input, parentId, sectorId, sectorName, kind, title, safeAttributes);
        try {
            T value = operation.get();
            span.finish("SUCCEEDED", "");
            return value;
        } catch (RuntimeException | Error failure) {
            span.finish("FAILED", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static Map<String, String> filter(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return Map.of();
        Map<String, String> safe = new HashMap<>();
        attributes.forEach((key, value) -> {
            if (SAFE_KEYS.contains(key) && value != null) {
                String text = value.replaceAll("[\\r\\n\\t]", " ");
                // Never forward prompts, query text, auth tokens, document contents or row bodies.
                int cap = "plannedActions".equals(key) ? 2048 : 96;
                safe.put(key, text.substring(0, Math.min(cap, text.length())));
            }
        });
        return Map.copyOf(safe);
    }

    private void emit(Span span, String status, String detail) {
        Map<String, String> meta = new HashMap<>();
        meta.put("trace.id", span.id);
        meta.put("trace.parentId", span.parentId == null ? "" : span.parentId);
        meta.put("trace.sectorId", span.sectorId);
        meta.put("trace.sectorName", span.sectorName);
        meta.put("trace.kind", span.kind);
        meta.put("trace.title", span.title);
        meta.put("trace.status", status);
        meta.put("trace.source", "agsvc");
        meta.put("trace.startedAt", span.startedAt.toString());
        meta.put("trace.message", detail);
        meta.put("trace.schemaVersion", "2");
        for (var entry : span.attributes.entrySet()) meta.put("trace.attr." + entry.getKey(), entry.getValue());
        Instant now = Instant.now();
        if (!"RUNNING".equals(status)) {
            meta.put("trace.endedAt", now.toString());
            meta.put("trace.durationMs", Long.toString(Math.max(0L,
                    Duration.between(span.startedAt, now).toMillis())));
        }
        try {
            events.publish(span.input.tenantId(), new MissionProgressEvent(
                span.input.missionId(), MissionStatus.RUNNING,
                span.sectorId, span.sectorName, "span:" + span.id, span.title,
                status.equals("RUNNING") ? StepStatus.RUNNING : status.equals("SUCCEEDED") ? StepStatus.SUCCEEDED
                        : status.equals("PAUSED") ? StepStatus.PAUSED : StepStatus.FAILED_TERMINAL,
                new ComponentInvocation("trace:" + span.kind, ComponentKind.CUSTOM, span.title,
                        Map.of(), Map.of(), span.startedAt, status.equals("RUNNING") ? null : now),
                detail.isBlank() ? span.title : detail, 0.0,
                "trace:" + span.input.missionId() + ":" + span.id + ":" + status.toLowerCase(),
                now, Map.copyOf(meta)
            ));
        } catch (RuntimeException telemetryFailure) {
            // Telemetry must not change business-action success/failure semantics.
            System.getLogger(MissionTracePublisher.class.getName()).log(
                    System.Logger.Level.WARNING, "Could not publish mission trace: " + telemetryFailure.getClass().getSimpleName());
        }
    }

    public final class Span {
        private final MissionRunInput input;
        private final String id, parentId, sectorId, sectorName, kind, title;
        private final Instant startedAt;
        private final Map<String, String> attributes;
        private boolean finished;
        private Span(MissionRunInput input, String id, String parentId, String sectorId,
                     String sectorName, String kind, String title, Instant startedAt, Map<String, String> attributes) {
            this.input = input; this.id = id; this.parentId = parentId; this.sectorId = sectorId;
            this.sectorName = sectorName; this.kind = kind; this.title = title;
            this.startedAt = startedAt; this.attributes = attributes;
        }
        public String id() { return id; }
        public synchronized void finish(String status, String detail) {
            if (finished) return;
            finished = true;
            emit(this, status, detail == null ? "" : detail);
        }
    }
}
