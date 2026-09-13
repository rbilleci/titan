package io.titan.transpiler.jdbc;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import io.titan.transpiler.jdbc.JdbcSqlSkeleton.Constant;
import io.titan.transpiler.jdbc.JdbcSqlSkeleton.Fragment;
import io.titan.transpiler.jdbc.JdbcSqlSkeleton.Hole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Models the <b>Java string construction</b> that builds a non-constant JDBC SQL-text argument into a
 * {@link JdbcSqlSkeleton} (WS-C Phase 3 Rung 1, design contract §3). <b>There is no SQL parser</b> —
 * this walks the Java AST ({@code +}-concatenation including the javac constant-folded all-literal
 * case, {@code String.format}, {@code String.join}, {@code String.repeat}, {@code
 * StringBuilder.append}-chains, and a recognized {@code placeholders(n)}-style helper) and classifies
 * each runtime hole as {@code VALUE}, {@code PLACEHOLDER}, {@code IDENTIFIER}, or {@code RAW_FRAGMENT}.
 * It reuses {@link DynamicInListRecognizer}'s paren-unwrap and {@code +}-recursion spine (via
 * {@link JdbcShapes#unwrap}) and its <b>never-false-accept</b> contract.
 *
 * <p><b>Security invariant (the whole point).</b> A raw value or identifier must NEVER reach the
 * emitted SQL text. The recovered skeleton transpiles (Rung 1) only when every hole is value-bindable
 * ({@link JdbcSqlSkeleton#allHolesValueBindable()}) — VALUE holes become a {@code ?} + a synthesized
 * bind, PLACEHOLDER runs become {@code ?}s bound by the statement's own {@code setXxx} ordinals. When
 * the recognizer cannot <b>prove</b> a hole is a placeholder run or a clean scalar value/identifier
 * splice, it classifies the hole {@code RAW_FRAGMENT} (fail-safe) — which makes the skeleton NOT
 * all-value-bindable, so the caller rejects it (strict) / defers it to Rung 3 (permissive). The
 * adversarial no-false-accept tests in {@code DynamicInListRecognizerTest} lock this.</p>
 *
 * <p><b>Placeholder-run arity must be statically recoverable.</b> Rung 1 reuses the fixed-{@code ?}
 * {@link io.titan.transpiler.tir.RawSql} substrate ({@code IN ($1,$2,…)} with N scalar binds), so a
 * placeholder run is recovered only when its {@code ?}-count is a compile-time constant (a literal
 * {@code placeholders(3)} / {@code "?,".repeat(3)} / {@code String.join(",", nCopies(3, "?"))}). A
 * genuinely runtime-sized collection bind ({@code list.size()} placeholders) is <b>not</b> recoverable
 * here — it falls through to the array-bind tier ({@code = ANY($1)}, design contract Rung 4), never
 * guessed.</p>
 */
public final class JdbcSqlSkeletonRecognizer {

    // A provable placeholder/separator constant: only '?', ',', '(' , ')', whitespace, and the literal
    // "IN" keyword fragment. Mirrors DynamicInListRecognizer.PLACEHOLDER_OR_SEPARATOR_ONLY — no
    // letters/digits that could carry a value or identifier into the text.
    private static final Pattern PLACEHOLDER_OR_SEPARATOR_ONLY =
            Pattern.compile("[?,()\\s]*(?:(?i:in)[?,()\\s]*)*");

    // The String.format conversions Rung 1 models as a single bound hole: %s/%d/%f/%x and their width/
    // precision-flagged forms. A '%%' is a literal '%' (handled in the tokenizer); any other conversion
    // (%n newline, %t date, an explicit argument-index '%1$s', etc.) is NOT modeled -> the whole format
    // is a RAW_FRAGMENT (fail-safe), never a partially-recovered skeleton that could drop a hole.
    private static final Pattern SIMPLE_FORMAT_CONVERSION =
            Pattern.compile("%[-+ 0,(#]*\\d*(?:\\.\\d+)?[sdfeEgGxX]");

    private JdbcSqlSkeletonRecognizer() {
    }

    /**
     * Recovers the skeleton of {@code sqlArg}. Returns {@link Optional#empty()} when {@code sqlArg} is
     * {@code null} or is itself a single string literal (the constant path, handled by {@code
     * constantStringValue}/{@code RawSqlConstantRule}, not here) — so the caller only consults this for
     * genuinely non-constant text. Otherwise always returns a skeleton; the skeleton may contain
     * {@code IDENTIFIER}/{@code RAW_FRAGMENT} holes (the fail-safe classifications), which the caller
     * treats as non-value-bindable and rejects. The skeleton is the faithful model of the construction
     * either way — the accept/reject decision is the caller's, keyed on {@link
     * JdbcSqlSkeleton#allHolesValueBindable()}.
     */
    public static Optional<JdbcSqlSkeleton> recover(ExpressionTree sqlArg) {
        if (sqlArg == null) {
            return Optional.empty();
        }
        ExpressionTree expr = JdbcShapes.unwrap(sqlArg);
        // A bare string literal is the constant path, not a skeleton — let the constant predicate own it.
        if (expr instanceof LiteralTree literal && literal.getValue() instanceof String) {
            return Optional.empty();
        }
        List<Fragment> fragments = new ArrayList<>();
        modelExpression(expr, fragments);
        return Optional.of(new JdbcSqlSkeleton(harden(coalesce(fragments))));
    }

    /**
     * Post-pass hardening of the recovered fragments against placeholder/value desyncs that only become
     * visible once the WHOLE construction (the constant immediately <i>after</i> each hole) is known —
     * the left-to-right classification cannot see them. Demotes a value-bindable hole (VALUE or
     * PLACEHOLDER) to RAW_FRAGMENT (fail-safe reject) when its trailing recovered {@code ?} would
     * <b>fuse</b> with the following constant's first character once rewritten to {@code $n} ({@code
     * "... id = " + n + "00"} → {@code $100}; {@code "... = " + id + "x"} → {@code $1x}). A demoted hole
     * makes the skeleton not-all-value-bindable, so the caller rejects (strict) / defers (permissive)
     * instead of emitting SQL whose {@code USING} arity no longer matches its placeholders. WS-C Phase 3
     * Rung 1 deploy fix; the security posture is unchanged (this only ever turns an accept into a reject).
     */
    private static List<Fragment> harden(List<Fragment> fragments) {
        List<Fragment> result = new ArrayList<>(fragments);
        for (int i = 0; i < result.size(); i++) {
            if (!(result.get(i) instanceof Hole hole)) {
                continue;
            }
            if (hole.kind() != JdbcSqlSkeleton.HoleKind.VALUE
                    && hole.kind() != JdbcSqlSkeleton.HoleKind.PLACEHOLDER) {
                continue;
            }
            // The hole's recovered text ends in a '?' (a VALUE hole always does; a PLACEHOLDER run only
            // when its last char is '?'); only then can its trailing '?' fuse with what follows.
            String placeholderText = hole.placeholderText();
            if (placeholderText.isEmpty() || placeholderText.charAt(placeholderText.length() - 1) != '?') {
                continue;
            }
            // The character immediately following this hole in the FULL recovered text — the next
            // fragment's first recovered char (a Constant's text OR an adjacent hole's placeholder run),
            // so two adjacent runs ('?' + '?') are caught as well, not only a hole-then-constant edge.
            char following = firstRecoveredCharAfter(result, i);
            // Fuses if the follower is a digit/identifier char/$ (e.g. '$1' + "00" -> "$100", + "x" ->
            // "$1x") OR another '?' (e.g. "?,?" + "?,?" -> "?,??,?" -> "$2$3"): both desync the binds.
            if (JdbcShapes.placeholderWouldFuseWithFollowing(String.valueOf(following)) || following == '?') {
                result.set(i, Hole.rawFragment(hole.bindExpr()));
            }
        }
        return result;
    }

    /** The first character of the recovered text after fragment {@code i}, or {@code '\0'} if none. */
    private static char firstRecoveredCharAfter(List<Fragment> result, int i) {
        for (int j = i + 1; j < result.size(); j++) {
            String text = result.get(j) instanceof Constant constant ? constant.text()
                    : result.get(j) instanceof Hole hole ? hole.placeholderText() : "";
            if (!text.isEmpty()) {
                return text.charAt(0);
            }
        }
        return '\0';
    }

    /**
     * Appends the fragments of {@code expr} into {@code out}. The dispatch over the construction shapes;
     * an unrecognized runtime operand becomes a VALUE/IDENTIFIER/RAW_FRAGMENT hole by edge-checking the
     * preceding constant — defaulting to {@code RAW_FRAGMENT} (fail-safe) when the position is not
     * provably a value or identifier position.
     */
    private static void modelExpression(ExpressionTree expr, List<Fragment> out) {
        ExpressionTree current = JdbcShapes.unwrap(expr);

        // (1) String literal -> constant text verbatim (its own '?'/letters are part of the constant).
        String literalText = JdbcShapes.stringLiteralValue(current);
        if (literalText != null) {
            out.add(new Constant(literalText));
            return;
        }

        // (2) '+' concatenation: model each operand left-to-right (the DynamicInListRecognizer '+' spine).
        if (current instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            modelExpression(binary.getLeftOperand(), out);
            modelExpression(binary.getRightOperand(), out);
            return;
        }

        // (3) Method invocations: String.format / String.join / <str>.repeat / StringBuilder chains /
        //     a recognized placeholders(n) helper. Returns true when one matched and appended fragments.
        if (current instanceof MethodInvocationTree invocation && modelInvocation(invocation, out)) {
            return;
        }

        // (4) Anything else is a runtime operand: classify by the preceding constant's edge.
        out.add(classifyBoundArgument(current, trailingConstantText(out)));
    }

    /**
     * Models a method-invocation operand. Returns {@code true} when it was <b>handled</b> as a known
     * string-construction shape (and appended its fragments — possibly a fail-safe RAW_FRAGMENT when the
     * shape was recognized but could not be proven value-bindable); returns {@code false} only for a
     * call that is NOT a string-construction shape (an ordinary scalar accessor such as {@code
     * obj.getId()}), which the caller then edge-classifies as a scalar VALUE/IDENTIFIER hole.
     *
     * <p><b>Security note.</b> Any recognized string-builder method — {@code String.format}, {@code
     * String.join}, {@code <str>.repeat}, a {@code StringBuilder}-chain {@code .toString()}, or a
     * recognized {@code placeholders(n)} helper — that does not prove out to a clean value-bindable
     * skeleton is appended as a RAW_FRAGMENT (return {@code true}), NEVER allowed to fall through to the
     * scalar-value edge check. Otherwise a runtime {@code String.join(",", values)} spliced after {@code
     * (} would be mis-classified as a scalar VALUE — an injection false-accept.</p>
     */
    private static boolean modelInvocation(MethodInvocationTree invocation, List<Fragment> out) {
        String method = invocationName(invocation);
        if (method == null) {
            return false;
        }
        ExpressionTree receiver = staticReceiverOrNull(invocation);
        List<? extends ExpressionTree> args = invocation.getArguments();

        // <constant-placeholder-string>.repeat(<constant int>) -> a PLACEHOLDER run of known arity:
        // "?,".repeat(3) -> the placeholder text "?,?,?," reproduced byte-for-byte. A repeat over a
        // non-placeholder receiver / non-literal count is a string construction we cannot prove ->
        // RAW_FRAGMENT (fail-safe), never a scalar value.
        if (method.equals("repeat") && receiver != null && args.size() == 1) {
            String receiverText = JdbcShapes.stringLiteralValue(receiver);
            Integer count = JdbcShapes.constantIntValue(args.getFirst());
            if (receiverText != null && count != null && count >= 0
                    && PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(receiverText).matches()) {
                out.add(placeholderRunOrRawFragment(receiverText.repeat(count), invocation));
            } else {
                out.add(Hole.rawFragment(invocation));
            }
            return true;
        }

        // String.format / String.join: a String static string-construction. Unprovable -> RAW_FRAGMENT.
        if (receiver instanceof IdentifierTree ident && ident.getName().contentEquals("String")) {
            if (method.equals("format")) {
                if (!modelStringFormat(args, out)) {
                    out.add(Hole.rawFragment(invocation));
                }
                return true;
            }
            if (method.equals("join")) {
                if (!modelStringJoin(args, out)) {
                    out.add(Hole.rawFragment(invocation));
                }
                return true;
            }
        }

        // StringBuilder.append-chain terminated by .toString(): unwind to the equivalent '+' sequence.
        // A .toString() that is NOT a provable `new StringBuilder()...` chain (e.g. an opaque
        // StringBuilder variable, or a scalar's toString) is treated as a string construction we cannot
        // prove -> RAW_FRAGMENT (fail-safe). This deliberately also rejects a scalar `id.toString()`
        // splice: without resolved types we cannot prove the receiver is a scalar, so we never bind it
        // as a value (the developer can write `+ id` for the value-splice, which IS recovered).
        if (method.equals("toString") && receiver != null && args.isEmpty()) {
            List<ExpressionTree> pieces = new ArrayList<>();
            if (unwindStringBuilderChain(receiver, pieces)) {
                for (ExpressionTree piece : pieces) {
                    modelExpression(piece, out);
                }
            } else {
                out.add(Hole.rawFragment(invocation));
            }
            return true;
        }

        // A recognized placeholders(n)/qmarks(n)-style helper (qualified or unqualified) returning a
        // '?'-run with compile-time-constant arity. Name-based (the helper body is not visible here);
        // arity must be a literal so the fixed-'?' substrate has the right count. A non-literal arity ->
        // RAW_FRAGMENT (fail-safe): a runtime-sized list bind is the array-bind tier (Rung 4), not Rung 1.
        if (isRecognizedPlaceholderHelper(method) && args.size() == 1) {
            Integer count = JdbcShapes.constantIntValue(args.getFirst());
            if (count != null && count >= 0) {
                out.add(placeholderRunOrRawFragment(commaSeparatedQuestionMarks(count), invocation));
            } else {
                out.add(Hole.rawFragment(invocation));
            }
            return true;
        }

        // Not a string-construction shape: let the caller edge-classify it as a scalar value/identifier
        // (e.g. `"WHERE id = " + obj.getId()` -> a VALUE hole bound via a synthesized __titan_pN).
        return false;
    }

    /** The simple method name of an invocation, for both {@code recv.m(...)} and unqualified {@code m(...)}. */
    private static String invocationName(MethodInvocationTree invocation) {
        ExpressionTree select = invocation.getMethodSelect();
        if (select instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier().toString();
        }
        if (select instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        return null;
    }

    /** The receiver of a {@code recv.m(...)} call, or {@code null} for an unqualified {@code m(...)} call. */
    private static ExpressionTree staticReceiverOrNull(MethodInvocationTree invocation) {
        return invocation.getMethodSelect() instanceof MemberSelectTree memberSelect
                ? memberSelect.getExpression() : null;
    }

    /**
     * Models {@code String.format(fmt, args...)}. Recovers a skeleton only when {@code fmt} is a
     * constant string whose conversions are all the simple value conversions ({@code %s}/{@code %d}/…);
     * each conversion becomes a hole bound to its argument (edge-checked VALUE vs IDENTIFIER from the
     * literal text immediately before it). A {@code %%} is a literal {@code %}. Any unmodeled conversion,
     * a non-constant {@code fmt}, or an arg-count mismatch makes the <b>whole</b> format a single
     * RAW_FRAGMENT (fail-safe) — never a partially-recovered skeleton that could drop a hole.
     */
    private static boolean modelStringFormat(List<? extends ExpressionTree> args, List<Fragment> out) {
        if (args.isEmpty()) {
            return false;
        }
        String fmt = JdbcShapes.stringLiteralValue(args.getFirst());
        if (fmt == null) {
            return false; // non-constant format string -> runtime-operand classification (RAW_FRAGMENT).
        }
        List<? extends ExpressionTree> formatArgs = args.subList(1, args.size());

        List<Fragment> recovered = new ArrayList<>();
        // Seed the edge-classification context with the constant already emitted before this format (a
        // preceding '+' run, e.g. "WHERE id = " + String.format("%d", id)), so the FIRST conversion's
        // argument is edge-classified against the real preceding text, not an empty prefix. This affects
        // only the value/identifier edge check; the preceding constant is already in `out`, so it is NOT
        // re-emitted here.
        String precedingContext = trailingConstantText(out);
        StringBuilder constant = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c != '%') {
                constant.append(c);
                i++;
                continue;
            }
            if (i + 1 < fmt.length() && fmt.charAt(i + 1) == '%') {
                constant.append('%');
                i += 2;
                continue;
            }
            Matcher matcher = SIMPLE_FORMAT_CONVERSION.matcher(fmt);
            if (!matcher.find(i) || matcher.start() != i) {
                return false; // an unmodeled conversion (%n, %t…, positional %1$s) -> whole RAW_FRAGMENT.
            }
            if (argIndex >= formatArgs.size()) {
                return false; // more conversions than args -> not faithfully modelable.
            }
            // Flush the constant before this conversion, then classify the conversion's argument by the
            // edge of the text immediately before it (the preceding '+' context plus this run's
            // constant). The conversion itself becomes one bound hole.
            String edge = precedingContext + constant;
            recovered.add(new Constant(constant.toString()));
            recovered.add(classifyBoundArgument(formatArgs.get(argIndex), edge));
            constant.setLength(0);
            precedingContext = ""; // only the first conversion needs the pre-format '+' context.
            argIndex++;
            i = matcher.end();
        }
        if (argIndex != formatArgs.size()) {
            return false; // fewer conversions than args -> a dropped argument; not faithfully modelable.
        }
        recovered.add(new Constant(constant.toString()));
        out.addAll(recovered);
        return true;
    }

    /**
     * Models {@code String.join(sep, elements)} — provable only when it produces a {@code ?}-run: a
     * placeholder/separator-only constant separator AND elements that are provably all {@code "?"} (a
     * {@code Collections.nCopies(n, "?")} with a literal {@code n}, or a varargs of {@code "?"}
     * literals). <b>No-false-accept:</b> {@code String.join(",", someRuntimeStringList)} joins runtime
     * <i>values</i> into the text — there is no way to prove those are placeholders — so it is NOT
     * recognized here (returns {@code false} → RAW_FRAGMENT via runtime-operand classification).
     */
    private static boolean modelStringJoin(List<? extends ExpressionTree> args, List<Fragment> out) {
        if (args.size() < 2) {
            return false;
        }
        String separator = JdbcShapes.stringLiteralValue(args.getFirst());
        if (separator == null || !PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(separator).matches()) {
            return false;
        }
        // join(sep, Collections.nCopies(N, "?")) with literal N and a "?" element.
        if (args.size() == 2 && JdbcShapes.unwrap(args.get(1)) instanceof MethodInvocationTree nCopies) {
            Integer count = nCopiesPlaceholderCount(nCopies);
            if (count == null) {
                return false;
            }
            out.add(placeholderRunOrRawFragment(joinPlaceholders(separator, count),
                    JdbcShapes.unwrap(args.get(1))));
            return true;
        }
        // join(sep, "?", "?", ...) varargs of "?" literals -> a known-arity placeholder run.
        int count = 0;
        for (ExpressionTree element : args.subList(1, args.size())) {
            if (!"?".equals(JdbcShapes.stringLiteralValue(element))) {
                return false;
            }
            count++;
        }
        out.add(placeholderRunOrRawFragment(joinPlaceholders(separator, count), args.getFirst()));
        return true;
    }

    /** {@code Collections.nCopies(<literal int>, "?")} -> the int, or {@code null} if not that shape. */
    private static Integer nCopiesPlaceholderCount(MethodInvocationTree invocation) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("nCopies")
                || invocation.getArguments().size() != 2) {
            return null;
        }
        Integer count = JdbcShapes.constantIntValue(invocation.getArguments().get(0));
        String element = JdbcShapes.stringLiteralValue(invocation.getArguments().get(1));
        if (count != null && count >= 0 && "?".equals(element)) {
            return count;
        }
        return null;
    }

    /**
     * Unwinds a {@code new StringBuilder().append(a).append(b)...} chain (optionally with an initial
     * {@code new StringBuilder("seed")}) into the ordered list of seed/appended expressions, so it is
     * modelled exactly like the equivalent {@code seed + a + b + …} concatenation. Returns {@code true}
     * when the receiver chain bottoms out at a {@code new StringBuilder(...)} (the provable shape);
     * {@code false} otherwise (an opaque {@code StringBuilder} variable whose prior appends are not
     * visible here — fail-safe, treated as a runtime operand / RAW_FRAGMENT).
     */
    private static boolean unwindStringBuilderChain(ExpressionTree receiver, List<ExpressionTree> pieces) {
        ExpressionTree current = JdbcShapes.unwrap(receiver);
        List<ExpressionTree> reversedAppends = new ArrayList<>();
        while (current instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("append")
                && invocation.getArguments().size() == 1) {
            reversedAppends.add(invocation.getArguments().getFirst());
            current = JdbcShapes.unwrap(select.getExpression());
        }
        if (!(current instanceof NewClassTree newClass) || !isStringBuilderType(newClass)) {
            return false;
        }
        // A single String constructor argument is the seed text (first piece); an int-capacity argument
        // is not text (ignored). Other constructor shapes are not modeled.
        if (newClass.getArguments().size() == 1) {
            ExpressionTree seedArg = newClass.getArguments().getFirst();
            if (JdbcShapes.stringLiteralValue(seedArg) != null
                    || JdbcShapes.constantIntValue(seedArg) == null) {
                // a String seed (or any non-int-literal seed) is a text piece; an int capacity is skipped.
                pieces.add(seedArg);
            }
        }
        for (int i = reversedAppends.size() - 1; i >= 0; i--) {
            pieces.add(reversedAppends.get(i));
        }
        return true;
    }

    private static boolean isStringBuilderType(NewClassTree newClass) {
        String identifier = newClass.getIdentifier().toString();
        return identifier.equals("StringBuilder") || identifier.endsWith(".StringBuilder")
                || identifier.equals("StringBuffer") || identifier.endsWith(".StringBuffer");
    }

    /**
     * Classifies a bound argument/runtime operand into a hole, using the constant text accumulated
     * before it. A clear value position ({@code "… = " + x}) is a VALUE hole (design contract D3); a
     * clear identifier position ({@code "FROM " + t}) is an IDENTIFIER hole (D2); anything else is the
     * FAIL-SAFE RAW_FRAGMENT — the recognizer never assumes a bound value by default.
     */
    private static Hole classifyBoundArgument(ExpressionTree bindExpr, String precedingConstant) {
        // A splice while a string literal / quoted identifier / comment is still open is NOT a real
        // value/identifier position — the recovered '?' would land inside the literal/comment and the
        // emitter's placeholder rewrite would skip it (silently desyncing the bind). Fail-safe reject.
        // (Mirrors the quoted-string-splice reject; covers "LIKE '%" + x, "-- " + x, "/* " + x.)
        if (!JdbcShapes.spliceLexicalStateIsClean(precedingConstant)) {
            return Hole.rawFragment(bindExpr);
        }
        // WS-C Phase 3 Rung 3 audit fix (Findings 1): a sort/grouping clause (ORDER BY / GROUP BY / … BY,
        // and its comma-continued elements) is a sort EXPRESSION, not a single bare identifier — the runtime
        // value may be "col DESC" / "col NULLS LAST" / a comma list, none of which %I/backtick can quote as
        // one identifier (it would corrupt them into "column \"col DESC\" does not exist"). Classify it
        // RAW_FRAGMENT so PERMISSIVE splices it VERBATIM (faithful to the plain-JDBC "ORDER BY " + orderBy
        // it migrates); STRICT still rejects (RAW_FRAGMENT is structural text). Checked BEFORE the IDENTIFIER
        // and identifier-list branches so the sort position never commits to a corrupting %I. (Table/relation
        // name positions are NOT sort clauses, so they fall through to IDENTIFIER below, runtime-quoted.)
        if (JdbcShapes.endsInSortClausePosition(precedingConstant)
                || JdbcShapes.endsInSortListPosition(precedingConstant)) {
            return Hole.rawFragment(bindExpr);
        }
        // A clause keyword that introduces an identifier (FROM/JOIN/UPDATE/TABLE/INTO) -> IDENTIFIER (a
        // table/relation name; runtime-quoted, qualified-name aware so "schema.table" is quoted per segment).
        if (JdbcShapes.endsInIdentifierPosition(precedingConstant)) {
            return Hole.identifier(bindExpr);
        }
        // A comma/open-paren CONTINUING an identifier list (SELECT a, / INSERT INTO t () must be checked
        // BEFORE the bare ','/'(' value heuristic, else an identifier splice after a comma would be silently
        // swallowed as a bound value (design contract D2 break). The ORDER BY / GROUP BY comma-list elements
        // were already taken as RAW_FRAGMENT above; what remains here is the SELECT/INSERT column list.
        if (JdbcShapes.endsInIdentifierListPosition(precedingConstant)) {
            return Hole.identifier(bindExpr);
        }
        if (JdbcShapes.endsInValuePosition(precedingConstant)) {
            return Hole.value(bindExpr);
        }
        return Hole.rawFragment(bindExpr); // not provably a value or identifier position -> fail safe.
    }

    /** The concatenated text of the trailing run of {@link Constant} fragments in {@code out}. */
    private static String trailingConstantText(List<Fragment> out) {
        StringBuilder sb = new StringBuilder();
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i) instanceof Constant constant) {
                sb.insert(0, constant.text());
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static boolean isRecognizedPlaceholderHelper(String method) {
        String lower = method.toLowerCase(Locale.ROOT);
        return lower.equals("placeholders") || lower.equals("placeholder")
                || lower.equals("qmarks") || lower.equals("questionmarks")
                || lower.equals("bindplaceholders") || lower.equals("repeatplaceholders")
                || lower.equals("inplaceholders") || lower.equals("makeplaceholders")
                || lower.equals("sqlplaceholders");
    }

    /**
     * Wraps a recovered placeholder-run text into a {@code PLACEHOLDER} hole, UNLESS the run is empty
     * (zero {@code ?}). A zero-length run ({@code placeholders(0)}, {@code "?,".repeat(0)}, {@code
     * join(nCopies(0,"?"))}) would render an {@code IN ()} — a syntax error on PG16 and MySQL8.4 with no
     * valid fixed-arity lowering — so it is the FAIL-SAFE RAW_FRAGMENT instead (mirrors {@code
     * DynamicInListRecognizer}'s {@code placeholderCount > 0} floor). WS-C Phase 3 Rung 1 deploy fix.
     */
    private static Hole placeholderRunOrRawFragment(String placeholderText, ExpressionTree source) {
        if (placeholderText.indexOf('?') < 0) {
            return Hole.rawFragment(source);
        }
        return Hole.placeholders(placeholderText);
    }

    /** {@code "?,?,…,?"} with {@code count} placeholders separated by commas (the IN-list run). */
    private static String commaSeparatedQuestionMarks(int count) {
        return joinPlaceholders(",", count);
    }

    /** {@code count} {@code "?"} joined by {@code separator} (an empty run for {@code count <= 0}). */
    private static String joinPlaceholders(String separator, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < count; n++) {
            if (n > 0) {
                sb.append(separator);
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * Coalesces adjacent {@link Constant} fragments into one, so the skeleton's constant runs are
     * maximal (cleaner edge-checks and recovered text). Holes are preserved in order. Empty constants
     * are dropped.
     */
    private static List<Fragment> coalesce(List<Fragment> fragments) {
        List<Fragment> result = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (Fragment fragment : fragments) {
            if (fragment instanceof Constant constant) {
                pending.append(constant.text());
            } else {
                flushPending(pending, result);
                result.add(fragment);
            }
        }
        flushPending(pending, result);
        return result;
    }

    private static void flushPending(StringBuilder pending, List<Fragment> result) {
        if (pending.length() > 0) {
            result.add(new Constant(pending.toString()));
            pending.setLength(0);
        }
    }
}
