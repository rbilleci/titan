package io.titan.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanJdbcCompatReportTaskTest {

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
                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.ResultSet;
                import java.sql.SQLException;

                class Repo {
                    @StoredProcedure
                    public static void cleanRead(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        }
                    }

                    @StoredProcedure
                    public static void splice(Connection c, long id) throws SQLException {
                        String sql = "SELECT * FROM accounts WHERE id = " + id;
                        PreparedStatement ps = c.prepareStatement(sql);
                        ps.executeQuery();
                    }
                }
                """);
        return sourceDir;
    }

    @Test
    void writesReportAndDoesNotFailByDefault() throws Exception {
        Path sourceDir = writeSources();

        Project project = ProjectBuilder.builder().build();
        TitanJdbcCompatReportTask task = project.getTasks()
                .create("titanJdbcCompatReportTest", TitanJdbcCompatReportTask.class);
        task.getSourceFiles().from(
                sourceDir.resolve("titan/dsl/StoredProcedure.java").toFile(),
                sourceDir.resolve("demo/Repo.java").toFile());
        task.getSqlSafety().set("strict");
        Path reportFile = tempDir.resolve("reports/jdbc-compat-report.json");
        task.getReportFile().set(reportFile.toFile());

        // failOnRejected defaults to false: the strict splice is rejected but the build must not fail.
        task.run();

        assertTrue(Files.exists(reportFile), "report JSON must be written");
        String json = Files.readString(reportFile);
        assertTrue(json.contains("\"rejected\": 1"), json);
        assertTrue(json.contains("\"transpilable\": 1"), json);
        assertTrue(json.contains("TITAN-E004"), json);
    }

    @Test
    void failOnRejectedTrueFailsTheBuild() throws Exception {
        Path sourceDir = writeSources();

        Project project = ProjectBuilder.builder().build();
        TitanJdbcCompatReportTask task = project.getTasks()
                .create("titanJdbcCompatReportFailTest", TitanJdbcCompatReportTask.class);
        task.getSourceFiles().from(
                sourceDir.resolve("titan/dsl/StoredProcedure.java").toFile(),
                sourceDir.resolve("demo/Repo.java").toFile());
        task.getSqlSafety().set("strict");
        task.getFailOnRejected().set(true);
        Path reportFile = tempDir.resolve("reports-fail/jdbc-compat-report.json");
        task.getReportFile().set(reportFile.toFile());

        GradleException ex = assertThrows(GradleException.class, task::run);
        assertTrue(ex.getMessage().contains("rejected"), ex.getMessage());
        // The report is still written before failing, so it remains a usable artifact.
        assertTrue(Files.exists(reportFile), "report JSON must be written even when failing");
    }

    @Test
    void permissiveBuildLevelDowngradesSpliceToPassthrough() throws Exception {
        Path sourceDir = writeSources();

        Project project = ProjectBuilder.builder().build();
        TitanJdbcCompatReportTask task = project.getTasks()
                .create("titanJdbcCompatReportPermissiveTest", TitanJdbcCompatReportTask.class);
        task.getSourceFiles().from(
                sourceDir.resolve("titan/dsl/StoredProcedure.java").toFile(),
                sourceDir.resolve("demo/Repo.java").toFile());
        task.getSqlSafety().set("permissive");
        task.getFailOnRejected().set(true);
        Path reportFile = tempDir.resolve("reports-permissive/jdbc-compat-report.json");
        task.getReportFile().set(reportFile.toFile());

        // Under permissive the splice is a passthrough, not a reject, so failOnRejected does not trip.
        task.run();

        String json = Files.readString(reportFile);
        assertTrue(json.contains("\"rejected\": 0"), json);
        assertTrue(json.contains("\"passthrough\": 1"), json);
        assertFalse(json.contains("TITAN-E004"), json);
    }
}
