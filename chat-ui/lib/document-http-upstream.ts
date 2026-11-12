/** Server-only configuration. Never import this module into a client component. */
export function getDocumentHttpUpstream(): { baseUrl: string; authorization: string } | null {
  // No verified browser identity is available in the supplied chat-ui sources.
  // Restrict this service-account proxy to local development until authenticated
  // production routes and identity/tenant mapping are integrated.
  if (process.env.NODE_ENV === "production" || process.env.DOCUMENT_UPLOAD_ENABLED !== "true") return null;
  const baseUrl = process.env.DOCUMENT_SERVICE_HTTP_URL?.replace(/\/+$/, "");
  const username = process.env.DOCUMENT_SERVICE_BASIC_USER;
  const password = process.env.DOCUMENT_SERVICE_BASIC_PASSWORD;
  if (!baseUrl || !username || !password) return null;
  return {
    baseUrl,
    authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`,
  };
}

export async function upstreamResponse(response: Response): Promise<Response> {
  // Avoid copying upstream content-security/auth headers into a browser response.
  const text = await response.text();
  return new Response(text, {
    status: response.status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}
