package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.titan.test.TestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("docker")
final class ReviewedHandlerDeployabilityIT {

    @TempDir
    Path tempDir;

    @Test
    void postgresqlCallsReviewedJdbcHandlerWithinTheCallerTransaction() throws Exception {
        verify("postgresql", TestContainers.freshPostgresDatabase("reviewed_handler_pg"));
    }

    @Test
    void mysqlCallsReviewedJdbcHandlerWithinTheCallerTransaction() throws Exception {
        verify("mysql", TestContainers.freshMysqlDatabase("reviewed_handler_mysql"));
    }

    private void verify(String dialect, TestContainers.SharedDatabase database) throws Exception {
        String schema = dialect.equals("postgresql") ? "public" : database.databaseName();
        Path entry = tempDir.resolve("ProcedureDispatch.java");
        Path handler = tempDir.resolve("ReviewedProcedure.java");
        Files.writeString(entry, """
                package example;
                import java.sql.Connection;
                import java.sql.SQLException;
                import titan.dsl.StoredProcedure;

                class ProcedureDispatch {
                    @StoredProcedure
                    static void run(Connection connection, long itemId, int delta) throws SQLException {
                        ReviewedProcedure.apply(connection, itemId, delta);
                    }
                }
                """);
        Files.writeString(handler, """
                package example;
                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.SQLException;

                class ReviewedProcedure {
                    static void apply(Connection connection, long itemId, int delta) throws SQLException {
                        PreparedStatement update = connection.prepareStatement(
                                "UPDATE %s.reviewed_items SET quantity = quantity + ? WHERE id = ?");
                        update.setInt(1, delta);
                        update.setLong(2, itemId);
                        update.executeUpdate();
                    }
                }
                """.formatted(schema));
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(entry, handler), List.of(), List.of(dialect), List.of(schema), true);

        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + schema
                        + ".reviewed_items (id BIGINT PRIMARY KEY, quantity INTEGER NOT NULL)");
                statement.execute("INSERT INTO " + schema + ".reviewed_items (id, quantity) VALUES (7, 10)");
            }
            for (TranspilationPipeline.GeneratedSql artifact : generated.stream()
                    .sorted(Comparator.comparingInt(sql -> sql.methodName().equals("apply") ? 0 : 1))
                    .toList()) {
                for (String sql : SqlScripts.split(artifact.sql())) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }

            connection.setAutoCommit(false);
            call(connection, schema, 3);
            connection.commit();
            assertEquals(13, quantity(connection, schema));

            call(connection, schema, 5);
            connection.rollback();
            assertEquals(13, quantity(connection, schema));
        }
    }

    private static void call(Connection connection, String schema, int delta) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + schema + ".run(?, ?)")) {
            statement.setLong(1, 7L);
            statement.setInt(2, delta);
            statement.execute();
        }
    }

    private static int quantity(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT quantity FROM " + schema + ".reviewed_items WHERE id = 7")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
