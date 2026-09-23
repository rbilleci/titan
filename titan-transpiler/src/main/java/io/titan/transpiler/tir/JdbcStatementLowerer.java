package io.titan.transpiler.tir;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.introspect.SchemaModel;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import io.titan.transpiler.jdbc.JdbcGeneratedKeyResolver;
import io.titan.transpiler.jdbc.JdbcShapes;
import io.titan.transpiler.jdbc.JdbcTypeOracle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import io.titan.transpiler.jdbc.SqlSafetyMode;

/**
 * WS-C Phase 2 (2b) JDBC → TIR lowering, running <b>parallel</b> to {@link DslQueryLowerer} and
 * <b>gated</b> to fire only on methods whose body bears {@code java.sql} handles (the same
 * {@link JdbcTypeOracle} anchor the Phase-1 recognizer uses — see
 * {@link #methodBodyBearsJdbcHandles}). For every other method (DSL / {@code @SQL} / plain Java)
 * this class is never entered, so those lowering paths are untouched.
 *
 * <p>The shape acceptance is shared with the recognizer through {@link JdbcShapes}: the lowerer
 * never accepts a shape the recognizer rejects. It elides the {@code Connection}/
 * {@code PreparedStatement}/{@code ResultSet}/{@code Statement} handle declarations (mirroring
 * {@code StatementLowerer.isDslScaffoldingDeclaration} returning no {@code DeclareVariable}) and
 * lowers, over the configured-source-dialect's native dynamic form (opaque {@link RawSql} text,
 * {@code docs/transpilable-jdbc-subset.md} §4):</p>
 * <ul>
 *   <li><b>I-4</b> single-row read → {@link RawReadIntoStatement}; for the
 *       {@code if (!rs.next()) throw} guard, a {@link RaiseStatement} is prepended (reusing
 *       {@code StatementLowerer.lowerThrow}).</li>
 *   <li><b>I-5</b> {@code while (rs.next()) { body }} → {@link RawCursorStatement} whose {@code body}
 *       lowers through the <b>existing</b> {@code lowerStatement} switch verbatim, with each in-loop
 *       {@code rs.getX(col)} rewritten to a {@link VariableRefExpression} on the per-row local.</li>
 *   <li><b>I-6</b> {@code executeUpdate} → {@link ExecuteSqlStatement} wrapping a {@link RawSql}.</li>
 *   <li><b>I-10</b> {@code commit()}/{@code rollback()} → {@link TransactionControlStatement}
 *       carrying the recognizer's verdict (procedure → emit + mandatory outer-atomic W005;
 *       function/trigger → hard E001); {@code setAutoCommit(...)} → elided + informational W005.</li>
 * </ul>
 *
 * <p>Bindings: {@code prepareStatement} + {@code setXxx(ordinalLiteral, value)} lower each value via
 * {@link ExpressionLowerer} and populate {@link RawSql#parameters()} with per-ordinal bind variable
 * names; an expression bind synthesises a {@code v_pN := <expr>} local. The emitter's
 * {@code rewritePositionalPlaceholders} (2a) handles the {@code ?}→placeholder rewrite.</p>
 */
final class JdbcStatementLowerer {

    /** Result of a verdict carried into the I-10 lowering, decided exactly like the recognizer. */
    interface DiagnosticReporter {
        void warn(String message);

        /** A shape/usage reject (mirrors the recognizer's {@code TITAN-E001} rejects). */
        void error(String message);

        /**
         * A constant-SQL safety violation (the recognizer's {@code TITAN-E004} gate). Defaults to
         * {@link #error(String)} for reporters that do not distinguish codes.
         */
        default void sqlSafetyError(String message) {
            error(message);
        }
    }

    private final ParsedSources parsed;
    private final JdbcShapes shapes;
    private final JdbcTypeOracle oracle;
    private final boolean storedProcedure;
    /**
     * The entry point's routine kind, threaded so the §9-invariant-1 dynamic-SQL gate can decide
     * precisely on the MySQL emission shape — NOT on the coarse {@code !storedProcedure} proxy. On MySQL a
     * {@code @ScheduledJob} is emitted as a backing stored {@code PROCEDURE} called by a {@code CREATE
     * EVENT … DO CALL} ({@link MySqlEmitter#emitScheduledJob}), where dynamic SQL (PREPARE/EXECUTE) is
     * legal — so it must NOT be rejected like a {@code FUNCTION}/trigger (both of which are emitted inline
     * with no procedure backing and genuinely hit ERROR 1336). A {@code @Trigger} also yields {@code
     * !storedProcedure} but, unlike a scheduled job, IS rejected (emitted inline as {@code CREATE
     * TRIGGER}). The kind also lets the diagnostic name the actual routine (FUNCTION vs trigger) rather
     * than always saying "stored FUNCTION".
     */
    private final io.titan.transpiler.EntryPointKind entryKind;
    private final DiagnosticReporter diagnostics;
    /**
     * The per-method effective raw-SQL safety mode (resolved by {@link
     * io.titan.transpiler.jdbc.SqlSafetyResolver}, the same authority the recognizer uses), so the
     * lowerer enforces the SAME constant-SQL gate the recognizer does (no bypass). Secure-by-default:
     * {@link SqlSafetyMode#STRICT} when unspecified.
     */
    private final SqlSafetyMode safetyMode;
    /**
     * True when MySQL is (or may be) a transpile target. MySQL static cursors cannot iterate
     * runtime-built text, so a non-constant I-5 cursor is rejected at lowering when this holds
     * (decision 1). Conservatively true when the target set is unknown.
     */
    private final boolean mysqlTargeted;
    /**
     * The introspected catalog (§6.3 / I-7): used by {@link #lowerGeneratedKeyRead} to resolve the
     * {@code INSERT … RETURNING <key-col>} column (the JDBC ordinal {@code 1} does not name a column).
     * {@code null} when no catalog is available (the bare/test overloads) — I-7 then rejects cleanly
     * (I-R8) rather than guessing a key column.
     */
    private final SchemaModel schemaModel;
    /**
     * The deployment schema name (the transpile's first {@code --schema}, or {@code public}), threaded
     * to {@link JdbcGeneratedKeyResolver#resolveAutoIncrementKeyColumn} as the {@code defaultSchema} so a
     * <b>bare</b> {@code INSERT INTO <table>} resolves against the deploy schema's table, not a same-named
     * table that happens to live in a <i>different</i> schema in the model. {@code null} (the bare/test
     * overloads) preserves the prior name-only match — still fail-safe, since an unmatched/ambiguous table
     * rejects (I-R8) rather than emitting a wrong key.
     */
    private final String defaultSchema;

    // Keyed by resolved Element (same idiom as JdbcUsageRecognizer's handle map).
    /** prepareStatement / inline-SQL state per resolved statement handle local. */
    private final Map<Element, StatementState> statementHandles = new HashMap<>();
    /** ResultSet → its driving statement handle (executeQuery source). */
    private final Map<Element, Element> resultSetDriver = new HashMap<>();
    /**
     * I-7 (§6.3 / I-R8): a {@code getGeneratedKeys()} ResultSet local → the routine local its single
     * {@code getLong(1)}/{@code getInt(1)} key read resolves to. Populated when the executeUpdate
     * over a {@code RETURN_GENERATED_KEYS} statement is lowered. I-7 is rejected ({@code TITAN-E001},
     * I-R8) on the parser-free JDBC path (no Catalog to resolve the {@code RETURNING} key column), but
     * the key local is still mapped here so the trailing {@code return keys.getLong(1)} lowers to a
     * {@link VariableRefExpression} (clean ref, no misleading secondary crash) while the build fails on
     * the reject.
     */
    private final Map<Element, String> generatedKeyLocals = new HashMap<>();
    /** Statement states already run through the constant-SQL gate (so it fires at most once each). */
    private final java.util.Set<StatementState> gatedStates =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /**
     * §9 invariant 1 — once-guard for the MySQL-stored-FUNCTION dynamic-SQL reject. MySQL forbids dynamic
     * SQL (PREPARE/EXECUTE) inside a stored {@code FUNCTION}/trigger (ERROR 1336, 0A000) at CREATE time,
     * so a JDBC read/execute (which lowers to a dynamic {@code PREPARE}/{@code EXECUTE} on MySQL — see
     * {@code MySqlEmitter.visitRawSql}/{@code visitRawReadIntoStatement}) inside a MySQL {@code
     * @StoredFunction} emits non-deployable SQL. {@link #rejectIfMysqlStoredFunctionEmitsDynamicSql} fires
     * a single {@code E001} per method (the first dynamic site) so the diagnostic is the one actionable
     * error, not one per statement. PostgreSQL functions allow dynamic {@code EXECUTE}, so this is gated on
     * {@code mysqlTargeted} (mirroring the decision-1 MySQL-cursor reject).
     */
    private boolean rejectedMysqlFunctionDynamicSql;
    /** Counter for synthesised expression-bind locals (__titan_pN). */
    private int bindLocalCounter;
    /** Counter for collision-free locals introduced by compile-time JDBC binder inlining. */
    private int inlineHelperCounter;
    /** Positive only while a compile-time JDBC binder helper body is being lowered. */
    private int inlineHelperDepth;
    /** Active helper-local element names while one binder helper body is lowered at its call site. */
    private Map<Element, String> inlineVariableNames = Map.of();
    /** Counter for synthesised generated-key locals (__titan_genkeyN). */
    private int generatedKeyLocalCounter;
    /**
     * §3.6 form 1 — subquery fusion (WS-C Phase 3 Rung 2). The whole method body path, so the
     * cross-statement fusion proof ({@link io.titan.transpiler.jdbc.JdbcSubqueryFusion}) can scan every
     * use of the {@code ids} collection / prior {@code ResultSet} (a use anywhere in the method, not
     * only the current block, defeats fusion). Set once at method entry; {@code null} guards a bare
     * test path.
     */
    private TreePath methodBodyPath;
    /**
     * §3.6 form 1 — proven fusion plans keyed by query B's resolved prepare-handle element. Registered
     * by {@link #recognizeBlockFusion} when the block is entered; consumed in {@link #applyPrepare} (the
     * plan is moved onto B's {@link StatementState}) so B's consuming read/cursor/execute fuses A's SQL
     * into its IN-subquery in {@link #buildRawSql}.
     */
    private final Map<Element, io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan> pendingFusionPlans =
            new HashMap<>();
    /**
     * §3.6 form 2 — proven collection-IN array-bind plans (WS-C Phase 3 Rung 4) keyed by query B's
     * resolved prepare-handle element. Registered by {@link #recognizeBlockCollectionIn} when the block
     * is entered; consumed in {@link #recordAndElideHandleDeclaration} (moved onto B's {@link
     * StatementState}) so B's consuming read/cursor/execute binds the whole collection as ONE array/JSON
     * parameter (PG {@code = ANY($n)} / MySQL {@code JSON_TABLE}) in {@link #buildRawSql}.
     */
    private final Map<Element, io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan>
            pendingCollectionInPlans = new HashMap<>();
    /**
     * Design contract D4 — proven optional-filter guarded-predicate plans keyed by query B's resolved
     * prepare-handle element. Registered by {@link #recognizeBlockGuardedPredicate} when the block is
     * entered; consumed in {@link #recordAndElideHandleDeclaration} (moved onto B's {@link StatementState})
     * so B's consuming read/cursor/execute emits ONE static guarded-predicate query (the base predicate
     * AND one {@code (? IS NULL OR col OP ?)} per optional clause, all values bound) in {@link
     * #buildRawSql}, instead of B's runtime-built {@code sql.toString()} text.
     */
    private final Map<Element, io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan>
            pendingGuardedPredicatePlans = new HashMap<>();
    /**
     * WS-C Phase 3 Rung 5 — the proven unknown-shape carrier plan for the whole method (the pure
     * metadata-driven generic reader, {@link io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer}).
     * Set once at method entry when the recognizer matches; {@code null} otherwise. When non-null the
     * method-body lowering elides the {@code rows}/{@code md}/{@code return rows} marshalling scaffolding
     * and replaces the {@code while (rs.next())} loop with a single {@link DynamicResultStatement} over
     * the recovered SQL — the carrier returns exactly the same data the {@code List<Map>} would.
     */
    private io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer.CarrierPlan carrierPlan;

    private JdbcStatementLowerer(
            ParsedSources parsed,
            boolean storedProcedure,
            io.titan.transpiler.EntryPointKind entryKind,
            DiagnosticReporter diagnostics,
            SqlSafetyMode safetyMode,
            boolean mysqlTargeted,
            SchemaModel schemaModel,
            String defaultSchema
    ) {
        this.parsed = parsed;
        this.oracle = new JdbcTypeOracle(parsed);
        this.shapes = new JdbcShapes(parsed, oracle);
        this.storedProcedure = storedProcedure;
        this.entryKind = entryKind;
        this.diagnostics = diagnostics == null ? NO_OP : diagnostics;
        this.safetyMode = safetyMode == null ? SqlSafetyMode.STRICT : safetyMode;
        this.mysqlTargeted = mysqlTargeted;
        this.schemaModel = schemaModel;
        this.defaultSchema = defaultSchema;
    }

    private static final DiagnosticReporter NO_OP = new DiagnosticReporter() {
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
    };

