package io.titan.management.routines;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the Phase-B no-dependency-cycle rule (design note in
 * {@code titan-management-routines/build.gradle.kts}; spike doc §"Module / bootstrap implications"):
 * the Titan-transpiled SQL flows ONE WAY, INTO {@code titan-management}, and never back. Concretely
 * this test enforces three invariants that, together, forbid a cycle:
 *
 * <ol>
 *   <li><b>{@code titan-management-routines} routine sources depend on {@code titan-dsl} ONLY.</b>
 *       The {@code @StoredProcedure} sources under {@code src/main/java} must not {@code import
 *       io.titan.management.*} (except their own {@code io.titan.management.routines} package) and
 *       must not {@code import io.titan.transpiler.*} (the transpiler is generator tooling on a
 *       separate build-only classpath, never a routine dependency). And the routines module's build
 *       declares the routine ({@code main}) source set's only Titan dependency as the
 *       {@code io.titan:titan-dsl} coordinate (resolved to the live {@code ../titan-dsl} via the root
 *       composite build, since titan-dsl was split into its own repo) — never {@code :titan-management}
 *       — and does NOT apply the {@code io.titan.gradle} plugin (it transpiles via the in-build
 *       transpiler, not the plugin).</li>
 *   <li><b>{@code titan-management} does NOT depend on {@code titan-transpiler} or
 *       {@code titan-gradle-plugin}.</b> It receives the SQL bundle as a resource via a task-output
 *       input, not a {@code project(...)} dependency. Its sources carry no {@code import
 *       io.titan.transpiler.*}; its build declares no {@code project(":titan-transpiler")} /
 *       {@code project(":titan-gradle-plugin")} / {@code project(":titan-management-routines")} edge
 *       and applies no {@code io.titan.gradle} plugin.</li>
 * </ol>
 *
 * <p>Implemented as a dependency-free source/build-text scan (style follows titan-dsl's
 * {@code ModuleBoundaryEnforcementTest} and titan-transpiler's
 * {@code ExceptionDisciplineEnforcementTest}, which resolve module directories from the test runtime
 * path and inspect files directly). ArchUnit is NOT on this module's test classpath and the rule
 * does not warrant adding it; a text scan is sufficient because the violations of interest are
 * {@code import} statements and {@code build.gradle.kts} {@code project(...)} / plugin declarations.</p>
 */
class ModuleBoundaryEnforcementTest {

    // ------------------------------------------------------------------
    // (1) routine sources depend on titan-dsl ONLY (no io.titan.management.*, no io.titan.transpiler.*)
    // ------------------------------------------------------------------

