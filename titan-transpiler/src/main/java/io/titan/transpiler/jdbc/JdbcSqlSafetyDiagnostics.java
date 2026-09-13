package io.titan.transpiler.jdbc;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;

/**
 * The single source of truth for the strict-scope dynamic-SQL splice diagnostic (the §5.3 three-part
 * {@code TITAN-E004} message). Shared by <b>both</b> {@link JdbcUsageRecognizer} (Phase 1 recognition
 * gate) and the Phase-2 {@code JdbcStatementLowerer} (the in-pipeline lowering gate), so the two emit
 * byte-identical wording and the lowerer never diverges from the recognizer's secure-by-default
 * verdict (WS-C Phase 2b audit: the sqlSafety gate must run in the transpile pipeline, not only in the
 * opt-in compat linter).
 */
public final class JdbcSqlSafetyDiagnostics {

    private JdbcSqlSafetyDiagnostics() {
    }

    /** The §5.3 three-part strict-scope diagnostic (value vs identifier variant). */
    public static String strictSpliceDiagnostic(ExpressionTree sqlArg) {
        boolean identifierSplice = looksLikeIdentifierSplice(sqlArg);
        StringBuilder sb = new StringBuilder();
        sb.append("SQL injection risk. The SQL text passed to prepareStatement(...)/execute(...) is built "
                + "from a runtime value, so it is not a compile-time constant. In strict scope (the default) "
                + "raw runtime values may not be spliced into SQL text. ");
        sb.append("(a) Why blocked: concatenating a runtime ")
                .append(identifierSplice ? "identifier" : "value")
                .append(" into SQL text is the classic SQL-injection vector. ");
        if (identifierSplice) {
            sb.append("(b) Safe alternative for this case: interpolating a table/column name cannot be a "
                    + "bound value. On PostgreSQL use format('%I', ident) to quote the identifier safely; "
                    + "MySQL has no format('%I') equivalent, so validate the name against a known catalog "
                    + "set (or an enum the routine switches on) and backtick-quote the verified name, or "
                    + "model the choice as a parameter the routine switches on. ");
        } else {
            sb.append("(b) Safe alternative for this case: keep the SQL text constant and BIND the value as "
                    + "a parameter (prepareStatement(\"... WHERE col = ?\"); ps.setLong(1, value)). For a "
                    + "dynamic IN-list use the safe forms in §3.6 (PostgreSQL WHERE id = ANY(?) array bind, "
                    + "or IN (SELECT ...) subquery fusion). ");
        }
        sb.append("(c) Escape hatch: annotate this method @SqlSafety(PERMISSIVE) to transpile it as-is as a "
                + "RawSql passthrough (the rest of the build stays strict); for bulk migration set "
                + "sqlSafety=permissive in the titan{} build config. Either way only the constant-text rule "
                + "is relaxed; bound values stay parameterized, and passthrough is single-dialect (D1). "
                + "(docs/transpilable-jdbc-subset.md §5.3)");
        return sb.toString();
    }

    private static boolean looksLikeIdentifierSplice(ExpressionTree sqlArg) {
        // Heuristic for diagnostic wording only (never a correctness gate): a constant fragment
        // ending in "FROM "/"JOIN "/"INTO "/"TABLE "/"UPDATE "/"ORDER BY " — or a comma/open-paren that
        // CONTINUES an identifier list ("ORDER BY a, "/"SELECT a, "/"INSERT INTO t (") — suggests an
        // identifier splice rather than a value splice. Delegates to the shared structural predicates
        // (JdbcShapes.endsInIdentifierPosition / endsInIdentifierListPosition) so the diagnostic wording
        // and the skeleton recognizer's IDENTIFIER hole classification stay one source of truth (D7).
        String prefix = constantPrefixText(JdbcShapes.unwrap(sqlArg));
        return JdbcShapes.endsInIdentifierPosition(prefix)
                || JdbcShapes.endsInIdentifierListPosition(prefix);
    }

    private static String constantPrefixText(ExpressionTree expr) {
        if (expr instanceof LiteralTree literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (expr instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            return constantPrefixText(JdbcShapes.unwrap(binary.getLeftOperand()));
        }
        return null;
    }
}
