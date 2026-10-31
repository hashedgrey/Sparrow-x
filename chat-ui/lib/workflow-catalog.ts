/** Embabel Workflow Visualizer 1.5.x public catalog, not mission execution telemetry. */
export type WorkflowStep = {
  name: string;
  type: string;
  description?: string | null;
  method?: string | null;
  inputs?: string[];
  optionalInputs?: string[];
  output?: string | null;
  pre?: string[];
  post?: string[];
  goal?: boolean;
  cost?: number | null;
  value?: number | null;
  goalValue?: number | null;
  costMethod?: string | null;
  valueMethod?: string | null;
  registered?: boolean | null;
  plannerGenerated?: boolean;
  canRerun?: boolean;
  readOnly?: boolean;
  exportedRemote?: boolean;
  trigger?: string | null;
  tags?: string[];
};

export type WorkflowFlowNode = {
  id: string;
  kind: string;
  step: string | null;
  label: string | null;
  detail: string | null;
};
export type WorkflowFlowEdge = {
  from: string;
  to: string;
  types: string[];
  conditions: string[];
  loop: boolean;
  paths: number[];
};
export type WorkflowPath = {
  goal: string;
  steps: string[];
  totalCost: number | null;
  dynamicCost: boolean;
  cheapest: boolean;
};
export type WorkflowFlow = {
  entryTypes: string[];
  nodes: WorkflowFlowNode[];
  edges: WorkflowFlowEdge[];
  paths: WorkflowPath[];
  truncated: boolean;
};
export type AgentWorkflow = {
  agentName: string;
  description?: string | null;
  className: string;
  plannerType: string;
  provider?: string | null;
  registered?: boolean | null;
  opaque?: boolean;
  steps: WorkflowStep[];
  flow: WorkflowFlow | null;
};
export type WorkflowCatalog = { agents: AgentWorkflow[] };

const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const text = (v: unknown): string => typeof v === "string" ? v : "";
const nullableText = (v: unknown): string | null => typeof v === "string" ? v : null;
const strings = (v: unknown): string[] => Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
const nullableNumber = (v: unknown): number | null => typeof v === "number" && Number.isFinite(v) ? v : null;
const nullableBool = (v: unknown): boolean | null => typeof v === "boolean" ? v : null;

/** Reject a changed or broken response rather than inventing edges or dummy agents. */
export function parseWorkflowCatalog(raw: unknown): WorkflowCatalog {
  if (!record(raw) || !Array.isArray(raw.agents)) throw new Error("Invalid Embabel workflow catalog: agents array is missing");
  if (raw.agents.length > 400) throw new Error("Workflow catalog exceeds supported agent limit");
  const agents: AgentWorkflow[] = raw.agents.map((item, i) => {
    if (!record(item) || !Array.isArray(item.steps) || !text(item.className))
      throw new Error(`Invalid workflow agent at index ${i}`);
    if (item.steps.length > 400) throw new Error("Workflow exceeds supported step limit");
    const steps: WorkflowStep[] = item.steps.map(s => {
      if (!record(s) || !text(s.name)) throw new Error("Invalid workflow step");
      return {
        name: text(s.name), type: text(s.type), description: nullableText(s.description),
        method: nullableText(s.method), inputs: strings(s.inputs), optionalInputs: strings(s.optionalInputs),
        output: nullableText(s.output), pre: strings(s.pre), post: strings(s.post),
        goal: s.goal === true, cost: nullableNumber(s.cost), value: nullableNumber(s.value),
        goalValue: nullableNumber(s.goalValue), costMethod: nullableText(s.costMethod),
        valueMethod: nullableText(s.valueMethod), registered: nullableBool(s.registered),
        plannerGenerated: s.plannerGenerated === true, canRerun: s.canRerun === true,
        readOnly: s.readOnly === true, exportedRemote: s.exportedRemote === true,
        trigger: nullableText(s.trigger), tags: strings(s.tags),
      };
    });
    let flow: WorkflowFlow | null = null;
    if (item.flow != null) {
      const f = item.flow;
      if (!record(f) || !Array.isArray(f.nodes) || !Array.isArray(f.edges) || !Array.isArray(f.paths))
        throw new Error(`Invalid flow for ${text(item.agentName) || text(item.className)}`);
      if (f.nodes.length > 1000 || f.edges.length > 3000 || f.paths.length > 300)
        throw new Error("Workflow graph exceeds supported limits");
      flow = {
        entryTypes: strings(f.entryTypes), truncated: f.truncated === true,
        nodes: f.nodes.map(n => {
          if (!record(n) || !text(n.id) || !text(n.kind)) throw new Error("Invalid workflow node");
          return { id: text(n.id), kind: text(n.kind), step: nullableText(n.step), label: nullableText(n.label), detail: nullableText(n.detail) };
        }),
        edges: f.edges.map(e => {
          if (!record(e) || !text(e.from) || !text(e.to)) throw new Error("Invalid workflow edge");
          return { from: text(e.from), to: text(e.to), types: strings(e.types), conditions: strings(e.conditions),
            loop: e.loop === true, paths: Array.isArray(e.paths) ? e.paths.filter((n): n is number => Number.isInteger(n) && n >= 0) : [] };
        }),
        paths: f.paths.map(p => {
          if (!record(p)) throw new Error("Invalid workflow path");
          return { goal: text(p.goal), steps: strings(p.steps), totalCost: nullableNumber(p.totalCost),
            dynamicCost: p.dynamicCost === true, cheapest: p.cheapest === true };
        }),
      };
    }
    return { agentName: text(item.agentName) || text(item.className).split(".").at(-1) || "Agent",
      className: text(item.className), description: nullableText(item.description),
      plannerType: text(item.plannerType) || "UNKNOWN", provider: nullableText(item.provider),
      registered: nullableBool(item.registered), opaque: item.opaque === true, steps, flow };
  });
  return { agents };
}
