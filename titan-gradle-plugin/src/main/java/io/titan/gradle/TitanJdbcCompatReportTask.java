package io.titan.gradle;

import io.titan.transpiler.jdbc.JdbcCompatLinter;
import io.titan.transpiler.jdbc.JdbcCompatReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Report-only JDBC compatibility linter task (WS-C Phase 1). Mirrors {@link TitanTranspileTask}'s
 * input/output declaration but produces a measurement artifact, not SQL: it runs
 * {@link JdbcCompatLinter} over the same Java sources and writes
 * {@code build/reports/titan/jdbc-compat-report.json} plus a console rendering.
 *
 * <p>It never fails the build by default ({@link #getFailOnRejected()} defaults to {@code false} —
 * pure measurement). Setting {@code failOnRejected = true} turns a method whose rollup is
 * {@code REJECTED} into a build failure (an opt-in budget gate; a {@code maxRejected} knob is a
 * later follow-up).</p>
 */
@CacheableTask
public abstract class TitanJdbcCompatReportTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Classpath
    public abstract ConfigurableFileCollection getClasspathFiles();

    @Input
    public abstract Property<String> getSqlSafety();

    @Input
    public abstract Property<Boolean> getFailOnRejected();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws Exception {
        List<Path> sourcePaths = getSourceFiles().getFiles().stream()
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .map(file -> file.toPath().toAbsolutePath())
                .sorted(Comparator.naturalOrder())
                .toList();

        List<Path> classpath = getClasspathFiles().getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath())
                .toList();

        String sqlSafety = getSqlSafety().getOrElse("strict");

        JdbcCompatReport report = new JdbcCompatLinter().lint(sourcePaths, classpath, sqlSafety);

        Path reportFile = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report.toJson(), StandardCharsets.UTF_8);

        getLogger().lifecycle(
                "Titan JDBC compatibility: {} methods (transpilable={}, passthrough={}, rejected={}) -> {}",
                report.methodCount(),
                report.methodsWith(io.titan.transpiler.jdbc.JdbcClassification.TRANSPILABLE),
                report.methodsWith(io.titan.transpiler.jdbc.JdbcClassification.PASSTHROUGH),
                report.methodsWith(io.titan.transpiler.jdbc.JdbcClassification.REJECTED),
                reportFile);

        if (getFailOnRejected().getOrElse(false) && report.hasRejected()) {
            throw new org.gradle.api.GradleException(
                    "Titan JDBC compatibility found rejected methods (failOnRejected=true). See "
                            + reportFile + " for the per-method verdicts.\n" + report.toText());
        }
    }
}
