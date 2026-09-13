package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3 Rung 4 — the security-critical <b>no-false-accept</b> lock for §3.6-form-2 collection
 * binding (design contract §8 posture). It drives {@link JdbcCollectionInRecognizer#recognize} directly
 * (decoupled from any downstream lowering), asserting the use proof returns a plan ONLY for the clean
 * runtime-sized {@code List}-driven {@code IN} (the run sized by {@code coll.size()} and bound
 * per-element from the SAME collection, or the {@code setArray}-over-collection form) and refuses
 * ({@link Optional#empty()}) the moment the collection is used for anything but that one {@code IN}. A
 * mis-fire would change semantics for some input (or splice a value), so in doubt it must NOT array-bind.
 */
class JdbcCollectionInRecognizerTest {

    @TempDir
    Path tempDir;

    private static String wrap(String methodBody) {
        return """
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, List<Long> ids, List<Long> other,
                                           String region, String b, int q, int minQty) throws SQLException {
                """ + methodBody + """
                    }
                    static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }
                    static void sink(List<Long> v) { }
                }
                """;
    }

    private Optional<JdbcCollectionInRecognizer.CollectionInPlan> recognize(String methodBody) throws Exception {
        return recognizeFullSource(wrap(methodBody), "run");
    }

    private Optional<JdbcCollectionInRecognizer.CollectionInPlan> recognizeFullSource(
            String source, String methodName) throws Exception {
        Path sourceFile = tempDir.resolve("Fixture.java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        CompilationUnitTree unit = parsed.compilationUnits().getFirst();

        BlockTree[] body = {null};
        TreePath[] bodyPath = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (node.getName().contentEquals(methodName) && node.getBody() != null && body[0] == null) {
                    body[0] = node.getBody();
                    bodyPath[0] = new TreePath(getCurrentPath(), node.getBody());
                }
                return super.visitMethod(node, unused);
            }
        }.scan(unit, null);

        JdbcTypeOracle oracle = new JdbcTypeOracle(parsed);
        JdbcShapes shapes = new JdbcShapes(parsed, oracle);
        return new JdbcCollectionInRecognizer(parsed, shapes, oracle)
                .recognize(body[0].getStatements(), bodyPath[0], bodyPath[0]);
    }

    // ===== positive recognition ==================================================================

    @Test
    void recognizesPerElementLoopBind() throws Exception {
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the canonical per-element loop bind must be recognized");
        assertEquals("customer_id", plan.get().lhs());
        assertEquals(JdbcCollectionInRecognizer.ElementType.BIGINT, plan.get().elementType());
        assertEquals("ids", plan.get().collectionName());
        assertTrue(plan.get().bindsBeforeRun().isEmpty() && plan.get().bindsAfterRun().isEmpty());
    }

    @Test
    void recognizesSetArrayBind() throws Exception {
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (?)");
                ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the explicit setArray-over-collection form must be recognized");
        assertEquals("customer_id", plan.get().lhs());
        assertEquals("ids", plan.get().collectionName());
    }

    @Test
    void recognizesBindBeforeTheInRun() throws Exception {
        // A bind BEFORE the run (the run at the end — the supported shape, since a `?` after a runtime-sized
        // run would shift at runtime). The run ordinal start is 2 (region is `?` #1); the per-element loop
        // binds i + 2 (ords 2..). region is the before-bind.
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE orders SET flagged = 1 WHERE region = ? AND customer_id IN ("
                                + placeholders(ids.size()) + ")");
                ps.setString(1, region);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 2, ids.get(i));
                }
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a bind before the IN run (run at end) must not block recognition");
        assertEquals("customer_id", plan.get().lhs());
        assertEquals(1, plan.get().bindsBeforeRun().size(), "region is the pre-IN bind");
        assertTrue(plan.get().bindsAfterRun().isEmpty());
    }

    @Test
    void bindAfterRuntimeSizedRunBlocks() throws Exception {
        // A literal-ordinal bind AFTER the runtime-sized run cannot be statically correct (the run shifts
        // every ordinal after it by the collection's runtime size) -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE orders SET flagged = 1 WHERE customer_id IN ("
                                + placeholders(ids.size()) + ") AND qty >= ?");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.setInt(2, minQty);
                ps.executeUpdate();
                """).isEmpty(), "a literal-ordinal bind after the runtime-sized run must block array-binding");
    }

    @Test
    void recognizesStringElement() throws Exception {
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, List<String> codes) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM events WHERE code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")");
                        for (int i = 0; i < codes.size(); i++) {
                            ps.setString(i + 1, codes.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent());
        assertEquals(JdbcCollectionInRecognizer.ElementType.TEXT, plan.get().elementType());
    }

    // ===== no-false-accept =======================================================================

    @Test
    void valueSpliceIsNotArrayBound() throws Exception {
        // A scalar value spliced into the text (not a collection-driven IN) must NOT be array-bound.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE id = " + minQty);
                ps.executeUpdate();
                """).isEmpty(), "a scalar value splice is not a collection-IN array-bind");
    }

    @Test
    void partialRunWithMissingPerElementBindBlocks() throws Exception {
        // The run is sized by ids.size() but there is NO per-element bind loop — refuse (a partial/absent
        // bind is not a clean single-collection per-element run).
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                ps.executeUpdate();
                """).isEmpty(), "a size()-sized run with no per-element bind loop must not array-bind");
    }

    @Test
    void mixedBindFromDifferentCollectionBlocks() throws Exception {
        // The run is sized by ids.size() but the loop binds from `other.get(i)`, a DIFFERENT collection.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, other.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "binding the run from a different collection must not array-bind");
    }

    @Test
    void listUsedInValuePositionElsewhereBlocks() throws Exception {
        // ids drives the IN, but is ALSO read elsewhere (ids.get(0) in a value position) — a use beyond
        // the one IN-source -> refuse (the whole list no longer cleanly drives only the IN).
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                PreparedStatement other2 = c.prepareStatement("UPDATE accounts SET top = ? WHERE id = 1");
                other2.setLong(1, ids.get(0));
                other2.executeUpdate();
                """).isEmpty(), "the list used in a value position elsewhere must block array-binding");
    }

    @Test
    void listPassedToMethodBlocks() throws Exception {
        // ids escapes by being passed to a method -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                sink(ids);
                ps.executeUpdate();
                """).isEmpty(), "the list passed to a method (escape) must block array-binding");
    }

    @Test
    void nonSizeSizingBlocks() throws Exception {
        // The run is sized by a literal (a fixed-arity placeholder run), not coll.size() — that is the
        // Rung-1 fixed-? substrate, not a runtime-sized collection bind. Refuse here (no collection drives
        // the run length).
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(3) + ")");
                ps.setLong(1, ids.get(0));
                ps.setLong(2, ids.get(1));
                ps.setLong(3, ids.get(2));
                ps.executeUpdate();
                """).isEmpty(), "a non-size() (literal) run sizing must not array-bind");
    }

    @Test
    void secondSizeUseBlocks() throws Exception {
        // ids.size() is used a SECOND time (outside the run) — a use beyond the one IN-source -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.setInt(99, ids.size());
                ps.executeUpdate();
                """).isEmpty(), "a second ids.size() use must block array-binding");
    }

    @Test
    void notInBlocks() throws Exception {
        // NOT IN is not array-bind-equivalent on a NULL-bearing list (NULL-poisoning differs) -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id NOT IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "NOT IN must not array-bind (empty/NULL equivalence differs)");
    }

    // ----- string-literal-embedded membership locks (security: no element/marker reaches a literal) -----

    @Test
    void literalInIsNotArrayBound() throws Exception {
        // FORM B SECURITY LOCK: the only `IN (?)` lies INSIDE a single-quoted string literal (the `note`
        // value), so there is NO real membership. Array-binding here would splice the marker into the
        // literal (note = 'see = ANY(?)' / a JSON_TABLE expr) and leave the bound array param dangling
        // (no real placeholder) -> corrupt, non-deployable SQL on both dialects. Must REFUSE.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement("UPDATE t SET note = 'see IN (?)' WHERE id = 5");
                ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                ps.executeUpdate();
                """).isEmpty(), "an `IN (?)` inside a string literal is not a membership — must not array-bind");
    }

    @Test
    void formALiteralInOpenParenIsNotArrayBound() throws Exception {
        // FORM A SECURITY LOCK: the constant prefix ends in `'a IN (` — but that `IN (` is the tail of a
        // string literal being assembled (`'a IN (?,?,…)'`), NOT a SQL membership opener (the `'` is still
        // open). Array-binding would splice the marker INTO the literal and dangle the array param. REFUSE.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement("UPDATE t SET note = 'a IN (" + placeholders(ids.size()) + ")'");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "a literal `'a IN ('` tail (open quote) is not a membership — must not array-bind");
    }

    @Test
    void formBLiteralInPlusRealInStillRecognizesTheRealOne() throws Exception {
        // FORM B POSITIVE control for the literal-exclusion: a decoy `IN (?)` inside a `note` literal AND a
        // REAL `id IN (?)` membership. The real (out-of-literal) `IN (?)` must be the recognized one (the
        // literal one is skipped, not double-counted as a second match) — lhs = id, the marker takes the
        // real site. Proves the fix excludes literal matches without over-rejecting the genuine membership.
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement("UPDATE t SET note = 'see IN (?)' WHERE id IN (?)");
                ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a real `id IN (?)` alongside a literal decoy must still be recognized");
        assertEquals("id", plan.get().lhs(), "the recognized membership is the REAL one, not the literal decoy");
        // The decoy literal stays verbatim in the surrounding text; the marker carries only the real site.
        assertTrue(plan.get().sqlPrefix().contains("'see IN (?)'"),
                "the decoy `IN (?)` literal must survive untouched in the prefix (no marker spliced into it)");
    }

    @Test
    void escapedQuoteBeforeRunStillRecognized() throws Exception {
        // POSITIVE control for the FORM A literal guard: a CLOSED literal with an escaped `''` quote
        // (`'it''s ok'`) before the real `id IN (` run. The quote walk closes the literal correctly, so the
        // `IN (` is outside any open literal -> still recognized (the guard must not over-reject a closed
        // literal that merely precedes the membership). region is the pre-IN bind at ordinal 1.
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE t SET note = 'it''s ok' WHERE region = ? AND id IN (" + placeholders(ids.size()) + ")");
                ps.setString(1, region);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 2, ids.get(i));
                }
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a closed `''`-escaped literal before the real IN run must not block recognition");
        assertEquals("id", plan.get().lhs());
        assertEquals(1, plan.get().bindsBeforeRun().size(), "region is the pre-IN bind");
    }

    @Test
    void nonScalarElementBlocks() throws Exception {
        // List<SomeRecord> has no scalar element type for the array/JSON cast -> refuse.
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;
                class Fixture {
                    record Row(long a) {}
                    @StoredProcedure
                    public static void run(Connection c, List<Row> rows) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM t WHERE id IN (" + String.join(",", Collections.nCopies(rows.size(), "?")) + ")");
                        for (int i = 0; i < rows.size(); i++) {
                            ps.setLong(i + 1, rows.get(i).a());
                        }
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "a non-scalar (record) element list must not array-bind");
    }

    // ----- placeholder/parameter alignment locks (security: prefix/suffix `?` accounting) ---------

    @Test
    void prefixPlaceholderCountMismatchMustBlock() throws Exception {
        // CRITICAL desync: the prefix carries TWO `?` (a = ?, b = ?) but only ordinal 2 is bound; the run
        // starts at ordinal 3 (loop binds i + 3). The prefix `?`-count (2) != before-run binds (1), so the
        // marker/array would mis-align onto a scalar column and the leading value onto the wrong column.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE a = ? AND b = ? AND id IN (" + placeholders(ids.size()) + ")");
                ps.setString(2, b);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 3, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "an unaccounted prefix `?` (count mismatch vs before-binds) must block array-binding");
    }

    @Test
    void prefixPlaceholderWithoutBindMustBlock() throws Exception {
        // A leading `region = ?` placeholder that is NEVER bound (no setString(1, region)); the run starts at
        // ordinal 2. The prefix `?` is unaccounted -> the emitted `region = $1` would be left unbound.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE region = ? AND id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 2, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "an unbound prefix `?` (no matching before-bind) must block array-binding");
    }

    @Test
    void suffixPlaceholderWithoutBindMustBlock() throws Exception {
        // A trailing `q = ?` placeholder AFTER the run, with NO bind for it. The run is supposed to be the
        // last placeholder group; a surviving suffix `?` is an unbound placeholder -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE id IN (" + placeholders(ids.size()) + ") AND q = ?");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "an unbound suffix `?` after the run must block array-binding");
    }

    @Test
    void suffixPlaceholderBoundByNonLiteralOrdinalMustBlock() throws Exception {
        // Same trailing `q = ?`, but now `setInt(k, q)` binds it at a NON-literal (variable) ordinal, which
        // collectOtherSetBinds drops — so the suffix `?` still survives unaccounted. Must still refuse.
        assertTrue(recognize("""
                int k = 2;
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE id IN (" + placeholders(ids.size()) + ") AND q = ?");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.setInt(k, q);
                ps.executeUpdate();
                """).isEmpty(), "a suffix `?` bound only at a non-literal ordinal must block array-binding");
    }

    @Test
    void extraUnboundPrefixPlaceholderWithGapMustBlock() throws Exception {
        // Two prefix `?` (a = ?, b = ?), run starts at ordinal 3, but only ordinal 1 is bound (a gap at
        // ordinal 2). Count matches in neither direction AND the ordinals are non-contiguous -> refuse.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE a = ? AND b = ? AND id IN (" + placeholders(ids.size()) + ")");
                ps.setString(1, region);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 3, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "a non-contiguous before-bind set (gap in 1..runStart-1) must block array-binding");
    }

    @Test
    void cleanTwoPrefixBindsBeforeRunStillRecognized() throws Exception {
        // POSITIVE control for the alignment guard: BOTH prefix `?` are bound (ords 1 and 2), run starts at
        // ordinal 3, no suffix `?`. This is a clean single-list IN with two leading scalars -> recognized,
        // with exactly two before-run binds and no after-run binds.
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM t WHERE a = ? AND b = ? AND id IN (" + placeholders(ids.size()) + ")");
                ps.setString(1, region);
                ps.setString(2, b);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 3, ids.get(i));
                }
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "two fully-bound prefix scalars before a clean IN-run must be recognized");
        assertEquals("id", plan.get().lhs());
        assertEquals(2, plan.get().bindsBeforeRun().size());
        assertTrue(plan.get().bindsAfterRun().isEmpty());
    }

    @Test
    void wrongOrdinalOffsetBlocks() throws Exception {
        // The per-element bind uses setLong(i, ...) (ordinal = i, not i+1) — not the per-element binding of
        // the run (the run's ordinals start at 1). Refuse rather than mis-bind.
        assertTrue(recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i, ids.get(i));
                }
                ps.executeUpdate();
                """).isEmpty(), "a per-element bind with the wrong ordinal offset must not array-bind");
    }

    @Test
    void subsumedTreesCoverSizeAndGetAndLoop() throws Exception {
        Optional<JdbcCollectionInRecognizer.CollectionInPlan> plan = recognize("""
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE accounts SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent());
        // The subsumed set must include the run's ids.size(), the loop's ids.get(i), and the bind loop.
        long sizeOrGetCalls = plan.get().subsumedTrees().stream()
                .filter(t -> t instanceof com.sun.source.tree.MethodInvocationTree mi
                        && mi.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree ms
                        && (ms.getIdentifier().contentEquals("size") || ms.getIdentifier().contentEquals("get")))
                .count();
        assertTrue(sizeOrGetCalls >= 2, "subsumed trees must cover ids.size() and ids.get(i)");
        assertTrue(plan.get().subsumedTrees().stream()
                        .anyMatch(t -> t instanceof com.sun.source.tree.ForLoopTree),
                "subsumed trees must include the per-element bind loop");
    }
}
