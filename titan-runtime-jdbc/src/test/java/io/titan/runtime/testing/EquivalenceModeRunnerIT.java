package io.titan.runtime.testing;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class EquivalenceModeRunnerIT {

    @Test
    void comparesJavaAndSqlModesAndReturnsSharedResult(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            recreateAccountsTable(connection);
            createInsertProcedure(connection, target);

            TestAccountsTable accounts = new TestAccountsTable();
            context.javaMode(target).run(jdbc -> jdbc.execute(DSL.insertInto(accounts)
                    .set(accounts.ID, 101)
                    .set(accounts.EMAIL, "eve@example.com")));
            context.sqlMode(target).call("insert_account", 102, "mallory@example.com");

            int count = context.equivalenceMode(target).compareScalar(
                    jdbc -> jdbc.fetchSql("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1)).get(0),
                    sql -> sql.fetchSql("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1)).get(0),
                    "accounts row count");

            assertEquals(2, count);
        }
    }

    @Test
    void failsWithDiffWhenModesDiverge(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            recreateAccountsTable(connection);

            TestAccountsTable accounts = new TestAccountsTable();
            context.javaMode(target).run(jdbc -> jdbc.execute(DSL.insertInto(accounts)
                    .set(accounts.ID, 77)
                    .set(accounts.EMAIL, "diff@example.com")));

            AssertionError error = assertThrows(AssertionError.class, () ->
                    context.equivalenceMode(target).compareScalar(
                            jdbc -> jdbc.fetchSql("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1)).get(0),
                            sql -> 0,
                            "intentional divergence"));

            String message = error.getMessage();
            assertTrue(message.contains("java-mode: 1"));
            assertTrue(message.contains("sql-mode: 0"));
        }
    }

    @Test
    void rowSetsCompareUnorderedByDefaultAndOrderedOnRequest(TitanTestContext context) throws Exception {
        // Audit R-5 regression, end-to-end: accidental row order must not alarm by default,
        // and Integer-vs-Long type skew between the two modes must not alarm at all.
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            recreateAccountsTable(connection);

            TestAccountsTable accounts = new TestAccountsTable();
            context.javaMode(target).run(jdbc -> jdbc.execute(DSL.insertInto(accounts)
                    .set(accounts.ID, 1).set(accounts.EMAIL, "a@example.com")));
            context.javaMode(target).run(jdbc -> jdbc.execute(DSL.insertInto(accounts)
                    .set(accounts.ID, 2).set(accounts.EMAIL, "b@example.com")));

            EquivalenceModeRunner runner = context.equivalenceMode(target);

            // Opposite ORDER BY directions: equal as multisets, divergent pairwise.
            List<List<Object>> rows = runner.compareRows(
                    jdbc -> jdbc.fetchSql("SELECT id, email FROM accounts ORDER BY id ASC",
                            rs -> List.of(rs.getInt(1), rs.getString(2))),
                    sql -> sql.fetchSql("SELECT id, email FROM accounts ORDER BY id DESC",
                            rs -> List.of(rs.getInt(1), rs.getString(2))),
                    "unordered row comparison");
            assertEquals(2, rows.size());

            AssertionError orderedFailure = assertThrows(AssertionError.class, () -> runner.compareRows(
                    jdbc -> jdbc.fetchSql("SELECT id, email FROM accounts ORDER BY id ASC",
                            rs -> List.of(rs.getInt(1), rs.getString(2))),
                    sql -> sql.fetchSql("SELECT id, email FROM accounts ORDER BY id DESC",
                            rs -> List.of(rs.getInt(1), rs.getString(2))),
                    "ordered row comparison",
                    ComparisonMode.ORDERED));
            assertTrue(orderedFailure.getMessage().contains("row 0"), orderedFailure.getMessage());

            // Integer (getInt) vs Long (getLong) for the same COUNT: normalized equal.
            runner.compareScalar(
                    jdbc -> jdbc.fetchSql("SELECT COUNT(*) FROM accounts", rs -> (Object) rs.getInt(1)).get(0),
                    sql -> sql.fetchSql("SELECT COUNT(*) FROM accounts", rs -> (Object) rs.getLong(1)).get(0),
                    "integral widening");
        }
    }

    private static void recreateAccountsTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS accounts");
            statement.execute("CREATE TABLE accounts(id INT PRIMARY KEY, email VARCHAR(255) NOT NULL)");
        }
    }

    private static void createInsertProcedure(Connection connection, DatabaseTarget target) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if (target == DatabaseTarget.POSTGRESQL) {
                statement.execute("DROP PROCEDURE IF EXISTS insert_account(INT, TEXT)");
                statement.execute("""
                        CREATE PROCEDURE insert_account(p_id INT, p_email TEXT)
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            INSERT INTO accounts(id, email) VALUES (p_id, p_email);
                        END;
                        $$
                        """);
            } else {
                statement.execute("DROP PROCEDURE IF EXISTS insert_account");
                statement.execute("""
                        CREATE PROCEDURE insert_account(IN p_id INT, IN p_email VARCHAR(255))
                        BEGIN
                            INSERT INTO accounts(id, email) VALUES (p_id, p_email);
                        END
                        """);
            }
        }
    }

    private static final class TestAccountsTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

        private TestAccountsTable() {
            super("accounts", "");
        }
    }
}
