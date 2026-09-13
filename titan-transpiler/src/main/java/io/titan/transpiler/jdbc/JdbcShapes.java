package io.titan.transpiler.jdbc;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import io.titan.transpiler.ParsedSources;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * The <b>single shared source of truth</b> for "what JDBC shape is this Java AST node?", used by
 * <b>both</b> {@link JdbcUsageRecognizer} (WS-C Phase 1 — recognition/classification) and the
 * Phase-2 {@code JdbcStatementLowerer} (which produces the TIR). Extracting the shape predicates
 * here is what guarantees the lowerer never accepts a shape the recognizer rejects: the acceptance
 * logic is not forked — both paths call these same predicates over the same resolved
 * {@link TypeMirror}s (via {@link JdbcTypeOracle}).
 *
 * <p>This type is purely structural: it answers "is this an {@code rs.next()} call?", "which I-4
 * single-row shape is this {@code if}?", "does this then-block throw?", etc. It holds no per-method
 * mutable state (that lives in the recognizer's handle map and the lowerer's binding map) — every
 * method is a pure function of the AST node, its {@link TreePath}, and the resolved types.</p>
 */
public final class JdbcShapes {

    /** What a tracked {@code java.sql} local/receiver is. */
    public enum HandleKind {
        PREPARED_STATEMENT, PLAIN_STATEMENT, RESULT_SET, RESULT_SET_METADATA, CALLABLE_STATEMENT, CONNECTION
    }

    /**
     * The recognized I-4 single-row shape of an {@code if} whose condition involves
     * {@code rs.next()} (§3.3). {@link #NOT_JDBC} means the {@code if} is an ordinary branch the
     * stock lowerer handles; {@link #REJECTED} is an enumerated unsupported single-row shape.
     */
    public enum SingleRowShape {
        /** {@code if (rs.next()) { reads }} with no else — maps to a NULL-on-no-row read. */
        THEN_BLOCK_READ,
        /** {@code if (!rs.next()) { throw ...; } <reads after>} — the §6.1 early-return guard. */
        GUARD_THROW,
        /** An enumerated unsupported single-row shape (else-branch, non-throwing guard). */
        REJECTED,
        /** Not an {@code rs.next()} branch at all — an ordinary {@code if}. */
        NOT_JDBC
    }

    private final ParsedSources parsed;
    private final JdbcTypeOracle oracle;

    public JdbcShapes(ParsedSources parsed, JdbcTypeOracle oracle) {
        this.parsed = parsed;
        this.oracle = oracle;
        if (parsed == null || oracle == null) {
            throw new IllegalArgumentException("parsed/oracle must not be null");
        }
    }

    public JdbcTypeOracle oracle() {
        return oracle;
    }

    // ---- handle classification ----

    /** The {@link HandleKind} of a resolved type, or {@code null} if it is not a JDBC handle. */
    public HandleKind classifyHandle(TypeMirror type) {
        if (type == null) {
            return null;
        }
        if (oracle.isResultSet(type)) {
            return HandleKind.RESULT_SET;
        }
        if (oracle.isResultSetMetaData(type)) {
            return HandleKind.RESULT_SET_METADATA;
        }
        if (oracle.isCallableStatement(type)) {
            return HandleKind.CALLABLE_STATEMENT;
        }
        if (oracle.isPreparedStatement(type)) {
            return HandleKind.PREPARED_STATEMENT;
        }
        if (oracle.isPlainStatement(type)) {
            return HandleKind.PLAIN_STATEMENT;
        }
        if (oracle.isConnectionLike(type)) {
            return HandleKind.CONNECTION;
        }
        return null;
    }

    // ---- rs.next() detection (shared by if/while/do-while recognition) ----

    public boolean isResultSetNextCall(ExpressionTree expr, TreePath path) {
        return resultSetNextReceiver(expr, path) != null;
    }

    /** The {@link Element} of the {@code ResultSet} receiver of an {@code rs.next()} call, or null. */
    public Element resultSetNextReceiver(ExpressionTree expr, TreePath path) {
        ExpressionTree unwrapped = unwrap(expr);
        if (unwrapped instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("next")
                && invocation.getArguments().isEmpty()) {
            TypeMirror receiverType = typeOf(select.getExpression(), path);
            if (receiverType != null && oracle.isResultSet(receiverType)) {
                return elementOf(select.getExpression(), path);
            }
        }
        return null;
    }

