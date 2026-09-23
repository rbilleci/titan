package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaToTirLowererTest {

    @TempDir
    Path tempDir;

    private static ExpressionNode initializer(Block block, String variableName) {
        return block.statements().stream()
                .filter(Assign.class::isInstance)
                .map(Assign.class::cast)
                .filter(assign -> assign.target() instanceof VariableRefExpression ref && variableName.equals(ref.name()))
                .map(Assign::expression)
                .findFirst()
                .orElseThrow();
    }

    private static <T extends ExpressionNode> T initializer(Block block, String variableName, Class<T> type) {
        return assertInstanceOf(type, initializer(block, variableName));
    }

    @Test
    void lowersDuplicateProjectionColumnsWithoutPruning() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDuplicateProjectionColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDuplicateProjectionColumns {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(3, select.columns().size());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression()).column());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression()).column());
        assertEquals("email", assertInstanceOf(ColumnRefExpression.class, select.columns().get(2).expression()).column());
    }

    @Test
    void lowersDuplicateProjectionColumnsFromInlineViewWithoutPruning() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDuplicateInlineViewProjectionColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDuplicateInlineViewProjectionColumns {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        InlineView<Object> activeAccounts = defineInlineView(
                                select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true))
                        );
                        Column<Integer> activeId = activeAccounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = activeAccounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        select(activeId, activeId, activeEmail)
                                .from(activeAccounts)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getLast());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(3, select.columns().size());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression()).column());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression()).column());
        assertEquals("email", assertInstanceOf(ColumnRefExpression.class, select.columns().get(2).expression()).column());
    }

    @Test
    void lowersDuplicateProjectionColumnsFromNamedCteWithoutPruning() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDuplicateNamedCteProjectionColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDuplicateNamedCteProjectionColumns {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> activeAccounts = DSL.name("active_accounts")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = activeAccounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = activeAccounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        DSL.with(activeAccounts)
                                .select(activeId, activeId, activeEmail)
                                .from(activeAccounts)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getLast());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(3, select.columns().size());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression()).column());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression()).column());
        assertEquals("email", assertInstanceOf(ColumnRefExpression.class, select.columns().get(2).expression()).column());
    }

    @Test
    void lowersDuplicateAliasedProjectionColumnsFromNamedCteWithoutNormalization() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDuplicateAliasedNamedCteProjectionColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDuplicateAliasedNamedCteProjectionColumns {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> activeAccounts = DSL.name("active_accounts")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = activeAccounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = activeAccounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        DSL.with(activeAccounts)
                                .select(activeId.as("primary_id"), activeId.as("duplicate_id"), activeEmail.as("contact_email"))
                                .from(activeAccounts)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getLast());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(3, select.columns().size());
        assertEquals("primary_id", select.columns().get(0).alias());
        assertEquals("duplicate_id", select.columns().get(1).alias());
        assertEquals("contact_email", select.columns().get(2).alias());
    }


    @Test
    void lowersIfWhileForAndReturnIntoTir() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSample.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringSample {
                    @StoredProcedure
                    public static void run(int start) {
                        int total = start;
                        if (total < 10) {
                            total = total + 1;
                        } else {
                            total = total - 1;
                        }

                        for (int i = 0; i < 3; i++) {
                            total += i;
                        }

                        while (total < 100) {
                            total = total + 10;
                        }

                        return;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        JavaToTirLowerer lowerer = new JavaToTirLowerer();
        Map<String, Block> lowered = lowerer.lower(parsed, entryPoints);

        assertEquals(1, lowered.size());
        Block block = lowered.values().iterator().next();
        assertEquals(1, block.declarations().size());
        assertEquals("total", ((DeclareVariable) block.declarations().getFirst()).name());

        assertTrue(block.statements().stream().anyMatch(IfStatement.class::isInstance));
        assertTrue(block.statements().stream().anyMatch(WhileStatement.class::isInstance));
        assertTrue(block.statements().stream().anyMatch(ReturnStatement.class::isInstance));

        // The counting for-loop lowers into a nested Block holding the loop-variable
        // declaration plus a ForRangeStatement with folded inclusive bounds (Phase 2.1).
        Block forLowered = block.statements().stream()
                .filter(Block.class::isInstance)
                .map(Block.class::cast)
                .findFirst()
                .orElseThrow();
        assertNotNull(forLowered);
        assertEquals(1, forLowered.declarations().size());
        assertEquals("i", ((DeclareVariable) forLowered.declarations().getFirst()).name());
        assertEquals(1, forLowered.statements().size());
        ForRangeStatement forRange = assertInstanceOf(ForRangeStatement.class, forLowered.statements().getFirst());
        assertEquals("i", forRange.variableName());
        assertEquals(0, assertInstanceOf(LiteralExpression.class, forRange.start()).value());
        assertEquals(2, assertInstanceOf(LiteralExpression.class, forRange.end()).value());
        assertNull(forRange.label());
    }

    @Test
    void lowersDoWhileLoopBindingConditionToOneTempPerIteration() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDoWhile.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDoWhile {
                    @StoredProcedure
                    public static void run(int total) {
                        do {
                            total = total - 1;
                        } while (total > 0);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        // do-while lowers to a wrapper block declaring the condition temp and the
        // first-iteration flag (deterministic source-position suffixes, audit D12).
        Block doWhile = assertInstanceOf(Block.class, block.statements().getFirst());
        assertEquals(2, doWhile.declarations().size());
        DeclareVariable conditionTemp = assertInstanceOf(DeclareVariable.class, doWhile.declarations().get(0));
        DeclareVariable firstFlag = assertInstanceOf(DeclareVariable.class, doWhile.declarations().get(1));
        assertTrue(conditionTemp.name().startsWith("__titan_dowhile_cond_"), conditionTemp.name());
        assertTrue(firstFlag.name().startsWith("__titan_dowhile_first_"), firstFlag.name());
        assertInstanceOf(TBooleanType.class, conditionTemp.type());
        assertInstanceOf(TBooleanType.class, firstFlag.type());

        assertEquals(2, doWhile.statements().size());
        Assign firstInit = assertInstanceOf(Assign.class, doWhile.statements().getFirst());
        assertEquals(firstFlag.name(), assertInstanceOf(VariableRefExpression.class, firstInit.target()).name());

        LoopStatement loop = assertInstanceOf(LoopStatement.class, doWhile.statements().get(1));
        assertNull(loop.exitCondition());

        // The loop body starts with the iteration gate: IF first THEN first := false
        // ELSE temp := cond; IF temp IS NULL OR temp = false THEN break.
        IfStatement gate = assertInstanceOf(IfStatement.class, loop.body().statements().getFirst());
        assertEquals(firstFlag.name(), assertInstanceOf(VariableRefExpression.class, gate.condition()).name());

        Block elseBlock = gate.elseBlock();
        assertNotNull(elseBlock);
        Assign conditionBinding = assertInstanceOf(Assign.class, elseBlock.statements().get(0));
        assertEquals(conditionTemp.name(), assertInstanceOf(VariableRefExpression.class, conditionBinding.target()).name());
        BinaryOpExpression loweredCondition = assertInstanceOf(BinaryOpExpression.class, conditionBinding.expression());
        assertEquals(BinaryOperator.GREATER_THAN, loweredCondition.operator());

        IfStatement exitCheck = assertInstanceOf(IfStatement.class, elseBlock.statements().get(1));
        // The exit test references the nullable condition temp, so the completed null pass
        // (plan 2.3) wraps it in COALESCE(..., FALSE) like every other nullable condition.
        CoalesceExpression coalescedExit = assertInstanceOf(CoalesceExpression.class, exitCheck.condition());
        BinaryOpExpression exitCondition = assertInstanceOf(BinaryOpExpression.class, coalescedExit.expressions().getFirst());
        assertEquals(BinaryOperator.OR, exitCondition.operator());
        // Both exit tests reference the temp, not a re-lowered copy of the condition.
        IsNullExpression nullCheck = assertInstanceOf(IsNullExpression.class, exitCondition.left());
        assertEquals(conditionTemp.name(), assertInstanceOf(VariableRefExpression.class, nullCheck.expression()).name());
        BinaryOpExpression falseCheck = assertInstanceOf(BinaryOpExpression.class, exitCondition.right());
        assertEquals(BinaryOperator.EQUAL, falseCheck.operator());
        assertEquals(conditionTemp.name(), assertInstanceOf(VariableRefExpression.class, falseCheck.left()).name());
        assertEquals(false, assertInstanceOf(LiteralExpression.class, falseCheck.right()).value());
        BreakStatement breakStatement = assertInstanceOf(BreakStatement.class, exitCheck.thenBlock().statements().getFirst());
        assertNull(breakStatement.label());

        // The original body follows the gate. The completed null pass (plan 2.3) now analyzes
        // LoopStatement bodies, so the arithmetic on the unmodeled (conservatively nullable)
        // parameter gets its unboxing guard ahead of the assignment.
        NullGuardStatement bodyGuard = assertInstanceOf(NullGuardStatement.class, loop.body().statements().get(1));
        assertEquals("total", bodyGuard.variableName());
        assertInstanceOf(Assign.class, loop.body().statements().get(2));
    }

    @Test
    void lowersUnlabeledBreakAndContinueToTirStatements() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringBreakContinue.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringBreakContinue {
                    @StoredProcedure
                    public static void run(int input) {
                        while (input > 0) {
                            input = input - 1;
                            if (input == 3) {
                                continue;
                            }
                            if (input == 7) {
                                break;
                            }
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        WhileStatement whileStatement = block.statements().stream()
                .filter(WhileStatement.class::isInstance)
                .map(WhileStatement.class::cast)
                .findFirst()
                .orElseThrow();

        List<IfStatement> guards = whileStatement.body().statements().stream()
                .filter(IfStatement.class::isInstance)
                .map(IfStatement.class::cast)
                .toList();
        assertEquals(2, guards.size());

        ContinueStatement continueStatement =
                assertInstanceOf(ContinueStatement.class, guards.get(0).thenBlock().statements().getFirst());
        assertNull(continueStatement.label());

        BreakStatement breakStatement =
                assertInstanceOf(BreakStatement.class, guards.get(1).thenBlock().statements().getFirst());
        assertNull(breakStatement.label());
    }

    @Test
    void lowersCountingForLoopWithVariableBoundIntoForRangeStatement() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringCountingFor.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringCountingFor {
                    @StoredFunction
                    public static int run(int from, int upTo) {
                        int sum = 0;
                        for (int i = from; i < upTo; i++) {
                            sum = sum + i;
                        }
                        for (int j = from; j <= upTo; j += 1) {
                            sum = sum + j;
                        }
                        return sum;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        // The completed null pass (plan 2.3) analyzes ForRangeStatement bounds, so the arithmetic
        // exclusive bound (upTo - 1) gets a NullGuardStatement ahead of the range statement.
        List<ForRangeStatement> ranges = block.statements().stream()
                .filter(Block.class::isInstance)
                .map(Block.class::cast)
                .flatMap(nested -> nested.statements().stream())
                .filter(ForRangeStatement.class::isInstance)
                .map(ForRangeStatement.class::cast)
                .toList();
        assertEquals(2, ranges.size());

        // i < upTo: exclusive bound becomes (upTo - 1).
        ForRangeStatement exclusive = ranges.get(0);
        assertEquals("i", exclusive.variableName());
        assertEquals("from", assertInstanceOf(VariableRefExpression.class, exclusive.start()).name());
        BinaryOpExpression minusOne = assertInstanceOf(BinaryOpExpression.class, exclusive.end());
        assertEquals(BinaryOperator.SUBTRACT, minusOne.operator());
        assertEquals("upTo", assertInstanceOf(VariableRefExpression.class, minusOne.left()).name());
        assertEquals(1, assertInstanceOf(LiteralExpression.class, minusOne.right()).value());

        // j <= upTo (with j += 1): inclusive bound stays as-is.
        ForRangeStatement inclusive = ranges.get(1);
        assertEquals("j", inclusive.variableName());
        assertEquals("upTo", assertInstanceOf(VariableRefExpression.class, inclusive.end()).name());
    }

    @Test
    void keepsWhileDesugarForNonCountingForLoops() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringNonCountingFor.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringNonCountingFor {
                    @StoredFunction
                    public static int run(int limit) {
                        int sum = 0;
                        // Stride 2: not a unit increment.
                        for (int i = 0; i < limit; i += 2) {
                            sum = sum + i;
                        }
                        // Loop variable reassigned in the body.
                        for (int j = 0; j < limit; j++) {
                            j = j + 1;
                            sum = sum + j;
                        }
                        // Bound mutated in the body: PG FOR would freeze it on entry.
                        for (int k = 0; k < limit; k++) {
                            limit = limit - 1;
                            sum = sum + k;
                        }
                        // Bound is a call: not provably loop-invariant.
                        for (int m = 0; m < "abc".length(); m++) {
                            sum = sum + m;
                        }
                        return sum;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        List<Block> loweredForLoops = block.statements().stream()
                .filter(Block.class::isInstance)
                .map(Block.class::cast)
                .toList();
        assertEquals(4, loweredForLoops.size());
        for (Block loweredForLoop : loweredForLoops) {
            assertTrue(loweredForLoop.statements().stream().anyMatch(WhileStatement.class::isInstance),
                    "expected while-desugar, got: " + loweredForLoop.statements());
            assertTrue(loweredForLoop.statements().stream().noneMatch(ForRangeStatement.class::isInstance));
        }
    }

    @Test
    void rejectsLabeledBreakAndContinueWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringLabeledBreak.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringLabeledBreak {
                    @StoredProcedure
                    public static void run(int input) {
                        outer:
                        while (input > 0) {
                            while (input > 1) {
                                break outer;
                            }
                            input = input - 1;
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        JavaToTirLowerer lowerer = new JavaToTirLowerer();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> lowerer.lower(parsed, entryPoints));
        // The enclosing labeled statement is reached before the labeled break itself; both
        // forms are rejected with positioned diagnostics (the validator additionally rejects
        // the labeled break/continue directly).
        assertTrue(exception.getMessage().contains("labeled statement"), exception.getMessage());
        assertTrue(exception.getMessage().contains("LoweringLabeledBreak.java"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Suggestion:"), exception.getMessage());
    }

    @Test
    void lowersEnhancedForLoopIntoForEachStatement() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringEnhancedFor.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringEnhancedFor {
                    @StoredProcedure
                    public static void run(int[] values) {
                        for (int value : values) {
                            helper(value);
                        }
                    }

                    static void helper(int value) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        Block foreachLowered = assertInstanceOf(Block.class, block.statements().getFirst());
        assertEquals(1, foreachLowered.declarations().size());
        DeclareVariable iterationVar = assertInstanceOf(DeclareVariable.class, foreachLowered.declarations().getFirst());
        assertEquals("value", iterationVar.name());
        assertInstanceOf(TIntType.class, iterationVar.type());

        assertEquals(1, foreachLowered.statements().size());
        ForEachStatement forEach = assertInstanceOf(ForEachStatement.class, foreachLowered.statements().getFirst());
        assertEquals("value", forEach.variableName());
        assertInstanceOf(VariableRefExpression.class, forEach.iterable());
        assertEquals("values", ((VariableRefExpression) forEach.iterable()).name());
        assertEquals(1, forEach.body().statements().size());
        assertInstanceOf(CallStatement.class, forEach.body().statements().getFirst());
    }


    @Test
    void rejectsEnhancedForLoopWithIncompatibleIterationVariableType() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringEnhancedForMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringEnhancedForMismatch {
                    @StoredProcedure
                    public static void run(int[] values) {
                        for (String value : values) {
                            System.out.println(value);
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Enhanced for-loop variable type"));
        assertTrue(thrown.getMessage().contains("not compatible"));
    }

    @Test
    void lowersMethodInvocationExpressionStatementAsCall() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringCalls.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringCalls {
                    @StoredProcedure
                    public static void run() {
                        helper(42);
                    }

                    static void helper(int value) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());
        CallStatement call = assertInstanceOf(CallStatement.class, block.statements().getFirst());
        assertEquals("LoweringCalls#helper(int)", call.procedureName());
        assertEquals(1, call.arguments().size());
    }

    @Test
    void lowersSystemOutPrintlnAsDebugPrintStatement() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDebugPrint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDebugPrint {
                    @StoredProcedure
                    public static void run() {
                        System.out.println("hello");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());
        DebugPrintStatement debugPrint = assertInstanceOf(DebugPrintStatement.class, block.statements().getFirst());
        LiteralExpression literal = assertInstanceOf(LiteralExpression.class, debugPrint.message());
        assertEquals("hello", literal.value());
    }

    @Test
    void lowersDslAbortWithErrorAsRaiseStatement() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTriggerAbort.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;
                import static titan.dsl.DSL.abortWithError;

                class LoweringTriggerAbort {
                    @StoredProcedure
                    public static void run() {
                        abortWithError("stop now");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        RaiseStatement raise = assertInstanceOf(RaiseStatement.class, block.statements().getFirst());
        assertEquals("45000", raise.sqlstate());
        // F-10 (plan 2.2): this test used to pin the broken behavior — the message was the
        // argument's *source text* including its quotes ("\"stop now\""). The message is now a
        // real lowered expression: a TEXT literal carrying the unquoted string value.
        LiteralExpression message = assertInstanceOf(LiteralExpression.class, raise.message());
        assertEquals("stop now", message.value());
        assertInstanceOf(TTextType.class, message.type());
    }

    @Test
    void lowersTriggerRowGetAndSetIntoNewOldColumnReferences() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTriggerRows.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;
                import static titan.dsl.TriggerEvent.UPDATE;
                import static titan.dsl.TriggerForEach.ROW;
                import static titan.dsl.TriggerTiming.BEFORE;

                class LoweringTriggerRows {
                    static final Column<String> EMAIL = new Column<>("email", SQLType.TEXT, Nullability.NULLABLE);

                    @Trigger(table = "accounts", timing = BEFORE, event = {UPDATE}, forEach = ROW)
                    public static void normalize() {
                        String previous = oldRow().get(EMAIL);
                        String current = newRow().get(EMAIL);
                        newRow().set(EMAIL, current.trim());
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(2, block.declarations().size());
        assertEquals(3, block.statements().size());

        DeclareVariable previousDecl = assertInstanceOf(DeclareVariable.class, block.declarations().get(0));
        ColumnRefExpression previousValue = assertInstanceOf(ColumnRefExpression.class, previousDecl.initializer());
        assertEquals("OLD", previousValue.table());
        assertEquals("EMAIL", previousValue.column());

        DeclareVariable currentDecl = assertInstanceOf(DeclareVariable.class, block.declarations().get(1));
        ColumnRefExpression currentValue = assertInstanceOf(ColumnRefExpression.class, currentDecl.initializer());
        assertEquals("NEW", currentValue.table());
        assertEquals("EMAIL", currentValue.column());

        Assign setAssign = assertInstanceOf(Assign.class, block.statements().get(2));
        ColumnRefExpression setTarget = assertInstanceOf(ColumnRefExpression.class, setAssign.target());
        assertEquals("NEW", setTarget.table());
        assertEquals("EMAIL", setTarget.column());
    }

    @Test
    void rejectsOldRowMutationDuringLowering() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringOldRowMutation.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;
                import static titan.dsl.TriggerEvent.UPDATE;
                import static titan.dsl.TriggerForEach.ROW;
                import static titan.dsl.TriggerTiming.BEFORE;

                class LoweringOldRowMutation {
                    static final Column<String> EMAIL = new Column<>("email", SQLType.TEXT, Nullability.NULLABLE);

                    @Trigger(table = "accounts", timing = BEFORE, event = {UPDATE}, forEach = ROW)
                    public static void invalid() {
                        oldRow().set(EMAIL, "x");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("oldRow().set"));
    }

    @Test
    void rejectsDynamicSqlStringConcatenationInExecCalls() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDynamicSqlConcat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDynamicSqlConcat {
                    @StoredProcedure
                    public static void run(String tableName) {
                        exec("SELECT * FROM " + tableName);
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E004"));
    }

    @Test
    void rejectsDynamicSqlStringConcatenationViaVariableInExecCalls() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDynamicSqlConcatViaVariable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDynamicSqlConcatViaVariable {
                    @StoredProcedure
                    public static void run(String tableName) {
                        String sql = "SELECT * FROM " + tableName;
                        exec(sql);
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E004"));
    }

    @Test
    void rejectsDynamicSqlStringFormatInExecCallsAsLoweringDefenseInDepth() throws Exception {
        // Plan 2.5 (audit D14): FeatureValidator enforces the compile-time-constant SQL rule
        // before lowering; this exercises the lowerer-side re-check directly (no validator run)
        // against a String.format bypass of the old concatenation-only heuristic.
        Path sourceFile = tempDir.resolve("LoweringDynamicSqlStringFormat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDynamicSqlStringFormat {
                    @StoredProcedure
                    public static void run(String tableName) {
                        exec(String.format("SELECT * FROM %s", tableName));
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("LoweringDynamicSqlStringFormat.java:6"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    @Test
    void allowsCompileTimeConstantSqlTextInExecCallsAtLowering() throws Exception {
        // Plan 2.5 (audit D14): LITERAL + CONSTANT concatenations are compile-time constant
        // and must keep lowering (the old heuristic flagged any top-level '+').
        Path sourceFile = tempDir.resolve("LoweringDynamicSqlConstantConcat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringDynamicSqlConstantConcat {
                    static final String ID_FILTER = " WHERE id = :id";

                    @StoredProcedure
                    public static void run(int id) {
                        exec("SELECT id FROM accounts" + ID_FILTER);
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertDoesNotThrow(() -> new JavaToTirLowerer().lower(parsed, entryPoints));
    }

    @Test
    void lowersDslTerminalCallsIntoExecuteSqlStatements() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslCalls.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslCalls {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run(int id) {
                        select(ACCOUNTS.ID, ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .join(USERS).on(ACCOUNTS.ID.eqColumn(USERS.ACCOUNT_ID))
                                .orderBy(ACCOUNTS.EMAIL.desc(), ACCOUNTS.ID.asc())
                                .forUpdate()
                                .skipLocked()
                                .limit(10)
                                .fetch();
                        insertInto(ACCOUNTS).set(ACCOUNTS.ID, id).execute();
                        update(ACCOUNTS).set(ACCOUNTS.EMAIL, "updated@example.com").where(ACCOUNTS.ID.eq(id)).execute();
                        deleteFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).execute();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class UsersTable extends Table<Object> {
                        final Column<Integer> ACCOUNT_ID = column("account_id", SQLType.INTEGER, Nullability.NOT_NULL);

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(4, block.statements().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals("accounts", select.from());
        assertEquals(1, select.joins().size());
        JoinSpec join = select.joins().getFirst();
        assertEquals(JoinType.INNER, join.joinType());
        assertEquals("users", join.target());
        assertInstanceOf(BinaryOpExpression.class, join.condition());
        assertEquals(10, select.limit());
        assertEquals(3, select.columns().size());
        ColumnRefExpression firstColumn = assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression());
        assertEquals("id", firstColumn.column());
        ColumnRefExpression secondColumn = assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression());
        assertEquals("id", secondColumn.column());
        ColumnRefExpression thirdColumn = assertInstanceOf(ColumnRefExpression.class, select.columns().get(2).expression());
        assertEquals("email", thirdColumn.column());
        assertEquals(2, select.orderBy().size());
        OrderBySpec firstOrder = select.orderBy().getFirst();
        ColumnRefExpression firstOrderExpr = assertInstanceOf(ColumnRefExpression.class, firstOrder.expression());
        assertEquals("email", firstOrderExpr.column());
        assertEquals(SortDirection.DESC, firstOrder.direction());
        OrderBySpec secondOrder = select.orderBy().get(1);
        ColumnRefExpression secondOrderExpr = assertInstanceOf(ColumnRefExpression.class, secondOrder.expression());
        assertEquals("id", secondOrderExpr.column());
        assertEquals(SortDirection.ASC, secondOrder.direction());
        assertNotNull(select.locking());
        assertTrue(select.locking().forUpdate());
        assertFalse(select.locking().forShare());
        assertTrue(select.locking().skipLocked());
        assertFalse(select.locking().noWait());

        ExecuteSqlStatement insertExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        InsertSql insert = assertInstanceOf(InsertSql.class, insertExec.sqlNode());
        assertEquals("accounts", insert.table());
        assertEquals(List.of("id"), insert.columns());
        assertEquals(1, insert.values().size());
        VariableRefExpression insertedValue = assertInstanceOf(VariableRefExpression.class, insert.values().getFirst());
        assertEquals("id", insertedValue.name());

        ExecuteSqlStatement updateExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(2));
        UpdateSql update = assertInstanceOf(UpdateSql.class, updateExec.sqlNode());
        assertEquals("accounts", update.table());
        assertEquals(1, update.sets().size());
        assertEquals("email", update.sets().getFirst().column());
        LiteralExpression updateValue = assertInstanceOf(LiteralExpression.class, update.sets().getFirst().value());
        assertEquals("updated@example.com", updateValue.value());
        assertNotNull(update.where());

        ExecuteSqlStatement deleteExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(3));
        DeleteSql delete = assertInstanceOf(DeleteSql.class, deleteExec.sqlNode());
        assertEquals("accounts", delete.table());
        assertNotNull(delete.where());
    }

    @Test
    void lowersDslSetAndConditionValueExpressionsAsRealExpressionTrees() throws Exception {
        // Phase 3.1 defect: composite Java expressions in DSL value positions used to fall back
        // to raw source text (VariableRefExpression("id + 100")), which the emitters cannot remap
        // to the allocated p_/v_ routine names — the SQL then references a nonexistent column.
        // Value arguments must lower as real expression trees whose leaf VariableRefExpressions
        // carry the Java parameter name the RoutineSqlNameAllocator knows how to remap.
        Path sourceFile = tempDir.resolve("LoweringDslValueExpressions.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslValueExpressions {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int id, int factor, String name) {
                        insertInto(ACCOUNTS)
                                .set(ACCOUNTS.ID, id + 100)
                                .set(ACCOUNTS.SCORE, id * factor)
                                .set(ACCOUNTS.EMAIL, name + "@example.com")
                                .execute();
                        update(ACCOUNTS)
                                .set(ACCOUNTS.SCORE, id - 1)
                                .where(ACCOUNTS.ID.eq(id + 100))
                                .execute();
                        select(ACCOUNTS.SCORE, sum(ACCOUNTS.ID))
                                .from(ACCOUNTS)
                                .groupBy(ACCOUNTS.SCORE)
                                .having(sum(ACCOUNTS.ID).gt(factor + 5))
                                .orderBy(ACCOUNTS.SCORE.add(1).desc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> SCORE = column("score", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(3, block.statements().size());

        ExecuteSqlStatement insertExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        InsertSql insert = assertInstanceOf(InsertSql.class, insertExec.sqlNode());
        assertEquals(List.of("id", "score", "email"), insert.columns());

        // set(ACCOUNTS.ID, id + 100): parameter + literal.
        BinaryOpExpression idPlus = assertInstanceOf(BinaryOpExpression.class, insert.values().get(0));
        assertEquals(BinaryOperator.ADD, idPlus.operator());
        assertEquals("id", assertInstanceOf(VariableRefExpression.class, idPlus.left()).name());
        assertEquals(100, assertInstanceOf(LiteralExpression.class, idPlus.right()).value());

        // set(ACCOUNTS.SCORE, id * factor): parameter * parameter.
        BinaryOpExpression idTimesFactor = assertInstanceOf(BinaryOpExpression.class, insert.values().get(1));
        assertEquals(BinaryOperator.MULTIPLY, idTimesFactor.operator());
        assertEquals("id", assertInstanceOf(VariableRefExpression.class, idTimesFactor.left()).name());
        assertEquals("factor", assertInstanceOf(VariableRefExpression.class, idTimesFactor.right()).name());

        // set(ACCOUNTS.EMAIL, name + "@example.com"): string concat with a parameter.
        FunctionCallExpression concat = assertInstanceOf(FunctionCallExpression.class, insert.values().get(2));
        assertEquals("__titan_str_concat", concat.name());
        assertEquals("name", assertInstanceOf(VariableRefExpression.class, concat.arguments().get(0)).name());
        assertEquals("@example.com", assertInstanceOf(LiteralExpression.class, concat.arguments().get(1)).value());

        ExecuteSqlStatement updateExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        UpdateSql update = assertInstanceOf(UpdateSql.class, updateExec.sqlNode());

        // update set(ACCOUNTS.SCORE, id - 1) goes through the same value lowering.
        BinaryOpExpression idMinus = assertInstanceOf(BinaryOpExpression.class, update.sets().getFirst().value());
        assertEquals(BinaryOperator.SUBTRACT, idMinus.operator());
        assertEquals("id", assertInstanceOf(VariableRefExpression.class, idMinus.left()).name());

        // where(ACCOUNTS.ID.eq(id + 100)): the condition value argument too. NullAnalysisPass
        // wraps the maybe-nullable condition in COALESCE(cond, FALSE) — proof the pass sees the
        // newly-lowered tree; the comparison underneath must carry the real expression.
        CoalesceExpression guardedWhere = assertInstanceOf(CoalesceExpression.class, update.where());
        BinaryOpExpression where = assertInstanceOf(BinaryOpExpression.class, guardedWhere.expressions().getFirst());
        assertEquals(BinaryOperator.EQUAL, where.operator());
        BinaryOpExpression whereValue = assertInstanceOf(BinaryOpExpression.class, where.right());
        assertEquals(BinaryOperator.ADD, whereValue.operator());
        assertEquals("id", assertInstanceOf(VariableRefExpression.class, whereValue.left()).name());
        assertEquals(100, assertInstanceOf(LiteralExpression.class, whereValue.right()).value());

        // having(sum(ACCOUNTS.ID).gt(factor + 5)): the aggregate lowers as a real SUM call (it
        // used to ride the raw-source-text fallback) and the comparison value as an expression
        // tree; orderBy(ACCOUNTS.SCORE.add(1).desc()) lowers column arithmetic the same way.
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(2));
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        FunctionCallExpression sumColumn = assertInstanceOf(FunctionCallExpression.class, select.columns().get(1).expression());
        assertEquals("SUM", sumColumn.name());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, sumColumn.arguments().getFirst()).column());
        ExpressionNode havingNode = select.having() instanceof CoalesceExpression guarded
                ? guarded.expressions().getFirst()
                : select.having();
        BinaryOpExpression having = assertInstanceOf(BinaryOpExpression.class, havingNode);
        assertEquals(BinaryOperator.GREATER_THAN, having.operator());
        assertEquals("SUM", assertInstanceOf(FunctionCallExpression.class, having.left()).name());
        BinaryOpExpression havingValue = assertInstanceOf(BinaryOpExpression.class, having.right());
        assertEquals(BinaryOperator.ADD, havingValue.operator());
        assertEquals("factor", assertInstanceOf(VariableRefExpression.class, havingValue.left()).name());
        BinaryOpExpression orderByExpr = assertInstanceOf(BinaryOpExpression.class, select.orderBy().getFirst().expression());
        assertEquals(BinaryOperator.ADD, orderByExpr.operator());
        assertEquals("score", assertInstanceOf(ColumnRefExpression.class, orderByExpr.left()).column());
        assertEquals(SortDirection.DESC, select.orderBy().getFirst().direction());
    }

    @Test
    void rejectsDslLimitWithNonLiteralArgumentInsteadOfMisLowering() throws Exception {
        // limit(...) only supports int literals today; a parameter-backed limit must fail with a
        // positioned diagnostic, never crash or silently emit the raw Java identifier.
        Path sourceFile = tempDir.resolve("LoweringDslNonLiteralLimit.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslNonLiteralLimit {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int rows) {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .limit(rows)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("limit"), exception.getMessage());
        assertTrue(exception.getMessage().contains("LoweringDslNonLiteralLimit.java"), exception.getMessage());
    }

    @Test
    void rejectsDslSelectChainCombiningSkipLockedAndNoWait() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslInvalidLockCombo.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslInvalidLockCombo {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .forUpdate()
                                .skipLocked()
                                .noWait()
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("skipLocked() and noWait() cannot be combined"));
    }

    @Test
    void lowersDslGroupByAndHavingIntoSelectSql() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslGroupByHaving.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslGroupByHaving {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.STATUS, count())
                                .from(ACCOUNTS)
                                .groupBy(ACCOUNTS.STATUS)
                                .having(ACCOUNTS.STATUS.isNotNull())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> STATUS = column("status", SQLType.VARCHAR, 32, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals(1, select.groupBy().size());
        ExpressionNode groupByExpr = select.groupBy().getFirst();
        if (groupByExpr instanceof ColumnRefExpression ref) {
            assertEquals("status", ref.column());
        } else {
            VariableRefExpression ref = assertInstanceOf(VariableRefExpression.class, groupByExpr);
            assertTrue(ref.name().contains("STATUS"));
        }
        assertNotNull(select.having());
    }

    @Test
    void lowersDslGroupingSetsRollupAndCubeIntoSelectSqlGroupBy() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslGroupingSets.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslGroupingSets {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.TENANT_ID, ACCOUNTS.STATUS, count())
                                .from(ACCOUNTS)
                                .groupBy(groupingSets(
                                        set(ACCOUNTS.TENANT_ID, ACCOUNTS.STATUS),
                                        set(ACCOUNTS.TENANT_ID),
                                        set()))
                                .fetch();

                        select(ACCOUNTS.TENANT_ID, ACCOUNTS.STATUS, count())
                                .from(ACCOUNTS)
                                .groupBy(rollup(ACCOUNTS.TENANT_ID, ACCOUNTS.STATUS), cube(ACCOUNTS.STATUS))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> TENANT_ID = column("tenant_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> STATUS = column("status", SQLType.VARCHAR, 32, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(2, block.statements().size());

        ExecuteSqlStatement groupingSetsExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        SelectSql groupingSetsSelect = assertInstanceOf(SelectSql.class, groupingSetsExec.sqlNode());
        assertEquals(1, groupingSetsSelect.groupBy().size());
        GroupingSetSpec groupingSetsExpr = assertInstanceOf(GroupingSetSpec.class, groupingSetsSelect.groupBy().getFirst());
        assertEquals(GroupingSetKind.GROUPING_SETS, groupingSetsExpr.kind());
        assertEquals(3, groupingSetsExpr.sets().size());
        List<ExpressionNode> fullSet = groupingSetsExpr.sets().get(0);
        assertEquals(2, fullSet.size());
        ColumnRefExpression tenantRef = assertInstanceOf(ColumnRefExpression.class, fullSet.get(0));
        assertEquals("accounts", tenantRef.table());
        assertEquals("tenant_id", tenantRef.column());
        ColumnRefExpression statusRef = assertInstanceOf(ColumnRefExpression.class, fullSet.get(1));
        assertEquals("status", statusRef.column());
        assertEquals(1, groupingSetsExpr.sets().get(1).size());
        assertTrue(groupingSetsExpr.sets().get(2).isEmpty());

        ExecuteSqlStatement rollupCubeExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        SelectSql rollupCubeSelect = assertInstanceOf(SelectSql.class, rollupCubeExec.sqlNode());
        assertEquals(2, rollupCubeSelect.groupBy().size());
        GroupingSetSpec rollupExpr = assertInstanceOf(GroupingSetSpec.class, rollupCubeSelect.groupBy().get(0));
        GroupingSetSpec cubeExpr = assertInstanceOf(GroupingSetSpec.class, rollupCubeSelect.groupBy().get(1));
        assertEquals(GroupingSetKind.ROLLUP, rollupExpr.kind());
        assertEquals(2, rollupExpr.sets().getFirst().size());
        assertEquals(GroupingSetKind.CUBE, cubeExpr.kind());
        assertEquals(1, cubeExpr.sets().getFirst().size());
    }

    @Test
    void lowersDslSetOperationsIntoNestedSqlNodes() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSetOps.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSetOps {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final ArchiveAccountsTable ARCHIVE = new ArchiveAccountsTable();
                    static final PendingAccountsTable PENDING = new PendingAccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .union(select(ARCHIVE.ID).from(ARCHIVE))
                                .unionAll(select(PENDING.ID).from(PENDING))
                                .intersect(select(ARCHIVE.ID).from(ARCHIVE))
                                .except(select(PENDING.ID).from(PENDING))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class ArchiveAccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        ArchiveAccountsTable() {
                            super("archive_accounts", "public");
                        }
                    }

                    static final class PendingAccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        PendingAccountsTable() {
                            super("pending_accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        ExceptSql except = assertInstanceOf(ExceptSql.class, selectExec.sqlNode());
        SelectSql exceptRight = assertInstanceOf(SelectSql.class, except.right());
        assertEquals("pending_accounts", exceptRight.from());

        IntersectSql intersect = assertInstanceOf(IntersectSql.class, except.left());
        SelectSql intersectRight = assertInstanceOf(SelectSql.class, intersect.right());
        assertEquals("archive_accounts", intersectRight.from());

        UnionSql topUnion = assertInstanceOf(UnionSql.class, intersect.left());
        assertTrue(topUnion.all());
        SelectSql pendingSelect = assertInstanceOf(SelectSql.class, topUnion.right());
        assertEquals("pending_accounts", pendingSelect.from());

        UnionSql firstUnion = assertInstanceOf(UnionSql.class, topUnion.left());
        assertFalse(firstUnion.all());
        SelectSql baseSelect = assertInstanceOf(SelectSql.class, firstUnion.left());
        SelectSql archiveSelect = assertInstanceOf(SelectSql.class, firstUnion.right());
        assertEquals("accounts", baseSelect.from());
        assertEquals("archive_accounts", archiveSelect.from());
    }

    @Test
    void rejectsDslSetOperationWithMismatchedProjectionCount() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSetOpsMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSetOpsMismatch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final ArchiveAccountsTable ARCHIVE = new ArchiveAccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .union(select(ARCHIVE.ID).from(ARCHIVE))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class ArchiveAccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        ArchiveAccountsTable() {
                            super("archive_accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("matching projection column count"));
        assertTrue(exception.getMessage().contains("union(...)"));
    }

    @Test
    void rejectsDslSetOperationWithMismatchedProjectionTypes() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSetOpsTypeMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSetOpsTypeMismatch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final ArchiveAccountsTable ARCHIVE = new ArchiveAccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .union(select(ARCHIVE.ID).from(ARCHIVE))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class ArchiveAccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        ArchiveAccountsTable() {
                            super("archive_accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("projection column type compatibility"));
        assertTrue(exception.getMessage().contains("left="));
        assertTrue(exception.getMessage().contains("right="));
    }

    @Test
    void rejectsDslSetOperationRightHandChainCombiningSkipLockedAndNoWait() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSetOpsRightHandInvalidLocking.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSetOpsRightHandInvalidLocking {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final ArchiveAccountsTable ARCHIVE = new ArchiveAccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .union(
                                        select(ARCHIVE.ID)
                                                .from(ARCHIVE)
                                                .forUpdate()
                                                .skipLocked()
                                                .noWait()
                                )
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class ArchiveAccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        ArchiveAccountsTable() {
                            super("archive_accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("skipLocked() and noWait() cannot be combined"));
    }

    @Test
    void lowersDslWithRecursiveCteIntoSelectCteSpecs() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithRecursive.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithRecursive {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        withRecursive(
                                name("ids").as(
                                        select(ACCOUNTS.ID)
                                                .from(ACCOUNTS)
                                                .where(ACCOUNTS.ID.gt(0))
                                )
                        )
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals(1, select.ctes().size());
        CteSpec cte = select.ctes().getFirst();
        assertEquals("ids", cte.name());
        assertTrue(cte.recursive());
        assertEquals("accounts", cte.query().from());
    }

    @Test
    void reportsWorkaroundWhenWithRecursiveUsesAsRecursiveBuilder() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithRecursiveBuilder.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithRecursiveBuilder {
                    static final NumberSeedTable NUMBER_SEED = new NumberSeedTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> orgTree = name("org_tree")
                                .fields("id")
                                .asRecursive(self -> select(NUMBER_SEED.ID)
                                        .from(NUMBER_SEED)
                                        .unionAll(
                                                select(self.field("id", SQLType.INTEGER, Nullability.NOT_NULL))
                                                        .from(self)
                                                        .where(self.field("id", SQLType.INTEGER, Nullability.NOT_NULL).lt(10))));

                        withRecursive(orgTree)
                                .select(NUMBER_SEED.ID)
                                .from(NUMBER_SEED)
                                .fetch();
                    }

                    static final class NumberSeedTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        NumberSeedTable() {
                            super("number_seed", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("withRecursive(...) lowering does not support DSL.name(...).asRecursive(self -> ...)"));
        assertTrue(exception.getMessage().contains("use DSL.name(...).as(select(...)) for non-recursive CTEs"));
        assertTrue(exception.getMessage().contains("@SQL/raw SQL"));
    }

    @Test
    void reportsWorkaroundWhenWithUsesAsSqlCteBody() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithAsSqlCte.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithAsSqlCte {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> activeAccounts = name("active_accounts")
                                .asSql("SELECT id FROM accounts");

                        with(activeAccounts)
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("with(...) lowering does not support DSL.name(...).asSql(\"...\")"));
        assertTrue(exception.getMessage().contains("DSL.name(...).as(select(...)) / defineInlineView(select(...))"));
        assertTrue(exception.getMessage().contains("@SQL/raw SQL"));
    }

    @Test
    void reportsWorkaroundWhenWithUsesHelperMethodCall() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithHelperMethodCall.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithHelperMethodCall {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        with(activeAccounts())
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static CommonTableExpression<Object> activeAccounts() {
                        return name("active_accounts")
                                .as(select(ACCOUNTS.ID)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0)));
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("with(...) does not inspect helper method calls yet"));
        assertTrue(exception.getMessage().contains("local variable defined via DSL.name(...).as(select(...))"));
        assertTrue(exception.getMessage().contains("defineInlineView(select(...))"));
    }

    @Test
    void lowersDslWithInlineViewInvocationIntoSelectCteSpecs() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithInlineViewInvocation.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithInlineViewInvocation {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        with(
                                defineInlineView(
                                        select(ACCOUNTS.ID)
                                                .from(ACCOUNTS)
                                                .where(ACCOUNTS.ID.gt(0))
                                )
                        )
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.ctes().size());
        CteSpec cte = select.ctes().getFirst();
        assertEquals("inline_view_1", cte.name());
        assertFalse(cte.recursive());
        assertEquals("accounts", cte.query().from());
    }

    @Test
    void lowersDslWithInlineViewVariableIntoSelectCteSpecs() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithInlineViewVariable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithInlineViewVariable {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        InlineView<Object> highValueAccounts = defineInlineView(
                                select(ACCOUNTS.ID)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0))
                        );

                        with(highValueAccounts)
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.ctes().size());
        CteSpec cte = select.ctes().getFirst();
        assertEquals("high_value_accounts", cte.name());
        assertFalse(cte.recursive());
        assertEquals("accounts", cte.query().from());
    }

    @Test
    void lowersDslWithNamedCteVariableIntoSelectCteSpecs() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWithNamedCteVariable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWithNamedCteVariable {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> activeAccounts = name("active_accounts")
                                .as(select(ACCOUNTS.ID)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0)));

                        with(activeAccounts)
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.ctes().size());
        CteSpec cte = select.ctes().getFirst();
        assertEquals("active_accounts", cte.name());
        assertFalse(cte.recursive());
        assertEquals("accounts", cte.query().from());
    }

    @Test
    void lowersDslSelectFromInlineViewVariableIntoImplicitCte() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromInlineViewVariable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromInlineViewVariable {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        InlineView<Object> activeAccounts = defineInlineView(
                                select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0))
                        );
                        Column<Integer> activeId = activeAccounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = activeAccounts.field("email", SQLType.VARCHAR, Nullability.NULLABLE);

                        select(activeId, activeEmail)
                                .from(activeAccounts)
                                .orderBy(activeEmail.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals("active_accounts", select.from());
        assertEquals(1, select.ctes().size());
        CteSpec cte = select.ctes().getFirst();
        assertEquals("active_accounts", cte.name());
        assertEquals("accounts", cte.query().from());
        assertEquals("active_accounts", ((ColumnRefExpression) select.columns().get(0).expression()).table());
        assertEquals("id", ((ColumnRefExpression) select.columns().get(0).expression()).column());
    }

    @Test
    void rejectsJoinOnUnsupportedPredicateShapes() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslJoinOnUnsupported.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslJoinOnUnsupported {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .join(USERS).on(ACCOUNTS.ACCOUNT_USERS_FK)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> USER_ID = column("user_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final ForeignKey<Object, Object> ACCOUNT_USERS_FK =
                                foreignKey("fk_accounts_users", new Column<?>[] { USER_ID }, USERS, new Column<?>[] { USERS.ID });

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class UsersTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("join on(...)"));
    }

    @Test
    void lowersDslFetchIntoTerminalIntoExecuteSqlStatement() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchInto.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchInto {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record AccountProjection(Integer id, String email) {}

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .fetchInto(AccountProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals("accounts", select.from());
        assertEquals(2, select.columns().size());
        ColumnRefExpression idColumn = assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression());
        assertEquals("id", idColumn.column());
        ColumnRefExpression emailColumn = assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression());
        assertEquals("email", emailColumn.column());
    }

    @Test
    void rejectsDslFetchIntoWhenRecordComponentIsMissingFromProjection() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchIntoMissingProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchIntoMissingProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record AccountProjection(Integer id, String lastLogin) {}

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .fetchInto(AccountProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("fetchInto(AccountProjection.class)"));
        assertTrue(exception.getMessage().contains("lastLogin"));
    }

    @Test
    void rejectsDslFetchIntoWhenRecordComponentTypeIsIncompatible() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchIntoTypeMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchIntoTypeMismatch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record AccountProjection(Integer id, Integer email) {}

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .fetchInto(AccountProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("fetchInto(AccountProjection.class)"));
        assertTrue(exception.getMessage().contains("type mismatch"));
        assertTrue(exception.getMessage().contains("email"));
    }

    @Test
    void rejectsDslFetchIntoWhenProjectionCountDoesNotMatchRecordComponents() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchIntoProjectionCountMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchIntoProjectionCountMismatch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record AccountProjection(Integer id, String email) {}

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.ACTIVE)
                                .from(ACCOUNTS)
                                .fetchInto(AccountProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("fetchInto(AccountProjection.class)"));
        assertTrue(exception.getMessage().contains("projected column count to match record component count"));
    }

    @Test
    void rejectsDslFetchIntoWhenProjectionNamesAreNotIdentifierShaped() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchIntoIdentifierShape.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchIntoIdentifierShape {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record CountProjection(Long count) {}

                    @StoredProcedure
                    public static void run() {
                        select(count())
                                .from(ACCOUNTS)
                                .fetchInto(CountProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("fetchInto(CountProjection.class)"));
        assertTrue(exception.getMessage().contains("identifier-shaped"));
    }

    @Test
    void rejectsDslFetchIntoWhenNormalizedProjectionNamesAreAmbiguous() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchIntoAmbiguousProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchIntoAmbiguousProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    record AmbiguousPlanProjection(Integer planId, Integer planid) {}

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.PLAN_ID, ACCOUNTS.PLANID)
                                .from(ACCOUNTS)
                                .fetchInto(AmbiguousPlanProjection.class);
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> PLANID = column("planid", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("fetchInto(AmbiguousPlanProjection.class)"));
        assertTrue(exception.getMessage().contains("multiple projected columns normalize to the same identifier"));
    }

    @Test
    void rejectsDslForEachWhenCallbackArityDoesNotMatchProjectionCount() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslForEachArityMismatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslForEachArityMismatch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .forEach(new TwoArgConsumer());
                    }

                    static final class TwoArgConsumer implements java.util.function.BiConsumer<Object, Object> {
                        @Override
                        public void accept(Object id, Object email) {
                        }
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("forEach(...) requires projected column count to match callback arity"));
    }

    @Test
    void lowersDslInsertOnConflictIntoConflictClause() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslInsertOnConflict.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslInsertOnConflict {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int id, String email) {
                        insertInto(ACCOUNTS)
                                .set(ACCOUNTS.ID, id)
                                .set(ACCOUNTS.EMAIL, email)
                                .onConflict(ACCOUNTS.EMAIL)
                                .doUpdate()
                                .set(ACCOUNTS.EMAIL, email)
                                .execute();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());

        ExecuteSqlStatement insertExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        InsertSql insert = assertInstanceOf(InsertSql.class, insertExec.sqlNode());
        assertEquals("accounts", insert.table());
        assertEquals(List.of("id", "email"), insert.columns());
        assertEquals(2, insert.values().size());
        assertNotNull(insert.onConflict());
        assertEquals(List.of("email"), insert.onConflict().columns());
        assertEquals(1, insert.onConflict().updates().size());
        SetClause update = insert.onConflict().updates().getFirst();
        assertEquals("email", update.column());
    }

    @Test
    void lowersExpressionValuedUpdateSetIntoColumnArithmetic() throws Exception {
        // G3 (spike B3): update(T).set(VERSION, VERSION.add(1)) lowers the SET right-hand side to a
        // column-ref + arithmetic expression node (server-side version = version + 1), NOT a bind
        // parameter; the existing column-arithmetic lowering for SELECT/WHERE is reused.
        Path sourceFile = tempDir.resolve("LoweringDslUpdateSetExpression.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslUpdateSetExpression {
                    static final CountersTable COUNTERS = new CountersTable();

                    @StoredProcedure
                    public static void run(int id) {
                        update(COUNTERS)
                                .set(COUNTERS.VALUE, COUNTERS.VALUE.add(1))
                                .where(COUNTERS.ID.eq(id))
                                .execute();
                    }

                    static final class CountersTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> VALUE = column("value", SQLType.INTEGER, Nullability.NOT_NULL);

                        CountersTable() {
                            super("counters", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement updateExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        UpdateSql update = assertInstanceOf(UpdateSql.class, updateExec.sqlNode());
        assertEquals(1, update.sets().size());
        SetClause set = update.sets().getFirst();
        assertEquals("value", set.column());
        BinaryOpExpression rhs = assertInstanceOf(BinaryOpExpression.class, set.value());
        assertEquals(BinaryOperator.ADD, rhs.operator());
        ColumnRefExpression leftRef = assertInstanceOf(ColumnRefExpression.class, rhs.left());
        assertEquals("counters", leftRef.table());
        assertEquals("value", leftRef.column());
        LiteralExpression literal = assertInstanceOf(LiteralExpression.class, rhs.right());
        assertEquals(1, ((Number) literal.value()).intValue());
    }

    @Test
    void lowersExpressionValuedConflictUpdateSetIntoColumnArithmetic() throws Exception {
        // G3 (spike B3): the ON CONFLICT DO UPDATE SET right-hand side is also lowered to a
        // column-ref + arithmetic expression (version = version + 1), not a bind parameter.
        Path sourceFile = tempDir.resolve("LoweringDslConflictSetExpression.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslConflictSetExpression {
                    static final CountersTable COUNTERS = new CountersTable();

                    @StoredProcedure
                    public static void run(int id) {
                        insertInto(COUNTERS)
                                .set(COUNTERS.ID, id)
                                .set(COUNTERS.VALUE, 1)
                                .onConflict(COUNTERS.ID)
                                .doUpdate()
                                .set(COUNTERS.VALUE, COUNTERS.VALUE.add(1))
                                .execute();
                    }

                    static final class CountersTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> VALUE = column("value", SQLType.INTEGER, Nullability.NOT_NULL);

                        CountersTable() {
                            super("counters", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement insertExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        InsertSql insert = assertInstanceOf(InsertSql.class, insertExec.sqlNode());
        assertNotNull(insert.onConflict());
        assertEquals(1, insert.onConflict().updates().size());
        SetClause set = insert.onConflict().updates().getFirst();
        assertEquals("value", set.column());
        BinaryOpExpression rhs = assertInstanceOf(BinaryOpExpression.class, set.value());
        assertEquals(BinaryOperator.ADD, rhs.operator());
        ColumnRefExpression leftRef = assertInstanceOf(ColumnRefExpression.class, rhs.left());
        assertEquals("counters", leftRef.table());
        assertEquals("value", leftRef.column());
        LiteralExpression literal = assertInstanceOf(LiteralExpression.class, rhs.right());
        assertEquals(1, ((Number) literal.value()).intValue());
    }

    @Test
    void lowersDslLateralJoinIntoSelectJoinSpec() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslLateralJoin.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslLateralJoin {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .lateralJoin(select(PLANS.NAME).from(PLANS).where(PLANS.ID.eq(ACCOUNTS.PLAN_ID)).limit(1))
                                .as("plan_lateral")
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);
                        final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> NAME = column("name", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals(1, select.joins().size());
        JoinSpec join = select.joins().getFirst();
        assertEquals(JoinType.LATERAL, join.joinType());
        // Plan 1.4b: the lateral target is a structured subquery, never smuggled Java source text.
        assertNull(join.target());
        LateralSubquery lateral = join.lateralTarget();
        assertNotNull(lateral);
        assertEquals("plan_lateral", lateral.alias());
        assertEquals("plans", lateral.subquery().from());
        assertEquals(1, lateral.subquery().limit());
        ColumnRefExpression projected = assertInstanceOf(
                ColumnRefExpression.class, lateral.subquery().columns().getFirst().expression());
        assertEquals("plans", projected.table());
        assertEquals("name", projected.column());
        BinaryOpExpression where = assertInstanceOf(BinaryOpExpression.class, lateral.subquery().where());
        assertEquals(BinaryOperator.EQUAL, where.operator());
        ColumnRefExpression outerRef = assertInstanceOf(ColumnRefExpression.class, where.right());
        assertEquals("accounts", outerRef.table());
        assertEquals("plan_id", outerRef.column());
    }

    @Test
    void lowersDslFullOuterJoinIntoSelectJoinSpec() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFullOuterJoin.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFullOuterJoin {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .fullOuterJoin(USERS)
                                .on(ACCOUNTS.ID.eqColumn(USERS.ACCOUNT_ID))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class UsersTable extends Table<Object> {
                        final Column<Integer> ACCOUNT_ID = column("account_id", SQLType.INTEGER, Nullability.NOT_NULL);

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals(1, select.joins().size());
        JoinSpec join = select.joins().getFirst();
        assertEquals(JoinType.FULL_OUTER, join.joinType());
        assertTrue(join.target().equals("users") || join.target().equals("USERS"));
        assertInstanceOf(BinaryOpExpression.class, join.condition());
    }

    @Test
    void rejectsSelectDslChainsWithoutFromSource() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslMissingFrom.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslMissingFrom {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID).fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("from(...)"));
    }

    @Test
    void allowsProjectedColumnsFromJoinedTableReference() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslJoinedTableProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslJoinedTableProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run() {
                        select(USERS.EMAIL)
                                .from(ACCOUNTS)
                                .join(USERS).on(ACCOUNTS.ID.eqColumn(USERS.ACCOUNT_ID))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class UsersTable extends Table<Object> {
                        final Column<Integer> ACCOUNT_ID = column("account_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        ColumnRefExpression projected = assertInstanceOf(ColumnRefExpression.class, select.columns().getFirst().expression());
        assertEquals("users", projected.table());
        assertEquals("email", projected.column());
    }

    @Test
    void rejectsProjectedColumnsFromUnknownTableReference() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslUnknownTableColumn.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslUnknownTableColumn {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final AccountsTable USERS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(USERS.EMAIL).from(ACCOUNTS).fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("USERS.EMAIL"));
    }

    @Test
    void rejectsWhereClauseColumnsFromUnknownTableReference() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslUnknownWhereTableColumn.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslUnknownWhereTableColumn {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final AccountsTable USERS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.EMAIL).from(ACCOUNTS).where(USERS.EMAIL.eq("x")).fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("USERS.EMAIL"));
    }

    @Test
    void lowersFetchOneAndFetchCountWithExpectedSelectSemantics() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslFetchModes.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslFetchModes {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID).from(ACCOUNTS).fetchOne();
                        select(ACCOUNTS.ID).from(ACCOUNTS).limit(5).fetchCount();
                        select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.ID.eq(1)).fetchExists();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(3, block.statements().size());

        ExecuteSqlStatement fetchOneExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        SelectSql fetchOneSelect = assertInstanceOf(SelectSql.class, fetchOneExec.sqlNode());
        assertEquals(1, fetchOneSelect.limit());
        assertEquals(1, fetchOneSelect.columns().size());
        ColumnRefExpression fetchOneColumn = assertInstanceOf(ColumnRefExpression.class, fetchOneSelect.columns().getFirst().expression());
        assertEquals("id", fetchOneColumn.column());

        ExecuteSqlStatement fetchCountExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        SelectSql fetchCountSelect = assertInstanceOf(SelectSql.class, fetchCountExec.sqlNode());
        assertNull(fetchCountSelect.limit());
        assertEquals(1, fetchCountSelect.columns().size());
        FunctionCallExpression countExpression = assertInstanceOf(FunctionCallExpression.class, fetchCountSelect.columns().getFirst().expression());
        assertEquals("COUNT", countExpression.name());
        assertEquals(1, countExpression.arguments().size());
        ColumnRefExpression countStar = assertInstanceOf(ColumnRefExpression.class, countExpression.arguments().getFirst());
        assertEquals("*", countStar.column());

        ExecuteSqlStatement fetchExistsExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(2));
        SelectSql fetchExistsSelect = assertInstanceOf(SelectSql.class, fetchExistsExec.sqlNode());
        assertTrue(fetchExistsSelect.existsWrapper());
        assertNull(fetchExistsSelect.limit());
        assertNotNull(fetchExistsSelect.where());
    }

    @Test
    void lowersFetchExistsValueIntoSelectIntoBoundToLocalUsableInABranch() throws Exception {
        // Phase A4 / G2: read an existence result into a typed boolean local and branch on it.
        Path sourceFile = tempDir.resolve("LoweringExistsInto.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringExistsInto {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int targetId) {
                        boolean present = select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ID.eq(targetId))
                                .fetchExistsValue();
                        if (present) {
                            return;
                        }
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        // The local is DECLAREd (boolean) without an initializer; the existence query binds into it.
        DeclareVariable presentDecl = block.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .filter(declaration -> declaration.name().equals("present"))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(TBooleanType.class, presentDecl.type());
        assertNull(presentDecl.initializer());

        SelectIntoStatement selectInto = assertInstanceOf(SelectIntoStatement.class, block.statements().get(0));
        assertEquals("present", selectInto.variableName());
        assertTrue(selectInto.query().existsWrapper());
        assertNotNull(selectInto.query().where());

        // The bound local is usable in a following branch — `if (present) return;`. The boolean
        // condition is wrapped in the lowerer's null-safe COALESCE(<cond>, false) coercion; the
        // load-bearing point is that the branch reads the SELECT-INTO-bound local by name.
        IfStatement ifStatement = assertInstanceOf(IfStatement.class, block.statements().get(1));
        CoalesceExpression coerced = assertInstanceOf(CoalesceExpression.class, ifStatement.condition());
        VariableRefExpression condition = assertInstanceOf(VariableRefExpression.class, coerced.expressions().getFirst());
        assertEquals("present", condition.name());
        ReturnStatement shortCircuit = assertInstanceOf(ReturnStatement.class, ifStatement.thenBlock().statements().getFirst());
        assertNull(shortCircuit.expression());
    }

    @Test
    void lowersFetchScalarIntoSelectIntoBoundToTypedLocal() throws Exception {
        // Phase A4 / G2: read a scalar column into a typed local for a precondition check.
        Path sourceFile = tempDir.resolve("LoweringScalarInto.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringScalarInto {
                    static final DraftsTable DRAFTS = new DraftsTable();

                    @StoredProcedure
                    public static void run(int id, int newVersion) {
                        int current = select(DRAFTS.VERSION)
                                .from(DRAFTS)
                                .where(DRAFTS.ID.eq(id))
                                .fetchScalar();
                        if (current >= newVersion) {
                            return;
                        }
                    }

                    static final class DraftsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> VERSION = column("version", SQLType.INTEGER, Nullability.NOT_NULL);

                        DraftsTable() {
                            super("drafts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        DeclareVariable currentDecl = block.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .filter(declaration -> declaration.name().equals("current"))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(TIntType.class, currentDecl.type());
        assertNull(currentDecl.initializer());

        SelectIntoStatement selectInto = assertInstanceOf(SelectIntoStatement.class, block.statements().get(0));
        assertEquals("current", selectInto.variableName());
        assertFalse(selectInto.query().existsWrapper());
        // Single projected column, LIMIT 1 single-row read.
        assertEquals(1, selectInto.query().columns().size());
        assertEquals(1, selectInto.query().limit());
        ColumnRefExpression projected = assertInstanceOf(ColumnRefExpression.class, selectInto.query().columns().getFirst().expression());
        assertEquals("version", projected.column());
    }

    @Test
    void rejectsFetchScalarReadIntoLocalWithoutAFromSource() throws Exception {
        // Validator negative (Phase A4 / G2): a read-into-local terminal still requires a resolved
        // from(...) source — an unresolvable scalar SELECT ... INTO must fail with a positioned
        // TITAN-E001 rather than emit an invalid SELECT INTO. (The DSL types already make a
        // multi-column scalar read unrepresentable: fetchScalar() exists only on the single-column
        // SelectBuilder1, so "two columns into one scalar local" cannot be authored.)
        Path sourceFile = tempDir.resolve("LoweringScalarIntoNoFrom.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringScalarIntoNoFrom {
                    static final DraftsTable DRAFTS = new DraftsTable();

                    @StoredProcedure
                    public static void run(int id) {
                        // No from(...) — the scalar read cannot be lowered into a SELECT INTO.
                        int current = select(DRAFTS.VERSION).fetchScalar();
                        if (current > 0) {
                            return;
                        }
                    }

                    static final class DraftsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> VERSION = column("version", SQLType.INTEGER, Nullability.NOT_NULL);

                        DraftsTable() {
                            super("drafts", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException exception = assertThrows(
                TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(exception.getMessage().contains("TITAN-E001"),
                "expected TITAN-E001, got: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("from"),
                "expected the diagnostic to mention the missing from(...) source: " + exception.getMessage());
    }

    @Test
    void lowersDslWhereClauseIntoSelectPredicate() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWhereClause.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWhereClause {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int targetId) {
                        select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.ID.eq(targetId)).fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertNotNull(select.where());
        assertTrue(select.where().toString().contains("targetId"));
    }

    @Test
    void lowersDslInequalityPredicatesIntoBinaryOperators() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslInequalityWhereClause.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslInequalityWhereClause {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ID.gt(3).and(ACCOUNTS.ID.le(9)))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        BinaryOpExpression where = assertInstanceOf(BinaryOpExpression.class, select.where());
        assertEquals(BinaryOperator.AND, where.operator());

        BinaryOpExpression left = assertInstanceOf(BinaryOpExpression.class, where.left());
        assertEquals(BinaryOperator.GREATER_THAN, left.operator());

        BinaryOpExpression right = assertInstanceOf(BinaryOpExpression.class, where.right());
        assertEquals(BinaryOperator.LESS_THAN_OR_EQUAL, right.operator());
    }

    @Test
    void lowersDslNotPredicateIntoNotExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslNotPredicate.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslNotPredicate {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ACTIVE.eq(true).not())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        NotExpression where = assertInstanceOf(NotExpression.class, select.where());
        BinaryOpExpression inner = assertInstanceOf(BinaryOpExpression.class, where.expression());
        assertEquals(BinaryOperator.EQUAL, inner.operator());
    }

    @Test
    void lowersDslNotExistsPredicateIntoNegatedExistsExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslNotExistsPredicate.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslNotExistsPredicate {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(DSL.notExists(
                                        select(ACCOUNTS.ID)
                                                .from(ACCOUNTS)
                                                .where(ACCOUNTS.PLAN_CODE.eq("vip"))
                                                .limit(1)))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        CoalesceExpression where = assertInstanceOf(CoalesceExpression.class, select.where());
        // Plan 1.4b: a structured ExistsExpression, never pre-rendered dialect SQL text.
        ExistsExpression exists = assertInstanceOf(ExistsExpression.class, where.expressions().getFirst());
        assertTrue(exists.negated());
        assertEquals("accounts", exists.subquery().from());
        assertEquals(1, exists.subquery().limit());
        BinaryOpExpression subqueryWhere = assertInstanceOf(BinaryOpExpression.class, exists.subquery().where());
        assertEquals(BinaryOperator.EQUAL, subqueryWhere.operator());
        LiteralExpression fallback = assertInstanceOf(LiteralExpression.class, where.expressions().get(1));
        assertEquals(false, fallback.value());
    }

    @Test
    void lowersDslCorrelatedExistsPredicateAgainstOuterCteField() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslCorrelatedExistsPredicate.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslCorrelatedExistsPredicate {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        CommonTableExpression<Object> active_accounts = name("active_accounts")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.PLAN_CODE)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        with(active_accounts)
                                .select(activeId)
                                .from(active_accounts)
                                .where(exists(
                                        select(ACCOUNTS.ID)
                                                .from(ACCOUNTS)
                                                .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                                .limit(1)))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals("active_accounts", select.from());
        assertNotNull(select.where());
        assertFalse(select.ctes().isEmpty());
    }

    @Test
    void lowersDslScalarProjectionIntoSubqueryLikeValue() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslScalarProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslScalarProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID,
                               DSL.scalar(
                                       select(ACCOUNTS.EMAIL)
                                               .from(ACCOUNTS)
                                               .where(ACCOUNTS.ID.eq(1))
                                               .limit(1),
                                       SQLType.TEXT))
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ID.le(2))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        SubqueryExpression scalar = assertInstanceOf(SubqueryExpression.class, select.columns().get(1).expression());
        SelectSql subquery = scalar.select();
        assertEquals(1, subquery.limit());
        BinaryOpExpression predicate = assertInstanceOf(BinaryOpExpression.class, subquery.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());
    }

    @Test
    void lowersDslScalarLocalBindingAsScaffolding() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslScalarLocalBinding.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslScalarLocalBinding {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        Column<Integer> firstAccountId = scalar(
                                select(ACCOUNTS.ID)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true))
                                        .limit(1),
                                SQLType.INTEGER);

                        select(ACCOUNTS.EMAIL)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ID.eq(firstAccountId))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertTrue(block.declarations().isEmpty());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        BinaryOpExpression predicate = assertInstanceOf(BinaryOpExpression.class, select.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());
        SubqueryExpression scalar = assertInstanceOf(SubqueryExpression.class, predicate.right());
        assertEquals(1, scalar.select().limit());
    }

    @Test
    void lowersDslArithmeticProjectionIntoBinaryOperator() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslArithmeticProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslArithmeticProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.LOGIN_COUNT.add(1), ACCOUNTS.LOGIN_COUNT.subtract(1), ACCOUNTS.LOGIN_COUNT.multiply(2))
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.LOGIN_COUNT.isNotNull())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        BinaryOpExpression add = assertInstanceOf(BinaryOpExpression.class, select.columns().get(1).expression());
        assertEquals(BinaryOperator.ADD, add.operator());

        BinaryOpExpression subtract = assertInstanceOf(BinaryOpExpression.class, select.columns().get(2).expression());
        assertEquals(BinaryOperator.SUBTRACT, subtract.operator());

        BinaryOpExpression multiply = assertInstanceOf(BinaryOpExpression.class, select.columns().get(3).expression());
        assertEquals(BinaryOperator.MULTIPLY, multiply.operator());
    }

    @Test
    void prunesSelectProjectionForFetchOneValueNChainsWhenSafe() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslValueProjection.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslValueProjection {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.ACTIVE).from(ACCOUNTS).fetchOne().value2();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.columns().size());
        ColumnRefExpression selected = assertInstanceOf(ColumnRefExpression.class, select.columns().getFirst().expression());
        assertEquals("email", selected.column());
    }

    @Test
    void prunesSelectProjectionForFetchValueNChainsWhenSafe() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslValueProjectionFetch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslValueProjectionFetch {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.ACTIVE).from(ACCOUNTS).fetch().value3();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.columns().size());
        ColumnRefExpression selected = assertInstanceOf(ColumnRefExpression.class, select.columns().getFirst().expression());
        assertEquals("active", selected.column());
        assertNull(select.limit());
    }

    @Test
    void keepsFullProjectionWhenValueIndexCannotBeProvenSafe() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslValueProjectionFallback.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslValueProjectionFallback {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, ACCOUNTS.EMAIL).from(ACCOUNTS).fetchOne().value5();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(2, select.columns().size());
    }

    @Test
    void insertsNullGuardForNullableVariableUsedInArithmetic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringNullGuard.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringNullGuard {
                    @StoredProcedure
                    public static void run(Integer wrapped) {
                        int total = 1;
                        total = wrapped + total;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(3, block.statements().size());
        assertInstanceOf(Assign.class, block.statements().get(0));
        NullGuardStatement guard = assertInstanceOf(NullGuardStatement.class, block.statements().get(1));
        assertEquals("wrapped", guard.variableName());
        // Plan 2.3: the guard carries the enclosing entry-point location instead of the old
        // hardcoded "autoboxing" placeholder.
        assertTrue(guard.sourceLocation().contains("LoweringNullGuard.java:"));
        assertInstanceOf(Assign.class, block.statements().get(2));
    }

    @Test
    void lowersCompareToWithNullGuardsAndElselessComparisonCase() throws Exception {
        // N2 (plan 2.3): compareTo must match Java's NullPointerException instead of silently
        // comparing NULL operands as equal. The lowering emits the __titan_compare_to marker and
        // NullAnalysisPass rewrites it to guards plus a three-branch CASE with no ELSE.
        Path sourceFile = tempDir.resolve("LoweringCompareToGuard.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringCompareToGuard {
                    @StoredFunction
                    public static int compareScore(Integer current, int threshold) {
                        int cmp = current.compareTo(threshold);
                        return cmp;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        // Parameters are not modeled in TIR yet, so both operands are conservatively guarded.
        NullGuardStatement receiverGuard = assertInstanceOf(NullGuardStatement.class, block.statements().get(0));
        assertEquals("current", receiverGuard.variableName());
        assertTrue(receiverGuard.sourceLocation().contains("LoweringCompareToGuard.java:"));
        NullGuardStatement argumentGuard = assertInstanceOf(NullGuardStatement.class, block.statements().get(1));
        assertEquals("threshold", argumentGuard.variableName());

        Assign cmp = assertInstanceOf(Assign.class, block.statements().get(2));
        CaseWhenExpression comparison = assertInstanceOf(CaseWhenExpression.class, cmp.expression());
        assertEquals(3, comparison.conditions().size());
        assertEquals(BinaryOperator.LESS_THAN,
                assertInstanceOf(BinaryOpExpression.class, comparison.conditions().get(0).condition()).operator());
        assertEquals(BinaryOperator.GREATER_THAN,
                assertInstanceOf(BinaryOpExpression.class, comparison.conditions().get(1).condition()).operator());
        assertEquals(BinaryOperator.EQUAL,
                assertInstanceOf(BinaryOpExpression.class, comparison.conditions().get(2).condition()).operator());
        assertNull(comparison.elseValue());

        // The declaration initializer is rewritten identically so the emitters keep pairing it
        // with the ordered assignment (no double evaluation).
        DeclareVariable cmpDeclaration = block.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .filter(declaration -> declaration.name().equals("cmp"))
                .findFirst()
                .orElseThrow();
        assertEquals(cmp.expression(), cmpDeclaration.initializer());
    }

    @Test
    void emitsE003ForProvableNullDereferenceWithEntryPointLocation() throws Exception {
        // Plan 2.3: TitanErrorCode.E003 for definite nulls only. TIR carries no SourceSpans yet,
        // so the diagnostic is positioned at the enclosing entry point (plan 1.1 TODO).
        Path sourceFile = tempDir.resolve("LoweringDefiniteNull.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringDefiniteNull {
                    @StoredFunction
                    public static int run() {
                        Integer ghost = null;
                        int total = ghost + 1;
                        return total;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(exception.getMessage().contains("TITAN-E003"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ghost"), exception.getMessage());
        assertTrue(exception.getMessage().contains("LoweringDefiniteNull.java:"), exception.getMessage());
    }

    @Test
    void doesNotEmitE003ForNullableButNotDefinitelyNullVariable() throws Exception {
        // Negative case: a reassigned variable is no longer provably null — runtime guard
        // territory, not a compile-time E003.
        Path sourceFile = tempDir.resolve("LoweringReassignedNull.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringReassignedNull {
                    @StoredFunction
                    public static int run(int seed) {
                        Integer ghost = null;
                        ghost = seed;
                        int total = ghost + 1;
                        return total;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertFalse(block.statements().isEmpty());
    }

    @Test
    void wrapsNullableBooleanConditionWithCoalesceFalse() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringCoalesce.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringCoalesce {
                    @StoredProcedure
                    public static void run(Boolean flag) {
                        if (flag) {
                            return;
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        IfStatement ifStatement = assertInstanceOf(IfStatement.class, block.statements().getFirst());
        CoalesceExpression coalesce = assertInstanceOf(CoalesceExpression.class, ifStatement.condition());
        assertEquals(2, coalesce.expressions().size());
        assertInstanceOf(VariableRefExpression.class, coalesce.expressions().get(0));
        LiteralExpression fallback = assertInstanceOf(LiteralExpression.class, coalesce.expressions().get(1));
        assertEquals(false, fallback.value());
    }

    @Test
    void lowersTryCatchFinallyIntoStructuredTirNode() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTryFinally.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringTryFinally {
                    @StoredProcedure
                    public static void run() {
                        try {
                            helper();
                        } catch (RuntimeException ex) {
                            helper();
                        } finally {
                            helper();
                        }
                    }

                    static void helper() {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.statements().size());
        TryCatchFinallyStatement tryStmt = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        assertEquals(1, tryStmt.tryBlock().statements().size());
        assertEquals(1, tryStmt.catches().size());
        assertEquals("ex", tryStmt.catches().getFirst().exceptionVariable());
        assertEquals("RuntimeException", tryStmt.catches().getFirst().exceptionType());
        assertEquals(List.of("22023", "55000", "22012", "45001", "2202E", "0A000", "45000"), tryStmt.catches().getFirst().sqlStates());
        assertNotNull(tryStmt.finallyBlock());
        assertEquals(1, tryStmt.finallyBlock().statements().size());
    }

    @Test
    void lowersTryWithResourcesIntoCursorCloseInFinally() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTryWithResources.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringTryWithResources {
                    @StoredProcedure
                    public static void run() {
                        try (CursorLike cur = open()) {
                            helper();
                        }
                    }

                    static CursorLike open() {
                        return new CursorLike();
                    }

                    static void helper() {
                    }

                    static final class CursorLike implements AutoCloseable {
                        @Override
                        public void close() {
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        TryCatchFinallyStatement tryStmt = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        assertEquals(2, tryStmt.tryBlock().declarations().size());
        DeclareVariable resource = assertInstanceOf(DeclareVariable.class, tryStmt.tryBlock().declarations().getFirst());
        assertEquals("cur", resource.name());
        DeclareVariable cursorOpenFlag = assertInstanceOf(DeclareVariable.class, tryStmt.tryBlock().declarations().get(1));
        assertEquals("__titan_cursor_open_cur", cursorOpenFlag.name());
        assertNotNull(tryStmt.finallyBlock());
        assertEquals(1, tryStmt.finallyBlock().statements().size());
        IfStatement guardedClose = assertInstanceOf(IfStatement.class, tryStmt.finallyBlock().statements().getFirst());
        assertEquals("__titan_cursor_open_cur", assertInstanceOf(VariableRefExpression.class, guardedClose.condition()).name());
        assertEquals(2, guardedClose.thenBlock().statements().size());
        CloseCursorStatement close = assertInstanceOf(CloseCursorStatement.class, guardedClose.thenBlock().statements().getFirst());
        assertEquals("cur", close.cursorName());
    }

    @Test
    void lowersMultiCatchIntoSqlStateUnionForDispatch() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringMultiCatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringMultiCatch {
                    @StoredProcedure
                    public static void run(int x, int y) {
                        try {
                            int z = x / y;
                        } catch (ArithmeticException | NullPointerException ex) {
                            helper();
                        }
                    }

                    static void helper() {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        TryCatchFinallyStatement tryStmt = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        assertEquals(1, tryStmt.catches().size());
        assertEquals("ArithmeticException | NullPointerException", tryStmt.catches().getFirst().exceptionType());
        assertEquals(List.of("22012", "45001"), tryStmt.catches().getFirst().sqlStates());
    }

    @Test
    void lowersStringMethodInvocationsIntoStringTranslationExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringStringMethods.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringStringMethods {
                    @StoredProcedure
                    public static void run(String input) {
                        String upper = input.toUpperCase();
                        String mid = input.substring(1, 3);
                        boolean hasA = input.contains("a");
                        boolean same = input.equals(null);
                        int idx = input.indexOf("z");
                        int idxFrom = input.indexOf("z", 2);
                        String stripped = input.strip();
                        boolean startsAt = input.startsWith("abc", 1);
                        String[] parts = input.split(",");
                        boolean regex = input.matches("[a-z]+$");
                        String fmt = String.format("%s-%s", input, "ok");
                        String ch = String.valueOf(input.charAt(0));
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(12, block.declarations().size());

        FunctionCallExpression upper = initializer(block, "upper", FunctionCallExpression.class);
        assertEquals("UPPER", upper.name());

        FunctionCallExpression mid = initializer(block, "mid", FunctionCallExpression.class);
        assertEquals("__titan_str_substring", mid.name());

        FunctionCallExpression hasA = initializer(block, "hasA", FunctionCallExpression.class);
        assertEquals("__titan_str_contains", hasA.name());

        CoalesceExpression same = initializer(block, "same", CoalesceExpression.class);
        assertEquals(2, same.expressions().size());
        CaseWhenExpression nullAwareEquals = assertInstanceOf(CaseWhenExpression.class, same.expressions().getFirst());
        LiteralExpression fallback = assertInstanceOf(LiteralExpression.class, same.expressions().get(1));
        assertEquals(false, fallback.value());
        assertEquals(1, nullAwareEquals.conditions().size());
        assertInstanceOf(IsNullExpression.class, nullAwareEquals.conditions().getFirst().condition());
        LiteralExpression nullEqualsValue = assertInstanceOf(LiteralExpression.class, nullAwareEquals.conditions().getFirst().value());
        assertEquals(false, nullEqualsValue.value());
        FunctionCallExpression stringEquals = assertInstanceOf(FunctionCallExpression.class, nullAwareEquals.elseValue());
        assertEquals("__titan_str_equals", stringEquals.name());
        assertEquals(2, stringEquals.arguments().size());

        FunctionCallExpression idx = initializer(block, "idx", FunctionCallExpression.class);
        assertEquals("__titan_str_index_of", idx.name());
        assertEquals(2, idx.arguments().size());

        FunctionCallExpression idxFrom = initializer(block, "idxFrom", FunctionCallExpression.class);
        assertEquals("__titan_str_index_of", idxFrom.name());
        assertEquals(3, idxFrom.arguments().size());

        FunctionCallExpression stripped = initializer(block, "stripped", FunctionCallExpression.class);
        assertEquals("TRIM", stripped.name());

        FunctionCallExpression startsAt = initializer(block, "startsAt", FunctionCallExpression.class);
        assertEquals("__titan_str_starts_with", startsAt.name());
        assertEquals(3, startsAt.arguments().size());

        FunctionCallExpression parts = initializer(block, "parts", FunctionCallExpression.class);
        assertEquals("__titan_str_split", parts.name());

        FunctionCallExpression regex = initializer(block, "regex", FunctionCallExpression.class);
        assertEquals("__titan_str_matches", regex.name());

        FunctionCallExpression fmt = initializer(block, "fmt", FunctionCallExpression.class);
        assertEquals("__titan_str_format", fmt.name());

        initializer(block, "ch", CastExpression.class);
    }

    @Test
    void lowersCharArithmeticOperandsToCharacterCodes() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringCharArithmetic.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringCharArithmetic {
                    @StoredFunction
                    public static long run(String input) {
                        char c = input.charAt(0);
                        return 10L + c - '0';
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ReturnStatement returnStatement = block.statements().stream()
                .filter(ReturnStatement.class::isInstance)
                .map(ReturnStatement.class::cast)
                .findFirst()
                .orElseThrow();
        BinaryOpExpression subtract = assertInstanceOf(BinaryOpExpression.class, returnStatement.expression());
        assertEquals(BinaryOperator.SUBTRACT, subtract.operator());

        FunctionCallExpression zero = assertInstanceOf(FunctionCallExpression.class, subtract.right());
        assertEquals("__titan_char_code", zero.name());

        BinaryOpExpression add = assertInstanceOf(BinaryOpExpression.class, subtract.left());
        FunctionCallExpression c = assertInstanceOf(FunctionCallExpression.class, add.right());
        assertEquals("__titan_char_code", c.name());
    }

    @Test
    void lowersTitanTextBase64UrlUtf8Intrinsics() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTitanTextBase64Url.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;
                import titan.dsl.Text;

                class LoweringTitanTextBase64Url {
                    @StoredFunction
                    public static String run(String input) {
                        String encoded = Text.base64UrlEncodeUtf8(input);
                        int alphabetIndex = Text.base64UrlAlphabetIndex('A');
                        return Text.base64UrlDecodeUtf8(encoded + alphabetIndex);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        DeclareVariable encoded = assertInstanceOf(DeclareVariable.class, block.declarations().getFirst());
        assertEquals("__titan_text_base64url_encode_utf8",
                assertInstanceOf(FunctionCallExpression.class, encoded.initializer()).name());
        DeclareVariable alphabetIndex = assertInstanceOf(DeclareVariable.class, block.declarations().get(1));
        assertEquals("__titan_text_base64url_alphabet_index",
                assertInstanceOf(FunctionCallExpression.class, alphabetIndex.initializer()).name());
        ReturnStatement returned = block.statements().stream()
                .filter(ReturnStatement.class::isInstance)
                .map(ReturnStatement.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("__titan_text_base64url_decode_utf8",
                assertInstanceOf(FunctionCallExpression.class, returned.expression()).name());
    }

    @Test
    void lowersFullyQualifiedStringHelpersIntoStringTranslationExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringFullyQualifiedStringHelpers.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringFullyQualifiedStringHelpers {
                    @StoredProcedure
                    public static void run(String input) {
                        String formatted = java.lang.String.format("[%s]", input);
                        String casted = java.lang.String.valueOf(input);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(2, block.declarations().size());

        DeclareVariable formatted = (DeclareVariable) block.declarations().get(0);
        assertInstanceOf(FunctionCallExpression.class, formatted.initializer());
        assertEquals("__titan_str_format", ((FunctionCallExpression) formatted.initializer()).name());

        DeclareVariable casted = (DeclareVariable) block.declarations().get(1);
        assertInstanceOf(CastExpression.class, casted.initializer());
    }

    @Test
    void lowersStringConcatenationIntoConcatTranslationExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringStringConcat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringStringConcat {
                    @StoredProcedure
                    public static void run(String left, String right) {
                        String both = left + right;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        DeclareVariable both = (DeclareVariable) block.declarations().getFirst();
        FunctionCallExpression concat = assertInstanceOf(FunctionCallExpression.class, both.initializer());
        assertEquals("__titan_str_concat", concat.name());
        assertEquals(2, concat.arguments().size());
    }

    @Test
    void foldsConstantIndexArithmeticInsideLoweredStringMethodArguments() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringStringMethodConstantArithmetic.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringStringMethodConstantArithmetic {
                    @StoredProcedure
                    public static void run(String input) {
                        String mid = input.substring(2 + 1, 7 - 2);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        DeclareVariable mid = (DeclareVariable) block.declarations().getFirst();
        FunctionCallExpression substringCall = assertInstanceOf(FunctionCallExpression.class, mid.initializer());
        assertEquals("__titan_str_substring", substringCall.name());
        assertEquals(3, ((LiteralExpression) substringCall.arguments().get(1)).value());
        assertEquals(5, ((LiteralExpression) substringCall.arguments().get(2)).value());
    }

    @Test
    void lowersMathMethodInvocationsIntoMathTranslationExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringMathMethods.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringMathMethods {
                    @StoredProcedure
                    public static void run(double x, double y) {
                        double abs = Math.abs(x);
                        double max = Math.max(x, y);
                        double log10 = Math.log10(x);
                        long rounded = Math.round(x);
                        double rand = Math.random();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(5, block.declarations().size());

        DeclareVariable abs = (DeclareVariable) block.declarations().get(0);
        assertEquals("ABS", ((FunctionCallExpression) abs.initializer()).name());

        DeclareVariable max = (DeclareVariable) block.declarations().get(1);
        assertEquals("GREATEST", ((FunctionCallExpression) max.initializer()).name());

        DeclareVariable log10 = (DeclareVariable) block.declarations().get(2);
        assertEquals("__titan_math_log10", ((FunctionCallExpression) log10.initializer()).name());

        DeclareVariable rounded = (DeclareVariable) block.declarations().get(3);
        assertEquals("__titan_math_round", ((FunctionCallExpression) rounded.initializer()).name());

        DeclareVariable rand = (DeclareVariable) block.declarations().get(4);
        assertEquals("__titan_math_random", ((FunctionCallExpression) rand.initializer()).name());
    }

    @Test
    void lowersBigDecimalMethodInvocationsIntoArithmeticCompareAndRoundExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringBigDecimalMethods.java");
        Files.writeString(sourceFile, """
                import java.math.BigDecimal;
                import java.math.BigInteger;
                import java.math.RoundingMode;
                import titan.dsl.StoredProcedure;

                class LoweringBigDecimalMethods {
                    @StoredProcedure
                    public static void run(BigDecimal a, BigDecimal b) {
                        BigDecimal sum = a.add(b);
                        BigDecimal diff = a.subtract(b);
                        BigDecimal product = a.multiply(b);
                        int cmp = a.compareTo(b);
                        BigDecimal scaled = a.setScale(2);
                        BigDecimal scaledHalfUp = a.setScale(2, RoundingMode.HALF_UP);
                        BigInteger total = BigInteger.valueOf(7);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(7, block.declarations().size());

        DeclareVariable sum = (DeclareVariable) block.declarations().get(0);
        BinaryOpExpression sumExpr = assertInstanceOf(BinaryOpExpression.class, sum.initializer());
        assertEquals(BinaryOperator.ADD, sumExpr.operator());

        // F-1: BigDecimal/BigInteger locals must be declared NUMERIC(38,10), not TEXT.
        TNumericType sumType = assertInstanceOf(TNumericType.class, sum.type());
        assertEquals(38, sumType.precision());
        assertEquals(10, sumType.scale());

        DeclareVariable diff = (DeclareVariable) block.declarations().get(1);
        BinaryOpExpression diffExpr = assertInstanceOf(BinaryOpExpression.class, diff.initializer());
        assertEquals(BinaryOperator.SUBTRACT, diffExpr.operator());

        DeclareVariable product = (DeclareVariable) block.declarations().get(2);
        BinaryOpExpression productExpr = assertInstanceOf(BinaryOpExpression.class, product.initializer());
        assertEquals(BinaryOperator.MULTIPLY, productExpr.operator());

        DeclareVariable cmp = (DeclareVariable) block.declarations().get(3);
        assertInstanceOf(CaseWhenExpression.class, cmp.initializer());

        DeclareVariable scaled = (DeclareVariable) block.declarations().get(4);
        FunctionCallExpression scaledExpr = assertInstanceOf(FunctionCallExpression.class, scaled.initializer());
        assertEquals("ROUND", scaledExpr.name());

        DeclareVariable scaledHalfUp = (DeclareVariable) block.declarations().get(5);
        FunctionCallExpression scaledHalfUpExpr = assertInstanceOf(FunctionCallExpression.class, scaledHalfUp.initializer());
        assertEquals("ROUND", scaledHalfUpExpr.name());
        assertInstanceOf(TNumericType.class, scaledHalfUp.type());

        DeclareVariable total = (DeclareVariable) block.declarations().get(6);
        assertInstanceOf(TNumericType.class, total.type());
    }

    @Test
    void rejectsBigDecimalSetScaleWithUnsupportedRoundingMode() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringBigDecimalMethodsInvalidRounding.java");
        Files.writeString(sourceFile, """
                import java.math.BigDecimal;
                import java.math.RoundingMode;
                import titan.dsl.StoredProcedure;

                class LoweringBigDecimalMethodsInvalidRounding {
                    @StoredProcedure
                    public static void run(BigDecimal a) {
                        BigDecimal scaled = a.setScale(2, RoundingMode.DOWN);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(error.getMessage().contains("Unsupported BigDecimal.setScale rounding mode"));
    }

    @Test
    void lowersJavaTimeNowAndDateArithmeticMethodInvocations() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringJavaTimeMethods.java");
        Files.writeString(sourceFile, """
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.time.OffsetDateTime;
                import java.time.ZonedDateTime;
                import titan.dsl.StoredProcedure;

                class LoweringJavaTimeMethods {
                    @StoredProcedure
                    public static void run() {
                        LocalDate today = LocalDate.now();
                        LocalDate tomorrow = today.plusDays(1);
                        LocalDate yesterday = today.minusDays(1);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime later = now.plusHours(3);
                        LocalDate justDate = now.toLocalDate();
                        Instant instantNow = Instant.now();
                        Instant epochInstant = Instant.ofEpochMilli(1234L);
                        ZonedDateTime zonedNow = ZonedDateTime.now();
                        OffsetDateTime offsetNow = OffsetDateTime.now();
                        Instant fromZoned = zonedNow.toInstant();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(11, block.declarations().size());

        DeclareVariable today = (DeclareVariable) block.declarations().get(0);
        FunctionCallExpression todayExpr = assertInstanceOf(FunctionCallExpression.class, today.initializer());
        assertEquals("__titan_time_localdate_now", todayExpr.name());

        DeclareVariable tomorrow = (DeclareVariable) block.declarations().get(1);
        FunctionCallExpression tomorrowExpr = assertInstanceOf(FunctionCallExpression.class, tomorrow.initializer());
        assertEquals("__titan_time_plus_days", tomorrowExpr.name());

        DeclareVariable later = (DeclareVariable) block.declarations().get(4);
        FunctionCallExpression laterExpr = assertInstanceOf(FunctionCallExpression.class, later.initializer());
        assertEquals("__titan_time_plus_hours", laterExpr.name());

        DeclareVariable justDate = (DeclareVariable) block.declarations().get(5);
        FunctionCallExpression justDateExpr = assertInstanceOf(FunctionCallExpression.class, justDate.initializer());
        assertEquals("__titan_time_to_local_date", justDateExpr.name());

        DeclareVariable instantNow = (DeclareVariable) block.declarations().get(6);
        FunctionCallExpression instantNowExpr = assertInstanceOf(FunctionCallExpression.class, instantNow.initializer());
        assertEquals("__titan_time_instant_now", instantNowExpr.name());
        assertInstanceOf(TTimestampTzType.class, instantNow.type());

        DeclareVariable epochInstant = (DeclareVariable) block.declarations().get(7);
        FunctionCallExpression epochInstantExpr = assertInstanceOf(FunctionCallExpression.class, epochInstant.initializer());
        assertEquals("__titan_time_instant_of_epoch_millis", epochInstantExpr.name());
        assertInstanceOf(TTimestampTzType.class, epochInstant.type());

        DeclareVariable zonedNow = (DeclareVariable) block.declarations().get(8);
        FunctionCallExpression zonedNowExpr = assertInstanceOf(FunctionCallExpression.class, zonedNow.initializer());
        assertEquals("__titan_time_zoneddatetime_now", zonedNowExpr.name());
        assertInstanceOf(TTimestampTzType.class, zonedNow.type());

        DeclareVariable offsetNow = (DeclareVariable) block.declarations().get(9);
        assertInstanceOf(TTimestampTzType.class, offsetNow.type());

        DeclareVariable fromZoned = (DeclareVariable) block.declarations().get(10);
        FunctionCallExpression fromZonedExpr = assertInstanceOf(FunctionCallExpression.class, fromZoned.initializer());
        assertEquals("__titan_time_to_instant", fromZonedExpr.name());
        assertInstanceOf(TTimestampTzType.class, fromZoned.type());
    }

    @Test
    void lowersDurationAndPeriodIntoIntervalFunctions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDurationPeriodMethods.java");
        Files.writeString(sourceFile, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredProcedure;

                class LoweringDurationPeriodMethods {
                    @StoredProcedure
                    public static void run() {
                        Duration timeout = Duration.ofSeconds(90);
                        Period grace = Period.ofDays(2);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime plusTimeout = now.plus(timeout);
                        LocalDateTime minusGrace = now.minus(grace);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(5, block.declarations().size());

        DeclareVariable timeout = (DeclareVariable) block.declarations().get(0);
        assertInstanceOf(TDurationType.class, timeout.type());
        FunctionCallExpression timeoutExpr = assertInstanceOf(FunctionCallExpression.class, timeout.initializer());
        assertEquals("__titan_time_duration_of_seconds", timeoutExpr.name());

        DeclareVariable grace = (DeclareVariable) block.declarations().get(1);
        assertInstanceOf(TPeriodType.class, grace.type());
        FunctionCallExpression graceExpr = assertInstanceOf(FunctionCallExpression.class, grace.initializer());
        assertEquals("__titan_time_period_of_days", graceExpr.name());

        DeclareVariable plusTimeout = (DeclareVariable) block.declarations().get(3);
        FunctionCallExpression plusTimeoutExpr = assertInstanceOf(FunctionCallExpression.class, plusTimeout.initializer());
        assertEquals("__titan_time_plus_amount", plusTimeoutExpr.name());

        DeclareVariable minusGrace = (DeclareVariable) block.declarations().get(4);
        FunctionCallExpression minusGraceExpr = assertInstanceOf(FunctionCallExpression.class, minusGrace.initializer());
        assertEquals("__titan_time_minus_amount", minusGraceExpr.name());
    }

    @Test
    void lowersTemporalZeroAndAdditionalUnitsIntoIntervalFunctions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTemporalZeroAndUnits.java");
        Files.writeString(sourceFile, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredProcedure;

                class LoweringTemporalZeroAndUnits {
                    @StoredProcedure
                    public static void run() {
                        Duration idle = Duration.zero();
                        Duration retry = Duration.ofMinutes(5);
                        Period sameDay = Period.zero();
                        Period nextYear = Period.ofYears(1);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime stillNow = now.plus(idle);
                        LocalDateTime beforeRetry = now.minus(retry);
                        LocalDateTime sameMoment = now.plus(sameDay);
                        LocalDateTime nextYearMoment = now.plus(nextYear);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(9, block.declarations().size());

        DeclareVariable idle = (DeclareVariable) block.declarations().get(0);
        FunctionCallExpression idleExpr = assertInstanceOf(FunctionCallExpression.class, idle.initializer());
        assertEquals("__titan_time_duration_zero", idleExpr.name());

        DeclareVariable retry = (DeclareVariable) block.declarations().get(1);
        FunctionCallExpression retryExpr = assertInstanceOf(FunctionCallExpression.class, retry.initializer());
        assertEquals("__titan_time_duration_of_minutes", retryExpr.name());

        DeclareVariable sameDay = (DeclareVariable) block.declarations().get(2);
        FunctionCallExpression sameDayExpr = assertInstanceOf(FunctionCallExpression.class, sameDay.initializer());
        assertEquals("__titan_time_period_zero", sameDayExpr.name());

        DeclareVariable nextYear = (DeclareVariable) block.declarations().get(3);
        FunctionCallExpression nextYearExpr = assertInstanceOf(FunctionCallExpression.class, nextYear.initializer());
        assertEquals("__titan_time_period_of_years", nextYearExpr.name());

        DeclareVariable stillNow = (DeclareVariable) block.declarations().get(5);
        FunctionCallExpression stillNowExpr = assertInstanceOf(FunctionCallExpression.class, stillNow.initializer());
        assertEquals("__titan_time_plus_amount", stillNowExpr.name());

        DeclareVariable beforeRetry = (DeclareVariable) block.declarations().get(6);
        FunctionCallExpression beforeRetryExpr = assertInstanceOf(FunctionCallExpression.class, beforeRetry.initializer());
        assertEquals("__titan_time_minus_amount", beforeRetryExpr.name());

        DeclareVariable sameMoment = (DeclareVariable) block.declarations().get(7);
        FunctionCallExpression sameMomentExpr = assertInstanceOf(FunctionCallExpression.class, sameMoment.initializer());
        assertEquals("__titan_time_plus_amount", sameMomentExpr.name());

        DeclareVariable nextYearMoment = (DeclareVariable) block.declarations().get(8);
        FunctionCallExpression nextYearMomentExpr = assertInstanceOf(FunctionCallExpression.class, nextYearMoment.initializer());
        assertEquals("__titan_time_plus_amount", nextYearMomentExpr.name());
    }

    @Test
    void lowersArrowStyleSwitchExpressionIntoCaseWhenExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSwitchExpression.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringSwitchExpression {
                    @StoredFunction
                    public static int run(int input) {
                        int score = switch (input) {
                            case 1, 2 -> 10;
                            case 3 -> 20;
                            default -> 0;
                        };
                        return score;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(1, block.declarations().size());

        DeclareVariable score = (DeclareVariable) block.declarations().getFirst();
        CaseWhenExpression switchExpr = assertInstanceOf(CaseWhenExpression.class, score.initializer());
        assertEquals(2, switchExpr.conditions().size());
        assertNotNull(switchExpr.elseValue());
        assertInstanceOf(LiteralExpression.class, switchExpr.elseValue());
    }

    @Test
    void lowersArrowStyleSwitchExpressionWithYieldBlockIntoCaseWhenExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSwitchExpressionYieldBlock.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringSwitchExpressionYieldBlock {
                    @StoredFunction
                    public static int run(int input) {
                        return switch (input) {
                            case 1 -> {
                                yield 10;
                            }
                            default -> {
                                yield 0;
                            }
                        };
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ReturnStatement statement = assertInstanceOf(ReturnStatement.class, block.statements().getFirst());
        CaseWhenExpression switchExpr = assertInstanceOf(CaseWhenExpression.class, statement.expression());
        assertEquals(1, switchExpr.conditions().size());
        assertNotNull(switchExpr.elseValue());
        assertInstanceOf(LiteralExpression.class, switchExpr.conditions().getFirst().value());
    }

    @Test
    void lowersArrowStyleSwitchStatementIntoIfChain() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSwitchStatement.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringSwitchStatement {
                    @StoredProcedure
                    public static void run(int input) {
                        switch (input) {
                            case 1, 2 -> {
                                int score = 10;
                            }
                            case 3 -> {
                                int score = 20;
                            }
                            default -> {
                                int score = 0;
                            }
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        Block switchBlock = assertInstanceOf(Block.class, block.statements().getFirst());
        assertEquals(1, switchBlock.declarations().size());
        assertTrue(switchBlock.declarations().getFirst() instanceof DeclareVariable);
        IfStatement switchIf = assertInstanceOf(IfStatement.class, switchBlock.statements().getFirst());
        assertEquals(1, switchIf.elseIfClauses().size());
        assertNotNull(switchIf.elseBlock());
    }

    @Test
    void lowersArrowStyleSwitchStatementExpressionBodies() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSwitchStatementExpressionBody.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringSwitchStatementExpressionBody {
                    @StoredProcedure
                    public static void run(int input) {
                        int score = 0;
                        switch (input) {
                            case 1 -> score = 10;
                            case 2 -> score += 5;
                            default -> log(score);
                        }
                    }

                    static void log(int score) {
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertInstanceOf(Assign.class, block.statements().getFirst());
        Block switchBlock = assertInstanceOf(Block.class, block.statements().get(1));
        IfStatement switchIf = assertInstanceOf(IfStatement.class, switchBlock.statements().getFirst());

        assertInstanceOf(Assign.class, switchIf.thenBlock().statements().getFirst());
        assertInstanceOf(Assign.class, switchIf.elseIfClauses().getFirst().block().statements().getFirst());
        assertNotNull(switchIf.elseBlock());
        assertInstanceOf(CallStatement.class, switchIf.elseBlock().statements().getFirst());
    }

    @Test
    void lowersExhaustiveEnumSwitchExpressionWithoutDefault() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringExhaustiveEnumSwitchExpression.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringExhaustiveEnumSwitchExpression {
                    enum Mode { BASIC, PRO }

                    @StoredFunction
                    public static int run(Mode mode) {
                        return switch (mode) {
                            case BASIC -> 1;
                            case PRO -> 2;
                        };
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ReturnStatement statement = assertInstanceOf(ReturnStatement.class, block.statements().getFirst());
        CaseWhenExpression switchExpr = assertInstanceOf(CaseWhenExpression.class, statement.expression());

        assertEquals(2, switchExpr.conditions().size());
        assertNotNull(switchExpr.elseValue());
        LiteralExpression elseLiteral = assertInstanceOf(LiteralExpression.class, switchExpr.elseValue());
        assertNull(elseLiteral.value());
    }

    @Test
    void resolvesVarInferenceAndWrapperPrimitiveNullabilityForDeclarations() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringTypeResolution.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringTypeResolution {
                    @StoredProcedure
                    public static void run() {
                        Integer wrapped = 42;
                        int primitive = wrapped;
                        var msg = "hello";
                        var flag = true;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(4, block.declarations().size());

        DeclareVariable wrapped = (DeclareVariable) block.declarations().get(0);
        assertEquals("wrapped", wrapped.name());
        assertInstanceOf(TIntType.class, wrapped.type());
        assertTrue(wrapped.nullable());

        DeclareVariable primitive = (DeclareVariable) block.declarations().get(1);
        assertEquals("primitive", primitive.name());
        assertInstanceOf(TIntType.class, primitive.type());
        assertFalse(primitive.nullable());

        DeclareVariable msg = (DeclareVariable) block.declarations().get(2);
        assertEquals("msg", msg.name());
        assertInstanceOf(TTextType.class, msg.type());
        assertTrue(msg.nullable());

        DeclareVariable flag = (DeclareVariable) block.declarations().get(3);
        assertEquals("flag", flag.name());
        assertInstanceOf(TBooleanType.class, flag.type());
        assertFalse(flag.nullable());
    }

    @Test
    void lowersOptionalMethodsIntoNullAwareExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringOptionalMethods.java");
        Files.writeString(sourceFile, """
                import java.util.Optional;
                import titan.dsl.StoredProcedure;

                class LoweringOptionalMethods {
                    @StoredProcedure
                    public static void run(Optional<String> opt) {
                        boolean present = opt.isPresent();
                        String value = opt.get();
                        String fallback = opt.orElse("x");
                        String must = opt.orElseThrow();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(4, block.declarations().size());

        DeclareVariable present = (DeclareVariable) block.declarations().get(0);
        assertInstanceOf(IsNotNullExpression.class, present.initializer());

        DeclareVariable value = (DeclareVariable) block.declarations().get(1);
        CaseWhenExpression valueGuard = assertInstanceOf(CaseWhenExpression.class, value.initializer());
        assertEquals(1, valueGuard.conditions().size());
        assertInstanceOf(IsNullExpression.class, valueGuard.conditions().getFirst().condition());
        FunctionCallExpression throwCall = assertInstanceOf(FunctionCallExpression.class, valueGuard.conditions().getFirst().value());
        assertEquals("__titan_optional_throw", throwCall.name());

        DeclareVariable fallback = (DeclareVariable) block.declarations().get(2);
        assertInstanceOf(CoalesceExpression.class, fallback.initializer());

        DeclareVariable must = (DeclareVariable) block.declarations().get(3);
        assertInstanceOf(CaseWhenExpression.class, must.initializer());
    }

    @Test
    void rejectsOptionalMapWithPositionedDiagnostic() throws Exception {
        // D8 (plan 2.2): Optional.map's Function argument (lambda/method reference) used to be
        // stringified into the SQL via source text; it is now rejected with a positioned
        // diagnostic and a suggestion. The supported Optional subset stays (see test above).
        Path sourceFile = tempDir.resolve("LoweringOptionalMap.java");
        Files.writeString(sourceFile, """
                import java.util.Optional;
                import titan.dsl.StoredProcedure;

                class LoweringOptionalMap {
                    @StoredProcedure
                    public static void run(Optional<String> opt) {
                        String mapped = opt.map(v -> v.trim()).orElse("empty");
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Optional.map"));
        assertTrue(thrown.getMessage().contains("LoweringOptionalMap.java:7"));
        assertTrue(thrown.getMessage().contains("isPresent()"));
    }

    @Test
    void rejectsOptionalOrElseThrowWithSupplierWithPositionedDiagnostic() throws Exception {
        // D8 (plan 2.2): orElseThrow(Supplier) used to stringify the lambda; rejected with a
        // positioned diagnostic. The no-argument orElseThrow() stays supported.
        Path sourceFile = tempDir.resolve("LoweringOptionalOrElseThrow.java");
        Files.writeString(sourceFile, """
                import java.util.Optional;
                import titan.dsl.StoredProcedure;

                class LoweringOptionalOrElseThrow {
                    @StoredProcedure
                    public static void run(Optional<String> opt) {
                        String must = opt.orElseThrow(() -> new RuntimeException("missing"));
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Optional.orElseThrow with a Supplier argument"));
        assertTrue(thrown.getMessage().contains("LoweringOptionalOrElseThrow.java:7"));
        assertTrue(thrown.getMessage().contains("no-argument orElseThrow()"));
    }

    @Test
    void lowersEnumConstantAndFieldAccessToEnumHelpers() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringEnumFieldAccess.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringEnumFieldAccess {
                    enum PlanTier {
                        FREE(0), PRO(100);
                        final int weight;
                        PlanTier(int weight) { this.weight = weight; }
                    }

                    @StoredProcedure
                    public static void run() {
                        String tier = PlanTier.PRO.name();
                        String weight = PlanTier.PRO.weight;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        DeclareVariable weight = (DeclareVariable) block.declarations().get(1);
        FunctionCallExpression helperCall = assertInstanceOf(FunctionCallExpression.class, weight.initializer());
        // B-2 (TG-BLK-005): nested enums are qualified by their enclosing type; member
        // accessors join with "__".
        assertEquals("__enum_lowering_enum_field_access_plan_tier__weight", helperCall.name());
        assertEquals(1, helperCall.arguments().size());
        LiteralExpression enumValue = assertInstanceOf(LiteralExpression.class, helperCall.arguments().getFirst());
        assertEquals("PRO", enumValue.value());
    }

    @Test
    void lowersEnumGetterCallsToEnumHelpers() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringEnumGetterCall.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringEnumGetterCall {
                    enum PlanTier {
                        FREE(0), PRO(100);
                        final int weight;
                        PlanTier(int weight) { this.weight = weight; }
                        int getWeight() { return weight; }
                    }

                    @StoredProcedure
                    public static void run(PlanTier tier) {
                        String weight = tier.getWeight();
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        DeclareVariable weight = (DeclareVariable) block.declarations().getFirst();
        FunctionCallExpression helperCall = assertInstanceOf(FunctionCallExpression.class, weight.initializer());
        // B-2 (TG-BLK-005): nested enums are qualified by their enclosing type; member
        // accessors join with "__".
        assertEquals("__enum_lowering_enum_getter_call_plan_tier__get_weight", helperCall.name());
        VariableRefExpression receiver = assertInstanceOf(VariableRefExpression.class, helperCall.arguments().getFirst());
        assertEquals("tier", receiver.name());
    }

    @Test
    void lowersStaticFieldReadAndWriteToRuntimeHelperCalls() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringStaticFields.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringStaticFields {
                    static int counter = 0;

                    @StoredProcedure
                    public static void run() {
                        int current = counter;
                        counter = current + 1;
                        counter++;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        DeclareVariable current = (DeclareVariable) block.declarations().getFirst();
        FunctionCallExpression getter = assertInstanceOf(FunctionCallExpression.class, current.initializer());
        assertEquals("__titan_static_get", getter.name());

        assertInstanceOf(Assign.class, block.statements().get(0));

        CallStatement assignSet = assertInstanceOf(CallStatement.class, block.statements().get(1));
        assertEquals("__titan_static_set", assignSet.procedureName());

        CallStatement incrementSet = assertInstanceOf(CallStatement.class, block.statements().get(2));
        assertEquals("__titan_static_set", incrementSet.procedureName());
    }

    @Test
    void constantFoldsStaticFinalConstantsInDslValuePositionsToLiterals() throws Exception {
        // G5 (spike B5): a reference to a `static final` compile-time constant (String — including a
        // constant concatenation — or a primitive) in a DSL set/where/arithmetic value position
        // inlines to its literal instead of routing through the __titan_static_get runtime-state
        // machinery, so no spurious titan_runtime deploy dependency is pulled in.
        Path sourceFile = tempDir.resolve("LoweringConstantFold.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringConstantFold {
                    static final EventsTable EVENTS = new EventsTable();
                    static final String PREFIX = "management.";
                    static final String COMMAND = PREFIX + "importModelDocument";
                    static final int PRIORITY = 7;

                    @StoredProcedure
                    public static void run(String id) {
                        insertInto(EVENTS)
                                .set(EVENTS.COMMAND_NAME, COMMAND)
                                .set(EVENTS.PRIORITY, PRIORITY)
                                .execute();
                        update(EVENTS)
                                .set(EVENTS.PRIORITY, PRIORITY)
                                .where(EVENTS.COMMAND_NAME.eq(COMMAND))
                                .execute();
                    }

                    static final class EventsTable extends Table<Object> {
                        final Column<String> COMMAND_NAME = column("command_name", SQLType.TEXT, Nullability.NOT_NULL);
                        final Column<Integer> PRIORITY = column("priority", SQLType.INTEGER, Nullability.NOT_NULL);

                        EventsTable() {
                            super("events", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(2, block.statements().size());

        ExecuteSqlStatement insertExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        InsertSql insert = assertInstanceOf(InsertSql.class, insertExec.sqlNode());

        // set(EVENTS.COMMAND_NAME, COMMAND): the concatenated String constant folds to a single
        // TEXT literal carrying the JLS compile-time-constant value, not a static_get call.
        LiteralExpression commandLiteral = assertInstanceOf(LiteralExpression.class, insert.values().get(0));
        assertEquals("management.importModelDocument", commandLiteral.value());
        assertInstanceOf(TTextType.class, commandLiteral.type());

        // set(EVENTS.PRIORITY, PRIORITY): the int constant folds to an INT literal.
        LiteralExpression priorityLiteral = assertInstanceOf(LiteralExpression.class, insert.values().get(1));
        assertEquals(7, priorityLiteral.value());
        assertInstanceOf(TIntType.class, priorityLiteral.type());

        ExecuteSqlStatement updateExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        UpdateSql update = assertInstanceOf(UpdateSql.class, updateExec.sqlNode());
        assertEquals(7, assertInstanceOf(LiteralExpression.class, update.sets().getFirst().value()).value());

        // where(EVENTS.COMMAND_NAME.eq(COMMAND)): the constant on the RHS of the comparison folds too.
        ExpressionNode whereNode = update.where() instanceof CoalesceExpression coalesce
                ? coalesce.expressions().getFirst()
                : update.where();
        BinaryOpExpression where = assertInstanceOf(BinaryOpExpression.class, whereNode);
        assertEquals(BinaryOperator.EQUAL, where.operator());
        assertEquals("management.importModelDocument",
                assertInstanceOf(LiteralExpression.class, where.right()).value());

        // Belt-and-braces: the emitted SQL for both dialects carries the inlined literals and never
        // the static_get runtime helper — proving the spurious titan_runtime dependency is gone.
        String pg = new PostgreSqlEmitter().emitProcedure("app", "run", SecurityMode.INVOKER, block);
        String mysql = new MySqlEmitter().emitProcedure("app", "run", SecurityMode.INVOKER, block);
        assertFalse(pg.contains("static_get"), "PG SQL must not reference static_get: " + pg);
        assertFalse(mysql.contains("static_get"), "MySQL SQL must not reference static_get: " + mysql);
        assertTrue(pg.contains("management.importModelDocument"), pg);
        assertTrue(mysql.contains("management.importModelDocument"), mysql);
    }

    @Test
    void doesNotConstantFoldMutableOrNonConstantStaticFields() throws Exception {
        // G5 regression guard: only JLS compile-time constants (getConstantValue() != null) fold.
        // A non-final static field (mutable) and a final-but-NOT-compile-time-constant static field
        // (a Duration, evaluated at class-init, getConstantValue() == null) must STILL route
        // through the static_get/static_set runtime-state machinery — the fold must not over-reach.
        Path sourceFile = tempDir.resolve("LoweringNoFoldMutable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;
                import java.time.Duration;

                class LoweringNoFoldMutable {
                    static final EventsTable EVENTS = new EventsTable();
                    static int sequence = 0;
                    static final long TTL_SECONDS = Duration.ofMinutes(5).getSeconds();

                    @StoredProcedure
                    public static void run() {
                        update(EVENTS).set(EVENTS.PRIORITY, sequence).execute();
                        update(EVENTS).set(EVENTS.TTL, TTL_SECONDS).execute();
                    }

                    static final class EventsTable extends Table<Object> {
                        final Column<Integer> PRIORITY = column("priority", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Long> TTL = column("ttl", SQLType.BIGINT, Nullability.NOT_NULL);

                        EventsTable() {
                            super("events", "public");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(2, block.statements().size());

        // set(EVENTS.PRIORITY, sequence): non-final static field still lowers to static_get.
        ExecuteSqlStatement sequenceExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(0));
        UpdateSql sequenceUpdate = assertInstanceOf(UpdateSql.class, sequenceExec.sqlNode());
        FunctionCallExpression sequenceGet =
                assertInstanceOf(FunctionCallExpression.class, sequenceUpdate.sets().getFirst().value());
        assertEquals("__titan_static_get", sequenceGet.name());
        assertEquals("LoweringNoFoldMutable#sequence",
                assertInstanceOf(LiteralExpression.class, sequenceGet.arguments().getFirst()).value());

        // set(EVENTS.TTL, TTL_SECONDS): final but NOT a compile-time constant (computed from a
        // Duration call), so getConstantValue() == null and it STILL resolves through static_get.
        ExecuteSqlStatement ttlExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().get(1));
        UpdateSql ttlUpdate = assertInstanceOf(UpdateSql.class, ttlExec.sqlNode());
        FunctionCallExpression ttlGet =
                assertInstanceOf(FunctionCallExpression.class, ttlUpdate.sets().getFirst().value());
        assertEquals("__titan_static_get", ttlGet.name());
    }

    @Test
    void lowersWindowFunctionsIntoSqlExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslWindowFunctions.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslWindowFunctions {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(
                                rowNumber().over(partitionBy(ACCOUNTS.PLAN_ID).orderBy(ACCOUNTS.ID.asc()).rowsBetween(unboundedPreceding(), currentRow())),
                                lag(ACCOUNTS.EMAIL, 2).over(partitionBy(ACCOUNTS.PLAN_ID).orderBy(ACCOUNTS.ID.asc()).rangeBetween(preceding(1), following(1)))
                        )
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, 255, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        // Plan 1.4b: structured WindowFunctionExpression nodes, never pre-rendered SQL text.
        WindowFunctionExpression rowNumber = assertInstanceOf(WindowFunctionExpression.class, select.columns().get(0).expression());
        WindowFunctionExpression lag = assertInstanceOf(WindowFunctionExpression.class, select.columns().get(1).expression());

        assertEquals("ROW_NUMBER", rowNumber.function());
        assertTrue(rowNumber.arguments().isEmpty());
        ColumnRefExpression partition = assertInstanceOf(ColumnRefExpression.class, rowNumber.spec().partitionBy().getFirst());
        assertEquals("accounts", partition.table());
        assertEquals("plan_id", partition.column());
        OrderBySpec ordering = rowNumber.spec().orderBy().getFirst();
        assertEquals(SortDirection.ASC, ordering.direction());
        ColumnRefExpression orderColumn = assertInstanceOf(ColumnRefExpression.class, ordering.expression());
        assertEquals("id", orderColumn.column());
        assertEquals(WindowFrameUnit.ROWS, rowNumber.spec().frame().unit());
        assertEquals(WindowFrameBoundKind.UNBOUNDED_PRECEDING, rowNumber.spec().frame().start().kind());
        assertEquals(WindowFrameBoundKind.CURRENT_ROW, rowNumber.spec().frame().end().kind());

        assertEquals("LAG", lag.function());
        assertEquals(2, lag.arguments().size());
        ColumnRefExpression lagColumn = assertInstanceOf(ColumnRefExpression.class, lag.arguments().get(0));
        assertEquals("email", lagColumn.column());
        LiteralExpression lagOffset = assertInstanceOf(LiteralExpression.class, lag.arguments().get(1));
        assertEquals(2, lagOffset.value());
        assertEquals(WindowFrameUnit.RANGE, lag.spec().frame().unit());
        assertEquals(WindowFrameBoundKind.PRECEDING, lag.spec().frame().start().kind());
        LiteralExpression precedingOffset = assertInstanceOf(LiteralExpression.class, lag.spec().frame().start().offset());
        assertEquals(1, precedingOffset.value());
        assertEquals(WindowFrameBoundKind.FOLLOWING, lag.spec().frame().end().kind());
    }

    @Test
    void lowersBareEnumSwitchCaseLabelsToTextLiterals() throws Exception {
        // F-2: bare enum case labels must lower to text literals, not unbound SQL identifiers.
        Path sourceFile = tempDir.resolve("LoweringBareEnumSwitchLabels.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringBareEnumSwitchLabels {
                    enum Mode { BASIC, PRO }

                    @StoredProcedure
                    public static void run(Mode mode) {
                        switch (mode) {
                            case BASIC -> {
                                int x = 1;
                            }
                            case PRO -> {
                                int x = 2;
                            }
                            default -> {
                                int x = 0;
                            }
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        Block switchBlock = assertInstanceOf(Block.class, block.statements().getFirst());
        IfStatement switchIf = assertInstanceOf(IfStatement.class, switchBlock.statements().getFirst());

        BinaryOpExpression basicCondition = assertInstanceOf(BinaryOpExpression.class, unwrapNullSafeCondition(switchIf.condition()));
        assertEquals(BinaryOperator.EQUAL, basicCondition.operator());
        LiteralExpression basicLabel = assertInstanceOf(LiteralExpression.class, basicCondition.right());
        assertEquals("BASIC", basicLabel.value());

        BinaryOpExpression proCondition = assertInstanceOf(
                BinaryOpExpression.class,
                unwrapNullSafeCondition(switchIf.elseIfClauses().getFirst().condition()));
        LiteralExpression proLabel = assertInstanceOf(LiteralExpression.class, proCondition.right());
        assertEquals("PRO", proLabel.value());
    }

    private static ExpressionNode unwrapNullSafeCondition(ExpressionNode condition) {
        return condition instanceof CoalesceExpression coalesce ? coalesce.expressions().getFirst() : condition;
    }

    @Test
    void generatesDeterministicSwitchTempNamesAcrossLowerings() throws Exception {
        // F-5: switch temp names must be a pure function of the source, not TreePath identity hashes.
        Path sourceFile = tempDir.resolve("LoweringDeterministicSwitchTemp.java");
        String source = """
                import titan.dsl.StoredProcedure;

                class LoweringDeterministicSwitchTemp {
                    @StoredProcedure
                    public static void run(int input) {
                        switch (input) {
                            case 1 -> {
                                int x = 1;
                            }
                            default -> {
                                int x = 0;
                            }
                        }
                    }
                }
                """;
        Files.writeString(sourceFile, source);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        Block switchBlock = assertInstanceOf(Block.class, block.statements().getFirst());
        DeclareVariable switchTemp = assertInstanceOf(DeclareVariable.class, switchBlock.declarations().getFirst());
        assertEquals("__titan_switch_value_" + source.indexOf("switch (input)"), switchTemp.name());

        ParsedSources reparsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> rediscovered = new EntryPointDiscovery().discover(reparsed);
        Block relowered = new JavaToTirLowerer().lower(reparsed, rediscovered).values().iterator().next();
        assertEquals(block, relowered);
    }

    @Test
    void lowersStringCompoundPlusAssignmentIntoConcatTranslationExpression() throws Exception {
        // F-6: String += must lower to __titan_str_concat, not numeric ADD.
        Path sourceFile = tempDir.resolve("LoweringStringCompoundAssignment.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringStringCompoundAssignment {
                    @StoredProcedure
                    public static void run(String s, int n) {
                        s += "x";
                        n += 1;
                        switch (n) {
                            case 1 -> s += "y";
                            default -> {
                            }
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        List<Assign> assigns = block.statements().stream()
                .filter(Assign.class::isInstance)
                .map(Assign.class::cast)
                .toList();
        Assign stringAppend = assigns.get(0);
        FunctionCallExpression concat = assertInstanceOf(FunctionCallExpression.class, stringAppend.expression());
        assertEquals("__titan_str_concat", concat.name());
        assertEquals(2, concat.arguments().size());

        Assign numericAdd = assigns.get(1);
        BinaryOpExpression add = assertInstanceOf(BinaryOpExpression.class, numericAdd.expression());
        assertEquals(BinaryOperator.ADD, add.operator());

        Block switchBlock = block.statements().stream()
                .filter(Block.class::isInstance)
                .map(Block.class::cast)
                .findFirst()
                .orElseThrow();
        IfStatement switchIf = assertInstanceOf(IfStatement.class, switchBlock.statements().getFirst());
        Assign caseAppend = switchIf.thenBlock().statements().stream()
                .filter(Assign.class::isInstance)
                .map(Assign.class::cast)
                .findFirst()
                .orElseThrow();
        FunctionCallExpression caseConcat = assertInstanceOf(FunctionCallExpression.class, caseAppend.expression());
        assertEquals("__titan_str_concat", caseConcat.name());
    }

    @Test
    void rejectsUnsupportedDslChainStepInsteadOfSilentlyDroppingIt() throws Exception {
        // F-7: unrecognized DSL chain steps must fail loudly instead of being silently skipped.
        Path sourceFile = tempDir.resolve("LoweringUnsupportedDslChainStep.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringUnsupportedDslChainStep {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .offset(5)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("offset"));
    }

    @Test
    void rejectsDslLimitStepWithNonLiteralArgumentInsteadOfSilentlyDroppingIt() throws Exception {
        // F-7 (§10.1 audit): only .limit(<int literal>) is recognized; .limit(variable) must
        // fail loudly with a positioned diagnostic instead of being silently skipped.
        Path sourceFile = tempDir.resolve("LoweringLimitVariableDslChainStep.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringLimitVariableDslChainStep {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(int rowCount) {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .limit(rowCount)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints)
        );
        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("limit"));
        assertTrue(error.getMessage().contains("LoweringLimitVariableDslChainStep.java"));
    }

    @Test
    void lowersEmptyStatementsToNoOps() throws Exception {
        // §10.1 audit: a lone ';' is legal Java with no effect. It previously fell into the
        // statement default-deny branch and failed lowering; it must lower to nothing, and an
        // empty statement used as a control-structure body must behave like an empty '{}' block.
        Path sourceFile = tempDir.resolve("LoweringEmptyStatement.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringEmptyStatement {
                    @StoredProcedure
                    public static void run(int value) {
                        ;
                        int doubled = value * 2;
                        ;;
                        if (doubled > 10) ;
                        ;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        // The stray semicolons contribute nothing: besides the NullAnalysisPass parameter
        // guard, only the declaration-initializing Assign and the lowered IF remain, and the
        // IF's empty-statement body is an empty no-op block.
        assertEquals(1, block.declarations().size());
        List<StatementNode> statements = block.statements().stream()
                .filter(statement -> !(statement instanceof NullGuardStatement))
                .toList();
        assertEquals(2, statements.size(), statements.toString());
        assertInstanceOf(Assign.class, statements.get(0));
        IfStatement ifStatement = assertInstanceOf(IfStatement.class, statements.get(1));
        Block thenBlock = ifStatement.thenBlock();
        assertEquals(1, thenBlock.statements().size());
        Block emptyBody = assertInstanceOf(Block.class, thenBlock.statements().getFirst());
        assertTrue(emptyBody.declarations().isEmpty());
        assertTrue(emptyBody.statements().isEmpty());
    }

    @Test
    void lowersLogicalComplementIntoNotExpression() throws Exception {
        // F-9: !x must lower to NotExpression instead of crashing the lowerer.
        Path sourceFile = tempDir.resolve("LoweringLogicalComplement.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringLogicalComplement {
                    @StoredProcedure
                    public static void run(boolean flag) {
                        boolean inverted = !flag;
                        if (!inverted) {
                            inverted = !flag;
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        DeclareVariable inverted = assertInstanceOf(DeclareVariable.class, block.declarations().getFirst());
        NotExpression initializer = assertInstanceOf(NotExpression.class, inverted.initializer());
        VariableRefExpression flagRef = assertInstanceOf(VariableRefExpression.class, initializer.expression());
        assertEquals("flag", flagRef.name());

        IfStatement ifStatement = block.statements().stream()
                .filter(IfStatement.class::isInstance)
                .map(IfStatement.class::cast)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(NotExpression.class, unwrapNullSafeCondition(ifStatement.condition()));
        Assign assign = assertInstanceOf(Assign.class, ifStatement.thenBlock().statements().getFirst());
        assertInstanceOf(NotExpression.class, assign.expression());
    }

    @Test
    void doesNotRewriteUserHelperMethodsThatShareStringOrBigDecimalNames() throws Exception {
        // F-3: name-only dispatch must not rewrite user types whose methods collide with String/BigDecimal names.
        // Sizer is a record because locals of arbitrary user classes no longer fall back to TEXT:
        // JavaTypeToTirMapper raises TITAN-E001 for them (plan 1.2), and FeatureValidator already
        // rejects instance dispatch on such types at the pipeline level.
        // B-1 (TG-BLK-007): these calls used to fall through to bare `length(...)`/`add(...)`
        // SQL calls; the lowerer now rejects them with a positioned diagnostic instead (it must
        // still never rewrite them to CHAR_LENGTH/+ — rejection proves no rewrite happened).
        Path sourceFile = tempDir.resolve("LoweringUserHelperNameCollisions.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringUserHelperNameCollisions {
                    @StoredProcedure
                    public static void run() {
                        Sizer sizer = Sizer.create();
                        int len = sizer.length();
                        int sum = sizer.add(5);
                    }
                }

                record Sizer() {
                    static Sizer create() {
                        return new Sizer();
                    }

                    int length() {
                        return 1;
                    }

                    int add(int value) {
                        return value;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("TITAN-E001"));
        assertTrue(thrown.getMessage().contains("'length'"));
        assertTrue(thrown.getMessage().contains("Sizer"));
        assertTrue(thrown.getMessage().contains("LoweringUserHelperNameCollisions.java:7"));
    }

    // ------------------------------------------------------------------
    // B-1 (TG-BLK-007): unknown methods on supported receivers are positioned TITAN-E001
    // errors naming the method and the supported alternatives — never bare SQL calls.
    // ------------------------------------------------------------------

    @Test
    void rejectsUnknownStringMethodWithPositionedDiagnostic() throws Exception {
        // The TG-BLK-007 reproduction: String.equalsIgnoreCase used to emit a verbatim
        // `equalsignorecase(a, b)` SQL call that failed only at execution time.
        Path sourceFile = tempDir.resolve("LoweringUnknownStringMethod.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringUnknownStringMethod {
                    @StoredFunction
                    public static boolean isAdmin(String role, String expected) {
                        return role.equalsIgnoreCase(expected);
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("TITAN-E001"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("String method 'equalsIgnoreCase' has no SQL lowering"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("supported String methods"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("toLowerCase"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("LoweringUnknownStringMethod.java:6"), thrown.getMessage());
    }

    @Test
    void rejectsUnknownBigDecimalMethodWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringUnknownBigDecimalMethod.java");
        Files.writeString(sourceFile, """
                import java.math.BigDecimal;
                import titan.dsl.StoredFunction;

                class LoweringUnknownBigDecimalMethod {
                    @StoredFunction
                    public static BigDecimal half(BigDecimal value) {
                        return value.divide(BigDecimal.TEN);
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("BigDecimal method 'divide' has no SQL lowering"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("multiply"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("LoweringUnknownBigDecimalMethod.java:7"), thrown.getMessage());
    }

    @Test
    void rejectsUnknownMathMethodWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringUnknownMathMethod.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringUnknownMathMethod {
                    @StoredFunction
                    public static double cubeRoot(double value) {
                        return Math.cbrt(value);
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Math method 'cbrt' has no SQL lowering"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("sqrt"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("LoweringUnknownMathMethod.java:6"), thrown.getMessage());
    }

    @Test
    void rejectsUnknownOptionalMethodWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringUnknownOptionalMethod.java");
        Files.writeString(sourceFile, """
                import java.util.Optional;
                import titan.dsl.StoredFunction;

                class LoweringUnknownOptionalMethod {
                    @StoredFunction
                    public static boolean missing(Optional<String> value) {
                        return value.isEmpty();
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Optional method 'isEmpty' has no SQL lowering"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("isPresent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("LoweringUnknownOptionalMethod.java:7"), thrown.getMessage());
    }

    @Test
    void rejectsEnumMethodWithoutBackingFieldWithPositionedDiagnostic() throws Exception {
        // The emitters only create accessor functions for field-backed enum methods; lowering a
        // computed method to an __enum_* helper call referenced a function never created.
        Path sourceFile = tempDir.resolve("LoweringEnumComputedMethod.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringEnumComputedMethod {
                    enum PlanTier {
                        FREE(0), PRO(100);
                        final int weight;
                        PlanTier(int weight) { this.weight = weight; }
                        int doubledWeight() { return weight * 2; }
                    }

                    @StoredFunction
                    public static int doubled(PlanTier tier) {
                        return tier.doubledWeight();
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("Enum method 'LoweringEnumComputedMethod.PlanTier.doubledWeight' has no SQL lowering"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("backing enum field"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("LoweringEnumComputedMethod.java:13"), thrown.getMessage());
    }

    @Test
    void lowersEnumNameCallAsReceiverIdentity() throws Exception {
        // Enum values lower as their constant-name strings, so java.lang.Enum.name() is
        // identity — it used to fall through to a bare `name(...)` SQL call (B-1).
        Path sourceFile = tempDir.resolve("LoweringEnumNameCall.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringEnumNameCall {
                    enum PlanTier { FREE, PRO }

                    @StoredFunction
                    public static String label(PlanTier tier) {
                        return tier.name();
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        ReturnStatement returned = assertInstanceOf(ReturnStatement.class, block.statements().getLast());
        VariableRefExpression receiver = assertInstanceOf(VariableRefExpression.class, returned.expression());
        assertEquals("tier", receiver.name());
    }

    // ------------------------------------------------------------------
    // Plan 2.2 (F-10): throw messages lower as real expressions.
    // ------------------------------------------------------------------

    @Test
    void lowersThrowStringLiteralMessageAsTextLiteral() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringThrowLiteral.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringThrowLiteral {
                    @StoredProcedure
                    public static void run() {
                        throw new IllegalStateException("boom");
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        RaiseStatement raise = assertInstanceOf(RaiseStatement.class, block.statements().getFirst());
        assertEquals("55000", raise.sqlstate());
        // The message is the constructor argument's value — not the throw's source text
        // ('new IllegalStateException("boom")'), which was the F-10 bug.
        LiteralExpression message = assertInstanceOf(LiteralExpression.class, raise.message());
        assertEquals("boom", message.value());
    }

    @Test
    void lowersDynamicThrowMessageAsExpression() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringThrowDynamic.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringThrowDynamic {
                    @StoredProcedure
                    public static void run(int code) {
                        throw new RuntimeException("failed with code " + code);
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        RaiseStatement raise = assertInstanceOf(RaiseStatement.class, block.statements().getFirst());
        assertEquals("45000", raise.sqlstate());
        // Dynamic messages survive as expressions (F-10: they used to be dropped into the
        // source text of the whole throw).
        FunctionCallExpression concat = assertInstanceOf(FunctionCallExpression.class, raise.message());
        assertEquals("__titan_str_concat", concat.name());
        LiteralExpression prefix = assertInstanceOf(LiteralExpression.class, concat.arguments().getFirst());
        assertEquals("failed with code ", prefix.value());
        assertInstanceOf(VariableRefExpression.class, concat.arguments().get(1));
    }

    @Test
    void lowersNoArgExceptionConstructorWithDefaultMessage() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringThrowNoArg.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringThrowNoArg {
                    @StoredProcedure
                    public static void run() {
                        throw new RuntimeException();
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        RaiseStatement raise = assertInstanceOf(RaiseStatement.class, block.statements().getFirst());
        assertEquals("45000", raise.sqlstate());
        // No-argument constructors keep the historical default: the throw expression's source
        // text as the message (there is no message argument to carry).
        LiteralExpression message = assertInstanceOf(LiteralExpression.class, raise.message());
        assertEquals("new RuntimeException()", message.value());
    }

    // ------------------------------------------------------------------
    // Plan 2.2 (F-9 follow-up): supported casts lower to CastExpression.
    // ------------------------------------------------------------------

    @Test
    void lowersSupportedCastsPerClassification() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringCasts.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringCasts {
                    @StoredFunction
                    public static long run(int small, double ratio) {
                        long widened = (long) small;
                        double promoted = (double) small;
                        long truncated = (long) ratio;
                        int identity = (int) small;
                        return widened + truncated + identity;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        // int -> long: numeric widening, plain CAST.
        CastExpression widened = assertInstanceOf(CastExpression.class, initializerOf(block, "widened"));
        assertInstanceOf(TBigintType.class, widened.targetType());
        assertFalse(widened.truncating());

        // int -> double: widening to IEEE-754 binary64 per the type mapper.
        CastExpression promoted = assertInstanceOf(CastExpression.class, initializerOf(block, "promoted"));
        assertInstanceOf(TDoubleType.class, promoted.targetType());
        assertFalse(promoted.truncating());

        // double -> long: Java truncates toward zero; the cast carries the truncating flag so
        // the emitters compose TRUNC/TRUNCATE instead of letting SQL CAST round.
        CastExpression truncated = assertInstanceOf(CastExpression.class, initializerOf(block, "truncated"));
        assertInstanceOf(TBigintType.class, truncated.targetType());
        assertTrue(truncated.truncating());

        // (int) on an int is an identity cast: no CAST at all, just the operand.
        assertInstanceOf(VariableRefExpression.class, initializerOf(block, "identity"));
    }

    @Test
    void rejectsLongToIntNarrowingCastWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringNarrowingCast.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class LoweringNarrowingCast {
                    @StoredFunction
                    public static int run(long input) {
                        return (int) input;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException thrown = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(thrown.getMessage().contains("narrowing cast expression from long to int"));
        assertTrue(thrown.getMessage().contains("LoweringNarrowingCast.java:6"));
    }

    private static ExpressionNode initializerOf(Block block, String variableName) {
        return block.declarations().stream()
                .filter(declaration -> declaration instanceof DeclareVariable variable
                        && variable.name().equals(variableName))
                .map(declaration -> ((DeclareVariable) declaration).initializer())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no declaration named " + variableName));
    }

    // ------------------------------------------------------------------
    // Plan 2.2 (F-11): exception identity keyed on FQN with distinct SQLSTATEs.
    // ------------------------------------------------------------------

    @Test
    void allocatesDistinctSqlStatesAndDispatchesCatchOnExactUserException() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringUserExceptions.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringUserExceptions {
                    static final class QuotaException extends RuntimeException {
                        QuotaException(String message) { super(message); }
                    }

                    static final class StaleException extends RuntimeException {
                        StaleException(String message) { super(message); }
                    }

                    @StoredProcedure
                    public static void run(int code) {
                        try {
                            if (code == 1) {
                                throw new QuotaException("quota");
                            }
                            throw new StaleException("stale");
                        } catch (StaleException stale) {
                            System.out.println("recovered");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        TryCatchFinallyStatement tryCatch = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        IfStatement gate = assertInstanceOf(IfStatement.class, tryCatch.tryBlock().statements().getFirst());
        RaiseStatement quotaRaise = assertInstanceOf(RaiseStatement.class, gate.thenBlock().statements().getFirst());
        RaiseStatement staleRaise = assertInstanceOf(RaiseStatement.class, tryCatch.tryBlock().statements().get(1));

        // Distinct deterministic states in first-seen throw order: 45000 is the generic
        // default and 45001 is owned by NullPointerException, so allocation starts at 45002.
        assertEquals("45002", quotaRaise.sqlstate());
        assertEquals("45003", staleRaise.sqlstate());

        // The catch of StaleException matches only its own state: QuotaException propagates
        // past it instead of being intercepted (the F-11 bug collapsed both to 45000).
        assertEquals(List.of("45003"), tryCatch.catches().getFirst().sqlStates());
    }

    @Test
    void catchOfCommonSupertypeMatchesAllAssignableUserExceptions() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringSupertypeCatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringSupertypeCatch {
                    static final class QuotaException extends RuntimeException {
                        QuotaException(String message) { super(message); }
                    }

                    static final class StaleException extends RuntimeException {
                        StaleException(String message) { super(message); }
                    }

                    @StoredProcedure
                    public static void run(int code) {
                        try {
                            if (code == 1) {
                                throw new QuotaException("quota");
                            }
                            throw new StaleException("stale");
                        } catch (RuntimeException any) {
                            System.out.println("recovered");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        TryCatchFinallyStatement tryCatch = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        List<String> states = tryCatch.catches().getFirst().sqlStates();

        // JDK hierarchy states stay as-is...
        assertTrue(states.contains("45000"));
        assertTrue(states.contains("22023"));
        // ...and the resolved type hierarchy pulls in every thrown user exception assignable
        // to RuntimeException, so the supertype catch still catches them.
        assertTrue(states.contains("45002"));
        assertTrue(states.contains("45003"));
    }

    @Test
    void multiCatchUnionAccumulatesUserExceptionStates() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringMultiCatch.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LoweringMultiCatch {
                    static final class QuotaException extends RuntimeException {
                        QuotaException(String message) { super(message); }
                    }

                    static final class StaleException extends RuntimeException {
                        StaleException(String message) { super(message); }
                    }

                    @StoredProcedure
                    public static void run(int code) {
                        try {
                            if (code == 1) {
                                throw new QuotaException("quota");
                            }
                            throw new StaleException("stale");
                        } catch (QuotaException | StaleException either) {
                            System.out.println("recovered");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        TryCatchFinallyStatement tryCatch = assertInstanceOf(TryCatchFinallyStatement.class, block.statements().getFirst());
        // Exactly the union of the two user exception states — no generic 45000, so a plain
        // RuntimeException raise would still propagate.
        assertEquals(List.of("45002", "45003"), tryCatch.catches().getFirst().sqlStates());
    }

    @Test
    void runsEmulationInsertionPassAfterNullAnalysis() throws Exception {
        // Plan 2.4 (E-7/E-11): the facade wires EmulationInsertionPass after NullAnalysisPass.
        // Parameter types come from the entry point's resolved signature, so int/int division and
        // modulo on parameters are rewritten to the dialect-neutral markers in the default mode,
        // while int addition keeps the native operator (fail-loud overflow parity).
        Path sourceFile = tempDir.resolve("LoweringIntegerEmulation.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;

                class LoweringIntegerEmulation {
                    @StoredFunction
                    public static int run(int dividend, int divisor) {
                        int ratio = dividend / divisor;
                        int remainder = dividend % divisor;
                        return ratio + remainder;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();

        FunctionCallExpression division = initializer(block, "ratio", FunctionCallExpression.class);
        assertEquals(EmulationInsertionPass.INT_DIV_MARKER, division.name());
        assertEquals("dividend", assertInstanceOf(VariableRefExpression.class, division.arguments().get(0)).name());
        assertEquals("divisor", assertInstanceOf(VariableRefExpression.class, division.arguments().get(1)).name());

        FunctionCallExpression modulo = initializer(block, "remainder", FunctionCallExpression.class);
        assertEquals(EmulationInsertionPass.INT_MOD_MARKER, modulo.name());

        ReturnStatement returnStatement = block.statements().stream()
                .filter(ReturnStatement.class::isInstance)
                .map(ReturnStatement.class::cast)
                .findFirst()
                .orElseThrow();
        BinaryOpExpression addition = assertInstanceOf(BinaryOpExpression.class, returnStatement.expression());
        assertEquals(BinaryOperator.ADD, addition.operator());
    }

    @Test
    void strictWraparoundModeRewritesIntArithmetic() throws Exception {
        // Plan 2.4 (E-11): the strictWraparound flag plumbed through the facade switches int
        // +/-/* on provably-int operands to the java_int_add/sub/mul wraparound markers.
        Path sourceFile = tempDir.resolve("LoweringWraparoundEmulation.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;

                class LoweringWraparoundEmulation {
                    @StoredFunction
                    public static int run(int a, int b) {
                        int total = a + b;
                        return total * a;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        io.titan.transpiler.diagnostics.DiagnosticSink sink = new io.titan.transpiler.diagnostics.DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, entryPoints, sink, true);
        sink.failIfErrors();
        Block block = lowered.values().iterator().next();

        FunctionCallExpression addition = initializer(block, "total", FunctionCallExpression.class);
        assertEquals(EmulationInsertionPass.INT_ADD_MARKER, addition.name());

        ReturnStatement returnStatement = block.statements().stream()
                .filter(ReturnStatement.class::isInstance)
                .map(ReturnStatement.class::cast)
                .findFirst()
                .orElseThrow();
        FunctionCallExpression multiplication = assertInstanceOf(FunctionCallExpression.class, returnStatement.expression());
        assertEquals(EmulationInsertionPass.INT_MUL_MARKER, multiplication.name());
    }
}
