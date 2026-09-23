package io.titan.transpiler.tir;

import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nullable-variable analysis and rewrite pass over lowered TIR (audit F-12/N1/N2, plan 2.3).
 *
 * <p>The pass walks the complete sealed TIR hierarchy with exhaustive {@code switch} expressions
 * and no {@code default} branches, so adding a new {@link StatementNode}, {@link ExpressionNode},
 * {@link SqlNode}, or {@link DeclarationNode} fails compilation here until the null semantics of
 * the new node are decided explicitly.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Flow-sensitive nullability tracking</b> with proper lexical scoping: every block seeds
 *       its own declarations (shadowing the outer scope), a declaration's scope ends with its
 *       block, branch effects are merged with a conservative join (a variable is nullable after
 *       an {@code if} when it is nullable on any branch), and loop bodies are iterated to a
 *       fixpoint so second-iteration nullability is not missed.</li>
 *   <li><b>Unboxing guards</b>: {@link NullGuardStatement}s are inserted before statements whose
 *       value-context expressions (assignments, returns, call/raise/debug arguments, for-range
 *       bounds, for-each iterables) use a possibly-null variable in arithmetic, matching Java's
 *       NullPointerException on unboxing. Boolean condition contexts (IF/WHILE/LOOP) deliberately
 *       keep the legacy lenient mode instead: a possibly-null condition is wrapped in
 *       {@code COALESCE(condition, FALSE)}.</li>
 *   <li><b>compareTo NPE parity (N2)</b>: {@link ExpressionLowerer} lowers
 *       {@code a.compareTo(b)} to the {@code __titan_compare_to} marker call; this pass is the
 *       single home of the null semantics. It inserts {@link NullGuardStatement}s for
 *       possibly-null variable operands (raising like Java's NPE on both dialects via the shared
 *       guard emission) and rewrites the marker to a three-branch CASE with no ELSE, so operands
 *       that cannot be statement-guarded (non-variable expressions) yield SQL NULL instead of
 *       the silent {@code 0} ("equal") the old lowering produced. The emitters reject the marker,
 *       guaranteeing the pass ran before emission.</li>
 *   <li><b>{@link TitanErrorCode#E003} diagnostics</b> for provable null dereferences: a variable
 *       that is definitely null (assigned the {@code null} literal and never reassigned on any
 *       path) used where Java would throw NullPointerException — arithmetic unboxing, compareTo
 *       operands (including a literal {@code null} argument), and boolean-condition unboxing.
 *       Only definite nulls are reported; possibly-null values get runtime guards, never
 *       speculative compile-time errors.</li>
 * </ul>
 *
 * <p>TODO(plan 1.1): TIR nodes do not carry {@code SourceSpan}s yet, so E003 diagnostics are
 * emitted without a position here and {@link JavaToTirLowerer} attaches the enclosing
 * entry-point location ({@code file:line} of the method). Once the deferred SourceSpan work
 * lands on TIR nodes, both the diagnostics and {@link NullGuardStatement#sourceLocation()}
 * should switch to per-statement positions.
 */
public final class NullAnalysisPass {

    /**
     * Marker function name produced by {@link ExpressionLowerer#lowerCompareTo}; rewritten by this
     * pass (never emitted).
     */
    static final String COMPARE_TO_MARKER = "__titan_compare_to";

    /** Three-point nullability lattice; {@code MAYBE_NULL} is the top of the join. */
    private enum Nullness {
        NOT_NULL,
        MAYBE_NULL,
        DEFINITELY_NULL;

        Nullness join(Nullness other) {
            return this == other ? this : MAYBE_NULL;
        }

        boolean mayBeNull() {
            return this != NOT_NULL;
        }
    }

    private final String entryPointLocation;
    private final Map<String, TitanDiagnostic> diagnostics = new LinkedHashMap<>();

    public NullAnalysisPass() {
        this(null);
    }

    /**
     * @param entryPointLocation {@code file:line} of the enclosing entry point; used as the
     *                           source location of inserted {@link NullGuardStatement}s until TIR
     *                           nodes carry per-statement spans (plan 1.1).
     */
    public NullAnalysisPass(String entryPointLocation) {
        this.entryPointLocation = entryPointLocation;
    }

    /**
     * Analyzes and rewrites the block. Throws {@link TitanDiagnosticException} carrying every
     * {@link TitanErrorCode#E003} violation found when the block provably dereferences null.
     */
    public Block analyze(Block input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        Block rewritten = analyzeBlock(input, new HashMap<>());
        if (!diagnostics.isEmpty()) {
            throw new TitanDiagnosticException(List.copyOf(diagnostics.values()));
        }
        return rewritten;
    }

    // ------------------------------------------------------------------
    // Blocks and scoping
    // ------------------------------------------------------------------

    /**
     * Analyzes {@code block} in a child scope of {@code outerState} and propagates the resulting
     * states of non-shadowed outer variables back into {@code outerState} (sequential execution).
     * Variables declared by the block itself go out of scope with it.
     */
    private Block analyzeBlock(Block block, Map<String, Nullness> outerState) {
        // Analyze lexical blocks in place. Copying the complete visible-variable map for every
        // nested block made large generated routines quadratic in both time and allocation. Save
        // only declarations that shadow an outer local, then remove/restore block locals on exit;
        // assignments to genuine outer locals remain in the shared map and therefore propagate
        // exactly as the former full-map copy/write-back did.
        Map<String, Nullness> state = outerState;
        Map<String, Nullness> shadowed = new HashMap<>();
        Set<String> newlyDeclared = new HashSet<>();
        for (DeclarationNode declaration : block.declarations()) {
            if (declaration instanceof DeclareVariable variable) {
                if (state.containsKey(variable.name())) {
                    shadowed.put(variable.name(), state.get(variable.name()));
                } else {
                    newlyDeclared.add(variable.name());
                }
                state.put(variable.name(), variable.nullable() ? Nullness.MAYBE_NULL : Nullness.NOT_NULL);
            }
        }

        List<DeclarationNode> rewrittenDeclarations = block.declarations().stream()
                .map(declaration -> rewriteDeclaration(declaration, state))
                .toList();

        List<StatementNode> rewrittenStatements = new ArrayList<>();
        for (StatementNode statement : block.statements()) {
            rewrittenStatements.addAll(analyzeStatement(statement, state));
        }

        List<DeclarationNode> rewrittenHandlers = block.exceptionHandlers().stream()
                .map(declaration -> rewriteDeclaration(declaration, state))
                .toList();

        for (String name : newlyDeclared) {
            state.remove(name);
        }
        state.putAll(shadowed);

        return new Block(rewrittenDeclarations, List.copyOf(rewrittenStatements), rewrittenHandlers);
    }

    private DeclarationNode rewriteDeclaration(DeclarationNode declaration, Map<String, Nullness> state) {
        return switch (declaration) {
            // Initializers must be rewritten exactly like their mirrored ordered Assign statement
            // (StatementLowerer adds one for every initialized local) so the emitters keep
            // recognizing the pair and suppress the inline DECLARE initializer.
            case DeclareVariable variable -> new DeclareVariable(
                    variable.name(),
                    variable.type(),
                    variable.nullable(),
                    variable.initializer() == null ? null : rewriteExpression(variable.initializer(), state));
            case DeclareCursor cursor -> new DeclareCursor(cursor.name(), rewriteSelect(cursor.query(), state));
            case DeclareHandler handler -> handler;
        };
    }

    // ------------------------------------------------------------------
    // Statements (exhaustive over the sealed StatementNode hierarchy)
    // ------------------------------------------------------------------

    private List<StatementNode> analyzeStatement(StatementNode statement, Map<String, Nullness> state) {
        return switch (statement) {
            case Block nested -> List.of(analyzeBlock(nested, state));
            case Assign assign -> analyzeAssign(assign, state);
            case IfStatement ifStatement -> analyzeIf(ifStatement, state);
            case WhileStatement whileStatement -> analyzeWhile(whileStatement, state);
            case LoopStatement loopStatement -> analyzeLoop(loopStatement, state);
            case ForCursorStatement forCursor -> analyzeForCursor(forCursor, state);
            case ForEachStatement forEach -> analyzeForEach(forEach, state);
            case ForRangeStatement forRange -> analyzeForRange(forRange, state);
            case ReturnStatement returnStatement -> analyzeReturn(returnStatement, state);
            case BreakStatement breakStatement -> List.of(breakStatement);
            case ContinueStatement continueStatement -> List.of(continueStatement);
            case RaiseStatement raise -> analyzeRaise(raise, state);
            case CallStatement call -> analyzeCall(call, state);
            case DebugPrintStatement debugPrint -> analyzeDebugPrint(debugPrint, state);
            case ExecuteSqlStatement executeSql -> analyzeExecuteSql(executeSql, state);
            case SelectIntoStatement selectInto -> analyzeSelectInto(selectInto, state);
            case RawReadIntoStatement rawRead -> analyzeRawReadInto(rawRead, state);
            case RawCursorStatement rawCursor -> analyzeRawCursor(rawCursor, state);
            case DynamicResultStatement dynamicResult -> List.of(dynamicResult);
            case GeneratedKeyReadStatement genKeyRead -> analyzeGeneratedKeyRead(genKeyRead, state);
            case TransactionControlStatement transactionControl -> List.of(transactionControl);
            case NullGuardStatement nullGuard -> List.of(nullGuard);
            case CloseCursorStatement closeCursor -> List.of(closeCursor);
            case TryCatchFinallyStatement tryCatch -> analyzeTryCatchFinally(tryCatch, state);
        };
    }

    private List<StatementNode> analyzeAssign(Assign assign, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForValueContext(assign.expression(), state));
        ExpressionNode rewritten = rewriteExpression(assign.expression(), state);
        out.add(new Assign(assign.target(), rewritten));

        if (assign.target() instanceof VariableRefExpression targetVariable) {
            state.put(targetVariable.name(), inferNullness(rewritten, state));
        }
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeReturn(ReturnStatement returnStatement, Map<String, Nullness> state) {
        if (returnStatement.expression() == null) {
            return List.of(returnStatement);
        }
        List<StatementNode> out = new ArrayList<>(guardsForValueContext(returnStatement.expression(), state));
        out.add(new ReturnStatement(rewriteExpression(returnStatement.expression(), state)));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeIf(IfStatement ifStatement, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForConditionContext(ifStatement.condition(), state));
        checkConditionUnboxing(ifStatement.condition(), state);
        ExpressionNode condition = rewriteCondition(ifStatement.condition(), state);

        Map<String, Nullness> pre = new HashMap<>(state);
        List<Map<String, Nullness>> branchEndStates = new ArrayList<>();

        Map<String, Nullness> thenState = new HashMap<>(pre);
        Block thenBlock = analyzeBlock(ifStatement.thenBlock(), thenState);
        branchEndStates.add(thenState);

        List<ElseIfClause> elseIfClauses = new ArrayList<>();
        for (ElseIfClause clause : ifStatement.elseIfClauses()) {
            // No hoisted guards for ELSIF conditions: they are evaluated only when every earlier
            // condition was false, so a statement-level guard ahead of the IF could raise where
            // Java never evaluated the operand. Markers degrade to the ELSE-less CASE's NULL.
            checkConditionUnboxing(clause.condition(), pre);
            Map<String, Nullness> clauseState = new HashMap<>(pre);
            elseIfClauses.add(new ElseIfClause(
                    rewriteCondition(clause.condition(), pre),
                    analyzeBlock(clause.block(), clauseState)));
            branchEndStates.add(clauseState);
        }

        Block elseBlock = null;
        if (ifStatement.elseBlock() == null) {
            // No else: the fall-through path keeps the pre-state.
            branchEndStates.add(pre);
        } else {
            Map<String, Nullness> elseState = new HashMap<>(pre);
            elseBlock = analyzeBlock(ifStatement.elseBlock(), elseState);
            branchEndStates.add(elseState);
        }

        joinBranchesInto(state, branchEndStates);
        out.add(new IfStatement(condition, thenBlock, List.copyOf(elseIfClauses), elseBlock));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeWhile(WhileStatement whileStatement, Map<String, Nullness> state) {
        Map<String, Nullness> fixpoint = loopFixpoint(whileStatement.body(), state);
        List<StatementNode> out = new ArrayList<>(guardsForConditionContext(whileStatement.condition(), fixpoint));
        checkConditionUnboxing(whileStatement.condition(), fixpoint);
        ExpressionNode condition = rewriteCondition(whileStatement.condition(), fixpoint);

        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        Block body = analyzeBlock(whileStatement.body(), bodyState);

        state.putAll(fixpoint);
        out.add(new WhileStatement(condition, body, whileStatement.label()));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeLoop(LoopStatement loopStatement, Map<String, Nullness> state) {
        Map<String, Nullness> fixpoint = loopFixpoint(loopStatement.body(), state);
        List<StatementNode> out = new ArrayList<>();

        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        Block body = analyzeBlock(loopStatement.body(), bodyState);

        ExpressionNode exitCondition = null;
        if (loopStatement.exitCondition() != null) {
            out.addAll(guardsForConditionContext(loopStatement.exitCondition(), fixpoint));
            checkConditionUnboxing(loopStatement.exitCondition(), fixpoint);
            exitCondition = rewriteCondition(loopStatement.exitCondition(), fixpoint);
        }

        state.putAll(fixpoint);
        out.add(new LoopStatement(body, exitCondition, loopStatement.label()));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeForCursor(ForCursorStatement forCursor, Map<String, Nullness> state) {
        // The DSL query is built eagerly in Java, so compareTo markers inside it are guarded
        // before the loop; the row variable's contents are unknown (conservatively nullable).
        List<StatementNode> out = new ArrayList<>(guardsForSqlContext(forCursor.query(), state));
        SelectSql query = rewriteSelect(forCursor.query(), state);

        Map<String, Nullness> fixpoint = loopFixpoint(forCursor.body(), state);
        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        Block body = analyzeBlock(forCursor.body(), bodyState);

        state.putAll(fixpoint);
        out.add(new ForCursorStatement(forCursor.variableName(), query, body, forCursor.label()));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeForEach(ForEachStatement forEach, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForValueContext(forEach.iterable(), state));
        ExpressionNode iterable = rewriteExpression(forEach.iterable(), state);

        Map<String, Nullness> fixpoint = loopFixpoint(forEach.body(), state);
        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        Block body = analyzeBlock(forEach.body(), bodyState);

        state.putAll(fixpoint);
        out.add(new ForEachStatement(forEach.variableName(), forEach.variableType(), iterable, body, forEach.label()));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeForRange(ForRangeStatement forRange, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForValueContext(forRange.start(), state));
        out.addAll(guardsForValueContext(forRange.end(), state));
        ExpressionNode start = rewriteExpression(forRange.start(), state);
        ExpressionNode end = rewriteExpression(forRange.end(), state);

        // The range variable holds a non-null value on every iteration.
        Map<String, Nullness> preBody = new HashMap<>(state);
        preBody.put(forRange.variableName(), Nullness.NOT_NULL);
        Map<String, Nullness> fixpoint = loopFixpoint(forRange.body(), preBody);
        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        bodyState.put(forRange.variableName(), Nullness.NOT_NULL);
        Block body = analyzeBlock(forRange.body(), bodyState);

        state.replaceAll((name, value) ->
                name.equals(forRange.variableName()) ? value : fixpoint.get(name));
        out.add(new ForRangeStatement(forRange.variableName(), start, end, body, forRange.label()));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeRaise(RaiseStatement raise, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>();
        ExpressionNode message = null;
        if (raise.message() != null) {
            out.addAll(guardsForValueContext(raise.message(), state));
            message = rewriteExpression(raise.message(), state);
        }
        List<ExpressionNode> details = new ArrayList<>();
        for (ExpressionNode detail : raise.details()) {
            out.addAll(guardsForValueContext(detail, state));
            details.add(rewriteExpression(detail, state));
        }
        out.add(new RaiseStatement(raise.sqlstate(), message, List.copyOf(details)));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeCall(CallStatement call, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>();
        List<ExpressionNode> arguments = new ArrayList<>();
        for (ExpressionNode argument : call.arguments()) {
            out.addAll(guardsForValueContext(argument, state));
            arguments.add(rewriteExpression(argument, state));
        }
        out.add(new CallStatement(call.procedureName(), List.copyOf(arguments)));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeDebugPrint(DebugPrintStatement debugPrint, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForValueContext(debugPrint.message(), state));
        out.add(new DebugPrintStatement(rewriteExpression(debugPrint.message(), state)));
        return List.copyOf(out);
    }

    private List<StatementNode> analyzeExecuteSql(ExecuteSqlStatement executeSql, Map<String, Nullness> state) {
        // SQL three-valued logic handles NULL operands natively, so no unboxing guards for
        // arithmetic inside SQL nodes — but compareTo markers were evaluated eagerly in Java
        // while the query was built, so they are guarded before the statement.
        List<StatementNode> out = new ArrayList<>(guardsForSqlContext(executeSql.sqlNode(), state));
        out.add(new ExecuteSqlStatement(rewriteSqlNode(executeSql.sqlNode(), state)));
        return List.copyOf(out);
    }

    /**
     * SELECT ... INTO a local (Phase A4 / G2) shares the {@link #analyzeExecuteSql} treatment: the
     * query interior is rewritten and any compareTo markers it carries are guarded ahead of the
     * statement. The bound variable becomes potentially NULL (a scalar SELECT INTO with no row
     * leaves it NULL; an EXISTS INTO is always non-null boolean, but MAYBE_NULL is the safe default
     * for downstream guards).
     */
    private List<StatementNode> analyzeSelectInto(SelectIntoStatement selectInto, Map<String, Nullness> state) {
        List<StatementNode> out = new ArrayList<>(guardsForSqlContext(selectInto.query(), state));
        out.add(new SelectIntoStatement(selectInto.variableName(), rewriteSelect(selectInto.query(), state)));
        state.put(selectInto.variableName(), Nullness.MAYBE_NULL);
        return List.copyOf(out);
    }

    /**
     * Native single-dialect single-row read (JDBC I-4 unparsed). The {@link RawSql} interior is
     * opaque ({@link #guardsForSqlContext}/{@link #rewriteSqlNode} pass RawSql through untouched), so
     * the node is emitted unchanged; like {@link #analyzeSelectInto}, every bound target becomes
     * potentially NULL (a no-row read leaves each target NULL — decision 4).
     */
    private List<StatementNode> analyzeRawReadInto(RawReadIntoStatement rawRead, Map<String, Nullness> state) {
        for (String variableName : rawRead.variableNames()) {
            state.put(variableName, Nullness.MAYBE_NULL);
        }
        return List.of(rawRead);
    }

    /**
     * Insert-with-generated-key recovery (JDBC I-7 §6.3): the key local is bound from the inserted
     * row's auto-increment/identity column (RETURNING / LAST_INSERT_ID). The INSERT always inserts a
     * row, so the key is non-null; conservatively recorded MAYBE_NULL like the other JDBC read targets
     * (a downstream NPE-parity guard is harmless — the value is never actually NULL). The opaque
     * {@link RawSql} insert text is not walked.
     */
    private List<StatementNode> analyzeGeneratedKeyRead(GeneratedKeyReadStatement genKeyRead, Map<String, Nullness> state) {
        state.put(genKeyRead.keyLocal(), Nullness.MAYBE_NULL);
        return List.of(genKeyRead);
    }

    /**
     * Native single-dialect multi-row read loop (JDBC I-5 unparsed). Mirrors {@link #analyzeForCursor}:
     * the {@link RawSql} query is opaque (no guards, no rewrite), and the body is analyzed to a loop
     * fixpoint so a variable mutated across iterations converges before the body sees it.
     */
    private List<StatementNode> analyzeRawCursor(RawCursorStatement rawCursor, Map<String, Nullness> state) {
        Map<String, Nullness> fixpoint = loopFixpoint(rawCursor.body(), state);
        Map<String, Nullness> bodyState = new HashMap<>(fixpoint);
        Block body = analyzeBlock(rawCursor.body(), bodyState);

        state.putAll(fixpoint);
        return List.of(new RawCursorStatement(
                rawCursor.variableNames(), rawCursor.query(), body, rawCursor.label()));
    }

    private List<StatementNode> analyzeTryCatchFinally(TryCatchFinallyStatement tryCatch, Map<String, Nullness> state) {
        Map<String, Nullness> pre = new HashMap<>(state);

        Map<String, Nullness> tryState = new HashMap<>(pre);
        Block tryBlock = analyzeBlock(tryCatch.tryBlock(), tryState);

        // An exception may interrupt the try block anywhere, so catch bodies start from the
        // join of the pre-state and the completed-try state.
        Map<String, Nullness> catchEntry = new HashMap<>(pre);
        joinInto(catchEntry, tryState);

        // Try-block declarations outlive the block at emission (the emitters hoist them so the
        // cursor-close machinery in finally can see them), so catch/finally see them at their
        // declared nullness. Lowerer-generated non-null temps always carry inline initializers
        // that run at DECLARE time, so this stays sound for interrupted try bodies.
        Map<String, Nullness> tryDeclared = new HashMap<>();
        for (DeclarationNode declaration : tryCatch.tryBlock().declarations()) {
            if (declaration instanceof DeclareVariable variable) {
                tryDeclared.put(variable.name(), variable.nullable() ? Nullness.MAYBE_NULL : Nullness.NOT_NULL);
            }
        }
        catchEntry.putAll(tryDeclared);

        Map<String, Nullness> completedTry = new HashMap<>(tryState);
        completedTry.putAll(tryDeclared);

        List<CatchClause> catches = new ArrayList<>();
        Map<String, Nullness> afterHandlers = new HashMap<>(completedTry);
        for (CatchClause catchClause : tryCatch.catches()) {
            Map<String, Nullness> catchState = new HashMap<>(catchEntry);
            Block catchBody = analyzeBlock(catchClause.body(), catchState);
            catches.add(new CatchClause(
                    catchClause.exceptionVariable(),
                    catchClause.exceptionType(),
                    catchClause.sqlStates(),
                    catchBody));
            joinInto(afterHandlers, catchState);
        }

        Block finallyBlock = null;
        if (tryCatch.finallyBlock() != null) {
            // finally also runs for propagating exceptions; start from the most conservative state.
            Map<String, Nullness> finallyState = new HashMap<>(afterHandlers);
            joinInto(finallyState, catchEntry);
            finallyBlock = analyzeBlock(tryCatch.finallyBlock(), finallyState);
            afterHandlers = finallyState;
        }

        // Restrict the write-back to the outer scope's variables (try-declared names end here).
        Map<String, Nullness> writeBack = afterHandlers;
        state.replaceAll((name, value) -> {
            Nullness result = writeBack.get(name);
            return result != null ? result : value;
        });
        return List.of(new TryCatchFinallyStatement(tryBlock, List.copyOf(catches), finallyBlock));
    }

    // ------------------------------------------------------------------
    // Loop fixpoint and state joins
    // ------------------------------------------------------------------

    /**
     * Iterates the loop body transfer function to a fixpoint over the nullability lattice so
     * nullability introduced by a previous iteration is visible (the join includes the
     * pre-state, covering zero-iteration execution). Rewrites and diagnostics from trial
     * iterations are discarded; diagnostics deduplicate by key, so the final analysis pass
     * reports each violation once.
     */
    private Map<String, Nullness> loopFixpoint(Block body, Map<String, Nullness> preState) {
        Map<String, Nullness> joined = new HashMap<>(preState);
        while (true) {
            Map<String, Nullness> trial = new HashMap<>(joined);
            analyzeBlock(body, trial);
            Map<String, Nullness> next = new HashMap<>(joined);
            joinInto(next, trial);
            if (next.equals(joined)) {
                return joined;
            }
            joined = next;
        }
    }

    /** Per-variable lattice join of {@code other} into {@code target} over the target's keys. */
    private void joinInto(Map<String, Nullness> target, Map<String, Nullness> other) {
        for (Map.Entry<String, Nullness> entry : target.entrySet()) {
            Nullness otherState = other.get(entry.getKey());
            if (otherState != null) {
                entry.setValue(entry.getValue().join(otherState));
            }
        }
    }

    private void joinBranchesInto(Map<String, Nullness> state, List<Map<String, Nullness>> branchEndStates) {
        state.replaceAll((name, value) -> {
            Nullness joined = null;
            for (Map<String, Nullness> branch : branchEndStates) {
                Nullness branchState = branch.getOrDefault(name, value);
                joined = joined == null ? branchState : joined.join(branchState);
            }
            return joined;
        });
    }

    // ------------------------------------------------------------------
    // Guard collection (and E003 detection for provable nulls)
    // ------------------------------------------------------------------

    /**
     * Guards for a value-context expression: possibly-null variables unboxed by arithmetic and
     * possibly-null variable operands of compareTo markers.
     */
    private List<StatementNode> guardsForValueContext(ExpressionNode expression, Map<String, Nullness> state) {
        Set<String> variables = new LinkedHashSet<>();
        collectScalarGuards(expression, state, variables, true);
        return toGuards(variables);
    }

    /**
     * Guards for a boolean condition context: compareTo-marker operands only. Arithmetic inside
     * conditions keeps the legacy lenient COALESCE treatment (see class javadoc).
     */
    private List<StatementNode> guardsForConditionContext(ExpressionNode condition, Map<String, Nullness> state) {
        Set<String> variables = new LinkedHashSet<>();
        collectScalarGuards(condition, state, variables, false);
        return toGuards(variables);
    }

    /** Guards for compareTo markers anywhere inside an eagerly built SQL node. */
    private List<StatementNode> guardsForSqlContext(SqlNode sqlNode, Map<String, Nullness> state) {
        Set<String> variables = new LinkedHashSet<>();
        collectSqlGuards(sqlNode, state, variables);
        return toGuards(variables);
    }

    private List<StatementNode> toGuards(Set<String> variables) {
        return variables.stream()
                .<StatementNode>map(variable -> new NullGuardStatement(variable, guardLocation()))
                .toList();
    }

    private String guardLocation() {
        return entryPointLocation == null || entryPointLocation.isBlank() ? "unknown" : entryPointLocation;
    }

    /**
     * Walks a scalar expression collecting variables that need an unboxing guard.
     * {@code arithmeticContext} enables arithmetic-operand collection (value contexts);
     * compareTo-marker operands are always collected. Subquery interiors are walked for
     * markers only via {@link #collectSqlGuards}.
     *
     * <p>A statement-level guard executes unconditionally, so collection descends only through
     * positions Java evaluates unconditionally: the right operand of {@code AND}/{@code OR}
     * (short-circuit), CASE branches past the first condition, and COALESCE fallbacks are
     * skipped — guarding them would raise where Java would never have evaluated the operand
     * (e.g. the {@code a != null && a.compareTo(b) > 0} idiom). Skipped compareTo positions
     * degrade to the ELSE-less CASE's SQL NULL, never a silent 0.
     */
    private void collectScalarGuards(
            ExpressionNode expression,
            Map<String, Nullness> state,
            Set<String> out,
            boolean arithmeticContext
    ) {
        switch (expression) {
            case BinaryOpExpression binaryOp -> {
                if (arithmeticContext && isArithmetic(binaryOp.operator())) {
                    collectArithmeticOperandVariables(binaryOp.left(), state, out);
                    collectArithmeticOperandVariables(binaryOp.right(), state, out);
                }
                boolean shortCircuit = binaryOp.operator() == BinaryOperator.AND
                        || binaryOp.operator() == BinaryOperator.OR;
                collectScalarGuards(binaryOp.left(), state, out, arithmeticContext);
                if (!shortCircuit) {
                    collectScalarGuards(binaryOp.right(), state, out, arithmeticContext);
                }
            }
            case FunctionCallExpression functionCall -> {
                if (COMPARE_TO_MARKER.equals(functionCall.name())) {
                    for (ExpressionNode operand : functionCall.arguments()) {
                        collectCompareToOperandGuard(operand, state, out);
                    }
                }
                for (ExpressionNode argument : functionCall.arguments()) {
                    collectScalarGuards(argument, state, out, arithmeticContext);
                }
            }
            case CaseWhenExpression caseWhen -> {
                // Only the first branch condition is evaluated unconditionally.
                if (!caseWhen.conditions().isEmpty()) {
                    collectScalarGuards(caseWhen.conditions().getFirst().condition(), state, out, arithmeticContext);
                }
            }
            case CoalesceExpression coalesce -> {
                // Only the first item is evaluated unconditionally.
                if (!coalesce.expressions().isEmpty()) {
                    collectScalarGuards(coalesce.expressions().getFirst(), state, out, arithmeticContext);
                }
            }
            case NotExpression not -> collectScalarGuards(not.expression(), state, out, arithmeticContext);
            case IsNullExpression isNull -> collectScalarGuards(isNull.expression(), state, out, arithmeticContext);
            case IsNotNullExpression isNotNull -> collectScalarGuards(isNotNull.expression(), state, out, arithmeticContext);
            case CastExpression cast -> collectScalarGuards(cast.expression(), state, out, arithmeticContext);
            case ArrayLengthExpression arrayLength -> collectScalarGuards(arrayLength.array(), state, out, arithmeticContext);
            case ArrayGetExpression arrayGet -> {
                collectScalarGuards(arrayGet.array(), state, out, arithmeticContext);
                collectScalarGuards(arrayGet.index(), state, out, arithmeticContext);
            }
            case ArrayConstructExpression arrayConstruct -> arrayConstruct.elements()
                    .forEach(element -> collectScalarGuards(element, state, out, arithmeticContext));
            case RecordConstructExpression recordConstruct -> recordConstruct.arguments()
                    .forEach(argument -> collectScalarGuards(argument, state, out, arithmeticContext));
            case RecordFieldExpression recordField -> collectScalarGuards(recordField.record(), state, out, arithmeticContext);
            case WindowFunctionExpression windowFunction -> windowFunction.arguments()
                    .forEach(argument -> collectScalarGuards(argument, state, out, arithmeticContext));
            case GroupingSetSpec groupingSet -> groupingSet.sets()
                    .forEach(set -> set.forEach(item -> collectScalarGuards(item, state, out, arithmeticContext)));
            case SubqueryExpression subquery -> collectSqlGuards(subquery.select(), state, out);
            case ExistsExpression exists -> collectSqlGuards(exists.subquery(), state, out);
            case InListExpression inList -> {
                collectScalarGuards(inList.value(), state, out, arithmeticContext);
                inList.items().forEach(item -> collectScalarGuards(item, state, out, arithmeticContext));
                if (inList.subquery() != null) {
                    collectSqlGuards(inList.subquery(), state, out);
                }
            }
            case VariableRefExpression ignored -> { /* guarded only inside dereferencing contexts */ }
            case ColumnRefExpression ignored -> { /* SQL-side value; no Java dereference */ }
            case LiteralExpression ignored -> { /* never dereferenced */ }
        }
    }

    /**
     * Collects possibly-null variables appearing as operands of an arithmetic expression
     * (descending through nested binary operators, mirroring the original pass). Definite nulls
     * are reported as E003 instead: Java provably throws NullPointerException unboxing them.
     */
    private void collectArithmeticOperandVariables(ExpressionNode expression, Map<String, Nullness> state, Set<String> out) {
        if (expression instanceof VariableRefExpression variableRef) {
            Nullness nullness = state.getOrDefault(variableRef.name(), Nullness.MAYBE_NULL);
            if (nullness == Nullness.DEFINITELY_NULL) {
                reportE003(
                        "variable '" + variableRef.name() + "' is definitely null when unboxed in an arithmetic expression",
                        variableRef.name() + "#arithmetic");
            } else if (nullness.mayBeNull()) {
                out.add(variableRef.name());
            }
            return;
        }
        if (expression instanceof BinaryOpExpression binaryOp) {
            collectArithmeticOperandVariables(binaryOp.left(), state, out);
            collectArithmeticOperandVariables(binaryOp.right(), state, out);
        }
    }

    /**
     * compareTo NPE parity (N2): possibly-null variable operands get a runtime
     * {@link NullGuardStatement}; provably null operands (definitely-null variables or the
     * {@code null} literal) are compile-time E003 violations. Non-variable operands cannot be
     * statement-guarded; the ELSE-less CASE rewrite degrades them to SQL NULL (never a silent 0).
     */
    private void collectCompareToOperandGuard(ExpressionNode operand, Map<String, Nullness> state, Set<String> out) {
        if (operand instanceof VariableRefExpression variableRef) {
            Nullness nullness = state.getOrDefault(variableRef.name(), Nullness.MAYBE_NULL);
            if (nullness == Nullness.DEFINITELY_NULL) {
                reportE003(
                        "variable '" + variableRef.name() + "' is definitely null when used as a compareTo operand",
                        variableRef.name() + "#compareTo");
            } else if (nullness.mayBeNull()) {
                out.add(variableRef.name());
            }
            return;
        }
        if (operand instanceof LiteralExpression literal && literal.value() == null) {
            reportE003(
                    "compareTo operand is the null literal",
                    "null-literal#compareTo");
        }
    }

    /** Walks every expression position of a SQL node collecting compareTo-marker guards. */
    private void collectSqlGuards(SqlNode sqlNode, Map<String, Nullness> state, Set<String> out) {
        switch (sqlNode) {
            case SelectSql select -> {
                for (SelectColumn column : select.columns()) {
                    collectScalarGuards(column.expression(), state, out, false);
                }
                if (select.joins() != null) {
                    for (JoinSpec join : select.joins()) {
                        if (join.lateralTarget() != null) {
                            collectSqlGuards(join.lateralTarget(), state, out);
                        }
                        if (join.condition() != null) {
                            collectScalarGuards(join.condition(), state, out, false);
                        }
                    }
                }
                if (select.where() != null) {
                    collectScalarGuards(select.where(), state, out, false);
                }
                select.groupBy().forEach(item -> collectScalarGuards(item, state, out, false));
                if (select.having() != null) {
                    collectScalarGuards(select.having(), state, out, false);
                }
                select.orderBy().forEach(spec -> collectScalarGuards(spec.expression(), state, out, false));
                if (select.ctes() != null) {
                    select.ctes().forEach(cte -> collectSqlGuards(cte.query(), state, out));
                }
            }
            case InsertSql insert -> {
                insert.values().forEach(value -> collectScalarGuards(value, state, out, false));
                if (insert.selectSource() != null) {
                    collectSqlGuards(insert.selectSource(), state, out);
                }
                if (insert.onConflict() != null) {
                    insert.onConflict().updates()
                            .forEach(set -> collectScalarGuards(set.value(), state, out, false));
                }
            }
            case UpdateSql update -> {
                update.sets().forEach(set -> collectScalarGuards(set.value(), state, out, false));
                if (update.where() != null) {
                    collectScalarGuards(update.where(), state, out, false);
                }
            }
            case DeleteSql delete -> {
                if (delete.where() != null) {
                    collectScalarGuards(delete.where(), state, out, false);
                }
            }
            case UnionSql union -> {
                collectSqlGuards(union.left(), state, out);
                collectSqlGuards(union.right(), state, out);
            }
            case IntersectSql intersect -> {
                collectSqlGuards(intersect.left(), state, out);
                collectSqlGuards(intersect.right(), state, out);
            }
            case ExceptSql except -> {
                collectSqlGuards(except.left(), state, out);
                collectSqlGuards(except.right(), state, out);
            }
            case RawSql ignored -> { /* opaque text; nothing to walk */ }
            case LateralSubquery lateral -> collectSqlGuards(lateral.subquery(), state, out);
        }
    }

    /** E003 for boolean unboxing: a definitely-null variable used directly as a condition. */
    private void checkConditionUnboxing(ExpressionNode condition, Map<String, Nullness> state) {
        ExpressionNode probe = condition instanceof NotExpression not ? not.expression() : condition;
        if (probe instanceof VariableRefExpression variableRef
                && state.getOrDefault(variableRef.name(), Nullness.MAYBE_NULL) == Nullness.DEFINITELY_NULL) {
            reportE003(
                    "variable '" + variableRef.name() + "' is definitely null when unboxed as a boolean condition",
                    variableRef.name() + "#condition");
        }
    }

    private void reportE003(String detail, String dedupeKey) {
        diagnostics.putIfAbsent(dedupeKey, new TitanDiagnostic(
                TitanErrorCode.E003,
                "Provable null dereference: " + detail
                        + "; Java throws NullPointerException here, so the transpiled routine must not silently continue",
                // TODO(plan 1.1): attach a real statement position once TIR nodes carry SourceSpans;
                // JavaToTirLowerer currently substitutes the enclosing entry-point location.
                null,
                "Assign a non-null value before this use, or guard the use with an explicit null check",
                null));
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    private ExpressionNode rewriteCondition(ExpressionNode condition, Map<String, Nullness> state) {
        ExpressionNode rewritten = rewriteExpression(condition, state);
        if (expressionMayBeNullable(rewritten, state)) {
            return new CoalesceExpression(List.of(rewritten, new LiteralExpression(false, new TBooleanType())));
        }
        return rewritten;
    }

    // ------------------------------------------------------------------
    // Expressions (exhaustive over the sealed ExpressionNode hierarchy)
    // ------------------------------------------------------------------

    private ExpressionNode rewriteExpression(ExpressionNode expression, Map<String, Nullness> state) {
        return switch (expression) {
            case ColumnRefExpression column -> column;
            case VariableRefExpression variable -> variable;
            case LiteralExpression literal -> literal;
            case BinaryOpExpression binaryOp -> new BinaryOpExpression(
                    rewriteExpression(binaryOp.left(), state),
                    binaryOp.operator(),
                    rewriteExpression(binaryOp.right(), state));
            case FunctionCallExpression functionCall -> rewriteFunctionCall(functionCall, state);
            case RecordConstructExpression recordConstruct -> new RecordConstructExpression(
                    recordConstruct.schema(),
                    recordConstruct.recordName(),
                    recordConstruct.arguments().stream().map(argument -> rewriteExpression(argument, state)).toList());
            case RecordFieldExpression recordField -> new RecordFieldExpression(
                    rewriteExpression(recordField.record(), state),
                    recordField.schema(),
                    recordField.recordName(),
                    recordField.fieldName());
            case ArrayConstructExpression arrayConstruct -> new ArrayConstructExpression(
                    arrayConstruct.elementType(),
                    arrayConstruct.elements().stream().map(element -> rewriteExpression(element, state)).toList());
            case ArrayLengthExpression arrayLength -> new ArrayLengthExpression(
                    rewriteExpression(arrayLength.array(), state));
            case ArrayGetExpression arrayGet -> new ArrayGetExpression(
                    rewriteExpression(arrayGet.array(), state),
                    rewriteExpression(arrayGet.index(), state),
                    arrayGet.elementType());
            case CaseWhenExpression caseWhen -> new CaseWhenExpression(
                    caseWhen.conditions().stream()
                            .map(branch -> new CaseBranch(
                                    rewriteExpression(branch.condition(), state),
                                    rewriteExpression(branch.value(), state)))
                            .toList(),
                    caseWhen.elseValue() == null ? null : rewriteExpression(caseWhen.elseValue(), state));
            case SubqueryExpression subquery -> new SubqueryExpression(rewriteSelect(subquery.select(), state));
            case IsNullExpression isNull -> new IsNullExpression(rewriteExpression(isNull.expression(), state));
            case IsNotNullExpression isNotNull -> new IsNotNullExpression(rewriteExpression(isNotNull.expression(), state));
            case NotExpression not -> new NotExpression(rewriteExpression(not.expression(), state));
            case CastExpression cast -> new CastExpression(
                    rewriteExpression(cast.expression(), state),
                    cast.targetType(),
                    cast.truncating());
            case CoalesceExpression coalesce -> new CoalesceExpression(
                    coalesce.expressions().stream().map(item -> rewriteExpression(item, state)).toList());
            case ExistsExpression exists -> new ExistsExpression(rewriteSelect(exists.subquery(), state), exists.negated());
            case InListExpression inList -> new InListExpression(
                    rewriteExpression(inList.value(), state),
                    inList.items().stream().map(item -> rewriteExpression(item, state)).toList(),
                    inList.subquery() == null ? null : rewriteSelect(inList.subquery(), state),
                    inList.negated());
            case WindowFunctionExpression windowFunction -> new WindowFunctionExpression(
                    windowFunction.function(),
                    windowFunction.arguments().stream().map(argument -> rewriteExpression(argument, state)).toList(),
                    rewriteWindowSpec(windowFunction.spec(), state));
            case GroupingSetSpec groupingSet -> new GroupingSetSpec(
                    groupingSet.kind(),
                    groupingSet.sets().stream()
                            .map(set -> set.stream().map(item -> rewriteExpression(item, state)).toList())
                            .toList());
        };
    }

    private ExpressionNode rewriteFunctionCall(FunctionCallExpression functionCall, Map<String, Nullness> state) {
        List<ExpressionNode> arguments = functionCall.arguments().stream()
                .map(argument -> rewriteExpression(argument, state))
                .toList();
        if (COMPARE_TO_MARKER.equals(functionCall.name()) && arguments.size() == 2) {
            // N2: three explicit branches and no ELSE. Null operands that escaped statement-level
            // guarding yield SQL NULL (which condition contexts coalesce to FALSE) instead of the
            // old silent ELSE 0 that made null compare "equal".
            ExpressionNode left = arguments.get(0);
            ExpressionNode right = arguments.get(1);
            return new CaseWhenExpression(
                    List.of(
                            new CaseBranch(
                                    new BinaryOpExpression(left, BinaryOperator.LESS_THAN, right),
                                    new LiteralExpression(-1, new TIntType())),
                            new CaseBranch(
                                    new BinaryOpExpression(left, BinaryOperator.GREATER_THAN, right),
                                    new LiteralExpression(1, new TIntType())),
                            new CaseBranch(
                                    new BinaryOpExpression(left, BinaryOperator.EQUAL, right),
                                    new LiteralExpression(0, new TIntType()))),
                    null);
        }
        return new FunctionCallExpression(functionCall.name(), arguments, functionCall.schema());
    }

    private WindowSpec rewriteWindowSpec(WindowSpec spec, Map<String, Nullness> state) {
        if (spec == null) {
            return null;
        }
        List<ExpressionNode> partitionBy = spec.partitionBy() == null
                ? null
                : spec.partitionBy().stream().map(item -> rewriteExpression(item, state)).toList();
        List<OrderBySpec> orderBy = spec.orderBy() == null
                ? null
                : spec.orderBy().stream()
                        .map(item -> new OrderBySpec(rewriteExpression(item.expression(), state), item.direction()))
                        .toList();
        WindowFrame frame = spec.frame() == null
                ? null
                : new WindowFrame(
                        spec.frame().unit(),
                        rewriteFrameBound(spec.frame().start(), state),
                        rewriteFrameBound(spec.frame().end(), state));
        return new WindowSpec(partitionBy, orderBy, frame);
    }

    private WindowFrameBound rewriteFrameBound(WindowFrameBound bound, Map<String, Nullness> state) {
        if (bound == null) {
            return null;
        }
        return new WindowFrameBound(
                bound.kind(),
                bound.offset() == null ? null : rewriteExpression(bound.offset(), state));
    }

    // ------------------------------------------------------------------
    // SQL nodes (exhaustive over the sealed SqlNode hierarchy)
    // ------------------------------------------------------------------

    private SqlNode rewriteSqlNode(SqlNode sqlNode, Map<String, Nullness> state) {
        return switch (sqlNode) {
            case SelectSql select -> rewriteSelect(select, state);
            case InsertSql insert -> new InsertSql(
                    insert.table(),
                    insert.columns(),
                    insert.values().stream().map(value -> rewriteExpression(value, state)).toList(),
                    insert.selectSource() == null ? null : rewriteSelect(insert.selectSource(), state),
                    insert.onConflict() == null ? null : new ConflictClause(
                            insert.onConflict().columns(),
                            insert.onConflict().updates().stream()
                                    .map(set -> new SetClause(set.column(), rewriteExpression(set.value(), state)))
                                    .toList()),
                    insert.returning());
            case UpdateSql update -> new UpdateSql(
                    update.table(),
                    update.sets().stream()
                            .map(set -> new SetClause(set.column(), rewriteExpression(set.value(), state)))
                            .toList(),
                    update.where() == null ? null : rewriteCondition(update.where(), state),
                    update.returning());
            case DeleteSql delete -> new DeleteSql(
                    delete.table(),
                    delete.where() == null ? null : rewriteCondition(delete.where(), state),
                    delete.returning());
            case UnionSql union -> new UnionSql(
                    rewriteSqlNode(union.left(), state),
                    rewriteSqlNode(union.right(), state),
                    union.all());
            case IntersectSql intersect -> new IntersectSql(
                    rewriteSqlNode(intersect.left(), state),
                    rewriteSqlNode(intersect.right(), state));
            case ExceptSql except -> new ExceptSql(
                    rewriteSqlNode(except.left(), state),
                    rewriteSqlNode(except.right(), state));
            case RawSql raw -> raw;
            case LateralSubquery lateral -> rewriteLateral(lateral, state);
        };
    }

    private LateralSubquery rewriteLateral(LateralSubquery lateral, Map<String, Nullness> state) {
        return new LateralSubquery(rewriteSelect(lateral.subquery(), state), lateral.alias());
    }

    private SelectSql rewriteSelect(SelectSql select, Map<String, Nullness> state) {
        List<SelectColumn> columns = select.columns() == null
                ? null
                : select.columns().stream()
                        .map(column -> new SelectColumn(rewriteExpression(column.expression(), state), column.alias()))
                        .toList();
        List<JoinSpec> joins = select.joins() == null
                ? null
                : select.joins().stream()
                        .map(join -> new JoinSpec(
                                join.joinType(),
                                join.target(),
                                join.targetAlias(),
                                join.lateralTarget() == null ? null : rewriteLateral(join.lateralTarget(), state),
                                join.condition() == null ? null : rewriteExpression(join.condition(), state)))
                        .toList();
        List<CteSpec> ctes = select.ctes() == null
                ? null
                : select.ctes().stream()
                        .map(cte -> new CteSpec(cte.name(), rewriteSelect(cte.query(), state), cte.recursive()))
                        .toList();
        return new SelectSql(
                columns,
                select.distinct(),
                select.from(),
                select.fromAlias(),
                joins,
                select.where() == null ? null : rewriteCondition(select.where(), state),
                select.groupBy().stream().map(item -> rewriteExpression(item, state)).toList(),
                select.having() == null ? null : rewriteCondition(select.having(), state),
                select.orderBy().stream()
                        .map(spec -> new OrderBySpec(rewriteExpression(spec.expression(), state), spec.direction(), spec.nulls()))
                        .toList(),
                select.limit(),
                select.offset(),
                select.existsWrapper(),
                select.locking(),
                ctes);
    }

    // ------------------------------------------------------------------
    // Nullability classification
    // ------------------------------------------------------------------

    /**
     * Whether an expression may evaluate to NULL. Exhaustive over the sealed hierarchy; choices
     * that deliberately preserve the historical (pre-completion) classification are commented.
     */
    private boolean expressionMayBeNullable(ExpressionNode expression, Map<String, Nullness> state) {
        return switch (expression) {
            // Conservative by design: unknown variables (e.g., method parameters before
            // parameter modeling lands in TIR) are treated as nullable so we never miss guards.
            case VariableRefExpression variable ->
                    state.getOrDefault(variable.name(), Nullness.MAYBE_NULL).mayBeNull();
            case LiteralExpression literal -> literal.value() == null;
            case BinaryOpExpression binaryOp -> expressionMayBeNullable(binaryOp.left(), state)
                    || expressionMayBeNullable(binaryOp.right(), state);
            case CaseWhenExpression caseWhen -> caseWhenMayBeNullable(caseWhen, state);
            // COALESCE is this pass's own non-null device; treating it as non-null also keeps
            // already-wrapped conditions from being wrapped twice.
            case CoalesceExpression ignored -> false;
            // EXISTS never evaluates to NULL, but before plan 1.4b it was smuggled through a
            // VariableRefExpression and therefore conservatively treated as nullable. Keeping
            // that classification preserves the COALESCE(..., FALSE) wrapper byte-for-byte in
            // emitted SQL (a semantic no-op) so existing golden output stays unchanged.
            case ExistsExpression ignored -> true;
            // IN over a NULL operand or NULL-yielding subquery follows three-valued logic inside
            // SQL nodes (the only place the DSL produces it), where NULL conditions are already
            // falsy; same rationale as ColumnRefExpression below.
            case InListExpression ignored -> false;
            case IsNullExpression ignored -> false;
            case IsNotNullExpression ignored -> false;
            case NotExpression not -> expressionMayBeNullable(not.expression(), state);
            case CastExpression cast -> expressionMayBeNullable(cast.expression(), state);
            // SQL scalar functions propagate NULL arguments (audit F-12: nullable conditions
            // buried in function arguments previously escaped the COALESCE guard entirely).
            case FunctionCallExpression functionCall -> functionCall.arguments().stream()
                    .anyMatch(argument -> expressionMayBeNullable(argument, state));
            // A scalar subquery yields NULL when it selects no row, but today subqueries occur
            // only inside SQL nodes (the DSL's scalar(select(...))), where NULL conditions are
            // already falsy under three-valued logic; classifying them nullable would only wrap
            // every containing WHERE clause. Historical classification kept; revisit when scalar
            // reads into procedural locals land.
            case SubqueryExpression ignored -> false;
            case WindowFunctionExpression ignored -> true;
            // Column references appear only inside SQL nodes, where NULL conditions are already
            // falsy under three-valued logic; classifying them nullable would only churn every
            // WHERE clause with a redundant wrapper. Historical classification kept.
            case ColumnRefExpression ignored -> false;
            case RecordFieldExpression ignored -> true;
            case RecordConstructExpression ignored -> false;
            case ArrayConstructExpression ignored -> false;
            case ArrayLengthExpression arrayLength -> expressionMayBeNullable(arrayLength.array(), state);
            case ArrayGetExpression ignored -> true;
            case GroupingSetSpec ignored -> false;
        };
    }

    private boolean caseWhenMayBeNullable(CaseWhenExpression caseWhen, Map<String, Nullness> state) {
        for (CaseBranch branch : caseWhen.conditions()) {
            if (expressionMayBeNullable(branch.value(), state)) {
                return true;
            }
        }
        return caseWhen.elseValue() == null || expressionMayBeNullable(caseWhen.elseValue(), state);
    }

    /**
     * Nullness transferred to a variable by assigning {@code expression} to it. Mirrors the
     * historical {@code inferExpressionNullable}: assigning from an unknown variable is treated
     * as non-null (only declared variables transfer their tracked state).
     */
    private Nullness inferNullness(ExpressionNode expression, Map<String, Nullness> state) {
        if (expression instanceof LiteralExpression literal) {
            return literal.value() == null ? Nullness.DEFINITELY_NULL : Nullness.NOT_NULL;
        }
        if (expression instanceof VariableRefExpression variable) {
            return state.getOrDefault(variable.name(), Nullness.NOT_NULL);
        }
        if (expression instanceof CoalesceExpression) {
            return Nullness.NOT_NULL;
        }
        return expressionMayBeNullable(expression, state) ? Nullness.MAYBE_NULL : Nullness.NOT_NULL;
    }

    private boolean isArithmetic(BinaryOperator operator) {
        return switch (operator) {
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO -> true;
            default -> false;
        };
    }
}
