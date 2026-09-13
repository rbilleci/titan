package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import io.titan.transpiler.tir.DialectId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsP0SubsetFeatures() throws Exception {
        Path sourceFile = tempDir.resolve("ValidP0Features.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class ValidP0Features {
                    @StoredProcedure
                    public static void run(int start, String label) {
                        int total = start;
                        if (label != null) {
                            total = total + 1;
                        }

                        for (int i = 0; i < 3; i++) {
                            total += i;
                        }

                        while (total < 20) {
                            total++;
                        }

                        do {
                            total--;
                        } while (total > 5);

                        for (int value : new int[]{1, 2, 3}) {
                            total += value;
                        }

                        try {
                            if (total < 0) {
                                throw new IllegalStateException("bad");
                            }
                        } catch (RuntimeException ex) {
                            total = 0;
                        }

                        return;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        EntryPointDiscovery discovery = new EntryPointDiscovery();
        List<DiscoveredEntryPoint> entryPoints = discovery.discover(parsed);

        FeatureValidator validator = new FeatureValidator();
        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void rejectsUnsupportedFeaturesWithTitanErrorCode() throws Exception {
        Path sourceFile = tempDir.resolve("UnsupportedFeatures.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class UnsupportedFeatures {
                    @StoredFunction
                    public static int run(int input) {
                        var x = switch (input) {
                            case 1 -> 10;
                            default -> 20;
                        };
                        return java.util.List.of(x).stream().map(v -> v + 1).findFirst().orElse(0);
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        EntryPointDiscovery discovery = new EntryPointDiscovery();
        List<DiscoveredEntryPoint> entryPoints = discovery.discover(parsed);

        FeatureValidator validator = new FeatureValidator();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("lambda expression"));
        assertTrue(message.contains("Suggestion:"));
        assertTrue(message.contains("Doc: Section 12.1"));
    }

    @Test
    void acceptsUnlabeledBreakAndContinueInsideLoops() throws Exception {
        Path sourceFile = tempDir.resolve("SupportedLoopControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class SupportedLoopControl {
                    @StoredProcedure
                    public static void run(int input) {
                        while (input > 0) {
                            input--;
                            if (input == 7) {
                                break;
                            }
                            for (int i = 0; i < input; i++) {
                                if (i == 3) {
                                    continue;
                                }
                                if (i == 5) {
                                    break;
                                }
                            }
                            if (input == 3) {
                                continue;
                            }
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        new FeatureValidator().validate(parsed, entryPoints);
    }

    @Test
    void rejectsLabeledBreakAndContinueWithTitanErrorCode() throws Exception {
        Path sourceFile = tempDir.resolve("LabeledLoopControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class LabeledLoopControl {
                    @StoredProcedure
                    public static void run(int input) {
                        outer:
                        while (input > 0) {
                            while (input > 1) {
                                if (input == 7) {
                                    break outer;
                                }
                                if (input == 3) {
                                    continue outer;
                                }
                                input--;
                            }
                            input--;
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("labeled break statement"));
        assertTrue(message.contains("labeled continue statement"));
        assertTrue(message.contains("Suggestion:"));
        assertTrue(message.contains("Doc: Section 12.1"));
    }

    @Test
    void rejectsBreakTargetingSwitchStatement() throws Exception {
        Path sourceFile = tempDir.resolve("SwitchBreakControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class SwitchBreakControl {
                    @StoredProcedure
                    public static void run(int input) {
                        while (input > 0) {
                            input--;
                            switch (input) {
                                case 1 -> {
                                    if (input > 0) {
                                        break;
                                    }
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("break statement targeting a switch"));
        assertTrue(message.contains("Suggestion:"));
    }

    @Test
    void rejectsBreakAndContinueCrossingTryBoundary() throws Exception {
        Path sourceFile = tempDir.resolve("TryLoopControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class TryLoopControl {
                    @StoredProcedure
                    public static void run(int input) {
                        while (input > 0) {
                            try {
                                if (input == 7) {
                                    break;
                                }
                                if (input == 3) {
                                    continue;
                                }
                                input--;
                            } finally {
                                input--;
                            }
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("break statement crossing a try/catch/finally boundary"));
        assertTrue(message.contains("continue statement crossing a try/catch/finally boundary"));
        assertTrue(message.contains("Suggestion:"));
    }

    @Test
    void allowsBreakInsideLoopNestedInTry() throws Exception {
        Path sourceFile = tempDir.resolve("TryWrappedLoopControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class TryWrappedLoopControl {
                    @StoredProcedure
                    public static void run(int input) {
                        try {
                            while (input > 0) {
                                input--;
                                if (input == 7) {
                                    break;
                                }
                            }
                        } finally {
                            input = 0;
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        new FeatureValidator().validate(parsed, entryPoints);
    }

    @Test
    void rejectsColonStyleSwitchWithPositionedArrowSuggestion() throws Exception {
        Path sourceFile = tempDir.resolve("ColonSwitchControl.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class ColonSwitchControl {
                    @StoredProcedure
                    public static void run(int input) {
                        int result = 0;
                        switch (input) {
                            case 1:
                                result = 10;
                                break;
                            default:
                                result = -1;
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("switch statement with colon-style cases"));
        assertTrue(message.contains("ColonSwitchControl.java:8"), message);
        assertTrue(message.contains("Suggestion: Rewrite the switch using arrow-style rules (case X -> ...)"), message);
        assertTrue(message.contains("Doc: Section 12.1"));
    }

    @Test
    void acceptsArrowStyleSwitchExpression() throws Exception {
        Path sourceFile = tempDir.resolve("SwitchExpressionFeatures.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class SwitchExpressionFeatures {
                    @StoredFunction
                    public static int run(int input) {
                        return switch (input) {
                            case 1, 2 -> 10;
                            default -> 20;
                        };
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void rejectsAbortWithErrorOutsideTriggerContext() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidAbortOutsideTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;
                import static titan.dsl.DSL.abortWithError;

                class InvalidAbortOutsideTrigger {
                    @StoredProcedure
                    public static void run() {
                        abortWithError("nope");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("abortWithError() outside trigger context"));
    }

    @Test
    void acceptsAbortWithErrorInsideTriggerContext() throws Exception {
        Path sourceFile = tempDir.resolve("ValidAbortTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.abortWithError;

                class ValidAbortTrigger {
                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = {TriggerEvent.INSERT}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        abortWithError("blocked");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void acceptsSupportedTriggerRowAccessorMethods() throws Exception {
        Path sourceFile = tempDir.resolve("ValidTriggerRowAccessors.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Column;
                import titan.dsl.SQLType;
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.newRow;
                import static titan.dsl.DSL.oldRow;

                class ValidTriggerRowAccessors {
                    static final Column<String> EMAIL = new Column<>("email", SQLType.VARCHAR);

                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = {TriggerEvent.UPDATE}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        String previous = oldRow().get(EMAIL);
                        String current = newRow().get(EMAIL);
                        newRow().set(EMAIL, current.trim());
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void acceptsCompilerKnownDslCatalogFieldAccess() throws Exception {
        Path sourceFile = tempDir.resolve("ValidDslCatalogFieldAccess.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Column;
                import titan.dsl.Nullability;
                import titan.dsl.SQLType;
                import titan.dsl.StoredFunction;
                import titan.dsl.Table;
                import static titan.dsl.DSL.select;

                class ValidDslCatalogFieldAccess {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredFunction
                    public static int firstId() {
                        return select(ACCOUNTS.ID).from(ACCOUNTS).fetchOne().value1();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts");
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void rejectsUserDefinedNewRowInterfaceDispatch() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidShadowedNewRow.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                interface ShadowRow {
                    String get(String column);
                }

                class InvalidShadowedNewRow {
                    @StoredFunction
                    public static String run(String column) {
                        return newRow().get(column);
                    }

                    static ShadowRow newRow() {
                        return null;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("interface dispatch in transpiled code"));
    }

    @Test
    void rejectsOldRowInInsertTrigger() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidInsertTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.oldRow;

                class InvalidInsertTrigger {
                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = {TriggerEvent.INSERT}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        oldRow();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("oldRow() in INSERT trigger context"));
    }

    @Test
    void rejectsNewRowInDeleteTrigger() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidDeleteTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.newRow;

                class InvalidDeleteTrigger {
                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = {TriggerEvent.DELETE}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        newRow();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("newRow() in DELETE trigger context"));
    }

    @Test
    void rejectsOldRowSetInUpdateTrigger() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidOldRowSetTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Column;
                import titan.dsl.SQLType;
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.oldRow;

                class InvalidOldRowSetTrigger {
                    static final Column<String> EMAIL = new Column<>("email", SQLType.VARCHAR);

                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = {TriggerEvent.UPDATE}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        oldRow().set(EMAIL, "mutated@example.com");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("oldRow().set(...) in trigger context"));
    }

    @Test
    void rejectsNewRowSetInAfterTrigger() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidAfterSetTrigger.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Column;
                import titan.dsl.SQLType;
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                import static titan.dsl.DSL.newRow;

                class InvalidAfterSetTrigger {
                    static final Column<String> EMAIL = new Column<>("email", SQLType.VARCHAR);

                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = {TriggerEvent.UPDATE}, forEach = TriggerForEach.ROW)
                    public static void run() {
                        newRow().set(EMAIL, "mutated@example.com");
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("newRow().set(...) in AFTER trigger context"));
    }

    @Test
    void acceptsExhaustiveEnumSwitchExpressionWithoutDefault() throws Exception {
        Path sourceFile = tempDir.resolve("EnumSwitchExpressionWithoutDefault.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class EnumSwitchExpressionWithoutDefault {
                    enum Tier { FREE, PRO }

                    @StoredFunction
                    public static int run(Tier tier) {
                        return switch (tier) {
                            case FREE -> 1;
                            case PRO -> 2;
                        };
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void rejectsNonExhaustiveSwitchExpressionWithoutDefault() throws Exception {
        Path sourceFile = tempDir.resolve("SwitchExpressionWithoutDefault.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class SwitchExpressionWithoutDefault {
                    @StoredFunction
                    public static int run(int input) {
                        return switch (input) {
                            case 1, 2 -> 10;
                        };
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("non-exhaustive switch expression"));
    }

    @Test
    void acceptsArrowStyleSwitchStatement() throws Exception {
        Path sourceFile = tempDir.resolve("SwitchStatementFeatures.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class SwitchStatementFeatures {
                    @StoredProcedure
                    public static void run(int input) {
                        switch (input) {
                            case 1 -> {
                                int x = input + 1;
                            }
                            default -> {
                                int y = 0;
                            }
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        assertDoesNotThrow(() -> validator.validate(parsed, entryPoints));
    }

    @Test
    void rejectsIterableEntryPointParameter() throws Exception {
        Path sourceFile = tempDir.resolve("IterableParameterFeatures.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;
                import java.util.List;

                class IterableParameterFeatures {
                    @StoredProcedure
                    public static void run(List<Integer> values) {
                        for (int value : values) {
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("entry-point Iterable parameter type 'java.util.List<java.lang.Integer>'"));
        assertTrue(exception.getMessage().contains("use array parameters instead"));
    }

    @Test
    void rejectsCastExpressionWithPositionedDiagnostic() throws Exception {
        // F-9 / plan 2.2: long-to-int narrowing stays rejected (Java silently wraps the low
        // 32 bits where SQL CAST range-errors) with a positioned TITAN-E001 diagnostic.
        Path sourceFile = tempDir.resolve("UnsupportedCastFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class UnsupportedCastFeature {
                    @StoredFunction
                    public static int run(long input) {
                        return (int) input;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("cast expression"));
        assertTrue(exception.getMessage().contains("from long to int"));
        assertTrue(exception.getMessage().contains("UnsupportedCastFeature.java:6"));
    }

    @Test
    void acceptsSupportedNumericCasts() throws Exception {
        // Plan 2.2 (F-9 follow-up): identity, numeric widening and fractional-to-integral
        // truncation casts are part of the supported subset and pass validation.
        Path sourceFile = tempDir.resolve("SupportedCastFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class SupportedCastFeature {
                    @StoredFunction
                    public static long run(int small, double ratio) {
                        long widened = (long) small;
                        double promoted = (double) widened;
                        long truncated = (long) (promoted * ratio);
                        int identity = (int) small;
                        return widened + truncated + identity;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        new FeatureValidator().validate(parsed, entryPoints);
    }

    @Test
    void rejectsReferenceCastWithPositionedDiagnostic() throws Exception {
        // Plan 2.2: reference casts (including (Object) upcasts) have no SQL equivalent.
        Path sourceFile = tempDir.resolve("ReferenceCastFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class ReferenceCastFeature {
                    @StoredFunction
                    public static String run(String text) {
                        return (String) (Object) text;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("reference cast expression"));
        assertTrue(exception.getMessage().contains("ReferenceCastFeature.java:6"));
    }

    @Test
    void rejectsSmallIntegralTargetCastWithPositionedDiagnostic() throws Exception {
        // Plan 2.2: (short)/(byte) targets truncate to 16/8 bits in Java; TIR maps both to
        // INT so a SQL cast cannot reproduce the wraparound — rejected with a position.
        Path sourceFile = tempDir.resolve("ShortCastFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class ShortCastFeature {
                    @StoredFunction
                    public static int run(int value) {
                        short narrowed = (short) value;
                        return narrowed;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("narrowing cast expression to byte/short"));
        assertTrue(exception.getMessage().contains("ShortCastFeature.java:6"));
    }

    @Test
    void rejectsInstanceofExpressionWithPositionedDiagnostic() throws Exception {
        // F-9: instanceof must produce a positioned TITAN-E001 diagnostic instead of crashing the lowerer.
        Path sourceFile = tempDir.resolve("UnsupportedInstanceofFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class UnsupportedInstanceofFeature {
                    @StoredFunction
                    public static boolean run(Object value) {
                        return value instanceof String;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("instanceof expression"));
        assertTrue(exception.getMessage().contains("UnsupportedInstanceofFeature.java:6"));
    }

    @Test
    void rejectsBitwiseAndShiftOperatorsWithPositionedDiagnostic() throws Exception {
        // F-9: bitwise/shift operators must produce positioned TITAN-E001 diagnostics instead of crashing the lowerer.
        Path sourceFile = tempDir.resolve("UnsupportedBitwiseFeature.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class UnsupportedBitwiseFeature {
                    @StoredFunction
                    public static int run(int input) {
                        int masked = input & 7;
                        int shifted = input << 2;
                        return masked + shifted;
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        FeatureValidator validator = new FeatureValidator();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("bitwise or shift operator"));
        assertTrue(exception.getMessage().contains("UnsupportedBitwiseFeature.java:6"));
        assertTrue(exception.getMessage().contains("UnsupportedBitwiseFeature.java:7"));
    }

    // Dynamic-SQL safety (plan 2.5, audit D14): the SQL text passed to exec/query/queryScalar
    // must be a compile-time constant (string literal, static final String constant, or a
    // concatenation of those). Everything else is a positioned TITAN-E004.

    @Test
    void acceptsRawSqlStringLiteral() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlLiteral.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlLiteral {
                    @StoredProcedure
                    public static void run(int id) {
                        exec("SELECT id FROM accounts WHERE id = :id");
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertDoesNotThrow(() -> new FeatureValidator().validate(parsed, entryPoints));
    }

    @Test
    void acceptsRawSqlStaticFinalConstant() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlConstant.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlConstant {
                    static final String ARCHIVE_SQL = "INSERT INTO archived_accounts SELECT * FROM accounts WHERE id = :id";

                    @StoredProcedure
                    public static void run(int id) {
                        exec(ARCHIVE_SQL);
                    }

                    static void exec(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertDoesNotThrow(() -> new FeatureValidator().validate(parsed, entryPoints));
    }

    @Test
    void acceptsRawSqlConcatenationOfLiteralsAndConstants() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlConstantConcat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlConstantConcat {
                    static final String BASE_SQL = "SELECT id FROM accounts";
                    static final String ID_FILTER = " WHERE id = :id";

                    @StoredProcedure
                    public static void run(int id) {
                        exec(BASE_SQL + ID_FILTER);
                        query("SELECT id FROM accounts" + ID_FILTER);
                        queryScalar((BASE_SQL) + (" LIMIT 1"));
                    }

                    static void exec(String sql) {
                    }

                    static void query(String sql) {
                    }

                    static void queryScalar(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertDoesNotThrow(() -> new FeatureValidator().validate(parsed, entryPoints));
    }

    @Test
    void acceptsRawSqlTextBlock() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlTextBlock.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlTextBlock {
                    @StoredProcedure
                    public static void run(int id) {
                        query(\"""
                                SELECT id
                                FROM accounts
                                WHERE id = :id
                                \""");
                    }

                    static void query(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertDoesNotThrow(() -> new FeatureValidator().validate(parsed, entryPoints));
    }

    @Test
    void rejectsRawSqlFromLocalVariable() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlLocalVariable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlLocalVariable {
                    @StoredProcedure
                    public static void run(int id) {
                        String sql = "SELECT id FROM accounts WHERE id = " + id;
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
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("DynamicSqlLocalVariable.java:7"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    @Test
    void rejectsRawSqlFromStringFormat() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlStringFormat.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlStringFormat {
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
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("DynamicSqlStringFormat.java:6"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    @Test
    void rejectsRawSqlFromStringBuilder() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlStringBuilder.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlStringBuilder {
                    @StoredProcedure
                    public static void run(String tableName) {
                        StringBuilder sql = new StringBuilder("SELECT * FROM ");
                        sql.append(tableName);
                        exec(sql.toString());
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
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("DynamicSqlStringBuilder.java:8"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    @Test
    void rejectsRawSqlFromTernary() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlTernary.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlTernary {
                    @StoredProcedure
                    public static void run(boolean archived) {
                        queryScalar(archived ? "SELECT count(*) FROM archived_accounts" : "SELECT count(*) FROM accounts");
                    }

                    static void queryScalar(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("DynamicSqlTernary.java:6"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    @Test
    void rejectsRawSqlFromMethodParameter() throws Exception {
        Path sourceFile = tempDir.resolve("DynamicSqlParameter.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DynamicSqlParameter {
                    @StoredProcedure
                    public static void run(String sql) {
                        query(sql);
                    }

                    static void query(String sql) {
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("DynamicSqlParameter.java:6"));
        assertTrue(exception.getMessage().contains(":name bind parameters"));
    }

    // ------------------------------------------------------------------
    // Dialect-capability gate (plan 3.1): unsupported construct + targeted
    // dialect combinations are positioned compile-time TITAN-E001 rejections;
    // version-gated combinations are TITAN-W005 warnings naming the floor.
    // ------------------------------------------------------------------

    private static final String FULL_OUTER_JOIN_FIXTURE = """
            import titan.dsl.*;
            import static titan.dsl.DSL.*;

            class FullOuterJoinChain {
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
            """;

    @Test
    void rejectsFullOuterJoinWhenMySqlIsTargeted() throws Exception {
        Path sourceFile = tempDir.resolve("FullOuterJoinChain.java");
        Files.writeString(sourceFile, FULL_OUTER_JOIN_FIXTURE);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        DiagnosticSink sink = new DiagnosticSink();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints, List.of(DialectId.MYSQL), sink));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"), message);
        assertTrue(message.contains("FULL OUTER JOIN is not supported by MySQL (any version)"), message);
        assertTrue(message.contains("FullOuterJoinChain.java:10"), message);
        assertTrue(message.contains("Suggestion:"), message);
        assertTrue(message.contains("UNION of the LEFT JOIN and RIGHT JOIN"), message);
    }

    @Test
    void allowsFullOuterJoinWhenOnlyPostgresIsTargeted() throws Exception {
        Path sourceFile = tempDir.resolve("FullOuterJoinChain.java");
        Files.writeString(sourceFile, FULL_OUTER_JOIN_FIXTURE);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        DiagnosticSink sink = new DiagnosticSink();
        assertDoesNotThrow(() -> new FeatureValidator().validate(
                parsed, entryPoints, List.of(DialectId.POSTGRESQL), sink));
        assertTrue(sink.warnings().isEmpty());
    }

    @Test
    void rejectsUpdateAndDeleteReturningWhenMySqlIsTargeted() throws Exception {
        Path sourceFile = tempDir.resolve("ReturningChains.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class ReturningChains {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run(String email) {
                        update(ACCOUNTS)
                                .set(ACCOUNTS.EMAIL, email)
                                .returning(ACCOUNTS.ID)
                                .execute();
                        deleteFrom(ACCOUNTS)
                                .returning(ACCOUNTS.ID)
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

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        DiagnosticSink sink = new DiagnosticSink();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints, List.of(DialectId.MYSQL), sink));

        String message = exception.getMessage();
        assertTrue(message.contains("UPDATE ... RETURNING is not supported by MySQL (any version)"), message);
        assertTrue(message.contains("DELETE ... RETURNING is not supported by MySQL (any version)"), message);
        assertTrue(message.contains("ReturningChains.java:9"), message);
        assertTrue(message.contains("ReturningChains.java:13"), message);
        assertTrue(message.contains("separate SELECT"), message);

        // PostgreSQL-only targeting passes the dialect gate (the construct itself remains
        // subject to lowering coverage, which reports its own positioned diagnostic).
        DiagnosticSink postgresSink = new DiagnosticSink();
        assertDoesNotThrow(() -> new FeatureValidator().validate(
                parsed, entryPoints, List.of(DialectId.POSTGRESQL), postgresSink));
    }

    @Test
    void rejectsStringFormatWhenMySqlIsTargetedAndAllowsItOnPostgres() throws Exception {
        Path sourceFile = tempDir.resolve("StringFormatFunction.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class StringFormatFunction {
                    @StoredFunction
                    public static String label(int value) {
                        return String.format("v=%d", value);
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        DiagnosticSink sink = new DiagnosticSink();
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(parsed, entryPoints, List.of(DialectId.MYSQL), sink));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"), message);
        assertTrue(message.contains("String.format is not supported by MySQL (any version)"), message);
        assertTrue(message.contains("StringFormatFunction.java:6"), message);
        assertTrue(message.contains("concatenation"), message);

        DiagnosticSink postgresSink = new DiagnosticSink();
        assertDoesNotThrow(() -> new FeatureValidator().validate(
                parsed, entryPoints, List.of(DialectId.POSTGRESQL), postgresSink));
        assertTrue(postgresSink.warnings().isEmpty());
    }

    @Test
    void warnsThatIntersectAndExceptRequireMySql8031() throws Exception {
        Path sourceFile = tempDir.resolve("SetOperationChains.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SetOperationChains {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID).from(ACCOUNTS)
                                .intersect(select(USERS.ACCOUNT_ID).from(USERS))
                                .fetch();
                        select(ACCOUNTS.ID).from(ACCOUNTS)
                                .except(select(USERS.ACCOUNT_ID).from(USERS))
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

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        // Version-gated, not unsupported: validation passes and emits W005 warnings.
        DiagnosticSink sink = new DiagnosticSink();
        assertDoesNotThrow(() -> new FeatureValidator().validate(
                parsed, entryPoints, List.of(DialectId.MYSQL), sink));

        assertEquals(2, sink.warnings().size(), sink.warnings().toString());
        TitanDiagnostic intersectWarning = sink.warnings().get(0);
        TitanDiagnostic exceptWarning = sink.warnings().get(1);
        assertEquals(TitanErrorCode.W005, intersectWarning.code());
        assertEquals(TitanErrorCode.W005, exceptWarning.code());
        assertTrue(intersectWarning.message().contains("INTERSECT requires MySQL >= 8.0.31"),
                intersectWarning.message());
        assertTrue(exceptWarning.message().contains("EXCEPT requires MySQL >= 8.0.31"),
                exceptWarning.message());
        assertTrue(intersectWarning.location().contains("SetOperationChains.java:10"),
                intersectWarning.location());
        assertTrue(exceptWarning.location().contains("SetOperationChains.java:13"),
                exceptWarning.location());

        // No version gate on PostgreSQL.
        DiagnosticSink postgresSink = new DiagnosticSink();
        assertDoesNotThrow(() -> new FeatureValidator().validate(
                parsed, entryPoints, List.of(DialectId.POSTGRESQL), postgresSink));
        assertTrue(postgresSink.warnings().isEmpty());
    }
}
