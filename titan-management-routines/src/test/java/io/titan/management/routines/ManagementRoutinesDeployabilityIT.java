package io.titan.management.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Deployability probe (Phase B1/B2 verification, @Tag("docker")). Proves the dogfooded store is
 * REAL on both live dialects:
 *
 * <ol>
 *   <li><b>B1:</b> the hand-authored schema DDL ({@code management_schema.{postgresql,mysql}.sql})
 *       applies to scratch PG 16 + MySQL 8.4 and the tables + keys exist (incl. the composite
 *       idempotency PK the ON CONFLICT target needs and the deployment supersede index).</li>
 *   <li><b>B2:</b> the packaged routine bundle (runtime helpers + routines) deploys on top, and
 *       {@code import_model_document} executes with the FAITHFUL idempotent replay: same key twice
 *       -> ONE audit row (server-side short-circuit, NOT ON CONFLICT, since the audit insert has no
 *       conflict clause); a different key -> a second mutation + a second audit row + a bumped draft
 *       version (G3). {@code activate_deployment} runs its read-decide-branch precondition + supersede
 *       + activate.</li>
 * </ol>
 *
 * <p>The routines have no transaction envelope of their own; here the {@code CALL} runs in
 * autocommit, which is sufficient to prove deployability + server-side branching (the durable
 * transaction envelope is the JDBC adapter's job, Phase B4).
 */
@Tag("docker")
class ManagementRoutinesDeployabilityIT {

    private static final String SCHEMA = "management";

    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainers() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("titan")
                .withUsername("titan")
                .withPassword("titan");
        postgres.start();
        // The management schema's database is created here by the test harness (admin); the install
        // assumes the database already exists and the deployer has DML/DDL within it.
        // log_bin_trust_function_creators=1 lets a non-super user CREATE FUNCTION under binary logging
        // (the activate_deployment @StoredFunction, RD-1) without SUPER/SET_USER_ID — the same flag
        // the dogfood IT uses; a real deployer would be granted the privilege or run with it set.
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName(SCHEMA)
                .withUsername("titan")
                .withPassword("titan")
                .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0");
        mysql.start();
    }

    @AfterAll
    static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void schemaAndRoutinesDeployAndImportReplaysFaithfullyOnPostgres() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA);
            }
            applyScript(connection, schema("postgresql"));
            assertSchemaObjectsExistPostgres(connection);
            // Only the routine bundle layers on the DDL — the routines reference NO titan_runtime
            // helpers (0 static_get, no arithmetic helpers), so the store needs only its own tables
            // (the spike's "clean form").
            applyScript(connection, routines("postgresql"));

            exerciseImportFaithfulReplay(connection, "postgresql");
            exerciseActivateDeployment(connection, "postgresql");
        }
    }

    @Test
    void schemaAndRoutinesDeployAndImportReplaysFaithfullyOnMysql() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("USE " + SCHEMA);
            }
            applyScript(connection, schema("mysql"));
            assertSchemaObjectsExistMysql(connection);
            // Routines only; no titan_runtime dependency (see the PostgreSQL leg).
            applyScript(connection, routines("mysql"));

            exerciseImportFaithfulReplay(connection, "mysql");
            exerciseActivateDeployment(connection, "mysql");
        }
    }

    // ------------------------------------------------------------------
    // B2 — faithful idempotent replay + activate
    // ------------------------------------------------------------------

    private void exerciseImportFaithfulReplay(Connection connection, String dialect) throws SQLException {
        String hashA = "sha256:" + "a".repeat(64);
        String hashB = "sha256:" + "b".repeat(64);

        // First apply (key-1): one draft, one audit row, one idempotency row.
        callImport(connection, "draft-a", "ws-1", "model-1", 1, "key-1", "{\"model\":\"v1\"}", hashA,
                "audit-1", 1, "req-1");
        assertEquals(1, count(connection, "management_drafts"), dialect + " draft inserted on first apply");
        assertEquals(1, count(connection, "management_audit_outcomes"), dialect + " one audit row after first apply");
        assertEquals(1, count(connection, "management_idempotency"), dialect + " one idempotency row after first apply");

        // Replay (SAME key-1): the server-side G2 branch short-circuits BEFORE the (conflict-free)
        // audit insert, so the audit row count stays at ONE — proving the read-decide-branch fired,
        // not an ON CONFLICT no-op (the audit insert has no conflict clause).
        callImport(connection, "draft-a", "ws-1", "model-1", 1, "key-1", "{\"model\":\"v1\"}", hashA,
                "audit-2", 2, "req-1");
        assertEquals(1, count(connection, "management_audit_outcomes"),
                dialect + " replay with same key must NOT add an audit row (server-side short-circuit)");
        assertEquals(1, count(connection, "management_idempotency"),
                dialect + " replay with same key must NOT add an idempotency row");

        // New key-2 for the same draft: a real second mutation. version bumps to 2 via G3.
        callImport(connection, "draft-a", "ws-1", "model-1", 1, "key-2", "{\"model\":\"v2\"}", hashB,
                "audit-3", 3, "req-2");
        assertEquals(2, count(connection, "management_audit_outcomes"), dialect + " new key adds an audit row");
        assertEquals(2, count(connection, "management_idempotency"), dialect + " new key adds an idempotency row");
        assertEquals(2, draftVersion(connection, "draft-a"), dialect + " G3: new-key mutation bumps version to 2");
    }

    private void exerciseActivateDeployment(Connection connection, String dialect) throws SQLException {
        // Seed a verified artifact (incl. its GAP-005 evidence + valid titan-*.json metadata paths) and
        // two deployments (one already ACTIVE, the target pending). RD-1: activate_deployment is now a
        // value-returning FUNCTION; E032 (GAP-005 metadata PATHS) is now part of its server-side chain.
        String hashC = "sha256:" + "c".repeat(64);
        callSeedArtifactRef(connection, "ar-1", "art-1", hashC,
                "generated/demo/titan-artifact.json", "generated/demo/titan-object-inventory.json",
                "generated/demo/titan-install-plan.json", "generated/demo/titan-install-verification.json", "passed");
        setEvidence(connection, "ar-1");
        callSeedDeployment(connection, "dep-old", "ws-1", "ar-1", "prod", "active", "2026-06-14T00:00:00Z");
        callSeedDeployment(connection, "dep-new", "ws-1", "ar-1", "prod", "pendingActivation", "2026-06-14T01:00:00Z");

        // SUCCESS: all preconditions pass server-side -> function returns 0, supersede + activate run.
        assertEquals(0, callActivate(connection, "dep-new", "ws-1", "ar-1", "prod", "2026-06-14T02:00:00Z", hashC),
                dialect + " activate returns 0 (success)");
        assertEquals("active", deploymentStatus(connection, "dep-new"), dialect + " target deployment activated");
        assertEquals("superseded", deploymentStatus(connection, "dep-old"), dialect + " sibling ACTIVE deployment superseded");

        // E033: activating a deployment whose artifact is NOT verified returns 33, no mutation. Its
        // metadata paths are valid titan-*.json suffixes so E032 passes and E033 is the first failure.
        String hashD = "sha256:" + "d".repeat(64);
        callSeedArtifactRef(connection, "ar-2", "art-2", hashD,
                "generated/demo/titan-artifact.json", "generated/demo/titan-object-inventory.json",
                "generated/demo/titan-install-plan.json", "generated/demo/titan-install-verification.json", "pending");
        setEvidence(connection, "ar-2");
        callSeedDeployment(connection, "dep-unverified", "ws-1", "ar-2", "staging", "pendingActivation",
                "2026-06-14T03:00:00Z");
        assertEquals(33, callActivate(connection, "dep-unverified", "ws-1", "ar-2", "staging",
                        "2026-06-14T04:00:00Z", hashD),
                dialect + " RD-1: unverified artifact returns E033 code server-side");
        assertEquals("pendingActivation", deploymentStatus(connection, "dep-unverified"),
                dialect + " E033: unverified artifact must NOT activate");

        // E032 (final residual, now server-side): a verified artifact whose metadata path columns do
        // NOT end with their fixed titan-*.json filenames returns 32 from the function via the four
        // AND-chained Column.like('%titan-*.json') conditions — NO mutation. This is the headline proof
        // that E032 surfaces from the ROUTINE, not the adapter.
        callSeedArtifactRef(connection, "ar-badpaths", "art-badpaths", hashC,
                "manifest.json", "inventory.json", "plan.json", "verify.json", "passed");
        setEvidence(connection, "ar-badpaths");
        callSeedDeployment(connection, "dep-badpaths", "ws-1", "ar-badpaths", "staging", "pendingActivation",
                "2026-06-14T03:30:00Z");
        assertEquals(32, callActivate(connection, "dep-badpaths", "ws-1", "ar-badpaths", "staging",
                        "2026-06-14T03:45:00Z", hashC),
                dialect + " E032: wrong metadata PATHS return 32 from the routine (Column.like suffix check)");
        assertEquals("pendingActivation", deploymentStatus(connection, "dep-badpaths"),
                dialect + " E032: wrong-path artifact must NOT activate");

        // E030: a missing artifact ref returns 30. E031: a wrong expected hash returns 31. E035: a
        // non-admin/platform actor returns 35. All server-side, read-decide-RETURN (RD-1).
        assertEquals(30, callActivate(connection, "dep-unverified", "ws-1", "ar-missing", "staging",
                        "2026-06-14T05:00:00Z", hashD),
                dialect + " RD-1: missing artifact returns E030 code");
        assertEquals(31, callActivate(connection, "dep-unverified", "ws-1", "ar-2", "staging",
                        "2026-06-14T05:00:00Z", "sha256:" + "0".repeat(64)),
                dialect + " RD-1: hash mismatch returns E031 code");
        assertEquals(35, callActivateAs(connection, "dep-unverified", "ws-1", "ar-2", "staging",
                        "2026-06-14T05:00:00Z", hashD, "viewer"),
                dialect + " RD-1: unauthorized actor returns E035 code");
    }

    // Populate the GAP-005 evidence columns the activate function's E036 check reads. The activate
    // helper passes the SAME values as expected, so E036 passes on the happy path.
    private static void setEvidence(Connection connection, String artifactRefId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + SCHEMA + ".management_artifact_refs SET package_mode = 'migration', dialect = 'postgresql', "
                        + "manifest_content_hash = 'mh', object_inventory_hash = 'oh', install_plan_hash = 'ph', "
                        + "install_verification_hash = 'vh', source_inputs_hash = 'sh' WHERE id = ?")) {
            statement.setString(1, artifactRefId);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // CALL helpers
    // ------------------------------------------------------------------

    private static void callImport(Connection connection, String draftId, String workspaceId, String modelId,
            int version, String key, String document, String hash, String auditId, int seq, String requestId)
            throws SQLException {
        // RD-2: three distinct hashes. The probe uses input=hash, output=hash+":out", document=hash+":doc"
        // so the deployed routine demonstrably writes each to its own column (asserted via outcome_hash).
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".import_model_document(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, draftId);
            statement.setString(2, workspaceId);
            statement.setString(3, modelId);
            statement.setInt(4, version);
            statement.setString(5, key);
            statement.setString(6, document);
            statement.setString(7, hash);            // inputHash
            statement.setString(8, hash + ":out");   // outputHash (distinct)
            statement.setString(9, hash + ":doc");   // documentHash (distinct)
            statement.setString(10, "2026-06-14T00:00:00Z");
            statement.setString(11, "2026-06-14T00:00:00Z");
            statement.setInt(12, seq);
            statement.setString(13, auditId);
            statement.setString(14, requestId);
            statement.setString(15, "2026-06-14T00:00:00Z");
            statement.execute();
        }
    }

    private static void callSeedArtifactRef(Connection connection, String id, String artifactId, String artifactHash,
            String manifest, String inventory, String plan, String verify, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".seed_artifact_ref(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, artifactId);
            statement.setString(3, artifactHash);
            statement.setString(4, manifest);
            statement.setString(5, inventory);
            statement.setString(6, plan);
            statement.setString(7, verify);
            statement.setString(8, status);
            statement.execute();
        }
    }

    private static void callSeedDeployment(Connection connection, String id, String workspaceId, String artifactRefId,
            String environment, String status, String requestedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".seed_deployment(?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, workspaceId);
            statement.setString(3, artifactRefId);
            statement.setString(4, environment);
            statement.setString(5, status);
            statement.setString(6, requestedAt);
            statement.execute();
        }
    }

    private static int callActivate(Connection connection, String deploymentId, String workspaceId,
            String artifactRefId, String environment, String activatedAt, String expectedHash) throws SQLException {
        return callActivateAs(connection, deploymentId, workspaceId, artifactRefId, environment, activatedAt,
                expectedHash, "platform");
    }

    // RD-1: activate_deployment is a value-returning @StoredFunction, invoked via SELECT fn(...) on
    // both dialects; it returns an int status code (0 = success, E03x otherwise). The evidence params
    // mirror setEvidence() so E036 passes on the happy path.
    private static int callActivateAs(Connection connection, String deploymentId, String workspaceId,
            String artifactRefId, String environment, String activatedAt, String expectedHash, String actorRole)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".activate_deployment(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, deploymentId);
            statement.setString(2, workspaceId);
            statement.setString(3, artifactRefId);
            statement.setString(4, environment);
            statement.setString(5, activatedAt);
            statement.setString(6, actorRole);
            statement.setString(7, expectedHash);
            statement.setString(8, "migration");
            statement.setString(9, "postgresql");
            statement.setString(10, "mh");
            statement.setString(11, "oh");
            statement.setString(12, "ph");
            statement.setString(13, "vh");
            statement.setString(14, "sh");
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next(), "activate_deployment returned no status row");
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Assertions on schema objects (B1) + query helpers
    // ------------------------------------------------------------------

    private static void assertSchemaObjectsExistPostgres(Connection connection) throws SQLException {
        for (String table : new String[] {
                "management_drafts", "management_artifact_refs", "management_deployments",
                "management_idempotency", "management_audit_outcomes"}) {
            assertEquals(1, scalar(connection,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                            + "' AND table_name = '" + table + "'"),
                    "PG table missing: " + table);
        }
        // Composite idempotency PK (3 key columns) — required for ON CONFLICT target inference.
        assertEquals(3, scalar(connection,
                "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                        + "WHERE tc.table_schema = '" + SCHEMA + "' AND tc.table_name = 'management_idempotency' "
                        + "AND tc.constraint_type = 'PRIMARY KEY'"),
                "PG composite idempotency PK must have 3 key columns");
        // Supersede index present.
        assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = '" + SCHEMA
                        + "' AND indexname = 'management_deployments_supersede_idx'"),
                "PG supersede index missing");
    }

    private static void assertSchemaObjectsExistMysql(Connection connection) throws SQLException {
        for (String table : new String[] {
                "management_drafts", "management_artifact_refs", "management_deployments",
                "management_idempotency", "management_audit_outcomes"}) {
            assertEquals(1, scalar(connection,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                            + "' AND table_name = '" + table + "'"),
                    "MySQL table missing: " + table);
        }
        assertEquals(3, scalar(connection,
                "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = '" + SCHEMA
                        + "' AND table_name = 'management_idempotency' AND constraint_name = 'PRIMARY'"),
                "MySQL composite idempotency PK must have 3 key columns");
        assertEquals(1, scalar(connection,
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '"
                        + SCHEMA + "' AND table_name = 'management_deployments' "
                        + "AND index_name = 'management_deployments_supersede_idx'"),
                "MySQL supersede index missing");
    }

    private static int count(Connection connection, String table) throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM " + SCHEMA + "." + table);
    }

    private static int draftVersion(Connection connection, String draftId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM " + SCHEMA + ".management_drafts WHERE id = ?")) {
            statement.setString(1, draftId);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next(), "no draft row for " + draftId);
                return rs.getInt(1);
            }
        }
    }

    private static String deploymentStatus(Connection connection, String deploymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM " + SCHEMA + ".management_deployments WHERE id = ?")) {
            statement.setString(1, deploymentId);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next(), "no deployment row for " + deploymentId);
                return rs.getString(1);
            }
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------
    // Script loading + application
    // ------------------------------------------------------------------

    private static void applyScript(Connection connection, String script) throws SQLException {
        for (String statementSql : SqlScripts.split(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            } catch (SQLException exception) {
                throw new SQLException("Failed to apply statement:\n" + statementSql, exception);
            }
        }
    }

    // The in-build transpile bundle: <sql.dir>/io/titan/management/sql/{dialect}/{schema,routines}.sql.
    // The generator copies the hand-authored DDL into the bundle as schema.sql (applied first), then
    // the transpiled routines into routines.sql — exactly the ordered bundle titan-management loads.
    private static Path bundleDir(String dialect) {
        return Path.of(System.getProperty(
                "titan.management.routines.sql.dir", "build/generated/management-sql"))
                .resolve("io/titan/management/sql")
                .resolve(dialect);
    }

    private static String schema(String dialect) throws Exception {
        return Files.readString(bundleDir(dialect).resolve("schema.sql"), StandardCharsets.UTF_8);
    }

    private static String routines(String dialect) throws Exception {
        return Files.readString(bundleDir(dialect).resolve("routines.sql"), StandardCharsets.UTF_8);
    }
}
