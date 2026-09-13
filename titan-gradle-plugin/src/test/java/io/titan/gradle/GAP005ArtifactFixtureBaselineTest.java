package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAP005ArtifactFixtureBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesCurrentGeneratedSqlArtifactOutputsWithoutInstallMetadata() throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks()
                .create("gap005ArtifactBaselineTranspile", TitanTranspileTask.class);
        TitanPackageTask migrationPackage = project.getTasks()
                .create("gap005ArtifactBaselineMigrationPackage", TitanPackageTask.class);
        TitanPackageTask directPackage = project.getTasks()
                .create("gap005ArtifactBaselineDirectPackage", TitanPackageTask.class);

        SourceFixture source = writeSource("""
                package demo;

                import titan.dsl.StoredFunction;

                record LessonDescriptor(long id, long courseId, String title, String visibility) {}

                class InventoryPricingKernel {
                    @StoredFunction
                    public static int priceForSku(String sku) {
                        if (sku.equals("SKU-001")) {
                            return 1299;
                        }
                        return 999;
                    }
                }

                class CourseBrowseKernel {
                    @StoredFunction
                    public static String visibleLessonTitle(long courseId, String viewerRole) {
                        LessonDescriptor[] lessons = new LessonDescriptor[] {
                            new LessonDescriptor(10L, 7L, "Intro", "public"),
                            new LessonDescriptor(11L, 7L, "Workshop", "member"),
                            new LessonDescriptor(12L, 8L, "Billing", "public")
                        };
                        for (LessonDescriptor lesson : lessons) {
                            if (lesson.courseId() == courseId
                                    && (lesson.visibility().equals("public") || viewerRole.equals("member"))) {
                                return lesson.title();
                            }
                        }
                        return "none";
                    }
                }
                """);

        Path generatedSql = tempDir.resolve("generated-sql");
        transpile.getSourceFiles().from(source.annotationSource().toFile(), source.entryPointSource().toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(generatedSql.toFile());
        transpile.run();

        assertEquals(
                List.of("mysql", "postgresql"),
                directoryNames(generatedSql),
                "current transpile output is dialect directories only");
        Set<String> expectedCurrentSqlFiles = Set.of(
                "LessonDescriptor__LessonDescriptor.record.sql",
                "demo_CourseBrowseKernel__visibleLessonTitle.sql",
                "demo_InventoryPricingKernel__priceForSku.sql");
        assertEquals(expectedCurrentSqlFiles, sqlFileNames(generatedSql.resolve("postgresql")));
        assertEquals(expectedCurrentSqlFiles, sqlFileNames(generatedSql.resolve("mysql")));
        assertTrue(Files.exists(generatedSql.resolve(".titan-transpile.outputs")));
        assertSqlContains(generatedSql.resolve("postgresql/demo_InventoryPricingKernel__priceForSku.sql"),
                "price_for_sku");
        assertSqlContains(generatedSql.resolve("mysql/demo_CourseBrowseKernel__visibleLessonTitle.sql"),
                "visible_lesson_title");
        assertGap005MetadataIsAbsent(generatedSql);

        Path migrationOutput = tempDir.resolve("migration-package");
        migrationPackage.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        migrationPackage.getMode().set("migration");
        migrationPackage.getTitanVersion().set("gap005-m0.1");
        migrationPackage.getOutputDir().set(project.getLayout().dir(project.provider(() -> migrationOutput.toFile())));
        migrationPackage.run();

        assertEquals(List.of("mysql", "postgresql"), directoryNames(migrationOutput));
        assertMigrationPackageShape(migrationOutput.resolve("postgresql"));
        assertMigrationPackageShape(migrationOutput.resolve("mysql"));
        assertTrue(anySqlContains(migrationOutput.resolve("postgresql"), "-- titan:source-file:LessonDescriptor__LessonDescriptor.record.sql"));
        assertTrue(anySqlContains(migrationOutput.resolve("postgresql"), "-- titan:source-file:demo_CourseBrowseKernel__visibleLessonTitle.sql"));
        assertTrue(anySqlContains(migrationOutput.resolve("postgresql"), "-- titan:source-file:demo_InventoryPricingKernel__priceForSku.sql"));
        assertTrue(anySqlContains(migrationOutput.resolve("mysql"), "-- titan-runtime-version:gap005-m0.1"));
        assertTrue(anySqlContains(migrationOutput.resolve("mysql"), "visible_lesson_title"));
        assertArtifactManifestIsPresent(migrationOutput);
        assertObjectInventoryIsPresent(migrationOutput);
        assertInstallPlanIsPresent(migrationOutput);
        assertInstallVerificationIsPresentAndPending(migrationOutput);

        Path directOutput = tempDir.resolve("direct-package");
        directPackage.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        directPackage.getMode().set("direct");
        directPackage.getTitanVersion().set("gap005-m0.1");
        directPackage.getOutputDir().set(project.getLayout().dir(project.provider(() -> directOutput.toFile())));
        directPackage.run();

        assertEquals(sqlFileNames(generatedSql.resolve("postgresql")), sqlFileNames(directOutput.resolve("postgresql")));
        assertEquals(sqlFileNames(generatedSql.resolve("mysql")), sqlFileNames(directOutput.resolve("mysql")));
        assertArtifactManifestIsPresent(directOutput);
        assertObjectInventoryIsPresent(directOutput);
        assertInstallPlanIsPresent(directOutput);
        assertInstallVerificationIsPresentAndPending(directOutput);
    }

    private SourceFixture writeSource(String entryPointSource) throws Exception {
        Path sourceDir = tempDir.resolve("src");
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

        Path entryPoint = sourceDir.resolve("demo/GAP005ArtifactFixtures.java");
        Files.writeString(entryPoint, entryPointSource, StandardCharsets.UTF_8);
        return new SourceFixture(annotationSource, entryPoint);
    }

    private static List<String> directoryNames(Path root) throws Exception {
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> sqlFileNames(Path dialectDir) throws Exception {
        try (Stream<Path> stream = Files.list(dialectDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static void assertMigrationPackageShape(Path dialectDir) throws Exception {
        List<String> fileNames;
        try (Stream<Path> stream = Files.list(dialectDir)) {
            fileNames = stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }

        assertEquals(2, fileNames.size());
        assertEquals("R__titan_010_runtime.sql", fileNames.get(0));
        assertEquals("R__titan_020_routines.sql", fileNames.get(1));
    }

    private static void assertGap005MetadataIsAbsent(Path artifactRoot) {
        List<String> missingTargets = List.of(
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json");
        for (String target : missingTargets) {
            assertFalse(Files.exists(artifactRoot.resolve(target)), "baseline should expose missing GAP-005 metadata: " + target);
            assertFalse(Files.exists(artifactRoot.resolve("postgresql").resolve(target)),
                    "baseline should expose missing PostgreSQL GAP-005 metadata: " + target);
            assertFalse(Files.exists(artifactRoot.resolve("mysql").resolve(target)),
                    "baseline should expose missing MySQL GAP-005 metadata: " + target);
        }
    }

    private static void assertArtifactManifestIsPresent(Path artifactRoot) throws Exception {
        Path manifestPath = artifactRoot.resolve("titan-artifact.json");
        assertTrue(Files.exists(manifestPath));
        String manifest = Files.readString(manifestPath, StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"schemaVersion\": \"titan.artifact.v1\""));
        assertTrue(manifest.contains("\"artifactId\": \"titan.generated-sql."));
        assertTrue(manifest.contains("\"titanVersion\": \"gap005-m0.1\""));
        assertTrue(manifest.contains("\"status\": \"generated\""));
        assertTrue(manifest.contains("\"id\": \"demo.InventoryPricingKernel.priceForSku(java.lang.String)\""));
        assertTrue(manifest.contains("\"id\": \"demo.CourseBrowseKernel.visibleLessonTitle(long,java.lang.String)\""));
        assertTrue(manifest.contains("\"className\": \"demo.InventoryPricingKernel\""));
        assertTrue(manifest.contains("\"methodName\": \"priceForSku\""));
        assertTrue(manifest.contains("\"parameterTypes\": [\"java.lang.String\"]"));
        assertTrue(manifest.contains("\"parameterTypes\": [\"long\", \"java.lang.String\"]"));
        assertTrue(manifest.contains("\"annotation\": \"StoredFunction\""));
        assertTrue(manifest.contains("\"path\": \"demo/GAP005ArtifactFixtures.java\""));
        assertTrue(manifest.contains("\"routineName\": \"price_for_sku\""));
        assertTrue(manifest.contains("\"routineName\": \"visible_lesson_title\""));
        assertTrue(manifest.contains("\"parameters\": ["));
        assertTrue(manifest.contains("\"type\": \""));
        assertTrue(manifest.toLowerCase().contains("\"returntype\": \"int"));
        assertFalse(manifest.contains("LessonDescriptor()"));
        assertTrue(manifest.contains("\"status\": \"inventoried\""));
        assertTrue(manifest.contains("install verification pending: run titanVerifyInstall"));
    }

    private static void assertObjectInventoryIsPresent(Path artifactRoot) throws Exception {
        Path inventoryPath = artifactRoot.resolve("titan-object-inventory.json");
        assertTrue(Files.exists(inventoryPath));
        String inventory = Files.readString(inventoryPath, StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"schemaVersion\": \"titan.object-inventory.v1\""));
        assertTrue(inventory.contains("\"artifactId\": \"titan.generated-sql."));
        assertTrue(inventory.contains("\"kind\": \"function\""));
        assertTrue(inventory.contains("\"name\": \"price_for_sku\""));
        assertTrue(inventory.contains("\"name\": \"visible_lesson_title\""));
        assertTrue(inventory.contains("\"sourceEntryPoint\": \"demo.InventoryPricingKernel.priceForSku()\""));
        assertTrue(inventory.contains("\"sourceEntryPoint\": \"demo.CourseBrowseKernel.visibleLessonTitle()\""));
        assertTrue(inventory.contains("\"sqlHash\": \""));
    }

    private static void assertInstallPlanIsPresent(Path artifactRoot) throws Exception {
        Path installPlanPath = artifactRoot.resolve("titan-install-plan.json");
        assertTrue(Files.exists(installPlanPath));
        String installPlan = Files.readString(installPlanPath, StandardCharsets.UTF_8);
        assertTrue(installPlan.contains("\"schemaVersion\": \"titan.install-plan.v1\""));
        assertTrue(installPlan.contains("\"artifactId\": \"titan.generated-sql."));
        assertTrue(installPlan.contains("\"dialect\": \"mysql\""));
        assertTrue(installPlan.contains("\"dialect\": \"postgresql\""));
        assertTrue(installPlan.contains("\"transactionMode\": \"statementBoundaryDdl\""));
        assertTrue(installPlan.contains("\"transactionMode\": \"singleTransaction\""));
        assertTrue(installPlan.contains("\"kind\": \"preflight\""));
        assertTrue(installPlan.contains("\"kind\": \"createOrReplace\""));
        assertTrue(installPlan.contains("\"kind\": \"verificationProbe\""));
        assertTrue(installPlan.contains("\"objectId\": \"postgresql.public.price_for_sku.function\""));
        assertTrue(installPlan.contains("\"objectId\": \"mysql.public.visible_lesson_title.function\""));
    }

    private static void assertInstallVerificationIsPresentAndPending(Path artifactRoot) throws Exception {
        Path reportPath = artifactRoot.resolve("titan-install-verification.json");
        assertTrue(Files.exists(reportPath));
        String report = Files.readString(reportPath, StandardCharsets.UTF_8);
        assertTrue(report.contains("\"schemaVersion\": \"titan.install-verification.v1\""));
        assertTrue(report.contains("\"artifactId\": \"titan.generated-sql."));
        assertTrue(report.contains("\"status\": \"pending\""));
        assertTrue(report.contains("\"kind\": \"scratch-required\""));
        assertTrue(report.contains("\"dialect\": \"mysql\""));
        assertTrue(report.contains("\"dialect\": \"postgresql\""));
        assertTrue(report.contains("\"code\": \"TITAN-GAP005-VERIFY-PENDING\""));
    }

    private static void assertSqlContains(Path path, String fragment) throws Exception {
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains(fragment), path + " should contain " + fragment);
    }

    private static boolean anySqlContains(Path dialectDir, String fragment) throws Exception {
        try (Stream<Path> stream = Files.list(dialectDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .anyMatch(path -> fileContains(path, fragment));
        }
    }

    private static boolean fileContains(Path path, String fragment) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(fragment);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SourceFixture(Path annotationSource, Path entryPointSource) {
    }
}
