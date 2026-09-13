package io.titan.transpiler.tir;

public sealed interface StatementNode extends TirNode permits Block, Assign, IfStatement, WhileStatement, LoopStatement,
        ForCursorStatement, ForEachStatement, ForRangeStatement, ReturnStatement, BreakStatement, ContinueStatement,
        RaiseStatement, CallStatement, DebugPrintStatement, ExecuteSqlStatement, SelectIntoStatement,
        NullGuardStatement, CloseCursorStatement, TryCatchFinallyStatement, RawReadIntoStatement,
        RawCursorStatement, DynamicResultStatement, GeneratedKeyReadStatement, TransactionControlStatement {
}
