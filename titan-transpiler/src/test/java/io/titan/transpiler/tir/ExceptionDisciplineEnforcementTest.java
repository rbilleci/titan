package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mechanical enforcement of plan §10.4 invariant 1 (Phase 1 exit criterion): lowering and
 * emission report failures as structured {@code TitanDiagnostic}s, never as raw
 * {@code IllegalArgumentException}s. The only other permitted throw is
 * {@code IllegalStateException} whose message starts with {@code "internal: "} — a documented
 * internal-invariant assertion on programmer error (unreachable from user sources).
 *
 * <p>Implemented as a dependency-free source-text scan (titan-transpiler has no ArchUnit on its
 * test classpath; style follows titan-dsl's {@code ModuleBoundaryEnforcementTest}, which also
 * resolves the module directory from the test runtime path and inspects files directly).</p>
 */
class ExceptionDisciplineEnforcementTest {

    /** Lowering classes, the lowering facade, the pipeline, and the SQL emitters. */
    private static final List<String> SCOPED_SOURCES = List.of(
            "StatementLowerer.java",
            "ExpressionLowerer.java",
            "DslQueryLowerer.java",
            "DslChainParser.java",
            "LowererSupport.java",
            "JavaToTirLowerer.java",
            "TranspilationPipeline.java",
            "AbstractSqlEmitter.java",
            "PostgreSqlEmitter.java",
            "MySqlEmitter.java",
            "DialectDispatchingEmitter.java");

    private static final String RAW_ILLEGAL_ARGUMENT = "throw new IllegalArgumentException";
    private static final String ILLEGAL_STATE = "throw new IllegalStateException";

    /** How far past a throw keyword the message literal may start (multi-line throw statements). */
    private static final int MESSAGE_LOOKAHEAD_CHARS = 200;

    @Test
    void loweringAndEmissionNeverThrowRawIllegalArgumentException() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String fileName : SCOPED_SOURCES) {
            String source = Files.readString(resolveTirSource(fileName));
            int index = source.indexOf(RAW_ILLEGAL_ARGUMENT);
            while (index >= 0) {
                violations.add(fileName + ":" + lineNumberAt(source, index));
                index = source.indexOf(RAW_ILLEGAL_ARGUMENT, index + 1);
            }
        }
        assertTrue(violations.isEmpty(),
                "Lowering/emission classes must throw TitanDiagnosticException (via "
                        + "LowererSupport.unsupportedFeature/loweringError or "
                        + "AbstractSqlEmitter.unsupportedOnDialect), never raw "
                        + "IllegalArgumentException. Violations: " + violations);
    }

    @Test
    void everyIllegalStateThrowIsADocumentedInternalInvariant() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String fileName : SCOPED_SOURCES) {
            String source = Files.readString(resolveTirSource(fileName));
            int index = source.indexOf(ILLEGAL_STATE);
            while (index >= 0) {
                int end = Math.min(source.length(), index + ILLEGAL_STATE.length() + MESSAGE_LOOKAHEAD_CHARS);
                String messageWindow = source.substring(index, end);
                if (!messageWindow.contains("\"internal: ")) {
                    violations.add(fileName + ":" + lineNumberAt(source, index));
                }
                index = source.indexOf(ILLEGAL_STATE, index + 1);
            }
        }
        assertTrue(violations.isEmpty(),
                "IllegalStateException in lowering/emission is reserved for internal-invariant "
                        + "assertions and must carry an 'internal: ' message prefix plus an "
                        + "explanatory comment. Violations: " + violations);
    }

    @Test
    void scopedSourceListMatchesTheFilesOnDisk() throws Exception {
        // Guards the scan itself: if a scoped class is renamed, fail loudly instead of
        // silently scanning nothing.
        for (String fileName : SCOPED_SOURCES) {
            Path source = resolveTirSource(fileName);
            assertTrue(Files.isRegularFile(source), "Scoped source missing on disk: " + source);
        }
    }

    private static int lineNumberAt(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Path resolveTirSource(String fileName) throws IOException, URISyntaxException {
        return resolveModuleDirectory()
                .resolve(Path.of("src", "main", "java", "io", "titan", "transpiler", "tir"))
                .resolve(fileName);
    }

    private static Path resolveModuleDirectory() throws URISyntaxException {
        Path classesDir = Path.of(ExceptionDisciplineEnforcementTest.class
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
                    "Unable to locate titan-transpiler module directory from test runtime path: " + classesDir);
        }
        return moduleDir;
    }
}
