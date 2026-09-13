package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3 Rung 3 — Tier 3 identifier + raw-fragment splice under {@code permissive} (the design
 * contract D2 boundary). Source → parse → lower → assert the TIR shape (the consuming statement's {@link
 * RawSql} carries the right {@link SpliceBind}s, value holes still bind), then → emit and assert the
 * native per-dialect runtime text-assembly (PostgreSQL {@code format('%I'/'%s', …)}, MySQL backtick
 * {@code CONCAT}/verbatim {@code CONCAT}).
 *
 * <p><b>The invariant under test:</b> STRICT (the default) NEVER emits an identifier/raw-fragment splice
 * — it rejects with the §5.3 three-part {@code TITAN-E004} exactly as before; only an effectively-{@code
 * permissive} method (via method/class {@code @SqlSafety(PERMISSIVE)} or the build flag, narrowest-scope
 * wins) emits one. A genuine VALUE hole in the same statement still BINDS (only the identifier/fragment
 * is structural). A strict scope is never escalated — even alongside a permissive sibling.</p>
 */
class JdbcSpliceLoweringTest {

    @TempDir
    Path tempDir;

    // ---- sources ----------------------------------------------------------------------------------

    /** A permissive dynamic ORDER BY identifier ({@code "… ORDER BY " + sortCol}) — IDENTIFIER hole. */
    private static final String PERMISSIVE_ORDER_BY = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class DynOrder {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, String sortCol) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (SELECT id FROM accounts ORDER BY " + sortCol + " LIMIT 1)");
                    ps.executeUpdate();
                }
            }
            """;

    /**
     * A permissive dynamic TABLE NAME identifier ({@code "UPDATE " + tbl + " SET …"}) — IDENTIFIER hole
     * (a relation name, runtime-quoted, qualified-name aware). Distinct from the ORDER BY fixture above,
     * which is a sort EXPRESSION (RAW_FRAGMENT) — a table name is a single quotable identifier.
     */
    private static final String PERMISSIVE_TABLE_NAME = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class DynTable {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, String tbl, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE " + tbl + " SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    /** A permissive raw-fragment predicate ({@code "… WHERE " + predicate}) — RAW_FRAGMENT hole. */
    private static final String PERMISSIVE_RAW_PREDICATE = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class DynPredicate {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, String predicate) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE " + predicate);
                    ps.executeUpdate();
                }
            }
            """;

    /**
     * A permissive statement mixing a runtime IDENTIFIER (the {@code UPDATE <table>} name) AND a bound
     * value (WHERE tier = ?). Proves only the identifier is spliced while the value stays bound. (Uses a
     * table name — an IDENTIFIER hole — so the "value binds + identifier splices" contract is exercised on
     * a genuine IDENTIFIER, not the RAW_FRAGMENT an ORDER BY now lowers to.)
     */
    private static final String PERMISSIVE_IDENT_PLUS_VALUE = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class DynMixed {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, String tbl, String tier) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + tbl + " SET flagged = 1 WHERE tier = " + tier);
                    ps.executeUpdate();
                }
            }
            """;

    // ---- helpers (mirror JdbcLoweringTest / JdbcCollectionInLoweringTest) --------------------------

    private record LowerResult(Map<String, Block> lowered, DiagnosticSink sink) { }

    private LowerResult lowerCollecting(String className, String source, io.titan.transpiler.jdbc.SqlSafetyMode buildDefault)
            throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer()
                .lower(parsed, entryPoints, sink, false, buildDefault, null);
        return new LowerResult(lowered, sink);
    }

    /**
     * Lowers with an explicit target-dialect list (so {@code mysqlTargeted} can be steered) and collects
     * diagnostics rather than throwing — used by the STRICT-cursor-splice diagnostic-precedence test, which
     * must assert the SAME E004 reject regardless of whether MySQL is among the targets.
     */
    private LowerResult lowerCollectingForTarget(
            String className, String source, io.titan.transpiler.jdbc.SqlSafetyMode buildDefault,
            io.titan.transpiler.tir.DialectId target) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer()
                .lower(parsed, entryPoints, sink, false, buildDefault, List.of(target), null, null);
        return new LowerResult(lowered, sink);
    }

    private RawSql onlyRawSql(LowerResult result) {
        assertTrue(result.sink().errors().isEmpty(),
                "expected a clean (emitting) lowering; errors=" + result.sink().errors());
        return result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .map(ExecuteSqlStatement::sqlNode)
                .filter(RawSql.class::isInstance).map(RawSql.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("expected an ExecuteSqlStatement(RawSql)"));
    }

    private String transpile(String className, String source, String dialect) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile), List.of(), List.of(dialect), List.of("billing"), true);
        return generated.stream()
                .filter(artifact -> dialect.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst().orElseThrow();
    }

    // ===== STRICT boundary: identifier AND raw-fragment still REJECT (E004) =========================

    @Test
    void strictRejectsIdentifierSpliceWithE004() throws Exception {
        // The SAME dynamic-ORDER-BY source, but with no @SqlSafety and a STRICT build default, must reject
        // with the identifier-variant §5.3 E004 — never emit. (The D2 boundary: identifiers are structural.)
        LowerResult result = lowerCollecting("StrictOrder", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class StrictOrder {
                    @StoredProcedure
                    public static void run(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004
                                && d.message().contains("SQL injection risk")
                                && d.message().contains("format('%I'")
                                && d.message().contains("(a)") && d.message().contains("(b)") && d.message().contains("(c)")),
                "strict identifier splice must REJECT with the three-part identifier-variant E004; errors="
                        + result.sink().errors());
    }

    @Test
    void strictRejectsRawFragmentSpliceWithE004() throws Exception {
        // A raw-fragment splice (the value concatenated where it is neither a clear value nor identifier
        // position — a whole predicate) must reject with E004 under STRICT, never emit.
        LowerResult result = lowerCollecting("StrictPredicate", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class StrictPredicate {
                    @StoredProcedure
                    public static void run(Connection c, String predicate) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE " + predicate);
                        ps.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004 && d.message().contains("SQL injection risk")),
                "strict raw-fragment splice must REJECT with E004; errors=" + result.sink().errors());
        // No RawSql with a splice was ever produced (the discarded TIR is never emitted).
        assertFalse(result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                        .map(ExecuteSqlStatement::sqlNode)
                        .filter(RawSql.class::isInstance).map(RawSql.class::cast)
                        .anyMatch(RawSql::hasSpliceBinds),
                "strict must never produce a splice RawSql");
    }

    // ===== No-escalation: a strict scope alongside a permissive sibling stays strict ===============

    @Test
    void strictMethodIsNotEscalatedByPermissiveSibling() throws Exception {
        // Two methods in one class: one @SqlSafety(PERMISSIVE) with an identifier splice (emits), one with
        // NO annotation under a STRICT build (must STILL reject E004). Proves narrowest-scope-wins — the
        // permissive sibling never relaxes the strict one.
        LowerResult result = lowerCollecting("MixedScopes", """
                import titan.dsl.StoredProcedure;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.*;

                class MixedScopes {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void permissive(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }

                    @StoredProcedure
                    public static void strict(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT);
        // The unannotated `strict` method still fails the build with E004 (the strict scope held).
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004 && d.message().contains("format('%I'")),
                "the unannotated method must STILL reject E004 (no escalation by the permissive sibling); errors="
                        + result.sink().errors());
    }

    @Test
    void classLevelStrictRetightensUnderPermissiveBuild() throws Exception {
        // Build is permissive, but a class-level @SqlSafety(STRICT) re-tightens every method in it: the
        // identifier splice must REJECT (E004), proving a strict scope is never escalated and that class
        // STRICT wins over the build flag (narrowest-scope-wins).
        LowerResult result = lowerCollecting("Retightened", """
                import titan.dsl.StoredProcedure;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.*;

                @SqlSafety(SqlSafetyMode.STRICT)
                class Retightened {
                    @StoredProcedure
                    public static void run(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts ORDER BY " + sortCol);
                        ps.executeQuery();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.PERMISSIVE);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004),
                "class-level STRICT must re-tighten under a permissive build (no escalation); errors="
                        + result.sink().errors());
    }

    // ===== PERMISSIVE emits: identifier hole — TIR shape + native per-dialect SQL ===================

    @Test
    void permissiveTableNameLowersToOneIdentifierSpliceBind() throws Exception {
        // A TABLE NAME splice ("UPDATE " + tbl + " SET …") is an IDENTIFIER hole (a relation name). The
        // bound value (id) still binds (one `?`, one USING) — only the identifier is structural.
        RawSql raw = onlyRawSql(lowerCollecting("DynTable", PERMISSIVE_TABLE_NAME, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT));
        assertEquals(1, raw.spliceBinds().size(), "exactly one identifier splice");
        SpliceBind splice = raw.spliceBinds().getFirst();
        assertEquals(SpliceBind.Kind.IDENTIFIER, splice.kind(), "a table name is an IDENTIFIER splice");
        assertEquals("tbl", splice.paramName(), "the spliced routine-local is the tbl parameter");
        assertTrue(raw.sql().contains(RawSql.spliceMarker(splice.markerId())),
                "the recovered text carries the splice marker at the table-name position");
        assertEquals(1, raw.sql().chars().filter(ch -> ch == '?').count(), "the value (id) keeps its bound `?`");
    }

    @Test
    void permissiveOrderByLowersToOneRawFragmentSpliceBind() throws Exception {
        // WS-C Phase 3 Rung 3 audit fix (Findings 1): an ORDER BY name is a sort EXPRESSION (it may carry a
        // direction "col DESC" / a comma list), NOT a single quotable identifier — so it is a RAW_FRAGMENT
        // (spliced verbatim under permissive, faithful to plain JDBC), never an IDENTIFIER that %I would
        // corrupt. STRICT still rejects it (covered by strictRejectsIdentifierSpliceWithE004).
        RawSql raw = onlyRawSql(lowerCollecting("DynOrder", PERMISSIVE_ORDER_BY, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT));
        assertEquals(1, raw.spliceBinds().size(), "exactly one splice");
        SpliceBind splice = raw.spliceBinds().getFirst();
        assertEquals(SpliceBind.Kind.RAW_FRAGMENT, splice.kind(),
                "an ORDER BY sort expression is a RAW_FRAGMENT splice (verbatim), not an IDENTIFIER");
        assertEquals("sortCol", splice.paramName(), "the spliced routine-local is the sortCol parameter");
        assertTrue(raw.sql().contains(RawSql.spliceMarker(splice.markerId())),
                "the recovered text carries the splice marker at the ORDER BY position");
        assertEquals(0, raw.sql().chars().filter(ch -> ch == '?').count(), "no `?` value bind in this statement");
        assertTrue(raw.parameters().isEmpty(), "no USING value binds for a pure ORDER BY splice");
    }

    @Test
    void permissiveTableNameEmitsQualifiedAwareQuoteOnPostgres() throws Exception {
        String sql = transpile("DynTable", PERMISSIVE_TABLE_NAME, "postgresql");
        // The table name is quoted at runtime qualified-name aware (Findings 2): split on '.', quote_ident
        // each segment, joined by '.' — so "billing.accounts" quotes per segment. The conversion is %s with
        // that runtime expression as the format() arg (a bare name quotes as a single segment = the old %I).
        assertTrue(sql.contains("EXECUTE format('UPDATE %s SET flagged = 1 WHERE id = $1', "
                        + "(SELECT string_agg(quote_ident(__titan_idpart), '.') "
                        + "FROM unnest(string_to_array(p_tbl, '.')) AS __titan_idpart)) USING p_id"),
                "PostgreSQL must quote the runtime table name qualified-name aware (quote_ident per segment) "
                        + "while binding the value via USING; was:\n" + sql);
        assertFalse(sql.contains(RawSql.spliceMarker(0)), "the splice marker must be fully expanded");
    }

    @Test
    void permissiveOrderByEmitsVerbatimOnPostgres() throws Exception {
        String sql = transpile("DynOrder", PERMISSIVE_ORDER_BY, "postgresql");
        // The ORDER BY sort expression is spliced VERBATIM via %s (the source's own exposure reproduced) —
        // so a runtime "bal DESC" reaches the text as `ORDER BY bal DESC`, not `ORDER BY "bal DESC"`.
        assertTrue(sql.contains("EXECUTE format('UPDATE accounts SET flagged = 1 WHERE id IN "
                        + "(SELECT id FROM accounts ORDER BY %s LIMIT 1)', p_sort_col)"),
                "PostgreSQL must splice the ORDER BY expression verbatim via format('%s', p_sort_col); was:\n" + sql);
        assertFalse(sql.contains(RawSql.spliceMarker(0)), "the splice marker must be fully expanded");
    }

    @Test
    void permissiveTableNameEmitsQualifiedAwareBacktickOnMysql() throws Exception {
        String sql = transpile("DynTable", PERMISSIVE_TABLE_NAME, "mysql");
        // MySQL backtick-quotes the runtime table name qualified-name aware (Findings 2): embedded backticks
        // doubled, then every '.' separator rewritten to `.` before wrapping — so "billing.accounts" becomes
        // `billing`.`accounts`. The whole assembled text is staged into @titan_dsql then PREPAREd.
        assertTrue(sql.contains("SET @titan_dsql_1 = CONCAT('UPDATE ', "
                        + "CONCAT('`', REPLACE(REPLACE(p_tbl, '`', '``'), '.', '`.`'), '`'), ' SET flagged = 1 WHERE id = ?')"),
                "MySQL must backtick-quote the runtime table name qualified-name aware; was:\n" + sql);
        assertTrue(sql.contains("PREPARE ") && sql.contains(" FROM @titan_dsql_1"),
                "MySQL must PREPARE from the staged @titan_dsql variable; was:\n" + sql);
        assertFalse(sql.contains(RawSql.spliceMarker(0)), "the splice marker must be fully expanded");
    }

    @Test
    void permissiveOrderByEmitsVerbatimConcatOnMysql() throws Exception {
        String sql = transpile("DynOrder", PERMISSIVE_ORDER_BY, "mysql");
        // The ORDER BY expression is the bare CONCAT operand (verbatim, no backtick quoting), staged into
        // @titan_dsql — so a runtime "bal DESC" reaches the text as `ORDER BY bal DESC`.
        assertTrue(sql.contains("SET @titan_dsql_1 = CONCAT('UPDATE accounts SET flagged = 1 WHERE id IN "
                        + "(SELECT id FROM accounts ORDER BY ', p_sort_col, ' LIMIT 1)')"),
                "MySQL must splice the ORDER BY expression verbatim as a bare CONCAT operand; was:\n" + sql);
        assertTrue(sql.contains("PREPARE ") && sql.contains(" FROM @titan_dsql_1"),
                "MySQL must PREPARE from the staged @titan_dsql variable; was:\n" + sql);
        assertFalse(sql.contains(RawSql.spliceMarker(0)), "the splice marker must be fully expanded");
    }

    // ===== PERMISSIVE emits: raw-fragment hole — TIR shape + native per-dialect SQL =================

    @Test
    void permissiveRawFragmentLowersToOneRawFragmentSpliceBind() throws Exception {
        RawSql raw = onlyRawSql(lowerCollecting("DynPredicate", PERMISSIVE_RAW_PREDICATE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT));
        assertEquals(1, raw.spliceBinds().size(), "exactly one raw-fragment splice");
        SpliceBind splice = raw.spliceBinds().getFirst();
        assertEquals(SpliceBind.Kind.RAW_FRAGMENT, splice.kind(), "a whole-predicate splice is RAW_FRAGMENT");
        assertEquals("predicate", splice.paramName(), "the spliced routine-local is the predicate parameter");
    }

    @Test
    void permissiveRawFragmentEmitsPercentSVerbatimOnPostgres() throws Exception {
        String sql = transpile("DynPredicate", PERMISSIVE_RAW_PREDICATE, "postgresql");
        // The fragment is spliced verbatim with %s (NO quoting — the source's own exposure reproduced).
        assertTrue(sql.contains("EXECUTE format('UPDATE accounts SET flagged = 1 WHERE %s', p_predicate)"),
                "PostgreSQL must splice the raw fragment verbatim via format('%s', p_predicate); was:\n" + sql);
    }

    @Test
    void permissiveRawFragmentEmitsVerbatimConcatOnMysql() throws Exception {
        String sql = transpile("DynPredicate", PERMISSIVE_RAW_PREDICATE, "mysql");
        // The fragment is the bare CONCAT operand (verbatim, no backtick quoting), staged into @titan_dsql.
        assertTrue(sql.contains("SET @titan_dsql_1 = CONCAT('UPDATE accounts SET flagged = 1 WHERE ', p_predicate)"),
                "MySQL must splice the raw fragment verbatim as a bare CONCAT operand; was:\n" + sql);
        assertTrue(sql.contains(" FROM @titan_dsql_1"),
                "MySQL must PREPARE from the staged @titan_dsql variable; was:\n" + sql);
    }

    // ===== Mixed: a bound VALUE still binds alongside an identifier splice ==========================

    @Test
    void permissiveMixedBindsValueAndSplicesIdentifier() throws Exception {
        RawSql raw = onlyRawSql(lowerCollecting("DynMixed", PERMISSIVE_IDENT_PLUS_VALUE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT));
        // The value (tier) BINDS — one `?` survives in the text and one USING param; the identifier
        // (tbl, a table name) SPLICES — one marker, one splice bind. Only the identifier reaches the text.
        assertEquals(1, raw.spliceBinds().size(), "exactly one identifier splice (tbl)");
        assertEquals(SpliceBind.Kind.IDENTIFIER, raw.spliceBinds().getFirst().kind(), "tbl is an identifier");
        assertEquals("tbl", raw.spliceBinds().getFirst().paramName(), "the splice is the tbl identifier");
        assertEquals(1, raw.sql().chars().filter(ch -> ch == '?').count(),
                "the value (tier) keeps its bound `?` (it is NOT spliced)");
        // A VALUE-position splice always synthesizes a __titan_pN bind local (design contract D3), so the
        // value is parameterized — never reaches the SQL text — exactly one USING bind for tier.
        assertEquals(1, raw.parameters().size(), "the value tier is bound via USING (one bind), never spliced");
        assertTrue(raw.parameters().getFirst().startsWith("__titan_p"),
                "the value bind is a synthesized __titan_pN local; was " + raw.parameters());
    }

    @Test
    void permissiveMixedEmitsBoundValuePlusFormatIdentifierOnPostgres() throws Exception {
        String sql = transpile("DynMixed", PERMISSIVE_IDENT_PLUS_VALUE, "postgresql");
        // tier binds via USING ($1, the synthesized __titan_p1 := p_tier); tbl is quoted qualified-name
        // aware (quote_ident per segment); only the identifier reaches the assembled text.
        assertTrue(sql.contains("EXECUTE format('UPDATE %s SET flagged = 1 WHERE tier = $1', "
                        + "(SELECT string_agg(quote_ident(__titan_idpart), '.') "
                        + "FROM unnest(string_to_array(p_tbl, '.')) AS __titan_idpart)) USING __titan_p1"),
                "PostgreSQL must bind the value ($1 USING the synthesized local) AND quote the identifier "
                        + "(qualified-name aware); was:\n" + sql);
        assertTrue(sql.contains("__titan_p1 := p_tier"), "the value must be staged into the bind local; was:\n" + sql);
    }

    // ===== Adversarial: a literal '%' in the SQL must not confuse PostgreSQL format() ==============

    /**
     * A permissive identifier splice whose surrounding SQL contains a literal {@code %} (a {@code LIKE
     * '%a%'} pattern) must double the {@code %} to {@code %%} in the {@code format()} template — otherwise
     * {@code format} would read {@code %a} as a (bad) conversion and corrupt the SQL. The splice's own
     * {@code %I} is inserted AFTER the doubling, so it stays a single conversion. (PostgreSQL only.)
     */
    @Test
    void permissiveIdentifierWithLiteralPercentDoublesItForFormatOnPostgres() throws Exception {
        String source = """
                import titan.dsl.StoredProcedure;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.*;

                class DynLike {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, String sortCol) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "UPDATE accounts SET flagged = 1 WHERE tier LIKE 'G%' ORDER BY " + sortCol);
                        ps.executeUpdate();
                    }
                }
                """;
        String sql = transpile("DynLike", source, "postgresql");
        // The literal % in 'G%' is doubled to 'G%%' inside the format template; the ORDER BY sort
        // expression is the verbatim %s conversion (inserted AFTER the doubling, so it stays a single %s).
        assertTrue(sql.contains("format('UPDATE accounts SET flagged = 1 WHERE tier LIKE ''G%%'' ORDER BY %s', p_sort_col)"),
                "the literal %% must be doubled for format() while the splice stays a single %%s; was:\n" + sql);
    }

    @Test
    void permissiveMixedEmitsBoundValuePlusBacktickIdentifierOnMysql() throws Exception {
        String sql = transpile("DynMixed", PERMISSIVE_IDENT_PLUS_VALUE, "mysql");
        // tier binds via USING @p (the `?` stays in the CONCAT literal segment); tbl is backtick-quoted
        // qualified-name aware; the whole assembled text is staged into @titan_dsql then PREPAREd from it.
        assertTrue(sql.contains("SET @titan_dsql_1 = CONCAT('UPDATE ', "
                        + "CONCAT('`', REPLACE(REPLACE(p_tbl, '`', '``'), '.', '`.`'), '`'), ' SET flagged = 1 WHERE tier = ?')"),
                "MySQL must keep the value `?` bound in the CONCAT literal AND backtick-quote the identifier; was:\n" + sql);
        assertTrue(sql.contains(" FROM @titan_dsql_1"), "MySQL must PREPARE from the staged variable; was:\n" + sql);
        assertTrue(sql.contains("USING @titan_p1") || sql.contains("USING @titan_p"),
                "MySQL must bind the value via USING @p; was:\n" + sql);
    }

    // ===== WS-C Phase 3 Rung 3 audit fix (Findings 2): qualified schema.table runtime quoting ========

    /** A permissive qualified table-name splice — used to lock the per-segment runtime quoting. */
    private static final String PERMISSIVE_QUALIFIED_TABLE = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class DynQual {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, String tbl) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE " + tbl + " SET flagged = 1");
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void qualifiedTableNameSplitsOnDotPerSegmentOnPostgres() throws Exception {
        // The emitted runtime quote splits on '.' and quote_ident()s each segment — so a runtime
        // "billing.accounts" becomes "billing"."accounts", NOT the broken single-identifier
        // "billing.accounts". (Unit-level lock for Findings 2; the deploy IT proves it live.)
        String sql = transpile("DynQual", PERMISSIVE_QUALIFIED_TABLE, "postgresql");
        assertTrue(sql.contains("(SELECT string_agg(quote_ident(__titan_idpart), '.') "
                        + "FROM unnest(string_to_array(p_tbl, '.')) AS __titan_idpart)"),
                "PostgreSQL must split the runtime identifier on '.' and quote_ident each segment; was:\n" + sql);
    }

    @Test
    void qualifiedTableNameSplitsOnDotPerSegmentOnMysql() throws Exception {
        // Embedded backticks doubled FIRST, then '.' separators rewritten to `.` before wrapping — so a
        // runtime "billing.accounts" becomes `billing`.`accounts`, NOT the broken `billing.accounts`.
        String sql = transpile("DynQual", PERMISSIVE_QUALIFIED_TABLE, "mysql");
        assertTrue(sql.contains("CONCAT('`', REPLACE(REPLACE(p_tbl, '`', '``'), '.', '`.`'), '`')"),
                "MySQL must backtick-quote each '.'-separated segment of the runtime identifier; was:\n" + sql);
    }

    // ===== Boundary: a STRICT cursor-read splice rejects with E004 regardless of target dialect ======

    /** A STRICT {@code while(rs.next())} cursor over an ORDER BY splice — the classic injection shape. */
    private static final String STRICT_CURSOR_ORDER_BY = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class StrictCursor {
                @StoredProcedure
                public static void run(Connection c, String sortCol) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("SELECT id, name FROM accounts ORDER BY " + sortCol);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        long id = rs.getLong(1);
                    }
                }
            }
            """;

    @Test
    void strictCursorSpliceRejectsWithE004OnMysqlTarget() throws Exception {
        // WS-C Phase 3 Rung 3 audit fix (boundary): a STRICT cursor splice targeting MySQL must reject with
        // the INJECTION E004 (the 3-part diagnostic), NOT the dialect-capability E001 ("use a typed DSL") —
        // the strict-splice gate now runs BEFORE the MySQL non-constant cursor reject. No splice is emitted.
        LowerResult result = lowerCollectingForTarget(
                "StrictCursor", STRICT_CURSOR_ORDER_BY, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT,
                io.titan.transpiler.tir.DialectId.MYSQL);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004
                                && d.message().contains("SQL injection risk")
                                && d.message().contains("format('%I'")),
                "the STRICT MySQL cursor splice must reject with the injection E004 (not E001); errors="
                        + result.sink().errors());
        assertFalse(result.sink().errors().stream()
                        .anyMatch(d -> d.message().contains("use a typed DSL")
                                && !d.message().contains("SQL injection risk")),
                "the dialect-capability E001 ('use a typed DSL') must NOT be the strict-splice diagnostic; errors="
                        + result.sink().errors());
        assertNoSpliceEmitted(result);
    }

    @Test
    void strictCursorSpliceRejectsWithE004OnPostgresTarget() throws Exception {
        // The SAME source on a PostgreSQL-only target also rejects with E004 — proving the diagnostic is
        // now consistent across targets (it was already E004 here; this locks that MySQL matches it).
        LowerResult result = lowerCollectingForTarget(
                "StrictCursor", STRICT_CURSOR_ORDER_BY, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT,
                io.titan.transpiler.tir.DialectId.POSTGRESQL);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004
                                && d.message().contains("SQL injection risk")
                                && d.message().contains("format('%I'")),
                "the STRICT PostgreSQL cursor splice must reject with the injection E004; errors="
                        + result.sink().errors());
        assertNoSpliceEmitted(result);
    }

    private void assertNoSpliceEmitted(LowerResult result) {
        assertFalse(result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .anyMatch(JdbcSpliceLoweringTest::statementHoldsSplice),
                "STRICT must never emit a splice RawSql (any statement)");
    }

    private static boolean statementHoldsSplice(StatementNode statement) {
        if (statement instanceof ExecuteSqlStatement exec && exec.sqlNode() instanceof RawSql raw) {
            return raw.hasSpliceBinds();
        }
        if (statement instanceof RawCursorStatement cursor && cursor.query() != null) {
            return cursor.query().hasSpliceBinds();
        }
        return false;
    }

    // ===== Permissive fidelity (Findings 3): a harden()-demoted value splices VERBATIM, strict rejects ==

    /**
     * A value concatenated with a trailing literal that would FUSE its placeholder ({@code "id = " + n +
     * "00"} → {@code $100}). The skeleton recognizer's {@code harden()} demotes the fused VALUE hole to a
     * RAW_FRAGMENT. Locked here: under PERMISSIVE {@code n} splices VERBATIM (a value-position operand
     * reaches the text unquoted, params empty) — the source's own exposure faithfully reproduced — and
     * under STRICT the same shape rejects with E004. A future change to {@code harden()} that re-bound
     * {@code n} (or changed whether it spliced) would trip this test.
     */
    private static final String PERMISSIVE_FUSED_VALUE = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class FusedValue {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void run(Connection c, int n) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE t SET x = 1 WHERE id = " + n + "00");
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void permissiveHardenDemotedValueSplicesVerbatim() throws Exception {
        RawSql raw = onlyRawSql(lowerCollecting("FusedValue", PERMISSIVE_FUSED_VALUE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT));
        assertEquals(1, raw.spliceBinds().size(), "exactly one splice (the harden()-demoted value)");
        assertEquals(SpliceBind.Kind.RAW_FRAGMENT, raw.spliceBinds().getFirst().kind(),
                "the fused value is demoted to a RAW_FRAGMENT (spliced verbatim, NOT bound)");
        assertEquals("n", raw.spliceBinds().getFirst().paramName(), "the spliced operand is n");
        assertTrue(raw.parameters().isEmpty(), "n is NOT bound (params empty) — it reaches the text verbatim");
        assertEquals(0, raw.sql().chars().filter(ch -> ch == '?').count(), "no surviving `?` value bind");
    }

    @Test
    void permissiveHardenDemotedValueEmitsVerbatimOnPostgres() throws Exception {
        String sql = transpile("FusedValue", PERMISSIVE_FUSED_VALUE, "postgresql");
        assertTrue(sql.contains("EXECUTE format('UPDATE t SET x = 1 WHERE id = %s00', p_n)"),
                "PostgreSQL must splice the fused value verbatim (id = %s00); was:\n" + sql);
    }

    @Test
    void strictHardenDemotedValueRejectsWithE004() throws Exception {
        LowerResult result = lowerCollecting("FusedValueStrict", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class FusedValueStrict {
                    @StoredProcedure
                    public static void run(Connection c, int n) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE t SET x = 1 WHERE id = " + n + "00");
                        ps.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004),
                "the STRICT harden()-demoted value splice must reject with E004; errors=" + result.sink().errors());
        assertNoSpliceEmitted(result);
    }
}
