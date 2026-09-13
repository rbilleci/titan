package io.titan.management;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link AuditStore} over {@code management.management_audit_outcomes} (B4).
 *
 * <p>It is the durable sibling of {@link ManagementAudit.FileAuditStore}: {@link #append} writes one
 * audit row in its own short transaction (autocommit restored after), and {@link #readAll} returns
 * every audit row ordered by {@code sequence} — the same total order the file store enforces. The
 * append-only contiguity invariant the file store checks on read is enforced here by the
 * {@code management_audit_outcomes} primary key on {@code id} plus the caller stamping the sequence;
 * a duplicate id/sequence surfaces as a SQL integrity error rather than a silent overwrite.
 *
 * <p>The schema (and the {@code management} schema/database it lives in) is assumed to exist — see
 * {@link ManagementSchemaInstaller} for the bootstrap/test deploy path; production bootstrap is
 * out-of-band.
 */
public final class JdbcAuditStore implements AuditStore {

    private final DataSource dataSource;

    public JdbcAuditStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public AuditRecord append(AuditRecord record) {
        Objects.requireNonNull(record, "audit record");
        try (Connection connection = dataSource.getConnection()) {
            ManagementJdbc.insertAudit(connection, record);
            return record;
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-AUDIT: failed to append audit record", exception);
        }
    }

    @Override
    public List<AuditRecord> readAll() {
        try (Connection connection = dataSource.getConnection()) {
            return readAll(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("TITAN-GAP006-AUDIT: failed to read audit records", exception);
        }
    }

    static List<AuditRecord> readAll(Connection connection) throws SQLException {
        List<AuditRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, sequence, command_name, status, actor_id, actor_role, actor_scope, request_id, "
                        + "idempotency_key, input_hash, output_hash, error_code, error_message, occurred_at "
                        + "FROM " + ManagementJdbc.SCHEMA + ".management_audit_outcomes ORDER BY sequence");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                records.add(mapAudit(rs));
            }
        }
        return records;
    }

    static List<AuditRecord> readForRequest(Connection connection, String requestId) throws SQLException {
        List<AuditRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, sequence, command_name, status, actor_id, actor_role, actor_scope, request_id, "
                        + "idempotency_key, input_hash, output_hash, error_code, error_message, occurred_at "
                        + "FROM " + ManagementJdbc.SCHEMA + ".management_audit_outcomes "
                        + "WHERE request_id = ? ORDER BY sequence")) {
            statement.setString(1, requestId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(mapAudit(rs));
                }
            }
        }
        return records;
    }

    private static AuditRecord mapAudit(ResultSet rs) throws SQLException {
        return new AuditRecord(
                rs.getString("id"),
                rs.getLong("sequence"),
                rs.getString("command_name"),
                ManagementJdbc.auditStatusFromId(rs.getString("status")),
                rs.getString("actor_id"),
                rs.getString("actor_role"),
                rs.getString("actor_scope"),
                rs.getString("request_id"),
                rs.getString("idempotency_key"),
                rs.getString("input_hash"),
                rs.getString("output_hash"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                Instant.parse(Objects.requireNonNull(rs.getString("occurred_at"))));
    }
}
