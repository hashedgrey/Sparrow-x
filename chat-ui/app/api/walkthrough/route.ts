import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { existsSync } from "node:fs";
import { NextResponse } from "next/server";
import type { WalkthroughGraph } from "@/lib/walkthrough/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Architecture-only SQLite. Do not point this at agsvc's durable mission database. */
export async function GET(): Promise<NextResponse> {
  const file = resolve(process.env.SP_WALKTHROUGH_DB_PATH || "data/walkthrough.sqlite");
  if (!existsSync(file)) {
    return NextResponse.json({ error: "Walkthrough graph not configured", hint: "Set SP_WALKTHROUGH_DB_PATH to an existing SQLite file" }, { status: 503 });
  }
  let db: DatabaseSync | undefined;
  try {
    db = new DatabaseSync(file, { readOnly: true });
    const read = <T,>(sql: string): T[] => db!.prepare(sql).all() as T[];
    const graph: WalkthroughGraph = {
      stages: read("SELECT * FROM stages ORDER BY sort_order, id"),
      transitions: read("SELECT * FROM transitions ORDER BY id"),
      sourceFiles: read("SELECT * FROM source_files ORDER BY path"),
      stageFiles: read("SELECT * FROM stage_files ORDER BY stage_id, file_id"),
      sourceSpans: read("SELECT * FROM source_spans ORDER BY id"),
      runtimeRules: read("SELECT * FROM runtime_rules ORDER BY priority, id"),
      callEdges: read("SELECT * FROM call_edges ORDER BY id"),
      // Optional for older installations: the UI falls back to source responsibilities.
      narratives: db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'node_narratives'").get()
        ? read("SELECT * FROM node_narratives ORDER BY file_id") : [],
    };
    return NextResponse.json(graph, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    console.error("Walkthrough graph load failed", error);
    return NextResponse.json({ error: "Walkthrough database could not be read" }, { status: 500 });
  } finally { db?.close(); }
}
