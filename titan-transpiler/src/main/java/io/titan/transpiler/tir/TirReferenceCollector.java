package io.titan.transpiler.tir;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Collects the typed references a lowered routine body makes (plan 4.4, audit G-10):
 * record types it uses, routine/helper names it calls, and tables/views it reads or writes.
 *
 * <p>The packaging dependency edges are derived from these structured references (plus the
 * entry-point call graph) — never from name-substring matching over emitted SQL, which invented
 * edges like {@code get_user_orders -> get_user} purely because one name contains the other.</p>
 */
final class TirReferenceCollector {

    private final Set<String> recordNames = new LinkedHashSet<>();
    private final Set<String> calledRoutineNames = new LinkedHashSet<>();
    private final Set<String> tableNames = new LinkedHashSet<>();

    static TirReferenceCollector collect(Block body) {
        TirReferenceCollector collector = new TirReferenceCollector();
        if (body != null) {
            collector.block(body);
        }
        return collector;
    }

    /** Java record names referenced through {@link TRecordType} or record expressions. */
    Set<String> recordNames() {
        return recordNames;
    }

    /** Routine names invoked via {@code CALL} or function-call expressions (post-remap SQL names). */
    Set<String> calledRoutineNames() {
        return calledRoutineNames;
    }

    /** Lowercased table/view names read or written by embedded SQL. */
    Set<String> tableNames() {
        return tableNames;
    }

    private void block(Block block) {
        block.declarations().forEach(this::declaration);
        block.statements().forEach(this::statement);
        block.exceptionHandlers().forEach(this::declaration);
    }

    private void declaration(DeclarationNode declaration) {
        switch (declaration) {
            case DeclareVariable variable -> {
                type(variable.type());
                expression(variable.initializer());
            }
            case DeclareCursor cursor -> sql(cursor.query());
            case DeclareHandler ignored -> {
            }
        }
    }

