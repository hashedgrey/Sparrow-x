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

Progress includes a trace object only when trace metadata is present. The projection can expose `id`, `parentId`, sector, kind, title, status, source, timestamps, message, duration and a bounded set of attributes. A newly emitted backend trace kind is **not guaranteed visible to the browser** unless the projection's allowlist and the UI adapter carry it.

## Decision and goal events

| Trace kind | Interpretation |
|---|---|
| `MISSION_ACTION_UTILITY` | A candidate capability was independently scored; attributes include capability, utility, required-source flag and action cost. It is **not** execution. |
| `MISSION_ACTION_RECOMMENDATION` | Goal review prepared scored candidates; `nextAction` is a highest-ranked **preview**, with candidate/alternative counts and `selectionAuthority=EMBABEL`. |
| `MISSION_ACTION_SELECTED` | Embabel actually selected the eligible capability; records utility, cost and net utility before the attempt. |
| `CAPABILITY_ATTEMPT` | Attempt span for the selected backend capability; distinguish success, failure, cancellation and retriable errors. |
| `GOAL_EVALUATED` | Review of the original goal against the evidence so far, not final certification. |
| `GOAL_SATISFIED`, `GOAL_EVIDENCE_READY`, `GOAL_BLOCKED` | Final outcome **after** evidence admission and synthesis checks. |
| `MISSION_FINISH` | Terminal mission trace including final `goalStatus` and earlier `reviewStatus` where provided. |

A low-confidence or unavailable bounded JEV answer must not be rendered as an LLM-selected action. Recorded `JEV_UNAVAILABLE` / `JEV_UNRESOLVED` events indicate an unresolved bounded decision, not a fabricated vote. A generated follow-up query is an unbounded operation and is separate from tool selection.

## Visualizing execution

- Use **mission ID + event sequence + action occurrence ID** as stable identity.
- Keep **chronology** separate from true dependencies; chronological adjacency is not proof of a causal edge.
- Preserve repeated actions and explicit plan-projection truncation.
- Interpret progress percentages as indicative milestones, not precise remaining work.
- Display only recorded utility values, eligibility and rationale; never synthesize model thoughts or missing planner votes.
- Separate `nextAction` (reviewer preview) from the action recorded in `MISSION_ACTION_SELECTED`, then associate the actual attempt and evidence with that selected action.
- Separate reviewer `SATISFIED` from final `goalCertified`; the final goal event can be downgraded when source evidence fails admission.
- Do not assume emitted decision events are automatically OpenTelemetry spans, Langfuse observations or SSE-visible fields; verify the actual exporter and projection mappings.
- Use persisted backend records as the source of truth. Any browser or SQLite projection is a cache/read model.

A useful trace viewer joins a timeline, the current goal state, evidence references, tool attempts, model usage and terminal result without conflating planned and executed actions.
