package io.titan.transpiler.tir;

public sealed interface ExpressionNode extends TirNode permits ColumnRefExpression, VariableRefExpression, LiteralExpression,
        BinaryOpExpression, FunctionCallExpression, RecordConstructExpression, RecordFieldExpression, ArrayConstructExpression,
        ArrayLengthExpression, ArrayGetExpression, CaseWhenExpression, SubqueryExpression,
        IsNullExpression, IsNotNullExpression, NotExpression, CastExpression, CoalesceExpression,
        ExistsExpression, InListExpression, WindowFunctionExpression, GroupingSetSpec {
}
