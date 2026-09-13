package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the §3.6(3) FAIL-SAFE contract directly: even with a maximally-permissive
 * {@code ordinalBound} (always "bound"), a value- or identifier-splice is NEVER classified as a
 * provable placeholder run. Only a structurally-provable {@code ?}/separator-only concatenation is.
 */
class DynamicInListRecognizerTest {

    @TempDir
    Path tempDir;

    // Always-bound predicate: the worst case for safety — proves the structural guard alone rejects
    // value splices, independent of any per-ordinal bind proof.
    private static final IntPredicate ALWAYS_BOUND = ordinal -> true;

    /** Parses the right-hand sides of a sequence of {@code String x = <expr>;} declarations. */
    private List<ExpressionTree> initializers(String... exprs) throws Exception {
        StringBuilder body = new StringBuilder("class Fixtures {\n  void m(long id, String tableName) {\n");
        for (int i = 0; i < exprs.length; i++) {
            body.append("    String s").append(i).append(" = ").append(exprs[i]).append(";\n");
        }
        body.append("  }\n}\n");
        Path sourceFile = tempDir.resolve("Fixtures.java");
        Files.writeString(sourceFile, body.toString());
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<ExpressionTree> result = new ArrayList<>();
        for (var unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getInitializer() != null) {
                        result.add(node.getInitializer());
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    @Test
    void valueSpliceIsNeverProvable() throws Exception {
        // "... IN (" + id + ")" — a runtime VALUE in the text. Must be false even with ALWAYS_BOUND.
        ExpressionTree expr = initializers("\"SELECT * FROM t WHERE id IN (\" + id + \")\"").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
    }

    @Test
    void identifierSpliceIsNeverProvable() throws Exception {
        ExpressionTree expr = initializers("\"SELECT * FROM \" + tableName").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
    }

    @Test
    void plainConstantLiteralIsNotTreatedAsCarveOut() throws Exception {
        // A constant literal is the RawSqlConstantRule constant path, not this carve-out.
        ExpressionTree expr = initializers("\"SELECT * FROM t WHERE id IN (?, ?)\"").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
    }

    @Test
    void concatenationWithANonPlaceholderConstantFragmentIsNotProvable() throws Exception {
        // The middle fragment carries letters (a potential value/identifier), so it is not provable.
        ExpressionTree expr = initializers("\"WHERE id IN (\" + \"DROP\" + \")\"").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
    }

    @Test
    void allConstantConcatenationIsFoldedAndHandledByTheConstantPathNotTheCarveOut() throws Exception {
        // javac constant-folds "(" + "?,?" + ")" into a single String LITERAL at parse time, so it
        // never reaches the carve-out's concatenation branch — it is the RawSqlConstantRule constant
        // path (TRANSPILABLE on its own). The carve-out only ever sees concatenations with a runtime
        // (non-folded) operand, which Phase 1 cannot prove is placeholder-only — so it FAILS SAFE by
        // returning false here regardless of the bind predicate.
        ExpressionTree expr = initializers("\"WHERE id IN (\" + \"?,?\" + \")\"").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ordinal -> false));
    }

    @Test
    void runtimePlaceholderFragmentIsNotProvableInPhase1() throws Exception {
        // A genuine runtime-sized ?-run (e.g. a method-built fragment) is a real BinaryTree with a
        // non-constant operand: not provable -> false, even with ALWAYS_BOUND. This is the exact
        // shape §3.6(3) describes, and Phase 1 (no per-ordinal bind proof) FAILS SAFE on it.
        ExpressionTree expr = initializers("\"WHERE id IN (\" + String.valueOf(id) + \")\"").getFirst();
        assertFalse(DynamicInListRecognizer.isProvablePlaceholderRun(expr, ALWAYS_BOUND));
    }

    // -----------------------------------------------------------------------------------------------
    // WS-C Phase 3 Rung 1 cross-lock: the NEW skeleton accept path (the lowerer keys ACCEPT on a
    // recovered skeleton being all-value-bindable) must NOT false-accept the SAME adversarial splices
    // this FAIL-SAFE recognizer rejects. These assert the skeleton recognizer classifies each
    // value/identifier/raw splice as NOT all-value-bindable — so a raw value or identifier can never
    // reach the emitted SQL text via the new path either. Never weaken these (design contract §8).
    // -----------------------------------------------------------------------------------------------

    private boolean skeletonAllValueBindable(String expr) throws Exception {
        ExpressionTree tree = initializers(expr).getFirst();
        return JdbcSqlSkeletonRecognizer.recover(tree)
                .filter(JdbcSqlSkeleton::hasHole)
                .filter(JdbcSqlSkeleton::allHolesValueBindable)
                .isPresent();
    }

    @Test
    void skeletonPathDoesNotFalseAcceptAnIdentifierSplice() throws Exception {
        // "SELECT * FROM " + tableName is an IDENTIFIER hole — reaches the text, not bindable.
        assertFalse(skeletonAllValueBindable("\"SELECT * FROM \" + tableName"));
    }

    @Test
    void skeletonPathDoesNotFalseAcceptARawFragmentSplice() throws Exception {
        // "... WHERE " + tableName splices an opaque predicate fragment (neither a clear value nor
        // identifier position) — RAW_FRAGMENT, not bindable.
        assertFalse(skeletonAllValueBindable("\"SELECT * FROM t WHERE \" + tableName"));
    }

    @Test
    void skeletonPathDoesNotFalseAcceptAStringJoinOverRuntimeValues() throws Exception {
        // String.join over a runtime String[] joins VALUES into the text (not "?"s) — RAW_FRAGMENT.
        assertFalse(skeletonAllValueBindable(
                "\"SELECT * FROM t WHERE id IN (\" + String.join(\",\", new String[]{tableName}) + \")\""));
    }

    @Test
    void skeletonPathAcceptsAValueSpliceAsAllValueBindable() throws Exception {
        // The one deliberately-accepted shape (design contract D3): a scalar value splice is
        // value-binding by construction (it becomes a synthesized '?' bind, never spliced text).
        assertTrue(skeletonAllValueBindable("\"SELECT * FROM t WHERE id = \" + id"));
    }
}
