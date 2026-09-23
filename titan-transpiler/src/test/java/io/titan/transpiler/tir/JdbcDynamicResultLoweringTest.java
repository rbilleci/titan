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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3 Rung 5 — the unknown-shape result carrier (design contract D5). Source → parse → lower
 * → assert the carrier TIR shape ({@link DynamicResultStatement}); → emit and assert the native
 * per-dialect carrier (PostgreSQL {@code jsonb_agg}/{@code to_jsonb} function {@code RETURNS jsonb};
 * MySQL a {@code @StoredProcedure} streaming a native open result set).
 *
 * <p><b>The invariants under test:</b> only a PURE metadata-driven generic reader carrier-lowers; ANY
 * business logic over the unknown-shape rows is Tier-4 and REJECTS ("generic ResultSet processing beyond
 * row marshalling cannot be transpiled"); a typed fixed-column read still lowers the normal (typed
 * cursor) way; and the carrier faithfully returns the same data the {@code List<Map>} would (empty
 * result → {@code []} on PostgreSQL).</p>
 */
class JdbcDynamicResultLoweringTest {

    @TempDir
    Path tempDir;

    // ---- sources ----------------------------------------------------------------------------------

    /** The canonical PURE metadata-driven generic reader (the carrier shape). A @StoredFunction. */
    private static final String GENERIC_READER = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class GenericRead {
                @StoredFunction
                public static List<Map<String,Object>> readAll(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets ORDER BY id");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    /**
     * The canonical generic reader whose SELECT ends with a trailing {@code ;} (legal JDBC). The carrier
     * must STRIP it so the PostgreSQL {@code FROM (<sql>) t} wrap is valid — an embedded {@code ;} CREATEs
     * fine but throws {@code syntax error at ";"} at CALL time on PostgreSQL.
     */
    private static final String GENERIC_READER_TRAILING_SEMI = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class GenericReadSemi {
                @StoredFunction
                public static List<Map<String,Object>> readAll(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets ORDER BY id;");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    /** A generic reader with a value-bound parameter ({@code WHERE kind = ?}) — the carrier binds it. */
    private static final String GENERIC_READER_BOUND = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class GenericReadBound {
                @StoredFunction
                public static List<Map<String,Object>> readByKind(Connection c, String kind) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets WHERE kind = ? ORDER BY id");
                    ps.setString(1, kind);
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    // ---- Tier-4 sources: generic metadata processing that does BUSINESS LOGIC (must REJECT) ---------

    /** Tier-4: branches on a column value while marshalling (filters rows in Java). */
    private static final String TIER4_BRANCH_ON_COLUMN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class FilterRows {
                @StoredFunction
                public static List<Map<String,Object>> readActive(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label, active FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        if (rs.getObject(3) != null) {
                            rows.add(row);
                        }
                    }
                    return rows;
                }
            }
            """;

    /** Tier-4: accumulates a value over the unknown-shape rows (a Java-side aggregate). */
    private static final String TIER4_ACCUMULATE = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class CountRows {
                @StoredFunction
                public static long countAll(Connection c) throws SQLException {
                    long count = 0;
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            Object v = rs.getObject(i);
                        }
                        count++;
                    }
                    return count;
                }
            }
            """;

    /** Tier-4: the rows are post-processed (a second use of `rows` beyond add/return). */
    private static final String TIER4_POSTPROCESS_ROWS = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class FirstRow {
                @StoredFunction
                public static Map<String,Object> readFirst(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows.get(0);
                }
            }
            """;

    /** Tier-4: a typed transform inside the loop (calls a method per column / computes a value). */
    private static final String TIER4_TYPED_TRANSFORM = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class UpperLabels {
                @StoredFunction
                public static List<Map<String,Object>> readUpper(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getString(i).toUpperCase());
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    /**
     * Tier-4: keys by getColumnName (base column name) over an ALIASED SELECT. The carrier keys by the
     * output LABEL, so getColumnName has no faithful carrier; the recognizer refuses → the lowerer's
     * Tier-4 gate fires (rather than silently mis-keying the map).
     */
    private static final String TIER4_GET_COLUMN_NAME_ALIASED = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ReadByName {
                @StoredFunction
                public static List<Map<String,Object>> readByName(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id AS widget_id, label AS widget_label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnName(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    /**
     * Tier-4: a residual md.getColumnCount() AFTER the marshalling loop — a use of the unknown-shape
     * metadata with no server-side form. The escape proof refuses; the lowerer must emit the clean Tier-4
     * diagnostic, NOT a misleading "no SQL lowering for getColumnCount".
     */
    private static final String TIER4_RESIDUAL_METADATA = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ResidualMeta {
                @StoredFunction
                public static List<Map<String,Object>> readResidual(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    int n = md.getColumnCount();
                    return rows;
                }
            }
            """;

    /** A typed FIXED-shape read (no ResultSetMetaData): must lower the NORMAL way (typed cursor), not a carrier. */
    private static final String TYPED_FIXED_READ = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class TypedRead {
                @StoredProcedure
                public static void touch(Connection c) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String label = rs.getString("label");
                    }
                }
            }
            """;

    // ---- harness ----------------------------------------------------------------------------------

    private record LowerResult(Map<String, Block> lowered, DiagnosticSink sink) { }

    private LowerResult lower(String className, String source) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer()
                .lower(parsed, entryPoints, sink, false, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, null);
        return new LowerResult(lowered, sink);
    }

    private DynamicResultStatement onlyCarrier(LowerResult result) {
        assertTrue(result.sink().errors().isEmpty(),
                "expected a clean (carrier-emitting) lowering; errors=" + result.sink().errors());
        return result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .filter(DynamicResultStatement.class::isInstance).map(DynamicResultStatement.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("expected a DynamicResultStatement carrier"));
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

    // ===== positive: the pure generic reader carrier-lowers =========================================

    @Test
    void pureGenericReaderLowersToCarrier() throws Exception {
        DynamicResultStatement carrier = onlyCarrier(lower("GenericRead", GENERIC_READER));
        // The carrier's RawSql is the constant SELECT, with no value binds and no splice/array binds.
        assertEquals("SELECT id, label FROM widgets ORDER BY id", carrier.query().sql());
        assertTrue(carrier.query().parameters().isEmpty(), "the constant carrier SELECT binds nothing");
        assertFalse(carrier.query().hasSpliceBinds(), "the carrier is not a splice");
    }

    @Test
    void carrierStripsTrailingSemicolonFromSql() throws Exception {
        // The trailing ';' is stripped at lowering so the carrier SQL is a bare SELECT (wrappable as a
        // derived table without a syntax error). Both dialects get the stripped text.
        DynamicResultStatement carrier = onlyCarrier(lower("GenericReadSemi", GENERIC_READER_TRAILING_SEMI));
        assertEquals("SELECT id, label FROM widgets ORDER BY id", carrier.query().sql(),
                "the carrier must strip the trailing ';' from the source SELECT");
        assertFalse(carrier.query().sql().endsWith(";"), "no trailing ';' may survive into the carrier SQL");
    }

    @Test
    void carrierWithTrailingSemicolonWrapsCleanlyOnPostgres() throws Exception {
        // End-to-end emit proof: the PG derived-table wrap contains the stripped SELECT (no ';' before t).
        String sql = transpile("GenericReadSemi", GENERIC_READER_TRAILING_SEMI, "postgresql");
        assertTrue(sql.contains("FROM (' || 'SELECT id, label FROM widgets ORDER BY id' || ') t'"),
                "the PG carrier must wrap the trailing-';'-stripped SELECT as a derived table; was:\n" + sql);
        assertFalse(sql.contains("ORDER BY id;' || ') t'"),
                "an un-stripped ';' before the derived-table close would be a CALL-time syntax error; was:\n" + sql);
    }

    @Test
    void boundGenericReaderCarriesItsValueBind() throws Exception {
        DynamicResultStatement carrier = onlyCarrier(lower("GenericReadBound", GENERIC_READER_BOUND));
        assertTrue(carrier.query().sql().contains("WHERE kind = ?"),
                "the carrier keeps the WHERE kind = ? placeholder");
        assertEquals(List.of("kind"), carrier.query().parameters(),
                "the carrier binds the kind value (never spliced into the text)");
    }

    // ===== PostgreSQL emission: jsonb_agg / to_jsonb function, RETURNS jsonb =========================

    @Test
    void carrierEmitsJsonbAggFunctionOnPostgres() throws Exception {
        String sql = transpile("GenericRead", GENERIC_READER, "postgresql");
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION"), "PG carrier is a function");
        assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("RETURNS JSONB"),
                "PG carrier returns jsonb; was:\n" + sql);
        assertTrue(sql.contains("jsonb_agg(to_jsonb(t))"),
                "PG carrier aggregates rows via jsonb_agg(to_jsonb(t)); was:\n" + sql);
        // The '[]'::jsonb literal sits inside the EXECUTE '...' text, so its single-quotes are doubled.
        assertTrue(sql.contains("''[]''::jsonb"),
                "PG carrier COALESCEs an empty result to '[]'::jsonb (quote-doubled in the EXECUTE text); was:\n" + sql);
        // The source SELECT is wrapped as a derived table `FROM (<sql>) t`; the wrapper concatenates the
        // jsonb-aggregation around the (constant) inner SELECT with || (so the value binds stay bound).
        assertTrue(sql.contains("FROM (' || 'SELECT id, label FROM widgets ORDER BY id' || ') t'"),
                "PG carrier wraps the source SELECT as a derived table; was:\n" + sql);
        assertTrue(sql.contains("RETURN "), "PG carrier RETURNs the aggregated jsonb; was:\n" + sql);
        // The generic-reader scaffolding (the List<Map>/ArrayList/put/add) never reaches the SQL.
        assertFalse(sql.contains("ArrayList") || sql.contains("getColumnLabel"),
                "the Java marshalling scaffolding must not leak into the SQL");
    }

    // ===== MySQL emission: a PROCEDURE streaming a native open result set ============================

    @Test
    void carrierEmitsOpenResultSetProcedureOnMysql() throws Exception {
        String sql = transpile("GenericRead", GENERIC_READER, "mysql");
        assertTrue(sql.contains("CREATE PROCEDURE"),
                "MySQL carrier is a PROCEDURE (dynamic SQL is forbidden in a FUNCTION); was:\n" + sql);
        assertFalse(sql.contains("CREATE FUNCTION"), "MySQL carrier must not be a function; was:\n" + sql);
        assertFalse(sql.contains("RETURNS"), "a MySQL procedure has no RETURNS; was:\n" + sql);
        assertTrue(sql.contains("SELECT id, label FROM widgets ORDER BY id;"),
                "MySQL carrier must emit the fixed-shape SELECT as native SQL; was:\n" + sql);
        assertFalse(sql.contains("PREPARE") || sql.contains("EXECUTE"),
                "a fixed-shape open result must not use dynamic SQL; was:\n" + sql);
        // No INTO (the result set streams to the client) and no JSON aggregation (that is the PG form).
        assertFalse(sql.contains("INTO @titan_r"),
                "the MySQL carrier must leave the result set OPEN (no INTO); was:\n" + sql);
        assertFalse(sql.contains("jsonb_agg"), "jsonb is the PG carrier form, not MySQL; was:\n" + sql);
    }

    // ===== the Tier-3/Tier-4 fail-safe: business logic over unknown-shape rows REJECTS ==============

    @Test
    void tier4BranchOnColumnRejects() throws Exception {
        assertTier4Rejected("FilterRows", TIER4_BRANCH_ON_COLUMN);
    }

    @Test
    void tier4AccumulateRejects() throws Exception {
        assertTier4Rejected("CountRows", TIER4_ACCUMULATE);
    }

    @Test
    void tier4PostProcessRowsRejects() throws Exception {
        assertTier4Rejected("FirstRow", TIER4_POSTPROCESS_ROWS);
    }

    @Test
    void tier4TypedTransformRejects() throws Exception {
        assertTier4Rejected("UpperLabels", TIER4_TYPED_TRANSFORM);
    }

    @Test
    void tier4GetColumnNameAliasedRejects() throws Exception {
        // getColumnName keys by base column name; the carrier keys by output label/alias. No faithful
        // carrier form -> Tier-4 reject (never a silently mis-keyed carrier).
        assertTier4Rejected("ReadByName", TIER4_GET_COLUMN_NAME_ALIASED);
    }

    @Test
    void tier4ResidualMetadataAfterLoopRejects() throws Exception {
        // A residual md.getColumnCount() after the loop is residual logic on the unknown-shape metadata;
        // the escape proof refuses and the lowerer emits the clean Tier-4 diagnostic.
        assertTier4Rejected("ResidualMeta", TIER4_RESIDUAL_METADATA);
    }

    /** A Tier-4 generic-processing method must REJECT with the clear "beyond row marshalling" diagnostic. */
    private void assertTier4Rejected(String className, String source) throws Exception {
        LowerResult result = lower(className, source);
        assertFalse(result.sink().errors().isEmpty(),
                "expected a Tier-4 reject (no carrier) for business logic over unknown-shape rows");
        assertTrue(result.sink().errors().stream().anyMatch(d ->
                        d.message().contains("generic ResultSet processing beyond row marshalling cannot be transpiled")),
                "expected the Tier-4 diagnostic; errors=" + result.sink().errors());
        // It must NOT have produced a carrier.
        assertTrue(result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .noneMatch(DynamicResultStatement.class::isInstance),
                "a Tier-4 method must not carrier-lower");
    }

    // ===== a typed fixed-shape read still lowers the NORMAL (typed cursor) way, not a carrier =======

    @Test
    void typedFixedReadStaysATypedCursor() throws Exception {
        LowerResult result = lower("TypedRead", TYPED_FIXED_READ);
        assertTrue(result.sink().errors().isEmpty(),
                "a typed fixed-column read still lowers cleanly; errors=" + result.sink().errors());
        boolean hasCarrier = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .anyMatch(DynamicResultStatement.class::isInstance);
        boolean hasTypedCursor = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .anyMatch(RawCursorStatement.class::isInstance);
        assertFalse(hasCarrier, "a typed fixed-column read must NOT become an unknown-shape carrier");
        assertTrue(hasTypedCursor, "a typed fixed-column read lowers to the normal typed cursor");
    }
}
