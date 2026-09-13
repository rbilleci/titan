package io.titan.transpiler.tir;

import java.util.List;

/**
 * The curated fixture corpus shared by {@link EmitterDeployabilityIT} (which proves every
 * artifact deploys and executes on live PostgreSQL and MySQL) and {@link GoldenSqlTest} (which
 * pins the exact emitted bytes against checked-in golden {@code .sql} files, plan 5.2).
 *
 * <p>Keeping one corpus for both layers is the point: the deployability IT proves the goldens
 * describe SQL that actually CREATEs and runs, and the golden test makes every emission change
 * to those same fixtures show up as a reviewable diff instead of slipping past substring
 * assertions (the E-1 failure mode). The sources cover the breadth of Phase 0-3 emission
 * shapes — try/catch/finally with the rethrow tail, string intrinsics, int and enum switches,
 * nested-loop break/continue, for-range and do-while lowering, cast chains, custom exceptions
 * with distinct SQLSTATEs, compareTo null guards, integer division/modulo emulation,
 * strict-wraparound arithmetic, DSL inserts with conflict clauses, set-value expression
 * remapping, the Phase 3.3 DSL surface (aliases/DISTINCT/CASE/NOT IN/NULLS ordering), and the
 * MySQL time-zone save/restore scaffolding.</p>
 */
public final class GoldenSqlCorpus {

    /** One transpilable fixture: id (golden directory name), Java source, pipeline mode. */
    public record Fixture(String id, String source, boolean strictWraparound) {
    }

    private GoldenSqlCorpus() {
    }

    /** Every fixture, in stable order; golden directories are keyed by {@link Fixture#id()}. */
    public static List<Fixture> fixtures() {
        return List.of(
                EMITTER_DEPLOYABILITY,
                STRICT_WRAPAROUND,
                TRY_ABORT,
                SET_VALUE_EXPRESSION,
                PHASE33_SURFACE,
                TIME_ZONE,
                CONTROL_CHAR_LITERALS,
                BOOLEAN_TEXT);
    }

    /** The schema the corpus transpiles into — same as the deployability IT deploys into. */
    public static final String SCHEMA = "test";

