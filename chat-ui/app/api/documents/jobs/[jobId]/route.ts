import { getDocumentHttpUpstream, upstreamResponse } from "@/lib/document-http-upstream";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(
  request: Request,
  { params }: { params: Promise<{ jobId: string }> },
): Promise<Response> {
  const upstream = getDocumentHttpUpstream();
  if (!upstream) {
    return Response.json({ error: "Document upload is not configured" }, { status: 503 });
  }
  const { jobId } = await params;
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(jobId)) {
    return Response.json({ error: "Invalid ingestion job ID" }, { status: 400 });
  }
  try {
    const response = await fetch(`${upstream.baseUrl}/api/documents/jobs/${encodeURIComponent(jobId)}`, {
      headers: { Authorization: upstream.authorization, Accept: "application/json" },
      cache: "no-store",
      redirect: "manual",
      signal: request.signal,
    });
    return upstreamResponse(response);
  } catch {
    return Response.json({ error: "Document service is unavailable" }, { status: 502 });
  }
}
