package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 4.4 (audit G-10) end-to-end: transpile → package → install-verify on live containers →
 * execute the generated rollback script → every Titan-generated object is gone. Both dialects.
 */
@Testcontainers
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class TitanVerifyAndRollbackIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0");

    @TempDir
    Path tempDir;

    @Test
    void deployVerifyAndRollBackOnPostgres() throws Exception {
        Packaged packaged = transpileAndPackage("pg");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    packaged.artifacts().manifest(),
                    packaged.artifacts().inventory(),
                    packaged.artifacts().installPlan(),
                    packaged.outputRoot(),
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "postgresql", POSTGRES.getDockerImageName(), connection));
            assertEquals("passed", report.status(), report.toJson());
            assertTrue(report.toJson().contains("\"checks\": [\"exists\", \"installedDefinitionObserved\", \"signatureMatches\"]"),
                    report.toJson());

            // B-5: the real package's manifest integrity-links the real rollback script, and the
            // passing verification above includes the rollback sha256 check against the file on
            // disk (verifyAndWrite reads the same outputRoot titanPackage wrote).
            String manifestJson = packaged.artifacts().manifest().toJson();
            assertTrue(manifestJson.contains("\"path\": \"titan-rollback.postgresql.sql\""), manifestJson);
            String rollbackOnDisk = Files.readString(
                    packaged.outputRoot().resolve("titan-rollback.postgresql.sql"), StandardCharsets.UTF_8);
            assertTrue(manifestJson.contains("\"sha256\": \""
                    + TitanArtifactManifest.sha256Hex(rollbackOnDisk.getBytes(StandardCharsets.UTF_8)) + "\""),
                    manifestJson);

            assertEquals(1, countPostgresRoutines(connection, "get_user_orders"));
            assertEquals(1, countPostgresRoutines(connection, "tier_label"));
            assertEquals(1, countPostgresType(connection, "__record_price_tier"));
            assertEquals(1, countPostgresRuntimeSchema(connection));

            executeScript(connection, packaged.outputRoot().resolve("titan-rollback.postgresql.sql"));

            assertEquals(0, countPostgresRoutines(connection, "get_user"));
            assertEquals(0, countPostgresRoutines(connection, "get_user_orders"));
            assertEquals(0, countPostgresRoutines(connection, "tier_label"));
            assertEquals(0, countPostgresRoutines(connection, "refresh_nightly"));
            assertEquals(0, countPostgresRoutines(connection, "__record_price_tier__new"));
            assertEquals(0, countPostgresType(connection, "__record_price_tier"));
            assertEquals(0, countPostgresRuntimeSchema(connection));
        }
    }

    @Test
    void deployVerifyAndRollBackOnMysql() throws Exception {
        Packaged packaged = transpileAndPackage("mysql");
        // Root so the schemas the package installs into can be provisioned (what
        // titanVerifyInstall's scratch mode does before verifying).
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `public`");
            statement.execute("CREATE DATABASE IF NOT EXISTS `titan_runtime`");

            TitanInstallVerification report = TitanArtifactInstallVerifier.verifyAndWrite(
                    packaged.artifacts().manifest(),
                    packaged.artifacts().inventory(),
                    packaged.artifacts().installPlan(),
                    packaged.outputRoot(),
                    new TitanArtifactInstallVerifier.ScratchDatabase(
                            "mysql", MYSQL.getDockerImageName(), connection));
            assertEquals("passed", report.status(), report.toJson());

            assertEquals(1, countMysqlRoutines(connection, "public", "get_user_orders"));
            assertEquals(1, countMysqlRoutines(connection, "public", "__record_price_tier__new"));
            assertEquals(1, countMysqlRoutines(connection, MYSQL.getDatabaseName(), "titan_rt_java_mod"));
            assertEquals(1, countMysqlEvents(connection, "nightly_refresh"));
            assertEquals(1, countMysqlTables(connection, "titan_runtime", "telemetry"));

            executeScript(connection, packaged.outputRoot().resolve("titan-rollback.mysql.sql"));

            assertEquals(0, countMysqlRoutines(connection, "public", "get_user"));
            assertEquals(0, countMysqlRoutines(connection, "public", "get_user_orders"));
            assertEquals(0, countMysqlRoutines(connection, "public", "tier_label"));
            assertEquals(0, countMysqlRoutines(connection, "public", "refresh_nightly"));
            assertEquals(0, countMysqlRoutines(connection, "public", "__record_price_tier__new"));
            assertEquals(0, countMysqlRoutines(connection, MYSQL.getDatabaseName(), "titan_rt_java_mod"));
            assertEquals(0, countMysqlEvents(connection, "nightly_refresh"));
            assertEquals(0, countMysqlTables(connection, "titan_runtime", "telemetry"));
        }
    }

    private record Packaged(TitanPackagedArtifacts.Result artifacts, Path outputRoot) {
    }

    private Packaged transpileAndPackage(String name) throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks()
                .create("verifyRollbackTranspile" + name, TitanTranspileTask.class);
        TitanPackageTask packageTask = project.getTasks()
                .create("verifyRollbackPackage" + name, TitanPackageTask.class);

        Path sourceDir = tempDir.resolve(name + "-src");
        Path annotationDir = Files.createDirectories(sourceDir.resolve("titan/dsl"));
        Files.writeString(annotationDir.resolve("StoredFunction.java"), """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredFunction {}
                """, StandardCharsets.UTF_8);
        Files.writeString(annotationDir.resolve("ScheduledJob.java"), """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface ScheduledJob {
                    String cron();

                    String name() default "";
                }
                """, StandardCharsets.UTF_8);
        Path entryPoint = sourceDir.resolve("demo/RollbackKernel.java");
        Files.createDirectories(entryPoint.getParent());
        Files.writeString(entryPoint, """
                package demo;

                import titan.dsl.ScheduledJob;
                import titan.dsl.StoredFunction;

                record PriceTier(long threshold, String label) {}

                class RollbackKernel {
                    @StoredFunction
                    public static long getUser(long id) {
                        return id;
                    }

                    @StoredFunction
                    public static long getUserOrders(long userId) {
                        return getUser(userId) * 10;
                    }

                    @StoredFunction
                    public static String tierLabel(long volume) {
                        PriceTier tier = new PriceTier(100L, "bulk");
                        if (volume >= tier.threshold()) {
                            return tier.label();
                        }
                        return "standard";
                    }

                    @ScheduledJob(cron = "15 4 * * *", name = "nightly_refresh")
                    public static void refreshNightly() {
                    }
                }
                """, StandardCharsets.UTF_8);

        Path generatedSql = tempDir.resolve(name + "-generated-sql");
        transpile.getSourceFiles().from(
                annotationDir.resolve("StoredFunction.java").toFile(),
                annotationDir.resolve("ScheduledJob.java").toFile(),
                entryPoint.toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(generatedSql.toFile());
        transpile.run();

        Path outputRoot = tempDir.resolve(name + "-package");
        packageTask.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        packageTask.getMode().set("migration");
        packageTask.getTitanVersion().set("4.4-it");
        packageTask.getOutputDir().set(project.getLayout().dir(project.provider(() -> outputRoot.toFile())));
        packageTask.run();

        Map<String, TitanArtifactMetadataFile.ArtifactRow> rows =
                TitanArtifactMetadataFile.read(generatedSql.resolve(TitanArtifactMetadataFile.FILE_NAME));
        TitanPackagedArtifacts.Result artifacts = TitanPackagedArtifacts.build(
                TitanPackagedArtifacts.collectDialectInputs(generatedSql),
                generatedSql,
                "migration",
                "4.4-it",
                rows);
        return new Packaged(artifacts, outputRoot);
    }

    private static void executeScript(Connection connection, Path script) throws Exception {
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        for (String statementSql : TitanSqlScripts.split(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    private static int countPostgresRoutines(Connection connection, String name) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = 'public' AND routine_name = '"
                        + name + "'");
    }

    private static int countPostgresType(Connection connection, String typeName) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace"
                        + " WHERE n.nspname = 'public' AND t.typname = '" + typeName + "'");
    }

    private static int countPostgresRuntimeSchema(Connection connection) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'titan_runtime'");
    }

    private static int countMysqlRoutines(Connection connection, String schema, String name) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = '" + schema
                        + "' AND routine_name = '" + name + "'");
    }

    private static int countMysqlEvents(Connection connection, String name) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM information_schema.events WHERE event_name = '" + name + "'");
    }

    private static int countMysqlTables(Connection connection, String schema, String table) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                        + "' AND table_name = '" + table + "'");
    }

    private static int count(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