    public static final Fixture EMITTER_DEPLOYABILITY = new Fixture("emitter-deployability", """
            import titan.dsl.*;
            import static titan.dsl.DSL.*;

            class EmitterDeployabilityFixture {
                enum Tier { BASIC, PRO }

                static final GateEventsTable GATE_EVENTS = new GateEventsTable();
                static final GateIdempotencyTable GATE_IDEMPOTENCY = new GateIdempotencyTable();
                static final GateAuditTable GATE_AUDIT = new GateAuditTable();

                // try/catch with finally; the unhandled-rethrow tail is always emitted, so
                // deploying this validates the E-1 SIGNAL-via-variable rewrite on real MySQL.
                @StoredFunction
                public static String guardedLabel(String input, boolean failFast) {
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

                // throw of a RuntimeException (RaiseStatement shape).
                @StoredFunction
                public static int rejectNegative(int value) {
                    if (value < 0) {
                        throw new RuntimeException("TITAN_GATE_NEGATIVE");
                    }
                    return value * 2;
                }

                // String-heavy method: charAt in arithmetic (__titan_char_code/ASCII),
                // indexOf with fromIndex, startsWith with offset, null-aware equals.
                @StoredFunction
                public static int stringProbe(String text, String needle) {
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

                // switch statement (deterministic temp naming shape, F-5).
                @StoredFunction
                public static int switchScore(int input) {
                    int score = 0;
                    switch (input) {
                        case 1 -> {
                            score = 10;
                        }
                        case 2 -> {
                            score = 20;
                        }
                        default -> {
                            score = -1;
                        }
                    }
                    return score;
                }

                // enum switch with bare case labels (F-2 shape).
                @StoredFunction
                public static int tierRank(Tier tier) {
                    int rank = -1;
                    switch (tier) {
                        case BASIC -> {
                            rank = 1;
                        }
                        case PRO -> {
                            rank = 2;
                        }
                        default -> {
                            rank = 0;
                        }
                    }
                    return rank;
                }

                // Phase 2.1: unlabeled break+continue in a nested loop. MySQL requires loop
                // labels for LEAVE/ITERATE, so this proves the generated labels exist and
                // that break/continue bind to the innermost loop only.
                @StoredFunction
                public static int nestedLoopScore(int rows, int cols) {
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

                // Phase 2.1: counting for-loops lower to ForRangeStatement (native FOR on
                // PostgreSQL, increment-first labeled LOOP on MySQL), including a continue
                // inside the range body and a zero-iteration case when from > upTo.
                @StoredFunction
                public static int countingSum(int from, int upTo) {
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

                // Phase 2.1 (audit D12): do-while now binds its condition to one temp per
                // iteration at the top of the loop body, so a continue re-tests the
                // condition exactly like Java instead of looping forever.
                @StoredFunction
                public static int doWhileCountdown(int start) {
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

                // Phase 2.2 (F-9): supported cast chain — int->long widening (CAST AS
                // BIGINT/SIGNED), long->double widening (CAST AS NUMERIC/DECIMAL), the
                // identity (int) no-op, and double->long narrowing with the
                // truncate-toward-zero composition (CAST(TRUNC(x)) / CAST(TRUNCATE(x, 0))),
                // including a negative value where rounding would diverge from Java.
                @StoredFunction
                public static long castChain(int small, double ratio) {
                    long widened = (long) small;
                    double scaled = (double) widened * ratio;
                    long truncated = (long) scaled;
                    int identity = (int) small;
                    return widened + truncated + identity;
                }

                // Phase 2.2 (F-11): two custom exceptions with distinct SQLSTATEs. The
                // catch of StaleSnapshotException must not intercept QuotaBreachException
                // (which propagates to the client), and (F-10) the dynamic concatenated
                // throw message must survive to the database error text.
                @StoredFunction
                public static String exceptionDispatch(int code) {
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

                // Phase 2.2 (F-11): a catch of the common supertype must still catch both
                // user exceptions (catch-superclass semantics over allocated SQLSTATEs).
                @StoredFunction
                public static String exceptionSupertype(int code) {
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

                static final class QuotaBreachException extends RuntimeException {
                    QuotaBreachException(String message) {
                        super(message);
                    }
                }

                static final class StaleSnapshotException extends RuntimeException {
                    StaleSnapshotException(String message) {
                        super(message);
                    }
                }

                // Phase 2.3 (N2): Java NPEs when a compareTo operand is null; the old
                // lowering silently compared NULL as "equal". The NullAnalysisPass guard
                // must raise on both databases when the argument bound from a NULL column
                // value reaches the comparison.
                @StoredFunction
                public static int compareScore(java.math.BigDecimal current, java.math.BigDecimal threshold) {
                    int cmp = current.compareTo(threshold);
                    if (cmp > 0) {
                        return 1;
                    }
                    if (cmp < 0) {
                        return -1;
                    }
                    return 0;
                }

                // Phase 2.4 (E-7): int/int division must truncate toward zero like Java on
                // both dialects (MySQL natively yields DECIMAL 3.5 for 7/2) and a zero
                // divisor must raise SQLSTATE 22012 (MySQL natively yields silent NULL).
                @StoredFunction
                public static int intRatio(int dividend, int divisor) {
                    return dividend / divisor;
                }

                // Phase 2.4 (E-7): int%int must take the dividend's sign like Java and raise
                // SQLSTATE 22012 on a zero divisor (MySQL natively yields silent NULL).
                @StoredFunction
                public static int intRemainder(int dividend, int divisor) {
                    return dividend % divisor;
                }

                // DSL insert with a conflict clause and no doUpdate().set(...) assignments:
                // lowers to ON CONFLICT (id) DO NOTHING on PostgreSQL and the new no-op
                // ON DUPLICATE KEY UPDATE id = id on MySQL (plan 0.11 / E-3).
                @StoredProcedure
                public static void recordEvent(int id, String name) {
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .execute();
                }

                // G1 (spike B1): a discarded SELECT ... FOR UPDATE issued as a routine statement
                // (the result of fetch() is thrown away). On PostgreSQL this must lower to
                // PERFORM — a bare SELECT raises "query has no destination for result data" at
                // execute (it CREATEs fine, so only running it on a CALL proves the fix). MySQL
                // accepts the bare SELECT statement (documented dialect asymmetry). The row lock
                // is real; here the row may or may not exist, so the routine just acquires it and
                // then upserts the same row, exercising both the discarded lock read and the write.
                @StoredProcedure
                public static void lockAndTouchEvent(int id, String name) {
                    select(GATE_EVENTS.ID)
                            .from(GATE_EVENTS)
                            .where(GATE_EVENTS.ID.eq(id))
                            .forUpdate()
                            .fetch();
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .set(GATE_EVENTS.NAME, name)
                            .execute();
                }

                // G4 (spike B4): a String value inserted into a SQLType.JSON column. On PostgreSQL
                // the column is jsonb and rejects a bound text parameter unless cast
                // (CAST(... AS JSONB)); on MySQL the column is JSON and is cast to JSON. The cast
                // is applied to both the insert VALUE and the ON CONFLICT / ON DUPLICATE KEY SET
                // value so the upsert round-trips a JSON document on both dialects.
                @StoredProcedure
                public static void storeEventDocument(int id, String name, String document) {
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .set(GATE_EVENTS.DOCUMENT, document)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .set(GATE_EVENTS.DOCUMENT, document)
                            .execute();
                }

                // G3 (spike B3): expression-valued set(column, columnExpression) — an atomic
                // server-side counter bump. The new value is computed in the database
                // (version = version + 1); the caller supplies no value, so repeated calls
                // increment monotonically without a read-modify-write round-trip.
                @StoredProcedure
                public static void bumpEventVersion(int id) {
                    update(GATE_EVENTS)
                            .set(GATE_EVENTS.VERSION, GATE_EVENTS.VERSION.add(1))
                            .where(GATE_EVENTS.ID.eq(id))
                            .execute();
                }

                // G3 (spike B3): the same expression-valued SET in an upsert — ON CONFLICT DO
                // UPDATE SET version = version + 1 (PG) / ON DUPLICATE KEY UPDATE (MySQL). First
                // call inserts at version 1; each replay bumps the existing row's version
                // server-side.
                @StoredProcedure
                public static void upsertEventVersion(int id, String name) {
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .set(GATE_EVENTS.VERSION, 1)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .set(GATE_EVENTS.VERSION, GATE_EVENTS.VERSION.add(1))
                            .execute();
                }

                // G2 (spike B2) — THE KEYSTONE: read-decide-branch idempotent replay. Read whether
                // the idempotency key is already present into a typed boolean local; if it is,
                // RETURN immediately WITHOUT mutating — no draft upsert, no audit row. Only the
                // first apply for a key mutates the draft AND appends exactly one audit row. The
                // audit insert carries NO conflict clause and the audit id is database-generated, so
                // a replay that failed to short-circuit would append a SECOND audit row — the
                // single-audit-row assertion proves the branch short-circuited server-side, not via
                // ON CONFLICT. The idempotency-key insert is recorded last. This is the faithful
                // form the spike could not express (boolean local + branch).
                @StoredProcedure
                public static void idempotentImport(int id, String name, String key) {
                    boolean alreadyApplied = select(GATE_IDEMPOTENCY.IDEMPOTENCY_KEY)
                            .from(GATE_IDEMPOTENCY)
                            .where(GATE_IDEMPOTENCY.IDEMPOTENCY_KEY.eq(key))
                            .fetchExistsValue();
                    if (alreadyApplied) {
                        return;
                    }
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .set(GATE_EVENTS.NAME, name)
                            .execute();
                    insertInto(GATE_AUDIT)
                            .set(GATE_AUDIT.IDEMPOTENCY_KEY, key)
                            .set(GATE_AUDIT.NAME, name)
                            .execute();
                    insertInto(GATE_IDEMPOTENCY)
                            .set(GATE_IDEMPOTENCY.IDEMPOTENCY_KEY, key)
                            .execute();
                }

                // G2 (spike B2): optimistic-concurrency / precondition check. Read the current
                // version into a typed int local; if it is already at or past the requested version,
                // RETURN without writing (the precondition fails). Otherwise update the row to the
                // new version + name. Proves the scalar read-decide-branch: a stale (<=) apply is a
                // server-side no-op, a newer one writes. No row yet => the local is NULL and the
                // comparison is UNKNOWN, so the guard does not fire and the first apply inserts.
                @StoredProcedure
                public static void applyIfNewer(int id, int newVersion, String name) {
                    int current = select(GATE_EVENTS.VERSION)
                            .from(GATE_EVENTS)
                            .where(GATE_EVENTS.ID.eq(id))
                            .fetchScalar();
                    if (current >= newVersion) {
                        return;
                    }
                    insertInto(GATE_EVENTS)
                            .set(GATE_EVENTS.ID, id)
                            .set(GATE_EVENTS.NAME, name)
                            .set(GATE_EVENTS.VERSION, newVersion)
                            .onConflict(GATE_EVENTS.ID)
                            .doUpdate()
                            .set(GATE_EVENTS.NAME, name)
                            .set(GATE_EVENTS.VERSION, newVersion)
                            .execute();
                }

                static final class GateEventsTable extends Table<Object> {
                    final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);
                    final Column<String> DOCUMENT = column("document", SQLType.JSON, Nullability.NULLABLE);
                    final Column<Integer> VERSION = column("version", SQLType.INTEGER, Nullability.NOT_NULL);

                    GateEventsTable() {
                        super("gate_events", "test");
                    }
                }

                static final class GateIdempotencyTable extends Table<Object> {
                    final Column<String> IDEMPOTENCY_KEY = column("idempotency_key", SQLType.VARCHAR, Nullability.NOT_NULL);

                    GateIdempotencyTable() {
                        super("gate_idempotency", "test");
                    }
                }

                static final class GateAuditTable extends Table<Object> {
                    final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    final Column<String> IDEMPOTENCY_KEY = column("idempotency_key", SQLType.VARCHAR, Nullability.NOT_NULL);
                    final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                    GateAuditTable() {
                        super("gate_audit", "test");
                    }
                }
            }
            """, false);

