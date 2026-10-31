# Agent decision architecture

SparrowX separates bounded semantic judgment, goal-directed planning, evidence acquisition and result validation. This separation ensures that model-generated recommendations do not become execution authority.

## System 1: typed judgments

`SystemOneDecisionClient` accepts a state map and typed questions. Supported question shapes include binary (`NoulQuestion`), bounded choice and rubric score. `SystemOneDecisionEngine` delegates to a configured provider, **Jev** or **Glide**, without constructing plans or executing tools.

`IntentComponent` determines five source/mission requirements—document content, internal context, company graph, verification and research—and independently decides whether HYBRID planning is warranted. The default acceptance and policy thresholds are:

| Policy | Default |
|---|---:|
| Minimum bounded-decision confidence | 0.75 |
| Source requirement | 0.55 |
| RESEARCH path | 0.60 |
| HYBRID planner | 0.60 |

If the bounded classifier abstains, the intent policy preserves uncertain evidence needs rather than treating them as negative votes. Uncertain research classification selects RESEARCH unless a caller-provided path takes precedence; uncertain planner classification selects HYBRID. A company-graph requirement implies internal context. The generative LLM does **not** select these policies.

The intent is persisted as a checkpoint before mission execution and remains stable for idempotent replays.

## Semantic escalation

`SemanticDecisionService` validates bounded responses and confidence. When a caller allows it, the service escalates unresolved fields to a structured LLM rather than inventing values. The utility assessment requests use a 0.80 confidence threshold and can escalate unavailable or uncertain capability scores. The intent selector uses a distinct **conservative policy fallback**, not LLM selection.

`SystemOneUtilityComponent` scores the relevance of eligible named capabilities, including document evidence, internal search, graph reads and synthesis. Eligibility and governance checks take precedence over returned values. The score is the estimated marginal benefit of a capability given the current evidence—not a guarantee of execution.

## System 2: Embabel planning

`EmbabelMissionRunner` maps the frozen intent's planner mode to `GOAP` or `HYBRID` in `ProcessOptions`. `MissionAgent` registers one terminal goal: `FinishState.complete`.

The main loop is:

1. **Start** — register the mission goal and initialize an evidence ledger.
2. **Reason** — review current observations and the goal; propose the next authorized capability, if needed.
3. **Execute** — invoke one eligible document, internal-search or company-graph action.
4. **Reevaluate** — incorporate the observation into the blackboard and review the goal again.
5. **Finish** — synthesize a grounded answer or return a truthful unresolved outcome.

Registered actions use conditions and values, including `missionDocumentRecommended`, `missionSearchRecommended` and `missionGraphRecommended`. `@Cost` methods read current reviewer proposals and must be side-effect-free; they do not make provider calls during planning.

`MissionGoalReviewComponent` evaluates whether evidence is sufficient, further investigation is justified, or the task is blocked/ambiguous. It can return `SATISFIED`, `READY_FOR_SYNTHESIS`, `NEEDS_EVIDENCE`, `AMBIGUOUS` or `BLOCKED` outcomes. The reviewer enforces evidence needs, eligibility and repetition limits.

## Agent explainability contract

The UI can display observable facts: selected path, planner mode, allowed capabilities, recorded utility, action execution, evidence refs, goal-review outcome, provider escalation and final result.

A chronological event sequence is **not** automatically a causal dependency graph. Display real parent/child IDs, action occurrences and recorded decision attributes; do not invent missing scores or claim access to hidden LLM reasoning.
