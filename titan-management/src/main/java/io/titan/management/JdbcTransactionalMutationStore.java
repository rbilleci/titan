package io.titan.management;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementAudit.AuditedCommandResult;
import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.CommandValidation;
import io.titan.management.ManagementCommands.RequestContext;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.DeploymentActivationRequest;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandHandler;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import io.titan.management.ManagementTransactions.TransactionalMutationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link TransactionalMutationStore} (B4) — the dogfooded management store whose
 * mutations run on Titan-transpiled SQL.
 *
 * <p>It owns the transaction envelope: {@code getConnection() -> setAutoCommit(false) -> [idempotency
 * read] -> CALL the transpiled mutation routine -> commit / rollback-on-exception}. MUTATIONS go
 * through the routines ({@code CALL management.import_model_document(...)},
 * {@code management.seed_draft(...)}, {@code management.activate_deployment(...)}, …); READS are plain
 * parameterized SELECTs whose ordering/filtering reproduce
 * {@link ManagementTransactions.FileTransactionalMutationStore} exactly.
 *
 * <p>The schema + routines are assumed to exist (see {@link ManagementSchemaInstaller} for the
 * bootstrap/test deploy path; production bootstrap is out-of-band).
 *
 * <h2>Faithfulness notes (RD-1 + RD-2 CLOSED — byte-faithful to the file store)</h2>
 * <ul>
 *   <li><b>execute / importModelDocument (RD-2 CLOSED).</b> The committed {@code import_model_document}
 *       routine is the happy-path FAITHFUL idempotent-replay import: it upserts the draft, writes ONE
 *       success-outcome audit row, and writes ONE success idempotency row — server-side, atomically,
 *       with a G2 short-circuit on replay. This adapter wraps that {@code CALL} in the transaction
 *       envelope and adds the {@code ATTEMPT} audit row itself so {@code auditRecordsForRequest}
 *       returns the file store's {@code [ATTEMPT, SUCCESS]} pair. The routine now carries THREE
 *       DISTINCT hashes: the adapter passes the canonical INPUT hash (the load-bearing idempotency +
 *       audit {@code input_hash} driving replay / E020), the handler's OUTPUT hash (the audit
 *       {@code output_hash} AND the idempotency {@code outcome_hash}) and the DOCUMENT hash (the draft
 *       {@code document_hash}) — so the durable {@code outcome_hash} is the output hash, distinct from
 *       the input hash. Validation-failure and handler-failure outcomes (which the success-only
 *       routine does not model) are written directly by the adapter, exactly as the file store does,
 *       without a routine {@code CALL}.</li>
 *   <li><b>activateDeployment preconditions (RD-1 CLOSED, 7-for-7 server-side).</b> The
 *       {@code activate_deployment} {@code @StoredFunction} performs the FULL typed precondition
 *       read-decide-RETURN chain server-side and returns an int status code; the adapter maps it back
 *       to the {@code TITAN-MGMT-E03x} domain error via {@link DeploymentActivationStatus}, no longer
 *       re-implementing any check. ALL SEVEN preconditions (E030–E036) now live in the routine —
 *       including E032, the GAP-005 metadata-PATH suffix match, expressed faithfully via
 *       {@code Column.like("%titan-*.json")} (a constant suffix with no LIKE metacharacters is exactly
 *       {@code endsWith}). The adapter is a PURE PASS-THROUGH for activation: {@code SELECT
 *       activate_deployment(...)} then map the int. See {@code activateInTransaction}.</li>
 * </ul>
 */
public final class JdbcTransactionalMutationStore implements TransactionalMutationStore {

    // ACTIVATE_DEPLOYMENT_COMMAND is private to the nested record; mirror its literal
    // (the same "management.activateDeployment" the file store and the routine bundle use).
    private static final String ACTIVATE_DEPLOYMENT_COMMAND = "management.activateDeployment";

    private final DataSource dataSource;

    public JdbcTransactionalMutationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    // ------------------------------------------------------------------
    // execute — the transactional, idempotent, audited import command.
    // ------------------------------------------------------------------

