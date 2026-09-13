package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutor;
import org.junit.jupiter.api.Test;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.SqlDialect;
import titan.dsl.Table;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3.2 (audit R-1/R-2/D-1): DSL statements execute with bound parameters on both targets;
 * hostile values (quotes, backslashes) round-trip as data, never as SQL.
 */
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JdbcExecutorBindingIT {

    private static final String HOSTILE_VALUE = "O'Brien \\ payload\\' OR '1'='1";

    @Test
    void hostileStringsRoundTripViaBoundParametersOnBothTargets(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createNotesTable(context.connection(target));
            NotesTable notes = new NotesTable();
            JavaModeRunner runner = context.javaMode(target);

            int inserted = runner.run(jdbc -> jdbc.execute(DSL.insertInto(notes)
                    .set(notes.ID, 1)
                    .set(notes.BODY, HOSTILE_VALUE)));
            assertEquals(1, inserted, "target " + target);

            List<String> bodies = runner.run(jdbc -> jdbc.fetch(
                    DSL.select(notes.BODY).from(notes).where(notes.BODY.eq(HOSTILE_VALUE)),
                    rs -> rs.getString(1)));
            assertEquals(List.of(HOSTILE_VALUE), bodies, "target " + target);

            int updated = runner.run(jdbc -> jdbc.execute(DSL.update(notes)
                    .set(notes.BODY, HOSTILE_VALUE + " v2")
                    .where(notes.BODY.eq(HOSTILE_VALUE))));
            assertEquals(1, updated, "target " + target);

            int deleted = runner.run(jdbc -> jdbc.execute(DSL.deleteFrom(notes)
                    .where(notes.BODY.eq(HOSTILE_VALUE + " v2"))));
            assertEquals(1, deleted, "target " + target);
        }
    }

    @Test
    void literalModeRendersExecutableDialectCorrectSql(TitanTestContext context) throws Exception {
        // Regression for audit D-1/D-2: the literal rendering mode (used by the transpiler)
        // must produce SQL that each engine parses with the hostile value intact, and typed
        // temporal literals instead of bare arithmetic.
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createNotesTable(context.connection(target));
            NotesTable notes = new NotesTable();
            SqlDialect dialect = target == DatabaseTarget.MYSQL ? SqlDialect.MYSQL : SqlDialect.POSTGRESQL;
            JavaModeRunner runner = context.javaMode(target);

            String insertSql = DSL.insertInto(notes)
                    .set(notes.ID, 2)
                    .set(notes.BODY, HOSTILE_VALUE)
                    .set(notes.NOTED_ON, LocalDate.of(2024, 6, 9))
                    .toSql(dialect);
            int inserted = runner.run(jdbc -> jdbc.executeSql(insertSql));
            assertEquals(1, inserted, "target " + target);

            String selectSql = DSL.select(notes.BODY)
                    .from(notes)
                    .where(notes.NOTED_ON.eq(LocalDate.of(2024, 6, 9)))
                    .toSql(dialect);
            List<String> bodies = runner.run(jdbc -> jdbc.fetchSql(selectSql, rs -> rs.getString(1)));
            assertEquals(List.of(HOSTILE_VALUE), bodies, "target " + target);
        }
    }

    @Test
    void mysqlInsertReturningUsesGeneratedKeys(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.MYSQL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tickets");
            statement.execute("CREATE TABLE tickets("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT, label VARCHAR(255) NOT NULL)");
        }

        TicketsTable tickets = new TicketsTable();
        JavaModeRunner runner = context.javaMode(DatabaseTarget.MYSQL);
        JdbcExecutor jdbc = runner.jdbc();
        assertEquals(SqlDialect.MYSQL, jdbc.dialect());

        List<Long> firstKey = jdbc.executeReturning(
                DSL.insertInto(tickets).set(tickets.LABEL, "first").returning(tickets.ID),
                rs -> rs.getLong(1));
        List<Long> secondKey = jdbc.executeReturning(
                DSL.insertInto(tickets).set(tickets.LABEL, "second").returning(tickets.ID),
                rs -> rs.getLong(1));

        assertEquals(1, firstKey.size());
        assertEquals(1, secondKey.size());
        assertTrue(secondKey.get(0) > firstKey.get(0),
                "generated keys must be monotonically increasing: " + firstKey + " then " + secondKey);
    }

    @Test
    void postgresInsertReturningStillUsesReturningClause(TitanTestContext context) throws Exception {
        Connection connection = context.connection(DatabaseTarget.POSTGRESQL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tickets");
            statement.execute("CREATE TABLE tickets("
                    + "id BIGSERIAL PRIMARY KEY, label VARCHAR(255) NOT NULL)");
        }

        TicketsTable tickets = new TicketsTable();
        JdbcExecutor jdbc = context.javaMode(DatabaseTarget.POSTGRESQL).jdbc();
        assertEquals(SqlDialect.POSTGRESQL, jdbc.dialect());

        List<Long> key = jdbc.executeReturning(
                DSL.insertInto(tickets).set(tickets.LABEL, "first").returning(tickets.ID),
                rs -> rs.getLong(1));

        assertEquals(1, key.size());
        assertTrue(key.get(0) >= 1L);
    }

    private void createNotesTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS notes");
            statement.execute("CREATE TABLE notes("
                    + "id INT PRIMARY KEY, body VARCHAR(255) NOT NULL, noted_on DATE NULL)");
        }
    }

    private static final class NotesTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> BODY = column("body", SQLType.VARCHAR, Nullability.NOT_NULL);
        private final Column<LocalDate> NOTED_ON = column("noted_on", SQLType.DATE, Nullability.NULLABLE);

        private NotesTable() {
            super("notes", "");
        }
    }

    private static final class TicketsTable extends Table<Object> {
        private final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
        private final Column<String> LABEL = column("label", SQLType.VARCHAR, Nullability.NOT_NULL);

        private TicketsTable() {
            super("tickets", "");
        }
    }
}
