package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional coverage for {@link TitanPermissiveScopesReportTask}, mirroring
 * {@link TitanJdbcCompatReportTaskTest}: it runs the report-only task over a fixture and asserts the
 * report file and its contents. The fixture deliberately mixes a method-level, a class-level, and a
 * build-level permissive scope so the <b>non-greppable</b> class-/build-level cases are visibly
 * caught (catching them is the whole value of the audit).
 */
class TitanPermissiveScopesReportTaskTest {

    @TempDir
    Path tempDir;

    private Path writeSources() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir.resolve("titan/dsl"));
        Files.createDirectories(sourceDir.resolve("demo"));

        Files.writeString(sourceDir.resolve("titan/dsl/StoredProcedure.java"), """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredProcedure {}
                """);

        Files.writeString(sourceDir.resolve("demo/Repo.java"), """
                package demo;

                import titan.dsl.StoredProcedure;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.Connection;
                import java.sql.SQLException;

                class Repo {
                    // METHOD_ANNOTATION: explicit method-level PERMISSIVE (greppable).
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void methodScoped(Connection c) throws SQLException {}

                    // BUILD_FLAG: no annotation; relaxed only by the build-level sqlSafety=permissive
                    // (the non-greppable case the report exists to surface).
                    @StoredProcedure
                    public static void buildScoped(Connection c) throws SQLException {}
                }
                """);

        // CLASS_ANNOTATION: an unannotated method inside a class-level @SqlSafety(PERMISSIVE)
        // (non-greppable per method).
        Files.writeString(sourceDir.resolve("demo/Legacy.java"), """
                package demo;

                import titan.dsl.StoredProcedure;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.Connection;
                import java.sql.SQLException;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class Legacy {
                    @StoredProcedure
                    public static void classScoped(Connection c) throws SQLException {}
                }
                """);
        return sourceDir;
    }

    private TitanPermissiveScopesReportTask task(Project project, String name, Path sourceDir, String sqlSafety, Path reportFile) {
        TitanPermissiveScopesReportTask task = project.getTasks()
                .create(name, TitanPermissiveScopesReportTask.class);
        task.getSourceFiles().from(
                sourceDir.resolve("titan/dsl/StoredProcedure.java").toFile(),
                sourceDir.resolve("demo/Repo.java").toFile(),
                sourceDir.resolve("demo/Legacy.java").toFile());
        // The real @SqlSafety/@SqlSafetyMode types come from the titan-dsl on the test classpath
        // (the JavaSourceParser inherits the test JVM classpath when none is passed explicitly).
        task.getSqlSafety().set(sqlSafety);
        task.getReportFile().set(reportFile.toFile());
        return task;
    }

    @Test
    void buildLevelStrictCatchesMethodAndClassPermissiveButNotBuildFlag() throws Exception {
        Path sourceDir = writeSources();
        Project project = ProjectBuilder.builder().build();
        Path reportFile = tempDir.resolve("reports/permissive-scopes.json");
        task(project, "permissiveScopesStrict", sourceDir, "strict", reportFile).run();

        assertTrue(Files.exists(reportFile), "report JSON must be written");
        String json = Files.readString(reportFile);
        // Under strict build, only the method-level and class-level PERMISSIVE scopes are effective.
        assertTrue(json.contains("\"effectivelyPermissive\": 2"), json);
        assertTrue(json.contains("\"METHOD_ANNOTATION\": 1"), json);
        assertTrue(json.contains("\"CLASS_ANNOTATION\": 1"), json);
        assertTrue(json.contains("methodScoped"), json);
        assertTrue(json.contains("classScoped"), json);
        // buildScoped is strict under a strict build -> not listed (no false positive).
        assertFalse(json.contains("buildScoped"), json);
        assertFalse(json.contains("\"BUILD_FLAG\""), json);
    }

    @Test
    void buildLevelPermissiveCatchesAllThreeSourcesIncludingNonGreppable() throws Exception {
        Path sourceDir = writeSources();
        Project project = ProjectBuilder.builder().build();
        Path reportFile = tempDir.resolve("reports-permissive/permissive-scopes.json");
        task(project, "permissiveScopesPermissive", sourceDir, "permissive", reportFile).run();

        String json = Files.readString(reportFile);
        // All three methods are effectively permissive; the build-level (non-greppable) one is caught.
        assertTrue(json.contains("\"methods\": 3"), json);
        assertTrue(json.contains("\"effectivelyPermissive\": 3"), json);
        assertTrue(json.contains("\"METHOD_ANNOTATION\": 1"), json);
        assertTrue(json.contains("\"CLASS_ANNOTATION\": 1"), json);
        assertTrue(json.contains("\"BUILD_FLAG\": 1"), json);
        assertTrue(json.contains("\"method\": \"buildScoped\""), json);
        assertTrue(json.contains("\"source\": \"BUILD_FLAG\""), json);
    }
}
