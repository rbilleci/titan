package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * First test suite for {@link NullAnalysisPass} (audit F-12: the designated null-safety pass had
 * zero tests). Covers scope tracking (nested blocks, shadowing, scope end), the statement kinds
 * the completed pass now analyzes (loops, for-each/range, try/catch/finally, call/raise
 * arguments), descent into function-call arguments, E003 emission (positive and negative), and
 * the compareTo marker rewrite with its null guards (N2).
 */
class NullAnalysisPassTest {

    private static final String LOCATION = "Fixture.java:7";

    // ------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------

    private static DeclareVariable declare(String name, boolean nullable) {
        return new DeclareVariable(name, new TIntType(), nullable, null);
    }

    private static VariableRefExpression ref(String name) {
        return new VariableRefExpression(name);
    }

    private static LiteralExpression intLiteral(int value) {
        return new LiteralExpression(value, new TIntType());
    }

    private static LiteralExpression nullLiteral() {
        return new LiteralExpression(null, new TIntType());
    }

    private static BinaryOpExpression plus(ExpressionNode left, ExpressionNode right) {
        return new BinaryOpExpression(left, BinaryOperator.ADD, right);
    }

    private static Assign assign(String target, ExpressionNode expression) {
        return new Assign(ref(target), expression);
    }

    private static Block block(List<DeclarationNode> declarations, StatementNode... statements) {
        return new Block(declarations, List.of(statements), List.of());
    }

    private static FunctionCallExpression compareToMarker(ExpressionNode receiver, ExpressionNode other) {
        return new FunctionCallExpression(NullAnalysisPass.COMPARE_TO_MARKER, List.of(receiver, other), null);
    }

    private static Block analyze(Block input) {
        return new NullAnalysisPass(LOCATION).analyze(input);
    }

    // ------------------------------------------------------------------
    // Scope tracking
    // ------------------------------------------------------------------

    @Test
    void tracksDeclarationsOfNestedBlocks() {
        // Audit F-12: only the root block's declarations were mapped. A non-null declaration in
        // a nested block must suppress the guard for arithmetic inside that block.
        Block nested = block(
                List.of(declare("inner", false), declare("result", false)),
                assign("inner", intLiteral(1)),
                assign("result", plus(ref("inner"), intLiteral(1))));
        Block rewritten = analyze(block(List.of(), nested));

        Block analyzedNested = assertInstanceOf(Block.class, rewritten.statements().getFirst());
        assertEquals(2, analyzedNested.statements().size());
        assertInstanceOf(Assign.class, analyzedNested.statements().get(0));
        assertInstanceOf(Assign.class, analyzedNested.statements().get(1));
    }

