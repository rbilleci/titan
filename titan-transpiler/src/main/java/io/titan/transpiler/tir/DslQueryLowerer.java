package io.titan.transpiler.tir;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Titan DSL query lowering for the Java-to-TIR pass: select-chain compilation
 * ({@link DslSelectChainState} driven by {@link DslChainParser}), insert/update/delete
 * statements, CTE and set-operation handling, and projection resolution/validation.
 */
final class DslQueryLowerer {

    private DslQueryLowerer() {
    }

    private static final String PHYSICAL_TABLE_ANNOTATION = "titan.dsl.PhysicalTable";

    private static final String PHYSICAL_COLUMN_ANNOTATION = "titan.dsl.PhysicalColumn";

    /**
     * Phase A4 / G2: the typed DSL terminals whose result is read into a typed routine local and
     * branched on — {@code fetchScalar()} (a single-column scalar value) and
     * {@code fetchExistsValue()} (an EXISTS boolean). Distinct from the SQL-skeleton terminals
     * ({@code fetch()}/{@code fetchOne()}/{@code fetchExists()}/...), which return {@code String}
     * and are fire-and-forget in routine bodies.
     */
    static boolean isDslReadIntoLocalTerminal(ExpressionTree expression) {
        if (!(expression instanceof MethodInvocationTree invocation)) {
            return false;
        }
        String terminal = LowererSupport.invocationName(invocation);
        return "fetchScalar".equals(terminal) || "fetchExistsValue".equals(terminal);
    }

    /**
     * Lowers a {@code fetchScalar()}/{@code fetchExistsValue()} DSL chain to the {@link SelectSql}
     * to bind into a routine local. Reuses the shared terminal lowering (projection/limit/exists
     * shaping), then validates the read-into-local scope: exactly one projected column for a scalar
     * read (an EXISTS read carries the wrapper instead). Multi-row INTO / full result sets are out
     * of scope by design (that is the row-materialization machinery).
     */
    static SelectSql lowerDslReadIntoLocal(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        SqlNode lowered = tryLowerDslSqlInvocation(invocation, invocationPath, parsedSources);
        if (!(lowered instanceof SelectSql select)) {
            throw LowererSupport.unsupportedFeature(
                    "Could not lower a read-into-local DSL terminal ("
                            + LowererSupport.invocationName(invocation) + ") to a SELECT query",
                    invocation,
                    parsedSources);
        }
        if (!select.existsWrapper() && select.columns().size() != 1) {
            // A scalar SELECT ... INTO binds exactly one column to one local; reject anything wider
            // with a positioned diagnostic instead of emitting a SELECT INTO that the database would
            // reject at execute ("too many columns"/"more than one column").
            throw LowererSupport.unsupportedFeature(
                    "fetchScalar() binds a single column into a routine local, but the projection has "
                            + select.columns().size() + " columns",
                    invocation,
                    parsedSources,
                    "Project exactly one column (for example, select(TABLE.COLUMN)), or use fetchExistsValue() for an existence check");
        }
        return select;
    }

    static SqlNode tryLowerDslSqlInvocation(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        String terminal = LowererSupport.invocationName(invocation);

        MethodInvocationTree terminalInvocation = invocation;
        Integer valueProjectionIndex = null;
        java.util.regex.Matcher valueMatcher = java.util.regex.Pattern.compile("value(\\d+)").matcher(terminal);
        if (valueMatcher.matches()) {
            if (invocation.getMethodSelect() instanceof MemberSelectTree member
                    && member.getExpression() instanceof MethodInvocationTree previous
                    && List.of("fetch", "fetchOne").contains(LowererSupport.invocationName(previous))) {
                terminalInvocation = previous;
                valueProjectionIndex = Integer.parseInt(valueMatcher.group(1)) - 1;
                terminal = LowererSupport.invocationName(terminalInvocation);
            }
        }

        // Phase A4 / G2: the typed read-into-local terminals behave like their SQL-skeleton
        // siblings for query shaping — fetchScalar() is a single-row fetchOne(), fetchExistsValue()
        // is an EXISTS check — but their result is bound to a routine local (SELECT ... INTO) by the
        // statement lowerer rather than discarded. They normalize to the existing terminal handling
        // here so projection/limit/exists-wrapper shaping stays in one place.
        if ("fetchScalar".equals(terminal)) {
            terminal = "fetchOne";
        } else if ("fetchExistsValue".equals(terminal)) {
            terminal = "fetchExists";
        }

        if (!List.of("fetch", "fetchOne", "fetchInto", "fetchCount", "fetchExists", "forEach", "execute").contains(terminal)) {
            return null;
        }

        if ("fetchInto".equals(terminal) && terminalInvocation.getArguments().size() != 1) {
            throw LowererSupport.unsupportedFeature("fetchInto(...) requires exactly one record class argument", terminalInvocation, parsedSources);
        }

        DslChainParser.Chain chain = DslChainParser.parse(
                terminalInvocation, DslChainParser.STATEMENT_STEPS, parsedSources);
        MethodInvocationTree root = chain.root();
        DslSelectChainState state = new DslSelectChainState();
        List<SetOpStep> setOps = new ArrayList<>();
        List<String> insertColumns = new ArrayList<>();
        List<ExpressionNode> insertValues = new ArrayList<>();
        List<String> conflictColumns = new ArrayList<>();
        List<SetClause> conflictUpdates = new ArrayList<>();

        for (DslChainParser.Step step : chain.steps()) {
            if (state.apply(step, parsedSources)) {
                continue;
            }
            MethodInvocationTree cursor = step.invocation();
            switch (step.kind()) {
                case SET -> {
                    String column = toColumnName(cursor.getArguments().getFirst(), parsedSources);
                    ExpressionNode value = jsonCastIfTargetColumnIsJson(
                            lowerDslValue(cursor.getArguments().get(1), parsedSources),
                            cursor.getArguments().getFirst(),
                            parsedSources);
                    if (column == null) {
                        // A silently-dropped assignment is lost data; fail loudly (Phase 3.1).
                        throw LowererSupport.unsupportedFeature(
                                "Could not resolve the set(...) column argument to a DSL table column",
                                cursor.getArguments().getFirst(),
                                parsedSources,
                                "Reference the column directly on its table object (for example, table.COLUMN_NAME)");
                    }
                    if (invocationChainContains(cursor, "doUpdate")) {
                        conflictUpdates.add(0, new SetClause(column, value));
                    } else {
                        insertColumns.add(0, column);
                        insertValues.add(0, value);
                    }
                }
                case ON_CONFLICT -> {
                    for (ExpressionTree argument : cursor.getArguments()) {
                        String column = toColumnName(argument, parsedSources);
                        if (column == null) {
                            // A silently-dropped conflict column changes statement semantics.
                            throw LowererSupport.unsupportedFeature(
                                    "Could not resolve the onConflict(...) column argument to a DSL table column",
                                    argument,
                                    parsedSources,
                                    "Reference the column directly on its table object (for example, table.COLUMN_NAME)");
                        }
                        conflictColumns.add(column);
                    }
                }
                case SET_OPERATION -> {
                    ExpressionTree argument = cursor.getArguments().getFirst();
                    if (!(argument instanceof MethodInvocationTree rhsInvocation)) {
                        throw LowererSupport.unsupportedFeature(step.name() + "(...) requires a SelectBuilder invocation argument", argument, parsedSources);
                    }
                    SelectSql rhsSelect = lowerDslSelectBuilderInvocation(rhsInvocation, invocationPath, parsedSources);
                    List<String> rhsProjectionTypes = resolveDslSelectProjectionTypeKeys(
                            rhsInvocation,
                            new TreePath(invocationPath, argument),
                            parsedSources);
                    setOps.add(new SetOpStep(step.name(), rhsSelect, rhsProjectionTypes));
                }
                default -> {
                }
            }
        }

        String rootName = LowererSupport.invocationName(root);
        boolean withRoot = "with".equals(rootName) || "withRecursive".equals(rootName);
        MethodInvocationTree selectInvocation = "select".equals(rootName) ? root : state.projectionInvocation;
        if (selectInvocation != null || "selectFrom".equals(rootName)) {
            List<SelectColumn> projectedColumns = resolveDslChainProjection(rootName, root, selectInvocation, state, parsedSources);
            if (valueProjectionIndex != null && valueProjectionIndex >= 0 && valueProjectionIndex < projectedColumns.size()) {
                projectedColumns = List.of(projectedColumns.get(valueProjectionIndex));
            }

            if ("fetchInto".equals(terminal) && selectInvocation != null) {
                validateFetchIntoProjectionCoverage(
                        terminalInvocation,
                        invocationPath,
                        parsedSources,
                        projectedColumns,
                        selectInvocation.getArguments());
            }

            if ("forEach".equals(terminal)) {
                validateForEachCallbackArity(
                        terminalInvocation,
                        invocationPath,
                        parsedSources,
                        projectedColumns.size());
            }

            state.validateColumnReferences(selectInvocation, false, parsedSources);

            if ("fetchCount".equals(terminal)) {
                projectedColumns = List.of(new SelectColumn(
                        new FunctionCallExpression("COUNT", List.of(new ColumnRefExpression(null, "*")), null),
                        null));
            }

            Integer effectiveLimit = state.limit;
            if ("fetchCount".equals(terminal) || "fetchExists".equals(terminal)) {
                effectiveLimit = null;
            } else if ("fetchOne".equals(terminal) && effectiveLimit == null) {
                effectiveLimit = 1;
            }

            if (state.from == null || state.from.isBlank()) {
                throw LowererSupport.unsupportedFeature("Could not lower DSL select chain without a resolved from(...) source", invocation, parsedSources);
            }
            LockingClause locking = state.lockingClause(invocation, parsedSources);

            List<CteSpec> ctes = state.resolveCtes(withRoot, root, invocationPath, parsedSources);
            SqlNode selectSql = new SelectSql(projectedColumns, state.distinct, state.from, state.fromAlias, state.orderedJoins(), state.where, state.groupBy, state.having, state.orderBy, effectiveLimit, null, isDslFetchExistsTerminal(terminalInvocation), locking, ctes);
            if (!setOps.isEmpty()) {
                List<SetOpStep> orderedSetOps = new ArrayList<>(setOps);
                java.util.Collections.reverse(orderedSetOps);
                int leftProjectionCount = projectedColumns.size();
                List<String> leftProjectionTypes = resolveProjectionTypeKeys(
                        selectInvocation.getArguments(),
                        invocationPath,
                        parsedSources);
                for (SetOpStep setOp : orderedSetOps) {
                    int rightProjectionCount = setOp.rhs().columns().size();
                    if (leftProjectionCount != rightProjectionCount) {
                        throw LowererSupport.unsupportedFeature(
                                setOp.operation()
                                        + "(...) requires matching projection column count in lowered DSL chains (left="
                                        + leftProjectionCount + ", right=" + rightProjectionCount + ")",
                                invocation,
                                parsedSources);
                    }
                    List<String> rightProjectionTypes = setOp.projectionTypeKeys();
                    int comparableCount = Math.min(leftProjectionTypes.size(), rightProjectionTypes.size());
                    for (int i = 0; i < comparableCount; i++) {
                        String leftType = leftProjectionTypes.get(i);
                        String rightType = rightProjectionTypes.get(i);
                        if (!areSetOperationProjectionTypesCompatible(leftType, rightType)) {
                            throw LowererSupport.unsupportedFeature(
                                    setOp.operation()
                                            + "(...) requires projection column type compatibility in lowered DSL chains (index="
                                            + i + ", left=" + leftType + ", right=" + rightType + ")",
                                    invocation,
                                    parsedSources);
                        }
                    }
                    selectSql = switch (setOp.operation()) {
                        case "union" -> new UnionSql(selectSql, setOp.rhs(), false);
                        case "unionAll" -> new UnionSql(selectSql, setOp.rhs(), true);
                        case "intersect" -> new IntersectSql(selectSql, setOp.rhs());
                        case "except" -> new ExceptSql(selectSql, setOp.rhs());
                        default -> throw LowererSupport.loweringError(
                                TitanErrorCode.E001,
                                "Unsupported set operation in lowered DSL chain: " + setOp.operation(),
                                invocation,
                                parsedSources,
                                null);
                    };
                    leftProjectionCount = rightProjectionCount;
                    leftProjectionTypes = rightProjectionTypes;
                }
            }
            return selectSql;
        }

        if ("insertInto".equals(rootName) && !root.getArguments().isEmpty()) {
            ConflictClause conflictClause = null;
            if (!conflictColumns.isEmpty() || !conflictUpdates.isEmpty()) {
                conflictClause = new ConflictClause(List.copyOf(conflictColumns), List.copyOf(conflictUpdates));
            }
            return new InsertSql(
                    qualifiedTableName(root.getArguments().getFirst(), parsedSources),
                    List.copyOf(insertColumns),
                    List.copyOf(insertValues),
                    null,
                    conflictClause,
                    List.of());
        }

        if ("update".equals(rootName) && !root.getArguments().isEmpty()) {
            List<SetClause> updateSets = insertColumns.isEmpty()
                    ? List.of()
                    : java.util.stream.IntStream.range(0, insertColumns.size())
                            .mapToObj(i -> new SetClause(insertColumns.get(i), insertValues.get(i)))
                            .toList();
            return new UpdateSql(qualifiedTableName(root.getArguments().getFirst(), parsedSources), updateSets, state.where, List.of());
        }

        if ("deleteFrom".equals(rootName) && !root.getArguments().isEmpty()) {
            return new DeleteSql(qualifiedTableName(root.getArguments().getFirst(), parsedSources), state.where, List.of());
        }

        return null;
    }

