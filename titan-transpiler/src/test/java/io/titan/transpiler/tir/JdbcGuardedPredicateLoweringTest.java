package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Design contract D4 — optional-filter guarded predicates. Source → parse → lower → assert the TIR shape
 * (the consuming statement's {@link RawSql} is ONE static query: the constant base AND one {@code (? IS
 * NULL OR col OP ?)} per optional clause, each param bound twice, the {@code StringBuilder}/append/bind
 * scaffolding elided), then → emit and assert the native per-dialect SQL is the same STATIC guarded
 * predicate (no dynamic-SQL assembly beyond the {@code EXECUTE … USING} substrate, identical WHERE on
 * PostgreSQL and MySQL). The invariant: the lowered query is static, every value is bound, and it is
 * semantically equivalent to the conditional builder for ALL param combinations.
 */
class JdbcGuardedPredicateLoweringTest {

    @TempDir
    Path tempDir;

    // The canonical D4 shape: a StringBuilder base + 2 optional clauses (= and >=), each gated on a
    // `param != null` and bound by a correlated gated `setXxx`. As an UPDATE so it is a @StoredProcedure
    // (the SELECT-into-list variant is the Rung-5 carrier; the WHERE transform is what D4 owns).
    private static final String SEARCH_BUILDER = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.math.BigDecimal;

