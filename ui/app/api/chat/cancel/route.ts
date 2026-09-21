import { NextResponse } from "next/server";

const AGENTIC_SERVICE_URL =
    process.env.AGENTIC_SERVICE_URL ??
    "http://localhost:8086";

type CancelRequest = {
    missionId?: string;
    tenantId?: string;
    userId?: string;
    reason?: string;
};

export async function POST(
    request: Request,
) {
    const body =
        (await request.json()) as CancelRequest;

    const missionId =
        body.missionId?.trim();

    if (!missionId) {
        return NextResponse.json(
            {
                error: "missionId is required",
            },
            {
                status: 400,
            },
        );
    }

    const upstream = await fetch(
        `${AGENTIC_SERVICE_URL}/api/assistant/missions/${encodeURIComponent(
            missionId,
        )}/cancel`,
        {
            method: "POST",

            headers: {
                "Content-Type":
                    "application/json",
            },

            body: JSON.stringify({
                tenantId:
                    body.tenantId ??
                    "tenant-test",

                userId:
                    body.userId ??
                    "assistant-ui-user",

                reason:
                    body.reason ??
                    "Stopped by user",
            }),

            cache: "no-store",
        },
    );

    const text =
        await upstream.text();

    return new Response(
        text,
        {
            status: upstream.status,

            headers: {
                "Content-Type":
                    upstream.headers.get(
                        "Content-Type",
                    ) ??
                    "application/json",
            },
        },
    );
}