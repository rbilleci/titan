package io.titan.transpiler.tir;

import java.util.List;

record DeclareVariable(String name, TirType type, boolean nullable, ExpressionNode initializer)
        implements DeclarationNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDeclareVariable(this);
    }
}

record DeclareCursor(String name, SelectSql query) implements DeclarationNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDeclareCursor(this);
    }
}

record DeclareHandler(List<String> conditions, HandlerAction action, String flagVariable)
        implements DeclarationNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDeclareHandler(this);
    }
}

enum HandlerAction {
    CONTINUE,
    EXIT
}

record Block(List<DeclarationNode> declarations, List<StatementNode> statements, List<DeclarationNode> exceptionHandlers)
        implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitBlock(this);
    }
}

record Assign(ExpressionNode target, ExpressionNode expression) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitAssign(this);
    }
}

record IfStatement(ExpressionNode condition, Block thenBlock, List<ElseIfClause> elseIfClauses, Block elseBlock)
        implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitIfStatement(this);
    }
}

record ElseIfClause(ExpressionNode condition, Block block) {}

record WhileStatement(ExpressionNode condition, Block body, String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitWhileStatement(this);
    }
}

record LoopStatement(Block body, ExpressionNode exitCondition, String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitLoopStatement(this);
    }
}

record ForCursorStatement(String variableName, SelectSql query, Block body, String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitForCursorStatement(this);
    }
}

record ForEachStatement(String variableName, TirType variableType, ExpressionNode iterable, Block body, String label)
        implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitForEachStatement(this);
    }
}

record ForRangeStatement(String variableName, ExpressionNode start, ExpressionNode end, Block body, String label)
        implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitForRangeStatement(this);
    }
}

record ReturnStatement(ExpressionNode expression) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitReturnStatement(this);
    }
}

record BreakStatement(String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitBreakStatement(this);
    }
}

record ContinueStatement(String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitContinueStatement(this);
    }
}

/**
 * Raises a SQL error. {@code message} is a real expression (plan 2.2, F-10): string-literal
 * messages render byte-identically to the historical literal form, while dynamic messages
 * (concatenations, variables, rethrown catch variables) are emitted per dialect — PostgreSQL
 * via {@code RAISE EXCEPTION USING MESSAGE = <expr>}, MySQL by staging the value through a
 * variable because {@code SIGNAL ... SET MESSAGE_TEXT} only accepts simple values (the E-1
 * staged-rethrow pattern).
 */
record RaiseStatement(String sqlstate, ExpressionNode message, List<ExpressionNode> details) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRaiseStatement(this);
    }
}

record CallStatement(String procedureName, List<ExpressionNode> arguments) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitCallStatement(this);
    }
}

record DebugPrintStatement(ExpressionNode message) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDebugPrintStatement(this);
    }
}

record ExecuteSqlStatement(SqlNode sqlNode) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitExecuteSqlStatement(this);
    }
}

/**
 * Binds a single-row, single-column (or EXISTS) query result to a routine local (Phase A4 / G2 —
 * read-decide-branch). Lowered from a declaration/assignment whose initializer is a typed DSL
 * read-into-local terminal ({@code fetchScalar()} ⇒ a scalar column, {@code fetchExistsValue()} ⇒
 * an EXISTS boolean). The bound {@code variableName} is a usable {@link VariableRefExpression} in
 * later control flow, so {@code if (v) ...} lowers via the ordinary if/return path.
 *
 * <p>Emitted as {@code SELECT <select-list> INTO <var> FROM ...;} on both PostgreSQL (plpgsql) and
 * MySQL — the INTO target sits between the select list and FROM. The EXISTS case carries
 * {@code query.existsWrapper() == true}, emitting {@code SELECT EXISTS (<subquery>) INTO <var>;}.
 * No-row behavior: a scalar SELECT INTO that matches no row leaves the variable NULL on both
 * dialects (PL/pgSQL assigns NULL and continues; MySQL's default-no-handler scalar SELECT INTO
 * leaves it unchanged from its DECLAREd NULL default), while an EXISTS INTO always binds a boolean
 * (false when empty). Scope is deliberately scalar/EXISTS only — multi-row / full row-set INTO is
 * out of scope (that is the row-materialization machinery).</p>
 */
