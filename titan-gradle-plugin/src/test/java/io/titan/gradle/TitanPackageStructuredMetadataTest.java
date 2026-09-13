package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 4.4 (audit G-10): packaging consumes the transpiler's structured metadata. These tests
 * pin the behaviors the old CREATE-regex packager got wrong: triggers and events missing from
 * the inventory, name-substring false dependency edges, and {@code NUMERIC(38,10)} signatures
 * split on the embedded comma.
 */
class TitanPackageStructuredMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void inventoryContainsTriggerAndEventObjects() throws Exception {
        Path output = transpileAndPackage("kinds", """
                package demo;

                import titan.dsl.ScheduledJob;
                import titan.dsl.StoredFunction;
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerTiming;

                class AccountKernel {
                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = { TriggerEvent.INSERT })
                    public static void auditAccounts() {
                    }

                    @ScheduledJob(cron = "15 4 * * *", name = "nightly_refresh")
                    public static void refreshNightly() {
                    }
                }
                """);

        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        // The old CREATE-regex never matched CREATE TRIGGER or CREATE EVENT: these objects were
        // silently absent from the inventory. They are first-class now.
        assertTrue(inventory.contains("\"id\": \"postgresql.public.audit_accounts_trg.trigger\""), inventory);
        assertTrue(inventory.contains("\"id\": \"postgresql.public.audit_accounts_fn.function\""), inventory);
        assertTrue(inventory.contains("\"id\": \"mysql.public.audit_accounts_insert_trg.trigger\""), inventory);
        assertTrue(inventory.contains("\"id\": \"mysql.public.nightly_refresh.event\""), inventory);
        assertTrue(inventory.contains("\"id\": \"mysql.public.refresh_nightly.procedure\""), inventory);
        // The PostgreSQL trigger depends on its trigger function (same artifact, created first).
        assertTrue(inventory.contains("\"dependsOn\": [\"postgresql.public.audit_accounts_fn.function\"]"), inventory);
        // The MySQL event depends on the procedure it schedules.
        assertTrue(inventory.contains("\"dependsOn\": [\"mysql.public.refresh_nightly.procedure\"]"), inventory);

