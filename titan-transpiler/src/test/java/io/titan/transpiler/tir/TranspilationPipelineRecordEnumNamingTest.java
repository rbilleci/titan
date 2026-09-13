package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B-2 (TG-BLK-005) regression suite: generated record/enum SQL names derive from the
 * declaration's source-local qualified name (never the bare simple name), member-level
 * functions use the {@link SqlNames} {@code "__"} join that snake-casing cannot produce, and
 * the residual collision space is rejected at transpile time with positioned TITAN-E007
 * diagnostics naming both declarations.
 */
class TranspilationPipelineRecordEnumNamingTest {

    @TempDir
    Path tempDir;

    /**
     * Shape 1 (silent last-wins collapse): two classes each declaring {@code record SortPath}
     * must both emit their DDL, and each routine must call its own record's functions.
     */
    @Test
    void sameSimpleNameRecordsInDifferentClassesBothEmit() throws Exception {
        Path retrievalA = tempDir.resolve("RetrievalA.java");
        Files.writeString(retrievalA, """
                import titan.dsl.StoredFunction;

                class RetrievalA {
                    record SortPath(String path, int depth) {}

                    @StoredFunction
                    public static int depthOfA(int depth) {
                        SortPath sortPath = new SortPath("a", depth);
                        return sortPath.depth();
                    }
                }
                """);
        Path retrievalB = tempDir.resolve("RetrievalB.java");
        Files.writeString(retrievalB, """
                import titan.dsl.StoredFunction;

                class RetrievalB {
                    record SortPath(String path, int depth) {}

                    @StoredFunction
                    public static int depthOfB(int depth) {
                        SortPath sortPath = new SortPath("b", depth);
                        return sortPath.depth();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = transpile(List.of(retrievalA, retrievalB));

        List<TranspilationPipeline.GeneratedSql> recordArtifacts = generated.stream()
                .filter(artifact -> "record-type".equals(artifact.artifactKind()))
                .toList();
        assertEquals(2, recordArtifacts.size(),
                "both same-simple-name records must emit (no last-wins collapse): " + names(generated));
        assertEquals(
                Set.of("__record_retrieval_a_sort_path", "__record_retrieval_b_sort_path"),
                Set.of(recordArtifacts.get(0).sqlName(), recordArtifacts.get(1).sqlName()));
        assertPackageClean(generated);

        TranspilationPipeline.GeneratedSql routineA = artifact(generated, "depthOfA");
        assertTrue(routineA.sql().contains("__record_retrieval_a_sort_path__new"), routineA.sql());
        assertTrue(routineA.sql().contains("__record_retrieval_a_sort_path__depth"), routineA.sql());
        assertFalse(routineA.sql().contains("__record_retrieval_b_sort_path"),
                "depthOfA must resolve its own record, not RetrievalB's: " + routineA.sql());

        TranspilationPipeline.GeneratedSql routineB = artifact(generated, "depthOfB");
        assertTrue(routineB.sql().contains("__record_retrieval_b_sort_path__new"), routineB.sql());
        assertFalse(routineB.sql().contains("__record_retrieval_a_sort_path"), routineB.sql());

        // The dependency edges must also resolve to each routine's own record type.
        assertTrue(routineA.dependsOn().stream().anyMatch(ref -> ref.name().equals("__record_retrieval_a_sort_path")),
                "depthOfA must depend on its own record type: " + routineA.dependsOn());
        assertTrue(routineB.dependsOn().stream().anyMatch(ref -> ref.name().equals("__record_retrieval_b_sort_path")),
                "depthOfB must depend on its own record type: " + routineB.dependsOn());
    }

    /**
     * Shape 2 (prefix-join ambiguity): {@code record Sort}'s {@code path} accessor and
     * {@code record SortPath}'s type name used to both render as {@code __record_sort_path}.
     * The {@code "__"} member join keeps them distinct, so both records coexist cleanly.
     */
    @Test
    void recordSortWithPathComponentCoexistsWithRecordSortPath() throws Exception {
        Path source = tempDir.resolve("SortFixture.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                record Sort(String path) {}

                record SortPath(String value, int depth) {}

                class SortFixture {
                    @StoredFunction
                    public static String describeSort(String path, int depth) {
                        Sort sort = new Sort(path);
                        SortPath sortPath = new SortPath(path, depth);
                        return sort.path() + ":" + sortPath.value();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = transpile(List.of(source));

        assertEquals(2, generated.stream().filter(a -> "record-type".equals(a.artifactKind())).count());
        TranspilationPipeline.GeneratedSql sort = artifact(generated, "Sort");
        TranspilationPipeline.GeneratedSql sortPath = artifact(generated, "SortPath");
        // Top-level records keep their historic type names...
        assertEquals("__record_sort", sort.sqlName());
        assertEquals("__record_sort_path", sortPath.sqlName());
        // ...and Sort's component accessor joins with "__", which snake-casing can never
        // produce, so it cannot collide with SortPath's type name.
        assertTrue(sort.sqlObjects().stream().anyMatch(object -> object.name().equals("__record_sort__path")),
                "Sort.path accessor must be __record_sort__path: " + sort.sqlObjects());
        assertPackageClean(generated);

        TranspilationPipeline.GeneratedSql routine = artifact(generated, "describeSort");
        assertTrue(routine.sql().contains("\"__record_sort__path\"(v_sort)"), routine.sql());
        assertTrue(routine.sql().contains("\"__record_sort_path__value\"(v_sort_path)"), routine.sql());
    }

    /**
     * Shape 3 (cross-kind coexistence): {@code enum Operation} and {@code record Operation}
     * must both produce artifacts — their SQL names differ by kind prefix and the artifact
     * files differ by kind token (asserted at the Gradle-task level in the plugin module).
     */
    @Test
    void enumAndRecordWithSameNameBothEmit() throws Exception {
        Path enumSource = tempDir.resolve("OperationEnum.java");
        Files.writeString(enumSource, """
                package alpha;

                enum Operation { CREATE, DELETE }
                """);
        Path recordSource = tempDir.resolve("OperationRecord.java");
        Files.writeString(recordSource, """
                package beta;

                record Operation(int id) {}
                """);

        List<TranspilationPipeline.GeneratedSql> generated = transpile(List.of(enumSource, recordSource));

        TranspilationPipeline.GeneratedSql enumArtifact = generated.stream()
                .filter(a -> "enum-lookup".equals(a.artifactKind())).findFirst().orElseThrow();
        TranspilationPipeline.GeneratedSql recordArtifact = generated.stream()
                .filter(a -> "record-type".equals(a.artifactKind())).findFirst().orElseThrow();
        assertEquals("__enum_operation", enumArtifact.sqlName());
        assertEquals("__record_operation", recordArtifact.sqlName());
        assertPackageClean(generated);
    }

    /** Nested declarations are qualified by their enclosing type chain. */
    @Test
    void nestedRecordAndEnumGetQualifiedSqlNames() throws Exception {
        Path source = tempDir.resolve("ProjectionRetrieval.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class ProjectionRetrieval {
                    record SortPath(String path, int depth) {}

                    enum Mode { FAST, FULL }

                    @StoredFunction
                    public static int sortDepth(int depth) {
                        SortPath sortPath = new SortPath("p", depth);
                        return sortPath.depth();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = transpile(List.of(source));

        TranspilationPipeline.GeneratedSql record = artifact(generated, "SortPath");
        assertEquals("record-type", record.artifactKind());
        assertEquals("ProjectionRetrieval.SortPath", record.className());
        assertEquals("__record_projection_retrieval_sort_path", record.sqlName());
        assertTrue(record.sql().contains("__record_projection_retrieval_sort_path__new"), record.sql());
        assertTrue(record.sql().contains("__record_projection_retrieval_sort_path__depth"), record.sql());

        TranspilationPipeline.GeneratedSql enumArtifact = artifact(generated, "Mode");
        assertEquals("ProjectionRetrieval.Mode", enumArtifact.className());
        assertEquals("__enum_projection_retrieval_mode", enumArtifact.sqlName());

        TranspilationPipeline.GeneratedSql routine = artifact(generated, "sortDepth");
        assertTrue(routine.sql().contains("\"__record_projection_retrieval_sort_path__new\"('p', p_depth)"), routine.sql());
        assertPackageClean(generated);
    }

    /**
     * Residual collision space: two declarations whose qualified names snake-case identically
     * are a positioned transpile-time TITAN-E007 naming both declarations — not a last-wins
     * emission caught later (if at all) by the package-time install-plan gate.
     */
    @Test
    void sameQualifiedNameRecordsAreATranspileTimeDiagnosticNamingBothDeclarations() throws Exception {
        Path first = tempDir.resolve("RecordAlpha.java");
        Files.writeString(first, """
                package alpha;

                record SortPath(int depth) {}
                """);
        Path second = tempDir.resolve("RecordBeta.java");
        Files.writeString(second, """
                package beta;

                record SortPath(int depth) {}
                """);

        TitanDiagnosticException exception = assertThrows(
                TitanDiagnosticException.class,
                () -> transpile(List.of(first, second)));

        List<TitanDiagnostic> collisions = exception.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.E007)
                .toList();
        assertEquals(1, collisions.size(), exception.getMessage());
        TitanDiagnostic collision = collisions.getFirst();
        assertTrue(collision.message().contains("RecordAlpha.java:3"), collision.message());
        assertTrue(collision.message().contains("RecordBeta.java:3"), collision.message());
        assertTrue(collision.message().contains("__record_sort_path"), collision.message());
        assertTrue(collision.location() != null && collision.location().contains("RecordBeta.java:3"),
                "diagnostic must be positioned: " + collision);
    }

    /** Enum variant of the residual-collision diagnostic. */
    @Test
    void sameQualifiedNameEnumsAreATranspileTimeDiagnostic() throws Exception {
        Path first = tempDir.resolve("EnumAlpha.java");
        Files.writeString(first, """
                package alpha;

                enum Operation { CREATE }
                """);
        Path second = tempDir.resolve("EnumBeta.java");
        Files.writeString(second, """
                package beta;

                enum Operation { DELETE }
                """);

        TitanDiagnosticException exception = assertThrows(
                TitanDiagnosticException.class,
                () -> transpile(List.of(first, second)));

        List<TitanDiagnostic> collisions = exception.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.E007)
                .toList();
        assertEquals(1, collisions.size(), exception.getMessage());
        TitanDiagnostic collision = collisions.getFirst();
        assertTrue(collision.message().contains("EnumAlpha.java:3"), collision.message());
        assertTrue(collision.message().contains("EnumBeta.java:3"), collision.message());
        assertTrue(collision.message().contains("__enum_operation"), collision.message());
    }

    private static List<TranspilationPipeline.GeneratedSql> transpile(List<Path> sources) {
        return new TranspilationPipeline().transpile(
                sources,
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);
    }

    private static TranspilationPipeline.GeneratedSql artifact(
            List<TranspilationPipeline.GeneratedSql> generated,
            String artifactName
    ) {
        return generated.stream()
                .filter(item -> item.artifactName().equals(artifactName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no artifact named '" + artifactName + "' in " + names(generated)));
    }

    /** Package-clean precondition: every described SQL object name is unique across artifacts. */
    private static void assertPackageClean(List<TranspilationPipeline.GeneratedSql> generated) {
        Set<String> seen = new HashSet<>();
        for (TranspilationPipeline.GeneratedSql item : generated) {
            for (SqlObject object : item.sqlObjects()) {
                String identity = item.target() + "." + object.schema() + "." + object.name();
                assertTrue(seen.add(identity),
                        "duplicate generated object name (install-plan gate would fail): " + identity);
            }
        }
    }

    private static List<String> names(List<TranspilationPipeline.GeneratedSql> generated) {
        return generated.stream().map(TranspilationPipeline.GeneratedSql::artifactName).toList();
    }
}
