package io.titan.runtime.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link TitanExecutionListener} that records executions into the {@code titan_runtime.telemetry}
 * table provisioned by the Titan runtime migrations (audit R-6: the table existed, nothing wrote
 * to it from the client side).
 *
 * <p>Each event becomes one row: {@code procedure_name} holds the SQL <em>with placeholders</em>
 * (never bound values, audit R-7), {@code started_at}/{@code finished_at}/{@code duration_ms}
 * carry the timing, and {@code status} is {@code 'success'} or {@code 'error'} — the same values
 * the transpiled routines write from inside the database.</p>
 *
 * <p>The sink writes through its own {@link DataSource} so callers decide whether telemetry
 * shares the executing connection (transactional with the work — rolled back together) or uses a
 * separate pool (survives rollbacks). Sink write failures surface as
 * {@link JdbcExecutionException}; per the listener contract they propagate when the observed
 * statement succeeded and ride along as suppressed exceptions when it failed.</p>
 */
public final class JdbcTelemetrySink implements TitanExecutionListener {

    /** {@code schema.table} or bare table; nothing else, so the INSERT text cannot be injected. */
    private static final Pattern TABLE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    public static final String DEFAULT_TABLE = "titan_runtime.telemetry";

    private final DataSource dataSource;
    private final String insertSql;

    public JdbcTelemetrySink(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcTelemetrySink(DataSource dataSource, String telemetryTable) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(telemetryTable, "telemetryTable");
        if (!TABLE_NAME.matcher(telemetryTable).matches()) {
            throw new IllegalArgumentException("Invalid telemetry table name: " + telemetryTable);
        }
        this.insertSql = "INSERT INTO " + telemetryTable
                + " (procedure_name, started_at, finished_at, duration_ms, status) VALUES (?, ?, ?, ?, ?)";
    }

    @Override
    public void onExecute(String sqlWithPlaceholders, Duration duration, Outcome outcome) {
        Instant finishedAt = Instant.now();
        Instant startedAt = finishedAt.minus(duration);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, sqlWithPlaceholders);
            statement.setTimestamp(2, Timestamp.from(startedAt));
            statement.setTimestamp(3, Timestamp.from(finishedAt));
            statement.setDouble(4, duration.toNanos() / 1_000_000.0d);
            statement.setString(5, outcome == Outcome.SUCCESS ? "success" : "error");
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to record execution telemetry", ex, insertSql);
        }
    }
}
