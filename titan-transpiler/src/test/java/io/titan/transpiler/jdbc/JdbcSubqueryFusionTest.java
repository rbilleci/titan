package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * WS-C Phase 3 Rung 2 — the security-critical <b>no-false-accept</b> lock for §3.6-form-1 subquery
 * fusion (design contract §8). It drives {@link JdbcSubqueryFusion#recognize} directly (decoupled from
 * any downstream lowering crash), asserting the dataflow proof returns a plan ONLY for the clean
 * A→ids→B shape and refuses ({@link Optional#empty()}) the moment {@code ids} or A's {@code ResultSet}
 * is used for anything but the one consuming IN. This is the recognizer-level twin of {@code
 * DynamicInListRecognizerTest}'s never-false-accept posture — a fusion that mis-fires would change
 * semantics for some input, so in doubt it must NOT fuse.
 */
class JdbcSubqueryFusionTest {

    @TempDir
    Path tempDir;

    private static String wrap(String methodBody) {
        return """
                import titan.dsl.*;
                import java.sql.*;
                import java.math.BigDecimal;
                import java.util.*;

                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, String region, int minQty,
                                           List<Long> other) throws SQLException {
                """ + methodBody + """
                    }
                    static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }
                    static String qmarks(int n) { return n == 0 ? "" : "?,".repeat(n - 1) + "?"; }
                    static void sink(List<Long> v) { }
                }
                """;
    }

    /** Parses the wrapped source and runs the fusion recognizer over the single method's body. */
    private Optional<JdbcSubqueryFusion.FusionPlan> recognize(String methodBody) throws Exception {
        return recognizeFullSource(wrap(methodBody), "run");
    }

    /** Parses a whole source file and runs the fusion recognizer over {@code methodName}'s body. */
    private Optional<JdbcSubqueryFusion.FusionPlan> recognizeFullSource(String source, String methodName)
            throws Exception {
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
        return new JdbcSubqueryFusion(parsed, shapes, oracle)
                .recognize(body[0].getStatements(), bodyPath[0], bodyPath[0]);
    }

    /** The EXACT deploy-IT fixture must fuse (this is the IT's recognition prerequisite). */
    @Test
    void fusesTheDeployFixtureShape() throws Exception {
        Optional<JdbcSubqueryFusion.FusionPlan> plan = recognizeFullSource("""
                import titan.dsl.StoredProcedure;
                import java.sql.*;
                import java.util.*;

                class FlagOrdersByCustomerTier {
                    @StoredProcedure
                    public static void flagOrdersByCustomerTier(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                                "UPDATE orders SET flagged = 1 WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int n) {
                        return String.join(",", Collections.nCopies(n, "?"));
                    }
                }
                """, "flagOrdersByCustomerTier");
        assertTrue(plan.isPresent(), "the deploy fixture must fuse");
        assertEquals("UPDATE orders SET flagged = 1 WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)",
                plan.get().fusedSql());
        assertTrue(plan.get().subsumedTrees().stream().anyMatch(t ->
                        t instanceof com.sun.source.tree.MethodInvocationTree mi
                                && mi.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree ms
                                && ms.getIdentifier().contentEquals("size")),
                "the ids.size() in B's prepare must be subsumed (so FeatureValidator exempts it)");
    }

    private void assertFuses(String methodBody, String expectedFusedSql) throws Exception {
        Optional<JdbcSubqueryFusion.FusionPlan> plan = recognize(methodBody);
        assertTrue(plan.isPresent(), "expected fusion to apply to this shape");
        assertEquals(expectedFusedSql, plan.get().fusedSql(), "fused SQL text");
    }

    private void assertDoesNotFuse(String methodBody) throws Exception {
        assertFalse(recognize(methodBody).isPresent(),
                "fusion MUST NOT apply: a defeating use of ids / A's ResultSet is present");
    }

    // The clean A→ids→B accumulation+IN preamble (positive shape), reused across the no-fuse fixtures
    // with exactly ONE defeating mutation each.
    private static final String A_AND_LOOP = """
                    List<Long> ids = new ArrayList<>();
                    PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                    psA.setString(1, tier);
                    ResultSet rsA = psA.executeQuery();
                    while (rsA.next()) {
                        ids.add(rsA.getLong("id"));
                    }
            """;

    private static final String B_IN = """
                    PreparedStatement psB = c.prepareStatement(
                        "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                    psB.executeUpdate();
            """;

    // ================================ POSITIVE ===============================================

    @Test
    void fusesCleanAccumulationIntoInSubquery() throws Exception {
        assertFuses(A_AND_LOOP + B_IN,
                "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)");
    }

    /**
     * The {@code subsumedTrees()} set (consumed by {@code FeatureValidator} to exempt the elided
     * collection constructs) must include the {@code new ArrayList}, the {@code ids.add(...)} call, and
     * the {@code ids.size()} call sizing B's run — so a fusable method's collection ops do not falsely
     * fail the P0 feature gate.
     */
    @Test
    void subsumedTreesCoverTheElidedCollectionConstructs() throws Exception {
        java.util.Optional<JdbcSubqueryFusion.FusionPlan> plan = recognize(A_AND_LOOP + B_IN);
        assertTrue(plan.isPresent(), "expected fusion");
        java.util.Set<com.sun.source.tree.Tree> subsumed = plan.get().subsumedTrees();
        long newClassCount = subsumed.stream().filter(t -> t.getKind() == com.sun.source.tree.Tree.Kind.NEW_CLASS).count();
        assertTrue(newClassCount >= 1, "the new ArrayList must be subsumed");
        // The ids.add(...) and ids.size() are method invocations in the subsumed set.
        boolean hasAdd = subsumed.stream().anyMatch(t ->
                t instanceof com.sun.source.tree.MethodInvocationTree mi
                        && mi.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree ms
                        && ms.getIdentifier().contentEquals("add"));
        boolean hasSize = subsumed.stream().anyMatch(t ->
                t instanceof com.sun.source.tree.MethodInvocationTree mi
                        && mi.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree ms
                        && ms.getIdentifier().contentEquals("size"));
        assertTrue(hasAdd, "ids.add(...) must be subsumed");
        assertTrue(hasSize, "ids.size() (sizing B's run) must be subsumed");
    }

    @Test
    void fusesWhenBHasBoundPredicatesAroundTheIn() throws Exception {
        assertFuses(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE region = ? AND customer_id IN ("
                                + placeholders(ids.size()) + ") AND qty >= ?");
                        psB.setString(1, region);
                        psB.setInt(2, minQty);
                        psB.executeUpdate();
                """,
                "DELETE FROM orders WHERE region = ? AND customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ?) AND qty >= ?");
    }

    @Test
    void fusesRepeatStyleRun() throws Exception {
        assertFuses(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + qmarks(ids.size()) + ")");
                        psB.executeUpdate();
                """,
                "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)");
    }

    // ============================ NO-FALSE-ACCEPT (must NOT fuse) =============================

    @Test
    void doesNotFuseSecondIdsSize() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        int extra = ids.size();
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsGet() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        long first = ids.get(0);
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsIsEmptySpecialCase() throws Exception {
        // An explicit empty special-case (the semantic-preservation trap the design calls out): if the
        // original code branches on ids being empty, fusion must NOT silently collapse it.
        assertDoesNotFuse(A_AND_LOOP + """
                        if (ids.isEmpty()) { return; }
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsIteratedAgain() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        for (Long v : ids) { sink(Collections.singletonList(v)); }
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsReturnedViaField() throws Exception {
        // Storing ids into a field (escape). (Modeled as passing to a method, which is also an escape;
        // a bare field-store needs an instance — passing-to-method is the representative escape.)
        assertDoesNotFuse(A_AND_LOOP + """
                        sink(ids);
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsPassedToMethod() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        sink(ids);
                """ + B_IN);
    }

    @Test
    void doesNotFuseIdsMutatedAfterLoop() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + B_IN + """
                        ids.clear();
                """);
    }

    @Test
    void doesNotFuseIdsAddedToAfterLoop() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + B_IN + """
                        ids.add(0L);
                """);
    }

    @Test
    void doesNotFuseSecondInOverIds() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + B_IN + """
                        PreparedStatement psC = c.prepareStatement(
                            "DELETE FROM shipments WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psC.executeUpdate();
                """);
    }

    @Test
    void doesNotFusePriorSelectsTwoColumns() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        List<String> names = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id, name FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                            names.add(rsA.getString("name"));
                        }
                """ + B_IN);
    }

    @Test
    void doesNotFusePriorGetXFeedsOtherLogic() throws Exception {
        assertDoesNotFuse("""
                        long sum = 0;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                            sum = sum + rsA.getLong("score");
                        }
                """ + B_IN);
    }

    @Test
    void doesNotFuseLoopBodyDoesMoreThanAdd() throws Exception {
        assertDoesNotFuse("""
                        long n = 0;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                            n = n + 1;
                        }
                """ + B_IN);
    }

    @Test
    void doesNotFuseRunSizedByDifferentCollection() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(other.size()) + ")");
                        psB.executeUpdate();
                """);
    }

    @Test
    void doesNotFusePriorSqlNonConstant() throws Exception {
        // A's SQL is itself a value/identifier splice — fusion must never inline a non-constant A (it
        // would put runtime text into the fused SQL). The strict gate handles A separately.
        assertDoesNotFuse("""
                        String col = region;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE " + col + " = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    @Test
    void doesNotFuseRunIsLiteralArityNotIdsSize() throws Exception {
        // A constant-arity run (placeholders(3)) is the Rung-1 skeleton case, not a fusion: the count is
        // not ids.size(), so there is no proven A→ids→B link to fuse.
        assertDoesNotFuse(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(3) + ")");
                        psB.setLong(1, 1L);
                        psB.setLong(2, 2L);
                        psB.setLong(3, 3L);
                        psB.executeUpdate();
                """);
    }

    @Test
    void doesNotFuseValueSplicedInsteadOfPlaceholderRun() throws Exception {
        // "... IN (" + ids.size() + ")" splices the COUNT as a value, not a ?-run — never a fusion (and
        // never a value reaching the text). Defeats recognition (the operand is not a placeholder run).
        assertDoesNotFuse(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + ids.size() + ")");
                        psB.executeUpdate();
                """);
    }

    @Test
    void doesNotFuseWhenNoPriorQuery() throws Exception {
        // An IN-run sized by a collection with no prior accumulation loop -> nothing to fuse.
        assertDoesNotFuse("""
                        List<Long> ids = other;
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                """);
    }

    // ============ NO-OVER-FUSE: A's SQL projection / kind / nestability (WS-C P3 R2 audit) ===========
    // The Java loop reading ONE column does NOT prove A PROJECTS one column. A's verbatim text is
    // inlined into `IN (…)`, so its projection/kind/terminators decide whether the fused query is the
    // same query. Each fixture below has a clean single-read loop but an A that must defeat fusion.

    /**
     * A projects TWO columns but the loop reads only the first ({@code ids.add(rsA.getLong("id"))}). The
     * earlier no-fuse test required the loop to ALSO read the 2nd column; THIS slips past that — the
     * single-read-of-a-multi-projection. {@code IN (SELECT id, name …)} is "subquery has too many
     * columns" on PostgreSQL, and the bound list materialized one named column. Refuse.
     */
    @Test
    void doesNotFusePriorProjectsTwoColumnsEvenIfJavaReadsOne() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id, name FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** {@code SELECT *} in A — opaque, multi-column projection; {@code IN (SELECT *)} is wrong/illegal. */
    @Test
    void doesNotFuseWhenPriorIsSelectStar() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT * FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** {@code SELECT tbl.*} in A — qualified star is still a whole-row projection. Refuse. */
    @Test
    void doesNotFuseWhenPriorIsQualifiedSelectStar() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT cust.* FROM customers cust WHERE cust.tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /**
     * A multi-projection A read by ordinal {@code getLong(2)} (the 2nd projected column). A subquery
     * cannot reproduce "pick projected column #2" — its first column is {@code region_id}. Refuse.
     */
    @Test
    void doesNotFuseWhenPriorReadsNonFirstOrdinalOfMultiColumnSelect() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT region_id, id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong(2));
                        }
                """ + B_IN);
    }

    /**
     * Even a SINGLE-column A read by a non-first ordinal ({@code getLong(2)}) is refused: the subquery
     * always exposes column #1, so a {@code getX(2)} consumes a column the fused {@code IN} would not.
     */
    @Test
    void doesNotFuseWhenPriorReadsSecondOrdinalOfSingleColumnSelect() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong(2));
                        }
                """ + B_IN);
    }

    /** A single-column A read by ordinal #1 ({@code getLong(1)}) IS the single column — must still fuse. */
    @Test
    void fusesWhenPriorReadsFirstOrdinalOfSingleColumnSelect() throws Exception {
        assertFuses("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong(1));
                        }
                """ + B_IN,
                "DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)");
    }

    /** A is an {@code UPDATE … RETURNING}, not a SELECT — illegal as a subquery and drops side effects. */
    @Test
    void doesNotFuseWhenPriorIsNotSelect() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("UPDATE customers SET seen = 1 WHERE tier = ? RETURNING id");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** A {@code WITH … SELECT} CTE in A is conservatively refused (single-column proof not a local check). */
    @Test
    void doesNotFuseWhenPriorIsWithCte() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement(
                            "WITH g AS (SELECT id FROM customers WHERE tier = ?) SELECT id FROM g");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** A's text ends in a statement-terminating ';' — it cannot nest as a subquery. Refuse. */
    @Test
    void doesNotFuseWhenPriorSqlHasTrailingSemicolon() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?;");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** A's text has a trailing {@code --} line comment that would swallow B's closing ')'. Refuse. */
    @Test
    void doesNotFuseWhenPriorSqlHasTrailingLineComment() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ? -- pick");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** A's text has a {@code /* … *}{@code /} block comment — also refused (nestability hygiene). */
    @Test
    void doesNotFuseWhenPriorSqlHasBlockComment() throws Exception {
        assertDoesNotFuse("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id /* the key */ FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                """ + B_IN);
    }

    /** A negated membership {@code NOT IN (…)} is refused — not NULL-equivalent to a bound NOT IN. */
    @Test
    void doesNotFuseNegatedIn() throws Exception {
        assertDoesNotFuse(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id NOT IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                """);
    }

    // ============================== POSITIVE: must still fuse (no over-refuse) ====================

    /**
     * A {@code SELECT} whose single projected column is a function call containing a top-level comma
     * inside its parens (e.g. {@code COALESCE(a, b)}) is STILL one column — the comma is at depth&gt;0,
     * not a select-list separator. Must fuse (a too-eager comma check would wrongly refuse this).
     */
    @Test
    void fusesSingleColumnExpressionWithParenComma() throws Exception {
        assertFuses("""
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement(
                            "SELECT COALESCE(primary_id, backup_id) FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong(1));
                        }
                """ + B_IN,
                "DELETE FROM orders WHERE customer_id IN "
                        + "(SELECT COALESCE(primary_id, backup_id) FROM customers WHERE tier = ?)");
    }

    /**
     * A literal {@code '?'} inside a single-quoted SQL string in B's constant text is data, not a bind
     * placeholder — it must NOT shift the pre/post-IN bind boundary. With a real bound predicate after
     * the IN, the binds must merge as [A's tier, B's region]; the literal '?' counts for nothing.
     */
    @Test
    void fusesWhenConstantTextContainsLiteralQuestionMark() throws Exception {
        assertFuses(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size())
                                + ") AND note = '?' AND region = ?");
                        psB.setString(1, region);
                        psB.executeUpdate();
                """,
                "DELETE FROM orders WHERE customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ?) AND note = '?' AND region = ?");
    }

    // =============== Direct micro-tests of the parser-free A-shape edge-checks =====================
    // These pin priorSqlIsFusableSubquery / countQuestionMarksOutsideStringLiterals at the unit level so
    // a regression in the local edge-checks is caught without a full parse.

    @Test
    void priorSqlFusabilityEdgeChecks() {
        // Accept: clean single-column SELECTs (by name, expression-with-paren-comma, cast, no-FROM).
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id FROM customers WHERE tier = ?"));
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("select  id  from customers"));
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery(
                "SELECT COALESCE(a, b) FROM t WHERE x = ?"), "a top-level-comma-free function call is one column");
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id::bigint FROM t"));
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT 1"));
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT DISTINCT id FROM t"));
        // A literal comma inside a string literal is not a select-list separator.
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT 'a, b' FROM t WHERE x = ?"));

        // Refuse: multi-column, star, qualified star, non-SELECT, terminator, comments, blank/null.
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id, name FROM customers"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT * FROM customers"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT cust.* FROM customers cust"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("UPDATE t SET x = 1 WHERE y = ? RETURNING id"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("WITH g AS (SELECT id FROM t) SELECT id FROM g"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id FROM t;"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id FROM t -- c"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT id /* c */ FROM t"));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("   "));
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery(null));
        // A `;` inside a string literal is data, not a terminator — but it is still multi-token text we
        // accept only if single-column; here it is a single-column literal, so accept.
        assertTrue(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECT ';' FROM t"));
        // SELECTSOMETHING is not the SELECT keyword (whole-word).
        assertFalse(JdbcSubqueryFusion.priorSqlIsFusableSubquery("SELECTOR_VALUE FROM t"));
    }

    @Test
    void questionMarkCountIgnoresStringLiterals() {
        assertEquals(2, JdbcSubqueryFusion.countQuestionMarksOutsideStringLiterals(
                "WHERE a = ? AND note = '?' AND b = ?"), "the literal '?' is not a placeholder");
        assertEquals(0, JdbcSubqueryFusion.countQuestionMarksOutsideStringLiterals("note = '?,?,?'"));
        assertEquals(1, JdbcSubqueryFusion.countQuestionMarksOutsideStringLiterals("x = ? AND y = 'it''s ?'"),
                "an escaped '' quote keeps the scanner inside the literal");
    }

    /**
     * The literal-'?' may also sit in the PREFIX (before the IN). It must not be counted as a pre-IN
     * placeholder (which would mis-classify the genuine pre-IN bind). Binds must be [B's region (pre-IN),
     * A's tier (subquery)].
     */
    @Test
    void fusesWhenPrefixContainsLiteralQuestionMark() throws Exception {
        Optional<JdbcSubqueryFusion.FusionPlan> plan = recognize(A_AND_LOOP + """
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE note = '?' AND region = ? AND customer_id IN ("
                                + placeholders(ids.size()) + ")");
                        psB.setString(1, region);
                        psB.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a literal '?' in the prefix must not defeat a valid fusion");
        assertEquals("DELETE FROM orders WHERE note = '?' AND region = ? AND customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ?)",
                plan.get().fusedSql());
        // The genuine pre-IN bind (region, ordinal 1) and A's bind (tier) — the literal '?' is not a bind.
        assertEquals(2, plan.get().orderedBindSources().size(),
                "exactly two real binds: B's region (pre-IN) and A's tier (subquery); the literal '?' is data");
        assertEquals(JdbcSubqueryFusion.BindSource.Kind.CONSUMING, plan.get().orderedBindSources().get(0).kind());
        assertEquals(JdbcSubqueryFusion.BindSource.Kind.PRIOR, plan.get().orderedBindSources().get(1).kind());
    }
}
