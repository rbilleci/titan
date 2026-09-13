package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3 Rung 2 — §3.6 form (1) subquery fusion. Source string → parse → lower → assert TIR
 * shape. The crux is the cross-statement dataflow proof: a prior query A whose {@code ResultSet} is
 * read into a single-column {@code ids} collection that is consumed <b>only</b> as a later query B's
 * {@code IN}-source fuses into one in-database statement ({@code B … IN (A_sql)}); A's prepare/execute/
 * loop and the {@code ids} collection are elided, and the binds merge in text order, all still bound.
 *
 * <p>The heart of this test class is the <b>no-over-fuse</b> set: each fixture differs from the
 * positive case by exactly one defeating use of {@code ids} / A's {@code ResultSet} (a second
 * {@code size()}, an {@code ids.get}, an escape, a mutation, a second IN, a two-column prior select, a
 * prior {@code getX} feeding other logic), and MUST NOT fuse — the build must keep the existing
 * Rung-1/I-5/gate behavior (which, for an unbound runtime IN-run, surfaces as no fused statement).</p>
 */
class JdbcSubqueryFusionLoweringTest {

    @TempDir
    Path tempDir;

    private static String prelude() {
        return """
                import titan.dsl.*;
                import java.sql.*;
                import java.math.BigDecimal;
                import java.util.*;

                """;
    }

