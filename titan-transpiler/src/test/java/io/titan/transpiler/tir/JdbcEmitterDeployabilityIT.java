package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 2 (sub-phase 2c) acceptance gate: the spec's §6.1/§6.2 JDBC worked examples — the same
 * sources {@link JdbcGoldenTest} byte-pins — must actually CREATE and RUN on their <em>one</em>
 * configured source-dialect database. Byte-matching a golden proves the emitter is stable; this IT
 * proves the emitted SQL is <em>real</em>: it deploys each transpiled routine via Testcontainers and
 * CALLs/SELECTs it against seeded rows, asserting the routine's effect/return.
 *
 * <p>Per the §1.1a single-dialect-native reframe each fixture targets one dialect only: §6.1
 * ({@code computeAccountFee}, single-row read + {@code if (!rs.next()) throw} guard I-4 + executeUpdate
 * I-6) deploys to PostgreSQL; §6.2 ({@code totalOverdue}, {@code while (rs.next())} cursor accumulate
 * I-5) deploys to MySQL.</p>
 *
 * <p>Each test provisions a <em>dedicated, freshly-created</em> database (via
 * {@link TestContainers#freshPostgresDatabase}/{@link TestContainers#freshMysqlDatabase}) so it shares
 * no schema/table state with the other deployability ITs on the same container — and so the routine
 * deploys into the spec's {@code billing} schema (the MySQL fresh DB is created by root, which can both
 * create the {@code billing} database and grant the connection access to it). The deploy schema thus
 * matches the golden's {@code billing} schema.</p>
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test.
@org.junit.jupiter.api.Tag("docker")
class JdbcEmitterDeployabilityIT {

    private static final String SCHEMA = JdbcGoldenCorpus.SCHEMA; // "billing" — matches the goldens.

    @TempDir
    Path tempDir;

    private List<TranspilationPipeline.GeneratedSql> transpile(JdbcGoldenCorpus.Fixture fixture) throws Exception {
        return transpile(fixture, fixture.sourceDialect().canonicalTarget(), fixture.schemaModel());
    }

    /** Transpiles a fixture's source to an explicit dialect with an explicit catalog (§6.3 / I-7). */
    private List<TranspilationPipeline.GeneratedSql> transpile(
            JdbcGoldenCorpus.Fixture fixture, String dialect, io.titan.introspect.SchemaModel schemaModel)
            throws Exception {
        Path source = tempDir.resolve(fixture.id() + ".java");
        Files.writeString(source, fixture.source());
        return new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of(dialect),
                List.of(SCHEMA),
                true,
                List.of(),
                false,
                List.of(),
                false,
                false,
                schemaModel);
    }

    /** Transpiles inline JDBC source to one dialect (the WS-C Phase 3 Rung 1 skeleton-recovery cases). */
    private List<TranspilationPipeline.GeneratedSql> transpileSource(
            String fileName, String source, String dialect) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return new TranspilationPipeline().transpile(
                List.of(file), List.of(), List.of(dialect), List.of(SCHEMA), true);
    }

    /**
     * §6.1 (PostgreSQL source): deploy {@code compute_account_fee} and prove the single-row read + fee
     * compute + UPDATE actually runs. A GOLD account's fee becomes balance * goldRate; a non-GOLD
     * account's fee becomes balance * stdRate (the ternary's two branches); a missing account raises
     * via the no-row guard (the {@code if (!rs.next()) throw} shape, lowered to a ROW_COUNT check).
     */
    @Test
    void computeAccountFeeDeploysAndRunsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile(JdbcGoldenCorpus.COMPUTE_ACCOUNT_FEE);

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc2c_compute_fee");
        try (Connection postgres = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("CREATE SCHEMA " + SCHEMA);
                // Routine bodies reference the fixture table unqualified; resolve it at execution time.
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                        + "id BIGINT PRIMARY KEY, balance NUMERIC(38,10) NOT NULL, "
                        + "tier TEXT NOT NULL, fee NUMERIC(38,10) NULL)");
                statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, balance, tier) VALUES "
                        + "(1, 1000, 'GOLD'), (2, 1000, 'STANDARD')");
            }
            deploy(postgres, generated, "postgresql");

            BigDecimal goldRate = new BigDecimal("0.001");
            BigDecimal stdRate = new BigDecimal("0.002");

            // GOLD branch: fee = 1000 * 0.001 = 1.0
            callComputeAccountFee(postgres, 1, goldRate, stdRate);
            assertEquals(0, new BigDecimal("1.0000000000").compareTo(readFee(postgres, 1)),
                    "GOLD account fee must be balance * goldRate (the ternary GOLD branch)");

            // Non-GOLD branch: fee = 1000 * 0.002 = 2.0
            callComputeAccountFee(postgres, 2, goldRate, stdRate);
            assertEquals(0, new BigDecimal("2.0000000000").compareTo(readFee(postgres, 2)),
                    "non-GOLD account fee must be balance * stdRate (the ternary ELSE branch)");

            // The if (!rs.next()) throw guard: a missing account must RAISE, not silently no-op. (This is
            // the WS-C Phase 2c regression: the guard was FOUND-based, but a dynamic EXECUTE ... INTO does
            // not set FOUND on PostgreSQL, so it formerly raised on EVERY call — even when a row matched.)
            try {
                callComputeAccountFee(postgres, 999, goldRate, stdRate);
                throw new AssertionError("expected compute_account_fee(999, …) to raise 'account not found'");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("account not found"),
                        "the no-row guard must raise 'account not found'; was: " + expected.getMessage());
            }
        }
    }

    // §9 invariant 1, PG side of the precision boundary: a PostgreSQL @StoredFunction containing a DYNAMIC
    // single-row read (the EXECUTE … INTO `RawReadIntoStatement` shape the new lowering gate REJECTS on
    // MySQL) deploys + runs fine — PG functions allow dynamic EXECUTE. This is the live positive
    // counterpart to JdbcLoweringTest.postgresStoredFunctionReadIsNotRejected, proving the gate is
    // dialect-conditional (not a blanket @StoredFunction reject): the identical construct that fails to
    // CREATE on MySQL (ERROR 1336) CREATEs and returns the correct row on PG 16.
    private static final String LOOKUP_TIER_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;

            class LookupTier {
                @StoredFunction
                public static String lookupTier(Connection c, long id) throws SQLException {
                    String tier;
                    PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                    ps.setLong(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        throw new SQLException("account not found");
                    }
                    tier = rs.getString("tier");
                    return tier;
                }
            }
            """;

    @Test
    void storedFunctionDynamicSingleRowReadDeploysAndRunsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("LookupTier.java", LOOKUP_TIER_FN, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_fn_dynread_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = pg.createStatement()) {
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                        + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL)");
                statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier) VALUES "
                        + "(1, 'GOLD'), (2, 'STANDARD')");
            }
            // The dynamic EXECUTE … INTO single-row read must CREATE in a PG FUNCTION (it would be ERROR
            // 1336 on MySQL — which the lowering gate rejects up front).
            deploy(pg, generated, "postgresql");

            assertEquals("GOLD", callLookupTier(pg, 1),
                    "the PG @StoredFunction dynamic read must return the matched row's tier");
            assertEquals("STANDARD", callLookupTier(pg, 2),
                    "the PG @StoredFunction dynamic read must return the matched row's tier");
            // The no-row guard (the RawReadIntoStatement's notFoundRaise) raises on PG too.
            try {
                callLookupTier(pg, 999);
                throw new AssertionError("expected lookup_tier(999) to raise 'account not found'");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("account not found"),
                        "the no-row guard must raise 'account not found'; was: " + expected.getMessage());
            }
        }
    }

    private static String callLookupTier(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".lookup_tier(?)")) {
            statement.setLong(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "lookup_tier returned no row");
                return resultSet.getString(1);
            }
        }
    }

    // §9 invariant 1, MySQL precision boundary (FIX lock): a MySQL @ScheduledJob with a DYNAMIC body
    // (PreparedStatement.executeUpdate -> a dynamic PREPARE/EXECUTE) must NOT be rejected at transpile — a
    // scheduled job is emitted as a backing stored PROCEDURE (where dynamic SQL is legal) that a CREATE
    // EVENT … DO CALL invokes (MySqlEmitter.emitScheduledJob). The earlier `!storedProcedure` gate
    // FALSE-rejected this deployable path (a SCHEDULED_JOB is !storedProcedure, like a FUNCTION/trigger).
    // This is the live counterpart to JdbcLoweringTest.mysqlScheduledJobDynamicExecuteIsNotRejected: it
    // proves the artifact set (CREATE PROCEDURE … PREPARE + CREATE EVENT … DO CALL) actually CREATEs on
    // MySQL 8.4 (NO ERROR 1336), the EVENT registers, and CALLing the procedure runs the dynamic body.
    private static final String STALE_SWEEP_JOB = """
            import titan.dsl.ScheduledJob;
            import java.sql.*;

            class StaleSweep {
                @ScheduledJob(cron = "0 0 * * *")
                public static void run(Connection c) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET stale = 1 WHERE ts < NOW()");
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void scheduledJobDynamicExecuteDeploysAndRunsOnMysql() throws Exception {
        // Must NOT throw at transpile (the FALSE-REJECT lock): the dynamic executeUpdate is legal in the
        // scheduled job's backing procedure.
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("StaleSweep.java", STALE_SWEEP_JOB, "mysql");

        // The emitted MySQL artifact is the procedure-backed EVENT shape: a CREATE PROCEDURE carrying the
        // in-procedure dynamic PREPARE, plus a CREATE EVENT … DO CALL that invokes it. (Asserting the shape
        // pins that the scheduled job is NOT emitted as a CREATE FUNCTION — which is where ERROR 1336 lives.)
        String mysqlSql = generated.stream()
                .filter(g -> "mysql".equals(g.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(mysqlSql.contains("CREATE PROCEDURE") && mysqlSql.contains("PREPARE "),
                "the @ScheduledJob must emit a CREATE PROCEDURE carrying the in-procedure dynamic PREPARE; "
                        + "sql=" + mysqlSql);
        assertTrue(mysqlSql.contains("CREATE EVENT") && mysqlSql.contains("DO CALL"),
                "the @ScheduledJob must emit a CREATE EVENT … DO CALL backing it; sql=" + mysqlSql);
        assertTrue(!mysqlSql.contains("CREATE FUNCTION"),
                "the @ScheduledJob must NOT be emitted as a CREATE FUNCTION (where ERROR 1336 would bite); "
                        + "sql=" + mysqlSql);

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA); // root creates `billing`
        try (Connection mysql = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                        + "id BIGINT PRIMARY KEY, ts DATETIME NOT NULL, stale INT NOT NULL DEFAULT 0)");
                // id 1,2 are stale (ts in the past); id 3 is fresh (ts in the future) and must NOT be flagged.
                statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, ts, stale) VALUES "
                        + "(1, NOW() - INTERVAL 2 DAY, 0), (2, NOW() - INTERVAL 1 HOUR, 0), "
                        + "(3, NOW() + INTERVAL 1 DAY, 0)");
            }
            // Deploy ALL emitted artifacts — the CREATE PROCEDURE *and* the CREATE EVENT … DO CALL. Neither
            // may hit ERROR 1336. (The event scheduler need not fire; we CALL the backing procedure to run
            // the dynamic body, and assert the EVENT registered in information_schema.)
            deploy(mysql, generated, "mysql");

            // The EVENT actually CREATEd and registered (the artifact deployed, not just parsed).
            assertEquals(1, scheduledEventCount(mysql, "run_event"),
                    "the CREATE EVENT must register the scheduled job's event in information_schema.EVENTS");

            // CALL the backing procedure: the in-procedure dynamic UPDATE runs and flags exactly the
            // past-ts rows (ids 1, 2), leaving the future-ts row (id 3) untouched.
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CALL " + SCHEMA + ".run()");
            }
            assertEquals(java.util.Set.of(1L, 2L), staleIds(mysql),
                    "the scheduled job's backing procedure must run the dynamic UPDATE and flag exactly the "
                            + "stale (past-ts) rows");
        }
    }

    /** Count of registered events with the given name in the deploy schema (proves a CREATE EVENT took). */
    private static int scheduledEventCount(Connection connection, String eventName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.EVENTS "
                        + "WHERE EVENT_SCHEMA = ? AND EVENT_NAME = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, eventName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "COUNT(*) returned no row");
                return resultSet.getInt(1);
            }
        }
    }

    /** The set of ids whose accounts row has {@code stale = 1} (the scheduled job's dynamic UPDATE effect). */
    private static java.util.Set<Long> staleIds(Connection connection) throws SQLException {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT id FROM " + SCHEMA + ".accounts WHERE stale = 1")) {
            while (resultSet.next()) {
                ids.add(resultSet.getLong(1));
            }
        }
        return ids;
    }

    /**
     * §6.2 (MySQL source): deploy {@code total_overdue} and prove the {@code while (rs.next())} cursor
     * accumulate actually runs. The function sums the {@code amount} of the customer's OVERDUE invoices
     * only (the {@code status = 'OVERDUE'} predicate is part of the inlined static cursor), skipping
     * non-OVERDUE rows and other customers. (WS-C Phase 2c regression: the per-row FETCH-target local
     * was DECLAREd in the loop-body scope, out of scope at the FETCH — the routine failed to CREATE.)
     */
    @Test
    void totalOverdueDeploysAndRunsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile(JdbcGoldenCorpus.TOTAL_OVERDUE);

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA); // root creates `billing`
        try (Connection mysql = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE TABLE " + SCHEMA + ".invoices ("
                        + "id INT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, "
                        + "amount DECIMAL(38,10) NOT NULL, status VARCHAR(32) NOT NULL)");
                // Customer 100: two OVERDUE (40.50 + 9.50 = 50.00) plus a PAID row that must be skipped.
                // Customer 200: an OVERDUE row that must NOT leak into customer 100's total.
                statement.execute("INSERT INTO " + SCHEMA + ".invoices (customer_id, amount, status) VALUES "
                        + "(100, 40.50, 'OVERDUE'), (100, 9.50, 'OVERDUE'), (100, 1000.00, 'PAID'), "
                        + "(200, 7.00, 'OVERDUE')");
            }
            // The §6.2 routine seeds its accumulator from BigDecimal.ZERO, which folds to a literal 0 (no
            // titan_rt_static_get), so — like a constant-only routine (the G5 deployability gate) — it
            // deploys and runs with NO runtime migration applied. Deliberately deploy only the routine.
            deploy(mysql, generated, "mysql");

            // 40.50 + 9.50 = 50.00 (OVERDUE only, customer 100 only).
            assertEquals(0, new BigDecimal("50.0000000000").compareTo(callTotalOverdue(mysql, 100)),
                    "total_overdue must sum only the customer's OVERDUE invoices (cursor accumulate)");
            // Customer 200 sees only its own OVERDUE row.
            assertEquals(0, new BigDecimal("7.0000000000").compareTo(callTotalOverdue(mysql, 200)),
                    "total_overdue must not leak other customers' invoices");
            // A customer with no OVERDUE invoices accumulates from the literal-0 seed -> 0.
            assertEquals(0, BigDecimal.ZERO.compareTo(callTotalOverdue(mysql, 300)),
                    "total_overdue over an empty cursor must return the 0 accumulator seed");
        }
    }

    /**
     * §6.3 (PostgreSQL source) — the deploy-suite's lock on the wrong-key-value bug. Deploys
     * {@code create_invoice} (lowered to {@code INSERT … RETURNING id INTO …}, the key column resolved
     * from the Catalog) and proves the returned key is the <b>actual inserted row's PK</b>
     * <i>even under an {@code AFTER INSERT} trigger that advances a second, unrelated sequence</i>.
     *
     * <p>This is the exact scenario where the withdrawn {@code SELECT lastval()} emission was wrong:
     * after each invoice insert the trigger inserts into an {@code audit_log} table whose own
     * {@code BIGSERIAL} advances {@code lastval()} to the audit row's id — so {@code lastval()} would
     * return the audit id, not the invoice id. {@code RETURNING id} reads the actual inserted invoice
     * row, so it is immune. We insert several rows (so the audit sequence runs ahead of the invoice
     * sequence) and assert the function's return equals the invoice PK SELECTed back.</p>
     */
    @Test
    void createInvoiceGeneratedKeyDeploysAndReturnsActualPkUnderTriggerOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile(JdbcGoldenCorpus.CREATE_INVOICE);

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc63_genkey_pg");
        try (Connection postgres = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                // invoices.id is a GENERATED IDENTITY (serial-equivalent) PK — the key create_invoice
                // recovers. A SEPARATE audit_log table with its own BIGSERIAL is advanced by an AFTER
                // INSERT trigger on invoices, so lastval() (the old wrong emission) would return the
                // audit row's id, not the invoice id. RETURNING id is immune.
                statement.execute("CREATE TABLE " + SCHEMA + ".invoices ("
                        + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                        + "customer_id BIGINT NOT NULL, amount NUMERIC(38,10) NOT NULL)");
                statement.execute("CREATE TABLE " + SCHEMA + ".audit_log ("
                        + "audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                        + "invoice_id BIGINT NOT NULL, note TEXT NOT NULL)");
                // Pre-advance the audit sequence so its values are clearly ahead of the invoice ids.
                statement.execute("INSERT INTO " + SCHEMA + ".audit_log (invoice_id, note) VALUES "
                        + "(0,'seed'),(0,'seed'),(0,'seed'),(0,'seed'),(0,'seed')");
                statement.execute("CREATE FUNCTION " + SCHEMA + ".invoices_audit() RETURNS trigger AS $$ "
                        + "BEGIN INSERT INTO " + SCHEMA + ".audit_log (invoice_id, note) "
                        + "VALUES (NEW.id, 'created'); RETURN NEW; END; $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER invoices_after_insert AFTER INSERT ON " + SCHEMA + ".invoices "
                        + "FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".invoices_audit()");
            }
            deploy(postgres, generated, "postgresql");

            long previousKey = 0;
            for (int i = 1; i <= 3; i++) {
                long customerId = 100 + i;
                long returnedKey = callCreateInvoice(postgres, customerId, new BigDecimal("12.34"));
                long actualPk = readInvoicePkByCustomer(postgres, customerId);
                assertEquals(actualPk, returnedKey,
                        "RETURNING id must return the actual inserted invoice PK, not the AFTER-INSERT "
                                + "trigger's audit-sequence value (lastval would fail here)");
                assertTrue(returnedKey > previousKey, "successive invoice keys must increase");
                previousKey = returnedKey;
            }
        }
    }

    /**
     * §6.3 (MySQL source) — the MySQL half of the wrong-key-value lock. {@code create_invoice} lowers to
     * the INSERT followed by {@code SET v = LAST_INSERT_ID()} (the MySQL JDBC driver's
     * {@code getGeneratedKeys()} behavior). An {@code AFTER INSERT} trigger inserts into a separate
     * {@code audit_log} (its own {@code AUTO_INCREMENT}); {@code LAST_INSERT_ID()} is the connection's
     * last AUTO_INCREMENT for the statement that set it — the invoice insert, not the trigger's audit
     * insert — so the recovered key is the actual invoice PK. (The §6.3 fixture is PostgreSQL-source;
     * here it is transpiled to MySQL explicitly to exercise the MySQL emission live.)
     */
    @Test
    void createInvoiceGeneratedKeyDeploysAndReturnsActualPkUnderTriggerOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated = transpile(
                JdbcGoldenCorpus.CREATE_INVOICE, DialectId.MYSQL.canonicalTarget(), JdbcGoldenCorpus.invoicesModel());

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection mysql = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE TABLE " + SCHEMA + ".invoices ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, "
                        + "amount DECIMAL(38,10) NOT NULL)");
                statement.execute("CREATE TABLE " + SCHEMA + ".audit_log ("
                        + "audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, invoice_id BIGINT NOT NULL, "
                        + "note VARCHAR(64) NOT NULL)");
                // Pre-advance the audit AUTO_INCREMENT so it is clearly ahead of the invoice ids.
                statement.execute("INSERT INTO " + SCHEMA + ".audit_log (invoice_id, note) VALUES "
                        + "(0,'seed'),(0,'seed'),(0,'seed'),(0,'seed'),(0,'seed')");
                statement.execute("CREATE TRIGGER invoices_after_insert AFTER INSERT ON " + SCHEMA + ".invoices "
                        + "FOR EACH ROW INSERT INTO " + SCHEMA + ".audit_log (invoice_id, note) "
                        + "VALUES (NEW.id, 'created')");
            }
            deploy(mysql, generated, "mysql");

            long previousKey = 0;
            for (int i = 1; i <= 3; i++) {
                long customerId = 200 + i;
                long returnedKey = callCreateInvoice(mysql, customerId, new BigDecimal("56.78"));
                long actualPk = readInvoicePkByCustomer(mysql, customerId);
                assertEquals(actualPk, returnedKey,
                        "LAST_INSERT_ID() must return the actual inserted invoice PK, not the AFTER-INSERT "
                                + "trigger's audit AUTO_INCREMENT value");
                assertTrue(returnedKey > previousKey, "successive invoice keys must increase");
                previousKey = returnedKey;
            }
        }
    }

    /** create_invoice is a FUNCTION returning the recovered key (same SELECT shape on both dialects). */
    private static long callCreateInvoice(Connection connection, long customerId, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".create_invoice(?, ?)")) {
            statement.setLong(1, customerId);
            statement.setBigDecimal(2, amount);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "create_invoice returned no row");
                return resultSet.getLong(1);
            }
        }
    }

    private static long readInvoicePkByCustomer(Connection connection, long customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + SCHEMA + ".invoices WHERE customer_id = ?")) {
            statement.setLong(1, customerId);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no invoices row with customer_id " + customerId);
                return resultSet.getLong(1);
            }
        }
    }

    private static void callComputeAccountFee(
            Connection connection, long accountId, BigDecimal goldRate, BigDecimal stdRate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".compute_account_fee(?, ?, ?)")) {
            statement.setLong(1, accountId);
            statement.setBigDecimal(2, goldRate);
            statement.setBigDecimal(3, stdRate);
            statement.execute();
        }
    }

    private static BigDecimal readFee(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fee FROM " + SCHEMA + ".accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no accounts row with id " + accountId);
                return resultSet.getBigDecimal(1);
            }
        }
    }

    private static BigDecimal callTotalOverdue(Connection connection, long customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".total_overdue(?)")) {
            statement.setLong(1, customerId);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "total_overdue returned no rows");
                return resultSet.getBigDecimal(1);
            }
        }
    }

    // ===== WS-C Phase 3 Rung 1: deploy-prove skeleton recovery of value-bindable non-constant SQL =====
    // The arbiter for Rung 1 (design contract §7/§11): a runtime-built placeholder run and a value-splice
    // must actually CREATE and RUN on PG 16 AND MySQL 8.4, returning correct rows — the recovered '?'-run
    // binds the setXxx ordinals / the spliced value binds, and no value reaches the SQL text. Emitted as
    // @StoredProcedure UPDATEs (dynamic EXECUTE is valid in a routine on both dialects, unlike a MySQL
    // FUNCTION) whose effect (the flagged row set) is SELECTed back and asserted.

    /**
     * §3.6 form 3 — a runtime-built, fixed-arity placeholder run ({@code WHERE id IN (} + {@code
     * "?, ".repeat(2)} + {@code "?)"}) whose three {@code ?}s are bound by the statement's own {@code
     * setLong} ordinals: flags exactly the three id'd rows. The recovered text is {@code IN (?, ?, ?)}
     * with the three values bound, never spliced. Constant arity (the repeat count is a literal) keeps it
     * on the fixed-{@code ?} Rung-1 substrate (a genuinely runtime-sized list bind is the array-bind
     * tier, Rung 4).
     */
    private static final String PLACEHOLDER_RUN_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class FlagByIds {
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
            """;

    /**
     * D3 value-splice auto-bind — {@code "… WHERE tier = " + tier} lowers to {@code … WHERE tier = ?} with
     * the spliced value bound (never spliced text): flags exactly the rows of that tier. Deployed and run
     * on both dialects.
     */
    private static final String VALUE_SPLICE_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class FlagByTier {
                @StoredProcedure
                public static void flagByTier(Connection c, String tier) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE tier = " + tier);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void placeholderRunDeploysAndFlagsBoundIdsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIds.java", PLACEHOLDER_RUN_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r1_phrun_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            callFlagByIds(pg, 10, 20, 40);
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(pg),
                    "the placeholder run must flag exactly the three bound ids (IN ($1,$2,$3))");
        }
    }

    @Test
    void placeholderRunDeploysAndFlagsBoundIdsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIds.java", PLACEHOLDER_RUN_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");
            callFlagByIds(my, 10, 20, 40);
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(my),
                    "the placeholder run must flag exactly the three bound ids (IN (?, ?, ?))");
        }
    }

    /**
     * WS-C Phase 3 Rung 1 FIX deploy-proof (deploy finding #1): a value splice that is NOT the last token
     * — {@code "… WHERE id = " + targetId + " AND tier = 'GOLD'"} — must emit a placeholder cleanly
     * SEPARATED from the following SQL ({@code id = $1 AND tier = 'GOLD'}), bind the value, and deploy +
     * CALL without a fused {@code $1AND}/$1-into-next-token error. The existing value-splice IT only
     * exercised a splice as the final token; this locks the boundary the auditor flagged untested.
     */
    private static final String VALUE_SPLICE_MIDDLE_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class FlagByIdAndTier {
                @StoredProcedure
                public static void flagByIdAndTier(Connection c, long targetId) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id = " + targetId + " AND tier = 'GOLD'");
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void valueSpliceFollowedByMoreSqlDeploysAndBindsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdAndTier.java", VALUE_SPLICE_MIDDLE_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r1_vsplice_mid_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            callFlagByIdAndTier(pg, 30); // id 30 is GOLD -> flagged; the value bound, not spliced.
            assertEquals(java.util.Set.of(30L), flaggedIds(pg),
                    "the mid-statement value splice must bind id and flag exactly the matching GOLD row");
        }
    }

    @Test
    void valueSpliceFollowedByMoreSqlDeploysAndBindsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdAndTier.java", VALUE_SPLICE_MIDDLE_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");
            callFlagByIdAndTier(my, 30);
            assertEquals(java.util.Set.of(30L), flaggedIds(my),
                    "the mid-statement value splice must bind id and flag exactly the matching GOLD row");
        }
    }

    @Test
    void valueSpliceDeploysAndFlagsByBoundValueOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByTier.java", VALUE_SPLICE_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r1_vsplice_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            callFlagByTier(pg, "GOLD");
            // Only the GOLD-tier rows (ids 10, 30) are flagged; the spliced value was bound, not spliced.
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(pg),
                    "the value splice must bind the tier value and flag exactly its rows");
        }
    }

    @Test
    void valueSpliceDeploysAndFlagsByBoundValueOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByTier.java", VALUE_SPLICE_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");
            callFlagByTier(my, "GOLD");
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(my),
                    "the value splice must bind the tier value and flag exactly its rows");
        }
    }

    /** Seeds a billing.accounts table (id, tier, flagged) on PostgreSQL with mixed tiers and ids. */
    private static void seedFlagAccountsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier) VALUES "
                    + "(10, 'GOLD'), (20, 'STANDARD'), (30, 'GOLD'), (40, 'STANDARD'), (50, 'SILVER')");
        }
    }

    /** Seeds the same accounts table on MySQL. */
    private static void seedFlagAccountsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier) VALUES "
                    + "(10, 'GOLD'), (20, 'STANDARD'), (30, 'GOLD'), (40, 'STANDARD'), (50, 'SILVER')");
        }
    }

    private static void callFlagByIds(Connection connection, long a, long b, long d) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_by_ids(?, ?, ?)")) {
            statement.setLong(1, a);
            statement.setLong(2, b);
            statement.setLong(3, d);
            statement.execute();
        }
    }

    private static void callFlagByTier(Connection connection, String tier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_by_tier(?)")) {
            statement.setString(1, tier);
            statement.execute();
        }
    }

    private static void callFlagByIdAndTier(Connection connection, long targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_by_id_and_tier(?)")) {
            statement.setLong(1, targetId);
            statement.execute();
        }
    }

    /** The set of ids whose row has {@code flagged = 1}. */
    private static java.util.Set<Long> flaggedIds(Connection connection) throws SQLException {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT id FROM " + SCHEMA + ".accounts WHERE flagged = 1")) {
            while (resultSet.next()) {
                ids.add(resultSet.getLong(1));
            }
        }
        return ids;
    }

    // ===== WS-C Phase 3 Rung 2: deploy-prove §3.6 form 1 subquery fusion (two queries -> ONE) =====
    // The arbiter for Rung 2 (design contract §7/§11): a two-query method whose prior ResultSet feeds
    // ONLY a later IN-list must fuse into ONE in-database statement that deploys and runs on PG 16 AND
    // MySQL 8.4, returning correct rows. A is inlined as B's IN-subquery; the intermediate list never
    // materializes. Emitted as a @StoredProcedure UPDATE (dynamic EXECUTE is valid in a routine on both
    // dialects, unlike a MySQL FUNCTION) whose effect (the flagged order set) is SELECTed back. The
    // EMPTY-prior-result case (a tier matching no customers) must flag nothing — IN (SELECT … no rows)
    // matches nothing, exactly as an empty bound list would.

    /**
     * A reads the GOLD customers' ids into a list; B flags the orders of those customers via
     * {@code … customer_id IN (} + {@code placeholders(ids.size())} + {@code )}. The list is consumed
     * ONLY as B's IN-source, so the two queries fuse into one
     * {@code UPDATE orders SET flagged = 1 WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)}.
     */
    private static final String SUBQUERY_FUSION_PROC = """
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
                    // The IN-run is built inline by ids.size() — a §3.6(3) placeholder run whose ONLY
                    // runtime input is ids; fusion replaces the whole run with A's SELECT as the subquery,
                    // so the run-construction (and the intermediate list) never materializes at runtime.
                    PreparedStatement psB = c.prepareStatement(
                            "UPDATE orders SET flagged = 1 WHERE customer_id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    psB.executeUpdate();
                }
            }
            """;

    @Test
    void subqueryFusionDeploysAsOneStatementAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagOrdersByCustomerTier.java", SUBQUERY_FUSION_PROC, "postgresql");
        // Prove it really fused to ONE statement: the emitted body has the IN-subquery, no second
        // EXECUTE over the customers SELECT, and no cursor/OPEN over A.
        assertFusedToOneStatement(generated, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r2_fusion_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionTablesPostgres(pg);
            deploy(pg, generated, "postgresql");

            // GOLD customers are 1 and 3; their orders are 100,300 (cust 1) and 130 (cust 3).
            callFlagOrdersByCustomerTier(pg, "GOLD");
            assertEquals(java.util.Set.of(100L, 130L, 300L), flaggedOrderIds(pg),
                    "fusion must flag exactly the orders whose customer is GOLD (IN (SELECT id …))");

            // EMPTY-prior-result: no customer has tier 'PLATINUM' -> the subquery returns no rows ->
            // IN (empty) matches nothing -> no additional orders flagged (and no error).
            clearFlagsPostgres(pg);
            callFlagOrdersByCustomerTier(pg, "PLATINUM");
            assertTrue(flaggedOrderIds(pg).isEmpty(),
                    "an empty prior result must flag nothing: IN (SELECT … no rows) matches nothing");
        }
    }

    @Test
    void subqueryFusionDeploysAsOneStatementAndFlagsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagOrdersByCustomerTier.java", SUBQUERY_FUSION_PROC, "mysql");
        assertFusedToOneStatement(generated, "mysql");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionTablesMysql(my);
            deploy(my, generated, "mysql");

            callFlagOrdersByCustomerTier(my, "GOLD");
            assertEquals(java.util.Set.of(100L, 130L, 300L), flaggedOrderIds(my),
                    "fusion must flag exactly the orders whose customer is GOLD (IN (SELECT id …))");

            clearFlagsMysql(my);
            callFlagOrdersByCustomerTier(my, "PLATINUM");
            assertTrue(flaggedOrderIds(my).isEmpty(),
                    "an empty prior result must flag nothing: IN (SELECT … no rows) matches nothing");
        }
    }

    // ===== WS-C Phase 3 Rung 2: fused MULTI-ROW READ (B is a `while(rsB.next())` cursor over IN(SELECT)).
    // The deploy-readiness arbiter for the fused-cursor form: A reads the GOLD ids; B reads each matching
    // order's amount in a `while(rsB.next())` loop and sums them, as a @StoredFunction. The fused cursor
    // must CREATE and RETURN the correct sum on BOTH PG16 (refcursor OPEN … FOR EXECUTE) and MySQL8.4
    // (static cursor). This pins the PG FETCH-target hoist fix — before it, PG rejected CREATE with
    // "__titan_row_amount_0 is not a known variable".

    private static final String SUBQUERY_FUSION_READ_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.math.BigDecimal;
            import java.util.*;

            class SumOrdersByCustomerTier {
                @StoredFunction
                public static BigDecimal sumOrdersByCustomerTier(Connection c, String tier) throws SQLException {
                    BigDecimal total = BigDecimal.ZERO;
                    List<Long> ids = new ArrayList<>();
                    PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                    psA.setString(1, tier);
                    ResultSet rsA = psA.executeQuery();
                    while (rsA.next()) {
                        ids.add(rsA.getLong("id"));
                    }
                    PreparedStatement psB = c.prepareStatement(
                            "SELECT amount FROM orders WHERE customer_id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    ResultSet rsB = psB.executeQuery();
                    while (rsB.next()) {
                        total = total.add(rsB.getBigDecimal("amount"));
                    }
                    return total;
                }
            }
            """;

    @Test
    void subqueryFusionMultiRowReadDeploysOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SumOrdersByCustomerTier.java", SUBQUERY_FUSION_READ_FN, "postgresql");
        assertFusedToOneStatement(generated, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r2_fusion_read_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionAmountTablesPostgres(pg);
            deploy(pg, generated, "postgresql");

            // GOLD customers 1,3 → orders 10.00 + 5.00 (cust 1) + 7.00 (cust 3) = 22.00; cust 2 excluded.
            assertEquals(0, new BigDecimal("22.0000000000").compareTo(callSumOrdersByCustomerTier(pg, "GOLD")),
                    "fused cursor must sum exactly the GOLD customers' order amounts");
            // EMPTY prior result: IN (SELECT … no rows) matches nothing → the 0 accumulator seed.
            assertEquals(0, BigDecimal.ZERO.compareTo(callSumOrdersByCustomerTier(pg, "PLATINUM")),
                    "an empty prior result sums to the 0 seed (IN (SELECT … no rows) matches nothing)");
        }
    }

    @Test
    void subqueryFusionMultiRowReadDeploysOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SumOrdersByCustomerTier.java", SUBQUERY_FUSION_READ_FN, "mysql");
        assertFusedToOneStatement(generated, "mysql");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionAmountTablesMysql(my);
            deploy(my, generated, "mysql");

            assertEquals(0, new BigDecimal("22.0000000000").compareTo(callSumOrdersByCustomerTier(my, "GOLD")),
                    "fused cursor must sum exactly the GOLD customers' order amounts");
            assertEquals(0, BigDecimal.ZERO.compareTo(callSumOrdersByCustomerTier(my, "PLATINUM")),
                    "an empty prior result sums to the 0 seed");
        }
    }

    // ===== WS-C Phase 3 Rung 2: bind text-order merge under deploy (B binds BEFORE and AFTER the IN). =====
    // The committed flag IT exercises only A's single bind (no B-bind); this locks the merged ordering
    // (B-pre, A, B-post) in the live gate: flag rows matching region (pre-IN) AND GOLD-customer (A) AND
    // qty (post-IN). A misorder would flag the wrong rows.

    private static final String SUBQUERY_FUSION_BINDS_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagAround {
                @StoredProcedure
                public static void flagAround(Connection c, String tier, String region, int minQty) throws SQLException {
                    List<Long> ids = new ArrayList<>();
                    PreparedStatement psA = c.prepareStatement("SELECT id FROM customers WHERE tier = ?");
                    psA.setString(1, tier);
                    ResultSet rsA = psA.executeQuery();
                    while (rsA.next()) {
                        ids.add(rsA.getLong("id"));
                    }
                    PreparedStatement psB = c.prepareStatement(
                            "UPDATE orders SET flagged = 1 WHERE region = ? AND customer_id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ") AND qty >= ?");
                    psB.setString(1, region);
                    psB.setInt(2, minQty);
                    psB.executeUpdate();
                }
            }
            """;

    @Test
    void subqueryFusionWithBindsAroundInDeploysOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagAround.java", SUBQUERY_FUSION_BINDS_PROC, "postgresql");
        assertFusedToOneStatement(generated, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r2_fusion_binds_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionRegionQtyTablesPostgres(pg);
            deploy(pg, generated, "postgresql");

            callFlagAround(pg, "GOLD", "EU", 5);
            assertEquals(java.util.Set.of(100L, 130L), flaggedOrderIds(pg),
                    "binds must merge in text order (region pre-IN, tier in subquery, qty post-IN)");
        }
    }

    @Test
    void subqueryFusionWithBindsAroundInDeploysOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagAround.java", SUBQUERY_FUSION_BINDS_PROC, "mysql");
        assertFusedToOneStatement(generated, "mysql");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFusionRegionQtyTablesMysql(my);
            deploy(my, generated, "mysql");

            callFlagAround(my, "GOLD", "EU", 5);
            assertEquals(java.util.Set.of(100L, 130L), flaggedOrderIds(my),
                    "binds must merge in text order (region pre-IN, tier in subquery, qty post-IN)");
        }
    }

    // ===== WS-C Phase 3 Rung 4: deploy-prove §3.6 form 2 collection binding (whole list -> ONE param). ===
    // The arbiter for Rung 4 (design contract §7/§11): a method taking a List<Long> param and doing an IN
    // over it must deploy + return correct rows on PG 16 AND MySQL 8.4, INCLUDING the empty-list case
    // (flags nothing) and a multi-element case. The whole list is bound as ONE native bigint[] (PG) / one
    // JSON-array string (MySQL) parameter — no element ever reaches the SQL text. PG `= ANY($1)`; MySQL
    // `IN (SELECT v FROM JSON_TABLE(?, '$[*]' COLUMNS (v BIGINT PATH '$')) t)`.

    /**
     * FORM A — the canonical per-element loop bind: {@code WHERE id IN (} + a {@code size()}-sized
     * placeholder run + {@code )}, bound by a {@code for (i…) ps.setLong(i+1, ids.get(i))} loop. The whole
     * list array-binds; the loop is elided. Deploys and flags exactly the listed ids on both dialects.
     */
    private static final String COLLECTION_IN_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIdList {
                @StoredProcedure
                public static void flagByIdList(Connection c, List<Long> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setLong(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void collectionInDeploysAndFlagsListedIdsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdList.java", COLLECTION_IN_PROC, "postgresql");
        // Prove the array-bind shape made it to the emitted SQL (= ANY($1), one bigint[] param).
        assertCollectionArrayBound(generated, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);

            deploy(pg, generated, "postgresql");

            // Multi-element: flag exactly ids 10,20,40 (the bound array drives the IN).
            callFlagByIdListPostgres(pg, 10L, 20L, 40L);
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(pg),
                    "the array bind must flag exactly the listed ids (id = ANY($1))");

            // Empty list: = ANY('{}') matches nothing -> flags nothing (and no error).
            clearAccountFlagsPostgres(pg);
            callFlagByIdListPostgres(pg);
            assertTrue(flaggedIds(pg).isEmpty(),
                    "an empty list must flag nothing: = ANY('{}') matches nothing");
        }
    }

    @Test
    void collectionInDeploysAndFlagsListedIdsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdList.java", COLLECTION_IN_PROC, "mysql");
        assertCollectionArrayBound(generated, "mysql");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);

            deploy(my, generated, "mysql");

            // Multi-element: the list is passed as ONE JSON-array string; JSON_TABLE explodes it.
            callFlagByIdListMysql(my, "[10, 20, 40]");
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(my),
                    "the JSON array bind must flag exactly the listed ids (JSON_TABLE)");

            // Empty list: JSON_TABLE('[]') yields no rows -> IN (no rows) matches nothing.
            clearAccountFlagsMysql(my);
            callFlagByIdListMysql(my, "[]");
            assertTrue(flaggedIds(my).isEmpty(),
                    "an empty JSON array must flag nothing: JSON_TABLE('[]') yields no rows");
        }
    }

    /**
     * FORM B — the explicit single-array idiom: {@code WHERE id IN (?)} bound by {@code ps.setArray(1,
     * c.createArrayOf("bigint", ids.toArray()))}. Lowers to the SAME {@code = ANY($1)} (PG) array bind.
     * Deployed on PostgreSQL (where {@code createArrayOf} is the native idiom).
     */
    private static final String COLLECTION_IN_SETARRAY_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIdArray {
                @StoredProcedure
                public static void flagByIdArray(Connection c, List<Long> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN (?)");
                    ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void collectionInSetArrayDeploysAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdArray.java", COLLECTION_IN_SETARRAY_PROC, "postgresql");
        assertCollectionArrayBound(generated, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_setarr_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");

            callFlagByIdArrayPostgres(pg, 30L, 50L);
            assertEquals(java.util.Set.of(30L, 50L), flaggedIds(pg),
                    "the setArray-over-collection form must array-bind and flag exactly the listed ids");
        }
    }

    @Test
    void collectionInSetArrayDeploysAndFlagsOnMysql() throws Exception {
        // FORM-B (setArray/createArrayOf) is dialect-AGNOSTIC in the recognizer: on MySQL it must lower to
        // the SAME JSON_TABLE membership as FORM A and deploy live. (The committed suite previously had only
        // the PG FORM-B case — this is the missing live-MySQL FORM-B lock.)
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdArray.java", COLLECTION_IN_SETARRAY_PROC, "mysql");
        assertCollectionArrayBound(generated, "mysql");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");

            // Multi-element: the list is bound as ONE JSON-array string; JSON_TABLE explodes it.
            callFlagByJsonList(my, "flag_by_id_array", "[30, 50]");
            assertEquals(java.util.Set.of(30L, 50L), flaggedIds(my),
                    "the FORM-B JSON array bind must flag exactly the listed ids on MySQL (JSON_TABLE)");

            // EMPTY list on FORM B: JSON_TABLE('[]') yields no rows -> matches nothing (no error).
            clearAccountFlagsMysql(my);
            callFlagByJsonList(my, "flag_by_id_array", "[]");
            assertTrue(flaggedIds(my).isEmpty(),
                    "an empty FORM-B JSON array must flag nothing on MySQL: JSON_TABLE('[]') yields no rows");
        }
    }

    // ----- element-type matrix: List<Integer> (INT) and List<String> (LONGTEXT) on BOTH dialects -----
    // These cover the two element types whose MySQL JSON_TABLE COLUMNS spelling was previously broken
    // (SIGNED / CHAR(1000) — ERROR 1064 / 1074 at CALL). Because the membership is built via dynamic
    // PREPARE, CREATE PROCEDURE alone would NOT surface the bug — so every case here CALLs the routine.

    private static final String COLLECTION_IN_INT_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByIntIdList {
                @StoredProcedure
                public static void flagByIntIdList(Connection c, List<Integer> ids) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id IN ("
                                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setInt(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    private static final String COLLECTION_IN_STRING_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByCodeList {
                @StoredProcedure
                public static void flagByCodeList(Connection c, List<String> codes) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE code IN ("
                                    + String.join(",", Collections.nCopies(codes.size(), "?")) + ")");
                    for (int i = 0; i < codes.size(); i++) {
                        ps.setString(i + 1, codes.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void collectionInIntListDeploysAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIntIdList.java", COLLECTION_IN_INT_PROC, "postgresql");
        assertEmittedContains(generated, "postgresql", "id = ANY($1)");
        assertEmittedContainsUpper(generated, "postgresql", "INTEGER[]");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_int_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");

            callFlagByIntListPostgres(pg, 10, 20, 40);
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(pg),
                    "List<Integer> array-bind must flag exactly the listed ids on PG (= ANY($1))");

            clearAccountFlagsPostgres(pg);
            callFlagByIntListPostgres(pg);
            assertTrue(flaggedIds(pg).isEmpty(), "an empty int list must flag nothing on PG");
        }
    }

    @Test
    void collectionInIntListDeploysAndFlagsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIntIdList.java", COLLECTION_IN_INT_PROC, "mysql");
        // The regression: the COLUMNS type must be the valid `INT`, never the CAST keyword `SIGNED`.
        assertEmittedContains(generated, "mysql", "COLUMNS (v INT PATH ''$'')");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");

            // CALL — surfaces the ERROR 1064 the old SIGNED spelling raised here (CREATE alone would pass).
            callFlagByJsonList(my, "flag_by_int_id_list", "[10, 20, 40]");
            assertEquals(java.util.Set.of(10L, 20L, 40L), flaggedIds(my),
                    "List<Integer> JSON array-bind must flag exactly the listed ids on MySQL (INT COLUMNS)");

            clearAccountFlagsMysql(my);
            callFlagByJsonList(my, "flag_by_int_id_list", "[]");
            assertTrue(flaggedIds(my).isEmpty(), "an empty int JSON array must flag nothing on MySQL");
        }
    }

    @Test
    void collectionInStringListDeploysAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByCodeList.java", COLLECTION_IN_STRING_PROC, "postgresql");
        assertEmittedContains(generated, "postgresql", "code = ANY($1)");
        assertEmittedContainsUpper(generated, "postgresql", "TEXT[]");

        String longCode = "x".repeat(1500); // > any bounded width — PG text[] has no length limit.
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_str_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByCodePostgres(pg, longCode);
            deploy(pg, generated, "postgresql");

            // Match by code (incl. the 1500-char code) — flags ids 10 (code 'A') and 30 (the long code).
            callFlagByCodeListPostgres(pg, "A", longCode);
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(pg),
                    "List<String> array-bind must flag exactly the listed codes on PG (= ANY($1)), incl. a long code");

            clearAccountFlagsPostgres(pg);
            callFlagByCodeListPostgres(pg);
            assertTrue(flaggedIds(pg).isEmpty(), "an empty string list must flag nothing on PG");
        }
    }

    @Test
    void collectionInStringListDeploysAndFlagsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByCodeList.java", COLLECTION_IN_STRING_PROC, "mysql");
        // The regression: the COLUMNS type must be a valid wide type (LONGTEXT), never CHAR(1000).
        assertEmittedContains(generated, "mysql", "COLUMNS (v LONGTEXT PATH ''$'')");

        String longCode = "x".repeat(1500); // > CHAR(255)/CHAR(1000): LONGTEXT must keep it (no NULL-trunc).
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByCodeMysql(my, longCode);
            deploy(my, generated, "mysql");

            // CALL — surfaces the ERROR 1074 the old CHAR(1000) spelling raised here. The JSON array carries
            // the 1500-char code; LONGTEXT extraction must NOT truncate it to NULL (row 30 must flag).
            callFlagByJsonList(my, "flag_by_code_list",
                    "[" + jsonString("A") + ", " + jsonString(longCode) + "]");
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(my),
                    "List<String> JSON array-bind must flag exactly the listed codes on MySQL (LONGTEXT), incl. a long code");

            clearAccountFlagsMysql(my);
            callFlagByJsonList(my, "flag_by_code_list", "[]");
            assertTrue(flaggedIds(my).isEmpty(), "an empty string JSON array must flag nothing on MySQL");
        }
    }

    // ===== UUID end-to-end: a java.util.UUID parameter + a List<UUID> collection-IN, both dialects ======
    // PostgreSQL has a native uuid type (param `uuid`, list `uuid[]` via = ANY($1)); MySQL has none, so a
    // UUID lowers to CHAR(36) (canonical 36-char string per charter §1.1a) — the scalar param is CHAR(36)
    // and the list extracts CHAR(36) elements from the bound JSON array. Values are bound, never spliced.

    private static final String EXT_ID_10 = "11111111-1111-1111-1111-111111111111";
    private static final String EXT_ID_20 = "22222222-2222-2222-2222-222222222222";
    private static final String EXT_ID_30 = "33333333-3333-3333-3333-333333333333";

    private static final String SCALAR_UUID_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.UUID;

            class FlagByExtId {
                @StoredProcedure
                public static void flagByExtId(Connection c, UUID extId) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE ext_id = ?");
                    ps.setObject(1, extId);
                    ps.executeUpdate();
                }
            }
            """;

    private static final String COLLECTION_IN_UUID_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByExtIdList {
                @StoredProcedure
                public static void flagByExtIdList(Connection c, List<UUID> extIds) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE ext_id IN ("
                                    + String.join(",", Collections.nCopies(extIds.size(), "?")) + ")");
                    for (int i = 0; i < extIds.size(); i++) {
                        ps.setObject(i + 1, extIds.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void scalarUuidDeploysAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByExtId.java", SCALAR_UUID_PROC, "postgresql");
        assertEmittedContainsUpper(generated, "postgresql", "UUID");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_uuid_scalar_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByExtIdPostgres(pg);
            deploy(pg, generated, "postgresql");

            callFlagByExtIdPostgres(pg, UUID.fromString(EXT_ID_20));
            assertEquals(java.util.Set.of(20L), flaggedIds(pg),
                    "the native uuid bind must flag exactly the row with the matching ext_id");
        }
    }

    @Test
    void scalarUuidDeploysAndFlagsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByExtId.java", SCALAR_UUID_PROC, "mysql");
        assertEmittedContainsUpper(generated, "mysql", "CHAR(36)");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByExtIdMysql(my);
            deploy(my, generated, "mysql");

            // CHAR(36) param: bind the canonical string (the runtime stringifies UUID on MySQL).
            callFlagByExtIdMysql(my, EXT_ID_20);
            assertEquals(java.util.Set.of(20L), flaggedIds(my),
                    "the CHAR(36) bind must flag exactly the row with the matching ext_id");
        }
    }

    @Test
    void collectionInUuidListDeploysAndFlagsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByExtIdList.java", COLLECTION_IN_UUID_PROC, "postgresql");
        assertEmittedContains(generated, "postgresql", "ext_id = ANY($1)");
        assertEmittedContainsUpper(generated, "postgresql", "UUID[]");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_uuid_collin_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByExtIdPostgres(pg);
            deploy(pg, generated, "postgresql");

            callFlagByExtIdListPostgres(pg, UUID.fromString(EXT_ID_10), UUID.fromString(EXT_ID_30));
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(pg),
                    "List<UUID> array-bind must flag exactly the listed ext_ids on PG (= ANY($1))");

            clearAccountFlagsPostgres(pg);
            callFlagByExtIdListPostgres(pg);
            assertTrue(flaggedIds(pg).isEmpty(), "an empty UUID list must flag nothing on PG");
        }
    }

    // ATG-017 (Part 1): a SECURITY DEFINER routine pins search_path to its own schema, so it resolves
    // its own objects even when the CALLER sets a hostile search_path — the classic hardening.
    private static final String SECURITY_DEFINER_FUNC = """
            import titan.dsl.StoredFunction;
            import titan.dsl.SecurityDefiner;
            import java.sql.*;

            class CountAll {
                @StoredFunction
                @SecurityDefiner
                public static long countAll(Connection c) throws SQLException {
                    long n = 0L;
                    PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n FROM accounts");
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        n = rs.getLong("n");
                    }
                    return n;
                }
            }
            """;

    @Test
    void atg017SecurityDefinerPinnedSearchPathResolvesOwnSchemaOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("CountAll.java", SECURITY_DEFINER_FUNC, "postgresql");
        assertEmittedContains(generated, "postgresql", "SET search_path = \"billing\", pg_temp");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_atg017_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg); // creates billing.accounts with 5 rows
            deploy(pg, generated, "postgresql");

            // HOSTILE: the caller's search_path does NOT include billing. Without the pinned search_path
            // the function's unqualified `accounts` would resolve to public.accounts (absent) and fail.
            try (Statement statement = pg.createStatement()) {
                statement.execute("SET search_path TO public");
            }
            try (PreparedStatement statement = pg.prepareStatement("SELECT " + SCHEMA + ".count_all()")) {
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "the function must return a row");
                    assertEquals(5L, resultSet.getLong(1),
                            "the pinned search_path must resolve billing.accounts despite caller search_path=public");
                }
            }
        }
    }

    // ATG-017b: the privilege policy lets a low-privilege role EXECUTE the SECURITY DEFINER routine
    // (which does privileged work as its owner) while that role is DENIED direct access to the table.
    private static final String SECURITY_DEFINER_POLICY_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SecurityDefiner;
            import java.sql.*;

            class FlagOne {
                @StoredProcedure
                @SecurityDefiner(executeRoles = {"titan_app_atg017b"}, revokePublic = true)
                public static void flagOne(Connection c, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void atg017bPolicyGrantsExecuteToLowPrivRoleWhileDirectTableAccessDeniedOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagOne.java", SECURITY_DEFINER_POLICY_PROC, "postgresql");
        // The policy is a SEPARATE artifact, so scan all PG artifacts (not just the routine).
        String allPostgres = generated.stream()
                .filter(artifact -> "postgresql".equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(allPostgres.contains("GRANT EXECUTE ON PROCEDURE"), allPostgres);
        assertTrue(allPostgres.contains("REVOKE ALL ON PROCEDURE"), allPostgres);

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_atg017b_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg); // billing.accounts, owned by the connecting role
            try (Statement statement = pg.createStatement()) {
                statement.execute("DROP ROLE IF EXISTS titan_app_atg017b");
                statement.execute("CREATE ROLE titan_app_atg017b");
                statement.execute("GRANT USAGE ON SCHEMA billing TO titan_app_atg017b");
            }
            deploy(pg, generated, "postgresql"); // creates the proc, REVOKEs PUBLIC, GRANTs EXECUTE to the role

            try (Statement statement = pg.createStatement()) {
                statement.execute("SET ROLE titan_app_atg017b");
            }
            // The low-priv role can EXECUTE the routine (which runs as the table-owning definer).
            try (PreparedStatement statement = pg.prepareStatement("CALL " + SCHEMA + ".flag_one(?)")) {
                statement.setLong(1, 10L);
                statement.execute();
            }
            // ...but is DENIED direct access to the underlying table.
            SQLException denied = assertThrows(SQLException.class, () -> {
                try (Statement statement = pg.createStatement();
                        var resultSet = statement.executeQuery("SELECT id FROM " + SCHEMA + ".accounts")) {
                    resultSet.next();
                }
            });
            assertTrue(denied.getMessage().toLowerCase(java.util.Locale.ROOT).contains("permission denied"),
                    "direct table access must be denied for the low-priv role; was: " + denied.getMessage());

            try (Statement statement = pg.createStatement()) {
                statement.execute("RESET ROLE");
            }
            assertTrue(flaggedIds(pg).contains(10L),
                    "the EXECUTE-granted SECURITY DEFINER routine must have flagged id 10 as its owner");
        }
    }

    // ATG-002b: a java.util.byte[] parameter is an opaque binary payload — PostgreSQL BYTEA, MySQL
    // LONGBLOB — not INTEGER[]. It must deploy and round-trip a real byte[] on both databases.
    private static final String BYTES_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class SetHash {
                @StoredProcedure
                public static void setHash(Connection c, long id, byte[] hash) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE accounts SET hash = ? WHERE id = ?");
                    ps.setBytes(1, hash);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
            """;

    private static final byte[] HASH_VALUE = {1, 2, 3, (byte) 0xFF, 0, 42, (byte) 0x80};

    @Test
    void atg002bByteArrayParamDeploysAndRoundTripsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SetHash.java", BYTES_PROC, "postgresql");
        assertEmittedContainsUpper(generated, "postgresql", "BYTEA");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_atg002b_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedHashAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");

            callSetHash(pg, 10L, HASH_VALUE);
            assertArrayEquals(HASH_VALUE, readHash(pg, 10L), "byte[] param must round-trip as BYTEA");
        }
    }

    @Test
    void atg002bByteArrayParamDeploysAndRoundTripsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SetHash.java", BYTES_PROC, "mysql");
        assertEmittedContainsUpper(generated, "mysql", "LONGBLOB");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedHashAccountsMysql(my);
            deploy(my, generated, "mysql");

            callSetHash(my, 10L, HASH_VALUE);
            assertArrayEquals(HASH_VALUE, readHash(my, 10L), "byte[] param must round-trip as LONGBLOB");
        }
    }

    private static void seedHashAccountsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts (id BIGINT PRIMARY KEY, hash BYTEA)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id) VALUES (10), (20)");
        }
    }

    private static void seedHashAccountsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts (id BIGINT PRIMARY KEY, hash LONGBLOB)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id) VALUES (10), (20)");
        }
    }

    private static void callSetHash(Connection connection, long id, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".set_hash(?, ?)")) {
            statement.setLong(1, id);
            statement.setBytes(2, hash);
            statement.execute();
        }
    }

    private static byte[] readHash(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT hash FROM " + SCHEMA + ".accounts WHERE id = ?")) {
            statement.setLong(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "account row must exist");
                return resultSet.getBytes(1);
            }
        }
    }

    // ATG-001: the supported scalar single-row count read — assign into a local inside if (rs.next()),
    // then return the local — must deploy + return the right count on PG. (The direct-return sugar
    // `if (rs.next()) return rs.getLong(c)` is rejected with an actionable diagnostic; see JdbcLoweringTest.)
    private static final String SCALAR_COUNT_FUNC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;

            class CountByTier {
                @StoredFunction
                public static long countByTier(Connection c, String tier) throws SQLException {
                    long n = 0L;
                    PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n FROM accounts WHERE tier = ?");
                    ps.setString(1, tier);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        n = rs.getLong("n");
                    }
                    return n;
                }
            }
            """;

    @Test
    void atg001ScalarCountReadDeploysAndReturnsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("CountByTier.java", SCALAR_COUNT_FUNC, "postgresql");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_atg001_count_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");

            try (PreparedStatement statement = pg.prepareStatement("SELECT " + SCHEMA + ".count_by_tier(?)")) {
                statement.setString(1, "GOLD");
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "the count function must return a row");
                    assertEquals(2L, resultSet.getLong(1), "GOLD accounts are ids 10 and 30");
                }
            }
        }
    }

    private static final String SCALAR_UUID_READ_FUNC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.UUID;

            class GetExtId {
                @StoredFunction
                public static UUID getExtId(Connection c, long id) throws SQLException {
                    UUID result = null;
                    PreparedStatement ps = c.prepareStatement("SELECT ext_id FROM accounts WHERE id = ?");
                    ps.setLong(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        result = rs.getObject(1, UUID.class);
                    }
                    return result;
                }
            }
            """;

    @Test
    void uuidReadViaTypedGetObjectDeploysAndReturnsOnPostgres() throws Exception {
        // There is no rs.getUuid; getObject(col, UUID.class) is the only idiomatic UUID read. This is the
        // live arbiter for the recognizer/lowerer divergence the audit caught (empty INTO never deployed).
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("GetExtId.java", SCALAR_UUID_READ_FUNC, "postgresql");
        assertEmittedContainsUpper(generated, "postgresql", "UUID");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_uuid_read_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByExtIdPostgres(pg);
            deploy(pg, generated, "postgresql");

            try (PreparedStatement statement = pg.prepareStatement("SELECT " + SCHEMA + ".get_ext_id(?)")) {
                statement.setLong(1, 20L);
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "the function must return a row");
                    assertEquals(UUID.fromString(EXT_ID_20), resultSet.getObject(1, UUID.class),
                            "the UUID single-row read must return account 20's ext_id");
                }
            }
        }
    }

    @Test
    void collectionInUuidListDeploysAndFlagsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByExtIdList.java", COLLECTION_IN_UUID_PROC, "mysql");
        // The element COLUMNS type must be the valid CHAR(36), extracting each canonical UUID string.
        assertEmittedContains(generated, "mysql", "COLUMNS (v CHAR(36) PATH ''$'')");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByExtIdMysql(my);
            deploy(my, generated, "mysql");

            // The list is bound as ONE JSON-array string of UUIDs; JSON_TABLE explodes it to CHAR(36) rows.
            callFlagByJsonList(my, "flag_by_ext_id_list", jsonStringArray(EXT_ID_10, EXT_ID_30));
            assertEquals(java.util.Set.of(10L, 30L), flaggedIds(my),
                    "List<UUID> JSON array-bind must flag exactly the listed ext_ids on MySQL (CHAR(36) COLUMNS)");

            clearAccountFlagsMysql(my);
            callFlagByJsonList(my, "flag_by_ext_id_list", "[]");
            assertTrue(flaggedIds(my).isEmpty(), "an empty UUID JSON array must flag nothing on MySQL");
        }
    }

    // ----- NULL-element equivalence: a NULL array/JSON element matches nothing via `=`, on BOTH dialects --

    @Test
    void collectionInNullElementMatchesNothingOnPostgres() throws Exception {
        // A NULL element under `= ANY($1)` behaves exactly as a bound IN-list: a NULL never matches via `=`.
        // Bind {30, NULL} -> only id 30 flags (NULL flags nothing, no error). Locks the documented NULL
        // equivalence live on PG16.
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdList.java", COLLECTION_IN_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_null_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");

            try (PreparedStatement statement = pg.prepareStatement("CALL " + SCHEMA + ".flag_by_id_list(?)")) {
                statement.setArray(1, pg.createArrayOf("bigint", new Long[] {30L, null}));
                statement.execute();
            }
            assertEquals(java.util.Set.of(30L), flaggedIds(pg),
                    "a NULL array element must match nothing via `=`; only the real id (30) flags");
        }
    }

    @Test
    void collectionInNullElementMatchesNothingOnMysql() throws Exception {
        // The MySQL analogue: JSON `[30, null]` through JSON_TABLE yields rows {30, NULL}; the NULL never
        // matches via `=`, so only id 30 flags. Locks the documented NULL equivalence live on MySQL 8.4.
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByIdList.java", COLLECTION_IN_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsMysql(my);
            deploy(my, generated, "mysql");

            callFlagByJsonList(my, "flag_by_id_list", "[30, null]");
            assertEquals(java.util.Set.of(30L), flaggedIds(my),
                    "a NULL JSON element must match nothing via `=`; only the real id (30) flags");
        }
    }

    // ----- String elements with JSON/quote metacharacters: bound/extracted, never spliced, on BOTH --------

    private static final String META_CODE = "a\"b\\c"; // a double-quote AND a backslash (JSON metachars).
    private static final String QUOTE_CODE = "O'Brien"; // a single quote (a SQL metachar, not a JSON one).

    @Test
    void collectionInStringMetacharElementDeploysOnPostgres() throws Exception {
        // §3.6 STRICT-safe lock: a List<String> element containing a double-quote, backslash, and single
        // quote is BOUND through `= ANY($1)` (native text[]) and compared by value — never spliced, no
        // corruption, no injection. Flags exactly the two matching rows.
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByCodeList.java", COLLECTION_IN_STRING_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r4_collin_meta_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByCodesPostgres(pg, META_CODE, QUOTE_CODE);
            deploy(pg, generated, "postgresql");

            try (PreparedStatement statement = pg.prepareStatement("CALL " + SCHEMA + ".flag_by_code_list(?)")) {
                statement.setArray(1, pg.createArrayOf("text", new String[] {META_CODE, QUOTE_CODE}));
                statement.execute();
            }
            assertEquals(java.util.Set.of(10L, 20L), flaggedIds(pg),
                    "metachar string elements must bind/compare by value (= ANY($1)) — flags rows 10 and 20");
        }
    }

    @Test
    void collectionInStringMetacharElementDeploysOnMysql() throws Exception {
        // The MySQL analogue: the elements are carried in a properly JSON-encoded array (`"` and `\`
        // escaped) bound as ONE JSON param, exploded by JSON_TABLE(LONGTEXT), and compared by value. The
        // single quote `'` is not a JSON metachar (no escaping) but proves no SQL-literal splicing occurs.
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByCodeList.java", COLLECTION_IN_STRING_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedFlagAccountsByCodesMysql(my, META_CODE, QUOTE_CODE);
            deploy(my, generated, "mysql");

            callFlagByJsonList(my, "flag_by_code_list", jsonStringArray(META_CODE, QUOTE_CODE));
            assertEquals(java.util.Set.of(10L, 20L), flaggedIds(my),
                    "metachar string elements must JSON-encode/bind/compare by value (JSON_TABLE) — flags rows 10 and 20");
        }
    }

    // ===== Design contract D4: deploy-prove optional-filter GUARDED PREDICATES (the arbiter). =========
    // The pervasive search-builder idiom — a constant base + a finite sequence of `if (param != null)
    // sql.append(" AND col OP ?")` optional clauses — lowers under STRICT to ONE STATIC query using
    // guarded predicates `AND (? IS NULL OR col OP ?)` (no dynamic EXECUTE, every value bound). This MUST
    // deploy and return the EXACT rows the conditional builder would, for EVERY param combination, on PG
    // 16 AND MySQL 8.4. The case uses mixed operators (=, >=, LIKE) over 3 optional filters; the WHERE
    // transform flags matching rows (an UPDATE, so it is a @StoredProcedure) and the flagged set is read
    // back. Param combinations: all-null (base only -> every active row), each-single, and all-set.

    /**
     * The canonical D4 search builder: a base {@code WHERE active = true} extended by 3 optional clauses —
     * {@code tier = ?} (text, EQ), {@code balance >= ?} (numeric, GE), {@code name LIKE ?} (text, LIKE) —
     * each gated on a {@code param != null} NULL-check and bound by a correlated gated {@code setXxx}. It
     * lowers to ONE static {@code … WHERE active = true AND (? IS NULL OR tier = ?) AND (? IS NULL OR
     * balance >= ?) AND (? IS NULL OR name LIKE ?)} (each param bound twice), flagging the matching rows.
     */
    private static final String SEARCH_BUILDER_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.math.BigDecimal;

            class SearchAccounts {
                @StoredProcedure
                public static void searchAccounts(Connection c, String tier, BigDecimal minBalance,
                                                  String namePat) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "UPDATE accounts SET flagged = 1 WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    if (minBalance != null) { sql.append(" AND balance >= ?"); }
                    if (namePat != null) { sql.append(" AND name LIKE ?"); }
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                    if (namePat != null) ps.setString(i++, namePat);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void guardedPredicateSearchDeploysAndMatchesAllCombinationsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SearchAccounts.java", SEARCH_BUILDER_PROC, "postgresql");
        // The lowered query is STATIC guarded predicates — no dynamic format()/EXECUTE assembly, every
        // value bound. Prove the guarded-predicate shape made it to the emitted SQL.
        assertEmittedContains(generated, "postgresql", "($1 IS NULL OR tier = $2)");
        assertEmittedContains(generated, "postgresql", "balance >= ");
        assertEmittedContains(generated, "postgresql", "name LIKE ");
        assertFalse(emittedSql(generated, "postgresql").contains("format("),
                "the guarded predicate must be static — no runtime format() assembly");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_d4_guarded_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            assertGuardedSearchCombinations(pg);
        }
    }

    @Test
    void guardedPredicateSearchDeploysAndMatchesAllCombinationsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SearchAccounts.java", SEARCH_BUILDER_PROC, "mysql");
        // On MySQL the guarded predicate is identical SQL — `(? IS NULL OR col OP ?)` — bound via the
        // PREPARE/EXECUTE USING @p substrate. No JSON_TABLE (it is not a collection bind).
        assertEmittedContains(generated, "mysql", "(? IS NULL OR tier = ?)");
        assertEmittedContains(generated, "mysql", "(? IS NULL OR balance >= ?)");
        assertEmittedContains(generated, "mysql", "(? IS NULL OR name LIKE ?)");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsMysql(my);
            deploy(my, generated, "mysql");
            assertGuardedSearchCombinations(my);
        }
    }

    /**
     * The canonical D4 base extended by an explicit ALL-NULL deploy lock: with every optional filter null,
     * the routine must flag exactly the base-predicate rows (every active row) — the guards collapse to
     * TRUE. This is the param combination the auditors singled out as critical (the most common search
     * invocation); it deploys and runs identically on PG 16 and MySQL 8.4. (The full per-combination sweep
     * is {@link #assertGuardedSearchCombinations}; this is the named, isolated all-null DEPLOY case.)
     */
    @Test
    void guardedPredicateSearchAllNullCallFlagsBasePredicateRowsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SearchAccounts.java", SEARCH_BUILDER_PROC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_d4_allnull_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            runSearch(pg, null, null, null); // all-null: the base predicate (active = true) alone.
            assertEquals(java.util.Set.of(1L, 2L, 3L, 5L), flaggedIds(pg),
                    "the all-null CALL must flag every active row (the base predicate), with no SQL error");
        }
    }

    @Test
    void guardedPredicateSearchAllNullCallFlagsBasePredicateRowsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("SearchAccounts.java", SEARCH_BUILDER_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsMysql(my);
            deploy(my, generated, "mysql");
            runSearch(my, null, null, null); // all-null: the base predicate (active = true) alone.
            assertEquals(java.util.Set.of(1L, 2L, 3L, 5L), flaggedIds(my),
                    "the all-null CALL must flag every active row (the base predicate), with no SQL error");
        }
    }

    // A search builder whose base WHERE is NOT the terminal top-level predicate — a trailing ORDER BY +
    // LIMIT (the auditors' PG repro). The guard ` AND (? IS NULL OR tier = ?)`, always appended at the END,
    // would land after `LIMIT 2` (`LIMIT 2 AND (…)` → PG parses `LIMIT (2 AND …)` → "argument of AND must
    // be type boolean"; MySQL → syntax error) and the ALL-NULL CALL — which the conditional builder serves
    // validly with the bare base — would be a hard runtime error. The recognizer FAILS SAFE on such a base,
    // and the unrecovered StringBuilder is then an unsupported P0 construct under STRICT → the transpile is
    // REJECTED. This locks that NO malformed guarded-predicate artifact is ever emitted/deployed, so that
    // all-null runtime error is unreachable (the auditor-allowed "rejects at transpile" outcome).
    private static final String TRAILING_LIMIT_SEARCH_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class SearchAccountsLimit {
                @StoredProcedure
                public static void searchAccountsLimit(Connection c, String tier) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "UPDATE accounts SET flagged = 1 WHERE active = true ORDER BY id LIMIT 2");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    if (tier != null) ps.setString(1, tier);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void guardedPredicateTrailingLimitBaseIsRejectedAtTranspileNotDeployedMalformed() throws Exception {
        // Both dialects must REJECT the trailing ORDER BY/LIMIT base at transpile (TITAN-E001), so no
        // malformed `… LIMIT 2 AND (…)` artifact is ever produced or deployed. This is the fix for the
        // auditors' live all-null failure: the divergent runtime error is unreachable because the artifact
        // does not exist. (Contrast: the canonical terminal-WHERE base deploys + serves all-null above.)
        for (String dialect : List.of("postgresql", "mysql")) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> transpileSource("SearchAccountsLimit.java", TRAILING_LIMIT_SEARCH_PROC, dialect),
                    "a base whose WHERE is followed by ORDER BY/LIMIT must not transpile to a deployable "
                            + "guarded predicate on " + dialect);
            assertTrue(thrown.getMessage().contains("TITAN-E001"),
                    "the rejection must be TITAN-E001 (unsupported construct), not a malformed emission; "
                            + "was: " + thrown.getMessage());
        }
    }

    /**
     * Asserts the guarded-predicate search returns the EXACT rows the conditional builder would, for each
     * param combination, against the shared seed. Each combination clears the flags first, CALLs the
     * routine with the given (nullable) filters, and asserts the flagged-id set equals what manually
     * applying ONLY the non-null clauses yields — the semantic-equivalence proof, live.
     *
     * <p>Seed (id, tier, balance, name, active):
     * 1 GOLD 100 Alice  T, 2 GOLD 50 Bob T, 3 SILVER 200 Alicia T, 4 GOLD 100 Carol F (inactive),
     * 5 SILVER 30 Dave T. The base predicate is {@code active = true}, so id 4 never matches.</p>
     */
    private static void assertGuardedSearchCombinations(Connection connection) throws SQLException {
        // (a) all-null -> base only (active = true) -> every active row: 1,2,3,5 (NOT 4, inactive).
        runSearch(connection, null, null, null);
        assertEquals(java.util.Set.of(1L, 2L, 3L, 5L), flaggedIds(connection),
                "all-null must match the base predicate only (every active row)");

        // (b) tier = GOLD only -> active GOLD rows: 1,2 (4 is GOLD but inactive).
        runSearch(connection, "GOLD", null, null);
        assertEquals(java.util.Set.of(1L, 2L), flaggedIds(connection),
                "tier=GOLD alone must match active GOLD rows (the (? IS NULL OR tier=?) guard)");

        // (c) balance >= 100 only -> active rows with balance >= 100: 1 (100), 3 (200). (2=50, 5=30 out.)
        runSearch(connection, null, new BigDecimal("100"), null);
        assertEquals(java.util.Set.of(1L, 3L), flaggedIds(connection),
                "balance>=100 alone must match active rows with balance>=100");

        // (d) name LIKE 'Ali%' only -> active rows whose name starts 'Ali': 1 (Alice), 3 (Alicia).
        // NOTE on dialect scope: D4 emits `name LIKE ?` VERBATIM on both dialects (exactly hand-written
        // JDBC) and does NOT inject any collation forcing — so a LIKE filter inherits the target engine's
        // default collation case-sensitivity (PostgreSQL: case-SENSITIVE; MySQL default *_ci: case-
        // INSENSITIVE). This case uses an EXACT-CASE prefix ('Ali%' vs names 'Alice'/'Alicia'), so it
        // matches the SAME rows {1,3} on BOTH PG and MySQL — the cross-dialect agreement asserted here is
        // intentional and case-exact. A lowercase pattern (e.g. 'ali%') would diverge (PG -> {}, MySQL ->
        // {1,3}); that is faithful reproduction of engine semantics, not a transpiler divergence, so D4
        // guarantees byte-identical row sets across dialects for LIKE only when the pattern case matches.
        runSearch(connection, null, null, "Ali%");
        assertEquals(java.util.Set.of(1L, 3L), flaggedIds(connection),
                "name LIKE 'Ali%' (exact-case prefix) alone must match active rows whose name starts Ali "
                        + "on both PG and MySQL");

        // (e) all-set: tier=GOLD AND balance>=100 AND name LIKE 'Ali%' -> active GOLD, bal>=100, Ali%: just 1.
        runSearch(connection, "GOLD", new BigDecimal("100"), "Ali%");
        assertEquals(java.util.Set.of(1L), flaggedIds(connection),
                "all three filters set must match the single row satisfying all (id 1 Alice)");

        // (f) tier=GOLD AND balance>=100 (name null) -> active GOLD with bal>=100: id 1 (100). (2=50 out.)
        runSearch(connection, "GOLD", new BigDecimal("100"), null);
        assertEquals(java.util.Set.of(1L), flaggedIds(connection),
                "two filters set (name omitted) must match active GOLD rows with balance>=100");

        // (g) a tier that matches no row -> empty (the guard reduces to a false comparison, no rows).
        runSearch(connection, "PLATINUM", null, null);
        assertTrue(flaggedIds(connection).isEmpty(),
                "a tier matching no row must flag nothing");
    }

    /** Clears the flags, then CALLs search_accounts with the (nullable) tier / minBalance / namePat. */
    private static void runSearch(
            Connection connection, String tier, BigDecimal minBalance, String namePat) throws SQLException {
        try (Statement clear = connection.createStatement()) {
            clear.execute("UPDATE " + SCHEMA + ".accounts SET flagged = 0");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".search_accounts(?, ?, ?)")) {
            if (tier == null) {
                statement.setNull(1, Types.VARCHAR);
            } else {
                statement.setString(1, tier);
            }
            if (minBalance == null) {
                statement.setNull(2, Types.NUMERIC);
            } else {
                statement.setBigDecimal(2, minBalance);
            }
            if (namePat == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, namePat);
            }
            statement.execute();
        }
    }

    /** Seeds the search accounts table (id, tier, balance, name, active, flagged) on PostgreSQL. */
    private static void seedSearchAccountsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL, balance NUMERIC NOT NULL, "
                    + "name TEXT NOT NULL, active BOOLEAN NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, balance, name, active) VALUES "
                    + "(1, 'GOLD', 100, 'Alice', true), (2, 'GOLD', 50, 'Bob', true), "
                    + "(3, 'SILVER', 200, 'Alicia', true), (4, 'GOLD', 100, 'Carol', false), "
                    + "(5, 'SILVER', 30, 'Dave', true)");
        }
    }

    /** Seeds the same search accounts table on MySQL (BOOLEAN -> TINYINT(1), the guard compares = true). */
    private static void seedSearchAccountsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL, balance DECIMAL(12,2) NOT NULL, "
                    + "name VARCHAR(64) NOT NULL, active BOOLEAN NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, balance, name, active) VALUES "
                    + "(1, 'GOLD', 100, 'Alice', true), (2, 'GOLD', 50, 'Bob', true), "
                    + "(3, 'SILVER', 200, 'Alicia', true), (4, 'GOLD', 100, 'Carol', false), "
                    + "(5, 'SILVER', 30, 'Dave', true)");
        }
    }

    // ===== Design contract D4 (ORDERED extension): the ORDERED / PAGINATED search builder. ===========
    // The ordered-builder idiom: a constant base + optional `if (param != null) sql.append(" AND col OP ?")`
    // filters, followed by an UNCONDITIONAL trailing constant ` ORDER BY <col>`. D4 lowers it to ONE STATIC
    // query `<base> <guards> ORDER BY <col>` — the guards at the WHERE boundary, the constant ORDER BY after
    // them. The arbiter: the lowered routine must return the EXACT id LIST — rows AND order — the conditional
    // builder would, for EVERY param combination, on PG 16 AND MySQL 8.4. We assert against the live
    // conditional-builder query (the hand-written WHERE with only the non-null filters + the same ORDER BY).
    //
    // ORDER BY column choice: `id` (the PRIMARY KEY — NON-NULL) so the cross-dialect row-order assertion is
    // EXACT (PG's NULLS-LAST vs MySQL's NULLS-FIRST default never bites a non-null sort column). DESC, so the
    // order is observable (not coincidentally the default insertion order). The builder is a @StoredFunction
    // returning the ordered rows (the Rung-5 generic-reader carrier marshals the result); D4 owns ONLY the
    // WHERE+tail transform of the SELECT it prepares — so this exercises the guards-before-tail lowering live.

    private static final String ORDERED_SEARCH_BUILDER_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.math.BigDecimal;
            import java.util.*;

            class OrderedSearch {
                @StoredFunction
                public static List<Map<String,Object>> orderedSearch(Connection c, String tier,
                        BigDecimal minBalance, String namePat) throws SQLException {
                    StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    if (minBalance != null) { sql.append(" AND balance >= ?"); }
                    if (namePat != null) { sql.append(" AND name LIKE ?"); }
                    sql.append(" ORDER BY id DESC");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    if (minBalance != null) ps.setBigDecimal(i++, minBalance);
                    if (namePat != null) ps.setString(i++, namePat);
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int j = 1; j <= md.getColumnCount(); j++) {
                            row.put(md.getColumnLabel(j), rs.getObject(j));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    @Test
    void orderedGuardedPredicateSearchDeploysAndMatchesAllCombinationsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("OrderedSearch.java", ORDERED_SEARCH_BUILDER_FN, "postgresql");
        // The guards sit at the WHERE boundary; the constant ORDER BY follows them — ONE static query, no
        // runtime format() assembly. The guard-then-tail shape made it verbatim into the emitted SQL.
        assertEmittedContains(generated, "postgresql",
                "WHERE active = true AND ($1 IS NULL OR tier = $2)");
        assertEmittedContains(generated, "postgresql", "ORDER BY id DESC");
        assertFalse(emittedSql(generated, "postgresql").contains("format("),
                "the ordered guarded predicate must be static — no runtime format() assembly");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_d4_ordered_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            assertOrderedSearchCombinations(pg, "postgresql");
        }
    }

    @Test
    void orderedGuardedPredicateSearchDeploysAndMatchesAllCombinationsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("OrderedSearch.java", ORDERED_SEARCH_BUILDER_FN, "mysql");
        assertEmittedContains(generated, "mysql",
                "WHERE active = true AND (? IS NULL OR tier = ?)");
        assertEmittedContains(generated, "mysql", "ORDER BY id DESC");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSearchAccountsMysql(my);
            deploy(my, generated, "mysql");
            assertOrderedSearchCombinations(my, "mysql");
        }
    }

    /**
     * The arbiter: for each param combination, the ORDERED-builder routine must return the EXACT id LIST —
     * the same rows IN THE SAME ORDER — the conditional builder would. The expected list is computed LIVE
     * by running the conditional-builder-equivalent query (the hand-written WHERE with ONLY the non-null
     * filters + the same {@code ORDER BY id DESC}) directly against the seed. Equality of the ordered {@code
     * List<Long>} (not a Set) is the semantic-equivalence + ordering proof, deployed.
     *
     * <p>Seed (id, tier, balance, name, active): 1 GOLD 100 Alice T, 2 GOLD 50 Bob T, 3 SILVER 200 Alicia T,
     * 4 GOLD 100 Carol F (inactive), 5 SILVER 30 Dave T. Base predicate {@code active = true} (id 4 never
     * matches); {@code ORDER BY id DESC} so an active-row result lists ids high→low.</p>
     */
    private static void assertOrderedSearchCombinations(Connection connection, String dialect)
            throws SQLException {
        // Each combination: the routine's ordered ids must equal the conditional builder's ordered ids.
        java.util.List<Object[]> combinations = java.util.List.of(
                new Object[]{null, null, null},                                  // all-null: every active row.
                new Object[]{"GOLD", null, null},                                 // tier=GOLD: active GOLD rows.
                new Object[]{null, new BigDecimal("100"), null},                  // balance>=100.
                new Object[]{null, null, "Ali%"},                                 // name LIKE 'Ali%' (exact case).
                new Object[]{"GOLD", new BigDecimal("100"), null},                // two filters.
                new Object[]{"GOLD", new BigDecimal("100"), "Ali%"},              // all three filters.
                new Object[]{"PLATINUM", null, null});                            // matches no row -> empty.
        for (Object[] combo : combinations) {
            String tier = (String) combo[0];
            BigDecimal minBalance = (BigDecimal) combo[1];
            String namePat = (String) combo[2];
            java.util.List<Long> expected = expectedOrderedIds(connection, tier, minBalance, namePat);
            java.util.List<Long> actual = runOrderedSearchIds(connection, dialect, tier, minBalance, namePat);
            assertEquals(expected, actual, "the ordered guarded-predicate routine must return the SAME ids "
                    + "IN THE SAME ORDER as the conditional builder on " + dialect
                    + " for tier=" + tier + ", minBalance=" + minBalance + ", namePat=" + namePat);
        }
        // Explicit all-null ORDER assertion (the auditors' critical case): high→low active ids.
        assertEquals(java.util.List.of(5L, 3L, 2L, 1L),
                runOrderedSearchIds(connection, dialect, null, null, null),
                "the all-null CALL must return every active row, ordered by id DESC, on " + dialect);
    }

    /**
     * The conditional builder's expected ordered ids: the hand-written WHERE with ONLY the non-null filters
     * (each appended exactly as the source's optional clause) + the same {@code ORDER BY id DESC}, bound and
     * run directly. This is the ground truth the D4-lowered routine must reproduce (rows + order).
     */
    private static java.util.List<Long> expectedOrderedIds(
            Connection connection, String tier, BigDecimal minBalance, String namePat) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM " + SCHEMA + ".accounts WHERE active = true");
        if (tier != null) {
            sql.append(" AND tier = ?");
        }
        if (minBalance != null) {
            sql.append(" AND balance >= ?");
        }
        if (namePat != null) {
            sql.append(" AND name LIKE ?");
        }
        sql.append(" ORDER BY id DESC");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            if (tier != null) {
                statement.setString(i++, tier);
            }
            if (minBalance != null) {
                statement.setBigDecimal(i++, minBalance);
            }
            if (namePat != null) {
                statement.setString(i++, namePat);
            }
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * CALLs the deployed ordered-search routine with the (nullable) filters and returns its returned ids IN
     * THE ROUTINE'S RETURN ORDER. PostgreSQL: the carrier is a jsonb function; {@code jsonb_array_elements}
     * preserves array order, so the ids come out ordered. MySQL: the carrier is a PROCEDURE streaming an
     * ordered result set; the ids come out in stream order.
     */
    private static java.util.List<Long> runOrderedSearchIds(
            Connection connection, String dialect, String tier, BigDecimal minBalance, String namePat)
            throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (dialect.equals("postgresql")) {
            // Ordinality preserves the jsonb array order; cast each element's id to bigint.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT (elem->>'id')::bigint FROM jsonb_array_elements("
                            + SCHEMA + ".ordered_search(?, ?, ?)) WITH ORDINALITY AS t(elem, ord) ORDER BY ord")) {
                bindOrderedSearchParams(statement, tier, minBalance, namePat);
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getLong(1));
                    }
                }
            }
            return ids;
        }
        // MySQL: CALL the procedure and stream the ordered result set's id column.
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".ordered_search(?, ?, ?)")) {
            bindOrderedSearchParams(statement, tier, minBalance, namePat);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong("id"));
                }
            }
        }
        return ids;
    }

    /** Binds the (nullable) tier / minBalance / namePat into a 3-param ordered-search CALL. */
    private static void bindOrderedSearchParams(
            PreparedStatement statement, String tier, BigDecimal minBalance, String namePat)
            throws SQLException {
        if (tier == null) {
            statement.setNull(1, Types.VARCHAR);
        } else {
            statement.setString(1, tier);
        }
        if (minBalance == null) {
            statement.setNull(2, Types.NUMERIC);
        } else {
            statement.setBigDecimal(2, minBalance);
        }
        if (namePat == null) {
            statement.setNull(3, Types.VARCHAR);
        } else {
            statement.setString(3, namePat);
        }
    }

    // ===== Design contract D4 (PARAMETERIZED pagination): the ? in the tail is a count/offset VALUE bind. ==
    // The paginated-builder idiom: a constant base + an optional `if (tier != null) sql.append(" AND tier =
    // ?")` filter, an UNCONDITIONAL trailing ` ORDER BY id <DIR> <PAGINATION>` where PAGINATION carries ?s in
    // count/offset position, and UNCONDITIONAL `ps.setInt(i++, …)` binds for those ?s. D4 lowers it to ONE
    // STATIC query `<base> <guard> ORDER BY id <DIR> <PAGINATION>` with USING binds [tier, tier, <pagination
    // binds in tail-? text order>] — clause binds first, pagination binds last. The arbiter: the routine must
    // return the EXACT id LIST — rows AND order AND count — the conditional builder would, for every (filter ×
    // page) combination, on its OWN engine. The pagination forms are single-dialect-native: PG `LIMIT ? OFFSET
    // ?`; MySQL `LIMIT ?, ?` (offset, count) AND `LIMIT ? OFFSET ?`. ORDER BY the NON-NULL primary key id so
    // the cross-call order assertion is exact (PG NULLS-LAST vs MySQL NULLS-FIRST never bites a non-null sort).
    // The builder is a @StoredFunction returning the page rows (the Rung-5 generic-reader carrier marshals the
    // result); D4 owns ONLY the WHERE+pagination transform of the SELECT it prepares.

    // PG-native: ` ORDER BY id LIMIT ? OFFSET ?` (count, offset — tail-? text order pageSize, offset).
    private static final String PAGED_SEARCH_PG_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class PagedSearchPg {
                @StoredFunction
                public static List<Map<String,Object>> pagedSearchPg(Connection c, String tier,
                        int pageSize, int offset) throws SQLException {
                    StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    ps.setInt(i++, pageSize);
                    ps.setInt(i++, offset);
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int j = 1; j <= md.getColumnCount(); j++) {
                            row.put(md.getColumnLabel(j), rs.getObject(j));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    // MySQL-native: ` ORDER BY id LIMIT ?, ?` (offset, count — tail-? text order offset, pageSize).
    private static final String PAGED_SEARCH_MYSQL_COMMA_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class PagedSearchMyComma {
                @StoredFunction
                public static List<Map<String,Object>> pagedSearchMyComma(Connection c, String tier,
                        int offset, int pageSize) throws SQLException {
                    StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    sql.append(" ORDER BY id LIMIT ?, ?");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    ps.setInt(i++, offset);
                    ps.setInt(i++, pageSize);
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int j = 1; j <= md.getColumnCount(); j++) {
                            row.put(md.getColumnLabel(j), rs.getObject(j));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    // MySQL-native: ` ORDER BY id LIMIT ? OFFSET ?` (also valid on MySQL; count, offset).
    private static final String PAGED_SEARCH_MYSQL_OFFSET_FN = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class PagedSearchMyOffset {
                @StoredFunction
                public static List<Map<String,Object>> pagedSearchMyOffset(Connection c, String tier,
                        int pageSize, int offset) throws SQLException {
                    StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    ps.setInt(i++, pageSize);
                    ps.setInt(i++, offset);
                    ResultSet rs = ps.executeQuery();
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int j = 1; j <= md.getColumnCount(); j++) {
                            row.put(md.getColumnLabel(j), rs.getObject(j));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            """;

    @Test
    void paginatedGuardedPredicateSearchDeploysAndMatchesPageBoundariesOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("PagedSearchPg.java", PAGED_SEARCH_PG_FN, "postgresql");
        // ONE static query: the guard at the WHERE boundary, the pagination ?s ($n) in the verbatim tail.
        assertEmittedContains(generated, "postgresql",
                "WHERE active = true AND ($1 IS NULL OR tier = $2) ORDER BY id LIMIT $3 OFFSET $4");
        assertFalse(emittedSql(generated, "postgresql").contains("format("),
                "the paginated guarded predicate must be static — no runtime format() assembly");

        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_d4_paged_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedPagedAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            assertPaginatedSearchPageBoundaries(pg, "postgresql", "paged_search_pg");
        }
    }

    @Test
    void paginatedGuardedPredicateSearchDeploysCommaFormPageBoundariesOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("PagedSearchMyComma.java", PAGED_SEARCH_MYSQL_COMMA_FN, "mysql");
        // The MySQL `LIMIT ?, ?` comma form (offset, count) splices verbatim after the guard, positional ?s.
        assertEmittedContains(generated, "mysql",
                "WHERE active = true AND (? IS NULL OR tier = ?) ORDER BY id LIMIT ?, ?");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedPagedAccountsMysql(my);
            deploy(my, generated, "mysql");
            assertPaginatedSearchPageBoundaries(my, "mysql", "paged_search_my_comma");
        }
    }

    @Test
    void paginatedGuardedPredicateSearchDeploysOffsetFormPageBoundariesOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("PagedSearchMyOffset.java", PAGED_SEARCH_MYSQL_OFFSET_FN, "mysql");
        assertEmittedContains(generated, "mysql",
                "WHERE active = true AND (? IS NULL OR tier = ?) ORDER BY id LIMIT ? OFFSET ?");

        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedPagedAccountsMysql(my);
            deploy(my, generated, "mysql");
            assertPaginatedSearchPageBoundaries(my, "mysql", "paged_search_my_offset");
        }
    }

    /**
     * The arbiter: for each (tier filter × page) combination, the paginated routine must return the EXACT id
     * LIST — same rows IN THE SAME ORDER — the conditional builder would for that page. The expected list is
     * computed LIVE by running the conditional-builder-equivalent query (the hand-written WHERE with the
     * non-null tier filter + the same {@code ORDER BY id} + the same {@code LIMIT}/{@code OFFSET}) directly
     * against the seed. Covers first page, middle page, last PARTIAL page, and an offset PAST END (→ empty).
     *
     * <p>Seed (active): id 1 GOLD, 2 SILVER, 3 GOLD, 4 SILVER, 5 GOLD, 6 SILVER (id 7 GOLD inactive — never
     * matches). With {@code pageSize = 2} and {@code ORDER BY id} ASC: all-null pages are [1,2],[3,4],[5,6],[]
     * (offset 6 past end); tier=GOLD ({1,3,5}) pages are [1,3],[5 — last partial],[] (offset 4 past end).</p>
     */
    private void assertPaginatedSearchPageBoundaries(Connection connection, String dialect, String routine)
            throws SQLException {
        record Page(String tier, int pageSize, int offset, String label) { }
        List<Page> pages = List.of(
                new Page(null, 2, 0, "all-null first page"),
                new Page(null, 2, 2, "all-null middle page"),
                new Page(null, 2, 4, "all-null last full page"),
                new Page(null, 2, 6, "all-null offset PAST END -> empty"),
                new Page(null, 0, 0, "all-null LIMIT 0 -> empty page (count boundary)"),
                new Page("GOLD", 2, 0, "tier=GOLD first page"),
                new Page("GOLD", 2, 2, "tier=GOLD last PARTIAL page (one row)"),
                new Page("GOLD", 2, 4, "tier=GOLD offset PAST END -> empty"),
                new Page("GOLD", 10, 0, "tier=GOLD single big page (all three)"));
        for (Page page : pages) {
            List<Long> expected = expectedPageIds(connection, page.tier(), page.pageSize(), page.offset());
            List<Long> actual = runPaginatedSearchIds(
                    connection, dialect, routine, page.tier(), page.pageSize(), page.offset());
            assertEquals(expected, actual, "the paginated guarded-predicate routine must return the SAME page "
                    + "(rows + order + count) as the conditional builder on " + dialect + " for [" + page.label()
                    + "] tier=" + page.tier() + " pageSize=" + page.pageSize() + " offset=" + page.offset());
        }
        // Explicit boundary locks (the auditors' critical cases), independent of the live conditional builder.
        assertEquals(java.util.List.of(1L, 2L),
                runPaginatedSearchIds(connection, dialect, routine, null, 2, 0),
                "all-null first page must be ids [1,2] on " + dialect);
        assertEquals(java.util.List.of(5L),
                runPaginatedSearchIds(connection, dialect, routine, "GOLD", 2, 2),
                "tier=GOLD last partial page (offset 2) must be the single id [5] on " + dialect);
        assertTrue(runPaginatedSearchIds(connection, dialect, routine, null, 2, 6).isEmpty(),
                "an offset PAST END must return an empty page on " + dialect);
    }

    /**
     * The conditional builder's expected page ids: the hand-written WHERE with the non-null tier filter +
     * {@code ORDER BY id} + {@code LIMIT <pageSize> OFFSET <offset>}, bound and run directly. The ground truth
     * the D4-lowered routine must reproduce (rows + order + count). (PG/MySQL both accept `LIMIT ? OFFSET ?`,
     * so this single ground-truth query serves both dialects regardless of which native form the routine uses.)
     */
    private static java.util.List<Long> expectedPageIds(
            Connection connection, String tier, int pageSize, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id FROM " + SCHEMA + ".accounts WHERE active = true");
        if (tier != null) {
            sql.append(" AND tier = ?");
        }
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            if (tier != null) {
                statement.setString(i++, tier);
            }
            statement.setInt(i++, pageSize);
            statement.setInt(i++, offset);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * CALLs the deployed paginated-search routine with the (nullable) tier + pageSize + offset and returns its
     * returned ids IN THE ROUTINE'S RETURN ORDER. PostgreSQL: the carrier is a jsonb function ({@code
     * jsonb_array_elements} preserves array order). MySQL: the carrier is a PROCEDURE streaming an ordered
     * result set. The MySQL comma-form routine binds (offset, pageSize); the offset/count routines bind
     * (pageSize, offset) — this helper passes the three CALL args in the routine's own parameter order.
     */
    private static java.util.List<Long> runPaginatedSearchIds(
            Connection connection, String dialect, String routine, String tier, int pageSize, int offset)
            throws SQLException {
        // The CALL/SELECT arg order is the ROUTINE's Java parameter order: the comma-form routine declares
        // (tier, offset, pageSize); the offset-form / PG routine declare (tier, pageSize, offset).
        boolean commaForm = routine.endsWith("_comma");
        int arg2 = commaForm ? offset : pageSize;
        int arg3 = commaForm ? pageSize : offset;
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (dialect.equals("postgresql")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT (elem->>'id')::bigint FROM jsonb_array_elements("
                            + SCHEMA + "." + routine + "(?, ?, ?)) WITH ORDINALITY AS t(elem, ord) ORDER BY ord")) {
                bindPaginatedSearchParams(statement, tier, arg2, arg3);
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getLong(1));
                    }
                }
            }
            return ids;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + "." + routine + "(?, ?, ?)")) {
            bindPaginatedSearchParams(statement, tier, arg2, arg3);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong("id"));
                }
            }
        }
        return ids;
    }

    /** Binds the (nullable) tier + two int pagination args into a 3-param paginated-search CALL/SELECT. */
    private static void bindPaginatedSearchParams(
            PreparedStatement statement, String tier, int arg2, int arg3) throws SQLException {
        if (tier == null) {
            statement.setNull(1, Types.VARCHAR);
        } else {
            statement.setString(1, tier);
        }
        statement.setInt(2, arg2);
        statement.setInt(3, arg3);
    }

    /** Seeds 6 ACTIVE accounts (ids 1..6, tier GOLD for odd / SILVER for even) + 1 inactive (id 7) on PG. */
    private static void seedPagedAccountsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL, active BOOLEAN NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, active) VALUES "
                    + "(1,'GOLD',true), (2,'SILVER',true), (3,'GOLD',true), (4,'SILVER',true), "
                    + "(5,'GOLD',true), (6,'SILVER',true), (7,'GOLD',false)");
        }
    }

    /** Same 6-active + 1-inactive seed on MySQL (BOOLEAN -> TINYINT(1)). */
    private static void seedPagedAccountsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL, active BOOLEAN NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, active) VALUES "
                    + "(1,'GOLD',true), (2,'SILVER',true), (3,'GOLD',true), (4,'SILVER',true), "
                    + "(5,'GOLD',true), (6,'SILVER',true), (7,'GOLD',false)");
        }
    }

    // A paginated builder whose pagination ? sits in a NON-pagination position — ` ORDER BY ?` (a bound no-op
    // sort, not a count/offset). The recognizer FAILS SAFE on such a tail, the unrecovered StringBuilder is an
    // unsupported P0 construct under STRICT, and the transpile is REJECTED (TITAN-E001) — so NO malformed
    // guarded-predicate artifact (a `… ORDER BY ?` whose ? the guarded lowering cannot soundly bind as a
    // pagination value) is ever emitted/deployed. Mirrors guardedPredicateTrailingLimitBaseIsRejected…
    private static final String ORDER_BY_PLACEHOLDER_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class OrderByPlaceholder {
                @StoredProcedure
                public static void orderByPlaceholder(Connection c, String tier, int sortCol) throws SQLException {
                    StringBuilder sql = new StringBuilder("UPDATE accounts SET flagged = 1 WHERE active = true");
                    if (tier != null) { sql.append(" AND tier = ?"); }
                    sql.append(" ORDER BY ?");
                    PreparedStatement ps = c.prepareStatement(sql.toString());
                    int i = 1;
                    if (tier != null) ps.setString(i++, tier);
                    ps.setInt(i++, sortCol);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void paginatedOrderByPlaceholderTailIsRejectedAtTranspileNotDeployedMalformed() throws Exception {
        // Both dialects must REJECT a ` ORDER BY ?` tail at transpile (TITAN-E001) — a bound ? in an ORDER BY
        // position is a no-op sort, not a pagination count/offset, so it is NOT a safe value bind. No malformed
        // `… ORDER BY ?` artifact is ever produced or deployed (the fail-safe reject of the named bad case).
        for (String dialect : List.of("postgresql", "mysql")) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> transpileSource("OrderByPlaceholder.java", ORDER_BY_PLACEHOLDER_PROC, dialect),
                    "a ` ORDER BY ?` tail must not transpile to a deployable guarded predicate on " + dialect);
            assertTrue(thrown.getMessage().contains("TITAN-E001"),
                    "the rejection must be TITAN-E001 (unsupported construct), not a malformed emission; was: "
                            + thrown.getMessage());
        }
    }

    /** accounts seeded so codes[0]->id 10, codes[1]->id 20, plus a non-matching row 30 (PG, TEXT codes). */
    private static void seedFlagAccountsByCodesPostgres(Connection pg, String... codes) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, code TEXT NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = pg.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, code) VALUES (?, ?)")) {
                insertCodeRow(insert, 10L, codes[0]);
                insertCodeRow(insert, 20L, codes[1]);
                insertCodeRow(insert, 30L, "no-match"); // a row that must NOT flag.
            }
        }
    }

    /** accounts seeded so codes[0]->id 10, codes[1]->id 20, plus a non-matching row 30 (MySQL, TEXT codes). */
    private static void seedFlagAccountsByCodesMysql(Connection my, String... codes) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, code TEXT NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = my.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, code) VALUES (?, ?)")) {
                insertCodeRow(insert, 10L, codes[0]);
                insertCodeRow(insert, 20L, codes[1]);
                insertCodeRow(insert, 30L, "no-match");
            }
        }
    }

    private static void callFlagByIntListPostgres(Connection connection, Integer... ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_int_id_list(?)")) {
            statement.setArray(1, connection.createArrayOf("integer", ids));
            statement.execute();
        }
    }

    private static void callFlagByCodeListPostgres(Connection connection, String... codes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_code_list(?)")) {
            statement.setArray(1, connection.createArrayOf("text", codes));
            statement.execute();
        }
    }

    /** CALLs a single-JSON-param collection-IN procedure on MySQL with the given JSON-array literal. */
    private static void callFlagByJsonList(Connection connection, String proc, String jsonArray)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + "." + proc + "(?)")) {
            statement.setString(1, jsonArray);
            statement.execute();
        }
    }

    /**
     * A correctly-escaped JSON string literal for {@code value} — escapes the two structural JSON
     * metacharacters {@code "} and {@code \} (the only ones the codes here use). A single quote {@code '}
     * is NOT a JSON metacharacter, so it needs no escaping inside a JSON string. Used to build the MySQL
     * JSON-array CALL argument; it must round-trip a quote/backslash element without corruption.
     */
    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A JSON array literal of correctly-escaped string elements (MySQL CALL argument for List&lt;String&gt;). */
    private static String jsonStringArray(String... values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append(jsonString(values[i]));
        }
        return json.append("]").toString();
    }

    /** accounts(id BIGINT, code TEXT, flagged) seeded so code 'A'->id 10, 'B'->20, longCode->30. */
    private static void seedFlagAccountsByCodePostgres(Connection pg, String longCode) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, code TEXT NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = pg.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, code) VALUES (?, ?)")) {
                insertCodeRow(insert, 10L, "A");
                insertCodeRow(insert, 20L, "B");
                insertCodeRow(insert, 30L, longCode);
            }
        }
    }

    private static void seedFlagAccountsByCodeMysql(Connection my, String longCode) throws SQLException {
        try (Statement statement = my.createStatement()) {
            // code is TEXT (not VARCHAR(255)) so it can hold the 1500-char value in full.
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, code TEXT NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = my.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, code) VALUES (?, ?)")) {
                insertCodeRow(insert, 10L, "A");
                insertCodeRow(insert, 20L, "B");
                insertCodeRow(insert, 30L, longCode);
            }
        }
    }

    private static void insertCodeRow(PreparedStatement insert, long id, String code) throws SQLException {
        insert.setLong(1, id);
        insert.setString(2, code);
        insert.executeUpdate();
    }

    // ----- UUID seed/call helpers (ext_id is native uuid on PG, CHAR(36) on MySQL) -----

    private static void seedFlagAccountsByExtIdPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, ext_id UUID NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = pg.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, ext_id) VALUES (?, ?)")) {
                insertExtIdRowPostgres(insert, 10L, UUID.fromString(EXT_ID_10));
                insertExtIdRowPostgres(insert, 20L, UUID.fromString(EXT_ID_20));
                insertExtIdRowPostgres(insert, 30L, UUID.fromString(EXT_ID_30));
            }
        }
    }

    private static void seedFlagAccountsByExtIdMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, ext_id CHAR(36) NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            try (PreparedStatement insert = my.prepareStatement(
                    "INSERT INTO " + SCHEMA + ".accounts (id, ext_id) VALUES (?, ?)")) {
                insertExtIdRowMysql(insert, 10L, EXT_ID_10);
                insertExtIdRowMysql(insert, 20L, EXT_ID_20);
                insertExtIdRowMysql(insert, 30L, EXT_ID_30);
            }
        }
    }

    private static void insertExtIdRowPostgres(PreparedStatement insert, long id, UUID extId) throws SQLException {
        insert.setLong(1, id);
        insert.setObject(2, extId);
        insert.executeUpdate();
    }

    private static void insertExtIdRowMysql(PreparedStatement insert, long id, String extId) throws SQLException {
        insert.setLong(1, id);
        insert.setString(2, extId);
        insert.executeUpdate();
    }

    private static void callFlagByExtIdPostgres(Connection connection, UUID extId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_ext_id(?)")) {
            statement.setObject(1, extId);
            statement.execute();
        }
    }

    private static void callFlagByExtIdMysql(Connection connection, String extId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_ext_id(?)")) {
            statement.setString(1, extId);
            statement.execute();
        }
    }

    private static void callFlagByExtIdListPostgres(Connection connection, UUID... extIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_ext_id_list(?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", extIds));
            statement.execute();
        }
    }

    private static void assertEmittedContains(
            List<TranspilationPipeline.GeneratedSql> generated, String target, String needle) {
        String sql = emittedSql(generated, target);
        assertTrue(sql.contains(needle), "expected emitted " + target + " SQL to contain [" + needle + "]; was:\n" + sql);
    }

    private static void assertEmittedContainsUpper(
            List<TranspilationPipeline.GeneratedSql> generated, String target, String needleUpper) {
        String sql = emittedSql(generated, target).toUpperCase(java.util.Locale.ROOT);
        assertTrue(sql.contains(needleUpper),
                "expected emitted " + target + " SQL (upper) to contain [" + needleUpper + "]");
    }

    private static String emittedSql(List<TranspilationPipeline.GeneratedSql> generated, String target) {
        return generated.stream()
                .filter(artifact -> target.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
    }

    /** Asserts the fixture transpiled to a single-parameter collection array-bind on {@code target}. */
    private static void assertCollectionArrayBound(
            List<TranspilationPipeline.GeneratedSql> generated, String target) {
        String sql = generated.stream()
                .filter(artifact -> target.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
        if (target.equals("postgresql")) {
            assertTrue(sql.contains("id = ANY($1)"),
                    "PostgreSQL must bind the whole list as one array via = ANY($1); was:\n" + sql);
            assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("BIGINT[]"),
                    "the list parameter must be a native bigint[] array; was:\n" + sql);
        } else {
            assertTrue(sql.contains("JSON_TABLE(?,"),
                    "MySQL must bind the whole list as one JSON array via JSON_TABLE(?); was:\n" + sql);
            assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("P_IDS JSON"),
                    "the list parameter must be a JSON parameter; was:\n" + sql);
        }
    }

    private static void callFlagByIdListPostgres(Connection connection, Long... ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_id_list(?)")) {
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            statement.execute();
        }
    }

    private static void callFlagByIdArrayPostgres(Connection connection, Long... ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_id_array(?)")) {
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            statement.execute();
        }
    }

    private static void callFlagByIdListMysql(Connection connection, String jsonArray) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + SCHEMA + ".flag_by_id_list(?)")) {
            statement.setString(1, jsonArray);
            statement.execute();
        }
    }

    private static void clearAccountFlagsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("UPDATE " + SCHEMA + ".accounts SET flagged = 0");
        }
    }

    private static void clearAccountFlagsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("UPDATE " + SCHEMA + ".accounts SET flagged = 0");
        }
    }

    private static BigDecimal callSumOrdersByCustomerTier(Connection connection, String tier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".sum_orders_by_customer_tier(?)")) {
            statement.setString(1, tier);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "sum_orders_by_customer_tier returned no rows");
                return resultSet.getBigDecimal(1);
            }
        }
    }

    private static void callFlagAround(Connection connection, String tier, String region, int minQty)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_around(?, ?, ?)")) {
            statement.setString(1, tier);
            statement.setString(2, region);
            statement.setInt(3, minQty);
            statement.execute();
        }
    }

    /** customers(id,tier) + orders(id,customer_id,amount); GOLD=1,3. Sum-by-tier fixture rows. */
    private static void seedFusionAmountTablesPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".customers (id BIGINT PRIMARY KEY, tier TEXT NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, amount NUMERIC(38,10) NOT NULL)");
            seedFusionAmountRows(statement);
        }
    }

    private static void seedFusionAmountTablesMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".customers (id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, amount DECIMAL(38,10) NOT NULL)");
            seedFusionAmountRows(statement);
        }
    }

    private static void seedFusionAmountRows(Statement statement) throws SQLException {
        statement.execute("INSERT INTO " + SCHEMA + ".customers (id, tier) VALUES "
                + "(1, 'GOLD'), (2, 'STANDARD'), (3, 'GOLD')");
        // cust1: 10.00 + 5.00; cust3: 7.00 -> GOLD sum 22.00. cust2: 100.00 (excluded).
        statement.execute("INSERT INTO " + SCHEMA + ".orders (id, customer_id, amount) VALUES "
                + "(100, 1, 10.00), (200, 2, 100.00), (130, 3, 7.00), (300, 1, 5.00)");
    }

    /** customers(id,tier) + orders(id,customer_id,region,qty,flagged); GOLD=1,3. Bind-order fixture. */
    private static void seedFusionRegionQtyTablesPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".customers (id BIGINT PRIMARY KEY, tier TEXT NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, region TEXT NOT NULL, "
                    + "qty INTEGER NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            seedFusionRegionQtyRows(statement);
        }
    }

    private static void seedFusionRegionQtyTablesMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".customers (id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, region VARCHAR(32) NOT NULL, "
                    + "qty INT NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            seedFusionRegionQtyRows(statement);
        }
    }

    /**
     * GOLD customers 1,3. Orders chosen so only 100 and 130 match region='EU' AND GOLD-cust AND qty>=5:
     * 100 (cust1, EU, qty 5) ✓, 130 (cust3, EU, qty 9) ✓, 300 (cust1, EU, qty 3) ✗ qty, 200 (cust2 — not
     * GOLD) ✗, 400 (cust1, US) ✗ region.
     */
    private static void seedFusionRegionQtyRows(Statement statement) throws SQLException {
        statement.execute("INSERT INTO " + SCHEMA + ".customers (id, tier) VALUES "
                + "(1, 'GOLD'), (2, 'STANDARD'), (3, 'GOLD')");
        statement.execute("INSERT INTO " + SCHEMA + ".orders (id, customer_id, region, qty) VALUES "
                + "(100, 1, 'EU', 5), (200, 2, 'EU', 9), (130, 3, 'EU', 9), (300, 1, 'EU', 3), (400, 1, 'US', 9)");
    }

    /** Asserts the fixture transpiled to ONE statement carrying the IN-subquery (not two round-trips). */
    private static void assertFusedToOneStatement(
            List<TranspilationPipeline.GeneratedSql> generated, String target) {
        String sql = generated.stream()
                .filter(artifact -> target.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        // The fused IN-subquery text is present...
        assertTrue(upper.contains("IN (SELECT ID FROM CUSTOMERS WHERE TIER ="),
                "the emitted routine must carry A inlined as B's IN-subquery; was:\n" + sql);
        // ...and there is no SECOND read of the customers table (a cursor/OPEN/second EXECUTE over A): the
        // only `customers` reference is inside the fused IN-subquery, so `FROM CUSTOMERS` appears once.
        int firstFrom = upper.indexOf("FROM CUSTOMERS");
        assertTrue(firstFrom >= 0 && upper.indexOf("FROM CUSTOMERS", firstFrom + 1) < 0,
                "A must be inlined once (no separate prior query over customers); was:\n" + sql);
    }

    private static void seedFusionTablesPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".customers ("
                    + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            seedFusionRows(statement);
        }
    }

    private static void seedFusionTablesMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".customers ("
                    + "id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL)");
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            seedFusionRows(statement);
        }
    }

    /** Customers 1,3 are GOLD; 2 is STANDARD. Orders link to those customers (one STANDARD-cust order). */
    private static void seedFusionRows(Statement statement) throws SQLException {
        statement.execute("INSERT INTO " + SCHEMA + ".customers (id, tier) VALUES "
                + "(1, 'GOLD'), (2, 'STANDARD'), (3, 'GOLD')");
        statement.execute("INSERT INTO " + SCHEMA + ".orders (id, customer_id) VALUES "
                + "(100, 1), (200, 2), (130, 3), (300, 1)");
    }

    private static void clearFlagsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("UPDATE " + SCHEMA + ".orders SET flagged = 0");
        }
    }

    private static void clearFlagsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("UPDATE " + SCHEMA + ".orders SET flagged = 0");
        }
    }

    private static void callFlagOrdersByCustomerTier(Connection connection, String tier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_orders_by_customer_tier(?)")) {
            statement.setString(1, tier);
            statement.execute();
        }
    }

    /** The set of order ids whose row has {@code flagged = 1}. */
    private static java.util.Set<Long> flaggedOrderIds(Connection connection) throws SQLException {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT id FROM " + SCHEMA + ".orders WHERE flagged = 1")) {
            while (resultSet.next()) {
                ids.add(resultSet.getLong(1));
            }
        }
        return ids;
    }

    // ===== WS-C Phase 3 Rung 3: deploy-prove Tier 3 identifier + raw-fragment splice (PERMISSIVE) ====
    // The arbiter for Rung 3 (design contract §7/§11): a @SqlSafety(PERMISSIVE) method splicing a runtime
    // IDENTIFIER (a dynamic table NAME) and one splicing a RAW_FRAGMENT (a dynamic ORDER BY sort
    // expression / a whole predicate) must deploy + return correct rows on PG 16 AND MySQL 8.4. STRICT
    // would reject all (E004); permissive assembles the SQL text at runtime — PG format('%s'+quote_ident /
    // '%s'), MySQL backtick-CONCAT(qualified) / verbatim CONCAT. Emitted as @StoredProcedure (dynamic
    // EXECUTE is valid in a routine on both dialects; a MySQL FUNCTION forbids it).
    //
    // AUDIT FIX coverage (the two HIGH correctness findings that the verbatim/qualified fix closes):
    //   * Findings 1 — a dynamic ORDER BY whose runtime value is a COLUMN + DIRECTION as ONE string
    //     ("bal DESC") must NOT be %I-quoted as one identifier (which yields 'column "bal DESC" does not
    //     exist'); it is spliced VERBATIM (faithful to plain JDBC). Proven by flagTopBy("bal DESC").
    //   * Findings 2 — a dynamic table name whose runtime value is SCHEMA-QUALIFIED ("billing.accounts")
    //     must be quoted per dotted segment ("billing"."accounts" / `billing`.`accounts`), not as one
    //     identifier (which yields 'relation "billing.accounts" does not exist'). Proven by
    //     flagInTable("billing.accounts"). The MIXED-CASE UNqualified control ("Accounts") proves the
    //     per-segment quoting still quotes a single segment (PostgreSQL folds unquoted names to lowercase).

    /**
     * RAW_FRAGMENT (sort expression) splice — a dynamic {@code ORDER BY <sortExpr>} where the runtime
     * value is a COLUMN + DIRECTION in one string ({@code "bal DESC"}), used to pick the top row whose id
     * is flagged. WS-C Phase 3 Rung 3 audit fix (Findings 1): the sort expression is spliced VERBATIM (PG
     * {@code %s} / MySQL bare {@code CONCAT} operand), faithfully reproducing the plain-JDBC {@code
     * "ORDER BY " + orderBy} — NOT {@code %I}-quoted as one identifier (which would corrupt {@code
     * "bal DESC"}). The proc does NOT append a direction itself, so the whole sort expression is the param.
     */
    private static final String IDENTIFIER_ORDER_BY_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class FlagTopBy {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void flagTopBy(Connection c, String sortExpr) throws SQLException {
                    // The ORDER BY value is a runtime sort EXPRESSION (column + direction). The top row is
                    // picked via a derived table so the same-table-in-subquery-of-UPDATE works on BOTH
                    // PostgreSQL and MySQL (MySQL forbids a bare 'UPDATE t WHERE id = (SELECT ... FROM t ...)';
                    // wrapping the subquery in a derived table materializes it and lifts that restriction).
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE id = (SELECT x.id FROM "
                                    + "(SELECT id FROM accounts ORDER BY " + sortExpr + ", id ASC LIMIT 1) AS x)");
                    ps.executeUpdate();
                }
            }
            """;

    /**
     * IDENTIFIER splice — a dynamic table NAME ({@code "UPDATE " + tbl + " SET …"}) used to flag a row by
     * id. WS-C Phase 3 Rung 3 audit fix (Findings 2): the runtime name is quoted qualified-name aware (PG
     * {@code quote_ident} per dotted segment / MySQL backtick per segment), so a SCHEMA-QUALIFIED {@code
     * "billing.accounts"} quotes as {@code "billing"."accounts"} / {@code `billing`.`accounts`}, NOT one
     * identifier (which fails 'relation does not exist'). A bare/mixed-case name quotes as a single segment.
     */
    private static final String IDENTIFIER_TABLE_NAME_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class FlagInTable {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void flagInTable(Connection c, String tbl, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE " + tbl + " SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    /**
     * RAW_FRAGMENT splice — a whole runtime predicate ({@code "… WHERE " + predicate}) spliced verbatim
     * (PG {@code %s} / MySQL bare {@code CONCAT} operand): the fragment reaches the SQL text as written
     * (the source's own exposure faithfully reproduced). Flags exactly the rows the predicate selects.
     */
    private static final String RAW_FRAGMENT_PREDICATE_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SqlSafety;
            import titan.dsl.SqlSafetyMode;
            import java.sql.*;

            class FlagByPredicate {
                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void flagByPredicate(Connection c, String predicate) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE " + predicate);
                    ps.executeUpdate();
                }
            }
            """;

    @Test
    void identifierOrderBySpliceDeploysAndFlagsTopRowOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagTopBy.java", IDENTIFIER_ORDER_BY_PROC, "postgresql");
        // Findings 1: the sort expression is spliced VERBATIM via %s (NOT %I-quoted), so a runtime
        // "bal DESC" reaches the text as `ORDER BY bal DESC` — not the broken `ORDER BY "bal DESC"`.
        assertTrue(generated.stream().anyMatch(a -> "postgresql".equals(a.target())
                        && a.sql().contains("ORDER BY %s") && !a.sql().contains("ORDER BY %I")),
                "PostgreSQL must splice the ORDER BY sort expression verbatim via %s (not %I)");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r3_ident_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            // The runtime value is COLUMN + DIRECTION as one string ("bal DESC") -> highest-balance row is
            // id 30 (bal 900). This is the exact Findings-1 idiom that %I corrupted; verbatim splices it.
            callFlagTopBy(pg, "bal DESC");
            assertEquals(java.util.Set.of(30L), flaggedIds(pg),
                    "the dynamic ORDER BY \"bal DESC\" must flag the top-balance row (id 30) — proving verbatim splice");
        }
    }

    @Test
    void identifierOrderBySpliceDeploysAndFlagsTopRowOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagTopBy.java", IDENTIFIER_ORDER_BY_PROC, "mysql");
        // Findings 1: the sort expression is the bare CONCAT operand (verbatim, no backtick quoting), so a
        // runtime "bal DESC" reaches the text as `ORDER BY bal DESC` — not the broken `ORDER BY `bal DESC``.
        assertTrue(generated.stream().anyMatch(a -> "mysql".equals(a.target())
                        && a.sql().contains("ORDER BY ', p_sort_expr, '")),
                "MySQL must splice the ORDER BY sort expression verbatim as a bare CONCAT operand");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsMysql(my);
            deploy(my, generated, "mysql");
            callFlagTopBy(my, "bal DESC");
            assertEquals(java.util.Set.of(30L), flaggedIds(my),
                    "the dynamic ORDER BY `bal DESC` must flag the top-balance row (id 30) — proving verbatim splice");
        }
    }

    @Test
    void identifierTableNameSpliceDeploysAndFlagsRowOnPostgres() throws Exception {
        // Findings 2: a SCHEMA-QUALIFIED table name ("billing.accounts") must quote per dotted segment
        // ("billing"."accounts"), NOT as one identifier (which fails 'relation does not exist').
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagInTable.java", IDENTIFIER_TABLE_NAME_PROC, "postgresql");
        assertTrue(generated.stream().anyMatch(a -> "postgresql".equals(a.target())
                        && a.sql().contains("quote_ident(__titan_idpart)")
                        && a.sql().contains("string_to_array(p_tbl, '.')")),
                "PostgreSQL must quote the runtime table name qualified-name aware (quote_ident per segment)");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r3_tbl_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            // Qualified "billing.accounts" -> "billing"."accounts" (works); flags id 20.
            callFlagInTable(pg, "billing.accounts", 20L);
            assertEquals(java.util.Set.of(20L), flaggedIds(pg),
                    "the schema-qualified table name must resolve (billing.accounts) and flag id 20");
        }
    }

    @Test
    void identifierTableNameSpliceDeploysAndFlagsRowOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagInTable.java", IDENTIFIER_TABLE_NAME_PROC, "mysql");
        assertTrue(generated.stream().anyMatch(a -> "mysql".equals(a.target())
                        && a.sql().contains("REPLACE(REPLACE(p_tbl, '`', '``'), '.', '`.`')")),
                "MySQL must backtick-quote each '.'-separated segment of the runtime table name");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsMysql(my);
            deploy(my, generated, "mysql");
            // Qualified "billing.accounts" -> `billing`.`accounts` (works); flags id 20.
            callFlagInTable(my, SCHEMA + ".accounts", 20L);
            assertEquals(java.util.Set.of(20L), flaggedIds(my),
                    "the schema-qualified table name must resolve (`billing`.`accounts`) and flag id 20");
        }
    }

    @Test
    void rawFragmentPredicateSpliceDeploysAndFlagsMatchingRowsOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByPredicate.java", RAW_FRAGMENT_PREDICATE_PROC, "postgresql");
        assertTrue(generated.stream().anyMatch(a -> "postgresql".equals(a.target())
                        && a.sql().contains("format(") && a.sql().contains("%s")),
                "PostgreSQL must emit format('… %s', …) for the verbatim raw-fragment splice");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r3_raw_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsPostgres(pg);
            deploy(pg, generated, "postgresql");
            // A raw predicate over a tier and an id range — spliced verbatim into the WHERE.
            callFlagByPredicate(pg, "tier = 'GOLD' AND id >= 20");
            assertEquals(java.util.Set.of(30L), flaggedIds(pg),
                    "the raw-fragment predicate must flag exactly the GOLD rows with id >= 20 (id 30)");
        }
    }

    @Test
    void rawFragmentPredicateSpliceDeploysAndFlagsMatchingRowsOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("FlagByPredicate.java", RAW_FRAGMENT_PREDICATE_PROC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedSortAccountsMysql(my);
            deploy(my, generated, "mysql");
            callFlagByPredicate(my, "tier = 'GOLD' AND id >= 20");
            assertEquals(java.util.Set.of(30L), flaggedIds(my),
                    "the raw-fragment predicate must flag exactly the GOLD rows with id >= 20 (id 30)");
        }
    }

    /**
     * Seeds a {@code billing.accounts} table with a lowercase {@code bal} column — so a dynamic ORDER BY
     * whose runtime value is {@code "bal DESC"} (column + direction in one string, the Findings-1 idiom)
     * is spliced VERBATIM and resolves. The schema-qualified table name ({@code billing.accounts}) is the
     * Findings-2 idiom exercised by the table-name ITs.
     */
    private static void seedSortAccountsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier TEXT NOT NULL, "
                    + "bal NUMERIC NOT NULL, flagged INTEGER NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, bal) VALUES "
                    + "(10, 'GOLD', 100), (20, 'STANDARD', 500), (30, 'GOLD', 900)");
        }
    }

    /** Seeds the same accounts table on MySQL (lowercase {@code bal} for the verbatim ORDER BY idiom). */
    private static void seedSortAccountsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".accounts ("
                    + "id BIGINT PRIMARY KEY, tier VARCHAR(32) NOT NULL, "
                    + "bal DECIMAL(20,2) NOT NULL, flagged INT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO " + SCHEMA + ".accounts (id, tier, bal) VALUES "
                    + "(10, 'GOLD', 100), (20, 'STANDARD', 500), (30, 'GOLD', 900)");
        }
    }

    private static void callFlagTopBy(Connection connection, String sortExpr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_top_by(?)")) {
            statement.setString(1, sortExpr);
            statement.execute();
        }
    }

    private static void callFlagInTable(Connection connection, String tbl, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_in_table(?, ?)")) {
            statement.setString(1, tbl);
            statement.setLong(2, id);
            statement.execute();
        }
    }

    private static void callFlagByPredicate(Connection connection, String predicate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".flag_by_predicate(?)")) {
            statement.setString(1, predicate);
            statement.execute();
        }
    }

    // ===== WS-C Phase 3 Rung 5: deploy-prove the unknown-shape result carrier (Tier 3) ==============
    // The arbiter for Rung 5 (design contract §7/§11 / D5): a PURE metadata-driven generic reader —
    // while(rs.next()) marshalling EVERY column (md.getColumnCount()/getColumnLabel/rs.getObject(i)) into
    // a per-row Map added to a returned List<Map<String,Object>> — lowers to a shape-agnostic CARRIER and
    // must deploy + return the correct data on PG 16 AND MySQL 8.4: PostgreSQL a jsonb function
    // (jsonb_agg(to_jsonb(t)), empty -> []); MySQL a @StoredProcedure streaming a native open result set
    // (empty -> no rows). The carrier faithfully returns the same data the List<Map> would.

    /**
     * The canonical pure metadata-driven generic reader (a @StoredFunction returning
     * List<Map<String,Object>>): every column is read via ResultSetMetaData (NOT fixed rs.getX(col)) and
     * marshalled into a per-row Map. Over a 3-column table, the carrier yields one JSON object per row
     * keyed by column label (PostgreSQL jsonb) / one result-set row per row (MySQL).
     */
    private static final String GENERIC_READER_SRC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ReadWidgets {
                @StoredFunction
                public static List<Map<String,Object>> readWidgets(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label, qty FROM widgets ORDER BY id");
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
     * The canonical generic reader whose SELECT ends with a trailing {@code ;} (legal JDBC). A regression
     * fixture (WS-C Phase 3 Rung 5 audit, correctness high): the carrier must STRIP the trailing {@code ;}
     * before the PostgreSQL {@code FROM (<sql>) t} wrap, else the routine CREATEs but throws {@code syntax
     * error at or near ";"} at CALL time on PostgreSQL (MySQL's PREPARE tolerates it but must still stream).
     */
    private static final String GENERIC_READER_SEMI_SRC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ReadWidgetsSemi {
                @StoredFunction
                public static List<Map<String,Object>> readWidgets(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label, qty FROM widgets ORDER BY id;");
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
     * A generic reader over a MIXED-TYPE table — BIGINT id, TEXT label, INT qty, NUMERIC(10,2) price,
     * BOOLEAN active, TEXT note (incl. NULL and an embedded quote). The carrier must round-trip every
     * column's value AND JSON/result-set type faithfully (WS-C Phase 3 Rung 5 audit, deploy medium: the
     * committed ITs asserted only string labels + counts, never a numeric/boolean/null column).
     */
    private static final String GENERIC_READER_TYPED_SRC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ReadTyped {
                @StoredFunction
                public static List<Map<String,Object>> readTyped(Connection c) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label, qty, price, active, note FROM widgets ORDER BY id");
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

    /** The same generic reader, but with a value-bound parameter (WHERE qty >= ?) — the carrier binds it. */
    private static final String GENERIC_READER_BOUND_SRC = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.*;

            class ReadWidgetsMin {
                @StoredFunction
                public static List<Map<String,Object>> readWidgetsMin(Connection c, int minQty) throws SQLException {
                    List<Map<String,Object>> rows = new ArrayList<>();
                    PreparedStatement ps = c.prepareStatement("SELECT id, label, qty FROM widgets WHERE qty >= ? ORDER BY id");
                    ps.setInt(1, minQty);
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

    @Test
    void genericReaderCarrierDeploysAndReturnsJsonOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgets.java", GENERIC_READER_SRC, "postgresql");
        // The carrier is a jsonb function aggregating every column generically (to_jsonb/jsonb_agg).
        assertTrue(generated.stream().anyMatch(a -> "postgresql".equals(a.target())
                        && a.sql().contains("jsonb_agg(to_jsonb(t))") && a.sql().toUpperCase().contains("RETURNS JSONB")),
                "PostgreSQL must emit a jsonb carrier function (jsonb_agg(to_jsonb(t)))");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r5_carrier_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsPostgres(pg);
            deploy(pg, generated, "postgresql");

            // Non-empty: the jsonb carrier returns one object per row, keyed by column label, in order.
            String json = selectScalarString(pg, "SELECT " + SCHEMA + ".read_widgets()::text");
            assertTrue(json.contains("\"id\"") && json.contains("\"label\"") && json.contains("\"qty\""),
                    "the carrier JSON must key each column by its label; was: " + json);
            assertTrue(json.contains("\"gadget\"") && json.contains("\"gizmo\""),
                    "the carrier JSON must contain every row's data; was: " + json);
            assertEquals(3, countJsonArrayElements(pg, SCHEMA + ".read_widgets()"),
                    "the carrier must return one JSON object per seeded row");

            // Empty result -> '[]'::jsonb (the COALESCE), matching new ArrayList<>() over no rows.
            try (Statement s = pg.createStatement()) {
                s.execute("DELETE FROM " + SCHEMA + ".widgets");
            }
            assertEquals("[]", selectScalarString(pg, "SELECT " + SCHEMA + ".read_widgets()::text"),
                    "an empty result must carrier-return '[]' (not null)");
        }
    }

    @Test
    void genericReaderCarrierDeploysAndStreamsResultSetOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgets.java", GENERIC_READER_SRC, "mysql");
        // The carrier is a PROCEDURE (dynamic SQL is forbidden in a MySQL FUNCTION) streaming a result set.
        assertTrue(generated.stream().anyMatch(a -> "mysql".equals(a.target())
                        && a.sql().contains("CREATE PROCEDURE") && !a.sql().contains("CREATE FUNCTION")),
                "MySQL must emit the carrier as a PROCEDURE (a function forbids dynamic SQL)");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsMysql(my);
            deploy(my, generated, "mysql");

            // Non-empty: CALL streams a result set with one row per seeded row, every column present.
            List<String> labels = callReadWidgetsLabels(my, SCHEMA + ".read_widgets");
            assertEquals(List.of("gadget", "gizmo", "gewgaw"), labels,
                    "the MySQL carrier result set must stream every row's columns, in order");

            // Empty result -> the result set streams zero rows (matching new ArrayList<>() over no rows).
            try (Statement s = my.createStatement()) {
                s.execute("DELETE FROM " + SCHEMA + ".widgets");
            }
            assertTrue(callReadWidgetsLabels(my, SCHEMA + ".read_widgets").isEmpty(),
                    "an empty result must stream zero result-set rows");
        }
    }

    @Test
    void genericReaderBoundCarrierFiltersByBoundValueOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgetsMin.java", GENERIC_READER_BOUND_SRC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r5_carrier_bound_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsPostgres(pg);
            deploy(pg, generated, "postgresql");
            // qty values are 5, 9, 2 — minQty=5 selects exactly the two rows with qty >= 5.
            assertEquals(2, countJsonArrayElements(pg, SCHEMA + ".read_widgets_min(5)"),
                    "the bound carrier must filter by the bound value (qty >= 5 -> 2 rows)");
            assertEquals(3, countJsonArrayElements(pg, SCHEMA + ".read_widgets_min(0)"),
                    "qty >= 0 selects every row");
        }
    }

    @Test
    void genericReaderBoundCarrierFiltersByBoundValueOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgetsMin.java", GENERIC_READER_BOUND_SRC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsMysql(my);
            deploy(my, generated, "mysql");
            // qty values are 5, 9, 2 — minQty=5 streams exactly the two rows with qty >= 5 (gadget, gizmo).
            try (PreparedStatement statement = my.prepareStatement("CALL " + SCHEMA + ".read_widgets_min(?)")) {
                statement.setInt(1, 5);
                assertEquals(List.of("gadget", "gizmo"), readLabelColumn(statement),
                        "the bound MySQL carrier must stream only the rows matching the bound value");
            }
        }
    }

    @Test
    void genericReaderCarrierTrailingSemicolonDeploysAndReturnsJsonOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgetsSemi.java", GENERIC_READER_SEMI_SRC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r5_carrier_semi_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsPostgres(pg);
            deploy(pg, generated, "postgresql");
            // The trailing ';' source must CALL cleanly (un-stripped, this throws "syntax error at ';'").
            assertEquals(3, countJsonArrayElements(pg, SCHEMA + ".read_widgets()"),
                    "the trailing-';' carrier must return one JSON object per seeded row (no CALL-time syntax error)");
            String json = selectScalarString(pg, "SELECT " + SCHEMA + ".read_widgets()::text");
            assertTrue(json.contains("\"gadget\"") && json.contains("\"gizmo\""),
                    "the trailing-';' carrier must return every row's data; was: " + json);
        }
    }

    @Test
    void genericReaderCarrierTrailingSemicolonStreamsResultSetOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadWidgetsSemi.java", GENERIC_READER_SEMI_SRC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedWidgetsMysql(my);
            deploy(my, generated, "mysql");
            assertEquals(List.of("gadget", "gizmo", "gewgaw"), callReadWidgetsLabels(my, SCHEMA + ".read_widgets"),
                    "the trailing-';' MySQL carrier must stream every row, in order");
        }
    }

    @Test
    void genericReaderCarrierRoundTripsMixedTypesOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadTyped.java", GENERIC_READER_TYPED_SRC, "postgresql");
        TestContainers.SharedDatabase db = TestContainers.freshPostgresDatabase("jdbc_r5_carrier_typed_pg");
        try (Connection pg = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedTypedWidgetsPostgres(pg);
            deploy(pg, generated, "postgresql");

            // Row 0 (id=1): every column's VALUE and JSON TYPE round-trips. ->> yields text; -> yields the
            // raw JSON token, so a JSON number/bool/null is distinguishable from a quoted string.
            assertEquals("1", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->>'id')"),
                    "BIGINT id round-trips as the JSON number 1");
            assertEquals("5", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->>'qty')"),
                    "INT qty round-trips as the JSON number 5");
            assertEquals("19.99", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->>'price')"),
                    "NUMERIC(10,2) price round-trips as the JSON number 19.99");
            assertEquals("true", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->'active')::text"),
                    "BOOLEAN active round-trips as the JSON boolean true (not a string)");
            assertEquals("gadget", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->>'label')"),
                    "TEXT label round-trips");
            // Row 0's note is NULL -> JSON null (the JSON token 'null', distinct from SQL NULL).
            assertEquals("null", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->0->'note')::text"),
                    "a NULL column round-trips as JSON null");
            // Row 1 (id=2): a fractional price, active=false, and a note with an embedded double-quote.
            assertEquals("1234567.89", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->1->>'price')"),
                    "a large NUMERIC round-trips exactly");
            assertEquals("false", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->1->'active')::text"),
                    "BOOLEAN false round-trips as the JSON boolean false");
            assertEquals("has \"quote\"", selectScalarString(pg, "SELECT (" + SCHEMA + ".read_typed()->1->>'note')"),
                    "an embedded double-quote in a string value round-trips (JSON-escaped then decoded)");
        }
    }

    @Test
    void genericReaderCarrierRoundTripsMixedTypesOnMysql() throws Exception {
        List<TranspilationPipeline.GeneratedSql> generated =
                transpileSource("ReadTyped.java", GENERIC_READER_TYPED_SRC, "mysql");
        TestContainers.SharedDatabase db = TestContainers.freshMysqlDatabase(SCHEMA);
        try (Connection my = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password())) {
            seedTypedWidgetsMysql(my);
            deploy(my, generated, "mysql");
            // The MySQL carrier streams every column with its native type — read each via getObject and
            // assert the value per row (BIGINT/VARCHAR/INT/DECIMAL/BOOLEAN/TEXT incl. NULL + embedded quote).
            try (PreparedStatement statement = my.prepareStatement("CALL " + SCHEMA + ".read_typed()")) {
                assertTrue(statement.execute(), "the MySQL carrier must stream a result set");
                try (var rs = statement.getResultSet()) {
                    assertTrue(rs.next(), "row 1 (id=1)");
                    assertEquals(1L, rs.getLong("id"));
                    assertEquals("gadget", rs.getString("label"));
                    assertEquals(5, rs.getInt("qty"));
                    assertEquals(0, new BigDecimal("19.99").compareTo(rs.getBigDecimal("price")),
                            "DECIMAL(10,2) price round-trips exactly");
                    assertTrue(rs.getBoolean("active"), "active=true round-trips");
                    assertEquals(null, rs.getString("note"), "a NULL note streams as SQL NULL");

                    assertTrue(rs.next(), "row 2 (id=2)");
                    assertEquals(2L, rs.getLong("id"));
                    assertEquals(0, new BigDecimal("1234567.89").compareTo(rs.getBigDecimal("price")),
                            "a large DECIMAL round-trips exactly");
                    assertTrue(!rs.getBoolean("active"), "active=false round-trips");
                    assertEquals("has \"quote\"", rs.getString("note"), "an embedded double-quote round-trips");
                }
            }
        }
    }

    /**
     * Seeds a 6-column mixed-type {@code billing.widgets} on PostgreSQL: id BIGINT, label TEXT, qty INT,
     * price NUMERIC(10,2), active BOOLEAN, note TEXT (row 1 NULL note; row 2 has an embedded double-quote).
     */
    private static void seedTypedWidgetsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".widgets ("
                    + "id BIGINT PRIMARY KEY, label TEXT NOT NULL, qty INTEGER NOT NULL, "
                    + "price NUMERIC(10,2) NOT NULL, active BOOLEAN NOT NULL, note TEXT)");
            statement.execute("INSERT INTO " + SCHEMA + ".widgets (id, label, qty, price, active, note) VALUES "
                    + "(1, 'gadget', 5, 19.99, true, NULL), "
                    + "(2, 'gizmo', 9, 1234567.89, false, 'has \"quote\"')");
        }
    }

    /** Seeds the same 6-column mixed-type widgets table on MySQL (BOOLEAN is TINYINT(1)). */
    private static void seedTypedWidgetsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".widgets ("
                    + "id BIGINT PRIMARY KEY, label VARCHAR(64) NOT NULL, qty INT NOT NULL, "
                    + "price DECIMAL(10,2) NOT NULL, active BOOLEAN NOT NULL, note TEXT)");
            statement.execute("INSERT INTO " + SCHEMA + ".widgets (id, label, qty, price, active, note) VALUES "
                    + "(1, 'gadget', 5, 19.99, true, NULL), "
                    + "(2, 'gizmo', 9, 1234567.89, false, 'has \"quote\"')");
        }
    }

    /** Seeds a 3-column {@code billing.widgets} table on PostgreSQL (id, label, qty). */
    private static void seedWidgetsPostgres(Connection pg) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute("CREATE TABLE " + SCHEMA + ".widgets ("
                    + "id BIGINT PRIMARY KEY, label TEXT NOT NULL, qty INTEGER NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".widgets (id, label, qty) VALUES "
                    + "(1, 'gadget', 5), (2, 'gizmo', 9), (3, 'gewgaw', 2)");
        }
    }

    /** Seeds the same 3-column widgets table on MySQL. */
    private static void seedWidgetsMysql(Connection my) throws SQLException {
        try (Statement statement = my.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + ".widgets ("
                    + "id BIGINT PRIMARY KEY, label VARCHAR(64) NOT NULL, qty INT NOT NULL)");
            statement.execute("INSERT INTO " + SCHEMA + ".widgets (id, label, qty) VALUES "
                    + "(1, 'gadget', 5), (2, 'gizmo', 9), (3, 'gewgaw', 2)");
        }
    }

    /** Reads a single scalar string from a one-row, one-column query. */
    private static String selectScalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "expected a row from: " + sql);
            return resultSet.getString(1);
        }
    }

    /**
     * The number of elements in the JSON array the carrier function {@code qualifiedCall} returns
     * (PostgreSQL) — {@code qualifiedCall} is the schema-qualified function call, e.g. {@code
     * billing.read_widgets()}.
     */
    private static int countJsonArrayElements(Connection connection, String qualifiedCall) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT jsonb_array_length(" + qualifiedCall + ")")) {
            assertTrue(resultSet.next(), "expected a count row");
            return resultSet.getInt(1);
        }
    }

    /** CALLs a MySQL carrier procedure and reads its streamed result set's {@code label} column, in order. */
    private static List<String> callReadWidgetsLabels(Connection connection, String procedure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("CALL " + procedure + "()")) {
            return readLabelColumn(statement);
        }
    }

    /** Executes a prepared CALL and collects its streamed result set's {@code label} column, in order. */
    private static List<String> readLabelColumn(PreparedStatement statement) throws SQLException {
        List<String> labels = new java.util.ArrayList<>();
        boolean hasResultSet = statement.execute();
        if (hasResultSet) {
            try (var resultSet = statement.getResultSet()) {
                while (resultSet.next()) {
                    labels.add(resultSet.getString("label"));
                }
            }
        }
        return labels;
    }

    private static void deploy(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated,
            String target
    ) throws SQLException {
        for (TranspilationPipeline.GeneratedSql artifact : generated) {
            if (!target.equals(artifact.target())) {
                continue;
            }
            for (String sql : SqlScripts.split(artifact.sql())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException exception) {
                    throw new SQLException("Failed to deploy generated " + target
                            + " artifact " + artifact.artifactName() + ":\n" + sql, exception);
                }
            }
        }
    }
}
