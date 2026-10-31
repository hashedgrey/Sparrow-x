# Mission events and execution traces

## Event sources

- `EmbabelMissionEventBridge`: converts agent runtime process/action events into mission-progress and trace information.
- `EmbabelPlanProjection`: produces a compact sequence of registered action identifiers while retaining repeated occurrences and truncation flags.
- `MissionTracePublisher`: creates bounded, structured trace spans and observations.
- Mission runtime event store: persists progress and associated trace metadata.
- `TraceSseProjection`: exposes allowlisted trace fields in SSE payloads.
- `AssistantMissionSseController`: streams events to the assistant UI.

## SSE contract

The endpoint is `POST /api/assistant/missions/stream` on Agentic Service, default HTTP port `8086`.

| Event | Meaning |
|---|---|
| `mission` | Mission ID, initial status and selected path |
| `progress` | Stage, step, status, indicative progress, resume token and optional trace |
| `answer_delta` | Incremental answer text |
| `usage` | Model, token, embedding and duration counters |
| `heartbeat` | Keepalive during quiet periods |
| `result` | Executive summary, answer and citations |
| `error` | Sanitized terminal failure |
| `complete` | Successful terminal marker |

Progress includes a trace object only when trace metadata is present. The projection can expose `id`, `parentId`, sector, kind, title, status, source, timestamps, message, duration and a bounded set of attributes.

## Visualizing execution

- Use **mission ID + event sequence + action occurrence ID** as stable identity.
- Keep **chronology** separate from true dependencies; chronological adjacency is not proof of a causal edge.
- Preserve repeated actions and explicit plan-projection truncation.
- Interpret progress percentages as indicative milestones, not precise remaining work.
- Display only recorded utility values, eligibility and rationale; never synthesize model thoughts or missing planner votes.
- Use persisted backend records as the source of truth. Any browser or SQLite projection is a cache/read model.

A useful trace viewer joins a timeline, the current goal state, evidence references, tool attempts, model usage and terminal result without conflating planned and executed actions.