    /**
     * Whether {@code methodBody} (a method's {@code BlockTree} path) declares — anywhere, at any
     * nesting — a local or resource of a {@code java.sql}/{@code javax.sql} handle type. This is the
     * gate: only such methods are routed through the JDBC lowerer.
     */
    static boolean methodBodyBearsJdbcHandles(TreePath methodBodyPath, ParsedSources parsed) {
        JdbcTypeOracle oracle = new JdbcTypeOracle(parsed);
        boolean[] found = {false};
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (!found[0]) {
                    TypeMirror type = parsed.trees().getTypeMirror(getCurrentPath());
                    if (type != null && oracle.isJdbcHandle(type)) {
                        found[0] = true;
                    }
                }
                return super.visitVariable(node, unused);
            }
        }.scan(methodBodyPath, null);
        return found[0];
    }

    /**
     * Lowers a JDBC-bearing method body. Mirrors {@code StatementLowerer.lowerBlock} but threads the
     * binding state and elides/transforms the JDBC scaffolding into the 2a nodes.
     */
    static Block lowerMethodBody(
            TreePath methodBodyPath,
            ParsedSources parsed,
            boolean storedProcedure,
            io.titan.transpiler.EntryPointKind entryKind,
            DiagnosticReporter diagnostics,
            SqlSafetyMode safetyMode,
            boolean mysqlTargeted,
            SchemaModel schemaModel,
            String defaultSchema
    ) {
        return new JdbcStatementLowerer(
                parsed, storedProcedure, entryKind, diagnostics, safetyMode, mysqlTargeted, schemaModel,
                defaultSchema)
                .lowerMethodBodyInternal(methodBodyPath);
    }

    /**
     * Lowers the whole method body with the I-7 (§6.3) method-level generated-key read resolver
     * installed for its duration. The supported gen-key read is a single {@code getLong(1)}/{@code
     * getInt(1)} over the {@code getGeneratedKeys()} ResultSet; this resolver maps it through the
     * ordinary {@link ExpressionLowerer} to a {@link VariableRefExpression} on the key local the INSERT's
     * {@link #lowerGeneratedKeyRead} allocated — so {@code return keys.getLong(1)} returns the recovered
     * key. The I-5 cursor body's own resolver swap correctly saves and restores this one (gen-key reads
     * never occur inside a cursor body). An unsupported gen-key read shape ({@code getString(1)}, a non-1
     * ordinal, a second key read, …) is rejected I-R8 here, matching the recognizer (no silent
     * recognizer-transpilable / lowerer-no-op gap for any gen-key read shape); it still resolves to the
     * key local so the build fails on the single actionable I-R8 error, not a secondary lowering crash.
     */
    private Block lowerMethodBodyInternal(TreePath methodBodyPath) {
        ExpressionLowerer.JdbcRowReadResolver generatedKeyResolver = (invocation, sources) -> {
            Element receiver = generatedKeyReadReceiver(invocation, sources);
            if (receiver == null || !generatedKeyLocals.containsKey(receiver)) {
                return null;
            }
            if (!JdbcShapes.isSingleIntKeyRead(invocation)) {
                diagnostics.error("generated-key recovery is limited to a single auto-increment/identity "
                        + "integer key read as a single getLong(1)/getInt(1); other generated keys "
                        + "(UUID/sequence/composite, a non-1 ordinal, a non-integer accessor, or more than "
                        + "one key read) must be modeled as an explicit RETURNING/OUT read "
                        + "(docs/transpilable-jdbc-subset.md §5 I-R8)");
            }
            return generatedKeyLocals.get(receiver);
        };
        this.methodBodyPath = methodBodyPath;
        // WS-C Phase 3 Rung 5 — unknown-shape result carrier (design contract D5). Recognize the PURE
        // metadata-driven generic reader at method-body level: a while(rs.next()) loop whose body only
        // marshals every column (md.getColumnCount()/getColumnLabel/rs.getObject(i)) into a per-row Map
        // added to a returned List<Map>. When it matches, the whole method lowers to ONE carrier
        // (DynamicResultStatement) and the rows/md/return scaffolding is elided (handled in lowerBlock).
        recognizeCarrier(methodBodyPath);
        // THE TIER-3/TIER-4 FAIL-SAFE: if the body does generic ResultSetMetaData processing but is NOT
        // the pure marshalling carrier (it computes/branches on a column, filters rows, builds a typed
        // DTO, accumulates, ...), there is NO server-side form. Reject with a clear Tier-4 diagnostic
        // rather than letting normal lowering crash on the ResultSetMetaData local with a misleading
        // "no SQL type mapping" error.
        if (carrierPlan == null && bodyDoesGenericMetadataProcessing(methodBodyPath)) {
            diagnostics.error("generic ResultSet processing beyond row marshalling cannot be transpiled; "
                    + "a metadata-driven reader (ResultSetMetaData + rs.getObject(i)) is transpilable only when "
                    + "its loop body does NOTHING but copy every column into a per-row Map<String,Object> and add "
                    + "it to a returned List<Map<String,Object>> (the result is returned as a carrier — PostgreSQL "
                    + "jsonb / MySQL result set). Any business logic on the unknown-shape rows (computing or "
                    + "branching on a column value, filtering rows in Java, calling a method per row/column, "
                    + "building typed DTOs, accumulating a value) has no server-side form. Read fixed columns with "
                    + "typed rs.getX(col) calls, or move the per-row logic into the SQL "
                    + "(docs/transpilable-jdbc-subset.md §3.6)");
            // Stop here: the body has an unsupported List<Map>/ResultSetMetaData shape that ordinary
            // lowering would crash on with a misleading "no SQL type mapping for Map" secondary error.
            // The recorded Tier-4 diagnostic fails the build; return an empty body so it is the SINGLE,
            // actionable error (no secondary lowering crash drowning it out).
            return new Block(List.of(), List.of(), List.of());
        }
        ExpressionLowerer.JdbcRowReadResolver previous = ExpressionLowerer.swapJdbcRowReads(generatedKeyResolver);
        try {
            return lowerBlock(methodBodyPath);
        } finally {
            ExpressionLowerer.swapJdbcRowReads(previous);
        }
    }

    /**
     * WS-C Phase 3 Rung 5 — runs the unknown-shape carrier recognizer over the whole method body and, on
     * a match, records the proven {@link io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer.CarrierPlan}
     * on {@link #carrierPlan}. Disabled on a bare test path ({@code methodBodyPath == null}). When no
     * carrier is provable, nothing is recorded and the existing lowering is unchanged.
     */
    private void recognizeCarrier(TreePath methodBodyPath) {
        if (methodBodyPath == null || !(methodBodyPath.getLeaf() instanceof BlockTree methodBody)) {
            return;
        }
        carrierPlan = new io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer(parsed, shapes, oracle)
                .recognize(methodBody, methodBodyPath)
                .orElse(null);
    }

    /**
     * Whether the method body does <b>generic, metadata-driven</b> ResultSet processing — it both reads
     * {@code ResultSetMetaData} (a {@code rs.getMetaData()}/{@code md.getColumnCount()}/{@code
     * getColumnLabel} call) AND reads columns shape-agnostically via {@code rs.getObject(<index>)}. This
     * is the Tier-3/Tier-4 trigger: such a method is the carrier shape if (and only if) the recognizer
     * proved it pure; if it bears these signals but was NOT recognized, it does business logic on the
     * unknown-shape rows (Tier-4, no server-side form) and must be rejected with a clear diagnostic — not
     * silently mis-lowered. A method with neither signal is an ordinary typed JDBC read (untouched).
     */
    private boolean bodyDoesGenericMetadataProcessing(TreePath methodBodyPath) {
        if (!(methodBodyPath.getLeaf() instanceof BlockTree)) {
            return false;
        }
        boolean[] usesMetaData = {false};
        boolean[] usesGetObject = {false};
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree select) {
                    String name = select.getIdentifier().toString();
                    TypeMirror receiverType = shapes.typeOf(select.getExpression(), getCurrentPath());
                    if (receiverType != null && oracle.isResultSetMetaData(receiverType)) {
                        usesMetaData[0] = true;
                    }
                    if (receiverType != null && oracle.isResultSet(receiverType)
                            && name.equals("getMetaData")) {
                        usesMetaData[0] = true;
                    }
                    // rs.getX(<non-constant index>) — a column read addressed by a RUNTIME index (the
                    // metadata-driven loop counter), the hallmark of generic processing. This covers the
                    // pure-marshalling rs.getObject(i) AND a typed transform like rs.getString(i)... — both
                    // are metadata-driven. A read by a CONSTANT column name/ordinal (rs.getLong("id") /
                    // rs.getObject(1)) is a typed fixed-shape read, NOT generic — require a non-literal index.
                    if (receiverType != null && oracle.isResultSet(receiverType)
                            && name.length() > 3 && name.startsWith("get") && node.getArguments().size() == 1
                            && !(JdbcShapes.unwrap(node.getArguments().getFirst()) instanceof LiteralTree)) {
                        usesGetObject[0] = true;
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(methodBodyPath, null);
        return usesMetaData[0] && usesGetObject[0];
    }

    /** The ResultSet receiver element of a {@code keys.getX(...)} invocation, or null. */
    private Element generatedKeyReadReceiver(MethodInvocationTree invocation, ParsedSources sources) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return null;
        }
        TreePath invocationPath = LowererSupport.resolveTreePath(sources, invocation);
        if (invocationPath == null) {
            return null;
        }
        return shapes.elementOf(select.getExpression(), invocationPath.getParentPath());
    }

    // ---- block lowering with multi-statement consumption (for the guard+reads shape) ----

    private Block lowerBlock(TreePath blockPath) {
        BlockTree block = (BlockTree) blockPath.getLeaf();
        List<DeclarationNode> declarations = new ArrayList<>();
        List<StatementNode> statements = new ArrayList<>();
        List<? extends StatementTree> body = block.getStatements();

        // §3.6 form 1 — subquery fusion (WS-C Phase 3 Rung 2). Before lowering, look for a proven
        // A→ids→B fusion in this block: a prior query A whose ResultSet is read in a while-loop into a
        // single-column collection `ids` that is consumed ONLY as a later query B's IN-source. When the
        // cross-statement dataflow proof holds, A's prepare/execute/loop and the `ids` declaration are
        // elided (added to fusionElidedIndices) and B's statement handle carries the FusionPlan so its
        // RawSql becomes the fused `… IN (<A_sql>)` (built in buildRawSql, binds merged in text order).
        // When no proof holds, nothing here fires and the existing Rung-1/I-5/gate behavior is unchanged.
        java.util.Set<Integer> fusionElidedIndices = recognizeBlockFusion(body, blockPath);

        // §3.6 form 2 — collection binding (WS-C Phase 3 Rung 4). Before lowering, look for a runtime-sized
        // List-driven IN whose collection is consumed ONLY as that one IN-source: a later query B's IN-run
        // sized by coll.size() and bound per-element by coll.get(i) (or the setArray-over-collection form).
        // When the use proof holds, B's statement handle carries the CollectionInPlan so its RawSql binds
        // the WHOLE collection as one array/JSON parameter (PG = ANY($n) / MySQL JSON_TABLE) in
        // buildRawSql, and the per-element bind loop / setArray is elided. No proof -> nothing fires.
        java.util.Set<Integer> collectionInElidedIndices = recognizeBlockCollectionIn(body, blockPath);

        // Design contract D4 — optional-filter guarded predicates. Before lowering, look for a constant
        // base query extended by a finite sequence of `if (param != null) sql.append(" AND col OP ?")`
        // optional clauses, each correlated to a `if (param != null) ps.setXxx(…, param)` bind on the SAME
        // param. When the control-flow / bind-correlation proof holds, B's statement handle carries the
        // GuardedPredicatePlan so its RawSql becomes ONE STATIC query (base AND each `(? IS NULL OR col OP
        // ?)`, all values bound — no dynamic EXECUTE) in buildRawSql, and the StringBuilder declaration,
        // every append `if`, the bind counter, and every bind `if` are elided. No proof -> nothing fires.
        java.util.Set<Integer> guardedPredicateElidedIndices = recognizeBlockGuardedPredicate(body, blockPath);

        // WS-C Phase 3 Rung 5 — unknown-shape carrier. When the whole method is a proven pure
        // metadata-driven reader, the `rows` accumulator declaration and the trailing `return rows` are
        // ELIDED (the carrier IS the return), and the marshalling `while (rs.next())` loop is replaced by
        // a single DynamicResultStatement over the recovered SQL (handled in lowerStatementAt). The `md`
        // declaration elides like any JDBC handle. Only the carrier's own top-level statements (by tree
        // identity) are elided here — the prepare/setXxx statements still lower so the carrier's RawSql
        // binds are recorded. Empty unless this block is the carrier's method body.
        java.util.Set<StatementTree> carrierElided = carrierElidedStatements(block);

        int i = 0;
        while (i < body.size()) {
            if (fusionElidedIndices.contains(i)) {
                i++; // A's prepare/execute/accumulation loop, or the `ids` declaration — elided by fusion.
                continue;
            }
            if (collectionInElidedIndices.contains(i)) {
                i++; // the per-element bind loop / setArray a proven collection-IN array-bind elides.
                continue;
            }
            if (guardedPredicateElidedIndices.contains(i)) {
                i++; // the StringBuilder decl / append `if`s / bind counter / bind `if`s a guarded predicate elides.
                continue;
            }
            if (carrierElided.contains(body.get(i))) {
                i++; // the carrier's `rows` accumulator declaration / `return rows` — the carrier replaces them.
                continue;
            }
            i = lowerStatementAt(body, i, blockPath, declarations, statements);
        }
        return new Block(List.copyOf(declarations), List.copyOf(statements), List.of());
    }

    /**
     * Runs the §3.6-form-1 cross-statement fusion recognizer over {@code body} (this block's
     * statements). When a fusion is proven, registers the {@link
     * io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan} on query B's statement-handle state (so
     * {@link #buildRawSql} fuses A into B's IN-subquery) and returns the set of statement indices to
     * elide (A's collection declaration, prepare, ResultSet, and the accumulation loop). Returns an
     * empty set when no fusion applies — leaving the block lowering completely unchanged. {@code
     * methodBodyPath == null} (a bare test path) disables fusion (no proof scope), so the existing
     * behavior holds.
     */
    private java.util.Set<Integer> recognizeBlockFusion(
            List<? extends StatementTree> body, TreePath blockPath) {
        if (methodBodyPath == null) {
            return java.util.Set.of();
        }
        java.util.Optional<io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan> plan =
                new io.titan.transpiler.jdbc.JdbcSubqueryFusion(parsed, shapes, oracle)
                        .recognize(body, blockPath, methodBodyPath);
        if (plan.isEmpty()) {
            return java.util.Set.of();
        }
        io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan fusion = plan.get();
        // Register the plan on B's statement-handle state. B's prepare declaration (the consuming
        // statement, a PreparedStatement local) is lowered through recordAndElideHandleDeclaration as
        // usual; applyPrepare then sees the registered plan and carries it on the state so the consuming
        // read/cursor/execute fuses. Keyed by the resolved handle element of B's prepare declaration.
        StatementTree consuming = fusion.consumingStatement();
        if (consuming instanceof VariableTree variable) {
            Element handle = parsed.trees().getElement(new TreePath(blockPath, variable));
            if (handle != null) {
                pendingFusionPlans.put(handle, fusion);
            }
        }
        return java.util.Set.of(
                fusion.collectionDeclIndex(),
                fusion.priorPrepareIndex(),
                fusion.priorResultSetIndex(),
                fusion.accumulationLoopIndex());
    }

    /**
     * §3.6 form 2 — runs the collection-IN array-bind recognizer over {@code body}. When a collection-IN
     * is proven, registers the {@link io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan}
     * on query B's statement-handle state (so {@link #buildRawSql} binds the whole collection as one
     * array/JSON parameter) and returns the set of statement indices to elide (the per-element bind loop /
     * setArray). Returns an empty set when no collection-IN applies — block lowering unchanged. {@code
     * methodBodyPath == null} (a bare test path) disables it (no proof scope), so the existing behavior
     * holds. (Fusion is recognized first; the two never overlap — fusion needs a prior query A read into
     * the collection in this block, collection-IN binds an already-materialized collection.)
     */
    private java.util.Set<Integer> recognizeBlockCollectionIn(
            List<? extends StatementTree> body, TreePath blockPath) {
        if (methodBodyPath == null) {
            return java.util.Set.of();
        }
        java.util.Optional<io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan> plan =
                new io.titan.transpiler.jdbc.JdbcCollectionInRecognizer(parsed, shapes, oracle)
                        .recognize(body, blockPath, methodBodyPath);
        if (plan.isEmpty()) {
            return java.util.Set.of();
        }
        io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan collectionIn = plan.get();
        StatementTree consuming = collectionIn.consumingStatement();
        if (consuming instanceof VariableTree variable) {
            Element handle = parsed.trees().getElement(new TreePath(blockPath, variable));
            if (handle != null) {
                pendingCollectionInPlans.put(handle, collectionIn);
            }
        }
        return java.util.Set.copyOf(collectionIn.elidedIndices());
    }

    /**
     * Design contract D4 — runs the optional-filter guarded-predicate recognizer over {@code body}. When a
     * guarded-predicate search builder is proven, registers the {@link
     * io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan} on query B's
     * statement-handle state (so {@link #buildRawSql} emits ONE static guarded-predicate query) and
     * returns the set of statement indices to elide (the {@code StringBuilder} declaration, each append
     * {@code if}, each trailing unconditional {@code ORDER BY}/{@code GROUP BY}/… + {@code LIMIT ?}/… tail
     * append, the bind counter, each correlated clause bind {@code if}, and each unconditional pagination
     * bind). Returns an empty set when no guarded-predicate builder applies — block lowering unchanged.
     * {@code methodBodyPath == null} (a bare
     * test path) disables it (no proof scope). (Fusion and collection-IN are recognized first; the three
     * never overlap — fusion/collection-IN drive an {@code IN}-list, the guarded predicate drives a
     * sequence of optional comparison clauses on null-checked params.)
     */
    private java.util.Set<Integer> recognizeBlockGuardedPredicate(
            List<? extends StatementTree> body, TreePath blockPath) {
        if (methodBodyPath == null) {
            return java.util.Set.of();
        }
        java.util.Optional<io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan> plan =
                new io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer(parsed, shapes, oracle)
                        .recognize(body, blockPath, methodBodyPath);
        if (plan.isEmpty()) {
            return java.util.Set.of();
        }
        io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan guarded = plan.get();
        StatementTree consuming = guarded.consumingStatement();
        if (consuming instanceof VariableTree variable) {
            Element handle = parsed.trees().getElement(new TreePath(blockPath, variable));
            if (handle != null) {
                pendingGuardedPredicatePlans.put(handle, guarded);
            }
        }
        java.util.Set<Integer> elided = new java.util.HashSet<>();
        elided.add(guarded.builderDeclIndex());
        elided.addAll(guarded.appendStatementIndices());
        elided.addAll(guarded.tailStatementIndices()); // the trailing unconditional ORDER BY/GROUP BY/LIMIT ? appends.
        if (guarded.bindCounterDeclIndex() >= 0) {
            elided.add(guarded.bindCounterDeclIndex());
        }
        elided.addAll(guarded.bindStatementIndices());
        elided.addAll(guarded.tailBindStatementIndices()); // the unconditional pagination binds.
        return elided;
    }

    /**
     * WS-C Phase 3 Rung 5 — the carrier's own top-level statements to ELIDE when lowering its method
     * body: the {@code List<Map> rows} accumulator declaration and the {@code return rows} (the carrier
     * IS the return, so neither is emitted). The {@code while} loop is NOT elided here — it is replaced
     * in-place by a {@link DynamicResultStatement} (see {@link #lowerStatementAt}); the {@code md}
     * declaration elides via the JDBC-handle path. Empty unless {@code block} is exactly the carrier's
     * method body (matched by tree identity, so a nested block is never affected).
     */
    private java.util.Set<StatementTree> carrierElidedStatements(BlockTree block) {
        if (carrierPlan == null
                || methodBodyPath == null
                || !(methodBodyPath.getLeaf() instanceof BlockTree methodBody)
                || methodBody != block) {
            return java.util.Set.of();
        }
        java.util.Set<StatementTree> elided =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        elided.add(carrierPlan.rowsDeclaration());
        elided.add(carrierPlan.returnStatement());
        return elided;
    }

    /** Whether {@code statement} is the carrier's marshalling {@code while (rs.next())} loop (by identity). */
    private boolean isCarrierWhileLoop(StatementTree statement) {
        return carrierPlan != null && carrierPlan.whileLoop() == statement;
    }

    /**
     * WS-C Phase 3 Rung 5 — replaces the carrier's marshalling {@code while (rs.next())} loop with a
     * single {@link DynamicResultStatement} over the recovered SQL. The driver statement-state (the
     * prepare + setXxx binds) was recorded by the earlier {@link #lowerStatementAt} passes, so {@link
     * #buildRawSql} produces the carrier's query with its {@code ?} binds (constant under strict, or a
     * permissive value-splice/skeleton — the SQL-text safety is unchanged). The MySQL non-constant-cursor
     * E001 (decision 1) does NOT apply: the carrier is NOT a static cursor, it is a single dynamic
     * PREPARE/EXECUTE whose result set streams — so a non-constant carrier SQL is fine on MySQL.
     */
    private void lowerCarrierLoop(
            WhileLoopTree whileTree,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        Element resultSet = shapes.resultSetNextReceiver(JdbcShapes.unwrap(whileTree.getCondition()), path);
        StatementState driver = driverStateFor(resultSet);
        RawSql query = stripCarrierTrailingSemicolon(buildRawSql(driver, declarations, statements));
        statements.add(new DynamicResultStatement(query));
    }

    /**
     * Strips a single trailing {@code ;} from the carrier's SQL text so it can be wrapped as a
     * derived-table source without a syntax error. WS-C Phase 3 Rung 5 audit fix (correctness, high): the
     * PostgreSQL carrier embeds the SELECT as {@code FROM (<sql>) t}; a legal JDBC trailing {@code ;}
     * ({@code "SELECT … ORDER BY id;"}) would assemble to {@code FROM (SELECT … ORDER BY id;) t}, which
     * CREATEs fine but throws {@code syntax error at or near ";"} at CALL time on PostgreSQL. (MySQL's
     * PREPARE tolerates it, but stripping here keeps both dialects identical.) Mirrors the {@code
     * stripTrailingSemicolon} the INSERT/single-row paths already apply ({@code PostgreSqlEmitter},
     * {@code MySqlEmitter}). Applied to the constant text only — a permissive splice/array marker never
     * ends in a bare {@code ;} (the runtime hole is the last token), and stripping touches a trailing
     * literal {@code ;} only, so a marker-terminated text is left untouched.
     */
    private static RawSql stripCarrierTrailingSemicolon(RawSql query) {
        String sql = query.sql();
        if (sql == null) {
            return query;
        }
        String trimmed = sql.stripTrailing();
        if (!trimmed.endsWith(";")) {
            return query;
        }
        String stripped = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        return new RawSql(stripped, query.parameters(), query.dialect(),
                query.arrayBinds(), query.spliceBinds());
    }

    /**
     * Lowers the statement at {@code index} (possibly consuming following sibling statements, e.g. the
     * reads after an {@code if (!rs.next()) throw} guard) and returns the next index to process.
     */
    private int lowerStatementAt(
            List<? extends StatementTree> body,
            int index,
            TreePath blockPath,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        StatementTree statement = body.get(index);
        TreePath path = new TreePath(blockPath, statement);

        if (statement.getKind() == Tree.Kind.EMPTY_STATEMENT) {
            return index + 1;
        }

        // (1) Handle declarations: record state + elide (no DeclareVariable), mirroring DSL scaffolding.
        if (statement instanceof VariableTree variable) {
            if (recordAndElideHandleDeclaration(variable, path)) {
                return index + 1;
            }
            // A plain (non-handle) local that is *initialised by* a JDBC read is the
            // `Type x = rs.getX(col)` read form; handled by the I-4 read collector. Standalone
            // declarations otherwise lower through the stock path.
            lowerOrdinaryVariable(variable, path, declarations, statements);
            return index + 1;
        }

        // (2) JDBC expression statements: setXxx binds (elided), executeUpdate (I-6), commit/rollback,
        //     setAutoCommit, bare rs.next() / getGeneratedKeys trailer (elided).
        if (statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree invocation) {
            if (lowerInlineJdbcBinderHelper(invocation, declarations, statements)) {
                return index + 1;
            }
            JdbcDisposition disposition = lowerJdbcInvocation(invocation, path, declarations, statements);
            if (disposition == JdbcDisposition.HANDLED) {
                return index + 1;
            }
            if (disposition == JdbcDisposition.NOT_JDBC) {
                statements.add(StatementLowerer.lowerStatement(statement, path, parsed));
                return index + 1;
            }
        }

        // (3) I-4 single-row read shapes.
        if (statement instanceof IfTree ifTree) {
            JdbcShapes.SingleRowShape shape = shapes.classifyIfShape(ifTree, path);
            if (shape == JdbcShapes.SingleRowShape.THEN_BLOCK_READ) {
                lowerThenBlockRead(ifTree, path, declarations, statements);
                return index + 1;
            }
            if (shape == JdbcShapes.SingleRowShape.GUARD_THROW) {
                return lowerGuardThrowRead(ifTree, path, body, index, blockPath, declarations, statements);
            }
            if (shape == JdbcShapes.SingleRowShape.REJECTED) {
                // An enumerated unsupported single-row shape (else-branch on rs.next(), non-throwing
                // guard, ...). Mirror the recognizer's I-4 E001 (JdbcUsageRecognizer.recognizeIf) rather
                // than delegating an un-lowerable rs.next()/getX to the stock path, which would crash
                // with a misleading 'Method next on ... ResultSet has no SQL lowering' (WS-C Phase 2b
                // audit: recognizer/lowerer divergence).
                diagnostics.error("unsupported single-row ResultSet shape: only if (rs.next()) { ... } and the "
                        + "early-return guard if (!rs.next()) { throw ...; } are recognized in v1 (an else-branch "
                        + "on rs.next() or a non-throwing guard is rejected) "
                        + "(docs/transpilable-jdbc-subset.md §3.3)");
                return index + 1;
            }
            // NOT_JDBC: an ordinary if — lower through the stock path.
        }

        // (4) I-5 multi-row cursor read.
        if (statement instanceof WhileLoopTree whileTree
                && shapes.isResultSetNextCall(JdbcShapes.unwrap(whileTree.getCondition()), path)
                && !shapes.bodyUnconditionallyBreaks(whileTree.getStatement())) {
            // WS-C Phase 3 Rung 5: when this is the proven unknown-shape carrier's marshalling loop, emit
            // a single DynamicResultStatement (PG jsonb / MySQL open result set) instead of a typed
            // RawCursorStatement — the row shape is metadata-driven, so there are no typed FETCH targets.
            if (isCarrierWhileLoop(statement)) {
                lowerCarrierLoop(whileTree, path, declarations, statements);
                return index + 1;
            }
            lowerCursorLoop(whileTree, path, declarations, statements);
            return index + 1;
        }

        // A request interpreter can execute a schema-selected write repeatedly (for example,
        // serial mutation root fields).  Its loop is ordinary control flow, but its body may own a
        // PreparedStatement.  Recurse through this lowerer just as for an ordinary conditional so
        // the handle declaration and executeUpdate are recognized before stock lowering sees them.
        if (statement instanceof WhileLoopTree whileTree) {
            statements.add(new WhileStatement(
                    ExpressionLowerer.lowerExpression(whileTree.getCondition(), parsed),
                    lowerJdbcAsBlock(new TreePath(path, whileTree.getStatement())),
                    null));
            return index + 1;
        }

        // (5) try-with-resources holding JDBC handles: recurse the try body through the JDBC lowerer so
        //     inner reads/binds are recognised; the resource close() is elided (no cursor token).
        if (statement instanceof TryTree tryTree && tryHoldsJdbcResource(tryTree, path)) {
            lowerJdbcTry(tryTree, path, declarations, statements);
            return index + 1;
        }

        // A query engine necessarily chooses SQL work after it has parsed the incoming request.
        // Do not hand an ordinary conditional back to StatementLowerer when one of its branches
        // contains JDBC handles: that lowerer maps PreparedStatement/ResultSet as ordinary Java
        // locals and fails before their recognised JDBC shapes can run. Recurse through this
        // lowerer instead, preserving the same statement/bind state within each branch.
        if (statement instanceof IfTree ifTree) {
            statements.add(lowerJdbcIf(ifTree, path));
            return index + 1;
        }

        // (6) Everything else: the existing control-flow / DSL / plain-Java lowering, verbatim.
        statements.add(StatementLowerer.lowerStatement(statement, path, parsed));
        return index + 1;
    }

    private IfStatement lowerJdbcIf(IfTree ifTree, TreePath ifPath) {
        ExpressionNode condition = ExpressionLowerer.lowerExpression(ifTree.getCondition(), parsed);
        Block thenBlock = lowerJdbcAsBlock(new TreePath(ifPath, ifTree.getThenStatement()));
        StatementTree elseStatement = ifTree.getElseStatement();
        Block elseBlock = elseStatement == null ? null
                : lowerJdbcAsBlock(new TreePath(ifPath, elseStatement));
        return new IfStatement(condition, thenBlock, List.of(), elseBlock);
    }

    private Block lowerJdbcAsBlock(TreePath statementPath) {
        if (statementPath.getLeaf() instanceof BlockTree) {
            return lowerBlock(statementPath);
        }
        StatementTree statement = (StatementTree) statementPath.getLeaf();
        if (statement instanceof IfTree ifTree) {
            return new Block(List.of(), List.of(lowerJdbcIf(ifTree, statementPath)), List.of());
        }
        return new Block(List.of(), List.of(StatementLowerer.lowerStatement(statement, statementPath, parsed)), List.of());
    }

    // ---- (1) handle declarations ----

    private boolean recordAndElideHandleDeclaration(VariableTree variable, TreePath path) {
        TypeMirror type = parsed.trees().getTypeMirror(path);
        JdbcShapes.HandleKind kind = shapes.classifyHandle(type);
        if (kind == null) {
            return false;
        }
        Element local = parsed.trees().getElement(path);
        switch (kind) {
            case PREPARED_STATEMENT, CALLABLE_STATEMENT -> {
                StatementState state = new StatementState();
                applyPrepare(variable.getInitializer(), state);
                // §3.6 form 1: if this handle is query B of a proven fusion, carry the plan onto its
                // state so its consuming read/cursor/execute fuses A into the IN-subquery (buildRawSql).
                // §3.6 form 2: likewise carry a proven collection-IN array-bind plan so the consuming
                // statement binds the whole collection as one array/JSON parameter (buildRawSql).
                if (local != null) {
                    state.fusionPlan = pendingFusionPlans.get(local);
                    state.collectionInPlan = pendingCollectionInPlans.get(local);
                    // D4: if this handle is query B of a proven optional-filter guarded predicate, carry the
                    // plan so its consuming read/cursor/execute emits the single static guarded-predicate query.
                    state.guardedPredicatePlan = pendingGuardedPredicatePlans.get(local);
                    statementHandles.put(local, state);
                }
            }
            case PLAIN_STATEMENT -> {
                if (local != null) {
                    statementHandles.put(local, new StatementState());
                }
            }
            case RESULT_SET -> linkResultSet(variable.getInitializer(), local, path);
            case RESULT_SET_METADATA -> {
                // WS-C Phase 3 Rung 5: a `ResultSetMetaData md = rs.getMetaData()` local — pure anchor for
                // the generic-reader column walk, elided like Connection (no value, no state). It only
                // appears in the recognized carrier (its column walk is subsumed by the carrier lowering);
                // outside the carrier the recognizer rejects (the Tier-4 fail-safe in lowerMethodBodyInternal).
            }
            case CONNECTION -> {
                // Connection/DataSource local: pure anchor, elided, no state.
            }
        }
        return true;
    }

    private void applyPrepare(ExpressionTree initializer, StatementState state) {
        ExpressionTree expr = JdbcShapes.unwrap(initializer);
        if (expr instanceof MethodInvocationTree invocation) {
            List<? extends ExpressionTree> args = invocation.getArguments();
            if (!args.isEmpty()) {
                state.sqlArg = args.getFirst();
            }
            // I-7 (§6.3): capture prepareStatement(SQL, RETURN_GENERATED_KEYS) so the executeUpdate
            // over this statement reaches the I-7 / I-R8 reject (the key column is not resolvable from
            // the Catalog on the parser-free JDBC path; see lowerGeneratedKeyRead).
            if (args.size() >= 2 && JdbcShapes.isReturnGeneratedKeys(args.get(1))) {
                state.returnGeneratedKeys = true;
            }
        }
    }

    private void linkResultSet(ExpressionTree initializer, Element resultSetLocal, TreePath path) {
        if (resultSetLocal == null) {
            return;
        }
        ExpressionTree expr = JdbcShapes.unwrap(initializer);
        if (expr instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select) {
            Element driver = shapes.elementOf(select.getExpression(), path);
            if (driver != null) {
                resultSetDriver.put(resultSetLocal, driver);
                // I-7 (§6.3 / I-R8): ResultSet keys = ps.getGeneratedKeys(); links the gen-key
                // ResultSet to the key local the driving INSERT's executeUpdate allocated, so the
                // trailing keys.getLong(1)/getInt(1) resolves to it. I-7 is rejected (I-R8) at the
                // executeUpdate, but the link keeps the trailing read from crashing the stock lowerer.
                if (select.getIdentifier().contentEquals("getGeneratedKeys")) {
                    StatementState driverState = statementHandles.get(driver);
                    if (driverState != null && driverState.generatedKeyLocal != null) {
                        generatedKeyLocals.put(resultSetLocal, driverState.generatedKeyLocal);
                    }
                }
            }
        }
    }

    private void lowerOrdinaryVariable(
            VariableTree variable,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        // A non-handle declaration that is NOT a JDBC read: lower exactly like the stock block path
        // (DECLARE + optional Assign). Reads (Type x = rs.getX(col)) are consumed by the I-4 collectors
        // before reaching here, so this is for ordinary locals (accumulators, computed values, ...).
        TypeMirror resolvedType = parsed.trees().getTypeMirror(path);
        TirType type = LowererSupport.mapType(resolvedType, "variable '" + variable.getName() + "'");
        Element variableElement = parsed.trees().getElement(path);
        String name = inlineVariableNames.getOrDefault(variableElement, variable.getName().toString());
        ExpressionNode initializer = variable.getInitializer() == null
                ? null
                : ExpressionLowerer.lowerExpression(variable.getInitializer(), parsed);
        declarations.add(new DeclareVariable(name, type, StatementLowerer.isNullable(resolvedType), initializer));
        if (initializer != null) {
            statements.add(new Assign(new VariableRefExpression(name), initializer));
        }
    }

    // ---- (2) JDBC invocation statements ----

    private enum JdbcDisposition { HANDLED, NOT_JDBC }

    /**
     * Inlines a source-local static void binder whose formal handle is a PreparedStatement. The helper
     * is a compile-time fragment, not an SQL routine: its setter calls update the caller's existing
     * statement state and its scalar locals are renamed per invocation.
     */
    private boolean lowerInlineJdbcBinderHelper(
            MethodInvocationTree invocation,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        TreePath invocationPath = LowererSupport.resolveTreePath(parsed, invocation);
        Element resolved = invocationPath == null ? null : parsed.trees().getElement(invocationPath);
        if (!(resolved instanceof ExecutableElement method)
                || method.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID
                || !method.getModifiers().contains(Modifier.STATIC)) {
            return false;
        }
        int statementParameter = -1;
        for (int index = 0; index < method.getParameters().size(); index++) {
            String parameterType = method.getParameters().get(index).asType().toString();
            if (parameterType.equals("java.sql.PreparedStatement")
                    || parameterType.equals("java.sql.CallableStatement")) {
                if (statementParameter >= 0) {
                    throw new IllegalArgumentException("inline JDBC binder helper has more than one statement handle: "
                            + method);
                }
                statementParameter = index;
            }
        }
        if (statementParameter < 0) {
            return false;
        }
        if (method.getParameters().size() != invocation.getArguments().size()) {
            throw new IllegalArgumentException("inline JDBC binder helper argument count does not match its declaration: "
                    + method);
        }
        Tree methodTreeValue = parsed.trees().getTree(method);
        TreePath methodPath = parsed.trees().getPath(method);
        if (!(methodTreeValue instanceof MethodTree methodTree) || methodTree.getBody() == null || methodPath == null) {
            throw new IllegalArgumentException("inline JDBC binder helper must be source-local and have a body: " + method);
        }

        Map<Element, ExpressionNode> substitutions = new HashMap<>();
        Map<Element, StatementState> handleAliases = new HashMap<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            VariableElement parameter = method.getParameters().get(index);
            ExpressionTree argument = invocation.getArguments().get(index);
            if (index == statementParameter) {
                TreePath argumentPath = LowererSupport.resolveTreePath(parsed, argument);
                Element callerHandle = argumentPath == null ? null : parsed.trees().getElement(argumentPath);
                StatementState state = callerHandle == null ? null : statementHandles.get(callerHandle);
                if (state == null) {
                    throw new IllegalArgumentException("inline JDBC binder helper requires a prepared statement local "
                            + "as its handle argument: " + invocation);
                }
                handleAliases.put(parameter, state);
            } else {
                substitutions.put(parameter, ExpressionLowerer.lowerExpression(argument, parsed));
            }
        }

        int inlineId = inlineHelperCounter++;
        Map<Element, String> renamedLocals = new HashMap<>();
        TreePath bodyPath = new TreePath(methodPath, methodTree.getBody());
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element != null && element.getKind() == ElementKind.LOCAL_VARIABLE) {
                    renamedLocals.put(element, "__titan_inline_" + inlineId + "_" + node.getName());
                }
                return super.visitVariable(node, unused);
            }
        }.scan(bodyPath, null);

        ExpressionLowerer.InlineVariableResolver previousResolver =
                ExpressionLowerer.swapInlineVariables(element -> {
                    ExpressionNode substitution = substitutions.get(element);
                    if (substitution != null) return substitution;
                    String renamed = renamedLocals.get(element);
                    return renamed == null ? null : new VariableRefExpression(renamed);
                });
        Map<Element, String> previousNames = inlineVariableNames;
        inlineVariableNames = renamedLocals;
        handleAliases.forEach(statementHandles::put);
        inlineHelperDepth++;
        try {
            Block inlined = lowerBlock(bodyPath);
            declarations.addAll(inlined.declarations());
            statements.addAll(inlined.statements());
        } finally {
            inlineHelperDepth--;
            for (Element parameter : handleAliases.keySet()) {
                statementHandles.remove(parameter);
            }
            inlineVariableNames = previousNames;
            ExpressionLowerer.swapInlineVariables(previousResolver);
        }
        return true;
    }

    private JdbcDisposition lowerJdbcInvocation(
            MethodInvocationTree invocation,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return JdbcDisposition.NOT_JDBC;
        }
        ExpressionTree receiverExpr = select.getExpression();
        String name = select.getIdentifier().toString();
        TypeMirror receiverType = shapes.typeOf(receiverExpr, path);
        if (receiverType == null) {
            return JdbcDisposition.NOT_JDBC;
        }

        if (oracle.isConnectionLike(receiverType)) {
            return lowerConnectionCall(name, statements);
        }
        if (oracle.isPreparedStatement(receiverType)) {
            return lowerPreparedStatementCall(name, invocation, receiverExpr, path, declarations, statements);
        }
        if (oracle.isPlainStatement(receiverType)) {
            return lowerPlainStatementCall(name, invocation, declarations, statements);
        }
        if (oracle.isResultSet(receiverType)) {
            // Bare rs.next()/keys.next()/close() as a statement: elided (consumed structurally / trailer).
            return JdbcDisposition.HANDLED;
        }
        return JdbcDisposition.NOT_JDBC;
    }

    private JdbcDisposition lowerConnectionCall(String name, List<StatementNode> statements) {
        switch (name) {
            case "setAutoCommit" -> {
                // I-10: elided with informational W005 (no in-routine analogue).
                diagnostics.warn("setAutoCommit(...) is elided: the routine already runs as one server-side "
                        + "unit (docs/transpilable-jdbc-subset.md §3.4)");
                return JdbcDisposition.HANDLED;
            }
            case "commit", "rollback" -> {
                lowerTransactionControl(name, statements);
                return JdbcDisposition.HANDLED;
            }
            default -> {
                // close()/isClosed()/getAutoCommit()/... elided.
                return JdbcDisposition.HANDLED;
            }
        }
    }

    private void lowerTransactionControl(String name, List<StatementNode> statements) {
        // I-10 verdict (identical to JdbcUsageRecognizer.recognizeTransactionControl): emit COMMIT/ROLLBACK
        // where the routine runs as a PROCEDURE — a @StoredProcedure, or a @ScheduledJob (emitted as a
        // backing procedure on both dialects: PG procedure + pg_cron, MySQL procedure + EVENT) — always with
        // the mandatory outer-atomic W005; reject in a FUNCTION or TRIGGER (no dialect permits transaction
        // control there). Gating on EntryPointKind, not the storedProcedure proxy, which false-rejected a
        // @ScheduledJob (same proxy bug the §9 dynamic-SQL gate had).
        if (storedProcedure || entryKind == io.titan.transpiler.EntryPointKind.SCHEDULED_JOB) {
            statements.add(new TransactionControlStatement(
                    "rollback".equals(name) ? TransactionAction.ROLLBACK : TransactionAction.COMMIT));
            String routine = storedProcedure ? "@StoredProcedure" : "@ScheduledJob";
            diagnostics.warn(name + "() is emitted only in a " + routine + " and behaves correctly only when "
                    + "the procedure is not invoked inside an outer atomic transaction; CALL it autonomously "
                    + "(mandatory W005, docs/transpilable-jdbc-subset.md §3.4)");
        } else {
            diagnostics.error("transaction control is not permitted inside a function/trigger; annotate the "
                    + "method @StoredProcedure or remove the " + name + "() "
                    + "(docs/transpilable-jdbc-subset.md §3.4)");
        }
    }

    /**
     * §9 invariant 1 — rejects a JDBC read/execute that would emit <b>dynamic</b> SQL ({@code
     * PREPARE}/{@code EXECUTE}) inside a MySQL routine that is created <b>inline</b> and so cannot run
     * dynamic SQL: a stored {@code FUNCTION} or a {@code TRIGGER}. MySQL forbids dynamic SQL in a {@code
     * FUNCTION}/trigger (ERROR 1336, {@code 0A000}) at CREATE time, so such a routine emits SQL that never
     * deploys (the byte-golden passes, but {@code CREATE FUNCTION}/{@code CREATE TRIGGER} fails on live
     * MySQL). The honest fix is to reject at lowering with a clear steer ("annotate {@code @StoredProcedure}"
     * for a function; rework the trigger for a trigger) — NOT to silently ship a non-deployable routine.
     *
     * <p>Gated precisely on the <b>MySQL emission shape</b>, not the coarse {@code !storedProcedure} proxy:
     * fires only when {@code mysqlTargeted} AND the routine is emitted inline (kind {@code STORED_FUNCTION}
     * or {@code TRIGGER}). It does NOT fire for a {@code @ScheduledJob}: on MySQL a scheduled job is emitted
     * as a backing stored {@code PROCEDURE} that a {@code CREATE EVENT … DO CALL} invokes ({@link
     * MySqlEmitter#emitScheduledJob}), and dynamic SQL is legal inside that procedure — gating it would be a
     * FALSE-REJECT of a genuinely deployable artifact (and a {@code @ScheduledJob} cannot be re-annotated
     * {@code @StoredProcedure} without dropping its schedule). PostgreSQL functions allow dynamic {@code
     * EXECUTE}, so a PG-only {@code @StoredFunction} is unaffected (the gate is {@code mysqlTargeted}-only);
     * a {@code @StoredProcedure} is unaffected on every dialect. Called at the DYNAMIC-PREPARE production
     * sites only — the {@link ExecuteSqlStatement} (I-6/I-7-fallback/I-8) and {@link RawReadIntoStatement}
     * (single-row) sites — NOT at the static cursor ({@link RawCursorStatement}, a static {@code DECLARE …
     * CURSOR FOR}), the static generated-key INSERT ({@link GeneratedKeyReadStatement}), or the
     * unknown-shape carrier ({@link DynamicResultStatement}, emitted as a MySQL PROCEDURE regardless of
     * {@code @StoredFunction}), none of which emit a dynamic PREPARE in a function. Once-guarded so a
     * method with several dynamic statements reports the single actionable error, not one per statement.
     *
     * @param construct a short human description of the rejected dynamic construct (e.g. {@code "single-row
     *                  ResultSet read"}), spliced into the diagnostic.
     */
    private void rejectIfMysqlStoredFunctionEmitsDynamicSql(String construct) {
        if (!mysqlTargeted || !mysqlDynamicSqlForbiddenForKind() || rejectedMysqlFunctionDynamicSql) {
            return;
        }
        rejectedMysqlFunctionDynamicSql = true;
        diagnostics.error(mysqlDynamicSqlRejectMessage(construct));
    }

    /**
     * Whether the current routine kind is one MySQL creates <b>inline</b> (so it cannot run dynamic SQL —
     * ERROR 1336): a stored {@code FUNCTION} or a {@code TRIGGER}. A {@code @StoredProcedure} runs dynamic
     * SQL natively, and a {@code @ScheduledJob} is emitted as a backing {@code PROCEDURE} called by a {@code
     * CREATE EVENT} ({@link MySqlEmitter#emitScheduledJob}) where dynamic SQL is equally legal — so neither
     * is gated. {@code entryKind} is {@code null} only on the bare/test constructor overloads (no entry
     * point), in which case the dialect-shape reject does not apply.
     */
    private boolean mysqlDynamicSqlForbiddenForKind() {
        return entryKind == io.titan.transpiler.EntryPointKind.STORED_FUNCTION
                || entryKind == io.titan.transpiler.EntryPointKind.TRIGGER;
    }

    /**
     * The §9-invariant-1 reject diagnostic, tailored to the routine kind. For a {@code @StoredFunction} it
     * names the actionable {@code @StoredProcedure} steer (a function CAN be a procedure when it does DML
     * and returns nothing meaningful); for a {@code @Trigger} that steer is impossible (a trigger is not a
     * routine you can re-annotate), so it instead steers to moving the dynamic work into a {@code
     * @StoredProcedure} the trigger {@code CALL}s. Both share the "ERROR 1336" citation so the cause is
     * unambiguous.
     */
    private String mysqlDynamicSqlRejectMessage(String construct) {
        if (entryKind == io.titan.transpiler.EntryPointKind.TRIGGER) {
            return "MySQL forbids dynamic SQL (PREPARE/EXECUTE) inside a TRIGGER (ERROR 1336): this "
                    + construct + " lowers to a dynamic EXECUTE that cannot run in a MySQL @Trigger — move the "
                    + "dynamic statement into a @StoredProcedure and CALL it from the trigger, or rewrite it as "
                    + "constant/static SQL (PostgreSQL triggers are unaffected) "
                    + "(docs/transpilable-jdbc-subset.md §9 invariant 1, §4)";
        }
        return "MySQL forbids dynamic SQL (PREPARE/EXECUTE) inside a stored FUNCTION (ERROR 1336): "
                + "this " + construct + " lowers to a dynamic EXECUTE that cannot run in a MySQL "
                + "@StoredFunction — annotate the method @StoredProcedure (PostgreSQL @StoredFunction is "
                + "unaffected) (docs/transpilable-jdbc-subset.md §9 invariant 1, §4)";
    }

    /**
     * I-7 (§6.3 / I-R8): lowers the generated-key recovery after a {@code RETURN_GENERATED_KEYS} INSERT.
     *
     * <p>The spec (§6.3, I-R8, Appendix A) mandates {@code INSERT … RETURNING <key-col> INTO <local>}
     * (PostgreSQL) / {@code SET <local> = LAST_INSERT_ID()} (MySQL) with the key column <b>resolved from
     * the Catalog</b> — the JDBC ordinal {@code 1} in {@code getLong(1)} does <i>not</i> name a column.
     * This is the JDBC-driver-faithful, trigger-immune form: {@code RETURNING}/{@code LAST_INSERT_ID()}
     * read the actual inserted row's key, so an {@code AFTER INSERT} trigger that advances any other
     * sequence cannot perturb them. (The earlier {@code SELECT lastval()} substitute was the
     * wrong-key-value defect: it returns the last <i>sequence</i> value — a different row's id under such
     * a trigger, and raises for a caller-supplied no-sequence key.)</p>
     *
     * <p>When the INSERT's target table resolves (via {@link JdbcGeneratedKeyResolver}) to <b>exactly
     * one</b> auto-increment/identity column in the {@link #schemaModel} Catalog, the INSERT and the key
     * read lower together to a single {@link GeneratedKeyReadStatement} carrying that column. When it is
     * <b>unresolvable</b> — no Catalog ({@code schemaModel == null}), the table is not introspected, or
     * the table has 0 / &gt;1 auto-increment columns — the INSERT is still emitted (so the build's other
     * diagnostics see it) but the key read is <b>rejected</b> ({@code TITAN-E001}, I-R8), never lowered
     * to a guessed/wrong key. Either way the key local is DECLAREd and mapped on the state so the trailing
     * {@code return keys.getLong(1)} resolves to a ref (the method-level resolver); on the reject path the
     * build fails on the E001, so no SQL is emitted.</p>
     */
    private void lowerGeneratedKeyRead(
            StatementState state,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        if (state.generatedKeyLocal != null) {
            return; // A second executeUpdate on the same gen-key statement reuses the one key local.
        }
        generatedKeyLocalCounter++;
        String keyLocal = "__titan_genkey" + generatedKeyLocalCounter;
        state.generatedKeyLocal = keyLocal;
        // The key local is BIGINT: it holds any INT/BIGINT identity value, and the §6.3 return is a long.
        // DECLAREd routine-level so the trailing `return keys.getLong(1)` resolves to a ref.
        declarations.add(new DeclareVariable(keyLocal, new TBigintType(), true, null));

        RawSql insert = buildRawSql(state, declarations, statements);
        String constantSql = constantStringValue(state.sqlArg);
        java.util.Optional<String> keyColumn = constantSql == null
                ? java.util.Optional.empty()
                : JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(schemaModel, constantSql, defaultSchema);

        if (keyColumn.isPresent()) {
            // Resolved: INSERT + Catalog-backed key recovery as one trigger-immune node.
            statements.add(new GeneratedKeyReadStatement(insert, keyColumn.get(), keyLocal));
            return;
        }

        // Unresolvable: emit the INSERT (so the build is not silently truncated) and reject the key read.
        // NEVER a guessed/session key (lastval()/LAST_INSERT_ID() over an unknown column would be wrong
        // under a trigger / for a caller-supplied key). This fallback INSERT is a DYNAMIC ExecuteSql
        // (PREPARE/EXECUTE on MySQL), unlike the resolved-key path's static GeneratedKeyReadStatement — so
        // a MySQL @StoredFunction here also trips the §9-invariant-1 dynamic-SQL reject.
        rejectIfMysqlStoredFunctionEmitsDynamicSql("INSERT with generated-key recovery");
        statements.add(new ExecuteSqlStatement(insert));
        diagnostics.error("generated-key recovery needs the inserted table's single auto-increment/identity "
                + "key column resolvable from the Catalog (the JDBC ordinal 1 in getLong(1)/getInt(1) does not "
                + "name the key column), but " + unresolvableReason(constantSql) + ". Introspect the table into "
                + "the schema model so its auto-increment column is known, or model the generated key as an "
                + "explicit RETURNING/OUT read (docs/transpilable-jdbc-subset.md §5 I-R8, §6.3)");
    }

    /** Why the I-7 key column could not be resolved, for the rejection diagnostic. */
    private String unresolvableReason(String constantSql) {
        if (schemaModel == null) {
            return "no schema model (Catalog) was supplied to the transpile";
        }
        if (constantSql == null) {
            return "the INSERT SQL is not a compile-time constant, so its target table cannot be scanned";
        }
        if (JdbcGeneratedKeyResolver.debugScanInsertTarget(constantSql).isEmpty()) {
            return "the INSERT target table could not be scanned from the SQL text (only a plain "
                    + "INSERT INTO <table> is recognized)";
        }
        return "the resolved table is not in the schema model, or does not have exactly one "
                + "auto-increment/identity column";
    }

    private JdbcDisposition lowerPreparedStatementCall(
            String name,
            MethodInvocationTree invocation,
            ExpressionTree receiverExpr,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        StatementState state = statementStateFor(receiverExpr, path);
        switch (name) {
            case "executeUpdate", "executeLargeUpdate", "execute" -> {
                // I-7 (§6.3): a RETURN_GENERATED_KEYS INSERT recovers the inserted row's auto-increment
                // key. When the key column resolves from the Catalog, the INSERT + key read lower
                // together to a single GeneratedKeyReadStatement (PG INSERT ... RETURNING <col> INTO,
                // MySQL INSERT then SET = LAST_INSERT_ID()); otherwise it is rejected (I-R8). Handled
                // separately so the gen-key node carries the INSERT itself rather than emitting a
                // standalone ExecuteSqlStatement.
                if (state != null && state.returnGeneratedKeys) {
                    lowerGeneratedKeyRead(state, declarations, statements);
                    return JdbcDisposition.HANDLED;
                }
                // I-6: ExecuteSqlStatement(RawSql) over the prepared SQL + bound values. On MySQL this
                // emits a dynamic PREPARE/EXECUTE, which a stored FUNCTION forbids (§9 invariant 1).
                rejectIfMysqlStoredFunctionEmitsDynamicSql("executeUpdate/execute");
                statements.add(new ExecuteSqlStatement(buildRawSql(state, declarations, statements)));
                return JdbcDisposition.HANDLED;
            }
            case "executeQuery" -> {
                // I-3 read execution; the result shape (I-4/I-5) is lowered at the consuming if/while,
                // not here. Gate the SQL at the call site (mirroring the recognizer) so an executeQuery
                // whose ResultSet is never consumed by an if/while is still gated — otherwise a splice
                // in unconsumed read text would slip past the lowering gate entirely.
                gateStatementSqlOnce(state);
                return JdbcDisposition.HANDLED;
            }
            case "addBatch", "executeBatch", "executeLargeBatch", "clearBatch" -> {
                rejectBatch();
                return JdbcDisposition.HANDLED;
            }
            default -> {
                if (name.startsWith("set") && state != null) {
                    recordBinding(invocation, state);
                }
                // setXxx / clearParameters / close / getGeneratedKeys: no statement emitted.
                return JdbcDisposition.HANDLED;
            }
        }
    }

    private JdbcDisposition lowerPlainStatementCall(
            String name,
            MethodInvocationTree invocation,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        switch (name) {
            case "executeUpdate", "executeLargeUpdate", "execute" -> {
                // I-8 update over inline constant SQL: st.executeUpdate(SQL). Dynamic PREPARE/EXECUTE on
                // MySQL — forbidden inside a stored FUNCTION (§9 invariant 1).
                StatementState inline = new StatementState();
                inline.sqlArg = JdbcShapes.firstArg(invocation);
                rejectIfMysqlStoredFunctionEmitsDynamicSql("plain-Statement executeUpdate/execute");
                statements.add(new ExecuteSqlStatement(buildRawSql(inline, declarations, statements)));
                return JdbcDisposition.HANDLED;
            }
            case "addBatch", "executeBatch", "executeLargeBatch", "clearBatch" -> {
                rejectBatch();
                return JdbcDisposition.HANDLED;
            }
            case "executeQuery" -> {
                // I-8 read; the result shape is consumed at the if/while. Gate the inline SQL at the call
                // site so an unconsumed plain read over non-constant SQL is still gated.
                StatementState inline = new StatementState();
                inline.sqlArg = JdbcShapes.firstArg(invocation);
                gateStatementSqlOnce(inline);
                return JdbcDisposition.HANDLED;
            }
            default -> {
                // close() elided.
                return JdbcDisposition.HANDLED;
            }
        }
    }

    /**
     * Rejects JDBC batch, mirroring the recognizer's BATCH E001 (JdbcUsageRecognizer:425/451). Without
     * this, the {@code default}/elide arm would silently drop {@code addBatch}/{@code executeBatch} and
     * lower the method to an empty (no-op) routine body — a recognizer-rejected method becoming a
     * silently-wrong empty routine (WS-C Phase 2b audit).
     */
    private void rejectBatch() {
        diagnostics.error("JDBC batch is not supported in v1; use for (item : items) executeUpdate(...) "
                + "(docs/transpilable-jdbc-subset.md §5.2)");
    }

    private void recordBinding(MethodInvocationTree invocation, StatementState state) {
        List<? extends ExpressionTree> args = invocation.getArguments();
        if (args.size() < 2) {
            return;
        }
        ExpressionTree ordinalArg = JdbcShapes.unwrap(args.get(0));
        if (!(ordinalArg instanceof LiteralTree literal) || !(literal.getValue() instanceof Integer ordinal)) {
            return;
        }
        state.binds.put(ordinal, args.get(1));
        if (inlineHelperDepth > 0) {
            ExpressionTree value = args.get(1);
            state.inlineBindValues.put(ordinal, ExpressionLowerer.lowerExpression(value, parsed));
            state.inlineBindTypes.put(ordinal, safeMap(typeOfBind(value)));
        }
    }

    // ---- (3) I-4 single-row reads ----

    private void lowerThenBlockRead(
            IfTree ifTree,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        // shape 1: if (rs.next()) { <reads> }  — reads are the then-block statements.
        Element resultSet = shapes.resultSetNextReceiver(JdbcShapes.unwrap(ifTree.getCondition()), path);
        StatementState driver = driverStateFor(resultSet);
        StatementTree thenStatement = ifTree.getThenStatement();
        TreePath thenPath = new TreePath(path, thenStatement);
        List<? extends StatementTree> thenStatements;
        TreePath enclosing;
        if (thenStatement instanceof BlockTree block) {
            thenStatements = block.getStatements();
            enclosing = new TreePath(thenPath, block);
        } else {
            thenStatements = List.of(thenStatement);
            enclosing = thenPath;
        }
        // ATG-001: `if (rs.next()) return rs.getX(col);` returns a column read directly — it has no
        // destination local, so there is no `SELECT … INTO <target>` to emit. This previously emitted an
        // invalid empty `INTO` (a PL/pgSQL syntax error that never deploys) because readTargetOf only
        // recognizes assignments/declarations. Reject with the assign-then-return rewrite (the lowerer
        // rejects LOUDLY here rather than silently dropping the read — no recognizer/lowerer no-op gap).
        for (StatementTree thenStmt : thenStatements) {
            if (isDirectReturnRead(thenStmt, enclosing, resultSet)) {
                diagnostics.error("TITAN-E001: returning a ResultSet column read directly from a single-row "
                        + "read (if (rs.next()) return rs.getX(col);) is not supported — the read has no "
                        + "destination variable, so no SELECT … INTO target can be emitted. Assign it to a "
                        + "local first, then return the local: "
                        + "T value = <default>; if (rs.next()) { value = rs.getX(col); } return value; "
                        + "(docs/transpilable-jdbc-subset.md §3.3).");
                return;
            }
        }
        List<String> intoTargets = new ArrayList<>();
        collectReads(thenStatements, enclosing, intoTargets, declarations);
        if (intoTargets.isEmpty()) {
            diagnostics.error("TITAN-E001: a single-row read if (rs.next()) { … } produced no column reads to "
                    + "assign; the block must contain at least one `local = rs.getX(col)` / "
                    + "`Type local = rs.getX(col)` read (docs/transpilable-jdbc-subset.md §3.3).");
            return;
        }
        RawSql query = buildRawSql(driver, declarations, statements);
        // MySQL emits a constant/bind-only single-row read as a static SELECT ... INTO, which is
        // legal inside a stored FUNCTION. Only an explicit structural splice needs PREPARE/EXECUTE
        // and therefore trips §9 invariant 1 / ERROR 1336.
        if (query.hasSpliceBinds()) {
            rejectIfMysqlStoredFunctionEmitsDynamicSql("dynamic single-row ResultSet read");
        }
        statements.add(new RawReadIntoStatement(List.copyOf(intoTargets), query));
    }

    private int lowerGuardThrowRead(
            IfTree ifTree,
            TreePath path,
            List<? extends StatementTree> body,
            int index,
            TreePath blockPath,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        // shape 2: if (!rs.next()) { throw ...; } <reads in following siblings>.
        Element resultSet = guardResultSet(ifTree, path);
        StatementState driver = driverStateFor(resultSet);

        // Consume the following sibling statements that are `<local> = rs.getX(col)` /
        // `Type <local> = rs.getX(col)` reads of this ResultSet.
        List<String> intoTargets = new ArrayList<>();
        int next = index + 1;
        while (next < body.size()) {
            StatementTree candidate = body.get(next);
            TreePath candidatePath = new TreePath(blockPath, candidate);
            String target = readTargetOf(candidate, candidatePath, resultSet, declarations);
            if (target == null) {
                break;
            }
            intoTargets.add(target);
            next++;
        }
        // The read runs first, then the §6.1 not-found guard RAISEs — `<read INTO …>; IF NOT FOUND
        // THEN RAISE …`. The no-row signal is carried on the node as `notFoundRaise` and rendered
        // FOUND-based by each emitter (PG `IF NOT FOUND`, MySQL CONTINUE HANDLER FOR NOT FOUND flag),
        // NOT as a "first INTO target IS NULL" proxy — a legitimately NULL first column on an existing
        // row must not falsely raise (WS-C Phase 2b audit: the IS NULL proxy mis-fires on a nullable
        // column).
        RawSql query = buildRawSql(driver, declarations, statements);
        StatementTree thrown = guardThrowStatement(ifTree.getThenStatement());
        RaiseStatement notFoundRaise = thrown instanceof com.sun.source.tree.ThrowTree throwTree
                ? StatementLowerer.lowerThrow(throwTree, new TreePath(path, throwTree), parsed)
                : null;
        // A constant/bind-only MySQL read is emitted statically and is legal in a function. Preserve
        // the dynamic-SQL reject only for structural splices that actually require PREPARE/EXECUTE.
        if (query.hasSpliceBinds()) {
            rejectIfMysqlStoredFunctionEmitsDynamicSql("dynamic single-row ResultSet read");
        }
        statements.add(new RawReadIntoStatement(List.copyOf(intoTargets), query, notFoundRaise));
        return next;
    }

    /** Collects {@code <local> = rs.getX(col)} / {@code Type <local> = rs.getX(col)} reads in order. */
    private void collectReads(
            List<? extends StatementTree> readStatements,
            TreePath enclosingPath,
            List<String> intoTargets,
            List<DeclarationNode> declarations
    ) {
        for (StatementTree statement : readStatements) {
            TreePath statementPath = new TreePath(enclosingPath, statement);
            String target = readTargetOf(statement, statementPath, null, declarations);
            if (target != null) {
                intoTargets.add(target);
            }
        }
    }

    /** True if {@code statement} is {@code return rs.getX(col);} — a direct return of a column read (ATG-001). */
    private boolean isDirectReturnRead(StatementTree statement, TreePath enclosingPath, Element expectedResultSet) {
        if (!(statement instanceof ReturnTree returnTree) || returnTree.getExpression() == null) {
            return false;
        }
        TreePath statementPath = new TreePath(enclosingPath, statement);
        return asColumnRead(returnTree.getExpression(), statementPath, expectedResultSet) != null;
    }

    /**
     * If {@code statement} is a single-row column read into a local, returns the local name (declaring
     * it if it is a {@code Type x = rs.getX(col)} form) and {@code null} otherwise. When
     * {@code expectedResultSet} is non-null the read's receiver must be that ResultSet.
     */
    private String readTargetOf(
            StatementTree statement,
            TreePath statementPath,
            Element expectedResultSet,
            List<DeclarationNode> declarations
    ) {
        if (statement instanceof VariableTree variable && variable.getInitializer() != null) {
            ColumnRead read = asColumnRead(variable.getInitializer(), statementPath, expectedResultSet);
            if (read == null) {
                return null;
            }
            TypeMirror resolvedType = parsed.trees().getTypeMirror(statementPath);
            TirType type = LowererSupport.mapType(resolvedType, "variable '" + variable.getName() + "'");
            declarations.add(new DeclareVariable(
                    variable.getName().toString(), type, StatementLowerer.isNullable(resolvedType), null));
            return variable.getName().toString();
        }
        if (statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof AssignmentTree assignment
                && assignment.getVariable() instanceof IdentifierTree target) {
            TreePath valuePath = new TreePath(statementPath, expressionStatement.getExpression());
            ColumnRead read = asColumnRead(assignment.getExpression(), valuePath, expectedResultSet);
            if (read == null) {
                return null;
            }
            return target.getName().toString();
        }
        return null;
    }

    // ---- (4) I-5 multi-row cursor ----

    private void lowerCursorLoop(
            WhileLoopTree whileTree,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        Element resultSet = shapes.resultSetNextReceiver(JdbcShapes.unwrap(whileTree.getCondition()), path);
        StatementState driver = driverStateFor(resultSet);

        // A FUSED (§3.6 form 1) / collection-IN (form 2) cursor does NOT use its own runtime sqlArg —
        // buildRawSql emits the fully-constant fused / array-bind text instead — so neither the strict-splice
        // gate nor the MySQL non-constant reject below applies to it (its sqlArg's placeholder-run would
        // otherwise be misread). Computed up front so both guards can exclude it.
        boolean fusedConstant = driver != null && (driver.fusionPlan != null || driver.collectionInPlan != null);
        // WS-C Phase 3 Rung 3 audit fix (boundary): a STRICT cursor read over an IDENTIFIER/RAW_FRAGMENT
        // splice must reject with the injection E004 (the 3-part diagnostic naming format('%I') / catalog
        // quoting / @SqlSafety(PERMISSIVE)) — the SAME diagnostic the executeUpdate/executeQuery/INTO paths
        // give — consistently regardless of target dialect. Run the strict-splice gate HERE, BEFORE the
        // MySQL non-constant E001 below, so a STRICT `while(rs.next())` ORDER-BY splice on a MySQL/default
        // target surfaces E004 (injection) and not the dialect-capability E001 ("use a typed DSL"). The gate
        // is idempotent (buildRawSql calls it again later); under STRICT it routes a splice skeleton to the
        // §5.3 E004, under PERMISSIVE it skips (the splice emits) so the MySQL-can't-iterate E001 below
        // still fires for the permissive-but-MySQL case. Excludes the fused/collection-IN cursor (its sqlArg
        // is not what is emitted).
        if (safetyMode == SqlSafetyMode.STRICT && driver != null && !fusedConstant
                && isSpliceEmittableSkeleton(driver.sqlArg)) {
            gateStatementSqlOnce(driver);
            return;
        }
        // MySQL non-constant cursor reject (decision 1): MySQL static cursors cannot iterate runtime-
        // built text, so a permissive non-constant I-5 cursor targeting MySQL is a hard E001 at lowering
        // (a temp-table materialization is the named v2 upgrade). Without this the emitter would receive
        // empty cursor text and emit an invalid `DECLARE ... CURSOR FOR ;`. STRICT non-constant SQL is
        // already E004 (the strict-splice gate just above, and buildRawSql), so this only fires for an
        // explicitly-permissive method.
        // §3.6 form 1 exception: a FUSED cursor's text is fully CONSTANT (A inlined as the IN-subquery
        // replaced the only runtime part — the placeholder run), so MySQL can iterate it as a static
        // cursor with A's/B's binds spliced as routine-local identifiers; do not reject it here.
        // §3.6 form 2 exception: a collection-IN cursor's array-bind text has a fixed shape (the membership
        // marker + scalar binds, no runtime-sized run) — MySQL renders it as `JSON_TABLE(?, …)` whose `?`
        // the static cursor splices as the JSON-param routine-local identifier (inlineCursorBinds), so it
        // iterates fine as a static cursor; do not reject it here either.
        if (mysqlTargeted && driver != null && !fusedConstant && constantStringValue(driver.sqlArg) == null) {
            diagnostics.error("MySQL multi-row read (while (rs.next())) over non-constant SQL is not supported: "
                    + "a MySQL static cursor cannot iterate runtime-built text. Keep the SELECT text constant "
                    + "(bind runtime values as parameters), or use a typed DSL/@SQL read "
                    + "(docs/transpilable-jdbc-subset.md §4, decision 1)");
            return;
        }

        // Discover the per-row column reads inside the loop body and allocate a scalar local per
        // distinct (receiver, column). Each in-loop rs.getX(col) then lowers to a VariableRefExpression
        // to that row local (the resolver below, consulted by ExpressionLowerer).
        StatementTree bodyStatement = whileTree.getStatement();
        TreePath bodyPath = new TreePath(path, bodyStatement);
        LinkedHashMap<ColumnKey, String> rowReads = new LinkedHashMap<>();
        List<DeclarationNode> rowDeclarations = new ArrayList<>();
        discoverRowReads(bodyPath, resultSet, rowReads, rowDeclarations);

        // Build the RawSql (and any expression-bind locals) BEFORE lowering the body so the OPEN's
        // USING binds are evaluated ahead of the loop, on the enclosing block.
        RawSql query = buildRawSql(driver, declarations, statements);

        ExpressionLowerer.JdbcRowReadResolver resolver = (invocation, sources) -> {
            ColumnRead read = asColumnRead(invocation, LowererSupport.resolveTreePath(sources, invocation), resultSet);
            return read == null ? null : rowReads.get(new ColumnKey(read.receiver(), read.column()));
        };
        ExpressionLowerer.JdbcRowReadResolver previous = ExpressionLowerer.swapJdbcRowReads(resolver);
        Block loweredBody;
        try {
            loweredBody = lowerLoopBody(bodyStatement, bodyPath);
        } finally {
            ExpressionLowerer.swapJdbcRowReads(previous);
        }

        // The per-row scalar locals are FETCH targets; declare them on the cursor body block so they
        // are in scope for both the FETCH and the lowered body.
        List<DeclarationNode> bodyDeclarations = new ArrayList<>(rowDeclarations);
        bodyDeclarations.addAll(loweredBody.declarations());
        Block cursorBody = new Block(List.copyOf(bodyDeclarations), loweredBody.statements(), loweredBody.exceptionHandlers());

        statements.add(new RawCursorStatement(List.copyOf(rowReads.values()), query, cursorBody, null));
    }

    private Block lowerLoopBody(StatementTree bodyStatement, TreePath bodyPath) {
        if (bodyStatement instanceof BlockTree) {
            // Preserve JDBC handle/bind state while lowering an inner cursor. The outer row-read
            // resolver is already installed by lowerCursorLoop and the inner call nests/restores
            // its own resolver, so this is safe for lexical ResultSet scopes.
            return lowerBlock(bodyPath);
        }
        return new Block(List.of(), List.of(StatementLowerer.lowerStatement(bodyStatement, bodyPath, parsed)), List.of());
    }

    private void discoverRowReads(
            TreePath bodyPath,
            Element resultSet,
            Map<ColumnKey, String> rowReads,
            List<DeclarationNode> rowDeclarations
    ) {
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                ColumnRead read = asColumnRead(node, getCurrentPath(), resultSet);
                if (read != null) {
                    ColumnKey key = new ColumnKey(read.receiver(), read.column());
                    rowReads.computeIfAbsent(key, k -> {
                        String rowLocal = "__titan_row_" + rowLocalFragment(read.column())
                                + "_" + rowReads.size();
                        rowDeclarations.add(new DeclareVariable(rowLocal, read.tirType(), true, null));
                        return rowLocal;
                    });
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(bodyPath, null);
    }

    // ---- (5) try-with-resources holding JDBC handles ----

    private boolean tryHoldsJdbcResource(TryTree tryTree, TreePath path) {
        for (Tree resource : tryTree.getResources()) {
            if (resource instanceof VariableTree resourceVar) {
                TypeMirror type = parsed.trees().getTypeMirror(new TreePath(path, resourceVar));
                if (type != null && oracle.isJdbcHandle(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void lowerJdbcTry(
            TryTree tryTree,
            TreePath path,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        // Record + elide the JDBC resource handles (their close() is elided), then lower the try body
        // through the JDBC lowerer so inner reads/binds are recognised. Resources that are NOT JDBC
        // handles are not expected in a JDBC-bearing try here (v1 scope); they would fall to the stock
        // path via the resource being absent. catch/finally lower through the stock block path.
        for (Tree resource : tryTree.getResources()) {
            if (resource instanceof VariableTree resourceVar) {
                recordAndElideHandleDeclaration(resourceVar, new TreePath(path, resourceVar));
            }
        }
        Block tryBody = lowerBlock(new TreePath(path, tryTree.getBlock()));
        // No catches/finally in the JDBC worked examples' resource-only try; if present, lower them
        // through the stock path to preserve their semantics.
        if (tryTree.getCatches().isEmpty() && tryTree.getFinallyBlock() == null) {
            declarations.addAll(tryBody.declarations());
            statements.addAll(tryBody.statements());
            return;
        }
        // Fall back to the stock try lowering for catch/finally-bearing tries (resource close elided
        // there too); the inner reads are still JDBC-recognised because the stock path re-enters the
        // JDBC lowerer only at method granularity — so for safety, emit the JDBC-lowered body inline
        // wrapped in a TryCatchFinallyStatement with stock-lowered handlers.
        List<CatchClause> catches = new ArrayList<>();
        for (com.sun.source.tree.CatchTree catchTree : tryTree.getCatches()) {
            String exceptionVariable = catchTree.getParameter() == null ? "ex"
                    : catchTree.getParameter().getName().toString();
            String exceptionType = catchTree.getParameter() == null || catchTree.getParameter().getType() == null
                    ? "java.lang.Exception" : catchTree.getParameter().getType().toString();
            Block catchBody = StatementLowerer.lowerBlock(new TreePath(path, catchTree.getBlock()), parsed);
            catches.add(new CatchClause(exceptionVariable, exceptionType,
                    List.of("45000"), catchBody));
        }
        Block finallyBlock = tryTree.getFinallyBlock() == null ? null
                : StatementLowerer.lowerBlock(new TreePath(path, tryTree.getFinallyBlock()), parsed);
        statements.add(new TryCatchFinallyStatement(tryBody, List.copyOf(catches), finallyBlock));
    }

    // ---- RawSql construction (binding map → parameters, synthesising v_pN locals) ----

    /**
     * Builds the {@link RawSql} for a recognised statement. The binding map's per-ordinal bound values
     * become {@link RawSql#parameters()} variable names: a bare-variable bind is referenced directly;
     * an expression bind synthesises a {@code __titan_pN} local (DECLARE appended to {@code blockDecls},
     * an initialising {@link Assign} appended to {@code statements} <i>before</i> the consuming
     * statement, since {@code buildRawSql} is invoked while the consuming statement is still being
     * assembled). The emitter's {@code rewritePositionalPlaceholders} rewrites each {@code ?}.
     */
    private RawSql buildRawSql(
            StatementState state,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        // §3.6 form 1 — subquery fusion (Rung 2): query B carries a proven plan. Emit the fused
        // `<B prefix>(<A sql>)<B suffix>` opaque text and merge A's binds + B's other binds in text
        // order, all still bound (no value/identifier spliced). A is inlined as a SUBQUERY, so the
        // runtime-sized placeholder run never materializes — the result shape is B's.
        if (state != null && state.fusionPlan != null) {
            return buildFusedRawSql(state.fusionPlan, blockDecls, statements);
        }

        // §3.6 form 2 — collection binding (Rung 4): query B carries a proven plan. Emit the array-bind
        // RawSql — the `<col> IN (run)` becomes a membership marker the emitter renders natively (PG `=
        // ANY($n)` / MySQL `JSON_TABLE`), and the WHOLE collection is bound as ONE array/JSON parameter
        // (no element ever reaches the text). B's other binds merge in text order around the marker.
        if (state != null && state.collectionInPlan != null) {
            return buildCollectionInRawSql(state.collectionInPlan, blockDecls, statements);
        }

        // Design contract D4 — optional-filter guarded predicates: query B carries a proven plan. Emit ONE
        // static query whose WHERE is the constant base AND one `(? IS NULL OR col OP ?)` per optional
        // clause (each `?` binds the clause's param — bound TWICE, in clause order). No dynamic EXECUTE,
        // all values bound; the runtime-built `sql.toString()` text is replaced entirely.
        if (state != null && state.guardedPredicatePlan != null) {
            return buildGuardedPredicateRawSql(state.guardedPredicatePlan, blockDecls, statements);
        }

        String sql = state == null ? null : constantStringValue(state.sqlArg);
        if (sql != null) {
            // Constant SQL: the developer's '?'s map to the setXxx ordinal binds in left-to-right order.
            List<String> bindNames = bindParameterNames(state, blockDecls, statements);
            return new RawSql(sql, bindNames, "");
        }

        // WS-C Phase 3 Rung 1: the SQL text is non-constant. Recover the Java string construction into a
        // JdbcSqlSkeleton; when EVERY hole is value-bindable (a placeholder run bound by setXxx, or a
        // scalar value splice that becomes a synthesized bind — design contract D2/D3), build the RawSql
        // from the recovered '?'-text + the merged bind names INSTEAD of rejecting. No value or
        // identifier reaches the emitted SQL text — VALUE holes bind, PLACEHOLDER runs are bound by the
        // statement's own setXxx ordinals. If ANY hole is IDENTIFIER/RAW_FRAGMENT (not value-bindable),
        // fall through to the gate: strict rejects (E004), permissive defers to Rung 3.
        java.util.Optional<io.titan.transpiler.jdbc.JdbcSqlSkeleton> recovered =
                state == null ? java.util.Optional.empty()
                        : io.titan.transpiler.jdbc.JdbcSqlSkeletonRecognizer.recover(state.sqlArg);
        if (recovered.isPresent() && recovered.get().hasHole() && recovered.get().allHolesValueBindable()) {
            io.titan.transpiler.jdbc.JdbcSqlSkeleton skeleton = recovered.get();
            String recoveredSql = skeleton.recoveredSql();
            List<String> bindNames = skeletonBindParameterNames(skeleton, state, blockDecls, statements);
            // Arity invariant (design contract task item c): the count of '?' placeholders surviving in
            // the recovered text MUST equal the number of USING binds. They are two independent walks of
            // the skeleton; if they diverge (a developer '?' with no matching setXxx, so the EXECUTE would
            // be under-bound and fail at CALL with "there is no parameter $N"), do NOT emit a desynced
            // EXECUTE — reject. This is a value-bindable skeleton, so the hole-kind gate would SKIP it;
            // raise a dedicated arity diagnostic directly. WS-C Phase 3 Rung 1 deploy fix.
            long placeholderCount = countQuestionMarksOutsideStringLiterals(recoveredSql);
            if (placeholderCount != bindNames.size()) {
                rejectPlaceholderBindArity(recoveredSql, (int) placeholderCount, bindNames.size());
                return new RawSql("", bindNames, "");
            }
            return new RawSql(recoveredSql, bindNames, "");
        }

        // WS-C Phase 3 Rung 3: the recovered skeleton has at least one IDENTIFIER/RAW_FRAGMENT hole (a
        // runtime table/column/ORDER-BY name, or an opaque raw SQL fragment) — NOT value-bindable, so it
        // reaches the SQL text (the D2 boundary). STRICT must still REJECT it (E004); only a PERMISSIVE
        // scope EMITS it, assembling the SQL text at runtime (PG format('%I')/'%s', MySQL CONCAT). Any
        // genuine VALUE holes in the same statement still BIND. This is the migration on-ramp the report
        // surfaces — never an escalation of a strict scope.
        if (safetyMode == SqlSafetyMode.PERMISSIVE
                && recovered.isPresent() && recovered.get().hasHole()) {
            return buildSpliceRawSql(recovered.get(), state, blockDecls, statements);
        }

        // Non-constant SQL not recoverable as all-value-bindable: enforce the constant-SQL gate (covers
        // the I-4/I-5/I-6/I-8 consuming paths). STRICT rejects with the §5.3 E004 splice diagnostic; a
        // PERMISSIVE scope only reaches here when NO skeleton was recoverable at all (the gate then emits
        // its "could not resolve" E001). The empty placeholder text below is never emitted in a clean
        // build — the gate's E004/E001 fails it.
        gateStatementSqlOnce(state);
        return new RawSql("", bindParameterNames(state, blockDecls, statements), "");
    }

    /**
     * WS-C Phase 3 Rung 3 — builds the {@link RawSql} for a recovered skeleton that contains at least one
     * {@code IDENTIFIER}/{@code RAW_FRAGMENT} hole, under a <b>PERMISSIVE</b> scope only (the caller has
     * already checked {@link #safetyMode}; {@code strict} never reaches here — it rejects with {@code
     * TITAN-E004}). A single left-to-right walk of the fragments — the exact order placeholders and
     * splices appear in the assembled text — produces three things in lockstep so they cannot drift:
     * <ul>
     *   <li>the SQL <b>text</b>: a {@link io.titan.transpiler.jdbc.JdbcSqlSkeleton.Constant} appends
     *       verbatim; a {@code PLACEHOLDER} run appends its {@code ?}/separator text; a {@code VALUE} hole
     *       appends a single {@code ?}; an {@code IDENTIFIER}/{@code RAW_FRAGMENT} hole appends a
     *       {@link RawSql#spliceMarker(int) splice marker} (NOT a {@code ?} — it is structural, not bound);</li>
     *   <li>the value {@link RawSql#parameters()} (the {@code USING} binds): a constant/PLACEHOLDER
     *       {@code ?} consumes the next {@code setXxx} ordinal; a {@code VALUE} hole's {@code ?} binds its
     *       spliced expression — identical to {@link #skeletonBindParameterNames} (values stay bound);</li>
     *   <li>the {@link RawSql#spliceBinds()}: each {@code IDENTIFIER}/{@code RAW_FRAGMENT} hole's
     *       expression is lowered to a routine-local name via the SAME {@link #bindNameFor} seam, recorded
     *       with its {@link SpliceBind.Kind} so the emitter quotes ({@code %I}/backtick) or splices
     *       verbatim ({@code %s}) it into the assembled text.</li>
     * </ul>
     * The security boundary is exact: a VALUE never reaches the text (it binds); only the identifier /
     * raw fragment is spliced, and only in a permissive scope. The emitter ({@code visitRawSql}) assembles
     * {@code EXECUTE format(...) USING <value binds>} (PostgreSQL) / {@code PREPARE … FROM CONCAT(...)}
     * (MySQL) from the marked text + the splice binds.
     */
    private RawSql buildSpliceRawSql(
            io.titan.transpiler.jdbc.JdbcSqlSkeleton skeleton,
            StatementState state,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        StringBuilder sql = new StringBuilder();
        List<String> bindNames = new ArrayList<>();
        List<SpliceBind> spliceBinds = new ArrayList<>();
        int jdbcOrdinal = 0;
        int spliceMarkerId = 0;
        for (io.titan.transpiler.jdbc.JdbcSqlSkeleton.Fragment fragment : skeleton.fragments()) {
            if (fragment instanceof io.titan.transpiler.jdbc.JdbcSqlSkeleton.Constant constant) {
                sql.append(constant.text());
                jdbcOrdinal = appendSetXxxBindsForQuestionMarks(
                        constant.text(), jdbcOrdinal, state, blockDecls, statements, bindNames);
            } else if (fragment instanceof io.titan.transpiler.jdbc.JdbcSqlSkeleton.Hole hole) {
                switch (hole.kind()) {
                    case PLACEHOLDER -> {
                        sql.append(hole.placeholderText());
                        jdbcOrdinal = appendSetXxxBindsForQuestionMarks(
                                hole.placeholderText(), jdbcOrdinal, state, blockDecls, statements, bindNames);
                    }
                    case VALUE -> {
                        sql.append('?');
                        bindNames.add(synthesizeBindLocal(hole.bindExpr(), blockDecls, statements));
                    }
                    case IDENTIFIER -> {
                        sql.append(RawSql.spliceMarker(spliceMarkerId));
                        spliceBinds.add(new SpliceBind(spliceMarkerId,
                                spliceParamName(hole.bindExpr(), blockDecls, statements),
                                SpliceBind.Kind.IDENTIFIER));
                        spliceMarkerId++;
                    }
                    case RAW_FRAGMENT -> {
                        sql.append(RawSql.spliceMarker(spliceMarkerId));
                        spliceBinds.add(new SpliceBind(spliceMarkerId,
                                spliceParamName(hole.bindExpr(), blockDecls, statements),
                                SpliceBind.Kind.RAW_FRAGMENT));
                        spliceMarkerId++;
                    }
                }
            }
        }
        // Arity invariant (as in the value-bindable path): the surviving '?' count must equal the value
        // bind count — the splice markers carry NO '?', so they are excluded by construction. A mismatch
        // (a developer '?' with no matching setXxx) would desync the EXECUTE USING; reject rather than
        // ship a broken statement. Rejected in both modes (it can never deploy).
        long placeholderCount = countQuestionMarksOutsideStringLiterals(sql.toString());
        if (placeholderCount != bindNames.size()) {
            rejectPlaceholderBindArity(sql.toString(), (int) placeholderCount, bindNames.size());
            return new RawSql("", bindNames, "");
        }
        return new RawSql(sql.toString(), bindNames, "", List.of(), spliceBinds);
    }

    /**
     * Resolves the routine-local name for an {@code IDENTIFIER}/{@code RAW_FRAGMENT} splice expression
     * (WS-C Phase 3 Rung 3). Reuses the SAME {@link #bindNameFor} seam the value binds use — a bare-variable
     * splice (the common {@code "ORDER BY " + sortCol} where {@code sortCol} is a String param/local) is
     * referenced directly; any other expression synthesizes a {@code __titan_pN := <lowered expr>} local —
     * so the spliced name is a real routine-local the emitter wraps in {@code format('%I'/'%s', …)} /
     * {@code CONCAT(…)}. The expression is the un-bindable operand the recognizer kept on the hole.
     */
    private String spliceParamName(
            ExpressionTree spliceExpr,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        return bindNameFor(spliceExpr, blockDecls, statements);
    }

    /**
     * §3.6 form 1 — builds the fused {@link RawSql} for query B from a proven {@link
     * io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan}: the SQL text is {@code <B prefix ending
     * "IN (">(<A sql>)<B suffix starting ")">} (A inlined verbatim as the IN-subquery), and the {@code
     * parameters()} are A's binds and B's other binds resolved in left-to-right <b>text order</b> via
     * the SAME {@link #bindNameFor} seam every other JDBC statement uses (a bare-variable bind is
     * referenced directly; any other expression synthesizes a {@code __titan_pN := <expr>} local).
     *
     * <p>The whole text is constant once A replaces the runtime placeholder run, so it is emitted
     * exactly like a constant statement (the fixed-{@code ?} {@code EXECUTE … USING} substrate on
     * PostgreSQL, the static cursor / {@code PREPARE} on MySQL). The arity invariant is asserted: the
     * fused text's surviving {@code ?} count must equal the merged bind count — A's {@code ?}s plus B's
     * surviving {@code ?}s (the IN-run's {@code ?}s are gone, replaced by the subquery) — or it is a
     * hard reject (it could never deploy), never a desynced {@code EXECUTE}.</p>
     */
    private RawSql buildFusedRawSql(
            io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan plan,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        String fusedSql = plan.fusedSql();
        List<String> bindNames = new ArrayList<>();
        for (io.titan.transpiler.jdbc.JdbcSubqueryFusion.BindSource source : plan.orderedBindSources()) {
            bindNames.add(bindNameFor(source.boundExpr(), blockDecls, statements));
        }
        long placeholderCount = countQuestionMarksOutsideStringLiterals(fusedSql);
        if (placeholderCount != bindNames.size()) {
            // The merged binds desync from the fused placeholders — a `?` in A or B with no matching
            // setXxx (a stray `?` in a constant prefix/suffix). A literal `?` inside a single-quoted SQL
            // string is NOT a placeholder and is excluded from the count. Emitting a genuine desync would
            // fail at CALL with "there is no parameter $N"; reject rather than ship a broken EXECUTE.
            // (Reuses the Rung-1 arity diagnostic.) Rejected in both modes — it can never deploy.
            rejectPlaceholderBindArity(fusedSql, (int) placeholderCount, bindNames.size());
            return new RawSql("", bindNames, "");
        }
        return new RawSql(fusedSql, bindNames, "");
    }

    /**
     * §3.6 form 2 — builds the array-bind {@link RawSql} for query B from a proven {@link
     * io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan}: the SQL text is {@code <B
     * prefix> + <membership marker> + <B suffix with the IN's closing ")" stripped>} — the whole {@code
     * <col> IN (run)} is replaced by a single {@link RawSql#arrayBindMarker(int) marker} that each emitter
     * renders natively (PostgreSQL {@code <col> = ANY($n)}, MySQL {@code <col> IN (SELECT v FROM
     * JSON_TABLE(?, …))}). The bound {@link RawSql#parameters()} are, in left-to-right <b>text order</b>:
     * B's binds before the run, then the <b>whole collection</b> as one array/JSON parameter (bound by its
     * routine-local name via the same {@link #bindNameFor} seam — the parameter's array/JSON type comes
     * from the existing {@code List<E>} → {@code TArrayType} signature mapping), then B's binds after the
     * run. The marker contributes exactly one {@code ?} once expanded, which lines up with the array
     * parameter's text-order position — so the array bind flows through the identical {@code USING}/{@code
     * @p} machinery as a scalar, and <b>no element ever reaches the SQL text</b> (the security invariant).
     */
    private RawSql buildCollectionInRawSql(
            io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan plan,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        int markerId = 0;
        String text = plan.sqlPrefix() + RawSql.arrayBindMarker(markerId) + stripLeadingCloseParen(plan.sqlSuffix());

        List<String> bindNames = new ArrayList<>();
        // B's binds before the run (they appear, in text, before the membership marker).
        for (ExpressionTree bind : plan.bindsBeforeRun()) {
            bindNames.add(bindNameFor(bind, blockDecls, statements));
        }
        // The whole collection as ONE array/JSON parameter, at the marker's text position. The recognizer
        // proved the collection is a bare local/parameter (FORM A's coll.get(i) / FORM B's coll.toArray()
        // receiver), so it binds by its simple name — the emitter resolves it to its routine-local
        // p_/v_-prefixed name, whose declared List<E> signature type is already the native array (PG) /
        // JSON (MySQL) (JavaTypeToTirMapper maps List<E> -> TArrayType). No element ever reaches the text.
        bindNames.add(plan.collectionName());
        TirType elementType = arrayElementTirType(plan.elementType());
        List<ArrayBind> arrayBinds = List.of(new ArrayBind(markerId, plan.lhs(), elementType));
        // B's binds after the run.
        for (ExpressionTree bind : plan.bindsAfterRun()) {
            bindNames.add(bindNameFor(bind, blockDecls, statements));
        }

        return new RawSql(text, bindNames, "", arrayBinds);
    }

    /**
     * Design contract D4 — builds the static guarded-predicate {@link RawSql} for query B from a proven
     * {@link io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan}: the SQL text is
     * {@code <base> AND (? IS NULL OR <col> <OP> ?) … <tail>} (one always-present guarded predicate per
     * optional clause, in clause order, then the ordered/paginated tail — {@code ORDER BY}/{@code GROUP
     * BY}/… + {@code LIMIT}/{@code OFFSET}/{@code FETCH} pagination), and the {@code parameters()} are: each
     * clause's param bound <b>twice</b> (in left-to-right text order: the {@code ? IS NULL} guard then the
     * {@code col OP ?} comparison), THEN each pagination tail param once (in tail-{@code ?} text order — the
     * clauses are textually first, the pagination {@code ?}s last). All are resolved via the SAME {@link
     * #bindNameFor} seam every other JDBC statement uses (a bare-variable operand — the recognizer proved a
     * gate/bind/pagination operand may be a plain {@code param} reference — is referenced directly; an
     * expression operand synthesizes a bind local).
     *
     * <p>The whole text is value-bound (no value/identifier reaches it — every clause param AND every
     * pagination {@code ?} is bound), so it is emitted exactly like a constant statement (the fixed-{@code ?}
     * {@code EXECUTE … USING} substrate on PostgreSQL, the static cursor / {@code PREPARE} on MySQL) — NO
     * dynamic SQL assembly. The arity invariant holds by construction (each clause contributes exactly two
     * {@code ?} and two binds; each pagination tail {@code ?} contributes one and one), and is asserted for
     * safety: the surviving {@code ?} count must equal the bind count or it is a hard reject.
     * <b>Semantic equivalence</b> (verified in the recognizer's contract): {@code (p IS NULL OR col OP p)}
     * returns the same rows as the conditional builder for every param combination and operator — p null →
     * TRUE (no-op == omitted clause), p non-null → {@code col OP p} (== the appended clause), unchanged
     * three-valued logic on a NULL {@code col}.</p>
     */
    private RawSql buildGuardedPredicateRawSql(
            io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan plan,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        String guardedSql = plan.guardedSql();
        List<String> bindNames = new ArrayList<>();
        // Each optional clause emits `AND (? IS NULL OR col OP ?)` — its param binds BOTH `?` (the IS NULL
        // guard, then the comparison), in clause order. Binding the same routine-local twice is exactly the
        // guarded predicate: when the param is NULL the first `?` makes `? IS NULL` TRUE (the whole guard a
        // no-op == omitting the clause); when non-null `(FALSE OR col OP ?)` reduces to `col OP ?`.
        for (io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.OptionalClause clause : plan.clauses()) {
            String paramName = bindNameFor(clause.paramExpr(), blockDecls, statements);
            bindNames.add(paramName); // the `? IS NULL` guard.
            bindNames.add(paramName); // the `col OP ?` comparison.
        }
        // Then the pagination tail binds, in tail-`?` TEXT order (the recognizer captured them in that order).
        // The clauses are always present so their `?`s are textually FIRST; the pagination `?`s (LIMIT/OFFSET/
        // FETCH count/offset operands the tail splices verbatim) are textually LAST — so their binds go LAST in
        // the USING list. Each is resolved via the SAME bindNameFor seam (a bare-variable operand — `offset`,
        // `pageSize` — is referenced directly; an expression operand — a literal `10` — synthesizes a
        // `__titan_pN := <value>` local). The MySQL `LIMIT ?, ?` offset/count order falls out for free: the
        // source `setInt(i++, offset); setInt(i++, pageSize)` ran in tail-`?` order, so offset binds first.
        for (com.sun.source.tree.ExpressionTree tailParam : plan.tailParamExprs()) {
            bindNames.add(bindNameFor(tailParam, blockDecls, statements));
        }
        long placeholderCount = countQuestionMarksOutsideStringLiterals(guardedSql);
        if (placeholderCount != bindNames.size()) {
            // Defensive: the recognizer proves exactly one `?` per clause and exactly one correlated bind,
            // so this never trips on a recognized plan — but a desynced EXECUTE could never deploy, so guard
            // it like every other build path. (Reuses the Rung-1 arity diagnostic.)
            rejectPlaceholderBindArity(guardedSql, (int) placeholderCount, bindNames.size());
            return new RawSql("", bindNames, "");
        }
        return new RawSql(guardedSql, bindNames, "");
    }

    /** The TIR element type for a recognized collection element (drives the per-dialect array/JSON cast). */
    private static TirType arrayElementTirType(
            io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.ElementType elementType) {
        return switch (elementType) {
            case BIGINT -> new TBigintType();
            case INT -> new TIntType();
            case TEXT -> new TTextType();
            case UUID -> new TUuidType();
        };
    }

    /** Strips the IN-list's closing {@code )} (and leading whitespace) from B's suffix (the marker owns it). */
    private static String stripLeadingCloseParen(String suffix) {
        String leadingWhitespace = suffix.substring(0, suffix.length() - suffix.stripLeading().length());
        String trimmed = suffix.stripLeading();
        // The recognizer proved the suffix starts with ')'; drop exactly that one paren.
        return leadingWhitespace + (trimmed.startsWith(")") ? trimmed.substring(1) : trimmed);
    }

    /**
     * Builds the {@link RawSql#parameters()} bind names for a recovered {@link
     * io.titan.transpiler.jdbc.JdbcSqlSkeleton} whose holes are all value-bindable (WS-C Phase 3 Rung
     * 1). Walks the skeleton fragments left-to-right — the exact order the {@code ?} placeholders appear
     * in {@link io.titan.transpiler.jdbc.JdbcSqlSkeleton#recoveredSql()}, which is the order {@code
     * EXECUTE … USING} binds them:
     * <ul>
     *   <li>each literal {@code ?} in a {@link io.titan.transpiler.jdbc.JdbcSqlSkeleton.Constant} or a
     *       {@code PLACEHOLDER} hole consumes the next JDBC {@code setXxx} ordinal (the developer's own
     *       {@code ps.setLong(n, …)} bind), resolved exactly as the constant path does — a bare-variable
     *       bind is referenced directly, an expression bind synthesizes a {@code __titan_pN} local;</li>
     *   <li>each {@code VALUE} hole's single {@code ?} binds the spliced expression itself — always a
     *       synthesized {@code __titan_pN := <lowered expr>} local (design contract D3), so the value is
     *       parameterized and never reaches the text. A VALUE hole does NOT consume a JDBC ordinal (it is
     *       not a {@code ?} the developer wrote / bound via {@code setXxx}).</li>
     * </ul>
     * The synthesized {@code DECLARE}/{@code Assign} are appended exactly like {@link
     * #bindParameterNames} (before the consuming statement). This is the single bind-assembly seam for
     * the skeleton path, so the recovered {@code ?}-text and the {@code USING} order cannot drift.
     */
    private List<String> skeletonBindParameterNames(
            io.titan.transpiler.jdbc.JdbcSqlSkeleton skeleton,
            StatementState state,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        List<String> names = new ArrayList<>();
        int jdbcOrdinal = 0;
        for (io.titan.transpiler.jdbc.JdbcSqlSkeleton.Fragment fragment : skeleton.fragments()) {
            if (fragment instanceof io.titan.transpiler.jdbc.JdbcSqlSkeleton.Constant constant) {
                jdbcOrdinal = appendSetXxxBindsForQuestionMarks(
                        constant.text(), jdbcOrdinal, state, blockDecls, statements, names);
            } else if (fragment instanceof io.titan.transpiler.jdbc.JdbcSqlSkeleton.Hole hole) {
                switch (hole.kind()) {
                    case PLACEHOLDER -> jdbcOrdinal = appendSetXxxBindsForQuestionMarks(
                            hole.placeholderText(), jdbcOrdinal, state, blockDecls, statements, names);
                    case VALUE -> names.add(
                            synthesizeBindLocal(hole.bindExpr(), blockDecls, statements));
                    // IDENTIFIER / RAW_FRAGMENT never reach here: the caller only invokes this when
                    // allHolesValueBindable() holds. (A defensive no-op keeps the switch exhaustive.)
                    case IDENTIFIER, RAW_FRAGMENT -> { }
                }
            }
        }
        return names;
    }

    /**
     * For each {@code ?} <b>outside a single-quoted SQL string literal</b> in {@code text}, consumes the
     * next JDBC {@code setXxx} ordinal and appends its resolved bind name (bare variable referenced
     * directly; expression bind synthesizes a {@code __titan_pN} local). Returns the advanced ordinal. A
     * literal {@code '?'} inside a string literal is data, not a placeholder, so it is skipped (it never
     * consumed a {@code setXxx} ordinal). A {@code ?} with no matching {@code setXxx} bind appends nothing
     * for that ordinal — mirroring the constant path's {@link #bindParameterNames}, where an unbound
     * ordinal contributes no {@code USING} argument (the build still fails its own recognizer gate if a
     * placeholder is genuinely unbound, but the lowerer never invents a bind).
     */
    private int appendSetXxxBindsForQuestionMarks(
            String text,
            int jdbcOrdinal,
            StatementState state,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements,
            List<String> names
    ) {
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++; // an escaped '' quote — still inside the literal.
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c != '?') {
                continue;
            }
            jdbcOrdinal++;
            ExpressionTree boundExpr = state == null ? null : state.binds.get(jdbcOrdinal);
            if (boundExpr == null) {
                continue;
            }
            names.add(bindNameFor(boundExpr, blockDecls, statements));
        }
        return jdbcOrdinal;
    }

    /**
     * Counts {@code ?} placeholders <b>outside</b> single-quoted SQL string literals — the count the
     * arity invariant compares against the number of {@code USING} binds. A literal {@code '?'} (or
     * {@code '?%'}) inside a quoted string is data, never a JDBC parameter, so counting it would falsely
     * desync a perfectly valid, injection-safe statement (a spurious E001 reject). The same quote-aware
     * walk {@link io.titan.transpiler.jdbc.JdbcSubqueryFusion} uses for the fusion bind-split boundary.
     */
    private static long countQuestionMarksOutsideStringLiterals(String text) {
        return io.titan.transpiler.jdbc.JdbcSubqueryFusion.countQuestionMarksOutsideStringLiterals(text);
    }

    /**
     * Runs the constant-SQL gate for a statement state at most once (an executeQuery whose result is
     * never consumed is gated at the call site; a consumed read/update is gated in {@link #buildRawSql}
     * — the idempotency set keeps a method from double-reporting). Mirrors the recognizer's
     * {@code gateStatementSql} placement (the gate lives at the execute/read sites, the single source of
     * acceptance — WS-C Phase 2b security audit).
     */
    private void gateStatementSqlOnce(StatementState state) {
        if (state != null && !gatedStates.add(state)) {
            return;
        }
        // §3.6 form 1: query B of a proven fusion does NOT use its own (runtime-IN) sqlArg — buildRawSql
        // emits the fully-constant fused `… IN (<A sql>)` text instead, so the original non-constant
        // concatenation must not be gated (it would falsely reject the fused, injection-safe statement).
        if (state != null && state.fusionPlan != null) {
            return;
        }
        // §3.6 form 2: query B of a proven collection-IN does NOT use its own (runtime per-element IN-run)
        // sqlArg — buildRawSql emits the array-bind text (the membership marker + the collection bound as
        // one array/JSON param) instead, so the original non-constant concatenation must not be gated (it
        // would falsely reject the array-bound, injection-safe statement).
        if (state != null && state.collectionInPlan != null) {
            return;
        }
        // Design contract D4: query B of a proven guarded predicate does NOT use its own (runtime-built
        // `sql.toString()`) sqlArg — buildRawSql emits the fully-static guarded-predicate text instead (the
        // base AND each `(? IS NULL OR col OP ?)`, all values bound), so the original non-constant
        // StringBuilder text must not be gated (it would falsely reject the static, injection-safe query).
        if (state != null && state.guardedPredicatePlan != null) {
            return;
        }
        if (state != null && constantStringValue(state.sqlArg) != null) {
            return;
        }
        // WS-C Phase 3 Rung 1: a non-constant text whose recovered skeleton is all value-bindable is
        // ACCEPTED in both modes (buildRawSql reconstructs it) — so it must NOT be gated here. This
        // matters for a consumed read: executeQuery gates first (at the call site), then buildRawSql runs
        // at the consuming if/while; without this skip the call-site gate would E004 a method buildRawSql
        // would have accepted. The gate stays exactly as strict as before for an IDENTIFIER/RAW_FRAGMENT
        // (not value-bindable) skeleton.
        if (state != null && isValueBindableSkeleton(state.sqlArg)) {
            return;
        }
        // WS-C Phase 3 Rung 3: under a PERMISSIVE scope, a recovered skeleton with an IDENTIFIER/RAW_FRAGMENT
        // hole is now EMITTED (buildSpliceRawSql assembles the SQL text at runtime), not deferred — so it
        // must NOT be gated here either (the call-site gate would otherwise fire the "deferred to Rung 3"
        // E001 for a method buildRawSql now accepts). STRICT is unchanged: the splice still falls through to
        // gateNonConstantSql below and REJECTS with the §5.3 E004 (the D2 boundary held — never escalated).
        if (safetyMode == SqlSafetyMode.PERMISSIVE
                && state != null && isSpliceEmittableSkeleton(state.sqlArg)) {
            return;
        }
        gateNonConstantSql(state == null ? null : state.sqlArg);
    }

    /**
     * Whether {@code sqlArg}'s recovered skeleton has at least one hole but is <b>not</b> all
     * value-bindable — i.e. it contains an {@code IDENTIFIER}/{@code RAW_FRAGMENT} hole that {@link
     * #buildSpliceRawSql} emits under a PERMISSIVE scope (WS-C Phase 3 Rung 3). The call-site-gate skip
     * companion of {@link #isValueBindableSkeleton}, scoped to permissive by the caller so a STRICT scope
     * is never relaxed.
     */
    private boolean isSpliceEmittableSkeleton(ExpressionTree sqlArg) {
        return io.titan.transpiler.jdbc.JdbcSqlSkeletonRecognizer.recover(sqlArg)
                .filter(io.titan.transpiler.jdbc.JdbcSqlSkeleton::hasHole)
                .filter(skeleton -> !skeleton.allHolesValueBindable())
                .isPresent();
    }

    /**
     * Whether {@code sqlArg}'s recovered {@link io.titan.transpiler.jdbc.JdbcSqlSkeleton} has at least
     * one hole and every hole is value-bindable (design contract D2). The single predicate keying the
     * Rung 1 accept path, shared by {@link #buildRawSql} (emit from the skeleton) and {@link
     * #gateStatementSqlOnce} (skip the reject) so the call-site gate and the consuming-site emission
     * cannot disagree.
     */
    private boolean isValueBindableSkeleton(ExpressionTree sqlArg) {
        return io.titan.transpiler.jdbc.JdbcSqlSkeletonRecognizer.recover(sqlArg)
                .filter(io.titan.transpiler.jdbc.JdbcSqlSkeleton::hasHole)
                .filter(io.titan.transpiler.jdbc.JdbcSqlSkeleton::allHolesValueBindable)
                .isPresent();
    }

    /**
     * Enforces the constant-SQL gate at lowering time for SQL text that did not resolve to a
     * compile-time constant <i>and</i> whose recovered skeleton is not all-value-bindable (WS-C Phase 3
     * Rung 1: the value-bindable case is accepted in {@link #buildRawSql} and never reaches here). The
     * skeleton therefore contains an {@code IDENTIFIER}/{@code RAW_FRAGMENT} hole (a value/identifier
     * that would reach the SQL text), or no recoverable skeleton at all. STRICT → the recognizer's exact
     * §5.3 three-part {@code TITAN-E004} (fails the build); PERMISSIVE → a clear {@code TITAN-E001}
     * naming the Rung 3 deferral (identifier / raw-fragment splice emission is the next rung) — never a
     * silent empty {@code EXECUTE ''}. {@code sqlArg} may be {@code null} when the SQL handle's text
     * could not be resolved at all (still gated, never emitted empty).
     */
    private void gateNonConstantSql(ExpressionTree sqlArg) {
        if (safetyMode == SqlSafetyMode.PERMISSIVE) {
            diagnostics.error("non-constant SQL text in this JDBC method builds the SQL from a runtime "
                    + "identifier or an opaque fragment (not a bindable value), so it cannot yet be "
                    + "reconstructed into a single-dialect RawSql at lowering time (permissive scope). Value "
                    + "splices and placeholder runs already transpile; runtime identifier (table/column) and "
                    + "raw-fragment splices are deferred to a later rung (Rung 3). Keep the SQL text constant "
                    + "and bind runtime values as parameters, validate an identifier against a known catalog "
                    + "set, or use a typed DSL/@SQL read (docs/transpilable-jdbc-subset.md §4)");
            return;
        }
        if (sqlArg == null) {
            diagnostics.error("the SQL text passed to prepareStatement(...)/execute(...) could not be resolved "
                    + "to a compile-time constant; keep it constant and bind runtime values as parameters "
                    + "(docs/transpilable-jdbc-subset.md §5.3)");
            return;
        }
        diagnostics.sqlSafetyError(io.titan.transpiler.jdbc.JdbcSqlSafetyDiagnostics.strictSpliceDiagnostic(sqlArg));
    }

    /**
     * Rejects a recovered all-value-bindable skeleton whose surviving {@code ?} placeholder count does
     * not match the resolved {@code USING} bind count (WS-C Phase 3 Rung 1 arity invariant, design
     * contract task item c). This is NOT a hole-kind/safety reject (every hole is value-bindable, so the
     * {@code gateNonConstantSql} path would skip it) — it is a correctness reject: emitting the skeleton
     * would ship an {@code EXECUTE} whose {@code USING} arity differs from its placeholders, failing at
     * CALL with "there is no parameter $N" / "too many parameters". Rejected in BOTH modes (it can never
     * deploy). Recorded (not thrown) like the other gates; the aggregated diagnostic fails the build.
     */
    private void rejectPlaceholderBindArity(String recoveredSql, int placeholderCount, int bindCount) {
        diagnostics.error("this JDBC method builds non-constant SQL whose recovered placeholder count ("
                + placeholderCount + " '?') does not match the number of bound parameters (" + bindCount
                + "). Emitting it would desync the EXECUTE … USING binds from the placeholders (a runtime "
                + "'there is no parameter $N' / 'too many parameters' error). This usually means a recovered "
                + "'?' has no matching ps.setXxx(...) bind, or a runtime value was spliced adjacent to a "
                + "placeholder. Bind every '?' with a setXxx call and keep value splices separated from "
                + "placeholders (docs/transpilable-jdbc-subset.md §3.6). Recovered text: " + recoveredSql);
    }

    private List<String> bindParameterNames(
            StatementState state,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        if (state == null || state.binds.isEmpty()) {
            return List.of();
        }
        int maxOrdinal = state.binds.keySet().stream().max(Integer::compareTo).orElse(0);
        List<String> names = new ArrayList<>();
        for (int ordinal = 1; ordinal <= maxOrdinal; ordinal++) {
            ExpressionTree boundExpr = state.binds.get(ordinal);
            if (boundExpr == null) {
                continue;
            }
            ExpressionNode inlineValue = state.inlineBindValues.get(ordinal);
            if (inlineValue != null) {
                names.add(synthesizeBindLocal(
                        inlineValue,
                        state.inlineBindTypes.getOrDefault(ordinal, new TTextType()),
                        blockDecls,
                        statements));
            } else {
                names.add(bindNameFor(boundExpr, blockDecls, statements));
            }
        }
        return names;
    }

    /**
     * The bind name for a bound expression, shared by the constant path ({@link #bindParameterNames})
     * and the skeleton path ({@link #skeletonBindParameterNames}) so the two cannot drift: a bare-variable
     * bind is referenced directly; any other expression synthesizes a {@code __titan_pN} local.
     */
    private String bindNameFor(
            ExpressionTree boundExpr,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        ExpressionTree unwrapped = JdbcShapes.unwrap(boundExpr);
        if (unwrapped instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        return synthesizeBindLocal(boundExpr, blockDecls, statements);
    }

    /**
     * Synthesizes a {@code __titan_pN := <lowered expr>} bind local (DECLARE appended to {@code
     * blockDecls}, an initialising {@link Assign} appended to {@code statements} <i>before</i> the
     * consuming statement) and returns its name. The single synthesis seam for both bind paths (constant
     * and skeleton): a VALUE-hole splice and an expression {@code setXxx} bind go through the identical
     * machinery, so the value is parameterized and never reaches the SQL text.
     */
    private String synthesizeBindLocal(
            ExpressionTree boundExpr,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        return synthesizeBindLocal(
                ExpressionLowerer.lowerExpression(boundExpr, parsed),
                safeMap(typeOfBind(boundExpr)),
                blockDecls,
                statements);
    }

    private String synthesizeBindLocal(
            ExpressionNode value,
            TirType type,
            List<DeclarationNode> blockDecls,
            List<StatementNode> statements
    ) {
        bindLocalCounter++;
        String bindLocal = "__titan_p" + bindLocalCounter;
        blockDecls.add(new DeclareVariable(bindLocal, type, true, null));
        statements.add(new Assign(new VariableRefExpression(bindLocal), value));
        return bindLocal;
    }

    private TypeMirror typeOfBind(ExpressionTree expr) {
        TreePath path = LowererSupport.resolveTreePath(parsed, expr);
        return path == null ? null : parsed.trees().getTypeMirror(path);
    }

    private TirType safeMap(TypeMirror type) {
        try {
            return LowererSupport.mapType(type, "JDBC bind value");
        } catch (RuntimeException ex) {
            return new TTextType();
        }
    }

    // ---- shared state access ----

    private StatementState statementStateFor(ExpressionTree receiverExpr, TreePath path) {
        Element receiver = shapes.elementOf(receiverExpr, path);
        return receiver == null ? null : statementHandles.get(receiver);
    }

    private StatementState driverStateFor(Element resultSet) {
        if (resultSet == null) {
            return null;
        }
        Element driver = resultSetDriver.get(resultSet);
        return driver == null ? null : statementHandles.get(driver);
    }

    // ---- column-read recognition ----

    private ColumnRead asColumnRead(ExpressionTree expr, TreePath path, Element expectedResultSet) {
        ExpressionTree unwrapped = JdbcShapes.unwrap(expr);
        if (!(unwrapped instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return null;
        }
        String name = select.getIdentifier().toString();
        // A column read is either a single-arg getter `getXxx(col)` or the typed two-arg
        // `getObject(col, Type.class)` (the only idiomatic read for a column whose Java type has no
        // dedicated getter, e.g. java.util.UUID). Both forms take the column key as the first argument;
        // the read type is the call's static type (T for the typed getObject). This must agree with
        // JdbcUsageRecognizer's recognizeColumnRead — otherwise a recognized read would lower to an
        // empty INTO target (TG: the typed-getObject divergence).
        boolean singleArgGetter = name.startsWith("get") && name.length() > 3 && invocation.getArguments().size() == 1;
        if (!singleArgGetter && !JdbcShapes.isTypedGetObjectColumnRead(invocation)) {
            return null;
        }
        TypeMirror receiverType = shapes.typeOf(select.getExpression(), pathFor(invocation, path));
        if (receiverType == null || !oracle.isResultSet(receiverType)) {
            return null;
        }
        Element receiver = shapes.elementOf(select.getExpression(), pathFor(invocation, path));
        if (expectedResultSet != null && !expectedResultSet.equals(receiver)) {
            return null;
        }
        ExpressionTree columnArg = JdbcShapes.unwrap(invocation.getArguments().get(0));
        String column = columnKeyText(columnArg);
        if (column == null) {
            return null;
        }
        TypeMirror readType = shapes.typeOf(invocation, pathFor(invocation, path));
        return new ColumnRead(receiver, column, safeMap(readType));
    }

    private TreePath pathFor(Tree node, TreePath fallback) {
        TreePath resolved = LowererSupport.resolveTreePath(parsed, node);
        if (resolved != null) {
            return resolved.getParentPath();
        }
        return fallback;
    }

    private static String columnKeyText(ExpressionTree columnArg) {
        if (columnArg instanceof LiteralTree literal) {
            Object value = literal.getValue();
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof Integer i) {
                return "#" + i;
            }
        }
        return null;
    }

    /**
     * A stable, non-empty identifier fragment for the per-row FETCH local name. A named column
     * sanitizes to its name; an ordinal read ({@code columnKeyText} returns {@code "#N"}) becomes
     * {@code colN} rather than sanitizing to an empty fragment (which produced a double-underscore
     * {@code __titan_row__N_0} local — WS-C Phase 2b polish).
     */
    private static String rowLocalFragment(String column) {
        if (column.startsWith("#")) {
            return "col" + column.substring(1);
        }
        String sanitized = DslQueryLowerer.sanitizeIdentifier(column);
        return sanitized.isEmpty() ? "col" : sanitized;
    }

    // ---- constant SQL extraction (mirrors RawSqlConstantRule's accepted shapes) ----

    private String constantStringValue(ExpressionTree expression) {
        if (expression == null) {
            return null;
        }
        ExpressionTree stripped = JdbcShapes.unwrap(expression);
        if (stripped instanceof LiteralTree literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (stripped instanceof com.sun.source.tree.BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = constantStringValue(binary.getLeftOperand());
            String right = constantStringValue(binary.getRightOperand());
            return (left == null || right == null) ? null : left + right;
        }
        if (stripped instanceof IdentifierTree || stripped instanceof MemberSelectTree) {
            TreePath path = LowererSupport.resolveTreePath(parsed, stripped);
            Element element = path == null ? null : parsed.trees().getElement(path);
            if (element instanceof VariableElement variable
                    && variable.getKind() == ElementKind.FIELD
                    && variable.getModifiers().contains(Modifier.STATIC)
                    && variable.getModifiers().contains(Modifier.FINAL)
                    && variable.getConstantValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    // ---- guard helpers ----

    private Element guardResultSet(IfTree ifTree, TreePath path) {
        ExpressionTree condition = JdbcShapes.unwrap(ifTree.getCondition());
        if (condition instanceof com.sun.source.tree.UnaryTree unary) {
            return shapes.resultSetNextReceiver(JdbcShapes.unwrap(unary.getExpression()), path);
        }
        return null;
    }

    private StatementTree guardThrowStatement(StatementTree thenStatement) {
        StatementTree terminal = thenStatement;
        if (terminal instanceof BlockTree block && !block.getStatements().isEmpty()) {
            terminal = block.getStatements().get(block.getStatements().size() - 1);
        }
        return terminal;
    }

    // ---- nested records ----

    private static final class StatementState {
        ExpressionTree sqlArg;
        final Map<Integer, ExpressionTree> binds = new LinkedHashMap<>();
        /** Pre-lowered bind values captured while an inline helper's substitutions are active. */
        final Map<Integer, ExpressionNode> inlineBindValues = new LinkedHashMap<>();
        final Map<Integer, TirType> inlineBindTypes = new LinkedHashMap<>();
        // I-7 (§6.3 / I-R8): prepareStatement(SQL, RETURN_GENERATED_KEYS) — the executeUpdate over
        // this statement reaches the I-R8 reject (no Catalog to resolve the RETURNING key column).
        boolean returnGeneratedKeys;
        // I-7: the routine local the generated key would be read into, allocated when the executeUpdate
        // is lowered; the getGeneratedKeys() ResultSet's getLong(1)/getInt(1) resolves to it (so the
        // trailing read lowers cleanly even though I-7 is rejected).
        String generatedKeyLocal;
        // §3.6 form 1 (subquery fusion, Rung 2): when non-null, this statement is query B of a proven
        // fusion — buildRawSql emits the fused `<B prefix>(<A sql>)<B suffix>` text with A's binds and
        // B's binds merged in text order, instead of B's own (runtime IN-run) SQL. A's prepare/execute/
        // loop and the `ids` collection are elided at the block level.
        io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan fusionPlan;
        // §3.6 form 2 (collection binding, Rung 4): when non-null, this statement is query B of a proven
        // collection-IN — buildRawSql emits the array-bind RawSql (the <col> IN (run) replaced by a
        // membership marker the emitter renders as PG `= ANY($n)` / MySQL `JSON_TABLE`, the whole
        // collection bound as one array/JSON parameter), instead of B's own per-element runtime IN-run.
        // The per-element bind loop / setArray is elided at the block level.
        io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan collectionInPlan;
        // Design contract D4: when non-null, this statement is query B of a proven optional-filter guarded
        // predicate — buildRawSql emits ONE static guarded-predicate query (the constant base AND one `(?
        // IS NULL OR col OP ?)` per optional clause, all values bound, no dynamic EXECUTE), instead of B's
        // runtime-built `sql.toString()` text. The StringBuilder declaration, append `if`s, bind counter,
        // and bind `if`s are elided at the block level.
        io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan guardedPredicatePlan;
    }

    private record ColumnRead(Element receiver, String column, TirType tirType) { }

    private record ColumnKey(Element receiver, String column) { }
}