record SelectIntoStatement(String variableName, SelectSql query) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitSelectIntoStatement(this);
    }
}

record NullGuardStatement(String variableName, String sourceLocation) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitNullGuardStatement(this);
    }
}

record CloseCursorStatement(String cursorName) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitCloseCursorStatement(this);
    }
}

/**
 * Single-row read from opaque, unparsed source-dialect SQL into one or more routine locals
 * (JDBC I-4 over unparsed text, WS-C Phase 2; native single-dialect emission, {@code
 * docs/transpilable-jdbc-subset.md} §4 step 3). Distinct from {@link SelectIntoStatement}, which
 * binds a parsed {@link SelectSql} into a single scalar/EXISTS target; this leaf carries a {@link
 * RawSql} whose text is emitted verbatim via the source dialect's dynamic read form and an ordered
 * list of INTO targets.
 *
 * <p>{@code query.parameters()} are in-scope variable names spliced as bound values (PostgreSQL
 * {@code USING}, MySQL session {@code @titan_p}); the {@code ?} placeholders in the text are
 * rewritten to the dialect placeholder by the emitter. Emitted as PostgreSQL
 * {@code EXECUTE '<sql>' INTO <vars> USING <params>;} (multi-target INTO is native to dynamic
 * EXECUTE) and on MySQL as the session-var-staged {@code SET @titan_rN = NULL; … PREPARE s FROM
 * '<sql with INTO @titan_rN injected>'; EXECUTE s USING @titan_pN; DEALLOCATE; SET <var> =
 * @titan_rN;} sequence — the INTO is embedded in the PREPARE text (MySQL has no dynamic
 * {@code EXECUTE … INTO}). No-row contract: every target is NULL-on-no-row (PL/pgSQL assigns NULL;
 * MySQL pre-inits each {@code @titan_rN} to NULL).</p>
 *
 * <p>{@code notFoundRaise} carries the {@code if (!rs.next()) throw …} early-return guard (JDBC I-4
 * §6.1). When non-null it is emitted as a <b>FOUND-based</b> no-row guard <i>after</i> the read —
 * PostgreSQL {@code IF NOT FOUND THEN <raise> END IF;} (the native {@code EXECUTE … INTO} sets the
 * FOUND flag), MySQL {@code IF ROW_COUNT() = 0 THEN <raise> END IF;} (the embedded-INTO PREPARE
 * sets {@code ROW_COUNT()} to the matched-row count). This is deliberately <b>not</b> a
 * "first INTO target IS NULL" proxy: a legitimately NULL first column on an existing row must not
 * trip the guard (a NULL-of-first-column proxy mis-fires on a nullable column — WS-C Phase 2b
 * audit). {@code null} for the plain {@code if (rs.next()) { … }} read, which is silently
 * NULL-on-no-row.</p>
 */
record RawReadIntoStatement(List<String> variableNames, RawSql query, RaiseStatement notFoundRaise)
        implements StatementNode {

    /** A read with no early-return guard (the {@code if (rs.next()) { … }} NULL-on-no-row form). */
    RawReadIntoStatement(List<String> variableNames, RawSql query) {
        this(variableNames, query, null);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRawReadIntoStatement(this);
    }
}

/**
 * Multi-row read loop over opaque, unparsed source-dialect SQL (JDBC I-5 over unparsed text, WS-C
 * Phase 2; {@code docs/transpilable-jdbc-subset.md} §4 step 3). Distinct from {@link
 * ForCursorStatement}, which iterates a parsed {@link SelectSql} binding a single row record; this
 * leaf carries a {@link RawSql} and FETCHes its columns into an ordered list of scalar locals,
 * running {@code body} once per row through the ordinary statement-lowering path.
 *
 * <p>Emitted as PostgreSQL {@code DECLARE <cur> refcursor; OPEN <cur> FOR EXECUTE '<sql>' USING
 * <params>; LOOP FETCH <cur> INTO <vars>; EXIT WHEN NOT FOUND; <body> END LOOP; CLOSE <cur>;} (a
 * dynamic refcursor over the source text). On MySQL the SQL must be constant text — it is inlined
 * into a static {@code DECLARE <cur> CURSOR FOR <text>} reusing the cursor safety scaffold
 * (done/open flags, EXIT HANDLER close-if-open + RESIGNAL, NOT FOUND handler, labeled LOOP). MySQL
 * cannot iterate runtime-built text via a static cursor, so {@code query.parameters()} here are
 * variable names spliced as inline routine-local identifier references (never values), and the
 * permissive non-constant case is rejected upstream (decision 1).</p>
 */
