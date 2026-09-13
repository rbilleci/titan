package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pass-level tests for {@link EmulationInsertionPass} (plan 2.4, audit E-7/E-11): insertion
 * shapes per mode, conservative typing (unknown operands are never rewritten), lexical scoping
 * of variable types, and the always-untouched SQL surface.
 */
class EmulationInsertionPassTest {

    // ------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------

    private static DeclareVariable declareInt(String name) {
        return new DeclareVariable(name, new TIntType(), false, null);
    }

    private static DeclareVariable declareLong(String name) {
        return new DeclareVariable(name, new TBigintType(), false, null);
    }

    private static DeclareVariable declareNumeric(String name) {
        return new DeclareVariable(name, new TNumericType(38, 10), false, null);
    }

    private static VariableRefExpression ref(String name) {
        return new VariableRefExpression(name);
    }

    private static LiteralExpression intLiteral(int value) {
        return new LiteralExpression(value, new TIntType());
    }

    private static BinaryOpExpression binary(ExpressionNode left, BinaryOperator operator, ExpressionNode right) {
        return new BinaryOpExpression(left, operator, right);
    }

    private static Block block(List<DeclarationNode> declarations, StatementNode... statements) {
        return new Block(declarations, List.of(statements), List.of());
    }

    private static Block applyDefault(Block input) {
        return new EmulationInsertionPass(false, Map.of()).apply(input);
    }

    private static Block applyStrict(Block input) {
        return new EmulationInsertionPass(true, Map.of()).apply(input);
    }

    private static ExpressionNode firstAssignedExpression(Block rewritten) {
        Assign assign = assertInstanceOf(Assign.class, rewritten.statements().getFirst());
        return assign.expression();
    }

    private static FunctionCallExpression assertMarker(ExpressionNode expression, String marker) {
        FunctionCallExpression call = assertInstanceOf(FunctionCallExpression.class, expression);
        assertEquals(marker, call.name());
        assertEquals(2, call.arguments().size());
        return call;
    }

    // ------------------------------------------------------------------
    // Integer division/modulo: always on (E-7)
    // ------------------------------------------------------------------

