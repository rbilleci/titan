package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.introspect.SchemaModel;
import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 2 (2b) JDBC → TIR lowering: source string → parse → lower → assert TIR shape, for the
 * native single-dialect read/cursor/execute/transaction nodes (I-4/I-5/I-6/I-10) and the Connection
 * signature drop. Mirrors {@code JavaToTirLowererTest}'s harness.
 */
class JdbcLoweringTest {

    @TempDir
    Path tempDir;

    private static String prelude() {
        return "import titan.dsl.*;\nimport java.sql.*;\nimport java.math.BigDecimal;\n\n";
    }

    private ParsedSources parse(String className, String source) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, prelude() + source);
        return new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
    }

    private Block lowerOne(String className, String source) throws Exception {
        ParsedSources parsed = parse(className, source);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, entryPoints);
        assertEquals(1, lowered.size(), "expected exactly one lowered entry point");
        return lowered.values().iterator().next();
    }

    private record LowerResult(Map<String, Block> lowered, DiagnosticSink sink) { }

    private LowerResult lowerCollecting(String className, String source) throws Exception {
        ParsedSources parsed = parse(className, source);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, entryPoints, sink);
        return new LowerResult(lowered, sink);
    }

    /** Lowers with an explicit build-level safety default and target-dialect set (Phase 2b gate). */
    private LowerResult lowerCollecting(
            String className,
            String source,
            io.titan.transpiler.jdbc.SqlSafetyMode safetyDefault,
            List<DialectId> targetDialects
    ) throws Exception {
        return lowerCollecting(className, source, safetyDefault, targetDialects, null);
    }

    /** Lowers additionally threading the introspected catalog (§6.3 / I-7 key-column resolution). */
    private LowerResult lowerCollecting(
            String className,
            String source,
            io.titan.transpiler.jdbc.SqlSafetyMode safetyDefault,
            List<DialectId> targetDialects,
            SchemaModel schemaModel
    ) throws Exception {
        ParsedSources parsed = parse(className, source);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer()
                .lower(parsed, entryPoints, sink, false, safetyDefault, targetDialects, schemaModel);
        return new LowerResult(lowered, sink);
    }

    /** A catalog with an {@code invoices(id auto-increment, customer_id, amount)} table in {@code billing}. */
    private static SchemaModel invoicesModel() {
        SchemaModel.ColumnMeta id = new SchemaModel.ColumnMeta(
                "id", "BIGINT", false, null, null, null, null, null, List.of(), true);
        SchemaModel.ColumnMeta customerId = new SchemaModel.ColumnMeta(
                "customer_id", "BIGINT", false, null, null, null);
        SchemaModel.ColumnMeta amount = new SchemaModel.ColumnMeta(
                "amount", "NUMERIC", false, 38, 10, null);
        SchemaModel.TableMeta invoices = new SchemaModel.TableMeta(
                "billing", "invoices", List.of(id, customerId, amount));
        return new SchemaModel(List.of(invoices));
    }

    private static <T> T findFirst(Block block, Class<T> type) {
        return block.statements().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    // ---- I-4 single-row read ----------------------------------------------------------------

    @Test
    void i4ThenBlockReadLowersToRawReadInto() throws Exception {
        Block block = lowerOne("ThenRead", """
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
        // The Connection/PreparedStatement/ResultSet handles are elided; the read becomes RawReadInto.
        RawReadIntoStatement read = findFirst(block, RawReadIntoStatement.class);
        assertEquals(List.of("tier"), read.variableNames());
        assertEquals("SELECT tier FROM accounts WHERE id = ?", read.query().sql());
        assertEquals(List.of("id"), read.query().parameters());
        // The read local is DECLAREd; no handle DECLAREs leak.
        assertTrue(block.declarations().stream().anyMatch(d -> d instanceof DeclareVariable dv && dv.name().equals("tier")));
    }

    @Test
    void atg001DirectReturnOfColumnReadIsRejectedNotEmptyInto() throws Exception {
        // ATG-001: `if (rs.next()) return rs.getLong(col); return 0L;` previously emitted an invalid empty
        // `INTO` (won't deploy). It must now be rejected with an actionable assign-then-return rewrite, and
        // must NOT lower to a RawReadIntoStatement with no targets.
        LowerResult result = lowerCollecting("ReturnCount", """
                class ReturnCount {
                    @StoredFunction
                    public static long run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n FROM accounts WHERE owner_id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            return rs.getLong("n");
                        }
                        return 0L;
                    }
                }
                """);
        assertFalse(result.sink().errors().isEmpty(), "the direct-return read shape must be rejected");
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.message().contains("return rs.getX(col)")
                                && d.message().contains("Assign it to a local")),
                "the reject must name the shape and the assign-then-return rewrite; errors=" + result.sink().errors());
        // The broken empty-INTO read must NOT be produced.
        assertTrue(result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .noneMatch(s -> s instanceof RawReadIntoStatement r && r.variableNames().isEmpty()),
                "no empty-INTO RawReadIntoStatement may be emitted");
    }

    @Test
    void atg001AssignThenReturnCountLowersCleanly() throws Exception {
        // The supported rewrite the diagnostic points to: assign into a local inside if (rs.next()), then
        // return the local. Targets PostgreSQL (a dynamic single-row read in a @StoredFunction is ERROR
        // 1336 on MySQL — an orthogonal, pre-existing dialect constraint). It must lower cleanly to a
        // RawReadInto targeting the local.
        LowerResult result = lowerCollecting("CleanCount", """
                class CleanCount {
                    @StoredFunction
                    public static long run(Connection c, long id) throws SQLException {
                        long n = 0L;
                        PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n FROM accounts WHERE owner_id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            n = rs.getLong("n");
                        }
                        return n;
                    }
                }
                """,
                io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL));
        assertTrue(result.sink().errors().isEmpty(),
                "assign-then-return must lower cleanly on PG; errors=" + result.sink().errors());
        RawReadIntoStatement read = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .filter(RawReadIntoStatement.class::isInstance).map(RawReadIntoStatement.class::cast)
                .findFirst().orElseThrow();
        assertEquals(List.of("n"), read.variableNames());
    }

    @Test
    void i4GuardThrowReadLowersToRaiseThenRawReadInto() throws Exception {
        Block block = lowerOne("GuardRead", """
                class GuardRead {
                    @StoredProcedure
                    public static void run(Connection c, long accountId) throws SQLException {
                        BigDecimal balance;
                        String tier;
                        PreparedStatement ps = c.prepareStatement("SELECT balance, tier FROM accounts WHERE id = ?");
                        ps.setLong(1, accountId);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            throw new IllegalStateException("account not found");
                        }
                        balance = rs.getBigDecimal("balance");
                        tier = rs.getString("tier");
                    }
                }
                """);
        // The read carries the §6.1 not-found guard on the node as `notFoundRaise` — emitted FOUND-based
        // (PG IF NOT FOUND / MySQL NOT FOUND handler flag), NOT as a "first INTO target IS NULL" proxy:
        // a legitimately NULL first column on an existing row must not falsely raise (WS-C Phase 2b
        // audit). There is therefore no separate IsNullExpression IfStatement guard statement.
        RawReadIntoStatement read = findFirst(block, RawReadIntoStatement.class);
        assertEquals(List.of("balance", "tier"), read.variableNames());
        assertEquals("SELECT balance, tier FROM accounts WHERE id = ?", read.query().sql());
        assertEquals(List.of("accountId"), read.query().parameters());
        RaiseStatement raise = assertInstanceOf(RaiseStatement.class, read.notFoundRaise());
        assertInstanceOf(LiteralExpression.class, raise.message());
        // No value-proxy IsNullExpression guard is emitted as a separate statement.
        assertTrue(block.statements().stream().noneMatch(s -> s instanceof IfStatement ifs
                        && ifs.condition() instanceof IsNullExpression),
                "the no-row guard must be FOUND-based on the node, not a separate IS NULL IfStatement");
    }

    @Test
    void i4GuardThrowReadNullableFirstColumnStillRaisesViaFoundNotValueProxy() throws Exception {
        // Regression (WS-C Phase 2b audit): a nullable first column must NOT be used as the no-row
        // signal. The guard is carried on notFoundRaise (FOUND-based at emission), so an existing row
        // with a NULL first column does not falsely raise — the lowering does not test the column value.
        Block block = lowerOne("NullableGuard", """
                class NullableGuard {
                    @StoredProcedure
                    public static void run(Connection c, long accountId) throws SQLException {
                        String note;
                        PreparedStatement ps = c.prepareStatement("SELECT optional_note FROM accounts WHERE id = ?");
                        ps.setLong(1, accountId);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            throw new IllegalStateException("account not found");
                        }
                        note = rs.getString("optional_note");
                    }
                }
                """);
        RawReadIntoStatement read = findFirst(block, RawReadIntoStatement.class);
        assertEquals(List.of("note"), read.variableNames());
        // The no-row guard rides on the node, FOUND-based — never a value test of the (nullable) column.
        assertInstanceOf(RaiseStatement.class, read.notFoundRaise());
        assertTrue(block.statements().stream().noneMatch(s -> s instanceof IfStatement ifs
                        && ifs.condition() instanceof IsNullExpression),
                "a nullable first column must not become the no-row proxy (would falsely raise on NULL)");
    }

    // ---- I-5 multi-row cursor read ----------------------------------------------------------

    @Test
    void i5WhileLoopLowersToRawCursorWithRowLocalBody() throws Exception {
        Block block = lowerOne("Loop", """
                class Loop {
                    @StoredFunction
                    public static BigDecimal total(Connection c, long customerId) throws SQLException {
                        BigDecimal total = BigDecimal.ZERO;
                        PreparedStatement ps = c.prepareStatement(
                            "SELECT amount FROM invoices WHERE customer_id = ?");
                        ps.setLong(1, customerId);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            total = total.add(rs.getBigDecimal("amount"));
                        }
                        return total;
                    }
                }
                """);
        RawCursorStatement cursor = findFirst(block, RawCursorStatement.class);
        assertEquals("SELECT amount FROM invoices WHERE customer_id = ?", cursor.query().sql());
        assertEquals(List.of("customerId"), cursor.query().parameters());
        // One per-row FETCH target (for `amount`).
        assertEquals(1, cursor.variableNames().size());
        String rowLocal = cursor.variableNames().getFirst();
        // The body's `total = total.add(<row local>)` references the FETCH local, not a getX call.
        Assign bodyAssign = findFirst(cursor.body(), Assign.class);
        String rendered = bodyAssign.expression().toString();
        assertTrue(rendered.contains(rowLocal),
                "loop body must reference the per-row FETCH local " + rowLocal + "; was " + rendered);
        // The FETCH local is DECLAREd on the cursor body.
        assertTrue(cursor.body().declarations().stream()
                .anyMatch(d -> d instanceof DeclareVariable dv && dv.name().equals(rowLocal)));
    }

    // ---- I-6 executeUpdate ------------------------------------------------------------------

    @Test
    void i6ExecuteUpdateLowersToExecuteSqlRawSql() throws Exception {
        Block block = lowerOne("Update", """
                class Update {
                    @StoredProcedure
                    public static void run(Connection c, long id, BigDecimal fee) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET fee = ? WHERE id = ?");
                        ps.setBigDecimal(1, fee);
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class);
        RawSql raw = assertInstanceOf(RawSql.class, exec.sqlNode());
        assertEquals("UPDATE accounts SET fee = ? WHERE id = ?", raw.sql());
        assertEquals(List.of("fee", "id"), raw.parameters());
    }

    @Test
    void i6ExpressionBindSynthesisesBindLocal() throws Exception {
        // A bind that is an expression (not a bare variable) synthesises a __titan_pN := <expr> local.
        Block block = lowerOne("ExprBind", """
                class ExprBind {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE t SET n = ? WHERE id = ?");
                        ps.setLong(1, id + 1);
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                }
                """);
        ExecuteSqlStatement exec = findFirst(block, ExecuteSqlStatement.class);
        RawSql raw = assertInstanceOf(RawSql.class, exec.sqlNode());
        // ordinal 1 is the expression bind -> a synthesised local; ordinal 2 is the bare `id`.
        assertEquals(2, raw.parameters().size());
        String bindLocal = raw.parameters().get(0);
        assertTrue(bindLocal.startsWith("__titan_p"), "expression bind should synthesise a __titan_pN local; was " + bindLocal);
        assertEquals("id", raw.parameters().get(1));
        // The synthesised local is DECLAREd and assigned the lowered expression before the execute.
        assertTrue(block.declarations().stream().anyMatch(d -> d instanceof DeclareVariable dv && dv.name().equals(bindLocal)));
        assertTrue(block.statements().stream().anyMatch(s -> s instanceof Assign a
                && a.target() instanceof VariableRefExpression ref && ref.name().equals(bindLocal)));
    }

    // ---- I-7 generated keys (§6.3) ----------------------------------------------------------

    private static final String CREATE_INVOICE_SOURCE = """
            class CreateInvoice {
                @StoredFunction
                public static long createInvoice(Connection c, long customerId, BigDecimal amount) throws SQLException {
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
            """;

    @Test
    void i7GeneratedKeysIsRejectedNoCatalog_postgres() throws Exception {
        // I-7 is REJECTED (I-R8) on the parser-free JDBC path: the spec's INSERT ... RETURNING <col>
        // needs the key column resolved from the Catalog (the JDBC ordinal 1 does not name it), which
        // is unavailable here. The earlier lastval() emission was wrong (it returns the last *sequence*
        // value — a different row's id under an AFTER INSERT trigger, and raises for a caller-supplied
        // key). The lowerer must reject E001, matching the recognizer (no silent gap), never emit
        // lastval().
        LowerResult result = lowerCollecting(
                "CreateInvoice", CREATE_INVOICE_SOURCE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL));
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001
                                && d.message().contains("resolvable from the Catalog")),
                "I-7 must be rejected E001 (I-R8, no Catalog to resolve the RETURNING column); "
                        + "errors=" + result.sink().errors());
        // The wrong-value session read must NEVER be emitted.
        assertFalse(result.sink().errors().isEmpty(), "I-7 must not lower cleanly");
    }

    @Test
    void i7GeneratedKeysIsRejectedNoCatalog_mysql() throws Exception {
        // Same I-R8 reject on a MySQL target: LAST_INSERT_ID() is equally wrong (last AUTO_INCREMENT
        // value, not the inserted row's resolved key column), so the lowerer rejects rather than emit
        // it.
        LowerResult result = lowerCollecting(
                "CreateInvoice", CREATE_INVOICE_SOURCE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001
                                && d.message().contains("resolvable from the Catalog")),
                "I-7 must be rejected E001 (I-R8, no Catalog) on MySQL too; errors=" + result.sink().errors());
    }

    @Test
    void i7GeneratedKeysResolvesToGeneratedKeyReadWithCatalog() throws Exception {
        // WITH a catalog that knows invoices.id is the single auto-increment column, I-7 lowers to a
        // GeneratedKeyReadStatement carrying that resolved column — NOT a reject, NOT a guessed key.
        LowerResult result = lowerCollecting(
                "CreateInvoice", CREATE_INVOICE_SOURCE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT,
                List.of(DialectId.POSTGRESQL), invoicesModel());
        assertTrue(result.sink().errors().isEmpty(),
                "I-7 must lower cleanly when the key column resolves from the Catalog; errors=" + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        GeneratedKeyReadStatement genKey = findFirst(block, GeneratedKeyReadStatement.class);
        assertEquals("id", genKey.keyColumn(), "the resolved key column must be the auto-increment id");
        assertEquals("INSERT INTO invoices (customer_id, amount) VALUES (?, ?)", genKey.insert().sql());
        assertEquals(List.of("customerId", "amount"), genKey.insert().parameters(),
                "the INSERT carries its positional bind params in order");
        // The trailing `return keys.getLong(1)` resolves to the gen-key local (no crash, no reject).
        assertTrue(block.statements().stream().anyMatch(s -> s instanceof ReturnStatement r
                        && r.expression() instanceof VariableRefExpression ref
                        && ref.name().equals(genKey.keyLocal())),
                "return keys.getLong(1) must resolve to the recovered key local " + genKey.keyLocal());
    }

    @Test
    void i7UnknownTableRejectsEvenWithCatalog() throws Exception {
        // A catalog that does NOT contain the inserted table cannot resolve the key column: reject
        // cleanly (I-R8), never guess. (invoicesModel knows only `invoices`; this inserts into `orders`.)
        LowerResult result = lowerCollecting("CreateOrder", """
                class CreateOrder {
                    @StoredFunction
                    public static long createOrder(Connection c, long customerId) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO orders (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, customerId);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(1);
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL), invoicesModel());
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001
                                && d.message().contains("resolvable from the Catalog")),
                "an INSERT into a table not in the catalog must reject I-R8, never guess; errors="
                        + result.sink().errors());
        assertTrue(result.lowered().isEmpty() || result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .noneMatch(GeneratedKeyReadStatement.class::isInstance),
                "an unresolvable table must not lower to a GeneratedKeyReadStatement");
    }

    @Test
    void i7NonSingleIntKeyReadIsRejectedNotSilentlyDropped() throws Exception {
        // The reconciliation arbiter: a getGeneratedKeys() read that is NOT a single getLong(1)/getInt(1)
        // — here getString(1) — must be a hard E001 (I-R8), NEVER a silent recognizer-transpilable /
        // lowerer-no-op gap. The lowerer rejects exactly the shapes the recognizer rejects.
        LowerResult result = lowerCollecting("BadKey", """
                class BadKey {
                    @StoredFunction
                    public static String createInvoice(Connection c, long customerId) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, customerId);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getString(1);
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL));
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001
                                && d.message().contains("single auto-increment/identity integer key")),
                "a non-single-int generated-key read must be rejected E001 (I-R8), not silently dropped; "
                        + "errors=" + result.sink().errors());
    }

    @Test
    void i7NonOneOrdinalKeyReadIsRejected() throws Exception {
        // getLong(2) — a non-1 ordinal — is also I-R8 (the JDBC ordinal 1 is the single generated key).
        LowerResult result = lowerCollecting("BadOrdinal", """
                class BadOrdinal {
                    @StoredFunction
                    public static long createInvoice(Connection c, long customerId) throws SQLException {
                        PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO invoices (customer_id) VALUES (?)",
                            Statement.RETURN_GENERATED_KEYS);
                        ps.setLong(1, customerId);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        keys.next();
                        return keys.getLong(2);
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL));
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001
                                && d.message().contains("single auto-increment/identity integer key")),
                "a non-1 ordinal generated-key read must be rejected E001 (I-R8); errors=" + result.sink().errors());
    }

    // ---- I-10 transactions ------------------------------------------------------------------

    @Test
    void i10CommitInProcedureLowersToTransactionControlWithW005() throws Exception {
        LowerResult result = lowerCollecting("Commit", """
                class Commit {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.commit();
                    }
                }
                """);
        Block block = result.lowered().values().iterator().next();
        TransactionControlStatement txn = findFirst(block, TransactionControlStatement.class);
        assertEquals(TransactionAction.COMMIT, txn.action());
        // The mandatory outer-atomic W005 is on the sink.
        assertTrue(result.sink().warnings().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.W005 && d.message().contains("commit()")),
                "expected mandatory W005 for commit() in a procedure; warnings=" + result.sink().warnings());
    }

    @Test
    void i10RollbackInProcedureLowersToTransactionControl() throws Exception {
        Block block = lowerOne("Rollback", """
                class Rollback {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.rollback();
                    }
                }
                """);
        TransactionControlStatement txn = findFirst(block, TransactionControlStatement.class);
        assertEquals(TransactionAction.ROLLBACK, txn.action());
    }

    @Test
    void i10CommitInFunctionIsHardRejectedE001() throws Exception {
        LowerResult result = lowerCollecting("CommitFn", """
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
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001 && d.message().contains("transaction control")),
                "expected hard E001 for commit() in a function; errors=" + result.sink().errors());
    }

    @Test
    void i10CommitInScheduledJobLowersToTransactionControlNotRejected() throws Exception {
        // A @ScheduledJob is emitted as a backing PROCEDURE on both dialects (PG procedure + pg_cron,
        // MySQL procedure + EVENT), so commit()/rollback() is legal there. It must NOT be rejected as
        // "inside a function/trigger" (the storedProcedure-proxy bug, the same class the §9 dynamic-SQL
        // gate had) — it lowers to a real COMMIT plus the mandatory outer-atomic W005.
        LowerResult result = lowerCollecting("CommitJob", """
                class CommitJob {
                    @ScheduledJob(cron = "0 4 * * *")
                    public static void run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                        c.commit();
                    }
                }
                """);
        Block block = result.lowered().values().iterator().next();
        TransactionControlStatement txn = findFirst(block, TransactionControlStatement.class);
        assertEquals(TransactionAction.COMMIT, txn.action());
        assertTrue(result.sink().errors().isEmpty(),
                "a @ScheduledJob commit() must NOT be rejected; errors=" + result.sink().errors());
        assertTrue(result.sink().warnings().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.W005 && d.message().contains("@ScheduledJob")),
                "expected the outer-atomic W005 naming @ScheduledJob; warnings=" + result.sink().warnings());
    }

    @Test
    void i10SetAutoCommitIsElidedWithNoTransactionControl() throws Exception {
        LowerResult result = lowerCollecting("Auto", """
                class Auto {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        c.setAutoCommit(false);
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE a SET x = 1");
                    }
                }
                """);
        Block block = result.lowered().values().iterator().next();
        // setAutoCommit is elided (no TransactionControl node), with an informational W005.
        assertFalse(block.statements().stream().anyMatch(TransactionControlStatement.class::isInstance));
        assertTrue(result.sink().warnings().stream()
                .anyMatch(d -> d.code() == TitanErrorCode.W005 && d.message().contains("setAutoCommit")));
    }

    // ---- recognizer/lowerer divergence + reject parity (WS-C Phase 2b audit) ----------------

    @Test
    void batchIsRejectedNotSilentlyElidedToEmptyBody() throws Exception {
        // The recognizer rejects JDBC batch with a BATCH E001; the lowerer must mirror it instead of
        // eliding addBatch/executeBatch into an empty (no-op) routine body.
        LowerResult result = lowerCollecting("Batch", """
                class Batch {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                        ps.setLong(1, id);
                        ps.addBatch();
                        ps.executeBatch();
                    }
                }
                """);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001 && d.message().contains("batch")),
                "expected a BATCH E001 reject; errors=" + result.sink().errors());
    }

    @Test
    void nestedCursorIsRejectedWithActionableMessageNotTypeMappingCrash() throws Exception {
        // A second ResultSet opened inside while (rs.next()) is rejected by the recognizer; the lowerer
        // must reject with the SAME actionable message, not crash on the inner PreparedStatement's type.
        LowerResult result = lowerCollecting("Nested", """
                class Nested {
                    @StoredProcedure
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement outer = c.prepareStatement("SELECT id FROM customers");
                        ResultSet rs = outer.executeQuery();
                        while (rs.next()) {
                            long id = rs.getLong("id");
                            PreparedStatement inner = c.prepareStatement("SELECT total FROM orders WHERE customer_id = ?");
                            inner.setLong(1, id);
                            ResultSet rs2 = inner.executeQuery();
                        }
                    }
                }
                """);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001 && d.message().contains("second ResultSet")),
                "expected the nested-cursor E001 (not a type-mapping crash); errors=" + result.sink().errors());
        assertTrue(result.sink().errors().stream()
                        .noneMatch(d -> d.message().contains("No SQL type mapping")),
                "must not surface the misleading PreparedStatement type-mapping crash; errors=" + result.sink().errors());
    }

    @Test
    void rejectedSingleRowShapeRaisesRecognizerE001NotRsNextLoweringCrash() throws Exception {
        // if (rs.next()) { ... } else { ... } is a REJECTED single-row shape: the lowerer must raise the
        // recognizer's I-4 E001, not delegate an un-lowerable rs.next() to the stock path (which crashes
        // with 'Method next on ... ResultSet has no SQL lowering').
        LowerResult result = lowerCollecting("ElseBranch", """
                class ElseBranch {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        String tier;
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            tier = rs.getString("tier");
                        } else {
                            tier = "none";
                        }
                    }
                }
                """);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001 && d.message().contains("single-row ResultSet shape")),
                "expected the I-4 single-row-shape E001; errors=" + result.sink().errors());
        assertTrue(result.sink().errors().stream()
                        .noneMatch(d -> d.message().contains("has no SQL lowering")),
                "must not surface the misleading rs.next() lowering crash; errors=" + result.sink().errors());
    }

    // ---- sqlSafety gate enforced IN the lowering pipeline (WS-C Phase 2b security audit) -----

    @Test
    void strictValueSpliceIsRejectedWithE004NotEmptySql() throws Exception {
        // STRICT (the default) value splice into prepareStatement SQL: a hard E004 (not an EXECUTE '').
        LowerResult result = lowerCollecting("ValueSplice", """
                class ValueSplice {
                    @StoredProcedure
                    public static void run(Connection c, String name) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged=1 WHERE name='" + name + "'");
                        ps.executeUpdate();
                    }
                }
                """);
        // The build FAILS with the three-part E004 (vs the audited bug: NO E004, silent EXECUTE '').
        // The build-failing error is the security guarantee; the discarded TIR is never emitted.
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004
                                && d.message().contains("SQL injection risk")
                                && d.message().contains("(a)") && d.message().contains("(b)") && d.message().contains("(c)")),
                "expected the three-part E004 strict-splice diagnostic; errors=" + result.sink().errors());
    }

    @Test
    void strictIdentifierSpliceIsRejectedWithE004IdentifierVariant() throws Exception {
        // STRICT identifier splice (table name): E004 with the identifier-specific safe-alternative.
        LowerResult result = lowerCollecting("IdSplice", """
                class IdSplice {
                    @StoredProcedure
                    public static void run(Connection c, String tableName) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT * FROM " + tableName);
                        ps.executeQuery();
                    }
                }
                """);
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E004 && d.message().contains("format('%I'")),
                "expected the identifier-variant E004 diagnostic; errors=" + result.sink().errors());
    }

    @Test
    void permissiveRawFragmentSpliceEmitsVerbatimNotEmptySql() throws Exception {
        // WS-C Phase 3 Rung 3: a PERMISSIVE method's raw-fragment splice (the value is concatenated INSIDE
        // an open single-quoted literal — name='" + name + "', so it is NOT a bindable value) now EMITS,
        // assembling the SQL text at runtime — never the audited silent EXECUTE '' and never the Rung-0/1
        // E001 deferral. The fragment is spliced verbatim (RAW_FRAGMENT), faithfully reproducing the
        // source's own exposure (the migration on-ramp the permissive mode exists for).
        LowerResult result = lowerCollecting("PermSplice", """
                class PermSplice {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, String name) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged=1 WHERE name='" + name + "'");
                        ps.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, null);
        assertTrue(result.sink().errors().isEmpty(),
                "a permissive raw-fragment splice must lower cleanly (it emits); errors=" + result.sink().errors());
        RawSql raw = result.lowered().values().stream()
                .flatMap(b -> b.statements().stream())
                .filter(ExecuteSqlStatement.class::isInstance).map(ExecuteSqlStatement.class::cast)
                .map(ExecuteSqlStatement::sqlNode)
                .filter(RawSql.class::isInstance).map(RawSql.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("expected an ExecuteSqlStatement(RawSql)"));
        assertEquals(1, raw.spliceBinds().size(), "exactly one splice for the in-quote fragment");
        SpliceBind splice = raw.spliceBinds().getFirst();
        assertEquals(SpliceBind.Kind.RAW_FRAGMENT, splice.kind(),
                "an open-quote splice is RAW_FRAGMENT (not a bindable value)");
        assertEquals("name", splice.paramName(), "the spliced routine-local is the `name` parameter");
        assertTrue(raw.sql().contains(RawSql.spliceMarker(splice.markerId())),
                "the recovered text carries the splice marker where the fragment goes");
        // The text is NOT silently empty (the audited bug), and the marker carries no `?` (structural).
        assertFalse(raw.sql().isBlank(), "the recovered text must not be empty");
    }

    @Test
    void mysqlNonConstantCursorIsRejectedE001UnderPermissive() throws Exception {
        // Decision 1: a permissive non-constant multi-row read targeting MySQL is a hard E001 (a MySQL
        // static cursor cannot iterate runtime-built text), never an invalid empty-text static cursor.
        LowerResult result = lowerCollecting("MysqlCursor", """
                class MysqlCursor {
                    @StoredProcedure
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    public static void run(Connection c, String col) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT " + col + " FROM invoices");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            long v = rs.getLong(1);
                        }
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.code() == TitanErrorCode.E001 && d.message().contains("MySQL multi-row read")),
                "expected the decision-1 MySQL non-constant cursor E001; errors=" + result.sink().errors());
        // No empty RawCursor was emitted.
        assertTrue(result.lowered().isEmpty() || result.lowered().values().stream()
                        .flatMap(b -> b.statements().stream())
                        .noneMatch(RawCursorStatement.class::isInstance),
                "a rejected MySQL non-constant cursor must not emit a (broken) RawCursorStatement");
    }

    // ---- §9 invariant 1: MySQL @StoredFunction dynamic SQL (PREPARE/EXECUTE) reject (ERROR 1336) ----
    // MySQL forbids dynamic SQL inside a stored FUNCTION, so a JDBC read/execute (which lowers to a
    // dynamic PREPARE/EXECUTE on MySQL) in a MySQL @StoredFunction emits non-deployable SQL. The lowerer
    // must REJECT E001 with the actionable "annotate @StoredProcedure" steer — never silently emit it.
    // PRECISE: reject exactly a MySQL @StoredFunction with a dynamic-PREPARE node, and NOTHING else (no
    // false-reject of @StoredProcedure, of PostgreSQL functions, of the static cursor / generated-key
    // INSERT / unknown-shape carrier, all of which emit no dynamic PREPARE in a function).

    /** A substring unique to the §9-invariant-1 dynamic-SQL-in-function reject diagnostic. */
    private static boolean isMysqlFunctionDynamicSqlReject(TitanDiagnostic d) {
        return d.code() == TitanErrorCode.E001
                && d.message().contains("dynamic SQL (PREPARE/EXECUTE) inside a stored FUNCTION")
                && d.message().contains("@StoredProcedure");
    }

    @Test
    void mysqlStoredFunctionSingleRowReadIsRejectedE001() throws Exception {
        // A single-row read (RawReadIntoStatement -> dynamic PREPARE/EXECUTE … INTO on MySQL) inside a
        // MySQL @StoredFunction must REJECT with the ERROR-1336 diagnostic, not emit non-deployable SQL.
        LowerResult result = lowerCollecting("FnRead", """
                class FnRead {
                    @StoredFunction
                    public static String run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                            return tier;
                        }
                        return null;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction single-row read must be rejected E001 (ERROR 1336); errors="
                        + result.sink().errors());
        // The reject names ERROR 1336 so the steer is unambiguous.
        assertTrue(result.sink().errors().stream()
                        .anyMatch(d -> d.message().contains("ERROR 1336")),
                "the diagnostic must cite MySQL ERROR 1336; errors=" + result.sink().errors());
    }

    @Test
    void mysqlStoredFunctionGuardThrowReadIsRejectedE001() throws Exception {
        // The other single-row shape (the if (!rs.next()) throw guard, also a RawReadIntoStatement) is
        // equally a dynamic read on MySQL — it must reject too (completeness: every dynamic node caught).
        LowerResult result = lowerCollecting("FnGuard", """
                class FnGuard {
                    @StoredFunction
                    public static String run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            throw new SQLException("not found");
                        }
                        String tier = rs.getString("tier");
                        return tier;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction guard-throw single-row read must be rejected E001; errors="
                        + result.sink().errors());
    }

    @Test
    void mysqlStoredFunctionExecuteUpdateIsRejectedE001() throws Exception {
        // An executeUpdate (ExecuteSqlStatement -> dynamic PREPARE/EXECUTE on MySQL) inside a MySQL
        // @StoredFunction must REJECT (a function can legitimately do DML and return a value).
        LowerResult result = lowerCollecting("FnUpdate", """
                class FnUpdate {
                    @StoredFunction
                    public static int run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                        ps.setLong(1, id);
                        ps.executeUpdate();
                        return 1;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction executeUpdate must be rejected E001 (ERROR 1336); errors="
                        + result.sink().errors());
    }

    @Test
    void mysqlStoredFunctionPlainStatementExecuteIsRejectedE001() throws Exception {
        // The I-8 plain-Statement execute path (also a dynamic ExecuteSqlStatement) must reject too.
        LowerResult result = lowerCollecting("FnPlainExec", """
                class FnPlainExec {
                    @StoredFunction
                    public static int run(Connection c) throws SQLException {
                        Statement st = c.createStatement();
                        st.executeUpdate("UPDATE accounts SET flagged = 1 WHERE id = 7");
                        return 1;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction plain-Statement execute must be rejected E001; errors="
                        + result.sink().errors());
    }

    @Test
    void mysqlStoredFunctionReportsTheRejectOncePerMethod() throws Exception {
        // The once-guard: a function with SEVERAL dynamic statements surfaces the SINGLE actionable
        // diagnostic, not one E001 per statement (the diagnostic is the steer, not a per-node spam).
        LowerResult result = lowerCollecting("FnMany", """
                class FnMany {
                    @StoredFunction
                    public static int run(Connection c, long id) throws SQLException {
                        PreparedStatement u = c.prepareStatement("UPDATE accounts SET a = 1 WHERE id = ?");
                        u.setLong(1, id);
                        u.executeUpdate();
                        PreparedStatement v = c.prepareStatement("UPDATE accounts SET b = 2 WHERE id = ?");
                        v.setLong(1, id);
                        v.executeUpdate();
                        return 1;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        long count = result.sink().errors().stream().filter(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject).count();
        assertEquals(1, count, "the dynamic-SQL-in-function reject must fire exactly once per method; errors="
                + result.sink().errors());
    }

    @Test
    void mysqlStoredProcedureSameReadAndExecuteIsNotRejected() throws Exception {
        // A @StoredProcedure on MySQL allows dynamic SQL — the SAME read+execute must lower cleanly (no
        // false-reject). This is the positive path the deploy IT covers.
        LowerResult result = lowerCollecting("ProcReadExec", """
                class ProcReadExec {
                    @StoredProcedure
                    public static void run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        }
                        PreparedStatement up = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                        up.setLong(1, id);
                        up.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredProcedure read/execute must NOT trip the function-only reject; errors="
                        + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL @StoredProcedure read/execute must lower cleanly; errors=" + result.sink().errors());
    }

    @Test
    void postgresStoredFunctionReadIsNotRejected() throws Exception {
        // PostgreSQL functions ALLOW dynamic EXECUTE — the same @StoredFunction read that MySQL rejects
        // must lower cleanly when MySQL is NOT a target (PG-only). This is the precision boundary: the
        // gate is dialect-conditional, never a blanket @StoredFunction reject.
        LowerResult result = lowerCollecting("PgFnRead", """
                class PgFnRead {
                    @StoredFunction
                    public static String run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                            return tier;
                        }
                        return null;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.POSTGRESQL));
        assertTrue(result.sink().errors().isEmpty(),
                "a PostgreSQL @StoredFunction read must lower cleanly (PG allows dynamic EXECUTE); errors="
                        + result.sink().errors());
        // It really did lower to the dynamic read node (not silently dropped).
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(RawReadIntoStatement.class::isInstance),
                "the PG @StoredFunction read must still lower to a RawReadIntoStatement");
    }

    @Test
    void mysqlStoredFunctionStaticCursorIsNotRejected() throws Exception {
        // I-5 while (rs.next()) over CONSTANT SQL lowers to a STATIC `DECLARE … CURSOR FOR <text>` on
        // MySQL (no PREPARE) — legal inside a FUNCTION. Gating it would be a FALSE-REJECT. (Mirrors the
        // i5WhileLoopLowersToRawCursorWithRowLocalBody @StoredFunction shape, on a MySQL target.)
        LowerResult result = lowerCollecting("FnCursor", """
                class FnCursor {
                    @StoredFunction
                    public static BigDecimal total(Connection c, long customerId) throws SQLException {
                        BigDecimal total = BigDecimal.ZERO;
                        PreparedStatement ps = c.prepareStatement(
                            "SELECT amount FROM invoices WHERE customer_id = ?");
                        ps.setLong(1, customerId);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            total = total.add(rs.getBigDecimal("amount"));
                        }
                        return total;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction STATIC cursor must NOT trip the dynamic-SQL reject; errors="
                        + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL @StoredFunction static cursor must lower cleanly; errors=" + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(RawCursorStatement.class::isInstance),
                "the static cursor must still lower to a RawCursorStatement");
    }

    @Test
    void mysqlStoredFunctionGeneratedKeyInsertIsNotRejected() throws Exception {
        // I-7 with a Catalog resolves to a GeneratedKeyReadStatement, which MySQL emits as a STATIC
        // INSERT (not a dynamic PREPARE — MySqlEmitter.visitGeneratedKeyReadStatement) — legal inside a
        // FUNCTION. Gating it would be a FALSE-REJECT. (Use invoicesModel() so I-7 resolves cleanly; the
        // method is @StoredFunction returning the generated key, exactly the §6.3 shape.)
        LowerResult result = lowerCollecting(
                "CreateInvoice", CREATE_INVOICE_SOURCE, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT,
                List.of(DialectId.MYSQL), invoicesModel());
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @StoredFunction static generated-key INSERT must NOT trip the dynamic-SQL reject; "
                        + "errors=" + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL @StoredFunction generated-key INSERT must lower cleanly with a Catalog; errors="
                        + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(GeneratedKeyReadStatement.class::isInstance),
                "I-7 with a Catalog must lower to a (static) GeneratedKeyReadStatement, not a dynamic INSERT");
    }

    @Test
    void mysqlStoredFunctionUnknownShapeCarrierIsNotRejected() throws Exception {
        // The Rung-5 unknown-shape carrier (a pure metadata-driven List<Map> reader) lowers to a
        // DynamicResultStatement, which MySQL emits as a PROCEDURE regardless of @StoredFunction (the
        // pipeline emits the carrier @StoredFunction as a procedure on MySQL) — so it never produces a
        // 1336 function. Gating it would be a FALSE-REJECT. (java.util.* imported in the source itself.)
        LowerResult result = lowerCollecting("CarrierFn", """
                import java.util.*;
                class CarrierFn {
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
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL unknown-shape carrier @StoredFunction (emitted as a PROCEDURE) must NOT trip the "
                        + "dynamic-SQL reject; errors=" + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL carrier @StoredFunction must lower cleanly; errors=" + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(DynamicResultStatement.class::isInstance),
                "the pure metadata-driven reader must lower to a DynamicResultStatement carrier");
    }

    @Test
    void mysqlNullTargetSetConservativelyRejectsStoredFunctionDynamicRead() throws Exception {
        // mysqlTargeted is conservatively TRUE when the target set is unknown (null) — so a
        // @StoredFunction dynamic read with no declared targets is rejected (it MIGHT target MySQL).
        // Mirrors the decision-1 cursor reject's conservative-true posture.
        LowerResult result = lowerCollecting("FnReadNullTargets", """
                class FnReadNullTargets {
                    @StoredFunction
                    public static String run(Connection c, long id) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                        ps.setLong(1, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                            return tier;
                        }
                        return null;
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, null);
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "an unknown target set (null) must conservatively reject a @StoredFunction dynamic read; "
                        + "errors=" + result.sink().errors());
    }

    // ---- §9 invariant 1 precision: @ScheduledJob is NOT a FUNCTION/trigger (procedure-backed EVENT) ----
    // A MySQL @ScheduledJob is emitted as a backing stored PROCEDURE that a CREATE EVENT … DO CALL invokes
    // (MySqlEmitter.emitScheduledJob), and dynamic SQL (PREPARE/EXECUTE) is legal inside that procedure —
    // so a dynamic read/execute in a @ScheduledJob must NOT trip the §9 reject (it is genuinely
    // deployable). Contrast: a @Trigger IS emitted inline as CREATE TRIGGER, so its dynamic SQL genuinely
    // hits ERROR 1336 and MUST still reject. (The earlier `!storedProcedure` gate FALSE-rejected the
    // scheduled job because a SCHEDULED_JOB is also !storedProcedure.)

    /**
     * A substring unique to the §9-invariant-1 reject for an inline-emitted MySQL TRIGGER. The trigger
     * steer differs from the function steer (a trigger cannot be re-annotated @StoredProcedure), so it
     * names the trigger and the CALL-a-procedure remediation rather than "annotate @StoredProcedure".
     */
    private static boolean isMysqlTriggerDynamicSqlReject(TitanDiagnostic d) {
        return d.code() == TitanErrorCode.E001
                && d.message().contains("dynamic SQL (PREPARE/EXECUTE) inside a TRIGGER")
                && d.message().contains("ERROR 1336")
                && d.message().contains("CALL it from the trigger");
    }

    @Test
    void mysqlScheduledJobDynamicExecuteIsNotRejected() throws Exception {
        // A MySQL @ScheduledJob whose body does a dynamic executeUpdate must NOT be rejected: it lowers to
        // an ExecuteSqlStatement and is emitted as a backing PROCEDURE (dynamic SQL legal) called by a
        // CREATE EVENT. This is the FALSE-REJECT the §9 gate previously hit via the over-broad
        // `!storedProcedure` condition (SCHEDULED_JOB is !storedProcedure but is procedure-backed). The
        // paired JdbcEmitterDeployabilityIT deploy case proves the artifact actually CREATEs on MySQL 8.4.
        LowerResult result = lowerCollecting("StaleSweep", """
                class StaleSweep {
                    @ScheduledJob(cron = "0 0 * * *")
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET stale = 1 WHERE ts < NOW()");
                        ps.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @ScheduledJob dynamic executeUpdate must NOT trip the function/trigger dynamic-SQL "
                        + "reject (it is emitted as a procedure-backed EVENT); errors=" + result.sink().errors());
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlTriggerDynamicSqlReject),
                "a MySQL @ScheduledJob must not trip the trigger variant of the reject either; errors="
                        + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL @ScheduledJob dynamic executeUpdate must lower cleanly; errors="
                        + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(ExecuteSqlStatement.class::isInstance),
                "the @ScheduledJob executeUpdate must still lower to an ExecuteSqlStatement (the dynamic node)");
    }

    @Test
    void mysqlScheduledJobDynamicSingleRowReadIsNotRejected() throws Exception {
        // The read side of the same precision boundary: a MySQL @ScheduledJob doing a single-row read
        // (RawReadIntoStatement -> dynamic PREPARE … INTO) must NOT be rejected either — the read runs in
        // the backing procedure where dynamic SQL is legal. Locks the read production sites for the
        // scheduled-job kind, not just executeUpdate.
        LowerResult result = lowerCollecting("ScanJob", """
                class ScanJob {
                    @ScheduledJob(cron = "0 0 * * *")
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = 7");
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            String tier = rs.getString("tier");
                        }
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().noneMatch(JdbcLoweringTest::isMysqlFunctionDynamicSqlReject),
                "a MySQL @ScheduledJob dynamic single-row read must NOT trip the dynamic-SQL reject; errors="
                        + result.sink().errors());
        assertTrue(result.sink().errors().isEmpty(),
                "the MySQL @ScheduledJob dynamic read must lower cleanly; errors=" + result.sink().errors());
        Block block = result.lowered().values().iterator().next();
        assertTrue(block.statements().stream().anyMatch(RawReadIntoStatement.class::isInstance),
                "the @ScheduledJob single-row read must still lower to a RawReadIntoStatement");
    }

    @Test
    void mysqlTriggerDynamicExecuteIsStillRejected() throws Exception {
        // The precision invariant's other half: a @Trigger IS emitted inline as CREATE TRIGGER (no
        // procedure backing — MySqlEmitter.emitTrigger), so its dynamic executeUpdate genuinely hits
        // ERROR 1336 and MUST still reject. The diagnostic is the trigger variant (names the trigger and
        // the CALL-a-procedure steer), NOT the function "annotate @StoredProcedure" steer that is
        // impossible for a trigger. This guards against the scheduled-job fix accidentally widening the
        // exemption to triggers.
        LowerResult result = lowerCollecting("FlagTrg", """
                import titan.dsl.TriggerTiming;
                import titan.dsl.TriggerEvent;
                class FlagTrg {
                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = {TriggerEvent.INSERT})
                    public static void run(Connection c) throws SQLException {
                        PreparedStatement ps = c.prepareStatement("UPDATE accounts SET stale = 1 WHERE ts < NOW()");
                        ps.executeUpdate();
                    }
                }
                """, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, List.of(DialectId.MYSQL));
        assertTrue(result.sink().errors().stream().anyMatch(JdbcLoweringTest::isMysqlTriggerDynamicSqlReject),
                "a MySQL @Trigger dynamic executeUpdate must still be rejected E001 (ERROR 1336), with the "
                        + "trigger-specific steer; errors=" + result.sink().errors());
        // It must NOT carry the function-only "annotate @StoredProcedure" steer (that is impossible for a
        // trigger). The trigger message deliberately omits "annotate the method @StoredProcedure".
        assertTrue(result.sink().errors().stream()
                        .noneMatch(d -> d.message().contains("annotate the method @StoredProcedure")),
                "the @Trigger reject must not give the impossible 'annotate the method @StoredProcedure' "
                        + "steer; errors=" + result.sink().errors());
    }
}
