import type { SparrowTrace } from "@/lib/sparrowx-chat-adapter";

export type TraceRow = { trace: SparrowTrace; depth: number; children: number; expandable: boolean };

/** Parent references are honored only within the same sector; cycles are broken safely. */
export function makeTraceTree(
  traces: SparrowTrace[],
  expanded: Record<string, boolean>,
): TraceRow[] {
  const byId = new Map(traces.map((span) => [span.id, span]));
  const children = new Map<string, SparrowTrace[]>();
  const roots: SparrowTrace[] = [];
  for (const span of traces) {
    const parent = span.parentId && byId.get(span.parentId);
    if (!parent || parent.id === span.id || parent.sectorId !== span.sectorId) {
      roots.push(span);
    } else {
      const branch = children.get(parent.id) ?? [];
      branch.push(span);
      children.set(parent.id, branch);
    }
  }
  const output: TraceRow[] = [];
  const visited = new Set<string>();
  const walk = (span: SparrowTrace, depth: number) => {
    if (visited.has(span.id)) return;
    visited.add(span.id);
    const branch = children.get(span.id) ?? [];
    output.push({ trace: span, depth: Math.min(depth, 12), children: branch.length, expandable: branch.length > 0 });
    if (expanded[span.id] !== false) for (const child of branch) walk(child, depth + 1);
  };
  for (const root of roots) walk(root, 0);
  // Handle true cycles, but never re-add intentionally collapsed descendants.
  const structural = new Set<string>();
  const mark = (span: SparrowTrace) => {
    if (structural.has(span.id)) return;
    structural.add(span.id);
    for (const child of children.get(span.id) ?? []) mark(child);
  };
  for (const root of roots) mark(root);
  for (const span of traces) if (!structural.has(span.id)) walk(span, 0);
  return output;
}
