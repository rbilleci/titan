package io.titan.gradle;

import io.titan.transpiler.jdbc.PermissiveScopeReport;
import io.titan.transpiler.jdbc.PermissiveScopeReportEngine;
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
 * Report-only "effective permissive scopes" audit task (NEW WORK; {@code docs/design-document.md}
 * §11.1). Mirrors {@link TitanJdbcCompatReportTask}'s input/output declaration but produces an
 * <i>auditability</i> artifact: it runs {@link PermissiveScopeReportEngine} over the same Java
 * sources and writes {@code build/reports/titan/permissive-scopes.json} plus a console rendering
 * listing every entry-point method whose effective {@code sqlSafety} resolves to permissive —
 * <b>including methods relaxed only by a class-level {@code @SqlSafety(PERMISSIVE)} or the
 * build-level {@code sqlSafety = permissive} flag</b>, which carry no per-method marker to grep for.
 *
 * <p>It is <b>report-only</b>: it never fails the build (no {@code failIfErrors} knob). A broad-scope
 * relaxation cannot quietly cover a forgotten method because that method is enumerated here with its
 * relaxation source.</p>
 */
@CacheableTask
public abstract class TitanPermissiveScopesReportTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Classpath
    public abstract ConfigurableFileCollection getClasspathFiles();

    @Input
    public abstract Property<String> getSqlSafety();

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

        PermissiveScopeReport report =
                new PermissiveScopeReportEngine().report(sourcePaths, classpath, sqlSafety);

        Path reportFile = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report.toJson(), StandardCharsets.UTF_8);

        getLogger().lifecycle(
                "Titan effective permissive scopes (sqlSafety={}): {} methods, {} effectively permissive -> {}",
                sqlSafety,
                report.methodCount(),
                report.effectivelyPermissiveCount(),
                reportFile);
    }
}
