package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutor;
import org.junit.jupiter.api.Test;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.Table;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 5.5 (audit R-6): {@link JdbcExecutor#inTransaction} commit/rollback semantics on live
 * PostgreSQL and MySQL — work commits atomically on success, rolls back completely on failure,
 * and the pinned connection comes back in usable auto-commit mode either way.
 */
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JdbcExecutorTransactionIT {

    @Test
    void commitsAllWorkWhenTheFunctionReturns(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createLedgerTable(context.connection(target));
            LedgerTable ledger = new LedgerTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc();

            Integer inserted = jdbc.inTransaction(tx -> {
                int first = tx.execute(DSL.insertInto(ledger).set(ledger.ID, 1).set(ledger.AMOUNT, 100));
                int second = tx.execute(DSL.insertInto(ledger).set(ledger.ID, 2).set(ledger.AMOUNT, -40));
                return first + second;
            });

            assertEquals(2, inserted, "target " + target);
            assertEquals(2, countRows(jdbc), "target " + target);
            assertEquals(60, sumAmounts(jdbc), "target " + target);
        }
    }

    @Test
    void rollsBackEverythingWhenTheFunctionThrows(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createLedgerTable(context.connection(target));
            LedgerTable ledger = new LedgerTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc();

            IllegalStateException boom = new IllegalStateException("business rule violated");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> jdbc.inTransaction(tx -> {
                        tx.execute(DSL.insertInto(ledger).set(ledger.ID, 1).set(ledger.AMOUNT, 100));
                        tx.execute(DSL.insertInto(ledger).set(ledger.ID, 2).set(ledger.AMOUNT, -40));
                        throw boom;
                    }));

            assertSame(boom, thrown, "target " + target);
            assertEquals(0, countRows(jdbc),
                    "rolled-back work must leave no rows behind on " + target);

            // The pinned connection must be usable (and back in auto-commit) after rollback.
            assertEquals(1, jdbc.execute(DSL.insertInto(ledger).set(ledger.ID, 3).set(ledger.AMOUNT, 7)));
            assertEquals(1, countRows(jdbc), "target " + target);
        }
    }

    @Test
    void failedStatementInsideTransactionRollsBackPriorStatements(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            createLedgerTable(context.connection(target));
            LedgerTable ledger = new LedgerTable();
            JdbcExecutor jdbc = context.javaMode(target).jdbc();

            assertThrows(RuntimeException.class, () -> jdbc.inTransaction(tx -> {
                tx.execute(DSL.insertInto(ledger).set(ledger.ID, 1).set(ledger.AMOUNT, 100));
                // duplicate primary key -> SQLException -> JdbcExecutionException
                tx.execute(DSL.insertInto(ledger).set(ledger.ID, 1).set(ledger.AMOUNT, 200));
                return null;
            }));

            assertEquals(0, countRows(jdbc),
                    "the successful first insert must roll back with the failed transaction on " + target);
        }
    }

    private static int countRows(JdbcExecutor jdbc) {
        return jdbc.fetchSql("SELECT COUNT(*) FROM ledger", rs -> rs.getInt(1)).get(0);
    }

    private static int sumAmounts(JdbcExecutor jdbc) {
        return jdbc.fetchSql("SELECT COALESCE(SUM(amount), 0) FROM ledger", rs -> rs.getInt(1)).get(0);
    }

    private static void createLedgerTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ledger(id INT PRIMARY KEY, amount INT NOT NULL)");
        }
    }

    private static final class LedgerTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<Integer> AMOUNT = column("amount", SQLType.INTEGER, Nullability.NOT_NULL);

        private LedgerTable() {
            super("ledger", "");
        }
    }
}
