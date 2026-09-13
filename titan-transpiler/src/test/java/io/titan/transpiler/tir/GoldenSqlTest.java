package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Golden-file gate for emitter output (plan 5.2). Transpiles the {@link GoldenSqlCorpus}
 * fixtures — the same sources {@link EmitterDeployabilityIT} proves deploy and execute on live
 * PostgreSQL and MySQL — and compares every emitted artifact byte-for-byte against the checked-in
 * golden files under {@code src/test/resources/golden/<dialect>/}, plus each dialect's runtime
 * migration script. This complements (does not replace) the existing emitter unit tests: the
 * substring assertions pin individual shapes, the goldens make every full-artifact change show
 * up as a reviewable diff instead of slipping past substring checks (the E-1 failure mode).
 *
 * <h2>Regenerating goldens</h2>
 *
 * When an intentional emission change lands, regenerate with
 *
 * <pre>{@code
 * ./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.GoldenSqlTest \
 *     -Dtitan.golden.update=true
 * }</pre>
 *
 * then review the {@code git diff} of {@code src/test/resources/golden/} like any other code
 * change and commit it together with the emitter change. The update run still executes every
 * sanity assertion, so a regeneration cannot check in structurally broken SQL. See the
 * "Golden emitter files" part of the developer guide testing section.
 *
 * <h2>Sanity rules</h2>
 *
 * Independent of the byte comparison, every artifact must satisfy structural sanity rules that
 * hold for all valid emitter output (these run in update mode too):
 * <ul>
 *   <li>balanced single quotes in every statement (an odd count means a string literal never
 *       closed — the classic broken-escaping shape);</li>
 *   <li>no {@code titan.dsl.} leakage — DSL namespace text in SQL means a Java expression was
 *       stringified instead of lowered;</li>
 *   <li>no unresolved {@code __titan_*(...)} marker calls — lowering passes communicate with the
 *       emitters through marker call nodes ({@code __titan_char_code}, {@code __titan_int_div},
 *       ...) that the emitters must rewrite to dialect SQL; a surviving marker call would deploy
 *       as a reference to a nonexistent function (bare {@code __titan_} variable names like
 *       {@code __titan_saved_message} are legitimate and allowed);</li>
 *   <li>no raw control bytes inside string literals (B-3 / TG-BLK-006) — a raw LF inside a
 *       literal is silently "indented" by {@code indentMultiline}, changing the literal's
 *       value; control characters must be emitted via dialect escapes ({@code E'\n'},
 *       backslash escapes, {@code CHAR(n USING utf8mb4)});</li>
 *   <li>artifacts are non-empty.</li>
 * </ul>
 */
class GoldenSqlTest {

    /** Marker-call pattern the emitters are supposed to rewrite; must never appear in SQL. */
    private static final Pattern UNRESOLVED_MARKER_CALL = Pattern.compile("__titan_\\w+\\s*\\(");

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mysql"})
    void emittedArtifactsMatchCheckedInGoldens(String dialect) throws Exception {
        boolean update = Boolean.getBoolean("titan.golden.update");
        Path goldenRoot = goldenRoot().resolve(dialect);
        if (update) {
            deleteRecursively(goldenRoot);
        }

        // Fixture sources are written to a stable module-relative path, NOT the @TempDir:
        // emitted artifacts embed the source path in titan:source comments and null-guard
        // messages, so a per-run temp path would make the goldens nondeterministic (and the
        // MySQL 128-char SIGNAL truncation makes post-hoc path normalization unsound — the
        // truncation point depends on the path length at emission time).
        Path fixtureDir = Files.createDirectories(Path.of("build", "golden-fixtures"));

        // fixture-id/artifact-file -> emitted SQL, in stable emission order.
        Map<String, String> emitted = new LinkedHashMap<>();
        for (GoldenSqlCorpus.Fixture fixture : GoldenSqlCorpus.fixtures()) {
            Path sourceFile = fixtureDir.resolve(fixture.id() + ".java");
            Files.writeString(sourceFile, fixture.source());
            List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                    List.of(sourceFile),
                    List.of(),
                    List.of(dialect),
                    List.of(GoldenSqlCorpus.SCHEMA),
                    true,
                    List.of(),
                    false,
                    List.of(),
                    false,
                    fixture.strictWraparound());
            assertFalse(generated.isEmpty(), fixture.id() + ": pipeline emitted no artifacts for " + dialect);
            int index = 0;
            for (TranspilationPipeline.GeneratedSql artifact : generated) {
                assertEquals(dialect, artifact.target(),
                        fixture.id() + ": single-target transpile returned a foreign-target artifact");
                String fileName = "%02d-%s.sql".formatted(index++, artifact.artifactName());
                emitted.put(fixture.id() + "/" + fileName, artifact.sql());
            }
        }

