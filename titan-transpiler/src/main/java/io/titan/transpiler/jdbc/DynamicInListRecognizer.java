package io.titan.transpiler.jdbc;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * FAIL-SAFE recognizer for the §3.6(3) literal {@code IN (?, ?, ...)} placeholder-count idiom — the
 * one place where the blunt "SQL text must be a compile-time constant" rule ({@code RawSqlConstantRule})
 * would be both wrong and harmful, because the only non-constant part is a generated run of
 * {@code ?}/separator tokens and <b>every value is bound</b>.
 *
 * <p><b>Non-negotiable safety contract</b> ({@code docs/transpilable-jdbc-subset.md} §3.6(3), and the
 * design contract's decision 5): this recognizer accepts a concatenation <i>only</i> when it can
 * <b>prove</b> the run is {@code ?}/{@code ,}/whitespace-separator-only (no value, no identifier ever
 * reaches the SQL text) <i>and</i> every interpolated ordinal has a matching {@code setXxx} bind.
 * Anything it cannot prove — a value spliced via {@code +}, a {@code String.format}, a
 * {@code StringBuilder}, an opaque helper whose output it cannot see — <b>falls through</b> (returns
 * {@code false}) to the I-R1 strict gate. It must <b>never</b> classify a value-splice as
 * safe/transpilable; the adversarial audit verifies there is no false-accept.</p>
 *
 * <p>Because Phase 1 does not parse the SQL text and cannot statically evaluate an arbitrary
 * {@code placeholders(n)} helper, the only structurally-provable shape is a concatenation whose
 * non-constant operands are themselves provably placeholder/separator string expressions. A genuine
 * runtime-sized {@code ?}-run built by an unanalyzable helper is <b>not</b> provable here, so it is
 * (correctly) rejected to strict — never guessed safe.</p>
 *
 * <p><b>Phase 1 status — guard shipped, accept path DEFERRED.</b> Per the design contract's Decision
 * 5, the recognition-only Phase 1 does <b>not</b> wire an accept branch off this guard: it cannot
 * additionally prove every placeholder ordinal has a matching {@code setXxx} across an unparsed run
 * (no SQL parse, no per-ordinal bind tracking), and the contract forbids ever false-accepting a
 * value splice. So {@link JdbcUsageRecognizer} does not call this in the classification path today;
 * the §3.6 dynamic-IN shapes fall through to the strict/permissive gate. This class and
 * {@code DynamicInListRecognizerTest} exist to lock the FAIL-SAFE structural guard for the later
 * phase that adds the bind proof / subquery fusion and turns on the accept branch.</p>
 */
public final class DynamicInListRecognizer {

    // A provable placeholder/separator constant: only '?', ',', '(' , ')', whitespace, and the
    // literal "IN" keyword fragment. No letters/digits that could be a value or identifier.
    private static final Pattern PLACEHOLDER_OR_SEPARATOR_ONLY =
            Pattern.compile("[?,()\\s]*(?:(?i:in)[?,()\\s]*)*");

    private DynamicInListRecognizer() {
    }

    /**
     * Returns {@code true} only when {@code sqlArg} is a {@code +} concatenation that is provably a
     * placeholder/separator-only run (every interpolated fragment a placeholder/separator constant)
     * and {@code ordinalBound} confirms every placeholder ordinal has a matching bind. Returns
     * {@code false} (fall through to the strict gate) for anything else — including a single string
     * literal (that is the constant path, handled by {@code RawSqlConstantRule}, not here).
     *
     * @param sqlArg       the SQL-text argument expression to test
     * @param ordinalBound predicate answering "does placeholder ordinal N have a matching setXxx?";
     *                     the FAIL-SAFE caller passes a conservative implementation
     */
    public static boolean isProvablePlaceholderRun(ExpressionTree sqlArg, IntPredicate ordinalBound) {
        ExpressionTree expr = unwrap(sqlArg);
        // Must be a concatenation: a bare literal is the constant path, not this carve-out.
        if (!(expr instanceof BinaryTree binary) || binary.getKind() != Tree.Kind.PLUS) {
            return false;
        }
        // Every operand of the (possibly nested) concatenation must be a provable
        // placeholder/separator string constant. If any operand is non-constant or carries a
        // value/identifier character, the whole thing falls through to strict — no false-accept.
        int placeholderCount = countProvablePlaceholders(expr);
        if (placeholderCount <= 0) {
            return false;
        }
        // Every ordinal 1..N must be provably bound. The FAIL-SAFE caller's predicate returns false
        // unless it can prove the bind, so an unprovable run is rejected to strict here.
        for (int ordinal = 1; ordinal <= placeholderCount; ordinal++) {
            if (!ordinalBound.test(ordinal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Counts the {@code ?} placeholders across a concatenation, returning {@code -1} the moment any
     * operand is not a provable placeholder/separator string constant (which forces a fall-through).
     */
    private static int countProvablePlaceholders(ExpressionTree expr) {
        ExpressionTree current = unwrap(expr);
        if (current instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            int left = countProvablePlaceholders(binary.getLeftOperand());
            if (left < 0) {
                return -1;
            }
            int right = countProvablePlaceholders(binary.getRightOperand());
            if (right < 0) {
                return -1;
            }
            return left + right;
        }
        if (current instanceof LiteralTree literal && literal.getValue() instanceof String text) {
            if (!PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(text).matches()) {
                return -1;
            }
            return (int) text.chars().filter(c -> c == '?').count();
        }
        // Non-constant operand (variable/method-result/format/StringBuilder): not provable -> reject.
        return -1;
    }

    private static ExpressionTree unwrap(ExpressionTree expr) {
        ExpressionTree current = expr;
        while (current instanceof ParenthesizedTree parenthesized) {
            current = parenthesized.getExpression();
        }
        return current;
    }
}