    @Override
    public TransactionalCommandExecution execute(
            CommandInvocation invocation,
            TransactionalCommandHandler handler,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        Objects.requireNonNull(invocation, "command invocation");
        Objects.requireNonNull(handler, "transactional command handler");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                TransactionalCommandExecution execution = executeInTransaction(
                        connection, invocation, handler, attemptAt, outcomeAt);
                connection.commit();
                return execution;
            } catch (RuntimeException | SQLException exception) {
                safeRollback(connection);
                throw asRuntime("TITAN-GAP006-TRANSACTION: execute failed", exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: execute failed", exception);
        }
    }

    private TransactionalCommandExecution executeInTransaction(
            Connection connection,
            CommandInvocation invocation,
            TransactionalCommandHandler handler,
            Instant attemptAt,
            Instant outcomeAt
    ) throws SQLException {
        CommandValidation validation = invocation.validate();
        long attemptSequence = nextAuditSequence(connection);
        AuditRecord attempt = auditRecord(invocation, validation, attemptSequence, AuditStatus.ATTEMPT,
                null, null, null, attemptAt);
        ManagementJdbc.insertAudit(connection, attempt);

        if (!validation.valid()) {
            AuditStatus status = unauthorized(validation) ? AuditStatus.UNAUTHORIZED : AuditStatus.FAILURE;
            AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L, status,
                    null, firstErrorCode(validation), firstErrorMessage(validation), outcomeAt);
            ManagementJdbc.insertAudit(connection, outcome);
            return new TransactionalCommandExecution(validation, attempt, outcome, null, null, false, false);
        }

