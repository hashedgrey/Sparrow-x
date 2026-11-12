import { getDocumentHttpUpstream, upstreamResponse } from "@/lib/document-http-upstream";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
const MAX_FILE_BYTES = 20 * 1024 * 1024;

export async function POST(request: Request): Promise<Response> {
  const upstream = getDocumentHttpUpstream();
  if (!upstream) {
    return Response.json({ error: "Document upload is not configured" }, { status: 503 });
  }

  const contentLength = Number(request.headers.get("content-length") || "0");
  if (contentLength > MAX_FILE_BYTES + 1024 * 1024) {
    return Response.json({ error: "Upload exceeds the 20 MB limit" }, { status: 413 });
  }

  let multipart: FormData;
  try {
    multipart = await request.formData();
  } catch {
    return Response.json({ error: "Expected multipart/form-data" }, { status: 400 });
  }
  const file = multipart.get("file");
  if (!(file instanceof File) || file.size === 0) {
    return Response.json({ error: "A non-empty file is required" }, { status: 400 });
  }
  if (file.size > MAX_FILE_BYTES) {
    return Response.json({ error: "Upload exceeds the 20 MB limit" }, { status: 413 });
  }
  const filename = file.name.toLowerCase();
  if (![".pdf", ".txt", ".md", ".docx"].some((ext) => filename.endsWith(ext))) {
    return Response.json({ error: "Supported formats: PDF, TXT, MD, DOCX" }, { status: 415 });
  }

  const formData = new FormData();
  formData.set("file", file, file.name);
  formData.set("title", file.name);
  try {
    const response = await fetch(`${upstream.baseUrl}/api/documents`, {
      method: "POST",
      headers: { Authorization: upstream.authorization, Accept: "application/json" },
      body: formData,
      cache: "no-store",
      redirect: "manual",
      signal: request.signal,
    });
    return upstreamResponse(response);
  } catch {
    return Response.json({ error: "Document service is unavailable" }, { status: 502 });
  }
}
