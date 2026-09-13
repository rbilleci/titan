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
import org.junit.jupiter.api.Test;

/**
 * Golden-file gate for JDBC-input emitter output (WS-C Phase 2, sub-phase 2c). Transpiles the
 * {@link JdbcGoldenCorpus} fixtures — the spec's §6.1/§6.2 worked examples, the same sources
 * {@link JdbcEmitterDeployabilityIT} proves deploy and execute on their live source-dialect database —
 * and compares every emitted artifact byte-for-byte against the checked-in golden files under
 * {@code src/test/resources/golden/jdbc/<id>/}.
 *
 * <p><b>Single-dialect by construction.</b> Unlike {@link GoldenSqlTest}, which emits every DSL
 * fixture to <em>both</em> targets, each JDBC fixture is transpiled to <em>only</em> its
 * {@link JdbcGoldenCorpus.Fixture#sourceDialect()} (the §1.1a single-dialect-native reframe). A fixture
 * targeting PostgreSQL has no MySQL golden, and vice versa.</p>
 *
 * <h2>Regenerating goldens</h2>
 *
 * <pre>{@code
 * ./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.JdbcGoldenTest \
 *     -Dtitan.jdbcgolden.update=true
 * }</pre>
 *
 * then review the {@code git diff} of {@code src/test/resources/golden/jdbc/} and commit it with the
 * emitter/lowering change. The update run still executes the structural sanity assertions (mirroring
 * {@link GoldenSqlTest}), so a regeneration cannot check in structurally broken SQL.
 */
class JdbcGoldenTest {

    /** Marker-call pattern the emitters are supposed to rewrite; must never appear in SQL. */
    private static final Pattern UNRESOLVED_MARKER_CALL = Pattern.compile("__titan_\\w+\\s*\\(");

    @Test
    void emittedJdbcArtifactsMatchCheckedInGoldens() throws Exception {
        boolean update = Boolean.getBoolean("titan.jdbcgolden.update");
        Path goldenRoot = goldenRoot();
        if (update) {
            deleteRecursively(goldenRoot);
        }

        // Fixture sources are written to a stable module-relative path, NOT a @TempDir: emitted MySQL
        // artifacts embed the source path in null-guard messages (e.g. the BigDecimal.ZERO/getX NPE
        // parity guards) AND in `-- titan:source:` comments, so a per-run temp path would make the
        // goldens nondeterministic. This mirrors GoldenSqlTest's build/golden-fixtures approach.
        Path fixtureDir = Files.createDirectories(Path.of("build", "jdbc-golden-fixtures"));

        // The baked path is exactly the *relative* string `build/jdbc-golden-fixtures/<id>.java` (the
        // emitter renders the source path verbatim — AbstractSqlEmitter#sourceCommentForLocation), which
        // is what makes the goldens CWD-independent: the embedded token is the same whether the test JVM
        // runs from the module dir or the repo root (goldenRoot() supports both). Pin that invariant so
        // an accidental absolutize of fixtureDir (which WOULD bake a machine-specific path into every
        // MySQL artifact and silently break the byte-goldens) fails LOUDLY here, not as a confusing diff.
        assertFalse(fixtureDir.isAbsolute(),
                "JDBC golden fixture dir must stay relative so the source path baked into emitted SQL is "
                        + "deterministic across working directories; was " + fixtureDir);

        // fixture-id/artifact-file -> emitted SQL, in stable emission order.
        Map<String, String> emitted = new LinkedHashMap<>();
        for (JdbcGoldenCorpus.Fixture fixture : JdbcGoldenCorpus.fixtures()) {
            Path sourceFile = fixtureDir.resolve(fixture.id() + ".java");
            Files.writeString(sourceFile, fixture.source());
            String dialect = fixture.sourceDialect().canonicalTarget();
            // The fixture's catalog (when present) is threaded so §6.3 I-7 resolves the RETURNING key
            // column; null for fixtures that do not need it (the bare overload's behavior).
            List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                    List.of(sourceFile),
                    List.of(),
                    List.of(dialect),
                    List.of(JdbcGoldenCorpus.SCHEMA),
                    true,
                    List.of(),
                    false,
                    List.of(),
                    false,
                    false,
                    fixture.schemaModel());
            assertFalse(generated.isEmpty(), fixture.id() + ": pipeline emitted no artifacts for " + dialect);
            int index = 0;
            for (TranspilationPipeline.GeneratedSql artifact : generated) {
                // Single-dialect by construction: the only artifact target is the source dialect.
                assertEquals(dialect, artifact.target(),
                        fixture.id() + ": single-source-dialect transpile returned a foreign-target artifact");
                String fileName = "%02d-%s.sql".formatted(index++, artifact.artifactName());
                emitted.put(fixture.id() + "/" + fileName, artifact.sql());
            }
        }

        List<String> sanityFailures = new ArrayList<>();
        for (Map.Entry<String, String> entry : emitted.entrySet()) {
            sanityFailures.addAll(sanityViolations(entry.getKey(), entry.getValue()));
        }
        assertTrue(sanityFailures.isEmpty(),
                "emitted JDBC SQL failed structural sanity checks:\n" + String.join("\n", sanityFailures));

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
                        + " — generate it with -Dtitan.jdbcgolden.update=true");

        // Exact set match: a renamed/removed artifact must fail, not silently orphan a golden.
        TreeSet<String> goldenFiles = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(goldenRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .forEach(file -> goldenFiles.add(goldenRoot.relativize(file).toString()));
        }
        assertEquals(new TreeSet<>(emitted.keySet()), goldenFiles,
                "golden file set under " + goldenRoot + " does not match the emitted artifact set; "
                        + "regenerate with -Dtitan.jdbcgolden.update=true and review the diff");

        for (Map.Entry<String, String> entry : emitted.entrySet()) {
            String golden = Files.readString(goldenRoot.resolve(entry.getKey()));
            if (!golden.equals(entry.getValue())) {
                fail("""
                        JDBC golden mismatch for %s

                        The emitted SQL changed. If the change is intentional, regenerate with
                        ./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.JdbcGoldenTest \
                        -Dtitan.jdbcgolden.update=true and review the golden diff.

                        --- golden ---
                        %s
                        --- emitted ---
                        %s""".formatted(entry.getKey(), golden, entry.getValue()));
            }
        }
    }

    /** Structural sanity rules every valid emitted artifact must satisfy (mirrors {@link GoldenSqlTest}). */
    private static List<String> sanityViolations(String artifactId, String sql) {
        List<String> violations = new ArrayList<>();
        if (sql.isBlank()) {
            violations.add(artifactId + ": artifact is empty");
            return violations;
        }
        if (sql.contains("titan.dsl.")) {
            violations.add(artifactId + ": contains 'titan.dsl.' — a Java expression leaked as raw text");
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
        }
        return violations;
    }

    /**
     * Goldens live in the source tree, not the classpath: the comparison must see the same files a
     * regeneration writes, and the diff must show up in {@code git status}. Test JVMs run with the
     * module directory as their working directory.
     */
    private static Path goldenRoot() {
        Path moduleRelative = Path.of("src", "test", "resources", "golden", "jdbc");
        if (Files.isDirectory(moduleRelative.getParent().getParent())) {
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