record RawCursorStatement(List<String> variableNames, RawSql query, Block body, String label) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRawCursorStatement(this);
    }
}

/**
 * Unknown-shape result carrier (JDBC Tier-3, WS-C Phase 3 Rung 5; {@code
 * docs/transpilable-jdbc-subset.md} §3.6 / design contract D5). Lowered from a <b>pure,
 * metadata-driven</b> generic reader — a {@code while (rs.next())} loop whose body ONLY marshals every
 * column (read via {@code ResultSetMetaData.getColumnCount()}/{@code getColumnLabel(i)}/{@code
 * rs.getObject(i)}) into a per-row {@code Map<String,Object>} and adds it to a returned {@code
 * List<Map<String,Object>>}. Because the row SHAPE is not statically known, there are no typed FETCH
 * targets; the whole reader becomes a single shape-agnostic carrier that yields exactly the same data
 * the {@code List<Map>} would, in the source dialect's native metadata-driven form:
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code RETURNS jsonb} function): {@code EXECUTE 'SELECT
 *       COALESCE(jsonb_agg(to_jsonb(t)), ''[]''::jsonb) FROM (<sql>) t' INTO <result> USING <binds>;
 *       RETURN <result>;}. {@code to_jsonb}/{@code jsonb_agg} ARE the native metadata-driven
 *       row→object / list operation — every column becomes a JSON object key by its result label,
 *       independent of the static shape; an empty result yields {@code '[]'::jsonb}.</li>
 *   <li><b>MySQL</b> (a {@code @StoredProcedure}): the dynamic {@code SELECT} is {@code
 *       PREPARE}/{@code EXECUTE}d and the result set is left <b>open</b> (no {@code INTO}, no {@code
 *       DEALLOCATE} consuming it) so MySQL's native dynamic result set streams to the client — MySQL
 *       forbids dynamic SQL in a {@code FUNCTION} (ERROR 1336), so a dynamic-return MySQL routine MUST
 *       be a procedure (§9 invariant 1).</li>
 * </ul>
 *
 * <p>The {@link #query}'s {@code ?} placeholders bind exactly like every other {@link RawSql} ({@code
 * USING} on PostgreSQL, {@code @p} on MySQL); its text is constant under strict, or a permissive
 * value-splice/skeleton (the SQL-text safety rules are unchanged — the carrier only changes the result
 * shape, never how values reach the text). Tier-4 (any Java compute over the unknown-shape rows beyond
 * faithful marshalling) is rejected upstream by {@link
 * io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer} — there is no server-side form, so it never
 * reaches this node.</p>
 */
record DynamicResultStatement(RawSql query) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDynamicResultStatement(this);
    }
}

