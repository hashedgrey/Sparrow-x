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

The stages are **logical concerns**, not a fixed list of Java actions. Embabel may repeat retrieval, search or graph actions as evidence and goal status change.

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
- Trace events show the actual path and any repeated action invocations.
- A second submission with the same request identity does not start a distinct mission.

Successful execution requires representative data in Internal Service and Document Service; the PostgreSQL schema initializer does not provide that content.
