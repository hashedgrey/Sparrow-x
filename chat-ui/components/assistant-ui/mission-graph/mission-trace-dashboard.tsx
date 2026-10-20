"use client";

import { useEffect, useMemo, useRef, useState, type FC, type ReactNode } from "react";
import {
  Activity, AlertCircle, ArrowRight, BrainCircuit, Check,
  ChevronLeft, ChevronRight, Circle, Clock3, Copy, Database,
  FileSearch, FileText, Flag, GitBranch, History, Info, LoaderCircle, Route,
  Scale, Search, Target, type LucideIcon,
} from "lucide-react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  attrs, buildWalkthrough, embabelPlanView, entitySummary, eventDetails, eventTime,
  firstKind, isFailureStatus, lastKind, normalizedTraces, shortAction, sourceSummary,
} from "./mission-walkthrough-data";
import { usePublishMissionSummary, type MissionHeaderSnapshot } from "../mission-workspace";
import "./mission-trace-dashboard.css";

type Props = {
  metadata?: SparrowMetadata;
  traces: SparrowTrace[];
  showEmpty?: boolean;
  /** Live original ThreadPrimitive conversation, rendered only in the Result step. */
  conversation?: ReactNode;
  /** Whether there are existing messages in the current thread. */
  hasConversation?: boolean;
  /** Retained as a fallback for standalone uses of this dashboard. */
  result?: string;
  /** Only the hidden source dashboard publishes to the toolbar; avoids context loops. */
  publishSummary?: boolean;
};
const RESULT_ID = "__mission_result__";

const MAX_STEPS = 11;
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
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).format(ms);
}
function humanStatus(raw?: string): string {
  const str = String(raw || "").toUpperCase();
  switch (str) {
    case "SUCCEEDED": return "Done";
    case "COMPLETED": return "Completed";
    case "RUNNING": return "Running";
    case "FAILED": case "FAILED_TERMINAL": return "Failed";
    case "BLOCKED": return "Blocked";
    case "PAUSED": return "Paused";
    default: return str ? str[0] + str.slice(1).toLowerCase().replaceAll("_", " ") : "Pending";
  }
}
function statusKind(raw?: string): "success" | "error" | "active" | "neutral" {
  const normalized = String(raw || "").toUpperCase();
  if (isFailureStatus(normalized)) return "error";
  if (normalized === "SUCCEEDED" || normalized === "COMPLETED") return "success";
  if (normalized === "RUNNING" || normalized === "PAUSED") return "active";
  return "neutral";
}
function stepIcon(label: string): LucideIcon {
  if (label.startsWith("Review")) return GitBranch;
  if (label === "Find entities") return Search;
  if (label === "Result") return FileText;
  if (label === "Complete") return Flag;
  if (label === "Start") return Route;
  return ArrowRight;
}

const PanelHeading: FC<{
  icon: LucideIcon;
  title: string;
  meta?: ReactNode;
}> = ({ icon: Icon, title, meta }) => (
  <div className="sp-section-head">
    <div className="sp-section-title">
      <span className="sp-icon-box" aria-hidden="true"><Icon size={17} strokeWidth={2.1} /></span>
      <strong>{title}</strong>
    </div>
    {meta != null && <span className="sp-section-meta">{meta}</span>}
  </div>
);

const StatusChip: FC<{ status?: string; label?: string }> = ({ status, label }) => (
  <span className={`sp-chip sp-chip-${statusKind(status)}`}>
    {statusKind(status) === "error" ? <AlertCircle size={12} aria-hidden="true" /> :
      statusKind(status) === "success" ? <Check size={12} aria-hidden="true" /> :
        statusKind(status) === "active" ? <LoaderCircle size={12} aria-hidden="true" /> :
          <Circle size={10} aria-hidden="true" />}
    {label || humanStatus(status)}
  </span>
);