/**
 * Insert-with-generated-key recovery (JDBC I-7, §6.3 / I-R8) over an opaque source-dialect INSERT:
 * runs {@code insert} and binds the inserted row's single auto-increment/identity key — the column
 * named by {@code keyColumn}, resolved from the Catalog (the JDBC ordinal {@code 1} in
 * {@code getLong(1)} does <i>not</i> name a column) — into the routine local {@code keyLocal}.
 *
 * <p>This is a <b>node</b> rather than pre-rendered text because the pipeline lowers once for all
 * targets and each emitter renders the dialect's <i>trigger-immune</i> driver-faithful form:
 * <ul>
 *   <li><b>PostgreSQL</b>: a single dynamic {@code EXECUTE '<insert.sql> RETURNING <keyColumn>' INTO
 *       <keyLocal> USING <insert.params>;} — PL/pgSQL's dynamic {@code EXECUTE … INTO} reads the
 *       <i>actual inserted row's</i> key column (what the PostgreSQL JDBC driver returns for
 *       {@code getGeneratedKeys()}), so an {@code AFTER INSERT} trigger advancing any other sequence
 *       cannot perturb it. (A session {@code lastval()} read would be wrong here.)</li>
 *   <li><b>MySQL</b>: emit the {@code insert} (the ordinary {@link RawSql} PREPARE/EXECUTE/DEALLOCATE)
 *       then {@code SET <keyLocal> = LAST_INSERT_ID();} on the very next statement — exactly what the
 *       MySQL JDBC driver returns for {@code getGeneratedKeys()} (MySQL has no {@code RETURNING}).
 *       {@code LAST_INSERT_ID()} is the connection's last AUTO_INCREMENT value and is immune to an
 *       {@code AFTER INSERT} trigger's own inserts/sequences (no {@code FROM DUAL} hack, no INTO
 *       injection).</li>
 * </ul>
 *
 * <p>The lowerer emits this leaf <i>only</i> when the key column resolves to exactly one
 * auto-increment/identity column from the Catalog; an unresolvable table (no model, table not
 * introspected, 0 or &gt;1 auto-increment columns) is rejected cleanly (I-R8 / {@code TITAN-E001}),
 * never lowered to a guessed key.</p>
 */
record GeneratedKeyReadStatement(RawSql insert, String keyColumn, String keyLocal) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitGeneratedKeyReadStatement(this);
    }
}

/**
 * Transaction-control statement (JDBC I-10, WS-C Phase 2; {@code docs/transpilable-jdbc-subset.md}
 * §3.4). Lowered from {@code connection.commit()}/{@code rollback()} in a method emitted as a
 * procedure. Emitted identically on both dialects as {@code COMMIT;} / {@code ROLLBACK;}. The
 * mandatory outer-atomic-block {@code TITAN-W005} portability warning is attached by the pipeline
 * at emission time (it owns the positioned diagnostic sink); this leaf carries only the action.
 */
record TransactionControlStatement(TransactionAction action) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitTransactionControlStatement(this);
    }
}

enum TransactionAction {
    COMMIT,
    ROLLBACK
}

record TryCatchFinallyStatement(Block tryBlock, List<CatchClause> catches, Block finallyBlock) implements StatementNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitTryCatchFinallyStatement(this);
    }
}

record CatchClause(String exceptionVariable, String exceptionType, List<String> sqlStates, Block body) {}

record SelectSql(List<SelectColumn> columns,
                 boolean distinct,
                 String from,
                 String fromAlias,
                 List<JoinSpec> joins,
                 ExpressionNode where,
                 List<ExpressionNode> groupBy,
                 ExpressionNode having,
                 List<OrderBySpec> orderBy,
                 Integer limit,
                 Integer offset,
                 boolean existsWrapper,
                 LockingClause locking,
                 List<CteSpec> ctes) implements SqlNode {
    SelectSql(List<SelectColumn> columns,
              String from,
              List<JoinSpec> joins,
              ExpressionNode where,
              List<ExpressionNode> groupBy,
              ExpressionNode having,
              List<OrderBySpec> orderBy,
              Integer limit,
              Integer offset,
              boolean existsWrapper,
              LockingClause locking,
              List<CteSpec> ctes) {
        this(columns, false, from, null, joins, where, groupBy, having, orderBy, limit, offset, existsWrapper, locking, ctes);
    }

    SelectSql(List<SelectColumn> columns,
              String from,
              List<JoinSpec> joins,
              ExpressionNode where,
              List<ExpressionNode> groupBy,
              ExpressionNode having,
              List<OrderBySpec> orderBy,
              Integer limit,
              Integer offset,
              LockingClause locking,
              List<CteSpec> ctes) {
        this(columns, false, from, null, joins, where, groupBy, having, orderBy, limit, offset, false, locking, ctes);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitSelectSql(this);
    }
}

