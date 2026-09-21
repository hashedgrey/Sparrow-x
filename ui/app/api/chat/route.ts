const AGENTIC_SERVICE_URL =
    process.env.AGENTIC_SERVICE_URL ??
    "http://localhost:8086";

type SparrowChatRequest = {
  query?: string;
  tenantId?: string;
  userId?: string;
  conversationId?: string;
};

export async function POST(req: Request) {
  const body =
      (await req.json()) as SparrowChatRequest;

  const query = body.query?.trim();

  if (!query) {
    return Response.json(
        {
          error: "query must not be blank",
        },
        {
          status: 400,
        },
    );
  }

  const upstream = await fetch(
      `${AGENTIC_SERVICE_URL}/api/assistant/missions/stream`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
        },
        body: JSON.stringify({
          query,
          tenantId: body.tenantId,
          userId: body.userId,
          conversationId:
          body.conversationId,
        }),
        signal: req.signal,
        cache: "no-store",
      },
  );

  if (!upstream.ok) {
    const detail =
        await upstream.text();

    return Response.json(
        {
          error:
              detail ||
              `Agentic Service returned HTTP ${upstream.status}`,
        },
        {
          status: upstream.status,
        },
    );
  }

  if (!upstream.body) {
    return Response.json(
        {
          error:
              "Agentic Service returned no response body",},
        {
          status: 502,
        },
    );
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      "Content-Type":
          "text/event-stream; charset=utf-8",
      "Cache-Control":
          "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}