export const MissionTraceDashboard: FC<Props> = ({
  metadata, traces, showEmpty = false, result = "", conversation,
  hasConversation = false, publishSummary = true,
}) => {
  const normalized = useMemo(() => normalizedTraces(traces), [traces]);
  const steps = useMemo(() => buildWalkthrough(traces), [traces]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [stepPage, setStepPage] = useState(0);
  const [explicitStepNavigation, setExplicitStepNavigation] = useState(false);
  const [compact, setCompact] = useState(false);
  const [copyState, setCopyState] = useState<"idle" | "copied" | "error">("idle");
  const containerRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const node = containerRef.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(entries =>
      setCompact((entries[0]?.contentRect.width ?? 700) <= 650));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const isFinished = Boolean(metadata?.complete) || steps.some(s => s.anchor.kind === "MISSION_FINISH");
  // Result is a presentation-only step, added after the actual 'Complete' event.
  // It is never inserted into the trace/GOAP plan or selected automatically.
  const hasResultStep = isFinished && steps.length > 0;
  const historyItems = [
    ...steps.map(s => ({ id: s.id, label: s.label, at: s.at, status: s.anchor.status })),
    ...(hasResultStep ? [{ id: RESULT_ID, label: "Result", at: steps[steps.length - 1]!.at,
      status: (hasConversation || result.trim()) ? "COMPLETED" : "PENDING" }] : []),
  ];
  const isResultSelected = hasResultStep && selectedId === RESULT_ID;
  const stepsPerPage = compact ? 5 : MAX_STEPS;
  const activeIndex = isResultSelected ? steps.length : selectedId === null ? Math.max(0, steps.length - 1) :
    Math.max(0, steps.findIndex(s => s.id === selectedId));
  const step = isResultSelected ? undefined : steps[activeIndex];
  useEffect(() => { setCopyState("idle"); }, [step?.id]);
  useEffect(() => {
    setSelectedId(null); setStepPage(0); setExplicitStepNavigation(false);
  }, [metadata?.missionId]);

  // Follow the newest step unless an earlier step/page was selected explicitly.
  const maxStepPage = Math.max(0, Math.ceil(historyItems.length / stepsPerPage) - 1);
  const effectiveStepPage = explicitStepNavigation ? Math.min(stepPage, maxStepPage) :
    Math.min(Math.floor(activeIndex / stepsPerPage), maxStepPage);
  const visibleSteps = historyItems.slice(effectiveStepPage * stepsPerPage, (effectiveStepPage + 1) * stepsPerPage);
  const contextual = useMemo(() => steps.slice(0, Math.min(activeIndex + 1, steps.length)).flatMap(s => s.traces), [steps, activeIndex]);
  const entities = useMemo(() => entitySummary(contextual), [contextual]);
  const sources = useMemo(() => sourceSummary(contextual), [contextual]);
  const failedSteps = steps.filter(s => isFailureStatus(s.anchor.status)).length;

  const plan = lastKind(step?.traces || [], "EMBABEL_PLAN_FORMULATED");
  const review = lastKind(step?.traces || [], "JAVA_VALIDATION") || lastKind(step?.traces || [], "GOAL_EVALUATED");
  const action = firstKind(step?.traces || [], "CAPABILITY_ATTEMPT");
  const graphResult = lastKind(step?.traces || [], "GRAPH_QUERY_RESULT");
  const vote = lastKind(step?.traces || [], "JEV_VOTE");
  const llmStart = lastKind(step?.traces || [], "LLM_GENERATION");
  const llmEnd = lastKind(step?.traces || [], "LLM_RESULT");
  const llmProposal = lastKind(step?.traces || [], "LLM_PROPOSAL");
  const displayedPlan = plan || lastKind(contextual, "EMBABEL_PLAN_FORMULATED");
  const planView = embabelPlanView(attrs(displayedPlan));
  const planner = attrs(displayedPlan).plannerActual ||
    attrs(firstKind(normalized, "INTENT_SNAPSHOT")).plannerActual || "—";
  const missionStatus = String(metadata?.missionStatus || (metadata?.complete ? "COMPLETED" : showEmpty && !metadata?.missionId ? "READY" : "RUNNING"));
  const startedAt = eventTime(normalized[0]);
  const endedAt = normalized.reduce((latest, trace) => {
    const end = typeof trace.endedAt === "number" ? trace.endedAt :
      typeof trace.endedAt === "string" ? Date.parse(trace.endedAt) || eventTime(trace) : eventTime(trace);
    return Math.max(latest, end);
  }, 0);
  const totalMs = metadata?.timing?.totalMs ?? (endedAt && startedAt ? endedAt - startedAt : undefined);
  const runSummary = useMemo<MissionHeaderSnapshot | null>(() => metadata?.missionId ? {
    missionId: metadata.missionId,
    metadata,
    planner,
    status: missionStatus,
    totalMs,
    failedSteps,
    eventCount: normalized.length,
    startedAt,
    traces,
  } : null, [metadata, planner, missionStatus, totalMs, failedSteps, normalized.length, startedAt, traces]);
  usePublishMissionSummary(publishSummary ? runSummary : null);

  const rows = (step?.traces || []).filter(t => !OMIT_FROM_DETAIL.has(t.kind || ""));
  const aReview = attrs(review);
  const aAction = attrs(action);
  const aVote = attrs(vote);
  const aLlm = attrs(llmStart);
  const aLlmEnd = attrs(llmEnd);
  const aProposal = attrs(llmProposal);
  const aGraph = attrs(graphResult);
  const stepEvidenceAdded = (step?.traces || []).reduce((total, t) => {
    if (t.kind !== "MISSION_EVIDENCE_DELTA") return total;
    const n = Number(attrs(t).addedEvidence);
    return total + (Number.isSafeInteger(n) && n >= 0 ? n : 0);
  }, 0);

  async function copyEvents() {
    if (!step || typeof navigator === "undefined" || !navigator.clipboard) {
      setCopyState("error");
      return;
    }
    try {
      await navigator.clipboard.writeText(JSON.stringify(step.traces, null, 2));
      setCopyState("copied");
    } catch { setCopyState("error"); }
  }
  function selectStep(id: string) {
    setSelectedId(id);
    setExplicitStepNavigation(false);
    setCopyState("idle");
  }

  if (!showEmpty && !metadata?.missionId && traces.length === 0) return null;

  return (
    <section ref={containerRef} className="sp-walkthrough" aria-label="SparrowX mission walkthrough">
      <header className="sp-head">
        <div className="sp-head-main">
          <span className="sp-brand-icon" aria-hidden="true"><Route size={19} /></span>
          <span className="sp-head-identity">
            <span className="sp-head-title"><strong>Mission walkthrough</strong><span className="sp-planner-chip">{planner}</span></span>
            <span className="sp-run-id" title={metadata?.missionId}>{metadata?.missionId || "No mission started"}</span>
          </span>
        </div>
        <div className="sp-head-meta">
          <span className="sp-elapsed"><Clock3 size={15} aria-hidden="true" /> {duration(totalMs)}</span>
          {failedSteps > 0 && <span className="sp-failure-count">{failedSteps} failed {failedSteps === 1 ? "step" : "steps"}</span>}
          <StatusChip status={missionStatus} />
        </div>
      </header>

      <div className="sp-body">
        <aside className="sp-history sp-tone-blue" aria-label="Step history">
          <PanelHeading icon={History} title="Step history" meta={<span className="sp-count">{historyItems.length}</span>} />
          <div className="sp-step-list">
            {visibleSteps.map((s, offset) => {
              const index = effectiveStepPage * stepsPerPage + offset;
              const selected = index === activeIndex;
              const Icon = stepIcon(s.label);
              const outcome = statusKind(s.status);
              return (
                <button type="button" key={s.id} onClick={() => selectStep(s.id)}
                  className={`sp-step ${selected ? "sp-selected" : ""} sp-step-${outcome}`}
                  aria-current={selected ? "step" : undefined}
                  title={`${clock(s.at)} · ${s.label} · ${humanStatus(s.status)}`}>
                  <span className="sp-step-marker" aria-hidden="true">
                    {outcome === "success" ? <Check size={13} /> :
                      outcome === "error" ? <AlertCircle size={13} /> : <Icon size={13} />}
                  </span>
                  <span className="sp-step-no">{index + 1}</span>
                  <span className="sp-step-label">{s.label}</span>
                  <span className="sp-step-state">{humanStatus(s.status)}</span>
                </button>
              );
            })}
          </div>
          {maxStepPage > 0 && <div className="sp-pager sp-history-pager">
            <button type="button" aria-label="Older steps" onClick={() => {
              setStepPage(Math.max(0, effectiveStepPage - 1)); setExplicitStepNavigation(true);
            }} disabled={effectiveStepPage === 0}><ChevronLeft size={14} /> Older</button>
            <span>{effectiveStepPage + 1}/{maxStepPage + 1}</span>
            <button type="button" aria-label="Newer steps" onClick={() => {
              setStepPage(Math.min(maxStepPage, effectiveStepPage + 1)); setExplicitStepNavigation(true);
            }} disabled={effectiveStepPage === maxStepPage}>Newer <ChevronRight size={14} /></button>
          </div>}
          <div className="sp-history-bottom">
            <span>{historyItems.length ? `Step ${activeIndex + 1} / ${historyItems.length}` : "No steps"}</span>
            <span>{historyItems[activeIndex] ? clock(historyItems[activeIndex]!.at) : "—"}</span>
          </div>
        </aside>

        {isResultSelected ? (
          <section className="sp-result-pane" aria-label="Mission result">
            <div className="sp-result-heading">
              <span className="sp-result-heading-icon" aria-hidden="true"><FileText size={19} /></span>
              <div>
                <strong>Mission result</strong>
                <p>Conversation history · Original chat messages and actions</p>
              </div>
              <StatusChip status={missionStatus} />
            </div>
            <div className={`sp-result-document ${conversation ? "sp-result-chat-document" : ""}`}>
              {conversation || (result.trim() ? <div className="sp-result-markdown" role="document">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{result}</ReactMarkdown>
              </div> : <div className="sp-result-pending">
                <Info size={19} aria-hidden="true" />
                <span>No conversation messages are available yet.</span>
              </div>)}
            </div>
          </section>
        ) : (
        <div className="sp-content">
          <div className="sp-card-grid">
            <section className="sp-box sp-tone-violet" aria-label="Embabel plan">
              <PanelHeading icon={Target} title="Embabel plan"
                meta={attrs(displayedPlan).planIteration ? `#${attrs(displayedPlan).planIteration}` : "—"} />
              <div className="sp-box-content">
                <div className="sp-primary" title={planView.rawPlan || planView.actions.map(a => a.id).join(" → ")}>
                  {planView.preview}
                </div>
                <div className="sp-sub">Goal: {attrs(displayedPlan).goal || "Not emitted"}
                  {attrs(displayedPlan).replanning === "true" ? " · Replanned" : ""}
                </div>
                {planView.actions.length > 0 && (
                  <details className="sp-plan-details">
                    <summary>Action sequence · {planView.actions.length}{planView.total > planView.actions.length ? ` of ${planView.total}` : ""}</summary>
                    <ol className="sp-plan-sequence">
                      {planView.actions.map((action, index) => (
                        <li key={`${index}-${action.id}`}>
                          <span>{action.label}</span><code title={action.id}>{action.id}</code>
                        </li>
                      ))}
                    </ol>
                    {planView.truncated && <div className="sp-plan-warning">Only the emitted part of this plan is available.</div>}
                    {planView.rawPlan && <div className="sp-plan-original" title="Qualified action names from Embabel">
                      <strong>Original action identifiers</strong><code>{planView.rawPlan}</code>
                      {attrs(displayedPlan).planRawTruncated === "true" && <em>Raw identifier field truncated; use the compact sequence above.</em>}
                    </div>}
                  </details>
                )}
              </div>
            </section>

            <section className="sp-box sp-tone-mint" aria-label="Decision and result">
              <PanelHeading icon={GitBranch} title="Decision" meta={step?.label || "—"} />
              <div className="sp-box-content">
                <div className="sp-primary">
                  {aReview.goalStatus ? `${aReview.goalStatus} · ${shortAction(aReview.nextAction || "NONE")}` :
                    aAction.capability ? `${aAction.capability} · ${humanStatus(action?.status)}` :
                      aGraph.resultCount != null ? `${aGraph.resultCount} graph result(s)` :
                        step?.label === "Complete" ? "Mission finalized" : "No decision emitted"}
                </div>
                <div className="sp-sub">{aReview.validation ? `Java: ${aReview.validation}` :
                  aAction.capability ? `Capability attempt ${aAction.attempt || "1"}` : "From emitted mission events"}</div>
              </div>
            </section>

            <section className="sp-box sp-tone-blue" aria-label="Resolved entities and evidence">
              <PanelHeading icon={FileSearch} title="Entities / evidence"
                meta={entities.evidence == null ? "No count" : `${entities.evidence} refs`} />
              <div className="sp-box-content">
                {entities.state === "not-started" ? <>
                  <div className="sp-primary sp-muted-primary">Not searched</div>
                  <div className="sp-sub">No entity retrieval attempt recorded</div>
                </> : entities.state === "failed" ? <>
                  <div className="sp-primary sp-text-danger"><AlertCircle size={15} aria-hidden="true" /> Search failed</div>
                  <div className="sp-sub" title={entities.failureDetail}>
                    {entities.failedAttempts} failed {entities.failedAttempts === 1 ? "attempt" : "attempts"}
                    {entities.failureDetail ? ` · ${entities.failureDetail}` : " · No result emitted"}
                  </div>
                </> : <>
                  <div className="sp-primary">{entities.state === "found" ?
                    entities.results != null ? `${entities.results} graph results` :
                      entities.roots != null ? `${entities.roots} graph roots` :
                        `${entities.names.length} named entities` :
                    entities.state === "empty" ? "0 graph results" : "Search attempted"}</div>
                  <div className="sp-sub">{entities.names.length ? entities.names.join(" · ") :
                    entities.state === "attempted" ? "No verified result count in trace" :
                      entities.type ? `${entities.type} · names not in trace` : "Entity names not emitted"}</div>
                </>}
                {(entities.evidence != null || stepEvidenceAdded > 0) && <div className="sp-footnote">
                  {entities.evidence != null ? `${entities.evidence} cumulative evidence refs` : "Evidence count unavailable"}
                  {stepEvidenceAdded > 0 ? ` · +${stepEvidenceAdded} this step` : ""}
                </div>}
              </div>
            </section>

            <section className="sp-box sp-tone-amber" aria-label="JEV decision">
              <PanelHeading icon={Scale} title="JEV"
                meta={vote ? (aVote.decision || "Recorded") : "No vote"} />
              <div className="sp-box-content">
                {vote ? <>
                  <div className="sp-primary">{aVote.field || "vote"}: {aVote.choice || aVote.vote || "—"}</div>
                  <div className="sp-sub">Confidence {aVote.confidence || "?"} · threshold {aVote.threshold || "?"}</div>
                </> : <>
                  <div className="sp-primary sp-muted-primary">No vote recorded</div>
                  <div className="sp-sub">No JEV vote emitted for this step</div>
                </>}
              </div>
            </section>

            <section className="sp-box sp-tone-rose" aria-label="LLM invocation and output">
              <PanelHeading icon={BrainCircuit} title="LLM"
                meta={llmEnd ? aLlmEnd.outcome || "Received" : llmStart ? "Running" : "No call"} />
              <div className="sp-box-content">
                {llmStart || llmEnd ? <>
                  <div className="sp-primary" title={aLlmEnd.model}>
                    {aLlmEnd.model || aLlmEnd.provider || "Structured decision"} · {aLlm.fieldCount || aLlmEnd.fieldCount || "?"} fields
                  </div>
                  <div className="sp-sub">{aProposal.goalStatus ?
                    `Proposal: ${aProposal.goalStatus}${aProposal.nextAction ? ` / ${shortAction(aProposal.nextAction)}` : ""}` :
                    aLlm.stage || "Structured generation"}</div>
                  <div className="sp-footnote">Prompt and full response not in SSE</div>
                </> : <>
                  <div className="sp-primary sp-muted-primary">No LLM call recorded</div>
                  <div className="sp-sub">No model event emitted for this step</div>
                </>}
              </div>
            </section>

            <section className="sp-box sp-tone-teal" aria-label="Sources and tools">
              <PanelHeading icon={Database} title="Sources & tools"
                meta={sources.tools.length ? `${sources.tools.length} tools` : "No tools"} />
              <div className="sp-box-content">
                {sources.attempts ? <>
                  <div className="sp-primary">{sources.attempts} capability {sources.attempts === 1 ? "attempt" : "attempts"}
                    {sources.failures > 0 ? <span className="sp-small-error"> · {sources.failures} failed</span> : ""}
                  </div>
                  <div className="sp-sub" title={sources.tools.join(" · ")}>{sources.tools.join(" · ") || "Tool names not emitted"}</div>
                  <div className="sp-footnote">{sources.observedResults} service/result events emitted</div>
                </> : <>
                  <div className="sp-primary sp-muted-primary">No tool calls recorded</div>
                  <div className="sp-sub">As of the selected step</div>
                </>}
              </div>
            </section>
          </div>

          <section className="sp-box sp-events sp-tone-slate" aria-label="Event history for selected step">
            <PanelHeading icon={Activity} title="Event history" meta={
              <span className="sp-event-controls">
                <span>{rows.length} events</span>
                <button type="button" onClick={() => void copyEvents()} disabled={!step}
                  title="Copy all trace events from this step" aria-label="Copy step events as JSON">
                  {copyState === "copied" ? <Check size={14} /> : <Copy size={14} />}
                  {copyState === "copied" ? "Copied" : copyState === "error" ? "Copy failed" : "Copy JSON"}
                </button>
              </span>
            } />
            <div className="sp-event-table-head" aria-hidden="true">
              <span>Time</span><span>Event</span><span>Status</span><span>Details</span><span>Duration</span>
            </div>
            <div className="sp-event-list" key={step?.id ?? "no-step"} role="region" aria-label="Scrollable event history" tabIndex={0}>
              {rows.length ? rows.map(t => (
                <div className="sp-event" key={t.id} title={`${t.kind || t.title}\n${JSON.stringify(attrs(t), null, 2)}`}>
                  <span className="sp-event-time">{clock(eventTime(t))}</span>
                  <span className="sp-event-title"><span className={`sp-event-dot sp-dot-${statusKind(t.status)}`} aria-hidden="true" />
                    <strong>{t.title || t.kind}</strong></span>
                  <span className="sp-event-status">{t.status ? <StatusChip status={t.status} /> : <span className="sp-event-no-status">—</span>}</span>
                  <span className="sp-event-description">{eventDetails(t) || "—"}</span>
                  <span className="sp-event-duration">{duration(t.durationMs)}</span>
                </div>
              )) : <div className="sp-event-empty"><Info size={16} aria-hidden="true" /> No additional events for this step</div>}
            </div>
            <div className="sp-event-footer">
              <span>Recorded events only · no generated reasoning text</span>
              <span>{rows.length} events · scroll to explore</span>
            </div>
          </section>
        </div>
        )}
      </div>
    </section>
  );
};
