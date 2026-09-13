package io.titan.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TestKit coverage for the Phase 4.1 plugin modernization (audit G-5, G-9, S8):
 * configuration-cache reuse, build-cache relocatability, recovery after output deletion,
 * generated-source wiring, JDBC driver provisioning errors, and the DDL-mode end-to-end
 * four-task chain.
 */
class TitanGradlePluginFunctionalTest {

    @TempDir
    Path tempDir;

    private static final String DDL = """
            CREATE TABLE public.accounts (
              id INTEGER NOT NULL,
              email VARCHAR(255),
              PRIMARY KEY (id)
            );
            """;

    private static final String ENTRY_POINT = """
            package demo;

            import titan.dsl.StoredProcedure;

            public class EntryPoints {
                @StoredProcedure
                public static void syncAccounts() {
                }
            }
            """;

    private static final Path CATALOG_DESCRIPTOR =
            Path.of("build/generated/sources/titan/generated/titan/public_/tables/Accounts.java");

    @Test
    void kotlinNestedConfigurationBuildsTheDocumentedPipeline() throws Exception {
        Path projectDir = writeDdlFixture(tempDir.resolve("kotlin"), "");
        Files.delete(projectDir.resolve("build.gradle"));
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.titan.gradle")
                }
                dependencies { implementation(files("%s")) }
                titan {
                    database {
                        ddlDir.set("ddl")
                        ddlMode.set("parser")
                        dialect.set("postgresql")
                    }
                    catalog { targetPackage.set("generated.titan") }
                    transpiler {
                        targets.set(listOf("postgresql"))
                        sqlSafety.set("strict")
                    }
                    deployment { mode.set("migration") }
                    verification {
                        dialect.set("postgresql")
                        failOnVerificationError.set(true)
                    }
                }
                """.formatted(titanDslClasspath()), StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "titanPackage").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":titanPackage").getOutcome());
        assertTrue(Files.isRegularFile(projectDir.resolve(CATALOG_DESCRIPTOR)));
        assertTrue(containsSqlFile(projectDir.resolve("build/generated/sql/titan/postgresql")));
        assertFalse(Files.exists(projectDir.resolve("build/generated/sql/titan/mysql")),
                "the Kotlin transpiler action must override the default dual-target setting");
    }

    @Test
    void endToEndFourTaskChainFromDdlFixture() throws Exception {
        Path projectDir = writeDdlFixture(tempDir.resolve("e2e"), "");

        BuildResult first = runner(projectDir, "titanPackage").build();
        for (String task : List.of(":titanIntrospect", ":titanGenerate", ":compileJava", ":titanTranspile", ":titanPackage")) {
            assertEquals(TaskOutcome.SUCCESS, first.task(task).getOutcome(), task + " on first build");
        }

        assertTrue(Files.exists(projectDir.resolve("build/titan/schema.json")));
        assertTrue(Files.exists(projectDir.resolve(CATALOG_DESCRIPTOR)));
        assertTrue(containsSqlFile(projectDir.resolve("build/generated/sql/titan/postgresql")),
                "transpiled PostgreSQL routines expected");
        assertTrue(containsSqlFile(projectDir.resolve("build/generated/sql/titan/mysql")),
                "transpiled MySQL routines expected");
        assertTrue(Files.exists(projectDir.resolve("build/titan/migrations/postgresql/R__titan_020_routines.sql")));
        assertTrue(Files.exists(projectDir.resolve("build/titan/migrations/titan-artifact.json")));
        assertTrue(Files.notExists(projectDir.resolve("src/main/resources/db/migration")),
                "GAP G-5: the build must not write into the source tree");

        // DDL mode is deterministic: with Gradle owning incrementality the whole chain is
        // UP-TO-DATE on an unchanged rebuild (no falsely rerunning tasks, no homegrown skips).
        BuildResult second = runner(projectDir, "titanPackage").build();
        for (String task : List.of(":titanIntrospect", ":titanGenerate", ":titanTranspile", ":titanPackage")) {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(task).getOutcome(), task + " on unchanged rebuild");
        }
    }

    // GAP G-9: the plugin must be configuration-cache compatible; the second invocation reuses
    // the entry instead of re-running configuration.
    @Test
    void configurationCacheIsStoredAndReused() throws Exception {
        Path projectDir = writeDdlFixture(tempDir.resolve("config-cache"), "");

        BuildResult first = runner(projectDir, "titanPackage", "--configuration-cache").build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored."),
                "first build must store a configuration cache entry:\n" + first.getOutput());

        BuildResult second = runner(projectDir, "titanPackage", "--configuration-cache").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."),
                "second build must reuse the configuration cache:\n" + second.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":titanPackage").getOutcome());
    }

    // GAP S8 regression: with the homegrown fingerprints, deleting an output file left it
    // missing forever because the in-action fingerprint check still matched.
    @Test
    void deletedOutputsAreRegeneratedOnRerun() throws Exception {
        Path projectDir = writeDdlFixture(tempDir.resolve("recovery"), "");

        runner(projectDir, "titanTranspile").build();

        Path descriptor = projectDir.resolve(CATALOG_DESCRIPTOR);
        assertTrue(Files.exists(descriptor));
        Files.delete(descriptor);

        BuildResult generateRerun = runner(projectDir, "titanGenerate").build();
        assertEquals(TaskOutcome.SUCCESS, generateRerun.task(":titanGenerate").getOutcome(),
                "deleting an output must take the task out of UP-TO-DATE");
        assertTrue(Files.exists(descriptor), "the deleted catalog source must be regenerated");

        Path sqlFile;
        try (Stream<Path> sql = Files.list(projectDir.resolve("build/generated/sql/titan/postgresql"))) {
            sqlFile = sql.filter(path -> path.getFileName().toString().endsWith(".sql")).findFirst().orElseThrow();
        }
        Files.delete(sqlFile);

        BuildResult transpileRerun = runner(projectDir, "titanTranspile").build();
        assertEquals(TaskOutcome.SUCCESS, transpileRerun.task(":titanTranspile").getOutcome(),
                "deleting a SQL artifact must take the task out of UP-TO-DATE");
        assertTrue(Files.exists(sqlFile), "the deleted SQL artifact must be regenerated");
    }

    // GAP S8: @CacheableTask with relative path sensitivity makes outputs relocatable — the same
    // inputs in a different checkout directory must be served FROM-CACHE.
    @Test
    void buildCacheIsRelocatableAcrossCheckouts() throws Exception {
        Path cacheDir = Files.createDirectories(tempDir.resolve("shared-build-cache"));
        String settingsExtra = """

