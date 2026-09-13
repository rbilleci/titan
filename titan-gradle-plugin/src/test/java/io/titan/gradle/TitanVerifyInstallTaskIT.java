package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 4.4 (audit G-10): {@code titanVerifyInstall} in scratch mode — the task provisions one
 * throwaway container per packaged dialect, prepares the target schemas, installs the package
 * and replaces the {@code pending} verification report with a real {@code passed} one.
 */
@EnabledIf("dockerAvailable")
class TitanVerifyInstallTaskIT {

    @TempDir
    Path tempDir;

    static boolean dockerAvailable() {
        return TitanScratchDatabases.dockerAvailable();
    }

    @Test
    void scratchModeVerifiesPackagedArtifactsAndReplacesPendingReport() throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks().create("verifyTaskTranspile", TitanTranspileTask.class);
        TitanPackageTask packageTask = project.getTasks().create("verifyTaskPackage", TitanPackageTask.class);
        TitanVerifyInstallTask verifyTask = project.getTasks().create("verifyTaskVerify", TitanVerifyInstallTask.class);

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
        Path entryPoint = sourceDir.resolve("demo/VerifyTaskKernel.java");
        Files.writeString(entryPoint, """
                package demo;

                import titan.dsl.StoredFunction;

                class VerifyTaskKernel {
                    @StoredFunction
                    public static long doubleIt(long value) {
                        return value * 2;
                    }
                }
                """, StandardCharsets.UTF_8);

        Path generatedSql = tempDir.resolve("generated-sql");
        transpile.getSourceFiles().from(annotationSource.toFile(), entryPoint.toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(generatedSql.toFile());
        transpile.run();

        Path artifactDir = tempDir.resolve("migrations");
        packageTask.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        packageTask.getMode().set("migration");
        packageTask.getTitanVersion().set("4.4-verify-task");
        packageTask.getOutputDir().set(project.getLayout().dir(project.provider(() -> artifactDir.toFile())));
        packageTask.run();

        String pendingReport = Files.readString(
                artifactDir.resolve("titan-install-verification.json"), StandardCharsets.UTF_8);
        assertTrue(pendingReport.contains("\"status\": \"pending\""), pendingReport);

        verifyTask.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        verifyTask.getArtifactDir().set(project.getLayout().dir(project.provider(() -> artifactDir.toFile())));
        verifyTask.getMode().set("migration");
        verifyTask.getTitanVersion().set("4.4-verify-task");
        verifyTask.getFailOnVerificationError().set(true);
        verifyTask.run();

        String report = Files.readString(
                artifactDir.resolve("titan-install-verification.json"), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"status\": \"passed\""), report);
        assertTrue(report.contains("\"kind\": \"scratch\""), report);
        assertTrue(report.contains("\"objectId\": \"postgresql.public.double_it.function\""), report);
        assertTrue(report.contains("\"objectId\": \"mysql.public.double_it.function\""), report);
        assertTrue(report.contains("\"checks\": [\"exists\""), report);
    }
}
