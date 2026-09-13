package io.titan.transpiler.jdbc;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import io.titan.transpiler.ParsedSources;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Recognizer + <b>proof</b> for the unknown-shape result carrier (JDBC Tier-3, WS-C Phase 3 Rung 5;
 * design contract §2 D5 / §4 / §7 Rung 5). It identifies the <b>pure, metadata-driven generic
 * reader</b> — a reader whose RESULT SHAPE is not statically known (its columns are read via {@code
 * ResultSetMetaData}, not fixed {@code rs.getX(col)} calls) — and only that. The canonical shape:
 *
 * <pre>{@code
 *   List<Map<String,Object>> rows = new ArrayList<>();
 *   ResultSet rs = ps.executeQuery();
 *   ResultSetMetaData md = rs.getMetaData();
 *   while (rs.next()) {
 *       Map<String,Object> row = new LinkedHashMap<>();
 *       for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
 *       rows.add(row);
 *   }
 *   return rows;
 * }</pre>
 *
 * <p>The whole method lowers to a single shape-agnostic <i>carrier</i> ({@link
 * io.titan.transpiler.tir.DynamicResultStatement}) returning exactly the same data the {@code
 * List<Map>} would, in the source dialect's native metadata-driven form (PostgreSQL {@code jsonb} via
 * {@code to_jsonb}/{@code jsonb_agg}; MySQL a native open result set in a procedure).</p>
 *
 * <h2>The Tier-3 / Tier-4 fail-safe (critical)</h2>
 * The carrier is sound <b>only</b> when the post-fetch processing is a faithful
 * column→map→list copy. The instant the Java does <i>any</i> business logic on the unknown-shape rows
 * — computes/branches on a column value, filters rows in Java, calls a method per row/column, builds a
 * typed DTO, accumulates a sum — there is <b>no server-side form</b>, so {@link #recognize} refuses
 * (returns {@link java.util.Optional#empty()}). The lowerer then rejects with a Tier-4 diagnostic
 * ("generic ResultSet processing beyond row marshalling cannot be transpiled; …"). This is the same
 * never-false-accept posture as {@link DynamicInListRecognizer}/{@link JdbcCollectionInRecognizer}: in
 * any doubt, do not carrier-lower.
 *
 * <p>Concretely, the method body must be <b>exactly</b> (modulo ordering of the three setup
 * declarations, and intervening pure JDBC scaffolding the front-end elides):</p>
 * <ul>
 *   <li>a {@code List<Map<String,Object>> rows = new ArrayList<>()} accumulator declaration;</li>
 *   <li>a {@code ResultSet} obtained from a statement's {@code executeQuery()} (the SQL source);</li>
 *   <li>a {@code ResultSetMetaData md = rs.getMetaData()} declaration;</li>
 *   <li>a {@code while (rs.next())} loop whose body is exactly: a fresh per-row {@code
 *       Map<String,Object>} ({@code new HashMap/LinkedHashMap/TreeMap}); a {@code for (int i = 1; i <=
 *       md.getColumnCount(); i++)} loop whose sole body is {@code row.put(md.getColumnLabel(i),
 *       rs.getObject(i))} — keyed by {@code getColumnLabel} ONLY, never {@code getColumnName} (the
 *       carrier keys by the SQL output label/alias, which {@code getColumnLabel} reproduces and {@code
 *       getColumnName}, the base column name, does not; see {@link #isMetadataColumnNameCall}); and
 *       {@code rows.add(row)} — nothing else;</li>
 *   <li>a single {@code return rows} terminating the method.</li>
 * </ul>
 * Any extra statement, any other use of {@code rows}/{@code row}/{@code rs}/{@code md}, any typed
 * column read, any branch/compute, defeats recognition.
 */
public final class JdbcDynamicResultRecognizer {

    private final ParsedSources parsed;
    private final JdbcShapes shapes;
    private final JdbcTypeOracle oracle;

    public JdbcDynamicResultRecognizer(ParsedSources parsed, JdbcShapes shapes, JdbcTypeOracle oracle) {
        if (parsed == null || shapes == null || oracle == null) {
            throw new IllegalArgumentException("parsed/shapes/oracle must not be null");
        }
        this.parsed = parsed;
        this.shapes = shapes;
        this.oracle = oracle;
    }

    /**
     * A proven unknown-shape carrier plan. {@link #sqlArg} is the {@code executeQuery}/{@code
     * prepareStatement} SQL-text expression the carrier runs (lowered through the ordinary constant /
     * skeleton / permissive SQL gates — its safety is unchanged). {@link #subsumedTrees()} is the whole
     * generic-reader scaffolding the front-end replaces, which the {@code FeatureValidator} consults (by
     * tree identity) to exempt exactly the {@code new ArrayList}/{@code new LinkedHashMap}/{@code
     * row.put}/{@code rows.add}/{@code md.*} marshalling constructs — never any other Map/collection op.
     */
    public record CarrierPlan(
            ExpressionTree sqlArg,
            StatementTree rowsDeclaration,
            StatementTree resultSetDeclaration,
            StatementTree metaDataDeclaration,
            StatementTree whileLoop,
            StatementTree returnStatement) {

        /**
         * The Java AST nodes a proven carrier <b>subsumes</b> — every node inside the {@code rows}
         * accumulator declaration, the {@code ResultSetMetaData md} declaration, the marshalling {@code
         * while} loop (its {@code new Map}, the {@code for} over {@code md.getColumnCount()}, the {@code
         * row.put(md.getColumnLabel(i), rs.getObject(i))} and {@code rows.add(row)}), and the {@code
         * return rows}. The {@code FeatureValidator} exempts exactly these from the unsupported
         * Map/collection/object-construction rejections; anything outside this set still rejects, so the
         * safety boundary is precise (mirrors {@link JdbcCollectionInRecognizer.CollectionInPlan#subsumedTrees()}).
         */
        public java.util.Set<Tree> subsumedTrees() {
            java.util.Set<Tree> subsumed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            TreeScanner<Void, Void> collector = new TreeScanner<>() {
                @Override
                public Void scan(Tree node, Void unused) {
                    if (node != null) {
                        subsumed.add(node);
                    }
                    return super.scan(node, unused);
                }
            };
            collector.scan(rowsDeclaration, null);
            collector.scan(metaDataDeclaration, null);
            collector.scan(whileLoop, null);
            collector.scan(returnStatement, null);
            return subsumed;
        }
    }

    /**
     * Attempts to recognize the whole {@code methodBody} as a pure metadata-driven generic reader.
     * Returns the proven {@link CarrierPlan}, or empty when the body is not exactly that shape or the
     * post-fetch processing does any business logic (fail-safe → the lowerer rejects Tier-4). {@code
     * methodBodyPath} is the method body's path (for type/element resolution and the use-and-escape
     * proof scan).
     */
    public java.util.Optional<CarrierPlan> recognize(BlockTree methodBody, TreePath methodBodyPath) {
        if (methodBody == null || methodBodyPath == null) {
            return java.util.Optional.empty();
        }
        List<? extends StatementTree> body = methodBody.getStatements();

        // (1) THE ACCUMULATOR: a `List<Map<String,Object>> rows = new ArrayList<>()`.
        StatementTree rowsDecl = null;
        Element rowsElement = null;
        for (StatementTree statement : body) {
            if (statement instanceof VariableTree variable && isListOfMapDeclaration(variable, methodBodyPath)) {
                if (rowsDecl != null) {
                    return java.util.Optional.empty(); // two List<Map> accumulators -> not the single-reader shape.
                }
                rowsDecl = statement;
                rowsElement = parsed.trees().getElement(new TreePath(methodBodyPath, variable));
            }
        }
        if (rowsDecl == null || rowsElement == null) {
            return java.util.Optional.empty();
        }

        // (2) THE while (rs.next()) MARSHALLING LOOP. Exactly one, over a ResultSet.
        com.sun.source.tree.WhileLoopTree whileLoop = null;
        Element resultSet = null;
        for (StatementTree statement : body) {
            if (statement instanceof com.sun.source.tree.WhileLoopTree loop) {
                Element rs = shapes.resultSetNextReceiver(
                        JdbcShapes.unwrap(loop.getCondition()), new TreePath(methodBodyPath, loop));
                if (rs != null) {
                    if (whileLoop != null) {
                        return java.util.Optional.empty(); // two row loops -> not the single-reader shape.
                    }
                    whileLoop = loop;
                    resultSet = rs;
                }
            }
        }
        if (whileLoop == null || resultSet == null) {
            return java.util.Optional.empty();
        }

        // (3) THE ResultSetMetaData md = rs.getMetaData() — over the SAME ResultSet.
        StatementTree metaDataDecl = null;
        Element metaData = null;
        for (StatementTree statement : body) {
            if (statement instanceof VariableTree variable) {
                Element md = metaDataDeclarationOf(variable, resultSet, methodBodyPath);
                if (md != null) {
                    if (metaDataDecl != null) {
                        return java.util.Optional.empty();
                    }
                    metaDataDecl = statement;
                    metaData = md;
                }
            }
        }
        if (metaDataDecl == null || metaData == null) {
            return java.util.Optional.empty();
        }

        // (4) THE SQL SOURCE: the statement whose executeQuery() drives this ResultSet, and its SQL arg.
        ExpressionTree sqlArg = sqlSourceFor(resultSet, body, methodBodyPath);
        if (sqlArg == null) {
            return java.util.Optional.empty();
        }

        // (5) THE LOOP BODY must be EXACTLY the pure marshalling: new row map + for-put + rows.add.
        //     Any business logic here is Tier-4 -> refuse.
        if (!isPureMarshallingLoopBody(whileLoop, resultSet, metaData, rowsElement, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        // (6) THE TERMINATOR: a single `return rows`.
        StatementTree returnStatement = soleReturnOfRows(body, rowsElement, methodBodyPath);
        if (returnStatement == null) {
            return java.util.Optional.empty();
        }

        // (7) THE USE-AND-ESCAPE PROOF: `rows` is used ONLY by its own declaration, the `rows.add(row)`
        //     in the loop, and the `return rows`. The per-row `row` is used ONLY by its own declaration,
        //     the `row.put(...)` and the `rows.add(row)`. The unknown-shape `rs`/`md` are used ONLY inside
        //     the recognized carrier pieces (the ResultSet/ResultSetMetaData declarations and the
        //     marshalling `while` loop — incl. its `rs.next()` condition). ANY other reference — a
        //     `md.getColumnCount()`/`rs.getRow()` AFTER the loop, an `rs.getX` outside it — is business
        //     logic on the unknown-shape result the carrier would silently drop; it has no server-side
        //     form, so refuse (Tier-4). Fail-safe: in doubt, do not carrier-lower.
        StatementTree resultSetDecl = resultSetDeclarationOf(resultSet, body, methodBodyPath);
        if (!accumulatorUsedOnlyForCarrier(rowsElement, returnStatement, methodBodyPath)
                || !unknownShapeHandlesUsedOnlyForCarrier(
                        resultSet, metaData, whileLoop, resultSetDecl, metaDataDecl, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new CarrierPlan(
                sqlArg, rowsDecl, resultSetDecl, metaDataDecl, whileLoop, returnStatement));
    }

    // ---- (1) the List<Map<String,Object>> accumulator ----

    /**
     * Whether {@code variable} is {@code List<Map<String,Object>> rows = new ArrayList<>()} (or {@code
     * new ArrayList<...>()} / {@code new LinkedList<>()}): a {@code List}-typed local whose single type
     * argument is {@code Map<String,Object>} (or {@code Map<String,?>}), initialised with a fresh empty
     * list construction.
     */
    private boolean isListOfMapDeclaration(VariableTree variable, TreePath methodBodyPath) {
        TypeMirror type = parsed.trees().getTypeMirror(new TreePath(methodBodyPath, variable));
        if (!isListOfMapType(type)) {
            return false;
        }
        // The LHS static type is List<Map<String,Object>> (verified above); require a FRESH empty
        // construction — `new ArrayList<>()`/`new LinkedList<>()` with no constructor args. A no-arg
        // collection constructor is an empty accumulator by definition; a copy-constructor (`new
        // ArrayList<>(other)`) is refused so the carrier can never silently drop pre-seeded rows.
        return isFreshNoArgConstruction(variable.getInitializer());
    }

    /** Whether {@code type} is {@code java.util.List<Map<String,Object>>} (List subtype, Map element). */
    private boolean isListOfMapType(TypeMirror type) {
        if (!(type instanceof DeclaredType declaredType) || declaredType.getTypeArguments().size() != 1) {
            return false;
        }
        if (!isSubtypeOf(type, "java.util.List")) {
            return false;
        }
        return isStringObjectMapType(declaredType.getTypeArguments().getFirst());
    }

    /** Whether {@code type} is {@code Map<String,Object>} (Map subtype; key String, value Object). */
    private boolean isStringObjectMapType(TypeMirror type) {
        if (!(type instanceof DeclaredType declaredType) || declaredType.getTypeArguments().size() != 2) {
            return false;
        }
        if (!isSubtypeOf(type, "java.util.Map")) {
            return false;
        }
        String key = declaredType.getTypeArguments().get(0).toString();
        String value = declaredType.getTypeArguments().get(1).toString();
        boolean stringKey = key.equals("java.lang.String") || key.equals("String");
        boolean objectValue = value.equals("java.lang.Object") || value.equals("Object")
                || value.equals("?") || value.startsWith("?");
        return stringKey && objectValue;
    }

    /**
     * Whether {@code initializer} is a fresh <b>no-argument</b> constructor call ({@code new X<>()}).
     * The LHS declared type already pins the collection/map kind (List&lt;Map&gt; / Map); this only
     * needs to confirm the value is a freshly-allocated empty container (no copy-constructor source).
     */
    private static boolean isFreshNoArgConstruction(ExpressionTree initializer) {
        ExpressionTree expr = JdbcShapes.unwrap(initializer);
        return expr instanceof com.sun.source.tree.NewClassTree newClass
                && newClass.getArguments().isEmpty();
    }

    // ---- (2)/(3) ResultSet + ResultSetMetaData declarations ----

    /**
     * If {@code variable} is {@code ResultSetMetaData md = <rs>.getMetaData()} over {@code resultSet},
     * returns its element; otherwise {@code null}. The receiver must be exactly the recognized
     * ResultSet (so a metadata read of a different result set is not mistaken for this carrier's).
     */
    private Element metaDataDeclarationOf(VariableTree variable, Element resultSet, TreePath methodBodyPath) {
        TreePath variablePath = new TreePath(methodBodyPath, variable);
        TypeMirror type = parsed.trees().getTypeMirror(variablePath);
        if (!isNamedType(type, "java.sql.ResultSetMetaData")) {
            return null;
        }
        ExpressionTree initializer = JdbcShapes.unwrap(variable.getInitializer());
        if (!(initializer instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("getMetaData")
                || !invocation.getArguments().isEmpty()) {
            return null;
        }
        Element receiver = shapes.elementOf(select.getExpression(), variablePath);
        if (receiver == null || !receiver.equals(resultSet)) {
            return null;
        }
        return parsed.trees().getElement(variablePath);
    }

    /** The {@code ResultSet rs = …} declaration for {@code resultSet}, or {@code null}. */
    private StatementTree resultSetDeclarationOf(
            Element resultSet, List<? extends StatementTree> body, TreePath methodBodyPath) {
        for (StatementTree statement : body) {
            if (statement instanceof VariableTree variable
                    && resultSet.equals(parsed.trees().getElement(new TreePath(methodBodyPath, variable)))) {
                return statement;
            }
        }
        return null;
    }

    // ---- (4) the SQL source driving the ResultSet ----

    /**
     * The SQL-text expression that produced {@code resultSet}: the {@code prepareStatement(SQL)} arg of
     * the statement whose {@code executeQuery()} initialised this ResultSet, or the inline {@code
     * st.executeQuery(SQL)} arg. Returns {@code null} when the source cannot be resolved (then the
     * carrier is refused — there is no SQL to run).
     */
    private ExpressionTree sqlSourceFor(
            Element resultSet, List<? extends StatementTree> body, TreePath methodBodyPath) {
        // rs = <stmt>.executeQuery()  OR  rs = <stmt>.executeQuery(SQL).
        ExpressionTree initializer = resultSetInitializer(resultSet, body, methodBodyPath);
        if (!(JdbcShapes.unwrap(initializer) instanceof MethodInvocationTree query)
                || !(query.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("executeQuery")) {
            return null;
        }
        // Inline form: st.executeQuery(SQL) — the SQL is the call's own argument.
        if (!query.getArguments().isEmpty()) {
            return query.getArguments().getFirst();
        }
        // Prepared form: ps.executeQuery() — resolve ps's prepareStatement(SQL) argument.
        Element statementHandle = shapes.elementOf(select.getExpression(),
                new TreePath(methodBodyPath, resultSetDeclarationOf(resultSet, body, methodBodyPath)));
        if (statementHandle == null) {
            return null;
        }
        for (StatementTree statement : body) {
            if (statement instanceof VariableTree variable
                    && statementHandle.equals(parsed.trees().getElement(new TreePath(methodBodyPath, variable)))) {
                ExpressionTree prepare = JdbcShapes.unwrap(variable.getInitializer());
                if (prepare instanceof MethodInvocationTree prepareCall
                        && prepareCall.getMethodSelect() instanceof MemberSelectTree prepareSelect
                        && prepareSelect.getIdentifier().contentEquals("prepareStatement")
                        && !prepareCall.getArguments().isEmpty()) {
                    return prepareCall.getArguments().getFirst();
                }
            }
        }
        return null;
    }

    private ExpressionTree resultSetInitializer(
            Element resultSet, List<? extends StatementTree> body, TreePath methodBodyPath) {
        for (StatementTree statement : body) {
            if (statement instanceof VariableTree variable
                    && resultSet.equals(parsed.trees().getElement(new TreePath(methodBodyPath, variable)))) {
                return variable.getInitializer();
            }
        }
        return null;
    }

    // ---- (5) the pure marshalling loop body ----

    /**
     * Whether the {@code while (rs.next())} body is EXACTLY the pure column-marshalling copy and nothing
     * else: a fresh per-row {@code Map<String,Object> row = new …Map<>()}; a {@code for (int i = 1; i <=
     * md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i))}; and {@code
     * rows.add(row)}. Exactly three statements, in any order that keeps the data flow valid (the row map
     * must be declared before it is put-into / added). Any fourth statement, any branch/compute, any
     * typed {@code rs.getX}, any other call defeats it (Tier-4).
     */
    private boolean isPureMarshallingLoopBody(
            com.sun.source.tree.WhileLoopTree whileLoop, Element resultSet, Element metaData,
            Element rowsElement, TreePath methodBodyPath) {
        StatementTree bodyStatement = whileLoop.getStatement();
        if (!(bodyStatement instanceof BlockTree block)) {
            return false; // a non-block single-statement body cannot hold all three marshalling steps.
        }
        TreePath blockPath = new TreePath(new TreePath(methodBodyPath, whileLoop), block);
        List<? extends StatementTree> statements = block.getStatements();
        if (statements.size() != 3) {
            return false; // exactly: declare row, for-put, rows.add — nothing more, nothing less.
        }

        // The per-row Map declaration: `Map<String,Object> row = new LinkedHashMap<>()`.
        Element rowElement = null;
        StatementTree forPut = null;
        StatementTree rowsAdd = null;
        for (StatementTree statement : statements) {
            if (statement instanceof VariableTree variable && isFreshRowMapDeclaration(variable, blockPath)) {
                if (rowElement != null) {
                    return false;
                }
                rowElement = parsed.trees().getElement(new TreePath(blockPath, variable));
            } else if (statement instanceof ForLoopTree) {
                if (forPut != null) {
                    return false;
                }
                forPut = statement;
            } else if (statement instanceof ExpressionStatementTree) {
                if (rowsAdd != null) {
                    return false;
                }
                rowsAdd = statement;
            } else {
                return false; // any other statement kind (if/return/throw/…) -> business logic, refuse.
            }
        }
        if (rowElement == null || forPut == null || rowsAdd == null) {
            return false;
        }

        // The for-loop must be the metadata-driven put: for (int i = 1; i <= md.getColumnCount(); i++)
        // row.put(md.getColumnLabel(i), rs.getObject(i)).
        if (!isMetadataColumnPutLoop(
                (ForLoopTree) forPut, resultSet, metaData, rowElement, blockPath)) {
            return false;
        }

        // The trailing statement must be exactly `rows.add(row)`.
        return isRowsAdd((ExpressionStatementTree) rowsAdd, rowsElement, rowElement, blockPath);
    }

    /** Whether {@code variable} is {@code Map<String,Object> row = new HashMap/LinkedHashMap/TreeMap<>()}. */
    private boolean isFreshRowMapDeclaration(VariableTree variable, TreePath blockPath) {
        TypeMirror type = parsed.trees().getTypeMirror(new TreePath(blockPath, variable));
        if (!isStringObjectMapType(type)) {
            return false;
        }
        // The LHS static type is Map<String,Object> (verified above); require a fresh empty map (no
        // copy-constructor, so the carrier never silently drops pre-seeded entries).
        return isFreshNoArgConstruction(variable.getInitializer());
    }

    /**
     * Whether {@code loop} is {@code for (int i = 1; i <= md.getColumnCount(); i++)} whose sole body is
     * {@code row.put(md.getColumnLabel(i), rs.getObject(i))} (keyed by {@code getColumnLabel} only — a
     * {@code getColumnName} key is rejected, see {@link #isMetadataColumnNameCall}). The 1-based
     * {@code <=} bound over {@code md.getColumnCount()} is the metadata-driven column walk; the body's
     * put marshals every column by its result label, generically.
     */
    private boolean isMetadataColumnPutLoop(
            ForLoopTree loop, Element resultSet, Element metaData, Element rowElement, TreePath blockPath) {
        TreePath loopPath = new TreePath(blockPath, loop);
        Element counter = columnCounterInit(loop, loopPath);
        if (counter == null) {
            return false;
        }
        if (!conditionIsLeColumnCount(loop, counter, metaData, loopPath)) {
            return false;
        }
        if (!updateIsIncrement(loop, counter, loopPath)) {
            return false;
        }
        // The body must be exactly one statement: row.put(md.getColumnLabel(i), rs.getObject(i)).
        StatementTree bodyStatement = loop.getStatement();
        StatementTree only = bodyStatement;
        if (bodyStatement instanceof BlockTree block) {
            if (block.getStatements().size() != 1) {
                return false;
            }
            only = block.getStatements().getFirst();
            loopPath = new TreePath(loopPath, block);
        }
        if (!(only instanceof ExpressionStatementTree expressionStatement)
                || !(expressionStatement.getExpression() instanceof MethodInvocationTree putCall)) {
            return false;
        }
        return isRowPut(putCall, resultSet, metaData, rowElement, counter, new TreePath(loopPath, only));
    }

    /** The loop counter of {@code for (int i = 1; …)} (initializer a single {@code int i = 1}), or null. */
    private Element columnCounterInit(ForLoopTree loop, TreePath loopPath) {
        if (loop.getInitializer().size() != 1
                || !(loop.getInitializer().getFirst() instanceof VariableTree counterDecl)
                || !(JdbcShapes.unwrap(counterDecl.getInitializer()) instanceof LiteralTree initLit)
                || !(initLit.getValue() instanceof Integer initValue) || initValue != 1) {
            return null;
        }
        return parsed.trees().getElement(new TreePath(loopPath, counterDecl));
    }

    /** Whether the condition is {@code i <= md.getColumnCount()} over the same counter and metadata. */
    private boolean conditionIsLeColumnCount(
            ForLoopTree loop, Element counter, Element metaData, TreePath loopPath) {
        if (!(JdbcShapes.unwrap(loop.getCondition()) instanceof BinaryTree condition)
                || condition.getKind() != Tree.Kind.LESS_THAN_EQUAL) {
            return false;
        }
        Element conditionVar = shapes.elementOf(JdbcShapes.unwrap(condition.getLeftOperand()), loopPath);
        if (conditionVar == null || !conditionVar.equals(counter)) {
            return false;
        }
        return isColumnCountCall(JdbcShapes.unwrap(condition.getRightOperand()), metaData, loopPath);
    }

    /** Whether {@code expr} is {@code md.getColumnCount()} over the recognized metadata element. */
    private boolean isColumnCountCall(ExpressionTree expr, Element metaData, TreePath path) {
        if (!(expr instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("getColumnCount")
                || !invocation.getArguments().isEmpty()) {
            return false;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        return receiver != null && receiver.equals(metaData);
    }

    /** Whether the for-update is exactly {@code i++}/{@code ++i}/{@code i += 1} over the counter. */
    private boolean updateIsIncrement(ForLoopTree loop, Element counter, TreePath loopPath) {
        if (loop.getUpdate().size() != 1
                || !(loop.getUpdate().getFirst() instanceof ExpressionStatementTree updateStatement)) {
            return false;
        }
        ExpressionTree update = JdbcShapes.unwrap(updateStatement.getExpression());
        if (update instanceof UnaryTree unary
                && (unary.getKind() == Tree.Kind.POSTFIX_INCREMENT
                        || unary.getKind() == Tree.Kind.PREFIX_INCREMENT)) {
            Element target = shapes.elementOf(JdbcShapes.unwrap(unary.getExpression()), loopPath);
            return target != null && target.equals(counter);
        }
        if (update instanceof com.sun.source.tree.CompoundAssignmentTree compound
                && compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT) {
            Element target = shapes.elementOf(JdbcShapes.unwrap(compound.getVariable()), loopPath);
            Integer amount = JdbcShapes.constantIntValue(compound.getExpression());
            return target != null && target.equals(counter) && amount != null && amount == 1;
        }
        return false;
    }

    /**
     * Whether {@code putCall} is {@code row.put(md.getColumnLabel(i), rs.getObject(i))} (or {@code
     * getColumnName}): the put receiver is the per-row map; the key is the metadata column label/name of
     * the SAME counter; the value is {@code rs.getObject(i)} over the SAME ResultSet and counter. This is
     * the generic, shape-agnostic marshal — NOT a typed {@code rs.getString(...)}/computed value.
     */
    private boolean isRowPut(
            MethodInvocationTree putCall, Element resultSet, Element metaData, Element rowElement,
            Element counter, TreePath path) {
        if (!(putCall.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("put")
                || putCall.getArguments().size() != 2) {
            return false;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        if (receiver == null || !receiver.equals(rowElement)) {
            return false;
        }
        // key: md.getColumnLabel(i) / md.getColumnName(i) over the same counter.
        if (!isMetadataColumnNameCall(
                JdbcShapes.unwrap(putCall.getArguments().get(0)), metaData, counter, path)) {
            return false;
        }
        // value: rs.getObject(i) over the same ResultSet and counter — the shape-agnostic read.
        return isResultSetGetObjectCall(
                JdbcShapes.unwrap(putCall.getArguments().get(1)), resultSet, counter, path);
    }

    /**
     * Whether {@code expr} is {@code md.getColumnLabel(i)} over counter {@code i}. ONLY {@code
     * getColumnLabel} is admitted — NOT {@code getColumnName}.
     *
     * <p><b>Why getColumnName is rejected (fail-safe; WS-C Phase 3 Rung 5 audit, critical/high).</b> The
     * carrier keys every output map by the SQL <i>output label</i> — PostgreSQL {@code to_jsonb(t)} over a
     * {@code FROM (<sql>) t} derived table keys by the derived-table column name (= the SELECT alias);
     * MySQL streams the native result-set headers (= the aliases). JDBC's {@code getColumnLabel(i)} IS
     * that output label, so a {@code getColumnLabel}-keyed reader is reproduced faithfully. But {@code
     * getColumnName(i)} returns the <i>base</i> column name (e.g. for {@code SELECT id AS widget_id},
     * {@code getColumnName=="id"} while {@code getColumnLabel=="widget_id"} — they DIFFER for any
     * aliased/expression column, verified on the MySQL driver). The carrier has NO server-side form that
     * keys by base column name (there is no portable "un-alias" of an arbitrary projection), so a {@code
     * getColumnName}-keyed reader over an aliased SELECT would silently produce the WRONG map keys. The
     * recognizer cannot tell an aliased from an unaliased SELECT without parsing the SQL, so the fail-safe
     * is to REJECT {@code getColumnName} entirely (it falls through to the Tier-4 reject) rather than
     * carrier-lower it with label semantics.
     */
    private boolean isMetadataColumnNameCall(
            ExpressionTree expr, Element metaData, Element counter, TreePath path) {
        if (!(expr instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || invocation.getArguments().size() != 1) {
            return false;
        }
        String name = select.getIdentifier().toString();
        if (!name.equals("getColumnLabel")) {
            return false;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        if (receiver == null || !receiver.equals(metaData)) {
            return false;
        }
        Element indexVar = shapes.elementOf(JdbcShapes.unwrap(invocation.getArguments().getFirst()), path);
        return indexVar != null && indexVar.equals(counter);
    }

    /** Whether {@code expr} is {@code rs.getObject(i)} over the recognized ResultSet and counter {@code i}. */
    private boolean isResultSetGetObjectCall(
            ExpressionTree expr, Element resultSet, Element counter, TreePath path) {
        if (!(expr instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("getObject")
                || invocation.getArguments().size() != 1) {
            return false;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        if (receiver == null || !receiver.equals(resultSet)) {
            return false;
        }
        Element indexVar = shapes.elementOf(JdbcShapes.unwrap(invocation.getArguments().getFirst()), path);
        return indexVar != null && indexVar.equals(counter);
    }

    /** Whether {@code statement} is exactly {@code rows.add(row)} over the accumulator and per-row map. */
    private boolean isRowsAdd(
            ExpressionStatementTree statement, Element rowsElement, Element rowElement, TreePath blockPath) {
        if (!(statement.getExpression() instanceof MethodInvocationTree addCall)
                || !(addCall.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("add")
                || addCall.getArguments().size() != 1) {
            return false;
        }
        TreePath statementPath = new TreePath(blockPath, statement);
        Element receiver = shapes.elementOf(select.getExpression(), statementPath);
        if (receiver == null || !receiver.equals(rowsElement)) {
            return false;
        }
        Element argument = shapes.elementOf(JdbcShapes.unwrap(addCall.getArguments().getFirst()), statementPath);
        return argument != null && argument.equals(rowElement);
    }

    // ---- (6) the return ----

    /**
     * The single {@code return rows} that terminates {@code body}, or {@code null} when the method does
     * not return exactly the accumulator (a transformed/wrapped/second return is business logic — Tier-4).
     */
    private StatementTree soleReturnOfRows(
            List<? extends StatementTree> body, Element rowsElement, TreePath methodBodyPath) {
        StatementTree found = null;
        for (StatementTree statement : body) {
            if (statement instanceof ReturnTree returnTree) {
                Element returned = returnTree.getExpression() == null ? null
                        : shapes.elementOf(JdbcShapes.unwrap(returnTree.getExpression()),
                                new TreePath(methodBodyPath, statement));
                if (returned == null || !returned.equals(rowsElement)) {
                    return null; // returns something other than `rows` -> not a faithful carrier.
                }
                if (found != null) {
                    return null;
                }
                found = statement;
            }
        }
        return found;
    }

    // ---- (7) the use-and-escape proof ----

    /**
     * Proves {@code rows} is consumed <b>only</b> as the carrier accumulator: every reference to it is
     * either its own declaration, the {@code rows.add(row)} inside the marshalling loop, or the {@code
     * return rows}. ANY other use — a {@code rows.get}/{@code size}/{@code stream}/iteration, passing it
     * to a method, a second return, a field store — means the Java does business logic on the rows the
     * carrier would silently drop, so the carrier is refused (Tier-4). Fail-safe: in doubt, do not
     * carrier-lower. (The {@code row}/{@code md} single-use is already pinned by the exact-shape loop-body
     * check; this guards the accumulator's whole-method escapes.)
     */
    private boolean accumulatorUsedOnlyForCarrier(
            Element rowsElement, StatementTree returnStatement, TreePath methodBodyPath) {
        boolean[] defeated = {false};
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Element receiver = parsed.trees().getElement(
                        new TreePath(getCurrentPath(), node.getExpression()));
                if (receiver != null && receiver.equals(rowsElement) && !isAllowedAddCall(node)) {
                    defeated[0] = true; // a `rows.<anything-but-the-proven-add>` use -> business logic.
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element != null && element.equals(rowsElement)
                        && !isMemberSelectReceiver(getCurrentPath())
                        && !isTheReturnExpression(getCurrentPath())) {
                    // A bare `rows` reference that is NOT a `rows.method(...)` receiver and NOT the
                    // `return rows` expression — an escape (argument, assignment, second return). Refuse.
                    defeated[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            private boolean isAllowedAddCall(MemberSelectTree node) {
                // The proven `rows.add(row)` (a one-arg add()). Other rows.add overloads / any other
                // method are not the carrier accumulation.
                if (!node.getIdentifier().contentEquals("add")) {
                    return false;
                }
                Tree parent = getCurrentPath().getParentPath() == null
                        ? null : getCurrentPath().getParentPath().getLeaf();
                return parent instanceof MethodInvocationTree call
                        && call.getMethodSelect() == node && call.getArguments().size() == 1;
            }

            private boolean isMemberSelectReceiver(TreePath path) {
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                return parent instanceof MemberSelectTree memberSelect
                        && memberSelect.getExpression() == path.getLeaf();
            }

            private boolean isTheReturnExpression(TreePath path) {
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                return parent == returnStatement
                        || (parent instanceof ReturnTree returnTree
                                && JdbcShapes.unwrap(returnTree.getExpression()) == path.getLeaf());
            }
        }.scan(methodBodyPath, null);
        return !defeated[0];
    }

    /**
     * Proves the unknown-shape JDBC handles {@code resultSet} ({@code rs}) and {@code metaData} ({@code
     * md}) are consumed <b>only</b> inside the recognized carrier pieces: their own declarations, and the
     * marshalling {@code while} loop (whose {@code rs.next()} condition, {@code md.getColumnCount()} bound,
     * {@code md.getColumnLabel(i)} key and {@code rs.getObject(i)} value are the proven generic marshal).
     * ANY reference to {@code rs}/{@code md} OUTSIDE those subtrees — a {@code md.getColumnCount()} or
     * {@code rs.getRow()} after the loop, an {@code rs.getX} elsewhere — is residual business logic on the
     * unknown-shape result; it has no server-side form and {@link
     * io.titan.transpiler.tir.JavaToTirLowerer} could not lower it, so the carrier is refused (Tier-4).
     *
     * <p>Without this, the use-and-escape proof covered only {@code rows}; a residual {@code md}/{@code rs}
     * read after the loop slipped past {@code recognize()} and crashed lowering later with a misleading
     * "no SQL lowering for getColumnCount" instead of the designed Tier-4 fail-safe (WS-C Phase 3 Rung 5
     * audit, medium). The per-row {@code row} single-use stays pinned by the exact-shape loop-body check.</p>
     */
    private boolean unknownShapeHandlesUsedOnlyForCarrier(
            Element resultSet, Element metaData, StatementTree whileLoop, StatementTree resultSetDecl,
            StatementTree metaDataDecl, TreePath methodBodyPath) {
        // The subtrees a reference to rs/md MAY lie within: their declarations and the marshalling loop.
        java.util.Set<Tree> carrierPieces =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        carrierPieces.add(whileLoop);
        carrierPieces.add(resultSetDecl);
        carrierPieces.add(metaDataDecl);
        boolean[] defeated = {false};
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if ((isResultSet(element) || isMetaData(element)) && !isWithinACarrierPiece(getCurrentPath())) {
                    defeated[0] = true; // an rs/md reference outside the recognized pieces -> residual use.
                }
                return super.visitIdentifier(node, unused);
            }

            private boolean isResultSet(Element element) {
                return element != null && resultSet != null && element.equals(resultSet);
            }

            private boolean isMetaData(Element element) {
                return element != null && metaData != null && element.equals(metaData);
            }

            /** Whether some ancestor on {@code path} is one of the recognized carrier subtrees. */
            private boolean isWithinACarrierPiece(TreePath path) {
                for (TreePath p = path; p != null; p = p.getParentPath()) {
                    if (carrierPieces.contains(p.getLeaf())) {
                        return true;
                    }
                }
                return false;
            }
        }.scan(methodBodyPath, null);
        return !defeated[0];
    }

    // ---- small type helpers ----

    private boolean isSubtypeOf(TypeMirror type, String qualifiedName) {
        if (!(type instanceof DeclaredType)) {
            return false;
        }
        javax.lang.model.element.TypeElement target = parsed.elements().getTypeElement(qualifiedName);
        if (target == null) {
            return false;
        }
        return parsed.types().isAssignable(
                parsed.types().erasure(type), parsed.types().erasure(target.asType()));
    }

    private static boolean isNamedType(TypeMirror type, String qualifiedName) {
        return type instanceof DeclaredType declaredType
                && declaredType.asElement() instanceof javax.lang.model.element.TypeElement element
                && element.getQualifiedName().contentEquals(qualifiedName);
    }
}