record InsertSql(String table,
                 List<String> columns,
                 List<ExpressionNode> values,
                 SelectSql selectSource,
                 ConflictClause onConflict,
                 List<String> returning) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitInsertSql(this);
    }
}

record UpdateSql(String table, List<SetClause> sets, ExpressionNode where, List<String> returning) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitUpdateSql(this);
    }
}

record DeleteSql(String table, ExpressionNode where, List<String> returning) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitDeleteSql(this);
    }
}

record UnionSql(SqlNode left, SqlNode right, boolean all) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitUnionSql(this);
    }
}

record IntersectSql(SqlNode left, SqlNode right) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitIntersectSql(this);
    }
}

record ExceptSql(SqlNode left, SqlNode right) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitExceptSql(this);
    }
}

/**
 * Opaque single-dialect SQL text with positional {@code ?} binds (JDBC-input §4 substrate). Each
 * {@code ?} outside a string literal is bound, left-to-right, to the same-position entry of {@link
 * #parameters} (a variable name) via the dialect's dynamic-execute path ({@code EXECUTE … USING} on
 * PostgreSQL, {@code PREPARE/EXECUTE … USING @p} on MySQL).
 *
 * <p><b>WS-C Phase 3 Rung 4 — collection/array binding (§3.6 form 2).</b> A whole runtime collection
 * can be bound as <b>one</b> array/JSON parameter instead of a per-element {@code ?} run. Where that
 * happens, {@link #sql} carries a per-membership <b>marker</b> ({@link #arrayBindMarker(int)}) at the
 * {@code col IN (…)} position, and {@link #arrayBinds} describes each marker so the emitter expands it
 * to the source dialect's native form: PostgreSQL {@code <lhs> = ANY(?)} (the parameter is a native
 * {@code bigint[]}/{@code int[]}/{@code text[]} array), MySQL {@code <lhs> IN (SELECT v FROM
 * JSON_TABLE(?, '$[*]' COLUMNS (v <sqlType> PATH '$')) t)} (the parameter is one JSON-array string).
 * The expanded text contributes exactly one {@code ?} per marker, which {@link
 * io.titan.transpiler.tir.AbstractSqlEmitter#rewritePositionalPlaceholders} then numbers in line with
 * the other placeholders — so the array parameter binds through the same {@code USING}/{@code @p}
 * machinery as a scalar, and <b>no element ever reaches the SQL text</b> (the security invariant).</p>
 *
 * <p><b>WS-C Phase 3 Rung 3 — identifier / raw-fragment splice ({@code permissive} only).</b> A runtime
 * identifier (table/column/{@code ORDER BY}) or an opaque raw SQL fragment cannot be a bound value — it
 * is <i>structural</i>, so it reaches the SQL text. Under a {@code permissive} scope (and never under
 * {@code strict}, which rejects with {@code TITAN-E004}) such a hole emits via {@link #spliceBinds}: a
 * per-splice <b>marker</b> ({@link #spliceMarker(int)}) sits in {@link #sql} where the identifier /
 * fragment goes, and each {@link SpliceBind} carries the routine-local name to splice and its
 * {@link SpliceBind.Kind}. The emitter does <b>not</b> bind these via {@code USING}; instead it assembles
 * the SQL text at runtime — PostgreSQL {@code EXECUTE format('… %I …', p_ident) USING <value binds>}
 * ({@code %I} runtime identifier quoting; {@code %s} for a raw fragment, spliced verbatim), MySQL
 * {@code PREPARE … FROM CONCAT('… ', <quoted/raw expr>, ' …'); EXECUTE … USING @p}. Genuine
 * {@code VALUE} holes in the same statement still bind through {@link #parameters} as usual; only the
 * identifier / fragment is structural. This faithfully reproduces the source's dynamic structure (and,
 * for a raw fragment, its injection exposure) — the migration on-ramp the {@code permissive} mode
 * exists for, surfaced by {@code titanPermissiveScopesReport}.</p>
 */