    private void statement(StatementNode statement) {
        switch (statement) {
            case Block nested -> block(nested);
            case Assign assign -> {
                expression(assign.target());
                expression(assign.expression());
            }
            case IfStatement ifStatement -> {
                expression(ifStatement.condition());
                block(ifStatement.thenBlock());
                for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                    expression(clause.condition());
                    block(clause.block());
                }
                if (ifStatement.elseBlock() != null) {
                    block(ifStatement.elseBlock());
                }
            }
            case WhileStatement whileStatement -> {
                expression(whileStatement.condition());
                block(whileStatement.body());
            }
            case LoopStatement loopStatement -> {
                block(loopStatement.body());
                expression(loopStatement.exitCondition());
            }
            case ForCursorStatement forCursor -> {
                sql(forCursor.query());
                block(forCursor.body());
            }
            case ForEachStatement forEach -> {
                type(forEach.variableType());
                expression(forEach.iterable());
                block(forEach.body());
            }
            case ForRangeStatement forRange -> {
                expression(forRange.start());
                expression(forRange.end());
                block(forRange.body());
            }
            case ReturnStatement returnStatement -> expression(returnStatement.expression());
            case RaiseStatement raise -> {
                expression(raise.message());
                raise.details().forEach(this::expression);
            }
            case CallStatement call -> {
                calledRoutineNames.add(call.procedureName());
                call.arguments().forEach(this::expression);
            }
            case DebugPrintStatement debugPrint -> expression(debugPrint.message());
            case ExecuteSqlStatement execute -> sql(execute.sqlNode());
            case SelectIntoStatement selectInto -> sql(selectInto.query());
            case RawReadIntoStatement rawRead -> {
                sql(rawRead.query());
                if (rawRead.notFoundRaise() != null) {
                    expression(rawRead.notFoundRaise().message());
                    rawRead.notFoundRaise().details().forEach(this::expression);
                }
            }
            case RawCursorStatement rawCursor -> {
                sql(rawCursor.query());
                block(rawCursor.body());
            }
            case DynamicResultStatement dynamicResult -> sql(dynamicResult.query());
            case GeneratedKeyReadStatement genKeyRead -> sql(genKeyRead.insert());
            case TryCatchFinallyStatement tryCatchFinally -> {
                block(tryCatchFinally.tryBlock());
                for (CatchClause catchClause : tryCatchFinally.catches()) {
                    block(catchClause.body());
                }
                if (tryCatchFinally.finallyBlock() != null) {
                    block(tryCatchFinally.finallyBlock());
                }
            }
            default -> {
            }
        }
    }

    private void sql(SqlNode sqlNode) {
        switch (sqlNode) {
            case SelectSql select -> {
                table(select.from());
                if (select.joins() != null) {
                    for (JoinSpec join : select.joins()) {
                        table(join.target());
                        if (join.lateralTarget() != null) {
                            sql(join.lateralTarget());
                        }
                        expression(join.condition());
                    }
                }
                select.columns().forEach(column -> expression(column.expression()));
                expression(select.where());
                select.groupBy().forEach(this::expression);
                expression(select.having());
                if (select.orderBy() != null) {
                    select.orderBy().forEach(orderBy -> expression(orderBy.expression()));
                }
                if (select.ctes() != null) {
                    select.ctes().forEach(cte -> sql(cte.query()));
                }
            }
            case InsertSql insert -> {
                table(insert.table());
                insert.values().forEach(this::expression);
                if (insert.selectSource() != null) {
                    sql(insert.selectSource());
                }
            }
            case UpdateSql update -> {
                table(update.table());
                update.sets().forEach(set -> expression(set.value()));
                expression(update.where());
            }
            case DeleteSql delete -> {
                table(delete.table());
                expression(delete.where());
            }
            case UnionSql union -> {
                sql(union.left());
                sql(union.right());
            }
            case IntersectSql intersect -> {
                sql(intersect.left());
                sql(intersect.right());
            }
            case ExceptSql except -> {
                sql(except.left());
                sql(except.right());
            }
            case LateralSubquery lateral -> sql(lateral.subquery());
            case RawSql ignored -> {
            }
            case null -> {
            }
        }
    }

    private void expression(ExpressionNode expression) {
        switch (expression) {
            case BinaryOpExpression binary -> {
                expression(binary.left());
                expression(binary.right());
            }
            case FunctionCallExpression functionCall -> {
                calledRoutineNames.add(functionCall.name());
                functionCall.arguments().forEach(this::expression);
            }
            case RecordConstructExpression recordConstruct -> {
                recordNames.add(recordConstruct.recordName());
                recordConstruct.arguments().forEach(this::expression);
            }
            case RecordFieldExpression recordField -> {
                recordNames.add(recordField.recordName());
                expression(recordField.record());
            }
            case ArrayConstructExpression arrayConstruct -> {
                type(arrayConstruct.elementType());
                arrayConstruct.elements().forEach(this::expression);
            }
            case ArrayLengthExpression arrayLength -> expression(arrayLength.array());
            case ArrayGetExpression arrayGet -> {
                type(arrayGet.elementType());
                expression(arrayGet.array());
                expression(arrayGet.index());
            }
            case CaseWhenExpression caseWhen -> {
                for (CaseBranch branch : caseWhen.conditions()) {
                    expression(branch.condition());
                    expression(branch.value());
                }
                expression(caseWhen.elseValue());
            }
            case SubqueryExpression subquery -> sql(subquery.select());
            case IsNullExpression isNull -> expression(isNull.expression());
            case IsNotNullExpression isNotNull -> expression(isNotNull.expression());
            case CastExpression cast -> {
                type(cast.targetType());
                expression(cast.expression());
            }
            case CoalesceExpression coalesce -> coalesce.expressions().forEach(this::expression);
            case ExistsExpression exists -> sql(exists.subquery());
            case WindowFunctionExpression window -> {
                window.arguments().forEach(this::expression);
                if (window.spec() != null) {
                    window.spec().partitionBy().forEach(this::expression);
                    window.spec().orderBy().forEach(orderBy -> expression(orderBy.expression()));
                }
            }
            case GroupingSetSpec groupingSet -> groupingSet.sets()
                    .forEach(set -> set.forEach(this::expression));
            case null -> {
            }
            default -> {
            }
        }
    }

    private void type(TirType type) {
        switch (type) {
            case TRecordType recordType -> recordNames.add(recordType.recordName());
            case TArrayType arrayType -> type(arrayType.elementType());
            case null -> {
            }
            default -> {
            }
        }
    }

    private void table(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        tableNames.add(name.trim().toLowerCase(Locale.ROOT));
    }
}
