package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase 1.3b exit criterion (universal identifier quoting): a schema whose table is named
 * {@code select} and whose columns are named {@code order}, {@code group} and {@code key} —
 * all SQL reserved words on PostgreSQL and/or MySQL — must transpile, deploy AND execute on
 * both live databases. Sibling of {@link EmitterDeployabilityIT}: substring assertions alone
 * happily pass syntactically invalid output, so the gate is CREATE + CALL/SELECT on real
 * PostgreSQL 16 and MySQL 8.4.
 *
 * <p>Shapes covered at execution time: DSL INSERT into the reserved-word table (quoted column
 * list, quoted ON CONFLICT target on PostgreSQL, the quoted no-op ON DUPLICATE KEY UPDATE
 * column on MySQL), DSL UPDATE with a reserved SET column and reserved WHERE column, DSL
 * DELETE with a reserved WHERE column, and an emitter-rendered SELECT (quoted projection,
 * FROM, WHERE and ORDER BY identifiers) executed directly against both databases.</p>
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class ReservedWordIdentifierIT {
    private static final String SCHEMA = "test";

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void reservedWordSchemaTranspilesDeploysAndExecutesOnPostgresAndMySql() throws Exception {
        Path source = write("""
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class ReservedWordIdentifierFixture {
                    static final SelectTable SELECT_ROWS = new SelectTable();

                    // INSERT into a table named "select" with reserved-word columns; the conflict
                    // clause exercises the quoted ON CONFLICT target (PostgreSQL) and the quoted
                    // no-op ON DUPLICATE KEY UPDATE column (MySQL).
                    @StoredProcedure
                    public static void recordRow(int key, int order, String group) {
                        insertInto(SELECT_ROWS)
                                .set(SELECT_ROWS.KEY, key)
                                .set(SELECT_ROWS.ORDER, order)
                                .set(SELECT_ROWS.GROUP, group)
                                .onConflict(SELECT_ROWS.KEY)
                                .doUpdate()
                                .execute();
                    }

                    // UPDATE with a reserved SET column and a reserved WHERE column.
                    @StoredProcedure
                    public static void renumber(int key, int order) {
                        update(SELECT_ROWS)
                                .set(SELECT_ROWS.ORDER, order)
                                .where(SELECT_ROWS.KEY.eq(key))
                                .execute();
                    }

                    // DELETE with a reserved WHERE column.
                    @StoredProcedure
                    public static void removeGroup(String group) {
                        deleteFrom(SELECT_ROWS)
                                .where(SELECT_ROWS.GROUP.eq(group))
                                .execute();
                    }

                    static final class SelectTable extends Table<Object> {
                        final Column<Integer> KEY = column("key", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> ORDER = column("order", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> GROUP = column("group", SQLType.VARCHAR, Nullability.NOT_NULL);

                        SelectTable() {
                            super("select", "test");
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        // Pin the quoted shapes before proving they deploy: every reserved word must be quoted
        // in both dialects' DML.
        String postgresSql = sqlFor(generated, "postgresql");
        assertTrue(postgresSql.contains("INSERT INTO \"test\".\"select\" (\"key\", \"order\", \"group\")"),
                "expected quoted PostgreSQL insert, got:\n" + postgresSql);
        assertTrue(postgresSql.contains("ON CONFLICT (\"key\") DO NOTHING"),
                "expected quoted PostgreSQL conflict target, got:\n" + postgresSql);
        assertTrue(postgresSql.contains("UPDATE \"test\".\"select\" SET \"order\" = p_order WHERE COALESCE((\"select\".\"key\" = p_key), FALSE)"),
                "expected quoted PostgreSQL update, got:\n" + postgresSql);
        assertTrue(postgresSql.contains("DELETE FROM \"test\".\"select\" WHERE COALESCE((\"select\".\"group\" = p_group), FALSE)"),
                "expected quoted PostgreSQL delete, got:\n" + postgresSql);
        String mysqlSql = sqlFor(generated, "mysql");
        assertTrue(mysqlSql.contains("INSERT INTO `test`.`select` (`key`, `order`, `group`)"),
                "expected quoted MySQL insert, got:\n" + mysqlSql);
        assertTrue(mysqlSql.contains("ON DUPLICATE KEY UPDATE `key` = `key`"),
                "expected quoted MySQL duplicate-key no-op, got:\n" + mysqlSql);
        assertTrue(mysqlSql.contains("UPDATE `test`.`select` SET `order` = p_order WHERE COALESCE((`select`.`key` = p_key), FALSE)"),
                "expected quoted MySQL update, got:\n" + mysqlSql);
        assertTrue(mysqlSql.contains("DELETE FROM `test`.`select` WHERE COALESCE((`select`.`group` = p_group), FALSE)"),
                "expected quoted MySQL delete, got:\n" + mysqlSql);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            deployPostgres(postgres, generated);
            deployMySql(mysql, generated);

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                callRecordRow(connection, 1, 10, "alpha");
                callRecordRow(connection, 2, 20, "beta");
                // Conflicting insert must be a silent no-op (PG: DO NOTHING, MySQL: no-op
                // ON DUPLICATE KEY UPDATE `key` = `key`), leaving the original order intact.
                callRecordRow(connection, 1, 99, "gamma");
                assertEquals(List.of(10, 20), readOrders(connection, dialect), dialect + " after conflicting insert");

                callRenumber(connection, 2, 25);
                assertEquals(List.of(10, 25), readOrders(connection, dialect), dialect + " after renumber");

                callRemoveGroup(connection, "alpha");
                assertEquals(List.of(25), readOrders(connection, dialect), dialect + " after delete");
            }
        }
    }

    /**
     * Executes the emitter-rendered SELECT (quoted projection/FROM/WHERE/ORDER BY identifiers)
     * directly against the live database: the read path goes through the same
     * {@code visitSelectSql} rendering the routines use.
     */
    private static List<Integer> readOrders(Connection connection, String dialect) throws SQLException {
        SelectSql select = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("select", "order"), "order")),
                "select",
                List.of(),
                new BinaryOpExpression(
                        new ColumnRefExpression("select", "key"),
                        BinaryOperator.GREATER_THAN,
                        new LiteralExpression(0, new TIntType())),
                List.of(),
                null,
                List.of(new OrderBySpec(new ColumnRefExpression("select", "order"), SortDirection.ASC)),
                null,
                null,
                null,
                List.of());
        String sql = "postgresql".equals(dialect)
                ? new PostgreSqlEmitter().visitSelectSql(select)
                : new MySqlEmitter().visitSelectSql(select);

        List<Integer> orders = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                orders.add(resultSet.getInt(1));
            }
        }
        return orders;
    }

    private static void callRecordRow(Connection connection, int key, int order, String group) throws SQLException {
        String sql = "CALL " + SCHEMA + ".record_row(?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, key);
            statement.setInt(2, order);
            statement.setString(3, group);
            statement.execute();
        }
    }

    private static void callRenumber(Connection connection, int key, int order) throws SQLException {
        String sql = "CALL " + SCHEMA + ".renumber(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, key);
            statement.setInt(2, order);
            statement.execute();
        }
    }

    private static void callRemoveGroup(Connection connection, String group) throws SQLException {
        String sql = "CALL " + SCHEMA + ".remove_group(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, group);
            statement.execute();
        }
    }

    private static void deployPostgres(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            // Routine bodies reference the fixture table unqualified; resolve at execution time.
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".\"select\" ("
                    + "\"key\" INT PRIMARY KEY, \"order\" INT NOT NULL, \"group\" TEXT NOT NULL)");
        }
        deploy(connection, generated, "postgresql");
    }

    private static void deployMySql(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".`select`");
            statement.execute("CREATE TABLE " + SCHEMA + ".`select` ("
                    + "`key` INT PRIMARY KEY, `order` INT NOT NULL, `group` VARCHAR(191) NOT NULL)");
        }
        deploy(connection, generated, "mysql");
    }

    private static String sqlFor(List<TranspilationPipeline.GeneratedSql> generated, String target) {
        return generated.stream()
                .filter(sql -> sql.target().equals(target))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static void deploy(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated,
            String target
    ) throws SQLException {
        for (TranspilationPipeline.GeneratedSql artifact : generated) {
            if (!target.equals(artifact.target())) {
                continue;
            }
            for (String sql : splitStatements(artifact.sql())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException exception) {
                    throw new SQLException("Failed to deploy generated " + target
                            + " artifact " + artifact.artifactName() + ":\n" + sql, exception);
                }
            }
        }
    }

    private static List<String> splitStatements(String sqlScript) {
        if (!sqlScript.contains("DELIMITER ")) {
            return splitSemicolonStatements(sqlScript);
        }

        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder current = new StringBuilder();

        for (String line : sqlScript.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            String buffered = current.toString().trim();
            if (buffered.endsWith(delimiter)) {
                statements.add(buffered.substring(0, buffered.length() - delimiter.length()).trim());
                current.setLength(0);
            }
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static List<String> splitSemicolonStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarTag = null;

        for (int i = 0; i < sqlScript.length(); i++) {
            if (dollarTag != null) {
                if (sqlScript.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(sqlScript.charAt(i));
                }
                continue;
            }

            char c = sqlScript.charAt(i);
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'' && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"' && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '$') {
                int end = sqlScript.indexOf('$', i + 1);
                if (end > i) {
                    String candidate = sqlScript.substring(i, end + 1);
                    if (candidate.matches("\\$[A-Za-z0-9_]*\\$")) {
                        dollarTag = candidate;
                        current.append(candidate);
                        i = end;
                        continue;
                    }
                }
            }
            if (c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private Path write(String source) throws Exception {
        Path sourceFile = tempDir.resolve("ReservedWordIdentifierFixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);
        return sourceFile;
    }
}
