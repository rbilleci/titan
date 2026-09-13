package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GAP004NestedHelperFixtureBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsLoopBasedDelimiterScannerNonRecursive() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                class GAP004DelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedBrackets(String text) {
                        int depth = 0;
                        for (int index = 0; index < text.length(); index++) {
                            char ch = text.charAt(index);
                            if (ch == '[') {
                                depth++;
                            } else if (ch == ']') {
                                depth--;
                                if (depth < 0) {
                                    return false;
                                }
                            }
                        }
                        return depth == 0;
                    }
                }
                """);

        assertEquals("", result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"balanced_brackets\""));
        assertTrue(result.postgresSql().contains("WHILE"));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`balanced_brackets`"));
        assertTrue(result.mysqlSql().contains("WHILE"));
    }

    @Test
    void capturesNestedPathTraversalWithScalarStateAsCurrentSupportedShape() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                class GAP004NestedPathTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int segmentStart = 0;
                        int depth = 0;
                        int index = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '.') {
                                if (segmentStart == index || depth != 0) {
                                    return false;
                                }
                                segmentStart = index + 1;
                            } else if (ch == '[') {
                                depth++;
                            } else if (ch == ']') {
                                depth--;
                            }
                            if (depth < 0) {
                                return false;
                            }
                            index++;
                        }
                        return segmentStart < path.length() && depth == 0;
                    }
                }
                """);

        assertEquals("", result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"valid_path\""));
        assertTrue(result.postgresSql().contains("v_segment_start"));
        assertTrue(result.postgresSql().contains("v_depth"));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`valid_path`"));
        assertTrue(result.mysqlSql().contains("v_segment_start"));
        assertTrue(result.mysqlSql().contains("v_depth"));
    }

    @Test
    void lowersNestedPathTraversalWithReadOnlyBoundedArrayState() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                class GAP004BoundedArrayPathTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int segmentStart = 0;
                        int depth = 0;
                        int mark = segmentMarks[0];
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '.') {
                                if (segmentStart == index || depth != 0) {
                                    return false;
                                }
                                segmentStart = index + 1;
                                mark = segmentMarks[depth];
                            } else if (ch == '[') {
                                depth++;
                                if (depth >= segmentMarks.length) {
                                    return false;
                                }
                                mark = segmentMarks[depth];
                            } else if (ch == ']') {
                                depth--;
                            }
                            if (depth < 0) {
                                return false;
                            }
                            index++;
                        }
                        return segmentStart < path.length() && depth == 0 && mark == 0;
                    }
                }
                """);

        assertEquals("", result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"valid_path\""));
        assertTrue(result.postgresSql().contains("v_segment_marks"));
        assertTrue(result.postgresSql().contains("v_mark"));
        // Plan 2.3: the completed null pass classifies CHAR_LENGTH(p_path) as nullable (its
        // argument is an unmodeled parameter), so the condition gains the COALESCE(..., FALSE)
        // wrapper - a semantic no-op under SQL three-valued logic.
        assertTrue(result.postgresSql().contains("WHILE COALESCE((v_index < CHAR_LENGTH(p_path)), FALSE) LOOP"), result.postgresSql());
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`valid_path`"));
        assertTrue(result.mysqlSql().contains("v_segment_marks"));
        assertTrue(result.mysqlSql().contains("v_mark"));
        assertTrue(result.mysqlSql().contains("WHILE COALESCE((v_index < CHAR_LENGTH(p_path)), FALSE) DO"), result.mysqlSql());
    }

    @Test
    void rejectsAliasedArrayTraversalState() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004AliasedArrayTraversalStateFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int[] activeMarks = segmentMarks;
                        int index = 0;
                        int depth = 0;
                        int mark = activeMarks[0];
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                depth++;
                                mark = activeMarks[depth];
                            }
                            index++;
                        }
                        return depth == 0 && mark == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("aliased array traversal state in transpiled code"));
        assertTrue(message.contains("bounded traversal arrays must be constructed in place and read-only"));
    }

    @Test
    void rejectsArrayTraversalStateWidenedIntoObjectAlias() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ObjectAliasedArrayTraversalStateFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        Object hiddenMarks = (Object) segmentMarks;
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0 && hiddenMarks != null;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("aliased array traversal state in transpiled code"));
        assertTrue(message.contains("bounded traversal arrays must be constructed in place and read-only"));
    }

    @Test
    void rejectsArrayTraversalStateAssignedIntoObjectAlias() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ObjectAliasAssignmentArrayTraversalStateFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        Object hiddenMarks = null;
                        hiddenMarks = segmentMarks;
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0 && hiddenMarks != null;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("aliased array traversal state in transpiled code"));
        assertTrue(message.contains("bounded traversal arrays must be constructed in place and read-only"));
    }

    @Test
    void rejectsArrayTraversalStateReassignment() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ArrayTraversalStateReassignmentFixture {
                    @StoredFunction
                    public static boolean validPath(String path, boolean reset) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int depth = 0;
                        if (reset) {
                            segmentMarks = new int[]{1, 1, 1, 1};
                        }
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0 && segmentMarks[0] == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("array traversal state reassignment in transpiled code"));
        assertTrue(message.contains("bounded traversal arrays must be constructed in place and read-only"));
    }

    @Test
    void lowersBoundedDelimiterScannerWithScalarDepthAndDeterministicCursorState() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                class GAP004BoundedDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            if (ch == '[' || ch == '(' || ch == '{') {
                                depth++;
                            } else if (ch == ']' || ch == ')' || ch == '}') {
                                depth--;
                                if (depth < 0) {
                                    return false;
                                }
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """);

        assertEquals("", result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"balanced_groups\""));
        // Plan 2.3: CHAR_LENGTH over an unmodeled parameter is classified nullable, so the
        // condition gains the COALESCE(..., FALSE) wrapper (semantic no-op in SQL conditions).
        assertTrue(result.postgresSql().contains("WHILE COALESCE((v_index < CHAR_LENGTH(p_text)), FALSE) LOOP"), result.postgresSql());
        assertTrue(result.postgresSql().contains("v_ch := SUBSTRING(p_text FROM (v_index + 1) FOR 1);"), result.postgresSql());
        assertTrue(result.postgresSql().contains("v_index := (v_index + 1);"), result.postgresSql());
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`balanced_groups`"));
        assertTrue(result.mysqlSql().contains("WHILE COALESCE((v_index < CHAR_LENGTH(p_text)), FALSE) DO"), result.mysqlSql());
        assertTrue(result.mysqlSql().contains("SET v_ch = SUBSTRING(p_text, (v_index + 1), 1);"), result.mysqlSql());
        assertTrue(result.mysqlSql().contains("SET v_index = (v_index + 1);"), result.mysqlSql());
    }

    @Test
    void rejectsBoundedDelimiterScannerWithoutDeterministicCursorIncrement() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004StalledDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = 0;
                        while (index < text.length()) {
                            char ch = text.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without deterministic cursor increment"));
        assertTrue(message.contains("scalar index state advanced exactly once per loop"));
    }

    @Test
    void rejectsForLoopDelimiterScannerWithoutDeterministicCursorIncrement() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004StalledForDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        for (int index = 0; index < text.length();) {
                            char ch = text.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without deterministic cursor increment"));
    }

    @Test
    void rejectsDelimiterScannerWithAdditionalCursorMutation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004DoubleIncrementDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text, boolean skip) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            if (skip) {
                                index++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without deterministic cursor increment"));
    }

    @Test
    void rejectsDelimiterScannerWithNonIncrementCursorWrite() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ResetCursorDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            index = 0;
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without deterministic cursor increment"));
    }

    @Test
    void rejectsDelimiterScannerWithNegativeCursorInitialization() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004NegativeCursorDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = -1;
                        char ch = ' ';
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without non-negative cursor initialization"));
    }

    @Test
    void rejectsDelimiterScannerWithNegativeCursorReassignmentBeforeLoop() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004NegativeCursorReassignmentDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        index = -1;
                        while (index < text.length()) {
                            ch = text.charAt(index);
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner without non-negative cursor initialization"));
    }

    @Test
    void rejectsDelimiterScannerWithMismatchedStringBounds() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004MismatchedDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text, String limit) {
                        int depth = 0;
                        int index = 0;
                        char ch = ' ';
                        while (index < limit.length()) {
                            ch = text.charAt(index);
                            if (ch == '[') {
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("nested traversal scanner with mismatched string bounds"));
        assertTrue(message.contains("charAt cursor receiver must match length receiver"));
    }

    @Test
    void rejectsMutableParserStateForDelimiterScanner() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004MutableDelimiterScannerFixture {
                    @StoredFunction
                    public static boolean balancedGroups(String text) {
                        char[] stack = new char[]{' ', ' ', ' ', ' '};
                        int depth = 0;
                        int index = 0;
                        while (index < text.length()) {
                            char ch = text.charAt(index);
                            if (ch == '[') {
                                stack[depth] = ch;
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("mutable array element assignment in transpiled code"));
        assertTrue(message.contains("structured arrays are immutable"));
    }

    @Test
    void rejectsParenthesizedArrayElementTraversalStateMutation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ParenthesizedArrayMutationTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                (segmentMarks[depth]) = 1;
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("mutable array element assignment in transpiled code"));
        assertTrue(message.contains("structured arrays are immutable"));
    }

    @Test
    void rejectsCompoundArrayElementTraversalStateMutation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004CompoundArrayMutationTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                segmentMarks[depth] += 1;
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("mutable array element assignment in transpiled code"));
        assertTrue(message.contains("structured arrays are immutable"));
    }

    @Test
    void rejectsParenthesizedCompoundArrayElementTraversalStateMutation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004ParenthesizedCompoundArrayMutationTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                (segmentMarks[depth]) += 1;
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("mutable array element assignment in transpiled code"));
        assertTrue(message.contains("structured arrays are immutable"));
    }

    @Test
    void rejectsUnaryArrayElementTraversalStateMutation() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class GAP004UnaryArrayMutationTraversalFixture {
                    @StoredFunction
                    public static boolean validPath(String path) {
                        int[] segmentMarks = new int[]{0, 0, 0, 0};
                        int index = 0;
                        int depth = 0;
                        while (index < path.length()) {
                            char ch = path.charAt(index);
                            if (ch == '[') {
                                segmentMarks[depth]++;
                                depth++;
                            }
                            index++;
                        }
                        return depth == 0;
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("mutable array element assignment in transpiled code"));
        assertTrue(message.contains("structured arrays are immutable"));
    }

    @Test
    void capturesFragmentLikeReferenceTraversalOverCompilerKnownDescriptors() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceTraversalFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(
                result.postgresSql().contains("__titan_internal_g4_reference_traversal_fixture_find_"),
                result.postgresSql());
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
        assertTrue(
                result.mysqlSql().contains("__titan_internal_g4_reference_traversal_fixture_find_"),
                result.mysqlSql());
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycle() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
        assertTrue(message.contains("acyclic compiler-known descriptor traversal"));
        assertFalse(message.contains("titan-graphql"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphThatExceedsTraversalBound() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceDepthFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 2; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph depth exceeds traversal bound"));
        assertTrue(message.contains("descriptor path root -> review -> publish requires 3 node visit(s), but loop bound is 2"));
        assertTrue(message.contains("increase the compiler-known traversal bound"));
        assertFalse(message.contains("titan-graphql"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphThatExceedsOffsetTraversalBound() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceOffsetDepthFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 6; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph depth exceeds traversal bound"));
        assertTrue(message.contains("descriptor path root -> review -> publish requires 3 node visit(s), but loop bound is 2"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWhenForLoopHasEarlierInitializer() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceMultiInitializerCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int ignored = 0, depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWhenForLoopIncrementsCursorInBody() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceBodyIncrementCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8;) {
                            StepDescriptor step = find(steps, current);
                            current = step.nextName();
                            depth++;
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphThatExceedsOffsetWhileTraversalBound() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceOffsetWhileDepthFixture {
                    static final int MAX_DEPTH = 8;

                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        int depth = 6;
                        while (depth < MAX_DEPTH) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                            depth++;
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph depth exceeds traversal bound"));
        assertTrue(message.contains("descriptor path root -> review -> publish requires 3 node visit(s), but loop bound is 2"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWithStaticFinalTraversalBound() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceConstantBoundCycleFixture {
                    static final int MAX_DEPTH = 8;

                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < MAX_DEPTH; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleInBoundedWhileTraversal() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceWhileCycleFixture {
                    static final int MAX_DEPTH = 8;

                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        int depth = 0;
                        while (depth < MAX_DEPTH) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                            depth++;
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void lowersReferenceGraphWithStaticFinalTerminalSentinel() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceConstantTerminalFixture {
                    static final String END = "";
                    static final int MAX_DEPTH = 8;

                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "publish", false),
                                new StepDescriptor("publish", END, true)
                        };
                        String current = start;
                        for (int depth = 0; depth < MAX_DEPTH; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresUntraversedDescriptorArraysWhenValidatingReferenceGraph() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceUnrelatedDescriptorFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] auditOnly = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return auditOnly.length > 0;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresDescriptorArrayWhenTraversalAdvancesFromDifferentLookupResult() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceMixedLookupFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] auditOnly = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor auditStep = find(auditOnly, current);
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return auditStep != null;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresDescriptorArrayWhenLookupLocalIsReassignedBeforeTraversalAdvance() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceReassignedLookupFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] auditOnly = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(auditOnly, current);
                            step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresDescriptorArrayWithSameNameOutsideTraversalScope() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceScopedNameFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        {
                            StepDescriptor[] steps = new StepDescriptor[]{
                                    new StepDescriptor("draft", "review", false),
                                    new StepDescriptor("review", "draft", false)
                            };
                            if (steps.length == 0) {
                                return false;
                            }
                        }
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "publish", false),
                                new StepDescriptor("publish", "", true)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (step.terminal()) {
                                return true;
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresDescriptorArrayWhenNextReferenceIsInspectedButNotTraversalAdvance() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceInspectionOnlyFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            String observed = step.nextName();
                            if (observed.length() > 100) {
                                return false;
                            }
                            current = "";
                        }
                        return true;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void ignoresDescriptorArrayWhenNextReferenceTempIsOverwrittenBeforeCursorAdvance() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceOverwrittenTempFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            String next = step.nextName();
                            next = "";
                            current = next;
                        }
                        return true;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWithUnbracedConditionalAdvance() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceUnbracedConditionalCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            if (!step.terminal())
                                current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWhenLookupIsAssignedInBothBranches() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceBranchLookupCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start, boolean choose) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step;
                            if (choose) {
                                step = find(steps, current);
                            } else {
                                step = find(steps, current);
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void ignoresDescriptorArrayWhenNextReferenceTempIsOverwrittenInBothBranches() throws Exception {
        PipelineResult result = transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceBranchOverwriteFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start, boolean choose) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("draft", "review", false),
                                new StepDescriptor("review", "draft", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            String next = step.nextName();
                            if (choose) {
                                next = "";
                            } else {
                                next = "";
                            }
                            current = next;
                        }
                        return true;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """);

        assertEquals(
                "TITAN-W004: Emitting 1 source-local helper routine(s) reachable from annotated Titan entry points.\n",
                result.renderedWarnings());
        assertTrue(result.postgresSql().contains("CREATE OR REPLACE FUNCTION \"public\".\"reaches_terminal\""));
        assertTrue(result.mysqlSql().contains("CREATE FUNCTION `public`.`reaches_terminal`"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWhenOneBranchKeepsNextReferenceTemp() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceOneBranchTempCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start, boolean choose) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step = find(steps, current);
                            String next = step.nextName();
                            if (choose) {
                                next = "";
                            }
                            current = next;
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsCompilerKnownReferenceGraphCycleWhenBranchLookupUsesDifferentPossibleCursors() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                record StepDescriptor(String name, String nextName, boolean terminal) {
                }

                class G4ReferenceBranchCursorCycleFixture {
                    @StoredFunction
                    public static boolean reachesTerminal(String start, String alternate, boolean choose) {
                        StepDescriptor[] steps = new StepDescriptor[]{
                                new StepDescriptor("root", "review", false),
                                new StepDescriptor("review", "root", false)
                        };
                        String current = start;
                        for (int depth = 0; depth < 8; depth++) {
                            StepDescriptor step;
                            if (choose) {
                                step = find(steps, current);
                            } else {
                                step = find(steps, alternate);
                            }
                            current = step.nextName();
                        }
                        return false;
                    }

                    static StepDescriptor find(StepDescriptor[] steps, String name) {
                        for (StepDescriptor step : steps) {
                            if (step.name().equals(name)) {
                                return step;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_STEP");
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("bounded reference graph cycle in compiler-known descriptor array 'steps'"));
        assertTrue(message.contains("root -> review -> root"));
    }

    @Test
    void rejectsDirectRecursiveHelperWithPreciseCallPathDiagnostic() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class G4RecFixture {
                    @StoredFunction
                    public static int nodeDepth(int remaining) {
                        return depth(remaining);
                    }

                    static int depth(int remaining) {
                        if (remaining <= 0) {
                            return 0;
                        }
                        return 1 + depth(remaining - 1);
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("Unsupported recursive helper call cycle"));
        assertTrue(message.contains("G4RecFixture.depth(int)"));
        assertTrue(message.contains("GAP004Fixture"));
        assertTrue(message.contains("bounded loop or traversal-state lowering"));
        assertTrue(message.contains("iterative scanner with scalar depth/index state"));
        assertFalse(message.contains("titan-graphql"));
    }

    @Test
    void rejectsMutualRecursiveHelpersWithFullCycleDiagnostic() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transpile("""
                import titan.dsl.StoredFunction;

                class G4MutualRecFixture {
                    @StoredFunction
                    public static int score(int remaining) {
                        return descend(remaining);
                    }

                    static int descend(int remaining) {
                        if (remaining <= 0) {
                            return 0;
                        }
                        return ascend(remaining - 1);
                    }

                    static int ascend(int remaining) {
                        if (remaining <= 0) {
                            return 0;
                        }
                        return 1 + descend(remaining - 1);
                    }
                }
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"));
        assertTrue(message.contains("G4MutualRecFixture.descend(int)"));
        assertTrue(message.contains("G4MutualRecFixture.ascend(int)"));
        assertTrue(message.contains(" -> "));
        assertTrue(message.contains("compiler-known acyclic descriptor traversal"));
    }

    private PipelineResult transpile(String source) throws Exception {
        Path sourceFile = tempDir.resolve("GAP004Fixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(sourceFile),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);
        String renderedWarnings = pipeline.warnings().stream()
                .map(warning -> warning.render() + "\n")
                .reduce("", String::concat);
        return new PipelineResult(
                renderedWarnings,
                sqlFor(generated, "postgresql"),
                sqlFor(generated, "mysql"));
    }

    private static String sqlFor(List<TranspilationPipeline.GeneratedSql> generated, String target) {
        return generated.stream()
                .filter(sql -> sql.target().equals(target))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private record PipelineResult(String renderedWarnings, String postgresSql, String mysqlSql) {
    }
}
