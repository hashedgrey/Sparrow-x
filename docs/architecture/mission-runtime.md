# Mission runtime

## Submission and identity

`MissionSubmissionService` normalizes budget policy and validates each request. Mission identity is deterministically derived from tenant ID and request ID. A request fingerprint prevents reusing an existing request ID with different input.

For a new submission, the service:

1. Classifies source requirements, execution path and planner mode using lightweight descriptors.
2. Persists the mission and an immutable execution/version snapshot.
3. Saves checkpoints for input, frozen intent and prepared artifacts.
4. Executes side-effecting artifact preparation only after establishing stable mission identity.
5. Starts or obtains the Temporal workflow with `TemporalMissionClient.startOrGet`.

A repeated request with the same fingerprint reuses the existing mission. Both FAST and RESEARCH paths enter Temporal.

## Temporal and Embabel

`MissionWorkflowImpl` owns durable workflow orchestration, signals and timers. `MissionActivitiesImpl` loads the frozen input, intent and prepared artifacts, checks their identity, then invokes Embabel through `EmbabelMissionRunner`.

Embabel runs on a virtual thread so the activity can poll for Temporal cancellation. A required human approval is converted into an `APPROVAL_REQUIRED` activity result; the workflow manages approval, rejection or expiration. Successful terminalization occurs only after the agent produces a mission result and the result checkpoint is available.

## State ownership

| Data | Owner |
|---|---|
| Mission identity, status and request fingerprint | PostgreSQL `MissionStore` |
| Input, intent, artifacts and result checkpoints | `CheckpointStore` |
| Workflow state, timers and signals | Temporal |
| Evidence observations and agent blackboard | Mission/Embabel runtime |
| Runtime progress events and tracing metadata | Mission event publisher and PostgreSQL event store |
| UI graph layout or local replay cache | UI projection; not authoritative business state |

## Reliability contracts

- Reject modified payloads submitted under the same tenant/request identity.
- Respect tenant and tool/source authorization on every downstream operation.
- Maintain stable checkpoints for recovery and replay.
- Treat human approval as a workflow gate, never as implicit tool authorization.
- Distinguish `COMPLETED`, failure, cancellation and waiting-for-approval outcomes.
- Keep streamed event chronology aligned with persisted mission records.

The backend does not require a fixed number of steps. The final trace may contain repeated action kinds as new evidence changes what should happen next.