        // The runtime migration scripts deploy alongside generated routines in production
        // installs (the deployability IT deploys them the same way); pin them too.
        String runtimeMigration = switch (dialect) {
            case "postgresql" -> new PostgreSqlDialectProvider().runtimeStrategy().runtimeMigrationSql();
            case "mysql" -> new MySqlDialectProvider().runtimeStrategy().runtimeMigrationSql();
            default -> throw new IllegalArgumentException(dialect);
        };
        emitted.put("runtime-migration.sql", runtimeMigration);

        List<String> sanityFailures = new ArrayList<>();
        for (Map.Entry<String, String> entry : emitted.entrySet()) {
            sanityFailures.addAll(sanityViolations(dialect + "/" + entry.getKey(), entry.getValue()));
        }
        assertTrue(sanityFailures.isEmpty(),
                "emitted SQL failed structural sanity checks:\n" + String.join("\n", sanityFailures));

        if (update) {
            for (Map.Entry<String, String> entry : emitted.entrySet()) {
                Path file = goldenRoot.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
            return;
        }

        assertTrue(Files.isDirectory(goldenRoot),
                "no golden directory at " + goldenRoot.toAbsolutePath()
                        + " — generate it with -Dtitan.golden.update=true");

        // Exact set match: a renamed/removed artifact must fail, not silently orphan a golden.
        TreeSet<String> goldenFiles = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(goldenRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .forEach(file -> goldenFiles.add(goldenRoot.relativize(file).toString()));
        }
        assertEquals(new TreeSet<>(emitted.keySet()), goldenFiles,
                "golden file set under " + goldenRoot + " does not match the emitted artifact set; "
                        + "regenerate with -Dtitan.golden.update=true and review the diff");

        for (Map.Entry<String, String> entry : emitted.entrySet()) {
            String golden = Files.readString(goldenRoot.resolve(entry.getKey()));
            if (!golden.equals(entry.getValue())) {
                fail("""
                        golden mismatch for %s/%s

                        The emitted SQL changed. If the change is intentional, regenerate with
                        ./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.GoldenSqlTest \
                        -Dtitan.golden.update=true and review the golden diff.

                        --- golden ---
                        %s
                        --- emitted ---
                        %s""".formatted(dialect, entry.getKey(), golden, entry.getValue()));
            }
        }
    }

    /** Structural sanity rules every valid emitted artifact must satisfy (see class javadoc). */
    private static List<String> sanityViolations(String artifactId, String sql) {
        List<String> violations = new ArrayList<>();
        if (sql.isBlank()) {
            violations.add(artifactId + ": artifact is empty");
            return violations;
        }
        if (sql.contains("titan.dsl.")) {
            violations.add(artifactId + ": contains 'titan.dsl.' — a DSL expression leaked as raw Java text");
        }
        java.util.regex.Matcher marker = UNRESOLVED_MARKER_CALL.matcher(sql);
        if (marker.find()) {
            violations.add(artifactId + ": contains unresolved emitter marker call '"
                    + marker.group() + "...' — an emitter failed to rewrite a lowering marker");
        }
        List<String> statements = SqlScripts.split(sql);
        if (statements.isEmpty()) {
            violations.add(artifactId + ": SQL splitter found no statements");
        }
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            long quotes = statement.chars().filter(c -> c == '\'').count();
            if (quotes % 2 != 0) {
                violations.add(artifactId + ": statement " + i + " has unbalanced single quotes ("
                        + quotes + "):\n" + statement);
            }
            String controlByteViolation = rawControlByteInsideLiteral(statement);
            if (controlByteViolation != null) {
                violations.add(artifactId + ": statement " + i + " " + controlByteViolation
                        + " — control characters must be emitted via dialect escapes "
                        + "(B-3 / TG-BLK-006):\n" + statement);
            }
        }
        return violations;
    }

    /**
     * B-3 (TG-BLK-006) invariant: no raw ASCII control byte (0x00-0x1F, 0x7F) may appear inside
     * a single-quoted SQL literal. Uses the same naive quote-toggling model as the
     * balanced-quotes rule (both dialects escape embedded quotes by doubling, which toggles
     * out and back in without a control byte in between).
     */
    private static String rawControlByteInsideLiteral(String statement) {
        boolean inLiteral = false;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
                continue;
            }
            if (inLiteral && (c < 0x20 || c == 0x7F)) {
                return "contains raw control byte 0x%02X inside a string literal".formatted((int) c);
            }
        }
        return null;
    }

    /**
     * Goldens live in the source tree, not the classpath: the comparison must see the same
     * files a regeneration writes, and the diff must show up in {@code git status}. Test JVMs
     * run with the module directory as their working directory.
     */
    private static Path goldenRoot() {
        Path moduleRelative = Path.of("src", "test", "resources", "golden");
        if (Files.isDirectory(moduleRelative.getParent())) {
            return moduleRelative;
        }
        return Path.of("titan-transpiler").resolve(moduleRelative);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