    private ParsedSources parse(String className, String source) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, prelude() + source);
        return new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
    }

    private record LowerResult(Map<String, Block> lowered, DiagnosticSink sink) { }

    private LowerResult lower(String className, String source) throws Exception {
        ParsedSources parsed = parse(className, source);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, entryPoints, sink);
        return new LowerResult(lowered, sink);
    }

    private Block lowerOneBlock(String className, String source) throws Exception {
        LowerResult result = lower(className, source);
        assertTrue(result.sink().errors().isEmpty(),
                "expected a clean lowering; errors=" + result.sink().errors());
        assertEquals(1, result.lowered().size(), "expected exactly one lowered entry point");
        return result.lowered().values().iterator().next();
    }

    private static <T> java.util.Optional<T> findFirst(Block block, Class<T> type) {
        return block.statements().stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    private static long countRawSqlLike(Block block) {
        // Count the statements that carry a RawSql (the fused statement is one; the two-query form is two).
        return block.statements().stream().filter(s ->
                (s instanceof ExecuteSqlStatement e && e.sqlNode() instanceof RawSql)
                        || s instanceof RawReadIntoStatement
                        || s instanceof RawCursorStatement).count();
    }

    // ================================ POSITIVE FUSE CASES =====================================

    /**
     * The canonical fuse: A reads {@code id} into {@code ids}; B's {@code IN} is sized by {@code
     * ids.size()}; {@code ids} is otherwise untouched. The two queries collapse into ONE
     * ExecuteSqlStatement whose RawSql is {@code … IN (SELECT id FROM customers WHERE tier = ?)} with
     * A's one bind. A's prepare/execute/loop and the {@code ids} declaration are elided.
     */
    @Test
    void fusesPriorResultSetIntoInSubquery_executeUpdateB() throws Exception {
        Block block = lowerOneBlock("FuseDelete", """
                class FuseDelete {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int n) {
                        return String.join(",", Collections.nCopies(n, "?"));
                    }
                }
                """);
        // Exactly ONE in-DB statement (the fused B); no separate A execute, no cursor over A.
        assertEquals(1, countRawSqlLike(block), "fusion must collapse A+B into one in-DB statement");
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class).orElseThrow();
        RawSql fused = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)",
                fused.sql(), "A's SQL must be inlined verbatim as B's IN-subquery");
        assertEquals(List.of("tier"), fused.parameters(), "A's bind must carry into the fused statement");
        // No RawCursorStatement (A's loop is elided, not lowered to a cursor).
        assertTrue(findFirst(block, RawCursorStatement.class).isEmpty(), "A's accumulation loop must be elided");
        // The `ids` List declaration must be elided (it is never a DeclareVariable).
        assertTrue(block.declarations().stream().noneMatch(d -> d instanceof DeclareVariable dv && dv.name().equals("ids")),
                "the ids collection declaration must be elided");
    }

    /**
     * B is itself a multi-row read ({@code while (rsB.next())}). The fused statement is a
     * RawCursorStatement whose query is {@code SELECT … IN (A_sql)}. Proves fusion composes with B's
     * own result shape (the result shape is B's, A's column feeds only the IN).
     */
    @Test
    void fusesPriorResultSetIntoInSubquery_multiRowReadB() throws Exception {
        Block block = lowerOneBlock("FuseRead", """
                class FuseRead {
                    @StoredFunction
                    public static BigDecimal run(Connection c, String tier) throws SQLException {
                        BigDecimal total = BigDecimal.ZERO;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "SELECT amount FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        ResultSet rsB = psB.executeQuery();
                        while (rsB.next()) {
                            total = total.add(rsB.getBigDecimal("amount"));
                        }
                        return total;
                    }
                    static String placeholders(int n) {
                        return String.join(",", Collections.nCopies(n, "?"));
                    }
                }
                """);
        assertEquals(1, countRawSqlLike(block), "fusion must collapse A+B into one in-DB statement");
        RawCursorStatement cursor = findFirst(block, RawCursorStatement.class).orElseThrow();
        assertEquals("SELECT amount FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)",
                cursor.query().sql(), "the fused cursor must read B with A inlined as the IN-subquery");
        assertEquals(List.of("tier"), cursor.query().parameters());
    }

    /**
     * B has its own bound predicate <b>before</b> the IN ({@code region = ?}) and after; the merged
     * binds must be in text order: B's pre-IN bind, then A's bind (now inside the subquery), then B's
     * post-IN bind. Proves the bind-ordering invariant the fused EXECUTE … USING relies on.
     */
    @Test
    void mergesBindsInTextOrderAroundTheSubquery() throws Exception {
        Block block = lowerOneBlock("FuseOrder", """
                class FuseOrder {
                    @StoredProcedure
                    public static void run(Connection c, String tier, String region, int minQty) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE region = ? AND customer_id IN ("
                                + placeholders(ids.size()) + ") AND qty >= ?");
                        psB.setString(1, region);
                        psB.setInt(2, minQty);
                        psB.executeUpdate();
                    }
                    static String placeholders(int n) {
                        return String.join(",", Collections.nCopies(n, "?"));
                    }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class).orElseThrow();
        RawSql fused = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("DELETE FROM orders WHERE region = ? AND customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ?) AND qty >= ?",
                fused.sql());
        // Text order: region (B pre-IN), tier (A, inside subquery), minQty (B post-IN).
        assertEquals(List.of("region", "tier", "minQty"), fused.parameters(),
                "binds must be ordered left-to-right by their text position (B-pre, A, B-post)");
    }

    /**
     * A has TWO binds; both must carry into the fused subquery in ordinal order, contiguous within the
     * subquery (A is inlined as one block), between B's pre/post-IN binds.
     */
    @Test
    void mergesMultipleAndConsumingBindsInTextOrder() throws Exception {
        Block block = lowerOneBlock("FuseTwoAbinds", """
                class FuseTwoAbinds {
                    @StoredProcedure
                    public static void run(Connection c, String tier, String region, String status) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement(
                            "SELECT id FROM customers WHERE tier = ? AND region = ?");
                        psA.setString(1, tier);
                        psA.setString(2, region);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size())
                                + ") AND status = ?");
                        psB.setString(1, status);
                        psB.executeUpdate();
                    }
                    static String placeholders(int n) {
                        return String.join(",", Collections.nCopies(n, "?"));
                    }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class).orElseThrow();
        RawSql fused = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("DELETE FROM orders WHERE customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ? AND region = ?) AND status = ?",
                fused.sql());
        // A's two binds (tier, region), then B's post-IN bind (status) — text order.
        assertEquals(List.of("tier", "region", "status"), fused.parameters());
    }

    /** The `"?, ".repeat(ids.size())`-style run is also recognized and fused. */
    @Test
    void fusesRepeatStylePlaceholderRun() throws Exception {
        Block block = lowerOneBlock("FuseRepeat", """
                class FuseRepeat {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + qmarks(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String qmarks(int n) {
                        return n == 0 ? "" : "?," .repeat(n - 1) + "?";
                    }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class).orElseThrow();
        RawSql fused = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)",
                fused.sql());
        assertEquals(List.of("tier"), fused.parameters());
    }

    // ============================== NO-OVER-FUSE (must NOT fuse) ==============================
    // Each fixture defeats the dataflow proof by exactly one extra use of `ids` / A's ResultSet. When
    // fusion does NOT apply, the runtime-sized IN-run is NOT value-bindable on the parser-free path, so
    // the build keeps the existing behavior — surfaced here as "no single fused in-DB statement"
    // (either the gate rejects, or no fusion happened). We assert the fused form did NOT appear.

    private void assertDidNotFuse(LowerResult result) {
        // The fused statement is the only way `… IN (SELECT id FROM customers …)` text could appear; if
        // fusion did not apply, no statement carries that subquery text. (The method may also fail to
        // lower cleanly — that is fine; the point is fusion must not have fired.)
        boolean fusedAppeared = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .anyMatch(JdbcSubqueryFusionLoweringTest::carriesSubqueryFusion);
        assertFalse(fusedAppeared, "fusion must NOT apply to this shape (a defeating use of ids/rs is present)");
    }

    private static boolean carriesSubqueryFusion(StatementNode statement) {
        String sql = rawSqlTextOf(statement);
        return sql != null && sql.contains("SELECT id FROM customers");
    }

    private static String rawSqlTextOf(StatementNode statement) {
        if (statement instanceof ExecuteSqlStatement e && e.sqlNode() instanceof RawSql raw) {
            return raw.sql();
        }
        if (statement instanceof RawReadIntoStatement r) {
            return r.query().sql();
        }
        if (statement instanceof RawCursorStatement r) {
            return r.query().sql();
        }
        return null;
    }

    /** A second {@code ids.size()} (e.g. an unrelated count) — `ids` is used beyond sizing B's run. */
    @Test
    void doesNotFuseWhenIdsSizeUsedTwice() throws Exception {
        LowerResult result = lower("NoFuseSizeTwice", """
                class NoFuseSizeTwice {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        int n = ids.size();
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** {@code ids.get(0)} read — the collection is consumed for more than the IN-source. */
    @Test
    void doesNotFuseWhenIdsGetRead() throws Exception {
        LowerResult result = lower("NoFuseGet", """
                class NoFuseGet {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        long first = ids.get(0);
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** {@code ids} is returned — it escapes the routine. */
    @Test
    void doesNotFuseWhenIdsReturned() throws Exception {
        LowerResult result = lower("NoFuseReturn", """
                class NoFuseReturn {
                    @StoredFunction
                    public static List<Long> run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                        return ids;
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** {@code ids} is passed to another method — it escapes. */
    @Test
    void doesNotFuseWhenIdsPassedToMethod() throws Exception {
        LowerResult result = lower("NoFusePass", """
                class NoFusePass {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        sink(ids);
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static void sink(List<Long> v) { }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** A second IN over {@code ids} (two consuming statements) — caught as a second {@code ids.size()}. */
    @Test
    void doesNotFuseWhenSecondInOverIds() throws Exception {
        LowerResult result = lower("NoFuseSecondIn", """
                class NoFuseSecondIn {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                        PreparedStatement psC = c.prepareStatement(
                            "DELETE FROM shipments WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psC.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** {@code ids.clear()} after the loop — a mutation of {@code ids} after accumulation. */
    @Test
    void doesNotFuseWhenIdsMutatedAfterLoop() throws Exception {
        LowerResult result = lower("NoFuseMutate", """
                class NoFuseMutate {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                        ids.clear();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** A selects TWO columns and the loop adds both — the prior rows feed more than the single IN-source. */
    @Test
    void doesNotFuseWhenPriorSelectsTwoColumns() throws Exception {
        LowerResult result = lower("NoFuseTwoCol", """
                class NoFuseTwoCol {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        List<String> names = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id, name FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                            names.add(rsA.getString("name"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** A's {@code rs.getX} feeds other Java logic (a second read in the loop accumulated elsewhere). */
    @Test
    void doesNotFuseWhenPriorGetXFeedsOtherLogic() throws Exception {
        LowerResult result = lower("NoFuseOtherLogic", """
                class NoFuseOtherLogic {
                    @StoredFunction
                    public static long run(Connection c, String tier) throws SQLException {
                        long sum = 0;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            long v = rsA.getLong("id");
                            ids.add(v);
                            sum = sum + rsA.getLong("score");
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                        return sum;
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** The loop body does more than the single add (a second statement) — not a pure accumulation. */
    @Test
    void doesNotFuseWhenLoopBodyDoesMoreThanAdd() throws Exception {
        LowerResult result = lower("NoFuseLoopExtra", """
                class NoFuseLoopExtra {
                    @StoredFunction
                    public static long run(Connection c, String tier) throws SQLException {
                        long n = 0;
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                            n = n + 1;
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                        return n;
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** A run sized by a DIFFERENT collection's size (not {@code ids.size()}) — not this idiom. */
    @Test
    void doesNotFuseWhenRunSizedByDifferentCollection() throws Exception {
        LowerResult result = lower("NoFuseOtherSize", """
                class NoFuseOtherSize {
                    @StoredProcedure
                    public static void run(Connection c, String tier, List<Long> other) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(other.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    /** A's SQL is itself non-constant (a value/identifier splice) — never inline a non-constant A. */
    @Test
    void doesNotFuseWhenPriorSqlNonConstant() throws Exception {
        LowerResult result = lower("NoFuseNonConstA", """
                class NoFuseNonConstA {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, String tierColumn, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE " + tierColumn + " = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertDidNotFuse(result);
    }

    // ===== NO-OVER-FUSE at the lowering level: A's projection / kind / nestability (WS-C P3 R2) =====
    // These reach the FULL lowering (errors=0 historically) and proved the over-fuse ships a
    // non-deployable / wrong-semantics routine. The fix refuses fusion; assert no inlined IN-subquery
    // appears in ANY lowered statement (a more general detector than the SELECT-id text match above).

    /** No statement may carry an inlined `IN (SELECT …)` / `IN (UPDATE …)` subquery (the fusion signature). */
    private void assertNoInlinedSubqueryFused(LowerResult result) {
        boolean fusedAppeared = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .anyMatch(JdbcSubqueryFusionLoweringTest::carriesAnyInlinedSubquery);
        assertFalse(fusedAppeared,
                "fusion must NOT apply: A is not a clean single-column read-only SELECT (no inlined IN-subquery)");
    }

    private static boolean carriesAnyInlinedSubquery(StatementNode statement) {
        String sql = rawSqlTextOf(statement);
        if (sql == null) {
            return false;
        }
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("IN (SELECT") || upper.contains("IN (UPDATE")
                || upper.contains("IN (INSERT") || upper.contains("IN (DELETE") || upper.contains("IN (WITH");
    }

    /** A projects two columns; the loop reads only the first — refuse (multi-column subquery). */
    @Test
    void doesNotFuseWhenPriorProjectsTwoColumnsEvenIfJavaReadsOne() throws Exception {
        LowerResult result = lower("NoFuseTwoColOneRead", """
                class NoFuseTwoColOneRead {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id, name FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    /** {@code SELECT *} in A — refuse. */
    @Test
    void doesNotFuseWhenPriorIsSelectStar() throws Exception {
        LowerResult result = lower("NoFuseStar", """
                class NoFuseStar {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT * FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    /** A is an {@code UPDATE … RETURNING}, not a SELECT — refuse (illegal subquery + lost side effect). */
    @Test
    void doesNotFuseWhenPriorIsNotSelect() throws Exception {
        LowerResult result = lower("NoFuseNotSelect", """
                class NoFuseNotSelect {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("UPDATE customers SET seen = 1 WHERE tier = ? RETURNING id");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    /** A's text ends in a statement terminator ';' — refuse (cannot nest as a subquery). */
    @Test
    void doesNotFuseWhenPriorSqlHasTrailingSemicolon() throws Exception {
        LowerResult result = lower("NoFuseSemicolon", """
                class NoFuseSemicolon {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?;");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    /** A's text has a trailing {@code --} line comment that would swallow B's ')' — refuse. */
    @Test
    void doesNotFuseWhenPriorSqlHasTrailingLineComment() throws Exception {
        LowerResult result = lower("NoFuseLineComment", """
                class NoFuseLineComment {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ? -- pick");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    /** A negated membership {@code NOT IN (…)} — refuse (not NULL-equivalent to a bound NOT IN). */
    @Test
    void doesNotFuseNegatedIn() throws Exception {
        LowerResult result = lower("NoFuseNotIn", """
                class NoFuseNotIn {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id NOT IN (" + placeholders(ids.size()) + ")");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        assertNoInlinedSubqueryFused(result);
    }

    // ===================== POSITIVE: literal '?' must not false-reject (E001) =====================

    /**
     * A literal {@code '?'} inside a single-quoted SQL string (here in B's SUFFIX, after the IN) is data,
     * not a placeholder. The fused arity check counts {@code ?} OUTSIDE string literals, so the fused
     * statement lowers cleanly (no E001) with exactly A's one bind. (WS-C Phase 3 Rung 2 audit — the
     * literal-'?' false-reject.)
     */
    @Test
    void fusesCleanlyWhenConstantTextContainsLiteralQuestionMark() throws Exception {
        Block block = lowerOneBlock("FuseLiteralQmark", """
                class FuseLiteralQmark {
                    @StoredProcedure
                    public static void run(Connection c, String tier) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size())
                                + ") AND note = '?'");
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class).orElseThrow();
        RawSql fused = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("DELETE FROM orders WHERE customer_id IN "
                        + "(SELECT id FROM customers WHERE tier = ?) AND note = '?'",
                fused.sql(), "the literal '?' stays in the text; the run's '?' is replaced by the subquery");
        assertEquals(List.of("tier"), fused.parameters(),
                "exactly A's one bind — the literal '?' is NOT a placeholder and binds nothing");
    }

    /**
     * The boundary-soundness lock for the low-severity {@code buildOrderedBindSources} finding: if B
     * literally binds the run's own ordinals (the malformed run-binding idiom — never a real fusion,
     * since binding {@code ids} needs {@code ids.get}), the fused {@code ?}-count and the merged bind
     * count desync and the build REJECTS (E001) rather than emitting a misordered/over-bound EXECUTE. We
     * assert the safe outcome: an arity error and no emitted fused statement (it fails SAFE).
     */
    @Test
    void rejectsWhenBindsCollideWithRunOrdinalsRatherThanMisbinding() throws Exception {
        LowerResult result = lower("RunOrdinalCollision", """
                class RunOrdinalCollision {
                    @StoredProcedure
                    public static void run(Connection c, String tier, long lo, long hi, int minQty) throws SQLException {
                        List<Long> ids = new ArrayList<>();
                        PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                        psA.setString(1, tier);
                        ResultSet rsA = psA.executeQuery();
                        while (rsA.next()) {
                            ids.add(rsA.getLong("id"));
                        }
                        PreparedStatement psB = c.prepareStatement(
                            "DELETE FROM orders WHERE customer_id IN (" + placeholders(ids.size()) + ") AND qty >= ?");
                        psB.setLong(1, lo);
                        psB.setLong(2, hi);
                        psB.setInt(3, minQty);
                        psB.executeUpdate();
                    }
                    static String placeholders(int k) { return String.join(",", Collections.nCopies(k, "?")); }
                }
                """);
        // Fail-safe: it must NOT emit a fused statement that silently misbinds. Either an error is raised
        // (the arity gate), or no inlined subquery appears — never a clean fused statement with the wrong
        // bind list. We assert no inlined subquery survived as a *clean* emission.
        boolean fusedAppeared = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .anyMatch(JdbcSubqueryFusionLoweringTest::carriesAnyInlinedSubquery);
        assertTrue(!fusedAppeared || !result.sink().errors().isEmpty(),
                "a run-ordinal-colliding B must not silently emit a misbound fused statement (it fails safe via E001)");
    }
}