record RawSql(String sql, List<String> parameters, String dialect, List<ArrayBind> arrayBinds,
              List<SpliceBind> spliceBinds) implements SqlNode {
    RawSql(String sql, List<String> parameters, String dialect) {
        this(sql, parameters, dialect, List.of(), List.of());
    }

    RawSql(String sql, List<String> parameters, String dialect, List<ArrayBind> arrayBinds) {
        this(sql, parameters, dialect, arrayBinds, List.of());
    }

    RawSql {
        arrayBinds = arrayBinds == null ? List.of() : List.copyOf(arrayBinds);
        spliceBinds = spliceBinds == null ? List.of() : List.copyOf(spliceBinds);
    }

    /**
     * The membership marker token placed in {@link #sql} for the array bind with id {@code markerId}.
     * Uses control char {@code U+0001}, which the skeleton recognizer never admits into recovered SQL
     * (it is not a printable SQL token), so a marker can never collide with real text. The emitter
     * replaces every occurrence with the dialect-native membership form.
     */
    static String arrayBindMarker(int markerId) {
        return "A" + markerId + "";
    }

    /**
     * The splice marker token placed in {@link #sql} for the {@link SpliceBind} with id {@code markerId}
     * (WS-C Phase 3 Rung 3). Uses control char {@code U+0002} (distinct from the {@code U+0001} array-bind
     * marker), which the skeleton recognizer never admits into recovered SQL, so it cannot collide with
     * real text and is invisible to {@link
     * io.titan.transpiler.tir.AbstractSqlEmitter#rewritePositionalPlaceholders} (it carries no {@code ?}).
     * The emitter replaces every occurrence with the dialect-native identifier-quoting / verbatim-splice
     * form at runtime ({@code format('%I'/'%s', …)} / {@code CONCAT(…)}).
     */
    static String spliceMarker(int markerId) {
        return "S" + markerId + "";
    }

    /** True when this {@link RawSql} carries at least one identifier / raw-fragment splice (Rung 3). */
    boolean hasSpliceBinds() {
        return !spliceBinds.isEmpty();
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRawSql(this);
    }
}

/**
 * WS-C Phase 3 Rung 3 — one identifier / raw-fragment splice of a {@link RawSql} (§3.6 / §4, {@code
 * permissive} only). The marker {@link RawSql#spliceMarker(int)} for {@link #markerId} sits in the SQL
 * text where a runtime identifier (table/column/{@code ORDER BY}) or an opaque raw SQL fragment goes;
 * the emitter expands it to the source dialect's runtime text-assembly form, splicing {@link #paramName}
 * (a routine-local variable name) rather than binding it via {@code USING}:
 * <ul>
 *   <li>{@link Kind#IDENTIFIER}: PostgreSQL {@code format('%I', <param>)} (runtime identifier quoting —
 *       safe from quote-breaking, still an arbitrary identifier); MySQL a runtime backtick-quote that
 *       doubles embedded backticks ({@code CONCAT('`', REPLACE(<param>, '`', '``'), '`')}) — the
 *       "catalog-validated backtick" of the contract, since MySQL has no {@code format('%I')}.</li>
 *   <li>{@link Kind#RAW_FRAGMENT}: the fragment is spliced <b>verbatim</b> ({@code %s} on PostgreSQL,
 *       a bare {@code CONCAT} operand on MySQL) — the source's own exposure faithfully reproduced (the
 *       runtime-assembled {@code EXECUTE} the contract deferred to this rung).</li>
 * </ul>
 * No value/identifier ever reaches a {@code strict}-scope routine: this node is produced ONLY for a
 * {@code permissive} scope; {@code strict} rejects the same hole with {@code TITAN-E004}.
 */
record SpliceBind(int markerId, String paramName, SpliceBind.Kind kind) {

    /** Whether the splice is a quoted identifier or a verbatim raw fragment. */
    enum Kind { IDENTIFIER, RAW_FRAGMENT }

    SpliceBind {
        if (paramName == null || kind == null) {
            throw new IllegalArgumentException("splice-bind paramName/kind must not be null");
        }
    }
}

