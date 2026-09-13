package io.titan.transpiler.jdbc;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Path;
import java.util.List;

/**
 * Engine for the {@code titanJdbcCompatReport} task: parses the Java sources (reusing
 * {@link JavaSourceParser}/{@link ParsedSources} — never a second parse), discovers entry points
 * with {@link EntryPointDiscovery}, runs the {@link JdbcUsageRecognizer} over the shared parse, and
 * aggregates the result into a {@link JdbcCompatReport}. Report-only: it records diagnostics but
 * never throws / never {@code failIfErrors()}, so a build can measure JDBC compatibility without
 * being blocked.
 *
 * <p>Lives in {@code titan-transpiler} so it is unit-testable without Gradle; the thin
 * {@code TitanJdbcCompatReportTask} just feeds it source/classpath paths and the {@code sqlSafety}
 * string and writes the JSON.</p>
 */
public final class JdbcCompatLinter {

    /**
     * Lints the given sources and returns the aggregate report.
     *
     * @param sourceFiles      Java source files to analyze (the same set the transpiler would parse)
     * @param classpathEntries compile classpath (so {@code titan.dsl}/{@code java.sql} resolve)
     * @param sqlSafety        the build-level mode string ({@code "strict"} default | {@code "permissive"})
     */
    public JdbcCompatReport lint(List<Path> sourceFiles, List<Path> classpathEntries, String sqlSafety) {
        SqlSafetyMode buildLevel = SqlSafetyResolver.parseBuildLevel(sqlSafety);
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new JdbcCompatReport(buildLevel, List.of());
        }
        ParsedSources parsed = new JavaSourceParser().parse(sourceFiles, classpathEntries);
        return lint(parsed, buildLevel);
    }

    /** Lints an already-parsed source set (the path the recognizer tests use directly). */
    public JdbcCompatReport lint(ParsedSources parsed, SqlSafetyMode buildLevel) {
        SqlSafetyMode mode = buildLevel == null ? SqlSafetyMode.STRICT : buildLevel;
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        SqlSafetyResolver resolver = new SqlSafetyResolver(mode);
        List<MethodJdbcReport> methods =
                new JdbcUsageRecognizer().recognize(parsed, entryPoints, resolver);
        return new JdbcCompatReport(mode, methods);
    }
}