                buildCache {
                    local {
                        directory = file('%s')
                    }
                }
                """.formatted(cacheDir.toAbsolutePath().toString().replace("\\", "\\\\"));

        Path checkoutA = writeDdlFixture(tempDir.resolve("checkout-a"), settingsExtra);
        Path checkoutB = writeDdlFixture(tempDir.resolve("checkout-b"), settingsExtra);

        BuildResult populate = runner(checkoutA, "titanTranspile", "--build-cache").build();
        assertEquals(TaskOutcome.SUCCESS, populate.task(":titanTranspile").getOutcome());

        BuildResult relocated = runner(checkoutB, "titanTranspile", "--build-cache").build();
        for (String task : List.of(":titanIntrospect", ":titanGenerate", ":titanTranspile")) {
            assertEquals(TaskOutcome.FROM_CACHE, relocated.task(task).getOutcome(),
                    task + " must be relocatable across checkout directories");
        }
    }

    // The introspection tasks resolve JDBC drivers from the 'titanJdbc' configuration; a missing
    // driver must produce an actionable message, not a raw "No suitable driver" SQLException.
    @Test
    void missingJdbcDriverFailureIsActionable() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("missing-driver"));
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'fixture'\n", StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.titan.gradle'
                }

                titan {
                    database.jdbcUrl = 'jdbc:postgresql://db.example.invalid:5432/app'
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "titanIntrospect").buildAndFail();

        String output = result.getOutput();
        assertTrue(output.contains("No JDBC driver accepts URL 'jdbc:postgresql://db.example.invalid:5432/app'"),
                "must explain the missing driver, was:\n" + output);
        assertTrue(output.contains("titanJdbc(\"org.postgresql:postgresql:"),
                "must suggest the exact titanJdbc dependency to add, was:\n" + output);
    }

    // GAP G-5: generated catalog sources are wired into sourceSets.main.java via the task's
    // output property, so a consumer compiles against the catalog with the implicit
    // titanGenerate dependency — no manual wiring.
    @Test
    void consumerCompilesAgainstGeneratedCatalogSources() throws Exception {
        Path projectDir = writeDdlFixture(tempDir.resolve("consumer"), "");
        Path consumerSource = projectDir.resolve("src/main/java/demo/UsesCatalog.java");
        Files.writeString(consumerSource, """
                package demo;

