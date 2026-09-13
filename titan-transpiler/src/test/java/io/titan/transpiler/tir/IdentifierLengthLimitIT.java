package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * TG-BLK-011 live-database regression: a nested record and enum whose qualified SQL names
 * exceed both dialects' identifier ceilings must package, DEPLOY and EXECUTE on live
 * PostgreSQL and MySQL, with the deployed catalog names matching the described inventory ids
 * byte for byte.
 *
 * <p>Without length-limited naming this fails in three distinct ways, all observed at consumer
 * scale: MySQL rejects every long CREATE with ER_TOO_LONG_IDENT; PostgreSQL silently truncates
 * to 63 bytes so all member accessors of one long record collapse onto a single function name
 * ("cannot change return type" where return types differ, silent last-body-wins where they
 * agree); and the "deployed" PostgreSQL names no longer match the inventory ids the install
 * verifier and rollback scripts carry.</p>
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test.
@org.junit.jupiter.api.Tag("docker")
class IdentifierLengthLimitIT {
    private static final String SCHEMA = "test";

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @TempDir
    Path tempDir;

    private static final String FIXTURE = """
            import titan.dsl.StoredFunction;

            class DurableManagementStoreProductStateJournal {
                record ProductStateJournalEntryPageSnapshot(String pageLabel, int entryCount, boolean hasNextPage) {}

                enum ProductLifecycleStageClassificationChannel {
                    ACTIVE(5), RETIRED(11);
                    final int priorityWeight;
                    ProductLifecycleStageClassificationChannel(int priorityWeight) {
                        this.priorityWeight = priorityWeight;
                    }
                    int priorityWeight() { return priorityWeight; }
                }

                @StoredFunction
                public static int journalPageWeight(String pageLabel, int entryCount, boolean hasNextPage,
                        ProductLifecycleStageClassificationChannel stage) {
                    ProductStateJournalEntryPageSnapshot snapshot =
                            new ProductStateJournalEntryPageSnapshot(pageLabel, entryCount, hasNextPage);
                    int weight = stage.priorityWeight();
                    if (snapshot.hasNextPage()) {
                        return snapshot.entryCount() + weight;
                    }
                    return snapshot.entryCount() - weight;
                }
            }
            """;

    @Test
    void overLimitNamesDeployAndExecuteOnBothDialectsWithCatalogMatchingInventory() throws Exception {
        Path source = tempDir.resolve("DurableManagementStoreProductStateJournal" + System.nanoTime() + ".java");
        Files.writeString(source, FIXTURE);

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
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            // Call sites resolve end to end: results equal the Java reference on both dialects.
            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";
                assertEquals(javaJournalPageWeight(7, true, 5),
                        callJournalPageWeight(connection, 7, true, "ACTIVE"),
                        dialect + " journalPageWeight(7, true, ACTIVE)");
                assertEquals(javaJournalPageWeight(7, false, 11),
                        callJournalPageWeight(connection, 7, false, "RETIRED"),
                        dialect + " journalPageWeight(7, false, RETIRED)");
            }

            // Deployed catalog names must equal the inventory ids byte for byte — the PG
            // silent-truncation failure mode "passed" probes only because probe identifiers
            // truncated identically while the catalog held different names.
            for (TranspilationPipeline.GeneratedSql artifact : generated) {
                for (SqlObject object : artifact.sqlObjects()) {
                    switch (artifact.target()) {
                        case "postgresql" -> assertPostgresObjectExists(postgres, object);
                        case "mysql" -> assertMySqlObjectExists(mysql, object);
                        default -> throw new AssertionError("unexpected target " + artifact.target());
                    }
                }
            }
        }
    }

    private static int javaJournalPageWeight(int entryCount, boolean hasNextPage, int weight) {
        return hasNextPage ? entryCount + weight : entryCount - weight;
    }

    private static int callJournalPageWeight(
            Connection connection, int entryCount, boolean hasNextPage, String stage) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".journal_page_weight(?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "page");
            statement.setInt(2, entryCount);
            statement.setBoolean(3, hasNextPage);
            statement.setString(4, stage);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "journal_page_weight returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static void assertPostgresObjectExists(Connection connection, SqlObject object) throws SQLException {
        String sql = switch (object.kind()) {
            case FUNCTION, PROCEDURE -> "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = ? AND p.proname = ?";
            case TYPE -> "SELECT COUNT(*) FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                    + "WHERE n.nspname = ? AND t.typname = ?";
            case TABLE -> "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
            default -> null;
        };
        if (sql == null) {
            return;
        }
        assertCatalogRow(connection, sql, object, "postgresql");
    }

    private static void assertMySqlObjectExists(Connection connection, SqlObject object) throws SQLException {
        String sql = switch (object.kind()) {
            case FUNCTION, PROCEDURE -> "SELECT COUNT(*) FROM information_schema.routines "
                    + "WHERE routine_schema = ? AND routine_name = ?";
            case TABLE -> "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
            default -> null;
        };
        if (sql == null) {
            return;
        }
        assertCatalogRow(connection, sql, object, "mysql");
    }

    private static void assertCatalogRow(
            Connection connection, String sql, SqlObject object, String dialect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, object.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "catalog query returned no rows");
                assertEquals(1, resultSet.getInt(1),
                        dialect + " catalog must contain the inventory id exactly (kind "
                                + object.kind() + "): " + object.name());
            }
        }
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
            for (String sql : SqlScripts.split(artifact.sql())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException exception) {
                    throw new SQLException("Failed to deploy generated " + target
                            + " artifact " + artifact.artifactName() + ":\n" + sql, exception);
                }
            }
        }
    }
}
