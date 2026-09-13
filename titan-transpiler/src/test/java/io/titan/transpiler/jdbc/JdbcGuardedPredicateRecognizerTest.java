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
 * Design contract D4 — the security/correctness-critical <b>no-false-recognize</b> lock for the
 * optional-filter / guarded-predicate search-builder idiom. It drives {@link
 * JdbcGuardedPredicateRecognizer#recognize} directly (decoupled from any downstream lowering), asserting
 * the control-flow / bind-correlation proof returns a plan ONLY for the canonical shape (a constant base
 * + a finite sequence of {@code if (param != null) sql.append(" AND col OP ?")} optional clauses, each
 * correlated to a {@code if (param != null) ps.setXxx(…, param)} bind on the SAME param) and refuses
 * ({@link Optional#empty()}) the moment ANY fail-safe condition holds. A mis-recognize would change
 * semantics for some param combination (or splice a value), so in doubt it must NOT recognize.
 */
class JdbcGuardedPredicateRecognizerTest {

    @TempDir
    Path tempDir;

    private static String wrap(String methodBody) {
        return """
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;
                import java.math.BigDecimal;

                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, BigDecimal minBalance,
                                           String name, Integer minAge, Long tier) throws SQLException {
                """ + methodBody + """
                    }
                    static void sink(String v) { }
                }
                """;
    }

    private Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> recognize(String methodBody)
            throws Exception {
        return recognizeFullSource(wrap(methodBody), "run");
    }

    private Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> recognizeFullSource(
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
        return new JdbcGuardedPredicateRecognizer(parsed, shapes, oracle)
                .recognize(body[0].getStatements(), bodyPath[0], bodyPath[0]);
    }

    // ===== positive recognition ==================================================================

    @Test
    void recognizesCanonicalStringBuilderSearch() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) { sql.append(" AND status = ?"); }
                if (minBalance != null) { sql.append(" AND balance >= ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the canonical StringBuilder search-builder must be recognized");
        assertEquals("SELECT id FROM accounts WHERE active = true", plan.get().baseSql());
        assertEquals(2, plan.get().clauses().size());
        assertEquals("status", plan.get().clauses().get(0).column());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.EQ, plan.get().clauses().get(0).op());
        assertEquals("balance", plan.get().clauses().get(1).column());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.GE, plan.get().clauses().get(1).op());
        assertEquals("SELECT id FROM accounts WHERE active = true"
                        + " AND (? IS NULL OR status = ?) AND (? IS NULL OR balance >= ?)",
                plan.get().guardedSql());
    }

    @Test
    void recognizesStringConcatPlusEqualsForm() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                String sql = "SELECT id FROM accounts WHERE active = true";
                if (status != null) sql += " AND status = ?";
                if (minBalance != null) sql += " AND balance >= ?";
                PreparedStatement ps = c.prepareStatement(sql);
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the String += search-builder must be recognized");
        assertEquals(2, plan.get().clauses().size());
    }

    @Test
    void recognizesStringSelfAssignConcatForm() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                String sql = "SELECT id FROM accounts WHERE active = true";
                if (status != null) { sql = sql + " AND status = ?"; }
                PreparedStatement ps = c.prepareStatement(sql);
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the String `sql = sql + ...` search-builder must be recognized");
        assertEquals(1, plan.get().clauses().size());
    }

    @Test
    void recognizesAllSupportedOperators() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.math.BigDecimal;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String a, String b, Integer d, Integer e,
                                           Integer f, Integer g, String h) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM t WHERE x = 1");
                        if (a != null) sql.append(" AND a = ?");
                        if (b != null) sql.append(" AND b <> ?");
                        if (d != null) sql.append(" AND d < ?");
                        if (e != null) sql.append(" AND e <= ?");
                        if (f != null) sql.append(" AND f > ?");
                        if (g != null) sql.append(" AND g >= ?");
                        if (h != null) sql.append(" AND h LIKE ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (a != null) ps.setString(i++, a);
                        if (b != null) ps.setString(i++, b);
                        if (d != null) ps.setInt(i++, d);
                        if (e != null) ps.setInt(i++, e);
                        if (f != null) ps.setInt(i++, f);
                        if (g != null) ps.setInt(i++, g);
                        if (h != null) ps.setString(i++, h);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "all 7 supported operators (=,<>,<,<=,>,>=,LIKE) must be recognized");
        List<JdbcGuardedPredicateRecognizer.OptionalClause> clauses = plan.get().clauses();
        assertEquals(JdbcGuardedPredicateRecognizer.Op.EQ, clauses.get(0).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.NE, clauses.get(1).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.LT, clauses.get(2).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.LE, clauses.get(3).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.GT, clauses.get(4).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.GE, clauses.get(5).op());
        assertEquals(JdbcGuardedPredicateRecognizer.Op.LIKE, clauses.get(6).op());
    }

    @Test
    void recognizesNotEqualBangForm() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status != ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the != operator must be recognized (canonicalized to <>)");
        assertEquals(JdbcGuardedPredicateRecognizer.Op.NE, plan.get().clauses().get(0).op());
        assertTrue(plan.get().guardedSql().contains("status <> ?"), "!= must emit as <>");
    }

    @Test
    void recognizesNoArgStringBuilderWithBaseAppendedFirst() throws Exception {
        // A new StringBuilder() seeded by a first non-guarded append IS NOT the recognized base form (the
        // base must be the constructor seed or a constant String). A no-arg builder with no base append is
        // a degenerate empty base — but the first append here is guarded, so the base is empty. We just
        // verify the empty-base form recognizes when the constructor has no seed and clauses are guarded.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE 1=1");
                        if (status != null) sql.append(" AND status = ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (status != null) ps.setString(1, status);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent());
        assertEquals("SELECT id FROM accounts WHERE 1=1", plan.get().baseSql());
    }

    // ===== no-false-recognize: the fail-safe conditions ==========================================

    @Test
    void rejectsNonNullCheckCondition() throws Exception {
        // `if (minAge > 0)` is not a NULL-check — it does not map to `(p IS NULL OR …)`. Refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (minAge > 0) sql.append(" AND age >= ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (minAge > 0) ps.setInt(1, minAge);
                ps.executeUpdate();
                """).isEmpty(), "a non-null-check guard (x > 0) must not be recognized");
    }

    @Test
    void rejectsIsEmptyCondition() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (!status.isEmpty()) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (!status.isEmpty()) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an isEmpty() guard must not be recognized");
    }

    @Test
    void rejectsCompoundAndCondition() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null && minBalance != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null && minBalance != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a compound && guard must not be recognized");
    }

    @Test
    void rejectsElseBranch() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) { sql.append(" AND status = ?"); } else { sql.append(" AND status IS NULL"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an else branch on the optional-clause if must not be recognized");
    }

    @Test
    void rejectsOrClause() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" OR status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an OR clause (not AND) must not be recognized");
    }

    @Test
    void rejectsSubqueryClause() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND id IN (SELECT id FROM x WHERE s = ?)");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a subquery clause must not be recognized (the ( and IN defeat)");
    }

    @Test
    void rejectsTwoPlaceholderClause() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status BETWEEN ? AND ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) { ps.setString(1, status); ps.setString(2, status); }
                ps.executeUpdate();
                """).isEmpty(), "a two-? clause (BETWEEN) must not be recognized");
    }

    @Test
    void rejectsRawFragmentClause() throws Exception {
        // A clause with no `?` placeholder (a raw predicate fragment) is not a single bound comparison.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status IS NOT NULL");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                ps.executeUpdate();
                """).isEmpty(), "a raw-fragment clause (no ?) must not be recognized");
    }

    @Test
    void rejectsIdentifierHoleClause() throws Exception {
        // A runtime identifier spliced into the clause text (not a constant) -> the append text is not
        // constant -> the append guard does not recognize.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (name != null) sql.append(" AND " + name + " = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (name != null) ps.setString(1, name);
                ps.executeUpdate();
                """).isEmpty(), "a non-constant clause text (identifier splice) must not be recognized");
    }

    @Test
    void rejectsValueSpliceInClause() throws Exception {
        // A value concatenated into the clause text (not bound) -> non-constant append text -> refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = '" + status + "'");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                ps.executeUpdate();
                """).isEmpty(), "a value-splice clause text must not be recognized");
    }

    @Test
    void rejectsBindToDifferentParam() throws Exception {
        // The clause gates on `status` but the bind binds `name` -> the guarded predicate would compare the
        // wrong value. Refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, name);
                ps.executeUpdate();
                """).isEmpty(), "a ? bound to a different param than the gate must not be recognized");
    }

    @Test
    void rejectsBindGatedOnDifferentParam() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (minBalance != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a bind gated on a different param than the clause must not be recognized");
    }

    @Test
    void rejectsUnconditionalBind() throws Exception {
        // The clause is optional (gated) but the bind is unconditional -> when status is null the clause is
        // omitted but the bind still fires -> a desynced runtime ordinal. Refuse (the bind must be gated).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an unconditional bind for an optional clause must not be recognized");
    }

    @Test
    void rejectsMissingBind() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                if (minBalance != null) sql.append(" AND balance >= ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a clause with no correlated bind must not be recognized");
    }

    @Test
    void rejectsMisorderedBinds() throws Exception {
        // The binds are in the OPPOSITE order to the appends -> the Nth `?` would bind the wrong param.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                if (minBalance != null) sql.append(" AND balance >= ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (minBalance != null) ps.setBigDecimal(1, minBalance);
                if (status != null) ps.setString(2, status);
                ps.executeUpdate();
                """).isEmpty(), "binds in a different order than the appends must not be recognized");
    }

    @Test
    void rejectsLoopBuildingClauses() throws Exception {
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, List<String> cols, List<String> vals) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        for (int i = 0; i < cols.size(); i++) sql.append(" AND x = ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        for (int i = 0; i < vals.size(); i++) ps.setString(i + 1, vals.get(i));
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "a loop building clauses must not be recognized");
    }

    @Test
    void rejectsNonConstantBase() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE name = '" + name + "'");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a non-constant base must not be recognized");
    }

    @Test
    void rejectsNonAppendStatementBetweenDeclAndPrepare() throws Exception {
        // An unconditional append (not gated) between the builder decl and the prepare is not an optional
        // clause -> the clause set is not all-optional -> refuse (every between-statement must be a guard).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                sql.append(" AND tier > 0");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an unconditional append between decl and prepare must not be recognized");
    }

    @Test
    void rejectsBuilderEscape() throws Exception {
        // The builder is passed to another method (escapes) -> we cannot prove it is consumed only by the
        // appends. Refuse.
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        log(sql);
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (status != null) ps.setString(1, status);
                        ps.executeUpdate();
                    }
                    static void log(StringBuilder b) { }
                }
                """, "run").isEmpty(), "a builder that escapes to a method must not be recognized");
    }

    @Test
    void rejectsParamUsedInValuePositionElsewhere() throws Exception {
        // `status` is also bound a second time in a value position (a non-gate, non-bind use) -> the
        // guarded predicate would not faithfully reproduce the source. Refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                sink(status);
                ps.executeUpdate();
                """).isEmpty(), "an optional param used elsewhere in a value position must not be recognized");
    }

    @Test
    void rejectsNoOptionalClauses() throws Exception {
        // A constant-base builder with no optional clauses is just a constant statement — not the search
        // builder. (It falls through to the existing constant/skeleton path.)
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                ps.executeUpdate();
                """).isEmpty(), "a builder with no optional clauses must not be recognized as a guarded predicate");
    }

    @Test
    void recognizesSameParamGatingTwoClauses() throws Exception {
        // The same param may gate two distinct clauses (each its own guarded predicate); the binds are two
        // gated setXxx on that param, in clause order. Semantically each clause is independent.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) { sql.append(" AND a = ?"); }
                if (status != null) { sql.append(" AND b <> ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (status != null) ps.setString(i++, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the same param gating two clauses must be recognized");
        assertEquals(2, plan.get().clauses().size());
        assertEquals("a", plan.get().clauses().get(0).column());
        assertEquals("b", plan.get().clauses().get(1).column());
    }

    @Test
    void recognizesLocalAsGateParam() throws Exception {
        // The gate/bind variable may be a local (not just a method param) — a `String tier = …` derived
        // value used as an optional filter. (The local's own declaration lowers normally; it is bound by
        // name, exactly like a param.)
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String raw) throws SQLException {
                        String tier = raw;
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (tier != null) ps.setString(1, tier);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "a local used as the gate/bind param must be recognized");
        assertEquals("tier", plan.get().clauses().get(0).column());
    }

    @Test
    void rejectsQualifiedColumnIsStillRecognized() throws Exception {
        // A schema/table-qualified column `a.status` is a single column identifier (dotted) — still a valid
        // guarded predicate (the column is structural text, not a value). Recognized.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts a WHERE a.active = true");
                if (status != null) sql.append(" AND a.status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a qualified column a.status must be recognized");
        assertEquals("a.status", plan.get().clauses().get(0).column());
    }

    @Test
    void rejectsClauseWithFunctionCall() throws Exception {
        // A function on the column side (LOWER(name)) introduces a `(` — not a single bare column. Refuse
        // (the recognizer is deliberately narrow; this is a fail-safe reject, not a miscompile).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (name != null) sql.append(" AND LOWER(name) = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (name != null) ps.setString(1, name);
                ps.executeUpdate();
                """).isEmpty(), "a clause with a function call on the column must not be recognized");
    }

    @Test
    void rejectsSecondPrepareOverBuilder() throws Exception {
        // The builder feeds TWO prepares — we cannot prove it is consumed only by the appends/one prepare.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                PreparedStatement ps2 = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a builder consumed by two prepares must not be recognized");
    }

    @Test
    void rejectsAppendAfterPrepare() throws Exception {
        // A `sql.append(...)` AFTER the prepare is a builder use outside the proven append guards / consuming
        // prepare — it would not be reflected in the already-captured text and is not elided. Refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                sql.append(" AND extra = 1");
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an append after the consuming prepare must not be recognized");
    }

    @Test
    void rejectsBuilderLengthRead() throws Exception {
        // A `sql.length()` read is a builder use outside the appends/consume — defeats the use proof.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                int n = sql.length();
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a builder length() read must not be recognized");
    }

    @Test
    void rejectsExtraBindBeyondClauses() throws Exception {
        // An EXTRA gated bind with no matching clause (more binds than clauses) -> the bind/clause
        // correlation is not 1:1 -> refuse (a stray bind would desync the runtime ordinals).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                ps.executeUpdate();
                """).isEmpty(), "an extra gated bind with no matching clause must not be recognized");
    }

    @Test
    void rejectsBaseWithoutWhere() throws Exception {
        // A base with NO WHERE has no predicate context for the always-present ` AND (? IS NULL OR …)` to
        // attach to. The all-null case would diverge (the original's bare `SELECT … FROM t` is valid; our
        // `SELECT … FROM t AND (NULL IS NULL …)` is malformed). Fail-safe reject.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base without a WHERE must not be recognized");
    }

    // ----- the WHERE must be the TERMINAL, top-level predicate context (guard-attachable) -------------
    // The guard ` AND (? IS NULL OR col OP ?)` is ALWAYS appended at the END of the base. It only attaches
    // as a boolean conjunct of the WHERE when that WHERE is at the query top level AND is the last clause.
    // A WHERE followed by a tail clause (ORDER BY / GROUP BY / HAVING / LIMIT / OFFSET / FETCH / FOR /
    // RETURNING / UNION) or a WHERE only inside a subquery is NOT guard-attachable: the appended ` AND (…)`
    // lands after the tail / after a `)` and the ALL-NULL call diverges from the conditional builder (which
    // emits the bare, valid base) — a hard SQL error at CALL time. All such bases must FAIL-SAFE reject.

    @Test
    void rejectsBaseWithTrailingOrderByAfterWhere() throws Exception {
        // `… WHERE active = true ORDER BY id` → the guard would append after ORDER BY (`… ORDER BY id AND
        // (…)`), malformed. The all-null call of the original (`… WHERE active = true ORDER BY id`) is valid.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true ORDER BY id");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by ORDER BY must not be recognized "
                + "(the appended AND (…) lands after ORDER BY, malforming the all-null query)");
    }

    @Test
    void rejectsBaseWithTrailingOrderByLimitAfterWhere() throws Exception {
        // The auditor's live repro: `… WHERE active = true ORDER BY id LIMIT 2`. On PG the appended
        // `LIMIT 2 AND (…)` parses as `LIMIT (2 AND …)` → "argument of AND must be type boolean".
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id, tier FROM accounts WHERE active = true ORDER BY id LIMIT 2");
                if (tier != null) { sql.append(" AND tier = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (tier != null) ps.setString(1, tier);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by ORDER BY/LIMIT must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingGroupByAfterWhere() throws Exception {
        // `… WHERE active = true GROUP BY tier` → `GROUP BY tier AND (…)` parses as `GROUP BY (tier AND …)`.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT tier, COUNT(id) FROM accounts WHERE active = true GROUP BY tier");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by GROUP BY must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingHavingAfterWhere() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT tier FROM accounts WHERE active = true GROUP BY tier HAVING COUNT(id) > 1");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by GROUP BY/HAVING must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingLimitAfterWhere() throws Exception {
        // The MySQL UPDATE … LIMIT repro: `UPDATE accounts SET flagged = 1 WHERE active = true LIMIT 2` —
        // the appended `LIMIT 2 AND (? IS NULL OR tier = ?)` is a MySQL syntax error at CALL time.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "UPDATE accounts SET flagged = 1 WHERE active = true LIMIT 2");
                if (tier != null) { sql.append(" AND tier = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (tier != null) ps.setString(1, tier);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by LIMIT must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingOffsetAfterWhere() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE active = true OFFSET 5");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by OFFSET must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingFetchAfterWhere() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE active = true FETCH FIRST 2 ROWS ONLY");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by FETCH FIRST must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingForUpdateAfterWhere() throws Exception {
        // `… WHERE active = true FOR UPDATE` → `FOR UPDATE AND (…)` is malformed (and changes locking).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE active = true FOR UPDATE");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by FOR UPDATE must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingReturningAfterWhere() throws Exception {
        // `UPDATE … WHERE active = true RETURNING id` → `RETURNING id AND (…)` is malformed.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "UPDATE accounts SET flagged = 1 WHERE active = true RETURNING id");
                if (tier != null) { sql.append(" AND tier = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (tier != null) ps.setString(1, tier);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by RETURNING must not be recognized");
    }

    @Test
    void rejectsBaseWithTrailingUnionAfterWhere() throws Exception {
        // `… WHERE active = true UNION SELECT …` → the appended AND lands in the SECOND select's tail.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE active = true UNION SELECT id FROM archived");
                if (status != null) { sql.append(" AND status = ?"); }
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base WHERE followed by UNION must not be recognized");
    }

    @Test
    void rejectsSubqueryOnlyWhereBase() throws Exception {
        // The outer query has NO top-level WHERE; the only WHERE is inside the derived table. Appending
        // ` AND (? IS NULL OR …)` yields `… sub AND (…)` — malformed, and the all-null case diverges from
        // the original's valid bare `… sub`. Must FALL THROUGH (fail-safe), like a WHERE-less base.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM (SELECT * FROM t WHERE active) sub");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base whose only WHERE is inside a subquery must NOT be recognized");
    }

    @Test
    void rejectsSubqueryOnlyWhereUpdateBase() throws Exception {
        // `UPDATE … = (SELECT 1 … WHERE m = 1)` — the only WHERE is inside the scalar subquery; the guard
        // would append after the statement tail (`… ) AND (…)`), diverging on the all-null call.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder(
                        "UPDATE accounts SET flagged = (SELECT 1 FROM marker WHERE m = 1)");
                if (tier != null) sql.append(" AND tier = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (tier != null) ps.setString(1, tier);
                ps.executeUpdate();
                """).isEmpty(), "a base whose only WHERE is inside a scalar subquery must NOT be recognized");
    }

    @Test
    void recognizesTopLevelWhereWithSubqueryWhereInPredicate() throws Exception {
        // CONTROL: a genuine top-level terminal WHERE whose predicate CONTAINS a subquery (with its own
        // WHERE) is still guard-attachable — the trailing ` AND (…)` attaches to the top-level WHERE, after
        // the `IN (…)`. This must still recognize (the subquery WHERE is at depth > 0, the outer at depth 0,
        // and nothing top-level follows it).
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE id IN (SELECT account_id FROM o WHERE paid = true)");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a top-level terminal WHERE with a subquery in its predicate is "
                + "guard-attachable and must be recognized");
        assertEquals("SELECT id FROM accounts WHERE id IN (SELECT account_id FROM o WHERE paid = true)",
                plan.get().baseSql());
    }

    @Test
    void recognizesBaseWhoseLiteralSpellsTrailingKeyword() throws Exception {
        // CONTROL: a string literal that merely SPELLS a tail keyword (`status = 'ORDER BY'`) is data, not
        // a clause — the WHERE is still the terminal top-level predicate, so it must recognize (the keyword
        // scan skips quoted regions). Guards against a too-eager textual reject.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE label = 'ORDER BY x LIMIT 1'");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a tail keyword inside a string literal is data, not a clause — "
                + "the terminal top-level WHERE must still be recognized");
    }

    @Test
    void recognizesColumnNamedLikeKeywordIsNotMistakenForTail() throws Exception {
        // CONTROL: a column whose name merely CONTAINS a keyword substring (`order_id`, `group_code`) is a
        // word token but NOT the bare keyword (`ORDER`/`GROUP`) — whole-word matching must not reject it.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder(
                        "SELECT id FROM accounts WHERE order_id > 0 AND group_code = 7");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a column name containing a keyword substring (order_id/group_code) "
                + "is not a tail clause — the WHERE is still terminal and must be recognized");
    }

    @Test
    void recognizesWhereOneEqualsOneBase() throws Exception {
        // The common `WHERE 1=1` seed base (so every optional clause is a uniform ` AND …`). Valid.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE 1=1");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a `WHERE 1=1` seed base must be recognized");
        assertEquals("SELECT id FROM accounts WHERE 1=1", plan.get().baseSql());
    }

    @Test
    void rejectsEqEqNullGate() throws Exception {
        // `if (status == null)` is the INVERSE of the optional-clause gate — it would append when the param
        // is null. Not the recognized shape.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status == null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status == null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an `== null` gate (the inverse) must not be recognized");
    }

    // ===== the ORDERED / PAGINATED builder: a TRAILING run of UNCONDITIONAL constant appends ==========
    // After the gated optional-clause appends, an ordered/paginated builder appends the trailing clause
    // (` ORDER BY id`, ` GROUP BY x`, …) UNCONDITIONALLY (for every call). The base STAYS WHERE-terminal —
    // the guards attach to the base WHERE, the constant tail follows them: `<base> <guards> <tail>`. The
    // tail does not change the filter logic (same row set), only the ordering/grouping. Admitted ONLY when
    // the tail is a constant, placeholder-free, non-predicate suffix STRICTLY AFTER every gated append.

    @Test
    void recognizesTrailingOrderBy() throws Exception {
        // The canonical ordered builder: 2 optional filters + a trailing unconditional ` ORDER BY id`.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) { sql.append(" AND status = ?"); }
                if (minBalance != null) { sql.append(" AND balance >= ?"); }
                sql.append(" ORDER BY id");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "an ordered builder with a trailing ORDER BY must be recognized");
        assertEquals("SELECT id FROM accounts WHERE active = true", plan.get().baseSql());
        assertEquals(" ORDER BY id", plan.get().tailSql());
        assertEquals(2, plan.get().clauses().size());
        // The guards sit at the WHERE boundary; the constant tail follows them.
        assertEquals("SELECT id FROM accounts WHERE active = true"
                        + " AND (? IS NULL OR status = ?) AND (? IS NULL OR balance >= ?) ORDER BY id",
                plan.get().guardedSql());
    }

    @Test
    void recognizesTrailingOrderByDesc() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id DESC");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a trailing ORDER BY id DESC must be recognized");
        assertEquals(" ORDER BY id DESC", plan.get().tailSql());
        assertTrue(plan.get().guardedSql().endsWith(" AND (? IS NULL OR status = ?) ORDER BY id DESC"),
                "the DESC tail must follow the guards verbatim; was: " + plan.get().guardedSql());
    }

    @Test
    void recognizesTrailingGroupBy() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a trailing GROUP BY must be recognized");
        assertEquals(" GROUP BY tier", plan.get().tailSql());
    }

    @Test
    void recognizesMultipleConcatenatedTrailingAppends() throws Exception {
        // Several unconditional trailing appends — ` GROUP BY tier` then ` ORDER BY tier` — concatenated
        // (in source order) into one constant tail, after the guards.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier");
                sql.append(" ORDER BY tier");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "multiple concatenated trailing appends must be recognized");
        assertEquals(" GROUP BY tier ORDER BY tier", plan.get().tailSql());
        assertTrue(plan.get().guardedSql().endsWith(
                        " AND (? IS NULL OR status = ?) GROUP BY tier ORDER BY tier"),
                "the concatenated tail must follow the guards; was: " + plan.get().guardedSql());
    }

    @Test
    void recognizesTrailingOrderByViaStringConcat() throws Exception {
        // The += form: the trailing unconditional ` ORDER BY id` is a `sql += " ORDER BY id"` (no gate).
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                String sql = "SELECT id FROM accounts WHERE active = true";
                if (status != null) sql += " AND status = ?";
                sql += " ORDER BY id";
                PreparedStatement ps = c.prepareStatement(sql);
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a trailing ORDER BY appended via String += must be recognized");
        assertEquals(" ORDER BY id", plan.get().tailSql());
    }

    @Test
    void recognizesNoTailBuilderHasEmptyTail() throws Exception {
        // REGRESSION: the existing no-tail builder must be unchanged — recognized with an EMPTY tail (the
        // guardedSql is exactly the base + guards, no trailing text).
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "the no-tail builder must still be recognized (unchanged)");
        assertEquals("", plan.get().tailSql(), "a no-tail builder must have an empty tail");
        assertEquals("SELECT id FROM accounts WHERE active = true AND (? IS NULL OR status = ?)",
                plan.get().guardedSql());
    }

    // ----- no-false-recognize for the tail: non-pagination ? / non-constant / interspersed / before / clause
    // (The PARAMETERIZED pagination tail — ` LIMIT ?`, ` OFFSET ?`, ` LIMIT ?, ?`, ` FETCH FIRST ? ROWS ONLY`
    // — is NOW recognized when each tail ? is a count/offset operand correlated 1:1 to a strictly-trailing
    // UNCONDITIONAL setXxx bind; the parameterized-pagination accept cases + their reject cases follow below.)

    @Test
    void recognizesParameterizedLimitTailWithCorrelatedBind() throws Exception {
        // A trailing ` ORDER BY id LIMIT ?` whose ? is a LIMIT count operand, correlated to a trailing
        // UNCONDITIONAL `ps.setInt(i++, 10)` bind — recognized; the captured tail param is the bound 10.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 10);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a ` LIMIT ?` tail with a correlated unconditional bind must be recognized");
        assertEquals(" ORDER BY id LIMIT ?", plan.get().tailSql());
        assertEquals(1, plan.get().tailParamExprs().size(), "the LIMIT ? must capture exactly one tail param");
        assertEquals("10", plan.get().tailParamExprs().get(0).toString(), "the tail param is the bound count 10");
        // The guards (always present, textually first) precede the verbatim pagination tail.
        assertEquals("SELECT id FROM accounts WHERE active = true"
                        + " AND (? IS NULL OR status = ?) ORDER BY id LIMIT ?",
                plan.get().guardedSql());
    }

    @Test
    void recognizesBareLimitTailWithCorrelatedBind() throws Exception {
        // The bare ` LIMIT ?` (no ORDER BY) — FETCH/LIMIT is whitelisted-leading, the ? is the LIMIT operand,
        // correlated to a trailing unconditional bind. Recognized; one captured tail param.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 5);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a bare ` LIMIT ?` tail with a correlated unconditional bind must be recognized");
        assertEquals(" LIMIT ?", plan.get().tailSql());
        assertEquals(1, plan.get().tailParamExprs().size());
    }

    @Test
    void rejectsNonConstantTail() throws Exception {
        // A trailing append whose text is a runtime splice (` ORDER BY " + name`) is NOT constant — the
        // recovered tail would carry a runtime value. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY " + name);
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a non-constant (runtime-spliced) tail must not be recognized");
    }

    @Test
    void rejectsInterspersedUnconditionalAppend() throws Exception {
        // An unconditional append BETWEEN two gated appends (not strictly trailing) — the tail must come
        // STRICTLY AFTER every gated append. Interspersed → the existing reject (unconditional append
        // between decl and prepare that is not a trailing tail). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id");
                if (minBalance != null) sql.append(" AND balance >= ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                ps.executeUpdate();
                """).isEmpty(), "an unconditional append interspersed with the gated appends must not be recognized");
    }

    @Test
    void rejectsTailBeforeAnyGatedAppend() throws Exception {
        // The trailing append comes BEFORE any gated append — there is no gated clause before it, so it is
        // an unconditional append between decl and the first filter. This is the existing reject (a tail
        // before the filters is the same as an interspersed/leading unconditional append). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                sql.append(" ORDER BY id");
                if (status != null) sql.append(" AND status = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a tail BEFORE the gated appends must not be recognized");
    }

    @Test
    void rejectsTrailingAndClauseMasqueradingAsTail() throws Exception {
        // A trailing unconditional ` AND col2 = ?` is a real PREDICATE (a clause), not an ordering tail — it
        // carries a `?` AND begins with AND. It must NOT be treated as a constant tail (that would change the
        // row set: an unconditional extra filter folded into the static text). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" AND tier = ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setString(i++, "GOLD");
                ps.executeUpdate();
                """).isEmpty(), "a trailing ` AND col = ?` clause must not be recognized as a tail");
    }

    @Test
    void rejectsTrailingUnconditionalAndPredicateEvenWithoutPlaceholder() throws Exception {
        // A trailing unconditional ` AND tier = 'GOLD'` (no `?`) is STILL a predicate (leads with AND) — it
        // would fold an unconditional extra filter into the static text, changing the row set vs the builder.
        // The leading-AND guard rejects it (it is not an ordering/grouping tail). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" AND tier = 'GOLD'");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a trailing ` AND col = const` predicate must not be recognized as a tail");
    }

    @Test
    void rejectsTrailingOrPredicateAsTail() throws Exception {
        // A trailing ` OR …` leads with the OR connective — a predicate, not an ordering tail. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" OR vip = true");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a trailing ` OR …` predicate must not be recognized as a tail");
    }

    @Test
    void rejectsTailWithQuotedLiteral() throws Exception {
        // A trailing append carrying a quoted literal (` ORDER BY ... 'x'`) fails the lexical-hygiene guard —
        // a quote in the spliced tail is unsafe (it could hide a placeholder/terminator). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY label COLLATE 'C'");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a tail carrying a quoted literal must not be recognized");
    }

    @Test
    void rejectsTailWithMysqlHashComment() throws Exception {
        // A MySQL `#` line comment in the tail comments out everything after it on that line. On a MySQL build
        // the tail splices VERBATIM into a MySQL procedure, so a `#` would desync the recognizer's tail-? count
        // from the ?s MySQL actually sees (here the recognizer would otherwise count 2, MySQL parses only 1).
        // Lexical-hygiene reject — keep rejecting comments (incl. `#`). Asserted directly at the lexer seam so it
        // holds for ANY targeted dialect, plus via the full recognizer.
        assertEquals(-1, JdbcGuardedPredicateRecognizer.tailPaginationPlaceholders(" LIMIT ? #x OFFSET ?"),
                "a `#` line comment in the tail must be rejected (count mismatch with MySQL)");
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, int off, int cnt) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" LIMIT ? #x OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, off);
                        ps.setInt(i++, cnt);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "a tail carrying a `#` MySQL comment must not be recognized");
    }

    @Test
    void rejectsMalformedThreeOperandLimitCommaTail() throws Exception {
        // `LIMIT a, b, ?` is a malformed 3-operand LIMIT (a SYNTAX ERROR in both MySQL and PostgreSQL). The
        // MySQL comma form permits at most ONE comma (LIMIT offset, count). A `?` after a SECOND comma under
        // LIMIT must be rejected — fail-safe (a TITAN reject), never a non-deployable artifact. Asserted at the
        // lexer seam (the count must be REJECT_TAIL, not 1) and via the full recognizer.
        assertEquals(-1, JdbcGuardedPredicateRecognizer.tailPaginationPlaceholders(" ORDER BY id LIMIT 10, 20, ?"),
                "a 3-operand `LIMIT a, b, ?` must be rejected, not counted as 1 pagination ?");
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, int x) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" ORDER BY id LIMIT 10, 20, ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, x);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "a malformed 3-operand LIMIT comma tail must not be recognized");
    }

    @Test
    void rejectsBuilderUseInTailRegionThatIsNotAnAppend() throws Exception {
        // A `sql.length()` read in the trailing region (not an append) is a builder use that is not a tail
        // append — it is not a bare constant append onto the builder, so the recognizer refuses (the
        // between-statement is neither a gated clause nor a constant tail append). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                int n = sql.length();
                sql.append(" ORDER BY id");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a non-append builder use in the tail region must not be recognized");
    }

    // ----- the tail-admission WHITELIST: a tail must be a PURE ordering/grouping/pagination clause ------
    // The tail is spliced VERBATIM after the always-present guards (`<base … WHERE …> AND (…)… <tail>`), so
    // it must be a genuine trailing ORDER BY / GROUP BY / HAVING / LIMIT n / OFFSET / FETCH / FOR-UPDATE /
    // WINDOW clause that the guarded WHERE simply precedes — never a construct that RE-OPENS or EXTENDS the
    // query. tailPaginationPlaceholders is a WHITELIST (first top-level word ∈ the ordering keywords,
    // no top-level UNION/INTERSECT/EXCEPT/WHERE/SELECT, no top-level AND/OR), not just a leading-AND/OR
    // blacklist: a UNION tail / second WHERE / leading comma / leading paren / HAVING…OR / LIMIT n OR — each
    // STARTS with (or contains at top level) a non-ordering construct, so it must FALL THROUGH (fail-safe),
    // routing the whole builder to the strict/Rung path rather than folding the construct into static text.

    @Test
    void rejectsUnionTail() throws Exception {
        // ` UNION SELECT …` starts a SECOND select — the guards would filter only the FIRST. Not an ordering
        // tail (first top-level word UNION is not whitelisted). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" UNION SELECT id FROM secrets");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` UNION SELECT …` tail must not be recognized (it re-opens the query)");
    }

    @Test
    void rejectsUnionAllTail() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" UNION ALL SELECT id FROM secrets");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` UNION ALL SELECT …` tail must not be recognized");
    }

    @Test
    void rejectsIntersectTail() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" INTERSECT SELECT id FROM vips");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an ` INTERSECT …` tail must not be recognized");
    }

    @Test
    void rejectsExceptTail() throws Exception {
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" EXCEPT SELECT id FROM banned");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "an ` EXCEPT …` tail must not be recognized");
    }

    @Test
    void rejectsUnionTailAfterAValidOrderByPrefix() throws Exception {
        // The tail STARTS with a whitelisted keyword (ORDER) but then re-opens the query with a top-level
        // UNION. The whole-tail top-level scan (not just the leading token) catches the UNION. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id UNION SELECT id FROM secrets");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` ORDER BY … UNION SELECT …` tail must not be recognized (top-level UNION)");
    }

    @Test
    void rejectsSecondWhereTail() throws Exception {
        // A second ` WHERE …` re-opens the predicate context (a double WHERE). First top-level word WHERE is
        // not whitelisted. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" WHERE deleted = false");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a second ` WHERE …` tail must not be recognized");
    }

    @Test
    void rejectsLeadingCommaTail() throws Exception {
        // A leading-comma ` , extra` extends a list — it does not BEGIN with an ordering clause keyword.
        // Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" , extra");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a leading-comma ` , extra` tail must not be recognized");
    }

    @Test
    void rejectsLeadingCloseParenTail() throws Exception {
        // A leading close-paren `) ORDER BY id` re-closes a paren before the ordering clause — the tail does
        // not BEGIN with an ordering keyword (the first top-level token is `)`). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(") ORDER BY id");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a leading close-paren `) ORDER BY id` tail must not be recognized");
    }

    @Test
    void rejectsLeadingOpenParenTail() throws Exception {
        // A leading open-paren `( … ) ORDER BY id` likewise does not BEGIN with an ordering keyword (the
        // first top-level token is `(`). Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" (SELECT 1) ORDER BY id");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a leading open-paren `( … ) ORDER BY id` tail must not be recognized");
    }

    @Test
    void rejectsHavingWithTopLevelOrTail() throws Exception {
        // The tail STARTS with GROUP (whitelisted) but the HAVING body carries a TOP-LEVEL OR — a boolean
        // connective at depth 0 in the tail is conservatively a non-ordering construct. Fall through
        // (fail-safe: a HAVING with a top-level boolean is rare; route it to the strict/Rung path).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier HAVING COUNT(*) > 0 OR tier = 9");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` GROUP BY … HAVING … OR …` tail (top-level OR) must not be recognized");
    }

    @Test
    void rejectsLimitNOrInjectionTail() throws Exception {
        // ` LIMIT 5 OR 1=1` STARTS with LIMIT (whitelisted) but the top-level OR (parsing as `LIMIT (5 OR
        // 1=1)`) is a boolean connective at depth 0 — not a pure pagination clause. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id");
                sql.append(" LIMIT 5 OR 1=1");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` LIMIT 5 OR 1=1` tail (top-level OR) must not be recognized");
    }

    @Test
    void rejectsUnbalancedOpenParenBase() throws Exception {
        // The base ends at paren depth > 0 (an unclosed subquery whose `)` is appended in the tail). The
        // recovered top-level WHERE is ambiguous with the inner subquery's WHERE, so the guard `AND (…)`
        // appended at end-of-base would land INSIDE the still-open subquery. baseWhereIsGuardAttachable now
        // requires depth==0 at end of scan → fall through (fail-safe).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE id IN (SELECT x FROM t WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(")");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a base ending at non-zero paren depth (unbalanced '(') must not be recognized");
    }

    // ----- positive locks: the WHITELIST still recognizes the GENUINE ordering/pagination tail idiom ----
    // Tightening the tail admission must NOT regress the real idiom — a bare HAVING / LIMIT n / OFFSET n /
    // FETCH FIRST … ROWS / FOR UPDATE tail (no top-level boolean, no query re-open) is still recognized and
    // spliced verbatim after the guards.

    @Test
    void recognizesBareHavingTail() throws Exception {
        // A bare ` HAVING COUNT(*) > 0` (no top-level boolean) is a legit grouping suffix — recognized.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier HAVING COUNT(*) > 0");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a bare GROUP BY … HAVING (no top-level boolean) tail must be recognized");
        assertEquals(" GROUP BY tier HAVING COUNT(*) > 0", plan.get().tailSql());
        assertTrue(plan.get().guardedSql().endsWith(
                        " AND (? IS NULL OR status = ?) GROUP BY tier HAVING COUNT(*) > 0"),
                "the HAVING tail must follow the guards verbatim; was: " + plan.get().guardedSql());
    }

    @Test
    void recognizesConstantLimitTail() throws Exception {
        // A CONSTANT ` LIMIT 50` (no `?`, no top-level boolean) is a legit pagination suffix — recognized.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id LIMIT 50");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a constant ` ORDER BY id LIMIT 50` tail must be recognized");
        assertEquals(" ORDER BY id LIMIT 50", plan.get().tailSql());
    }

    @Test
    void recognizesOffsetTail() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id OFFSET 10");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "a constant ` ORDER BY id OFFSET 10` tail must be recognized");
        assertEquals(" ORDER BY id OFFSET 10", plan.get().tailSql());
    }

    @Test
    void recognizesFetchFirstRowsTail() throws Exception {
        // ANSI ` FETCH FIRST 10 ROWS ONLY` — FETCH is whitelisted, no top-level boolean. Recognized.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id FETCH FIRST 10 ROWS ONLY");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "an ` ORDER BY id FETCH FIRST 10 ROWS ONLY` tail must be recognized");
        assertEquals(" ORDER BY id FETCH FIRST 10 ROWS ONLY", plan.get().tailSql());
    }

    @Test
    void recognizesForUpdateTail() throws Exception {
        // ` FOR UPDATE` — FOR is whitelisted, and UPDATE here is the object of the locking clause (NOT a
        // forbidden second statement). Recognized.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id FOR UPDATE");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """);
        assertTrue(plan.isPresent(), "an ` ORDER BY id FOR UPDATE` tail must be recognized");
        assertEquals(" ORDER BY id FOR UPDATE", plan.get().tailSql());
    }

    // ===== the PARAMETERIZED PAGINATION tail: ? in a count/offset position, bound by a trailing setXxx =====
    // The tail may carry a ? ONLY in a pagination count/offset position (LIMIT ?, OFFSET ?, LIMIT ?, ?,
    // OFFSET ? ROWS, FETCH FIRST/NEXT ? ROWS ONLY). Each such ? is a safe VALUE bind correlated 1:1 to a
    // strictly-trailing run of UNCONDITIONAL ps.setXxx(<ord>, <expr>) binds, in tail-? TEXT order. The forms
    // are single-dialect-native (emitted/deployed each on its own engine); the recognizer just recovers text
    // + captures the tail params, so these accept tests are dialect-agnostic. The captured tailParamExprs are
    // in tail-? text order (= source-bind order), and the lowerer binds them AFTER the clause binds.

    /** Asserts the recovered tailParamExprs (rendered as source text) equal {@code expected}, in order. */
    private static void assertTailParams(
            Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan, String... expected) {
        assertTrue(plan.isPresent(), "expected the parameterized-pagination tail to be recognized");
        List<String> actual = plan.get().tailParamExprs().stream().map(Object::toString).toList();
        assertEquals(List.of(expected), actual, "the captured tail params must be in tail-? text order");
    }

    @Test
    void recognizesLimitOffsetParameterizedTail() throws Exception {
        // PG-native ` ORDER BY id LIMIT ? OFFSET ?` — two pagination ?s (count then offset), bound by two
        // trailing unconditional setInt in text order: pageSize -> LIMIT ?, offset -> OFFSET ?.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int pageSize, int offset) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, pageSize);
                        ps.setInt(i++, offset);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "a ` LIMIT ? OFFSET ?` tail must be recognized");
        assertEquals(" ORDER BY id LIMIT ? OFFSET ?", plan.get().tailSql());
        assertTailParams(plan, "pageSize", "offset");
        assertEquals("SELECT id FROM accounts WHERE active = true"
                        + " AND (? IS NULL OR status = ?) ORDER BY id LIMIT ? OFFSET ?",
                plan.get().guardedSql());
    }

    @Test
    void recognizesMysqlLimitCommaParameterizedTail() throws Exception {
        // MySQL-native ` ORDER BY id DESC LIMIT ?, ?` (offset, count) — two pagination ?s; the trailing
        // unconditional setInt run is offset then pageSize (tail-? text order), so offset binds first.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, int offset, int pageSize) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" ORDER BY id DESC LIMIT ?, ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, offset);
                        ps.setInt(i++, pageSize);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "a MySQL ` LIMIT ?, ?` comma tail must be recognized");
        assertEquals(" ORDER BY id DESC LIMIT ?, ?", plan.get().tailSql());
        assertTailParams(plan, "offset", "pageSize"); // offset binds first (text order), count second.
    }

    @Test
    void recognizesOffsetOnlyParameterizedTail() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int skip) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" ORDER BY id OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, skip);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "a bare ` OFFSET ?` tail must be recognized");
        assertEquals(" ORDER BY id OFFSET ?", plan.get().tailSql());
        assertTailParams(plan, "skip");
    }

    @Test
    void recognizesOffsetRowsFetchNextParameterizedTail() throws Exception {
        // PG/ANSI ` OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` — two pagination ?s (offset then next-count).
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int skip, int take) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, skip);
                        ps.setInt(i++, take);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "an ` OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` tail must be recognized");
        assertEquals(" ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", plan.get().tailSql());
        assertTailParams(plan, "skip", "take");
    }

    @Test
    void recognizesFetchFirstParameterizedTail() throws Exception {
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int take) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" ORDER BY id FETCH FIRST ? ROWS ONLY");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, take);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent(), "a ` FETCH FIRST ? ROWS ONLY` tail must be recognized");
        assertEquals(" ORDER BY id FETCH FIRST ? ROWS ONLY", plan.get().tailSql());
        assertTailParams(plan, "take");
    }

    @Test
    void recognizesPaginationTailParamAsBareLocalReference() throws Exception {
        // The pagination operand may be a bare local/param (bound directly by name) — here a local `lim`.
        Optional<JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int lim) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, lim);
                        ps.executeUpdate();
                    }
                }
                """, "run");
        assertTrue(plan.isPresent());
        assertTailParams(plan, "lim");
    }

    // ----- no-false-recognize for the PARAMETERIZED pagination tail -----------------------------------

    @Test
    void rejectsOrderByPlaceholderTail() throws Exception {
        // ` ORDER BY ?` — a ? in an ORDER BY position is a bound CONSTANT (a no-op sort by a literal), NOT a
        // pagination count/offset. The ? is after `BY`, not a pagination keyword → reject (out of scope).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 1);
                ps.executeUpdate();
                """).isEmpty(), "a ` ORDER BY ?` tail (a bound no-op sort) must not be recognized");
    }

    @Test
    void rejectsGroupByPlaceholderTail() throws Exception {
        // ` GROUP BY ?` — likewise the ? is after `BY`, not a pagination operand → reject.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 1);
                ps.executeUpdate();
                """).isEmpty(), "a ` GROUP BY ?` tail must not be recognized");
    }

    @Test
    void rejectsForUpdateObjectPlaceholderTail() throws Exception {
        // ` FOR UPDATE OF ?` — the ? is the object of a FOR-locking clause (after UPDATE/OF), not a
        // pagination count/offset operand → reject.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id FOR UPDATE OF ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setString(i++, "accounts");
                ps.executeUpdate();
                """).isEmpty(), "a ? as the object of FOR UPDATE must not be recognized");
    }

    @Test
    void rejectsOrderByFunctionArgumentPlaceholderTail() throws Exception {
        // ` ORDER BY field(id, ?, ?)` — the ?s are INSIDE a function-call paren (depth > 0), not a top-level
        // pagination operand. A ? at paren depth > 0 is never a pagination count/offset → reject. (Pins the
        // depth>0 safety boundary: a function-argument ? must NOT be mistaken for a bindable pagination ?.)
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY field(id, ?, ?)");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 1);
                ps.setInt(i++, 2);
                ps.executeUpdate();
                """).isEmpty(), "a ? inside an ORDER BY function-call argument must not be recognized");
    }

    @Test
    void rejectsHavingPlaceholderTail() throws Exception {
        // ` GROUP BY tier HAVING count(*) > ?` — HAVING is whitelisted-leading but the ? follows `>` (an
        // operator that breaks pagination-keyword adjacency); HAVING is NOT a pagination introducer → reject.
        // A bound HAVING predicate value is not a pagination operand.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier HAVING count(*) > ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 0);
                ps.executeUpdate();
                """).isEmpty(), "a ? in a HAVING predicate must not be recognized");
    }

    @Test
    void rejectsHavingPlaceholderEvenWhenFollowedByLimitPlaceholder() throws Exception {
        // ` GROUP BY tier HAVING count(id) > ? LIMIT ?` — the HAVING ? (a non-pagination predicate value) must
        // DEFEAT the whole tail even though a later LIMIT ? is a valid pagination operand. A single unsafe-
        // position ? anywhere in the tail is fail-safe reject (we do not partially admit a tail).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT tier, COUNT(id) FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" GROUP BY tier HAVING count(id) > ? LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 0);
                ps.setInt(i++, 10);
                ps.executeUpdate();
                """).isEmpty(), "a HAVING ? must defeat the tail even with a trailing valid LIMIT ?");
    }

    @Test
    void rejectsGatedPaginationAppend() throws Exception {
        // ` if (lim != null) sql.append(" LIMIT ?")` is a GATED append — NOT the unconditional-tail shape.
        // Once the gated `status` append is in the (non-tail) clause run, a SECOND gated `if` append that is
        // not an ` AND col OP ?` clause defeats the between-statement check. Fall through.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                if (minAge != null) sql.append(" LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                if (minAge != null) ps.setInt(i++, minAge);
                ps.executeUpdate();
                """).isEmpty(), "a GATED if-guarded LIMIT ? pagination append must not be recognized");
    }

    @Test
    void rejectsLiteralOrdinalPaginationBindMixedWithOptionalFilters() throws Exception {
        // ` LIMIT ?` with an UNCONDITIONAL `ps.setInt(3, pageSize)` — a LITERAL ordinal 3 mixed with an
        // optional (gated) filter. The runtime ordinal of the LIMIT ? depends on how many filters fired, so a
        // bare literal cannot be proven correct for all combinations (it is a latent source bug when the
        // filter is null). Refuse — the pagination bind ordinal must be the self-adjusting counter (i++).
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, int pageSize) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (tier != null) ps.setString(1, tier);
                        ps.setInt(3, pageSize);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(),
                "a literal-ordinal pagination bind mixed with optional filters must not be recognized");
    }

    @Test
    void rejectsConstantExpressionOrdinalPaginationBindMixedWithOptionalFilters() throws Exception {
        // ` LIMIT ?` with an UNCONDITIONAL `ps.setInt(1 + 2, pageSize)` — a compile-time-constant EXPRESSION
        // ordinal. It is runtime-FIXED at 3 just like a bare literal, so it is the SAME latent source bug when
        // the fired-filter count varies. It must be rejected exactly as the bare literal `3` is — the ordinal
        // must be the self-adjusting counter (i++), never a fixed expression. (Guards the named fail-safe
        // against the constant-fold bypass: `constantIntValue` only saw a bare literal, not `1 + 2`.)
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String tier, int pageSize) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (tier != null) ps.setString(1, tier);
                        ps.setInt(1 + 2, pageSize);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(),
                "a constant-expression-ordinal pagination bind mixed with optional filters must not be recognized");
    }

    @Test
    void rejectsNamedConstantOrdinalPaginationBindMixedWithOptionalFilters() throws Exception {
        // ` LIMIT ?` with an UNCONDITIONAL `ps.setInt(POS, pageSize)` where `POS` is a `static final int = 3`.
        // A named compile-time constant is runtime-FIXED at 3 — the SAME latent bug as the bare literal when the
        // fired-filter count varies. It must be rejected; the ordinal must be the self-adjusting counter (i++),
        // not a field/constant reference. (Guards the named fail-safe against the named-static-final bypass: a
        // field identifier resolves to a non-LOCAL element, so the counter whitelist refuses it.)
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    static final int POS = 3;
                    @StoredProcedure
                    public static void run(Connection c, String tier, int pageSize) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (tier != null) sql.append(" AND tier = ?");
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (tier != null) ps.setString(1, tier);
                        ps.setInt(POS, pageSize);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(),
                "a named-constant-ordinal pagination bind mixed with optional filters must not be recognized");
    }

    @Test
    void rejectsPaginationTailWithNoCorrelatedBind() throws Exception {
        // A ` LIMIT ?` tail with NO trailing unconditional bind — the ? count (1) exceeds the unconditional
        // bind run (0). Fewer binds than tail ?s → reject (an unbound ? the guarded lowering cannot bind).
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                if (status != null) ps.setString(1, status);
                ps.executeUpdate();
                """).isEmpty(), "a ` LIMIT ?` tail with no correlated unconditional bind must not be recognized");
    }

    @Test
    void rejectsPaginationTailWithTooFewBinds() throws Exception {
        // Two pagination ?s (` LIMIT ? OFFSET ?`) but only ONE trailing unconditional bind — count mismatch.
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int a) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" LIMIT ? OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, a);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "two pagination ?s with one trailing bind (count mismatch) must not be recognized");
    }

    @Test
    void rejectsPaginationTailWithTooManyBinds() throws Exception {
        // One pagination ? (` LIMIT ?`) but TWO trailing unconditional binds — the extra setXxx over the
        // handle has no tail ? to correlate (handleBoundOnlyByMatchedBinds defeats the stray bind).
        assertTrue(recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                class Fixture {
                    @StoredProcedure
                    public static void run(Connection c, String status, int a, int b) throws SQLException {
                        StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                        if (status != null) sql.append(" AND status = ?");
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, a);
                        ps.setInt(i++, b);
                        ps.executeUpdate();
                    }
                }
                """, "run").isEmpty(), "one pagination ? with two trailing binds (extra bind) must not be recognized");
    }

    @Test
    void rejectsInterspersedBindBetweenClauseAndPaginationBinds() throws Exception {
        // A NON-bind statement (a `sink(...)` call) interspersed between the clause bind and the pagination
        // bind breaks the strictly-trailing contiguity — the pagination bind is not immediately after the
        // clause binds. Refuse. (Also `status` then escapes via sink → the param use-proof would defeat too.)
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                sink(status);
                ps.setInt(i++, 5);
                ps.executeUpdate();
                """).isEmpty(), "an interspersed statement between clause and pagination binds must not be recognized");
    }

    @Test
    void rejectsPaginationBindAfterConsume() throws Exception {
        // The pagination bind comes AFTER the consuming executeUpdate — it is not in the strictly-trailing run
        // before the consume. (executeUpdate is not the consuming prepare here, but the bind after the LAST
        // expected position breaks contiguity: the tail bind run is empty at the consume point.) Refuse.
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" LIMIT ?");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.executeUpdate();
                ps.setInt(i++, 5);
                """).isEmpty(), "a pagination bind after the consume must not be recognized");
    }

    @Test
    void rejectsConstantLimitWithSeparateUnconditionalBindOnHandle() throws Exception {
        // A CONSTANT ` LIMIT 50` tail (0 pagination ?s) but a stray unconditional `ps.setInt(2, 9)` over the
        // handle — there is no tail ? to correlate it, so handleBoundOnlyByMatchedBinds defeats the stray
        // bind. (Locks that a constant tail does NOT silently admit an extra unconditional bind.)
        assertTrue(recognize("""
                StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                if (status != null) sql.append(" AND status = ?");
                sql.append(" ORDER BY id LIMIT 50");
                PreparedStatement ps = c.prepareStatement(sql.toString());
                int i = 1;
                if (status != null) ps.setString(i++, status);
                ps.setInt(i++, 9);
                ps.executeUpdate();
                """).isEmpty(), "a constant-tail builder with a stray unconditional bind must not be recognized");
    }
}
