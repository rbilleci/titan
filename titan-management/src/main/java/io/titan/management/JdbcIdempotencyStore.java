package io.titan.management;

import io.titan.management.ManagementAudit.AuditedCommandResult;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementIdempotency.IdempotencyDecision;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.IdempotencyStore;
import io.titan.management.ManagementIdempotency.IdempotentCommandHandler;
import io.titan.management.ManagementIdempotency.IdempotentCommandResult;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link IdempotencyStore} over {@code management.management_idempotency} (B4).
 *
 * <p>Durable sibling of {@link ManagementIdempotency.FileIdempotencyStore}. The composite primary
 * key {@code (command_name, scope, idempotency_key)} is the natural idempotency key, so
 * {@link #find} is a parameterized lookup and {@link #append} relies on the PK for
 * already-exists detection (a duplicate insert raises a SQL integrity violation that surfaces as
 * the same {@code TITAN-GAP006-IDEMPOTENCY} error the file store throws).
 *
 * <p>{@link #executeOnce} reproduces the file store's read-decide-branch verbatim, but inside a
 * single SERVER transaction with the idempotency row taken under the engine's lock:
 * find-existing under {@code FOR UPDATE} → on hit, replay (or {@code TITAN-MGMT-E020} on input
 * mismatch); on miss, run the handler, persist the resulting record, commit. The row lock is what
 * lets two racing callers serialize on the same key — the proof the single-process file store
 * cannot show.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Field DECISION_RECORD = field("record");
    private static final Field DECISION_REPLAYED = field("replayed");
    private static final Field DECISION_CONFLICT = field("conflict");

    private final DataSource dataSource;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public Optional<IdempotencyRecord> find(String commandName, String scope, String idempotencyKey) {
        Objects.requireNonNull(commandName, "idempotency command name");
        Objects.requireNonNull(scope, "idempotency scope");
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, commandName, scope, idempotencyKey, false);
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: failed to read idempotency records", exception);
        }
    }

    @Override
    public IdempotencyRecord append(IdempotencyRecord record) {
        Objects.requireNonNull(record, "idempotency record");
        try (Connection connection = dataSource.getConnection()) {
            if (find(connection, record.commandName(), record.scope(), record.idempotencyKey(), false).isPresent()) {
                throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: idempotency record already exists");
            }
            ManagementJdbc.insertIdempotency(connection, record);
            return record;
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: failed to append idempotency record", exception);
        }
    }

    @Override
    public List<IdempotencyRecord> readAll() {
        try (Connection connection = dataSource.getConnection()) {
            return readAll(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: failed to read idempotency records", exception);
        }
    }

    @Override
    public AuditedCommandResult executeOnce(
            CommandInvocation invocation,
            IdempotentCommandHandler handler,
            Instant outcomeAt,
            IdempotencyDecision decision
    ) {
        String commandName = invocation.descriptor().commandName();
        String scope = invocation.actor().scope();
        String key = invocation.request().idempotencyKey();
        String inputHash = invocation.canonicalInputHash();
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Take the idempotency row under the engine's row lock so two racing callers serialize.
                Optional<IdempotencyRecord> existing = find(connection, commandName, scope, key, true);
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    setDecisionRecord(decision, record);
                    if (!record.inputHash().equals(inputHash)) {
                        setDecisionConflict(decision);
                        connection.commit();
                        return AuditedCommandResult.failure("TITAN-MGMT-E020", "idempotency input mismatch");
                    }
                    setDecisionReplayed(decision);
                    connection.commit();
                    return replay(record);
                }

                IdempotentCommandResult result;
                try {
                    result = Objects.requireNonNull(handler.execute(invocation), "idempotent command result");
                } catch (RuntimeException exception) {
                    result = IdempotentCommandResult.failure(
                            "TITAN-MGMT-E011",
                            "command handler failed: " + exception.getClass().getSimpleName());
                }
                IdempotencyRecord record = recordFromResult(invocation, inputHash, result, outcomeAt);
                ManagementJdbc.insertIdempotency(connection, record);
                setDecisionRecord(decision, record);
                connection.commit();
                return result.auditedResult();
            } catch (RuntimeException | SQLException exception) {
                safeRollback(connection);
                throw exception instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: executeOnce failed", exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: executeOnce failed", exception);
        }
    }

    static Optional<IdempotencyRecord> find(
            Connection connection,
            String commandName,
            String scope,
            String idempotencyKey,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT command_name, scope, idempotency_key, input_hash, outcome_status, outcome_hash, "
                + "result_ref, error_code, error_message, created_at "
                + "FROM " + ManagementJdbc.SCHEMA + ".management_idempotency "
                + "WHERE command_name = ? AND scope = ? AND idempotency_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandName);
            statement.setString(2, scope);
            statement.setString(3, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapIdempotency(rs));
                }
                return Optional.empty();
            }
        }
    }

    static List<IdempotencyRecord> readAll(Connection connection) throws SQLException {
        List<IdempotencyRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT command_name, scope, idempotency_key, input_hash, outcome_status, outcome_hash, "
                        + "result_ref, error_code, error_message, created_at "
                        + "FROM " + ManagementJdbc.SCHEMA + ".management_idempotency "
                        + "ORDER BY command_name, scope, idempotency_key");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                records.add(mapIdempotency(rs));
            }
        }
        return records;
    }

    private static IdempotencyRecord mapIdempotency(ResultSet rs) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("command_name"),
                rs.getString("scope"),
                rs.getString("idempotency_key"),
                rs.getString("input_hash"),
                ManagementJdbc.outcomeStatusFromId(rs.getString("outcome_status")),
                rs.getString("outcome_hash"),
                rs.getString("result_ref"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                Instant.parse(Objects.requireNonNull(rs.getString("created_at"))));
    }

    private static AuditedCommandResult replay(IdempotencyRecord record) {
        if (record.outcomeStatus() == OutcomeStatus.SUCCESS) {
            return AuditedCommandResult.success(record.outcomeHash());
        }
        return AuditedCommandResult.failure(record.errorCode(), record.errorMessage());
    }

    private static IdempotencyRecord recordFromResult(
            CommandInvocation invocation,
            String inputHash,
            IdempotentCommandResult result,
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

    // ManagementIdempotency.IdempotencyDecision is a package-visible carrier whose mutable fields
    // are private with no setters (the file store, in the same package, assigns them directly). The
    // JDBC store lives in the same package but the fields are still private to the nested class, so
    // it sets them via reflection — keeping the IdempotencyStore contract (which hands the caller's
    // decision back through executeOnce) intact without touching ManagementIdempotency.
    private static Field field(String name) {
        try {
            Field f = IdempotencyDecision.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: IdempotencyDecision field missing: " + name,
                    exception);
        }
    }

    private static void setDecisionRecord(IdempotencyDecision decision, IdempotencyRecord record) {
        try {
            DECISION_RECORD.set(decision, record);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: cannot set decision record", exception);
        }
    }

    private static void setDecisionReplayed(IdempotencyDecision decision) {
        try {
            DECISION_REPLAYED.setBoolean(decision, true);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: cannot set decision replayed", exception);
        }
    }

    private static void setDecisionConflict(IdempotencyDecision decision) {
        try {
            DECISION_CONFLICT.setBoolean(decision, true);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: cannot set decision conflict", exception);
        }
    }
}