    // Phase 2.4 (E-11): transpiled in strict-wraparound mode — int arithmetic must reproduce
    // Java's silent 32-bit wrap via the java_int_add/sub/mul runtime helpers.
    public static final Fixture STRICT_WRAPAROUND = new Fixture("strict-wraparound", """
            import titan.dsl.*;

            class WraparoundFixture {
                @StoredFunction
                public static int wrapAdd(int a, int b) {
                    return a + b;
                }

                @StoredFunction
                public static int wrapSub(int a, int b) {
                    return a - b;
                }

                @StoredFunction
                public static int wrapMul(int a, int b) {
                    return a * b;
                }
            }
            """, true);

    // E-5 (plan 3.1): try-body statements after a failing statement must not execute.
    public static final Fixture TRY_ABORT = new Fixture("try-abort", """
            import titan.dsl.*;

            class TryAbortFixture {
                @StoredFunction
                public static String trySideEffects(boolean explode) {
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
            }
            """, false);

    // Phase 3.1: composite Java expressions in DSL .set(...) value positions must lower
    // through ExpressionLowerer and remap to allocated p_/v_ routine names.
    public static final Fixture SET_VALUE_EXPRESSION = new Fixture("set-value-expression", """
            import titan.dsl.*;
            import static titan.dsl.DSL.*;

            class SetValueExpressionFixture {
                static final MathEventsTable MATH_EVENTS = new MathEventsTable();

                @StoredProcedure
                public static void recordMath(int id, int factor, String name) {
                    insertInto(MATH_EVENTS)
                            .set(MATH_EVENTS.ID, id + 100)
                            .set(MATH_EVENTS.SCORE, id * factor)
                            .set(MATH_EVENTS.LABEL, name + "-tag")
                            .execute();
                }

                static final class MathEventsTable extends Table<Object> {
                    final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    final Column<Integer> SCORE = column("score", SQLType.INTEGER, Nullability.NOT_NULL);
                    final Column<String> LABEL = column("label", SQLType.VARCHAR, Nullability.NOT_NULL);

                    MathEventsTable() {
                        super("math_events", "test");
                    }
                }
            }
            """, false);

