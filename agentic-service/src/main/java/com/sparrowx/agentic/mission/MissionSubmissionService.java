package com.sparrowx.agentic.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.components.IntentComponent;
import com.sparrowx.agentic.components.IntentComponent.IntentRequest;
import com.sparrowx.agentic.exceptions.MissionValidationException;
import com.sparrowx.agentic.governance.BudgetPolicy;
import com.sparrowx.agentic.mission.artifact.ArtifactPreparationResult;
import com.sparrowx.agentic.mission.artifact.InputArtifact;
import com.sparrowx.agentic.mission.artifact.PreparedArtifact;
import com.sparrowx.agentic.mission.model.Mission;
import com.sparrowx.agentic.mission.model.MissionBudget;
import com.sparrowx.agentic.mission.model.MissionConstraints;
import com.sparrowx.agentic.mission.model.MissionPath;
import com.sparrowx.agentic.mission.model.MissionRequest;
import com.sparrowx.agentic.mission.model.MissionStatus;
import com.sparrowx.agentic.mission.model.MissionVersionSnapshot;
import com.sparrowx.agentic.mission.store.MissionStore;
import com.sparrowx.agentic.planning.MissionIntent;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointRef;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointSerializer;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointSnapshot;
import com.sparrowx.agentic.runtime.checkpoint.CheckpointStore;
import com.sparrowx.agentic.steps.PrepareInputArtifactsStep;
import com.sparrowx.agentic.temporal.client.TemporalMissionClient;
import com.sparrowx.agentic.temporal.model.MissionWorkflowInput;
import com.sparrowx.agentic.util.Hashing;
import com.sparrowx.agentic.validation.MissionRequestValidator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MissionSubmissionService {

    private static final int INPUT_SCHEMA_VERSION = 1;
    private static final int INTENT_SCHEMA_VERSION = 1;
    private static final int PREPARED_ARTIFACTS_SCHEMA_VERSION = 1;

    private final MissionStore missionStore;
    private final CheckpointStore checkpointStore;
    private final CheckpointSerializer checkpointSerializer;
    private final PrepareInputArtifactsStep prepareInputArtifactsStep;
    private final IntentComponent intentComponent;
    private final MissionRequestValidator requestValidator;
    private final BudgetPolicy budgetPolicy;
    private final VersionSnapshotProvider versionSnapshotProvider;
    private final TemporalMissionClient temporalMissionClient;
    private final ObjectMapper objectMapper;

    public MissionSubmissionService(
            MissionStore missionStore,
            CheckpointStore checkpointStore,
            CheckpointSerializer checkpointSerializer,
            PrepareInputArtifactsStep prepareInputArtifactsStep,
            IntentComponent intentComponent,
            MissionRequestValidator requestValidator,
            BudgetPolicy budgetPolicy,
            VersionSnapshotProvider versionSnapshotProvider,
            TemporalMissionClient temporalMissionClient,
            ObjectMapper objectMapper
    ) {
        this.missionStore = Objects.requireNonNull(missionStore, "missionStore");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.checkpointSerializer = Objects.requireNonNull(checkpointSerializer, "checkpointSerializer");
        this.prepareInputArtifactsStep =
                Objects.requireNonNull(prepareInputArtifactsStep, "prepareInputArtifactsStep");
        this.intentComponent =
                Objects.requireNonNull(intentComponent, "intentComponent");
        this.requestValidator =
                Objects.requireNonNull(requestValidator, "requestValidator");
        this.budgetPolicy =
                Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        this.versionSnapshotProvider =
                Objects.requireNonNull(versionSnapshotProvider, "versionSnapshotProvider");
        this.temporalMissionClient =
                Objects.requireNonNull(temporalMissionClient, "temporalMissionClient");
        this.objectMapper =
                Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Mission submit(MissionRequest request) {
        Objects.requireNonNull(request, "request");
        MissionBudget normalizedBudget = budgetPolicy.normalize(request.budget());
        MissionRequest normalizedRequest =
                new MissionRequest(
                        request.context(), request.query(),
                        request.inputArtifacts(), request.constraints(), normalizedBudget);

        requestValidator.validate(normalizedRequest);
        String fingerprint = fingerprint(normalizedRequest);
        String tenantId = normalizedRequest.context().tenantId();
        String requestId = normalizedRequest.context().requestId();

        /*
         * Idempotent replay.
         *
         * Existing missions use their frozen MissionIntent rather than
         * rerunning semantic classification.
         */
        Mission existing = missionStore.findByRequestId(tenantId, requestId).orElse(null);

        if (existing != null) {
            verifyFingerprint(existing, fingerprint);

            MissionIntent intent = loadOrResolveIntent(existing, normalizedRequest);
            startOrGet(existing, normalizedRequest, intent);
            return existing;
        }

        Instant submittedAt = Instant.now();
        String missionId = stableMissionId(tenantId, requestId);

        /*
         * Semantic classification happens before artifact preparation.
         *
         * Only artifact descriptors are exposed here; no upload or ingestion
         * side effect is needed to decide FAST/RESEARCH or GOAP/HYBRID.
         */
        MissionIntent intent = classifyIntent(missionId, normalizedRequest);

        MissionVersionSnapshot versionSnapshot =
                Objects.requireNonNull(versionSnapshotProvider.currentSnapshot(), "version snapshot");

        Mission candidate =
                new Mission(
                        missionId,
                        normalizedRequest.context(),
                        fingerprint,
                        normalizedRequest.query(),
                        List.of(),
                        normalizedRequest.constraints(),
                        normalizedRequest.budget(),
                        intent.selectedPath(),
                        MissionStatus.SUBMITTED,
                        versionSnapshot,
                        null,
                        null,
                        submittedAt,
                        null,
                        submittedAt,
                        null
                );

        Mission stored = missionStore.createOrGet(candidate);
        verifyFingerprint(stored, fingerprint);

        /*
         * A concurrent submission may have won createOrGet().
         * Its frozen path must agree with this semantic result.
         */
        if (stored.selectedPath() != intent.selectedPath()) {

            MissionIntent storedIntent = loadIntent(stored);

            if (storedIntent == null || storedIntent.selectedPath() != stored.selectedPath()) {

                throw new IllegalStateException(
                        "MISSION_INTENT_CONFLICT: persisted mission path "
                                + stored.selectedPath() + " differs from resolved path "
                                + intent.selectedPath()
                );
            }
            intent = storedIntent;
        }
        startOrGet(stored, normalizedRequest, intent
        );
        return stored;
    }

    private void startOrGet(Mission mission, MissionRequest normalizedRequest, MissionIntent intent
    ) {
        verifyIntent(mission, intent);
        CheckpointRef inputReference = persistMissionInput(mission, normalizedRequest);
        CheckpointRef intentReference = persistMissionIntent(mission, intent);

        /*
         * Side-effecting preparation happens only after the business mission
         * has a stable identity and submittedAt timestamp.
         */
        ArtifactPreparationResult prepared =
                prepareInputArtifactsStep.execute(
                        mission.missionId(), mission.context().requestId(),
                        normalizedRequest, mission.submittedAt()
                );

        CheckpointRef preparedReference = persistPreparedArtifacts(mission, prepared);
        ensureWorkflowStarted(mission, intent, inputReference, intentReference, preparedReference);
    }

    private MissionIntent loadOrResolveIntent(Mission mission, MissionRequest request) {
        MissionIntent existing = loadIntent(mission);

        if (existing != null) {
            verifyIntent(mission, existing);
            return existing;
        }

        /*
         * Recovery for a submission that persisted the Mission but failed
         * before the intent checkpoint was written.
         */
        MissionIntent resolved = classifyIntent(mission.missionId(), request);
        verifyIntent(mission, resolved);
        persistMissionIntent(mission, resolved);
        return resolved;
    }

    private MissionIntent loadIntent(Mission mission
    ) {
        String checkpointId = intentCheckpointId(mission.missionId());

        CheckpointSnapshot snapshot =
                checkpointStore.findById(mission.tenantId(),
                        mission.missionId(), checkpointId).orElse(null);

        if (snapshot == null) {
            return null;
        }

        if (snapshot.reference().checkpointType() != CheckpointRef.CheckpointType.MISSION_INTENT) {

            throw new IllegalStateException("intent checkpoint has wrong type: " + checkpointId);
        }
        return checkpointSerializer.deserialize(snapshot, MissionIntent.class);
    }

    private MissionIntent classifyIntent(String missionId, MissionRequest request) {
        MissionConstraints constraints = request.constraints();

        return intentComponent.interpret(
                new IntentRequest(missionId, request.query(),

                        /*
                         * Lightweight semantic descriptors only.
                         * Actual uploads/ingestion occur later.
                         */
                        intentArtifacts(request.inputArtifacts()),
                        constraints.preferredPath(),
                        setOf(constraints.allowedTools()),
                        setOf(constraints.allowedSourceServices()),
                        listOf(constraints.requiredOutputSections()),
                        constraints.requireCitations(),
                        constraints.requireHumanReview(),
                        constraints.allowExternalSources(),
                        Map.of(
                                "tenantId", request.context().tenantId(),
                                "requestId", request.context().requestId())
                )
        );
    }

    private static List<PreparedArtifact> intentArtifacts(List<InputArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }

        return artifacts.stream()
                .map(artifact ->
                        new PreparedArtifact(
                                artifact.artifactId(),
                                artifact.type(),
                                "",
                                "",
                                "",
                                "",
                                "",
                                artifact.filename(),
                                artifact.contentType(),
                                artifact.sha256(),
                                artifact.metadata()
                        )
                )
                .toList();
    }

    private CheckpointRef persistMissionInput(Mission mission, MissionRequest request
    ) {
        String checkpointId = "input_" + mission.missionId();

        CheckpointSnapshot snapshot =
                checkpointSerializer.serialize(
                        checkpointId,
                        mission.tenantId(),
                        mission.missionId(),
                        CheckpointRef.CheckpointType.MISSION_INPUT,
                        INPUT_SCHEMA_VERSION,
                        mission.submittedAt(),
                        Map.of("requestId", mission.context().requestId(),
                                "requestFingerprint", mission.requestFingerprint()),
                        request
                );

        return saveAndRequireSame(snapshot);
    }

    private CheckpointRef persistMissionIntent(Mission mission, MissionIntent intent) {
        String checkpointId = intentCheckpointId(mission.missionId());

        CheckpointSnapshot snapshot =
                checkpointSerializer.serialize(
                        checkpointId,
                        mission.tenantId(),
                        mission.missionId(),
                        CheckpointRef.CheckpointType.MISSION_INTENT,
                        INTENT_SCHEMA_VERSION,
                        mission.submittedAt(),
                        Map.of(
                                "requestId", mission.context().requestId(),
                                "requestFingerprint", mission.requestFingerprint(),
                                "path", intent.selectedPath().name(),
                                "planner", intent.plannerMode().name()
                        ), intent
                );

        return saveAndRequireSame(snapshot);
    }

    private CheckpointRef persistPreparedArtifacts(Mission mission, ArtifactPreparationResult prepared
    ) {
        String checkpointId = "prepared_" + mission.missionId();

        CheckpointSnapshot snapshot =
                checkpointSerializer.serialize(
                        checkpointId,
                        mission.tenantId(),
                        mission.missionId(),
                        CheckpointRef.CheckpointType.PREPARED_ARTIFACTS,
                        PREPARED_ARTIFACTS_SCHEMA_VERSION,
                        mission.submittedAt(),
                        Map.of(
                                "requestId", mission.context().requestId(),
                                "requestFingerprint", mission.requestFingerprint(),
                                "artifactCount", Integer.toString(prepared.preparedArtifacts().size()
                                )
                        ),
                        prepared
                );

        return saveAndRequireSame(snapshot);
    }

    private CheckpointRef saveAndRequireSame(CheckpointSnapshot snapshot) {
        CheckpointRef persisted =
                Objects.requireNonNull(checkpointStore.save(snapshot),
                        "checkpointStore.save must not return null");

        if (!snapshot.reference().equals(persisted)) {

            throw new IllegalStateException(
                    "CHECKPOINT_IDEMPOTENCY_CONFLICT: " + snapshot
                            .reference().checkpointId()
            );
        }

        return persisted;
    }

    private void ensureWorkflowStarted(
            Mission mission,
            MissionIntent intent,
            CheckpointRef inputReference,
            CheckpointRef intentReference,
            CheckpointRef preparedReference
    ) {
        MissionWorkflowInput workflowInput =
                new MissionWorkflowInput(
                        mission.missionId(),
                        mission.tenantId(),
                        mission.context().requestId(),
                        inputReference,
                        intentReference,
                        preparedReference,
                        mission.versionSnapshot().snapshotId(),
                        mission.selectedPath(),
                        intent.requiresHumanReview(),
                        mission.budget(),
                        mission.context().traceId()
                );

        /*
         * Temporary:
         *
         * All paths still enter Temporal in this slice.
         *
         * Once MissionActivitiesImpl consumes missionIntentRef correctly,
         * FAST without HITL will be split to direct execution.
         */
        temporalMissionClient.startOrGet(workflowInput);
    }

    private static void verifyIntent(Mission mission, MissionIntent intent
    ) {
        Objects.requireNonNull(intent, "intent must not be null");

        if (!mission.missionId().equals(intent.missionId())) {

            throw new IllegalStateException("MissionIntent belongs to another mission");
        }

        if (mission.selectedPath() != intent.selectedPath()) {

            throw new IllegalStateException("MissionIntent path does not match persisted mission path");
        }
    }

    private String fingerprint(MissionRequest request) {
        FingerprintInput input =
                new FingerprintInput(
                        request.context().tenantId(),
                        request.context().userId(),
                        request.context().projectId(),
                        request.context().teamId(),
                        request.context().metadata(),
                        request.query(),
                        request.inputArtifacts(),
                        request.constraints(),
                        request.budget()
                );

        try {
            return Hashing.sha256Hex(objectMapper.writeValueAsBytes(input));

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException("Unable to fingerprint mission request", exception);
        }
    }

    private static void verifyFingerprint(Mission mission, String requestedFingerprint) {
        if (!mission.requestFingerprint().equals(requestedFingerprint)) {

            throw new MissionValidationException("requestId is already associated with "
                    + "different mission input"
            );
        }
    }

    private static String stableMissionId(String tenantId, String requestId
    ) {
        String identity = tenantId + '\u001f' + requestId;
        String hash = Hashing.sha256Hex(identity.getBytes(StandardCharsets.UTF_8));

        return "msn_" + hash.substring(0, 32);
    }

    private static String intentCheckpointId(String missionId) {
        return "intent_" + missionId;
    }

    private static Set<String> setOf(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static List<String> listOf(List<String> values
    ) {
        return values == null ? List.of() : List.copyOf(values
        );
    }

    @FunctionalInterface
    public interface VersionSnapshotProvider {

        MissionVersionSnapshot currentSnapshot();
    }

    private record FingerprintInput(
            String tenantId,
            String userId,
            String projectId,
            String teamId,
            Map<String, String> contextMetadata,
            String query,
            List<InputArtifact> inputArtifacts,
            MissionConstraints constraints,
            MissionBudget budget
    ) {
    }
}