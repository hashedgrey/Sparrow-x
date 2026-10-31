"use client";

import { useEffect, useMemo, useRef, useState, type FC, type ReactNode } from "react";
import {
  Activity, AlertCircle, ArrowRight, BrainCircuit, Check,
  ChevronLeft, ChevronRight, Circle, Clock3, Copy, Database,
  Flag, GitBranch, History, Info, LoaderCircle, MessageSquare, Route,
  Scale, Search, Target, type LucideIcon,
} from "lucide-react";
import type { SparrowMetadata, SparrowTrace } from "@/lib/sparrowx-chat-adapter";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  attrs, buildWalkthrough, embabelPlanView, eventDetails, eventTime,
  firstKind, isFailureStatus, lastKind, normalizedTraces, shortAction,
} from "./mission-walkthrough-data";
import { usePublishMissionSummary, type MissionHeaderSnapshot } from "../mission-workspace";
import "./mission-trace-dashboard.css";

type Props = {
  metadata?: SparrowMetadata;
  traces: SparrowTrace[];
  showEmpty?: boolean;
  /** Always-mounted assistant-ui conversation; never clone or transform messages. */
  conversation?: ReactNode;
  /** The real assistant-ui composer, displayed in Chat rather than the toolbar. */
  composer?: ReactNode;
  hasConversation?: boolean;
  /** The parent controls the Chat/metrics switch for streamed missions. */
  activeView?: "chat" | "metrics";
  onViewChange?: (view: "chat" | "metrics") => void;
  awaitingStream?: boolean;
  /** True from submission through completion; drives dots below Chat composer. */
  chatLoading?: boolean;
  chatNeedsAttention?: boolean;
  /** Retained as a fallback for standalone uses of this dashboard. */
  result?: string;
  /** Only the hidden source dashboard publishes to the toolbar; avoids context loops. */
  publishSummary?: boolean;
};


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
function capabilityLabel(capability?: string): string {
  switch (capability) {
    case "internal.entities.search": return "Find entities";
    case "internal.company-graph.read": return "Query company graph";
    case "document.evidence.build": return "Build document evidence";
    default: return capability ? shortAction(capability) : "No action selected";
  }
}

function emittedCount(value?: string): number | undefined {
  if (value == null || value.trim() === "") return undefined;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? number : undefined;
}

