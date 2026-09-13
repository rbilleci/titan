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
 * WS-C Phase 3 Rung 4 — §3.6 form (2) collection binding. Source → parse → lower → assert the TIR shape
 * (the consuming statement's {@link RawSql} carries one {@link ArrayBind} membership marker and binds the
 * whole collection as ONE parameter, no per-element {@code ?} run), then → emit and assert the native
 * per-dialect SQL (PostgreSQL {@code = ANY($1)}, MySQL {@code JSON_TABLE(?, …)}). The invariant: no list
 * element ever reaches the SQL text — the whole list is one bound array/JSON parameter.
 */
class JdbcCollectionInLoweringTest {

    @TempDir
    Path tempDir;

    private static final String FLAG_BY_IDS = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIds {
                @StoredProcedure
                public static void flagByIds(Connection c, List<Long> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setLong(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    // The headline idiom the feature advertises: the placeholder run built by a local `placeholders(n)`
    // helper (= String.join(",", Collections.nCopies(n, "?"))) — a RECOGNIZED run helper. The array-bind
    // elides the helper CALL, so the helper itself must not be discovered/transpiled (it would otherwise
    // reject with E001 on its Collections.nCopies). Locks recognizer ⇄ validator ⇄ helper-discovery ⇄
    // lowerer agreement on FORM A through the full pipeline.
    private static final String FLAG_BY_IDS_HELPER = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIdsHelper {
                @StoredProcedure
                public static void flagByIds(Connection c, List<Long> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (" + placeholders(ids.size()) + ")");
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setLong(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
                static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }
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
    void lowersToOneArrayBindRawSql() throws Exception {
        Block block = lowerOneBlock("FlagByIds", FLAG_BY_IDS);
        // The per-element bind loop is elided; the consuming statement is a single ExecuteSqlStatement.
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        assertFalse(block.statements().stream().anyMatch(s -> s instanceof ForRangeStatement),
                "the per-element bind loop must be elided (no ForRangeStatement)");

        assertTrue(execute.sqlNode() instanceof RawSql, "the consuming statement must carry a RawSql");
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals(1, raw.arrayBinds().size(), "exactly one collection array-bind");
        ArrayBind bind = raw.arrayBinds().getFirst();
        assertEquals("id", bind.lhs(), "the membership LHS is the matched column");
        assertTrue(bind.elementType() instanceof TBigintType, "List<Long> binds a bigint element");
        // The whole collection is bound as ONE parameter — `ids` (resolved to p_ids by the emitter).
        assertEquals(List.of("ids"), raw.parameters(), "the whole list is bound as one parameter");
        // The text carries the membership marker (no literal `?` run remains): the only `?` arrives when
        // the emitter expands the marker, so the recovered text has zero `?` of its own.
        assertEquals(0, raw.sql().chars().filter(ch -> ch == '?').count(),
                "no per-element `?` run remains in the recovered text (the marker carries the membership)");
        assertTrue(raw.sql().contains(RawSql.arrayBindMarker(0)), "the text carries the array-bind marker");
    }

    // ===== emitted SQL (native per dialect) ======================================================

    @Test
    void emitsAnyArrayBindOnPostgres() throws Exception {
        String sql = transpile("FlagByIds", FLAG_BY_IDS, "postgresql");
        assertTrue(sql.contains("id = ANY($1)"),
                "PostgreSQL must bind the whole list as one array via = ANY($1); was:\n" + sql);
        // The routine parameter is a native bigint[] array (the List<Long> signature maps to TArrayType).
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("BIGINT[]"),
                "the list parameter must be a native bigint[] array; was:\n" + sql);
        assertFalse(sql.contains(RawSql.arrayBindMarker(0)), "the marker must be fully expanded");
    }

    @Test
    void emitsJsonTableArrayBindOnMysql() throws Exception {
        String sql = transpile("FlagByIds", FLAG_BY_IDS, "mysql");
        // The membership is emitted inside a PREPARE '...' literal, so its own single quotes are doubled
        // ('$[*]' -> ''$[*]''). Assert the structure (JSON_TABLE over the bound `?`, BIGINT column cast).
        assertTrue(sql.contains("JSON_TABLE(?, ''$[*]'' COLUMNS (v BIGINT PATH ''$''))"),
                "MySQL must bind the whole list as one JSON-array via JSON_TABLE(?); was:\n" + sql);
        assertTrue(sql.contains("id IN (SELECT v FROM JSON_TABLE("),
                "MySQL must keep the membership as IN (SELECT v FROM JSON_TABLE(...)); was:\n" + sql);
        // The list parameter is a JSON column (TArrayType maps to JSON on MySQL).
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("P_IDS JSON"),
                "the list parameter must be a JSON parameter; was:\n" + sql);
        assertFalse(sql.contains(RawSql.arrayBindMarker(0)), "the marker must be fully expanded");
    }

    // ===== FORM A `placeholders(coll.size())` helper — end-to-end agreement ======================

    @Test
    void helperFormLowersToOneArrayBindRawSql() throws Exception {
        // The recognized run helper FORM A must lower to the SAME single ArrayBind as the inline form, with
        // a clean lowering (no errors): the helper call + bind loop are subsumed, the whole list is one
        // param, no per-element `?` remains.
        Block block = lowerOneBlock("FlagByIdsHelper", FLAG_BY_IDS_HELPER);
        ExecuteSqlStatement execute = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .findFirst().orElseThrow();
        assertTrue(execute.sqlNode() instanceof RawSql, "the consuming statement must carry a RawSql");
        RawSql raw = (RawSql) execute.sqlNode();
        assertEquals(1, raw.arrayBinds().size(), "exactly one collection array-bind for the helper form");
        assertEquals("id", raw.arrayBinds().getFirst().lhs());
        assertEquals(List.of("ids"), raw.parameters(), "the whole list is bound as one parameter");
        assertEquals(0, raw.sql().chars().filter(ch -> ch == '?').count(),
                "no per-element `?` run remains in the recovered text");
    }

    @Test
    void helperFormTranspilesEndToEndOnPostgres() throws Exception {
        // The documented headline idiom must transpile end-to-end (recognizer accepted it standalone; the
        // pipeline — incl. InternalHelperDiscovery + FeatureValidator — must AGREE, not reject with E001 on
        // the elided helper's Collections.nCopies). Mirrors the inline form's = ANY($1) result exactly.
        String sql = transpile("FlagByIdsHelper", FLAG_BY_IDS_HELPER, "postgresql");
        assertTrue(sql.contains("id = ANY($1)"),
                "the placeholders(coll.size()) helper form must lower to = ANY($1); was:\n" + sql);
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("BIGINT[]"),
                "the list parameter must be a native bigint[] array; was:\n" + sql);
        // The elided helper must NOT be emitted as its own routine (it is subsumed, never called).
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("placeholders"),
                "the array-bound helper must not be transpiled as a routine; was:\n" + sql);
    }

    @Test
    void helperFormTranspilesEndToEndOnMysql() throws Exception {
        String sql = transpile("FlagByIdsHelper", FLAG_BY_IDS_HELPER, "mysql");
        assertTrue(sql.contains("JSON_TABLE(?, ''$[*]'' COLUMNS (v BIGINT PATH ''$''))"),
                "the helper form must lower to the JSON_TABLE membership on MySQL; was:\n" + sql);
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("placeholders"),
                "the array-bound helper must not be transpiled as a routine; was:\n" + sql);
    }

