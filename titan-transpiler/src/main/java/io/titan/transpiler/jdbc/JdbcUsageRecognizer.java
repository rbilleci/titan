package io.titan.transpiler.jdbc;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointKind;
import io.titan.transpiler.MethodSignatureKeys;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import io.titan.transpiler.tir.RawSqlConstantRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * WS-C Phase 1 JDBC recognizer: a {@link TreePathScanner} over the <b>shared</b>
 * {@link ParsedSources} (after {@code EntryPointDiscovery}) that classifies each method's
 * {@code java.sql} usage into {@link JdbcUsage}s per {@code docs/transpilable-jdbc-subset.md}.
 * It does <b>not</b> create a second javac parse, produces no TIR, and changes no emitter — it is a
 * pure recognition + classification pass whose {@code visit*} mirror the in-scope set a later
 * lowering phase can handle.
 *
 * <p>It locates each {@link DiscoveredEntryPoint}'s {@link MethodTree} by re-scanning and matching
 * the {@code enclosing#name(types)} key, exactly as {@code JavaToTirLowerer.lower} does. State per
 * method is a {@code Map<Element, JdbcHandle>} keyed by the resolved local of each
 * {@code PreparedStatement}/{@code Statement}/{@code ResultSet}.</p>
 *
 * <h2>Strict/permissive gate</h2>
 * The constant-SQL gate (§4 step 4) applies <b>only</b> to the SQL argument of
 * {@code prepareStatement}/{@code createStatement(...).execute*(SQL)}; every other rule is
 * unconditional. It uses {@link RawSqlConstantRule#isCompileTimeConstantSqlText}. Non-constant SQL
 * is REJECTED+E004 (the §5.3 three-part diagnostic) in a strict scope and PASSTHROUGH in a
 * permissive scope — except the §3.6 placeholder-count IN-list carve-out, which is value-binding by
 * construction and stays TRANSPILABLE in <b>both</b> modes (and is FAIL-SAFE: only a provable
 * {@code ?}/separator-only run with every ordinal bound is accepted; anything ambiguous falls
 * through to the strict gate, never false-accepting a value splice).
 */
public final class JdbcUsageRecognizer {

    /** What a tracked {@code java.sql} local is. */
    private enum HandleKind { PREPARED_STATEMENT, PLAIN_STATEMENT, RESULT_SET, CALLABLE_STATEMENT }

    /** Mutable per-method record of a recognized JDBC handle local. */
    private static final class JdbcHandle {
        final HandleKind kind;
        // The SQL-text argument of prepareStatement / the createStatement+execute call (may be null
        // for a plain Statement whose SQL arrives at execute time).
        ExpressionTree sqlArg;
        boolean returnGeneratedKeys;
        // For a ResultSet: the statement handle element that produced it (executeQuery driver).
        Element drivingStatement;
        // For a ResultSet: true when it came from ps.getGeneratedKeys() — its bare next() is part of
        // the I-7 generated-keys trailer (§6.3) and is NOT the rejected bare-next single-row shape.
        boolean fromGeneratedKeys;
        // For a getGeneratedKeys() ResultSet: how many getX(...) key reads have been seen. I-7 is
        // transpilable for the single getLong(1)/getInt(1) shape; this count lets an off-shape read (a
        // second read, or a non-single-int read) get its precise I-R8 diagnostic — matching the 2b
        // lowerer's reject (no silent gap).
        int genKeyReadCount;
        // True once a terminal execute/executeQuery/executeUpdate has been seen on this handle.
        boolean executed;

        JdbcHandle(HandleKind kind) {
            this.kind = kind;
        }
    }

    public List<MethodJdbcReport> recognize(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            SqlSafetyResolver safetyResolver
    ) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        if (entryPoints == null) {
            throw new IllegalArgumentException("entryPoints must not be null");
        }
        if (safetyResolver == null) {
            throw new IllegalArgumentException("safetyResolver must not be null");
        }

        Map<String, DiscoveredEntryPoint> byKey = new HashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            byKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        JdbcTypeOracle oracle = new JdbcTypeOracle(parsedSources);
        List<MethodJdbcReport> reports = new ArrayList<>();

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath path = getCurrentPath();
                    Element element = parsedSources.trees().getElement(path);
                    if (!(element instanceof ExecutableElement method)) {
                        return super.visitMethod(node, unused);
                    }
                    String key = MethodSignatureKeys.of(method);
                    DiscoveredEntryPoint entryPoint = byKey.get(key);
                    if (entryPoint == null || node.getBody() == null) {
                        return super.visitMethod(node, unused);
                    }
                    SqlSafetyMode mode = safetyResolver.effectiveMode(method);
                    MethodScan scan = new MethodScan(parsedSources, oracle, unit, entryPoint, mode);
                    List<JdbcUsage> usages = scan.run(new TreePath(path, node.getBody()));
                    reports.add(new MethodJdbcReport(
                            entryPoint.className(),
                            entryPoint.methodName(),
                            entryPoint.sourceFile(),
                            entryPoint.line(),
                            entryPoint.kind(),
                            usages));
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }
        return List.copyOf(reports);
    }

    /**
     * Single-method scan: walks the body collecting {@link JdbcUsage}s into source order, holding
     * the per-method handle map. A nested {@link TreePathScanner} keeps the dispatch close to
     * {@code StatementLowerer.lowerStatement}'s switch so "in scope" stays aligned.
     */
    private static final class MethodScan {

        private final ParsedSources parsed;
        private final JdbcTypeOracle oracle;
        private final JdbcShapes shapes;
        private final CompilationUnitTree unit;
        private final EntryPointKind entryKind;
        private final SqlSafetyMode mode;

        private final Map<Element, JdbcHandle> handles = new HashMap<>();
        private final List<JdbcUsage> usages = new ArrayList<>();

        MethodScan(
                ParsedSources parsed,
                JdbcTypeOracle oracle,
                CompilationUnitTree unit,
                DiscoveredEntryPoint entryPoint,
                SqlSafetyMode mode
        ) {
            this.parsed = parsed;
            this.oracle = oracle;
            this.shapes = new JdbcShapes(parsed, oracle);
            this.unit = unit;
            this.entryKind = entryPoint.kind();
            this.mode = mode;
        }

        List<JdbcUsage> run(TreePath bodyPath) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    recognizeDeclaration(node, getCurrentPath());
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    recognizeInvocation(node, getCurrentPath());
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitIf(IfTree node, Void unused) {
                    recognizeIf(node, getCurrentPath());
                    return super.visitIf(node, unused);
                }

                @Override
                public Void visitWhileLoop(WhileLoopTree node, Void unused) {
                    recognizeWhile(node, getCurrentPath());
                    return super.visitWhileLoop(node, unused);
                }

                @Override
                public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
                    recognizeDoWhile(node, getCurrentPath());
                    return super.visitDoWhileLoop(node, unused);
                }

                @Override
                public Void visitReturn(ReturnTree node, Void unused) {
                    recognizeReturn(node, getCurrentPath());
                    return super.visitReturn(node, unused);
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    recognizeAssignment(node, getCurrentPath());
                    return super.visitAssignment(node, unused);
                }

                @Override
                public Void visitTry(TryTree node, Void unused) {
                    recognizeTry(node, getCurrentPath());
                    return super.visitTry(node, unused);
                }
            }.scan(bodyPath, null);
            return List.copyOf(usages);
        }

        // ---- I-9: try-with-resources / catch(SQLException) around JDBC ----

        private void recognizeTry(TryTree node, TreePath path) {
            // I-9 (§3.5): a try-with-resources holding a JDBC handle, or a catch(SQLException) around
            // JDBC, is TRANSPILABLE — the resource close() is elided and SQLException maps via
            // ExceptionSqlStateRegistry. Record an explicit I-9 usage so the per-idiom coverage is not
            // invisible (the inner statements are recognized separately; this marks the I-9 shape).
            boolean jdbcResource = false;
            for (Tree resource : node.getResources()) {
                if (resource instanceof VariableTree resourceVar) {
                    TypeMirror type = parsed.trees().getTypeMirror(new TreePath(path, resourceVar));
                    if (type != null && oracle.isJdbcHandle(type)) {
                        jdbcResource = true;
                        break;
                    }
                }
            }
            boolean catchesSqlException = false;
            for (CatchTree catchClause : node.getCatches()) {
                TypeMirror caught =
                        parsed.trees().getTypeMirror(new TreePath(path, catchClause.getParameter()));
                if (caught != null && oracle.isSqlException(caught)) {
                    catchesSqlException = true;
                    break;
                }
            }
            if (jdbcResource || catchesSqlException) {
                accept(JdbcIdiom.I_9, node, path);
            }
        }

        // ---- declarations: anchor JDBC handle locals (and try-with-resources resources) ----

        private void recognizeDeclaration(VariableTree node, TreePath path) {
            TypeMirror type = parsed.trees().getTypeMirror(path);
            if (type == null || !oracle.isJdbcHandle(type)) {
                return;
            }
            Element local = parsed.trees().getElement(path);
            if (local == null) {
                return;
            }
            if (oracle.isResultSet(type)) {
                JdbcHandle handle = new JdbcHandle(HandleKind.RESULT_SET);
                bindResultSetInitializer(node.getInitializer(), handle, path);
                handles.put(local, handle);
                return;
            }
            if (oracle.isCallableStatement(type)) {
                handles.put(local, new JdbcHandle(HandleKind.CALLABLE_STATEMENT));
                // The prepareCall(...) initializer is rejected when recognized as an invocation.
                return;
            }
            if (oracle.isPreparedStatement(type)) {
                JdbcHandle handle = new JdbcHandle(HandleKind.PREPARED_STATEMENT);
                handles.put(local, handle);
                bindPrepareInitializer(node.getInitializer(), handle, path);
                return;
            }
            if (oracle.isPlainStatement(type)) {
                handles.put(local, new JdbcHandle(HandleKind.PLAIN_STATEMENT));
                // createStatement(...) initializer recognized for scrollable/updatable reject below.
                inspectCreateStatement(node.getInitializer(), path);
                return;
            }
            // Connection/DataSource locals are anchors with no further per-handle state.
        }

        private void bindResultSetInitializer(ExpressionTree initializer, JdbcHandle handle, TreePath path) {
            ExpressionTree expr = unwrap(initializer);
            if (expr instanceof MethodInvocationTree invocation
                    && invocation.getMethodSelect() instanceof MemberSelectTree select) {
                Element receiver = receiverElement(select, path);
                if (receiver != null) {
                    handle.drivingStatement = receiver;
                }
                if (select.getIdentifier().contentEquals("getGeneratedKeys")) {
                    handle.fromGeneratedKeys = true;
                }
            }
        }

        private void bindPrepareInitializer(ExpressionTree initializer, JdbcHandle handle, TreePath path) {
            ExpressionTree expr = unwrap(initializer);
            if (expr instanceof MethodInvocationTree invocation) {
                applyPrepareCall(invocation, handle);
            }
        }

        // ---- invocations: the heart of the dispatch ----

        private void recognizeInvocation(MethodInvocationTree node, TreePath path) {
            if (!(node.getMethodSelect() instanceof MemberSelectTree select)) {
                return;
            }
            ExpressionTree receiverExpr = select.getExpression();
            String name = select.getIdentifier().toString();
            TypeMirror receiverType = typeOf(receiverExpr, path);
            if (receiverType == null) {
                return;
            }

            if (oracle.isConnection(receiverType) || oracle.isDataSource(receiverType)) {
                recognizeConnectionCall(name, node, path);
                return;
            }
            if (oracle.isCallableStatement(receiverType)) {
                // §5.1: any use of a CallableStatement is out of scope.
                reject(JdbcIdiom.CALLABLE_STATEMENT, node, path,
                        "CallableStatement is not supported; call the target Java method directly so "
                                + "Titan lowers it to a CallStatement (docs/transpilable-jdbc-subset.md §5.1)");
                return;
            }
            if (oracle.isPreparedStatement(receiverType)) {
                recognizePreparedStatementCall(name, node, receiverExpr, path);
                return;
            }
            if (oracle.isPlainStatement(receiverType)) {
                recognizePlainStatementCall(name, node, path);
                return;
            }
            if (oracle.isResultSet(receiverType)) {
                recognizeResultSetCall(name, node, receiverExpr, path);
                return;
            }
        }

        private void recognizeConnectionCall(String name, MethodInvocationTree node, TreePath path) {
            switch (name) {
                case "setAutoCommit" ->
                        // I-10: elided with informational W005 (no in-routine analogue).
                        passthroughWarn(JdbcIdiom.I_10, node, path,
                                "setAutoCommit is elided: the routine already runs as one server-side unit "
                                        + "(docs/transpilable-jdbc-subset.md §3.4)");
                case "commit", "rollback" -> recognizeTransactionControl(name, node, path);
                case "setSavepoint", "releaseSavepoint" ->
                        // §3.4 item 5: savepoints out of scope v1.
                        reject(JdbcIdiom.I_10, node, path,
                                "savepoints are not supported in v1 (docs/transpilable-jdbc-subset.md §3.4)");
                case "getMetaData" ->
                        reject(JdbcIdiom.I_R6, node, path,
                                "DatabaseMetaData introspection is not supported in transpiled code "
                                        + "(docs/transpilable-jdbc-subset.md §5 I-R6)");
                case "prepareCall" ->
                        reject(JdbcIdiom.CALLABLE_STATEMENT, node, path,
                                "CallableStatement / prepareCall is not supported; call the target Java method "
                                        + "directly (docs/transpilable-jdbc-subset.md §5.1)");
                case "createStatement" -> inspectCreateStatement(node, path);
                case "prepareStatement" -> {
                    // Recognized via the enclosing declaration; a bare prepareStatement(...) with no
                    // handle (e.g. inlined) still gets its SQL gated here so nothing is missed.
                    if (!hasEnclosingHandleDeclaration(path)) {
                        JdbcHandle scratch = new JdbcHandle(HandleKind.PREPARED_STATEMENT);
                        applyPrepareCall(node, scratch);
                        gateStatementSql(scratch.sqlArg, JdbcIdiom.I_1, node, path);
                    }
                }
                default -> {
                    // close()/isClosed()/getAutoCommit()/... elided, no diagnostic.
                }
            }
        }

        // NOTE — the MySQL-stored-FUNCTION dynamic-SQL reject (§9 invariant 1; ERROR 1336) is NOT mirrored
        // here, by design. Unlike transaction control below — which no FUNCTION/trigger can do on ANY dialect,
        // so the verdict is dialect-independent and belongs in this dialect-agnostic report — the dynamic-SQL
        // reject is dialect-CONDITIONAL: a JDBC read/execute in a @StoredFunction emits a dynamic PREPARE that
        // fails to CREATE only on MySQL; the identical code transpiles + deploys fine as a PostgreSQL
        // @StoredFunction (PG functions allow dynamic EXECUTE) or as a @StoredProcedure on either dialect. The
        // titanJdbcCompatReport path carries no target dialect (JdbcCompatLinter.lint takes only sources +
        // sqlSafety — there is no DialectId input on TitanJdbcCompatReportTask), so classifying such a method
        // REJECTED unconditionally would FALSE-reject the perfectly-transpilable PG-function / @StoredProcedure
        // case. The reject is therefore enforced where the dialect is known: the codegen lowerer
        // (JdbcStatementLowerer.rejectIfMysqlStoredFunctionEmitsDynamicSql, gated on mysqlTargeted AND an
        // inline-emitted kind — STORED_FUNCTION or TRIGGER; a @ScheduledJob is emitted as a backing PROCEDURE
        // where dynamic SQL is legal, so it is NOT gated), which IS the build-failing fix. Threading the
        // target dialect into this report is a possible follow-up if the compat report ever needs to surface
        // the MySQL-specific verdict.
        private void recognizeTransactionControl(String name, MethodInvocationTree node, TreePath path) {
            // I-10 txn rule: procedure-backed (a @StoredProcedure, or a @ScheduledJob — emitted as a backing
            // procedure on both dialects) -> TRANSPILABLE + mandatory W005; FUNCTION/TRIGGER -> REJECTED E001
            // (no dialect permits transaction control there). Gating on EntryPointKind, not a procedure proxy.
            if (entryKind == EntryPointKind.STORED_PROCEDURE || entryKind == EntryPointKind.SCHEDULED_JOB) {
                String routine = entryKind == EntryPointKind.STORED_PROCEDURE ? "@StoredProcedure" : "@ScheduledJob";
                passthroughWarn(JdbcIdiom.I_10, node, path,
                        name + "() is emitted only in a " + routine + " and behaves correctly only when the "
                                + "procedure is not invoked inside an outer atomic transaction; CALL it "
                                + "autonomously (mandatory W005, docs/transpilable-jdbc-subset.md §3.4)");
            } else {
                reject(JdbcIdiom.I_10, node, path,
                        "transaction control is not permitted inside a function/trigger; annotate the method "
                                + "@StoredProcedure or remove the " + name + "() (docs/transpilable-jdbc-subset.md §3.4)");
            }
        }

        private void recognizePreparedStatementCall(
                String name, MethodInvocationTree node, ExpressionTree receiverExpr, TreePath path) {
            JdbcHandle handle = handleFor(receiverExpr, path);
            switch (name) {
                case "executeQuery" -> {
                    // I-3. SQL came from prepareStatement; gate constant-ness. Result-shaped reads over
                    // unparsed SQL are NO LONGER rejected: WS-C Phase 2 emits them natively single-dialect
                    // (PG EXECUTE … INTO / OPEN … FOR EXECUTE; MySQL prepared EXECUTE … INTO / cursor), so
                    // constant SQL is TRANSPILABLE and permissive non-constant is PASSTHROUGH (the I-3
                    // REVISIT flag of docs/archived/ws-c-phase1-jdbc-recognizer-design.md is now reconciled — the
                    // gate's permissive branch already routes to PASSTHROUGH, never E001).
                    gateStatementSql(handle == null ? null : handle.sqlArg, JdbcIdiom.I_3, node, path);
                    if (handle != null) {
                        handle.executed = true;
                    }
                }
                case "executeUpdate", "executeLargeUpdate" ->
                        // I-6.
                        recognizeExecuteUpdate(handle, node, path);
                case "execute" ->
                        // execute() may be a query or DML; treat like an execute-SQL gate.
                        gateStatementSql(handle == null ? null : handle.sqlArg, JdbcIdiom.I_6, node, path);
                case "getGeneratedKeys" -> recognizeGetGeneratedKeys(handle, node, path);
                case "addBatch", "executeBatch", "executeLargeBatch", "clearBatch" ->
                        reject(JdbcIdiom.BATCH, node, path,
                                "JDBC batch is not supported in v1; use for (item : items) executeUpdate(...) "
                                        + "(docs/transpilable-jdbc-subset.md §5.2)");
                default -> {
                    if (name.startsWith("set")) {
                        recognizeSetBinding(name, node, path);
                    }
                    // close()/clearParameters()/... elided.
                }
            }
        }

        private void recognizePlainStatementCall(String name, MethodInvocationTree node, TreePath path) {
            switch (name) {
                case "executeQuery" -> {
                    // I-8 read: createStatement().executeQuery(SQL) — gate the SQL arg.
                    gateStatementSql(firstArg(node), JdbcIdiom.I_8, node, path);
                }
                case "execute" ->
                        gateStatementSql(firstArg(node), JdbcIdiom.I_8, node, path);
                case "executeUpdate", "executeLargeUpdate" -> {
                    // I-8 update over constant SQL.
                    gateStatementSql(firstArg(node), JdbcIdiom.I_6, node, path);
                }
                case "addBatch", "executeBatch", "executeLargeBatch", "clearBatch" ->
                        reject(JdbcIdiom.BATCH, node, path,
                                "JDBC batch is not supported in v1; use for (item : items) executeUpdate(...) "
                                        + "(docs/transpilable-jdbc-subset.md §5.2)");
                default -> {
                    // close() elided.
                }
            }
        }

        private void recognizeExecuteUpdate(JdbcHandle handle, MethodInvocationTree node, TreePath path) {
            gateStatementSql(handle == null ? null : handle.sqlArg, JdbcIdiom.I_6, node, path);
            if (handle != null) {
                handle.executed = true;
            }
        }

        // ---- I-2 indexed parameter binding ----

        private void recognizeSetBinding(String name, MethodInvocationTree node, TreePath path) {
            // ps.setXxx(ordinalLiteral, value): TRANSPILABLE (records ordinal); non-literal ordinal -> REJECTED.
            if (node.getArguments().isEmpty()) {
                return;
            }
            ExpressionTree ordinal = unwrap(node.getArguments().getFirst());
            if (isIntLiteral(ordinal)) {
                accept(JdbcIdiom.I_2, node, path);
            } else {
                reject(JdbcIdiom.I_R2, node, path,
                        "the parameter ordinal of " + name + "(...) must be a constant int literal "
                                + "(docs/transpilable-jdbc-subset.md §5 I-R2)");
            }
        }

        // Bind-application onto a prepared handle from its prepareStatement(...) call.
        private void applyPrepareCall(MethodInvocationTree invocation, JdbcHandle handle) {
            List<? extends ExpressionTree> args = invocation.getArguments();
            if (!args.isEmpty()) {
                handle.sqlArg = args.getFirst();
            }
            if (args.size() >= 2 && isReturnGeneratedKeys(args.get(1))) {
                handle.returnGeneratedKeys = true;
            }
        }

        // ---- the strict/permissive constant-SQL gate (applies ONLY to statement SQL args) ----

        private void gateStatementSql(ExpressionTree sqlArg, JdbcIdiom idiom, MethodInvocationTree node, TreePath path) {
            if (sqlArg == null) {
                // The execute is over a handle whose SQL could not be resolved (e.g. a ternary-/field-
                // initialized prepare, or an unrecognized initializer) — no SQL text was proven. A
                // measurement tool must not report that as clean TRANSPILABLE; record it as a
                // PASSTHROUGH 'unresolved SQL' note (no diagnostic code) so the count is not inflated.
                usages.add(JdbcUsage.passthrough(idiom, location(node, path), null,
                        "the statement's SQL text could not be resolved to a constant at this call site; "
                                + "recorded as unresolved-SQL passthrough rather than proven transpilable "
                                + "(docs/transpilable-jdbc-subset.md §4)"));
                return;
            }
            if (RawSqlConstantRule.isCompileTimeConstantSqlText(sqlArg, unit, parsed)) {
                accept(idiom, node, path);
                return;
            }
            // §3.6(3) placeholder-count IN-list carve-out (DYNAMIC_IN_*): DEFERRED in Phase 1. Phase 1
            // does not parse SQL or track per-ordinal binds across an unparsed ?-run, so it cannot
            // PROVE a runtime-sized run is placeholder/separator-only with every ordinal bound — and
            // the contract forbids ever false-accepting a value splice. The safe forms therefore fall
            // through to the strict gate below (rejected under strict; permissive passthrough), and a
            // genuinely-provable run is implemented in a later phase (see design contract §3.6 note).
            // DynamicInListRecognizer locks the FAIL-SAFE structural guard for that future work.
            // Non-constant SQL text: strict -> E004 reject; permissive -> passthrough. This holds for
            // result-shaped reads too (I-3) — WS-C Phase 2 emits them natively single-dialect, so a
            // permissive-scope unparsed read is PASSTHROUGH (never the old REJECTED-E001 stance).
            if (mode == SqlSafetyMode.PERMISSIVE) {
                usages.add(JdbcUsage.passthrough(JdbcIdiom.I_R1, location(node, path), null,
                        "non-constant SQL text accepted as a single-dialect RawSql passthrough "
                                + "(permissive scope; docs/transpilable-jdbc-subset.md §4)"));
            } else {
                usages.add(JdbcUsage.rejected(JdbcIdiom.I_R1, location(node, path), TitanErrorCode.E004,
                        strictSpliceDiagnostic(sqlArg)));
            }
        }

        /** The §5.3 three-part strict-scope diagnostic (value vs identifier variant). */
        private String strictSpliceDiagnostic(ExpressionTree sqlArg) {
            // Single source of truth shared with the Phase-2 lowerer's in-pipeline gate.
            return JdbcSqlSafetyDiagnostics.strictSpliceDiagnostic(sqlArg);
        }

        // ---- result-shape recognition: I-3 / I-4 / I-5 and the negative lists ----

        private void recognizeIf(IfTree node, TreePath path) {
            // I-4 single-row read: only the two accepted shapes; everything else REJECTED. The shape
            // discrimination lives in JdbcShapes so the recognizer and the lowerer stay aligned.
            switch (shapes.classifyIfShape(node, path)) {
                case THEN_BLOCK_READ, GUARD_THROW -> accept(JdbcIdiom.I_4, node, path);
                case REJECTED -> reject(JdbcIdiom.I_4, node, path,
                        "unsupported single-row ResultSet shape: only if (rs.next()) { ... } and the "
                                + "early-return guard if (!rs.next()) { throw ...; } are recognized in v1 "
                                + "(an else-branch on rs.next() or a non-throwing guard is rejected) "
                                + "(docs/transpilable-jdbc-subset.md §3.3)");
                case NOT_JDBC -> {
                    // An ordinary if — out of JDBC recognition scope, lowered normally.
                }
            }
        }

        private void recognizeWhile(WhileLoopTree node, TreePath path) {
            // I-5 multi-row cursor read: while (rs.next()) { ... }.
            ExpressionTree condition = unwrap(node.getCondition());
            if (!shapes.isResultSetNextCall(condition, path)) {
                return;
            }
            Element driving = shapes.resultSetNextReceiver(condition, path);
            // §3.3 negative list: a while (rs.next()) { ...; break; } whose body unconditionally breaks
            // out after the first iteration is a single-row read disguised as a loop — an enumerated
            // reject (distinct from a legitimate *conditional* break inside a true multi-row loop).
            if (bodyUnconditionallyBreaks(node.getStatement())) {
                reject(JdbcIdiom.I_4, node, path,
                        "unsupported single-row ResultSet shape: while (rs.next()) { ...; break; } used as a "
                                + "single-row read is rejected in v1; use if (rs.next()) { ... } for one row "
                                + "(docs/transpilable-jdbc-subset.md §3.3)");
                return;
            }
            // Guardrail: the loop body must not open a second driving ResultSet, and the ResultSet
            // must not escape (escape is caught by recognizeReturn/recognizeAssignment globally).
            if (bodyOpensNestedCursor(node.getStatement(), driving, path)) {
                reject(JdbcIdiom.I_5, node, path,
                        "a second ResultSet opened inside while (rs.next()) is rejected in v1 (nested cursors "
                                + "are §7 open-question territory; docs/transpilable-jdbc-subset.md §3.3)");
                return;
            }
            accept(JdbcIdiom.I_5, node, path);
        }

        private boolean bodyUnconditionallyBreaks(StatementTree body) {
            return shapes.bodyUnconditionallyBreaks(body);
        }

        private void recognizeDoWhile(DoWhileLoopTree node, TreePath path) {
            // §3.3 negative list: do { ... } while (rs.next()) used as a single-row read is rejected.
            if (isResultSetNextCall(unwrap(node.getCondition()), path)) {
                reject(JdbcIdiom.I_4, node, path,
                        "unsupported single-row ResultSet shape: do/while on rs.next() is rejected in v1 "
                                + "(docs/transpilable-jdbc-subset.md §3.3)");
            }
        }

        private void recognizeResultSetCall(
                String name, MethodInvocationTree node, ExpressionTree receiverExpr, TreePath path) {
            // Scrollable/updatable (I-R5), metadata (I-R6), stream/LOB (I-R7), non-constant column key (I-R2).
            if (isUpdateOrScrollName(name)) {
                reject(JdbcIdiom.I_R5, node, path,
                        "scrollable/updatable ResultSet is not supported; use an UPDATE/DELETE statement or a "
                                + "forward-only read (docs/transpilable-jdbc-subset.md §5 I-R5)");
                return;
            }
            if (name.equals("getMetaData")) {
                reject(JdbcIdiom.I_R6, node, path,
                        "ResultSet metadata introspection is not supported in transpiled code "
                                + "(docs/transpilable-jdbc-subset.md §5 I-R6)");
                return;
            }
            if (isStreamLobName(name)) {
                reject(JdbcIdiom.I_R7, node, path,
                        "stream/LOB streaming handles are not supported; read the value directly (e.g. getBytes/"
                                + "getString) where the column type supports it (docs/transpilable-jdbc-subset.md §5 I-R7)");
                return;
            }
            if (name.startsWith("get") && name.length() > 3) {
                JdbcHandle handle = handleFor(receiverExpr, path);
                if (handle != null && handle.fromGeneratedKeys) {
                    recognizeGeneratedKeyRead(handle, node, path);
                    return;
                }
                recognizeColumnRead(name, node, path);
                return;
            }
            if (name.equals("next") && node.getArguments().isEmpty()) {
                recognizeBareNext(node, receiverExpr, path);
                return;
            }
            // wasNull()/close() are handled structurally (if/while) or elided.
        }

        private void recognizeGeneratedKeyRead(JdbcHandle handle, MethodInvocationTree node, TreePath path) {
            // §6.3: I-7 is accepted at the getGeneratedKeys() call site for the canonical single
            // getLong(1)/getInt(1) shape (see recognizeGetGeneratedKeys). This off-shape check rejects
            // I-R8 when the read is NOT that shape (getString(1), a non-1 ordinal, a boxed/Object
            // accessor, or a SECOND key read), so it gets its precise diagnostic, matching the 2b lowerer
            // (no silent recognizer-transpilable / lowerer-no-op gap). The shape predicate lives in
            // JdbcShapes — the single source of truth the lowerer also consults.
            handle.genKeyReadCount++;
            if (handle.genKeyReadCount > 1 || !JdbcShapes.isSingleIntKeyRead(node)) {
                reject(JdbcIdiom.I_R8, node, path,
                        "generated-key recovery is limited to a single auto-increment/identity integer key "
                                + "read as a single getLong(1)/getInt(1); other generated keys (UUID/sequence/"
                                + "composite, a non-1 ordinal, a non-integer accessor, or more than one key read) "
                                + "must be modeled as an explicit RETURNING/OUT read "
                                + "(docs/transpilable-jdbc-subset.md §5 I-R8)");
            }
            // A single getLong(1)/getInt(1) is the supported I-7 read; the getGeneratedKeys() call
            // site already recorded the I-7 accept, so the in-scope read needs no extra usage.
        }

        private void recognizeBareNext(MethodInvocationTree node, ExpressionTree receiverExpr, TreePath path) {
            // A rs.next() that is the condition of an if/while/do-while is consumed by recognizeIf/
            // recognizeWhile/recognizeDoWhile (its enclosing tree is that loop/branch, possibly via a
            // ! or parentheses), not an expression-statement — leave those to the structural handlers.
            if (!isBareExpressionStatement(path)) {
                return;
            }
            // The generated-keys trailer (§6.3) is `keys.next(); ... getLong(1)` over the ResultSet from
            // ps.getGeneratedKeys(); that bare next() is part of I-7, not the rejected single-row shape.
            JdbcHandle handle = handleFor(receiverExpr, path);
            if (handle != null && handle.fromGeneratedKeys) {
                return;
            }
            // §3.3 negative list: a bare unguarded `rs.next();` single-row read is an enumerated reject.
            reject(JdbcIdiom.I_4, node, path,
                    "unsupported single-row ResultSet shape: a bare unguarded rs.next() is rejected in v1; "
                            + "guard the read with if (rs.next()) { ... } or if (!rs.next()) { throw ...; } "
                            + "(docs/transpilable-jdbc-subset.md §3.3)");
        }

        private void recognizeColumnRead(String name, MethodInvocationTree node, TreePath path) {
            // I-R2: the column must be a constant name string or ordinal int literal.
            if (node.getArguments().isEmpty()) {
                return;
            }
            ExpressionTree key = unwrap(node.getArguments().getFirst());
            if (!isStringLiteral(key) && !isIntLiteral(key)) {
                reject(JdbcIdiom.I_R2, node, path,
                        "ResultSet column must be referenced by a constant name or ordinal, not a variable "
                                + "(docs/transpilable-jdbc-subset.md §5 I-R2)");
                return;
            }
            // A second argument is in scope ONLY for the typed getObject(col, Type.class) form — the
            // canonical read for a column whose Java type has no dedicated getXxx accessor (e.g.
            // java.util.UUID). Any other multi-arg getter (notably the getObject(col, Map) overload) is
            // not lowerable, so reject it rather than accept a shape the lowerer would silently drop to an
            // empty INTO target (parity with JdbcStatementLowerer.asColumnRead).
            if (node.getArguments().size() == 1 || JdbcShapes.isTypedGetObjectColumnRead(node)) {
                // In scope: an actual SELECT-INTO/cursor column read; recorded as part of I-4/I-5.
                return;
            }
            reject(JdbcIdiom.I_R2, node, path,
                    "unsupported ResultSet getter: only getXxx(col) or the typed getObject(col, Type.class) "
                            + "are supported (docs/transpilable-jdbc-subset.md §5 I-R2)");
        }

        private void recognizeGetGeneratedKeys(JdbcHandle handle, MethodInvocationTree node, TreePath path) {
            // I-7 (§6.3): generated-key recovery is accepted for the canonical shape —
            // prepareStatement(SQL, RETURN_GENERATED_KEYS) then a single getLong(1)/getInt(1) over the
            // getGeneratedKeys() ResultSet. The 2b lowerer lowers this to the trigger-immune, driver-
            // faithful form (PostgreSQL INSERT ... RETURNING <col> INTO <local> with the key column
            // resolved from the Catalog; MySQL SET <local> = LAST_INSERT_ID()). This linter parity-accepts
            // the call site; the off-shape read check in recognizeGeneratedKeyRead still rejects I-R8 for a
            // getString(1)/getLong(2)/second-read shape, matching exactly what the lowerer can build (no
            // silent recognizer-transpilable / lowerer-no-op gap). A getGeneratedKeys() WITHOUT
            // RETURN_GENERATED_KEYS yields no keys, so it is rejected.
            if (handle != null && handle.returnGeneratedKeys) {
                accept(JdbcIdiom.I_7, node, path);
                return;
            }
            reject(JdbcIdiom.I_R8, node, path,
                    "generated-key recovery requires prepareStatement(SQL, RETURN_GENERATED_KEYS) and a single "
                            + "auto-increment integer key read as getLong(1)/getInt(1) "
                            + "(docs/transpilable-jdbc-subset.md §5 I-R8)");
        }

        // ---- escape detection (I-R3 / I-R4): returning or storing a handle ----

        private void recognizeReturn(ReturnTree node, TreePath path) {
            if (node.getExpression() == null) {
                return;
            }
            TypeMirror returnedType = typeOf(node.getExpression(), path);
            if (returnedType == null) {
                return;
            }
            if (oracle.isResultSet(returnedType)) {
                reject(JdbcIdiom.I_R3, node, path,
                        "a ResultSet cannot escape a transpiled routine (docs/transpilable-jdbc-subset.md §5 I-R3)");
            } else if (oracle.isConnectionLike(returnedType) || oracle.isAnyStatement(returnedType)) {
                reject(JdbcIdiom.I_R4, node, path,
                        "a Connection/Statement/PreparedStatement cannot escape a transpiled routine "
                                + "(docs/transpilable-jdbc-subset.md §5 I-R4)");
            }
        }

        private void recognizeAssignment(AssignmentTree node, TreePath path) {
            // Storing a handle into a field is an escape (I-R3/I-R4); a plain local reassignment is not.
            TypeMirror valueType = typeOf(node.getExpression(), path);
            if (valueType == null || !oracle.isJdbcHandle(valueType)) {
                return;
            }
            Element target = elementOf(node.getVariable(), path);
            if (target != null && target.getKind() == javax.lang.model.element.ElementKind.FIELD) {
                if (oracle.isResultSet(valueType)) {
                    reject(JdbcIdiom.I_R3, node, path,
                            "a ResultSet cannot be stored in a field of a transpiled routine "
                                    + "(docs/transpilable-jdbc-subset.md §5 I-R3)");
                } else {
                    reject(JdbcIdiom.I_R4, node, path,
                            "a Connection/Statement cannot be stored in a field of a transpiled routine "
                                    + "(docs/transpilable-jdbc-subset.md §5 I-R4)");
                }
            }
        }

        // ---- createStatement scrollable/updatable (I-R5) ----

        private void inspectCreateStatement(ExpressionTree initializer, TreePath path) {
            ExpressionTree expr = unwrap(initializer);
            if (expr instanceof MethodInvocationTree invocation) {
                inspectCreateStatement(invocation, path);
            }
        }

        private void inspectCreateStatement(MethodInvocationTree invocation, TreePath path) {
            // createStatement(TYPE_SCROLL_*, CONCUR_UPDATABLE) -> I-R5 reject.
            for (ExpressionTree arg : invocation.getArguments()) {
                String constName = constantFieldName(arg, path);
                if (constName != null && (constName.startsWith("TYPE_SCROLL")
                        || constName.equals("CONCUR_UPDATABLE"))) {
                    reject(JdbcIdiom.I_R5, invocation, path,
                            "scrollable/updatable Statement (" + constName + ") is not supported; cursors are "
                                    + "forward-only/read-only (docs/transpilable-jdbc-subset.md §5 I-R5)");
                    return;
                }
            }
        }

        // ---- helpers ----
        // The shape predicates below delegate to JdbcShapes — the single source of truth shared with
        // the Phase-2 lowerer, so "in scope" never diverges between recognition and lowering.

        private boolean isResultSetNextCall(ExpressionTree expr, TreePath path) {
            return shapes.isResultSetNextCall(expr, path);
        }

        private boolean isBareExpressionStatement(TreePath path) {
            return shapes.isBareExpressionStatement(path);
        }

        private boolean bodyOpensNestedCursor(Tree body, Element driving, TreePath path) {
            // True if the loop body opens a *different* ResultSet via executeQuery on another handle.
            // Hoisted into JdbcShapes so the Phase-2 lowerer shares this exact guard (single source of
            // truth) and rejects the nested-cursor shape with the same actionable message.
            return shapes.bodyOpensNestedCursor(body, driving, path);
        }

        private boolean hasEnclosingHandleDeclaration(TreePath invocationPath) {
            // True when this prepareStatement(...) is the initializer of a recognized handle local
            // (so it was already gated at declaration time and must not be double-gated here).
            TreePath parent = invocationPath.getParentPath();
            return parent != null && parent.getLeaf() instanceof VariableTree;
        }

        private JdbcHandle handleFor(ExpressionTree receiverExpr, TreePath path) {
            Element receiver = elementOf(receiverExpr, path);
            return receiver == null ? null : handles.get(receiver);
        }

        private Element receiverElement(MemberSelectTree select, TreePath path) {
            return elementOf(select.getExpression(), path);
        }

        private Element elementOf(ExpressionTree expr, TreePath path) {
            if (expr == null) {
                return null;
            }
            return parsed.trees().getElement(new TreePath(path, expr));
        }

        private TypeMirror typeOf(ExpressionTree expr, TreePath path) {
            if (expr == null) {
                return null;
            }
            return parsed.trees().getTypeMirror(new TreePath(path, expr));
        }

        private ExpressionTree firstArg(MethodInvocationTree node) {
            return JdbcShapes.firstArg(node);
        }

        private String constantFieldName(ExpressionTree arg, TreePath path) {
            ExpressionTree expr = unwrap(arg);
            if (expr instanceof MemberSelectTree select) {
                return select.getIdentifier().toString();
            }
            if (expr instanceof IdentifierTree id) {
                return id.getName().toString();
            }
            return null;
        }

        private boolean isReturnGeneratedKeys(ExpressionTree arg) {
            return JdbcShapes.isReturnGeneratedKeys(arg);
        }

        private void accept(JdbcIdiom idiom, Tree node, TreePath path) {
            usages.add(JdbcUsage.transpilable(idiom, location(node, path)));
        }

        private void reject(JdbcIdiom idiom, Tree node, TreePath path, String reason) {
            usages.add(JdbcUsage.rejected(idiom, location(node, path), TitanErrorCode.E001, reason));
        }

        private void passthroughWarn(JdbcIdiom idiom, Tree node, TreePath path, String reason) {
            // I-10 elision/W005 cases are TRANSPILABLE with a portability warning carried as the
            // reason + W005 code (not a reject, not a RawSql passthrough).
            usages.add(new JdbcUsage(idiom, JdbcClassification.TRANSPILABLE, location(node, path),
                    TitanErrorCode.W005, reason));
        }

        private String location(Tree node, TreePath path) {
            long position = parsed.trees().getSourcePositions().getStartPosition(unit, node);
            if (position < 0) {
                return unit.getSourceFile().getName();
            }
            return unit.getSourceFile().getName() + ":" + unit.getLineMap().getLineNumber(position);
        }

        private static ExpressionTree unwrap(ExpressionTree expr) {
            return JdbcShapes.unwrap(expr);
        }

        private static boolean isIntLiteral(ExpressionTree expr) {
            return JdbcShapes.isIntLiteral(expr);
        }

        private static boolean isStringLiteral(ExpressionTree expr) {
            return JdbcShapes.isStringLiteral(expr);
        }

        private static boolean isUpdateOrScrollName(String name) {
            return UPDATE_OR_SCROLL_NAMES.contains(name) || name.startsWith("update");
        }

        private static boolean isStreamLobName(String name) {
            return STREAM_LOB_NAMES.contains(name);
        }
    }

    private static final Set<String> UPDATE_OR_SCROLL_NAMES = Set.of(
            "insertRow", "deleteRow", "updateRow", "moveToInsertRow", "moveToCurrentRow",
            "absolute", "relative", "previous", "first", "last", "beforeFirst", "afterLast");

    private static final Set<String> STREAM_LOB_NAMES = Set.of(
            "getBinaryStream", "getCharacterStream", "getAsciiStream", "getUnicodeStream",
            "getBlob", "getClob", "getNClob", "getSQLXML");
}
