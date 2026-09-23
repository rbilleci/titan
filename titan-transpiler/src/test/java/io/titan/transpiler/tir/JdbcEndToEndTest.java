package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 2 (2b) end-to-end: a standard-JDBC {@code @StoredProcedure}/{@code @StoredFunction}
 * transpiled through the full {@link TranspilationPipeline} to native single-dialect SQL. Proves the
 * Connection-parameter drop (§2.2) at the emitted-signature level and the native dynamic emission of
 * the recognized read/update shapes.
 */
class JdbcEndToEndTest {

    @TempDir
    Path tempDir;

    private List<TranspilationPipeline.GeneratedSql> transpile(String fileName, String source, String target) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return new TranspilationPipeline().transpile(List.of(file), List.of(), List.of(target), List.of("billing"), true);
    }

    private static String sqlFor(List<TranspilationPipeline.GeneratedSql> generated, String target) {
        return generated.stream().filter(g -> g.target().equals(target)).findFirst().orElseThrow().sql();
    }

    @Test
    void connectionParameterIsDroppedFromEmittedSignature() throws Exception {
        // The Connection is infrastructure: it must not appear as a routine parameter (§2.2), while the
        // data parameter (account_id) does. The single-row read emits as native PG EXECUTE … INTO.
        List<TranspilationPipeline.GeneratedSql> generated = transpile("ComputeFee.java", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class ComputeFee {
                    @StoredProcedure
                    public static void computeAccountFee(Connection c, long accountId) throws SQLException {
                        String tier;
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, accountId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            tier = rs.getString("tier");
                        }
                    }
                }
                """, "postgresql");
        String pg = sqlFor(generated, "postgresql");
        // The data parameter survives; neither a `c`/`connection` parameter nor a java.sql type leaks.
        assertTrue(pg.contains("p_account_id"), "the data parameter should be emitted; was:\n" + pg);
        assertFalse(pg.toLowerCase().contains("connection"), "Connection must be dropped from the signature; was:\n" + pg);
        // Native single-dialect read emission (dynamic EXECUTE … INTO over the source SQL).
        assertTrue(pg.contains("EXECUTE 'SELECT tier FROM accounts WHERE id = $1' INTO v_tier USING p_account_id"),
                "expected native PG EXECUTE … INTO; was:\n" + pg);
    }

    @Test
    void dataSourceParameterIsAlsoDropped() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile("DsDrop.java", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;
                import javax.sql.DataSource;

                class DsDrop {
                    @StoredProcedure
                    public static void touch(DataSource ds, long id) throws SQLException {
                        Connection c = ds.getConnection();
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET seen = 1 WHERE id = ?");
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                """, "postgresql");
        String pg = sqlFor(generated, "postgresql");
        assertFalse(pg.toLowerCase().contains("datasource"), "DataSource must be dropped; was:\n" + pg);
        assertTrue(pg.contains("p_id"), "the data parameter should survive; was:\n" + pg);
        assertTrue(pg.contains("EXECUTE 'UPDATE accounts SET seen = 1 WHERE id = $1' USING p_id"),
                "expected native PG dynamic EXECUTE for executeUpdate; was:\n" + pg);
    }

    // ----- WS-C Phase 3 Rung 1: skeleton recovery of value-bindable non-constant SQL ------------

    /**
     * Rung 1 §3.6 form 3 — a runtime-built placeholder run ({@code IN (} + {@code "?, ".repeat(2) + "?"}
     * + {@code )}) whose {@code ?}s are bound by the statement's own {@code setLong} ordinals transpiles
     * (in STRICT — value-bindable, no value reaches the text) to the recovered {@code IN ($1, $2, $3)}
     * text with the three binds in order. Previously this was an E004 reject. ({@code String.repeat}
     * keeps the construction value-bindable AND inside the Titan subset — no collection op, no helper to
     * transpile.)
     */
    @Test
    void placeholderRunRecoversAndBindsByOrdinalOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile("PlaceholderRunPg.java", """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class PlaceholderRunPg {
                    @StoredFunction
                    public static long countByIds(Connection c, long a, long b, long d) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement(
                                "SELECT count(*) AS c FROM accounts WHERE id IN (" + "?, ".repeat(2) + "?)");
                        ps.setLong(1, a);
                        ps.setLong(2, b);
                        ps.setLong(3, d);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """, "postgresql");
        String pg = sqlFor(generated, "postgresql");
        assertTrue(pg.contains("SELECT count(*) AS c FROM accounts WHERE id IN ($1, $2, $3)"),
                "expected the recovered fixed placeholder run IN ($1, $2, $3); was:\n" + pg);
        assertTrue(pg.contains("USING p_a, p_b, p_d"),
                "the three placeholders must bind the three setLong ordinals in order; was:\n" + pg);
    }

    /** The same placeholder run transpiles on MySQL as native static SQL with typed parameters. */
    @Test
    void placeholderRunRecoversAndBindsByOrdinalOnMysql() throws Exception {
        // The live deploy IT placeholderRunDeploysAndFlagsBoundIdsOnMysql proves the procedure form on
        // MySQL 8.4; this byte-golden also locks out needless dynamic SQL.
        List<TranspilationPipeline.GeneratedSql> generated = transpile("PlaceholderRunMy.java", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class PlaceholderRunMy {
                    @StoredProcedure
                    public static void flagByIds(Connection c, long a, long b, long d) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "UPDATE accounts SET flagged = 1 WHERE id IN (" + "?, ".repeat(2) + "?)");
                        ps.setLong(1, a);
                        ps.setLong(2, b);
                        ps.setLong(3, d);
                        ps.executeUpdate();
                    }
                }
                """, "mysql");
        String my = sqlFor(generated, "mysql");
        assertTrue(my.contains("WHERE id IN (p_a, p_b, p_d)"),
                "MySQL must preserve ordinal order with typed routine parameters; was:\n" + my);
        assertFalse(my.contains("PREPARE") || my.contains("@titan_p"),
                "the fixed placeholder run must remain native static SQL; was:\n" + my);
    }

    /**
     * Rung 1 D3 — a scalar value-splice auto-binds: {@code "… WHERE tier = " + tier} lowers (in STRICT)
     * to {@code … WHERE tier = $1} plus a synthesized bind of the spliced value, never spliced text.
     */
    @Test
    void valueSpliceAutoBindsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile("ValueSplicePg.java", """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class ValueSplicePg {
                    @StoredFunction
                    public static long countByTier(Connection c, String tier) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement(
                                "SELECT count(*) AS c FROM accounts WHERE tier = " + tier);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """, "postgresql");
        String pg = sqlFor(generated, "postgresql");
        assertTrue(pg.contains("WHERE tier = $1"),
                "the spliced value must become a bound $1, never spliced text; was:\n" + pg);
        // The bind is a synthesized __titan_pN local assigned the spliced expression (the value is
        // parameterized — no value reaches the SQL text).
        assertTrue(pg.contains("USING __titan_p"),
                "the value splice must bind a synthesized local via USING; was:\n" + pg);
        assertFalse(pg.contains("tier = ' "), "the value must not be spliced verbatim into the text; was:\n" + pg);
    }

    /** The value-splice also transpiles on MySQL through a typed synthesized local. */
    @Test
    void valueSpliceAutoBindsOnMysql() throws Exception {
        // The live deploy IT valueSpliceDeploysAndFlagsByBoundValueOnMysql proves this on MySQL 8.4.
        List<TranspilationPipeline.GeneratedSql> generated = transpile("ValueSpliceMy.java", """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class ValueSpliceMy {
                    @StoredProcedure
                    public static void flagByTier(Connection c, String tier) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "UPDATE accounts SET flagged = 1 WHERE tier = " + tier);
                        ps.executeUpdate();
                    }
                }
                """, "mysql");
        String my = sqlFor(generated, "mysql");
        assertTrue(my.contains("WHERE tier = __titan_p1"),
                "MySQL must reference the typed synthesized local; was:\n" + my);
        assertTrue(my.contains("SET __titan_p1 = p_tier"),
                "the value splice must assign the source value to the synthesized local; was:\n" + my);
        assertFalse(my.contains("PREPARE") || my.contains("@titan_p"),
                "a value-only splice must not force dynamic SQL; was:\n" + my);
    }

    /**
     * WS-C Phase 3 Rung 1 FIX (correctness/deploy, item c): a recovered placeholder run with MORE {@code
     * ?} than bound {@code setXxx} ordinals ({@code "?, ".repeat(2) + "?"} = 3 placeholders, only 2
     * setLong) must NOT emit an under-bound {@code EXECUTE '... IN ($1, $2, $3)' USING p_a, p_b} (PG fails
     * at CALL with "there is no parameter $3"). The arity invariant rejects it (placeholder count != bind
     * count), never ships the desynced EXECUTE.
     */
    @Test
    void placeholderRunWithFewerBindsThanPlaceholdersIsRejected() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("UnderBound.java");
        Files.writeString(file, """
                import titan.dsl.StoredProcedure;
                import java.sql.*;

                class UnderBound {
                    @StoredProcedure
                    public static void flag(Connection c, long a, long b) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                                "UPDATE accounts SET flagged = 1 WHERE id IN (" + "?, ".repeat(2) + "?)");
                        ps.setLong(1, a);
                        ps.setLong(2, b);
                        ps.executeUpdate();
                    }
                }
                """);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true));
        assertFalse(ex.getMessage().contains("$1, $2, $3"),
                "an under-bound placeholder run must not emit a desynced 3-placeholder EXECUTE; got: " + ex.getMessage());
    }

    /**
     * The security floor still holds: a runtime IDENTIFIER splice ({@code "… FROM " + tableName}) is NOT
     * value-bindable, so STRICT rejects it with the identifier-variant E004 (it is not flipped to accept
     * by Rung 1 — only value/placeholder holes are). Locks that the accept path did not widen the gate.
     */
    @Test
    void identifierSpliceStillRejectedUnderStrict() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("IdentSplice.java");
        Files.writeString(file, """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class IdentSplice {
                    @StoredFunction
                    public static long countAll(Connection c, String tableName) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement("SELECT count(*) AS c FROM " + tableName);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true));
        assertTrue(ex.getMessage().contains("TITAN-E004"),
                "an identifier splice must still be a strict E004 reject; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("format('%I'"),
                "the reject must be the identifier-variant diagnostic; got: " + ex.getMessage());
    }

    /**
     * WS-C Phase 3 Rung 1 FIX (correctness, critical): a VALUE splice whose immediately-following constant
     * begins with a DIGIT ({@code "... id = " + count + "00"}) must NOT emit a fused {@code $100} (PG reads
     * positional parameter #100 → "there is no parameter $100"). The recognizer demotes it to RAW_FRAGMENT,
     * so STRICT rejects it (never emits the fused placeholder). Locks the no-$100 fusion.
     */
    @Test
    void valueSpliceFollowedByDigitIsRejectedNotFusedOnPostgres() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("FuseDigit.java");
        Files.writeString(file, """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class FuseDigit {
                    @StoredFunction
                    public static long q(Connection c, long count) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement(
                                "SELECT tier FROM accounts WHERE id = " + count + "00");
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true));
        assertFalse(ex.getMessage().contains("$100"),
                "a digit-adjacent value splice must never emit a fused $100; got: " + ex.getMessage());
    }

    /**
     * WS-C Phase 3 Rung 1 FIX (correctness, critical): a VALUE splice inside an OPEN single-quoted LIKE
     * pattern ({@code "... LIKE '%" + frag + "%'"}) must NOT emit {@code LIKE '%?%'} (the {@code ?} is
     * skipped by the quote-aware rewrite, leaving a USING arg with no placeholder → "too many parameters").
     * The recognizer demotes the open-quote splice to RAW_FRAGMENT → STRICT rejects (never emits the
     * desynced literal).
     */
    @Test
    void likeWildcardSpliceIsRejectedNotEmittedAsQuotedPlaceholderOnPostgres() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("LikeSplice.java");
        Files.writeString(file, """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class LikeSplice {
                    @StoredFunction
                    public static long q(Connection c, String frag) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement(
                                "SELECT count(*) AS c FROM accounts WHERE tier LIKE '%" + frag + "%'");
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true));
        assertFalse(ex.getMessage().contains("'%?"),
                "a LIKE-pattern splice inside an open quote must not emit a quoted '%?'; got: " + ex.getMessage());
    }

    /**
     * WS-C Phase 3 Rung 1 FIX (security, high): an identifier spliced after an identifier-list comma
     * ({@code "... ORDER BY tier, " + sortCol}) must be rejected as an IDENTIFIER under STRICT (E004),
     * NOT silently swallowed as a bound value (which neuters the dynamic sort and breaks D2). Locks the
     * identifier-list classification end-to-end.
     */
    @Test
    void identifierAfterOrderByCommaIsStrictRejectedNotBoundOnPostgres() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("OrderByComma.java");
        Files.writeString(file, """
                import titan.dsl.StoredFunction;
                import java.sql.*;

                class OrderByComma {
                    @StoredFunction
                    public static long q(Connection c, String sortCol) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement(
                                "SELECT count(*) AS c FROM accounts ORDER BY tier, " + sortCol);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true));
        assertTrue(ex.getMessage().contains("TITAN-E004"),
                "an identifier after an ORDER BY-list comma must be a strict E004 reject; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("format('%I'"),
                "the reject must be the identifier-variant diagnostic; got: " + ex.getMessage());
    }

    /**
     * WS-C Phase 3 Rung 3: under PERMISSIVE an identifier splice ({@code FROM " + tableName}) now EMITS,
     * quoting the runtime identifier at runtime with PostgreSQL {@code quote_ident} — qualified-name aware
     * (Findings 2): the value is split on {@code '.'} and each segment {@code quote_ident}-quoted, so a
     * dotted {@code schema.table} quotes per segment rather than failing. A bare name quotes as a single
     * segment (the old {@code %I} result). STRICT still rejects the same shape with E004 (see {@link
     * #identifierSpliceStillRejectedUnderStrict}); this is the D2 boundary, opt-in.
     */
    @Test
    void identifierSpliceUnderPermissiveEmitsFormatPercentI() throws Exception {
        TranspilationPipeline pipeline = new TranspilationPipeline();
        Path file = tempDir.resolve("IdentSplicePermissive.java");
        Files.writeString(file, """
                import titan.dsl.StoredFunction;
                import titan.dsl.SqlSafety;
                import titan.dsl.SqlSafetyMode;
                import java.sql.*;

                class IdentSplicePermissive {
                    @StoredFunction
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static long countAll(Connection c, String tableName) throws SQLException {
                        long n = 0;
                        PreparedStatement ps = c.prepareStatement("SELECT count(*) AS c FROM " + tableName);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong(1);
                        }
                        return n;
                    }
                }
                """);
        List<TranspilationPipeline.GeneratedSql> generated =
                pipeline.transpile(List.of(file), List.of(), List.of("postgresql"), List.of("billing"), true);
        String pg = generated.stream()
                .filter(artifact -> "postgresql".equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst().orElseThrow();
        // The single-row read assembles its text at runtime: EXECUTE format('SELECT … FROM %s', <quoted>),
        // where <quoted> is the qualified-aware runtime quote (split on '.', quote_ident each segment).
        assertTrue(pg.contains("format('SELECT count(*) AS c FROM %s'"),
                "permissive identifier splice must assemble the runtime identifier via format; got:\n" + pg);
        assertTrue(pg.contains("quote_ident(__titan_idpart)")
                        && pg.contains("string_to_array(p_table_name, '.')"),
                "the runtime identifier must be quoted qualified-name aware (quote_ident per dotted segment); got:\n" + pg);
    }
}
