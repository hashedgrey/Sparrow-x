package com.sparrowx.agentic.runtime;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent;
import com.embabel.agent.api.event.AgentProcessFinishedEvent;
import com.embabel.agent.api.event.ReplanRequestedEvent;
import com.embabel.agent.api.event.GoalAchievedEvent;
import com.embabel.agent.api.event.StateTransitionEvent;
import com.embabel.agent.core.ActionStatusCode;
import com.embabel.agent.core.AgentProcess;
import com.sparrowx.agentic.agents.MissionAgent;
import com.sparrowx.agentic.agents.MissionRunInput;
import com.sparrowx.agentic.mission.MissionEventPublisher;
import com.sparrowx.agentic.mission.model.ComponentInvocation;
import com.sparrowx.agentic.mission.model.ComponentKind;
import com.sparrowx.agentic.mission.model.MissionProgressEvent;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.runtime.model.StepStatus;
import com.sparrowx.agentic.trace.MissionTracePublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class EmbabelMissionEventBridge
        implements AgenticEventListener {

    private final MissionEventPublisher eventPublisher;
    private final MissionTracePublisher tracePublisher;
    // Process-local iteration counts; do not confuse reviewer loops with Embabel planning ticks.
    private final ConcurrentHashMap<String, AtomicLong> planningIterations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressHighWater = new ConcurrentHashMap<>();
    // Embabel can enter the same state action repeatedly. Progress IDs must
    // identify an execution occurrence, not merely its short action name.
    private final ConcurrentHashMap<String, AtomicLong> occurrenceCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> activeOccurrences = new ConcurrentHashMap<>();

    public EmbabelMissionEventBridge(MissionEventPublisher eventPublisher,
                                     MissionTracePublisher tracePublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.tracePublisher = Objects.requireNonNull(tracePublisher, "tracePublisher must not be null");
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        // Embabel planning is independent of SparrowX's reviewer recommendation.
        // These event classes/getters are verified against the 1.5.2 tagged API.
        if (event instanceof AgentProcessReadyToPlanEvent ready) {
            publishPlanningReady(ready);
            return;
        }
        if (event instanceof AgentProcessPlanFormulatedEvent formulated) {
            publishPlan(formulated);
            return;
        }
        if (event instanceof ReplanRequestedEvent replan) {
            publishRuntime(replan.getAgentProcess(), "EMBABEL_REPLAN_REQUESTED",
                    "Embabel replanning requested", Map.of("replanning", "true"));
            return;
        }
        if (event instanceof StateTransitionEvent transition) {
            publishRuntime(transition.getAgentProcess(), "EMBABEL_STATE_TRANSITION",
                    "Embabel state transition", Map.of(
                            "stateFrom", transition.getPreviousState() == null ? "none" :
                                    transition.getPreviousState().getClass().getSimpleName(),
                            "stateTo", transition.getNewState().getClass().getSimpleName()));
            return;
        }
        if (event instanceof GoalAchievedEvent achieved) {
            publishRuntime(achieved.getAgentProcess(), "EMBABEL_GOAL_ACHIEVED",
                    "Embabel goal achieved", Map.of("goal", achieved.getGoal().getName()));
            return;
        }
        if (event instanceof AgentProcessFinishedEvent finished) {
            releaseProcess(finished.getAgentProcess());
            return;
        }
        if (event instanceof ActionExecutionStartEvent started) {
            publishStarted(started);
            return;
        }

        if (event instanceof ActionExecutionResultEvent completed) {
            publishCompleted(completed);
        }
    }

    private void publishPlanningReady(AgentProcessReadyToPlanEvent event) {
        String key = event.getProcessId();
        long iteration = planningIterations.computeIfAbsent(key, ignored -> new AtomicLong()).get() + 1;
        publishRuntime(event.getAgentProcess(), "EMBABEL_PLANNING_READY", "Embabel ready to plan",
                Map.of("planIteration", Long.toString(iteration),
                        "plannerActual", event.getAgentProcess().getProcessOptions().getPlannerType().name()));
    }

    private void publishPlan(AgentProcessPlanFormulatedEvent event) {
        long iteration = planningIterations.computeIfAbsent(event.getProcessId(), ignored -> new AtomicLong())
                .incrementAndGet();
        // Plan.getActions()/getGoal() come from com.embabel.plan.Plan in 1.5.2.
        // Only action identifiers cross SSE; never serialize WorldState or action arguments.
        List<String> actions = event.getPlan().getActions().stream()
                .map(action -> action.getName()).toList();
        List<String> shown = actions.stream().limit(20).toList();
        String actionsLabel = String.join(" -> ", shown);
        publishRuntime(event.getAgentProcess(), "EMBABEL_PLAN_FORMULATED", "Embabel plan formulated",
                Map.of("goal", event.getPlan().getGoal().getName(),
                        "planIteration", Long.toString(iteration),
                        "planActionCount", Integer.toString(actions.size()),
                        "plannedActions", actionsLabel,
                        "planTruncated", Boolean.toString(actions.size() > shown.size()
                                || actionsLabel.length() > 2048),
                        "replanning", Boolean.toString(iteration > 1),
                        "plannerActual", event.getAgentProcess().getProcessOptions().getPlannerType().name()));
    }

    private void publishRuntime(AgentProcess process, String kind, String title, Map<String, String> attrs) {
        MissionRunInput input = missionInput(process);
        if (input == null) return;
        // A new, completed trace span is used for a single instantaneous runtime event.
        tracePublisher.observe(input, null, "planning", "Embabel planning", kind, title, attrs, () -> null);
    }

    private void releaseProcess(AgentProcess process) {
        String processId = process.getId();
        planningIterations.remove(processId);
        MissionRunInput input = missionInput(process);
        if (input != null) progressHighWater.remove(occurrenceKey(input.missionId(), processId, "progress"));
        if (input != null) {
            String prefix = input.missionId() + ":" + processId + ":";
            occurrenceCounters.keySet().removeIf(key -> key.startsWith(prefix));
            activeOccurrences.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private double overallMilestone(String missionId, String processId, double candidate) {
        // Indicative high-water milestone, not a promise of % of GOAP actions remaining.
        return progressHighWater.merge(occurrenceKey(missionId, processId, "progress"),
                candidate, Math::max);
    }

    private void publishStarted(
            ActionExecutionStartEvent event
    ) {
        MissionRunInput input = missionInput(event);

        if (input == null) {
            return;
        }

        ActionProjection projection = projection(event.getAction().getName());

        if (projection == null) {
            return;
        }

        Instant now = Instant.now();
        long occurrence = nextOccurrence(input.missionId(), event.getProcessId(), projection.stepId());

        eventPublisher.publish(
                        input.tenantId(),
                        new MissionProgressEvent(
                                input.missionId(),
                                MissionStatus.RUNNING,
                                projection.stageId(),
                                projection.stageName(),
                                projection.stepId(),
                                projection.stepName(),
                                StepStatus.RUNNING,
                                new ComponentInvocation(
                                        projection.componentId(),
                                        projection.componentKind(),
                                        projection.componentName(),
                                        Map.of(),
                                        Map.of(),
                                        now,
                                        null
                                ),
                                projection.startMessage(),
                                overallMilestone(input.missionId(), event.getProcessId(), projection.startPercent()),
                                resumeToken(input.missionId(), event.getProcessId(),
                                        projection.stepId(), occurrence, "started"),
                                now,
                                Map.of(
                                        "embabelProcessId",
                                        event.getProcessId(),
                                        "embabelAction",
                                        event.getAction().getName()
                                )
                        )
                );

    }

    private void publishCompleted(
            ActionExecutionResultEvent event
    ) {
        MissionRunInput input = missionInput(event);

        if (input == null) {
            return;
        }

        ActionProjection projection =
                projection(
                        event.getAction().getName()
                );

        if (projection == null) {
            return;
        }

        ActionStatusCode status = event.getActionStatus().getStatus();

        StepStatus stepStatus =
                switch (status) {
                    case SUCCEEDED -> StepStatus.SUCCEEDED;

                    case WAITING, PAUSED -> StepStatus.PAUSED;

                    default -> StepStatus.FAILED_TERMINAL;
                };

        String message =
                status == ActionStatusCode.SUCCEEDED
                        ? projection.completeMessage()
                        : projection.stepName()
                        + " ended with "
                        + status.name();

        Instant now = Instant.now();
        long occurrence = finishOccurrence(input.missionId(), event.getProcessId(), projection.stepId());

        eventPublisher.publish(
                        input.tenantId(),
                        new MissionProgressEvent(
                                input.missionId(),
                                MissionStatus.RUNNING,
                                projection.stageId(),
                                projection.stageName(),
                                projection.stepId(),
                                projection.stepName(),
                                stepStatus,
                                new ComponentInvocation(
                                        projection.componentId(),
                                        projection.componentKind(),
                                        projection.componentName(),
                                        Map.of(),
                                        Map.of(
                                                "embabelStatus",
                                                status.name()
                                        ),
                                        now.minus(
                                                event.getRunningTime()
                                        ),
                                        now
                                ),
                                message,
                                overallMilestone(input.missionId(), event.getProcessId(), projection.completePercent()),
                                resumeToken(input.missionId(), event.getProcessId(),
                                        projection.stepId(), occurrence, "completed"),
                                now,
                                Map.of(
                                        "embabelProcessId",
                                        event.getProcessId(),
                                        "embabelAction",
                                        event.getAction().getName(),
                                        "embabelStatus",
                                        status.name()
                                )
                        )
                );

    }

    /**
     * The v2+ agent clears the standalone MissionRunInput on state transitions.
     * Every active mission state owns an immutable reference to that input.
     * Resolve it from the active state to support repeated GOAP/Hybrid loops.
     */
    private static MissionRunInput missionInput(ActionExecutionStartEvent event) {
        return missionInput(event.getAgentProcess());
    }

    private static MissionRunInput missionInput(ActionExecutionResultEvent event) {
        return missionInput(event.getAgentProcess());
    }

    private static MissionRunInput missionInput(AgentProcess process) {
        MissionRunInput direct = process.last(MissionRunInput.class);
        if (direct != null) return direct;
        var reason = process.last(MissionAgent.ReasonState.class);
        if (reason != null) return reason.input();
        var execute = process.last(MissionAgent.ExecuteState.class);
        if (execute != null) return execute.input();
        var finish = process.last(MissionAgent.FinishState.class);
        return finish == null ? null : finish.input();
    }

    /** High-water milestone percentages are indicative; a GOAP plan may replan indefinitely. */
    private static ActionProjection projection(
            String actionName
    ) {
        if (actionName == null
                || actionName.isBlank()) {
            return null;
        }

        String shortName =
                actionName.contains(".")
                        ? actionName.substring(
                        actionName.lastIndexOf('.') + 1
                )
                        : actionName;

        return switch (shortName) {

            // Current Embabel state-machine action names (MissionAgent v2+).
            // Only milestone labels live here; the graph/retrieval decisions
            // remain with the planner and reviewer, never in the progress bridge.
            case "start" -> new ActionProjection(
                    "mission", "Mission", "start", "Start mission",
                    "mission-agent", ComponentKind.CUSTOM, "MissionAgent",
                    "Starting mission", "Mission started", 5.0, 10.0);

            case "reason" -> new ActionProjection(
                    "reasoning", "Mission reasoning", "reason", "Review evidence and choose action",
                    "goal-review", ComponentKind.PLANNING, "MissionGoalReviewComponent",
                    "Evaluating available evidence", "Evidence reviewed", 12.0, 20.0);

            case "searchEntities" -> new ActionProjection(
                    "internal", "Internal discovery", "search-entities", "Find internal graph roots",
                    "internal-search", ComponentKind.CUSTOM, "InternalContextTool",
                    "Identifying relevant internal entities", "Internal entities resolved", 25.0, 40.0);

            case "queryGraph" -> new ActionProjection(
                    "internal", "Company graph", "query-graph", "Query internal company graph",
                    "internal-graph", ComponentKind.CUSTOM, "InternalContextTool",
                    "Querying company relationships", "Company graph queried", 50.0, 70.0);

            case "retrieveDocuments" -> new ActionProjection(
                    "document", "Document evidence", "retrieve-documents", "Retrieve document evidence",
                    "document-evidence", ComponentKind.CUSTOM, "DocumentTool",
                    "Retrieving relevant document evidence", "Document evidence retrieved", 50.0, 70.0);

            // Old agent actions retained for compatibility with in-flight missions.
            case "interpret" ->
                    new ActionProjection(
                            "interpret",
                            "Intent interpretation",
                            "interpret",
                            "Interpret mission request",
                            "intent-component",
                            ComponentKind.INTENT_INTERPRETATION,
                            "IntentComponent",
                            "Interpreting mission intent",
                            "Mission intent interpreted",
                            10.0,
                            20.0
                    );

            case "plan" ->
                    new ActionProjection(
                            "planning",
                            "Mission planning",
                            "plan",
                            "Create mission plan",
                            "planning-component",
                            ComponentKind.PLANNING,
                            "PlanningComponent",
                            "Planning mission execution",
                            "Mission plan created",
                            30.0,
                            40.0
                    );

            case "collectEvidence" ->
                    new ActionProjection(
                            "evidence",
                            "Evidence collection",
                            "collect-evidence",
                            "Collect mission evidence",
                            "evidence-service",
                            ComponentKind.CUSTOM,
                            "MissionEvidenceService",
                            "Collecting mission evidence",
                            "Mission evidence collected",
                            50.0,
                            70.0
                    );

            case "complete" ->
                    new ActionProjection(
                            "synthesis",
                            "Response synthesis",
                            "synthesize",
                            "Synthesize mission result",
                            "synthesis-component",
                            ComponentKind.SYNTHESIS,
                            "SynthesisComponent",
                            "Synthesizing grounded response",
                            "Grounded response synthesized",
                            80.0,
                            90.0
                    );

            default -> null;
        };
    }

    private static String occurrenceKey(String missionId, String processId, String stepId) {
        return missionId + ":" + processId + ":" + stepId;
    }

    private long nextOccurrence(String missionId, String processId, String stepId) {
        String key = occurrenceKey(missionId, processId, stepId);
        long ordinal = occurrenceCounters.computeIfAbsent(key, unused -> new AtomicLong())
                .incrementAndGet();
        activeOccurrences.computeIfAbsent(key, unused -> new ConcurrentLinkedQueue<>())
                .add(ordinal);
        return ordinal;
    }

    private long finishOccurrence(String missionId, String processId, String stepId) {
        String key = occurrenceKey(missionId, processId, stepId);
        var pending = activeOccurrences.get(key);
        Long ordinal = pending == null ? null : pending.poll();
        if (pending != null && pending.isEmpty()) activeOccurrences.remove(key, pending);
        // A result can arrive after a listener restart without its start event.
        // Give that observation a distinct ID rather than reusing a prior token.
        return ordinal == null
                ? occurrenceCounters.computeIfAbsent(key, unused -> new AtomicLong()).incrementAndGet()
                : ordinal;
    }

    private static String resumeToken(String missionId, String processId, String stepId,
                                      long ordinal, String state) {
        return "progress:" + missionId + ":" + processId + ":" + stepId + ":" + ordinal + ":" + state;
    }

    private record ActionProjection(
            String stageId,
            String stageName,
            String stepId,
            String stepName,
            String componentId,
            ComponentKind componentKind,
            String componentName,
            String startMessage,
            String completeMessage,
            double startPercent,
            double completePercent
    ) {
    }
}
