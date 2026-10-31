import type { AgentWorkflow, WorkflowStep } from "./workflow-catalog";

/**
 * Documentation-only state machine from the uploaded AGSvc MissionAgent.java (2026-10-29).
 * It is NOT an Embabel flow-catalog result and is NOT an observed execution.
 * Fail closed when live catalog signatures drift so outdated diagrams are not presented as fact.
 */
export type MissionStateNode = {
  id: string;
  label: string;
  kind: "input" | "action" | "state" | "decision" | "goal";
  x: number;
  y: number;
  stepName?: string;
  detail: string;
};
export type MissionStateEdge = {
  from: string;
  to: string;
  label?: string;
  feedback?: boolean;
};
export type MissionStateModel = {
  nodes: MissionStateNode[];
  edges: MissionStateEdge[];
  verifiedActionNames: string[];
};

const ACTION_REQUIREMENTS: Record<string, { input: string; output: string; pre?: string }> = {
  start: { input: "MissionRunInput", output: "ReasonState" },
  reason: { input: "ReasonState", output: "Stage" },
  searchEntities: { input: "ExecuteState", output: "ReasonState", pre: "missionSearchRecommended" },
  queryGraph: { input: "ExecuteState", output: "ReasonState", pre: "missionGraphRecommended" },
  retrieveDocuments: { input: "ExecuteState", output: "ReasonState", pre: "missionDocumentRecommended" },
  complete: { input: "FinishState", output: "MissionResult" },
};

function matches(step: WorkflowStep | undefined, requirement: { input: string; output: string; pre?: string }) {
  return step?.registered !== false && Boolean(step?.inputs?.includes(requirement.input)) &&
    step?.output === requirement.output &&
    (!requirement.pre || Boolean(step.pre?.includes(requirement.pre)));
}

export function buildMissionStateModel(agent: AgentWorkflow): MissionStateModel | null {
  if (agent.className !== "com.sparrowx.agentic.agents.MissionAgent") return null;
  const byName = new Map(agent.steps.map(step => [step.name, step]));
  if (!Object.entries(ACTION_REQUIREMENTS).every(([name, expected]) => matches(byName.get(name), expected))) return null;
  if (byName.get("complete")?.goal !== true) return null;

  const nodes: MissionStateNode[] = [
    { id: "input", label: "MissionRunInput", kind: "input", x: 20, y: 110,
      detail: "Mission request and interpreted intent enter the agent." },
    { id: "start", label: "start", kind: "action", x: 220, y: 110, stepName: "start",
      detail: "Initializes the mission with an empty evidence ledger and returns ReasonState." },
    { id: "reason-state", label: "ReasonState", kind: "state", x: 420, y: 110,
      detail: "Carries MissionRunInput and accumulated Progress to the reviewer." },
    { id: "reason", label: "reason", kind: "action", x: 620, y: 110, stepName: "reason",
      detail: "Runs goal review and returns either ExecuteState or FinishState; the Java signature exposes the common Stage type." },
    { id: "decision", label: "Review decision", kind: "decision", x: 820, y: 110,
      detail: "Decision.status()==NEEDS_EVIDENCE produces ExecuteState; every other status produces FinishState." },
    { id: "finish-state", label: "FinishState", kind: "state", x: 1030, y: 20,
      detail: "Terminal or blocked review; NEEDS_EVIDENCE is rejected by FinishState." },
    { id: "complete", label: "complete", kind: "goal", x: 1240, y: 20, stepName: "complete",
      detail: "The terminal @AchievesGoal action returns MissionResult. A blocked review can yield a truthful unresolved outcome." },
    { id: "execute-state", label: "ExecuteState", kind: "state", x: 820, y: 340,
      detail: "Reviewer has requested evidence. A single authorized capability is selected from the available proposals." },
    { id: "searchEntities", label: "searchEntities", kind: "action", x: 1050, y: 255, stepName: "searchEntities",
      detail: "Requires missionSearchRecommended. Returns updated ReasonState after evidence or failure is recorded." },
    { id: "queryGraph", label: "queryGraph", kind: "action", x: 1050, y: 355, stepName: "queryGraph",
      detail: "Requires missionGraphRecommended (including graph roots). Returns updated ReasonState." },
    { id: "retrieveDocuments", label: "retrieveDocuments", kind: "action", x: 1050, y: 455, stepName: "retrieveDocuments",
      detail: "Requires missionDocumentRecommended. Returns updated ReasonState." },
  ];
  const edges: MissionStateEdge[] = [
    {from:"input", to:"start"},
    {from:"start", to:"reason-state", label:"ReasonState"},
    {from:"reason-state", to:"reason"},
    {from:"reason", to:"decision", label:"Stage"},
    {from:"decision", to:"finish-state", label:"other review status"},
    {from:"finish-state", to:"complete"},
    {from:"decision", to:"execute-state", label:"NEEDS_EVIDENCE"},
    {from:"execute-state", to:"searchEntities", label:"missionSearchRecommended"},
    {from:"execute-state", to:"queryGraph", label:"missionGraphRecommended"},
    {from:"execute-state", to:"retrieveDocuments", label:"missionDocumentRecommended"},
    {from:"searchEntities", to:"reason-state", label:"updated ReasonState", feedback:true},
    {from:"queryGraph", to:"reason-state", label:"updated ReasonState", feedback:true},
    {from:"retrieveDocuments", to:"reason-state", label:"updated ReasonState", feedback:true},
  ];
  return {nodes, edges, verifiedActionNames: Object.keys(ACTION_REQUIREMENTS)};
}
