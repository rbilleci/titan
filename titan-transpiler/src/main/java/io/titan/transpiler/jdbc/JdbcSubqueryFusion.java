package io.titan.transpiler.jdbc;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.ParsedSources;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * Cross-statement recognizer + <b>dataflow proof</b> for §3.6 form (1) — <i>subquery fusion</i> (WS-C
 * Phase 3 Rung 2, design contract §7 Rung 2). It identifies the idiom where a prior JDBC query
 * <b>A</b>'s {@code ResultSet} is read in a {@code while (rs.next())} loop into a single-column
 * collection {@code ids}, and a later query <b>B</b>'s {@code IN}-list is driven by that collection
 * ({@code "… IN (" + placeholders(ids.size()) + ")"}), and then <b>proves</b> {@code ids} and A's
 * {@code ResultSet} are consumed <i>only</i> as B's {@code IN}-source. When the proof holds, the two
 * round-trips collapse into one in-database statement: A's SQL text is inlined as B's {@code IN
 * (SELECT …)} subquery, A's binds and B's other binds are combined <b>in text order</b> (all still
 * bound), and A's prepare/execute/loop and the {@code ids} collection are elided.
 *
 * <p><b>This recognizer never rewrites anything.</b> It is a pure analysis: it returns a
 * {@link FusionPlan} (the proven triple + the fused SQL text + the merged bind sources) that the
 * {@code JdbcStatementLowerer} consumes, or {@link java.util.Optional#empty()} when fusion does not
 * apply — in which case the lowerer keeps the existing Rung-1/I-5/gate behavior unchanged.</p>
 *
 * <h2>The security/semantics invariant — fail-safe, never fuse in doubt</h2>
 * Fusion is sound only when the fused query is equivalent to the two-query version <b>for all
 * inputs</b>. The proof (in {@link #idsConsumedOnlyAsInSource}) rejects fusion the moment any of the
 * following is observed anywhere in the method body, and the recognizer also requires the
 * accumulation loop to do <i>nothing but</i> the single {@code ids.add(rs.getX(col))} read:
 * <ul>
 *   <li>any read/use of {@code ids} other than the one consuming {@code IN}: {@code ids.get(i)},
 *       a second {@code ids.size()} (or any {@code ids.size()} other than the one sizing B's run), a
 *       second iteration / for-each, logging, passing {@code ids} to a method, an
 *       {@code isEmpty()}/special-case-empty branch;</li>
 *   <li>any mutation of {@code ids} after the accumulation loop (a second {@code add}/{@code
 *       remove}/{@code clear}/{@code set}/{@code addAll});</li>
 *   <li>any escape of {@code ids}: stored to a field, returned, or passed as a method argument;</li>
 *   <li>more than one consuming {@code IN} over {@code ids};</li>
 *   <li>any {@code rs.getX} in A's loop body feeding other Java logic, or A selecting more than one
 *       column (the loop body must be exactly {@code ids.add(rs.getX(constColumn))});</li>
 *   <li>A's {@code ResultSet} escaping or being read outside the accumulation loop.</li>
 * </ul>
 * If <b>any</b> of these is violated, fusion does not apply. This mirrors {@link
 * DynamicInListRecognizer}'s never-false-accept posture: the values are always bound (A is inlined as
 * a subquery, never a value/identifier spliced into the text), and in doubt we do not fuse.
 *
 * <h2>Empty / NULL equivalence (semantic-preservation)</h2>
 * The two-query version binds an empty {@code ids} as an empty {@code IN ()} run, which matches no
 * rows; {@code IN (SELECT … returning no rows)} also matches no rows — equivalent. (If the original
 * code special-cases an empty {@code ids} — an {@code if (ids.isEmpty())} branch, an {@code isEmpty()}
 * read — that is a use of {@code ids} beyond the one {@code IN}, so the proof already refuses to
 * fuse.) A NULL id in A's result behaves the same under {@code IN (SELECT …)} as under a bound list
 * (a NULL never matches via {@code =}); the fused form preserves this.
 */
public final class JdbcSubqueryFusion {

    private final ParsedSources parsed;
    private final JdbcShapes shapes;
    private final JdbcTypeOracle oracle;

    public JdbcSubqueryFusion(ParsedSources parsed, JdbcShapes shapes, JdbcTypeOracle oracle) {
        if (parsed == null || shapes == null || oracle == null) {
            throw new IllegalArgumentException("parsed/shapes/oracle must not be null");
        }
        this.parsed = parsed;
        this.shapes = shapes;
        this.oracle = oracle;
    }

    /**
     * A proven fusion of a prior query A into a later query B's {@code IN}-subquery. The lowerer
     * elides the statements at {@link #priorPrepareIndex}, {@link #priorResultSetIndex} and {@link
     * #accumulationLoopIndex} (A's prepare/execute and the {@code while (rs.next())} accumulation), as
     * well as the {@code ids} collection declaration at {@link #collectionDeclIndex}, and replaces
     * statement B (at {@link #consumingStatementIndex}) with a fused statement whose SQL is {@link
     * #fusedSqlPrefix} {@code +} A's SQL {@code +} {@link #fusedSqlSuffix} (the prefix ends in the
     * {@code IN (} opener and the suffix starts with the closing {@code )}, so A drops straight into the
     * IN-slot) and whose bound values are {@link #orderedBindSources} in left-to-right text order. The B
     * statement is whatever shape it already was (a read / cursor / execute); only its {@code RawSql}
     * text + binds change.
     */
    public record FusionPlan(
            int collectionDeclIndex,
            int priorPrepareIndex,
            int priorResultSetIndex,
            int accumulationLoopIndex,
            int consumingStatementIndex,
            StatementTree consumingStatement,
            String fusedSqlPrefix,
            String priorSql,
            String fusedSqlSuffix,
            List<BindSource> orderedBindSources,
            List<StatementTree> elidedStatements,
            ExpressionTree consumingRunOperand) {

        public FusionPlan {
            orderedBindSources = List.copyOf(orderedBindSources);
            elidedStatements = List.copyOf(elidedStatements);
        }

        /**
         * The Java AST nodes a proven fusion <b>subsumes</b> — every node inside an elided statement
         * (A's collection declaration, prepare, executeQuery, and the accumulation loop) plus B's entire
         * placeholder-run operand ({@code placeholders(ids.size())} / {@code String.join(",",
         * Collections.nCopies(ids.size(),"?"))} / {@code "?,".repeat(ids.size())}, including its {@code
         * ids.size()}). The {@code FeatureValidator} consults this set (by tree identity) to exempt
         * exactly the {@code new ArrayList}/{@code ids.add}/{@code ids.size}/run-construction the fusion
         * removes — never any other collection op, so the safety boundary is precise.
         */
        public java.util.Set<Tree> subsumedTrees() {
            java.util.Set<Tree> subsumed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            com.sun.source.util.TreeScanner<Void, Void> collector = new com.sun.source.util.TreeScanner<>() {
                @Override
                public Void scan(Tree node, Void unused) {
                    if (node != null) {
                        subsumed.add(node);
                    }
                    return super.scan(node, unused);
                }
            };
            for (StatementTree statement : elidedStatements) {
                collector.scan(statement, null);
            }
            if (consumingRunOperand != null) {
                collector.scan(consumingRunOperand, null);
            }
            return subsumed;
        }

        /**
         * The full fused SQL text with A inlined as B's IN-subquery. {@link #fusedSqlPrefix} already
         * ends in the {@code IN (} opener and {@link #fusedSqlSuffix} starts with the closing {@code )}
         * (the placeholder run sat exactly between them), so A's {@code SELECT …} is dropped straight
         * into that slot — no extra parens are added.
         */
        public String fusedSql() {
            return fusedSqlPrefix + priorSql + fusedSqlSuffix;
        }
    }

    /**
     * One bound value of the fused statement, in left-to-right text order. A {@link Kind#PRIOR} source
     * is one of A's {@code setXxx} ordinals (it binds a {@code ?} inside the inlined subquery); a
     * {@link Kind#CONSUMING} source is one of B's own {@code setXxx} ordinals (a {@code ?} outside the
     * subquery). The lowerer resolves each {@code boundExpr} through the identical bind machinery as a
     * non-fused statement, so the value is parameterized and never reaches the SQL text.
     */
    public record BindSource(Kind kind, ExpressionTree boundExpr) {
        public enum Kind { PRIOR, CONSUMING }
    }

    /**
     * Attempts to fuse the first fusable A→ids→B triple in {@code body} (a block's statements).
     * Returns the proven {@link FusionPlan}, or empty when no triple is present or the dataflow proof
     * refuses (fail-safe). {@code body} is the ordered sibling list; {@code blockPath} is its path
     * (for type/element resolution). {@code methodBodyPath} is the whole method body, over which the
     * {@code ids}/{@code ResultSet} use-and-escape proof scans (a use anywhere in the method, not only
     * in this block, defeats fusion).
     */
    public java.util.Optional<FusionPlan> recognize(
            List<? extends StatementTree> body, TreePath blockPath, TreePath methodBodyPath) {
        for (int loopIndex = 0; loopIndex < body.size(); loopIndex++) {
            StatementTree statement = body.get(loopIndex);
            if (!(statement instanceof WhileLoopTree whileLoop)) {
                continue;
            }
            java.util.Optional<FusionPlan> plan =
                    tryFuseAt(body, loopIndex, whileLoop, blockPath, methodBodyPath);
            if (plan.isPresent()) {
                return plan;
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<FusionPlan> tryFuseAt(
            List<? extends StatementTree> body,
            int loopIndex,
            WhileLoopTree whileLoop,
            TreePath blockPath,
            TreePath methodBodyPath) {
        TreePath loopPath = new TreePath(blockPath, whileLoop);

        // (1) The loop must be `while (rs.next()) { ids.add(rs.getX(constColumn)); }` and NOTHING else.
        Element resultSet = shapes.resultSetNextReceiver(JdbcShapes.unwrap(whileLoop.getCondition()), loopPath);
        if (resultSet == null) {
            return java.util.Optional.empty();
        }
        Accumulation accumulation = recognizeSingleColumnAccumulation(whileLoop, resultSet, loopPath);
        if (accumulation == null) {
            return java.util.Optional.empty();
        }
        Element ids = accumulation.collection();

        // (2) The ResultSet must be driven by a prior `ps = c.prepareStatement(A_sql); … = ps.executeQuery()`
        //     earlier in THIS block, with A's SQL a compile-time constant (the inlined subquery text).
        PriorQuery prior = findPriorQuery(body, loopIndex, resultSet, blockPath);
        if (prior == null) {
            return java.util.Optional.empty();
        }

        // (3) A later statement B (after the loop) whose IN-list is driven by `placeholders(ids.size())`,
        //     with `ids` bound by ordinal. Recover B's prefix/suffix around the IN-run and its bind plan.
        ConsumingInList consuming = findConsumingInList(body, loopIndex, ids, blockPath);
        if (consuming == null) {
            return java.util.Optional.empty();
        }

        // (4) THE PROOF: `ids` (and A's ResultSet) are consumed ONLY as B's IN-source — no other read,
        //     mutation, escape, second IN, or rs.getX feeding other logic. Fail-safe: in doubt, no fuse.
        //     (A second consuming IN over `ids` is caught here as a second `ids.size()` call -> defeat.)
        if (!idsConsumedOnlyAsInSource(ids, resultSet, accumulation, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        List<StatementTree> elided = List.of(
                body.get(prior.collectionDeclIndex()),
                body.get(prior.prepareIndex()),
                body.get(prior.resultSetIndex()),
                body.get(loopIndex));
        return java.util.Optional.of(new FusionPlan(
                prior.collectionDeclIndex(),
                prior.prepareIndex(),
                prior.resultSetIndex(),
                loopIndex,
                consuming.statementIndex(),
                body.get(consuming.statementIndex()),
                consuming.sqlPrefix(),
                prior.sql(),
                consuming.sqlSuffix(),
                buildOrderedBindSources(prior, consuming),
                elided,
                consuming.runOperand()));
    }

    // ---- (1) the single-column accumulation loop -------------------------------------------------

    /** The recognized `while (rs.next()) { ids.add(rs.getX(constColumn)); }` accumulation. */
    private record Accumulation(
            Element collection, String column, MethodInvocationTree addCall, ExpressionTree readCall) {
    }

    /**
     * Recognizes a loop body that is <b>exactly</b> one statement {@code ids.add(rs.getX(constColumn))}
     * over {@code resultSet} — a single-column accumulation into a collection {@code ids}. Returns
     * {@code null} for any other body shape (more than one statement, a body that does anything besides
     * the single add, an add whose argument is not a single constant-column {@code rs.getX}, an add
     * whose receiver is not a local collection, or a getX over a different ResultSet). This is the
     * fail-safe gate: A's rows must feed nothing but the collection.
     */
    private Accumulation recognizeSingleColumnAccumulation(
            WhileLoopTree whileLoop, Element resultSet, TreePath loopPath) {
        StatementTree bodyStatement = whileLoop.getStatement();
        List<? extends StatementTree> statements;
        TreePath bodyPath;
        if (bodyStatement instanceof BlockTree block) {
            statements = block.getStatements();
            bodyPath = new TreePath(loopPath, block);
        } else {
            statements = List.of(bodyStatement);
            bodyPath = loopPath;
        }
        if (statements.size() != 1) {
            return null; // the body must do nothing but the single add.
        }
        StatementTree only = statements.getFirst();
        if (!(only instanceof ExpressionStatementTree expressionStatement)
                || !(expressionStatement.getExpression() instanceof MethodInvocationTree addCall)) {
            return null;
        }
        TreePath statementPath = new TreePath(bodyPath, only);
        if (!(addCall.getMethodSelect() instanceof MemberSelectTree addSelect)
                || !addSelect.getIdentifier().contentEquals("add")
                || addCall.getArguments().size() != 1) {
            return null;
        }
        Element collection = shapes.elementOf(addSelect.getExpression(), statementPath);
        if (collection == null || !isLocalVariable(collection)) {
            return null;
        }
        // The add's argument must be a single-column rs.getX over THIS ResultSet with a constant column.
        ExpressionTree argument = JdbcShapes.unwrap(addCall.getArguments().getFirst());
        String column = constantColumnReadOf(argument, resultSet, statementPath);
        if (column == null) {
            return null;
        }
        return new Accumulation(collection, column, addCall, argument);
    }

    /**
     * If {@code expr} is {@code rs.getX(constColumn)} over {@code expectedResultSet} with a constant
     * column name/ordinal, returns the column key; otherwise {@code null}. Single source of truth for
     * "this is a clean single-column read of A's ResultSet" — the same constant-column rule I-R2
     * enforces elsewhere.
     */
    private String constantColumnReadOf(ExpressionTree expr, Element expectedResultSet, TreePath path) {
        ExpressionTree unwrapped = JdbcShapes.unwrap(expr);
        if (!(unwrapped instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return null;
        }
        String name = select.getIdentifier().toString();
        if (!name.startsWith("get") || name.length() <= 3 || invocation.getArguments().size() != 1) {
            return null;
        }
        TypeMirror receiverType = shapes.typeOf(select.getExpression(), path);
        if (receiverType == null || !oracle.isResultSet(receiverType)) {
            return null;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        if (receiver == null || !receiver.equals(expectedResultSet)) {
            return null;
        }
        ExpressionTree columnArg = JdbcShapes.unwrap(invocation.getArguments().getFirst());
        if (columnArg instanceof LiteralTree literal) {
            Object value = literal.getValue();
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof Integer i) {
                // An ordinal read other than column #1 cannot be reproduced by the inlined subquery: A
                // must be a single-column SELECT (priorSqlIsFusableSubquery), so the subquery projects
                // column 1, and `IN (SELECT …)` always compares against that first projected column. A
                // `getX(2+)` consumes a different column than the fused subquery would expose — refuse so
                // we never compare the IN against the wrong column. (A `getX(1)` is the single column.)
                return i == 1 ? "#1" : null;
            }
        }
        return null;
    }

    // ---- (2) the prior query A -------------------------------------------------------------------

    /** A's prepare/execute discovered earlier in the block, plus the `ids` collection declaration. */
    private record PriorQuery(
            int collectionDeclIndex,
            int prepareIndex,
            int resultSetIndex,
            Element statementHandle,
            String sql,
            java.util.SortedMap<Integer, ExpressionTree> binds) {
    }

    /**
     * Finds the prior query A driving {@code resultSet}: the {@code ResultSet rs = ps.executeQuery()}
     * declaration earlier in {@code body}, its {@code PreparedStatement ps = c.prepareStatement(A_sql)}
     * (with A's SQL a compile-time constant), its {@code ps.setXxx(n, …)} binds, and the {@code List<…>
     * ids = …} collection declaration. Returns {@code null} unless all are present and A's SQL is a
     * constant — A's text is inlined verbatim as the subquery, so it must not itself splice a value
     * (a non-constant A is the strict-gate's concern, not fusion's). The collection must be declared
     * (so its declaration can be elided) — a parameter/field collection is not fusable here.
     */
    private PriorQuery findPriorQuery(
            List<? extends StatementTree> body, int loopIndex, Element resultSet, TreePath blockPath) {
        // The ResultSet declaration: `ResultSet rs = <stmt>.executeQuery();`.
        int resultSetIndex = -1;
        Element statementHandle = null;
        for (int i = 0; i < loopIndex; i++) {
            StatementTree statement = body.get(i);
            if (statement instanceof VariableTree variable
                    && resultSet.equals(declaredElement(variable, blockPath))) {
                statementHandle = executeQueryReceiver(variable.getInitializer(), new TreePath(blockPath, variable));
                resultSetIndex = i;
            }
        }
        if (resultSetIndex < 0 || statementHandle == null) {
            return null;
        }
        // The PreparedStatement declaration: `PreparedStatement ps = c.prepareStatement(A_sql);`.
        int prepareIndex = -1;
        String sql = null;
        for (int i = 0; i < resultSetIndex; i++) {
            StatementTree statement = body.get(i);
            if (statement instanceof VariableTree variable
                    && statementHandle.equals(declaredElement(variable, blockPath))) {
                sql = constantPrepareSql(variable.getInitializer(), new TreePath(blockPath, variable));
                prepareIndex = i;
            }
        }
        if (prepareIndex < 0 || sql == null) {
            return null;
        }
        // A's text is inlined VERBATIM into B's `IN (…)` slot, so it must be a clean, read-only,
        // single-column SELECT that nests safely as a parenthesized subquery. Reject anything that
        // would make `IN (<A>)` a different query than the two-query (bound-list) original:
        //   - A is not a SELECT/WITH…SELECT (e.g. an UPDATE…RETURNING) — illegal as a subquery and it
        //     would drop A's side effects;
        //   - A projects more than one column (or SELECT * / tbl.*) — `IN (multi-column subquery)` is a
        //     SQL error and the bound list materialized exactly one named column;
        //   - A's text carries a statement terminator `;` or a line/block comment that would unbalance
        //     the inlined parentheses (the `--` swallows B's closing `)`).
        // Fail-safe: in any doubt about A's shape (parser-free edge-checks over the constant text), do
        // not fuse. (Confirmed over-fuse cases: design §3.6(1); WS-C Phase 3 Rung 2 audit.)
        if (!priorSqlIsFusableSubquery(sql)) {
            return null;
        }
        // A's setXxx binds (ordinal -> value), collected from `ps.setXxx(n, v)` between prepare and loop.
        java.util.SortedMap<Integer, ExpressionTree> binds =
                collectSetBinds(body, prepareIndex, loopIndex, statementHandle, blockPath);
        // The collection declaration to elide — a declared local `List<…> ids = new …`.
        int collectionDeclIndex = findCollectionDeclIndex(body, loopIndex, blockPath);
        if (collectionDeclIndex < 0) {
            return null;
        }
        return new PriorQuery(collectionDeclIndex, prepareIndex, resultSetIndex, statementHandle, sql, binds);
    }

    /** The index of the `List<…> ids = …;` declaration of the accumulation collection, or -1. */
    private int findCollectionDeclIndex(List<? extends StatementTree> body, int loopIndex, TreePath blockPath) {
        // Resolved by matching the loop's `ids.add(...)` receiver element to a prior declaration.
        // (Recomputed here from the loop so the index aligns with the actual declaration statement.)
        WhileLoopTree whileLoop = (WhileLoopTree) body.get(loopIndex);
        TreePath loopPath = new TreePath(blockPath, whileLoop);
        Element resultSet = shapes.resultSetNextReceiver(JdbcShapes.unwrap(whileLoop.getCondition()), loopPath);
        Accumulation accumulation = recognizeSingleColumnAccumulation(whileLoop, resultSet, loopPath);
        if (accumulation == null) {
            return -1;
        }
        Element ids = accumulation.collection();
        for (int i = 0; i < loopIndex; i++) {
            StatementTree statement = body.get(i);
            if (statement instanceof VariableTree variable && ids.equals(declaredElement(variable, blockPath))) {
                return i;
            }
        }
        return -1;
    }

    /** The receiver element of a {@code <rs> = <stmt>.executeQuery()} initializer, or null. */
    private Element executeQueryReceiver(ExpressionTree initializer, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(initializer);
        if (expr instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("executeQuery")
                && invocation.getArguments().isEmpty()) {
            return shapes.elementOf(select.getExpression(), path);
        }
        return null;
    }

    /**
     * The compile-time-constant SQL text of a {@code <ps> = c.prepareStatement(SQL)} initializer, or
     * {@code null} if the initializer is not a {@code prepareStatement} call or its SQL is not a
     * constant. A's text is inlined as the subquery, so it must be constant (a value/identifier
     * spliced into A would otherwise reach the fused text). A {@code prepareStatement(SQL,
     * RETURN_GENERATED_KEYS)} is not a read driver — only the single-arg/SQL-first form is taken.
     */
    private String constantPrepareSql(ExpressionTree initializer, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(initializer);
        if (!(expr instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("prepareStatement")
                || invocation.getArguments().isEmpty()) {
            return null;
        }
        return constantStringValue(invocation.getArguments().getFirst(), path);
    }

    /** A's `ps.setXxx(ordinalLiteral, value)` binds between prepare and the loop (ordinal -> value). */
    private java.util.SortedMap<Integer, ExpressionTree> collectSetBinds(
            List<? extends StatementTree> body,
            int prepareIndex,
            int loopIndex,
            Element statementHandle,
            TreePath blockPath) {
        java.util.SortedMap<Integer, ExpressionTree> binds = new java.util.TreeMap<>();
        for (int i = prepareIndex + 1; i < loopIndex; i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof ExpressionStatementTree expressionStatement)
                    || !(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
                continue;
            }
            if (!select.getIdentifier().toString().startsWith("set")
                    || invocation.getArguments().size() < 2) {
                continue;
            }
            Element receiver = shapes.elementOf(select.getExpression(), new TreePath(blockPath, statement));
            if (receiver == null || !receiver.equals(statementHandle)) {
                continue;
            }
            ExpressionTree ordinalArg = JdbcShapes.unwrap(invocation.getArguments().get(0));
            if (ordinalArg instanceof LiteralTree literal && literal.getValue() instanceof Integer ordinal) {
                binds.put(ordinal, invocation.getArguments().get(1));
            }
        }
        return binds;
    }

    // ---- (3) the consuming IN-list statement B ---------------------------------------------------

    /**
     * B's IN-list driven by {@code ids}: the constant prefix ending in {@code IN (} before the
     * placeholder run, the constant suffix starting with {@code )} after it, the {@code ps.setXxx}
     * binds outside the run, and the index of B's statement. The placeholder run must be sized by
     * {@code ids.size()} (no other shape), and the run must be the <i>only</i> non-constant part of
     * B's SQL between the prefix and suffix.
     */
    private record ConsumingInList(
            int statementIndex,
            Element statementHandle,
            String sqlPrefix,
            String sqlSuffix,
            int runOrdinalStart,
            java.util.SortedMap<Integer, ExpressionTree> binds,
            ExpressionTree sizeCall,
            ExpressionTree runOperand) {
    }

    /**
     * Finds the later statement B whose SQL is {@code <prefix ending IN (> + placeholders(ids.size())
     * + <suffix starting )>}, with {@code ids} bound by ordinal. Recovers B's prepare (its SQL is the
     * {@code prefix + run + suffix} concatenation), its {@code setXxx} binds, and the statement that
     * consumes it (an {@code executeQuery} / {@code executeUpdate} / the read it drives). Returns
     * {@code null} unless exactly the recognized shape is present — a runtime-sized run that is NOT
     * {@code ids.size()}-driven, a prefix not ending in {@code IN (}, or a suffix not starting in
     * {@code )} all defeat recognition (and the {@code idsConsumedOnlyAsInSource} proof additionally
     * rejects a second IN over {@code ids}).
     */
    private ConsumingInList findConsumingInList(
            List<? extends StatementTree> body, int loopIndex, Element ids, TreePath blockPath) {
        for (int i = loopIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof VariableTree variable)) {
                continue;
            }
            TreePath variablePath = new TreePath(blockPath, variable);
            TypeMirror type = parsed.trees().getTypeMirror(variablePath);
            if (type == null || !oracle.isPreparedStatement(type)) {
                continue;
            }
            ExpressionTree initializer = JdbcShapes.unwrap(variable.getInitializer());
            if (!(initializer instanceof MethodInvocationTree invocation)
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                    || !select.getIdentifier().contentEquals("prepareStatement")
                    || invocation.getArguments().isEmpty()) {
                continue;
            }
            InRunSplit split = recognizeInRun(invocation.getArguments().getFirst(), ids, variablePath);
            if (split == null) {
                continue;
            }
            Element statementHandle = declaredElement(variable, blockPath);
            if (statementHandle == null) {
                continue;
            }
            // B's setXxx binds (ordinal -> value); the run itself is bound by ids in the two-query form,
            // which fusion DROPS (the subquery replaces it), so B's surviving ?-binds are these only.
            java.util.SortedMap<Integer, ExpressionTree> binds =
                    collectAllSetBinds(body, i, statementHandle, blockPath);
            return new ConsumingInList(
                    i, statementHandle, split.prefix(), split.suffix(), split.runOrdinalStart(), binds,
                    split.sizeCall(), split.runOperand());
        }
        return null;
    }

    /** B's SQL split at the {@code ids.size()}-driven placeholder run: prefix (…{@code IN (}) + suffix ({@code )}…). */
    private record InRunSplit(
            String prefix, String suffix, int runOrdinalStart, ExpressionTree sizeCall, ExpressionTree runOperand) {
    }

    /**
     * Recognizes B's SQL argument as {@code <constant prefix ending "IN ("> + <ids.size()-driven
     * placeholder run> + <constant suffix starting ")">}. The recognized run forms (a single
     * non-constant operand of the {@code +} concatenation) are exactly the literal-IN idioms whose
     * arity is {@code ids.size()}:
     * <ul>
     *   <li>{@code placeholders(ids.size())} (or any recognized {@code placeholders}-style helper);</li>
     *   <li>{@code "?,".repeat(ids.size())} / {@code "?, ".repeat(ids.size())} and the
     *       {@code String.join(",", Collections.nCopies(ids.size(), "?"))} sibling — provided the
     *       constant prefix/suffix absorb the trailing/leading separator so the spliced run is a clean
     *       {@code ?}-list. (For a {@code repeat} that leaves a dangling separator the recognizer keeps
     *       it on the run side and the suffix; equivalence is preserved because the run is fully
     *       replaced by the subquery either way.)</li>
     * </ul>
     * Returns {@code null} unless the prefix ends in {@code IN (} (case-insensitive, whitespace
     * tolerant) and the suffix starts with {@code )}. {@code runOrdinalStart} is the 1-based JDBC
     * ordinal the run would have occupied — used only to split B's {@code setXxx} binds into
     * before-run vs after-run for text-order assembly (the run's own ordinals are dropped). Fail-safe:
     * any operand that is not a provable {@code ids.size()}-sized {@code ?} run defeats recognition.
     */
    private InRunSplit recognizeInRun(ExpressionTree sqlArg, Element ids, TreePath path) {
        // Flatten the + concatenation into ordered operands.
        List<ExpressionTree> operands = new ArrayList<>();
        flattenConcatenation(JdbcShapes.unwrap(sqlArg), operands);
        // Find the single operand that is an ids.size()-driven placeholder run (capturing its size call).
        int runOperand = -1;
        ExpressionTree sizeCall = null;
        for (int i = 0; i < operands.size(); i++) {
            ExpressionTree candidateSize = idsSizedPlaceholderRunSizeCall(operands.get(i), ids, path);
            if (candidateSize != null) {
                if (runOperand >= 0) {
                    return null; // two ids.size()-driven runs in one SQL -> not the single-IN shape.
                }
                runOperand = i;
                sizeCall = candidateSize;
            }
        }
        if (runOperand < 0) {
            return null;
        }
        ExpressionTree runOperandExpr = operands.get(runOperand);
        // Every OTHER operand must be a compile-time-constant string (the prefix/suffix). A non-constant
        // operand elsewhere (a value/identifier splice) means B is not a clean constant-around-the-run
        // shape -> defer to the existing gate, never fuse a value into the text.
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < operands.size(); i++) {
            if (i == runOperand) {
                continue;
            }
            String constant = constantStringValue(operands.get(i), path);
            if (constant == null) {
                return null;
            }
            (i < runOperand ? prefix : suffix).append(constant);
        }
        // Edge-check: the prefix must end in `IN (` and the suffix start with `)` (the IN-list bounds).
        if (!endsInInOpenParen(prefix.toString()) || !startsWithCloseParen(suffix.toString())) {
            return null;
        }
        // Count B's setXxx ordinals before the run (the ?s in the prefix) so the run's own ordinals (the
        // ids binds being dropped) are excluded from the surviving bind split. A literal '?' inside a
        // single-quoted SQL string is NOT a placeholder and must not shift the boundary.
        int prefixQuestionMarks = countQuestionMarksOutsideStringLiterals(prefix.toString());
        return new InRunSplit(
                prefix.toString(), suffix.toString(), prefixQuestionMarks + 1, sizeCall, runOperandExpr);
    }

    /**
     * If {@code operand} is a placeholder run whose arity is {@code ids.size()} — the runtime-sized
     * IN-run fusion replaces — returns the {@code ids.size()} call expression; otherwise {@code null}.
     * Recognized: {@code placeholders(ids.size())} (a recognized helper), {@code "?,".repeat(ids.size())}
     * / {@code "?, ".repeat(ids.size())} (a placeholder/separator-only receiver), and {@code
     * String.join(sep, Collections.nCopies(ids.size(), "?"))}. The size argument must be exactly {@code
     * ids.size()} over the SAME {@code ids} collection (resolved by element); any other count (a literal,
     * a different collection, an arithmetic expression) is NOT this shape.
     */
    private ExpressionTree idsSizedPlaceholderRunSizeCall(ExpressionTree operand, Element ids, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(operand);
        if (!(expr instanceof MethodInvocationTree invocation)) {
            return null;
        }
        String method = invocationName(invocation);
        if (method == null) {
            return null;
        }
        // placeholders(ids.size()) / qmarks(ids.size()) / … (a recognized helper, single arg = ids.size()).
        if (isRecognizedPlaceholderHelper(method) && invocation.getArguments().size() == 1) {
            return idsSizeCallOrNull(invocation.getArguments().getFirst(), ids, path);
        }
        ExpressionTree receiver = invocation.getMethodSelect() instanceof MemberSelectTree select
                ? select.getExpression() : null;
        // "?,".repeat(ids.size()) — placeholder/separator-only receiver, count = ids.size().
        if (method.equals("repeat") && receiver != null && invocation.getArguments().size() == 1) {
            String receiverText = JdbcShapes.stringLiteralValue(receiver);
            if (receiverText != null
                    && PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(receiverText).matches()
                    && receiverText.indexOf('?') >= 0) {
                return idsSizeCallOrNull(invocation.getArguments().getFirst(), ids, path);
            }
            return null;
        }
        // String.join(sep, Collections.nCopies(ids.size(), "?")).
        if (method.equals("join") && receiver instanceof IdentifierTree ident
                && ident.getName().contentEquals("String")
                && invocation.getArguments().size() == 2) {
            String separator = JdbcShapes.stringLiteralValue(invocation.getArguments().get(0));
            if (separator == null || !PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(separator).matches()) {
                return null;
            }
            ExpressionTree second = JdbcShapes.unwrap(invocation.getArguments().get(1));
            if (second instanceof MethodInvocationTree nCopies
                    && nCopies.getMethodSelect() instanceof MemberSelectTree nSelect
                    && nSelect.getIdentifier().contentEquals("nCopies")
                    && nCopies.getArguments().size() == 2
                    && "?".equals(JdbcShapes.stringLiteralValue(nCopies.getArguments().get(1)))) {
                return idsSizeCallOrNull(nCopies.getArguments().get(0), ids, path);
            }
        }
        return null;
    }

    /** The {@code ids.size()} call over the same {@code ids} collection (by element), or {@code null}. */
    private ExpressionTree idsSizeCallOrNull(ExpressionTree expr, Element ids, TreePath path) {
        ExpressionTree unwrapped = JdbcShapes.unwrap(expr);
        if (unwrapped instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("size")
                && invocation.getArguments().isEmpty()) {
            Element receiver = shapes.elementOf(select.getExpression(), path);
            if (receiver != null && receiver.equals(ids)) {
                return unwrapped;
            }
        }
        return null;
    }

    /** B's `ps.setXxx(ordinalLiteral, value)` binds anywhere in the block after its prepare (ordinal -> value). */
    private java.util.SortedMap<Integer, ExpressionTree> collectAllSetBinds(
            List<? extends StatementTree> body, int prepareIndex, Element statementHandle, TreePath blockPath) {
        java.util.SortedMap<Integer, ExpressionTree> binds = new java.util.TreeMap<>();
        for (int i = prepareIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof ExpressionStatementTree expressionStatement)
                    || !(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
                continue;
            }
            if (!select.getIdentifier().toString().startsWith("set")
                    || invocation.getArguments().size() < 2) {
                continue;
            }
            Element receiver = shapes.elementOf(select.getExpression(), new TreePath(blockPath, statement));
            if (receiver == null || !receiver.equals(statementHandle)) {
                continue;
            }
            ExpressionTree ordinalArg = JdbcShapes.unwrap(invocation.getArguments().get(0));
            if (ordinalArg instanceof LiteralTree literal && literal.getValue() instanceof Integer ordinal) {
                binds.put(ordinal, invocation.getArguments().get(1));
            }
        }
        return binds;
    }

    // ---- (4) THE DATAFLOW PROOF ------------------------------------------------------------------

    /**
     * The crux: proves {@code ids} and A's {@code ResultSet} are consumed <b>only</b> as B's IN-source.
     * Scans the WHOLE method body and refuses fusion (returns {@code false}) the moment it sees any use
     * of {@code ids} other than the proven accumulation {@code add} and the single {@code ids.size()}
     * sizing B's run, any mutation of {@code ids} after the loop, any escape of {@code ids} (field
     * store / return / method argument), a second consuming IN, or any use of A's {@code ResultSet}
     * beyond the {@code next()}/{@code getX} of the accumulation loop. Fail-safe: any unrecognized
     * reference to {@code ids} or {@code rs} is treated as a defeating use (we never fuse in doubt).
     */
    private boolean idsConsumedOnlyAsInSource(
            Element ids,
            Element resultSet,
            Accumulation accumulation,
            TreePath methodBodyPath) {
        // The exact AST nodes that are the ALLOWED uses: the accumulation add's receiver, the size()
        // call sizing B's run, the rs.next() conditions and the rs.getX in the loop. Everything else
        // that references `ids` or `rs` defeats fusion. We compare by tree identity where possible and
        // by element + structural role otherwise.
        boolean[] defeated = {false};
        int[] idsSizeCount = {0};
        int[] idsAddCount = {0};

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Element receiver = parsed.trees().getElement(new TreePath(getCurrentPath(), node.getExpression()));
                if (receiver != null && receiver.equals(ids)) {
                    classifyIdsUse(node);
                } else if (receiver != null && receiver.equals(resultSet)) {
                    classifyResultSetUse(node);
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                // A bare reference to `ids`/`rs` (NOT the receiver of a `recv.method(...)` member-select,
                // which the member-select handler classifies): passing it as an argument, returning it,
                // an assignment source/target — i.e. an escape or other use. Any such bare use defeats.
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element == null) {
                    return super.visitIdentifier(node, unused);
                }
                if ((element.equals(ids) || element.equals(resultSet)) && !isMemberSelectReceiver(getCurrentPath())) {
                    defeated[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            private void classifyIdsUse(MemberSelectTree node) {
                String member = node.getIdentifier().toString();
                MethodInvocationTree call = enclosingCall(node);
                if (call == null) {
                    defeated[0] = true; // `ids.field` access (not a call) -> unknown use, defeat.
                    return;
                }
                switch (member) {
                    case "add" -> {
                        // Allowed ONLY if it is the proven accumulation add; any other add (a second
                        // accumulation, an add after the loop) is a mutation -> defeat.
                        if (call == accumulation.addCall()) {
                            idsAddCount[0]++;
                        } else {
                            defeated[0] = true;
                        }
                    }
                    case "size" -> {
                        // Allowed ONLY for the single size() sizing B's run; a second size() (e.g.
                        // logging the count) is another use -> defeat.
                        if (call.getArguments().isEmpty()) {
                            idsSizeCount[0]++;
                            if (idsSizeCount[0] > 1) {
                                defeated[0] = true;
                            }
                        } else {
                            defeated[0] = true;
                        }
                    }
                    // ANY other method on ids — get/iterator/forEach/stream/isEmpty/contains/remove/
                    // clear/set/addAll/toString/… — is a use beyond the one IN-source -> defeat.
                    default -> defeated[0] = true;
                }
            }

            private void classifyResultSetUse(MemberSelectTree node) {
                // A's ResultSet may be used ONLY as the loop's `rs.next()` condition and the single
                // `rs.getX(col)` inside the proven accumulation add. ANY other rs.<method> — a second
                // getX feeding other logic, a getX outside the loop, close()/wasNull()/getMetaData()/… —
                // defeats fusion (the rows must feed nothing but the collection).
                MethodInvocationTree call = enclosingCall(node);
                if (call == null) {
                    defeated[0] = true; // `rs.field` -> unknown use.
                    return;
                }
                String member = node.getIdentifier().toString();
                if (member.equals("next") && call.getArguments().isEmpty()) {
                    return; // the loop's rs.next() condition (the recognizer already proved the loop shape).
                }
                if (call == accumulation.readCall()) {
                    return; // the single accumulation rs.getX(col).
                }
                defeated[0] = true;
            }

            private MethodInvocationTree enclosingCall(MemberSelectTree node) {
                Tree parent = getCurrentPath().getParentPath() == null
                        ? null : getCurrentPath().getParentPath().getLeaf();
                return parent instanceof MethodInvocationTree call && call.getMethodSelect() == node ? call : null;
            }

            private boolean isMemberSelectReceiver(TreePath path) {
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                return parent instanceof MemberSelectTree memberSelect && memberSelect.getExpression() == path.getLeaf();
            }
        }.scan(methodBodyPath, null);

        if (defeated[0]) {
            return false;
        }
        // Exactly one accumulation add and exactly one size() (sizing B's run) must have been seen. The
        // ResultSet's own next()/getX uses are inside the loop the recognizer already proved is the
        // single accumulation; a getX outside the loop would reference rs as a bare identifier or a
        // member-select we did not whitelist for rs (we only whitelist rs via the loop body shape), so
        // any stray rs.getX outside the loop is caught as a defeat above (its receiver is a bare rs
        // identifier reference). Require the precise counts so a missing/extra use is rejected.
        return idsAddCount[0] == 1 && idsSizeCount[0] == 1;
    }

    // ---- merged bind assembly (text order) -------------------------------------------------------

    /**
     * The fused statement's bound values in left-to-right <b>text order</b>: B's binds whose ordinal is
     * before the IN-run, then A's binds (the {@code ?}s now inside the inlined subquery), then B's
     * binds whose ordinal is after the run. The downstream {@code EXECUTE … USING} binds the Nth
     * {@code ?} to the Nth source, and after inlining the subquery sits between B's pre-run and
     * post-run {@code ?}s — so this is exactly the order the placeholders appear.
     */
    private List<BindSource> buildOrderedBindSources(PriorQuery prior, ConsumingInList consuming) {
        List<BindSource> sources = new ArrayList<>();
        // B's binds before the run (ordinals < runOrdinalStart).
        for (var entry : consuming.binds().entrySet()) {
            if (entry.getKey() < consuming.runOrdinalStart()) {
                sources.add(new BindSource(BindSource.Kind.CONSUMING, entry.getValue()));
            }
        }
        // A's binds (inside the subquery), in ordinal order.
        for (ExpressionTree bind : prior.binds().values()) {
            sources.add(new BindSource(BindSource.Kind.PRIOR, bind));
        }
        // B's binds after the run (ordinals >= runOrdinalStart). In the two-query form these ordinals
        // were AFTER the ids run, so in text they follow the inlined subquery.
        for (var entry : consuming.binds().entrySet()) {
            if (entry.getKey() >= consuming.runOrdinalStart()) {
                sources.add(new BindSource(BindSource.Kind.CONSUMING, entry.getValue()));
            }
        }
        return sources;
    }

    // ---- small shared helpers --------------------------------------------------------------------

    private static void flattenConcatenation(ExpressionTree expr, List<ExpressionTree> out) {
        ExpressionTree current = JdbcShapes.unwrap(expr);
        if (current instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            flattenConcatenation(binary.getLeftOperand(), out);
            flattenConcatenation(binary.getRightOperand(), out);
        } else {
            out.add(current);
        }
    }

    /**
     * The compile-time-constant String value of {@code expression} (a string literal, a {@code static
     * final String} JLS constant, or a {@code +} concatenation of those), resolved against {@code
     * enclosingPath}; {@code null} if it is not a constant. This mirrors {@code
     * RawSqlConstantRule}/{@code JdbcStatementLowerer.constantStringValue} (the D7 shared predicate):
     * the prefix/suffix around the IN-run and A's SQL must be constant so no runtime value reaches the
     * fused text.
     */
    private String constantStringValue(ExpressionTree expression, TreePath enclosingPath) {
        if (expression == null) {
            return null;
        }
        ExpressionTree stripped = JdbcShapes.unwrap(expression);
        if (stripped instanceof LiteralTree literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (stripped instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = constantStringValue(binary.getLeftOperand(), enclosingPath);
            String right = constantStringValue(binary.getRightOperand(), enclosingPath);
            return (left == null || right == null) ? null : left + right;
        }
        if (stripped instanceof IdentifierTree || stripped instanceof MemberSelectTree) {
            Element element = parsed.trees().getElement(new TreePath(enclosingPath, stripped));
            if (element instanceof javax.lang.model.element.VariableElement variable
                    && variable.getKind() == javax.lang.model.element.ElementKind.FIELD
                    && variable.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && variable.getModifiers().contains(javax.lang.model.element.Modifier.FINAL)
                    && variable.getConstantValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static boolean isLocalVariable(Element element) {
        return element.getKind() == javax.lang.model.element.ElementKind.LOCAL_VARIABLE;
    }

    private static String invocationName(MethodInvocationTree invocation) {
        ExpressionTree select = invocation.getMethodSelect();
        if (select instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier().toString();
        }
        if (select instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        return null;
    }

    /** The resolved {@link Element} a local-variable declaration introduces (resolved by its own path). */
    private Element declaredElement(VariableTree variable, TreePath blockPath) {
        return parsed.trees().getElement(new TreePath(blockPath, variable));
    }

    private static boolean isRecognizedPlaceholderHelper(String method) {
        String lower = method.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("placeholders") || lower.equals("placeholder")
                || lower.equals("qmarks") || lower.equals("questionmarks")
                || lower.equals("bindplaceholders") || lower.equals("repeatplaceholders")
                || lower.equals("inplaceholders") || lower.equals("makeplaceholders")
                || lower.equals("sqlplaceholders");
    }

    /**
     * Whether {@code prefix} ends (whitespace-tolerant, case-insensitive) in a <b>plain, non-negated</b>
     * {@code IN (} membership opener. A {@code NOT IN (} is rejected: the empty/NULL equivalence the
     * fusion proof relies on holds for {@code IN} but NOT for {@code NOT IN} — when A's result contains a
     * NULL, {@code col NOT IN (SELECT … with a NULL)} is NULL-poisoned (returns no rows) whereas {@code
     * col NOT IN (boundList)} (a primitive read turns a SQL NULL into 0) does not, so the two forms
     * diverge. Non-nullability of A's column is not provable on the parser-free path, so we refuse all
     * {@code NOT IN}. (WS-C Phase 3 Rung 2 audit — silent-wrong-results finding.)
     */
    private static boolean endsInInOpenParen(String prefix) {
        String trimmed = prefix.stripTrailing();
        if (trimmed.isEmpty() || trimmed.charAt(trimmed.length() - 1) != '(') {
            return false;
        }
        String beforeParen = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        String upper = beforeParen.toUpperCase(java.util.Locale.ROOT);
        if (!upper.endsWith("IN")) {
            return false;
        }
        // `IN` must be a whole word (a column literally named "...in" would mis-trigger otherwise).
        int start = upper.length() - "IN".length();
        if (start == 0) {
            return true;
        }
        char before = upper.charAt(start - 1);
        if (Character.isLetterOrDigit(before) || before == '_') {
            return false;
        }
        // Reject `NOT IN (` — the word immediately before `IN` must not be `NOT` (whole-word check). A
        // negated membership is not fusion-equivalent on a NULL-bearing A.
        String beforeIn = upper.substring(0, start).stripTrailing();
        if (beforeIn.endsWith("NOT")) {
            int notStart = beforeIn.length() - "NOT".length();
            if (notStart == 0) {
                return false;
            }
            char beforeNot = beforeIn.charAt(notStart - 1);
            return Character.isLetterOrDigit(beforeNot) || beforeNot == '_';
        }
        return true;
    }

    /** Whether {@code suffix} starts (whitespace-tolerant) with the {@code )} closing the IN-list. */
    private static boolean startsWithCloseParen(String suffix) {
        String trimmed = suffix.stripLeading();
        return !trimmed.isEmpty() && trimmed.charAt(0) == ')';
    }

    /**
     * Counts {@code ?} placeholders <b>outside</b> single-quoted SQL string literals. A literal {@code
     * '?'} (or {@code '?%'}) is data, not a JDBC parameter, so it must not be counted when splitting B's
     * binds at the IN-run boundary (a mis-count shifts {@code runOrdinalStart} and skews the pre/post-IN
     * split). SQL doubles a quote to escape it inside a literal ({@code ''}); the scanner treats {@code
     * ''} as an escaped quote, staying in the literal. This is the same quote-aware walk {@link
     * io.titan.transpiler.tir.JdbcStatementLowerer} uses for the fused arity check. (WS-C Phase 3 Rung 2
     * audit — literal-'?' false-reject finding.)
     */
    public static int countQuestionMarksOutsideStringLiterals(String text) {
        int count = 0;
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
            } else if (c == '\'') {
                inString = true;
            } else if (c == '?') {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether A's constant SQL is a clean, read-only, single-column {@code SELECT} that can be inlined
     * verbatim into B's {@code IN (…)} slot without changing the query's meaning or breaking its syntax.
     * Parser-free local edge-checks over the constant text (the same posture as {@link
     * #endsInInOpenParen}); any doubt refuses fusion. Requires:
     * <ul>
     *   <li>the trimmed text begins (case-insensitive) with {@code SELECT} or {@code WITH} — a
     *       data-modifying {@code UPDATE}/{@code INSERT}/{@code DELETE … RETURNING} cannot be a subquery
     *       and would drop A's side effects;</li>
     *   <li>no statement terminator {@code ;} and no {@code --} line comment or {@code /* *}{@code /}
     *       block comment <i>outside</i> string literals — either would unbalance/terminate the inlined
     *       parenthesized subquery (the {@code --} swallows B's closing {@code )});</li>
     *   <li>for a plain {@code SELECT} (not a {@code WITH} CTE, which we keep conservative and refuse),
     *       the select-list between the top-level {@code SELECT} and the first top-level {@code FROM}
     *       projects exactly one column: no top-level comma, and not {@code *} / {@code tbl.*}.</li>
     * </ul>
     */
    static boolean priorSqlIsFusableSubquery(String sql) {
        if (sql == null) {
            return false;
        }
        String trimmed = sql.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        // No statement terminator / comment that would break parenthesized nesting (outside literals).
        if (containsTerminatorOrComment(trimmed)) {
            return false;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        // Read-only: must start with SELECT (a WITH…SELECT is conservatively refused — single-column
        // projection of a CTE chain is not a clean local edge-check, so fail-safe).
        if (!startsWithKeyword(upper, "SELECT")) {
            return false;
        }
        return selectListIsSingleColumn(trimmed);
    }

    /** Whether {@code upperText} begins with {@code keyword} as a whole word (a following non-word char). */
    private static boolean startsWithKeyword(String upperText, String keyword) {
        if (!upperText.startsWith(keyword)) {
            return false;
        }
        if (upperText.length() == keyword.length()) {
            return true;
        }
        char after = upperText.charAt(keyword.length());
        return !(Character.isLetterOrDigit(after) || after == '_');
    }

    /**
     * Whether {@code sql} contains a statement-terminating {@code ;} or a {@code --} / {@code /* *}{@code
     * /} comment <b>outside</b> single-quoted string literals. Any of these makes A unsafe to inline as a
     * parenthesized subquery (the {@code ;} terminates the statement, the {@code --} comments out the
     * rest of the line including B's closing paren).
     */
    private static boolean containsTerminatorOrComment(String sql) {
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> inString = true;
                case ';' -> {
                    return true;
                }
                case '-' -> {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                        return true;
                    }
                }
                case '/' -> {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                        return true;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    /**
     * Whether the select-list of a {@code SELECT} projects exactly one column: scanning from after the
     * leading {@code SELECT} keyword to the first <b>top-level</b> {@code FROM} (depth-0, outside parens
     * and string literals), there is no top-level comma and the list is not {@code *} / {@code tbl.*}.
     * A subquery whose only FROM-less form (e.g. {@code SELECT 1}) is single-column is accepted; a list
     * with a top-level comma is multi-column → refuse.
     */
    private static boolean selectListIsSingleColumn(String sql) {
        int i = "SELECT".length(); // safe: caller verified the leading SELECT keyword.
        // Skip an optional DISTINCT/ALL quantifier so its boundary is not mistaken for the list.
        StringBuilder list = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean sawFrom = false;
        for (; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                list.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        list.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                list.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                list.append(c);
                continue;
            }
            if (c == ')') {
                depth--;
                list.append(c);
                continue;
            }
            if (depth == 0 && c == ',') {
                return false; // a top-level comma ⇒ more than one projected column.
            }
            if (depth == 0 && isFromKeywordAt(sql, i)) {
                sawFrom = true;
                break;
            }
            list.append(c);
        }
        String projected = list.toString().strip();
        // Strip a leading set-quantifier (DISTINCT / ALL) before inspecting for `*`.
        projected = stripLeadingSetQuantifier(projected);
        if (projected.isEmpty() && sawFrom) {
            return false; // `SELECT FROM …` — malformed; refuse.
        }
        // Refuse `*` or `tbl.*` (a star anywhere at the top level of the single-column list). With no
        // top-level comma, a `*` means the whole-row projection — multi-column and not the named list.
        return !topLevelListHasStar(projected);
    }

    /** Whether {@code sql} has the whole-word keyword {@code FROM} starting at index {@code i}. */
    private static boolean isFromKeywordAt(String sql, int i) {
        if (i + 4 > sql.length()) {
            return false;
        }
        if (!sql.regionMatches(true, i, "FROM", 0, 4)) {
            return false;
        }
        char before = i == 0 ? ' ' : sql.charAt(i - 1);
        char after = i + 4 < sql.length() ? sql.charAt(i + 4) : ' ';
        boolean wordBefore = Character.isLetterOrDigit(before) || before == '_';
        boolean wordAfter = Character.isLetterOrDigit(after) || after == '_';
        return !wordBefore && !wordAfter;
    }

    /** Drops a leading {@code DISTINCT} / {@code ALL} set-quantifier from a select-list, if present. */
    private static String stripLeadingSetQuantifier(String projected) {
        String upper = projected.toUpperCase(java.util.Locale.ROOT);
        if (startsWithKeyword(upper, "DISTINCT")) {
            return projected.substring("DISTINCT".length()).strip();
        }
        if (startsWithKeyword(upper, "ALL")) {
            return projected.substring("ALL".length()).strip();
        }
        return projected;
    }

    /**
     * Whether a single-column (no top-level comma) projection list contains a {@code *} at the top level
     * (outside parens/strings) — either bare {@code *} or {@code alias.*}. A {@code *} inside a function
     * call like {@code count(*)} sits at depth&gt;0 and is fine (a single computed column).
     */
    private static boolean topLevelListHasStar(String projected) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < projected.length(); i++) {
            char c = projected.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < projected.length() && projected.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> inString = true;
                case '(' -> depth++;
                case ')' -> depth--;
                case '*' -> {
                    if (depth == 0) {
                        return true;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    // A provable placeholder/separator constant (mirrors DynamicInListRecognizer / the skeleton recognizer).
    private static final java.util.regex.Pattern PLACEHOLDER_OR_SEPARATOR_ONLY =
            java.util.regex.Pattern.compile("[?,()\\s]*(?:(?i:in)[?,()\\s]*)*");
}
