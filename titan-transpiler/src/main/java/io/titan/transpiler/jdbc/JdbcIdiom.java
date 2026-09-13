package io.titan.transpiler.jdbc;

/**
 * The JDBC idiom a recognized usage matched, named after the rows of
 * {@code docs/transpilable-jdbc-subset.md} §3 (in-scope), §3.6 (safe dynamic IN), and §5
 * (out-of-scope / hard reject).
 *
 * <p>The recognizer attaches one of these to every {@link JdbcUsage} so the linter can report
 * coverage {@code byIdiom}. It carries the spec section as documentation only; the verdict lives
 * on {@link JdbcClassification}.</p>
 */
public enum JdbcIdiom {
    /** I-1 — prepared/plain statement over constant SQL (anchors the binding map). */
    I_1,
    /** I-2 — indexed {@code setXxx(ordinalLiteral, value)} binding. */
    I_2,
    /** I-3 — {@code rs = ps.executeQuery()} query execution. */
    I_3,
    /** I-4 — single-row read (the two accepted §3.3 shapes). */
    I_4,
    /** I-5 — {@code while (rs.next())} multi-row cursor read. */
    I_5,
    /** I-6 — {@code executeUpdate()} DML execution. */
    I_6,
    /** I-7 — {@code RETURN_GENERATED_KEYS} + {@code getGeneratedKeys()} single auto-inc key. */
    I_7,
    /** I-8 — plain {@code Statement} + constant SQL ({@code createStatement().execute(SQL)}). */
    I_8,
    /** I-9 — try-with-resources / {@code catch(SQLException)} around JDBC. */
    I_9,
    /** I-10 — transactions ({@code setAutoCommit}/{@code commit}/{@code rollback}). */
    I_10,

    /**
     * §3.6(1) — prior-query subquery fusion: {@code IN (SELECT ...)} (value-binding, both modes).
     *
     * @deprecated DEFERRED in Phase 1 (recognition-only). The §3.6 dynamic-IN carve-outs require SQL
     *     parsing / per-ordinal bind proof the analysis-only pass does not perform, and the contract
     *     forbids ever false-accepting a value splice; until a later phase implements a provable
     *     recognizer these shapes fall through to the strict gate. Retained for the planned work and
     *     to keep {@code byIdiom} stable. See {@code docs/transpilable-jdbc-subset.md} §3.6.
     */
    @Deprecated
    DYNAMIC_IN_SUBQUERY_FUSION,
    /**
     * §3.6(2) — whole-collection bind ({@code = ANY(?)} / JSON / temp table) (both modes).
     *
     * @deprecated DEFERRED in Phase 1 — see {@link #DYNAMIC_IN_SUBQUERY_FUSION}.
     */
    @Deprecated
    DYNAMIC_IN_COLLECTION_BIND,
    /**
     * §3.6(3) — provable {@code IN (?, ?, ...)} placeholder run, every ordinal bound (both modes).
     *
     * @deprecated DEFERRED in Phase 1 — the recognizer cannot prove a runtime-sized {@code ?}-run is
     *     placeholder-only with every ordinal bound without SQL parsing, so it is never emitted (the
     *     shape falls through to the strict gate). {@code DynamicInListRecognizer} locks the FAIL-SAFE
     *     structural guard for the future implementation. See {@link #DYNAMIC_IN_SUBQUERY_FUSION}.
     */
    @Deprecated
    DYNAMIC_IN_PLACEHOLDER_RUN,

    /** I-R1 — dynamic/concatenated SQL into {@code prepareStatement} (strict-scope reject). */
    I_R1,
    /** I-R2 — non-constant {@code ResultSet} column key. */
    I_R2,
    /** I-R3 — returning/storing a {@code ResultSet}. */
    I_R3,
    /** I-R4 — returning/storing a {@code Connection}/{@code Statement}/{@code PreparedStatement}. */
    I_R4,
    /** I-R5 — scrollable/updatable {@code ResultSet}. */
    I_R5,
    /**
     * I-R6 — {@code ResultSetMetaData}/{@code DatabaseMetaData} introspection. <b>WS-C Phase 3 Rung 5
     * (implemented, partial):</b> the PURE metadata-driven generic reader — a {@code while (rs.next())}
     * loop that marshals every column via {@code ResultSetMetaData.getColumnCount()}/{@code
     * getColumnLabel(i)}/{@code rs.getObject(i)} into a per-row {@code Map<String,Object>} added to a
     * returned {@code List<Map<String,Object>>} — now transpiles to an unknown-shape carrier (PostgreSQL
     * {@code jsonb} via {@code jsonb_agg}/{@code to_jsonb}; MySQL a native open result-set procedure), per
     * design contract D5 / §3.6. Any business logic over the unknown-shape rows (Tier-4), {@code
     * DatabaseMetaData}, and non-pure {@code ResultSetMetaData} processing remain rejects.
     */
    I_R6,
    /** I-R7 — stream/LOB streaming handles. */
    I_R7,
    /** I-R8 — non-single-auto-increment generated keys. */
    I_R8,

    /** §5.1 — {@code CallableStatement} / {@code prepareCall("{call ...}")}. */
    CALLABLE_STATEMENT,
    /** §5.2 — {@code addBatch()}/{@code executeBatch()}. */
    BATCH
}
