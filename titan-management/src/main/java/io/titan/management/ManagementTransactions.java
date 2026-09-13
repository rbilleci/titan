package io.titan.management;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementAudit.AuditedCommandResult;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.CommandValidation;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ManagementTransactions {
    private static final String NULL_FIELD = "-";
    private static final Set<String> IMPORT_MODEL_DOCUMENT_ATOMIC_WRITES = Set.of(
            "management_drafts",
            "management_artifact_refs",
            "management_idempotency",
            "management_audit_outcomes");
    private static final Set<String> DEPLOYMENT_ACTIVATION_ROLES = Set.of("admin", "platform");

    private ManagementTransactions() {
    }

    public record TransactionContract(
            String commandName,
            String transactionMode,
            List<String> atomicWrites,
            Map<String, String> dialectNotes
    ) {
        public TransactionContract {
            ManagementCommands.CommandDescriptor descriptor =
                    ManagementCommands.ManagementCommandDescriptors.importModelDocument();
            if (!descriptor.commandName().equals(commandName)) {
                throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: unsupported transaction command");
            }
            if (!"required".equals(transactionMode)) {
                throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: transaction mode must be required");
            }
            atomicWrites = List.copyOf(Objects.requireNonNull(atomicWrites, "transaction atomic writes"));
            dialectNotes = Map.copyOf(Objects.requireNonNull(dialectNotes, "transaction dialect notes"));
            if (!Set.copyOf(atomicWrites).equals(IMPORT_MODEL_DOCUMENT_ATOMIC_WRITES)
                    || atomicWrites.size() != IMPORT_MODEL_DOCUMENT_ATOMIC_WRITES.size()) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-TRANSACTION: transaction contract must exactly match Titan-owned atomic writes");
            }
            if (!dialectNotes.containsKey("postgresql") || !dialectNotes.containsKey("mysql")) {
                throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: dialect notes require PostgreSQL and MySQL");
            }
        }
    }

    public interface TransactionalMutationStore {
        TransactionalCommandExecution execute(
                CommandInvocation invocation,
                TransactionalCommandHandler handler,
                Instant attemptAt,
                Instant outcomeAt);

        void seedDraft(Draft draft);

        void seedArtifactRef(ArtifactRef artifactRef);

        void seedDeployment(Deployment deployment);

        Optional<Draft> draft(String draftId);

        List<Draft> drafts();

        List<Draft> draftsForWorkspace(String workspaceId);

        List<Draft> draftsForModel(String workspaceId, String modelId);

        Optional<ArtifactRef> artifactRef(String artifactRefId);

        List<ArtifactRef> artifactRefs();

        Optional<Deployment> deployment(String deploymentId);

        List<Deployment> deployments();

        List<Deployment> deploymentsForWorkspace(String workspaceId);

        List<AuditRecord> auditRecords();

        List<AuditRecord> auditRecordsForRequest(String requestId);

        List<IdempotencyRecord> idempotencyRecords();

        DeploymentActivationExecution activateDeployment(
                DeploymentActivationRequest request,
                Instant attemptAt,
                Instant outcomeAt);
    }

    public static final class FileTransactionalMutationStore implements TransactionalMutationStore {
        private static final ConcurrentMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();
        private final Path logPath;
        private final Object pathLock;

        public FileTransactionalMutationStore(Path logPath) {
            this.logPath = Objects.requireNonNull(logPath, "transaction log path");
            this.pathLock = PATH_LOCKS.computeIfAbsent(logPath.toAbsolutePath().normalize(), ignored -> new Object());
        }

        @Override
        public TransactionalCommandExecution execute(
                CommandInvocation invocation,
                TransactionalCommandHandler handler,
                Instant attemptAt,
                Instant outcomeAt
        ) {
            Objects.requireNonNull(invocation, "command invocation");
            Objects.requireNonNull(handler, "transactional command handler");
            synchronized (pathLock) {
                State state = readState();
                CommandValidation validation = invocation.validate();
                AuditRecord attempt = auditRecord(invocation, validation, state.nextAuditSequence(), AuditStatus.ATTEMPT,
                        null, null, null, attemptAt);
                appendLines(List.of(encodeAudit(attempt)));

                if (!validation.valid()) {
                    AuditStatus status = unauthorized(validation) ? AuditStatus.UNAUTHORIZED : AuditStatus.FAILURE;
                    AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L, status,
                            null, firstErrorCode(validation), firstErrorMessage(validation), outcomeAt);
                    appendCommittedBatch(List.of(encodeAudit(outcome)));
                    return new TransactionalCommandExecution(validation, attempt, outcome, null, null, false, false);
                }

                String inputHash = invocation.canonicalInputHash();
                Optional<IdempotencyRecord> existing = state.findIdempotency(
                        invocation.descriptor().commandName(),
                        invocation.actor().scope(),
                        invocation.request().idempotencyKey());
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    AuditedCommandResult replay = replay(record);
                    if (!record.inputHash().equals(inputHash)) {
                        replay = AuditedCommandResult.failure("TITAN-MGMT-E020", "idempotency input mismatch");
                    }
                    AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L,
                            replay.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                            replay.outputHash(), replay.errorCode(), replay.errorMessage(), outcomeAt);
                    appendCommittedBatch(List.of(encodeAudit(outcome)));
                    return new TransactionalCommandExecution(
                            validation,
                            attempt,
                            outcome,
                            replay,
                            record,
                            record.inputHash().equals(inputHash),
                            !record.inputHash().equals(inputHash));
                }

                TransactionContext context = new TransactionContext(state);
                TransactionalCommandResult commandResult;
                try {
                    commandResult = Objects.requireNonNull(handler.execute(invocation, context),
                            "transactional command result");
                } catch (RuntimeException exception) {
                    commandResult = TransactionalCommandResult.failure(
                            "TITAN-MGMT-E011",
                            "command handler failed: " + exception.getClass().getSimpleName());
                }

                IdempotencyRecord idempotencyRecord = idempotencyRecord(invocation, inputHash, commandResult, outcomeAt);
                AuditedCommandResult auditedResult = commandResult.auditedResult();
                List<String> transactionLines = new ArrayList<>();
                if (auditedResult.success()) {
                    context.stagedDrafts.values().forEach(draft -> transactionLines.add(encodeDraft(draft)));
                    context.stagedArtifactRefs.values().forEach(ref -> transactionLines.add(encodeArtifactRef(ref)));
                }
                transactionLines.add(encodeIdempotency(idempotencyRecord));
                AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L,
                        auditedResult.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                        auditedResult.outputHash(), auditedResult.errorCode(), auditedResult.errorMessage(), outcomeAt);
                transactionLines.add(encodeAudit(outcome));
                appendCommittedBatch(transactionLines);
                return new TransactionalCommandExecution(
                        validation,
                        attempt,
                        outcome,
                        auditedResult,
                        idempotencyRecord,
                        false,
                        false);
            }
        }

        @Override
        public void seedDraft(Draft draft) {
            synchronized (pathLock) {
                appendLines(List.of(encodeDraft(draft)));
            }
        }

        @Override
        public void seedArtifactRef(ArtifactRef artifactRef) {
            synchronized (pathLock) {
                appendLines(List.of(encodeArtifactRef(artifactRef)));
            }
        }

        @Override
        public void seedDeployment(Deployment deployment) {
            synchronized (pathLock) {
                appendLines(List.of(encodeDeployment(deployment)));
            }
        }

        @Override
        public Optional<Draft> draft(String draftId) {
            synchronized (pathLock) {
                return Optional.ofNullable(readState().drafts.get(draftId));
            }
        }

        @Override
        public List<Draft> drafts() {
            synchronized (pathLock) {
                return readState().drafts();
            }
        }

        @Override
        public List<Draft> draftsForWorkspace(String workspaceId) {
            Objects.requireNonNull(workspaceId, "workspace id");
            synchronized (pathLock) {
                return readState().drafts().stream()
                        .filter(draft -> draft.workspaceId().equals(workspaceId))
                        .toList();
            }
        }

        @Override
        public List<Draft> draftsForModel(String workspaceId, String modelId) {
            Objects.requireNonNull(workspaceId, "workspace id");
            Objects.requireNonNull(modelId, "model id");
            synchronized (pathLock) {
                return readState().drafts().stream()
                        .filter(draft -> draft.workspaceId().equals(workspaceId))
                        .filter(draft -> draft.modelId().equals(modelId))
                        .toList();
            }
        }

        @Override
        public Optional<ArtifactRef> artifactRef(String artifactRefId) {
            synchronized (pathLock) {
                return Optional.ofNullable(readState().artifactRefs.get(artifactRefId));
            }
        }

        @Override
        public List<ArtifactRef> artifactRefs() {
            synchronized (pathLock) {
                return readState().artifactRefs();
            }
        }

        @Override
        public Optional<Deployment> deployment(String deploymentId) {
            synchronized (pathLock) {
                return Optional.ofNullable(readState().deployments.get(deploymentId));
            }
        }

        @Override
        public List<Deployment> deployments() {
            synchronized (pathLock) {
                return readState().deployments();
            }
        }

        @Override
        public List<Deployment> deploymentsForWorkspace(String workspaceId) {
            Objects.requireNonNull(workspaceId, "workspace id");
            synchronized (pathLock) {
                return readState().deployments().stream()
                        .filter(deployment -> deployment.workspaceId().equals(workspaceId))
                        .toList();
            }
        }

        @Override
        public List<AuditRecord> auditRecords() {
            synchronized (pathLock) {
                return readState().auditRecords();
            }
        }

        @Override
        public List<AuditRecord> auditRecordsForRequest(String requestId) {
            Objects.requireNonNull(requestId, "request id");
            synchronized (pathLock) {
                return readState().auditRecords().stream()
                        .filter(record -> requestId.equals(record.requestId()))
                        .toList();
            }
        }

        @Override
        public List<IdempotencyRecord> idempotencyRecords() {
            synchronized (pathLock) {
                return readState().idempotencyRecords();
            }
        }

        @Override
        public DeploymentActivationExecution activateDeployment(
                DeploymentActivationRequest request,
                Instant attemptAt,
                Instant outcomeAt
        ) {
            Objects.requireNonNull(request, "deployment activation request");
            Objects.requireNonNull(attemptAt, "deployment activation attempt time");
            Objects.requireNonNull(outcomeAt, "deployment activation outcome time");
            synchronized (pathLock) {
                State state = readState();
                String inputHash = request.inputHash();
                AuditRecord attempt = deploymentAuditRecord(
                        request,
                        state.nextAuditSequence(),
                        AuditStatus.ATTEMPT,
                        inputHash,
                        null,
                        null,
                        null,
                        attemptAt);
                appendLines(List.of(encodeAudit(attempt)));

                Optional<IdempotencyRecord> existing = state.findIdempotency(
                        DeploymentActivationRequest.COMMAND_NAME,
                        request.workspaceId(),
                        request.idempotencyKey());
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    AuditedCommandResult replay = replay(record);
                    boolean conflict = !record.inputHash().equals(inputHash);
                    if (conflict) {
                        replay = AuditedCommandResult.failure("TITAN-MGMT-E020", "idempotency input mismatch");
                    }
                    AuditRecord outcome = deploymentAuditRecord(
                            request,
                            attempt.sequence() + 1L,
                            replay.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                            inputHash,
                            replay.outputHash(),
                            replay.errorCode(),
                            replay.errorMessage(),
                            outcomeAt);
                    appendCommittedBatch(List.of(encodeAudit(outcome)));
                    return new DeploymentActivationExecution(
                            attempt,
                            outcome,
                            replay.success() ? state.deployments.get(record.resultRef()) : null,
                            record,
                            record.inputHash().equals(inputHash),
                            conflict);
                }

                ActivationValidation validation = validateDeploymentActivation(state, request);
                if (!validation.valid()) {
                    AuditedCommandResult failure = AuditedCommandResult.failure(
                            validation.errorCode(),
                            validation.errorMessage());
                    IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                            DeploymentActivationRequest.COMMAND_NAME,
                            request.workspaceId(),
                            request.idempotencyKey(),
                            inputHash,
                            OutcomeStatus.FAILURE,
                            hashFailure(failure),
                            null,
                            validation.errorCode(),
                            validation.errorMessage(),
                            outcomeAt);
                    AuditRecord outcome = deploymentAuditRecord(
                            request,
                            attempt.sequence() + 1L,
                            AuditStatus.FAILURE,
                            inputHash,
                            null,
                            validation.errorCode(),
                            validation.errorMessage(),
                            outcomeAt);
                    appendCommittedBatch(List.of(encodeIdempotency(idempotencyRecord), encodeAudit(outcome)));
                    return new DeploymentActivationExecution(attempt, outcome, null, idempotencyRecord, false, false);
                }

                Deployment activeDeployment = new Deployment(
                        request.deploymentId(),
                        request.workspaceId(),
                        request.artifactRefId(),
                        request.environment(),
                        DeploymentStatus.ACTIVE,
                        state.deployments.containsKey(request.deploymentId())
                                ? state.deployments.get(request.deploymentId()).requestedAt()
                                : outcomeAt,
                        outcomeAt);
                List<Deployment> supersededDeployments = state.deployments().stream()
                        .filter(deployment -> deployment.workspaceId().equals(request.workspaceId()))
                        .filter(deployment -> deployment.environment().equals(request.environment()))
                        .filter(deployment -> deployment.status() == DeploymentStatus.ACTIVE)
                        .filter(deployment -> !deployment.id().equals(request.deploymentId()))
                        .map(deployment -> new Deployment(
                                deployment.id(),
                                deployment.workspaceId(),
                                deployment.artifactRefId(),
                                deployment.environment(),
                                DeploymentStatus.SUPERSEDED,
                                deployment.requestedAt(),
                                deployment.activatedAt()))
                        .toList();
                String outputHash = sha256(activeDeployment.stableJson()
                        + "\n"
                        + String.join("\n", supersededDeployments.stream()
                                .map(Deployment::stableJson)
                                .sorted()
                                .toList()));
                AuditedCommandResult result = AuditedCommandResult.success(outputHash);
                IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                        DeploymentActivationRequest.COMMAND_NAME,
                        request.workspaceId(),
                        request.idempotencyKey(),
                        inputHash,
                        OutcomeStatus.SUCCESS,
                        outputHash,
                        activeDeployment.id(),
                        null,
                        null,
                        outcomeAt);
                AuditRecord outcome = deploymentAuditRecord(
                        request,
                        attempt.sequence() + 1L,
                        AuditStatus.SUCCESS,
                        inputHash,
                        outputHash,
                        null,
                        null,
                        outcomeAt);
                List<String> transactionLines = new ArrayList<>();
                supersededDeployments.forEach(deployment -> transactionLines.add(encodeDeployment(deployment)));
                transactionLines.add(encodeDeployment(activeDeployment));
                transactionLines.add(encodeIdempotency(idempotencyRecord));
                transactionLines.add(encodeAudit(outcome));
                appendCommittedBatch(transactionLines);
                return new DeploymentActivationExecution(
                        attempt,
                        outcome,
                        activeDeployment,
                        idempotencyRecord,
                        false,
                        false);
            }
        }

        private State readState() {
            State state = new State();
            if (!Files.exists(logPath)) {
                return state;
            }
            try {
                List<String[]> pendingBatch = null;
                for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    if ("BATCH_BEGIN".equals(fields[0])) {
                        pendingBatch = new ArrayList<>();
                        continue;
                    }
                    if ("BATCH_COMMIT".equals(fields[0])) {
                        if (pendingBatch != null) {
                            for (String[] pendingFields : pendingBatch) {
                                applyEntry(state, pendingFields);
                            }
                            pendingBatch = null;
                        }
                        continue;
                    }
                    if (pendingBatch != null) {
                        pendingBatch.add(fields);
                    } else {
                        applyEntry(state, fields);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("TITAN-GAP006-TRANSACTION: failed to read transaction log", exception);
            }
            return state;
        }

        private void applyEntry(State state, String[] fields) {
            switch (fields[0]) {
                case "DRAFT" -> state.drafts.put(decodeField(fields[1]), decodeDraft(fields));
                case "ARTIFACT" -> state.artifactRefs.put(decodeField(fields[1]), decodeArtifactRef(fields));
                case "DEPLOYMENT" -> state.deployments.put(decodeField(fields[1]), decodeDeployment(fields));
                case "AUDIT" -> state.auditRecords.add(decodeAudit(fields));
                case "IDEMPOTENCY" -> state.idempotencyRecords.add(decodeIdempotency(fields));
                default -> throw new IllegalStateException(
                        "TITAN-GAP006-TRANSACTION: malformed transaction log entry");
            }
        }

        private void appendCommittedBatch(List<String> lines) {
            List<String> framedLines = new ArrayList<>();
            framedLines.add("BATCH_BEGIN");
            framedLines.addAll(lines);
            framedLines.add("BATCH_COMMIT");
            appendLines(framedLines);
        }

        private void appendLines(List<String> lines) {
            try {
                Path parent = logPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        logPath,
                        String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new IllegalStateException("TITAN-GAP006-TRANSACTION: failed to append transaction log", exception);
            }
        }
    }

    public record TransactionalCommandExecution(
            CommandValidation validation,
            AuditRecord attemptRecord,
            AuditRecord outcomeRecord,
            AuditedCommandResult result,
            IdempotencyRecord idempotencyRecord,
            boolean replayed,
            boolean conflict
    ) {
        public TransactionalCommandExecution {
            Objects.requireNonNull(validation, "command validation");
            Objects.requireNonNull(attemptRecord, "attempt audit record");
            Objects.requireNonNull(outcomeRecord, "outcome audit record");
        }

        public boolean success() {
            return result != null && result.success();
        }
    }

    public record DeploymentActivationRequest(
            String actorId,
            String actorRole,
            String workspaceId,
            String requestId,
            String idempotencyKey,
            String deploymentId,
            String artifactRefId,
            String environment,
            String expectedArtifactHash,
            String expectedPackageMode,
            String expectedDialect,
            String expectedManifestContentHash,
            String expectedObjectInventoryHash,
            String expectedInstallPlanHash,
            String expectedInstallVerificationHash,
            String expectedSourceInputsHash
    ) {
        private static final String COMMAND_NAME = "management.activateDeployment";

        public DeploymentActivationRequest {
            actorId = requireStableText(actorId, "deployment activation actor id");
            actorRole = requireStableText(actorRole, "deployment activation actor role");
            workspaceId = requireStableText(workspaceId, "deployment activation workspace id");
            requestId = requireStableText(requestId, "deployment activation request id");
            idempotencyKey = requireStableText(idempotencyKey, "deployment activation idempotency key");
            deploymentId = requireStableText(deploymentId, "deployment activation deployment id");
            artifactRefId = requireStableText(artifactRefId, "deployment activation artifact ref id");
            environment = requireStableText(environment, "deployment activation environment");
            requireHashIdentity(expectedArtifactHash, "deployment activation expected artifact hash");
            expectedPackageMode = requireStableText(expectedPackageMode, "deployment activation expected package mode");
            expectedDialect = requireStableText(expectedDialect, "deployment activation expected dialect");
            requireHashIdentity(expectedManifestContentHash, "deployment activation expected manifest content hash");
            requireHashIdentity(expectedObjectInventoryHash, "deployment activation expected object inventory hash");
            requireHashIdentity(expectedInstallPlanHash, "deployment activation expected install plan hash");
            requireHashIdentity(expectedInstallVerificationHash, "deployment activation expected install verification hash");
            requireHashIdentity(expectedSourceInputsHash, "deployment activation expected source inputs hash");
        }

        private String inputHash() {
            return sha256(String.join(
                    "\n",
                    COMMAND_NAME,
                    workspaceId,
                    deploymentId,
                    artifactRefId,
                    environment,
                    expectedArtifactHash,
                    expectedPackageMode,
                    expectedDialect,
                    expectedManifestContentHash,
                    expectedObjectInventoryHash,
                    expectedInstallPlanHash,
                    expectedInstallVerificationHash,
                    expectedSourceInputsHash));
        }
    }

    public record DeploymentActivationExecution(
            AuditRecord attemptRecord,
            AuditRecord outcomeRecord,
            Deployment deployment,
            IdempotencyRecord idempotencyRecord,
            boolean replayed,
            boolean conflict
    ) {
        public DeploymentActivationExecution {
            Objects.requireNonNull(attemptRecord, "deployment activation attempt audit record");
            Objects.requireNonNull(outcomeRecord, "deployment activation outcome audit record");
        }

        public boolean success() {
            return outcomeRecord.status() == AuditStatus.SUCCESS;
        }
    }

    public record TransactionalCommandResult(
            AuditedCommandResult auditedResult,
            String resultRef
    ) {
        public TransactionalCommandResult {
            Objects.requireNonNull(auditedResult, "audited result");
            if (auditedResult.success()) {
                requireResultRef(resultRef);
            }
        }

        public static TransactionalCommandResult success(String outputHash, String resultRef) {
            return new TransactionalCommandResult(AuditedCommandResult.success(outputHash), resultRef);
        }

        public static TransactionalCommandResult failure(String errorCode, String errorMessage) {
            return new TransactionalCommandResult(AuditedCommandResult.failure(errorCode, errorMessage), null);
        }
    }

    public interface TransactionalCommandHandler {
        TransactionalCommandResult execute(CommandInvocation invocation, TransactionContext transaction);
    }

    public static final class TransactionContext {
        private final State baseState;
        private final Map<String, Draft> stagedDrafts = new LinkedHashMap<>();
        private final Map<String, ArtifactRef> stagedArtifactRefs = new LinkedHashMap<>();

        private TransactionContext(State baseState) {
            this.baseState = baseState;
        }

        public Optional<Draft> draft(String draftId) {
            if (stagedDrafts.containsKey(draftId)) {
                return Optional.of(stagedDrafts.get(draftId));
            }
            return Optional.ofNullable(baseState.drafts.get(draftId));
        }

        public void putDraft(Draft draft) {
            stagedDrafts.put(draft.id(), draft);
        }

        public void putArtifactRef(ArtifactRef artifactRef) {
            stagedArtifactRefs.put(artifactRef.id(), artifactRef);
        }
    }

    public static TransactionContract importModelDocumentTransactionContract() {
        return new TransactionContract(
                "management.importModelDocument",
                "required",
                List.of(
                        "management_drafts",
                        "management_artifact_refs",
                        "management_idempotency",
                        "management_audit_outcomes"),
                Map.of(
                        "postgresql",
                        "single transaction with row locks for draft and idempotency key",
                        "mysql",
                        "single InnoDB transaction; avoid DDL inside management mutation transaction"));
    }

    private static AuditRecord auditRecord(
            CommandInvocation invocation,
            CommandValidation validation,
            long sequence,
            AuditStatus status,
            String outputHash,
            String errorCode,
            String errorMessage,
            Instant occurredAt
    ) {
        ManagementCommands.ActorContext actor = invocation.actor();
        ManagementCommands.RequestContext request = invocation.request();
        return new AuditRecord(
                "audit-" + String.format("%06d", sequence),
                sequence,
                invocation.descriptor().commandName(),
                status,
                actor == null ? null : actor.actorId(),
                actor == null ? null : actor.role(),
                actor == null ? null : actor.scope(),
                request == null ? null : request.requestId(),
                request == null ? null : request.idempotencyKey(),
                validation.inputHash(),
                outputHash,
                errorCode,
                errorMessage,
                occurredAt);
    }

    private static AuditRecord deploymentAuditRecord(
            DeploymentActivationRequest request,
            long sequence,
            AuditStatus status,
            String inputHash,
            String outputHash,
            String errorCode,
            String errorMessage,
            Instant occurredAt
    ) {
        return new AuditRecord(
                "audit-" + String.format("%06d", sequence),
                sequence,
                DeploymentActivationRequest.COMMAND_NAME,
                status,
                request.actorId(),
                request.actorRole(),
                request.workspaceId(),
                request.requestId(),
                request.idempotencyKey(),
                inputHash,
                outputHash,
                errorCode,
                errorMessage,
                occurredAt);
    }

    private static ActivationValidation validateDeploymentActivation(
            State state,
            DeploymentActivationRequest request
    ) {
        if (!DEPLOYMENT_ACTIVATION_ROLES.contains(request.actorRole())) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E035",
                    "deployment activation requires admin or platform actor");
        }
        ArtifactRef artifactRef = state.artifactRefs.get(request.artifactRefId());
        if (artifactRef == null) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E030",
                    "deployment activation requires existing artifact ref");
        }
        if (!artifactRef.artifactHash().equals(request.expectedArtifactHash())) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E031",
                    "deployment activation artifact hash mismatch");
        }
        if (!hasGap005MetadataPaths(artifactRef)) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E032",
                    "deployment activation requires GAP-005 artifact metadata");
        }
        if (!matchesGap005Evidence(artifactRef, request)) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E036",
                    "deployment activation GAP-005 artifact evidence mismatch");
        }
        if (artifactRef.verificationStatus() != VerificationStatus.PASSED) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E033",
                    "deployment activation requires passed install verification");
        }
        Deployment existing = state.deployments.get(request.deploymentId());
        if (existing != null
                && existing.status() != DeploymentStatus.PENDING_ACTIVATION
                && existing.status() != DeploymentStatus.ACTIVE) {
            return ActivationValidation.failure(
                    "TITAN-MGMT-E034",
                    "deployment activation cannot activate terminal deployment");
        }
        return ActivationValidation.pass();
    }

    private static boolean hasGap005MetadataPaths(ArtifactRef artifactRef) {
        return artifactRef.manifestPath().endsWith("titan-artifact.json")
                && artifactRef.objectInventoryPath().endsWith("titan-object-inventory.json")
                && artifactRef.installPlanPath().endsWith("titan-install-plan.json")
                && artifactRef.installVerificationPath().endsWith("titan-install-verification.json");
    }

    private static boolean matchesGap005Evidence(ArtifactRef artifactRef, DeploymentActivationRequest request) {
        return request.expectedPackageMode().equals(artifactRef.packageMode())
                && request.expectedDialect().equals(artifactRef.dialect())
                && request.expectedManifestContentHash().equals(artifactRef.manifestContentHash())
                && request.expectedObjectInventoryHash().equals(artifactRef.objectInventoryHash())
                && request.expectedInstallPlanHash().equals(artifactRef.installPlanHash())
                && request.expectedInstallVerificationHash().equals(artifactRef.installVerificationHash())
                && request.expectedSourceInputsHash().equals(artifactRef.sourceInputsHash());
    }

    private record ActivationValidation(boolean valid, String errorCode, String errorMessage) {
        private static ActivationValidation pass() {
            return new ActivationValidation(true, null, null);
        }

        private static ActivationValidation failure(String errorCode, String errorMessage) {
            return new ActivationValidation(false, errorCode, errorMessage);
        }
    }

    private static IdempotencyRecord idempotencyRecord(
            CommandInvocation invocation,
            String inputHash,
            TransactionalCommandResult result,
            Instant createdAt
    ) {
        AuditedCommandResult auditedResult = result.auditedResult();
        return new IdempotencyRecord(
                invocation.descriptor().commandName(),
                invocation.actor().scope(),
                invocation.request().idempotencyKey(),
                inputHash,
                auditedResult.success() ? OutcomeStatus.SUCCESS : OutcomeStatus.FAILURE,
                auditedResult.success() ? auditedResult.outputHash() : hashFailure(auditedResult),
                result.resultRef(),
                auditedResult.errorCode(),
                auditedResult.errorMessage(),
                createdAt);
    }

    private static AuditedCommandResult replay(IdempotencyRecord record) {
        if (record.outcomeStatus() == OutcomeStatus.SUCCESS) {
            return AuditedCommandResult.success(record.outcomeHash());
        }
        return AuditedCommandResult.failure(record.errorCode(), record.errorMessage());
    }

    private static boolean unauthorized(CommandValidation validation) {
        return validation.errors().stream().anyMatch(error ->
                error.startsWith("TITAN-MGMT-E002 ")
                        || error.startsWith("TITAN-MGMT-E005 ")
                        || error.startsWith("TITAN-MGMT-E008 "));
    }

    private static String firstErrorCode(CommandValidation validation) {
        String firstError = validation.errors().isEmpty() ? "TITAN-MGMT-E010" : validation.errors().get(0);
        int separator = firstError.indexOf(' ');
        return separator < 0 ? firstError : firstError.substring(0, separator);
    }

    private static String firstErrorMessage(CommandValidation validation) {
        return validation.errors().isEmpty() ? "command failed" : validation.errors().get(0);
    }

    private static String encodeDraft(Draft draft) {
        return String.join(
                "\t",
                "DRAFT",
                encodeField(draft.id()),
                encodeField(draft.workspaceId()),
                encodeField(draft.modelId()),
                Long.toString(draft.version()),
                encodeField(draft.status().name()),
                encodeField(draft.documentHash()),
                encodeField(draft.createdAt().toString()),
                encodeField(draft.updatedAt().toString()),
                encodeMetadata(draft.metadata()));
    }

    private static Draft decodeDraft(String[] fields) {
        // Audit G-11 defect fix: draft metadata used to be dropped on encode and replayed as
        // Map.of(). It now round-trips through the log; 9-field entries are the legacy
        // metadata-less format and decode with empty metadata.
        if (fields.length != 9 && fields.length != 10) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed draft entry");
        }
        return new Draft(
                decodeField(fields[1]),
                decodeField(fields[2]),
                decodeField(fields[3]),
                Long.parseLong(fields[4]),
                DraftStatus.valueOf(decodeField(fields[5])),
                decodeField(fields[6]),
                Instant.parse(Objects.requireNonNull(decodeField(fields[7]))),
                Instant.parse(Objects.requireNonNull(decodeField(fields[8]))),
                fields.length == 10 ? decodeMetadata(fields[9]) : Map.of());
    }

    /**
     * Encodes draft metadata as a single tab-free log field: entries (already sorted by the
     * {@link Draft} canonical constructor) are rendered {@code base64url(key)=base64url(value)}
     * and joined with {@code ,}. An empty map encodes as the null-field marker.
     */
    private static String encodeMetadata(Map<String, String> metadata) {
        if (metadata.isEmpty()) {
            return NULL_FIELD;
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            entries.add(encodeField(entry.getKey()) + "=" + encodeField(entry.getValue()));
        }
        return String.join(",", entries);
    }

    private static Map<String, String> decodeMetadata(String field) {
        if (NULL_FIELD.equals(field)) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String entry : field.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed draft metadata entry");
            }
            metadata.put(
                    decodeField(entry.substring(0, separator)),
                    decodeField(entry.substring(separator + 1)));
        }
        return metadata;
    }

    private static String encodeArtifactRef(ArtifactRef ref) {
        return String.join(
                "\t",
                "ARTIFACT",
                encodeField(ref.id()),
                encodeField(ref.artifactId()),
                encodeField(ref.artifactHash()),
                encodeField(ref.manifestPath()),
                encodeField(ref.objectInventoryPath()),
                encodeField(ref.installPlanPath()),
                encodeField(ref.installVerificationPath()),
                encodeField(ref.verificationStatus().name()),
                encodeField(ref.packageMode()),
                encodeField(ref.dialect()),
                encodeField(ref.manifestContentHash()),
                encodeField(ref.objectInventoryHash()),
                encodeField(ref.installPlanHash()),
                encodeField(ref.installVerificationHash()),
                encodeField(ref.sourceInputsHash()));
    }

    private static ArtifactRef decodeArtifactRef(String[] fields) {
        if (fields.length != 9 && fields.length != 16) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed artifact entry");
        }
        return new ArtifactRef(
                decodeField(fields[1]),
                decodeField(fields[2]),
                decodeField(fields[3]),
                decodeField(fields[4]),
                decodeField(fields[5]),
                decodeField(fields[6]),
                decodeField(fields[7]),
                VerificationStatus.valueOf(decodeField(fields[8])),
                fields.length == 16 ? decodeField(fields[9]) : null,
                fields.length == 16 ? decodeField(fields[10]) : null,
                fields.length == 16 ? decodeField(fields[11]) : null,
                fields.length == 16 ? decodeField(fields[12]) : null,
                fields.length == 16 ? decodeField(fields[13]) : null,
                fields.length == 16 ? decodeField(fields[14]) : null,
                fields.length == 16 ? decodeField(fields[15]) : null);
    }

    private static String encodeDeployment(Deployment deployment) {
        return String.join(
                "\t",
                "DEPLOYMENT",
                encodeField(deployment.id()),
                encodeField(deployment.workspaceId()),
                encodeField(deployment.artifactRefId()),
                encodeField(deployment.environment()),
                encodeField(deployment.status().name()),
                encodeField(deployment.requestedAt().toString()),
                encodeField(deployment.activatedAt() == null ? null : deployment.activatedAt().toString()));
    }

    private static Deployment decodeDeployment(String[] fields) {
        if (fields.length != 8) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed deployment entry");
        }
        String activatedAt = decodeField(fields[7]);
        return new Deployment(
                decodeField(fields[1]),
                decodeField(fields[2]),
                decodeField(fields[3]),
                decodeField(fields[4]),
                DeploymentStatus.valueOf(decodeField(fields[5])),
                Instant.parse(Objects.requireNonNull(decodeField(fields[6]))),
                activatedAt == null ? null : Instant.parse(activatedAt));
    }

    private static String encodeAudit(AuditRecord record) {
        return String.join(
                "\t",
                "AUDIT",
                encodeField(record.id()),
                Long.toString(record.sequence()),
                encodeField(record.commandName()),
                encodeField(record.status().name()),
                encodeField(record.actorId()),
                encodeField(record.actorRole()),
                encodeField(record.actorScope()),
                encodeField(record.requestId()),
                encodeField(record.idempotencyKey()),
                encodeField(record.inputHash()),
                encodeField(record.outputHash()),
                encodeField(record.errorCode()),
                encodeField(record.errorMessage()),
                encodeField(record.occurredAt().toString()));
    }

    private static AuditRecord decodeAudit(String[] fields) {
        if (fields.length != 15) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed audit entry");
        }
        return new AuditRecord(
                decodeField(fields[1]),
                Long.parseLong(fields[2]),
                decodeField(fields[3]),
                AuditStatus.valueOf(decodeField(fields[4])),
                decodeField(fields[5]),
                decodeField(fields[6]),
                decodeField(fields[7]),
                decodeField(fields[8]),
                decodeField(fields[9]),
                decodeField(fields[10]),
                decodeField(fields[11]),
                decodeField(fields[12]),
                decodeField(fields[13]),
                Instant.parse(Objects.requireNonNull(decodeField(fields[14]))));
    }

    private static String encodeIdempotency(IdempotencyRecord record) {
        return String.join(
                "\t",
                "IDEMPOTENCY",
                encodeField(record.commandName()),
                encodeField(record.scope()),
                encodeField(record.idempotencyKey()),
                encodeField(record.inputHash()),
                encodeField(record.outcomeStatus().name()),
                encodeField(record.outcomeHash()),
                encodeField(record.resultRef()),
                encodeField(record.errorCode()),
                encodeField(record.errorMessage()),
                encodeField(record.createdAt().toString()));
    }

    private static IdempotencyRecord decodeIdempotency(String[] fields) {
        if (fields.length != 11) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: malformed idempotency entry");
        }
        return new IdempotencyRecord(
                decodeField(fields[1]),
                decodeField(fields[2]),
                decodeField(fields[3]),
                decodeField(fields[4]),
                OutcomeStatus.valueOf(decodeField(fields[5])),
                decodeField(fields[6]),
                decodeField(fields[7]),
                decodeField(fields[8]),
                decodeField(fields[9]),
                Instant.parse(Objects.requireNonNull(decodeField(fields[10]))));
    }

    private static String encodeField(String value) {
        if (value == null) {
            return NULL_FIELD;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        if (NULL_FIELD.equals(value)) {
            return null;
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void requireResultRef(String resultRef) {
        if (resultRef == null || resultRef.isBlank()) {
            throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: successful transaction requires result ref");
        }
    }

    private static String requireStableText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: requires " + field);
        }
        if (!value.matches("[a-z][a-z0-9]*(?:[-_.:][a-z0-9]+)*")) {
            throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: " + field + " must be stable");
        }
        return value;
    }

    private static void requireHashIdentity(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("TITAN-GAP006-TRANSACTION: " + field + " must use sha256 identity");
        }
    }

    private static String hashFailure(AuditedCommandResult result) {
        return sha256(result.errorCode() + "\n" + result.errorMessage());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class State {
        private final Map<String, Draft> drafts = new LinkedHashMap<>();
        private final Map<String, ArtifactRef> artifactRefs = new LinkedHashMap<>();
        private final Map<String, Deployment> deployments = new LinkedHashMap<>();
        private final List<AuditRecord> auditRecords = new ArrayList<>();
        private final List<IdempotencyRecord> idempotencyRecords = new ArrayList<>();

        private long nextAuditSequence() {
            return auditRecords.size() + 1L;
        }

        private Optional<IdempotencyRecord> findIdempotency(String commandName, String scope, String idempotencyKey) {
            return idempotencyRecords.stream()
                    .filter(record -> record.commandName().equals(commandName)
                            && record.scope().equals(scope)
                            && record.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        private List<Draft> drafts() {
            return drafts.values().stream()
                    .sorted(Comparator.comparing(Draft::workspaceId)
                            .thenComparing(Draft::modelId)
                            .thenComparingLong(Draft::version)
                            .thenComparing(Draft::id))
                    .toList();
        }

        private List<ArtifactRef> artifactRefs() {
            return artifactRefs.values().stream()
                    .sorted(Comparator.comparing(ArtifactRef::id))
                    .toList();
        }

        private List<Deployment> deployments() {
            return deployments.values().stream()
                    .sorted(Comparator.comparing(Deployment::workspaceId)
                            .thenComparing(Deployment::environment)
                            .thenComparing(Deployment::requestedAt)
                            .thenComparing(Deployment::id))
                    .toList();
        }

        private List<AuditRecord> auditRecords() {
            return auditRecords.stream()
                    .sorted(Comparator.comparingLong(AuditRecord::sequence))
                    .toList();
        }

        private List<IdempotencyRecord> idempotencyRecords() {
            return List.copyOf(idempotencyRecords);
        }
    }
}
