package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutor;
import org.junit.jupiter.api.Test;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.InsertBuilder;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.Table;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5.5 (audit R-6): batch execution and streaming fetch on live PostgreSQL and MySQL.
 */
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JdbcExecutorBatchAndStreamingIT {

    private static final int BATCH_ROWS = 250;
    private static final int STREAM_ROWS = 5_000;

    @Test
    void executeBatchInsertsEveryRowOnBothTargets(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createEventsTable(context.connection(target));
            EventsTable events = new EventsTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc();

            List<InsertBuilder> inserts = new ArrayList<>(BATCH_ROWS);
            for (int i = 1; i <= BATCH_ROWS; i++) {
                inserts.add(DSL.insertInto(events).set(events.ID, i).set(events.LABEL, "event-" + i));
            }
            int[] counts = jdbc.executeBatch(inserts);

            assertEquals(BATCH_ROWS, counts.length, "target " + target);
            assertEquals(BATCH_ROWS,
                    jdbc.fetchSql("SELECT COUNT(*) FROM events", rs -> rs.getInt(1)).get(0),
                    "target " + target);
            assertEquals("event-137",
                    jdbc.fetchSql("SELECT label FROM events WHERE id = 137", rs -> rs.getString(1)).get(0),
                    "target " + target);
        }
    }

    @Test
    void streamingFetchDeliversLargeRowSetsInOrderWithBoundedFetchSize(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createEventsTable(context.connection(target));
            EventsTable events = new EventsTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc();

            List<InsertBuilder> inserts = new ArrayList<>(STREAM_ROWS);
            for (int i = 1; i <= STREAM_ROWS; i++) {
                inserts.add(DSL.insertInto(events).set(events.ID, i).set(events.LABEL, "row-" + i));
            }
            jdbc.executeBatch(inserts);

            List<Integer> seen = new ArrayList<>(STREAM_ROWS);
            jdbc.fetchStreaming(
                    DSL.select(events.ID).from(events).orderBy(events.ID.asc()),
                    100,
                    rs -> rs.getInt(1),
                    seen::add);

            assertEquals(STREAM_ROWS, seen.size(), "target " + target);
            assertEquals(1, seen.get(0), "target " + target);
            assertEquals(STREAM_ROWS, seen.get(seen.size() - 1), "target " + target);
            for (int i = 1; i < seen.size(); i++) {
                assertTrue(seen.get(i - 1) < seen.get(i),
                        "streamed rows must arrive in ORDER BY order on " + target);
            }

            // The connection must be fully usable after the streaming lifecycle completes
            // (PostgreSQL: the wrapping cursor transaction committed and auto-commit restored).
            assertEquals(STREAM_ROWS,
                    jdbc.fetchSql("SELECT COUNT(*) FROM events", rs -> rs.getInt(1)).get(0),
                    "target " + target);
        }
    }

    @Test
    void mysqlRowStreamingModeWithMinValueFetchSize(TitanTestContext context) throws Exception {
        createEventsTable(context.connection(DatabaseTarget.MYSQL));
        EventsTable events = new EventsTable();
        JdbcExecutor jdbc = context.javaMode(DatabaseTarget.MYSQL).jdbc();

        List<InsertBuilder> inserts = new ArrayList<>();
        for (int i = 1; i <= 1_000; i++) {
            inserts.add(DSL.insertInto(events).set(events.ID, i).set(events.LABEL, "row-" + i));
        }
        jdbc.executeBatch(inserts);

        // Connector/J only streams row-by-row with fetchSize == Integer.MIN_VALUE (without
        // useCursorFetch=true on the URL); the executor passes the documented hint through.
        List<Integer> seen = new ArrayList<>();
        jdbc.fetchStreaming(
                DSL.select(events.ID).from(events).orderBy(events.ID.asc()),
                Integer.MIN_VALUE,
                rs -> rs.getInt(1),
                seen::add);

        assertEquals(1_000, seen.size());
        assertEquals(1, seen.get(0));
        assertEquals(1_000, seen.get(seen.size() - 1));
    }

    private static void createEventsTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE events(id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)");
        }
    }

    private static final class EventsTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> LABEL = column("label", SQLType.VARCHAR, Nullability.NOT_NULL);

        private EventsTable() {
            super("events", "");
        }
    }
}