    // ---- I-4 single-row shape classification (§3.3) ----

    /**
     * Classifies an {@code if} whose condition may be {@code rs.next()} / {@code !rs.next()} into one
     * of the {@link SingleRowShape}s. This is the exact discriminator the recognizer uses, so the
     * lowerer's I-4 production and the recognizer's I-4 verdict stay aligned.
     */
    public SingleRowShape classifyIfShape(IfTree node, TreePath path) {
        ExpressionTree condition = unwrap(node.getCondition());
        // shape 1: if (rs.next()) { reads }   (no else)
        if (isResultSetNextCall(condition, path)) {
            return node.getElseStatement() != null ? SingleRowShape.REJECTED : SingleRowShape.THEN_BLOCK_READ;
        }
        // shape 2: if (!rs.next()) { throw ...; } <reads after>  (the §6.1 early-return guard).
        if (condition instanceof UnaryTree unary && unary.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
                && isResultSetNextCall(unwrap(unary.getExpression()), path)) {
            return thenBlockThrows(node.getThenStatement())
                    ? SingleRowShape.GUARD_THROW
                    : SingleRowShape.REJECTED;
        }
        return SingleRowShape.NOT_JDBC;
    }

    public boolean thenBlockThrows(StatementTree thenStatement) {
        // The early-return guard's then-statement is (or unconditionally reaches) a throw. A bare
        // `throw ...;` matches; a block matches when its last reachable statement is a throw.
        StatementTree terminal = thenStatement;
        if (terminal instanceof BlockTree block) {
            List<? extends StatementTree> statements = block.getStatements();
            if (statements.isEmpty()) {
                return false;
            }
            terminal = statements.get(statements.size() - 1);
        }
        return terminal instanceof ThrowTree;
    }

    public boolean bodyUnconditionallyBreaks(StatementTree body) {
        // True when the last statement of the loop body is an unlabeled `break;` reached on every
        // path (a bare terminal break, not nested in an if/else): the loop reads exactly one row.
        StatementTree terminal = body;
        if (terminal instanceof BlockTree block) {
            List<? extends StatementTree> statements = block.getStatements();
            if (statements.isEmpty()) {
                return false;
            }
            terminal = statements.get(statements.size() - 1);
        }
        return terminal instanceof BreakTree breakTree && breakTree.getLabel() == null;
    }

