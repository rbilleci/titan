package io.titan.management;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Shared JDBC plumbing for the management stores (B4): the {@code management} schema name the
 * transpiled routines and the bundled DDL agree on, the enum {@code id()} <-> enum mappings that
 * round-trip status columns (the tables store the stable {@code id()} string — {@code 'success'},
 * {@code 'attempt'}, … — the records serialize, NOT {@code name()}), and the row-writers for the
 * audit and idempotency tables.
 *
 * <p>These writers are plain parameterized INSERTs (not routines): the audit/idempotency rows the
 * routines do NOT own — the attempt audit row, validation-failure outcomes, the deployment
 * activation rows — are written directly so the JDBC stores reproduce the file store's record set.
 * The {@code importModelDocument} happy path's success-outcome audit + idempotency rows ARE written
 * by the transpiled routine; see {@link JdbcTransactionalMutationStore}.
 */
final class ManagementJdbc {

    /** The schema/database the bundled DDL creates its tables in and the routines qualify against. */
    static final String SCHEMA = "management";

    private ManagementJdbc() {
    }

    static String auditStatusId(AuditStatus status) {
        return status.id();
    }

    static AuditStatus auditStatusFromId(String id) {
        for (AuditStatus status : AuditStatus.values()) {
            if (status.id().equals(id)) {
                return status;
            }
        }
        throw new IllegalStateException("TITAN-GAP006-AUDIT: unknown audit status id: " + id);
    }

    static OutcomeStatus outcomeStatusFromId(String id) {
        for (OutcomeStatus status : OutcomeStatus.values()) {
            if (status.id().equals(id)) {
                return status;
            }
        }
        throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: unknown outcome status id: " + id);
    }

    /** Writes one audit row, mapping enum status to its stored {@code id()} string. */
    static void insertAudit(Connection connection, AuditRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + SCHEMA + ".management_audit_outcomes "
                        + "(id, sequence, command_name, status, actor_id, actor_role, actor_scope, request_id, "
                        + "idempotency_key, input_hash, output_hash, error_code, error_message, occurred_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, record.id());
            statement.setLong(2, record.sequence());
            statement.setString(3, record.commandName());
            statement.setString(4, auditStatusId(record.status()));
            setNullableString(statement, 5, record.actorId());
            setNullableString(statement, 6, record.actorRole());
            setNullableString(statement, 7, record.actorScope());
            setNullableString(statement, 8, record.requestId());
            setNullableString(statement, 9, record.idempotencyKey());
            statement.setString(10, record.inputHash());
            setNullableString(statement, 11, record.outputHash());
            setNullableString(statement, 12, record.errorCode());
            setNullableString(statement, 13, record.errorMessage());
            statement.setString(14, record.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    /** Writes one idempotency row, mapping enum outcome status to its stored {@code id()} string. */
    static void insertIdempotency(Connection connection, IdempotencyRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + SCHEMA + ".management_idempotency "
                        + "(command_name, scope, idempotency_key, input_hash, outcome_status, outcome_hash, "
                        + "result_ref, error_code, error_message, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, record.commandName());
            statement.setString(2, record.scope());
            statement.setString(3, record.idempotencyKey());
            statement.setString(4, record.inputHash());
            statement.setString(5, record.outcomeStatus().id());
            statement.setString(6, record.outcomeHash());
            setNullableString(statement, 7, record.resultRef());
            setNullableString(statement, 8, record.errorCode());
            setNullableString(statement, 9, record.errorMessage());
            statement.setString(10, record.createdAt().toString());
            statement.executeUpdate();
        }
    }

    static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
