package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual README example source, including its failure/rollback paths. */
@Tag("docker")
class ReadmeBusinessExamplesIT {
    private static final Path EXAMPLE = Path.of("../examples/quickstart");

    @TempDir
    Path generatedDir;

    @Test
    void businessExamplesRunOnPostgres() throws Exception {
        verify("postgresql", TestContainers.freshPostgresDatabase("readme_business"));
    }

    @Test
    void businessExamplesRunOnMysql() throws Exception {
        verify("mysql", TestContainers.freshMysqlDatabase("app"));
    }

    private void verify(String dialect, TestContainers.SharedDatabase database) throws Exception {
        var generated = ReadmeBusinessExampleSupport.transpile(dialect, generatedDir);
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password())) {
            try (Statement statement = connection.createStatement()) {
                if (dialect.equals("postgresql")) {
                    statement.execute("CREATE SCHEMA app");
                    statement.execute("SET search_path TO app, public");
                } else {
                    statement.execute("CREATE DATABASE IF NOT EXISTS titan_runtime");
                }
                String runtime = dialect.equals("postgresql")
                        ? new PostgreSqlDialectProvider().runtimeStrategy().runtimeMigrationSql()
                        : new MySqlDialectProvider().runtimeStrategy().runtimeMigrationSql();
                for (String sql : SqlScripts.split(runtime)) {
                    statement.execute(sql);
                }
                for (String sql : SqlScripts.split(Files.readString(
                        EXAMPLE.resolve("src/main/resources/db/schema/inventory.sql")))) {
                    statement.execute(sql);
                }
                for (var artifact : generated) {
                    for (String sql : SqlScripts.split(artifact.sql())) {
                        statement.execute(sql);
                    }
                }
                statement.execute("INSERT INTO app.inventory (sku, available) VALUES (101, 10)");
            }

            // The caller owns the transaction in both native Java and generated SQL use.
            connection.setAutoCommit(false);
            for (String routine : List.of("reserve_stock", "reserve_stock_jdbc")) {
                callReserve(connection, routine, 7001, 101, 4);
                connection.commit();
                assertEquals(6, scalar(connection, "SELECT available FROM app.inventory WHERE sku = 101"));
                assertEquals(4, scalar(connection, "SELECT quantity FROM app.reservations WHERE order_id = 7001"));
                connection.commit();

                rejectedReservation(connection, routine, 7002, 101, 0, "quantity must be positive");
                rejectedReservation(connection, routine, 7002, 101, -1, "quantity must be positive");
                rejectedReservation(connection, routine, 7002, 999, 1, "unknown SKU");
                rejectedReservation(connection, routine, 7002, 101, 7, "insufficient stock");

                // The inventory update precedes the duplicate-key failure. Caller rollback must
                // restore it too; a failed CALL is not a universal automatic transaction rollback.
                assertThrows(SQLException.class, () -> callReserve(connection, routine, 7001, 101, 2));
                connection.rollback();
                assertEquals(6, scalar(connection, "SELECT available FROM app.inventory WHERE sku = 101"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM app.reservations"));
                connection.commit();

                callReserve(connection, routine, 7002, 101, 6);
                connection.commit();
                assertEquals(0, scalar(connection, "SELECT available FROM app.inventory WHERE sku = 101"));
                assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM app.reservations"));
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DELETE FROM app.reservations");
                    statement.execute("UPDATE app.inventory SET available = 10 WHERE sku = 101");
                }
                connection.commit();
            }

            int[][] pricingCases = {
                {0, 0, 0}, {1, 200, 200}, {40, 200, 200}, {100, 500, 450},
                {101, 503, 452}, {1000, 3200, 2880}, {1200, 3600, 3240},
                {1000000, 2001200, 1801080}
            };
            for (int[] sample : pricingCases) {
                assertEquals(sample[1], charge(connection, sample[0], false));
                assertEquals(sample[2], charge(connection, sample[0], true));
            }
            connection.commit();
            for (int invalid : List.of(-1, 1000001)) {
                SQLException error = assertThrows(SQLException.class, () -> charge(connection, invalid, false));
                assertTrue(error.getMessage().contains("units must be between 0 and 1000000"));
                connection.rollback();
            }
        }
    }

    private static void rejectedReservation(Connection connection, String routine, long order, long sku,
            int quantity, String message) throws SQLException {
        SQLException error = assertThrows(SQLException.class, () -> callReserve(connection, routine, order, sku, quantity));
        assertTrue(error.getMessage().contains(message), error.getMessage());
        connection.rollback();
        assertEquals(6, scalar(connection, "SELECT available FROM app.inventory WHERE sku = 101"));
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM app.reservations"));
        connection.commit();
    }

    private static void callReserve(Connection connection, String routine, long order, long sku, int quantity)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL app." + routine + "(?, ?, ?)")) {
            statement.setLong(1, order);
            statement.setLong(2, sku);
            statement.setInt(3, quantity);
            statement.execute();
        }
    }

    private static int charge(Connection connection, int units, boolean partner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT app.monthly_charge_cents(?, ?)")) {
            statement.setInt(1, units);
            statement.setBoolean(2, partner);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