    private static SelectSql lowerDslSelectBuilderInvocation(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        return lowerDslSelectBuilderInvocation(invocation, invocationPath, parsedSources, false);
    }

    private static SelectSql lowerDslSelectBuilderInvocation(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources,
            boolean allowOuterReferences
    ) {
        DslChainParser.Chain chain = DslChainParser.parse(
                invocation, DslChainParser.SELECT_BUILDER_STEPS, parsedSources);
        MethodInvocationTree root = chain.root();
        DslSelectChainState state = new DslSelectChainState();
        for (DslChainParser.Step step : chain.steps()) {
            state.apply(step, parsedSources);
        }

        String rootName = LowererSupport.invocationName(root);
        boolean withRoot = "with".equals(rootName) || "withRecursive".equals(rootName);
        MethodInvocationTree selectInvocation = "select".equals(rootName) ? root : state.projectionInvocation;
        if (selectInvocation == null && !"selectFrom".equals(rootName)) {
            throw LowererSupport.unsupportedFeature("Set-operation arguments must be DSL select(...) chains", invocation, parsedSources);
        }
        List<SelectColumn> projectedColumns = resolveDslChainProjection(rootName, root, selectInvocation, state, parsedSources);

        state.validateColumnReferences(selectInvocation, allowOuterReferences, parsedSources);

        if (state.from == null || state.from.isBlank()) {
            throw LowererSupport.unsupportedFeature("Could not lower DSL set-operation argument without a resolved from(...) source", invocation, parsedSources);
        }
        LockingClause locking = state.lockingClause(invocation, parsedSources);

        List<CteSpec> ctes = state.resolveCtes(withRoot, root, invocationPath, parsedSources);
        return new SelectSql(projectedColumns, state.distinct, state.from, state.fromAlias, state.orderedJoins(), state.where, state.groupBy, state.having, state.orderBy, state.limit, null, isDslFetchExistsTerminal(invocation), locking, ctes);
    }

    /**
     * Resolves the projected columns of a select chain. For {@code selectFrom(...)} roots this
     * also resolves the from-source into {@code state}; otherwise {@code selectInvocation} must
     * be non-null (callers enforce their own diagnostics before calling).
     */
    private static List<SelectColumn> resolveDslChainProjection(
            String rootName,
            MethodInvocationTree root,
            MethodInvocationTree selectInvocation,
            DslSelectChainState state,
            ParsedSources parsedSources
    ) {
        if ("selectFrom".equals(rootName)) {
            if (root.getArguments().isEmpty()) {
                throw LowererSupport.unsupportedFeature("selectFrom(...) requires a table argument", root, parsedSources);
            }
            state.fromExpression = root.getArguments().getFirst();
            // ATG-020: the FROM clause is schema-qualified; columns keep the bare table-name qualifier.
            state.from = qualifiedTableName(state.fromExpression, parsedSources);
            return lowerDslSelectFromColumns(
                    state.fromExpression, tableName(state.fromExpression, parsedSources), parsedSources);
        }
        return selectInvocation.getArguments().stream()
                .map(arg -> toSelectColumn(arg, parsedSources))
                .toList();
    }

    /**
     * Shared select-chain clause state accumulated from parsed DSL chain steps. Statement and
     * select-builder lowering interpret the shared clause steps identically; statement-only steps
     * (set/onConflict/set operations) are interpreted by the statement call site.
     */
    private static final class DslSelectChainState {
        private MethodInvocationTree projectionInvocation;
        private boolean distinct;
        private String from;
        private String fromAlias;
        private ExpressionTree fromExpression;
        private ExpressionNode where;
        private ExpressionTree whereExpression;
        private List<ExpressionNode> groupBy = List.of();
        private List<ExpressionTree> groupByExpressions = List.of();
        private ExpressionNode having;
        private ExpressionTree havingExpression;
        private List<OrderBySpec> orderBy = List.of();
        private List<ExpressionTree> orderByExpressions = List.of();
        private Integer limit;
        private final List<JoinSpec> joins = new ArrayList<>();
        private final List<JoinValidationSpec> joinValidations = new ArrayList<>();
        private boolean forUpdate;
        private boolean forShare;
        private boolean skipLocked;
        private boolean noWait;

        /** Interprets a shared clause step; returns false for steps the caller must interpret. */
        boolean apply(DslChainParser.Step step, ParsedSources parsedSources) {
            MethodInvocationTree cursor = step.invocation();
            MethodInvocationTree previous = step.receiver();
            switch (step.kind()) {
                case SELECT -> projectionInvocation = cursor;
                case DISTINCT -> distinct = true;
                case FROM -> {
                    fromExpression = cursor.getArguments().getFirst();
                    AliasedTableRef aliased = resolveDslAliasedTable(fromExpression, parsedSources);
                    if (aliased != null) {
                        from = aliased.tableName();
                        fromAlias = aliased.alias();
                    } else {
                        // ATG-020: schema-qualify the FROM clause (columns keep the bare qualifier).
                        from = qualifiedTableName(fromExpression, parsedSources);
                    }
                }
                case WHERE -> {
                    whereExpression = cursor.getArguments().getFirst();
                    where = lowerDslCondition(cursor.getArguments().getFirst(), parsedSources);
                }
                case GROUP_BY -> {
                    groupByExpressions = List.copyOf(cursor.getArguments());
                    groupBy = cursor.getArguments().stream()
                            .map(arg -> lowerDslGroupByExpression(arg, parsedSources))
                            .toList();
                }
                case HAVING -> {
                    havingExpression = cursor.getArguments().getFirst();
                    having = lowerDslCondition(cursor.getArguments().getFirst(), parsedSources);
                }
                case LIMIT -> limit = (Integer) ((LiteralTree) cursor.getArguments().getFirst()).getValue();
                case ORDER_BY -> {
                    orderByExpressions = List.copyOf(cursor.getArguments());
                    orderBy = lowerDslOrderBy(cursor.getArguments(), parsedSources);
                }
                case JOIN_ON -> {
                    JoinType joinType = DslChainParser.toJoinType(LowererSupport.invocationName(previous));
                    if (joinType != null && !previous.getArguments().isEmpty()) {
                        ExpressionTree joinTargetExpression = previous.getArguments().getFirst();
                        ExpressionNode joinCondition = lowerDslJoinOnCondition(cursor.getArguments(), parsedSources);
                        if (joinCondition == null || !isBooleanJoinCondition(joinCondition)) {
                            throw LowererSupport.unsupportedFeature("Could not lower join on(...) predicate into SQL condition", cursor, parsedSources);
                        }
                        AliasedTableRef aliased = resolveDslAliasedTable(joinTargetExpression, parsedSources);
                        if (aliased != null) {
                            joins.add(new JoinSpec(joinType, aliased.tableName(), aliased.alias(), joinCondition));
                        } else {
                            joins.add(new JoinSpec(joinType, qualifiedTableName(joinTargetExpression, parsedSources), joinCondition));
                        }
                        joinValidations.add(new JoinValidationSpec(joinTargetExpression, cursor.getArguments().getFirst()));
                    }
                }
                case CROSS_JOIN -> {
                    ExpressionTree joinTargetExpression = cursor.getArguments().getFirst();
                    AliasedTableRef aliased = resolveDslAliasedTable(joinTargetExpression, parsedSources);
                    if (aliased != null) {
                        joins.add(new JoinSpec(JoinType.CROSS, aliased.tableName(), aliased.alias(), null));
                    } else {
                        joins.add(new JoinSpec(JoinType.CROSS, qualifiedTableName(joinTargetExpression, parsedSources), null));
                    }
                    joinValidations.add(new JoinValidationSpec(joinTargetExpression, null));
                }
                case LATERAL_AS -> joins.add(new JoinSpec(
                        JoinType.LATERAL,
                        lowerDslLateralSubquery(previous.getArguments().getFirst(), cursor.getArguments().getFirst(), parsedSources),
                        null));
                case FOR_UPDATE -> {
                    forUpdate = true;
                    forShare = false;
                }
                case FOR_SHARE -> {
                    forShare = true;
                    forUpdate = false;
                }
                case SKIP_LOCKED -> skipLocked = true;
                case NO_WAIT -> noWait = true;
                default -> {
                    return false;
                }
            }
            return true;
        }

        void validateColumnReferences(MethodInvocationTree selectInvocation, boolean allowOuterReferences, ParsedSources parsedSources) {
            DslQueryLowerer.validateColumnReferences(
                    selectInvocation == null ? List.of() : selectInvocation.getArguments(),
                    fromExpression,
                    joinValidations,
                    whereExpression,
                    groupByExpressions,
                    havingExpression,
                    orderByExpressions,
                    allowOuterReferences,
                    parsedSources);
        }

        LockingClause lockingClause(MethodInvocationTree invocation, ParsedSources parsedSources) {
            if ((skipLocked || noWait) && !forUpdate && !forShare) {
                throw LowererSupport.unsupportedFeature("skipLocked()/noWait() require forUpdate() or forShare() in lowered DSL chains", invocation, parsedSources);
            }
            if (skipLocked && noWait) {
                throw LowererSupport.unsupportedFeature("skipLocked() and noWait() cannot be combined in the same lowered DSL chain", invocation, parsedSources);
            }
            return (forUpdate || forShare || skipLocked || noWait)
                    ? new LockingClause(forUpdate, forShare, skipLocked, noWait)
                    : null;
        }

        List<JoinSpec> orderedJoins() {
            return joins.isEmpty() ? List.of() : joins.reversed();
        }

        List<CteSpec> resolveCtes(boolean withRoot, MethodInvocationTree root, TreePath invocationPath, ParsedSources parsedSources) {
            List<CteSpec> explicitCtes = withRoot
                    ? lowerDslCtes(root, invocationPath, parsedSources)
                    : List.of();
            List<CteSpec> implicitCtes = lowerImplicitDslCtes(fromExpression, joins, invocationPath, parsedSources);
            return mergeCtes(explicitCtes, implicitCtes);
        }
    }

