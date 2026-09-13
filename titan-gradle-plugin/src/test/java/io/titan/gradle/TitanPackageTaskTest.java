package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TitanPackageTaskTest {

    @Test
    void migrationModeBundlesDialectSqlIntoRepeatableMigrations() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageTest", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input");
        Path pgDir = Files.createDirectories(sqlInput.resolve("postgresql"));
        Files.writeString(pgDir.resolve("B.sql"), "SELECT 2;\n", StandardCharsets.UTF_8);
        Files.writeString(pgDir.resolve("A.sql"), "SELECT 1;\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-migration-output");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.2.3");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();

        Path dialectOutput = output.resolve("postgresql");
        assertTrue(Files.exists(dialectOutput));

        List<Path> files;
        try (Stream<Path> stream = Files.list(dialectOutput)) {
            files = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        assertEquals(2, files.size());
        assertEquals("R__titan_010_runtime.sql", files.get(0).getFileName().toString());
        assertEquals("R__titan_020_routines.sql", files.get(1).getFileName().toString());

        String runtime = Files.readString(files.get(0), StandardCharsets.UTF_8);
        assertTrue(runtime.contains("-- titan-runtime-version:1.2.3"));
        assertTrue(runtime.contains("java_mod"));
        assertTrue(runtime.contains("java_round"));

        String bundled = Files.readString(files.get(1), StandardCharsets.UTF_8);
        int indexA = bundled.indexOf("-- titan:source-file:A.sql");
        int indexB = bundled.indexOf("-- titan:source-file:B.sql");
        assertTrue(indexA >= 0);
        assertTrue(indexB > indexA);

        String manifest = Files.readString(output.resolve("titan-artifact.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"schemaVersion\": \"titan.artifact.v1\""));
        assertTrue(manifest.contains("\"artifactId\": \"titan.generated-sql."));
        assertTrue(manifest.contains("\"packageMode\": \"migration\""));
        assertTrue(manifest.contains("\"dialects\": [\"postgresql\"]"));
        assertTrue(manifest.contains("\"path\": \"postgresql/A.sql\""));
        assertTrue(manifest.contains("\"path\": \"postgresql/B.sql\""));
        assertTrue(manifest.contains("\"status\": \"generated\""));
        assertTrue(manifest.contains("\"status\": \"inventoried\""));
        assertTrue(Files.exists(output.resolve("titan-object-inventory.json")));
        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"sourceInputPath\": \"postgresql/R__titan_010_runtime.sql\""));
        assertTrue(inventory.contains("\"schema\": \"titan_runtime\""));
        assertTrue(inventory.contains("\"name\": \"java_mod\""));
        assertTrue(Files.exists(output.resolve("titan-install-plan.json")));
        String installPlan = Files.readString(output.resolve("titan-install-plan.json"), StandardCharsets.UTF_8);
        assertTrue(installPlan.contains("\"schemaVersion\": \"titan.install-plan.v1\""));
        assertTrue(installPlan.contains("\"transactionMode\": \"singleTransaction\""));
        assertTrue(installPlan.contains("\"kind\": \"runtimeBootstrap\""));
        assertTrue(installPlan.contains("\"objectId\": \"postgresql.titan_runtime.java_mod.function\""));
        assertTrue(Files.exists(output.resolve("titan-install-verification.json")));
        String installVerification = Files.readString(output.resolve("titan-install-verification.json"), StandardCharsets.UTF_8);
        assertTrue(installVerification.contains("\"schemaVersion\": \"titan.install-verification.v1\""));
        assertTrue(installVerification.contains("\"status\": \"pending\""));
        assertTrue(installVerification.contains("\"kind\": \"scratch-required\""));
        assertTrue(installVerification.contains("\"code\": \"TITAN-GAP005-VERIFY-PENDING\""));
    }

    @Test
    void directModeCopiesSqlFilesPerDialect() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageDirect", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-direct");
        Path myDir = Files.createDirectories(sqlInput.resolve("mysql"));
        Files.writeString(myDir.resolve("routine.sql"), "SELECT 'ok';\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-direct-output");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("direct");
        task.getTitanVersion().set("1.2.3");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();

        Path copied = output.resolve("mysql").resolve("routine.sql");
        assertTrue(Files.exists(copied));
        assertEquals("SELECT 'ok';\n", Files.readString(copied, StandardCharsets.UTF_8));

        String manifest = Files.readString(output.resolve("titan-artifact.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"packageMode\": \"direct\""));
        assertTrue(manifest.contains("\"dialects\": [\"mysql\"]"));
        assertTrue(manifest.contains("\"path\": \"mysql/routine.sql\""));
        assertTrue(Files.exists(output.resolve("titan-object-inventory.json")));
        String installPlan = Files.readString(output.resolve("titan-install-plan.json"), StandardCharsets.UTF_8);
        assertTrue(installPlan.contains("\"dialect\": \"mysql\""));
        assertTrue(installPlan.contains("\"transactionMode\": \"statementBoundaryDdl\""));
        assertTrue(installPlan.contains("\"kind\": \"createOrReplace\""));
        String installVerification = Files.readString(output.resolve("titan-install-verification.json"), StandardCharsets.UTF_8);
        assertTrue(installVerification.contains("\"dialect\": \"mysql\""));
        assertTrue(installVerification.contains("\"status\": \"pending\""));
    }

    @Test
    void migrationModeSkipsEmptyRoutineMigrationsWhenSqlIsBlank() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageBlankSql", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-blank");
        Path pgDir = Files.createDirectories(sqlInput.resolve("postgresql"));
        Files.writeString(pgDir.resolve("blank.sql"), "   \n\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-migration-output-blank");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.2.3");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();

        Path dialectOutput = output.resolve("postgresql");
        assertTrue(Files.exists(dialectOutput));

        List<String> fileNames;
        try (Stream<Path> stream = Files.list(dialectOutput)) {
            fileNames = stream
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertEquals(List.of("R__titan_010_runtime.sql"), fileNames);
        assertTrue(Files.exists(output.resolve("titan-artifact.json")));
        assertTrue(Files.exists(output.resolve("titan-object-inventory.json")));
        assertTrue(Files.exists(output.resolve("titan-install-plan.json")));
        assertTrue(Files.exists(output.resolve("titan-install-verification.json")));
    }

    @Test
    void packageManifestIsByteStableAcrossRepeatedRuns() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageStableManifest", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-stable-manifest");
        Path pgDir = Files.createDirectories(sqlInput.resolve("postgresql"));
        Path myDir = Files.createDirectories(sqlInput.resolve("mysql"));
        Files.writeString(pgDir.resolve("B.sql"), "SELECT 2;\n", StandardCharsets.UTF_8);
        Files.writeString(pgDir.resolve("A.sql"), "SELECT 1;\n", StandardCharsets.UTF_8);
        Files.writeString(myDir.resolve("routine.sql"), "SELECT 'ok';\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-stable-manifest-output");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.2.3");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();
        byte[] first = Files.readAllBytes(output.resolve("titan-artifact.json"));
        byte[] firstInventory = Files.readAllBytes(output.resolve("titan-object-inventory.json"));
        byte[] firstInstallPlan = Files.readAllBytes(output.resolve("titan-install-plan.json"));
        byte[] firstInstallVerification = Files.readAllBytes(output.resolve("titan-install-verification.json"));

        task.run();
        byte[] second = Files.readAllBytes(output.resolve("titan-artifact.json"));
        byte[] secondInventory = Files.readAllBytes(output.resolve("titan-object-inventory.json"));
        byte[] secondInstallPlan = Files.readAllBytes(output.resolve("titan-install-plan.json"));
        byte[] secondInstallVerification = Files.readAllBytes(output.resolve("titan-install-verification.json"));

        assertArrayEquals(first, second);
        assertArrayEquals(firstInventory, secondInventory);
        assertArrayEquals(firstInstallPlan, secondInstallPlan);
        assertArrayEquals(firstInstallVerification, secondInstallVerification);
    }

    // Plan 4.4 (audit G-10): the inventory is built from the transpiler's structured metadata —
    // record types, typed signatures and record-usage dependency edges come from the pipeline,
    // not from regex over the emitted SQL.
    @Test
    void packageEmitsObjectInventoryWithStableOrderAndDependencies() throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks().create("titanTranspileObjectInventory", TitanTranspileTask.class);
        TitanPackageTask task = project.getTasks().create("titanPackageObjectInventory", TitanPackageTask.class);

        Path sourceDir = Files.createTempDirectory("titan-object-inventory-src");
        Files.createDirectories(sourceDir.resolve("titan/dsl"));
        Files.createDirectories(sourceDir.resolve("demo"));
        Path annotationSource = sourceDir.resolve("titan/dsl/StoredFunction.java");
        Files.writeString(annotationSource, """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredFunction {}
                """, StandardCharsets.UTF_8);
        Path entryPointSource = sourceDir.resolve("demo/CourseBrowseKernel.java");
        Files.writeString(entryPointSource, """
                package demo;

                import titan.dsl.StoredFunction;

                record LessonDescriptor(long id, long courseId, String title) {}

                class CourseBrowseKernel {
                    @StoredFunction
                    public static String visibleLessonTitle(long courseId) {
                        LessonDescriptor[] lessons = new LessonDescriptor[] {
                            new LessonDescriptor(10L, 7L, "Intro"),
                            new LessonDescriptor(11L, 8L, "Workshop")
                        };
                        for (LessonDescriptor lesson : lessons) {
                            if (lesson.courseId() == courseId) {
                                return lesson.title();
                            }
                        }
                        return "none";
                    }
                }
                """, StandardCharsets.UTF_8);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-object-inventory");
        transpile.getSourceFiles().from(annotationSource.toFile(), entryPointSource.toFile());
        transpile.getTargets().set(List.of("postgresql"));
        transpile.getOutputDir().set(sqlInput.toFile());
        transpile.run();

        Path output = Files.createTempDirectory("titan-object-inventory-output");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.2.3");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();

        String manifest = Files.readString(output.resolve("titan-artifact.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"id\": \"postgresql.public.__record_lesson_descriptor.type\""));
        assertTrue(manifest.contains("\"id\": \"postgresql.public.visible_lesson_title.function\""));
        assertTrue(manifest.contains("\"status\": \"inventoried\""));
        assertFalse(manifest.contains("object inventory pending GAP005-M2.1"));

        String inventory = Files.readString(output.resolve("titan-object-inventory.json"), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"schemaVersion\": \"titan.object-inventory.v1\""));
        assertTrue(inventory.contains("\"kind\": \"type\""));
        assertTrue(inventory.contains("\"kind\": \"function\""));
        assertTrue(inventory.contains("\"signature\": \"(p_course_id BIGINT)\""));
        assertTrue(inventory.contains("\"sourceInputPath\": \"postgresql/R__titan_010_runtime.sql\""));
        assertTrue(inventory.contains("\"sourceInputPath\": \"postgresql/demo_CourseBrowseKernel__visibleLessonTitle.sql\""));
        assertTrue(inventory.contains("\"sourceEntryPoint\": \"demo.CourseBrowseKernel.visibleLessonTitle()\""));
        // The routine uses the record type, so the structured edge points at the record's
        // primary object (the composite type on PostgreSQL).
        assertTrue(inventory.contains("\"dependsOn\": [\"postgresql.public.__record_lesson_descriptor.type\"]"), inventory);
        assertTrue(inventory.contains("\"sqlHash\": \""));
        assertTrue(inventory.indexOf("\"name\": \"__record_lesson_descriptor\"")
                < inventory.indexOf("\"name\": \"visible_lesson_title\""), inventory);
        String installPlan = Files.readString(output.resolve("titan-install-plan.json"), StandardCharsets.UTF_8);
        assertTrue(installPlan.contains("\"manifestContentSha256\": \""));
        assertTrue(installPlan.contains("\"inventoryContentSha256\": \""));
        assertTrue(installPlan.contains("\"check\": \"generatedNameCollision\""));
        assertTrue(installPlan.indexOf("\"objectId\": \"postgresql.public.__record_lesson_descriptor.type\"")
                < installPlan.indexOf("\"objectId\": \"postgresql.public.visible_lesson_title.function\""), installPlan);
    }

    @Test
    void runtimeMigrationRegeneratesWhenVersionOrGeneratedContentChanges() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageRuntimeVersion", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-runtime-version");
        Path pgDir = Files.createDirectories(sqlInput.resolve("postgresql"));
        Files.writeString(pgDir.resolve("routine.sql"), "SELECT 1;\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-runtime-output");

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.0.0");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();

        Path runtimeMigration = output.resolve("postgresql").resolve("R__titan_010_runtime.sql");
        String firstWrite = Files.readString(runtimeMigration, StandardCharsets.UTF_8);

        Files.writeString(runtimeMigration, firstWrite + "\n-- manual-local-edit\n", StandardCharsets.UTF_8);
        task.getTitanVersion().set("1.0.0");
        task.run();
        String secondWrite = Files.readString(runtimeMigration, StandardCharsets.UTF_8);
        assertEquals(firstWrite, secondWrite);
        assertFalse(secondWrite.contains("-- manual-local-edit"));

        task.getTitanVersion().set("1.0.1");
        task.run();
        String thirdWrite = Files.readString(runtimeMigration, StandardCharsets.UTF_8);
        assertTrue(thirdWrite.contains("-- titan-runtime-version:1.0.1"));
        assertFalse(thirdWrite.contains("-- manual-local-edit"));
    }

    @Test
    void repackagingChangedRoutinesReplacesBundleAndNeverTouchesVersionedMigrations() throws IOException {
        Project project = ProjectBuilder.builder().build();
        TitanPackageTask task = project.getTasks().create("titanPackageRepackage", TitanPackageTask.class);

        Path sqlInput = Files.createTempDirectory("titan-sql-input-repackage");
        Path pgDir = Files.createDirectories(sqlInput.resolve("postgresql"));
        Path routine = pgDir.resolve("routine.sql");
        Files.writeString(routine, "SELECT 1;\n", StandardCharsets.UTF_8);

        Path output = Files.createTempDirectory("titan-repackage-output");
        Path dialectOutput = Files.createDirectories(output.resolve("postgresql"));
        // Stale titan-owned bundles from the legacy versioned scheme must be cleaned up.
        Files.writeString(dialectOutput.resolve("V0000000000000000__titan_runtime.sql"), "-- legacy runtime\n", StandardCharsets.UTF_8);
        Files.writeString(dialectOutput.resolve("Vdeadbeefdeadbeef__titan_routines.sql"), "-- legacy routines\n", StandardCharsets.UTF_8);
        // User-authored versioned migrations must never be modified or deleted.
        String userMigration = "CREATE TABLE user_data (id integer);\n";
        Files.writeString(dialectOutput.resolve("V1__user_baseline.sql"), userMigration, StandardCharsets.UTF_8);

        task.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> sqlInput.toFile())));
        task.getMode().set("migration");
        task.getTitanVersion().set("1.0.0");
        task.getOutputDir().set(project.getLayout().dir(project.provider(() -> output.toFile())));

        task.run();
        String firstBundle = Files.readString(dialectOutput.resolve("R__titan_020_routines.sql"), StandardCharsets.UTF_8);
        assertTrue(firstBundle.contains("SELECT 1;"));

        Files.writeString(routine, "SELECT 2;\n", StandardCharsets.UTF_8);
        task.run();

        List<String> fileNames;
        try (Stream<Path> stream = Files.list(dialectOutput)) {
            fileNames = stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(
                List.of("R__titan_010_runtime.sql", "R__titan_020_routines.sql", "V1__user_baseline.sql"),
                fileNames);

        String secondBundle = Files.readString(dialectOutput.resolve("R__titan_020_routines.sql"), StandardCharsets.UTF_8);
        assertTrue(secondBundle.contains("SELECT 2;"));
        assertFalse(secondBundle.contains("SELECT 1;"));
        assertEquals(userMigration, Files.readString(dialectOutput.resolve("V1__user_baseline.sql"), StandardCharsets.UTF_8));
    }

    @Test
    void runtimeRepeatableMigrationSortsBeforeRoutinesUnderFlywayDescriptionOrdering() {
        // Flyway runs repeatable migrations in description order; the description is the file
        // name between the "R__" prefix and the ".sql" suffix with underscores as spaces.
        String runtimeDescription = flywayDescription(TitanPackageTask.RUNTIME_MIGRATION_FILE_NAME);
        String routinesDescription = flywayDescription(TitanPackageTask.ROUTINES_MIGRATION_FILE_NAME);
        assertTrue(runtimeDescription.compareTo(routinesDescription) < 0,
                runtimeDescription + " must sort before " + routinesDescription);
    }

    private static String flywayDescription(String fileName) {
        return fileName.substring("R__".length(), fileName.length() - ".sql".length()).replace('_', ' ');
    }
}
