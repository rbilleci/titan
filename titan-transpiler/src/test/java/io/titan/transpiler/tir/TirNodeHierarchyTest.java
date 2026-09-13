package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TirNodeHierarchyTest {

    @Test
    void dispatchesViaVisitorAcrossRepresentativeNodes() {
        TirVisitor<String> visitor = new TirVisitor<>() {
            @Override public String visitDeclareVariable(DeclareVariable node) { return "declare-var"; }
            @Override public String visitDeclareCursor(DeclareCursor node) { return "declare-cursor"; }
            @Override public String visitDeclareHandler(DeclareHandler node) { return "declare-handler"; }
            @Override public String visitBlock(Block node) { return "block"; }
            @Override public String visitAssign(Assign node) { return "assign"; }
            @Override public String visitIfStatement(IfStatement node) { return "if"; }
            @Override public String visitWhileStatement(WhileStatement node) { return "while"; }
            @Override public String visitLoopStatement(LoopStatement node) { return "loop"; }
            @Override public String visitForCursorStatement(ForCursorStatement node) { return "for-cursor"; }
            @Override public String visitForEachStatement(ForEachStatement node) { return "for-each"; }
            @Override public String visitForRangeStatement(ForRangeStatement node) { return "for-range"; }
            @Override public String visitReturnStatement(ReturnStatement node) { return "return"; }
            @Override public String visitBreakStatement(BreakStatement node) { return "break"; }
            @Override public String visitContinueStatement(ContinueStatement node) { return "continue"; }
            @Override public String visitRaiseStatement(RaiseStatement node) { return "raise"; }
            @Override public String visitCallStatement(CallStatement node) { return "call"; }
            @Override public String visitDebugPrintStatement(DebugPrintStatement node) { return "debug-print"; }
            @Override public String visitExecuteSqlStatement(ExecuteSqlStatement node) { return "exec-sql"; }
            @Override public String visitSelectIntoStatement(SelectIntoStatement node) { return "select-into"; }
            @Override public String visitNullGuardStatement(NullGuardStatement node) { return "null-guard"; }
            @Override public String visitCloseCursorStatement(CloseCursorStatement node) { return "close-cursor"; }
            @Override public String visitTryCatchFinallyStatement(TryCatchFinallyStatement node) { return "try-catch-finally"; }
            @Override public String visitRawReadIntoStatement(RawReadIntoStatement node) { return "raw-read-into"; }
            @Override public String visitRawCursorStatement(RawCursorStatement node) { return "raw-cursor"; }
            @Override public String visitDynamicResultStatement(DynamicResultStatement node) { return "dynamic-result"; }
            @Override public String visitGeneratedKeyReadStatement(GeneratedKeyReadStatement node) { return "generated-key-read"; }
            @Override public String visitTransactionControlStatement(TransactionControlStatement node) { return "transaction-control"; }
            @Override public String visitSelectSql(SelectSql node) { return "select"; }
            @Override public String visitInsertSql(InsertSql node) { return "insert"; }
            @Override public String visitUpdateSql(UpdateSql node) { return "update"; }
            @Override public String visitDeleteSql(DeleteSql node) { return "delete"; }
            @Override public String visitUnionSql(UnionSql node) { return "union"; }
            @Override public String visitIntersectSql(IntersectSql node) { return "intersect"; }
            @Override public String visitExceptSql(ExceptSql node) { return "except"; }
            @Override public String visitRawSql(RawSql node) { return "raw"; }
            @Override public String visitLateralSubquery(LateralSubquery node) { return "lateral-subquery"; }
            @Override public String visitColumnRefExpression(ColumnRefExpression node) { return "col-ref"; }
            @Override public String visitVariableRefExpression(VariableRefExpression node) { return "var-ref"; }
            @Override public String visitLiteralExpression(LiteralExpression node) { return "literal"; }
            @Override public String visitBinaryOpExpression(BinaryOpExpression node) { return "binary"; }
            @Override public String visitFunctionCallExpression(FunctionCallExpression node) { return "fn"; }
            @Override public String visitRecordConstructExpression(RecordConstructExpression node) { return "record-new"; }
            @Override public String visitRecordFieldExpression(RecordFieldExpression node) { return "record-field"; }
            @Override public String visitArrayConstructExpression(ArrayConstructExpression node) { return "array-new"; }
            @Override public String visitArrayLengthExpression(ArrayLengthExpression node) { return "array-length"; }
            @Override public String visitArrayGetExpression(ArrayGetExpression node) { return "array-get"; }
            @Override public String visitCaseWhenExpression(CaseWhenExpression node) { return "case"; }
            @Override public String visitSubqueryExpression(SubqueryExpression node) { return "subquery"; }
            @Override public String visitIsNullExpression(IsNullExpression node) { return "is-null"; }
            @Override public String visitIsNotNullExpression(IsNotNullExpression node) { return "is-not-null"; }
            @Override public String visitNotExpression(NotExpression node) { return "not"; }
            @Override public String visitCastExpression(CastExpression node) { return "cast"; }
            @Override public String visitCoalesceExpression(CoalesceExpression node) { return "coalesce"; }
            @Override public String visitExistsExpression(ExistsExpression node) { return "exists"; }
            @Override public String visitInListExpression(InListExpression node) { return "in-list"; }
            @Override public String visitWindowFunctionExpression(WindowFunctionExpression node) { return "window"; }
            @Override public String visitGroupingSetSpec(GroupingSetSpec node) { return "grouping-set"; }
        };

        assertEquals("declare-var", new DeclareVariable("v", new TIntType(), false, null).accept(visitor));
        assertEquals("for-each", new ForEachStatement("item", new TIntType(), new VariableRefExpression("items"), new Block(List.of(), List.of(), List.of()), null).accept(visitor));
        assertEquals("return", new ReturnStatement(new LiteralExpression(1, new TIntType())).accept(visitor));
        assertEquals("close-cursor", new CloseCursorStatement("cur_items").accept(visitor));
        assertEquals("debug-print", new DebugPrintStatement(new LiteralExpression("hi", new TTextType())).accept(visitor));
        assertEquals("select", new SelectSql(List.of(), "accounts", List.of(), null, List.of(), null, List.of(), null, null, null, List.of()).accept(visitor));
        assertEquals("binary", new BinaryOpExpression(new LiteralExpression(1, new TIntType()), BinaryOperator.ADD, new LiteralExpression(2, new TIntType())).accept(visitor));
        assertEquals("record-new", new RecordConstructExpression("Account", List.of()).accept(visitor));
        assertEquals("record-field", new RecordFieldExpression(new VariableRefExpression("account"), "Account", "id").accept(visitor));
        assertEquals("array-new", new ArrayConstructExpression(new TIntType(), List.of()).accept(visitor));
        assertEquals("array-length", new ArrayLengthExpression(new VariableRefExpression("items")).accept(visitor));
        assertEquals("array-get", new ArrayGetExpression(new VariableRefExpression("items"), new LiteralExpression(0, new TIntType()), new TTextType()).accept(visitor));

        SelectSql subquery = new SelectSql(List.of(), "accounts", List.of(), null, List.of(), null, List.of(), null, null, null, List.of());
        assertEquals("select-into", new SelectIntoStatement("v_present", subquery).accept(visitor));
        assertEquals("exists", new ExistsExpression(subquery, false).accept(visitor));
        assertEquals("in-list", new InListExpression(
                new ColumnRefExpression("accounts", "id"),
                List.of(new LiteralExpression(1, new TIntType())),
                false).accept(visitor));
        assertEquals("window", new WindowFunctionExpression(
                "ROW_NUMBER", List.of(), new WindowSpec(List.of(), List.of(), null)).accept(visitor));
        assertEquals("grouping-set", new GroupingSetSpec(
                GroupingSetKind.ROLLUP, List.of(List.of(new ColumnRefExpression("accounts", "id")))).accept(visitor));
        assertEquals("lateral-subquery", new LateralSubquery(subquery, "lat").accept(visitor));

        RawSql rawRead = new RawSql("SELECT a, b FROM t WHERE id = ?", List.of("p_id"), "POSTGRESQL");
        assertEquals("raw-read-into", new RawReadIntoStatement(List.of("v_a", "v_b"), rawRead).accept(visitor));
        assertEquals("raw-cursor", new RawCursorStatement(
                List.of("v_a"), rawRead, new Block(List.of(), List.of(), List.of()), null).accept(visitor));
        assertEquals("dynamic-result", new DynamicResultStatement(rawRead).accept(visitor));
        RawSql rawInsert = new RawSql("INSERT INTO t (a) VALUES (?)", List.of("p_a"), "POSTGRESQL");
        assertEquals("generated-key-read",
                new GeneratedKeyReadStatement(rawInsert, "id", "v_key").accept(visitor));
        assertEquals("transaction-control",
                new TransactionControlStatement(TransactionAction.COMMIT).accept(visitor));
    }
}
