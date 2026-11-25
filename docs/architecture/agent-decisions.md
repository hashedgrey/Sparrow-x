# Agent decision architecture

SparrowX separates **bounded semantic judgment, symbolic planning, deterministic governance, evidence acquisition and result validation**. System 1 estimates what evidence is needed and valuable; Embabel selects executable actions; Java enforces authorization, prerequisites and budgets. Model-generated assessments are inputs to execution, not execution authority.

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

The intent is persisted as a checkpoint before mission execution and remains stable for idempotent replays. At this boundary, `IntentComponent` derives **effective** allowed tools and source services from server policy and caller restrictions; downstream components treat the frozen sets as explicit grants, not optional filters. An empty frozen tool allowlist permits no capability. A tool is available only if both that capability and its source service are allowed.

## Bounded decisions and generative escalation

`SemanticDecisionService` validates typed System 1 responses against configured confidence thresholds. Missing, malformed, unavailable or low-confidence **bounded** answers remain unresolved; they are not silently replaced or reclassified by a generative LLM. `tryIndependentBounded` retains independently valid confident answers even when other fields abstain. Intent policy may apply a conservative fallback, explicitly distinguished from a JEV vote.

The generative LLM is reserved for explicitly unbounded outputs, such as a distinct evidence subquery after an earlier query has been executed. It does not choose source requirements, certify goal completion, assign bounded action scores or select which action runs.

## Evidence-action utility

`MissionGoalReviewComponent` checks the original goal against observed evidence, then obtains **independent** `NoulQuestion` marginal-value scores for eligible document, internal-search and company-graph capabilities. Each score estimates whether that capability will add necessary, new evidence; it is not proof of relevance or permission to execute. A sole eligible choice follows the deterministic unique-action path.

Before scoring or execution, Java enforces frozen authorization, graph prerequisites, outstanding required sources, action budgets and repetition limits. Required sources receive a `0.10` minimum utility floor if JEV abstains or scores them too low; this preserves mandatory work without allowing the model to override authorization. Proposed actions must meet the `0.06` executable-utility threshold and exceed the `0.05` evidence-action cost. Each accepted proposal carries an action, query and utility for Embabel to evaluate. The utility is a predicted marginal-value signal, not a calibrated probability of success or a multi-step plan.

**JEV scores; Embabel selects.** The highest-ranked proposal in `nextAction` is a **UI preview**, not proof that it was executed. Embabel selects an eligible proposal according to the configured planner: GOAP searches modeled goal-reaching paths, while HYBRID uses immediate net utility in its exploratory mode. A retry of a failed capability/query is bounded; a successfully executed query requires a distinct follow-up subquery before repeating that capability.

## Symbolic planning: Embabel GOAP and HYBRID

`EmbabelMissionRunner` maps the frozen intent's planner mode to `GOAP` or `HYBRID` in `ProcessOptions`. The documented terminal goal is `FinishState.complete`.

| Planner | Decision mechanism | Optimization objective |
|---|---|---|
| GOAP (Goal-Oriented Action Planning) | Searches modeled state transitions using preconditions and effects | Minimize cumulative path cost to the goal |
| HYBRID | Utility-based selection with a terminal-goal satisfaction check | Maximize immediate net utility during exploration, then terminate when the real goal is satisfied |

JEV supplies semantic assessments and candidate values. HYBRID uses these values in its utility policy (`value - cost`); GOAP searches paths rather than directly maximizing the next action's JEV score. With a common evidence-action cost of `0.05`, eligible evidence actions are ranked by their supplied values in HYBRID's exploratory utility selection.

GOAP's lookahead is useful when alternative paths have distinct modeled dependencies, effects or cumulative costs. In a shared reason/execute loop, its practical behavior may differ little from HYBRID when action eligibility and path costs are similar. Neither planner infers unmodeled dependencies or independently verifies source evidence.

**HYBRID configuration check:** Embabel's documented iterative utility pattern pairs the real terminal goal with an unsatisfiable exploration goal such as `NIRVANA`. A real terminal goal alone does not necessarily support a sequence of exploratory actions when no single action can reach it. These documents identify `FinishState.complete` but do not establish whether an additional exploration goal is registered or equivalent behavior is provided by the pinned framework version. Verify the actual `MissionAgent`, runner configuration and multi-step execution tests before claiming that HYBRID performs unrestricted iterative utility selection. See [Embabel planning strategies](https://hub.embabel.com/reference/planners).

The main loop is:

1. **Start** — register the mission goal and initialize an evidence ledger.
2. **Reason** — evaluate goal status and produce eligible, scored evidence-action proposals, if evidence is needed.
3. **Execute** — Embabel selects and invokes one authorized document, internal-search or company-graph action according to its configured strategy; Java enforces execution constraints.
4. **Reevaluate** — incorporate the observation into the blackboard and review the goal again.
5. **Finish** — synthesize a grounded answer or return a truthful unresolved outcome.

Registered actions use conditions and value methods, including `missionDocumentRecommended`, `missionSearchRecommended` and `missionGraphRecommended`. Value/cost evaluation consumes existing reviewer proposals; it must not make provider calls during planning. Evidence-action cost is a shared constant (`0.05`). If a distinct follow-up query is needed, generative escalation prepares that query as part of the candidate proposal; it does not determine which capability Embabel executes.

`MissionGoalReviewComponent` evaluates whether evidence is sufficient, further investigation is justified, or the task is blocked/ambiguous. It can return `SATISFIED`, `READY_FOR_SYNTHESIS`, `NEEDS_EVIDENCE`, `AMBIGUOUS` or `BLOCKED` outcomes. The reviewer enforces evidence needs, eligibility, current root scope and repetition limits. A new internal-search result invalidates an older graph proof for goal certification.

## Deterministic governance and certification

Authorization, graph prerequisites, source requirements, evidence admission and budgets remain independent of the semantic utility scores. A high score cannot bypass a prerequisite or expand a frozen grant. A required-source utility floor keeps an otherwise eligible mandatory action available; it does not prove the resulting evidence is sufficient.

A terminal planner state means the execution has reached its modeled finish condition, not necessarily that the original investigation is complete. Final certification depends on admitted evidence and `goalCertified`; a blocked or incomplete outcome can be reported without asserting success.

## Agent explainability contract

The UI can display observable facts: selected path, planner mode, effective allowed capabilities, JEV utility scores, candidate proposals, **Embabel-selected** capability, action cost/net utility, evidence refs, goal-review outcome, generative subquery creation and final certification result. Candidate events (`MISSION_ACTION_UTILITY`, `MISSION_ACTION_RECOMMENDATION`) are not execution events; `MISSION_ACTION_SELECTED` records the selected action.

The final `GOAL_SATISFIED` / `GOAL_EVIDENCE_READY` / `GOAL_BLOCKED` trace reflects evidence admission and `goalCertified`, not merely an earlier JEV satisfaction vote. A chronological event sequence is **not** automatically a causal dependency graph. Display real parent/child IDs, action occurrences and recorded decision attributes; do not invent missing scores or claim access to hidden LLM reasoning.
