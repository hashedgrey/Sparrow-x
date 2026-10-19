/** A sector remains open as new events stream in. Only an explicit user action folds it. */
export function sectorIsExpanded(
  collapsed: Readonly<Record<string, boolean>>,
  sectorId: string,
): boolean {
  return collapsed[sectorId] !== true;
}

/** Do not infer the default state from trace count; streaming must never toggle this. */
export function toggleSector(
  collapsed: Readonly<Record<string, boolean>>,
  sectorId: string,
): Record<string, boolean> {
  return { ...collapsed, [sectorId]: collapsed[sectorId] !== true };
}