    // Phase 3.3 (audit D-7): table aliases enabling a self-join, DISTINCT, a searched CASE
    // projection, NOT IN, and NULLS LAST ordering (natively on PostgreSQL, emulated with the
    // (expr IS NULL) leading sort key on MySQL).
    public static final Fixture PHASE33_SURFACE = new Fixture("phase33-surface", """
            import titan.dsl.*;
            import static titan.dsl.DSL.*;

            class Phase33SurfaceFixture {
                static final EmployeesTable EMPLOYEES = new EmployeesTable();

                @StoredProcedure
                public static void surfaceQuery() {
                    AliasedTable<Object> e = EMPLOYEES.as("e");
                    AliasedTable<Object> m = EMPLOYEES.as("m");
                    select(e.col(EMPLOYEES.NAME),
                            when(m.col(EMPLOYEES.SCORE).ge(90), "top")
                                    .when(m.col(EMPLOYEES.SCORE).ge(50), "mid")
                                    .otherwise("low"),
                            m.col(EMPLOYEES.SCORE))
                            .distinct()
                            .from(e)
                            .join(m).on(e.col(EMPLOYEES.MANAGER_ID), m.col(EMPLOYEES.ID))
                            .where(e.col(EMPLOYEES.ID).notIn(5, 6))
                            .orderBy(m.col(EMPLOYEES.SCORE).desc().nullsLast(), e.col(EMPLOYEES.NAME).asc())
                            .fetch();
                }

                static final class EmployeesTable extends Table<Object> {
                    public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    public final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);
                    public final Column<Integer> MANAGER_ID = column("manager_id", SQLType.INTEGER, Nullability.NULLABLE);
                    public final Column<Integer> SCORE = column("score", SQLType.INTEGER, Nullability.NULLABLE);

                    EmployeesTable() {
                        super("employees", "test");
                    }
                }
            }
            """, false);

