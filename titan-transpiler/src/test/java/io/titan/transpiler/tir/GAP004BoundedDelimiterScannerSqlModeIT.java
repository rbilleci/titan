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

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class GAP004BoundedDelimiterScannerSqlModeIT {
    private static final String SCHEMA = "test";

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void boundedDelimiterScannerMatchesJavaSemanticsInPostgresAndMySql() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                class GAP004BoundedDelimiterScannerSqlFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            if (ch == '[' || ch == '(' || ch == '{') {
                                depth++;
                            } else if (ch == ']' || ch == ')' || ch == '}') {
                                depth--;
                                if (depth < 0) {
                                    return false;
                                }
                            } else if (ch == '\\\\') {
                                return false;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            deployPostgres(postgres, generated);
            deployMySql(mysql, generated);

            assertTrue(sqlFor(generated, "mysql").contains("CHAR(92 USING utf8mb4)"));

            for (String input : List.of("", "[]", "a\\b", "a[b(c)d]", "{alpha[beta](gamma)}", "][", "abc[")) {
                boolean expected = javaBalancedGroups(input);
                assertEquals(expected, callBalancedGroups(postgres, "postgresql", input), input);
                assertEquals(expected, callBalancedGroups(mysql, "mysql", input), input);
            }
        }
    }

    private static boolean callBalancedGroups(Connection connection, String dialect, String text) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".balanced_groups(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, text);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), dialect + " routine returned no rows");
                return resultSet.getBoolean(1);
            }
        }
    }

    private static boolean javaBalancedGroups(String text) {
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '[' || ch == '(' || ch == '{') {
                depth++;
            } else if (ch == ']' || ch == ')' || ch == '}') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            } else if (ch == '\\') {
                return false;
            }
            index++;
        }
        return depth == 0;
    }

    private static void deployPostgres(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        deploy(connection, generated, "postgresql");
    }

    private static void deployMySql(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            statement.execute("SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES'");
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
        Path sourceFile = tempDir.resolve("GAP004SqlModeFixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);
        return sourceFile;
    }
}