/**
 * WS-C Phase 3 Rung 4 — one collection-bound membership test of a {@link RawSql} (§3.6 form 2). The
 * marker {@link RawSql#arrayBindMarker(int)} for {@link #markerId} sits in the SQL text where {@code
 * <lhs> IN (…)} was; the emitter expands it natively for the source dialect, binding the collection as
 * a single array (PostgreSQL) / JSON (MySQL) parameter (the parameter name is the matching-position
 * entry of {@link RawSql#parameters()}). {@link #lhs} is the membership left-hand side (the column the
 * list is matched against, recovered as the constant text just before {@code IN (}); {@link
 * #elementType} drives the element SQL type ({@code bigint}/{@code int}/{@code text} ARRAY on
 * PostgreSQL, the {@code COLUMNS} cast type on MySQL).
 */
record ArrayBind(int markerId, String lhs, TirType elementType) {
    ArrayBind {
        if (lhs == null || elementType == null) {
            throw new IllegalArgumentException("array-bind lhs/elementType must not be null");
        }
    }
}

record LateralSubquery(SelectSql subquery, String alias) implements SqlNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitLateralSubquery(this);
    }
}

record ColumnRefExpression(String table, String column) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitColumnRefExpression(this);
    }
}

record VariableRefExpression(String name) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitVariableRefExpression(this);
    }
}

record LiteralExpression(Object value, TirType type) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitLiteralExpression(this);
    }
}

record BinaryOpExpression(ExpressionNode left, BinaryOperator operator, ExpressionNode right) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitBinaryOpExpression(this);
    }
}

enum BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MODULO,
    LIKE,
    EQUAL,
    NOT_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    AND,
    OR
}

record FunctionCallExpression(String name, List<ExpressionNode> arguments, String schema) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitFunctionCallExpression(this);
    }
}

record RecordConstructExpression(String schema, String recordName, List<ExpressionNode> arguments) implements ExpressionNode {
    RecordConstructExpression(String recordName, List<ExpressionNode> arguments) {
        this(null, recordName, arguments);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRecordConstructExpression(this);
    }
}

record RecordFieldExpression(ExpressionNode record, String schema, String recordName, String fieldName) implements ExpressionNode {
    RecordFieldExpression(ExpressionNode record, String recordName, String fieldName) {
        this(record, null, recordName, fieldName);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitRecordFieldExpression(this);
    }
}

record ArrayConstructExpression(TirType elementType, List<ExpressionNode> elements) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitArrayConstructExpression(this);
    }
}

record ArrayLengthExpression(ExpressionNode array) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitArrayLengthExpression(this);
    }
}

record ArrayGetExpression(ExpressionNode array, ExpressionNode index, TirType elementType) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitArrayGetExpression(this);
    }
}

record CaseWhenExpression(List<CaseBranch> conditions, ExpressionNode elseValue) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitCaseWhenExpression(this);
    }
}

record CaseBranch(ExpressionNode condition, ExpressionNode value) {}

record SubqueryExpression(SelectSql select) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitSubqueryExpression(this);
    }
}

record IsNullExpression(ExpressionNode expression) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitIsNullExpression(this);
    }
}

record IsNotNullExpression(ExpressionNode expression) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitIsNotNullExpression(this);
    }
}

record NotExpression(ExpressionNode expression) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitNotExpression(this);
    }
}

/**
 * Explicit SQL CAST. {@code truncating} marks a Java fractional-to-integral narrowing cast
 * ({@code (int)}/{@code (long)} on a double/float/NUMERIC value): Java truncates toward zero
 * while a bare SQL CAST rounds, so the emitters compose a dialect truncation function
 * (PostgreSQL {@code TRUNC(x)}, MySQL {@code TRUNCATE(x, 0)}) inside the CAST (plan 2.2, F-9).
 */
record CastExpression(ExpressionNode expression, TirType targetType, boolean truncating) implements ExpressionNode {
    CastExpression(ExpressionNode expression, TirType targetType) {
        this(expression, targetType, false);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitCastExpression(this);
    }
}

record CoalesceExpression(List<ExpressionNode> expressions) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitCoalesceExpression(this);
    }
}

record ExistsExpression(SelectSql subquery, boolean negated) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitExistsExpression(this);
    }
}

