package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Runtime-strategy SQL correctness tests for the plan 2.4 arithmetic emulation helpers
 * (audit E-7/E-11), evaluated on live PostgreSQL 16 and MySQL 8.4 against Java references:
 *
 * <ul>
 *   <li><b>32-bit wraparound</b> {@code java_int_add/sub/mul} on both dialects (the MySQL trio
 *       was missing entirely; the PostgreSQL trio existed but had never been executed by any
 *       test), including {@code Integer.MAX_VALUE + 1}, {@code Integer.MIN_VALUE} extremes and
 *       negative operands — the double-mod pattern depends on {@code %} taking the dividend's
 *       sign, which this IT proves on both engines.</li>
 *   <li><b>Integer division/modulo</b>: the new {@code titan_rt_java_int_div} and the
 *       zero-guarded {@code titan_rt_java_mod} on MySQL (truncation toward zero, dividend-sign
 *       remainder, SQLSTATE 22012 on zero) and the native-operator parity evidence on
 *       PostgreSQL that justifies leaving {@code titan_runtime.java_mod} unwired there.</li>
 * </ul>
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class RuntimeArithmeticEmulationIT {

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    private static final int[][] WRAP_CASES = {
            {Integer.MAX_VALUE, 1},
            {Integer.MIN_VALUE, -1},
            {Integer.MIN_VALUE, Integer.MIN_VALUE},
            {Integer.MAX_VALUE, Integer.MAX_VALUE},
            {Integer.MAX_VALUE, 2},
            {-46341, 46341},
            {-1, -1},
            {123456789, 987654321},
            {0, 0},
    };

    private static final int[][] DIV_MOD_CASES = {
            {7, 2},
            {-7, 2},
            {7, -2},
            {-7, -2},
            {-7, 3},
            {7, -3},
            {-7, -3},
            {0, 5},
            {Integer.MAX_VALUE, -1},
            {1, Integer.MIN_VALUE},
    };

    @Test
    void mysqlRuntimeHelpersMatchJavaSemantics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            deployMySqlRuntime(connection);

            for (int[] operands : WRAP_CASES) {
                int a = operands[0];
                int b = operands[1];
                assertEquals(a + b, evalInt(connection, "SELECT titan_rt_java_int_add(?, ?)", a, b),
                        "mysql java_int_add(" + a + ", " + b + ")");
                assertEquals(a - b, evalInt(connection, "SELECT titan_rt_java_int_sub(?, ?)", a, b),
                        "mysql java_int_sub(" + a + ", " + b + ")");
                assertEquals(a * b, evalInt(connection, "SELECT titan_rt_java_int_mul(?, ?)", a, b),
                        "mysql java_int_mul(" + a + ", " + b + ")");
            }

            for (int[] operands : DIV_MOD_CASES) {
                int a = operands[0];
                int b = operands[1];
                assertEquals(a / b, evalInt(connection, "SELECT titan_rt_java_int_div(?, ?)", a, b),
                        "mysql java_int_div(" + a + ", " + b + ")");
                assertEquals(a % b, evalInt(connection, "SELECT titan_rt_java_mod(?, ?)", a, b),
                        "mysql java_mod(" + a + ", " + b + ")");
            }

            // Division/modulo by zero must raise SQLSTATE 22012 like Java's ArithmeticException
            // and PostgreSQL's division_by_zero — never MySQL's silent NULL.
            assertRaisesDivisionByZero(connection, "SELECT titan_rt_java_int_div(?, ?)", 7, 0, "mysql java_int_div by zero");
            assertRaisesDivisionByZero(connection, "SELECT titan_rt_java_mod(?, ?)", 7, 0, "mysql java_mod by zero");
        }
    }

    @Test
    void postgresRuntimeHelpersAndNativeOperatorsMatchJavaSemantics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            deployPostgresRuntime(connection);

            for (int[] operands : WRAP_CASES) {
                int a = operands[0];
                int b = operands[1];
                assertEquals(a + b, evalInt(connection, "SELECT titan_runtime.java_int_add(?, ?)", a, b),
                        "pg java_int_add(" + a + ", " + b + ")");
                assertEquals(a - b, evalInt(connection, "SELECT titan_runtime.java_int_sub(?, ?)", a, b),
                        "pg java_int_sub(" + a + ", " + b + ")");
                assertEquals(a * b, evalInt(connection, "SELECT titan_runtime.java_int_mul(?, ?)", a, b),
                        "pg java_int_mul(" + a + ", " + b + ")");
            }

            // Native-operator parity evidence (why the INT_DIV/INT_MOD markers emit plain '/'
            // and '%' on PostgreSQL, and why titan_runtime.java_mod stays unwired): PostgreSQL
            // integer division truncates toward zero and '%' takes the dividend's sign, exactly
            // like Java, for every sign combination.
            for (int[] operands : DIV_MOD_CASES) {
                int a = operands[0];
                int b = operands[1];
                assertEquals(a / b, evalInt(connection, "SELECT CAST(? AS INTEGER) / CAST(? AS INTEGER)", a, b),
                        "pg native division (" + a + " / " + b + ")");
                assertEquals(a % b, evalInt(connection, "SELECT CAST(? AS INTEGER) % CAST(? AS INTEGER)", a, b),
                        "pg native modulo (" + a + " % " + b + ")");
                assertEquals(a % b, evalInt(connection, "SELECT titan_runtime.java_mod(?, ?)", a, b),
                        "pg java_mod(" + a + ", " + b + ") identical to native %");
            }

            assertRaisesDivisionByZero(connection, "SELECT CAST(? AS INTEGER) / CAST(? AS INTEGER)", 7, 0, "pg division by zero");
            assertRaisesDivisionByZero(connection, "SELECT CAST(? AS INTEGER) % CAST(? AS INTEGER)", 7, 0, "pg modulo by zero");
        }
    }

    private static void deployPostgresRuntime(Connection connection) throws SQLException {
        executeScript(connection, new PostgreSqlDialectProvider().runtimeStrategy().runtimeMigrationSql());
    }

    private static void deployMySqlRuntime(Connection connection) throws SQLException {
        // The container's test user cannot create the titan_runtime database; rehome the
        // telemetry table into the current database exactly like TitanTestExtension does. The
        // helper functions are unqualified and land in the current database, which is where
        // generated routines resolve their unqualified references.
        executeScript(connection, new MySqlDialectProvider().runtimeStrategy().runtimeMigrationSql()
                .replace("titan_runtime.telemetry", "telemetry"));
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String sql : SqlScripts.split(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException exception) {
                throw new SQLException("Failed runtime migration statement:\n" + sql, exception);
            }
        }
    }

    private static int evalInt(Connection connection, String sql, int a, int b) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, a);
            statement.setInt(2, b);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), sql + " returned no rows");
                int value = resultSet.getInt(1);
                assertTrue(!resultSet.wasNull(), sql + " returned NULL for (" + a + ", " + b + ")");
                return value;
            }
        }
    }

    /**
     * Both engines raise SQLSTATE 22012 (division_by_zero) server-side. PostgreSQL's driver
     * surfaces it verbatim; MySQL Connector/J converts any class-22 condition into
     * {@link java.sql.DataTruncation}, whose {@code getSQLState()} is hardcoded to 22001 by the
     * JDBC spec — so assert the data-exception class (22xxx) plus the division-by-zero message.
     */
    private static void assertRaisesDivisionByZero(Connection connection, String sql, int a, int b, String description) {
        try {
            evalInt(connection, sql, a, b);
        } catch (SQLException exception) {
            assertTrue(exception.getSQLState() != null && exception.getSQLState().startsWith("22"),
                    description + " raised wrong SQLSTATE: " + exception.getSQLState()
                            + " (" + exception.getMessage() + ")");
            assertTrue(exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("division by zero"),
                    description + " raised wrong message: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected " + description + " to raise a SQLSTATE 22xxx division-by-zero error");
    }
}