function stepIcon(label: string): LucideIcon {
  if (label.startsWith("Review")) return GitBranch;
  if (label === "Find entities") return Search;
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
  metadata, traces, showEmpty = false, result = "", conversation, composer,
  hasConversation = false, activeView, onViewChange,
  awaitingStream = false, chatLoading = false, chatNeedsAttention = false, publishSummary = true,
}) => {
  const normalized = useMemo(() => normalizedTraces(traces), [traces]);
  const steps = useMemo(() => buildWalkthrough(traces), [traces]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [standaloneView, setStandaloneView] = useState<"chat" | "metrics">(showEmpty ? "chat" : "metrics");
  const currentView = activeView ?? standaloneView;
  const isChatSelected = currentView === "chat";
  function chooseView(next: "chat" | "metrics") {
    if (onViewChange) onViewChange(next);
    else setStandaloneView(next);
  }
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

  // Chat is always pinned outside the step list, never counted as a GOAP step.
  const historyItems = steps.map(s => ({ id: s.id, label: s.label, at: s.at, status: s.anchor.status }));
  const stepsPerPage = compact ? 5 : MAX_STEPS;
  const activeIndex = selectedId === null ? Math.max(0, steps.length - 1) :
    Math.max(0, steps.findIndex(s => s.id === selectedId));
  const step = steps[activeIndex];
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
  const failedSteps = steps.filter(s => isFailureStatus(s.anchor.status)).length;

  // Every metric below describes the selected step, not cumulative activity.
  const stepTraces = step?.traces || [];
  const plan = lastKind(stepTraces, "EMBABEL_PLAN_FORMULATED");
  const validation = lastKind(stepTraces, "JAVA_VALIDATION");
  const review = lastKind(stepTraces, "GOAL_EVALUATED") || validation;
  const action = firstKind(stepTraces, "CAPABILITY_ATTEMPT");
  const graphBound = lastKind(stepTraces, "GRAPH_QUERY_BOUND");
  const graphResult = lastKind(stepTraces, "GRAPH_QUERY_RESULT");
  const entitySearch = lastKind(stepTraces, "INTSVC_SEARCH");
  const evidenceSnapshot = lastKind(stepTraces, "MISSION_EVIDENCE_SNAPSHOT");
  const votes = stepTraces.filter(t => t.kind === "JEV_VOTE");
  const unresolved = stepTraces.filter(t => t.kind === "JEV_UNRESOLVED");
  const llmCalls = stepTraces.filter(t => t.kind === "LLM_GENERATION");
  const llmResults = stepTraces.filter(t => t.kind === "LLM_RESULT");
  const isReview = step?.anchor.kind === "REASON_LOOP";
  const isAction = step?.anchor.kind === "CAPABILITY_ATTEMPT";
  const displayedPlan = plan || lastKind(contextual, "EMBABEL_PLAN_FORMULATED");
  const planView = embabelPlanView(attrs(displayedPlan));
  const planIsPrevious = Boolean(displayedPlan && !plan);
  const planner = attrs(displayedPlan).plannerActual ||
    attrs(firstKind(normalized, "INTENT_SNAPSHOT")).plannerActual || "—";
  const missionStatus = String(metadata?.missionStatus || (metadata?.complete ? "COMPLETED" : showEmpty && !metadata?.missionId ? "READY" : "RUNNING"));
  const finalGoalStatus = attrs(lastKind(normalized, "MISSION_FINISH") || lastKind(normalized, "GOAL_BLOCKED")).goalStatus;

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

  const rows = stepTraces.filter(t => !OMIT_FROM_DETAIL.has(t.kind || ""));
  const aReview = attrs(review);
  const aValidation = attrs(validation);
  const aAction = attrs(action);
  const aGraph = attrs(graphResult);
  const aBound = attrs(graphBound);
  const aSearch = attrs(entitySearch);
  const aSnapshot = attrs(evidenceSnapshot);
  const stepEvidenceAdded = stepTraces.reduce((total, t) => {
    if (t.kind !== "MISSION_EVIDENCE_DELTA") return total;
    return total + (emittedCount(attrs(t).addedEvidence) ?? 0);
  }, 0);
  const beforeEvidence = emittedCount(aSnapshot.evidenceCount);
  const rejectedVotes = votes.filter(t => attrs(t).decision === "BELOW_THRESHOLD" || attrs(t).decision === "UNRESOLVED").length;

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
    chooseView("metrics");
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
          <button type="button"
            className={`sp-chat-pin ${isChatSelected ? "sp-chat-pin-selected" : ""} ${chatNeedsAttention && !isChatSelected ? "sp-chat-pin-attention" : ""}`}
            aria-current={isChatSelected ? "page" : undefined}
            aria-label={chatNeedsAttention && !isChatSelected ? "Chat, new result available" : "Chat"}
            onClick={() => chooseView("chat")}
          >
            <span className="sp-chat-pin-icon" aria-hidden="true"><MessageSquare size={16} /></span>
            <span className="sp-chat-pin-label">Chat</span>
            {chatNeedsAttention && !isChatSelected ? <span className="sp-chat-pin-new">New result</span> : null}
          </button>
          <div className="sp-step-list">
            {visibleSteps.map((s, offset) => {
              const index = effectiveStepPage * stepsPerPage + offset;
              const selected = !isChatSelected && index === activeIndex;
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
            <span>{isChatSelected ? "Chat" : historyItems.length ? `Step ${activeIndex + 1} / ${historyItems.length}` : "No steps"}</span>
            <span>{historyItems[activeIndex] ? clock(historyItems[activeIndex]!.at) : "—"}</span>
          </div>
        </aside>

        <div className="sp-main-stage">
          <section className={`sp-chat-pane ${!isChatSelected ? "sp-pane-hidden" : ""} ${!hasConversation ? "sp-chat-empty" : ""}`}
            aria-label="Chat" aria-hidden={!isChatSelected}>
            {!hasConversation && <div className="sp-chat-welcome">
              <MessageSquare size={24} strokeWidth={1.8} aria-hidden="true" />
              <h2>What can Arrow help you with?</h2>
            </div>}
            <div className="sp-chat-thread">
              {conversation || (result.trim() ? <div className="sp-result-markdown" role="document">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{result}</ReactMarkdown>
              </div> : null)}
            </div>
            {composer && <div className="sp-chat-composer-dock">{composer}</div>}
            {chatLoading && <div className="sp-chat-awaiting" role="status" aria-live="polite">
              <span className="sp-chat-loading-dots" aria-hidden="true">
                <span /><span /><span />
              </span>
              <span>{awaitingStream ? "Connecting to mission stream…" : "Arrow is working…"}</span>
            </div>}
          </section>
          <div className={`sp-content ${isChatSelected ? "sp-pane-hidden" : ""}`} aria-hidden={isChatSelected}>
          <div className="sp-card-grid sp-timeline-card-grid">
            <section className="sp-box sp-tone-violet" aria-label="Embabel plan">
              <PanelHeading icon={Target} title="Embabel plan"
                meta={attrs(displayedPlan).planIteration ? `Iteration ${attrs(displayedPlan).planIteration}` : "No plan"} />
              <div className="sp-box-content">
                <div className="sp-primary" title={planView.rawPlan || planView.actions.map(a => a.id).join(" → ")}>
                  {planView.preview}
                </div>
                <div className="sp-sub">{!displayedPlan ? "No Embabel plan emitted" : planIsPrevious ? "Most recent prior plan" : "Plan associated with this step"}
                  {attrs(displayedPlan).replanning === "true" ? " · Replanned" : ""}
                </div>
                <div className="sp-footnote">Embabel planner target: {attrs(displayedPlan).goal || "Not emitted"}; this is not user-answer certification.</div>
                {planView.actions.length > 0 && <details className="sp-plan-details">
                  <summary>Action sequence · {planView.actions.length}{planView.total > planView.actions.length ? ` of ${planView.total}` : ""}</summary>
                  <ol className="sp-plan-sequence">{planView.actions.map((item, index) =>
                    <li key={`${index}-${item.id}`}><span>{item.label}</span><code title={item.id}>{item.id}</code></li>)}</ol>
                  {planView.truncated && <div className="sp-plan-warning">The emitted plan is truncated.</div>}
                  {planView.rawPlan && <div className="sp-plan-original"><strong>Original Embabel action identifiers</strong><code>{planView.rawPlan}</code></div>}
                </details>}
              </div>
            </section>

            <section className="sp-box sp-tone-mint" aria-label="Goal review and selected action">
              <PanelHeading icon={GitBranch} title={isReview ? "Goal review" : "Selected action"} meta={step?.label || "—"} />
              <div className="sp-box-content">
                {isReview ? <>
                  <div className={`sp-primary ${aReview.goalStatus === "BLOCKED" ? "sp-text-danger" : ""}`}>
                    Goal: {aReview.goalStatus || "Not yet evaluated"}
                  </div>
                  <div className="sp-timeline-fact"><strong>Next action</strong><span>{capabilityLabel(aReview.nextAction)}</span></div>
                  <div className="sp-sub">{aValidation.validation === "FAST_PATH_OR_NO_LLM"
                    ? "Validated by Java fast path; no goal-review LLM was required"
                    : aValidation.validation ? `Validation: ${aValidation.validation}` : "Derived from emitted goal-review events"}</div>
                  {aReview.goalStatus === "BLOCKED" && <div className="sp-timeline-warning">Goal was not certified. This does not negate any verified graph rows.</div>}
                </> : isAction ? <>
                  <div className="sp-primary">{capabilityLabel(aAction.capability)}</div>
                  <div className="sp-timeline-fact"><strong>Capability</strong><span>{aAction.capability || "Not emitted"}</span></div>
                  <div className="sp-sub">Attempt {aAction.attempt || "?"} · Execution {humanStatus(action?.status)}</div>
                  <div className="sp-footnote">JEV and LLM decisions made inside this action are shown in Model decisions.</div>
                </> : step?.anchor.kind === "MISSION_FINISH" ? <>
                  <div className="sp-primary">Execution {humanStatus(missionStatus)}</div>
                  <div className={`sp-timeline-fact ${finalGoalStatus === "BLOCKED" ? "sp-text-danger" : ""}`}>
                    <strong>Answer certification</strong><span>{finalGoalStatus || "Not emitted"}</span>
                  </div>
                  {finalGoalStatus === "BLOCKED" && <div className="sp-timeline-warning">An answer was produced, but AGSVC did not certify the goal.</div>}
                </> : step?.anchor.kind === "RESPONSE_SYNTHESIS" ? <>
                  <div className="sp-primary">Answer synthesis</div>
                  <div className="sp-sub">Goal status at synthesis: {attrs(step.anchor).goalStatus || "Not emitted"}</div>
                </> : <>
                  <div className="sp-primary">{step?.label || "Waiting for the mission"}</div>
                  <div className="sp-sub">The selected phase has no goal decision or capability selection.</div>
                </>}
              </div>
            </section>

            <section className="sp-box sp-tone-amber" aria-label="JEV and LLM model decisions">
              <PanelHeading icon={Scale} title="Model decisions" meta={`${votes.length} JEV · ${llmCalls.length} LLM`} />
              <div className="sp-box-content">
                {!votes.length && !llmCalls.length ? <>
                  <div className="sp-primary sp-muted-primary">No model decision in this step</div>
                  <div className="sp-sub">{isReview && aValidation.validation === "FAST_PATH_OR_NO_LLM"
                    ? "Goal review used Java fast-path validation"
                    : "No JEV votes or LLM invocations emitted"}</div>
                </> : <>
                  {!!votes.length && <div className="sp-timeline-group-heading">JEV · {votes.length} vote(s){rejectedVotes ? ` · ${rejectedVotes} below threshold` : ""}</div>}
                  {votes.map(t => {
                    const a = attrs(t);
                    const below = a.decision === "BELOW_THRESHOLD";
                    return <div className="sp-timeline-vote" key={t.id}>
                      <div><strong>{a.field || "Decision"}</strong><span>{a.choice || "—"}</span></div>
                      <div className={below ? "sp-timeline-vote-rejected" : "sp-sub"}>
                        Confidence {a.confidence || "?"} / required {a.threshold || "?"} · {below ? "Not accepted" : a.decision || "Recorded"}
                      </div>
                    </div>;
                  })}
                  {!!unresolved.length && <div className="sp-footnote">{unresolved.length} unresolved bounded check(s) recorded</div>}
                  {!!llmCalls.length && <div className="sp-timeline-group-heading">LLM · {llmCalls.length} invocation(s)</div>}
                  {llmCalls.map(call => <div className="sp-timeline-vote" key={call.id}>
                    <div><strong>{attrs(call).stage || "Structured generation"}</strong>
                      <span>{attrs(call).fieldCount || "?"} fields</span></div>
                    <div className="sp-sub">{attrs(llmResults.find(response => response.parentId === call.id)).model || "Model response pending"}
                      {llmResults.some(response => response.parentId === call.id) ? " · Response received" : ""}</div>
                  </div>)}
                  {!!llmCalls.length && <div className="sp-footnote">The stream records LLM invocation and receipt, not the full structured output or accepted field values.</div>}
                </>}
              </div>
            </section>

            <section className="sp-box sp-tone-teal" aria-label="Tool execution and verified evidence">
              <PanelHeading icon={Database} title="Tool & evidence"
                meta={stepEvidenceAdded ? `+${stepEvidenceAdded} evidence` : "This step"} />
              <div className="sp-box-content">
                {graphResult ? <>
                  <div className="sp-primary">{aGraph.resultCount || "?"} matching graph rows</div>
                  <div className="sp-sub">Evaluated {aGraph.rootCount || "?"} {aGraph.rootType || ""} roots · {aGraph.complete === "true" && aGraph.truncated === "false" ? "Complete result" : "Completeness unverified"}</div>
                  <div className="sp-timeline-fact"><strong>Query</strong><span>{aBound.traversalCount || "?"} traversals · {aBound.predicateCount || "?"} predicates · {aBound.projectionCount || "?"} projections</span></div>
                  <div className="sp-footnote">Query completeness confirms the executed scope, not mission-goal certification.</div>
                </> : entitySearch ? <>
                  <div className="sp-primary">Internal entity search completed</div>
                  <div className="sp-sub">Source: {aSearch.service || "INTSVC"} · Root type: {aSearch.rootType || "Not emitted"}</div>
                  <div className="sp-footnote">This search event does not emit candidate labels or counts; they are not inferred.</div>
                </> : isReview ? <>
                  <div className="sp-primary">{beforeEvidence === undefined ? "Evidence count not emitted" : `${beforeEvidence} evidence ref(s) before review`}</div>
                  <div className="sp-sub">No tool executed during this review step</div>
                </> : isAction ? <>
                  <div className="sp-primary">{aAction.capability ? "Capability execution recorded" : "No capability recorded"}</div>
                  <div className="sp-sub">A verified service-result event has not been emitted in this step yet.</div>
                </> : <>
                  <div className="sp-primary sp-muted-primary">No tool result for this step</div>
                  <div className="sp-sub">Open an action step for retrieval or graph-result details</div>
                </>}
                {stepEvidenceAdded > 0 && <div className="sp-timeline-fact"><strong>Stored evidence</strong><span>+{stepEvidenceAdded} reference(s) in this step</span></div>}
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
                  <span className="sp-event-status">{t.kind === "JEV_VOTE" && attrs(t).decision === "BELOW_THRESHOLD"
                    ? <span className="sp-chip sp-chip-neutral">Not accepted</span>
                    : t.kind === "JEV_UNRESOLVED" ? <span className="sp-chip sp-chip-neutral">Unresolved</span>
                    : t.kind === "GOAL_BLOCKED" ? <span className="sp-chip sp-chip-error">Blocked goal</span>
                    : t.status ? <StatusChip status={t.status} /> : <span className="sp-event-no-status">—</span>}</span>
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
        </div>
      </div>
    </section>
  );
};