    @Test
    void nestedNullableDeclarationStillGetsGuarded() {
        Block nested = block(
                List.of(declare("inner", true), declare("result", false)),
                assign("result", plus(ref("inner"), intLiteral(1))));
        Block rewritten = analyze(block(List.of(), nested));

        Block analyzedNested = assertInstanceOf(Block.class, rewritten.statements().getFirst());
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, analyzedNested.statements().getFirst());
        assertEquals("inner", guard.variableName());
        assertEquals(LOCATION, guard.sourceLocation());
    }

    @Test
    void declarationScopeEndsWithItsBlock() {
        // 'inner' is non-null inside the nested block; outside, the name is unknown again and
        // the pass must fall back to its conservative (guarded) default.
        Block nested = block(
                List.of(declare("inner", false)),
                assign("inner", intLiteral(1)));
        Block root = block(
                List.of(declare("result", false)),
                nested,
                assign("result", plus(ref("inner"), intLiteral(1))));
        Block rewritten = analyze(root);

        assertEquals(3, rewritten.statements().size());
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().get(1));
        assertEquals("inner", guard.variableName());
    }

    @Test
    void shadowingDeclarationDoesNotLeakIntoOuterScope() {
        // The nested block shadows non-null 'x' with a nullable 'x'; arithmetic inside the
        // nested block is guarded, while the outer 'x' stays non-null after the block
        // (no guard, no E003).
        Block nested = block(
                List.of(declare("x", true), declare("innerResult", false)),
                assign("innerResult", plus(ref("x"), intLiteral(1))));
        Block root = block(
                List.of(declare("x", false), declare("result", false)),
                assign("x", intLiteral(3)),
                nested,
                assign("result", plus(ref("x"), intLiteral(1))));
        Block rewritten = analyze(root);

        Block analyzedNested = assertInstanceOf(Block.class, rewritten.statements().get(1));
        NullGuardStatement innerGuard = assertInstanceOf(NullGuardStatement.class, analyzedNested.statements().getFirst());
        assertEquals("x", innerGuard.variableName());

        // Outer arithmetic right after the block: no guard for the outer x.
        assertInstanceOf(Assign.class, rewritten.statements().get(2));
        assertEquals(3, rewritten.statements().size());
    }

    @Test
    void branchAssignmentsJoinConservatively() {
        // x is non-null before the IF; one branch makes it null. After the IF it may be null on
        // some path, so the later arithmetic gets a runtime guard (and no E003 — not definite).
        Block root = block(
                List.of(declare("x", false), declare("flag", true), declare("result", false)),
                assign("x", intLiteral(1)),
                new IfStatement(
                        ref("flag"),
                        block(List.of(), assign("x", nullLiteral())),
                        List.of(),
                        null),
                assign("result", plus(ref("x"), intLiteral(1))));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(
                NullGuardStatement.class,
                rewritten.statements().get(rewritten.statements().size() - 2));
        assertEquals("x", guard.variableName());
    }

    // ------------------------------------------------------------------
    // Newly covered statement kinds
    // ------------------------------------------------------------------

    @Test
    void analyzesLoopStatementBodyAndExitCondition() {
        // Audit F-12: LoopStatement (do-while) passed through unanalyzed.
        Block body = block(
                List.of(),
                assign("result", plus(ref("step"), intLiteral(1))));
        Block root = block(
                List.of(declare("result", false), declare("step", true)),
                assign("result", intLiteral(0)),
                new LoopStatement(body, ref("exitFlag"), null));
        Block rewritten = analyze(root);

        LoopStatement loop = assertInstanceOf(LoopStatement.class, rewritten.statements().get(1));
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, loop.body().statements().getFirst());
        assertEquals("step", guard.variableName());
        // exitFlag is unknown, therefore conservatively nullable: COALESCE(exitFlag, FALSE).
        CoalesceExpression exit = assertInstanceOf(CoalesceExpression.class, loop.exitCondition());
        assertEquals("exitFlag", assertInstanceOf(VariableRefExpression.class, exit.expressions().getFirst()).name());
    }

    @Test
    void loopCarriedNullabilityReachesFixpoint() {
        // total is non-null on entry but assigned null inside the body, so the second iteration
        // unboxes a possibly-null value: the fixpoint must produce a guard that a single
        // entry-state analysis would miss.
        Block body = block(
                List.of(),
                assign("result", plus(ref("total"), intLiteral(1))),
                assign("total", nullLiteral()));
        Block root = block(
                List.of(declare("total", false), declare("result", false)),
                assign("total", intLiteral(1)),
                new WhileStatement(ref("go"), body, null));
        Block rewritten = analyze(root);

        WhileStatement loop = assertInstanceOf(WhileStatement.class, rewritten.statements().get(1));
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, loop.body().statements().getFirst());
        assertEquals("total", guard.variableName());
    }

    @Test
    void analyzesForEachIterableAndBody() {
        Block body = block(
                List.of(),
                assign("result", plus(ref("element"), intLiteral(1))));
        Block root = block(
                List.of(declare("element", true), declare("result", false)),
                assign("result", intLiteral(0)),
                new ForEachStatement("element", new TIntType(), ref("items"), body, null));
        Block rewritten = analyze(root);

        ForEachStatement forEach = assertInstanceOf(ForEachStatement.class, rewritten.statements().get(1));
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, forEach.body().statements().getFirst());
        assertEquals("element", guard.variableName());
    }

    @Test
    void analyzesForRangeBoundsAndTreatsRangeVariableAsNonNull() {
        Block body = block(
                List.of(),
                assign("total", plus(ref("total"), ref("i"))));
        Block root = block(
                List.of(declare("i", false), declare("total", false), declare("upTo", true)),
                assign("total", intLiteral(0)),
                new ForRangeStatement("i", intLiteral(1), plus(ref("upTo"), intLiteral(-1)), body, null));
        Block rewritten = analyze(root);

        // The arithmetic upper bound uses nullable upTo: guarded ahead of the range statement.
        NullGuardStatement boundGuard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().get(1));
        assertEquals("upTo", boundGuard.variableName());
        ForRangeStatement forRange = assertInstanceOf(ForRangeStatement.class, rewritten.statements().get(2));
        // i is the range variable: non-null inside the body, so no guard there.
        assertInstanceOf(Assign.class, forRange.body().statements().getFirst());
    }

    @Test
    void analyzesTryCatchFinallyBlocks() {
        // Audit F-12: try/catch/finally passed through unanalyzed.
        Block tryBlock = block(List.of(), assign("a", plus(ref("a"), intLiteral(1))));
        Block catchBlock = block(List.of(), assign("a", plus(ref("a"), intLiteral(2))));
        Block finallyBlock = block(List.of(), assign("a", plus(ref("a"), intLiteral(3))));
        Block root = block(
                List.of(declare("a", true)),
                new TryCatchFinallyStatement(
                        tryBlock,
                        List.of(new CatchClause("error", "RuntimeException", List.of("45000"), catchBlock)),
                        finallyBlock));
        Block rewritten = analyze(root);

        TryCatchFinallyStatement tryCatch = assertInstanceOf(TryCatchFinallyStatement.class, rewritten.statements().getFirst());
        assertInstanceOf(NullGuardStatement.class, tryCatch.tryBlock().statements().getFirst());
        assertInstanceOf(NullGuardStatement.class, tryCatch.catches().getFirst().body().statements().getFirst());
        assertInstanceOf(NullGuardStatement.class, tryCatch.finallyBlock().statements().getFirst());
    }

    @Test
    void analyzesCallStatementArguments() {
        // Audit F-12: CallStatement arguments passed through unanalyzed.
        Block root = block(
                List.of(declare("amount", true)),
                new CallStatement("apply_charge", List.of(plus(ref("amount"), intLiteral(1)))));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("amount", guard.variableName());
        assertInstanceOf(CallStatement.class, rewritten.statements().get(1));
    }

    @Test
    void analyzesRaiseStatementMessageExpression() {
        // Plan 2.2 made RaiseStatement.message an ExpressionNode; the pass must descend into it.
        Block root = block(
                List.of(declare("count", true)),
                new RaiseStatement(
                        "45000",
                        new FunctionCallExpression(
                                "__titan_str_concat",
                                List.of(
                                        new LiteralExpression("count: ", new TTextType()),
                                        plus(ref("count"), intLiteral(1))),
                                null),
                        List.of()));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("count", guard.variableName());
        assertInstanceOf(RaiseStatement.class, rewritten.statements().get(1));
    }

    // ------------------------------------------------------------------
    // Expression descent
    // ------------------------------------------------------------------

    @Test
    void descendsIntoFunctionCallArgumentsForGuards() {
        // Audit F-12: nullable values buried in function arguments never got guarded.
        Block root = block(
                List.of(declare("score", true), declare("result", false)),
                assign("result", new FunctionCallExpression(
                        "GREATEST",
                        List.of(plus(ref("score"), intLiteral(10)), intLiteral(0)),
                        null)));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("score", guard.variableName());
    }

    @Test
    void nullableConditionBuriedInFunctionArgsGetsCoalesceWrapped() {
        Block root = block(
                List.of(declare("flag", true)),
                new IfStatement(
                        new NotExpression(new FunctionCallExpression("__titan_str_contains",
                                List.of(ref("text"), ref("needle")), null)),
                        block(List.of(), assign("flag", intLiteral(1))),
                        List.of(),
                        null));
        Block rewritten = analyze(root);

        IfStatement ifStatement = assertInstanceOf(IfStatement.class, rewritten.statements().getFirst());
        CoalesceExpression wrapped = assertInstanceOf(CoalesceExpression.class, ifStatement.condition());
        assertInstanceOf(NotExpression.class, wrapped.expressions().getFirst());
        assertEquals(false, assertInstanceOf(LiteralExpression.class, wrapped.expressions().get(1)).value());
    }

    // ------------------------------------------------------------------
    // compareTo marker (N2)
    // ------------------------------------------------------------------

    @Test
    void rewritesCompareToMarkerWithGuardsForNullableVariableOperands() {
        Block root = block(
                List.of(declare("a", true), declare("b", false), declare("cmp", false)),
                assign("b", intLiteral(5)),
                assign("cmp", compareToMarker(ref("a"), ref("b"))));
        Block rewritten = analyze(root);

        // Guard only for the nullable receiver; b is provably non-null.
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().get(1));
        assertEquals("a", guard.variableName());
        assertEquals(LOCATION, guard.sourceLocation());

        Assign cmp = assertInstanceOf(Assign.class, rewritten.statements().get(2));
        CaseWhenExpression rewrittenCase = assertInstanceOf(CaseWhenExpression.class, cmp.expression());
        assertEquals(3, rewrittenCase.conditions().size());
        assertEquals(BinaryOperator.LESS_THAN,
                assertInstanceOf(BinaryOpExpression.class, rewrittenCase.conditions().get(0).condition()).operator());
        assertEquals(-1, assertInstanceOf(LiteralExpression.class, rewrittenCase.conditions().get(0).value()).value());
        assertEquals(BinaryOperator.GREATER_THAN,
                assertInstanceOf(BinaryOpExpression.class, rewrittenCase.conditions().get(1).condition()).operator());
        assertEquals(1, assertInstanceOf(LiteralExpression.class, rewrittenCase.conditions().get(1).value()).value());
        assertEquals(BinaryOperator.EQUAL,
                assertInstanceOf(BinaryOpExpression.class, rewrittenCase.conditions().get(2).condition()).operator());
        assertEquals(0, assertInstanceOf(LiteralExpression.class, rewrittenCase.conditions().get(2).value()).value());
        // N2: no ELSE branch — a null operand that escaped guarding yields NULL, never a
        // silent "equal".
        assertNull(rewrittenCase.elseValue());
    }

    @Test
    void rewritesCompareToMarkerInsideConditionAndGuardsBeforeTheIf() {
        Block root = block(
                List.of(declare("a", true), declare("hit", false)),
                new IfStatement(
                        new BinaryOpExpression(
                                compareToMarker(ref("a"), intLiteral(3)),
                                BinaryOperator.GREATER_THAN,
                                intLiteral(0)),
                        block(List.of(), assign("hit", intLiteral(1))),
                        List.of(),
                        null));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("a", guard.variableName());
        IfStatement ifStatement = assertInstanceOf(IfStatement.class, rewritten.statements().get(1));
        // The ELSE-less CASE makes the comparison nullable, so the condition is coalesced.
        CoalesceExpression wrapped = assertInstanceOf(CoalesceExpression.class, ifStatement.condition());
        BinaryOpExpression comparison = assertInstanceOf(BinaryOpExpression.class, wrapped.expressions().getFirst());
        assertInstanceOf(CaseWhenExpression.class, comparison.left());
    }

    @Test
    void rewritesCompareToMarkerInsideSqlNodesAndGuardsBeforeTheStatement() {
        // The DSL builds queries eagerly in Java, so a compareTo inside a WHERE clause NPEs at
        // build time; the guard goes ahead of the SQL statement.
        SelectSql select = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                "accounts",
                List.of(),
                new BinaryOpExpression(
                        new ColumnRefExpression("accounts", "rank"),
                        BinaryOperator.EQUAL,
                        compareToMarker(ref("a"), intLiteral(1))),
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                null);
        Block root = block(
                List.of(declare("a", true)),
                new ExecuteSqlStatement(select));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("a", guard.variableName());
        ExecuteSqlStatement execute = assertInstanceOf(ExecuteSqlStatement.class, rewritten.statements().get(1));
        SelectSql rewrittenSelect = assertInstanceOf(SelectSql.class, execute.sqlNode());
        // The marker inside the WHERE clause is rewritten to the comparison CASE.
        CoalesceExpression where = assertInstanceOf(CoalesceExpression.class, rewrittenSelect.where());
        BinaryOpExpression predicate = assertInstanceOf(BinaryOpExpression.class, where.expressions().getFirst());
        assertInstanceOf(CaseWhenExpression.class, predicate.right());
    }

    @Test
    void doesNotGuardShortCircuitedCompareToOperands() {
        // The a != null && a.compareTo(b) > 0 idiom: Java short-circuits, so a hoisted guard
        // would raise where Java never evaluates the comparison. The marker still rewrites to
        // the ELSE-less CASE (NULL, coalesced to FALSE — exact Java parity for this idiom).
        Block root = block(
                List.of(declare("a", true), declare("hit", false)),
                new IfStatement(
                        new BinaryOpExpression(
                                new IsNotNullExpression(ref("a")),
                                BinaryOperator.AND,
                                new BinaryOpExpression(
                                        compareToMarker(ref("a"), intLiteral(3)),
                                        BinaryOperator.GREATER_THAN,
                                        intLiteral(0))),
                        block(List.of(), assign("hit", intLiteral(1))),
                        List.of(),
                        null));
        Block rewritten = analyze(root);

        IfStatement ifStatement = assertInstanceOf(IfStatement.class, rewritten.statements().getFirst());
        assertEquals(1, rewritten.statements().size());
        // The marker is still rewritten inside the condition.
        CoalesceExpression wrapped = assertInstanceOf(CoalesceExpression.class, ifStatement.condition());
        BinaryOpExpression and = assertInstanceOf(BinaryOpExpression.class, wrapped.expressions().getFirst());
        BinaryOpExpression comparison = assertInstanceOf(BinaryOpExpression.class, and.right());
        assertInstanceOf(CaseWhenExpression.class, comparison.left());
    }

    @Test
    void doesNotGuardConditionallyEvaluatedTernaryArms() {
        // y = flag ? maybe + 1 : 0 — Java evaluates the arm only when the condition holds, so
        // no hoisted unboxing guard (which would raise on the flag == false path).
        Block root = block(
                List.of(declare("maybe", true), declare("flag", true), declare("y", false)),
                assign("y", new CaseWhenExpression(
                        List.of(new CaseBranch(ref("flag"), plus(ref("maybe"), intLiteral(1)))),
                        intLiteral(0))));
        Block rewritten = analyze(root);

        assertEquals(1, rewritten.statements().size());
        assertInstanceOf(Assign.class, rewritten.statements().getFirst());
    }

    @Test
    void guardLocationFallsBackToUnknownWithoutEntryPointContext() {
        Block root = block(
                List.of(declare("a", true), declare("r", false)),
                assign("r", plus(ref("a"), intLiteral(1))));
        Block rewritten = new NullAnalysisPass().analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("unknown", guard.sourceLocation());
    }

    // ------------------------------------------------------------------
    // E003: provable null dereferences
    // ------------------------------------------------------------------

    @Test
    void emitsE003ForDefinitelyNullVariableInArithmetic() {
        Block root = block(
                List.of(declare("ghost", true), declare("total", false)),
                assign("ghost", nullLiteral()),
                assign("total", plus(ref("ghost"), intLiteral(1))));

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class, () -> analyze(root));
        assertEquals(1, exception.diagnostics().size());
        assertEquals("TITAN-E003", exception.diagnostics().getFirst().code().code());
        assertTrue(exception.getMessage().contains("ghost"));
        assertTrue(exception.getMessage().contains("arithmetic"));
    }

    @Test
    void emitsE003ForDefinitelyNullCompareToOperand() {
        Block root = block(
                List.of(declare("ghost", true), declare("cmp", false)),
                assign("ghost", nullLiteral()),
                assign("cmp", compareToMarker(ref("ghost"), intLiteral(5))));

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class, () -> analyze(root));
        assertEquals("TITAN-E003", exception.diagnostics().getFirst().code().code());
        assertTrue(exception.getMessage().contains("compareTo"));
    }

    @Test
    void emitsE003ForNullLiteralCompareToArgument() {
        Block root = block(
                List.of(declare("a", false), declare("cmp", false)),
                assign("a", intLiteral(1)),
                assign("cmp", compareToMarker(ref("a"), nullLiteral())));

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class, () -> analyze(root));
        assertEquals("TITAN-E003", exception.diagnostics().getFirst().code().code());
        assertTrue(exception.getMessage().contains("null literal"));
    }

    @Test
    void emitsE003ForDefinitelyNullBooleanCondition() {
        Block root = block(
                List.of(declare("flag", true), declare("r", false)),
                assign("flag", nullLiteral()),
                new IfStatement(
                        ref("flag"),
                        block(List.of(), assign("r", intLiteral(1))),
                        List.of(),
                        null));

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class, () -> analyze(root));
        assertEquals("TITAN-E003", exception.diagnostics().getFirst().code().code());
        assertTrue(exception.getMessage().contains("boolean condition"));
    }

    @Test
    void doesNotEmitE003ForMerelyPossiblyNullVariables() {
        // Conservative emission contract: possibly-null gets a runtime guard, never a
        // compile-time error.
        Block root = block(
                List.of(declare("maybe", true), declare("total", false)),
                assign("total", plus(ref("maybe"), intLiteral(1))));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, rewritten.statements().getFirst());
        assertEquals("maybe", guard.variableName());
    }

    @Test
    void doesNotEmitE003AfterReassignmentToNonNull() {
        Block root = block(
                List.of(declare("ghost", true), declare("total", false)),
                assign("ghost", nullLiteral()),
                assign("ghost", intLiteral(4)),
                assign("total", plus(ref("ghost"), intLiteral(1))));
        Block rewritten = analyze(root);

        // Neither an E003 nor a guard: ghost is provably non-null at the use.
        assertEquals(3, rewritten.statements().size());
        rewritten.statements().forEach(statement -> assertInstanceOf(Assign.class, statement));
    }

    @Test
    void doesNotEmitE003WhenNullOnlyOnOneBranch() {
        // Definite-only contract: null on one branch joins to possibly-null, which guards at
        // runtime instead of failing the build.
        Block root = block(
                List.of(declare("x", false), declare("flag", true), declare("total", false)),
                assign("x", intLiteral(1)),
                new IfStatement(
                        ref("flag"),
                        block(List.of(), assign("x", nullLiteral())),
                        List.of(),
                        null),
                assign("total", plus(ref("x"), intLiteral(1))));
        Block rewritten = analyze(root);

        NullGuardStatement guard = assertInstanceOf(
                NullGuardStatement.class,
                rewritten.statements().get(rewritten.statements().size() - 2));
        assertEquals("x", guard.variableName());
    }

    @Test
    void deduplicatesE003DiagnosticsAcrossLoopFixpointIterations() {
        Block body = block(
                List.of(),
                assign("total", plus(ref("ghost"), intLiteral(1))));
        Block root = block(
                List.of(declare("ghost", true), declare("total", false), declare("go", true)),
                assign("ghost", nullLiteral()),
                new WhileStatement(ref("go"), body, null));

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class, () -> analyze(root));
        assertEquals(1, exception.diagnostics().size());
    }
}