        String inputHash = invocation.canonicalInputHash();
        Optional<IdempotencyRecord> existing = JdbcIdempotencyStore.find(
                connection,
                invocation.descriptor().commandName(),
                invocation.actor().scope(),
                invocation.request().idempotencyKey(),
                true);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            boolean conflict = !record.inputHash().equals(inputHash);
            AuditedCommandResult replay = conflict
                    ? AuditedCommandResult.failure("TITAN-MGMT-E020", "idempotency input mismatch")
                    : replay(record);
            AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L,
                    replay.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                    replay.outputHash(), replay.errorCode(), replay.errorMessage(), outcomeAt);
            ManagementJdbc.insertAudit(connection, outcome);
            return new TransactionalCommandExecution(
                    validation, attempt, outcome, replay, record, !conflict, conflict);
        }

        TransactionalCommandResult commandResult;
        try {
            commandResult = Objects.requireNonNull(handler.execute(invocation, null),
                    "transactional command result");
        } catch (RuntimeException exception) {
            commandResult = TransactionalCommandResult.failure(
                    "TITAN-MGMT-E011",
                    "command handler failed: " + exception.getClass().getSimpleName());
        }

        AuditedCommandResult auditedResult = commandResult.auditedResult();

        if (auditedResult.success()) {
            // The Titan-transpiled routine is the durable mutation: it upserts the draft, writes the
            // success-outcome audit row (seq = attempt + 1, id passed in) and the idempotency row in
            // one server-side transaction with the G2 replay short-circuit. The adapter has already
            // written the ATTEMPT row; this CALL adds the matching SUCCESS row + idempotency row.
            //
            // RD-2 (CLOSED): the routine now carries THREE DISTINCT hashes. The adapter passes the
            // CANONICAL INPUT hash (load-bearing for replay / E020 — the idempotency + audit
            // input_hash), the handler's OUTPUT hash (the audit output_hash AND the idempotency
            // outcome_hash — the result identity replayed to the caller), and the DOCUMENT hash (the
            // draft document_hash). The durable outcome_hash is now the OUTPUT hash, distinct from the
            // input_hash — byte-faithful to the file store.
            String draftId = commandResult.resultRef();
            String document = deriveDocument(invocation);
            String outputHash = auditedResult.outputHash();
            String documentHash = sha256(document);
            callImportModelDocument(
                    connection,
                    draftId,
                    invocation.actor().scope(),
                    deriveModelId(draftId),
                    1,
                    invocation.request().idempotencyKey(),
                    document,
                    inputHash,
                    outputHash,
                    documentHash,
                    attemptAt.toString(),
                    outcomeAt.toString(),
                    attempt.sequence() + 1L,
                    "audit-" + String.format("%06d", attempt.sequence() + 1L),
                    invocation.request().requestId(),
                    outcomeAt.toString());
            IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                    invocation.descriptor().commandName(),
                    invocation.actor().scope(),
                    invocation.request().idempotencyKey(),
                    inputHash,
                    OutcomeStatus.SUCCESS,
                    outputHash,
                    draftId,
                    null,
                    null,
                    outcomeAt);
            AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L,
                    AuditStatus.SUCCESS, outputHash, null, null, outcomeAt);
            return new TransactionalCommandExecution(
                    validation, attempt, outcome,
                    AuditedCommandResult.success(outputHash), idempotencyRecord, false, false);
        }

        // Failure outcome the success-only routine does not model: write the idempotency + outcome
        // audit rows directly (exactly as the file store does), no routine CALL.
        IdempotencyRecord idempotencyRecord = idempotencyRecord(invocation, inputHash, commandResult, outcomeAt);
        ManagementJdbc.insertIdempotency(connection, idempotencyRecord);
        AuditRecord outcome = auditRecord(invocation, validation, attempt.sequence() + 1L,
                AuditStatus.FAILURE, auditedResult.outputHash(), auditedResult.errorCode(),
                auditedResult.errorMessage(), outcomeAt);
        ManagementJdbc.insertAudit(connection, outcome);
        return new TransactionalCommandExecution(
                validation, attempt, outcome, auditedResult, idempotencyRecord, false, false);
    }

    // ------------------------------------------------------------------
    // Seeds — CALL the seed routines.
    // ------------------------------------------------------------------

    @Override
    public void seedDraft(Draft draft) {
        Objects.requireNonNull(draft, "draft");
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CALL " + ManagementJdbc.SCHEMA + ".seed_draft(?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, draft.id());
                statement.setString(2, draft.workspaceId());
                statement.setString(3, draft.modelId());
                statement.setInt(4, (int) draft.version());
                statement.setString(5, draft.status().id());
                statement.setString(6, "{}");
                statement.setString(7, draft.documentHash());
                statement.setString(8, draft.createdAt().toString());
                statement.setString(9, draft.updatedAt().toString());
                statement.execute();
            }
        });
    }

    @Override
    public void seedArtifactRef(ArtifactRef artifactRef) {
        Objects.requireNonNull(artifactRef, "artifact ref");
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CALL " + ManagementJdbc.SCHEMA + ".seed_artifact_ref(?,?,?,?,?,?,?,?)")) {
                statement.setString(1, artifactRef.id());
                statement.setString(2, artifactRef.artifactId());
                statement.setString(3, artifactRef.artifactHash());
                statement.setString(4, artifactRef.manifestPath());
                statement.setString(5, artifactRef.objectInventoryPath());
                statement.setString(6, artifactRef.installPlanPath());
                statement.setString(7, artifactRef.installVerificationPath());
                statement.setString(8, artifactRef.verificationStatus().id());
                statement.execute();
            }
            // The seed routine carries only the base artifact columns; the GAP-005 evidence columns
            // (package_mode, dialect, *_hash) are part of the durable contract the activation
            // precondition reads, so write them with a follow-on UPDATE in the same transaction.
            if (artifactRef.packageMode() != null) {
                updateArtifactEvidence(connection, artifactRef);
            }
        });
    }

    private void updateArtifactEvidence(Connection connection, ArtifactRef artifactRef) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + ManagementJdbc.SCHEMA + ".management_artifact_refs SET "
                        + "package_mode = ?, dialect = ?, manifest_content_hash = ?, object_inventory_hash = ?, "
                        + "install_plan_hash = ?, install_verification_hash = ?, source_inputs_hash = ? WHERE id = ?")) {
            statement.setString(1, artifactRef.packageMode());
            statement.setString(2, artifactRef.dialect());
            statement.setString(3, artifactRef.manifestContentHash());
            statement.setString(4, artifactRef.objectInventoryHash());
            statement.setString(5, artifactRef.installPlanHash());
            statement.setString(6, artifactRef.installVerificationHash());
            statement.setString(7, artifactRef.sourceInputsHash());
            statement.setString(8, artifactRef.id());
            statement.executeUpdate();
        }
    }

    @Override
    public void seedDeployment(Deployment deployment) {
        Objects.requireNonNull(deployment, "deployment");
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CALL " + ManagementJdbc.SCHEMA + ".seed_deployment(?,?,?,?,?,?)")) {
                statement.setString(1, deployment.id());
                statement.setString(2, deployment.workspaceId());
                statement.setString(3, deployment.artifactRefId());
                statement.setString(4, deployment.environment());
                statement.setString(5, deployment.status().id());
                statement.setString(6, deployment.requestedAt().toString());
                statement.execute();
            }
            if (deployment.activatedAt() != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + ManagementJdbc.SCHEMA + ".management_deployments SET activated_at = ? WHERE id = ?")) {
                    statement.setString(1, deployment.activatedAt().toString());
                    statement.setString(2, deployment.id());
                    statement.executeUpdate();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // activateDeployment — pure pass-through: CALL the function, map its int status code.
    // ------------------------------------------------------------------

    @Override
    public DeploymentActivationExecution activateDeployment(
            DeploymentActivationRequest request,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        Objects.requireNonNull(request, "deployment activation request");
        Objects.requireNonNull(attemptAt, "deployment activation attempt time");
        Objects.requireNonNull(outcomeAt, "deployment activation outcome time");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                DeploymentActivationExecution execution = activateInTransaction(
                        connection, request, attemptAt, outcomeAt);
                connection.commit();
                return execution;
            } catch (RuntimeException | SQLException exception) {
                safeRollback(connection);
                throw asRuntime("TITAN-GAP006-TRANSACTION: activateDeployment failed", exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: activateDeployment failed", exception);
        }
    }

    private DeploymentActivationExecution activateInTransaction(
            Connection connection,
            DeploymentActivationRequest request,
            Instant attemptAt,
            Instant outcomeAt
    ) throws SQLException {
        String inputHash = activationInputHash(request);
        long attemptSequence = nextAuditSequence(connection);
        AuditRecord attempt = deploymentAuditRecord(request, attemptSequence, AuditStatus.ATTEMPT,
                inputHash, null, null, null, attemptAt);
        ManagementJdbc.insertAudit(connection, attempt);

        Optional<IdempotencyRecord> existing = JdbcIdempotencyStore.find(
                connection,
                ACTIVATE_DEPLOYMENT_COMMAND,
                request.workspaceId(),
                request.idempotencyKey(),
                true);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            boolean conflict = !record.inputHash().equals(inputHash);
            AuditedCommandResult replay = conflict
                    ? AuditedCommandResult.failure("TITAN-MGMT-E020", "idempotency input mismatch")
                    : replay(record);
            AuditRecord outcome = deploymentAuditRecord(request, attempt.sequence() + 1L,
                    replay.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                    inputHash, replay.outputHash(), replay.errorCode(), replay.errorMessage(), outcomeAt);
            ManagementJdbc.insertAudit(connection, outcome);
            Deployment replayedDeployment = replay.success() && record.resultRef() != null
                    ? readDeployment(connection, record.resultRef()).orElse(null)
                    : null;
            return new DeploymentActivationExecution(
                    attempt, outcome, replayedDeployment, record, !conflict, conflict);
        }

        // RD-1 (CLOSED, 7-for-7 server-side): the activate_deployment @StoredFunction performs the
        // FULL typed precondition read-decide-RETURN chain server-side for ALL SEVEN preconditions
        // (E030/E031/E032/E033/E034/E035/E036) and returns an int status code. The adapter is a PURE
        // PASS-THROUGH: it does NOT re-implement any check — it maps the returned code back to the
        // TITAN-MGMT-E03x domain error via DeploymentActivationStatus. E032 (the GAP-005 metadata-PATH
        // suffix match) moved server-side as the final residual: the routine expresses it faithfully
        // via Column.like('%titan-*.json') (a constant suffix with no LIKE metacharacters is exactly
        // endsWith), so it now sits between E031 and E036 in the routine's chain — preserving the file
        // store's first-failure ordering with zero adapter logic.

        // CALL the function (read-decide-RETURN server-side). On any E03x code it returns WITHOUT
        // mutating; only code 0 (all preconditions passed) performs the supersede + activate.
        int statusCode = callActivateDeployment(connection, request, outcomeAt.toString());
        AuditedCommandResult routineFailure = DeploymentActivationStatus.toOutcome(statusCode);
        if (routineFailure != null) {
            return writeActivationFailure(connection, request, attempt, inputHash, outcomeAt, routineFailure);
        }

        Deployment activeDeployment = readDeployment(connection, request.deploymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "TITAN-GAP006-TRANSACTION: activated deployment row missing after activate"));
        List<Deployment> supersededDeployments = readSupersededSiblings(
                connection, request.workspaceId(), request.environment(), request.deploymentId());
        String outputHash = sha256(activeDeployment.stableJson()
                + "\n"
                + String.join("\n", supersededDeployments.stream()
                        .map(Deployment::stableJson)
                        .sorted()
                        .toList()));
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                ACTIVATE_DEPLOYMENT_COMMAND,
                request.workspaceId(),
                request.idempotencyKey(),
                inputHash,
                OutcomeStatus.SUCCESS,
                outputHash,
                activeDeployment.id(),
                null,
                null,
                outcomeAt);
        ManagementJdbc.insertIdempotency(connection, idempotencyRecord);
        AuditRecord outcome = deploymentAuditRecord(request, attempt.sequence() + 1L, AuditStatus.SUCCESS,
                inputHash, outputHash, null, null, outcomeAt);
        ManagementJdbc.insertAudit(connection, outcome);
        return new DeploymentActivationExecution(attempt, outcome, activeDeployment, idempotencyRecord, false, false);
    }

    private DeploymentActivationExecution writeActivationFailure(
            Connection connection,
            DeploymentActivationRequest request,
            AuditRecord attempt,
            String inputHash,
            Instant outcomeAt,
            AuditedCommandResult failure) throws SQLException {
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                ACTIVATE_DEPLOYMENT_COMMAND,
                request.workspaceId(),
                request.idempotencyKey(),
                inputHash,
                OutcomeStatus.FAILURE,
                hashFailure(failure),
                null,
                failure.errorCode(),
                failure.errorMessage(),
                outcomeAt);
        ManagementJdbc.insertIdempotency(connection, idempotencyRecord);
        AuditRecord outcome = deploymentAuditRecord(request, attempt.sequence() + 1L, AuditStatus.FAILURE,
                inputHash, null, failure.errorCode(), failure.errorMessage(), outcomeAt);
        ManagementJdbc.insertAudit(connection, outcome);
        return new DeploymentActivationExecution(attempt, outcome, null, idempotencyRecord, false, false);
    }

    private List<Deployment> readSupersededSiblings(
            Connection connection, String workspaceId, String environment, String activatedId) throws SQLException {
        List<Deployment> superseded = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, workspace_id, artifact_ref_id, environment, status, requested_at, activated_at "
                        + "FROM " + ManagementJdbc.SCHEMA + ".management_deployments "
                        + "WHERE workspace_id = ? AND environment = ? AND status = 'superseded' AND id <> ?")) {
            statement.setString(1, workspaceId);
            statement.setString(2, environment);
            statement.setString(3, activatedId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    superseded.add(mapDeployment(rs));
                }
            }
        }
        return superseded;
    }

    // ------------------------------------------------------------------
    // Reads — plain parameterized SELECTs reproducing the file store ordering/filtering.
    // ------------------------------------------------------------------

    @Override
    public Optional<Draft> draft(String draftId) {
        Objects.requireNonNull(draftId, "draft id");
        return readOnly(connection -> readDraft(connection, draftId));
    }

    @Override
    public List<Draft> drafts() {
        return readOnly(this::readDrafts);
    }

    @Override
    public List<Draft> draftsForWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id");
        return readOnly(connection -> readDrafts(connection).stream()
                .filter(draft -> draft.workspaceId().equals(workspaceId))
                .toList());
    }

    @Override
    public List<Draft> draftsForModel(String workspaceId, String modelId) {
        Objects.requireNonNull(workspaceId, "workspace id");
        Objects.requireNonNull(modelId, "model id");
        return readOnly(connection -> readDrafts(connection).stream()
                .filter(draft -> draft.workspaceId().equals(workspaceId))
                .filter(draft -> draft.modelId().equals(modelId))
                .toList());
    }

    @Override
    public Optional<ArtifactRef> artifactRef(String artifactRefId) {
        Objects.requireNonNull(artifactRefId, "artifact ref id");
        return readOnly(connection -> readArtifactRef(connection, artifactRefId));
    }

    @Override
    public List<ArtifactRef> artifactRefs() {
        return readOnly(this::readArtifactRefs);
    }

    @Override
    public Optional<Deployment> deployment(String deploymentId) {
        Objects.requireNonNull(deploymentId, "deployment id");
        return readOnly(connection -> readDeployment(connection, deploymentId));
    }

    @Override
    public List<Deployment> deployments() {
        return readOnly(this::readDeployments);
    }

    @Override
    public List<Deployment> deploymentsForWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id");
        return readOnly(connection -> readDeployments(connection).stream()
                .filter(deployment -> deployment.workspaceId().equals(workspaceId))
                .toList());
    }

    @Override
    public List<AuditRecord> auditRecords() {
        return readOnly(JdbcAuditStore::readAll);
    }

    @Override
    public List<AuditRecord> auditRecordsForRequest(String requestId) {
        Objects.requireNonNull(requestId, "request id");
        return readOnly(connection -> JdbcAuditStore.readForRequest(connection, requestId));
    }

    @Override
    public List<IdempotencyRecord> idempotencyRecords() {
        return readOnly(JdbcIdempotencyStore::readAll);
    }

    // ------------------------------------------------------------------
    // Read row-mappers + ordering (mirror FileTransactionalMutationStore.State).
    // ------------------------------------------------------------------

    private Optional<Draft> readDraft(Connection connection, String draftId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                draftSelect() + " WHERE id = ?")) {
            statement.setString(1, draftId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapDraft(rs)) : Optional.empty();
            }
        }
    }

    private List<Draft> readDrafts(Connection connection) throws SQLException {
        List<Draft> drafts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(draftSelect());
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                drafts.add(mapDraft(rs));
            }
        }
        drafts.sort(Comparator.comparing(Draft::workspaceId)
                .thenComparing(Draft::modelId)
                .thenComparingLong(Draft::version)
                .thenComparing(Draft::id));
        return drafts;
    }

    private static String draftSelect() {
        return "SELECT id, workspace_id, model_id, version, status, document_hash, created_at, updated_at, metadata "
                + "FROM " + ManagementJdbc.SCHEMA + ".management_drafts";
    }

    private static Draft mapDraft(ResultSet rs) throws SQLException {
        return new Draft(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("model_id"),
                rs.getInt("version"),
                DraftStatus.valueOf(draftStatusName(rs.getString("status"))),
                rs.getString("document_hash"),
                Instant.parse(Objects.requireNonNull(rs.getString("created_at"))),
                Instant.parse(Objects.requireNonNull(rs.getString("updated_at"))),
                parseMetadata(rs.getString("metadata")));
    }

    private static String draftStatusName(String id) {
        for (DraftStatus status : DraftStatus.values()) {
            if (status.id().equals(id)) {
                return status.name();
            }
        }
        throw new IllegalStateException("TITAN-GAP006-DOMAIN: unknown draft status id: " + id);
    }

    private Optional<ArtifactRef> readArtifactRef(Connection connection, String artifactRefId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(artifactSelect() + " WHERE id = ?")) {
            statement.setString(1, artifactRefId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapArtifactRef(rs)) : Optional.empty();
            }
        }
    }

    private List<ArtifactRef> readArtifactRefs(Connection connection) throws SQLException {
        List<ArtifactRef> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(artifactSelect());
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                refs.add(mapArtifactRef(rs));
            }
        }
        refs.sort(Comparator.comparing(ArtifactRef::id));
        return refs;
    }

    private static String artifactSelect() {
        return "SELECT id, artifact_id, artifact_hash, manifest_path, object_inventory_path, install_plan_path, "
                + "install_verification_path, verification_status, package_mode, dialect, manifest_content_hash, "
                + "object_inventory_hash, install_plan_hash, install_verification_hash, source_inputs_hash "
                + "FROM " + ManagementJdbc.SCHEMA + ".management_artifact_refs";
    }

    private static ArtifactRef mapArtifactRef(ResultSet rs) throws SQLException {
        return new ArtifactRef(
                rs.getString("id"),
                rs.getString("artifact_id"),
                rs.getString("artifact_hash"),
                rs.getString("manifest_path"),
                rs.getString("object_inventory_path"),
                rs.getString("install_plan_path"),
                rs.getString("install_verification_path"),
                VerificationStatus.valueOf(verificationStatusName(rs.getString("verification_status"))),
                rs.getString("package_mode"),
                rs.getString("dialect"),
                rs.getString("manifest_content_hash"),
                rs.getString("object_inventory_hash"),
                rs.getString("install_plan_hash"),
                rs.getString("install_verification_hash"),
                rs.getString("source_inputs_hash"));
    }

    private static String verificationStatusName(String id) {
        for (VerificationStatus status : VerificationStatus.values()) {
            if (status.id().equals(id)) {
                return status.name();
            }
        }
        throw new IllegalStateException("TITAN-GAP006-DOMAIN: unknown verification status id: " + id);
    }

    private Optional<Deployment> readDeployment(Connection connection, String deploymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(deploymentSelect() + " WHERE id = ?")) {
            statement.setString(1, deploymentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapDeployment(rs)) : Optional.empty();
            }
        }
    }

    private List<Deployment> readDeployments(Connection connection) throws SQLException {
        List<Deployment> deployments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(deploymentSelect());
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                deployments.add(mapDeployment(rs));
            }
        }
        deployments.sort(Comparator.comparing(Deployment::workspaceId)
                .thenComparing(Deployment::environment)
                .thenComparing(Deployment::requestedAt)
                .thenComparing(Deployment::id));
        return deployments;
    }

    private static String deploymentSelect() {
        return "SELECT id, workspace_id, artifact_ref_id, environment, status, requested_at, activated_at "
                + "FROM " + ManagementJdbc.SCHEMA + ".management_deployments";
    }

    private static Deployment mapDeployment(ResultSet rs) throws SQLException {
        String activatedAt = rs.getString("activated_at");
        return new Deployment(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("artifact_ref_id"),
                rs.getString("environment"),
                DeploymentStatus.valueOf(deploymentStatusName(rs.getString("status"))),
                Instant.parse(Objects.requireNonNull(rs.getString("requested_at"))),
                activatedAt == null ? null : Instant.parse(activatedAt));
    }

    private static String deploymentStatusName(String id) {
        for (DeploymentStatus status : DeploymentStatus.values()) {
            if (status.id().equals(id)) {
                return status.name();
            }
        }
        throw new IllegalStateException("TITAN-GAP006-DOMAIN: unknown deployment status id: " + id);
    }

    // ------------------------------------------------------------------
    // Routine CALL helpers.
    // ------------------------------------------------------------------

    private static void callImportModelDocument(
            Connection connection, String draftId, String workspaceId, String modelId, int version,
            String idempotencyKey, String document, String inputHash, String outputHash, String documentHash,
            String createdAt, String updatedAt, long auditSequence, String auditId, String requestId,
            String occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + ManagementJdbc.SCHEMA + ".import_model_document(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, draftId);
            statement.setString(2, workspaceId);
            statement.setString(3, modelId);
            statement.setInt(4, version);
            statement.setString(5, idempotencyKey);
            statement.setString(6, document);
            statement.setString(7, inputHash);
            statement.setString(8, outputHash);
            statement.setString(9, documentHash);
            statement.setString(10, createdAt);
            statement.setString(11, updatedAt);
            statement.setInt(12, (int) auditSequence);
            statement.setString(13, auditId);
            statement.setString(14, requestId);
            statement.setString(15, occurredAt);
            statement.execute();
        }
    }

    /**
     * Invokes the {@code activate_deployment} {@code @StoredFunction} via {@code SELECT fn(...)} (the
     * portable value-returning invocation on both PG + MySQL) and returns its int status code (0 =
     * success after supersede + activate; an E03x code for the first failed precondition). The full
     * read-decide-RETURN runs server-side under the row lock the function takes up front.
     */
    private static int callActivateDeployment(
            Connection connection, DeploymentActivationRequest request, String activatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ManagementJdbc.SCHEMA + ".activate_deployment(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, request.deploymentId());
            statement.setString(2, request.workspaceId());
            statement.setString(3, request.artifactRefId());
            statement.setString(4, request.environment());
            statement.setString(5, activatedAt);
            statement.setString(6, request.actorRole());
            statement.setString(7, request.expectedArtifactHash());
            statement.setString(8, request.expectedPackageMode());
            statement.setString(9, request.expectedDialect());
            statement.setString(10, request.expectedManifestContentHash());
            statement.setString(11, request.expectedObjectInventoryHash());
            statement.setString(12, request.expectedInstallPlanHash());
            statement.setString(13, request.expectedInstallVerificationHash());
            statement.setString(14, request.expectedSourceInputsHash());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "TITAN-GAP006-TRANSACTION: activate_deployment returned no status row");
                }
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Transaction/connection plumbing.
    // ------------------------------------------------------------------

    private interface ConnectionWork {
        void run(Connection connection) throws SQLException;
    }

    private interface ConnectionRead<T> {
        T read(Connection connection) throws SQLException;
    }

    private void inTransaction(ConnectionWork work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                safeRollback(connection);
                throw asRuntime("TITAN-GAP006-TRANSACTION: mutation failed", exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: mutation failed", exception);
        }
    }

    private <T> T readOnly(ConnectionRead<T> read) {
        try (Connection connection = dataSource.getConnection()) {
            return read.read(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-TRANSACTION: read failed", exception);
        }
    }

    private static long nextAuditSequence(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + ManagementJdbc.SCHEMA + ".management_audit_outcomes");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1) + 1L;
        }
    }

    // ------------------------------------------------------------------
    // Audit/idempotency record construction (mirrors ManagementTransactions).
    // ------------------------------------------------------------------

    private static AuditRecord auditRecord(
            CommandInvocation invocation, CommandValidation validation, long sequence, AuditStatus status,
            String outputHash, String errorCode, String errorMessage, Instant occurredAt) {
        ActorContext actor = invocation.actor();
        RequestContext request = invocation.request();
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
            DeploymentActivationRequest request, long sequence, AuditStatus status, String inputHash,
            String outputHash, String errorCode, String errorMessage, Instant occurredAt) {
        return new AuditRecord(
                "audit-" + String.format("%06d", sequence),
                sequence,
                ACTIVATE_DEPLOYMENT_COMMAND,
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

    private static IdempotencyRecord idempotencyRecord(
            CommandInvocation invocation, String inputHash, TransactionalCommandResult result, Instant createdAt) {
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

    // ------------------------------------------------------------------
    // Derivations for the routine inputs the import descriptor does not carry verbatim.
    // ------------------------------------------------------------------

    private static String deriveModelId(String draftId) {
        // The import descriptor carries workspaceId/sourceFormat/sourceText, not a modelId (an extra
        // input field would be rejected as unsupported, TITAN-MGMT-E007). The dogfooded store derives
        // a stable modelId from the draft id so the durable draft row satisfies the Draft record's
        // stable-id contract.
        return "model-" + draftId;
    }

    private static String deriveDocument(CommandInvocation invocation) {
        // The draft document is a JSON column; the import descriptor's sourceText is the model body.
        // Persist it wrapped as a JSON object so the ::jsonb / CAST(... AS JSON) cast in the routine
        // accepts it regardless of the raw source format.
        String sourceText = invocation.input().getOrDefault("sourceText", "");
        return "{\"sourceText\":" + jsonString(sourceText) + "}";
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static Map<String, String> parseMetadata(String json) {
        // The routines write metadata as '{}' (the seed/import routines do not carry a metadata
        // param), so the only shape persisted through the dogfooded store is the empty object.
        if (json == null || json.isBlank() || json.trim().equals("{}")) {
            return Map.of();
        }
        // Defensive: a non-empty object is not produced by the routines; treat it as empty rather
        // than risk a brittle hand-rolled JSON parse. (Recorded: the routines have no metadata input.)
        return Map.of();
    }

    private static String activationInputHash(DeploymentActivationRequest request) {
        return sha256(String.join(
                "\n",
                ACTIVATE_DEPLOYMENT_COMMAND,
                request.workspaceId(),
                request.deploymentId(),
                request.artifactRefId(),
                request.environment(),
                request.expectedArtifactHash(),
                request.expectedPackageMode(),
                request.expectedDialect(),
                request.expectedManifestContentHash(),
                request.expectedObjectInventoryHash(),
                request.expectedInstallPlanHash(),
                request.expectedInstallVerificationHash(),
                request.expectedSourceInputsHash()));
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

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort rollback; the original failure is what propagates
        }
    }

    private static RuntimeException asRuntime(String message, Exception exception) {
        if (exception instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message, exception);
    }
}