        String installPlan = Files.readString(output.resolve("titan-install-plan.json"), StandardCharsets.UTF_8);
        assertTrue(installPlan.contains("\"id\": \"create.postgresql.public.audit_accounts_trg.trigger\""), installPlan);
        assertTrue(installPlan.contains("\"id\": \"create.mysql.public.nightly_refresh.event\""), installPlan);
    }

    @Test
    void nameContainmentNoLongerCreatesDependencyEdges() throws Exception {
        Path output = transpileAndPackage("false-edge", """
                package demo;

                import titan.dsl.StoredFunction;

                class UserKernel {
                    @StoredFunction
                    public static long getUser(long id) {
                        return id;
                    }

                    @StoredFunction
                    public static long getUserOrders(long userId) {
                        return userId * 10;
                    }
                }
                """);

        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"name\": \"get_user\""), inventory);
        assertTrue(inventory.contains("\"name\": \"get_user_orders\""), inventory);
        // The old packager inferred get_user_orders -> get_user purely from the name substring;
        // such phantom edges could even fail TitanInstallPlan with spurious cycles. The
        // structured inventory has no edges between routines that never call each other.
        assertFalse(inventory.contains("\"dependsOn\": [\"postgresql.public.get_user.function\"]"), inventory);
        assertFalse(inventory.contains("\"dependsOn\": [\"mysql.public.get_user.function\"]"), inventory);
        // And the install plan builds cleanly from the edge-free inventory.
        assertTrue(Files.exists(output.resolve("titan-install-plan.json")));
    }

    @Test
    void manifestKeepsParenthesizedNumericParameterIntact() throws Exception {
        Path output = transpileAndPackage("numeric", """
                package demo;

                import java.math.BigDecimal;
                import titan.dsl.StoredFunction;

                class PricingKernel {
                    @StoredFunction
                    public static BigDecimal applyDiscount(BigDecimal amount, long percent) {
                        return amount;
                    }
                }
                """);

        String manifest = Files.readString(output.resolve("titan-artifact.json"), StandardCharsets.UTF_8);
        // The old sqlParameters split on every comma, shearing NUMERIC(38,10) into two bogus
        // parameters ("p_amount NUMERIC(38" and "10)"). The typed metadata keeps it whole.
        assertTrue(manifest.contains("\"name\": \"p_amount\""), manifest);
        assertTrue(manifest.contains("\"type\": \"NUMERIC(38,10)\""), manifest);
        assertTrue(manifest.contains("\"type\": \"DECIMAL(38,10)\""), manifest);
        assertFalse(manifest.contains("\"type\": \"NUMERIC(38\""), manifest);
        assertFalse(manifest.contains("\"name\": \"10)\""), manifest);

        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"signature\": \"(p_amount NUMERIC(38,10), p_percent BIGINT)\""), inventory);
    }

    @Test
    void rollbackScriptsDropEveryObjectInReverseDependencyOrder() throws Exception {
        Path output = transpileAndPackage("rollback", """
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
                """);

        String postgres = Files.readString(output.resolve("titan-rollback.postgresql.sql"), StandardCharsets.UTF_8);
        // Kind- and dialect-correct DROPs with typed argument lists.
        assertTrue(postgres.contains("DROP FUNCTION IF EXISTS \"public\".\"get_user\"(BIGINT);"), postgres);
        assertTrue(postgres.contains("DROP FUNCTION IF EXISTS \"public\".\"get_user_orders\"(BIGINT);"), postgres);
        assertTrue(postgres.contains("DROP PROCEDURE IF EXISTS \"public\".\"refresh_nightly\"();"), postgres);
        assertTrue(postgres.contains("DROP TYPE IF EXISTS \"public\".\"__record_price_tier\";"), postgres);
        assertTrue(postgres.contains("DROP TABLE IF EXISTS \"titan_runtime\".\"telemetry\";"), postgres);
        // Reverse dependency order: the caller drops before its callee, routines before the
        // record type they use, and the runtime schema goes last.
        assertTrue(postgres.indexOf("\"get_user_orders\"") < postgres.indexOf("DROP FUNCTION IF EXISTS \"public\".\"get_user\"("), postgres);
        assertTrue(postgres.indexOf("\"tier_label\"") < postgres.indexOf("DROP TYPE IF EXISTS \"public\".\"__record_price_tier\""), postgres);
        assertTrue(postgres.trim().endsWith("DROP SCHEMA IF EXISTS \"titan_runtime\";"), postgres);

        String mysql = Files.readString(output.resolve("titan-rollback.mysql.sql"), StandardCharsets.UTF_8);
        assertTrue(mysql.contains("DROP FUNCTION IF EXISTS `public`.`get_user`;"), mysql);
        assertTrue(mysql.contains("DROP EVENT IF EXISTS `nightly_refresh`;"), mysql);
        assertTrue(mysql.contains("DROP PROCEDURE IF EXISTS `public`.`refresh_nightly`;"), mysql);
        assertTrue(mysql.contains("DROP FUNCTION IF EXISTS `titan_rt_java_mod`;"), mysql);
        assertTrue(mysql.contains("DROP TABLE IF EXISTS `titan_runtime`.`telemetry`;"), mysql);
        // The event drops before the procedure it schedules.
        assertTrue(mysql.indexOf("DROP EVENT IF EXISTS `nightly_refresh`;")
                < mysql.indexOf("DROP PROCEDURE IF EXISTS `public`.`refresh_nightly`;"), mysql);
    }

    @Test
    void manifestIntegrityLinksRollbackScriptPerDialect() throws Exception {
        // B-5 (TG-BLK-008): the manifest must reference each titan-rollback.<dialect>.sql by
        // reproducible relative path, statement count and sha256 of the script's raw bytes — so
        // a consumer integrity-links the rollback instead of trusting the filename convention.
        Path output = transpileAndPackage("rollback-ref", """
                package demo;

                import titan.dsl.StoredFunction;

                class RollbackRefKernel {
                    @StoredFunction
                    public static long getUser(long id) {
                        return id;
                    }

                    @StoredFunction
                    public static long getUserOrders(long userId) {
                        return getUser(userId) * 10;
                    }
                }
                """);

        String manifest = Files.readString(output.resolve("titan-artifact.json"), StandardCharsets.UTF_8);
        // The additive field is present and the schema string is unchanged (additive proof: the
        // GAP-005 adapter still requires exactly titan.artifact.v1).
        assertTrue(manifest.contains("\"schemaVersion\": \"titan.artifact.v1\""), manifest);
        assertTrue(manifest.contains("\"rollbackScripts\": ["), manifest);

        for (String dialect : List.of("postgresql", "mysql")) {
            String fileName = "titan-rollback." + dialect + ".sql";
            byte[] scriptBytes = Files.readAllBytes(output.resolve(fileName));
            String expectedSha256 = TitanArtifactManifest.sha256Hex(scriptBytes);
            int expectedStatements = TitanRollbackScript.statementCount(
                    new String(scriptBytes, StandardCharsets.UTF_8));

            // Reproducible relative path (no dialect directory: rollback lives at the package root).
            assertTrue(manifest.contains("\"path\": \"" + fileName + "\""), manifest);
            // sha256 of the raw script bytes (no SQL normalization) so a consumer hashing the
            // file content matches exactly.
            assertTrue(manifest.contains("\"sha256\": \"" + expectedSha256 + "\""),
                    dialect + " rollback sha256 " + expectedSha256 + " missing from:\n" + manifest);
            assertTrue(manifest.contains("\"statementCount\": " + expectedStatements),
                    dialect + " rollback statementCount " + expectedStatements + " missing from:\n" + manifest);
            assertTrue(expectedStatements > 0,
                    dialect + " rollback script should drop at least one object");
        }
        // Reproducible ordering: rollback entries are sorted by dialect (mysql before postgresql),
        // matching the stable ordering of the manifest's other arrays.
        assertTrue(manifest.indexOf("\"path\": \"titan-rollback.mysql.sql\"")
                        < manifest.indexOf("\"path\": \"titan-rollback.postgresql.sql\""),
                "rollback entries must be ordered by dialect:\n" + manifest);
    }

    @Test
    void enumAccessorNamedAfterBackingFieldPackagesCleanly() throws Exception {
        // B-4 (TG-BLK-004): a no-arg enum method named exactly after its backing field (the
        // record-style accessor default) used to emit a byte-identical duplicate accessor AND
        // describe a duplicate inventory object — the inventory's unique-id gate then failed
        // the whole package. The deduplicated pipeline must transpile AND package cleanly,
        // with exactly one inventory object per accessor id for each spelling style.
        Path output = transpileAndPackage("enum-accessor", """
                package demo;

                import titan.dsl.StoredFunction;

                class ChannelKernel {
                    enum Channel {
                        EMAIL("smtp", true), SMS("gateway", false);
                        final String transport;
                        final boolean retryable;
                        Channel(String transport, boolean retryable) {
                            this.transport = transport;
                            this.retryable = retryable;
                        }
                        String transport() { return transport; }
                        boolean isRetryable() { return retryable; }
                    }

                    @StoredFunction
                    public static String transportOf(Channel channel) {
                        return channel.transport();
                    }
                }
                """);

        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        for (String dialect : List.of("postgresql", "mysql")) {
            // Field-named accessor: exactly one object id (the duplicate failed the gate).
            assertEquals(1, countOccurrences(inventory,
                            "\"id\": \"" + dialect + ".public.__enum_channel_kernel_channel__transport.function\""),
                    dialect + " transport accessor must appear exactly once in:\n" + inventory);
            // get/is-style accessor: its own object alongside the field-derived one.
            assertEquals(1, countOccurrences(inventory,
                            "\"id\": \"" + dialect + ".public.__enum_channel_kernel_channel__retryable.function\""),
                    dialect + " retryable field accessor must appear exactly once in:\n" + inventory);
            assertEquals(1, countOccurrences(inventory,
                            "\"id\": \"" + dialect + ".public.__enum_channel_kernel_channel__is_retryable.function\""),
                    dialect + " isRetryable accessor must appear exactly once in:\n" + inventory);
        }
        assertTrue(Files.exists(output.resolve("titan-install-plan.json")));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private Path transpileAndPackage(String name, String entryPointSource) throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks()
                .create("structuredMetadataTranspile" + name.replaceAll("[^A-Za-z0-9]", ""), TitanTranspileTask.class);
        TitanPackageTask packageTask = project.getTasks()
                .create("structuredMetadataPackage" + name.replaceAll("[^A-Za-z0-9]", ""), TitanPackageTask.class);

        Path sourceDir = tempDir.resolve(name + "-src");
        writeDslStubs(sourceDir);
        Path entryPoint = sourceDir.resolve("demo/Fixture.java");
        Files.createDirectories(entryPoint.getParent());
        Files.writeString(entryPoint, entryPointSource, StandardCharsets.UTF_8);

        Path generatedSql = tempDir.resolve(name + "-generated-sql");
        transpile.getSourceFiles().from(
                sourceDir.resolve("titan/dsl/StoredFunction.java").toFile(),
                sourceDir.resolve("titan/dsl/Trigger.java").toFile(),
                sourceDir.resolve("titan/dsl/TriggerTiming.java").toFile(),
                sourceDir.resolve("titan/dsl/TriggerEvent.java").toFile(),
                sourceDir.resolve("titan/dsl/TriggerForEach.java").toFile(),
                sourceDir.resolve("titan/dsl/ScheduledJob.java").toFile(),
                entryPoint.toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(generatedSql.toFile());
        transpile.run();

        Path output = tempDir.resolve(name + "-package");
        packageTask.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        packageTask.getMode().set("migration");
        packageTask.getTitanVersion().set("4.4-test");
        packageTask.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));
        packageTask.run();
        return output;
    }

    private static void writeDslStubs(Path sourceDir) throws Exception {
        Path dslDir = sourceDir.resolve("titan/dsl");
        Files.createDirectories(dslDir);
        Files.writeString(dslDir.resolve("StoredFunction.java"), """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredFunction {}
                """, StandardCharsets.UTF_8);
        Files.writeString(dslDir.resolve("TriggerTiming.java"), """
                package titan.dsl;

                public enum TriggerTiming { BEFORE, AFTER }
                """, StandardCharsets.UTF_8);
        Files.writeString(dslDir.resolve("TriggerEvent.java"), """
                package titan.dsl;

                public enum TriggerEvent { INSERT, UPDATE, DELETE }
                """, StandardCharsets.UTF_8);
        Files.writeString(dslDir.resolve("TriggerForEach.java"), """
                package titan.dsl;

                public enum TriggerForEach { ROW, STATEMENT }
                """, StandardCharsets.UTF_8);
        Files.writeString(dslDir.resolve("Trigger.java"), """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface Trigger {
                    String table();

                    TriggerTiming timing();

                    TriggerEvent[] event();

                    TriggerForEach forEach() default TriggerForEach.ROW;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dslDir.resolve("ScheduledJob.java"), """
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
    }
}
