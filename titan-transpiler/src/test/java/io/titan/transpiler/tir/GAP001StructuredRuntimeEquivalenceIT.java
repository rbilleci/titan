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
class GAP001StructuredRuntimeEquivalenceIT {
    private static final String SCHEMA = "test";

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void descriptorArrayLookupMatchesJavaSemanticsInPostgresAndMySql() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String outputKey, boolean listRoot) {
                }

                class GAP001RecordArrayEquivalenceFixture {
                    @StoredFunction
                    public static String findOutputKey(String name) {
                        FieldDescriptor[] fields = new FieldDescriptor[]{
                                new FieldDescriptor("title", "title", false),
                                new FieldDescriptor("author", "authorName", false)
                        };
                        if (fields.length < 1) {
                            throw new IllegalArgumentException("TITAN_VALIDATION_EMPTY_DESCRIPTOR_LIST");
                        }
                        FieldDescriptor first = fields[0];
                        if (first.name().equals(name)) {
                            return first.outputKey();
                        }
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field.outputKey();
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    @StoredFunction
                    public static String findOutputKeyFromImmutableList(String name) {
                        for (FieldDescriptor field : java.util.List.of(
                                new FieldDescriptor("title", "title", false),
                                new FieldDescriptor("author", "authorName", false))) {
                            if (field.name().equals(name)) {
                                return field.outputKey();
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
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

            assertDescriptorLookupMatches(postgres, "postgresql", "find_output_key", "title");
            assertDescriptorLookupMatches(postgres, "postgresql", "find_output_key", "author");
            assertDescriptorLookupMatches(postgres, "postgresql", "find_output_key_from_immutable_list", "author");
            assertDescriptorLookupFails(postgres, "postgresql", "find_output_key", "missing");

            assertDescriptorLookupMatches(mysql, "mysql", "find_output_key", "title");
            assertDescriptorLookupMatches(mysql, "mysql", "find_output_key", "author");
            assertDescriptorLookupMatches(mysql, "mysql", "find_output_key_from_immutable_list", "author");
            assertDescriptorLookupFails(mysql, "mysql", "find_output_key", "missing");
        }
    }

    private static void assertDescriptorLookupMatches(
            Connection connection,
            String dialect,
            String routineName,
            String fieldName
    ) throws SQLException {
        assertEquals(javaFindOutputKey(fieldName), callFunction(connection, dialect, routineName, fieldName));
    }

    private static void assertDescriptorLookupFails(
            Connection connection,
            String dialect,
            String routineName,
            String fieldName
    ) {
        try {
            callFunction(connection, dialect, routineName, fieldName);
        } catch (SQLException exception) {
            assertTrue(exception.getMessage().contains("TITAN_VALIDATION_UNKNOWN_FIELD"), exception.getMessage());
            return;
        }
        throw new AssertionError("Expected SQL-mode descriptor lookup to reject unknown field");
    }

    private static String callFunction(
            Connection connection,
            String dialect,
            String routineName,
            String fieldName
    ) throws SQLException {
        String sql = "SELECT " + SCHEMA + "." + routineName + "(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fieldName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "routine returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String javaFindOutputKey(String name) {
        FieldDescriptor[] fields = new FieldDescriptor[]{
                new FieldDescriptor("title", "title", false),
                new FieldDescriptor("author", "authorName", false)
        };
        if (fields.length < 1) {
            throw new IllegalArgumentException("TITAN_VALIDATION_EMPTY_DESCRIPTOR_LIST");
        }
        FieldDescriptor first = fields[0];
        if (first.name().equals(name)) {
            return first.outputKey();
        }
        for (FieldDescriptor field : fields) {
            if (field.name().equals(name)) {
                return field.outputKey();
            }
        }
        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
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
        }
        deploy(connection, generated, "mysql");
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
        Path sourceFile = tempDir.resolve("GAP001EquivalenceFixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);
        return sourceFile;
    }

    private record FieldDescriptor(String name, String outputKey, boolean listRoot) {
    }
}
