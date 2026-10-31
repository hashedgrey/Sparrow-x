import type { WorkflowFlow, WorkflowFlowEdge, WorkflowFlowNode } from "./workflow-catalog";

export type PositionedWorkflowNode = WorkflowFlowNode & { x: number; y: number };
export type PositionedWorkflowGraph = { nodes: PositionedWorkflowNode[]; edges: WorkflowFlowEdge[] };

/** Deterministic layered layout. Ignore explicitly backward loop edges for ranking. */
export function layoutWorkflow(flow: WorkflowFlow): PositionedWorkflowGraph {
  const byId = new Map(flow.nodes.map(n => [n.id, n]));
  const outgoing = new Map<string, string[]>();
  const indegree = new Map(flow.nodes.map(n => [n.id, 0]));
  const edges = flow.edges.filter(e => byId.has(e.from) && byId.has(e.to));
  for (const e of edges.filter(e => !e.loop && e.from !== e.to)) {
    outgoing.set(e.from, [...(outgoing.get(e.from) || []), e.to]);
    indegree.set(e.to, (indegree.get(e.to) || 0) + 1);
  }
  const rank = new Map<string, number>();
  const queue: string[] = [];
  for (const n of flow.nodes) {
    if (indegree.get(n.id) === 0) {
      rank.set(n.id, 0);
      queue.push(n.id);
    }
  }
  // Kahn ranking: stable, bounded even if an unmarked cycle exists.
  for (let at = 0; at < queue.length; at++) {
    const id = queue[at]!;
    for (const to of outgoing.get(id) || []) {
      rank.set(to, Math.max(rank.get(to) || 0, (rank.get(id) || 0) + 1));
      indegree.set(to, (indegree.get(to) || 0) - 1);
      if (indegree.get(to) === 0) queue.push(to);
    }
  }
  const maxRank = Math.max(0, ...rank.values());
  // Disconnected/implicit-cycle nodes still remain visible, never silently excluded.
  for (const n of flow.nodes) if (!rank.has(n.id)) rank.set(n.id, maxRank + 1);
  const columns = new Map<number, WorkflowFlowNode[]>();
  for (const n of flow.nodes) {
    const col = rank.get(n.id) || 0;
    columns.set(col, [...(columns.get(col) || []), n]);
  }
  const nodes: PositionedWorkflowNode[] = [];
  for (const [col, items] of [...columns.entries()].sort((a, b) => a[0] - b[0])) {
    const ordered = [...items].sort((a,b) => {
      const order = (n: WorkflowFlowNode) => n.kind === "START" ? 0 : n.kind === "END" ? 2 : 1;
      return order(a) - order(b) || a.id.localeCompare(b.id);
    });
    for (let row = 0; row < ordered.length; row++) {
      nodes.push({ ...ordered[row]!, x: col * 258 + 25, y: row * 128 + 35 });
    }
  }
  return { nodes, edges };
}
