"use client";

import { useEffect, useMemo, useRef, useState, type FC } from "react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import {
  attrs, buildWalkthrough, entitySummary, eventDetails, eventTime,
  firstKind, lastKind, normalizedTraces, shortAction,
} from "./mission-walkthrough-data";
import "./mission-trace-dashboard.css";

type Props = { metadata?: SparrowMetadata; traces: SparrowTrace[] };
const MAX_STEPS = 11;
const EVENTS_PER_PAGE = 5;
const OMIT_FROM_DETAIL = new Set([
  "EMBABEL_PLANNING_READY", "EMBABEL_PLAN_FORMULATED", "MISSION_EVIDENCE_SNAPSHOT",
  "INTENT_SNAPSHOT", "REASON_LOOP", "MISSION_ACTION_RECOMMENDATION", "JEV_ACCEPTED",
]);

function duration(ms?: number): string {
  if (ms == null || !Number.isFinite(ms)) return "—";
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}
function clock(ms: number): string {
  if (!ms) return "—";
  return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(ms);
}
function humanStatus(raw?: string): string {
  const str = String(raw || "").toUpperCase();
  return str === "SUCCEEDED" || str === "COMPLETED" ? "Done" :
    str === "RUNNING" ? "Running" : str === "FAILED" || str === "FAILED_TERMINAL" ? "Failed" :
      str === "PAUSED" ? "Paused" : str || "Pending";
}
function actionPlan(raw?: string): string {
  if (!raw) return "No Embabel plan recorded for this step";
  return raw.split(" -> ").map(shortAction).join(" → ");
}
function copyStep(traces: SparrowTrace[]) {
  if (typeof navigator !== "undefined" && navigator.clipboard) {
    void navigator.clipboard.writeText(JSON.stringify(traces, null, 2)).catch(() => undefined);
  }
}

