package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One test per idiom row of the JDBC subset, plus the §6.1/§6.2/§6.3 worked examples. */
class JdbcUsageRecognizerTest {

    @TempDir
    Path tempDir;

    // --- harness -----------------------------------------------------------------------------

    private MethodJdbcReport recognizeOne(String className, String source) throws Exception {
        return recognizeOne(className, source, SqlSafetyMode.STRICT);
    }

    private MethodJdbcReport recognizeOne(String className, String source, SqlSafetyMode buildLevel) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        List<MethodJdbcReport> reports = new JdbcUsageRecognizer()
                .recognize(parsed, entryPoints, new SqlSafetyResolver(buildLevel));
        assertEquals(1, reports.size(), "expected exactly one entry-point report");
        return reports.getFirst();
    }

    private static boolean hasUsage(MethodJdbcReport report, JdbcIdiom idiom, JdbcClassification classification) {
        return report.usages().stream()
                .anyMatch(u -> u.idiom() == idiom && u.classification() == classification);
    }

    private static JdbcUsage firstUsage(MethodJdbcReport report, JdbcIdiom idiom) {
        return report.usages().stream().filter(u -> u.idiom() == idiom).findFirst().orElseThrow();
    }

    private static String prelude() {
        return "import titan.dsl.*;\nimport java.sql.*;\n\n";
    }

    // --- I-1/I-2/I-3/I-4: single-row read over constant SQL (the common shape) ----------------

    @Test
    void i4ThenBlockReadOverConstantSqlIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("ThenRead", prelude() + """
                class ThenRead {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_2, JdbcClassification.TRANSPILABLE));
        assertTrue(hasUsage(report, JdbcIdiom.I_3, JdbcClassification.TRANSPILABLE));
        assertTrue(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void i4ElseBranchOnNextIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("ElseRead", prelude() + """
                class ElseRead {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        } else {
                            throw new IllegalStateException("x");
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.REJECTED));
    }

    @Test
    void i4DoWhileOnNextIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("DoWhileRead", prelude() + """
                class DoWhileRead {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts");
                        ResultSet rs = ps.executeQuery();
                        do {
                            String tier = rs.getString("tier");
                        } while (rs.next());
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.REJECTED));
    }

    @Test
    void i4BareUnguardedNextIsRejected() throws Exception {
        // §3.3 negative list: a bare `rs.next();` then a read is an enumerated TITAN-E001 reject —
        // NOT silently elided/accepted. (Regression for the §3.6 false-accept audit finding.)
        MethodJdbcReport report = recognizeOne("BareNext", prelude() + """
                class BareNext {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t WHERE id = 1");
                        ResultSet rs = ps.executeQuery();
                        rs.next();
                        String a = rs.getString("a");
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_4);
        assertEquals(JdbcClassification.REJECTED, usage.classification());
        assertEquals(TitanErrorCode.E001, usage.code());
    }

    @Test
    void i4WhileNextBreakUsedAsSingleRowIsRejected() throws Exception {
        // §3.3 negative list: while (rs.next()) { ...; break; } used as a single-row read is rejected.
        MethodJdbcReport report = recognizeOne("WhileBreak", prelude() + """
                class WhileBreak {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            String a = rs.getString("a");
                            break;
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.REJECTED));
        // It must NOT also be (mis)accepted as a transpilable I-5 cursor.
        assertFalse(hasUsage(report, JdbcIdiom.I_5, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void i4NotNextGuardWithoutThrowIsNotAcceptedAsTranspilable() throws Exception {
        // if (!rs.next()) { <non-throw> } is NOT the early-return guard shape: it must NOT roll up
        // TRANSPILABLE as I-4. (Regression for the shape-2 unconditional-accept audit finding.)
        MethodJdbcReport report = recognizeOne("NoThrowGuard", prelude() + """
                class NoThrowGuard {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t WHERE id = 1");
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            int z = 0;
                        }
                        String a = rs.getString("a");
                    }
                }
                """);
        // The !next guard without a throw is rejected as an unsupported single-row shape; either way
        // it is decidedly NOT a clean TRANSPILABLE I-4 accept.
        assertFalse(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.TRANSPILABLE));
        assertEquals(JdbcClassification.REJECTED, report.rollup());
    }

    // --- I-5 multi-row cursor ----------------------------------------------------------------

    @Test
    void i5WhileNextIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Loop", prelude() + """
                class Loop {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT amount FROM invoices");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            java.math.BigDecimal a = rs.getBigDecimal("amount");
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_5, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void i5LexicalNestedCursorInsideLoopIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Nested", prelude() + """
                class Nested {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM a");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            PreparedStatement ps2 = c.prepareStatement("SELECT x FROM b WHERE a = ?");
                            ps2.setLong(1, rs.getLong("id"));
                            ResultSet rs2 = ps2.executeQuery();
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_5, JdbcClassification.TRANSPILABLE));
        assertFalse(hasUsage(report, JdbcIdiom.I_5, JdbcClassification.REJECTED));
    }

    // --- I-6 / I-8 update + plain statement --------------------------------------------------

    @Test
    void i6ExecuteUpdateOverConstantSqlIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Upd", prelude() + """
                class Upd {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET fee = 1 WHERE id = ?");
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_6, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void i8PlainStatementExecuteOverConstantSqlIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Plain", prelude() + """
                class Plain {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("DELETE FROM sessions WHERE expired = true");
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_6, JdbcClassification.TRANSPILABLE));
    }

    // --- I-7 generated keys ------------------------------------------------------------------

    @Test
    void i7GeneratedKeysWithReturnGeneratedKeysIsTranspilable() throws Exception {
        // I-7 is TRANSPILABLE for the canonical shape: prepareStatement(SQL, RETURN_GENERATED_KEYS) then a
        // single getLong(1). The 2b lowerer lowers it to the trigger-immune, driver-faithful form
        // (PostgreSQL INSERT ... RETURNING <col> INTO with the key column resolved from the Catalog;
        // MySQL SET v = LAST_INSERT_ID()). The recognizer parity-accepts; the off-shape read checks below
        // still reject I-R8 (no silent recognizer-transpilable / lowerer-no-op gap).
        MethodJdbcReport report = recognizeOne("Keys", prelude() + """
                class Keys {
                    @StoredFunction
                    public static long run(Connection c, long cid) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, cid);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(1);
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_7, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void i7GeneratedKeysWithoutReturnGeneratedKeysIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("KeysBad", prelude() + """
                class KeysBad {
                    @StoredFunction
                    public static long run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("INSERT INTO invoices DEFAULT VALUES");
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(1);
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R8, JdbcClassification.REJECTED));
    }

    @Test
    void i7GeneratedKeysReadByNonIntAccessorIsRejected() throws Exception {
        // Reconciliation (no silent gap): RETURN_GENERATED_KEYS is present, but the key is read by
        // getString(1) — not a single auto-increment integer key. The recognizer must reject I-R8 so
        // its classification matches exactly what the 2b lowerer can build.
        MethodJdbcReport report = recognizeOne("KeysString", prelude() + """
                class KeysString {
                    @StoredFunction
                    public static String run(Connection c, long cid) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, cid);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getString(1);
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R8, JdbcClassification.REJECTED));
    }

    @Test
    void i7GeneratedKeysReadByNonOneOrdinalIsRejected() throws Exception {
        // getLong(2) — a non-1 ordinal — is I-R8: the JDBC ordinal 1 is the single generated key.
        MethodJdbcReport report = recognizeOne("KeysOrdinal", prelude() + """
                class KeysOrdinal {
                    @StoredFunction
                    public static long run(Connection c, long cid) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, cid);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(2);
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R8, JdbcClassification.REJECTED));
    }

    @Test
    void i7GeneratedKeysReadTwiceIsRejected() throws Exception {
        // Reading the generated-keys ResultSet more than once is I-R8 (composite/multi-key territory):
        // LAST_INSERT_ID()/lastval() yields a single value, so a second read cannot be honored.
        MethodJdbcReport report = recognizeOne("KeysTwice", prelude() + """
                class KeysTwice {
                    @StoredFunction
                    public static long run(Connection c, long cid) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, cid);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        long a = keys.getLong(1);
                        long b = keys.getLong(1);
                        return a + b;
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R8, JdbcClassification.REJECTED));
    }

    // --- I-10 transactions -------------------------------------------------------------------

    @Test
    void i10SetAutoCommitIsTranspilableWithW005() throws Exception {
        MethodJdbcReport report = recognizeOne("Auto", prelude() + """
                class Auto {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        c.setAutoCommit(false);
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_10);
        assertEquals(JdbcClassification.TRANSPILABLE, usage.classification());
        assertEquals(TitanErrorCode.W005, usage.code());
    }

    @Test
    void i10CommitInProcedureIsTranspilableWithMandatoryW005() throws Exception {
        MethodJdbcReport report = recognizeOne("Commit", prelude() + """
                class Commit {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.commit();
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_10);
        assertEquals(TitanErrorCode.W005, usage.code());
    }

    @Test
    void i10CommitInScheduledJobIsTranspilableWithW005() throws Exception {
        MethodJdbcReport report = recognizeOne("CommitJob", prelude() + """
                class CommitJob {
                    @ScheduledJob(cron = "0 4 * * *")
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.commit();
                    }
                }
                """);
        // A @ScheduledJob is procedure-backed on both dialects, so commit() is TRANSPILABLE (W005), not
        // REJECTED as "inside a function/trigger" (the storedProcedure-proxy bug).
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_10);
        assertEquals(JdbcClassification.TRANSPILABLE, usage.classification());
        assertEquals(TitanErrorCode.W005, usage.code());
    }

    @Test
    void i10CommitInFunctionIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("CommitFn", prelude() + """
                class CommitFn {
                    @StoredFunction
                    public static int run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.commit();
                        return 1;
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_10);
        assertEquals(JdbcClassification.REJECTED, usage.classification());
        assertEquals(TitanErrorCode.E001, usage.code());
    }

    // --- structural rejects (I-R2..I-R7), CallableStatement, batch ---------------------------

    @Test
    void irR2NonConstantColumnKeyIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("ColVar", prelude() + """
                class ColVar {
                    @StoredProcedure
                    public static void run(Connection c, String col) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t");
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            Object v = rs.getObject(col);
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R2, JdbcClassification.REJECTED));
    }

    @Test
    void irR2NonLiteralOrdinalBindIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("OrdVar", prelude() + """
                class OrdVar {
                    @StoredProcedure
                    public static void run(Connection c, int i, long v) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE t SET a = ? WHERE id = ?");
                        ps.setLong(i, v);
                        ps.executeUpdate();
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R2, JdbcClassification.REJECTED));
    }

    @Test
    void irR3ReturningResultSetIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Escape", prelude() + """
                class Escape {
                    @StoredFunction
                    public static ResultSet run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t");
                        ResultSet rs = ps.executeQuery();
                        return rs;
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R3, JdbcClassification.REJECTED));
    }

    @Test
    void irR5ScrollableUpdatableStatementIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Scroll", prelude() + """
                class Scroll {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement(
                            ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
                        ResultSet rs = st.executeQuery("SELECT a FROM t");
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R5, JdbcClassification.REJECTED));
    }

    @Test
    void irR5UpdateRowIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("UpdateRow", prelude() + """
                class UpdateRow {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            rs.updateInt("a", 1);
                            rs.updateRow();
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R5, JdbcClassification.REJECTED));
    }

    @Test
    void irR6MetadataIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Meta", prelude() + """
                class Meta {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT a FROM t");
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData md = rs.getMetaData();
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R6, JdbcClassification.REJECTED));
    }

    @Test
    void irR7StreamLobIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Lob", prelude() + """
                class Lob {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT data FROM t");
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            java.io.InputStream s = rs.getBinaryStream("data");
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R7, JdbcClassification.REJECTED));
    }

    @Test
    void callableStatementIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Call", prelude() + """
                class Call {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        CallableStatement cs = c.prepareCall("{call do_it(?)}");
                        cs.setLong(1, 5);
                        cs.execute();
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.CALLABLE_STATEMENT, JdbcClassification.REJECTED));
    }

    @Test
    void batchIsRejected() throws Exception {
        MethodJdbcReport report = recognizeOne("Batch", prelude() + """
                class Batch {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("INSERT INTO t (a) VALUES (?)");
                        ps.setLong(1, 1);
                        ps.addBatch();
                        ps.executeBatch();
                    }
                }
                """);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.BATCH, JdbcClassification.REJECTED));
    }

    // --- strict/permissive pair (the E004 gate) ----------------------------------------------

    private static final String NON_CONSTANT_SQL_SOURCE = prelude() + """
            class Dyn {
                @StoredProcedure
                public static void run(Connection c, long customerId) throws SQLException {
                    String sql = "SELECT * FROM orders WHERE customer_id = " + customerId;
                    PreparedStatement ps = c.prepareStatement(sql);
                    ps.executeQuery();
                }
            }
            """;

    @Test
    void nonConstantSqlUnderStrictIsRejectedWithE004ThreePartDiagnostic() throws Exception {
        MethodJdbcReport report = recognizeOne("Dyn", NON_CONSTANT_SQL_SOURCE, SqlSafetyMode.STRICT);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_R1);
        assertEquals(JdbcClassification.REJECTED, usage.classification());
        assertEquals(TitanErrorCode.E004, usage.code());
        // §5.3 three-part diagnostic: risk + safe form + escape.
        String reason = usage.reason();
        assertTrue(reason.contains("(a)") && reason.contains("(b)") && reason.contains("(c)"), reason);
        assertTrue(reason.contains("ANY(?)"), reason);
        assertTrue(reason.contains("@SqlSafety(PERMISSIVE)"), reason);
        assertTrue(reason.contains("sqlSafety=permissive"), reason);
    }

    @Test
    void nonConstantSqlUnderPermissiveBuildLevelIsPassthrough() throws Exception {
        MethodJdbcReport report = recognizeOne("Dyn", NON_CONSTANT_SQL_SOURCE, SqlSafetyMode.PERMISSIVE);
        assertEquals(JdbcClassification.PASSTHROUGH, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R1, JdbcClassification.PASSTHROUGH));
    }

    @Test
    void nonConstantSqlUnderMethodPermissiveAnnotationIsPassthrough() throws Exception {
        MethodJdbcReport report = recognizeOne("DynAnn", prelude() + """
                class DynAnn {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, long customerId) throws SQLException {
                        String sql = "SELECT * FROM orders WHERE customer_id = " + customerId;
                        PreparedStatement ps = c.prepareStatement(sql);
                        ps.executeQuery();
                    }
                }
                """, SqlSafetyMode.STRICT);
        assertEquals(JdbcClassification.PASSTHROUGH, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R1, JdbcClassification.PASSTHROUGH));
    }

    @Test
    void identifierSpliceUnderStrictGetsIdentifierSpecificDiagnostic() throws Exception {
        MethodJdbcReport report = recognizeOne("IdSplice", prelude() + """
                class IdSplice {
                    @StoredProcedure
                    public static void run(Connection c, String tableName) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT * FROM " + tableName);
                        ps.executeQuery();
                    }
                }
                """, SqlSafetyMode.STRICT);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        JdbcUsage usage = firstUsage(report, JdbcIdiom.I_R1);
        assertEquals(TitanErrorCode.E004, usage.code());
        assertTrue(usage.reason().contains("format('%I'"), usage.reason());
    }

    // --- §3.6 placeholder carve-out FAIL-SAFE: a value splice must NOT be accepted ------------

    @Test
    void valueSpliceIsNeverAcceptedAsPlaceholderRun() throws Exception {
        // "... IN (" + customerId + ")" is a VALUE splice, not a ?-run: must be E004, never TRANSPILABLE.
        MethodJdbcReport report = recognizeOne("FakeIn", prelude() + """
                class FakeIn {
                    @StoredProcedure
                    public static void run(Connection c, long customerId) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT * FROM t WHERE id IN (" + customerId + ")");
                        ps.executeQuery();
                    }
                }
                """, SqlSafetyMode.STRICT);
        assertEquals(JdbcClassification.REJECTED, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_R1, JdbcClassification.REJECTED));
        assertFalse(hasUsage(report, JdbcIdiom.DYNAMIC_IN_PLACEHOLDER_RUN, JdbcClassification.TRANSPILABLE));
    }

    // --- §6 worked examples (verbatim TRANSPILABLE fixtures) ----------------------------------

    @Test
    void workedExample61ComputeAccountFeeIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Billing61", prelude() + """
                class Billing61 {
                    @StoredProcedure
                    public static void computeAccountFee(Connection c, long accountId) throws SQLException {
                        java.math.BigDecimal balance;
                        String tier;
                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT balance, tier FROM accounts WHERE id = ?")) {
                            ps.setLong(1, accountId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) {
                                    throw new IllegalStateException("account not found");
                                }
                                balance = rs.getBigDecimal("balance");
                                tier = rs.getString("tier");
                            }
                        }
                        java.math.BigDecimal fee = "GOLD".equals(tier)
                                ? balance.multiply(new java.math.BigDecimal("0.001"))
                                : balance.multiply(new java.math.BigDecimal("0.002"));
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE accounts SET fee = ? WHERE id = ?")) {
                            up.setBigDecimal(1, fee);
                            up.setLong(2, accountId);
                            up.executeUpdate();
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup(),
                "§6.1 worked example must be fully TRANSPILABLE; usages=" + report.usages());
        assertTrue(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.TRANSPILABLE));
        assertTrue(hasUsage(report, JdbcIdiom.I_6, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void workedExample62TotalOverdueIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Billing62", prelude() + """
                class Billing62 {
                    @StoredFunction
                    public static java.math.BigDecimal totalOverdue(Connection c, long customerId) throws SQLException {
                        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                        PreparedStatement ps = c.prepareStatement(
                            "SELECT amount FROM invoices WHERE customer_id = ? AND status = 'OVERDUE'");
                        ps.setLong(1, customerId);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            total = total.add(rs.getBigDecimal("amount"));
                        }
                        return total;
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup(),
                "§6.2 worked example must be fully TRANSPILABLE; usages=" + report.usages());
        assertTrue(hasUsage(report, JdbcIdiom.I_5, JdbcClassification.TRANSPILABLE));
    }

    @Test
    void workedExample63CreateInvoiceIsTranspilable() throws Exception {
        // §6.3's generated-key recovery (I-7) is TRANSPILABLE: the recognizer parity-accepts the canonical
        // RETURN_GENERATED_KEYS + single getLong(1) shape, which the 2b lowerer lowers to the trigger-
        // immune driver-faithful form (PostgreSQL INSERT ... RETURNING <col> INTO with the key column
        // resolved from the Catalog; MySQL SET v = LAST_INSERT_ID()).
        MethodJdbcReport report = recognizeOne("Billing63", prelude() + """
                class Billing63 {
                    @StoredFunction
                    public static long createInvoice(Connection c, long customerId, java.math.BigDecimal amount) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id, amount) VALUES (?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, customerId);
                        ps.setBigDecimal(2, amount);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(1);
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup(),
                "§6.3 worked example must be fully TRANSPILABLE; usages=" + report.usages());
        assertTrue(hasUsage(report, JdbcIdiom.I_7, JdbcClassification.TRANSPILABLE));
        // The generated-keys trailer's bare `keys.next();` is part of I-7, NOT a rejected bare-next.
        assertFalse(hasUsage(report, JdbcIdiom.I_4, JdbcClassification.REJECTED));
    }

    // --- I-9 try-with-resources / catch(SQLException) -----------------------------------------

    @Test
    void i9TryWithResourcesAndCatchSqlExceptionIsTranspilable() throws Exception {
        MethodJdbcReport report = recognizeOne("Tw", prelude() + """
                class Tw {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        try (PreparedStatement ps = c.prepareStatement("SELECT a FROM t WHERE id = ?")) {
                            ps.setLong(1, id);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    String a = rs.getString("a");
                                }
                            }
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_9, JdbcClassification.TRANSPILABLE),
                "the try-with-resources / catch(SQLException) shape must record an I-9 usage; usages="
                        + report.usages());
    }

    // --- I-1 elidable Connection/DataSource parameter (pipeline concern — recognizer must not reject) ---

    @Test
    void connectionAndDataSourceParametersDoNotCauseAReject() throws Exception {
        // §2.2: a Connection/DataSource parameter is elided by the pipeline, not the recognizer. The
        // recognition pass must classify such a method cleanly (no spurious I-R4 escape reject).
        MethodJdbcReport report = recognizeOne("Params", prelude()
                + "import javax.sql.DataSource;\n" + """
                class Params {
                    @StoredProcedure
                    public static void run(Connection c, DataSource ds, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE t SET a = 1 WHERE id = ?");
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                """);
        assertEquals(JdbcClassification.TRANSPILABLE, report.rollup());
        assertFalse(hasUsage(report, JdbcIdiom.I_R4, JdbcClassification.REJECTED));
    }

    // --- unresolved-SQL execute (Finding 6: not a clean TRANSPILABLE) -------------------------

    @Test
    void executeOverHandleWithUnresolvableSqlIsUnresolvedPassthroughNotCleanTranspilable() throws Exception {
        // A prepared handle whose SQL comes from a ternary cannot be resolved to a constant at the
        // executeQuery() call site; it must be recorded as PASSTHROUGH (unresolved SQL), not clean
        // TRANSPILABLE — a measurement tool must not claim SQL it never proved.
        MethodJdbcReport report = recognizeOne("Unresolved", prelude() + """
                class Unresolved {
                    @StoredProcedure
                    public static void run(Connection c, boolean flag) throws SQLException {
                        PreparedStatement ps = flag
                            ? c.prepareStatement("SELECT a FROM t")
                            : c.prepareStatement("SELECT b FROM t");
                        ps.executeQuery();
                    }
                }
                """);
        assertEquals(JdbcClassification.PASSTHROUGH, report.rollup());
        assertTrue(hasUsage(report, JdbcIdiom.I_3, JdbcClassification.PASSTHROUGH),
                "an execute over a handle with unresolved SQL must be PASSTHROUGH; usages=" + report.usages());
        assertFalse(hasUsage(report, JdbcIdiom.I_3, JdbcClassification.TRANSPILABLE));
    }

    // --- I-3 reconciliation (single-dialect-native reframe; Phase 2 emits result-shaped reads) ----

    @Test
    void i3ConstantResultShapedReadIsTranspilable() throws Exception {
        // Phase 2 emits result-shaped reads over constant SQL natively single-dialect, so the I-3
        // executeQuery() over constant SQL is TRANSPILABLE (the I-3 REVISIT flag is reconciled).
        MethodJdbcReport report = recognizeOne("ConstRead", prelude() + """
                class ConstRead {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        }
                    }
                }
                """);
        assertTrue(hasUsage(report, JdbcIdiom.I_3, JdbcClassification.TRANSPILABLE));
        assertFalse(hasUsage(report, JdbcIdiom.I_3, JdbcClassification.REJECTED));
    }

    @Test
    void i3PermissiveNonConstantResultShapedReadIsPassthroughNotRejected() throws Exception {
        // Reconciliation of the Phase-1 I-3 REVISIT flag: a result-shaped read (executeQuery) over
        // NON-constant SQL in a permissive scope is now PASSTHROUGH (Phase 2 emits it natively
        // single-dialect), NOT the old REJECTED-E001 stance. A bare executeQuery() (no rs.next())
        // exercises the gate directly without the I-4/I-5 shape recognition.
        MethodJdbcReport report = recognizeOne("PermRead", prelude() + """
                class PermRead {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, long customerId) throws SQLException {
                        String sql = "SELECT amount FROM invoices WHERE customer_id = " + customerId;
                        PreparedStatement ps = c.prepareStatement(sql);
                        ResultSet rs = ps.executeQuery();
                    }
                }
                """, SqlSafetyMode.STRICT);
        assertEquals(JdbcClassification.PASSTHROUGH, report.rollup());
        assertFalse(report.usages().stream()
                        .anyMatch(u -> u.classification() == JdbcClassification.REJECTED),
                "a permissive non-constant result-shaped read must not be REJECTED (Phase 2 reconciled); usages="
                        + report.usages());
    }
}
