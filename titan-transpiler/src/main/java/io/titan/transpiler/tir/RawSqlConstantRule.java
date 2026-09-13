package io.titan.transpiler.tir;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import io.titan.transpiler.ParsedSources;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;

/**
 * Single authority for the dynamic-SQL safety rule (plan 2.5, audit D14). Shared by
 * {@link io.titan.transpiler.FeatureValidator} (always-on pre-screen) and
 * {@link DslQueryLowerer} (defense-in-depth during lowering) so the two can never drift.
 *
 * <p>The SQL-text argument of a raw-SQL entry point ({@code exec}/{@code query}/
 * {@code queryScalar}) must be a compile-time constant — the validator's
 * {@code compilerKnownString} model extended with constant concatenation:</p>
 * <ul>
 *   <li>a String literal (including multi-line text blocks),</li>
 *   <li>a reference to a {@code static final String} field whose value is a JLS
 *       compile-time constant ({@link VariableElement#getConstantValue()}), or</li>
 *   <li>a {@code +} concatenation whose operands each satisfy these rules.</li>
 * </ul>
 *
 * <p>Everything else — local variables (even single-assignment), method parameters,
 * {@code String.format}, {@code StringBuilder}, {@code concat()}, ternaries — is rejected
 * with {@code TITAN-E004}. Dynamic values belong in {@code :name} bind parameters inside the
 * constant SQL text; the emitters rewrite those to dialect placeholders
 * ({@code AbstractSqlEmitter#rewriteNamedParameters}).</p>
 */
public final class RawSqlConstantRule {

    /** Raw-SQL entry points whose first argument is SQL text. */
    public static final List<String> RAW_SQL_INVOCATION_NAMES = List.of("exec", "query", "queryScalar");

    /** Diagnostic message for a non-constant SQL-text argument. */
    public static String violationMessage(String invocationName) {
        return "SQL injection risk. The SQL text passed to " + invocationName
                + "(...) must be a compile-time constant: a string literal, a static final String constant,"
                + " or a concatenation of those";
    }

    /** Suggestion attached to every {@code TITAN-E004} dynamic-SQL diagnostic. */
    public static String bindParameterSuggestion() {
        return "Keep the SQL text constant and pass dynamic values as :name bind parameters"
                + " inside it (Titan rewrites :name references to dialect placeholders at emission)"
                + " instead of building the SQL string at runtime";
    }

    public static boolean isRawSqlInvocationName(String invocationName) {
        return RAW_SQL_INVOCATION_NAMES.contains(invocationName);
    }

    /**
     * Returns true when {@code expression} is a compile-time constant String under the rules
     * above. {@code unit} is the compilation unit containing the expression when the caller
     * knows it; otherwise identifier resolution falls back to a cross-unit path lookup.
     */
    public static boolean isCompileTimeConstantSqlText(
            ExpressionTree expression,
            CompilationUnitTree unit,
            ParsedSources parsedSources
    ) {
        ExpressionTree stripped = LowererSupport.unwrapParenthesized(expression);
        if (stripped instanceof LiteralTree literal) {
            return literal.getValue() instanceof String;
        }
        if (stripped instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            return isCompileTimeConstantSqlText(binary.getLeftOperand(), unit, parsedSources)
                    && isCompileTimeConstantSqlText(binary.getRightOperand(), unit, parsedSources);
        }
        if (stripped instanceof IdentifierTree || stripped instanceof MemberSelectTree) {
            Element element = resolveElement(stripped, unit, parsedSources);
            return element instanceof VariableElement variable
                    && variable.getKind() == ElementKind.FIELD
                    && variable.getModifiers().contains(Modifier.STATIC)
                    && variable.getModifiers().contains(Modifier.FINAL)
                    && variable.getConstantValue() instanceof String;
        }
        return false;
    }

    private static Element resolveElement(
            ExpressionTree expression,
            CompilationUnitTree unit,
            ParsedSources parsedSources
    ) {
        TreePath path = unit == null ? null : TreePath.getPath(unit, expression);
        if (path == null) {
            path = LowererSupport.resolveTreePath(parsedSources, expression);
        }
        if (path == null) {
            return null;
        }
        return parsedSources.trees().getElement(path);
    }

    private RawSqlConstantRule() {
    }
}