/**
 * {@code value [NOT] IN (...)} membership predicate (audit D-7). Exactly one of {@code items}
 * (non-empty value list) or {@code subquery} (single-column SELECT) is set.
 */
record InListExpression(ExpressionNode value, List<ExpressionNode> items, SelectSql subquery, boolean negated) implements ExpressionNode {
    InListExpression(ExpressionNode value, List<ExpressionNode> items, boolean negated) {
        this(value, items, null, negated);
    }

    InListExpression(ExpressionNode value, SelectSql subquery, boolean negated) {
        this(value, List.of(), subquery, negated);
    }

    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitInListExpression(this);
    }
}

record WindowFunctionExpression(String function, List<ExpressionNode> arguments, WindowSpec spec) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitWindowFunctionExpression(this);
    }
}

/**
 * Grouping construct inside GROUP BY: {@code GROUPING SETS (...)}, {@code ROLLUP(...)},
 * {@code CUBE(...)}, or a bare parenthesized grouping set. For {@link GroupingSetKind#GROUPING_SETS}
 * each element of {@code sets} is one grouping set; for the other kinds {@code sets} holds exactly
 * one element carrying the argument expressions.
 */
record GroupingSetSpec(GroupingSetKind kind, List<List<ExpressionNode>> sets) implements ExpressionNode {
    @Override
    public <R> R accept(TirVisitor<R> visitor) {
        return visitor.visitGroupingSetSpec(this);
    }
}

enum GroupingSetKind {
    GROUPING_SETS,
    ROLLUP,
    CUBE,
    /** A bare parenthesized grouping set used directly as a GROUP BY element. */
    SET
}

record SelectColumn(ExpressionNode expression, String alias) {}

/**
 * One join of a {@link SelectSql}. Exactly one of {@code target} (a plain relation name) or
 * {@code lateralTarget} (a structured lateral subquery) is set. {@code targetAlias} carries an
 * explicit table alias on plain relation targets (audit D-7, self-joins); always {@code null}
 * for lateral targets (the alias is structured on {@link LateralSubquery#alias()}).
 */
record JoinSpec(JoinType joinType, String target, String targetAlias, LateralSubquery lateralTarget, ExpressionNode condition) {
    JoinSpec(JoinType joinType, String target, ExpressionNode condition) {
        this(joinType, target, null, null, condition);
    }

    JoinSpec(JoinType joinType, String target, String targetAlias, ExpressionNode condition) {
        this(joinType, target, targetAlias, null, condition);
    }

    JoinSpec(JoinType joinType, LateralSubquery lateralTarget, ExpressionNode condition) {
        this(joinType, null, null, lateralTarget, condition);
    }
}

enum JoinType { INNER, LEFT, RIGHT, FULL_OUTER, CROSS, LATERAL }

/**
 * One ORDER BY element. {@code nulls} is the explicit NULL placement (audit D-7); when absent
 * the dialect default applies.
 */
record OrderBySpec(ExpressionNode expression, SortDirection direction, NullsOrder nulls) {
    OrderBySpec(ExpressionNode expression, SortDirection direction) {
        this(expression, direction, null);
    }
}

enum SortDirection { ASC, DESC }
enum NullsOrder { FIRST, LAST }

record WindowSpec(List<ExpressionNode> partitionBy, List<OrderBySpec> orderBy, WindowFrame frame) {}
record WindowFrame(WindowFrameUnit unit, WindowFrameBound start, WindowFrameBound end) {}
enum WindowFrameUnit { ROWS, RANGE, GROUPS }

/** One frame boundary; {@code offset} is set only for {@code PRECEDING}/{@code FOLLOWING}. */
record WindowFrameBound(WindowFrameBoundKind kind, ExpressionNode offset) {}
enum WindowFrameBoundKind { UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING }
record LockingClause(boolean forUpdate, boolean forShare, boolean skipLocked, boolean noWait) {}
record CteSpec(String name, SelectSql query, boolean recursive) {}
record ConflictClause(List<String> columns, List<SetClause> updates) {}
record SetClause(String column, ExpressionNode value) {}