    // E-8 (plan 3.1): MySQL session time-zone save/restore on every exit path; also a plain
    // function RETURN, a procedure fall-through, and an exception exit in one fixture.
    public static final Fixture TIME_ZONE = new Fixture("time-zone", """
            import titan.dsl.*;
            import static titan.dsl.DSL.*;

            class TimeZoneFixture {
                static final TzEventsTable TZ_EVENTS = new TzEventsTable();

                // Function RETURN path (the staged-return shape).
                @StoredFunction
                public static int doubled(int value) {
                    return value * 2;
                }

                // Procedure fall-through path.
                @StoredProcedure
                public static void touch(int id) {
                    insertInto(TZ_EVENTS)
                            .set(TZ_EVENTS.ID, id)
                            .set(TZ_EVENTS.NAME, "touched")
                            .execute();
                }

                // Exception path: the routine-level EXIT handler must restore before RESIGNAL.
                @StoredFunction
                public static int failing(int value) {
                    if (value < 0) {
                        throw new RuntimeException("TITAN_TZ_BOOM");
                    }
                    return value;
                }

                static final class TzEventsTable extends Table<Object> {
                    final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                    TzEventsTable() {
                        super("tz_events", "test");
                    }
                }
            }
            """, false);

    // B-3 (TG-BLK-006): control-character char/string literals must survive emission and the
    // indenter. Char literals used to be emitted as raw bytes; indentMultiline then treated an
    // embedded LF inside the literal as a line break and "indented" it — '\n' became a
    // five-character literal, silently flipping comparisons. The corruption deploys fine, so
    // the deployability IT executes these routines and compares against the Java reference.
    public static final Fixture CONTROL_CHAR_LITERALS = new Fixture("control-char-literals", """
            import titan.dsl.*;

            class ControlCharLiteralsFixture {
                // Char literals '\\n'/'\\t'/'\\r' compared inside IF bodies — the exact
                // indentMultiline corruption shape from TG-BLK-006 (is_whitespace(chr(10))).
                @StoredFunction
                public static int classifyControlChar(String text) {
                    char first = text.charAt(0);
                    int kind = 0;
                    if (first == '\\n') {
                        kind = 1;
                    }
                    if (first == '\\t') {
                        kind = 2;
                    }
                    if (first == '\\r') {
                        kind = 3;
                    }
                    return kind;
                }

                // String literals with embedded control characters in expression positions:
                // concatenation builds "a\\nb"-shaped values, contains() probes a CRLF needle.
                @StoredFunction
                public static String joinWithNewline(String left, String right) {
                    return left + "\\n" + right;
                }

                @StoredFunction
                public static boolean containsCrLf(String text) {
                    return text.contains("\\r\\n");
                }
            }
            """, false);

