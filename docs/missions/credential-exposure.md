# Mission walkthrough: suspected credential exposure

## Objective

> An incident report describes suspected credential exposure involving the Agentic Orchestrator service. Identify the responsible team, find applicable credential-handling policies and incident-response runbooks, and outline investigation and remediation steps supported by those documents.

This scenario combines structured company context and source-document evidence. It is an internal evidence investigation, not an automatic compromise-detection pipeline.

## Logical stages

| Stage | Required outcome |
|---|---|
| Mission admission | Stable mission ID, validated tenant/request context and frozen intent |
| Goal registration | A terminal goal and initial evidence state |
| Service resolution | Internal entity identifier and matching organizational relationships |
| Document retrieval | Incident report, credential policy and runbook passages, if available |
| Evidence review | Source-backed findings, missing facts and whether further retrieval adds value |
| Governance | Enforced tool/source authorization and any applicable human gate |
| Final synthesis | Cited facts, open questions and policy-supported next actions |

The stages are **logical concerns**, not a fixed list of Java actions. Embabel may repeat retrieval, search or graph actions as evidence and goal status change. A company-graph read requires an eligible, resolved root scope from internal search; the reviewer must not reuse older graph proof after a newer search changes that scope.

## Planning and evidence gates

- **Frozen access:** Effective tool and source-service grants are established in mission intent. Empty tool grants deny execution; neither JEV scores nor human approval expand them.
- **Bounded review:** `MissionGoalReviewComponent` judges outstanding evidence and independently scores eligible document, internal-search and company-graph capabilities with JEV. Required sources have a minimum utility floor, but only if authorized and otherwise eligible.
- **Planner selection:** Embabel chooses from the scored action proposals. A reviewer's `nextAction` is a preview, not evidence that a tool ran. `MISSION_ACTION_SELECTED` and the capability-attempt trace distinguish actual selection and execution.
- **Graph integrity:** A COMPLETE company-graph status alone does not prove coverage. The chosen root set must be exhaustive where required, and verified graph evidence must match each root and its query scope; missing root proofs remain evidence gaps.
- **Retry limits:** A failed capability/query may receive a bounded same-query retry. A completed invocation requires a distinct evidence subquery before another attempt.
- **Certification:** `GOAL_SATISFIED` depends on admitted evidence and final `goalCertified`, not merely a reviewer vote. An uncertified partial report may still communicate verified findings without claiming the investigation is complete.

## What a useful answer contains

1. **Identified service and owner:** supported by Internal Service entities and relationships.
2. **Documented incident facts:** linked to the actual report passages.
3. **Applicable controls:** credential-handling policy sections and response runbooks, with citations.
4. **Unknowns:** assertions lacking direct source support or security telemetry.
5. **Recommended follow-up:** clearly marked as recommendation rather than completed remediation.

## Validation

- Each factual claim links to evidence or is explicitly marked as inference.
- Missing runbooks produce evidence gaps, not fabricated policies.
- Authorization and tenant constraints are honored.
- Trace events distinguish candidate utility (`MISSION_ACTION_UTILITY`), the recommendation preview (`MISSION_ACTION_RECOMMENDATION`), Embabel selection (`MISSION_ACTION_SELECTED`), actual attempts and terminal certification.
- The incident is **suspected**: findings must not imply a credential was confirmed leaked, rotated or revoked unless evidence records that action.
- Final certification requires evidence from mandatory authorized sources, including valid root-scoped graph proof where company ownership is requested.
- A second submission with the same request identity does not start a distinct mission.

Successful execution requires representative data in Internal Service and Document Service; the PostgreSQL schema initializer does not provide that content.
