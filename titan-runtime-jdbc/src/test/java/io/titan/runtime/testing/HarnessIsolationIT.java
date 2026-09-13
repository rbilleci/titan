package io.titan.runtime.testing;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 5.4 (audit R-3): per-test isolation must be commit-proof. The old harness rolled back a
 * shared per-class connection — but MySQL DDL commits implicitly and explicit commits stuck, so
 * state leaked between tests. These ordered tests prove that committed DML <em>and</em> DDL from
 * one test are invisible to the next on both dialects, and that the injected connections are
 * close-proof.
 */
@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class HarnessIsolationIT {

    @Test
    @Order(1)
    void leaveCommittedDdlAndDmlBehind(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            try (Statement statement = connection.createStatement()) {
                // DDL: implicitly committed on MySQL no matter what the harness does.
                statement.execute("CREATE TABLE leak_probe(id INT PRIMARY KEY)");
                // DML on an auto-commit connection: committed immediately on both dialects.
                statement.execute("INSERT INTO leak_probe(id) VALUES (1)");
            }
            assertEquals(1, countRows(connection, "leak_probe"), "target " + target);
        }
    }

    @Test
    @Order(2)
    void committedStateFromThePreviousTestIsGone(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            assertFalse(tableExists(connection, target, "leak_probe"),
                    "committed DDL from the previous test leaked into this test on " + target);
            // The harness-provisioned probe table is present and empty in the fresh database.
            assertEquals(0, countRows(connection, "titan_test_probe"), "target " + target);
        }
    }

    @Test
    @Order(3)
    void closingTheInjectedConnectionIsANoOp(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            connection.close(); // non-closing proxy: must not kill the harness connection
            assertFalse(connection.isClosed(), "injected connection must be close-proof on " + target);
            assertEquals(0, countRows(connection, "titan_test_probe"), "target " + target);
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, DatabaseTarget target, String table) throws Exception {
        String sql = target == DatabaseTarget.POSTGRESQL
                ? "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = '" + table + "'"
                : "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = '" + table + "'";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1) > 0;
        }
    }
}