    @Test
    void routineSourcesImportOnlyTitanDslAndNeitherManagementNorTranspiler() throws Exception {
        Path routineMainSrc = routinesModuleDir().resolve(Path.of("src", "main", "java"));
        List<String> violations = new ArrayList<>();
        for (Path javaFile : javaSourcesUnder(routineMainSrc)) {
            String source = Files.readString(javaFile);
            for (String line : source.lines().toList()) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                // io.titan.management.* is forbidden EXCEPT the routines' own package.
                if (trimmed.startsWith("import io.titan.management.")
                        && !trimmed.startsWith("import io.titan.management.routines")) {
                    violations.add(rel(routineMainSrc, javaFile) + " -> " + trimmed);
                }
                // The transpiler must never be a routine-source dependency.
                if (trimmed.startsWith("import io.titan.transpiler.")
                        || trimmed.equals("import io.titan.transpiler;")) {
                    violations.add(rel(routineMainSrc, javaFile) + " -> " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "titan-management-routines routine sources (src/main/java) must depend on titan-dsl "
                        + "ONLY — no import of io.titan.management.* (except io.titan.management.routines) "
                        + "and no import of io.titan.transpiler.*. The SQL flows one way INTO "
                        + "titan-management; the routines never depend back on it, and the transpiler is "
                        + "build-only generator tooling. Violations: " + violations);
    }

    @Test
    void routinesBuildDeclaresOnlyTitanDslForTheRoutineSourceSetAndDoesNotApplyTheTitanPlugin() throws Exception {
        String build = stripLineComments(
                Files.readString(routinesModuleDir().resolve("build.gradle.kts")));

        // The plugin-free seam: applying io.titan.gradle would resolve the (possibly stale) published
        // transpiler from mavenLocal and add plugin-applied project edges. The module transpiles via
        // the in-build titan-transpiler instead, so it must NOT apply the plugin.
        assertTrue(!appliesTitanGradlePlugin(build),
                "titan-management-routines must NOT apply the io.titan.gradle plugin (it transpiles "
                        + "via the in-build titan-transpiler; applying the plugin would re-introduce the "
                        + "mavenLocal/plugin-edge seam the Phase-B design removed).");

        // The ROUTINE (main) source set's Titan dependency must be titan-dsl only — never
        // titan-management. (titan-transpiler appears only on build-only configurations:
        // transpilerClasspath + the `generator` source set — generator tooling, not a routine dep.)
        assertTrue(!declaresProjectDependencyEdge(build, ":titan-management"),
                "titan-management-routines must NOT declare a project dependency on titan-management — "
                        + "that would be the cycle (the SQL flows INTO titan-management, never back).");

        // Positive pin: the routine source set's implementation/api dependency IS titan-dsl. Since
        // titan-dsl was split into its own repo it is declared by COORDINATE (io.titan:titan-dsl,
        // resolved to the live ../titan-dsl via the root composite build), not project(":titan-dsl").
        boolean declaresDslForMain = Pattern
                .compile("\\b(?:implementation|api)\\s*\\(\\s*\"io\\.titan:titan-dsl(?::[^\"]*)?\"\\s*\\)")
                .matcher(build)
                .find();
        assertTrue(declaresDslForMain,
                "titan-management-routines routine sources must depend on titan-dsl "
                        + "(implementation(\"io.titan:titan-dsl:...\")).");
    }

    // ------------------------------------------------------------------
    // (2) titan-management does NOT depend on titan-transpiler or titan-gradle-plugin
    // ------------------------------------------------------------------

    @Test
    void managementSourcesDoNotImportTheTranspiler() throws Exception {
        Path managementMainSrc = managementModuleDir().resolve(Path.of("src", "main", "java"));
        List<String> violations = new ArrayList<>();
        for (Path javaFile : javaSourcesUnder(managementMainSrc)) {
            String source = Files.readString(javaFile);
            for (String line : source.lines().toList()) {
                String trimmed = line.strip();
                if (trimmed.startsWith("import io.titan.transpiler.")
                        || trimmed.equals("import io.titan.transpiler;")) {
                    violations.add(rel(managementMainSrc, javaFile) + " -> " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "titan-management sources must not import io.titan.transpiler.* — it ships the SQL "
                        + "bundle as a packaged resource (a task-output input), not via a transpiler "
                        + "dependency. Violations: " + violations);
    }

    @Test
    void managementBuildDeclaresNoProjectEdgeToTranspilerPluginOrRoutinesAndDoesNotApplyTheTitanPlugin()
            throws Exception {
        // Strip // line comments first: the design note in this build script DISCUSSES the forbidden
        // edges in prose (e.g. "implementation(project(...))" inside a comment explaining WHY it is
        // avoided), and those must not trip the scan.
        String build = stripLineComments(
                Files.readString(managementModuleDir().resolve("build.gradle.kts")));

        // The forbidden thing is a DEPENDENCY-DECLARATION edge — a configuration call wrapping
        // project(":titan-X") (e.g. implementation(project(":titan-transpiler"))). The ALLOWED
        // task-output wiring `project(":titan-management-routines").tasks.named(...)` is a bare
        // cross-project TASK reference, not wrapped in a configuration call, so it is not matched.
        List<String> forbidden = new ArrayList<>();
        for (String forbiddenPath : List.of(
                ":titan-transpiler", ":titan-gradle-plugin", ":titan-management-routines")) {
            if (declaresProjectDependencyEdge(build, forbiddenPath)) {
                forbidden.add(forbiddenPath);
            }
        }
        assertTrue(forbidden.isEmpty(),
                "titan-management must NOT declare a project DEPENDENCY edge on titan-transpiler, "
                        + "titan-gradle-plugin, or titan-management-routines — it receives the SQL bundle "
                        + "as a processResources task-output INPUT (no ProjectDependency on any "
                        + "configuration, so the titan-graphql consumer's configuration-time project "
                        + "dependency walk never sees them — TG-BLK-009). Forbidden edges found: "
                        + forbidden);

        assertTrue(!appliesTitanGradlePlugin(build),
                "titan-management is a published library consumed by titan-graphql; it must NOT apply "
                        + "the io.titan.gradle plugin (blast radius — the SQL is produced by the separate "
                        + "titan-management-routines module).");
    }

    // ------------------------------------------------------------------
    // Guard: the scanned module directories actually exist (fail loudly if the layout moves).
    // ------------------------------------------------------------------

    @Test
    void scannedModuleLayoutExistsOnDisk() throws Exception {
        assertTrue(Files.isDirectory(routinesModuleDir().resolve(Path.of("src", "main", "java"))),
                "routines module main source dir missing");
        assertTrue(Files.isRegularFile(routinesModuleDir().resolve("build.gradle.kts")),
                "routines module build script missing");
        assertTrue(Files.isDirectory(managementModuleDir().resolve(Path.of("src", "main", "java"))),
                "titan-management module main source dir missing");
        assertTrue(Files.isRegularFile(managementModuleDir().resolve("build.gradle.kts")),
                "titan-management module build script missing");
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /** Matches an `id("io.titan.gradle")` / `id "io.titan.gradle"` plugin application. */
    private static boolean appliesTitanGradlePlugin(String build) {
        Matcher matcher = Pattern
                .compile("id\\s*\\(?\\s*[\"']io\\.titan\\.gradle[\"']")
                .matcher(build);
        return matcher.find();
    }

    /**
     * True iff {@code build} declares a DEPENDENCY edge on {@code projectPath} — a configuration call
     * wrapping {@code project("<projectPath>")}, e.g. {@code implementation(project(":titan-x"))} or
     * {@code "generatorImplementation"(project(":titan-x"))}. A bare cross-project TASK reference
     * ({@code project(":titan-x").tasks.named(...)}) or {@code evaluationDependsOn(":titan-x")} is NOT
     * a dependency edge and is intentionally not matched.
     */
    private static boolean declaresProjectDependencyEdge(String build, String projectPath) {
        // <configToken>(  project(  "<path>"  )  )   — the configToken is any identifier (possibly a
        // quoted string config name) immediately preceding the project(...) call. The bare
        // `project("<path>").tasks...` form has no such wrapping call and is excluded.
        Pattern pattern = Pattern.compile(
                "[\\w\"']\\s*\\(\\s*project\\(\\s*\"" + Pattern.quote(projectPath) + "\"\\s*\\)\\s*\\)");
        return pattern.matcher(build).find();
    }

    /** Removes {@code //} line comments so prose discussing forbidden edges does not trip the scan. */
    private static String stripLineComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    private static List<Path> javaSourcesUnder(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String rel(Path base, Path file) {
        return base.relativize(file).toString();
    }

    private static Path routinesModuleDir() throws URISyntaxException {
        return resolveOwnModuleDirectory();
    }

    private static Path managementModuleDir() throws URISyntaxException {
        Path sibling = resolveOwnModuleDirectory().resolveSibling("titan-management");
        if (!Files.isDirectory(sibling)) {
            throw new IllegalStateException(
                    "Unable to locate sibling titan-management module directory at " + sibling);
        }
        return sibling;
    }

    private static Path resolveOwnModuleDirectory() throws URISyntaxException {
        Path classesDir = Path.of(ModuleBoundaryEnforcementTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

        Path moduleDir = classesDir;
        while (moduleDir != null && !Files.exists(moduleDir.resolve("build.gradle.kts"))) {
            moduleDir = moduleDir.getParent();
        }

        if (moduleDir == null) {
            throw new IllegalStateException(
                    "Unable to locate titan-management-routines module directory from test runtime path: "
                            + classesDir);
        }
        return moduleDir;
    }
}