                public class UsesCatalog {
                    static final Class<?> CATALOG_TABLE = generated.titan.public_.tables.Accounts.class;
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "compileJava").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":titanGenerate").getOutcome(),
                "compileJava must trigger titanGenerate through the srcDir(taskProvider) wiring");
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("build/classes/java/main/demo/UsesCatalog.class")));
        assertTrue(Files.exists(projectDir.resolve("build/classes/java/main/generated/titan/public_/tables/Accounts.class")),
                "the generated catalog descriptor must be compiled into the main classes output");
    }

    // B-7 (TG-BLK-001): `titan { transpiler { strictWraparound.set(true) } }` is plumbed through to
    // the pipeline's wraparound flag end-to-end. The flag's effect is observable in the emitted SQL:
    // int-typed `a + b` is rewritten to the java_int_add runtime helper only when the flag is set.
    @Test
    void strictWraparoundFlagReachesPipelineThroughExtension() throws Exception {
        // Default (flag unset): int + stays a plain SQL `+`.
        Path defaultDir = writeWraparoundFixture(tempDir.resolve("wrap-default"), "");
        BuildResult defaultBuild = runner(defaultDir, "titanTranspile").build();
        assertEquals(TaskOutcome.SUCCESS, defaultBuild.task(":titanTranspile").getOutcome());
        assertFalse(wrapAddSql(defaultDir).contains("java_int_add"),
                "without strictWraparound, int + must stay a plain SQL `+`");

        // strictWraparound = true: int + is rewritten to the java_int_add runtime helper.
        Path strictDir = writeWraparoundFixture(
                tempDir.resolve("wrap-strict"),
                "transpiler.strictWraparound = true");
        BuildResult strictBuild = runner(strictDir, "titanTranspile").build();
        assertEquals(TaskOutcome.SUCCESS, strictBuild.task(":titanTranspile").getOutcome());
        assertTrue(wrapAddSql(strictDir).contains("java_int_add"),
                "strictWraparound=true must reach the pipeline and rewrite int + to java_int_add");
    }

    private GradleRunner runner(Path projectDir, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private Path writeDdlFixture(Path projectDir, String settingsExtra) throws Exception {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n" + settingsExtra, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.titan.gradle'
                }

                dependencies {
                    implementation files('%s')
                }

                titan {
                    database.ddlDir = 'ddl'
                    // Fixtures stay hermetic (no Docker, no network): use the documented
                    // no-Docker fallback parser instead of the default scratch-container mode.
                    database.ddlMode = 'parser'
                    database.dialect = 'postgresql'
                }
                """.formatted(titanDslClasspath()), StandardCharsets.UTF_8);

        Path ddlDir = Files.createDirectories(projectDir.resolve("ddl"));
        Files.writeString(ddlDir.resolve("schema.sql"), DDL, StandardCharsets.UTF_8);

        Path sourceDir = Files.createDirectories(projectDir.resolve("src/main/java/demo"));
        Files.writeString(sourceDir.resolve("EntryPoints.java"), ENTRY_POINT, StandardCharsets.UTF_8);

        // Generated row records import org.jspecify.annotations.Nullable; a real consumer adds
        // org.jspecify:jspecify from Maven Central, but the fixture stays hermetic (no network)
        // with a source-compatible stub.
        Path jspecifyDir = Files.createDirectories(projectDir.resolve("src/main/java/org/jspecify/annotations"));
        Files.writeString(jspecifyDir.resolve("Nullable.java"), """
                package org.jspecify.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE_USE)
                public @interface Nullable {
                }
                """, StandardCharsets.UTF_8);
        return projectDir;
    }

    /**
     * The compiled titan-dsl classes from this build, handed to the fixture as a plain file
     * dependency so the generated catalog sources (which import {@code titan.dsl.*}) and the
     * annotated entry points compile inside the TestKit build.
     */
    private static String titanDslClasspath() throws Exception {
        Path location = Path.of(Class.forName("titan.dsl.StoredProcedure")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        return location.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static boolean containsSqlFile(Path dialectDir) throws IOException {
        if (!Files.isDirectory(dialectDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dialectDir)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".sql"));
        }
    }

    // A wraparound fixture: the standard hermetic DDL project plus a @StoredFunction whose body is
    // int-typed `a + b`, so the emitted PostgreSQL SQL contains java_int_add iff strictWraparound is
    // on. `extraTitanConfig` is an extra line injected inside the titan { } block (flat property-path
    // style, matching `database.ddlDir = 'ddl'`), e.g. `transpiler.strictWraparound = true`.
    private Path writeWraparoundFixture(Path projectDir, String extraTitanConfig) throws Exception {
        Path dir = writeDdlFixture(projectDir, "");

        // The DDL fixture's build.gradle ends its titan { } block with `}` on its own line; inject
        // the extra config just before that closing brace so it lands inside the block.
        if (!extraTitanConfig.isEmpty()) {
            String buildScript = Files.readString(dir.resolve("build.gradle"), StandardCharsets.UTF_8);
            int titanBlockClose = buildScript.lastIndexOf("\n}");
            String injected = buildScript.substring(0, titanBlockClose)
                    + "\n    " + extraTitanConfig
                    + buildScript.substring(titanBlockClose);
            Files.writeString(dir.resolve("build.gradle"), injected, StandardCharsets.UTF_8);
        }

        Path sourceDir = dir.resolve("src/main/java/demo");
        Files.writeString(sourceDir.resolve("Arithmetic.java"), """
                package demo;

                import titan.dsl.StoredFunction;

                public class Arithmetic {
                    @StoredFunction
                    public static int wrapAdd(int a, int b) {
                        return a + b;
                    }
                }
                """, StandardCharsets.UTF_8);
        return dir;
    }

    private static String wrapAddSql(Path projectDir) throws IOException {
        Path dialectDir = projectDir.resolve("build/generated/sql/titan/postgresql");
        try (Stream<Path> files = Files.list(dialectDir)) {
            Path wrapAdd = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> path.getFileName().toString().contains("wrapAdd"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no wrapAdd SQL artifact in " + dialectDir));
            return Files.readString(wrapAdd, StandardCharsets.UTF_8);
        }
    }
}