    /**
     * B-10 (TG-BLK-012): a boolean value reaching a string/JSON/text context must render
     * {@code true}/{@code false}, matching Java's {@link Boolean#toString(boolean)} and
     * PostgreSQL's {@code boolean::text}. On MySQL a boolean is {@code TINYINT}, so a bare
     * {@code CAST(<boolean> AS CHAR)} renders {@code '1'}/{@code '0'} — the divergence that took
     * six of the consumer's 97 MySQL-equivalence cases ({@code pageInfo.hasNextPage},
     * {@code isRepeatable}, ...) off strict equality. Covers each boolean-to-text site: a boolean
     * operand in {@code String +} concatenation, {@code String.valueOf(boolean)}, and a boolean
     * field rendered into a JSON-ish output string.
     */
    public static final Fixture BOOLEAN_TEXT = new Fixture("boolean-text", """
            import titan.dsl.*;

            class BooleanTextFixture {
                // Boolean operand in a String + concatenation chain (lowers to __titan_str_concat).
                @StoredFunction
                public static String labelFlag(String name, boolean flag) {
                    return name + "=" + flag;
                }

                // String.valueOf(boolean) — lowers to a CAST to text.
                @StoredFunction
                public static String renderBoolean(boolean flag) {
                    return String.valueOf(flag);
                }

                // A boolean predicate result (a comparison, not a stored boolean) reaching text.
                @StoredFunction
                public static String describeOrder(int left, int right) {
                    return "ascending=" + (left <= right);
                }

                // The TG-BLK-012 shape verbatim: boolean fields rendered into a JSON object string,
                // exactly like the consumer's pageInfo.hasNextPage / hasPreviousPage output.
                @StoredFunction
                public static String pageInfoJson(boolean hasNextPage, boolean hasPreviousPage) {
                    return "{\\"hasNextPage\\":" + hasNextPage
                            + ",\\"hasPreviousPage\\":" + hasPreviousPage + "}";
                }

                // A boolean-returning routine: the consumer's actual divergence path is a call to
                // a routine like this whose BOOLEAN result is concatenated into the JSON output.
                @StoredFunction
                public static boolean hasMore(int count, int limit) {
                    return count > limit;
                }

                // Boolean-returning routine CALL embedded in JSON text — the exact consumer shape
                // (pageInfo.hasNextPage comes from a generated routine, not a bare parameter).
                @StoredFunction
                public static String pageInfoFromRoutine(int count, int limit) {
                    return "{\\"hasNextPage\\":" + hasMore(count, limit) + "}";
                }
            }
            """, false);
}
