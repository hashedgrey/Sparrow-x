import { NextRequest } from "next/server";
import { parseWorkflowCatalog } from "@/lib/workflow-catalog";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Never expose the upstream URL or its diagnostics to unauthenticated clients. */
const noCacheHeaders = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };
const errorResponse = (message: string, status: number) =>
  Response.json({ error: message }, { status, headers: noCacheHeaders });

export async function GET(request: NextRequest): Promise<Response> {
  // This route is read-only and refuses anonymous catalog discovery.
  // Replace this with SP's server-side session verification when available.
  const authorization = request.headers.get("authorization");
  if (!authorization || !/^(Basic|Bearer)\s+\S+/i.test(authorization)) {
    return errorResponse("Authentication required to inspect agent workflows", 401);
  }

  const base = process.env.AGENTIC_SERVICE_URL || "http://localhost:8086";
  let endpoint: URL;
  try {
    endpoint = new URL("/embabel-workflows/api", base);
    if (!(["http:", "https:"].includes(endpoint.protocol))) throw new Error("Unsupported protocol");
  } catch { return errorResponse("Workflow catalog upstream is not configured", 503); }

  let response: Response;
  try {
    response = await fetch(endpoint, {
      method: "GET", cache: "no-store",
      headers: { Accept: "application/json", Authorization: authorization },
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(10000)]),
    });
  } catch { return errorResponse("Workflow catalog is unavailable", 502); }

  if (response.status === 401 || response.status === 403)
    return errorResponse("Workflow catalog credentials are invalid or unauthorized", response.status);
  if (response.status === 404)
    return errorResponse("Workflow visualizer endpoint is unavailable; enable the AGSvc starter", 503);
  if (!response.ok) return errorResponse("Workflow catalog request failed", 502);
  if (!response.headers.get("content-type")?.includes("application/json"))
    return errorResponse("Workflow catalog returned an unexpected content type", 502);

  try {
    // Avoid forwarding an arbitrary large internal response; return only validated fields.
    const body = await response.text();
    if (body.length > 3_000_000) return errorResponse("Workflow catalog is too large", 502);
    const catalog = parseWorkflowCatalog(JSON.parse(body) as unknown);
    return Response.json(catalog, { headers: noCacheHeaders });
  } catch { return errorResponse("Workflow catalog response is invalid", 502); }
}
