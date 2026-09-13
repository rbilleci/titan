package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutionException;
import io.titan.runtime.jdbc.JdbcExecutor;
import io.titan.runtime.jdbc.JdbcTelemetrySink;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.Table;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5.5 (audit R-6/R-7): the {@link JdbcTelemetrySink} listener writes execution events
 * into the already-provisioned {@code titan_runtime.telemetry} table on both dialects —
 * placeholder SQL only, never bound values.
 */
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JdbcTelemetrySinkIT {

    private static final String SENSITIVE_VALUE = "topsecret-salary-867530";

    @Test
    void executionEventsLandInTitanRuntimeTelemetry(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            createSalariesTable(connection);

            SalariesTable salaries = new SalariesTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc()
                    .withListener(new JdbcTelemetrySink(new SingleConnectionDataSource(connection)));

            jdbc.execute(DSL.insertInto(salaries).set(salaries.ID, 1).set(salaries.NOTE, SENSITIVE_VALUE));
            jdbc.fetch(DSL.select(salaries.ID).from(salaries).where(salaries.NOTE.eq(SENSITIVE_VALUE)),
                    rs -> rs.getInt(1));
            assertThrows(JdbcExecutionException.class,
                    () -> jdbc.executeSql("DELETE FROM table_that_does_not_exist"));

            List<TelemetryRow> rows = jdbc.fetchSql(
                    "SELECT procedure_name, status, duration_ms, started_at, finished_at"
                            + " FROM titan_runtime.telemetry ORDER BY id",
                    rs -> new TelemetryRow(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getObject(3) == null ? null : rs.getDouble(3),
                            rs.getTimestamp(4),
                            rs.getTimestamp(5)));

            // insert + select succeed, the bad DELETE fails, and the final SELECT above adds
            // one more success row after the snapshot we read - so assert on the first three.
            assertTrue(rows.size() >= 3, "expected at least 3 telemetry rows on " + target + ", got " + rows);

            TelemetryRow insertRow = rows.get(0);
            assertTrue(insertRow.sql().startsWith("INSERT INTO salaries"), insertRow.sql());
            assertTrue(insertRow.sql().contains("?"), "telemetry must carry placeholder SQL: " + insertRow.sql());
            assertFalse(insertRow.sql().contains(SENSITIVE_VALUE),
                    "bound values must never reach telemetry (audit R-7): " + insertRow.sql());
            assertEquals("success", insertRow.status());

            TelemetryRow selectRow = rows.get(1);
            assertTrue(selectRow.sql().startsWith("SELECT"), selectRow.sql());
            assertTrue(selectRow.sql().contains("?"), selectRow.sql());
            assertFalse(selectRow.sql().contains(SENSITIVE_VALUE), selectRow.sql());
            assertEquals("success", selectRow.status());

            TelemetryRow failureRow = rows.get(2);
            assertTrue(failureRow.sql().contains("table_that_does_not_exist"), failureRow.sql());
            assertEquals("error", failureRow.status());

            for (TelemetryRow row : rows.subList(0, 3)) {
                assertTrue(row.durationMs() != null && row.durationMs() >= 0.0d,
                        "duration_ms must be recorded on " + target + ": " + row);
                assertTrue(!row.finishedAt().before(row.startedAt()),
                        "finished_at must not precede started_at on " + target + ": " + row);
            }
        }
    }

    @Test
    void telemetryTableStartsEmptyForEveryTest(TitanTestContext context) {
        // Guards the harness contract this IT depends on: per-test PostgreSQL databases and the
        // per-test truncation of MySQL's server-global titan_runtime.telemetry (audit R-3).
        for (DatabaseTarget target : DatabaseTarget.values()) {
            int count = context.javaMode(target).jdbc()
                    .fetchSql("SELECT COUNT(*) FROM titan_runtime.telemetry", rs -> rs.getInt(1)).get(0);
            assertEquals(0, count, "telemetry must start empty on " + target);
        }
    }

    private record TelemetryRow(String sql, String status, Double durationMs,
                                java.sql.Timestamp startedAt, java.sql.Timestamp finishedAt) {
    }

    private static void createSalariesTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE salaries(id INT PRIMARY KEY, note VARCHAR(128) NOT NULL)");
        }
    }

    private static final class SalariesTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> NOTE = column("note", SQLType.VARCHAR, Nullability.NOT_NULL);

        private SalariesTable() {
            super("salaries", "");
        }
    }
}
