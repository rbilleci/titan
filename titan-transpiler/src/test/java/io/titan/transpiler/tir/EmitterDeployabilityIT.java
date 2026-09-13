package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase 0 exit-criteria "deployability gate" (plan item 5.2, pulled forward in minimal form):
 * every emitted artifact must actually CREATE on live PostgreSQL 16 and MySQL 8.4.
 *
 * <p>Emitter unit tests assert substrings of generated SQL and therefore happily pass
 * syntactically invalid output — the E-1 bug ({@code SIGNAL SQLSTATE IFNULL(...)}, which fails
 * at CREATE time for every MySQL routine containing try/catch) shipped exactly that way. This
 * IT transpiles one fixture covering the Phase-0-touched emission shapes and deploys every
 * generated artifact against both databases, then executes the routines on their happy paths
 * to catch handler-ordering and name-resolution problems that deployment alone cannot.</p>
 *
 * <p>Shapes covered: try/catch with finally and the always-emitted unhandled-rethrow tail
 * (the new MySQL SIGNAL-via-variable rethrow), throw of RuntimeException (RaiseStatement),
 * String intrinsics (charAt arithmetic → __titan_char_code/ASCII, indexOf with fromIndex,
 * startsWith with offset, null-aware equals), String += concatenation (F-6), an int switch
 * (deterministic temp naming, F-5), an enum switch with bare case labels (F-2), a DSL
 * insert lowered to ON CONFLICT DO NOTHING / the new MySQL ON DUPLICATE KEY no-op (0.11),
 * the Phase 2.1 control-flow set: unlabeled break/continue in nested loops (MySQL loop
 * labels), counting for-loops lowered to ForRangeStatement (PG native FOR vs MySQL
 * increment-first LOOP desugar), and the temp-bound do-while condition (audit D12), plus the
 * Phase 2.2 expression set: a supported cast chain (widening CASTs and the truncate-toward-zero
 * composition for fractional-to-integral narrowing, F-9), dynamic throw messages surviving to
 * the database error (F-10), and two custom exceptions with distinct SQLSTATEs where the catch
 * of one must not intercept the other while a supertype catch takes both (F-11), plus the
 * Phase 2.3 null-safety set (N2): compareTo on a NULL column value must raise a
 * NullPointerException-parity error on both databases (via the NullAnalysisPass-inserted null
 * guard) instead of silently comparing equal, plus the Phase 2.4 emulation-insertion set
 * (E-7/E-11): int/int division truncating toward zero with SQLSTATE 22012 on a zero divisor
 * (MySQL natively yields DECIMAL and silent NULL), modulo taking the dividend's sign, and a
 * second fixture transpiled in strict-wraparound mode whose int arithmetic reproduces Java's
 * silent 32-bit wrap via the java_int_add/sub/mul runtime helpers — which also makes this IT
 * deploy the runtime migration scripts on both databases like production installs, plus the
 * Phase A4 keystone (G2 / spike B2): the read-decide-branch read-into-local set — a faithful
 * idempotent-replay routine that reads the idempotency key into a boolean local via
 * {@code SELECT EXISTS(...) INTO} and RETURNs early on replay (proving the short-circuit runs
 * server-side, since the audit insert has no ON CONFLICT and the audit id is database-generated,
 * so a non-short-circuiting replay would append a second audit row), and a scalar
 * optimistic-concurrency precondition that reads the current version into an int local via
 * {@code SELECT version INTO} and branches (a stale apply is a server-side no-op, a newer one
 * writes).</p>
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class EmitterDeployabilityIT {
    private static final String SCHEMA = "test";

    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void everyEmittedArtifactDeploysAndExecutesOnPostgresAndMySql() throws Exception {
        Path source = write(GoldenSqlCorpus.EMITTER_DEPLOYABILITY.source());

        // Phase 2.4 (E-11): a separate fixture transpiled in strict-wraparound mode — int
        // arithmetic must reproduce Java's silent 32-bit wrap via the java_int_add/sub/mul
        // runtime helpers (the default fail-loud mode instead raises on overflow, which the
        // wrap_add assertions below would trip over).
        Path wraparoundSource = write(GoldenSqlCorpus.STRICT_WRAPAROUND.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        List<TranspilationPipeline.GeneratedSql> wraparoundGenerated = new TranspilationPipeline().transpile(
                List.of(wraparoundSource),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true,
                List.of(),
                false,
                List.of(),
                false,
                true);

        // Pin the strict-wraparound emission shape before proving it deploys and wraps.
        assertTrue(sqlFor(wraparoundGenerated, "postgresql").contains("titan_runtime.java_int_add(p_a, p_b)"),
                "expected strict-wraparound PostgreSQL addition to call the runtime helper");
        assertTrue(sqlFor(wraparoundGenerated, "mysql").contains("titan_rt_java_int_add(p_a, p_b)"),
                "expected strict-wraparound MySQL addition to call the runtime helper");

        // The E-1 regression shipped because substring assertions passed invalid SQL. Pin the
        // fixed rethrow shape before proving it deploys: the saved message is re-signalled via a
        // variable, never via an expression after SIGNAL SQLSTATE.
        String mysqlSql = sqlFor(generated, "mysql");
        assertFalse(mysqlSql.contains("SIGNAL SQLSTATE IFNULL"),
                "E-1 regression: SIGNAL SQLSTATE must be followed by a string literal, not an expression");
        assertTrue(mysqlSql.contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message"),
                "expected the unhandled-rethrow tail to re-signal via the saved-message variable");

        // Phase 2.3 (N2): pin the NullAnalysisPass compareTo shape before proving it deploys —
        // a null guard ahead of the comparison on both dialects, and an ELSE-less CASE so a NULL
        // operand can never silently compare "equal".
        String postgresSql = sqlFor(generated, "postgresql");
        assertTrue(postgresSql.contains("IF p_current IS NULL THEN RAISE EXCEPTION 'NullPointerException at "),
                "expected the PostgreSQL compareTo null guard to raise NullPointerException");
        assertTrue(mysqlSql.contains("IF p_current IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at "),
                "expected the MySQL compareTo null guard to signal NullPointerException");
        assertTrue(postgresSql.contains("WHEN (p_current = p_threshold) THEN 0 END"),
                "expected the compareTo CASE to end with the equality branch (no ELSE 0)");
        assertTrue(mysqlSql.contains("WHEN (p_current = p_threshold) THEN 0 END"),
                "expected the compareTo CASE to end with the equality branch (no ELSE 0)");

        // Phase 2.4 (E-7): pin the EmulationInsertionPass division/modulo shapes before proving
        // them — native operators on PostgreSQL (already Java-exact), runtime helpers with the
        // 22012 zero guard on MySQL (whose native '/' yields DECIMAL and NULL on zero).
        assertTrue(postgresSql.contains("(p_dividend / p_divisor)"),
                "expected PostgreSQL int division to stay the native operator");
        assertTrue(postgresSql.contains("(p_dividend % p_divisor)"),
                "expected PostgreSQL int modulo to stay the native operator");
        assertTrue(mysqlSql.contains("titan_rt_java_int_div(p_dividend, p_divisor)"),
                "expected MySQL int division to call the truncating, zero-guarded runtime helper");
        assertTrue(mysqlSql.contains("titan_rt_java_mod(p_dividend, p_divisor)"),
                "expected MySQL int modulo to call the zero-guarded runtime helper");

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            deployPostgres(postgres, generated);
            deployMySql(mysql, generated);
            deploy(postgres, wraparoundGenerated, "postgresql");
            deploy(mysql, wraparoundGenerated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                // Execute the try/catch routine on the happy path and the caught path to prove
                // handler dispatch ordering, not just CREATE-time syntax.
                assertEquals(javaGuardedLabel("alpha", false),
                        callGuardedLabel(connection, "alpha", false), dialect + " guarded_label happy path");
                assertEquals(javaGuardedLabel("alpha", true),
                        callGuardedLabel(connection, "alpha", true), dialect + " guarded_label caught path");

                assertEquals(javaRejectNegative(21), callIntFunction(connection, "reject_negative", 21),
                        dialect + " reject_negative happy path");
                assertRejectNegativeRaises(connection, dialect);

                assertEquals(javaStringProbe("abcabc", "ca"),
                        callStringProbe(connection, "abcabc", "ca"), dialect + " string_probe");
                assertEquals(javaStringProbe("zebra", "zeb"),
                        callStringProbe(connection, "zebra", "zeb"), dialect + " string_probe prefix");
                assertEquals(javaStringProbe("same", "same"),
                        callStringProbe(connection, "same", "same"), dialect + " string_probe equals");

                for (int input : new int[]{1, 2, 7}) {
                    assertEquals(javaSwitchScore(input), callIntFunction(connection, "switch_score", input),
                            dialect + " switch_score(" + input + ")");
                }

                assertEquals(1, callTierRank(connection, "BASIC"), dialect + " tier_rank(BASIC)");
                assertEquals(2, callTierRank(connection, "PRO"), dialect + " tier_rank(PRO)");

                // Nested break/continue: inner-loop control flow must not leak to the outer
                // loop (MySQL LEAVE/ITERATE label binding), including the outer break path.
                assertEquals(javaNestedLoopScore(3, 6),
                        callIntFunction2(connection, "nested_loop_score", 3, 6),
                        dialect + " nested_loop_score(3, 6)");
                assertEquals(javaNestedLoopScore(2, 2),
                        callIntFunction2(connection, "nested_loop_score", 2, 2),
                        dialect + " nested_loop_score(2, 2)");
                assertEquals(javaNestedLoopScore(50, 9),
                        callIntFunction2(connection, "nested_loop_score", 50, 9),
                        dialect + " nested_loop_score(50, 9) outer-break path");
                assertEquals(javaNestedLoopScore(4, 0),
                        callIntFunction2(connection, "nested_loop_score", 4, 0),
                        dialect + " nested_loop_score(4, 0) empty inner loop");

                // Counting for-loops (ForRangeStatement): continue inside the range body,
                // inclusive bounds, and the zero-iteration case when from > upTo.
                assertEquals(javaCountingSum(1, 5),
                        callIntFunction2(connection, "counting_sum", 1, 5),
                        dialect + " counting_sum(1, 5)");
                assertEquals(javaCountingSum(3, 3),
                        callIntFunction2(connection, "counting_sum", 3, 3),
                        dialect + " counting_sum(3, 3)");
                assertEquals(javaCountingSum(5, 1),
                        callIntFunction2(connection, "counting_sum", 5, 1),
                        dialect + " counting_sum(5, 1) zero iterations");
                assertEquals(javaCountingSum(-2, 2),
                        callIntFunction2(connection, "counting_sum", -2, 2),
                        dialect + " counting_sum(-2, 2) negative start");

                // do-while with a continue that must re-test the condition (audit D12 shape).
                assertEquals(javaDoWhileCountdown(5),
                        callIntFunction(connection, "do_while_countdown", 5),
                        dialect + " do_while_countdown(5) continue path");
                assertEquals(javaDoWhileCountdown(0),
                        callIntFunction(connection, "do_while_countdown", 0),
                        dialect + " do_while_countdown(0) single-iteration path");

                // Phase 2.2 (F-9): cast chain result equality against Java, including the
                // negative truncation case where SQL rounding would diverge (Java truncates
                // -7.5 toward zero to -7; CAST-without-TRUNC would round to -8).
                assertEquals(javaCastChain(7, 2.5),
                        callCastChain(connection, 7, 2.5), dialect + " cast_chain(7, 2.5)");
                assertEquals(javaCastChain(-3, 2.5),
                        callCastChain(connection, -3, 2.5), dialect + " cast_chain(-3, 2.5) negative truncation");
                assertEquals(javaCastChain(3, 1.3),
                        callCastChain(connection, 3, 1.3), dialect + " cast_chain(3, 1.3) fractional truncation");

                // Phase 2.2 (F-11): catch of the second exception only — the first must
                // propagate to the client instead of being intercepted, carrying its dynamic
                // message (F-10); equal results against Java on the non-throwing paths.
                assertEquals(javaExceptionDispatch(0),
                        callStringFunction(connection, "exception_dispatch", 0),
                        dialect + " exception_dispatch(0) no-throw path");
                assertEquals(javaExceptionDispatch(2),
                        callStringFunction(connection, "exception_dispatch", 2),
                        dialect + " exception_dispatch(2) caught path");
                assertQuotaBreachPropagates(connection, dialect);

                // Phase 2.2 (F-11): a catch of the common supertype catches both.
                for (int code : new int[]{0, 1, 2}) {
                    assertEquals(javaExceptionSupertype(code),
                            callStringFunction(connection, "exception_supertype", code),
                            dialect + " exception_supertype(" + code + ")");
                }

                // Phase 2.3 (N2): compareTo NPE parity. Non-null column values compare exactly
                // like the Java reference; the NULL column value must raise on both databases
                // (asserted via the SQLException catch path) instead of comparing "equal".
                assertEquals(javaCompareScore(java.math.BigDecimal.valueOf(9), java.math.BigDecimal.valueOf(5)),
                        callCompareScoreFromColumn(connection, 2, 5),
                        dialect + " compare_score greater-than column value");
                assertEquals(javaCompareScore(java.math.BigDecimal.valueOf(2), java.math.BigDecimal.valueOf(5)),
                        callCompareScoreFromColumn(connection, 3, 5),
                        dialect + " compare_score less-than column value");
                assertEquals(javaCompareScore(java.math.BigDecimal.valueOf(5), java.math.BigDecimal.valueOf(5)),
                        callCompareScoreFromColumn(connection, 4, 5),
                        dialect + " compare_score equal column value");
                assertCompareScoreOnNullColumnRaises(connection, dialect);

                // Phase 2.4 (E-7): integer division truncates toward zero like Java for every
                // sign combination (MySQL's native 7/2 = 3.5 would round to 4 on assignment),
                // and a zero divisor raises SQLSTATE 22012 instead of MySQL's silent NULL.
                for (int[] operands : new int[][]{{7, 2}, {-7, 2}, {7, -2}, {-7, -2}, {0, 5}}) {
                    assertEquals(javaIntRatio(operands[0], operands[1]),
                            callIntFunction2(connection, "int_ratio", operands[0], operands[1]),
                            dialect + " int_ratio(" + operands[0] + ", " + operands[1] + ")");
                }
                assertDivisionByZeroRaises(connection, dialect, "int_ratio");

                // Phase 2.4 (E-7): modulo takes the dividend's sign like Java (-7%3 = -1,
                // 7%-3 = 1) and raises 22012 on a zero divisor.
                for (int[] operands : new int[][]{{-7, 3}, {7, -3}, {-7, -3}, {7, 3}, {0, 5}}) {
                    assertEquals(javaIntRemainder(operands[0], operands[1]),
                            callIntFunction2(connection, "int_remainder", operands[0], operands[1]),
                            dialect + " int_remainder(" + operands[0] + ", " + operands[1] + ")");
                }
                assertDivisionByZeroRaises(connection, dialect, "int_remainder");

                // Phase 2.4 (E-11): strict-wraparound mode reproduces Java's silent 32-bit wrap
                // end-to-end, including the extremes and negative operands.
                for (int[] operands : new int[][]{
                        {Integer.MAX_VALUE, 1},
                        {Integer.MIN_VALUE, -1},
                        {Integer.MAX_VALUE, Integer.MAX_VALUE},
                        {-46341, 46341},
                        {7, 6}}) {
                    int a = operands[0];
                    int b = operands[1];
                    assertEquals(a + b, callIntFunction2(connection, "wrap_add", a, b),
                            dialect + " wrap_add(" + a + ", " + b + ")");
                    assertEquals(a - b, callIntFunction2(connection, "wrap_sub", a, b),
                            dialect + " wrap_sub(" + a + ", " + b + ")");
                    assertEquals(a * b, callIntFunction2(connection, "wrap_mul", a, b),
                            dialect + " wrap_mul(" + a + ", " + b + ")");
                }

                // First insert lands; the conflicting second insert must be a silent no-op on
                // both dialects (PG: DO NOTHING, MySQL: ON DUPLICATE KEY UPDATE id = id).
                callRecordEvent(connection, 1, "first");
                callRecordEvent(connection, 1, "second");
                assertEquals(List.of("first"), readGateEventNames(connection), dialect + " conflict no-op");

                // G1 (spike B1): a routine doing a discarded SELECT ... FOR UPDATE must now
                // EXECUTE on PG, not just deploy. Before the fix the bare SELECT raised
                // "query has no destination for result data" at CALL time (the deploy-vs-execute
                // gap the spike proved). On id=1 the row exists (locked + name updated); on id=2
                // it does not (lock read returns no rows, then the upsert inserts it).
                callLockAndTouchEvent(connection, 1, "locked-update");
                callLockAndTouchEvent(connection, 2, "locked-insert");
                assertEquals(List.of("locked-update", "locked-insert"),
                        readGateEventNames(connection), dialect + " lockAndTouchEvent executes");

                // G4 (spike B4): a String inserted into a JSONB (PG) / JSON (MySQL) column must
                // EXECUTE and round-trip. Before the fix PG raised "column is of type jsonb but
                // expression is of type text" at CALL time. The document is read back and compared
                // as a parsed JSON object so whitespace/key-order normalisation does not matter.
                callStoreEventDocument(connection, 3, "doc-row", "{\"k\": 1}");
                assertEquals("1", readGateEventDocumentField(connection, 3, "k"),
                        dialect + " storeEventDocument round-trips JSON");
                // The ON CONFLICT / ON DUPLICATE KEY SET value is also cast: replay updates the doc.
                callStoreEventDocument(connection, 3, "doc-row", "{\"k\": 2}");
                assertEquals("2", readGateEventDocumentField(connection, 3, "k"),
                        dialect + " storeEventDocument upsert re-casts JSON");

                // G3 (spike B3): expression-valued set(VERSION, VERSION.add(1)) — an atomic
                // server-side counter bump in a plain UPDATE. The row at id=1 was inserted by
                // record_event at version 0; calling bump_event_version twice must increment the
                // counter to 1 then 2 with no value supplied by the caller (the database computes
                // version = version + 1 in place). Before this fix the routine did not compile
                // (TITAN-E001: set(Column<T>, T) rejected the Column<Integer> right-hand side).
                assertEquals(0, readGateEventVersion(connection, 1), dialect + " version starts at 0");
                callBumpEventVersion(connection, 1);
                assertEquals(1, readGateEventVersion(connection, 1), dialect + " UPDATE bump 0->1");
                callBumpEventVersion(connection, 1);
                assertEquals(2, readGateEventVersion(connection, 1), dialect + " UPDATE bump 1->2");

                // G3 (spike B3): the same expression-valued SET in an ON CONFLICT DO UPDATE /
                // ON DUPLICATE KEY UPDATE upsert. The first call inserts at version 1; each replay
                // bumps the conflicting row's version server-side (1 -> 2 -> 3).
                callUpsertEventVersion(connection, 9, "upsert-counter");
                assertEquals(1, readGateEventVersion(connection, 9), dialect + " upsert inserts at version 1");
                callUpsertEventVersion(connection, 9, "upsert-counter");
                assertEquals(2, readGateEventVersion(connection, 9), dialect + " upsert bump 1->2");
                callUpsertEventVersion(connection, 9, "upsert-counter");
                assertEquals(3, readGateEventVersion(connection, 9), dialect + " upsert bump 2->3");

                // G2 (spike B2) — THE KEYSTONE: faithful idempotent replay via read-decide-branch.
                // The routine reads the idempotency key into a boolean local and RETURNs early when
                // present, WITHOUT any ON CONFLICT dedupe on the audit insert. The audit id is
                // database-generated, so a replay that did NOT short-circuit would append a SECOND
                // audit row. First apply: draft mutates (id=20 → "import-a"), exactly one audit row,
                // one idempotency row.
                callIdempotentImport(connection, 20, "import-a", "key-A");
                assertEquals("import-a", readGateEventName(connection, 20), dialect + " first apply mutates draft");
                assertEquals(1, countAuditRows(connection, "key-A"), dialect + " first apply writes one audit row");
                assertTrue(idempotencyKeyPresent(connection, "key-A"), dialect + " first apply records the key");

                // Replay with the SAME key and a DIFFERENT name: the routine must short-circuit
                // server-side BEFORE the draft upsert and the audit insert. The draft name must NOT
                // change to "import-a-REPLAY", and there must still be exactly ONE audit row for the
                // key — proving the read-decide-branch RETURN executed in the database, not a dedupe.
                callIdempotentImport(connection, 20, "import-a-REPLAY", "key-A");
                assertEquals("import-a", readGateEventName(connection, 20),
                        dialect + " replay must NOT re-mutate the draft (server-side short-circuit)");
                assertEquals(1, countAuditRows(connection, "key-A"),
                        dialect + " replay must NOT append a second audit row (server-side short-circuit)");

                // A genuinely new key for a different draft mutates again and writes its own audit
                // row — the early-return is keyed, not a blanket skip.
                callIdempotentImport(connection, 21, "import-b", "key-B");
                assertEquals("import-b", readGateEventName(connection, 21), dialect + " new key applies");
                assertEquals(1, countAuditRows(connection, "key-B"), dialect + " new key writes one audit row");
                assertEquals(1, countAuditRows(connection, "key-A"), dialect + " key-A still has one audit row");

                // G2 (spike B2): scalar precondition / optimistic-concurrency check. Read the
                // current version into an int local and branch. id=30 is new: the local is NULL, the
                // comparison is UNKNOWN, the guard does not fire, so the first apply inserts at v5.
                callApplyIfNewer(connection, 30, 5, "v5");
                assertEquals(5, readGateEventVersion(connection, 30), dialect + " precondition first apply inserts at v5");
                assertEquals("v5", readGateEventName(connection, 30), dialect + " precondition first apply sets name");

                // A stale apply (newVersion <= current) must short-circuit server-side: neither the
                // version nor the name changes.
                callApplyIfNewer(connection, 30, 5, "v5-stale-equal");
                assertEquals(5, readGateEventVersion(connection, 30), dialect + " equal version is a no-op");
                assertEquals("v5", readGateEventName(connection, 30), dialect + " equal version leaves name");
                callApplyIfNewer(connection, 30, 3, "v3-stale-older");
                assertEquals(5, readGateEventVersion(connection, 30), dialect + " older version is a no-op");
                assertEquals("v5", readGateEventName(connection, 30), dialect + " older version leaves name");

                // A strictly newer apply passes the precondition and writes.
                callApplyIfNewer(connection, 30, 8, "v8");
                assertEquals(8, readGateEventVersion(connection, 30), dialect + " newer version applies");
                assertEquals("v8", readGateEventName(connection, 30), dialect + " newer version updates name");
            }
        }
    }

    /**
     * E-5 (plan 3.1): Java aborts the remainder of a try block as soon as a statement throws —
     * statements after the failing one must NOT execute. The MySQL lowering used CONTINUE
     * handlers scoped to the try-body block, which resume at the NEXT try-body statement after
     * intercepting the error, so a side effect placed after the failing statement still ran
     * (diverging from Java and from PostgreSQL's nested-block semantics). This IT runs the
     * try/throw/side-effect shape live on both databases and compares the observable result
     * against the in-JVM Java reference.
     *
     * <p>The side effect is a routine-variable mutation rather than DML on purpose: plpgsql
     * EXCEPTION blocks are subtransactions that roll back DML performed before the failing
     * statement when the exception is caught (an inherent PostgreSQL-lowering divergence from
     * Java, observed while building this IT), while variable assignments are non-transactional
     * on both dialects — so the variable isolates exactly the E-5 question.</p>
     */
    @Test
    void tryBlockStatementsAfterAFailureMustNotExecute() throws Exception {
        Path source = write(GoldenSqlCorpus.TRY_ABORT.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                assertEquals(javaTrySideEffects(false), callTrySideEffects(connection, false),
                        dialect + " try_side_effects(false) happy path");

                // The statement AFTER the throw must not have executed: the expected value is
                // exactly "start:before:caught", never "start:before:after:caught".
                assertEquals(javaTrySideEffects(true), callTrySideEffects(connection, true),
                        dialect + " try_side_effects(true): try-body statements after the "
                                + "failing statement must not execute (Java EXIT-handler parity, E-5)");
            }
        }
    }

    /**
     * Phase 3.1 defect: composite Java expressions in DSL {@code .set(...)} value positions used
     * to be stringified into the SQL as raw source text ({@code id + 100}), bypassing the
     * RoutineSqlNameAllocator remapping to the allocated {@code p_}/{@code v_} routine names —
     * 'column does not exist' at runtime on both dialects, while a plain {@code set(T.ID, id)}
     * remapped fine. Set values now lower through ExpressionLowerer as real expression trees.
     * This IT covers a parameter + literal, a parameter * parameter, and a string concat with a
     * parameter — executed live and read back, result-equal against the in-JVM Java reference.
     */
    @Test
    void dslSetValueExpressionsRemapToRoutineParameterNames() throws Exception {
        Path source = write(GoldenSqlCorpus.SET_VALUE_EXPRESSION.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        // Pin the remapped shape before proving it deploys: the INSERT must reference the
        // allocated p_ parameter names, never the raw Java identifiers (`\bid + 100` style),
        // which resolve as nonexistent columns at runtime.
        java.util.regex.Pattern rawIdPlus = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])id\\s*\\+\\s*100");
        java.util.regex.Pattern rawIdTimes = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])id\\s*\\*\\s*factor");
        for (String target : List.of("postgresql", "mysql")) {
            String sql = sqlFor(generated, target);
            assertTrue(sql.contains("p_id"),
                    target + ": expected the set(...) arithmetic to reference the remapped p_id parameter");
            assertTrue(sql.contains("p_factor"),
                    target + ": expected the set(...) arithmetic to reference the remapped p_factor parameter");
            assertTrue(sql.contains("p_name"),
                    target + ": expected the set(...) string concat to reference the remapped p_name parameter");
            assertFalse(rawIdPlus.matcher(sql).find(),
                    target + ": set(...) value emitted the raw Java identifier 'id + 100' instead of p_id");
            assertFalse(rawIdTimes.matcher(sql).find(),
                    target + ": set(...) value emitted the raw Java identifiers 'id * factor' instead of p_id/p_factor");
        }

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute("CREATE TABLE " + SCHEMA + ".math_events ("
                        + "id INT PRIMARY KEY, score INT NOT NULL, label TEXT NOT NULL)");
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
                statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".math_events");
                statement.execute("CREATE TABLE " + SCHEMA + ".math_events ("
                        + "id INT PRIMARY KEY, score INT NOT NULL, label VARCHAR(191) NOT NULL)");
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                for (int[] operands : new int[][]{{7, 6}, {-3, 5}}) {
                    int id = operands[0];
                    int factor = operands[1];
                    String name = "alpha" + id;
                    int[] expected = javaRecordMath(id, factor);
                    callRecordMath(connection, id, factor, name);
                    int[] actual = readMathEvent(connection, expected[0]);
                    assertEquals(expected[1], actual[0],
                            dialect + " record_math(" + id + ", " + factor + ") score (parameter * parameter)");
                    assertEquals(name + "-tag", readMathEventLabel(connection, expected[0]),
                            dialect + " record_math(" + id + ", " + factor + ") label (string concat with parameter)");
                }
            }
        }
    }

    /**
     * Phase 3.3 (audit D-7): the new DSL surface — table aliases enabling a self-join, DISTINCT,
     * a searched CASE projection, NOT IN, and NULLS LAST ordering — exercised in one fixture.
     * The fixture transpiles through the full pipeline and every generated artifact CREATEs on
     * both databases (deployability); the lowered query itself is then rendered by both emitters
     * and executed against seeded rows, with results compared row-for-row against an in-JVM Java
     * reference (MySQL's emulated NULLS ordering must produce the same row order as PostgreSQL's
     * native NULLS LAST).
     */
    @Test
    void dslSelfJoinDistinctCaseNotInAndNullsOrderingExecuteResultEqualOnBothDatabases() throws Exception {
        Path source = write(GoldenSqlCorpus.PHASE33_SURFACE.source());

        // Deployability: every generated artifact must CREATE on both databases.
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        // The executable query: lower the fixture once and render the SelectSql per dialect.
        io.titan.transpiler.ParsedSources parsed =
                new io.titan.transpiler.JavaSourceParser().parse(List.of(source), List.of(), "21", false);
        var entryPoints = new io.titan.transpiler.EntryPointDiscovery().discover(parsed);
        Block body = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        SelectSql query = (SelectSql) ((ExecuteSqlStatement) body.statements().getLast()).sqlNode();
        String postgresQuery = new PostgreSqlEmitter().visitSelectSql(query);
        String mysqlQuery = new MySqlEmitter().visitSelectSql(query);

        assertTrue(postgresQuery.contains("SELECT DISTINCT"), postgresQuery);
        // ATG-020: the FROM/JOIN clause carries the declared "test" schema; columns use the alias.
        assertTrue(postgresQuery.contains("FROM \"test\".\"employees\" AS \"e\""), postgresQuery);
        assertTrue(postgresQuery.contains("NULLS LAST"), postgresQuery);
        assertTrue(mysqlQuery.contains("FROM `test`.`employees` AS `e`"), mysqlQuery);
        assertTrue(mysqlQuery.contains("(`m`.`score` IS NULL) ASC"),
                "MySQL must emulate NULLS LAST: " + mysqlQuery);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute("CREATE TABLE " + SCHEMA + ".employees ("
                        + "id INT PRIMARY KEY, name TEXT NOT NULL, manager_id INT NULL, score INT NULL)");
                seedEmployees(statement);
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
                statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".employees");
                statement.execute("CREATE TABLE " + SCHEMA + ".employees ("
                        + "id INT PRIMARY KEY, name VARCHAR(191) NOT NULL, manager_id INT NULL, score INT NULL)");
                seedEmployees(statement);
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            List<String> expected = javaSurfaceQueryReference();
            assertEquals(expected, fetchSurfaceRows(postgres, postgresQuery),
                    "postgresql self-join/DISTINCT/CASE/NOT IN/NULLS LAST result rows");
            assertEquals(expected, fetchSurfaceRows(mysql, mysqlQuery),
                    "mysql self-join/DISTINCT/CASE/NOT IN/emulated NULLS LAST result rows");
        }
    }

    /** Employee rows shared by both databases and the Java reference. */
    private static final int[][] EMPLOYEE_ROWS = {
            // id, manager_id (0 = NULL), score (-1 = NULL)
            {1, 0, 95},
            {2, 1, -1},
            {3, 1, 80},
            {4, 2, 95},
            {5, 2, 40},
            {6, 3, -1},
            {7, 3, 80},
            {8, 4, 10},
            {9, 4, 20},
    };

    private static final String[] EMPLOYEE_NAMES = {
            "amara", "bao", "chen", "dipti", "erik", "fern", "gita", "dup", "dup"
    };

    private static void seedEmployees(Statement statement) throws SQLException {
        for (int i = 0; i < EMPLOYEE_ROWS.length; i++) {
            int[] row = EMPLOYEE_ROWS[i];
            statement.execute("INSERT INTO " + SCHEMA + ".employees (id, name, manager_id, score) VALUES ("
                    + row[0] + ", '" + EMPLOYEE_NAMES[i] + "', "
                    + (row[1] == 0 ? "NULL" : String.valueOf(row[1])) + ", "
                    + (row[2] < 0 ? "NULL" : String.valueOf(row[2])) + ")");
        }
    }

    /**
     * In-JVM Java reference: self-join employees e -> m on e.manager_id = m.id, e.id NOT IN
     * (5, 6), DISTINCT (e.name, CASE(m.score), m.score), ORDER BY m.score DESC NULLS LAST,
     * e.name ASC.
     */
    private static List<String> javaSurfaceQueryReference() {
        record Employee(int id, String name, Integer managerId, Integer score) {}
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < EMPLOYEE_ROWS.length; i++) {
            int[] row = EMPLOYEE_ROWS[i];
            employees.add(new Employee(
                    row[0],
                    EMPLOYEE_NAMES[i],
                    row[1] == 0 ? null : row[1],
                    row[2] < 0 ? null : row[2]));
        }

        record ResultRow(String name, String label, Integer score) {}
        java.util.LinkedHashSet<ResultRow> distinctRows = new java.util.LinkedHashSet<>();
        for (Employee e : employees) {
            if (e.managerId() == null || e.id() == 5 || e.id() == 6) {
                continue;
            }
            for (Employee m : employees) {
                if (m.id() != e.managerId()) {
                    continue;
                }
                String label = m.score() != null && m.score() >= 90 ? "top"
                        : m.score() != null && m.score() >= 50 ? "mid"
                        : "low";
                distinctRows.add(new ResultRow(e.name(), label, m.score()));
            }
        }
        return distinctRows.stream()
                .sorted(java.util.Comparator
                        .comparing(ResultRow::score,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(ResultRow::name))
                .map(row -> row.name() + "|" + row.label() + "|" + (row.score() == null ? "NULL" : row.score()))
                .toList();
    }

    private static List<String> fetchSurfaceRows(Connection connection, String selectSql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            if (connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres")) {
                statement.execute("SET search_path TO " + SCHEMA + ", public");
            } else {
                statement.execute("USE " + SCHEMA);
            }
            try (var resultSet = statement.executeQuery(selectSql)) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    String label = resultSet.getString(2);
                    int score = resultSet.getInt(3);
                    boolean scoreNull = resultSet.wasNull();
                    rows.add(name + "|" + label + "|" + (scoreNull ? "NULL" : score));
                }
            }
        }
        return rows;
    }

    /** In-JVM Java reference for {@link #dslSetValueExpressionsRemapToRoutineParameterNames()}. */
    private static int[] javaRecordMath(int id, int factor) {
        return new int[]{id + 100, id * factor};
    }

    private static void callRecordMath(Connection connection, int id, int factor, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL " + SCHEMA + ".record_math(?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, factor);
            statement.setString(3, name);
            statement.execute();
        }
    }

    /** Reads back the inserted row by id; the row existing at id+100 proves the + remapping. */
    private static int[] readMathEvent(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT score FROM " + SCHEMA + ".math_events WHERE id = ?")) {
            statement.setInt(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no math_events row with id " + id
                        + " — the parameter + literal set(...) value did not produce the expected key");
                return new int[]{resultSet.getInt(1)};
            }
        }
    }

    private static String readMathEventLabel(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT label FROM " + SCHEMA + ".math_events WHERE id = ?")) {
            statement.setInt(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no math_events row with id " + id);
                return resultSet.getString(1);
            }
        }
    }

    /** In-JVM Java reference for {@link #tryBlockStatementsAfterAFailureMustNotExecute()}. */
    private static String javaTrySideEffects(boolean explode) {
        String result = "start";
        try {
            result = result + ":before";
            if (explode) {
                throw new RuntimeException("TITAN_E5_BOOM");
            }
            result = result + ":after";
        } catch (RuntimeException error) {
            result = result + ":caught";
        }
        return result;
    }

    private static String callTrySideEffects(Connection connection, boolean explode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".try_side_effects(?)")) {
            statement.setBoolean(1, explode);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "try_side_effects returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    /**
     * E-8 (plan 3.1): generated MySQL routines pin the session to UTC ({@code SET time_zone =
     * '+00:00'}) and historically never restored it, leaking the pinned zone into the caller's
     * session. The fix saves the caller's zone into a DECLAREd routine-local and restores it on
     * every exit path: function RETURN (staged through a typed local so the return expression
     * still evaluates under UTC), procedure fall-through, and exception exits via the
     * routine-level EXIT handler. This IT proves {@code @@session.time_zone} is unchanged after
     * each path on live MySQL.
     */
    @Test
    void mySqlSessionTimeZoneIsRestoredOnEveryExitPath() throws Exception {
        Path source = write(GoldenSqlCorpus.TIME_ZONE.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of(SCHEMA),
                true);

        try (Connection mysql = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
                statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".tz_events");
                statement.execute("CREATE TABLE " + SCHEMA + ".tz_events (id INT PRIMARY KEY, name VARCHAR(191) NOT NULL)");
            }
            deploy(mysql, generated, "mysql");

            String callerZone = "+05:30";
            try (Statement statement = mysql.createStatement()) {
                statement.execute("SET time_zone = '" + callerZone + "'");
            }

            assertEquals(42, callIntFunction(mysql, "doubled", 21), "doubled(21)");
            assertEquals(callerZone, sessionTimeZone(mysql),
                    "mysql session time_zone leaked after function RETURN path (E-8)");

            try (PreparedStatement statement = mysql.prepareStatement("CALL " + SCHEMA + ".touch(?)")) {
                statement.setInt(1, 1);
                statement.execute();
            }
            assertEquals(callerZone, sessionTimeZone(mysql),
                    "mysql session time_zone leaked after procedure fall-through path (E-8)");

            try {
                callIntFunction(mysql, "failing", -1);
                throw new AssertionError("Expected failing(-1) to raise");
            } catch (SQLException exception) {
                assertTrue(exception.getMessage().contains("TITAN_TZ_BOOM"),
                        "failing(-1) raised wrong message: " + exception.getMessage());
            }
            assertEquals(callerZone, sessionTimeZone(mysql),
                    "mysql session time_zone leaked after exception exit path (E-8)");
        }
    }

    /**
     * B-3 (TG-BLK-006): char/string literals containing control characters must deploy AND
     * execute correctly on both dialects. Char literals used to be emitted as raw bytes into
     * the SQL; {@code indentMultiline} then treated the embedded LF as a line break and
     * "indented" it — {@code '\n'} became a five-character literal. The corruption deploys
     * fine (it is still a valid literal), so deployment alone proves nothing: these routines
     * are executed against control-character inputs and compared with the in-JVM Java
     * reference. PostgreSQL now renders control characters as {@code E'\n'} escape strings,
     * MySQL as backslash escapes inside the literal.
     */
    @Test
    void controlCharacterLiteralsDeployAndExecuteResultEqualOnBothDatabases() throws Exception {
        Path source = write(GoldenSqlCorpus.CONTROL_CHAR_LITERALS.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        // Pin the emission shape before proving it executes: no raw control byte may appear
        // inside a literal on either dialect (the corrupted shape was a literal containing a
        // raw LF followed by indentation spaces).
        String postgresSql = sqlFor(generated, "postgresql");
        String mysqlSql = sqlFor(generated, "mysql");
        assertTrue(postgresSql.contains("E'\\n'"),
                "expected the PostgreSQL char literal '\\n' to render as the escape string E'\\n'");
        assertTrue(mysqlSql.contains("CHAR(10 USING utf8mb4)"),
                "expected the MySQL char literal '\\n' to render as CHAR(10 USING utf8mb4)");
        assertTrue(mysqlSql.contains("'\\r\\n'"),
                "expected the MySQL string literal \"\\r\\n\" to render with backslash escapes");
        for (String dialectSql : List.of(postgresSql, mysqlSql)) {
            boolean inLiteral = false;
            for (int i = 0; i < dialectSql.length(); i++) {
                char c = dialectSql.charAt(i);
                if (c == '\'') {
                    inLiteral = !inLiteral;
                    continue;
                }
                assertFalse(inLiteral && (c < 0x20 || c == 0x7F),
                        "raw control byte 0x%02X inside a string literal at offset %d".formatted((int) c, i));
            }
        }

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                // The TG-BLK-006 shape: a routine classifying '\n'/'\t'/'\r' inputs. The
                // corrupted literal ('\n' + indentation) makes every probe fall through to 0.
                for (String probe : List.of("\nrest", "\trest", "\rrest", "arest", " rest")) {
                    assertEquals(javaClassifyControlChar(probe),
                            callClassifyControlChar(connection, probe),
                            dialect + " classify_control_char(" + probe.replace("\n", "\\n")
                                    .replace("\t", "\\t").replace("\r", "\\r") + ")");
                }

                // String literal with an embedded LF in a concatenation chain.
                assertEquals(javaJoinWithNewline("alpha", "beta"),
                        callJoinWithNewline(connection, "alpha", "beta"),
                        dialect + " join_with_newline");

                // CRLF needle: must match the two-character sequence, not an indented variant.
                assertEquals(javaContainsCrLf("head\r\ntail"),
                        callContainsCrLf(connection, "head\r\ntail"),
                        dialect + " contains_cr_lf(CRLF input)");
                assertEquals(javaContainsCrLf("head\ntail"),
                        callContainsCrLf(connection, "head\ntail"),
                        dialect + " contains_cr_lf(bare LF input)");
                assertEquals(javaContainsCrLf("headtail"),
                        callContainsCrLf(connection, "headtail"),
                        dialect + " contains_cr_lf(no break input)");
            }
        }
    }

    /** In-JVM Java references for {@link #controlCharacterLiteralsDeployAndExecuteResultEqualOnBothDatabases()}. */
    private static int javaClassifyControlChar(String text) {
        char first = text.charAt(0);
        int kind = 0;
        if (first == '\n') {
            kind = 1;
        }
        if (first == '\t') {
            kind = 2;
        }
        if (first == '\r') {
            kind = 3;
        }
        return kind;
    }

    private static String javaJoinWithNewline(String left, String right) {
        return left + "\n" + right;
    }

    private static boolean javaContainsCrLf(String text) {
        return text.contains("\r\n");
    }

    private static int callClassifyControlChar(Connection connection, String text) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".classify_control_char(?)")) {
            statement.setString(1, text);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "classify_control_char returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static String callJoinWithNewline(Connection connection, String left, String right) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".join_with_newline(?, ?)")) {
            statement.setString(1, left);
            statement.setString(2, right);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "join_with_newline returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static boolean callContainsCrLf(Connection connection, String text) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".contains_cr_lf(?)")) {
            statement.setString(1, text);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "contains_cr_lf returned no rows");
                return resultSet.getBoolean(1);
            }
        }
    }

    /**
     * B-10 (TG-BLK-012): a boolean value reaching string/JSON/text output must render
     * {@code true}/{@code false} on both dialects, equal to the in-JVM Java reference. The
     * regression: MySQL's {@code BOOLEAN} is {@code TINYINT}, so a bare {@code CAST(<boolean> AS
     * CHAR)} rendered {@code '1'}/{@code '0'} where Java and PostgreSQL render {@code 'true'}/
     * {@code 'false'} — six of the consumer's 97 MySQL-equivalence cases diverged at exactly the
     * boolean positions of their JSON output. Deployment alone proves nothing (the wrong text
     * still deploys); these routines are executed against both boolean inputs and compared with
     * the Java reference. The emission shape is pinned first: MySQL coerces booleans with
     * {@code IF(..., 'true', 'false')}; PostgreSQL keeps its native {@code CAST(... AS TEXT)}.
     */
    @Test
    void booleanToTextRendersTrueFalseEqualOnBothDatabases() throws Exception {
        Path source = write(GoldenSqlCorpus.BOOLEAN_TEXT.source());

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        String postgresSql = sqlFor(generated, "postgresql");
        String mysqlSql = sqlFor(generated, "mysql");
        // MySQL must convert booleans to 'true'/'false' rather than the TINYINT '1'/'0'.
        assertTrue(mysqlSql.contains("CASE WHEN p_flag IS NULL THEN NULL WHEN p_flag THEN 'true' ELSE 'false' END"),
                "expected the MySQL boolean-to-text coercion to render the 'true'/'false' CASE for p_flag");
        assertTrue(mysqlSql.contains("WHEN (p_left <= p_right) THEN 'true' ELSE 'false' END"),
                "expected a MySQL boolean predicate reaching text to render via the 'true'/'false' CASE");
        assertFalse(mysqlSql.contains("CAST(p_flag AS CHAR)"),
                "MySQL must not CAST a boolean directly to CHAR (yields '1'/'0')");
        // The consumer's actual path: a boolean-returning routine CALL concatenated into JSON must
        // also coerce via CASE, never CAST(<call> AS CHAR). The call is emitted bare (no schema).
        assertTrue(mysqlSql.contains("CASE WHEN has_more(p_count, p_limit) IS NULL THEN NULL "
                        + "WHEN has_more(p_count, p_limit) THEN 'true' ELSE 'false' END"),
                "expected a MySQL boolean-returning routine call reaching text to render via the 'true'/'false' CASE");
        assertFalse(mysqlSql.contains("CAST(has_more(p_count, p_limit) AS CHAR)"),
                "MySQL must not CAST a boolean-returning routine call directly to CHAR (yields '1'/'0')");
        // PostgreSQL's boolean::text is already 'true'/'false'; its rendering is unchanged.
        assertTrue(postgresSql.contains("CAST(p_flag AS TEXT)"),
                "expected PostgreSQL to keep its native CAST(p_flag AS TEXT) boolean rendering");
        assertFalse(postgresSql.contains("'true' ELSE 'false'"),
                "PostgreSQL must not emit the MySQL 'true'/'false' boolean coercion");

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            }
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";

                for (boolean flag : List.of(true, false)) {
                    // boolean in String + concatenation
                    assertEquals(javaLabelFlag("active", flag),
                            callStringBool(connection, "label_flag", "active", flag),
                            dialect + " label_flag(" + flag + ")");
                    // String.valueOf(boolean)
                    assertEquals(javaRenderBoolean(flag),
                            callRenderBoolean(connection, flag),
                            dialect + " render_boolean(" + flag + ")");
                }

                // A boolean predicate (comparison) reaching text.
                assertEquals(javaDescribeOrder(1, 2),
                        callDescribeOrder(connection, 1, 2),
                        dialect + " describe_order(1, 2)");
                assertEquals(javaDescribeOrder(2, 1),
                        callDescribeOrder(connection, 2, 1),
                        dialect + " describe_order(2, 1)");

                // The TG-BLK-012 JSON shape: booleans rendered into a JSON object string.
                for (boolean hasNext : List.of(true, false)) {
                    for (boolean hasPrev : List.of(true, false)) {
                        assertEquals(javaPageInfoJson(hasNext, hasPrev),
                                callPageInfoJson(connection, hasNext, hasPrev),
                                dialect + " page_info_json(" + hasNext + ", " + hasPrev + ")");
                    }
                }

                // The consumer's actual path: a boolean-returning routine call embedded in JSON.
                assertEquals(javaPageInfoFromRoutine(5, 3),
                        callPageInfoFromRoutine(connection, 5, 3),
                        dialect + " page_info_from_routine(5, 3) [hasMore=true]");
                assertEquals(javaPageInfoFromRoutine(2, 3),
                        callPageInfoFromRoutine(connection, 2, 3),
                        dialect + " page_info_from_routine(2, 3) [hasMore=false]");
            }
        }
    }

    /** In-JVM Java references for {@link #booleanToTextRendersTrueFalseEqualOnBothDatabases()}. */
    private static String javaLabelFlag(String name, boolean flag) {
        return name + "=" + flag;
    }

    private static String javaRenderBoolean(boolean flag) {
        return String.valueOf(flag);
    }

    private static String javaDescribeOrder(int left, int right) {
        return "ascending=" + (left <= right);
    }

    private static String javaPageInfoJson(boolean hasNextPage, boolean hasPreviousPage) {
        return "{\"hasNextPage\":" + hasNextPage
                + ",\"hasPreviousPage\":" + hasPreviousPage + "}";
    }

    private static String javaPageInfoFromRoutine(int count, int limit) {
        return "{\"hasNextPage\":" + (count > limit) + "}";
    }

    private static String callStringBool(Connection connection, String routine, String text, boolean flag)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + "." + routine + "(?, ?)")) {
            statement.setString(1, text);
            statement.setBoolean(2, flag);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), routine + " returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String callRenderBoolean(Connection connection, boolean flag) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".render_boolean(?)")) {
            statement.setBoolean(1, flag);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "render_boolean returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String callDescribeOrder(Connection connection, int left, int right) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".describe_order(?, ?)")) {
            statement.setInt(1, left);
            statement.setInt(2, right);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "describe_order returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String callPageInfoJson(Connection connection, boolean hasNext, boolean hasPrev)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".page_info_json(?, ?)")) {
            statement.setBoolean(1, hasNext);
            statement.setBoolean(2, hasPrev);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "page_info_json returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String callPageInfoFromRoutine(Connection connection, int count, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SCHEMA + ".page_info_from_routine(?, ?)")) {
            statement.setInt(1, count);
            statement.setInt(2, limit);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "page_info_from_routine returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static String sessionTimeZone(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT @@session.time_zone")) {
            assertTrue(resultSet.next(), "no session time_zone row");
            return resultSet.getString(1);
        }
    }

    private static String javaGuardedLabel(String input, boolean failFast) {
        String label = "start";
        try {
            if (failFast) {
                throw new IllegalStateException("TITAN_GATE_FAIL_FAST");
            }
            label += ":" + input;
        } catch (IllegalStateException error) {
            label = "caught";
        } finally {
            label += ":done";
        }
        return label;
    }

    private static int javaRejectNegative(int value) {
        if (value < 0) {
            throw new RuntimeException("TITAN_GATE_NEGATIVE");
        }
        return value * 2;
    }

    private static int javaStringProbe(String text, String needle) {
        char first = text.charAt(0);
        int score = first - 'a';
        int at = text.indexOf(needle, 1);
        if (text.startsWith(needle, 2)) {
            score = score + 10;
        }
        if (text.equals(needle)) {
            score = score + 100;
        }
        return score + at;
    }

    private static int javaSwitchScore(int input) {
        return switch (input) {
            case 1 -> 10;
            case 2 -> 20;
            default -> -1;
        };
    }

    private static int javaNestedLoopScore(int rows, int cols) {
        int score = 0;
        int r = 0;
        while (r < rows) {
            int c = 0;
            while (c < cols) {
                c = c + 1;
                if (c == 3) {
                    continue;
                }
                if (c == 5) {
                    break;
                }
                score = score + c;
            }
            r = r + 1;
            if (score > 100) {
                break;
            }
        }
        return score;
    }

    private static int javaCountingSum(int from, int upTo) {
        int sum = 0;
        for (int i = from; i <= upTo; i++) {
            if (i == from + 2) {
                continue;
            }
            sum = sum + i;
        }
        for (int j = 0; j < 4; j++) {
            sum = sum + 100;
        }
        return sum;
    }

    private static int javaDoWhileCountdown(int start) {
        int total = 0;
        int n = start;
        do {
            n = n - 1;
            if (n == 2) {
                continue;
            }
            total = total + n;
        } while (n > 0);
        return total;
    }

    private static long javaCastChain(int small, double ratio) {
        long widened = (long) small;
        double scaled = (double) widened * ratio;
        long truncated = (long) scaled;
        int identity = (int) small;
        return widened + truncated + identity;
    }

    private static String javaExceptionDispatch(int code) {
        String result = "ran";
        try {
            if (code == 1) {
                throw new QuotaBreachException("TITAN_QUOTA:" + code);
            }
            if (code == 2) {
                throw new StaleSnapshotException("TITAN_STALE");
            }
        } catch (StaleSnapshotException stale) {
            result = "stale-caught";
        }
        return result;
    }

    private static String javaExceptionSupertype(int code) {
        String result = "ran";
        try {
            if (code == 1) {
                throw new QuotaBreachException("TITAN_QUOTA_SUPER");
            }
            if (code == 2) {
                throw new StaleSnapshotException("TITAN_STALE_SUPER");
            }
        } catch (RuntimeException any) {
            result = "caught";
        }
        return result;
    }

    private static final class QuotaBreachException extends RuntimeException {
        QuotaBreachException(String message) {
            super(message);
        }
    }

    private static final class StaleSnapshotException extends RuntimeException {
        StaleSnapshotException(String message) {
            super(message);
        }
    }

    private static int javaIntRatio(int dividend, int divisor) {
        return dividend / divisor;
    }

    private static int javaIntRemainder(int dividend, int divisor) {
        return dividend % divisor;
    }

    /**
     * Phase 2.4 (E-7): Java throws ArithmeticException for {@code x / 0} and {@code x % 0}; both
     * databases must raise SQLSTATE 22012 (division_by_zero) server-side — PostgreSQL natively,
     * MySQL via the runtime helper's SIGNAL — never MySQL's default silent NULL. MySQL
     * Connector/J converts any class-22 condition into {@link java.sql.DataTruncation}, whose
     * {@code getSQLState()} is hardcoded to 22001 by the JDBC spec, so the assertion accepts the
     * data-exception class (22xxx) plus the division-by-zero message.
     */
    private static void assertDivisionByZeroRaises(Connection connection, String dialect, String routineName) {
        try {
            callIntFunction2(connection, routineName, 7, 0);
        } catch (SQLException exception) {
            assertTrue(exception.getSQLState() != null && exception.getSQLState().startsWith("22"),
                    dialect + " " + routineName + "(7, 0) raised wrong SQLSTATE: " + exception.getSQLState()
                            + " (" + exception.getMessage() + ")");
            assertTrue(exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("division by zero"),
                    dialect + " " + routineName + "(7, 0) raised wrong message: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected " + dialect + " " + routineName
                + "(7, 0) to raise a SQLSTATE 22xxx division-by-zero error like Java's ArithmeticException");
    }

    private static int javaCompareScore(java.math.BigDecimal current, java.math.BigDecimal threshold) {
        int cmp = current.compareTo(threshold);
        if (cmp > 0) {
            return 1;
        }
        if (cmp < 0) {
            return -1;
        }
        return 0;
    }

    private static String callGuardedLabel(Connection connection, String input, boolean failFast) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".guarded_label(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input);
            statement.setBoolean(2, failFast);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "guarded_label returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    private static int callIntFunction(Connection connection, String routineName, int argument) throws SQLException {
        String sql = "SELECT " + SCHEMA + "." + routineName + "(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, argument);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), routineName + " returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static int callIntFunction2(Connection connection, String routineName, int first, int second) throws SQLException {
        String sql = "SELECT " + SCHEMA + "." + routineName + "(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, first);
            statement.setInt(2, second);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), routineName + " returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static int callStringProbe(Connection connection, String text, String needle) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".string_probe(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, text);
            statement.setString(2, needle);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "string_probe returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static int callTierRank(Connection connection, String tier) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".tier_rank(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tier);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "tier_rank returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    private static void callRecordEvent(Connection connection, int id, String name) throws SQLException {
        String sql = "CALL " + SCHEMA + ".record_event(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.execute();
        }
    }

    private static List<String> readGateEventNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT name FROM " + SCHEMA + ".gate_events ORDER BY id")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    /** G1: drives the discarded-FOR-UPDATE routine (record_event-shaped CALL signature). */
    private static void callLockAndTouchEvent(Connection connection, int id, String name) throws SQLException {
        String sql = "CALL " + SCHEMA + ".lock_and_touch_event(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.execute();
        }
    }

    /** G4: inserts a String into the JSON/JSONB document column via the transpiled routine. */
    private static void callStoreEventDocument(Connection connection, int id, String name, String document)
            throws SQLException {
        String sql = "CALL " + SCHEMA + ".store_event_document(?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setString(3, document);
            statement.execute();
        }
    }

    /**
     * G4: reads a top-level field out of the stored JSON/JSONB document so the round-trip is
     * compared structurally (a native JSON path extraction proves the value was stored AS json,
     * not as an opaque text blob). PostgreSQL's {@code ->>} and MySQL's
     * {@code JSON_UNQUOTE(JSON_EXTRACT(...))} both return the field's text form.
     */
    private static String readGateEventDocumentField(Connection connection, int id, String field) throws SQLException {
        boolean postgres = connection.getMetaData().getURL().startsWith("jdbc:postgresql");
        String extract = postgres
                ? "document ->> '" + field + "'"
                : "JSON_UNQUOTE(JSON_EXTRACT(document, '$." + field + "'))";
        String sql = "SELECT " + extract + " FROM " + SCHEMA + ".gate_events WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /** G3: drives the plain-UPDATE expression-valued counter bump (version = version + 1). */
    private static void callBumpEventVersion(Connection connection, int id) throws SQLException {
        String sql = "CALL " + SCHEMA + ".bump_event_version(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.execute();
        }
    }

    /** G3: drives the upsert whose ON CONFLICT / ON DUPLICATE KEY SET bumps version server-side. */
    private static void callUpsertEventVersion(Connection connection, int id, String name) throws SQLException {
        String sql = "CALL " + SCHEMA + ".upsert_event_version(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.execute();
        }
    }

    /** G3: reads the server-computed version counter to prove the bump happened in the database. */
    private static int readGateEventVersion(Connection connection, int id) throws SQLException {
        String sql = "SELECT version FROM " + SCHEMA + ".gate_events WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "gate_events row " + id + " not found");
                return resultSet.getInt(1);
            }
        }
    }

    /** G2: drives the read-decide-branch idempotent-replay routine. */
    private static void callIdempotentImport(Connection connection, int id, String name, String key) throws SQLException {
        String sql = "CALL " + SCHEMA + ".idempotent_import(?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setString(3, key);
            statement.execute();
        }
    }

    /** G2: drives the scalar precondition (apply only if newVersion is strictly newer) routine. */
    private static void callApplyIfNewer(Connection connection, int id, int newVersion, String name) throws SQLException {
        String sql = "CALL " + SCHEMA + ".apply_if_newer(?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.setInt(2, newVersion);
            statement.setString(3, name);
            statement.execute();
        }
    }

    /** G2: reads the draft name to prove (or disprove) that a mutation happened. */
    private static String readGateEventName(Connection connection, int id) throws SQLException {
        String sql = "SELECT name FROM " + SCHEMA + ".gate_events WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "gate_events row " + id + " not found");
                return resultSet.getString(1);
            }
        }
    }

    /**
     * G2: counts the audit rows for a key. Since the audit INSERT has no conflict clause and the id
     * is database-generated, a count > 1 means a replay appended a duplicate — i.e. the routine
     * did NOT short-circuit server-side. The single-row assertion is the proof of read-decide-branch.
     */
    private static int countAuditRows(Connection connection, String key) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + SCHEMA + ".gate_audit WHERE idempotency_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "audit count returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    /** G2: whether the idempotency key was recorded by the first apply. */
    private static boolean idempotencyKeyPresent(Connection connection, String key) throws SQLException {
        String sql = "SELECT 1 FROM " + SCHEMA + ".gate_idempotency WHERE idempotency_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void assertRejectNegativeRaises(Connection connection, String dialect) {
        try {
            callIntFunction(connection, "reject_negative", -1);
        } catch (SQLException exception) {
            assertTrue(exception.getMessage().contains("TITAN_GATE_NEGATIVE"),
                    dialect + " raised wrong message: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected " + dialect + " reject_negative(-1) to raise");
    }

    private static long callCastChain(Connection connection, int small, double ratio) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".cast_chain(?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, small);
            // The double parameter maps to NUMERIC(38,10); PostgreSQL resolves function
            // overloads without an implicit double-precision->numeric conversion, so bind as
            // BigDecimal (numeric) directly.
            statement.setBigDecimal(2, java.math.BigDecimal.valueOf(ratio));
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "cast_chain returned no rows");
                return resultSet.getLong(1);
            }
        }
    }

    private static String callStringFunction(Connection connection, String routineName, int argument) throws SQLException {
        String sql = "SELECT " + SCHEMA + "." + routineName + "(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, argument);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), routineName + " returned no rows");
                return resultSet.getString(1);
            }
        }
    }

    /**
     * Phase 2.2 (F-11 + F-10): {@code exception_dispatch(1)} throws QuotaBreachException, whose
     * SQLSTATE is distinct from the caught StaleSnapshotException — the catch must not
     * intercept it, and the dynamic concatenated message must reach the client error text
     * exactly as Java produced it.
     */
    private static void assertQuotaBreachPropagates(Connection connection, String dialect) {
        String javaMessage;
        try {
            javaExceptionDispatch(1);
            throw new AssertionError("Java reference must throw for code 1");
        } catch (QuotaBreachException expected) {
            javaMessage = expected.getMessage();
        }
        try {
            callStringFunction(connection, "exception_dispatch", 1);
        } catch (SQLException exception) {
            assertTrue(exception.getMessage().contains(javaMessage),
                    dialect + " propagated wrong message: " + exception.getMessage()
                            + " (expected to contain '" + javaMessage + "')");
            return;
        }
        throw new AssertionError("Expected " + dialect + " exception_dispatch(1) to raise; "
                + "the StaleSnapshotException catch must not intercept QuotaBreachException");
    }

    private static void deployPostgres(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            // Routine bodies reference fixture tables unqualified; resolve them at execution time.
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            // G4 (spike B4): the document column is strict jsonb on PostgreSQL — it rejects a bound
            // text parameter unless the value is cast, which is exactly what storeEventDocument must
            // prove. G1 (spike B1): the same table backs lockAndTouchEvent's discarded FOR UPDATE.
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_events ("
                    + "id INT PRIMARY KEY, name TEXT NOT NULL, document JSONB NULL, "
                    + "version INT NOT NULL DEFAULT 0)");
            // G2 (spike B2): the idempotency/audit tables backing the read-decide-branch routines.
            // The audit id is database-generated and the routine's audit INSERT carries NO conflict
            // clause, so a second audit row can only be prevented by the routine short-circuiting.
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_idempotency ("
                    + "idempotency_key TEXT PRIMARY KEY)");
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_audit ("
                    + "id SERIAL PRIMARY KEY, idempotency_key TEXT NOT NULL, name TEXT NOT NULL)");
            createScoreProbes(statement);
        }
        // Phase 2.4: generated routines reference the titan_runtime helpers (strict-wraparound
        // java_int_add/sub/mul); deploy the runtime migration exactly like production installs.
        executeRuntimeMigration(connection, new PostgreSqlDialectProvider().runtimeStrategy().runtimeMigrationSql());
        deploy(connection, generated, "postgresql");
    }

    private static void deployMySql(
            Connection connection,
            List<TranspilationPipeline.GeneratedSql> generated
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".gate_events");
            // G4: the document column is JSON on MySQL (it parses a string implicitly, but the
            // explicit cast must still round-trip). G1: also backs lockAndTouchEvent.
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_events ("
                    + "id INT PRIMARY KEY, name VARCHAR(191) NOT NULL, document JSON NULL, "
                    + "version INT NOT NULL DEFAULT 0)");
            // G2 (spike B2): idempotency/audit tables backing the read-decide-branch routines.
            // The MySQL audit id is AUTO_INCREMENT and the audit INSERT carries no ON DUPLICATE KEY
            // clause, so only the routine's server-side short-circuit prevents a duplicate audit row.
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".gate_idempotency");
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_idempotency ("
                    + "idempotency_key VARCHAR(191) PRIMARY KEY)");
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".gate_audit");
            statement.execute("CREATE TABLE " + SCHEMA + ".gate_audit ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, idempotency_key VARCHAR(191) NOT NULL, name VARCHAR(191) NOT NULL)");
            statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".score_probes");
            createScoreProbes(statement);
        }
        // Phase 2.4: generated routines reference the titan_rt_* helpers (java_int_div/java_mod
        // for integer division/modulo parity, java_int_add/sub/mul in strict-wraparound mode).
        // The container's test user cannot create the titan_runtime database; rehome the
        // telemetry table into the current database exactly like TitanTestExtension does. The
        // helper functions are unqualified and land in the current database, which is where
        // generated routines resolve their unqualified references.
        executeRuntimeMigration(connection, new MySqlDialectProvider().runtimeStrategy().runtimeMigrationSql()
                .replace("titan_runtime.telemetry", "telemetry"));
        deploy(connection, generated, "mysql");
    }

    private static void executeRuntimeMigration(Connection connection, String runtimeSql) throws SQLException {
        for (String sql : SqlScripts.split(runtimeSql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException exception) {
                throw new SQLException("Failed to deploy runtime migration statement:\n" + sql, exception);
            }
        }
    }

    /** Nullable-score rows backing the Phase 2.3 compareTo NPE-parity assertions (N2). */
    private static void createScoreProbes(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE " + SCHEMA + ".score_probes ("
                + "id INT PRIMARY KEY, score DECIMAL(38,10) NULL)");
        statement.execute("INSERT INTO " + SCHEMA + ".score_probes (id, score) VALUES "
                + "(1, NULL), (2, 9), (3, 2), (4, 5)");
    }

    private static int callCompareScoreFromColumn(Connection connection, int rowId, int threshold) throws SQLException {
        String sql = "SELECT " + SCHEMA + ".compare_score(score, ?) FROM " + SCHEMA + ".score_probes WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, java.math.BigDecimal.valueOf(threshold));
            statement.setInt(2, rowId);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "compare_score returned no rows");
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Phase 2.3 (N2): Java throws NullPointerException when compareTo's receiver is bound from a
     * NULL column value; both databases must raise the NullAnalysisPass guard's
     * NullPointerException-parity error through the catch path instead of returning a silent 0.
     */
    private static void assertCompareScoreOnNullColumnRaises(Connection connection, String dialect) {
        try {
            javaCompareScore(null, java.math.BigDecimal.valueOf(5));
            throw new AssertionError("Java reference must throw NullPointerException for a null receiver");
        } catch (NullPointerException expected) {
            // Java parity baseline established.
        }
        try {
            callCompareScoreFromColumn(connection, 1, 5);
        } catch (SQLException exception) {
            assertTrue(exception.getMessage().contains("NullPointerException"),
                    dialect + " raised wrong message: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected " + dialect
                + " compare_score on a NULL column value to raise NullPointerException parity error");
    }

    private static String sqlFor(List<TranspilationPipeline.GeneratedSql> generated, String target) {
        return generated.stream()
                .filter(sql -> sql.target().equals(target))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    /**
     * G5 (spike B5): a routine referencing a {@code static final String} compile-time constant
     * inlines the literal at emission — the emitted SQL must contain the literal and never the
     * {@code static_get} runtime-state helper, so the routine deploys and executes on BOTH dialects
     * WITHOUT the {@code titan_runtime} static-state migration being applied. The test deliberately
     * does NOT deploy any runtime migration; the only schema object is the target table. If the
     * constant still lowered to {@code static_get}, the CALL would fail at execute
     * ({@code function titan_runtime.static_get / titan_rt_static_get does not exist}) — proving the
     * spurious deploy dependency is gone.
     */
    @Test
    void staticFinalStringConstantInlinesAsLiteralAndExecutesWithoutRuntimeHelpers() throws Exception {
        Path source = write("""
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class ConstantFoldDeployability {
                    static final AuditTable AUDIT = new AuditTable();
                    static final String COMMAND_NAME = "management.importModelDocument";

                    @StoredProcedure
                    public static void recordImport(int id) {
                        insertInto(AUDIT)
                                .set(AUDIT.ID, id)
                                .set(AUDIT.COMMAND_NAME, COMMAND_NAME)
                                .execute();
                    }

                    static final class AuditTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> COMMAND_NAME = column("command_name", SQLType.TEXT, Nullability.NOT_NULL);

                        AuditTable() {
                            super("constant_fold_audit", "public");
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of(SCHEMA),
                true);

        // The emitted SQL inlines the constant as a literal on both dialects and references no
        // static_get / static_set runtime helper — no titan_runtime dependency is pulled in.
        for (String target : List.of("postgresql", "mysql")) {
            String sql = sqlFor(generated, target);
            assertTrue(sql.contains("'management.importModelDocument'"),
                    target + ": expected the static final String constant inlined as a literal:\n" + sql);
            assertFalse(sql.contains("static_get"),
                    target + ": constant reference must not emit a static_get runtime helper:\n" + sql);
            assertFalse(sql.contains("static_set"),
                    target + ": constant reference must not emit a static_set runtime helper:\n" + sql);
        }

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement statement = postgres.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET search_path TO " + SCHEMA + ", public");
                statement.execute("CREATE TABLE " + SCHEMA + ".constant_fold_audit ("
                        + "id INT PRIMARY KEY, command_name TEXT NOT NULL)");
            }
            try (Statement statement = mysql.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
                statement.execute("DROP TABLE IF EXISTS " + SCHEMA + ".constant_fold_audit");
                statement.execute("CREATE TABLE " + SCHEMA + ".constant_fold_audit ("
                        + "id INT PRIMARY KEY, command_name VARCHAR(191) NOT NULL)");
            }
            // Deliberately NO runtime migration is deployed — only the generated routine.
            deploy(postgres, generated, "postgresql");
            deploy(mysql, generated, "mysql");

            for (Connection connection : List.of(postgres, mysql)) {
                String dialect = connection == postgres ? "postgresql" : "mysql";
                try (PreparedStatement statement =
                             connection.prepareStatement("CALL " + SCHEMA + ".record_import(?)")) {
                    statement.setInt(1, 1);
                    statement.execute();
                }
                String stored;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT command_name FROM " + SCHEMA + ".constant_fold_audit WHERE id = ?")) {
                    statement.setInt(1, 1);
                    try (var resultSet = statement.executeQuery()) {
                        assertTrue(resultSet.next(), dialect + ": expected the inserted audit row");
                        stored = resultSet.getString(1);
                    }
                }
                assertEquals("management.importModelDocument", stored,
                        dialect + ": the folded constant literal must be persisted by the routine");
            }
        }
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

    private Path write(String source) throws Exception {
        Path sourceFile = tempDir.resolve("EmitterDeployabilityFixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);
        return sourceFile;
    }
}
