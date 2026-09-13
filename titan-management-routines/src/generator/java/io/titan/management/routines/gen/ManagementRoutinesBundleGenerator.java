package io.titan.management.routines.gen;

import io.titan.transpiler.tir.TranspilationPipeline;
import io.titan.transpiler.tir.TranspilationPipeline.GeneratedSql;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-build SQL bundle generator for titan-management-routines (B-8 / GAP-006, Phase B).
 *
 * <p>THE BUILD-ARCHITECTURE FIX. This module is a committed in-tree subproject and CANNOT apply the
 * {@code io.titan.gradle} plugin: a project cannot {@code includeBuild} its own root, so applying the
 * plugin forced resolving it (and its transitive {@code titan-transpiler}) from <b>mavenLocal</b> —
 * a published, possibly-stale artifact (the previous agent hit a 3-day-stale-mavenLocal G2 bug). A
 * clean CI checkout could not {@code ./gradlew build} the module without first publishing the plugin.
 *
 * <p>Instead this generator calls the {@link TranspilationPipeline#transpile} API <b>directly</b> —
 * the exact same entry point {@code EmitterDeployabilityIT} and the spike's deployability IT use —
 * with the <b>in-build</b> {@code titan-transpiler} + {@code titan-dsl} on its classpath (wired by
 * the Gradle {@code transpileManagementRoutines} JavaExec task). No plugin, no mavenLocal, no
 * introspection: the routine sources are transpiled against live in-repo transpiler source.
 *
 * <p>It emits, per dialect, an ordered deployment bundle (the hand-authored schema DDL FIRST, then
 * the transpiled mutation routines) plus the individual routine SQL files (so the no-Docker
 * transpile-inspection test can assert per-routine shapes). The bundle is what {@code titan-management}
 * loads at runtime (Phase B3/B4): apply {@code schema.sql} then {@code routines.sql}.
 *
 * <p>CLI: {@code <sourceFile> <classpathFile> <ddlDir> <outputDir>}
 * <ul>
 *   <li>{@code sourceFile}  — the @StoredProcedure routine Java source (ManagementRoutines.java)</li>
 *   <li>{@code classpathFile} — a file holding the File.pathSeparator-joined transpile classpath
 *       (titan-dsl + the compiled routine classes); passed via a file to dodge command-line length
 *       limits and quoting.</li>
 *   <li>{@code ddlDir}      — src/main/resources/ddl, holding management_schema.{dialect}.sql</li>
 *   <li>{@code outputDir}   — build/generated/management-sql; the bundle is written under
 *       {@code <outputDir>/io/titan/management/sql/{dialect}/} so processResources mirrors it into
 *       the jar at that resource path.</li>
 * </ul>
 */
public final class ManagementRoutinesBundleGenerator {

    /** The emitted routines are CREATEd in the management schema (the DDL creates the tables there). */
    private static final String SCHEMA = "management";

    /** Both dialects the management store ships. */
    private static final List<String> TARGETS = List.of("postgresql", "mysql");

    /** The jar resource path the bundle is packaged under (titan-management loads from here). */
    private static final String RESOURCE_ROOT = "io/titan/management/sql";

    private ManagementRoutinesBundleGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: ManagementRoutinesBundleGenerator <sourceFile> <classpathFile> <ddlDir> <outputDir>");
        }
        Path sourceFile = Path.of(args[0]).toAbsolutePath();
        Path classpathFile = Path.of(args[1]).toAbsolutePath();
        Path ddlDir = Path.of(args[2]).toAbsolutePath();
        Path outputRoot = Path.of(args[3]).toAbsolutePath().resolve(RESOURCE_ROOT);

        List<Path> classpath = readClasspath(classpathFile);

        // The keystone: transpile the IN-BUILD routine source through the IN-BUILD transpiler.
        // strictMode=true (parity with EmitterDeployabilityIT); no observability so the routines pull
        // in zero titan_runtime helpers (G5 folds the command/status literals — verified downstream).
        List<GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile),
                classpath,
                TARGETS,
                List.of(SCHEMA),
                true);

        for (String dialect : TARGETS) {
            writeDialectBundle(dialect, generated, ddlDir, outputRoot);
        }

        System.out.println("[transpileManagementRoutines] wrote bundle for "
                + TARGETS + " to " + outputRoot);
    }

    private static void writeDialectBundle(
            String dialect,
            List<GeneratedSql> generated,
            Path ddlDir,
            Path outputRoot) throws Exception {
        Path dialectDir = outputRoot.resolve(dialect);
        Files.createDirectories(dialectDir);

        // The hand-authored schema DDL — the bootstrap seam, applied BEFORE the routines (the
        // routines reference these tables and ON CONFLICT target-inference needs the keys to exist).
        String ddl = Files.readString(ddlDir.resolve("management_schema." + dialect + ".sql"),
                StandardCharsets.UTF_8);
        Files.writeString(dialectDir.resolve("schema.sql"), ddl, StandardCharsets.UTF_8);

        // The transpiled mutation routines for this dialect, in a stable (by SQL name) order so the
        // bundle is deterministic build-to-build. Each routine is also written individually so the
        // transpile-inspection test can assert per-routine shapes.
        List<GeneratedSql> routines = generated.stream()
                .filter(sql -> dialect.equals(sql.target()))
                .sorted(Comparator.comparing(GeneratedSql::sqlName))
                .toList();

        Path routineDir = dialectDir.resolve("routines");
        Files.createDirectories(routineDir);

        StringBuilder routinesBundle = new StringBuilder();
        routinesBundle.append("-- titan-management-routines: transpiled MUTATION routines (")
                .append(dialect).append(").\n")
                .append("-- Generated IN-BUILD by ManagementRoutinesBundleGenerator (no plugin, no mavenLocal).\n")
                .append("-- Apply schema.sql FIRST, then this bundle.\n\n");

        for (GeneratedSql routine : routines) {
            String routineSql = routine.sql().stripTrailing();
            routinesBundle.append("-- titan:routine:").append(routine.sqlName()).append('\n');
            routinesBundle.append(routineSql).append('\n');
            // The PostgreSQL dollar-quoted body already ends in ';'; the MySQL block ends in
            // 'DELIMITER ;'. A blank line between routines keeps the concatenated bundle readable
            // and splits cleanly (the test SqlScripts splitter is delimiter/semicolon aware).
            routinesBundle.append('\n');

            // Per-routine file: method-name keyed (the transpile-inspection test resolves these).
            Files.writeString(
                    routineDir.resolve(routine.methodName() + ".sql"),
                    routineSql + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }

        Files.writeString(dialectDir.resolve("routines.sql"),
                routinesBundle.toString(), StandardCharsets.UTF_8);
    }

    private static List<Path> readClasspath(Path classpathFile) throws Exception {
        String raw = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        for (String entry : raw.split(java.io.File.pathSeparator)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(Path.of(trimmed));
            }
        }
        return entries;
    }
}