export const MissionTraceDashboard: FC<Props> = ({ metadata, traces }) => {
  const normalized = useMemo(() => normalizedTraces(traces), [traces]);
  const steps = useMemo(() => buildWalkthrough(traces), [traces]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [eventPage, setEventPage] = useState(0);
  const [stepPage, setStepPage] = useState(0);
  const [explicitStepNavigation, setExplicitStepNavigation] = useState(false);
  const [compact, setCompact] = useState(false);
  const containerRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const node = containerRef.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(entries => setCompact((entries[0]?.contentRect.width ?? 700) <= 570));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  const stepsPerPage = compact ? 5 : MAX_STEPS;
  const activeIndex = selectedId === null ? Math.max(0, steps.length - 1) :
    Math.max(0, steps.findIndex(s => s.id === selectedId));
  const step = steps[activeIndex];

  useEffect(() => { setEventPage(0); }, [step?.id]);
  useEffect(() => { setSelectedId(null); setStepPage(0); setExplicitStepNavigation(false); }, [metadata?.missionId]);

  // Auto-follow newest steps unless the user is investigating an earlier step.
  const maxStepPage = Math.max(0, Math.ceil(steps.length / stepsPerPage) - 1);
  const effectiveStepPage = explicitStepNavigation ? Math.min(stepPage, maxStepPage) :
    Math.min(Math.floor(activeIndex / stepsPerPage), maxStepPage);
  const visibleSteps = steps.slice(effectiveStepPage * stepsPerPage, (effectiveStepPage + 1) * stepsPerPage);
  const contextual = useMemo(() => steps.slice(0, activeIndex + 1).flatMap(s => s.traces), [steps, activeIndex]);
  const entities = useMemo(() => entitySummary(contextual), [contextual]);
  const plan = lastKind(step?.traces || [], "EMBABEL_PLAN_FORMULATED");
  const review = lastKind(step?.traces || [], "JAVA_VALIDATION") || lastKind(step?.traces || [], "GOAL_EVALUATED");
  const action = firstKind(step?.traces || [], "CAPABILITY_ATTEMPT");
  const graphResult = lastKind(step?.traces || [], "GRAPH_QUERY_RESULT");
  const vote = lastKind(step?.traces || [], "JEV_VOTE");
  const llmStart = lastKind(step?.traces || [], "LLM_GENERATION");
  const llmEnd = lastKind(step?.traces || [], "LLM_RESULT");
  const llmProposal = lastKind(step?.traces || [], "LLM_PROPOSAL");
  const contextPlan = lastKind(contextual, "EMBABEL_PLAN_FORMULATED");
  const displayedPlan = plan || contextPlan;
  const planner = attrs(contextPlan).plannerActual || attrs(firstKind(normalized, "INTENT_SNAPSHOT")).plannerActual || "—";
  const missionStatus = String(metadata?.missionStatus || (metadata?.complete ? "COMPLETED" : "RUNNING"));
  const startedAt = eventTime(normalized[0]);
  const endedAt = normalized.reduce((latest, t) => Math.max(latest, typeof t.endedAt === "number" ? t.endedAt : eventTime(t)), 0);
  const totalMs = metadata?.timing?.totalMs ?? (endedAt && startedAt ? endedAt - startedAt : undefined);
  const rows = (step?.traces || []).filter(t => !OMIT_FROM_DETAIL.has(t.kind || ""));
  const maxEventPage = Math.max(0, Math.ceil(rows.length / EVENTS_PER_PAGE) - 1);
  const safeEventPage = Math.min(eventPage, maxEventPage);
  const visibleEvents = rows.slice(safeEventPage * EVENTS_PER_PAGE, (safeEventPage + 1) * EVENTS_PER_PAGE);
  const aReview = attrs(review);
  const aAction = attrs(action);
  const aVote = attrs(vote);
  const aLlm = attrs(llmStart);
  const aLlmEnd = attrs(llmEnd);
  const aProposal = attrs(llmProposal);
  const aGraph = attrs(graphResult);

  function selectStep(id: string) {
    setSelectedId(id);
    setExplicitStepNavigation(false);
    setEventPage(0);
  }

  if (!metadata?.missionId && traces.length === 0) return null;

  return (
    <section ref={containerRef} className="sp-walkthrough" aria-label="SparrowX mission walkthrough">
      <header className="sp-head">
        <div className="sp-head-main">
          <strong>Mission walkthrough</strong>
          <span className="sp-muted">{planner}</span>
          <span className="sp-muted sp-run-id" title={metadata?.missionId}>{metadata?.missionId || "—"}</span>
        </div>
        <div className="sp-head-meta">
          <span>{duration(totalMs)}</span>
          <span className={`sp-status ${missionStatus.toUpperCase().includes("FAIL") ? "sp-fail" : ""}`}>{humanStatus(missionStatus)}</span>
        </div>
      </header>

      <div className="sp-body">
        <aside className="sp-history" aria-label="Step history">
          <div className="sp-section-head"><strong>Step history</strong><span>{steps.length}</span></div>
          <div className="sp-step-list">
            {visibleSteps.map((s, offset) => {
              const index = effectiveStepPage * stepsPerPage + offset;
              const selected = index === activeIndex;
              return (
                <button type="button" key={s.id} onClick={() => selectStep(s.id)}
                  className={`sp-step ${selected ? "sp-selected" : ""}`}
                  aria-current={selected ? "step" : undefined} title={`${clock(s.at)} · ${s.label}`}>
                  <span className="sp-step-no">{index + 1}</span>
                  <span className="sp-step-label">{s.label}</span>
                  <span className="sp-step-state">{humanStatus(s.anchor.status)}</span>
                </button>
              );
            })}
          </div>
          {maxStepPage > 0 && <div className="sp-pager">
            <button type="button" onClick={() => { setStepPage(Math.max(0, effectiveStepPage - 1)); setExplicitStepNavigation(true); }} disabled={effectiveStepPage === 0}>Older</button>
            <span>{effectiveStepPage + 1}/{maxStepPage + 1}</span>
            <button type="button" onClick={() => { setStepPage(Math.min(maxStepPage, effectiveStepPage + 1)); setExplicitStepNavigation(true); }} disabled={effectiveStepPage === maxStepPage}>Newer</button>
          </div>}
          <div className="sp-history-bottom">
            <span>{step ? `${activeIndex + 1} of ${steps.length}` : "No steps"}</span>
            <span>{step ? clock(step.at) : "—"}</span>
          </div>
        </aside>

        <div className="sp-content">
          <div className="sp-top-grid">
            <section className="sp-box" aria-label="Embabel plan">
              <div className="sp-section-head"><strong>Embabel plan</strong><span>{attrs(displayedPlan).planIteration ? `#${attrs(displayedPlan).planIteration}` : ""}</span></div>
              <div className="sp-box-content">
                <div className="sp-primary" title={attrs(displayedPlan).plannedActions}>{actionPlan(attrs(displayedPlan).plannedActions)}</div>
                <div className="sp-sub">Goal: {attrs(displayedPlan).goal || "Not emitted"}{attrs(displayedPlan).replanning === "true" ? " · Replanned" : ""}</div>
              </div>
            </section>
            <section className="sp-box" aria-label="Decision and result">
              <div className="sp-section-head"><strong>Decision</strong><span>{step?.label || "—"}</span></div>
              <div className="sp-box-content">
                <div className="sp-primary">{aReview.goalStatus ? `${aReview.goalStatus} · ${shortAction(aReview.nextAction || "NONE")}` :
                  aAction.capability ? `${aAction.capability} · ${humanStatus(action?.status)}` :
                    aGraph.resultCount ? `${aGraph.resultCount} graph result(s)` :
                      step?.label === "Complete" ? "Mission finalized" : "No decision emitted"}</div>
                <div className="sp-sub">{aReview.validation ? `Java: ${aReview.validation}` :
                  aAction.capability ? `Capability attempt ${aAction.attempt || "1"}` : "From emitted mission events"}</div>
              </div>
            </section>
          </div>

          <div className="sp-mid-grid">
            <section className="sp-box" aria-label="Resolved entities and evidence">
              <div className="sp-section-head"><strong>Entities / evidence</strong><span>{entities.evidence == null ? "—" : `${entities.evidence} refs`}</span></div>
              <div className="sp-box-content">
                {entities.searchSeen || entities.type ? <>
                  <div className="sp-primary">{entities.type || "Entity"}{entities.roots == null ? " · searched" : ` · ${entities.roots} verified root${entities.roots === 1 ? "" : "s"}`}</div>
                  <div className="sp-sub">{entities.names.length ? entities.names.join(" · ") :
                    entities.results != null ? `${entities.results} graph result${entities.results === 1 ? "" : "s"} · names not in SSE` :
                      "Entity names not transmitted by AGSVC"}</div>
                </> : <span className="sp-empty">No entity search yet</span>}
              </div>
            </section>

            <section className="sp-box" aria-label="JEV decision">
              <div className="sp-section-head"><strong>JEV</strong><span>{vote ? aVote.decision : "Not used"}</span></div>
              <div className="sp-box-content">
                {vote ? <>
                  <div className="sp-primary">{aVote.field || "vote"}: {aVote.choice || aVote.vote || "—"}</div>
                  <div className="sp-sub">Confidence {aVote.confidence || "?"} · threshold {aVote.threshold || "?"}</div>
                </> : <span className="sp-empty">No JEV vote for this step</span>}
              </div>
            </section>

            <section className="sp-box" aria-label="LLM invocation and output">
              <div className="sp-section-head"><strong>LLM</strong><span>{llmEnd ? aLlmEnd.outcome || "Received" : llmStart ? "Running" : "Not used"}</span></div>
              <div className="sp-box-content">
                {llmStart || llmEnd ? <>
                  <div className="sp-primary" title={aLlmEnd.model}>{aLlmEnd.model || aLlmEnd.provider || "Structured decision"} · {aLlm.fieldCount || aLlmEnd.fieldCount || "?"} fields</div>
                  <div className="sp-sub">{aProposal.goalStatus ? `Proposal: ${aProposal.goalStatus}${aProposal.nextAction ? ` / ${shortAction(aProposal.nextAction)}` : ""}` : aLlm.stage || "Structured generation"}</div>
                  <div className="sp-note">Prompt and full response not in SSE</div>
                </> : <span className="sp-empty">No LLM call for this step</span>}
              </div>
            </section>
          </div>

          <section className="sp-box sp-events" aria-label="Event history for selected step">
            <div className="sp-section-head">
              <strong>Event history</strong>
              <div className="sp-event-controls">
                <span>{rows.length} events</span>
                <button type="button" onClick={() => copyStep(step?.traces || [])} disabled={!step} title="Copy all trace events from this step">Copy JSON</button>
              </div>
            </div>
            <div className="sp-event-list">
              {visibleEvents.length ? visibleEvents.map(t => (
                <div className="sp-event" key={t.id} title={`${t.kind || t.title}\n${JSON.stringify(attrs(t), null, 2)}`}>
                  <span className="sp-event-time">{clock(eventTime(t))}</span>
                  <strong>{t.title || t.kind}</strong>
                  <span className="sp-event-description">{eventDetails(t)}</span>
                  <span className="sp-event-duration">{duration(t.durationMs)}</span>
                </div>
              )) : <div className="sp-empty sp-event-empty">No additional event details for this step</div>}
            </div>
            <div className="sp-event-footer">
              <span>Recorded events only · no generated reasoning text</span>
              <div className="sp-pager">
                <button type="button" onClick={() => setEventPage(Math.max(0, safeEventPage - 1))} disabled={safeEventPage === 0}>Previous</button>
                <span>{safeEventPage + 1}/{maxEventPage + 1}</span>
                <button type="button" onClick={() => setEventPage(Math.min(maxEventPage, safeEventPage + 1))} disabled={safeEventPage === maxEventPage}>Next</button>
              </div>
            </div>
          </section>
        </div>
      </div>
    </section>
  );
};