    /**
     * True if the {@code while (rs.next())} loop {@code body} opens a <i>different</i>
     * {@code ResultSet} (a nested cursor) than the {@code driving} one — §3.3 rejects a second
     * ResultSet opened inside the loop in v1. The single source of truth for this guard, shared by
     * {@link JdbcUsageRecognizer} (its cursor-loop rejection) and the Phase-2
     * {@code JdbcStatementLowerer.lowerCursorLoop} (so the lowerer rejects with the same actionable
     * message instead of crashing on the inner {@code PreparedStatement} local's type mapping).
     */
    public boolean bodyOpensNestedCursor(Tree body, Element driving, TreePath path) {
        boolean[] found = {false};
        TreePath bodyPath = new TreePath(path, body);
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                TreePath vp = getCurrentPath();
                TypeMirror t = parsed.trees().getTypeMirror(vp);
                if (t != null && oracle.isResultSet(t)) {
                    Element local = parsed.trees().getElement(vp);
                    if (local != null && !local.equals(driving)) {
                        found[0] = true;
                    }
                }
                return super.visitVariable(node, unused);
            }
        }.scan(bodyPath, null);
        return found[0];
    }

    // ---- shared small predicates ----

    public boolean isBareExpressionStatement(TreePath path) {
        TreePath parent = path.getParentPath();
        return parent != null && parent.getLeaf() instanceof ExpressionStatementTree;
    }

    /** True for a {@code prepareStatement(SQL, RETURN_GENERATED_KEYS)} second argument. */
    public static boolean isReturnGeneratedKeys(ExpressionTree arg) {
        ExpressionTree expr = unwrap(arg);
        if (expr instanceof MemberSelectTree select) {
            return select.getIdentifier().contentEquals("RETURN_GENERATED_KEYS");
        }
        return isIntLiteralValue(expr, 1);
    }

    /**
     * Whether {@code invocation} is the <b>supported</b> generated-key read shape (§6.3 / I-7): a single
     * auto-increment/identity <i>integer</i> key read as {@code getLong(1)} or {@code getInt(1)} over the
     * {@code getGeneratedKeys()} ResultSet. The JDBC ordinal {@code 1} does not name a column — it is the
     * first (and only) generated key, whose column is resolved from the Catalog
     * ({@link JdbcGeneratedKeyResolver}) and emitted as {@code INSERT … RETURNING <col>} (PostgreSQL) /
     * {@code SET v = LAST_INSERT_ID()} (MySQL).
     *
     * <p>This predicate is the shared source of truth for both {@link JdbcUsageRecognizer} (parity-accept)
     * and the {@code JdbcStatementLowerer}: a read that is <i>not</i> this shape ({@code getString(1)},
     * {@code getLong(2)}, a boxed/Object accessor, a non-literal ordinal, or a second key read) is
     * rejected I-R8 by both with the same precise diagnostic instead of being silently dropped.</p>
     */
    public static boolean isSingleIntKeyRead(MethodInvocationTree invocation) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return false;
        }
        String name = select.getIdentifier().toString();
        if (!name.equals("getLong") && !name.equals("getInt")) {
            return false;
        }
        return invocation.getArguments().size() == 1
                && isIntLiteralValue(unwrap(invocation.getArguments().getFirst()), 1);
    }

    /**
     * Whether {@code invocation} is the typed two-arg ResultSet getter {@code getObject(<col>, <Type>.class)}
     * — the canonical way to read a column whose Java type has no dedicated {@code getXxx} accessor (notably
     * {@code java.util.UUID}: there is no {@code rs.getUuid}). The first argument is the column key (a
     * constant name/ordinal, validated by the caller); the second is a {@code .class} literal supplying the
     * read's static type. Shared source of truth for {@link JdbcUsageRecognizer} (parity-accept) and the
     * {@code JdbcStatementLowerer} (which reads the same shape into a column read), so neither accepts a
     * form the other silently drops — the divergence that previously emitted an empty {@code INTO} target.
     */
    public static boolean isTypedGetObjectColumnRead(MethodInvocationTree invocation) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("getObject")
                || invocation.getArguments().size() != 2) {
            return false;
        }
        return isClassLiteral(invocation.getArguments().get(1));
    }

    /** True for a {@code SomeType.class} literal (e.g. {@code UUID.class}, {@code java.util.UUID.class}). */
    public static boolean isClassLiteral(ExpressionTree arg) {
        return unwrap(arg) instanceof MemberSelectTree select && select.getIdentifier().contentEquals("class");
    }

    public TypeMirror typeOf(ExpressionTree expr, TreePath path) {
        if (expr == null) {
            return null;
        }
        return parsed.trees().getTypeMirror(new TreePath(path, expr));
    }

    public Element elementOf(ExpressionTree expr, TreePath path) {
        if (expr == null) {
            return null;
        }
        return parsed.trees().getElement(new TreePath(path, expr));
    }

    public static ExpressionTree firstArg(MethodInvocationTree node) {
        return node.getArguments().isEmpty() ? null : node.getArguments().getFirst();
    }

    public static ExpressionTree unwrap(ExpressionTree expr) {
        ExpressionTree current = expr;
        while (current instanceof ParenthesizedTree p) {
            current = p.getExpression();
        }
        return current;
    }

    public static boolean isIntLiteral(ExpressionTree expr) {
        return expr instanceof LiteralTree literal && literal.getValue() instanceof Integer;
    }

    static boolean isIntLiteralValue(ExpressionTree expr, int value) {
        return expr instanceof LiteralTree literal
                && literal.getValue() instanceof Integer i && i == value;
    }

    public static boolean isStringLiteral(ExpressionTree expr) {
        return expr instanceof LiteralTree literal && literal.getValue() instanceof String;
    }

    static boolean isIdentifierNamed(ExpressionTree expr, String name) {
        return unwrap(expr) instanceof IdentifierTree id && id.getName().contentEquals(name);
    }

    // ---- shared skeleton-recovery structural predicates (WS-C Phase 3, design contract D1/D7) ----
    // The single source of truth for "what does a constant SQL fragment ending at a hole look like?",
    // used by JdbcSqlSkeletonRecognizer (and any lowerer-side agreement) so the recognizer and the
    // lowerer cannot diverge on hole classification.

    /** The constant int value of an int-literal expression, or {@code null} if it is not one. */
    public static Integer constantIntValue(ExpressionTree expr) {
        ExpressionTree current = unwrap(expr);
        return current instanceof LiteralTree literal && literal.getValue() instanceof Integer i ? i : null;
    }

    /** The String value of a string-literal expression, or {@code null} if it is not one. */
    public static String stringLiteralValue(ExpressionTree expr) {
        ExpressionTree current = unwrap(expr);
        return current instanceof LiteralTree literal && literal.getValue() instanceof String s ? s : null;
    }

    /**
     * Whether a constant SQL fragment ending in {@code constantPrefix} places the next runtime hole in
     * an <b>identifier position</b> (a table/column splice), as opposed to a value position. The single
     * structural source of truth shared by {@link JdbcSqlSafetyDiagnostics} (diagnostic wording) and the
     * skeleton recognizer (hole classification — design contract D2: identifier holes are NOT
     * value-bindable). A fragment ending in {@code FROM}/{@code JOIN}/{@code INTO}/{@code UPDATE}/{@code
     * TABLE}/{@code ORDER BY}/{@code GROUP BY}/{@code BY} (ignoring trailing whitespace) introduces an
     * identifier; one ending in {@code =}/{@code (}/{@code ,}/{@code IN (}/an operator/a comparison
     * introduces a value. <b>FAIL-SAFE direction is the caller's:</b> this is a positive identifier
     * test; the recognizer treats a fragment that ends in <i>neither</i> a clear value position nor a
     * clear identifier position as the catch-all {@code RAW_FRAGMENT}, never silently as a bound value.
     */
    public static boolean endsInIdentifierPosition(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        String upper = constantPrefix.toUpperCase(java.util.Locale.ROOT).stripTrailing();
        return upper.endsWith("FROM") || upper.endsWith("JOIN") || upper.endsWith("INTO")
                || upper.endsWith("UPDATE") || upper.endsWith("TABLE")
                || upper.endsWith("ORDER BY") || upper.endsWith("GROUP BY") || upper.endsWith(" BY");
    }

    /**
     * Whether a constant SQL fragment ending in {@code constantPrefix} places the next runtime hole in a
     * <b>sort / grouping clause</b> ({@code ORDER BY}/{@code GROUP BY}/{@code … BY}) — a position whose
     * runtime value is a sort/grouping <i>expression</i>, NOT a single bare identifier. This is the
     * subset of {@link #endsInIdentifierPosition} for which the runtime value may legitimately carry a
     * trailing direction ({@code col DESC}), a {@code NULLS LAST}, a function, or a comma list — none of
     * which is a single quotable identifier. WS-C Phase 3 Rung 3 audit fix (Findings 1): the skeleton
     * recognizer classifies such a hole {@code RAW_FRAGMENT} (spliced verbatim under {@code permissive},
     * faithfully reproducing the source — the plain-JDBC {@code "ORDER BY " + orderBy} semantics) rather
     * than {@code IDENTIFIER} (which would {@code %I}/backtick-quote the whole {@code "bal DESC"} as ONE
     * identifier and fail at runtime with "column \"bal DESC\" does not exist"). {@code strict} still
     * rejects it (it is structural text, not a bound value), and the strict diagnostic still recommends
     * {@code format('%I', col)} for the bare-column case via {@link #endsInIdentifierPosition} — this
     * predicate only steers the <i>permissive emit</i> away from a corrupting {@code %I}. Table/relation
     * name positions ({@code FROM}/{@code JOIN}/{@code INTO}/{@code UPDATE}/{@code TABLE}) are NOT sort
     * clauses, so they stay {@code IDENTIFIER} (runtime-quoted, qualified-name aware).
     */
    public static boolean endsInSortClausePosition(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        String upper = constantPrefix.toUpperCase(java.util.Locale.ROOT).stripTrailing();
        return upper.endsWith("ORDER BY") || upper.endsWith("GROUP BY") || upper.endsWith(" BY");
    }

    /**
     * Whether a comma- or open-paren-delimited identifier-list position (per {@link
     * #endsInIdentifierListPosition}) is specifically a continuation of a <b>sort / grouping list</b>
     * ({@code ORDER BY a, } / {@code GROUP BY a, }) — as opposed to a {@code SELECT} list or an {@code
     * INSERT INTO t (} column list. WS-C Phase 3 Rung 3 audit fix (Findings 1): a later element of an
     * {@code ORDER BY}/{@code GROUP BY} list ({@code "ORDER BY tier, " + sortCol}) is also a sort
     * expression that may carry a direction, so it is classified {@code RAW_FRAGMENT} (verbatim) like the
     * first element, NOT {@code IDENTIFIER}. A {@code SELECT}/{@code INSERT} column-list element stays an
     * identifier (a bare column name). Narrow / fail-safe: only the provable {@code ORDER BY}/{@code GROUP
     * BY} comma-continuation returns {@code true}.
     */
    public static boolean endsInSortListPosition(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        String trimmed = constantPrefix.stripTrailing();
        if (trimmed.isEmpty() || trimmed.charAt(trimmed.length() - 1) != ',') {
            return false;
        }
        return commaContinuesSortList(trimmed.toUpperCase(java.util.Locale.ROOT));
    }

    /** A top-level {@code ,} continuing specifically an {@code ORDER BY} / {@code GROUP BY} list. */
    private static boolean commaContinuesSortList(String upper) {
        int depth = 0;
        for (int i = upper.length() - 2; i >= 0; i--) {
            char c = upper.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (depth == 0) {
                String upToHere = upper.substring(0, i + 1);
                if (endsWithWord(upToHere, "ORDER BY") || endsWithWord(upToHere, "GROUP BY")) {
                    return true;
                }
                // Reaching another controlling clause first means the comma is not in a sort list.
                if (endsWithWord(upToHere, "FROM") || endsWithWord(upToHere, "WHERE")
                        || endsWithWord(upToHere, "VALUES") || endsWithWord(upToHere, "SET")
                        || endsWithWord(upToHere, "SELECT")) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Whether a constant SQL fragment ending in {@code constantPrefix} places the next runtime hole in a
     * clear <b>value position</b> (so a runtime expression there is a bindable scalar value, design
     * contract D3). True when the fragment ends (ignoring trailing whitespace) in an assignment/comparison
     * operator or an open paren/comma that introduces a value: {@code =}, {@code <}, {@code >}, {@code +},
     * {@code -}, {@code *}, {@code /}, {@code (}, {@code ,}, or the keyword {@code VALUES}/{@code IN}/{@code
     * LIKE}/{@code AND}/{@code OR}/{@code RETURN}/{@code SET}/{@code WHEN}/{@code THEN}/{@code ELSE}. Used
     * only to <i>confirm</i> a value position; a fragment that ends in neither this nor an identifier
     * position is the FAIL-SAFE {@code RAW_FRAGMENT} (the recognizer never assumes value by default).
     */
    public static boolean endsInValuePosition(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        String trimmed = constantPrefix.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '=' || last == '<' || last == '>' || last == '+' || last == '-'
                || last == '*' || last == '/' || last == '(' || last == ',' || last == '%') {
            return true;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith("VALUES") || upper.endsWith(" IN") || upper.endsWith("(IN")
                || upper.endsWith("LIKE") || upper.endsWith(" AND") || upper.endsWith(" OR")
                || upper.endsWith("RETURN") || upper.endsWith(" SET") || upper.endsWith("WHEN")
                || upper.endsWith("THEN") || upper.endsWith("ELSE");
    }

    /**
     * Whether a constant SQL fragment ending in {@code constantPrefix} places the next runtime hole in an
     * <b>identifier-list position</b> — a comma- or open-paren-delimited slot of a clause whose elements
     * are identifiers, not values. This refines the bare {@code ,}/{@code (} value heuristic in {@link
     * #endsInValuePosition}: a {@code ,} after {@code ORDER BY tier}/{@code GROUP BY a}/{@code SELECT a},
     * or a {@code (} opening an {@code INSERT INTO t (}/{@code GROUP BY (} column list, continues an
     * identifier list — so the next hole is an IDENTIFIER (not value-bindable, design contract D2), NOT a
     * bound value. (WS-C Phase 3 Rung 1 security fix: {@code "ORDER BY tier, " + sortCol} must not be
     * silently swallowed as a bound literal — that neuters the dynamic sort and breaks D2's identifier
     * reject.) The single source of truth shared by the skeleton recognizer's hole classification.
     *
     * <p><b>Scope (deliberately narrow / fail-safe).</b> Returns {@code true} only for the clearly-provable
     * identifier-list contexts and only when the trailing delimiter is {@code ,} or {@code (}; everything
     * else (notably a {@code ,}/{@code (} in a {@code VALUES (...)} row or a function-argument list) is
     * left to {@link #endsInValuePosition} as before, so the existing {@code INSERT ... VALUES (} value
     * splice keeps binding. When ambiguous it returns {@code false}; the caller's fail-safe then keeps the
     * hole RAW_FRAGMENT or — via {@link #endsInValuePosition} — a bound value (never a silent identifier
     * splice into the text, which is the security property that matters).</p>
     */
    public static boolean endsInIdentifierListPosition(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        String trimmed = constantPrefix.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last != ',' && last != '(') {
            return false;
        }
        // The controlling clause is the nearest clause keyword at the current paren context scanning back
        // across the comma-separated list. An ORDER BY / GROUP BY / SELECT (pre-FROM) list is identifiers;
        // an INSERT INTO <table> ( column list is identifiers; a VALUES ( row / a function-call paren is
        // values (left to endsInValuePosition). Paren-depth aware so VALUES (a, ) is not read as the
        // INSERT column list.
        return identifierListClause(trimmed, last);
    }

    private static boolean identifierListClause(String trimmed, char last) {
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (last == '(') {
            // A '(' opens an identifier list only as the INSERT/REPLACE column list — directly after the
            // target table name and BEFORE any VALUES/SELECT. "INSERT INTO t (" -> identifier; "VALUES (",
            // "v (", any other "(" -> not an identifier list (value list / function args).
            return openParenIsInsertColumnList(upper);
        }
        // last == ',': continue the nearest top-level identifier-list clause. Walk back over the prefix at
        // paren-depth 0 to the controlling keyword; ORDER BY / GROUP BY (any element) and SELECT (only
        // before FROM) are identifier lists.
        return commaContinuesIdentifierList(upper);
    }

    /** "INSERT INTO &lt;table&gt; (" / "REPLACE INTO &lt;table&gt; (" with no intervening VALUES/SELECT. */
    private static boolean openParenIsInsertColumnList(String upper) {
        // Strip the trailing '(' and any whitespace before it; the remainder must end in "INSERT INTO
        // <ident>" / "REPLACE INTO <ident>" (a single unqualified or dotted identifier), with no VALUES or
        // SELECT keyword after the INTO (which would mean we are past the column list).
        String head = upper.substring(0, upper.length() - 1).stripTrailing();
        int into = head.lastIndexOf("INTO");
        if (into < 0) {
            return false;
        }
        // Must be a whole-word INTO preceded by INSERT/REPLACE (a real column-list opener), not e.g. a
        // column named "...into". Require INSERT or REPLACE earlier on the line.
        String beforeInto = head.substring(0, into);
        if (!(beforeInto.contains("INSERT") || beforeInto.contains("REPLACE"))) {
            return false;
        }
        String afterInto = head.substring(into + "INTO".length());
        // No VALUES / SELECT between INTO and the '(' -> the '(' is the column list. A second '(' already
        // in afterInto would mean a nested paren (not the column-list opener).
        if (afterInto.contains("VALUES") || afterInto.contains("SELECT") || afterInto.indexOf('(') >= 0) {
            return false;
        }
        // afterInto should be exactly the table identifier (letters/digits/_/./whitespace/backticks/quotes).
        String ident = afterInto.strip();
        if (ident.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '`' || c == '"')) {
                return false;
            }
        }
        return true;
    }

    /** A top-level {@code ,} continuing an ORDER BY / GROUP BY / pre-FROM SELECT identifier list. */
    private static boolean commaContinuesIdentifierList(String upper) {
        // Walk back from the trailing comma at paren-depth 0; find the controlling clause keyword. Stop at
        // a depth-0 '(' (the comma is inside a paren group — a value/function-arg list, not our concern).
        int depth = 0;
        // skip the trailing comma itself.
        for (int i = upper.length() - 2; i >= 0; i--) {
            char c = upper.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    // The comma sits directly inside a '(' group: a VALUES row / function args / an
                    // explicit "(a, b)" identifier paren we do not positively prove here -> not an
                    // identifier list (fail-safe: leave to endsInValuePosition).
                    return false;
                }
                depth--;
            } else if (depth == 0) {
                // At top level: does the text up to here end a controlling clause keyword?
                String upToHere = upper.substring(0, i + 1);
                if (endsWithWord(upToHere, "ORDER BY") || endsWithWord(upToHere, "GROUP BY")) {
                    return true;
                }
                // A SELECT list is identifiers only before its FROM. If we reach FROM/WHERE/etc first
                // (scanning back), the comma is not in the SELECT list.
                if (endsWithWord(upToHere, "FROM") || endsWithWord(upToHere, "WHERE")
                        || endsWithWord(upToHere, "VALUES") || endsWithWord(upToHere, "SET")) {
                    return false;
                }
                if (endsWithWord(upToHere, "SELECT")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code text} ends with {@code word} as a whole token (word boundary before it). */
    private static boolean endsWithWord(String text, String word) {
        String t = text.stripTrailing();
        if (!t.endsWith(word)) {
            return false;
        }
        int start = t.length() - word.length();
        if (start == 0) {
            return true;
        }
        char before = t.charAt(start - 1);
        return !(Character.isLetterOrDigit(before) || before == '_');
    }

    /**
     * Whether the lexical state at the end of {@code constantPrefix} is <b>clean</b> for splicing a hole —
     * i.e. scanning {@code constantPrefix} as SQL text leaves us NOT inside an open single-quoted string
     * literal, double-quoted identifier, line comment ({@code --}), or block comment ({@code /*}). A hole
     * spliced while a quote/comment is open is NOT a real value/identifier position: the recovered {@code
     * ?} would land inside the literal/comment, where the emitter's placeholder rewrite skips it — the
     * bind silently desyncs ({@code LIKE '%?'} has 0 placeholders but 1 {@code USING} arg → a runtime
     * "too many parameters" error). The skeleton recognizer therefore demotes any hole whose preceding
     * constant is NOT lexically clean to RAW_FRAGMENT (fail-safe reject), mirroring the existing
     * quoted-string-splice reject. (WS-C Phase 3 Rung 1 deploy fix.)
     *
     * <p>Conservative by construction: MySQL backslash escapes are treated as escaping the next char
     * inside a string (so {@code '\''} stays open as PostgreSQL would also see ambiguous), but the
     * direction is always "in doubt → not clean → reject", never "assume closed → bind".</p>
     */
    public static boolean spliceLexicalStateIsClean(String constantPrefix) {
        if (constantPrefix == null) {
            return false;
        }
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < constantPrefix.length(); i++) {
            char c = constantPrefix.charAt(i);
            char next = i + 1 < constantPrefix.length() ? constantPrefix.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                // A backslash escapes the next char (MySQL); standard SQL doubles the quote ('').
                if (c == '\\' && next != '\0') {
                    i++;
                } else if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '\\' && next != '\0') {
                    i++;
                } else if (c == '"' && next == '"') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            }
        }
        return !(inSingle || inDouble || inLineComment || inBlockComment);
    }

    /**
     * Whether a recovered {@code ?} placeholder immediately followed by {@code followingConstant} would
     * <b>fuse</b> with that text once rewritten to the dialect placeholder. On PostgreSQL {@code ?} →
     * {@code $n}; if the next character is an ASCII digit, {@code $1} + {@code "00"} becomes the literal
     * token {@code $100} (PostgreSQL reads positional parameter #100 — "there is no parameter $100"); if
     * it is an identifier char ({@code A-Za-z_}) or {@code $}, {@code $1x}/{@code $1$2} is a malformed
     * parameter token. The skeleton recognizer demotes a VALUE/PLACEHOLDER hole whose following constant
     * starts with such a fusing char to RAW_FRAGMENT (fail-safe reject) — a value/placeholder run must
     * never silently change which positional parameter it references. (WS-C Phase 3 Rung 1 deploy fix:
     * {@code "... id = " + n + "00"} and {@code "... = " + id + "x"}.)
     */
    public static boolean placeholderWouldFuseWithFollowing(String followingConstant) {
        if (followingConstant == null || followingConstant.isEmpty()) {
            return false;
        }
        char first = followingConstant.charAt(0);
        return Character.isLetterOrDigit(first) || first == '_' || first == '$';
    }
}
