package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.jdbc.PermissiveScopeReport.PermissiveScopeEntry;
import io.titan.transpiler.jdbc.SqlSafetyResolver.RelaxationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The no-false-negative / no-false-positive coverage that is the point of the "effective permissive
 * scopes" report ({@code docs/design-document.md} §11.1): over a corpus exercising <b>every</b>
 * relaxation source, the report must list <b>exactly</b> the effectively-permissive entry points with
 * the right {@link RelaxationSource}.
 *
 * <p>A false negative (a permissive method the report misses) is a security blind spot — especially
 * the class-/build-level cases, which carry no {@code @SqlSafety} marker to grep for; a false
 * positive (a re-tightened or strict-default method listed) would mislead an audit. Both are asserted
 * here.</p>
 */
class PermissiveScopeReportEngineTest {

    @TempDir
    Path tempDir;

    private PermissiveScopeReport reportFor(String className, String source, SqlSafetyMode buildLevel) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        return new PermissiveScopeReportEngine().report(parsed, buildLevel);
    }

    private static Map<String, PermissiveScopeEntry> byMethod(PermissiveScopeReport report) {
        return report.permissive().stream()
                .collect(Collectors.toMap(PermissiveScopeEntry::method, e -> e));
    }

    private static Optional<PermissiveScopeEntry> entry(PermissiveScopeReport report, String method) {
        return report.permissive().stream().filter(e -> e.method().equals(method)).findFirst();
    }

    // --- the headline corpus: every source + every negative, in one method body -------------------

    @Test
    void listsExactlyTheEffectivelyPermissiveMethodsWithTheRightSource() throws Exception {
        // Build-level STRICT so the only permissive methods are the ones a method-/class-level
        // @SqlSafety(PERMISSIVE) opens up. This proves the greppable cases AND that strict-default
        // methods stay off the list.
        PermissiveScopeReport report = reportFor("Mixed", """
                import titan.dsl.*;
                import java.sql.*;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class Mixed {
                    // (1) METHOD_ANNOTATION: explicit method-level PERMISSIVE.
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void methodPermissive(Connection c) throws SQLException {}

                    // (2) CLASS_ANNOTATION: UNannotated method inheriting the class-level PERMISSIVE
                    // (no per-method @SqlSafety to grep for — the non-greppable case).
                    @StoredProcedure
                    public static void classPermissiveInherited(Connection c) throws SQLException {}

                    // (negative) re-tightened: method-level STRICT inside the permissive class MUST NOT appear.
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.STRICT)
                    public static void reTightened(Connection c) throws SQLException {}
                }
                """, SqlSafetyMode.STRICT);

        // Exactly the two permissive methods are listed (the re-tightened one is excluded).
        assertEquals(3, report.methodCount(), "all three entry points are examined");
        assertEquals(2, report.effectivelyPermissiveCount(), "only the two permissive scopes are listed");
        Map<String, PermissiveScopeEntry> listed = byMethod(report);
        assertEquals(Map.of("methodPermissive", RelaxationSource.METHOD_ANNOTATION,
                        "classPermissiveInherited", RelaxationSource.CLASS_ANNOTATION),
                listed.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().source())),
                "exactly the two permissive scopes, each with the source that decided it");
        assertFalse(listed.containsKey("reTightened"), "a re-tightened @SqlSafety(STRICT) must NOT appear");
        // Every listed entry is genuinely PERMISSIVE.
        assertTrue(report.permissive().stream().allMatch(e -> e.effectiveMode() == SqlSafetyMode.PERMISSIVE));
    }

    // --- BUILD_FLAG: a bare method under build-level permissive (no annotation anywhere) ----------

    @Test
    void buildLevelPermissiveListsBareMethodAsBuildFlagSource() throws Exception {
        // No @SqlSafety anywhere; the whole build is permissive. The method is relaxed solely by the
        // non-greppable build-level flag and MUST appear with source=BUILD_FLAG.
        PermissiveScopeReport report = reportFor("Bare", """
                import titan.dsl.*;
                import java.sql.*;

                class Bare {
                    @StoredProcedure
                    public static void noAnnotations(Connection c) throws SQLException {}
                }
                """, SqlSafetyMode.PERMISSIVE);

        assertEquals(1, report.methodCount());
        assertEquals(1, report.effectivelyPermissiveCount());
        PermissiveScopeEntry only = report.permissive().getFirst();
        assertEquals("noAnnotations", only.method());
        assertEquals(RelaxationSource.BUILD_FLAG, only.source(),
                "a method relaxed only by the build-level flag has no annotation to grep — it must "
                        + "still be reported, with source=BUILD_FLAG");
        assertEquals(SqlSafetyMode.PERMISSIVE, only.effectiveMode());
    }

    @Test
    void buildLevelStrictWithNoAnnotationsListsNothing() throws Exception {
        // The secure default: strict build, no annotations -> no permissive scopes -> empty report.
        // (No false positives.)
        PermissiveScopeReport report = reportFor("AllStrict", """
                import titan.dsl.*;
                import java.sql.*;

                class AllStrict {
                    @StoredProcedure
                    public static void a(Connection c) throws SQLException {}
                    @StoredFunction
                    public static int b(Connection c) throws SQLException { return 1; }
                }
                """, SqlSafetyMode.STRICT);

        assertEquals(2, report.methodCount());
        assertEquals(0, report.effectivelyPermissiveCount());
        assertTrue(report.permissive().isEmpty());
    }

    // --- nested/inner class: nearest-enclosing wins (with source attribution) ---------------------

    @Test
    void nestedClassResolvesToNearestEnclosingAnnotationWithCorrectSource() throws Exception {
        // Outer is PERMISSIVE; Inner has its own STRICT (the outer PERMISSIVE does NOT transitively
        // cover it); Plain is unannotated and inherits Outer's PERMISSIVE. Under build-level STRICT.
        PermissiveScopeReport report = reportFor("Nest", """
                import titan.dsl.*;
                import java.sql.*;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class Nest {
                    @StoredProcedure
                    public static void outerMethod(Connection c) throws SQLException {}

                    @SqlSafety(SqlSafetyMode.STRICT)
                    static class Inner {
                        @StoredProcedure
                        public static void innerMethod(Connection c) throws SQLException {}
                    }

                    static class Plain {
                        @StoredProcedure
                        public static void plainMethod(Connection c) throws SQLException {}
                    }
                }
                """, SqlSafetyMode.STRICT);

        assertEquals(3, report.methodCount());
        // outerMethod: inherits class-level PERMISSIVE -> listed, CLASS_ANNOTATION.
        assertEquals(RelaxationSource.CLASS_ANNOTATION, entry(report, "outerMethod").orElseThrow().source());
        // innerMethod: Inner's own STRICT re-tightens -> NOT listed (the outer PERMISSIVE does not reach in).
        assertTrue(entry(report, "innerMethod").isEmpty(),
                "an inner class's own @SqlSafety(STRICT) must keep it off the list (outer PERMISSIVE "
                        + "does not transitively cover nested classes)");
        // plainMethod: nested + unannotated -> nearest enclosing annotation is Outer's PERMISSIVE.
        assertEquals(RelaxationSource.CLASS_ANNOTATION, entry(report, "plainMethod").orElseThrow().source());
        assertEquals(2, report.effectivelyPermissiveCount());
    }

    // --- method-level PERMISSIVE survives a build that is permissive but a class that re-tightens --

    @Test
    void methodPermissiveOverridesReTighteningClassUnderPermissiveBuild() throws Exception {
        // Class STRICT (re-tightening the permissive build), but a method-level PERMISSIVE re-opens
        // just that method. Only that method is listed, source=METHOD_ANNOTATION; the sibling under
        // the class STRICT is excluded — proving precedence is honored, not just "any permissive flag".
        PermissiveScopeReport report = reportFor("Recovery", """
                import titan.dsl.*;
                import java.sql.*;

                @SqlSafety(SqlSafetyMode.STRICT)
                class Recovery {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void reopened(Connection c) throws SQLException {}

                    @StoredProcedure
                    public static void stillStrict(Connection c) throws SQLException {}
                }
                """, SqlSafetyMode.PERMISSIVE);

        assertEquals(2, report.methodCount());
        assertEquals(1, report.effectivelyPermissiveCount());
        PermissiveScopeEntry only = report.permissive().getFirst();
        assertEquals("reopened", only.method());
        assertEquals(RelaxationSource.METHOD_ANNOTATION, only.source());
        assertTrue(entry(report, "stillStrict").isEmpty(),
                "a class-level STRICT re-tightening the permissive build must keep its unannotated "
                        + "method off the list");
    }

    // --- JSON / text rendering of the non-greppable cases is visible and deterministic ------------

    @Test
    void jsonAndTextSurfaceTheNonGreppableSourcesDeterministically() throws Exception {
        // One method-level, one class-level, and one build-level permissive scope, all in one report
        // (build-level permissive so the bare method is BUILD_FLAG).
        PermissiveScopeReport report = reportFor("Audit", """
                import titan.dsl.*;
                import java.sql.*;

                class Audit {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void methodScoped(Connection c) throws SQLException {}

                    @StoredProcedure
                    public static void buildScoped(Connection c) throws SQLException {}
                }
                """, SqlSafetyMode.PERMISSIVE);

        String json = report.toJson();
        // The bySource histogram and per-method source make the non-greppable cases visible.
        assertTrue(json.contains("\"effectivelyPermissive\": 2"), json);
        assertTrue(json.contains("\"METHOD_ANNOTATION\": 1"), json);
        assertTrue(json.contains("\"BUILD_FLAG\": 1"), json);
        assertTrue(json.contains("\"source\": \"BUILD_FLAG\""), json);
        assertTrue(json.contains("\"source\": \"METHOD_ANNOTATION\""), json);

        String text = report.toText();
        assertTrue(text.contains("effectivelyPermissive=2"), text);
        assertTrue(text.contains("Audit#buildScoped"), text);
        assertTrue(text.contains("source=BUILD_FLAG"), text);
        assertTrue(text.contains("source=METHOD_ANNOTATION"), text);

        // Determinism: identical input renders byte-identical output.
        assertEquals(json, report.toJson());
        assertEquals(text, report.toText());
    }

    @Test
    void emptySourcesProduceAnEmptyReport() {
        PermissiveScopeReport report = new PermissiveScopeReportEngine().report(List.of(), List.of(), "permissive");
        assertEquals(0, report.methodCount());
        assertEquals(0, report.effectivelyPermissiveCount());
        assertTrue(report.toJson().contains("\"effectivelyPermissive\": 0"));
    }

    // --- WS-C Phase 3 Rung 3: a method that EMITS an identifier/raw-fragment splice is surfaced -----

    @Test
    void surfacesAMethodThatEmitsAnIdentifierOrRawFragmentSplice() throws Exception {
        // The ratchet: a method whose @SqlSafety(PERMISSIVE) makes Rung 3 emit a dynamic-identifier (ORDER
        // BY) splice AND one that emits a raw-fragment (WHERE) splice MUST both be surfaced by the report —
        // these are the non-greppable risk the report exists to catch. A strict sibling (which would REJECT
        // the same splice at lowering) must NOT be listed, so the report tracks exactly the relaxed scopes.
        PermissiveScopeReport report = reportFor("Splices", """
                import titan.dsl.*;
                import java.sql.*;

                class Splices {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void dynamicOrderBy(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }

                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void rawPredicate(Connection c, String predicate) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE " + predicate);
                        ps.executeUpdate();
                    }

                    // A strict sibling doing the SAME identifier splice would be E004-rejected at lowering —
                    // and is correctly NOT counted as an effectively-permissive (relaxed) scope.
                    @StoredProcedure
                    public static void strictOrderBy(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }
                }
                """, SqlSafetyMode.STRICT);

        assertEquals(3, report.methodCount(), "all three entry points are examined");
        assertEquals(2, report.effectivelyPermissiveCount(),
                "both permissive splice methods are surfaced (the ratchet); the strict sibling is not");
        assertEquals(RelaxationSource.METHOD_ANNOTATION, entry(report, "dynamicOrderBy").orElseThrow().source(),
                "the identifier-splice method is surfaced with its relaxation source");
        assertEquals(RelaxationSource.METHOD_ANNOTATION, entry(report, "rawPredicate").orElseThrow().source(),
                "the raw-fragment-splice method is surfaced with its relaxation source");
        assertTrue(entry(report, "strictOrderBy").isEmpty(),
                "the strict sibling (which would REJECT the splice) must NOT be counted as relaxed");
    }
}