    // ===== MySQL JSON_TABLE COLUMNS type — must be a VALID column type (not a CAST keyword) ========

    private static final String FLAG_BY_INT_IDS = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIntIds {
                @StoredProcedure
                public static void flagByIntIds(Connection c, List<Integer> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setInt(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    private static final String FLAG_BY_STR_CODES = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByStrCodes {
                @StoredProcedure
                public static void flagByStrCodes(Connection c, List<String> codes) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")");
                    for (int i = 0; i < codes.size(); i++) {
                        ps.setString(i + 1, codes.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void mysqlIntListEmitsValidIntColumnTypeNotSigned() throws Exception {
        // REGRESSION LOCK: TIntType must map to the JSON_TABLE COLUMNS column type INT (a valid declared
        // column type), NOT the CAST-target keyword SIGNED (ERROR 1064 at CALL). Cf. correctness/deploy
        // criticals.
        String sql = transpile("FlagByIntIds", FLAG_BY_INT_IDS, "mysql");
        assertTrue(sql.contains("COLUMNS (v INT PATH ''$'')"),
                "List<Integer> must extract via a valid INT column type; was:\n" + sql);
        assertFalse(sql.contains("COLUMNS (v SIGNED"),
                "SIGNED is a CAST keyword, invalid in a JSON_TABLE COLUMNS slot; was:\n" + sql);
    }

    @Test
    void mysqlStringListEmitsLongtextNotChar1000() throws Exception {
        // REGRESSION LOCK: TTextType must map to LONGTEXT (valid, and unbounded so no element truncates to
        // NULL), NOT CHAR(1000) (ERROR 1074: exceeds the 255-char COLUMNS cap). Cf. correctness/deploy
        // criticals + the truncation finding (LONGTEXT keeps PG text[] row-equivalence for long elements).
        String sql = transpile("FlagByStrCodes", FLAG_BY_STR_CODES, "mysql");
        assertTrue(sql.contains("COLUMNS (v LONGTEXT PATH ''$'')"),
                "List<String> must extract via LONGTEXT (truncation-free); was:\n" + sql);
        // The membership's extracted column must not be a CHAR(...) (the 255-char cap; CHAR(1000) is
        // ERROR 1074). Check only the `v <type>` COLUMNS slot — an unrelated VARCHAR(64) elsewhere (the
        // saved time-zone local) must not trip this.
        assertFalse(sql.contains("COLUMNS (v CHAR"),
                "the JSON_TABLE COLUMNS type must not be CHAR(...) (exceeds the 255-char cap); was:\n" + sql);
    }

    // FORM B with a deliberately-mismatched createArrayOf type-string: the cast must follow the static
    // List<E> (bigint, the bound value type), NOT the "text" the developer wrote — type-consistency with
    // the bound elements is what matters; the JDBC type-string is non-authoritative (and documented so).
    private static final String SETARRAY_MISMATCHED_TYPE_STRING = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIdArrayMismatch {
                @StoredProcedure
                public static void flagByIdArray(Connection c, List<Long> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (?)");
                    ps.setArray(1, c.createArrayOf("text", ids.toArray()));
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void setArrayTypeStringIsNonAuthoritativeCastFollowsListElement() throws Exception {
        Block block = lowerOneBlock("FlagByIdArrayMismatch", SETARRAY_MISMATCHED_TYPE_STRING);
        RawSql raw = block.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .map(ExecuteSqlStatement::sqlNode).filter(RawSql.class::isInstance).map(RawSql.class::cast)
                .findFirst().orElseThrow();
        assertEquals(1, raw.arrayBinds().size());
        // The element cast follows List<Long> (BIGINT), NOT the createArrayOf("text", ...) argument.
        assertTrue(raw.arrayBinds().getFirst().elementType() instanceof TBigintType,
                "createArrayOf's type-string must be ignored; the cast follows the List<E> static type (bigint)");
        // And it emits the bigint membership on each dialect (PG bigint[] / MySQL BIGINT COLUMNS).
        String pg = transpile("FlagByIdArrayMismatch", SETARRAY_MISMATCHED_TYPE_STRING, "postgresql");
        assertTrue(pg.toUpperCase(java.util.Locale.ROOT).contains("BIGINT[]"),
                "PG cast must be bigint[] (from List<Long>), not text[]; was:\n" + pg);
    }

    @Test
    void noListElementReachesTheText() throws Exception {
        // The security invariant: the recovered text + emitted SQL never splice a list element — only the
        // membership form + a single bound `?`/`$1` appears.
        String pg = transpile("FlagByIds", FLAG_BY_IDS, "postgresql");
        String mysql = transpile("FlagByIds", FLAG_BY_IDS, "mysql");
        // Exactly one bound placeholder for the collection on each dialect (no per-element run).
        assertTrue(pg.contains("USING p_ids"), "PG binds the whole list param via USING; was:\n" + pg);
        assertTrue(mysql.contains("SET @titan_p1 = p_ids"),
                "MySQL binds the whole list param via @p; was:\n" + mysql);
    }
}
