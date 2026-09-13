package io.titan.gradle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class TitanArtifactInstallVerifierIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0");

    @BeforeEach
    void resetScratchDatabases() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION IF EXISTS public.titan_gap005_probe(integer)");
        }
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION IF EXISTS titan_gap005_probe");
        }
    }

    @Test
    void installsAndVerifiesScratchPostgresqlArtifact() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-pg-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", sql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("passed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"kind\": \"scratch\""));
            assertTrue(json.contains("\"objectId\": \"postgresql.public.titan_gap005_probe.function\""));
            assertTrue(json.contains("\"checks\": [\"exists\", \"installedDefinitionObserved\", \"signatureMatches\"]"));
            assertTrue(json.contains("\"drift\": []"));
            assertTrue(json.contains("\"diagnostics\": []"));
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void migrationPackageUsesBundledSqlForHashAndInstallVerification() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-migration-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        // The bundle format titanPackage writes: each generated file behind its
        // titan:source-file marker (the verifier locates per-object SQL via these markers).
        Files.writeString(
                artifactRoot.resolve("postgresql/R__titan_020_routines.sql"),
                "-- titan:source-file:titan_gap005_probe.sql\n" + sql,
                StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest(
                "postgresql",
                "migration",
                inventory,
                "(input_value integer)",
                "integer",
                sql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("passed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"objectId\": \"postgresql.public.titan_gap005_probe.function\""));
            assertTrue(json.contains("\"checks\": [\"exists\", \"installedDefinitionObserved\", \"signatureMatches\"]"));
            assertTrue(json.contains("\"drift\": []"), json);
            assertTrue(json.contains("\"diagnostics\": []"), json);
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void installsAndVerifiesScratchMysqlArtifact() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-mysql-artifact");
        Files.createDirectories(artifactRoot.resolve("mysql"));
        String sql = """
                CREATE FUNCTION titan_gap005_probe(input_value INT)
                RETURNS INT
                DETERMINISTIC
                RETURN input_value + 1
                """;
        Files.writeString(artifactRoot.resolve("mysql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("mysql", MYSQL.getDatabaseName(), "(input_value int)", sql);
        TitanArtifactManifest manifest = manifest("mysql", inventory, "(input_value int)", "int", sql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "mysql",
                            MYSQL.getDockerImageName(),
                            connection));

            assertEquals("passed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"objectId\": \"mysql." + MYSQL.getDatabaseName() + ".titan_gap005_probe.function\""));
            assertTrue(json.contains("\"checks\": [\"exists\", \"installedDefinitionObserved\", \"signatureMatches\"]"));
            assertTrue(json.contains("\"drift\": []"));
            assertTrue(json.contains("\"diagnostics\": []"));
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void brokenInstallStepReportsActionableDiagnostic() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-broken-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value +
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", sql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"stepId\": \"install.sql.titan_gap005_probe.sql\""));
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-INSTALL-SQL\""));
            assertTrue(json.contains("\"objectId\": \"postgresql.public.titan_gap005_probe.function\""));
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-OBJECT-MISSING\""));
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void modifiedPackageSqlReportsDeterministicObjectDrift() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-drift-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String originalSql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        String modifiedSql = originalSql.replace("input_value + 1", "input_value + 2");
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), modifiedSql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", originalSql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", originalSql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"check\": \"sourceInputSha256\""), json);
            assertTrue(json.contains("\"check\": \"objectSqlSha256\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-SOURCE-DRIFT\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-OBJECT-DRIFT\""), json);
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void existingInstalledRoutineDriftIsReportedBeforePackageInstall() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-installed-drift-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String originalSql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        String installedSql = originalSql.replace("input_value + 1", "input_value + 2");
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), originalSql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", originalSql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", originalSql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(installedSql);

            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"check\": \"installedDefinitionCanonicalSha256\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-INSTALLED-DEFINITION-DRIFT\""), json);
            assertTrue(json.contains("\"stepId\": \"verify.installed-definition.postgresql.public.titan_gap005_probe.function\""), json);
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    // Plan 4.4 (audit G-10): drift is compared canonical-to-canonical via a database round-trip.
    // A previously installed routine that differs only in formatting/quoting from the packaged
    // text canonicalizes to the same pg_get_functiondef output and must NOT report drift — the
    // old packaged-hash-vs-catalog-text comparison could never match at all.
    @Test
    void equivalentlyFormattedInstalledRoutineDoesNotDrift() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-formatting-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String packagedSql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        // Same routine, different whitespace, keyword casing and qualification style.
        String preinstalledSql =
                "create or replace function titan_gap005_probe(input_value int) returns int language sql as $$"
                        + "\n  SELECT input_value + 1\n$$;";
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), packagedSql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", packagedSql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", packagedSql);
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(preinstalledSql);

            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("passed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"drift\": []"), json);
            assertTrue(json.contains("\"diagnostics\": []"), json);
        }
    }

    @Test
    void modifiedManifestHashReportsDeterministicDrift() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-manifest-drift-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest("postgresql", inventory, "(input_value integer)", "integer", sql)
                .withManifestContentSha256(sha("stale-manifest"));
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"check\": \"manifestContentSha256\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-MANIFEST-DRIFT\""), json);
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rollbackScriptMatchingManifestHashPasses() throws Exception {
        // B-5 (TG-BLK-008): the verifier integrity-links the rollback script — present at its
        // manifest path with a matching sha256 over the raw bytes → verification passes.
        Path artifactRoot = Files.createTempDirectory("titan-gap005-rollback-ok-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        String rollbackSql = "-- titan-rollback for probe (postgresql)\n"
                + "DROP FUNCTION IF EXISTS \"public\".\"titan_gap005_probe\"(integer);\n";
        Files.writeString(artifactRoot.resolve("titan-rollback.postgresql.sql"), rollbackSql, StandardCharsets.UTF_8);
        TitanArtifactManifest.RollbackScript rollbackRef = new TitanArtifactManifest.RollbackScript(
                "postgresql",
                "titan-rollback.postgresql.sql",
                TitanRollbackScript.statementCount(rollbackSql),
                TitanArtifactManifest.sha256Hex(rollbackSql.getBytes(StandardCharsets.UTF_8)));

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest(
                "postgresql",
                "direct",
                inventory,
                "(input_value integer)",
                "integer",
                sql,
                TitanArtifactHashes.sourceInputsSha256(List.of(new TitanArtifactManifest.SourceInput(
                        "postgresql",
                        "postgresql/titan_gap005_probe.sql",
                        TitanArtifactHashes.sourceInputSha256(sql)))),
                List.of(rollbackRef));
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("passed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"drift\": []"), json);
            assertTrue(json.contains("\"diagnostics\": []"), json);
        }
    }

    @Test
    void tamperedRollbackScriptFailsVerificationWithActionableMessage() throws Exception {
        // B-5: the manifest pins the rollback sha256; a script edited after packaging diverges
        // from that hash and must fail verification naming the rollback file.
        Path artifactRoot = Files.createTempDirectory("titan-gap005-rollback-tampered-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        String rollbackSql = "-- titan-rollback for probe (postgresql)\n"
                + "DROP FUNCTION IF EXISTS \"public\".\"titan_gap005_probe\"(integer);\n";
        // Manifest pins the ORIGINAL rollback hash...
        TitanArtifactManifest.RollbackScript rollbackRef = new TitanArtifactManifest.RollbackScript(
                "postgresql",
                "titan-rollback.postgresql.sql",
                TitanRollbackScript.statementCount(rollbackSql),
                TitanArtifactManifest.sha256Hex(rollbackSql.getBytes(StandardCharsets.UTF_8)));
        // ...but the file on disk was tampered with after packaging.
        String tamperedRollbackSql = rollbackSql
                + "DROP TABLE IF EXISTS \"public\".\"audit_log\";\n";
        Files.writeString(
                artifactRoot.resolve("titan-rollback.postgresql.sql"),
                tamperedRollbackSql,
                StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest(
                "postgresql",
                "direct",
                inventory,
                "(input_value integer)",
                "integer",
                sql,
                TitanArtifactHashes.sourceInputsSha256(List.of(new TitanArtifactManifest.SourceInput(
                        "postgresql",
                        "postgresql/titan_gap005_probe.sql",
                        TitanArtifactHashes.sourceInputSha256(sql)))),
                List.of(rollbackRef));
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"check\": \"rollbackScriptSha256\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-ROLLBACK-DRIFT\""), json);
            assertTrue(json.contains("Rollback script titan-rollback.postgresql.sql hash does not match the manifest entry"), json);
        }
    }

    @Test
    void missingRollbackScriptFailsVerification() throws Exception {
        // B-5: a manifest that references a rollback script which is absent from the package
        // fails verification — the integrity link guarantees the artifact ships the script.
        Path artifactRoot = Files.createTempDirectory("titan-gap005-rollback-missing-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);
        // Intentionally do NOT write titan-rollback.postgresql.sql.

        TitanArtifactManifest.RollbackScript rollbackRef = new TitanArtifactManifest.RollbackScript(
                "postgresql",
                "titan-rollback.postgresql.sql",
                1,
                sha("absent-rollback"));

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest(
                "postgresql",
                "direct",
                inventory,
                "(input_value integer)",
                "integer",
                sql,
                TitanArtifactHashes.sourceInputsSha256(List.of(new TitanArtifactManifest.SourceInput(
                        "postgresql",
                        "postgresql/titan_gap005_probe.sql",
                        TitanArtifactHashes.sourceInputSha256(sql)))),
                List.of(rollbackRef));
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-ROLLBACK-MISSING\""), json);
            assertTrue(json.contains("Rollback script titan-rollback.postgresql.sql referenced by the manifest is missing"), json);
        }
    }

    @Test
    void modifiedSourceInputsHashReportsDeterministicDrift() throws Exception {
        Path artifactRoot = Files.createTempDirectory("titan-gap005-source-inputs-drift-artifact");
        Files.createDirectories(artifactRoot.resolve("postgresql"));
        String sql = """
                CREATE OR REPLACE FUNCTION public.titan_gap005_probe(input_value integer)
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT input_value + 1
                $$;
                """;
        Files.writeString(artifactRoot.resolve("postgresql/titan_gap005_probe.sql"), sql, StandardCharsets.UTF_8);

        TitanObjectInventory inventory = inventory("postgresql", "public", "(input_value integer)", sql);
        TitanArtifactManifest manifest = manifest(
                "postgresql",
                inventory,
                "(input_value integer)",
                "integer",
                sql,
                sha("stale-source-inputs"));
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    manifest,
                    inventory,
                    installPlan,
                    artifactRoot,
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql",
                            POSTGRES.getDockerImageName(),
                            connection));

            assertEquals("failed", report.status(), report.toJson());
            String json = report.toJson();
            assertTrue(json.contains("\"check\": \"sourceInputsSha256\""), json);
            assertTrue(json.contains("\"code\": \"TITAN-GAP005-SOURCE-INPUTS-DRIFT\""), json);
            assertEquals(json, Files.readString(artifactRoot.resolve("titan-install-verification.json"), StandardCharsets.UTF_8));
        }
    }

    private static TitanObjectInventory inventory(String dialect, String schema, String signature, String sql) {
        TitanObjectInventory inventory = new TitanObjectInventory(
                TitanObjectInventory.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.verifier-test",
                List.of(new TitanObjectInventory.GeneratedObject(
                        dialect + "." + schema + ".titan_gap005_probe.function",
                        dialect,
                        "function",
                        schema,
                        "titan_gap005_probe",
                        signature,
                        dialect + "/titan_gap005_probe.sql",
                        "demo.Gap005Kernel.probe()",
                        "invoker",
                        0,
                        List.of(),
                        TitanArtifactHashes.objectSqlSha256(sql))),
                new TitanObjectInventory.InventoryHashes(TitanObjectInventory.INVENTORY_CONTENT_HASH_PLACEHOLDER));
        return inventory.withInventoryContentSha256(inventory.canonicalContentHash());
    }

    private static TitanArtifactManifest manifest(
            String dialect,
            TitanObjectInventory inventory,
            String signature,
            String returnType,
            String sql
    ) {
        return manifest(dialect, "direct", inventory, signature, returnType, sql);
    }

    private static TitanArtifactManifest manifest(
            String dialect,
            String packageMode,
            TitanObjectInventory inventory,
            String signature,
            String returnType,
            String sql
    ) {
        List<TitanArtifactManifest.SourceInput> sourceInputs = List.of(new TitanArtifactManifest.SourceInput(
                dialect,
                dialect + "/titan_gap005_probe.sql",
                TitanArtifactHashes.sourceInputSha256(sql)));
        return manifest(
                dialect,
                packageMode,
                inventory,
                signature,
                returnType,
                sql,
                TitanArtifactHashes.sourceInputsSha256(sourceInputs));
    }

    private static TitanArtifactManifest manifest(
            String dialect,
            TitanObjectInventory inventory,
            String signature,
            String returnType,
            String sql,
            String sourceInputsSha256
    ) {
        return manifest(dialect, "direct", inventory, signature, returnType, sql, sourceInputsSha256);
    }

    private static TitanArtifactManifest manifest(
            String dialect,
            String packageMode,
            TitanObjectInventory inventory,
            String signature,
            String returnType,
            String sql,
            String sourceInputsSha256
    ) {
        return manifest(dialect, packageMode, inventory, signature, returnType, sql, sourceInputsSha256, List.of());
    }

    private static TitanArtifactManifest manifest(
            String dialect,
            String packageMode,
            TitanObjectInventory inventory,
            String signature,
            String returnType,
            String sql,
            String sourceInputsSha256,
            List<TitanArtifactManifest.RollbackScript> rollbackScripts
    ) {
        TitanArtifactManifest manifest = new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                inventory.artifactId(),
                "test",
                packageMode,
                List.of(dialect),
                List.of(new TitanArtifactManifest.SourceInput(
                        dialect,
                        dialect + "/titan_gap005_probe.sql",
                        TitanArtifactHashes.sourceInputSha256(sql))),
                List.of(new TitanArtifactManifest.EntryPoint(
                        "demo.Gap005Kernel.probe(int)",
                        new TitanArtifactManifest.JavaEntryPoint(
                                "demo.Gap005Kernel",
                                "probe",
                                List.of("int"),
                                "StoredFunction",
                                new TitanArtifactManifest.SourceLocation("src/test/demo/Gap005Kernel.java", 1)),
                        List.of(new TitanArtifactManifest.SqlEntryPoint(
                                dialect,
                                inventory.objects().get(0).id(),
                                "titan_gap005_probe",
                                "function",
                                List.of(new TitanArtifactManifest.SqlParameter(0, "input_value", signatureParameterType(signature))),
                                returnType,
                                dialect + "/titan_gap005_probe.sql")),
                        "invoker")),
                List.of(new TitanArtifactManifest.GeneratedObjectReference(inventory.objects().get(0).id(), "inventoried")),
                rollbackScripts,
                new TitanArtifactManifest.ManifestHashes(sourceInputsSha256, TitanArtifactManifest.MANIFEST_CONTENT_HASH_PLACEHOLDER),
                new TitanArtifactManifest.Validation("generated", List.of()));
        return manifest.withManifestContentSha256(manifest.canonicalContentHash());
    }

    private static String signatureParameterType(String signature) {
        return signature.substring(signature.indexOf(' ') + 1, signature.length() - 1);
    }

    private static String sha(String value) {
        return TitanArtifactManifest.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

}