    private static List<SelectColumn> lowerDslSelectFromColumns(
            ExpressionTree tableExpression,
            String from,
            ParsedSources parsedSources
    ) {
        if (parsedSources == null) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) lowering currently requires source inspection",
                    tableExpression,
                    null,
                    "Try rewriting as select(table.COL1, ...).from(table) for now");
        }
        if (tableExpression instanceof MemberSelectTree) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) on qualified/generated table references is not lowered yet",
                    tableExpression,
                    parsedSources,
                    "For now, either bind the table to a local variable of its concrete type first (for example, OwnersTable owners = CatalogTables.OWNERS; selectFrom(owners)...) or rewrite selectFrom(Foo.BAR) as select(Foo.BAR.COL1, ...).from(Foo.BAR)");
        }
        if (tableExpression instanceof MethodInvocationTree) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) does not inspect helper method calls yet",
                    tableExpression,
                    parsedSources,
                    "Bind the returned table to a local variable of its concrete table type first, or rewrite the query as select(table.COL1, ...).from(table) for now");
        }
        if (!(tableExpression instanceof IdentifierTree identifierTree)) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) lowering currently requires a source-visible identifier-backed DSL table",
                    tableExpression,
                    parsedSources,
                    "Bind the table to a local variable first, or rewrite as select(table.COL1, ...).from(table) for now");
        }
        TreePath path = LowererSupport.resolveTreePath(parsedSources, identifierTree);
        if (path == null) {
            throw LowererSupport.unsupportedFeature("Could not resolve selectFrom(...) table symbol", identifierTree, parsedSources);
        }
        Element element = parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variableElement)
                || !(variableElement.asType() instanceof javax.lang.model.type.DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            throw LowererSupport.unsupportedFeature("Could not resolve selectFrom(...) table type", identifierTree, parsedSources);
        }
        boolean genericDslTableAlias = isGenericDslTableAlias(typeElement);
        if (genericDslTableAlias) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) could not inspect columns from a variable declared as generic Table/TableLike",
                    identifierTree,
                    parsedSources,
                    "Bind the table to a local variable of its concrete generated type (or use var) before calling selectFrom(...), or rewrite the query as select(table.COL1, ...).from(table) for now");
        }
        Tree declaration = parsedSources.trees().getTree(typeElement);
        if (!(declaration instanceof ClassTree classTree)) {
            throw LowererSupport.unsupportedFeature("Could not inspect selectFrom(...) table declaration", identifierTree, parsedSources);
        }
        List<SelectColumn> columns = new ArrayList<>();
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof VariableTree field)) {
                continue;
            }
            if (!field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)) {
                continue;
            }
            ExpressionTree initializer = field.getInitializer();
            if (!(initializer instanceof MethodInvocationTree initInvocation)
                    || !"column".equals(LowererSupport.invocationName(initInvocation))
                    || initInvocation.getArguments().isEmpty()) {
                continue;
            }
            String columnName = asStringLiteral(initInvocation.getArguments().getFirst(), "selectFrom column name", parsedSources);
            columns.add(new SelectColumn(new ColumnRefExpression(from, columnName), columnName));
        }
        if (columns.isEmpty()) {
            throw LowererSupport.unsupportedFeature(
                    "selectFrom(...) could not discover any public Column fields on the table declaration",
                    tableExpression,
                    parsedSources,
                    "Expose public Column constants on the table type, or rewrite the query as select(table.COL1, ...).from(table) for now");
        }
        return List.copyOf(columns);
    }

    static boolean isGenericDslTableAlias(TypeElement typeElement) {
        if (typeElement == null) {
            return false;
        }
        String qualifiedName = typeElement.getQualifiedName().toString();
        return "titan.dsl.Table".equals(qualifiedName) || "titan.dsl.TableLike".equals(qualifiedName);
    }

    private static boolean isDslFetchExistsTerminal(MethodInvocationTree invocation) {
        if (invocation == null) {
            return false;
        }
        String name = LowererSupport.invocationName(invocation);
        // fetchExistsValue() (Phase A4 / G2, read-into-local) shares fetchExists()'s EXISTS-wrapper
        // shaping; it differs only in that its boolean result is bound to a local via SELECT INTO.
        return "fetchExists".equals(name) || "fetchExistsValue".equals(name);
    }

    private static List<CteSpec> lowerDslCtes(
            MethodInvocationTree withInvocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        String rootName = LowererSupport.invocationName(withInvocation);
        if (!"with".equals(rootName) && !"withRecursive".equals(rootName)) {
            return List.of();
        }
        boolean recursive = "withRecursive".equals(rootName);
        List<CteSpec> ctes = new ArrayList<>();
        int inlineViewSequence = 1;
        for (ExpressionTree expression : withInvocation.getArguments()) {
            ctes.add(lowerDslCte(expression, recursive, invocationPath, parsedSources, inlineViewSequence));
            inlineViewSequence++;
        }
        return List.copyOf(ctes);
    }

    private static CteSpec lowerDslCte(
            ExpressionTree cteExpression,
            boolean recursive,
            TreePath invocationPath,
            ParsedSources parsedSources,
            int inlineViewSequence
    ) {
        InlineViewSource inlineViewSource = resolveInlineViewSource(cteExpression, parsedSources);
        if (inlineViewSource != null) {
            SelectSql cteQuery = lowerDslSelectBuilderInvocation(inlineViewSource.selectInvocation(), invocationPath, parsedSources);
            String inlineName = inlineViewSource.name() == null || inlineViewSource.name().isBlank()
                    ? "inline_view_" + inlineViewSequence
                    : inlineViewSource.name();
            return new CteSpec(inlineName, cteQuery, recursive);
        }

        MethodInvocationTree cteDefinitionInvocation = namedCteDefinitionInvocation(cteExpression, parsedSources);
        if (cteDefinitionInvocation == null) {
            if (cteExpression instanceof MethodInvocationTree) {
                throw LowererSupport.unsupportedFeature(
                        "with(...) does not inspect helper method calls yet",
                        cteExpression,
                        parsedSources,
                        "Bind the CTE/inline view to a local variable defined via DSL.name(...).as(select(...)) or defineInlineView(select(...)), or inline that definition directly in with(...), for now");
            }
            throw LowererSupport.unsupportedFeature(
                    "with(...) arguments must use DSL.name(...).as(select(...)) or defineInlineView(select(...)) shape",
                    cteExpression,
                    parsedSources);
        }
        if (!(cteDefinitionInvocation.getMethodSelect() instanceof MemberSelectTree asSelect)
                || !(asSelect.getExpression() instanceof MethodInvocationTree nameInvocation)) {
            throw LowererSupport.unsupportedFeature(
                    "with(...) arguments must use DSL.name(...).as(select(...)) or defineInlineView(select(...)) shape",
                    cteDefinitionInvocation,
                    parsedSources);
        }

        String asMethod = asSelect.getIdentifier().toString();
        if ("asRecursive".equals(asMethod)) {
            throw LowererSupport.unsupportedFeature(
                    "withRecursive(...) lowering does not support DSL.name(...).asRecursive(self -> ...) inside transpiled entry points yet",
                    cteDefinitionInvocation,
                    parsedSources,
                    "For now, use DSL.name(...).as(select(...)) for non-recursive CTEs, or move the recursive CTE body to @SQL/raw SQL until Titan lowers this builder path");
        }
        if ("asSql".equals(asMethod)) {
            throw LowererSupport.unsupportedFeature(
                    "with(...) lowering does not support DSL.name(...).asSql(\"...\") inside transpiled entry points yet",
                    cteDefinitionInvocation,
                    parsedSources,
                    "Rewrite the CTE body as DSL.name(...).as(select(...)) / defineInlineView(select(...)), or keep the query in @SQL/raw SQL for now");
        }
        if (!"as".equals(asMethod)) {
            throw LowererSupport.unsupportedFeature("with(...) lowering only supports CTE definitions via as(select(...))", cteDefinitionInvocation, parsedSources);
        }

        if (nameInvocation.getArguments().isEmpty()) {
            throw LowererSupport.unsupportedFeature("CTE name must be a non-empty string literal", nameInvocation, parsedSources);
        }
        String cteName = asStringLiteral(nameInvocation.getArguments().getFirst(), "cte name", parsedSources);

        if (cteDefinitionInvocation.getArguments().isEmpty() || !(cteDefinitionInvocation.getArguments().getFirst() instanceof MethodInvocationTree cteSelect)) {
            throw LowererSupport.unsupportedFeature("CTE as(...) requires a SelectBuilder invocation argument", cteDefinitionInvocation, parsedSources);
        }

        SelectSql cteQuery = lowerDslSelectBuilderInvocation(cteSelect, invocationPath, parsedSources);
        return new CteSpec(cteName, cteQuery, recursive);
    }

    private record InlineViewSource(String name, MethodInvocationTree selectInvocation) {
    }

    private static InlineViewSource resolveInlineViewSource(ExpressionTree expression, ParsedSources parsedSources) {
        MethodInvocationTree inlineInvocation = inlineViewInvocation(expression, parsedSources);
        if (inlineInvocation == null || inlineInvocation.getArguments().isEmpty()) {
            return null;
        }
        ExpressionTree firstArgument = inlineInvocation.getArguments().getFirst();
        if (!(firstArgument instanceof MethodInvocationTree selectInvocation)) {
            throw LowererSupport.unsupportedFeature("defineInlineView(...) requires a SelectBuilder invocation argument", firstArgument, parsedSources);
        }

        String name = null;
        if (expression instanceof IdentifierTree identifierTree) {
            name = ExpressionLowerer.toSnakeCase(identifierTree.getName().toString());
        }
        return new InlineViewSource(name, selectInvocation);
    }

    private static MethodInvocationTree inlineViewInvocation(ExpressionTree expression, ParsedSources parsedSources) {
        if (expression instanceof MethodInvocationTree invocation && "defineInlineView".equals(LowererSupport.invocationName(invocation))) {
            return invocation;
        }

        ExpressionTree initializer = variableInitializer(expression, parsedSources);
        if (initializer instanceof MethodInvocationTree invocation && "defineInlineView".equals(LowererSupport.invocationName(invocation))) {
            return invocation;
        }
        return null;
    }

    private static MethodInvocationTree namedCteDefinitionInvocation(ExpressionTree expression, ParsedSources parsedSources) {
        if (expression instanceof MethodInvocationTree invocation && isNamedCteDefinitionMethod(invocation)) {
            return invocation;
        }

        ExpressionTree initializer = variableInitializer(expression, parsedSources);
        if (initializer instanceof MethodInvocationTree invocation && isNamedCteDefinitionMethod(invocation)) {
            return invocation;
        }
        return null;
    }

    private static boolean isNamedCteDefinitionMethod(MethodInvocationTree invocation) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree memberSelect)) {
            return false;
        }
        String methodName = memberSelect.getIdentifier().toString();
        return "as".equals(methodName) || "asRecursive".equals(methodName) || "asSql".equals(methodName);
    }

    private static ExpressionTree variableInitializer(ExpressionTree expression, ParsedSources parsedSources) {
        TreePath expressionPath = LowererSupport.resolveTreePath(parsedSources, expression);
        if (expressionPath == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(expressionPath);
        if (!(element instanceof VariableElement variableElement)) {
            return null;
        }
        Tree declarationTree = parsedSources.trees().getTree(variableElement);
        if (!(declarationTree instanceof VariableTree variableTree)) {
            return null;
        }
        return variableTree.getInitializer();
    }

    static boolean isDslScaffoldingDeclaration(VariableTree variableTree, ParsedSources parsedSources) {
        ExpressionTree initializer = variableTree.getInitializer();
        if (initializer == null || parsedSources == null) {
            return false;
        }
        if (initializer instanceof MethodInvocationTree invocation) {
            if ("defineInlineView".equals(LowererSupport.invocationName(invocation))) {
                return true;
            }
            if (namedCteDefinitionInvocation(initializer, parsedSources) != null) {
                return true;
            }
            if (isDslScalarInvocation(invocation, parsedSources)) {
                return true;
            }
            if (invocation.getMethodSelect() instanceof MemberSelectTree fieldSelect
                    && "field".equals(fieldSelect.getIdentifier().toString())
                    && resolveDslTableSource(fieldSelect.getExpression(), parsedSources) != null) {
                return true;
            }
            // Aliased table declarations (audit D-7): AliasedTable<R> e = TABLE.as("e");
            if (resolveDslAliasedTable(initializer, parsedSources) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDslScalarInvocation(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (!"scalar".equals(LowererSupport.invocationName(invocation))) {
            return false;
        }
        TreePath invocationPath = LowererSupport.resolveTreePath(parsedSources, invocation);
        ExecutableElement method = LowererSupport.resolvedMethodElement(invocationPath, parsedSources);
        return method != null
                && "titan.dsl.DSL".contentEquals(method.getEnclosingElement().toString())
                && "scalar".contentEquals(method.getSimpleName());
    }

    private static List<CteSpec> lowerImplicitDslCtes(
            ExpressionTree fromExpression,
            List<JoinSpec> joins,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        if (parsedSources == null) {
            return List.of();
        }
        List<CteSpec> ctes = new ArrayList<>();
        if (fromExpression != null) {
            DslTableSource source = resolveDslTableSource(fromExpression, parsedSources);
            if (source != null) {
                ctes.add(new CteSpec(source.name(), lowerDslSelectBuilderInvocation(source.selectInvocation(), invocationPath, parsedSources), false));
            }
        }
        return List.copyOf(ctes);
    }

    private static List<CteSpec> mergeCtes(List<CteSpec> explicitCtes, List<CteSpec> implicitCtes) {
        java.util.LinkedHashMap<String, CteSpec> merged = new java.util.LinkedHashMap<>();
        for (CteSpec cte : explicitCtes) {
            merged.put(cte.name(), cte);
        }
        for (CteSpec cte : implicitCtes) {
            merged.putIfAbsent(cte.name(), cte);
        }
        return List.copyOf(merged.values().stream().toList());
    }

    private static DslTableSource resolveDslTableSource(ExpressionTree expression, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return null;
        }
        InlineViewSource inlineViewSource = resolveInlineViewSource(expression, parsedSources);
        if (inlineViewSource != null) {
            String inlineName = inlineViewSource.name() == null || inlineViewSource.name().isBlank()
                    ? (expression instanceof IdentifierTree identifierTree ? ExpressionLowerer.toSnakeCase(identifierTree.getName().toString()) : "inline_view")
                    : inlineViewSource.name();
            return new DslTableSource(inlineName, inlineViewSource.selectInvocation());
        }
        MethodInvocationTree cteDefinitionInvocation = namedCteDefinitionInvocation(expression, parsedSources);
        if (cteDefinitionInvocation != null
                && cteDefinitionInvocation.getMethodSelect() instanceof MemberSelectTree asSelect
                && "as".equals(asSelect.getIdentifier().toString())
                && asSelect.getExpression() instanceof MethodInvocationTree nameInvocation
                && !nameInvocation.getArguments().isEmpty()) {
            String cteName = asStringLiteral(nameInvocation.getArguments().getFirst(), "DSL.name(...)", parsedSources);
            if (!cteDefinitionInvocation.getArguments().isEmpty() && cteDefinitionInvocation.getArguments().getFirst() instanceof MethodInvocationTree selectInvocation) {
                return new DslTableSource(cteName, selectInvocation);
            }
        }
        return null;
    }

    static ColumnRefExpression resolveDslFieldReference(IdentifierTree identifierTree, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return null;
        }
        ExpressionTree initializer = variableInitializer(identifierTree, parsedSources);
        if (!(initializer instanceof MethodInvocationTree fieldInvocation)
                || !(fieldInvocation.getMethodSelect() instanceof MemberSelectTree fieldSelect)
                || !"field".equals(fieldSelect.getIdentifier().toString())
                || fieldInvocation.getArguments().isEmpty()) {
            return null;
        }
        DslTableSource source = resolveDslTableSource(fieldSelect.getExpression(), parsedSources);
        if (source == null) {
            return null;
        }
        String column = asStringLiteral(fieldInvocation.getArguments().getFirst(), "InlineView.field(...)", parsedSources);
        return new ColumnRefExpression(source.name(), column);
    }

    private record SetOpStep(String operation, SelectSql rhs, List<String> projectionTypeKeys) {}

    private record DslTableSource(String name, MethodInvocationTree selectInvocation) {}

    /** Resolved {@code TABLE.as("alias")} reference (audit D-7, self-joins). */
    private record AliasedTableRef(String tableName, String alias) {}

    /**
     * Resolves a FROM/JOIN target (or a column-reference receiver) to an aliased table: either an
     * inline {@code TABLE.as("alias")} invocation or a variable initialized with one, where the
     * receiver resolves to a DSL physical table. Returns {@code null} for unaliased targets.
     */
    private static AliasedTableRef resolveDslAliasedTable(ExpressionTree expression, ParsedSources parsedSources) {
        if (expression == null || parsedSources == null) {
            return null;
        }
        MethodInvocationTree asInvocation = null;
        if (expression instanceof MethodInvocationTree invocation
                && "as".equals(LowererSupport.invocationName(invocation))
                && invocation.getArguments().size() == 1) {
            asInvocation = invocation;
        } else {
            ExpressionTree initializer = variableInitializer(expression, parsedSources);
            if (initializer instanceof MethodInvocationTree invocation
                    && "as".equals(LowererSupport.invocationName(invocation))
                    && invocation.getArguments().size() == 1) {
                asInvocation = invocation;
            }
        }
        if (asInvocation == null || !(asInvocation.getMethodSelect() instanceof MemberSelectTree asSelect)) {
            return null;
        }
        String physicalTableName = resolveDslPhysicalTableName(asSelect.getExpression(), parsedSources);
        if (physicalTableName == null) {
            return null;
        }
        if (!(asInvocation.getArguments().getFirst() instanceof LiteralTree literal)
                || !(literal.getValue() instanceof String alias)
                || alias.isBlank()) {
            throw LowererSupport.unsupportedFeature(
                    "Table alias as(...) requires a non-blank string literal alias",
                    asInvocation,
                    parsedSources);
        }
        // ATG-020: the aliased FROM/JOIN clause is schema-qualified; columns reference the ALIAS, so the
        // schema never reaches a column qualifier.
        return new AliasedTableRef(qualifiedTableName(asSelect.getExpression(), parsedSources), alias);
    }

    /**
     * Lowers an alias-qualified column reference {@code aliasVar.col(TABLE.COLUMN)}
     * (audit D-7, self-joins) to {@code ColumnRefExpression(alias, column)}. Returns
     * {@code null} when the invocation is not that shape.
     */
    private static ColumnRefExpression lowerDslAliasedColumn(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree member)
                || !"col".equals(member.getIdentifier().toString())
                || invocation.getArguments().size() != 1) {
            return null;
        }
        AliasedTableRef aliased = resolveDslAliasedTable(member.getExpression(), parsedSources);
        if (aliased == null) {
            return null;
        }
        ExpressionTree columnArgument = invocation.getArguments().getFirst();
        if (!(columnArgument instanceof MemberSelectTree columnSelect)) {
            throw LowererSupport.unsupportedFeature(
                    "col(...) requires a direct DSL table column reference (for example, TABLE.COLUMN)",
                    columnArgument,
                    parsedSources);
        }
        return new ColumnRefExpression(aliased.alias(), resolveDslColumnName(columnSelect, parsedSources));
    }

    private record JoinValidationSpec(ExpressionTree targetExpression, ExpressionTree conditionExpression) {}

    private record ValidationColumnRef(String table, String column) {}

    private static List<String> resolveDslSelectProjectionTypeKeys(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        DslChainParser.Chain chain = DslChainParser.parse(invocation);
        MethodInvocationTree projectionInvocation = chain.steps().stream()
                .filter(step -> step.kind() == DslChainParser.StepKind.SELECT)
                .map(DslChainParser.Step::invocation)
                .findFirst()
                .orElse(null);

        MethodInvocationTree selectInvocation = "select".equals(chain.rootName()) ? chain.root() : projectionInvocation;
        if (selectInvocation == null) {
            return List.of();
        }
        return resolveProjectionTypeKeys(selectInvocation.getArguments(), invocationPath, parsedSources);
    }

    private static List<String> resolveProjectionTypeKeys(
            List<? extends ExpressionTree> projectionExpressions,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        List<String> keys = new ArrayList<>();
        for (ExpressionTree expression : projectionExpressions) {
            TypeMirror typeMirror = parsedSources.trees().getTypeMirror(new TreePath(invocationPath, expression));
            keys.add(normalizeTypeKey(typeMirror));
        }
        return List.copyOf(keys);
    }

    private static boolean areSetOperationProjectionTypesCompatible(String leftType, String rightType) {
        if (Objects.equals(leftType, rightType)) {
            return true;
        }
        if (isNumericTypeKey(leftType) && isNumericTypeKey(rightType)) {
            return true;
        }
        return false;
    }

    private static String normalizeTypeKey(TypeMirror typeMirror) {
        if (typeMirror == null) {
            return "<unknown>";
        }
        String type = typeMirror.toString().trim();
        return switch (type) {
            case "byte", "java.lang.Byte" -> "java.lang.Byte";
            case "short", "java.lang.Short" -> "java.lang.Short";
            case "int", "java.lang.Integer" -> "java.lang.Integer";
            case "long", "java.lang.Long" -> "java.lang.Long";
            case "float", "java.lang.Float" -> "java.lang.Float";
            case "double", "java.lang.Double" -> "java.lang.Double";
            case "java.math.BigInteger" -> "java.math.BigInteger";
            case "java.math.BigDecimal" -> "java.math.BigDecimal";
            default -> type;
        };
    }

    private static boolean isNumericTypeKey(String key) {
        return "java.lang.Byte".equals(key)
                || "java.lang.Short".equals(key)
                || "java.lang.Integer".equals(key)
                || "java.lang.Long".equals(key)
                || "java.lang.Float".equals(key)
                || "java.lang.Double".equals(key)
                || "java.math.BigInteger".equals(key)
                || "java.math.BigDecimal".equals(key);
    }

    private static void validateFetchIntoProjectionCoverage(
            MethodInvocationTree fetchIntoInvocation,
            TreePath invocationPath,
            ParsedSources parsedSources,
            List<SelectColumn> projectedColumns,
            List<? extends ExpressionTree> projectedExpressions
    ) {
        if (fetchIntoInvocation.getArguments().isEmpty()) {
            return;
        }

        ExpressionTree recordClassArg = fetchIntoInvocation.getArguments().getFirst();
        if (!(recordClassArg instanceof MemberSelectTree classLiteral)
                || !"class".contentEquals(classLiteral.getIdentifier())) {
            return;
        }

        TreePath classExpressionPath = new TreePath(new TreePath(invocationPath, recordClassArg), classLiteral.getExpression());
        TypeMirror recordTypeMirror = parsedSources.trees().getTypeMirror(classExpressionPath);
        if (recordTypeMirror == null || recordTypeMirror.getKind() == TypeKind.ERROR) {
            return;
        }

        Element typeElement = parsedSources.types().asElement(recordTypeMirror);
        if (!(typeElement instanceof TypeElement recordType) || recordType.getKind() != ElementKind.RECORD) {
            return;
        }

        List<? extends RecordComponentElement> recordComponents = recordType.getRecordComponents();
        for (RecordComponentElement component : recordComponents) {
            if (isNestedRowMaterializationComponent(parsedSources, component.asType())) {
                throw LowererSupport.unsupportedFeature(
                        "fetchInto(" + recordType.getSimpleName()
                                + ".class) does not yet support nested row materialization for record component '"
                                + component.getSimpleName() + "'",
                        fetchIntoInvocation,
                        parsedSources,
                        "Materialize root and relation row shapes separately until grouped child-row assembly is supported");
            }
        }
        if (projectedColumns.size() != recordComponents.size()) {
            throw LowererSupport.unsupportedFeature(
                    "fetchInto(" + recordType.getSimpleName()
                            + ".class) requires projected column count to match record component count: projected "
                            + projectedColumns.size()
                            + " columns for "
                            + recordComponents.size()
                            + " components",
                    fetchIntoInvocation,
                    parsedSources);
        }

        java.util.Map<String, TypeMirror> projectedTypesByName = new java.util.LinkedHashMap<>();
        for (int i = 0; i < projectedColumns.size() && i < projectedExpressions.size(); i++) {
            String projectionName = projectedColumnName(projectedColumns.get(i));
            String projectionDescription = projectionName;
            if (projectionDescription == null || projectionDescription.isBlank()) {
                projectionDescription = projectedExpressions.get(i).toString();
            }
            if (projectionName == null || projectionName.isBlank() || !isIdentifierShaped(projectionName)) {
                throw LowererSupport.unsupportedFeature(
                        "fetchInto(" + recordType.getSimpleName()
                                + ".class) requires identifier-shaped projected column names, but found '"
                                + projectionDescription + "'",
                        fetchIntoInvocation,
                        parsedSources);
            }
            TreePath projectionPath = TreePath.getPath(invocationPath.getCompilationUnit(), projectedExpressions.get(i));
            TypeMirror projectionType = projectionPath == null
                    ? null
                    : parsedSources.trees().getTypeMirror(projectionPath);
            String normalizedProjection = normalizeIdentifier(projectionName);
            if (projectedTypesByName.containsKey(normalizedProjection)) {
                throw LowererSupport.unsupportedFeature(
                        "fetchInto(" + recordType.getSimpleName()
                                + ".class) has ambiguous projected columns (multiple projected columns normalize to the same identifier '"
                                + normalizedProjection + "')",
                        fetchIntoInvocation,
                        parsedSources);
            }
            projectedTypesByName.put(normalizedProjection, unwrapColumnTypeArgument(parsedSources, projectionType));
        }

        for (Element component : recordComponents) {
            String normalizedComponent = normalizeIdentifier(component.getSimpleName().toString());
            TypeMirror projectedType = projectedTypesByName.get(normalizedComponent);
            if (projectedType == null) {
                throw LowererSupport.unsupportedFeature(
                        "fetchInto(" + recordType.getSimpleName()
                                + ".class) requires projected column for record component '"
                                + component.getSimpleName() + "'",
                        fetchIntoInvocation,
                        parsedSources);
            }

            if (projectedType.getKind() != TypeKind.ERROR
                    && !isFetchIntoTypeCompatible(parsedSources, component.asType(), projectedType)) {
                throw LowererSupport.unsupportedFeature(
                        "fetchInto(" + recordType.getSimpleName()
                                + ".class) type mismatch for record component '"
                                + component.getSimpleName() + "'",
                        fetchIntoInvocation,
                        parsedSources);
            }
        }
    }

    private static boolean isNestedRowMaterializationComponent(ParsedSources parsedSources, TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return false;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return true;
        }
        Element componentElement = parsedSources.types().asElement(type);
        return componentElement instanceof TypeElement typeElement
                && typeElement.getKind() == ElementKind.RECORD;
    }

    private static void validateForEachCallbackArity(
            MethodInvocationTree forEachInvocation,
            TreePath invocationPath,
            ParsedSources parsedSources,
            int projectedColumnCount
    ) {
        if (forEachInvocation.getArguments().isEmpty()) {
            return;
        }

        ExpressionTree callback = forEachInvocation.getArguments().getFirst();
        Integer callbackArity = callback instanceof LambdaExpressionTree lambda
                ? lambda.getParameters().size()
                : null;

        if (callbackArity == null) {
            TreePath forEachPath = LowererSupport.resolveTreePath(parsedSources, forEachInvocation);
            if (forEachPath == null) {
                forEachPath = invocationPath;
            }

            ExecutableElement method = LowererSupport.resolvedMethodElement(forEachPath, parsedSources);
            if (method == null || method.getParameters().size() != 1) {
                return;
            }

            String erasedParameterType = parsedSources.types().erasure(method.getParameters().getFirst().asType()).toString();
            callbackArity = switch (erasedParameterType) {
                case "java.util.function.BiConsumer" -> 2;
                case "titan.dsl.TriConsumer" -> 3;
                case "titan.dsl.QuadConsumer" -> 4;
                case "titan.dsl.QuintConsumer" -> 5;
                case "titan.dsl.HexConsumer" -> 6;
                case "titan.dsl.HeptConsumer" -> 7;
                case "titan.dsl.OctConsumer" -> 8;
                case "titan.dsl.EnneaConsumer" -> 9;
                case "titan.dsl.DecaConsumer" -> 10;
                default -> null;
            };
        }

        if (callbackArity != null && projectedColumnCount != callbackArity) {
            throw LowererSupport.unsupportedFeature(
                    "forEach(...) requires projected column count to match callback arity: projected "
                            + projectedColumnCount
                            + " columns for callback arity "
                            + callbackArity,
                    forEachInvocation,
                    parsedSources);
        }
    }

    private static String projectedColumnName(SelectColumn selectColumn) {
        if (selectColumn.alias() != null && !selectColumn.alias().isBlank()) {
            return selectColumn.alias();
        }
        if (selectColumn.expression() instanceof ColumnRefExpression columnRefExpression) {
            return columnRefExpression.column();
        }
        return null;
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier == null
                ? ""
                : identifier.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isIdentifierShaped(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        if (!Character.isLetter(identifier.charAt(0)) && identifier.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    static String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "resource";
        }
        return identifier.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static TypeMirror unwrapColumnTypeArgument(ParsedSources parsedSources, TypeMirror projectedType) {
        if (!(projectedType instanceof DeclaredType declaredType)) {
            return projectedType;
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement typeElement)) {
            return projectedType;
        }
        if (!"titan.dsl.Column".contentEquals(typeElement.getQualifiedName())) {
            return projectedType;
        }
        if (declaredType.getTypeArguments().isEmpty()) {
            return projectedType;
        }
        return declaredType.getTypeArguments().getFirst();
    }

    private static boolean isFetchIntoTypeCompatible(
            ParsedSources parsedSources,
            TypeMirror targetType,
            TypeMirror projectedType
    ) {
        TypeMirror target = boxIfPrimitive(parsedSources, targetType);
        TypeMirror projected = boxIfPrimitive(parsedSources, projectedType);
        return parsedSources.types().isAssignable(projected, target)
                || parsedSources.types().isAssignable(target, projected);
    }

    private static TypeMirror boxIfPrimitive(ParsedSources parsedSources, TypeMirror mirror) {
        if (mirror == null || !mirror.getKind().isPrimitive()) {
            return mirror;
        }
        TypeElement boxed = parsedSources.types().boxedClass((PrimitiveType) mirror);
        return boxed == null ? mirror : boxed.asType();
    }

    private static ExpressionNode lowerDslJoinOnCondition(List<? extends ExpressionTree> arguments, ParsedSources parsedSources) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        if (arguments.size() == 1) {
            return lowerDslCondition(arguments.getFirst(), parsedSources);
        }
        if (arguments.size() == 2 || arguments.size() == 3) {
            ExpressionNode left = lowerDslValue(arguments.get(0), parsedSources);
            ExpressionNode right = lowerDslValue(arguments.get(1), parsedSources);
            if ((left instanceof ColumnRefExpression || left instanceof VariableRefExpression)
                    && (right instanceof ColumnRefExpression || right instanceof VariableRefExpression)) {
                ExpressionNode equality = new BinaryOpExpression(left, BinaryOperator.EQUAL, right);
                if (arguments.size() == 2) {
                    return equality;
                }
                ExpressionNode extra = lowerDslCondition(arguments.get(2), parsedSources);
                if (extra != null && isBooleanJoinCondition(extra)) {
                    return new BinaryOpExpression(equality, BinaryOperator.AND, extra);
                }
            }
        }
        return null;
    }

    private static boolean isBooleanJoinCondition(ExpressionNode expression) {
        if (expression instanceof BinaryOpExpression binary) {
            return switch (binary.operator()) {
                case EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, AND, OR -> true;
                default -> false;
            };
        }
        if (expression instanceof IsNullExpression || expression instanceof IsNotNullExpression) {
            return true;
        }
        return expression instanceof LiteralExpression literal && literal.value() instanceof Boolean;
    }

    /**
     * Defense-in-depth re-check of the dynamic-SQL safety rule (plan 2.5, audit D14).
     * {@link io.titan.transpiler.FeatureValidator} enforces the same {@link RawSqlConstantRule}
     * before lowering starts; this guard keeps direct lowerer invocations (and any future
     * pipeline reordering) safe.
     */
    static void rejectUnsafeDynamicSqlConcatenation(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        String invocationName = LowererSupport.invocationName(invocation);
        if (!RawSqlConstantRule.isRawSqlInvocationName(invocationName)) {
            return;
        }
        if (invocation.getArguments().isEmpty()) {
            return;
        }

        ExpressionTree sqlExpression = invocation.getArguments().getFirst();
        CompilationUnitTree unit = invocationPath == null ? null : invocationPath.getCompilationUnit();
        if (!RawSqlConstantRule.isCompileTimeConstantSqlText(sqlExpression, unit, parsedSources)) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E004,
                    RawSqlConstantRule.violationMessage(invocationName),
                    sqlExpression,
                    parsedSources,
                    RawSqlConstantRule.bindParameterSuggestion());
        }
    }

    private static List<OrderBySpec> lowerDslOrderBy(List<? extends ExpressionTree> arguments, ParsedSources parsedSources) {
        List<OrderBySpec> specs = new ArrayList<>();
        for (ExpressionTree argument : arguments) {
            specs.add(lowerDslOrderByArgument(argument, parsedSources));
        }
        return specs;
    }

    private static OrderBySpec lowerDslOrderByArgument(ExpressionTree argument, ParsedSources parsedSources) {
        if (argument instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree member
                && invocation.getArguments().isEmpty()) {
            String method = member.getIdentifier().toString();
            if ("asc".equals(method)) {
                return new OrderBySpec(lowerDslValue(member.getExpression(), parsedSources), SortDirection.ASC);
            }
            if ("desc".equals(method)) {
                return new OrderBySpec(lowerDslValue(member.getExpression(), parsedSources), SortDirection.DESC);
            }
            // NULLS FIRST/LAST on a sort field (audit D-7): .asc().nullsFirst() etc.
            if ("nullsFirst".equals(method) || "nullsLast".equals(method)) {
                OrderBySpec inner = lowerDslOrderByArgument(member.getExpression(), parsedSources);
                return new OrderBySpec(
                        inner.expression(),
                        inner.direction(),
                        "nullsFirst".equals(method) ? NullsOrder.FIRST : NullsOrder.LAST);
            }
        }
        return new OrderBySpec(lowerDslValue(argument, parsedSources), SortDirection.ASC);
    }

    private static void validateColumnReferences(
            List<? extends ExpressionTree> projectedExpressions,
            ExpressionTree fromExpression,
            List<JoinValidationSpec> joins,
            ExpressionTree whereExpression,
            List<? extends ExpressionTree> groupByExpressions,
            ExpressionTree havingExpression,
            List<? extends ExpressionTree> orderByExpressions,
            boolean allowOuterReferences,
            ParsedSources parsedSources
    ) {
        String from = resolveValidationSourceIdentity(fromExpression, parsedSources);
        if (from == null || from.isBlank()) {
            return;
        }

        java.util.Set<String> validSources = new java.util.LinkedHashSet<>();
        validSources.add(from);
        if (joins != null) {
            for (JoinValidationSpec join : joins) {
                String joinTarget = join == null ? null : resolveValidationSourceIdentity(join.targetExpression(), parsedSources);
                if (joinTarget != null && !joinTarget.isBlank()) {
                    validSources.add(joinTarget);
                }
            }
        }

        List<ValidationColumnRef> referencedColumns = new ArrayList<>();
        for (ExpressionTree expression : projectedExpressions) {
            collectReferencedColumnRefs(expression, parsedSources, referencedColumns);
        }
        if (whereExpression != null) {
            collectReferencedColumnRefs(whereExpression, parsedSources, referencedColumns);
        }
        if (groupByExpressions != null) {
            for (ExpressionTree expression : groupByExpressions) {
                if (expression != null) {
                    collectReferencedColumnRefs(expression, parsedSources, referencedColumns);
                }
            }
        }
        if (havingExpression != null) {
            collectReferencedColumnRefs(havingExpression, parsedSources, referencedColumns);
        }
        if (orderByExpressions != null) {
            for (ExpressionTree expression : orderByExpressions) {
                if (expression != null) {
                    collectReferencedColumnRefs(expression, parsedSources, referencedColumns);
                }
            }
        }
        if (joins != null) {
            for (JoinValidationSpec join : joins) {
                if (join != null && join.conditionExpression() != null) {
                    collectReferencedColumnRefs(join.conditionExpression(), parsedSources, referencedColumns);
                }
            }
        }

        for (ValidationColumnRef ref : referencedColumns) {
            if (ref.table() == null || ref.table().isBlank() || "*".equals(ref.column())) {
                continue;
            }
            if (!validSources.contains(ref.table())) {
                if (allowOuterReferences) {
                    continue;
                }
                throw LowererSupport.unsupportedFeature(
                        "Could not validate column reference '"
                                + ref.table() + "." + ref.column()
                                + "' against SELECT sources " + validSources,
                        fromExpression,
                        parsedSources);
            }
        }
    }

    private static void collectReferencedColumnRefs(
            ExpressionTree expression,
            ParsedSources parsedSources,
            List<ValidationColumnRef> out
    ) {
        if (expression == null || parsedSources == null) {
            return;
        }
        new TreeScanner<Void, List<ValidationColumnRef>>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, List<ValidationColumnRef> refs) {
                String method = LowererSupport.invocationName(node);
                boolean subqueryWrapper = List.of("exists", "notExists", "scalar").contains(method)
                        && !node.getArguments().isEmpty()
                        && node.getArguments().getFirst() instanceof MethodInvocationTree;
                if (subqueryWrapper) {
                    return null;
                }
                // Alias-qualified column reference (audit D-7): record the alias as the source
                // and do not descend — the inner TABLE.COLUMN select refers to the base table,
                // which is intentionally absent from the alias-joined source set.
                ColumnRefExpression aliasedColumn = lowerDslAliasedColumn(node, parsedSources);
                if (aliasedColumn != null) {
                    refs.add(new ValidationColumnRef(aliasedColumn.table(), aliasedColumn.column()));
                    return null;
                }
                return super.visitMethodInvocation(node, refs);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, List<ValidationColumnRef> refs) {
                String table = resolveValidationSourceIdentity(node.getExpression(), parsedSources);
                if (table != null && !table.isBlank()) {
                    refs.add(new ValidationColumnRef(table, node.getIdentifier().toString()));
                }
                return super.visitMemberSelect(node, refs);
            }
        }.scan(expression, out);
    }

    private static String resolveValidationSourceIdentity(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree == null || parsedSources == null) {
            return null;
        }
        AliasedTableRef aliased = resolveDslAliasedTable(expressionTree, parsedSources);
        if (aliased != null) {
            return aliased.alias();
        }
        DslTableSource dslTableSource = resolveDslTableSource(expressionTree, parsedSources);
        if (dslTableSource != null) {
            return dslTableSource.name();
        }
        if (resolveDslPhysicalTableName(expressionTree, parsedSources) != null) {
            return expressionTree.toString();
        }
        return null;
    }

    private static SelectColumn toSelectColumn(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree member
                && "as".contentEquals(member.getIdentifier())
                && invocation.getArguments().size() == 1) {
            return new SelectColumn(
                    lowerDslValue(member.getExpression(), parsedSources),
                    asStringLiteral(invocation.getArguments().getFirst(), "projection alias", parsedSources));
        }
        if (expressionTree instanceof MemberSelectTree member
                && isDslColumnReference(member.getExpression(), parsedSources)) {
            return new SelectColumn(new ColumnRefExpression(
                    tableName(member.getExpression(), parsedSources),
                    resolveDslColumnName(member, parsedSources)), null);
        }
        return new SelectColumn(lowerDslValue(expressionTree, parsedSources), null);
    }

    private static ExpressionNode lowerDslCondition(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree member) {
            String method = member.getIdentifier().toString();
            if (("eq".equals(method) || "eqColumn".equals(method)) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.EQUAL,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("ne".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.NOT_EQUAL,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("lt".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.LESS_THAN,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("le".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.LESS_THAN_OR_EQUAL,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("gt".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.GREATER_THAN,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("ge".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.GREATER_THAN_OR_EQUAL,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("like".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslValue(member.getExpression(), parsedSources),
                        BinaryOperator.LIKE,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("and".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslCondition(member.getExpression(), parsedSources),
                        BinaryOperator.AND,
                        lowerDslCondition(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("or".equals(method) && invocation.getArguments().size() == 1) {
                return new BinaryOpExpression(
                        lowerDslCondition(member.getExpression(), parsedSources),
                        BinaryOperator.OR,
                        lowerDslCondition(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("isNull".equals(method) && invocation.getArguments().isEmpty()) {
                return new IsNullExpression(lowerDslValue(member.getExpression(), parsedSources));
            }
            if ("isNotNull".equals(method) && invocation.getArguments().isEmpty()) {
                return new IsNotNullExpression(lowerDslValue(member.getExpression(), parsedSources));
            }
            if ("not".equals(method) && invocation.getArguments().isEmpty()) {
                return new NotExpression(lowerDslCondition(member.getExpression(), parsedSources));
            }
            if ("in".equals(method) || "notIn".equals(method)) {
                return lowerDslInCondition(invocation, member, "notIn".equals(method), parsedSources);
            }
        }
        return lowerDslValue(expressionTree, parsedSources);
    }

    /**
     * Lowers {@code column.in(...)}/{@code column.notIn(...)} (audit D-7). Value lists lower to
     * {@link InListExpression} items; a single select-chain argument lowers to the subquery form.
     * Empty argument lists keep the DSL's constant semantics: {@code in()} matches nothing,
     * {@code notIn()} matches everything.
     */
    private static ExpressionNode lowerDslInCondition(
            MethodInvocationTree invocation,
            MemberSelectTree member,
            boolean negated,
            ParsedSources parsedSources
    ) {
        ExpressionNode value = lowerDslValue(member.getExpression(), parsedSources);
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        if (arguments.isEmpty()) {
            return new LiteralExpression(negated, new TBooleanType());
        }
        if (arguments.size() == 1
                && arguments.getFirst() instanceof MethodInvocationTree subqueryInvocation
                && isDslSelectChain(subqueryInvocation)) {
            SelectSql subquery = lowerDslSubquery(subqueryInvocation, parsedSources);
            if (subquery == null) {
                throw LowererSupport.unsupportedFeature(
                        (negated ? "notIn" : "in") + "(...) subquery argument could not be lowered",
                        subqueryInvocation,
                        parsedSources);
            }
            return new InListExpression(value, subquery, negated);
        }
        List<ExpressionNode> items = arguments.stream()
                .map(argument -> lowerDslValue(argument, parsedSources))
                .toList();
        return new InListExpression(value, items, negated);
    }

    /** True when the invocation chain is rooted at a DSL select entry (select/selectFrom/with). */
    private static boolean isDslSelectChain(MethodInvocationTree invocation) {
        DslChainParser.Chain chain = DslChainParser.parse(invocation);
        String rootName = chain.rootName();
        return "select".equals(rootName)
                || "selectFrom".equals(rootName)
                || "with".equals(rootName)
                || "withRecursive".equals(rootName);
    }

    private static ExpressionNode lowerDslGroupByExpression(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MethodInvocationTree invocation) {
            String name = LowererSupport.invocationName(invocation);
            if ("rollup".equals(name) || "cube".equals(name)) {
                return new GroupingSetSpec(
                        "rollup".equals(name) ? GroupingSetKind.ROLLUP : GroupingSetKind.CUBE,
                        List.of(lowerDslExpressionList(invocation.getArguments(), parsedSources)));
            }
            if ("groupingSets".equals(name)) {
                List<List<ExpressionNode>> sets = invocation.getArguments().stream()
                        .map(arg -> lowerDslGroupingSetElement(arg, parsedSources))
                        .toList();
                return new GroupingSetSpec(GroupingSetKind.GROUPING_SETS, sets);
            }
            if ("set".equals(name)) {
                return new GroupingSetSpec(
                        GroupingSetKind.SET,
                        List.of(lowerDslExpressionList(invocation.getArguments(), parsedSources)));
            }
        }
        return lowerDslValue(expressionTree, parsedSources);
    }

    private static List<ExpressionNode> lowerDslGroupingSetElement(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MethodInvocationTree invocation && "set".equals(LowererSupport.invocationName(invocation))) {
            return lowerDslExpressionList(invocation.getArguments(), parsedSources);
        }
        return List.of(lowerDslValue(expressionTree, parsedSources));
    }

    private static List<ExpressionNode> lowerDslExpressionList(List<? extends ExpressionTree> arguments, ParsedSources parsedSources) {
        return arguments.stream()
                .map(arg -> lowerDslValue(arg, parsedSources))
                .toList();
    }

    private static ExpressionNode lowerDslValue(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof LiteralTree literal) {
            return ExpressionLowerer.lowerLiteral(literal);
        }
        if (expressionTree instanceof IdentifierTree id) {
            // G5 (spike B5): a static final compile-time constant in a DSL value position
            // (set/where/insert) inlines to its literal rather than routing through the
            // __titan_static_get runtime-state machinery, so referencing a named command/status
            // constant pulls in no titan_runtime deploy dependency. Mutable static fields
            // (getConstantValue() == null) still fall through to static_get below.
            ExpressionNode foldedConstant = ExpressionLowerer.foldStaticFinalCompileTimeConstant(id, parsedSources);
            if (foldedConstant != null) {
                return foldedConstant;
            }
            String staticFieldKey = ExpressionLowerer.resolveStaticFieldKey(id, parsedSources);
            if (staticFieldKey != null) {
                return new FunctionCallExpression("__titan_static_get", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType())
                ), null);
            }
            ColumnRefExpression fieldRef = resolveDslFieldReference(id, parsedSources);
            if (fieldRef != null) {
                return fieldRef;
            }
            ExpressionTree initializer = variableInitializer(id, parsedSources);
            if (initializer instanceof MethodInvocationTree invocation) {
                ExpressionNode inlined = lowerDslSpecialValueInvocation(invocation, parsedSources);
                if (inlined != null) {
                    return inlined;
                }
            }
            return new VariableRefExpression(id.getName().toString());
        }
        if (expressionTree instanceof MemberSelectTree member && isDslColumnReference(member.getExpression(), parsedSources)) {
            return new ColumnRefExpression(tableName(member.getExpression(), parsedSources), resolveDslColumnName(member, parsedSources));
        }
        if (expressionTree instanceof MethodInvocationTree invocation) {
            ColumnRefExpression aliasedColumn = lowerDslAliasedColumn(invocation, parsedSources);
            if (aliasedColumn != null) {
                return aliasedColumn;
            }
            CaseWhenExpression caseExpression = lowerDslCaseExpression(invocation, parsedSources);
            if (caseExpression != null) {
                return caseExpression;
            }
            if (invocation.getMethodSelect() instanceof MemberSelectTree member) {
                String method = member.getIdentifier().toString();
                if ("add".equals(method) && invocation.getArguments().size() == 1) {
                    return new BinaryOpExpression(
                            lowerDslValue(member.getExpression(), parsedSources),
                            BinaryOperator.ADD,
                            lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
                }
                if ("subtract".equals(method) && invocation.getArguments().size() == 1) {
                    return new BinaryOpExpression(
                            lowerDslValue(member.getExpression(), parsedSources),
                            BinaryOperator.SUBTRACT,
                            lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
                }
                if ("multiply".equals(method) && invocation.getArguments().size() == 1) {
                    return new BinaryOpExpression(
                            lowerDslValue(member.getExpression(), parsedSources),
                            BinaryOperator.MULTIPLY,
                            lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
                }
            }
            ExpressionNode special = lowerDslSpecialValueInvocation(invocation, parsedSources);
            if (special != null) {
                return special;
            }
            ExpressionNode sqlExpression = lowerDslSqlExpressionInvocation(invocation, parsedSources);
            if (sqlExpression != null) {
                return sqlExpression;
            }
        }
        // Phase 3.1 defect fix: anything that is not a DSL construct is a plain Java expression
        // (e.g. `id + 100` in set(...)/eq(...) value positions) and must lower through
        // ExpressionLowerer like every other Java expression in the pipeline. The old fallback
        // stringified the source text into a single VariableRefExpression, which the
        // RoutineSqlNameAllocator cannot remap to the allocated p_/v_ routine names — the emitted
        // SQL then referenced raw Java identifiers ('column does not exist' at runtime).
        // ExpressionLowerer rejects unsupported shapes with a positioned diagnostic instead of
        // silently emitting raw names.
        if (parsedSources == null) {
            throw LowererSupport.unsupportedFeature(
                    "DSL argument expression lowering requires source inspection: '" + expressionTree + "'",
                    expressionTree,
                    null);
        }
        return ExpressionLowerer.lowerExpression(expressionTree, parsedSources);
    }

    /**
     * Lowers a searched CASE chain {@code when(cond, v).when(cond2, v2).otherwise(elseV)}
     * (audit D-7) to {@link CaseWhenExpression}. Returns {@code null} when the invocation is not
     * an {@code otherwise(...)} terminating a {@code when(...)} chain.
     */
    private static CaseWhenExpression lowerDslCaseExpression(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (!"otherwise".equals(LowererSupport.invocationName(invocation))
                || invocation.getArguments().size() != 1
                || !(invocation.getMethodSelect() instanceof MemberSelectTree otherwiseSelect)) {
            return null;
        }
        List<MethodInvocationTree> whenInvocations = new ArrayList<>();
        ExpressionTree cursor = otherwiseSelect.getExpression();
        while (cursor instanceof MethodInvocationTree whenInvocation
                && "when".equals(LowererSupport.invocationName(whenInvocation))) {
            whenInvocations.add(whenInvocation);
            cursor = whenInvocation.getMethodSelect() instanceof MemberSelectTree whenSelect
                    && whenSelect.getExpression() instanceof MethodInvocationTree previous
                    ? previous
                    : null;
        }
        if (whenInvocations.isEmpty()) {
            return null;
        }
        List<CaseBranch> branches = new ArrayList<>();
        for (int i = whenInvocations.size() - 1; i >= 0; i--) {
            MethodInvocationTree whenInvocation = whenInvocations.get(i);
            if (whenInvocation.getArguments().size() != 2) {
                throw LowererSupport.unsupportedFeature(
                        "when(...) requires exactly (condition, result) arguments in lowered CASE chains",
                        whenInvocation,
                        parsedSources);
            }
            ExpressionNode condition = lowerDslCondition(whenInvocation.getArguments().get(0), parsedSources);
            ExpressionNode result = lowerDslValue(whenInvocation.getArguments().get(1), parsedSources);
            branches.add(new CaseBranch(condition, result));
        }
        ExpressionNode elseValue = lowerDslValue(invocation.getArguments().getFirst(), parsedSources);
        return new CaseWhenExpression(List.copyOf(branches), elseValue);
    }

    private static ExpressionNode lowerDslSpecialValueInvocation(MethodInvocationTree invocation, ParsedSources parsedSources) {
        String method = LowererSupport.invocationName(invocation);
        if ("scalar".equals(method)
                && !invocation.getArguments().isEmpty()
                && invocation.getArguments().getFirst() instanceof MethodInvocationTree subqueryInvocation) {
            SelectSql subquery = lowerDslSubquery(subqueryInvocation, parsedSources);
            if (subquery != null) {
                return new SubqueryExpression(subquery);
            }
        }
        return null;
    }

    private static SelectSql lowerDslSubquery(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return null;
        }
        TreePath invocationPath = LowererSupport.resolveTreePath(parsedSources, invocation);
        if (invocationPath == null) {
            return null;
        }
        return lowerDslSelectBuilderInvocation(invocation, invocationPath, parsedSources, true);
    }

    /**
     * Lowers DSL invocations whose value is a SQL construct: {@code count()}, window functions
     * ({@code fn(...).over(spec)}), and {@code exists(...)}/{@code notExists(...)} subquery
     * predicates. Returns {@code null} when the invocation is not one of these shapes. SQL text is
     * never rendered here; dialect rendering happens exclusively in the emitters (plan 1.4b,
     * audit F-8/A1/A2).
     */
    private static ExpressionNode lowerDslSqlExpressionInvocation(MethodInvocationTree invocation, ParsedSources parsedSources) {
        String name = LowererSupport.invocationName(invocation);
        if ("count".equals(name) && invocation.getArguments().isEmpty()) {
            return new VariableRefExpression("COUNT(*)");
        }
        // DSL aggregates (titan.dsl.DSL#sum/avg/min/max) lower to real aggregate calls. These
        // used to ride the raw-source-text fallback, which only produced working SQL when the
        // Java field names happened to case-fold to the physical SQL names.
        if (List.of("sum", "avg", "min", "max").contains(name)
                && invocation.getArguments().size() == 1
                && isDslStaticFunctionInvocation(invocation, parsedSources)) {
            return new FunctionCallExpression(
                    name.toUpperCase(Locale.ROOT),
                    List.of(lowerDslValue(invocation.getArguments().getFirst(), parsedSources)),
                    null);
        }
        if ("over".equals(name)
                && invocation.getMethodSelect() instanceof MemberSelectTree member
                && !invocation.getArguments().isEmpty()) {
            WindowFunctionExpression window = lowerDslWindowFunction(
                    member.getExpression(),
                    invocation.getArguments().getFirst(),
                    parsedSources);
            if (window != null) {
                return window;
            }
        }
        if ("exists".equals(name)
                && !invocation.getArguments().isEmpty()
                && invocation.getArguments().getFirst() instanceof MethodInvocationTree subqueryInvocation) {
            SelectSql subquery = lowerDslSubquery(subqueryInvocation, parsedSources);
            if (subquery != null) {
                return new ExistsExpression(subquery, false);
            }
        }
        if ("notExists".equals(name)
                && !invocation.getArguments().isEmpty()
                && invocation.getArguments().getFirst() instanceof MethodInvocationTree subqueryInvocation) {
            SelectSql subquery = lowerDslSubquery(subqueryInvocation, parsedSources);
            if (subquery != null) {
                return new ExistsExpression(subquery, true);
            }
        }
        return null;
    }

    /** True when the invocation resolves to a static method declared on {@code titan.dsl.DSL}. */
    private static boolean isDslStaticFunctionInvocation(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return false;
        }
        TreePath invocationPath = LowererSupport.resolveTreePath(parsedSources, invocation);
        ExecutableElement method = LowererSupport.resolvedMethodElement(invocationPath, parsedSources);
        return method != null
                && method.getEnclosingElement() != null
                && "titan.dsl.DSL".equals(method.getEnclosingElement().toString());
    }

    private static WindowFunctionExpression lowerDslWindowFunction(
            ExpressionTree functionExpression,
            ExpressionTree specExpression,
            ParsedSources parsedSources
    ) {
        if (!(functionExpression instanceof MethodInvocationTree invocation)) {
            return null;
        }
        String name = LowererSupport.invocationName(invocation);
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        String function = switch (name) {
            case "rowNumber" -> arguments.isEmpty() ? "ROW_NUMBER" : null;
            case "rank" -> arguments.isEmpty() ? "RANK" : null;
            case "denseRank" -> arguments.isEmpty() ? "DENSE_RANK" : null;
            case "ntile" -> arguments.size() == 1 ? "NTILE" : null;
            case "lag" -> arguments.size() == 1 || arguments.size() == 2 ? "LAG" : null;
            case "lead" -> arguments.size() == 1 || arguments.size() == 2 ? "LEAD" : null;
            case "firstValue" -> arguments.size() == 1 ? "FIRST_VALUE" : null;
            case "lastValue" -> arguments.size() == 1 ? "LAST_VALUE" : null;
            case "nthValue" -> arguments.size() == 2 ? "NTH_VALUE" : null;
            default -> null;
        };
        if (function == null) {
            return null;
        }
        WindowSpec spec = lowerDslWindowSpec(specExpression, parsedSources);
        return new WindowFunctionExpression(function, lowerDslExpressionList(arguments, parsedSources), spec);
    }

    private static WindowSpec lowerDslWindowSpec(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (!(expressionTree instanceof MethodInvocationTree)) {
            throw LowererSupport.unsupportedFeature(
                    "over(...) requires an inline window specification chain (partitionBy/orderBy/rowsBetween/...)",
                    expressionTree,
                    parsedSources);
        }
        List<ExpressionNode> partitionBy = new ArrayList<>();
        List<OrderBySpec> orderBy = new ArrayList<>();
        WindowFrame frame = null;
        MethodInvocationTree cursor = (MethodInvocationTree) expressionTree;
        while (cursor != null) {
            String name = LowererSupport.invocationName(cursor);
            switch (name) {
                case "partitionBy" -> partitionBy.addAll(0, lowerDslExpressionList(cursor.getArguments(), parsedSources));
                case "orderBy" -> orderBy.addAll(0, lowerDslOrderBy(cursor.getArguments(), parsedSources));
                case "rowsBetween", "rangeBetween", "groupsBetween" -> {
                    if (cursor.getArguments().size() != 2) {
                        throw LowererSupport.unsupportedFeature(
                                name + "(...) requires exactly two window frame boundaries",
                                cursor,
                                parsedSources);
                    }
                    WindowFrameUnit unit = switch (name) {
                        case "rowsBetween" -> WindowFrameUnit.ROWS;
                        case "rangeBetween" -> WindowFrameUnit.RANGE;
                        default -> WindowFrameUnit.GROUPS;
                    };
                    frame = new WindowFrame(
                            unit,
                            lowerDslWindowFrameBound(cursor.getArguments().get(0), parsedSources),
                            lowerDslWindowFrameBound(cursor.getArguments().get(1), parsedSources));
                }
                default -> throw LowererSupport.unsupportedFeature(
                        "Window specification step '" + name + "(...)' is not supported in lowered DSL chains",
                        cursor,
                        parsedSources);
            }

            ExpressionTree select = cursor.getMethodSelect();
            if (select instanceof MemberSelectTree member && member.getExpression() instanceof MethodInvocationTree previous) {
                cursor = previous;
            } else {
                cursor = null;
            }
        }
        return new WindowSpec(List.copyOf(partitionBy), List.copyOf(orderBy), frame);
    }

    private static WindowFrameBound lowerDslWindowFrameBound(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MethodInvocationTree invocation) {
            String name = LowererSupport.invocationName(invocation);
            if ("unboundedPreceding".equals(name) && invocation.getArguments().isEmpty()) {
                return new WindowFrameBound(WindowFrameBoundKind.UNBOUNDED_PRECEDING, null);
            }
            if ("currentRow".equals(name) && invocation.getArguments().isEmpty()) {
                return new WindowFrameBound(WindowFrameBoundKind.CURRENT_ROW, null);
            }
            if ("unboundedFollowing".equals(name) && invocation.getArguments().isEmpty()) {
                return new WindowFrameBound(WindowFrameBoundKind.UNBOUNDED_FOLLOWING, null);
            }
            if ("preceding".equals(name) && invocation.getArguments().size() == 1) {
                return new WindowFrameBound(
                        WindowFrameBoundKind.PRECEDING,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
            if ("following".equals(name) && invocation.getArguments().size() == 1) {
                return new WindowFrameBound(
                        WindowFrameBoundKind.FOLLOWING,
                        lowerDslValue(invocation.getArguments().getFirst(), parsedSources));
            }
        }
        throw LowererSupport.unsupportedFeature(
                "Window frame boundary is not supported in lowered DSL chains",
                expressionTree,
                parsedSources);
    }

    private static String tableName(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (parsedSources == null) {
            if (expressionTree instanceof IdentifierTree id) {
                return id.getName().toString();
            }
            return expressionTree.toString();
        }
        String physicalTableName = resolveDslPhysicalTableName(expressionTree, parsedSources);
        if (physicalTableName != null) {
            return physicalTableName;
        }
        DslTableSource dslTableSource = resolveDslTableSource(expressionTree, parsedSources);
        if (dslTableSource != null) {
            return dslTableSource.name();
        }
        if (expressionTree instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        return expressionTree.toString();
    }

    private static boolean isDslColumnReference(ExpressionTree tableExpression, ParsedSources parsedSources) {
        return tableExpression != null
                && (resolveDslPhysicalTableName(tableExpression, parsedSources) != null
                || resolveDslTableSource(tableExpression, parsedSources) != null);
    }

    private static String resolveDslPhysicalTableName(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return null;
        }
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expressionTree);
        if (path == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(path);
        TypeElement typeElement = typeElementForTableReference(element);
        if (typeElement == null) {
            return null;
        }
        String annotatedPhysicalTableName = annotationStringValue(typeElement, PHYSICAL_TABLE_ANNOTATION, "name");
        if (annotatedPhysicalTableName != null && !annotatedPhysicalTableName.isBlank()) {
            return annotatedPhysicalTableName;
        }
        Tree declaration = parsedSources.trees().getTree(typeElement);
        if (!(declaration instanceof ClassTree classTree)) {
            return null;
        }
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof MethodTree methodTree)
                    || methodTree.getReturnType() != null
                    || methodTree.getBody() == null) {
                continue;
            }
            for (StatementTree statement : methodTree.getBody().getStatements()) {
                if (!(statement instanceof ExpressionStatementTree expressionStatement)
                        || !(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                        || !(invocation.getMethodSelect() instanceof IdentifierTree superIdentifier)
                        || !"super".contentEquals(superIdentifier.getName())
                        || invocation.getArguments().isEmpty()
                        || !(invocation.getArguments().getFirst() instanceof LiteralTree literal)
                        || !(literal.getValue() instanceof String tableName)
                        || tableName.isBlank()) {
                    continue;
                }
                return tableName;
            }
        }
        return null;
    }

    /**
     * ATG-020: the declared schema of a DSL physical table — {@code @PhysicalTable(schema = "…")} or the
     * second {@code super("table", "schema")} constructor argument — or {@code null} when none is declared.
     * Used to schema-qualify the FROM/JOIN clause; column qualifiers keep the bare table name (see
     * {@link #qualifiedTableName}).
     */
    private static String resolveDslPhysicalSchema(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (parsedSources == null) {
            return null;
        }
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expressionTree);
        if (path == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(path);
        TypeElement typeElement = typeElementForTableReference(element);
        if (typeElement == null) {
            return null;
        }
        String annotatedSchema = annotationStringValue(typeElement, PHYSICAL_TABLE_ANNOTATION, "schema");
        if (annotatedSchema != null && !annotatedSchema.isBlank()) {
            return annotatedSchema;
        }
        Tree declaration = parsedSources.trees().getTree(typeElement);
        if (!(declaration instanceof ClassTree classTree)) {
            return null;
        }
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof MethodTree methodTree)
                    || methodTree.getReturnType() != null
                    || methodTree.getBody() == null) {
                continue;
            }
            for (StatementTree statement : methodTree.getBody().getStatements()) {
                if (statement instanceof ExpressionStatementTree expressionStatement
                        && expressionStatement.getExpression() instanceof MethodInvocationTree invocation
                        && invocation.getMethodSelect() instanceof IdentifierTree superIdentifier
                        && "super".contentEquals(superIdentifier.getName())
                        && invocation.getArguments().size() >= 2
                        && invocation.getArguments().get(1) instanceof LiteralTree literal
                        && literal.getValue() instanceof String schema
                        && !schema.isBlank()) {
                    return schema;
                }
            }
        }
        return null;
    }

    /**
     * ATG-020: the FROM/JOIN-clause physical name for a DSL table — {@code "schema.table"} when a schema is
     * declared, else the bare table name. The emitter quotes each dot-separated part
     * ({@code "schema"."table"}). Column references keep the <b>bare</b> table name ({@link #tableName})
     * as their qualifier, so a schema-qualified source does not over-qualify columns to the invalid
     * {@code "schema"."table"."col"} (which PostgreSQL rejects).
     */
    private static String qualifiedTableName(ExpressionTree expressionTree, ParsedSources parsedSources) {
        String table = tableName(expressionTree, parsedSources);
        String schema = resolveDslPhysicalSchema(expressionTree, parsedSources);
        // The default schema "public" is elided: it is PostgreSQL's default search_path entry (so
        // qualifying it is a no-op there), and MySQL has no "public" database (so qualifying it would
        // break — there is no public.table). A genuine domain schema (e.g. "identity") is preserved.
        if (schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema)) {
            return table;
        }
        return schema + "." + table;
    }

    private static String toColumnName(ExpressionTree expressionTree, ParsedSources parsedSources) {
        if (expressionTree instanceof MemberSelectTree member) {
            return resolveDslColumnName(member, parsedSources);
        }
        return null;
    }

    private static String resolveDslColumnName(MemberSelectTree memberSelectTree, ParsedSources parsedSources) {
        if (memberSelectTree == null || parsedSources == null) {
            return memberSelectTree == null ? null : memberSelectTree.getIdentifier().toString();
        }
        TreePath path = LowererSupport.resolveTreePath(parsedSources, memberSelectTree);
        if (path == null) {
            return memberSelectTree.getIdentifier().toString();
        }
        Element element = parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variableElement)) {
            return memberSelectTree.getIdentifier().toString();
        }
        String annotatedPhysicalColumnName = annotationStringValue(variableElement, PHYSICAL_COLUMN_ANNOTATION, "value");
        if (annotatedPhysicalColumnName != null && !annotatedPhysicalColumnName.isBlank()) {
            return annotatedPhysicalColumnName;
        }
        Tree declarationTree = parsedSources.trees().getTree(variableElement);
        if (!(declarationTree instanceof VariableTree variableTree) || variableTree.getInitializer() == null) {
            return memberSelectTree.getIdentifier().toString();
        }
        ExpressionTree initializer = variableTree.getInitializer();
        if (!(initializer instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof IdentifierTree identifier)
                || !"column".contentEquals(identifier.getName())
                || invocation.getArguments().isEmpty()) {
            return memberSelectTree.getIdentifier().toString();
        }
        String physicalColumnName = asStringLiteral(invocation.getArguments().getFirst(), "column name", parsedSources);
        return physicalColumnName == null || physicalColumnName.isBlank()
                ? memberSelectTree.getIdentifier().toString()
                : physicalColumnName;
    }

    /**
     * G4 (spike B4): when a {@code set(...)} / insert value targets a {@code SQLType.JSON} column,
     * wrap it in a cast to {@link TJsonType} so the emitter renders a dialect-appropriate JSON cast
     * ({@code CAST(<value> AS JSONB)} on PostgreSQL, {@code CAST(<value> AS JSON)} on MySQL). A
     * {@code jsonb} column otherwise rejects a bound {@code String} parameter
     * ({@code "column is of type jsonb but expression is of type text"}); MySQL parses the string
     * implicitly but the explicit cast keeps both dialects correct and uniform.
     *
     * <p>Only a value targeting a JSON column is wrapped, and a value already shaped as a cast to
     * {@code TJsonType} (or a bare {@code NULL} literal, which both dialects accept directly) is
     * left as-is to avoid a redundant double cast.</p>
     */
    private static ExpressionNode jsonCastIfTargetColumnIsJson(
            ExpressionNode value,
            ExpressionTree columnArgument,
            ParsedSources parsedSources
    ) {
        if (!columnSqlTypeIsJson(columnArgument, parsedSources)) {
            return value;
        }
        if (value instanceof CastExpression cast && cast.targetType() instanceof TJsonType) {
            return value;
        }
        if (value instanceof LiteralExpression literal && literal.value() == null) {
            return value;
        }
        return new CastExpression(value, new TJsonType());
    }

    /**
     * Whether the column referenced by {@code columnArgument} was declared with {@code SQLType.JSON}.
     * Mirrors {@link #resolveDslColumnName(MemberSelectTree, ParsedSources)}'s element resolution:
     * follows {@code table.COLUMN} to the field's {@code column("name", SQLType.X, …)} initializer
     * and inspects the second argument. Returns {@code false} for anything it cannot resolve to a
     * {@code SQLType.JSON} reference, so a column whose type is unknown keeps the unwrapped value.
     */
    private static boolean columnSqlTypeIsJson(ExpressionTree columnArgument, ParsedSources parsedSources) {
        if (!(columnArgument instanceof MemberSelectTree memberSelectTree) || parsedSources == null) {
            return false;
        }
        TreePath path = LowererSupport.resolveTreePath(parsedSources, memberSelectTree);
        if (path == null) {
            return false;
        }
        Element element = parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variableElement)) {
            return false;
        }
        Tree declarationTree = parsedSources.trees().getTree(variableElement);
        if (!(declarationTree instanceof VariableTree variableTree)
                || !(variableTree.getInitializer() instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof IdentifierTree identifier)
                || !"column".contentEquals(identifier.getName())
                || invocation.getArguments().size() < 2) {
            return false;
        }
        return isJsonSqlTypeReference(invocation.getArguments().get(1));
    }

    /** Whether {@code typeArgument} is the {@code SQLType.JSON} enum constant (qualified or imported). */
    private static boolean isJsonSqlTypeReference(ExpressionTree typeArgument) {
        if (typeArgument instanceof MemberSelectTree typeMember) {
            return "JSON".contentEquals(typeMember.getIdentifier())
                    && typeMember.getExpression() instanceof IdentifierTree qualifier
                    && "SQLType".contentEquals(qualifier.getName());
        }
        // A statically-imported `JSON` constant arrives as a bare identifier.
        return typeArgument instanceof IdentifierTree identifier && "JSON".contentEquals(identifier.getName());
    }

    private static TypeElement typeElementForTableReference(Element element) {
        if (!(element instanceof VariableElement variableElement)
                || !(variableElement.asType() instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            return null;
        }
        return typeElement;
    }

    private static String annotationStringValue(Element element, String annotationType, String attributeName) {
        if (element == null) {
            return null;
        }
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
            if (!annotationType.equals(annotationMirror.getAnnotationType().toString())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : annotationMirror.getElementValues().entrySet()) {
                if (!attributeName.equals(entry.getKey().getSimpleName().toString())) {
                    continue;
                }
                Object value = entry.getValue().getValue();
                return value instanceof String stringValue ? stringValue : null;
            }
        }
        return null;
    }

    private static boolean invocationChainContains(MethodInvocationTree invocation, String methodName) {
        MethodInvocationTree cursor = invocation;
        while (cursor != null) {
            if (methodName.equals(LowererSupport.invocationName(cursor))) {
                return true;
            }
            ExpressionTree select = cursor.getMethodSelect();
            if (select instanceof MemberSelectTree member && member.getExpression() instanceof MethodInvocationTree previous) {
                cursor = previous;
            } else {
                cursor = null;
            }
        }
        return false;
    }

    private static LateralSubquery lowerDslLateralSubquery(
            ExpressionTree subqueryExpression,
            ExpressionTree aliasExpression,
            ParsedSources parsedSources
    ) {
        String alias = aliasLiteral(aliasExpression, parsedSources);
        if (!(subqueryExpression instanceof MethodInvocationTree subqueryInvocation)) {
            throw LowererSupport.unsupportedFeature(
                    "lateralJoin(...) requires an inline DSL select(...) subquery argument",
                    subqueryExpression,
                    parsedSources);
        }
        SelectSql subquery = lowerDslSubquery(subqueryInvocation, parsedSources);
        if (subquery == null) {
            throw LowererSupport.unsupportedFeature(
                    "Could not lower lateralJoin(...) subquery into SQL",
                    subqueryExpression,
                    parsedSources);
        }
        return new LateralSubquery(subquery, alias);
    }

    private static String aliasLiteral(ExpressionTree aliasExpression, ParsedSources parsedSources) {
        if (aliasExpression instanceof LiteralTree literal && literal.getValue() instanceof String alias && !alias.isBlank()) {
            return alias;
        }
        throw LowererSupport.unsupportedFeature("lateralJoin(...).as(...) requires a non-blank string literal alias", aliasExpression, parsedSources);
    }

    private static String asStringLiteral(ExpressionTree expression, String label, ParsedSources parsedSources) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value && !value.isBlank()) {
            return value;
        }
        throw LowererSupport.unsupportedFeature(label + " must be a non-blank string literal", expression, parsedSources);
    }
}