            class FlagAccounts {
                @StoredProcedure
                public static void flagAccounts(Connection c, String status, BigDecimal minBalance)
                        throws SQLException {
                    StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                    if (status != null) { sql.append(" AND status = ?"); }
                    if (minBalance != null) { sql.append(" AND balance >= ?"); }
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (status != null) ps.setString(i++, status);
                    if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                    ps.executeUpdate();
                }
            }
            """;

    private ParsedSources parse(String className, String source) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        return new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
    }

    private Block lowerOneBlock(String className, String source) throws Exception {
        ParsedSources parsed = parse(className, source);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, entryPoints, sink);
        assertTrue(sink.errors().isEmpty(), "expected a clean lowering; errors=" + sink.errors());
        assertEquals(1, lowered.size(), "expected exactly one lowered entry point");
        return lowered.values().iterator().next();
    }

    private String transpile(String className, String source, String dialect) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile), List.of(), List.of(dialect), List.of("billing"), true);
        return generated.stream()
                .filter(artifact -> dialect.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
    }

    // ===== TIR shape =============================================================================

    @Test
    void lowersToOneStaticGuardedPredicateRawSql() throws Exception {
        Block block = lowerOneBlock("FlagAccounts", SEARCH_BUILDER);
        // The StringBuilder decl, append `if`s, bind counter, and bind `if`s are all elided — the only
        // statement is the single ExecuteSqlStatement over the static guarded-predicate RawSql.
        assertFalse(block.statements().stream().anyMatch(s -> s instanceof IfStatement),
                "the optional-clause / bind `if`s must be elided (subsumed by the guarded predicate)");
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR status = ?) AND (? IS NULL OR balance >= ?)",
                raw.sql(), "the lowered SQL must be the single static guarded-predicate query");
        // Each param bound TWICE (the IS NULL guard + the comparison), in clause order: status,status,
        // minBalance,minBalance. The recognizer proved both are bare param refs, so they bind by name.
        assertEquals(List.of("status", "status", "minBalance", "minBalance"), raw.parameters(),
                "each optional param must be bound twice (guard + comparison), in clause order");
        assertTrue(raw.arrayBinds().isEmpty() && raw.spliceBinds().isEmpty(),
                "the guarded predicate is a plain value-bound RawSql — no array/splice binds");
    }

    // ===== emitted SQL — PostgreSQL ==============================================================

    @Test
    void emitsStaticGuardedPredicateOnPostgres() throws Exception {
        String sql = transpile("FlagAccounts", SEARCH_BUILDER, "postgresql");
        // The guarded predicate is emitted as a static EXECUTE … USING (the `?` rewritten to $n, each param
        // bound twice). No format()/CONCAT runtime assembly (no dynamic SQL beyond the EXECUTE substrate).
        assertTrue(sql.contains("($1 IS NULL OR status = $2)"),
                "PG must emit the first clause as ($1 IS NULL OR status = $2); was:\n" + sql);
        assertTrue(sql.contains("($3 IS NULL OR balance >= $4)"),
                "PG must emit the second clause as ($3 IS NULL OR balance >= $4); was:\n" + sql);
        // The same param drives both `?` of a clause: $1 and $2 are USING-bound to p_status, p_status.
        assertTrue(sql.contains("USING p_status, p_status, p_min_balance, p_min_balance"),
                "PG must bind each param twice in USING (guard + comparison); was:\n" + sql);
        assertFalse(sql.contains("format("),
                "the guarded predicate must NOT use runtime format() assembly (it is static); was:\n" + sql);
    }

    // ===== emitted SQL — MySQL ===================================================================

    @Test
    void emitsStaticGuardedPredicateOnMysql() throws Exception {
        String sql = transpile("FlagAccounts", SEARCH_BUILDER, "mysql");
        // On MySQL the `?` placeholders stay `?` (positional), bound by the PREPARE/EXECUTE USING @p. The
        // guarded predicate WHERE is identical SQL — `(? IS NULL OR col OP ?)`.
        assertTrue(sql.contains("(? IS NULL OR status = ?)"),
                "MySQL must emit the first clause as (? IS NULL OR status = ?); was:\n" + sql);
        assertTrue(sql.contains("(? IS NULL OR balance >= ?)"),
                "MySQL must emit the second clause as (? IS NULL OR balance >= ?); was:\n" + sql);
        // Each param set into a session var twice (guard + comparison) — the USING binds p_status twice.
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("JSON_TABLE") == false,
                "the guarded predicate must NOT use JSON_TABLE (it is not a collection bind); was:\n" + sql);
    }

    // ===== the += String-concat form lowers identically ==========================================

    // ===== fail-safe: a non-guard-attachable WHERE must NOT lower to a guarded predicate ==============
    // The guard ` AND (? IS NULL OR col OP ?)` is appended at the END of the base. When the base WHERE is
    // not the terminal top-level predicate (a trailing ORDER BY/GROUP BY/HAVING/LIMIT/…/RETURNING, or a
    // WHERE only inside a subquery), the recognizer FAILS SAFE — and the unrecovered `new StringBuilder`
    // is then an unsupported object construction under STRICT, so the whole transpile is REJECTED with
    // TITAN-E001 rather than emitting a malformed `… LIMIT 2 AND (…)` / `… sub AND (…)` query whose
    // all-null CALL would be a hard SQL error. (The semantic-equivalence invariant: never mis-lower.)

    /**
     * Asserts that transpiling {@code source} under STRICT is REJECTED with TITAN-E001 — used to lock that a
     * non-guard-attachable base does NOT collapse into a guarded predicate (the StringBuilder is left as an
     * unsupported construct, the conservative fail-safe). Proves no malformed guarded-predicate artifact is
     * ever emitted for such a base, so the all-null runtime error the auditors found is unreachable.
     */
    private void assertTranspileRejected(String className, String source, String dialect) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(sourceFile), List.of(), List.of(dialect), List.of("billing"), true),
                "a non-guard-attachable base must be rejected (not lowered to a guarded predicate)");
        assertTrue(thrown.getMessage().contains("TITAN-E001"),
                "the rejection must be a TITAN-E001 unsupported-feature error; was: " + thrown.getMessage());
    }

    private static String searchBuilderWithBase(String base) {
        return """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class TrailSearch {
                    @StoredProcedure
                    public static void trailSearch(Connection c, String tier) throws SQLException {
                        StringBuilder sql = new StringBuilder("%s");
                        if (tier != null) { sql.append(" AND tier = ?"); }
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        if (tier != null) ps.setString(1, tier);
                        ps.executeUpdate();
                    }
                }
                """.formatted(base);
    }

    @Test
    void trailingOrderByLimitBaseIsRejectedNotLoweredOnPostgres() throws Exception {
        // The auditor's PG repro: `… WHERE active = true ORDER BY id LIMIT 2`. The guard after LIMIT would
        // parse as `LIMIT (2 AND …)` at the all-null CALL. Must be rejected at transpile, not lowered.
        assertTranspileRejected("TrailSearch", searchBuilderWithBase(
                "UPDATE accounts SET flagged = 1 WHERE active = true ORDER BY id LIMIT 2"), "postgresql");
    }

    @Test
    void trailingLimitBaseIsRejectedNotLoweredOnMysql() throws Exception {
        // The auditor's MySQL repro: `UPDATE accounts SET flagged = 1 WHERE active = true LIMIT 2`.
        assertTranspileRejected("TrailSearch", searchBuilderWithBase(
                "UPDATE accounts SET flagged = 1 WHERE active = true LIMIT 2"), "mysql");
    }

    @Test
    void trailingGroupByBaseIsRejectedNotLowered() throws Exception {
        assertTranspileRejected("TrailSearch", searchBuilderWithBase(
                "UPDATE accounts SET flagged = 1 WHERE active = true GROUP BY tier"), "postgresql");
    }

    @Test
    void trailingReturningBaseIsRejectedNotLowered() throws Exception {
        assertTranspileRejected("TrailSearch", searchBuilderWithBase(
                "UPDATE accounts SET flagged = 1 WHERE active = true RETURNING id"), "postgresql");
    }

    @Test
    void subqueryOnlyWhereBaseIsRejectedNotLowered() throws Exception {
        // The outer statement has NO top-level WHERE — the only WHERE is inside the scalar subquery. The
        // guard would append after the tail (`… ) AND (…)`), diverging on the all-null call.
        assertTranspileRejected("TrailSearch", searchBuilderWithBase(
                "UPDATE accounts SET flagged = (SELECT 1 FROM marker WHERE m = 1)"), "postgresql");
    }

    @Test
    void terminalWhereWithSubqueryPredicateStillLowersToGuardedPredicate() throws Exception {
        // CONTROL: a genuine top-level TERMINAL WHERE whose predicate merely CONTAINS a subquery still
        // lowers to the static guarded predicate (the trailing AND attaches to the top-level WHERE).
        String source = searchBuilderWithBase(
                "UPDATE accounts SET flagged = 1 WHERE id IN (SELECT account_id FROM o WHERE paid = true)");
        Block block = lowerOneBlock("TrailSearch", source);
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals("UPDATE accounts SET flagged = 1 WHERE id IN (SELECT account_id FROM o WHERE paid = true)"
                        + " AND (? IS NULL OR tier = ?)",
                raw.sql(), "a terminal top-level WHERE with a subquery predicate must still lower to the "
                        + "static guarded predicate (the guard attaches to the top-level WHERE)");
    }

    // ===== the ORDERED / PAGINATED builder: guards BEFORE the trailing constant tail ==================
    // An ordered builder appends a trailing UNCONDITIONAL constant clause (` ORDER BY id DESC`) AFTER the
    // optional filters. It lowers to ONE static query `<base> <guards> <tail>` — the guards at the WHERE
    // boundary, the constant ORDER BY after them. The tail carries no bind (the param list is unchanged);
    // it is the same text on PG and MySQL (an ORDER BY on a NON-NULL column orders identically).

    private static final String ORDERED_SEARCH_BUILDER = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.math.BigDecimal;

            class OrderedSearch {
                @StoredProcedure
                public static void orderedSearch(Connection c, String status, BigDecimal minBalance)
                        throws SQLException {
                    StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                    if (status != null) { sql.append(" AND status = ?"); }
                    if (minBalance != null) { sql.append(" AND balance >= ?"); }
                    sql.append(" ORDER BY id DESC");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (status != null) ps.setString(i++, status);
                    if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void orderedBuilderLowersToOneStaticQueryWithGuardsBeforeTail() throws Exception {
        Block block = lowerOneBlock("OrderedSearch", ORDERED_SEARCH_BUILDER);
        assertFalse(block.statements().stream().anyMatch(s -> s instanceof IfStatement),
                "the optional-clause / bind `if`s AND the trailing ORDER BY append must all be elided");
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        RawSql raw = (RawSql) execute.sqlNode();
        // The guards sit at the WHERE boundary; the constant ORDER BY follows them — ONE static query.
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR status = ?) AND (? IS NULL OR balance >= ?) ORDER BY id DESC",
                raw.sql(), "the ordered builder must lower to base + guards + the constant ORDER BY tail");
        // The tail carries NO bind — the param list is exactly the two filters bound twice each.
        assertEquals(List.of("status", "status", "minBalance", "minBalance"), raw.parameters(),
                "the ORDER BY tail must add no bind (each filter param bound twice, in clause order)");
    }

    @Test
    void orderedBuilderEmitsGuardsBeforeTailOnPostgres() throws Exception {
        String sql = transpile("OrderedSearch", ORDERED_SEARCH_BUILDER, "postgresql");
        // Byte-golden: the guarded WHERE (the `?` rewritten to $n) immediately precedes the constant tail.
        assertTrue(sql.contains("WHERE active = true AND ($1 IS NULL OR status = $2)"
                        + " AND ($3 IS NULL OR balance >= $4) ORDER BY id DESC"),
                "PG must emit the guards at the WHERE boundary, the ORDER BY tail after them; was:\n" + sql);
        assertTrue(sql.contains("USING p_status, p_status, p_min_balance, p_min_balance"),
                "PG must bind each filter param twice; the ORDER BY adds no USING bind; was:\n" + sql);
        assertFalse(sql.contains("format("),
                "the ordered guarded predicate must be static — no runtime format() assembly; was:\n" + sql);
    }

    @Test
    void orderedBuilderEmitsGuardsBeforeTailOnMysql() throws Exception {
        String sql = transpile("OrderedSearch", ORDERED_SEARCH_BUILDER, "mysql");
        // Byte-golden: on MySQL the `?` stay positional; the guarded WHERE precedes the constant tail.
        assertTrue(sql.contains("WHERE active = true AND (? IS NULL OR status = ?)"
                        + " AND (? IS NULL OR balance >= ?) ORDER BY id DESC"),
                "MySQL must emit the guards at the WHERE boundary, the ORDER BY tail after them; was:\n" + sql);
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("JSON_TABLE"),
                "the ordered guarded predicate must NOT use JSON_TABLE; was:\n" + sql);
    }

    @Test
    void parameterizedLimitTailLowersWithPaginationBoundLast() throws Exception {
        // A trailing ` LIMIT ?` whose ? is a LIMIT count operand, correlated to a trailing UNCONDITIONAL
        // `ps.setInt(i++, 5)`. NOW lowered (this increment): ONE static query with the clause guard first
        // (bound twice) and the pagination ? bound LAST (the clause is textually first, the LIMIT ? last).
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class LimitSearch {
                    @StoredProcedure
                    public static void limitSearch(Connection c, String status) throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (status != null) { sql.append(" AND status = ?"); }
                        sql.append(" LIMIT ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setInt(i++, 5);
                        ps.executeUpdate();
                    }
                }
                """;
        Block block = lowerOneBlock("LimitSearch", source);
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR status = ?) LIMIT ?",
                raw.sql(), "the parameterized LIMIT tail must splice verbatim after the guard");
        // The clause param bound TWICE (guard + comparison), THEN the pagination operand once, LAST. The 5 is
        // a literal expression operand → a synthesized __titan_pN bind local (not a bare name).
        assertEquals(3, raw.parameters().size(), "two clause binds + one pagination bind");
        assertEquals("status", raw.parameters().get(0));
        assertEquals("status", raw.parameters().get(1));
        assertTrue(raw.parameters().get(2).startsWith("__titan_p"),
                "the literal LIMIT operand binds via a synthesized local; was: " + raw.parameters().get(2));
    }

    @Test
    void trailingAndClauseTailIsRejectedNotLowered() throws Exception {
        // A trailing unconditional ` AND tier = ?` is a real predicate (leads with AND, carries a `?`), not
        // an ordering tail — folding it into the static text would change the row set. Fail-safe reject.
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class AndTailSearch {
                    @StoredProcedure
                    public static void andTailSearch(Connection c, String status) throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (status != null) { sql.append(" AND status = ?"); }
                        sql.append(" AND tier = ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        ps.setString(i++, "GOLD");
                        ps.executeUpdate();
                    }
                }
                """;
        assertTranspileRejected("AndTailSearch", source, "postgresql");
    }

    @Test
    void stringConcatFormLowersToSameStaticQuery() throws Exception {
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;
                import java.math.BigDecimal;

                class FlagAccountsConcat {
                    @StoredProcedure
                    public static void flagAccountsConcat(Connection c, String status, BigDecimal minBalance)
                            throws SQLException {
                        String sql = "UPDATE accounts SET flagged = 1 WHERE active = true";
                        if (status != null) sql += " AND status = ?";
                        if (minBalance != null) sql += " AND balance >= ?";
                        PreparedStatement ps = c.prepareStatement(sql);
                        int i = 1;
                        if (status != null) ps.setString(i++, status);
                        if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                        ps.executeUpdate();
                    }
                }
                """;
        Block block = lowerOneBlock("FlagAccountsConcat", source);
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR status = ?) AND (? IS NULL OR balance >= ?)",
                raw.sql(), "the String += form must lower to the same static guarded-predicate query");
    }

    // ===== the PARAMETERIZED PAGINATION tail: clause binds FIRST, pagination binds in tail-? text order ===
    // The exact RawSql.parameters() order is the contract: each clause param bound TWICE (guard + comparison,
    // clause order) THEN each pagination operand once, in tail-? TEXT order. The clauses are always present so
    // their ?s are textually first; the pagination ?s (count/offset) are textually last, so they bind last.
    // (Lowering is dialect-agnostic here — it asserts the TIR bind order, not deployed SQL; each native
    // pagination form is deployed on its own engine in the IT.)

    /** Lowers {@code source}, returns the consuming statement's {@link RawSql} (the single guarded predicate). */
    private RawSql loweredRawSql(String className, String source) throws Exception {
        Block block = lowerOneBlock(className, source);
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        return (RawSql) execute.sqlNode();
    }

    @Test
    void mysqlLimitCommaTailBindsOffsetThenCountAfterTheClause() throws Exception {
        // MySQL ` LIMIT ?, ?` (offset, count): the source binds offset then pageSize (tail-? text order), so
        // the pagination binds are [offset, pageSize] AFTER the clause's [tier, tier].
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class PagedFlag {
                    @StoredProcedure
                    public static void pagedFlag(Connection c, String tier, int offset, int pageSize)
                            throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (tier != null) { sql.append(" AND tier = ?"); }
                        sql.append(" ORDER BY id DESC LIMIT ?, ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, offset);
                        ps.setInt(i++, pageSize);
                        ps.executeUpdate();
                    }
                }
                """;
        RawSql raw = loweredRawSql("PagedFlag", source);
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR tier = ?) ORDER BY id DESC LIMIT ?, ?",
                raw.sql(), "the MySQL LIMIT ?, ? comma tail must splice verbatim after the guard");
        // clause binds (tier twice) THEN pagination binds in tail-? text order (offset first, count second).
        assertEquals(List.of("tier", "tier", "offset", "pageSize"), raw.parameters(),
                "clause binds first, then offset then count (the MySQL LIMIT ?, ? offset/count text order)");
        assertTrue(raw.arrayBinds().isEmpty() && raw.spliceBinds().isEmpty(),
                "the paginated guarded predicate is a plain value-bound RawSql — no array/splice binds");
    }

    @Test
    void limitOffsetTailBindsCountThenOffsetAfterTheClause() throws Exception {
        // PG ` LIMIT ? OFFSET ?` (count, offset): tail-? text order is pageSize then offset.
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class PagedFlag2 {
                    @StoredProcedure
                    public static void pagedFlag2(Connection c, String tier, int pageSize, int offset)
                            throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (tier != null) { sql.append(" AND tier = ?"); }
                        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, pageSize);
                        ps.setInt(i++, offset);
                        ps.executeUpdate();
                    }
                }
                """;
        RawSql raw = loweredRawSql("PagedFlag2", source);
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR tier = ?) ORDER BY id LIMIT ? OFFSET ?",
                raw.sql());
        assertEquals(List.of("tier", "tier", "pageSize", "offset"), raw.parameters(),
                "clause binds first, then count (LIMIT ?) then offset (OFFSET ?) in text order");
    }

    @Test
    void filterPlusPaginationComboBindsAllClausesThenPagination() throws Exception {
        // Two optional filters + a two-? pagination tail: the bind order is the four clause binds (two per
        // clause, clause order) THEN the two pagination binds (tail-? text order). The arity invariant holds
        // (2 per clause + 1 per pagination ? = 6 ?, 6 binds).
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;
                import java.math.BigDecimal;

                class PagedSearch {
                    @StoredProcedure
                    public static void pagedSearch(Connection c, String tier, BigDecimal minBalance,
                            int pageSize, int offset) throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (tier != null) { sql.append(" AND tier = ?"); }
                        if (minBalance != null) { sql.append(" AND balance >= ?"); }
                        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                        ps.setInt(i++, pageSize);
                        ps.setInt(i++, offset);
                        ps.executeUpdate();
                    }
                }
                """;
        RawSql raw = loweredRawSql("PagedSearch", source);
        assertEquals("UPDATE accounts SET flagged = 1 WHERE active = true"
                        + " AND (? IS NULL OR tier = ?) AND (? IS NULL OR balance >= ?) ORDER BY id LIMIT ? OFFSET ?",
                raw.sql());
        assertEquals(List.of("tier", "tier", "minBalance", "minBalance", "pageSize", "offset"),
                raw.parameters(),
                "all clause binds (2 per clause, clause order) THEN the pagination binds (tail-? text order)");
    }

    @Test
    void emitsParameterizedPaginationStaticallyOnMysql() throws Exception {
        // End-to-end emit (MySQL) of the LIMIT ?, ? comma form: positional ?s, no JSON_TABLE / format() — the
        // pagination ?s are part of the same static PREPARE/EXECUTE substrate as the guards.
        String source = """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class PagedFlagMy {
                    @StoredProcedure
                    public static void pagedFlagMy(Connection c, String tier, int offset, int pageSize)
                            throws SQLException {
                        StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                        if (tier != null) { sql.append(" AND tier = ?"); }
                        sql.append(" ORDER BY id DESC LIMIT ?, ?");
                        PreparedStatement ps = c.prepareStatement(sql.toString());
                        int i = 1;
                        if (tier != null) ps.setString(i++, tier);
                        ps.setInt(i++, offset);
                        ps.setInt(i++, pageSize);
                        ps.executeUpdate();
                    }
                }
                """;
        String sql = transpile("PagedFlagMy", source, "mysql");
        assertTrue(sql.contains("(? IS NULL OR tier = ?) ORDER BY id DESC LIMIT ?, ?"),
                "MySQL must emit the guard then the verbatim LIMIT ?, ? comma tail; was:\n" + sql);
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("JSON_TABLE"),
                "the paginated guarded predicate must NOT use JSON_TABLE; was:\n" + sql);
        assertFalse(sql.contains("format("),
                "the paginated guarded predicate must be static — no runtime format() assembly; was:\n" + sql);
    }
}