    @Test
    void rewritesIntDivisionToMarkerInDefaultMode() {
        Block input = block(
                List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.DIVIDE, ref("b"))));

        FunctionCallExpression marker = assertMarker(
                firstAssignedExpression(applyDefault(input)), EmulationInsertionPass.INT_DIV_MARKER);
        assertEquals(ref("a"), marker.arguments().get(0));
        assertEquals(ref("b"), marker.arguments().get(1));
    }

    @Test
    void rewritesIntModuloToMarkerInDefaultMode() {
        Block input = block(
                List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.MODULO, ref("b"))));

        assertMarker(firstAssignedExpression(applyDefault(input)), EmulationInsertionPass.INT_MOD_MARKER);
    }

    @Test
    void rewritesLongDivisionToMarker() {
        // BIGINT operands are integral too: MySQL BIGINT/BIGINT still yields DECIMAL natively.
        Block input = block(
                List.of(declareLong("a"), declareLong("b"), declareLong("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.DIVIDE, ref("b"))));

        assertMarker(firstAssignedExpression(applyDefault(input)), EmulationInsertionPass.INT_DIV_MARKER);
    }

    @Test
    void rewritesDivisionByZeroLiteral() {
        // The lowerer's constant folder skips x/0 (it cannot fold a Java ArithmeticException),
        // so the pass must still see and rewrite it: the marker raises 22012 on both dialects.
        Block input = block(
                List.of(declareInt("r")),
                new Assign(ref("r"), binary(intLiteral(7), BinaryOperator.DIVIDE, intLiteral(0))));

        assertMarker(firstAssignedExpression(applyDefault(input)), EmulationInsertionPass.INT_DIV_MARKER);
    }

    @Test
    void usesParameterTypesForProvability() {
        Block input = block(
                List.of(declareInt("r")),
                new Assign(ref("r"), binary(ref("dividend"), BinaryOperator.DIVIDE, ref("divisor"))));

        Block rewritten = new EmulationInsertionPass(
                false,
                Map.of("dividend", new TIntType(), "divisor", new TIntType()))
                .apply(input);

        assertMarker(firstAssignedExpression(rewritten), EmulationInsertionPass.INT_DIV_MARKER);
    }

    @Test
    void doesNotRewriteDivisionWithUnknownOperand() {
        // 'mystery' is neither declared nor a parameter: conservatively untyped, never rewritten.
        Block input = block(
                List.of(declareInt("a"), declareInt("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.DIVIDE, ref("mystery"))));

        BinaryOpExpression kept = assertInstanceOf(BinaryOpExpression.class, firstAssignedExpression(applyDefault(input)));
        assertEquals(BinaryOperator.DIVIDE, kept.operator());
    }

    @Test
    void doesNotRewriteNumericDivision() {
        // double/BigDecimal division is true division in Java as well: native '/' is correct.
        Block input = block(
                List.of(declareNumeric("a"), declareInt("b"), declareNumeric("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.DIVIDE, ref("b"))));

        BinaryOpExpression kept = assertInstanceOf(BinaryOpExpression.class, firstAssignedExpression(applyDefault(input)));
        assertEquals(BinaryOperator.DIVIDE, kept.operator());
    }

    @Test
    void rewritesDivisionOfCastAndNestedArithmetic() {
        // CAST carries its target type; a nested division marker keeps its integral type so the
        // outer division is still provably integral: (a / b) / CAST(x AS INT).
        Block input = block(
                List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                new Assign(ref("r"), binary(
                        binary(ref("a"), BinaryOperator.DIVIDE, ref("b")),
                        BinaryOperator.DIVIDE,
                        new CastExpression(ref("mystery"), new TIntType()))));

        FunctionCallExpression outer = assertMarker(
                firstAssignedExpression(applyDefault(input)), EmulationInsertionPass.INT_DIV_MARKER);
        assertMarker(outer.arguments().get(0), EmulationInsertionPass.INT_DIV_MARKER);
        assertInstanceOf(CastExpression.class, outer.arguments().get(1));
    }

    @Test
    void rewritesDivisionInsideConditions() {
        // Java evaluates the division in 'if (a / b > 1)' with int semantics too.
        Block input = block(
                List.of(declareInt("a"), declareInt("b")),
                new IfStatement(
                        binary(
                                binary(ref("a"), BinaryOperator.DIVIDE, ref("b")),
                                BinaryOperator.GREATER_THAN,
                                intLiteral(1)),
                        block(List.of()),
                        List.of(),
                        null));

        IfStatement rewritten = assertInstanceOf(IfStatement.class, applyDefault(input).statements().getFirst());
        BinaryOpExpression comparison = assertInstanceOf(BinaryOpExpression.class, rewritten.condition());
        assertMarker(comparison.left(), EmulationInsertionPass.INT_DIV_MARKER);
    }

    @Test
    void declarationScopeEndsWithItsBlock() {
        // 'inner' is int-typed inside the nested block only; the outer use is unknown-typed.
        Block nested = block(
                List.of(declareInt("inner"), declareInt("x")),
                new Assign(ref("x"), binary(ref("inner"), BinaryOperator.DIVIDE, intLiteral(2))));
        Block root = block(
                List.of(declareInt("r")),
                nested,
                new Assign(ref("r"), binary(ref("inner"), BinaryOperator.DIVIDE, intLiteral(2))));

        Block rewritten = applyDefault(root);
        Block rewrittenNested = assertInstanceOf(Block.class, rewritten.statements().get(0));
        assertMarker(
                assertInstanceOf(Assign.class, rewrittenNested.statements().getFirst()).expression(),
                EmulationInsertionPass.INT_DIV_MARKER);
        BinaryOpExpression keptOutside = assertInstanceOf(
                BinaryOpExpression.class,
                assertInstanceOf(Assign.class, rewritten.statements().get(1)).expression());
        assertEquals(BinaryOperator.DIVIDE, keptOutside.operator());
    }

    @Test
    void typesTheForRangeVariableAsInt() {
        Block body = block(
                List.of(declareInt("r")),
                new Assign(ref("r"), binary(ref("i"), BinaryOperator.MODULO, intLiteral(3))));
        Block input = block(
                List.of(),
                new ForRangeStatement("i", intLiteral(0), intLiteral(10), body, null));

        ForRangeStatement rewritten = assertInstanceOf(ForRangeStatement.class, applyDefault(input).statements().getFirst());
        assertMarker(
                assertInstanceOf(Assign.class, rewritten.body().statements().getFirst()).expression(),
                EmulationInsertionPass.INT_MOD_MARKER);
    }

    @Test
    void leavesSqlSurfaceUntouched() {
        // DSL queries and raw SQL are written against SQL semantics; column types are unknown.
        SelectSql query = new SelectSql(
                List.of(new SelectColumn(
                        binary(new ColumnRefExpression("t", "a"), BinaryOperator.DIVIDE, new ColumnRefExpression("t", "b")),
                        "ratio")),
                "t", null, null, List.of(), null, List.of(), null, null, null, null);
        ExecuteSqlStatement statement = new ExecuteSqlStatement(query);
        Block input = block(List.of(declareInt("a")), statement);

        assertSame(statement, applyDefault(input).statements().getFirst());
    }

    // ------------------------------------------------------------------
    // 32-bit wraparound: policy-gated (E-11)
    // ------------------------------------------------------------------

    @Test
    void defaultModeKeepsNativeIntAddSubMul() {
        // Fail-loud parity policy: overflow raises on both dialects instead of wrapping.
        for (BinaryOperator operator : List.of(BinaryOperator.ADD, BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY)) {
            Block input = block(
                    List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                    new Assign(ref("r"), binary(ref("a"), operator, ref("b"))));

            BinaryOpExpression kept = assertInstanceOf(
                    BinaryOpExpression.class, firstAssignedExpression(applyDefault(input)));
            assertEquals(operator, kept.operator());
        }
    }

    @Test
    void strictModeRewritesIntAddSubMulToWraparoundMarkers() {
        Map<BinaryOperator, String> expected = Map.of(
                BinaryOperator.ADD, EmulationInsertionPass.INT_ADD_MARKER,
                BinaryOperator.SUBTRACT, EmulationInsertionPass.INT_SUB_MARKER,
                BinaryOperator.MULTIPLY, EmulationInsertionPass.INT_MUL_MARKER);
        expected.forEach((operator, marker) -> {
            Block input = block(
                    List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                    new Assign(ref("r"), binary(ref("a"), operator, ref("b"))));

            assertMarker(firstAssignedExpression(applyStrict(input)), marker);
        });
    }

    @Test
    void strictModeNestsWraparoundMarkers() {
        // (a + b) * c: the inner marker is typed INT, so the outer multiply is rewritten too.
        Block input = block(
                List.of(declareInt("a"), declareInt("b"), declareInt("c"), declareInt("r")),
                new Assign(ref("r"), binary(
                        binary(ref("a"), BinaryOperator.ADD, ref("b")),
                        BinaryOperator.MULTIPLY,
                        ref("c"))));

        FunctionCallExpression outer = assertMarker(
                firstAssignedExpression(applyStrict(input)), EmulationInsertionPass.INT_MUL_MARKER);
        assertMarker(outer.arguments().get(0), EmulationInsertionPass.INT_ADD_MARKER);
    }

    @Test
    void strictModeDoesNotRewriteLongArithmetic() {
        // 64-bit wraparound is not emulated (documented divergence): BIGINT raises on overflow.
        Block input = block(
                List.of(declareLong("a"), declareLong("b"), declareLong("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.ADD, ref("b"))));

        BinaryOpExpression kept = assertInstanceOf(BinaryOpExpression.class, firstAssignedExpression(applyStrict(input)));
        assertEquals(BinaryOperator.ADD, kept.operator());
    }

    @Test
    void strictModeDoesNotRewriteUnknownOperands() {
        Block input = block(
                List.of(declareInt("a"), declareInt("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.ADD, ref("mystery"))));

        BinaryOpExpression kept = assertInstanceOf(BinaryOpExpression.class, firstAssignedExpression(applyStrict(input)));
        assertEquals(BinaryOperator.ADD, kept.operator());
    }

    @Test
    void strictModeStillRewritesDivisionAndModulo() {
        Block input = block(
                List.of(declareInt("a"), declareInt("b"), declareInt("r")),
                new Assign(ref("r"), binary(ref("a"), BinaryOperator.DIVIDE, ref("b"))));

        assertMarker(firstAssignedExpression(applyStrict(input)), EmulationInsertionPass.INT_DIV_MARKER);
    }

    @Test
    void rewritesDeclarationInitializerLikeItsMirroredAssign() {
        // StatementLowerer mirrors every initialized local with an ordered Assign; the emitters
        // recognize the pair only when both carry the same rewritten expression.
        BinaryOpExpression division = binary(ref("a"), BinaryOperator.DIVIDE, intLiteral(2));
        Block input = new Block(
                List.of(declareInt("a"), new DeclareVariable("r", new TIntType(), false, division)),
                List.of(new Assign(ref("r"), division)),
                List.of());

        Block rewritten = applyDefault(input);
        DeclareVariable declaration = assertInstanceOf(DeclareVariable.class, rewritten.declarations().get(1));
        ExpressionNode initializer = declaration.initializer();
        assertMarker(initializer, EmulationInsertionPass.INT_DIV_MARKER);
        assertEquals(initializer, firstAssignedExpression(rewritten));
    }
}
