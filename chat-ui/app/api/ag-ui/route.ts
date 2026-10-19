import { randomUUID } from "node:crypto";
import { NextRequest } from "next/server";
import { parseSseBlock, SparrowAgUiBridge, type AgUiWireEvent } from "@/lib/ag-ui/bridge";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** AG-UI protocol facade for the existing SparrowX chat SSE endpoint.
 * Keep /api/chat until all clients migrate; no AGSvc rewrite required.
 */
export async function POST(request: NextRequest): Promise<Response> {
  const input = await request.json() as {
    query?: string;
    conversationId?: string;
    threadId?: string;
    runId?: string;
    messages?: Array<{ role?: string; content?: string | Array<{ type: string; text?: string }> }>;
  };
  const latest = input.messages?.filter((m) => m.role === "user").at(-1);
  const userText = typeof latest?.content === "string" ? latest.content : Array.isArray(latest?.content) ? latest.content.map((c) => c.type === "text" ? c.text ?? "" : "").join("\n") : "";
  const query = input.query?.trim() || userText.trim();
  if (!query) return new Response("Missing user query", { status: 400 });

  const threadId = input.threadId || input.conversationId || randomUUID();
  const runId = input.runId || randomUUID();
  // Reuse the current Next.js proxy for authentication and mission submission.
  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  if (request.headers.has("cookie")) headers.set("cookie", request.headers.get("cookie")!);
  if (request.headers.has("authorization")) headers.set("authorization", request.headers.get("authorization")!);
  const upstream = await fetch(new URL("/api/chat", request.url), {
    method: "POST", headers, body: JSON.stringify({ query, conversationId: threadId }),
    signal: request.signal, cache: "no-store",
  });
  if (!upstream.ok || !upstream.body) {
    return new Response(await upstream.text().catch(() => "SparrowX unavailable"), { status: upstream.status });
  }

  const bridge = new SparrowAgUiBridge(threadId, runId);
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const emit = (event: AgUiWireEvent) => controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
      const reader = upstream.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      try {
        while (true) {
          const { done, value } = await reader.read();
          buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
          buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
          let boundary = buffer.indexOf("\n\n");
          while (boundary !== -1) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const event = parseSseBlock(block);
            if (event?.event === "heartbeat") controller.enqueue(encoder.encode(": heartbeat\n\n"));
            else if (event) for (const mapped of bridge.convert(event)) emit(mapped);
            boundary = buffer.indexOf("\n\n");
          }
          if (done) {
            const tail = parseSseBlock(buffer.trim());
            if (tail) for (const mapped of bridge.convert(tail)) emit(mapped);
            for (const mapped of bridge.unexpectedEnd()) emit(mapped);
            break;
          }
        }
      } catch (error) {
        if (!request.signal.aborted) {
          for (const mapped of bridge.unexpectedEnd()) emit(mapped);
        }
      } finally {
        reader.releaseLock();
        try { controller.close(); } catch { /* client disconnected */ }
      }
    },
    cancel() { void upstream.body?.cancel().catch(() => undefined); },
  });
  return new Response(stream, { headers: {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform", "Connection": "keep-alive", "X-Accel-Buffering": "no",
  } });
}
