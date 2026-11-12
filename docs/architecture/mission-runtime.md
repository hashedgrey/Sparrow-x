# Mission runtime

## Submission and identity

`MissionSubmissionService` normalizes budget policy and validates each request. Mission identity is deterministically derived from tenant ID and request ID. A request fingerprint prevents reusing an existing request ID with different input.

For a new submission, the service:

1. Classifies source requirements, execution path and planner mode using lightweight descriptors.
2. Persists the mission and an immutable execution/version snapshot.
3. Saves checkpoints for input, frozen intent (including effective tool/source grants) and prepared artifacts.
4. Executes side-effecting artifact preparation only after establishing stable mission identity.
5. Starts or obtains the Temporal workflow with `TemporalMissionClient.startOrGet`.

A repeated request with the same fingerprint reuses the existing mission. Both FAST and RESEARCH paths enter Temporal.

## Temporal and Embabel

`MissionWorkflowImpl` owns durable workflow orchestration, signals and timers. `MissionActivitiesImpl` loads the frozen input, intent and prepared artifacts, checks their identity, then invokes Embabel through `EmbabelMissionRunner`.

Embabel runs on a virtual thread so the activity can poll for Temporal cancellation. Graph batch operations use a shared gRPC deadline; cancellation and authorization failures are distinguished from recoverable transport/evidence shortfalls. A required human approval is converted into an `APPROVAL_REQUIRED` activity result; the workflow manages approval, rejection or expiration. Successful terminalization occurs only after the agent produces a mission result and the result checkpoint is available.

## State ownership

| Data | Owner |
|---|---|
| Mission identity, status and request fingerprint | PostgreSQL `MissionStore` |
| Input, intent, artifacts and result checkpoints | `CheckpointStore` |
| Workflow state, timers and signals | Temporal |
| Evidence observations, scored action proposals and agent blackboard | Mission/Embabel runtime |
| Runtime progress events and tracing metadata | Mission event publisher and PostgreSQL event store |
| UI graph layout or local replay cache | UI projection; not authoritative business state |

## Reliability contracts

- Reject modified payloads submitted under the same tenant/request identity.
- Respect tenant and **effective, frozen** tool/source grants on every downstream operation; an empty frozen allowlist denies capabilities.
- Let JEV evaluate bounded goal status and per-capability marginal utility, then let Embabel choose among eligible proposals. Do not treat a preview recommendation as the executed action.
- Apply evidence-action cost, required-source floors, graph prerequisites, action budgets and bounded retry/subquery rules during the reason/execute loop.
- Maintain stable checkpoints for recovery and replay, including tolerance of legacy evidence-state checkpoints that still contain the removed `utility` field.
- Admit only source-authorized, backend-validated evidence for synthesis. Graph evidence must match the selected root/type and retain the original batch-query scope; a new root search cannot reuse an older graph proof.
- Distinguish a successful execution or reviewer vote from **goal certification**. Final synthesis and terminal events must reflect evidence admission and the resulting `goalCertified` flag; uncertified partial evidence may still be reported without a success claim.
- Treat human approval as a workflow gate, never as implicit tool authorization.
- Distinguish `COMPLETED`, failure, cancellation and waiting-for-approval outcomes.
- Keep streamed event chronology aligned with persisted mission records, distinguishing utility proposals, Embabel action selection, capability attempts and final goal outcome.

The backend does not require a fixed number of steps. The final trace may contain repeated action kinds as new evidence changes what should happen next.
