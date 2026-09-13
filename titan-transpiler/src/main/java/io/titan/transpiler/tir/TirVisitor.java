package io.titan.transpiler.tir;

public interface TirVisitor<R> {
    R visitDeclareVariable(DeclareVariable node);
    R visitDeclareCursor(DeclareCursor node);
    R visitDeclareHandler(DeclareHandler node);

    R visitBlock(Block node);
    R visitAssign(Assign node);
    R visitIfStatement(IfStatement node);
    R visitWhileStatement(WhileStatement node);
    R visitLoopStatement(LoopStatement node);
    R visitForCursorStatement(ForCursorStatement node);
    R visitForEachStatement(ForEachStatement node);
    R visitForRangeStatement(ForRangeStatement node);
    R visitReturnStatement(ReturnStatement node);
    R visitBreakStatement(BreakStatement node);
    R visitContinueStatement(ContinueStatement node);
    R visitRaiseStatement(RaiseStatement node);
    R visitCallStatement(CallStatement node);
    R visitDebugPrintStatement(DebugPrintStatement node);
    R visitExecuteSqlStatement(ExecuteSqlStatement node);
    R visitSelectIntoStatement(SelectIntoStatement node);
    R visitNullGuardStatement(NullGuardStatement node);
    R visitCloseCursorStatement(CloseCursorStatement node);
    R visitTryCatchFinallyStatement(TryCatchFinallyStatement node);
    R visitRawReadIntoStatement(RawReadIntoStatement node);
    R visitRawCursorStatement(RawCursorStatement node);
    R visitDynamicResultStatement(DynamicResultStatement node);
    R visitGeneratedKeyReadStatement(GeneratedKeyReadStatement node);
    R visitTransactionControlStatement(TransactionControlStatement node);

    R visitSelectSql(SelectSql node);
    R visitInsertSql(InsertSql node);
    R visitUpdateSql(UpdateSql node);
    R visitDeleteSql(DeleteSql node);
    R visitUnionSql(UnionSql node);
    R visitIntersectSql(IntersectSql node);
    R visitExceptSql(ExceptSql node);
    R visitRawSql(RawSql node);
    R visitLateralSubquery(LateralSubquery node);

    R visitColumnRefExpression(ColumnRefExpression node);
    R visitVariableRefExpression(VariableRefExpression node);
    R visitLiteralExpression(LiteralExpression node);
    R visitBinaryOpExpression(BinaryOpExpression node);
    R visitFunctionCallExpression(FunctionCallExpression node);
    R visitRecordConstructExpression(RecordConstructExpression node);
    R visitRecordFieldExpression(RecordFieldExpression node);
    R visitArrayConstructExpression(ArrayConstructExpression node);
    R visitArrayLengthExpression(ArrayLengthExpression node);
    R visitArrayGetExpression(ArrayGetExpression node);
    R visitCaseWhenExpression(CaseWhenExpression node);
    R visitSubqueryExpression(SubqueryExpression node);
    R visitIsNullExpression(IsNullExpression node);
    R visitIsNotNullExpression(IsNotNullExpression node);
    R visitNotExpression(NotExpression node);
    R visitCastExpression(CastExpression node);
    R visitCoalesceExpression(CoalesceExpression node);
    R visitExistsExpression(ExistsExpression node);
    R visitInListExpression(InListExpression node);
    R visitWindowFunctionExpression(WindowFunctionExpression node);
    R visitGroupingSetSpec(GroupingSetSpec node);
}
