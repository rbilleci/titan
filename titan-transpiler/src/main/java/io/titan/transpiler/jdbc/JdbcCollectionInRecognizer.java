package io.titan.transpiler.jdbc;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.ParsedSources;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeMirror;

/**
 * Recognizer + <b>use proof</b> for §3.6 form (2) — <i>collection binding</i> (WS-C Phase 3 Rung 4,
 * design contract §4/§7 Rung 4). It identifies the idiom where a whole runtime collection drives a
 * later query's {@code IN}-list and binds the <b>entire collection as ONE parameter</b> (a native
 * array on PostgreSQL, a JSON-array string on MySQL) instead of a per-element {@code ?} run:
 *
 * <pre>{@code
 *   List<Long> ids;   // a method param, or a local not consumed beyond the IN
 *   PreparedStatement ps = c.prepareStatement("... WHERE customer_id IN (" + placeholders(ids.size()) + ")");
 *   for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));   // FORM A — per-element bind loop
 * }</pre>
 *
 * <p>and the explicit single-array idiom:</p>
 *
 * <pre>{@code
 *   PreparedStatement ps = c.prepareStatement("... WHERE col IN (?)");
 *   ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));            // FORM B — setArray over a collection
 * }</pre>
 *
 * <p>Both lower to the same single bind — PostgreSQL {@code WHERE customer_id = ANY($1)} (one
 * {@code bigint[]}/{@code int[]}/{@code text[]} parameter) / MySQL {@code WHERE customer_id IN (SELECT
 * v FROM JSON_TABLE(?, '$[*]' COLUMNS (v <sqlType> PATH '$')) t)} (one JSON-array string parameter). One
 * placeholder, one bind, no per-element splicing. <b>STRICT-safe:</b> values are bound, never text.</p>
 *
 * <h2>The use proof — fail-safe, never array-bind in doubt</h2>
 * Array-binding is sound only when the whole collection drives <b>exactly</b> the one {@code IN}, and
 * the run is sized by <i>that collection's</i> {@code size()} (FORM A) or is a single literal {@code ?}
 * (FORM B). {@link #recognize} refuses (returns {@link java.util.Optional#empty()}) the moment any of
 * these holds — and the lowerer then keeps the existing Rung-1/gate behavior:
 * <ul>
 *   <li>the {@code IN}-run is not cleanly one collection's per-element binds — mixed binds (a {@code ?}
 *       bound by something other than {@code coll.get(i)}), a partial run (fewer/more set calls than
 *       the run length), a non-{@code .size()} sizing, or a {@code ?} bound from a <i>different</i>
 *       collection;</li>
 *   <li>the collection is used anywhere else in the method in a <b>value position</b> (a second {@code
 *       IN}, a {@code get}/{@code size}/iteration/{@code stream}/{@code isEmpty} outside the proven run,
 *       passing it to a method, storing/returning it) — exactly the never-false-accept posture of
 *       {@link DynamicInListRecognizer} and {@link JdbcSubqueryFusion};</li>
 *   <li>the element type is not a clean scalar ({@code List<Long>}→{@code bigint}, {@code
 *       List<Integer>}→{@code int}, {@code List<String>}→{@code text}); a {@code List<SomeRecord>} or a
 *       raw {@code List} is refused.</li>
 * </ul>
 *
 * <h2>Empty / NULL equivalence (semantic-preservation)</h2>
 * An empty collection binds an empty array {@code '{}'} → {@code = ANY('{}')} matches nothing, and
 * {@code JSON_TABLE('[]', …)} yields no rows → matches nothing. This is the correct intent of an
 * empty membership and a <b>strict improvement</b> over the per-element original: a per-element run
 * over an empty list emits {@code IN ()}, which is a SQL <i>syntax error</i> on both PostgreSQL and
 * MySQL (so the original Java would throw at runtime), whereas the array bind cleanly matches nothing.
 * A NULL element behaves identically under {@code = ANY(array)} / {@code JSON_TABLE} as under a bound
 * {@code IN}-list (a NULL never matches via {@code =}). Duplicates and element-type fidelity ({@code
 * Long}→{@code bigint}, exact, no lossy cast) are preserved because the values are bound, not re-parsed
 * from text.
 */
public final class JdbcCollectionInRecognizer {

    private final ParsedSources parsed;
    private final JdbcShapes shapes;
    private final JdbcTypeOracle oracle;

    public JdbcCollectionInRecognizer(ParsedSources parsed, JdbcShapes shapes, JdbcTypeOracle oracle) {
        if (parsed == null || shapes == null || oracle == null) {
            throw new IllegalArgumentException("parsed/shapes/oracle must not be null");
        }
        this.parsed = parsed;
        this.shapes = shapes;
        this.oracle = oracle;
    }

    /** The scalar element type a {@code List<E>} array-binds to (design contract §1/§4). */
    public enum ElementType {
        /** {@code List<Long>} → PostgreSQL {@code bigint[]} / MySQL {@code COLUMNS (v BIGINT …)}. */
        BIGINT,
        /** {@code List<Integer>} → PostgreSQL {@code int[]} / MySQL {@code COLUMNS (v INT …)}. */
        INT,
        /** {@code List<String>} → PostgreSQL {@code text[]} / MySQL {@code COLUMNS (v LONGTEXT …)}. */
        TEXT,
        /** {@code List<UUID>} → PostgreSQL {@code uuid[]} / MySQL {@code COLUMNS (v CHAR(36) …)}. */
        UUID
    }

    /**
     * A proven collection-IN bind plan. The lowerer:
     * <ul>
     *   <li>replaces statement B (at {@link #consumingStatementIndex}) with a {@code RawSql} whose text is
     *       {@link #sqlPrefix} {@code + <membership marker> +} {@link #sqlSuffix} — the {@code <lhs> IN
     *       (run)} is gone, the marker carries the membership the emitter renders natively;</li>
     *   <li>binds the whole {@link #collection} as one array/JSON parameter (its routine-local name);</li>
     *   <li>elides the binding statement(s) ({@link #elidedIndices} — FORM A's per-element {@code for}
     *       loop, FORM B's {@code setArray}) and ignores B's own per-element {@code setXxx} ordinals.</li>
     * </ul>
     * {@link #lhs} is the membership left-hand side (the column the list is matched against). {@link
     * #bindsBeforeRun}/{@link #bindsAfterRun} are B's surviving {@code setXxx(ordinal, value)} binds
     * <i>outside</i> the run, split into before-run / after-run for text-order assembly.
     */
    public record CollectionInPlan(
            int consumingStatementIndex,
            StatementTree consumingStatement,
            Element collection,
            String collectionName,
            ElementType elementType,
            String lhs,
            String sqlPrefix,
            String sqlSuffix,
            List<ExpressionTree> bindsBeforeRun,
            List<ExpressionTree> bindsAfterRun,
            List<Integer> elidedIndices,
            List<StatementTree> elidedStatements,
            ExpressionTree runOperand) {

        public CollectionInPlan {
            bindsBeforeRun = List.copyOf(bindsBeforeRun);
            bindsAfterRun = List.copyOf(bindsAfterRun);
            elidedIndices = List.copyOf(elidedIndices);
            elidedStatements = List.copyOf(elidedStatements);
        }

        /**
         * The Java AST nodes a proven collection-IN bind <b>subsumes</b> — every node inside an elided
         * binding statement (FORM A's {@code for} loop incl. its {@code coll.size()}/{@code coll.get(i)},
         * FORM B's {@code setArray}/{@code createArrayOf}/{@code coll.toArray()}) plus B's whole
         * placeholder-run operand ({@code placeholders(coll.size())} / {@code "?,".repeat(coll.size())} /
         * the {@code String.join(",", Collections.nCopies(coll.size(), "?"))} run, including its {@code
         * coll.size()}). The {@code FeatureValidator} consults this set (by tree identity) to exempt
         * exactly the {@code coll.size()}/{@code coll.get}/iterator binds and the list construction the
         * array-bind removes — never any other collection op, so the safety boundary is precise.
         */
        public java.util.Set<Tree> subsumedTrees() {
            java.util.Set<Tree> subsumed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            com.sun.source.util.TreeScanner<Void, Void> collector = new com.sun.source.util.TreeScanner<>() {
                @Override
                public Void scan(Tree node, Void unused) {
                    if (node != null) {
                        subsumed.add(node);
                    }
                    return super.scan(node, unused);
                }
            };
            for (StatementTree statement : elidedStatements) {
                collector.scan(statement, null);
            }
            if (runOperand != null) {
                collector.scan(runOperand, null);
            }
            return subsumed;
        }
    }

    /**
     * Attempts to recognize the first collection-IN bind in {@code body} (a block's statements). Returns
     * the proven {@link CollectionInPlan}, or empty when no collection-IN is present or the use proof
     * refuses (fail-safe). {@code blockPath} is the block's path (for type/element resolution); {@code
     * methodBodyPath} is the whole method body, over which the collection's use-and-escape proof scans.
     */
    public java.util.Optional<CollectionInPlan> recognize(
            List<? extends StatementTree> body, TreePath blockPath, TreePath methodBodyPath) {
        if (methodBodyPath == null) {
            return java.util.Optional.empty();
        }
        for (int i = 0; i < body.size(); i++) {
            java.util.Optional<CollectionInPlan> plan = tryRecognizeAt(body, i, blockPath, methodBodyPath);
            if (plan.isPresent()) {
                return plan;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Recognizes <b>every</b> collection-IN bind reachable in {@code methodBody} — scanning each nested
     * block with the whole method body as the use-proof scope (the same scope the lowerer recognizes each
     * block under). Used by the {@code FeatureValidator} to exempt exactly the collection constructs every
     * proven array-bind subsumes (and the array-bound {@code List<E>} parameters), method-wide. Empty when
     * no collection-IN is provable.
     */
    public List<CollectionInPlan> recognizeAll(
            com.sun.source.tree.BlockTree methodBody, TreePath methodBodyPath) {
        if (methodBody == null || methodBodyPath == null) {
            return List.of();
        }
        List<CollectionInPlan> plans = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitBlock(com.sun.source.tree.BlockTree node, Void unused) {
                recognize(node.getStatements(), getCurrentPath(), methodBodyPath).ifPresent(plans::add);
                return super.visitBlock(node, unused);
            }
        }.scan(methodBodyPath, null);
        return plans;
    }

    private java.util.Optional<CollectionInPlan> tryRecognizeAt(
            List<? extends StatementTree> body, int index, TreePath blockPath, TreePath methodBodyPath) {
        StatementTree statement = body.get(index);
        if (!(statement instanceof VariableTree variable)) {
            return java.util.Optional.empty();
        }
        TreePath variablePath = new TreePath(blockPath, variable);
        TypeMirror type = parsed.trees().getTypeMirror(variablePath);
        if (type == null || !oracle.isPreparedStatement(type)) {
            return java.util.Optional.empty();
        }
        ExpressionTree initializer = JdbcShapes.unwrap(variable.getInitializer());
        if (!(initializer instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("prepareStatement")
                || invocation.getArguments().isEmpty()) {
            return java.util.Optional.empty();
        }
        Element statementHandle = parsed.trees().getElement(variablePath);
        if (statementHandle == null) {
            return java.util.Optional.empty();
        }

        // Split B's SQL into <prefix ending "<lhs> IN ("> + <single-collection run> + <suffix ")…">.
        // FORM A: the run is a coll.size()-sized placeholder run. FORM B: the run is a single literal `?`.
        InRunMatch match = matchInRun(invocation.getArguments().getFirst(), variablePath);
        if (match == null) {
            return java.util.Optional.empty();
        }

        // The per-element binding statement, and the bound collection. FORM A: the collection is already
        // resolved from the run's coll.size() (match.collection()); the loop must bind each ? from it.
        // FORM B: the collection comes from the setArray's coll.toArray(). The binding statement is elided.
        BindingMatch binding =
                match.form() == Form.SIZE_RUN
                        ? matchPerElementLoop(body, index, statementHandle, match, blockPath)
                        : matchSetArray(body, index, statementHandle, match, blockPath);
        if (binding == null) {
            return java.util.Optional.empty();
        }
        Element collection = match.collection() != null ? match.collection() : binding.collection();

        // The element type of coll (List<Long>/Integer/String → bigint/int/text). A non-scalar element
        // (record, raw List) is not array-bindable -> refuse.
        ElementType elementType = elementTypeOf(collection);
        if (elementType == null) {
            return java.util.Optional.empty();
        }

        // B's surviving setXxx binds outside the run (ordinals < run-start before, >= run-start after).
        java.util.SortedMap<Integer, ExpressionTree> otherBinds =
                collectOtherSetBinds(body, index, statementHandle, match.runOrdinalStart(), binding, blockPath);

        // A literal setXxx ordinal AT OR AFTER the run start (other than the elided binding statement)
        // cannot be statically correct: the run occupies a RUNTIME number of ordinals, so any `?` after it
        // shifts by the collection's runtime size. Refuse rather than mis-bind — the supported shapes have
        // the collection IN-run as the LAST placeholder group (binds only BEFORE it). FORM B (single `?`)
        // is itself the run, so it has no after-run binds either.
        if (otherBinds.keySet().stream().anyMatch(ordinal -> ordinal >= match.runOrdinalStart())) {
            return java.util.Optional.empty();
        }

        // PLACEHOLDER/PARAMETER ALIGNMENT PROOF (the heart of "array-bind ONLY a clean single-list IN").
        // After expansion the emitted text carries exactly: <prefix `?`s> + <the one marker `?`> +
        // <suffix `?`s>, and the bound params are <before-run binds> + <the collection> + <after-run
        // binds>. For the marker (array/JSON param) and every scalar `?` to line up with the right
        // parameter, every prefix `?` MUST be accounted for by a before-run setXxx and NO `?` may survive
        // in the suffix:
        //   • PREFIX — the run starts at ordinal `runStart`, so the prefix holds exactly `runStart - 1`
        //     `?`s (runStart was derived as countQuestionMarks(prefix)+1). Those must be bound by a
        //     CONTIGUOUS block of literal ordinals 1..runStart-1 — one bind per prefix `?`, none missing,
        //     none extra. An unbound/under-bound prefix `?` (a leading `region = ?` never set, or a
        //     setXxx count short of the `?` run) would shift every later `$n` and mis-route the value
        //     meant for one column onto another (and force the array/JSON param onto a scalar column).
        //   • SUFFIX — after the after-run guard above, there can be no accounted-for bind at/after the
        //     run, so any `?` left in the suffix (`) AND q = ?`) is necessarily UNBOUND. Require zero.
        // In either doubt we refuse and fall through to the existing Rung-1/gate behavior — never
        // array-bind a run whose bind/placeholder alignment is not provable.
        if (!placeholdersAlign(match, otherBinds.keySet())) {
            return java.util.Optional.empty();
        }

        // THE PROOF: coll is consumed ONLY as this one IN-source — no other value-position use anywhere in
        // the method. Fail-safe: any unrecognized reference to coll defeats array-binding.
        if (!collectionConsumedOnlyAsInSource(collection, match, binding, blockPath, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        List<ExpressionTree> before = new ArrayList<>();
        List<ExpressionTree> after = new ArrayList<>();
        for (var entry : otherBinds.entrySet()) {
            (entry.getKey() < match.runOrdinalStart() ? before : after).add(entry.getValue());
        }

        List<Integer> elidedIndices = new ArrayList<>(binding.elidedIndices());
        List<StatementTree> elidedStatements = new ArrayList<>();
        for (int elided : elidedIndices) {
            elidedStatements.add(body.get(elided));
        }

        String collectionName = collection.getSimpleName().toString();
        return java.util.Optional.of(new CollectionInPlan(
                index,
                statement,
                collection,
                collectionName,
                elementType,
                match.lhs(),
                match.sqlPrefix(),
                match.sqlSuffix(),
                before,
                after,
                elidedIndices,
                elidedStatements,
                match.runOperand()));
    }

    // ---- B's SQL split: <prefix … lhs IN (> + <run> + <) …> -------------------------------------

    private enum Form { SIZE_RUN, SINGLE_QMARK }

    private record InRunMatch(
            Form form,
            Element collection,
            String lhs,
            String sqlPrefix,
            String sqlSuffix,
            int runOrdinalStart,
            ExpressionTree runOperand,
            ExpressionTree sizeCall) {
    }

    /**
     * Splits B's SQL argument into the constant prefix ending in {@code <lhs> IN (}, the
     * single-collection run, and the constant suffix starting with {@code )}. Returns {@code null} unless
     * exactly one of the two recognized run forms is present and the constant prefix/suffix are clean:
     * FORM A — exactly one operand is a {@code coll.size()}-sized placeholder run (every other operand
     * constant); FORM B — the SQL is fully constant with a single {@code IN (?)} run.
     */
    private InRunMatch matchInRun(ExpressionTree sqlArg, TreePath path) {
        List<ExpressionTree> operands = new ArrayList<>();
        flattenConcatenation(JdbcShapes.unwrap(sqlArg), operands);

        // FORM A: one operand is a coll.size()-sized placeholder run.
        int runOperandIndex = -1;
        Element runCollection = null;
        ExpressionTree sizeCall = null;
        for (int i = 0; i < operands.size(); i++) {
            CollectionSizedRun run = sizedPlaceholderRun(operands.get(i), path);
            if (run != null) {
                if (runOperandIndex >= 0) {
                    return null; // two size()-sized runs -> not the single-IN shape.
                }
                runOperandIndex = i;
                runCollection = run.collection();
                sizeCall = run.sizeCall();
            }
        }
        if (runOperandIndex >= 0) {
            StringBuilder prefix = new StringBuilder();
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < operands.size(); i++) {
                if (i == runOperandIndex) {
                    continue;
                }
                String constant = constantStringValue(operands.get(i), path);
                if (constant == null) {
                    return null; // a value/identifier splice elsewhere -> not a clean run shape.
                }
                (i < runOperandIndex ? prefix : suffix).append(constant);
            }
            // The trailing `<lhs> IN (` must be a real membership opener, NOT the tail of a string literal
            // being assembled (e.g. "… note = 'a IN (" + run + ")'"). When the constant prefix ends inside
            // an unterminated single-quoted literal, the `IN (` is data: array-binding here would splice
            // the marker INTO the literal and leave the bound array param dangling (corrupt SQL, both
            // dialects). Require the prefix to end OUTSIDE any open literal before reading its lhs. (The
            // trailing `IN (` carries no `'`, so the literal state at the prefix's end is the state at `(`.)
            if (!endsOutsideStringLiteral(prefix.toString())) {
                return null;
            }
            String lhs = inListLhs(prefix.toString());
            if (lhs == null || !startsWithCloseParen(suffix.toString())) {
                return null;
            }
            int runStart = countQuestionMarksOutsideStringLiterals(prefix.toString()) + 1;
            return new InRunMatch(Form.SIZE_RUN, runCollection, lhs,
                    stripInOpenParen(prefix.toString(), lhs), suffix.toString(), runStart,
                    operands.get(runOperandIndex), sizeCall);
        }

        // FORM B: a fully-constant SQL with a single `IN (?)` run (the collection comes from setArray).
        String constant = constantStringValue(JdbcShapes.unwrap(sqlArg), path);
        if (constant == null) {
            return null;
        }
        SingleQmarkSplit split = splitSingleQmarkInList(constant);
        if (split == null) {
            return null;
        }
        return new InRunMatch(Form.SINGLE_QMARK, null, split.lhs(),
                split.prefix(), split.suffix(), split.runOrdinal(), null, null);
    }

    private record CollectionSizedRun(Element collection, ExpressionTree sizeCall) {
    }

    /**
     * If {@code operand} is a placeholder run whose arity is {@code coll.size()} over a single collection
     * — the runtime-sized IN-run array-binding replaces — returns that collection and its {@code size()}
     * call; otherwise {@code null}. Recognized run shapes mirror {@link JdbcSubqueryFusion}: {@code
     * placeholders(coll.size())} (a recognized helper), {@code "?,".repeat(coll.size())}, and {@code
     * String.join(sep, Collections.nCopies(coll.size(), "?"))}.
     */
    private CollectionSizedRun sizedPlaceholderRun(ExpressionTree operand, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(operand);
        if (!(expr instanceof MethodInvocationTree invocation)) {
            return null;
        }
        String method = invocationName(invocation);
        if (method == null) {
            return null;
        }
        if (isRecognizedPlaceholderHelper(method) && invocation.getArguments().size() == 1) {
            return collectionSizeCall(invocation.getArguments().getFirst(), path);
        }
        ExpressionTree receiver = invocation.getMethodSelect() instanceof MemberSelectTree select
                ? select.getExpression() : null;
        if (method.equals("repeat") && receiver != null && invocation.getArguments().size() == 1) {
            String receiverText = JdbcShapes.stringLiteralValue(receiver);
            if (receiverText != null
                    && PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(receiverText).matches()
                    && receiverText.indexOf('?') >= 0) {
                return collectionSizeCall(invocation.getArguments().getFirst(), path);
            }
            return null;
        }
        if (method.equals("join") && receiver instanceof IdentifierTree ident
                && ident.getName().contentEquals("String")
                && invocation.getArguments().size() == 2) {
            String separator = JdbcShapes.stringLiteralValue(invocation.getArguments().get(0));
            if (separator == null || !PLACEHOLDER_OR_SEPARATOR_ONLY.matcher(separator).matches()) {
                return null;
            }
            ExpressionTree second = JdbcShapes.unwrap(invocation.getArguments().get(1));
            if (second instanceof MethodInvocationTree nCopies
                    && nCopies.getMethodSelect() instanceof MemberSelectTree nSelect
                    && nSelect.getIdentifier().contentEquals("nCopies")
                    && nCopies.getArguments().size() == 2
                    && "?".equals(JdbcShapes.stringLiteralValue(nCopies.getArguments().get(1)))) {
                return collectionSizeCall(nCopies.getArguments().get(0), path);
            }
        }
        return null;
    }

    /** The collection whose {@code size()} this is (a no-arg {@code coll.size()}), or {@code null}. */
    private CollectionSizedRun collectionSizeCall(ExpressionTree expr, TreePath path) {
        ExpressionTree unwrapped = JdbcShapes.unwrap(expr);
        if (unwrapped instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("size")
                && invocation.getArguments().isEmpty()) {
            Element receiver = shapes.elementOf(select.getExpression(), path);
            if (isCollectionLocalOrParam(receiver)) {
                return new CollectionSizedRun(receiver, unwrapped);
            }
        }
        return null;
    }

    private record SingleQmarkSplit(String lhs, String prefix, String suffix, int runOrdinal) {
    }

    /**
     * Splits a constant SQL with a single {@code <lhs> IN (?)} run into the prefix (ending {@code <lhs>}),
     * suffix (starting {@code )}), and the run's 1-based ordinal. Returns {@code null} unless exactly one
     * {@code IN (?)} membership is present <b>outside</b> any single-quoted SQL string literal (a
     * parser-free local edge-check over the constant text).
     *
     * <p><b>String-literal exclusion (security):</b> the {@code SINGLE_QMARK_IN} regex can match {@code IN
     * (?)} text that lies inside a {@code '…'} string literal (e.g. {@code note = 'see IN (?)'}). Such a
     * match is data, not a membership — accepting it would splice the array marker INTO the literal and
     * leave the bound array param dangling (no real placeholder), corrupting the SQL on both dialects.
     * We therefore skip every match whose start is inside an open literal, and require exactly one
     * <i>real</i> (out-of-literal) {@code IN (?)} — zero real matches, or a second real match, refuses.</p>
     */
    private SingleQmarkSplit splitSingleQmarkInList(String sql) {
        java.util.regex.Matcher matcher = SINGLE_QMARK_IN.matcher(sql);
        int matchStart = -1;
        int matchEnd = -1;
        while (matcher.find()) {
            if (inStringLiteralAt(sql, matcher.start())) {
                continue; // an `IN (?)` inside a single-quoted literal is data, not a membership — skip.
            }
            if (matchStart >= 0) {
                return null; // more than one real `IN (?)` -> not the single-collection shape.
            }
            matchStart = matcher.start();
            matchEnd = matcher.end();
        }
        if (matchStart < 0) {
            return null; // no real (out-of-literal) `IN (?)` membership.
        }
        String prefix = sql.substring(0, matchStart);
        String lhs = inListLhs(prefix + " IN (");
        if (lhs == null) {
            return null;
        }
        String suffix = sql.substring(matchEnd);
        int runOrdinal = countQuestionMarksOutsideStringLiterals(prefix) + 1;
        // Strip the trailing `<lhs>` from the prefix (the marker carries the full `<lhs> IN (…)`), so the
        // emitted text is `<prefix without lhs> + <marker> + <suffix>` — matching FORM A's sqlPrefix shape.
        return new SingleQmarkSplit(lhs, stripInOpenParen(prefix, lhs), suffix, runOrdinal);
    }

    // ---- the per-element binding statement -------------------------------------------------------

    /**
     * The recognized per-element binding statement: its index(es) to elide, the binding statement itself
     * (the {@code for} loop or the {@code setArray} expression statement — so the use proof can whitelist
     * the collection accesses inside it), and (FORM B only) the collection the {@code setArray} resolved.
     */
    private record BindingMatch(
            List<Integer> elidedIndices, StatementTree bindingStatement, Element collection) {
    }

    /**
     * FORM A — matches the per-element bind loop {@code for (int i = 0; i < coll.size(); i++) ps.setXxx(i
     * + 1, coll.get(i));} immediately following B's prepare (allowing intervening unrelated statements is
     * NOT permitted — the loop must be the single statement that binds the run). The loop body must be
     * exactly one {@code ps.setXxx(<index+1>, coll.get(<index>))} over the SAME statement handle and the
     * SAME collection, sized by {@code coll.size()}. Returns the loop's index to elide, or {@code null}.
     */
    private BindingMatch matchPerElementLoop(
            List<? extends StatementTree> body, int prepareIndex, Element statementHandle,
            InRunMatch match, TreePath blockPath) {
        for (int i = prepareIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof ForLoopTree loop)) {
                continue;
            }
            TreePath loopPath = new TreePath(blockPath, loop);
            Element loopVar = forLoopCounterOverCollectionSize(loop, match.collection(), loopPath);
            if (loopVar == null) {
                continue;
            }
            MethodInvocationTree setCall = soleSetCall(loop);
            if (setCall == null) {
                continue;
            }
            if (!setCallBindsCollectionElement(setCall, statementHandle, match.collection(), loopVar,
                    match.runOrdinalStart(), new TreePath(loopPath, loop.getStatement()))) {
                continue;
            }
            return new BindingMatch(List.of(i), loop, null);
        }
        return null;
    }

    /**
     * FORM B — matches {@code ps.setArray(<runOrdinal>, c.createArrayOf("type", coll.toArray()));}
     * binding the single {@code IN (?)}. The {@code createArrayOf}'s element source must be {@code
     * coll.toArray()} (or {@code coll.toArray(new …[0])}) over a collection local/param; the resolved
     * collection becomes the bound array. Returns the setArray statement index to elide, or {@code null}.
     */
    private BindingMatch matchSetArray(
            List<? extends StatementTree> body, int prepareIndex, Element statementHandle,
            InRunMatch match, TreePath blockPath) {
        for (int i = prepareIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof ExpressionStatementTree expressionStatement)
                    || !(expressionStatement.getExpression() instanceof MethodInvocationTree setArray)
                    || !(setArray.getMethodSelect() instanceof MemberSelectTree select)
                    || !select.getIdentifier().contentEquals("setArray")
                    || setArray.getArguments().size() != 2) {
                continue;
            }
            TreePath statementPath = new TreePath(blockPath, statement);
            Element receiver = shapes.elementOf(select.getExpression(), statementPath);
            if (receiver == null || !receiver.equals(statementHandle)) {
                continue;
            }
            ExpressionTree ordinalArg = JdbcShapes.unwrap(setArray.getArguments().get(0));
            Integer ordinal = JdbcShapes.constantIntValue(ordinalArg);
            if (ordinal == null || ordinal != match.runOrdinalStart()) {
                continue;
            }
            Element collection = createArrayOfCollectionSource(setArray.getArguments().get(1), statementPath);
            if (collection == null) {
                continue;
            }
            return new BindingMatch(List.of(i), statement, collection);
        }
        return null;
    }

    // ---- the use proof ---------------------------------------------------------------------------

    /**
     * Proves {@code collection} is consumed <b>only</b> as the one IN-source. Scans the whole method body
     * and refuses array-binding the moment it sees any reference to {@code collection} other than the
     * allowed binding/sizing uses: FORM A allows the run's {@code coll.size()} (one), the loop's {@code
     * coll.size()} (one) and the loop's {@code coll.get(i)} (one); FORM B allows the {@code coll.toArray()}
     * in the setArray. ANY other use — a second {@code IN}, a {@code get}/{@code size}/iteration/{@code
     * stream}/{@code isEmpty}/{@code contains} elsewhere, an escape (field/return/argument), a bare
     * identifier reference — defeats the proof. Fail-safe: in doubt, do not array-bind.
     */
    private boolean collectionConsumedOnlyAsInSource(
            Element collection, InRunMatch match, BindingMatch binding,
            TreePath blockPath, TreePath methodBodyPath) {
        boolean[] defeated = {false};
        java.util.Set<Tree> allowedCalls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (match.sizeCall() != null) {
            allowedCalls.add(match.sizeCall()); // FORM A: the run's coll.size().
        }
        // The binding statement's own coll.* member-selects (the loop's get(i)/size(), or the toArray()).
        java.util.Set<Tree> bindingCollectionUses =
                bindingCollectionUses(binding, collection, blockPath);

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Element receiver = parsed.trees().getElement(new TreePath(getCurrentPath(), node.getExpression()));
                if (receiver != null && receiver.equals(collection)) {
                    classifyCollectionUse(node);
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element != null && element.equals(collection) && !isMemberSelectReceiver(getCurrentPath())) {
                    // A bare reference to coll that is NOT the receiver of a `coll.method(...)` member-select
                    // (which the member-select handler classifies) — an escape (passed as an argument,
                    // returned, an assignment source/target). A local collection's own declaration name is a
                    // Name (not an IdentifierTree), so it never reaches here; a parameter has no declaration
                    // identifier in the body at all. Any such bare use defeats array-binding (fail-safe).
                    defeated[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            private void classifyCollectionUse(MemberSelectTree node) {
                MethodInvocationTree call = enclosingCall(node);
                if (call == null) {
                    defeated[0] = true; // `coll.field` access -> unknown use.
                    return;
                }
                // Allowed: the run's size() (FORM A), and any member-select inside the binding statement.
                if (allowedCalls.contains(call) || bindingCollectionUses.contains(node)) {
                    return;
                }
                defeated[0] = true;
            }

            private MethodInvocationTree enclosingCall(MemberSelectTree node) {
                Tree parent = getCurrentPath().getParentPath() == null
                        ? null : getCurrentPath().getParentPath().getLeaf();
                return parent instanceof MethodInvocationTree call && call.getMethodSelect() == node ? call : null;
            }

            private boolean isMemberSelectReceiver(TreePath path) {
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                return parent instanceof MemberSelectTree memberSelect && memberSelect.getExpression() == path.getLeaf();
            }
        }.scan(methodBodyPath, null);

        return !defeated[0];
    }

    /**
     * The set of {@code collection} member-selects inside the binding statement (allowed uses): FORM A's
     * loop {@code coll.size()} and {@code coll.get(i)}; FORM B's {@code coll.toArray()}. Collected by
     * identity so the use proof can whitelist exactly these and defeat any other reference.
     */
    private java.util.Set<Tree> bindingCollectionUses(
            BindingMatch binding, Element collection, TreePath blockPath) {
        java.util.Set<Tree> uses = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Element receiver = parsed.trees().getElement(new TreePath(getCurrentPath(), node.getExpression()));
                if (receiver != null && receiver.equals(collection)) {
                    uses.add(node);
                }
                return super.visitMemberSelect(node, unused);
            }
        }.scan(new TreePath(blockPath, binding.bindingStatement()), null);
        return uses;
    }

    // ---- shape helpers ---------------------------------------------------------------------------

    /** The loop counter element of {@code for (int i = 0; i < coll.size(); i++)} over {@code coll}, or null. */
    private Element forLoopCounterOverCollectionSize(ForLoopTree loop, Element collection, TreePath loopPath) {
        // Initializer: a single `int i = 0` (or `long i = 0`).
        if (loop.getInitializer().size() != 1
                || !(loop.getInitializer().getFirst() instanceof VariableTree counterDecl)
                || !(JdbcShapes.unwrap(counterDecl.getInitializer()) instanceof LiteralTree initLit)
                || !(initLit.getValue() instanceof Integer initValue) || initValue != 0) {
            return null;
        }
        Element counter = parsed.trees().getElement(new TreePath(loopPath, counterDecl));
        if (counter == null) {
            return null;
        }
        // Condition: `i < coll.size()` over the SAME collection and counter.
        if (!(JdbcShapes.unwrap(loop.getCondition()) instanceof BinaryTree condition)
                || condition.getKind() != Tree.Kind.LESS_THAN) {
            return null;
        }
        Element conditionVar = shapes.elementOf(JdbcShapes.unwrap(condition.getLeftOperand()), loopPath);
        if (conditionVar == null || !conditionVar.equals(counter)) {
            return null;
        }
        CollectionSizedRun bound = collectionSizeCall(condition.getRightOperand(), loopPath);
        if (bound == null || !bound.collection().equals(collection)) {
            return null;
        }
        return counter;
    }

    /** The single {@code ps.setXxx(...)} call that is the loop body (a one-statement body), or {@code null}. */
    private MethodInvocationTree soleSetCall(ForLoopTree loop) {
        StatementTree bodyStatement = loop.getStatement();
        StatementTree only = bodyStatement;
        if (bodyStatement instanceof BlockTree block) {
            if (block.getStatements().size() != 1) {
                return null;
            }
            only = block.getStatements().getFirst();
        }
        if (only instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree call
                && call.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().toString().startsWith("set")
                && call.getArguments().size() == 2) {
            return call;
        }
        return null;
    }

    /**
     * Whether {@code setCall} is {@code ps.setXxx(<counter> + 1, coll.get(<counter>))} over {@code
     * statementHandle}, {@code collection} and {@code counter} — the exact per-element bind the run drives.
     * The ordinal argument must be {@code counter + 1} (or {@code 1 + counter}); the value argument must be
     * {@code coll.get(counter)} over the same collection and counter.
     */
    private boolean setCallBindsCollectionElement(
            MethodInvocationTree setCall, Element statementHandle, Element collection, Element counter,
            int runOrdinalStart, TreePath bodyPath) {
        MemberSelectTree select = (MemberSelectTree) setCall.getMethodSelect();
        Element receiver = shapes.elementOf(select.getExpression(), bodyPath);
        if (receiver == null || !receiver.equals(statementHandle)) {
            return false;
        }
        if (!ordinalIsCounterPlusOffset(
                JdbcShapes.unwrap(setCall.getArguments().get(0)), counter, runOrdinalStart, bodyPath)) {
            return false;
        }
        ExpressionTree valueArg = JdbcShapes.unwrap(setCall.getArguments().get(1));
        if (!(valueArg instanceof MethodInvocationTree getCall)
                || !(getCall.getMethodSelect() instanceof MemberSelectTree getSelect)
                || !getSelect.getIdentifier().contentEquals("get")
                || getCall.getArguments().size() != 1) {
            return false;
        }
        Element getReceiver = shapes.elementOf(getSelect.getExpression(), bodyPath);
        if (getReceiver == null || !getReceiver.equals(collection)) {
            return false;
        }
        Element indexVar = shapes.elementOf(JdbcShapes.unwrap(getCall.getArguments().getFirst()), bodyPath);
        return indexVar != null && indexVar.equals(counter);
    }

    /**
     * Whether {@code expr} is the per-element bind ordinal {@code counter + offset} (or {@code offset +
     * counter}) — where {@code offset} is the run's 1-based start ordinal ({@code runOrdinalStart}). The
     * run occupies ordinals {@code offset .. offset+size-1}, so the loop's {@code setXxx} ordinal must be
     * {@code counter + offset} as {@code counter} goes {@code 0 .. size-1}. With {@code offset == 1} (the
     * run at the statement start) this is the canonical {@code i + 1}.
     */
    private boolean ordinalIsCounterPlusOffset(
            ExpressionTree expr, Element counter, int offset, TreePath path) {
        if (!(expr instanceof BinaryTree binary) || binary.getKind() != Tree.Kind.PLUS) {
            return false;
        }
        Element left = shapes.elementOf(JdbcShapes.unwrap(binary.getLeftOperand()), path);
        Element right = shapes.elementOf(JdbcShapes.unwrap(binary.getRightOperand()), path);
        Integer leftLit = JdbcShapes.constantIntValue(binary.getLeftOperand());
        Integer rightLit = JdbcShapes.constantIntValue(binary.getRightOperand());
        boolean counterPlusOffset = counter.equals(left) && rightLit != null && rightLit == offset;
        boolean offsetPlusCounter = counter.equals(right) && leftLit != null && leftLit == offset;
        return counterPlusOffset || offsetPlusCounter;
    }

    /**
     * The collection source of {@code c.createArrayOf("type", coll.toArray())}, or {@code null}. The
     * {@code createArrayOf} <i>type-string</i> first argument is INTENTIONALLY not read: the array/JSON
     * element cast is derived from the declared {@code List<E>} static type ({@link #elementTypeOf}),
     * which is the value type actually bound, so the cast is always type-consistent with the bound
     * elements regardless of the (possibly stale/mismatched) JDBC type-string. The static {@code List<E>}
     * type is authoritative here — not the {@code createArrayOf} argument.
     */
    private Element createArrayOfCollectionSource(ExpressionTree arrayArg, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(arrayArg);
        if (!(expr instanceof MethodInvocationTree create)
                || !(create.getMethodSelect() instanceof MemberSelectTree createSelect)
                || !createSelect.getIdentifier().contentEquals("createArrayOf")
                || create.getArguments().size() != 2) {
            return null;
        }
        ExpressionTree elementsArg = JdbcShapes.unwrap(create.getArguments().get(1));
        if (!(elementsArg instanceof MethodInvocationTree toArray)
                || !(toArray.getMethodSelect() instanceof MemberSelectTree toArraySelect)
                || !toArraySelect.getIdentifier().contentEquals("toArray")) {
            return null;
        }
        Element collection = shapes.elementOf(toArraySelect.getExpression(), path);
        return isCollectionLocalOrParam(collection) ? collection : null;
    }

    /** B's surviving {@code setXxx(ordinal, value)} binds outside the run (ordinal != run, != the binding's). */
    private java.util.SortedMap<Integer, ExpressionTree> collectOtherSetBinds(
            List<? extends StatementTree> body, int prepareIndex, Element statementHandle,
            int runOrdinalStart, BindingMatch binding, TreePath blockPath) {
        java.util.SortedMap<Integer, ExpressionTree> binds = new java.util.TreeMap<>();
        for (int i = prepareIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (binding.elidedIndices().contains(i)) {
                continue; // the binding statement itself (its set ordinals are the run, dropped).
            }
            if (!(statement instanceof ExpressionStatementTree expressionStatement)
                    || !(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
                continue;
            }
            if (!select.getIdentifier().toString().startsWith("set")
                    || invocation.getArguments().size() < 2) {
                continue;
            }
            Element receiver = shapes.elementOf(select.getExpression(), new TreePath(blockPath, statement));
            if (receiver == null || !receiver.equals(statementHandle)) {
                continue;
            }
            Integer ordinal = JdbcShapes.constantIntValue(JdbcShapes.unwrap(invocation.getArguments().get(0)));
            if (ordinal != null && ordinal != runOrdinalStart) {
                binds.put(ordinal, invocation.getArguments().get(1));
            }
        }
        return binds;
    }

    /**
     * Whether B's surviving scalar binds line up with the {@code ?}-placeholders that flank the IN-run, so
     * the array-bind's marker and every scalar {@code ?} bind the right parameter (see the call site for
     * the full rationale). Requires (a) the before-run binds to be exactly the contiguous literal ordinals
     * {@code 1 .. runStart-1} — one per prefix {@code ?}, none missing/extra — and (b) zero {@code ?}
     * surviving in the suffix (the IN-run is the last placeholder group). {@code beforeOrdinals} are the
     * literal-ordinal binds outside the run; by the caller's after-run guard they are all {@code <
     * runStart}, so their count and contiguity fully decide the prefix side.
     */
    private static boolean placeholdersAlign(InRunMatch match, java.util.Set<Integer> beforeOrdinals) {
        int prefixPlaceholders = match.runOrdinalStart() - 1; // == countQuestionMarks(prefix).
        // Every prefix `?` must be bound by exactly one literal-ordinal setXxx, and those ordinals must be
        // precisely 1..runStart-1 (contiguous, no gap → no unaccounted prefix `?`, no stray extra bind).
        if (beforeOrdinals.size() != prefixPlaceholders) {
            return false;
        }
        for (int ordinal = 1; ordinal <= prefixPlaceholders; ordinal++) {
            if (!beforeOrdinals.contains(ordinal)) {
                return false;
            }
        }
        // No `?` may remain after the run — a surviving suffix `?` is an unbound placeholder (the run is
        // the last placeholder group). The closing `)` the suffix begins with carries no `?`, so counting
        // over the raw suffix is equivalent to counting over the lowerer's `)`-stripped suffix.
        return countQuestionMarksOutsideStringLiterals(match.sqlSuffix()) == 0;
    }

    // ---- element type + small helpers ------------------------------------------------------------

    /**
     * The scalar {@link ElementType} of {@code collection}'s declared {@code List<E>} element, or {@code
     * null} when it is not a scalar-element List (a {@code List<SomeRecord>}, a raw {@code List}, or a
     * non-List): {@code Long}/{@code long}→{@code BIGINT}, {@code Integer}/{@code int}→{@code INT}, {@code
     * String}→{@code TEXT}. Resolved from the declared type's single type argument.
     */
    private ElementType elementTypeOf(Element collection) {
        if (collection == null) {
            return null;
        }
        TypeMirror type = collection.asType();
        if (!(type instanceof javax.lang.model.type.DeclaredType declaredType)
                || declaredType.getTypeArguments().size() != 1) {
            return null;
        }
        String element = declaredType.getTypeArguments().getFirst().toString();
        return switch (element) {
            case "java.lang.Long", "Long", "long" -> ElementType.BIGINT;
            case "java.lang.Integer", "Integer", "int" -> ElementType.INT;
            case "java.lang.String", "String" -> ElementType.TEXT;
            case "java.util.UUID", "UUID" -> ElementType.UUID;
            default -> null;
        };
    }

    /** Whether {@code element} is a local-variable or parameter of a {@code java.util.Collection} subtype. */
    private boolean isCollectionLocalOrParam(Element element) {
        if (element == null
                || (element.getKind() != ElementKind.LOCAL_VARIABLE && element.getKind() != ElementKind.PARAMETER)) {
            return false;
        }
        return isCollectionType(element.asType());
    }

    private boolean isCollectionType(TypeMirror type) {
        if (!(type instanceof javax.lang.model.type.DeclaredType)) {
            return false;
        }
        javax.lang.model.element.TypeElement collectionElement =
                parsed.elements().getTypeElement("java.util.Collection");
        if (collectionElement == null) {
            return false;
        }
        return parsed.types().isAssignable(
                parsed.types().erasure(type), parsed.types().erasure(collectionElement.asType()));
    }

    /**
     * The membership left-hand side recovered from a constant {@code prefix} ending in {@code <lhs> IN (}:
     * the bare column token (letters/digits/_/./quotes/backticks) immediately before the {@code IN (}, or
     * {@code null} if the prefix does not cleanly end in a non-negated {@code <ident> IN (}. Parser-free
     * local edge-check (the same posture as {@link JdbcSubqueryFusion#endsInInOpenParen}); a {@code NOT IN}
     * is refused (its empty/NULL equivalence differs from a bound list when an element is NULL).
     */
    static String inListLhs(String prefix) {
        if (prefix == null) {
            return null;
        }
        String trimmed = prefix.stripTrailing();
        if (trimmed.isEmpty() || trimmed.charAt(trimmed.length() - 1) != '(') {
            return null;
        }
        String beforeParen = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        String upper = beforeParen.toUpperCase(java.util.Locale.ROOT);
        if (!upper.endsWith("IN")) {
            return null;
        }
        int inStart = beforeParen.length() - 2;
        if (inStart > 0) {
            char before = beforeParen.charAt(inStart - 1);
            if (Character.isLetterOrDigit(before) || before == '_') {
                return null; // a column literally named "...in".
            }
        }
        String beforeIn = beforeParen.substring(0, inStart).stripTrailing();
        if (beforeIn.isEmpty()) {
            return null; // no LHS column before IN (.
        }
        // Reject NOT IN (.
        String beforeInUpper = beforeIn.toUpperCase(java.util.Locale.ROOT);
        if (beforeInUpper.endsWith("NOT")) {
            int notStart = beforeIn.length() - 3;
            if (notStart == 0
                    || (!Character.isLetterOrDigit(beforeIn.charAt(notStart - 1)) && beforeIn.charAt(notStart - 1) != '_')) {
                return null;
            }
        }
        // The LHS is the last whitespace-delimited token of beforeIn (a column ref, possibly qualified).
        int tokenStart = beforeIn.length();
        while (tokenStart > 0 && isLhsTokenChar(beforeIn.charAt(tokenStart - 1))) {
            tokenStart--;
        }
        String lhs = beforeIn.substring(tokenStart);
        return lhs.isEmpty() ? null : lhs;
    }

    private static boolean isLhsTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '`' || c == '"';
    }

    /** The constant prefix with the trailing {@code <lhs> IN (} stripped (so the marker takes its place). */
    private static String stripInOpenParen(String prefix, String lhs) {
        // Remove from the last index of `lhs` (immediately before IN () to the end; the marker text the
        // lowerer appends carries the lhs + the membership, so the prefix must NOT keep `lhs IN (`.
        int lhsIndex = prefix.lastIndexOf(lhs);
        // Guard: lhsIndex is the lhs immediately before the IN ( — strip from there.
        return lhsIndex < 0 ? prefix : prefix.substring(0, lhsIndex);
    }

    private static boolean startsWithCloseParen(String suffix) {
        String trimmed = suffix.stripLeading();
        return !trimmed.isEmpty() && trimmed.charAt(0) == ')';
    }

    private static void flattenConcatenation(ExpressionTree expr, List<ExpressionTree> out) {
        ExpressionTree current = JdbcShapes.unwrap(expr);
        if (current instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            flattenConcatenation(binary.getLeftOperand(), out);
            flattenConcatenation(binary.getRightOperand(), out);
        } else {
            out.add(current);
        }
    }

    private String constantStringValue(ExpressionTree expression, TreePath enclosingPath) {
        if (expression == null) {
            return null;
        }
        ExpressionTree stripped = JdbcShapes.unwrap(expression);
        if (stripped instanceof LiteralTree literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (stripped instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = constantStringValue(binary.getLeftOperand(), enclosingPath);
            String right = constantStringValue(binary.getRightOperand(), enclosingPath);
            return (left == null || right == null) ? null : left + right;
        }
        if (stripped instanceof IdentifierTree || stripped instanceof MemberSelectTree) {
            Element element = parsed.trees().getElement(new TreePath(enclosingPath, stripped));
            if (element instanceof javax.lang.model.element.VariableElement variable
                    && variable.getKind() == ElementKind.FIELD
                    && variable.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && variable.getModifiers().contains(javax.lang.model.element.Modifier.FINAL)
                    && variable.getConstantValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static int countQuestionMarksOutsideStringLiterals(String text) {
        return JdbcSubqueryFusion.countQuestionMarksOutsideStringLiterals(text);
    }

    /**
     * Whether the SQL-literal scan of {@code text} ends <b>outside</b> any open single-quoted string
     * literal — i.e. every {@code '} that opens a literal in {@code text} is matched by a closing {@code
     * '} (with {@code ''} treated as an escaped quote, staying in the literal). Used to reject FORM A
     * when the constant prefix's trailing {@code IN (} membership opener actually lies inside an
     * unterminated literal (a developer assembling a value like {@code 'a IN ('}): the prefix ends in
     * {@code (} but with the literal still open, so {@code IN (} is data, not a membership. (The
     * trailing {@code IN (} carries no {@code '}, so the literal state at the prefix's end equals the
     * state at the {@code (}.) Mirrors the quote walk of {@link #countQuestionMarksOutsideStringLiterals}.
     */
    private static boolean endsOutsideStringLiteral(String text) {
        return !inStringLiteralAt(text, text.length());
    }

    /**
     * Whether index {@code position} of {@code text} lies <b>inside</b> an open single-quoted SQL string
     * literal — scanning {@code text[0 .. position)} with the same quote state machine as {@link
     * #countQuestionMarksOutsideStringLiterals} ({@code ''} is an escaped quote, staying in the literal).
     * Used so FORM B never treats an {@code IN (?)} that begins inside a {@code '…'} literal as a real
     * membership (the array marker would otherwise be spliced into the literal's text).
     */
    private static boolean inStringLiteralAt(String text, int position) {
        boolean inString = false;
        for (int i = 0; i < position && i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++; // an escaped '' quote — still inside the literal.
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
            }
        }
        return inString;
    }

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

    private static boolean isRecognizedPlaceholderHelper(String method) {
        String lower = method.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("placeholders") || lower.equals("placeholder")
                || lower.equals("qmarks") || lower.equals("questionmarks")
                || lower.equals("bindplaceholders") || lower.equals("repeatplaceholders")
                || lower.equals("inplaceholders") || lower.equals("makeplaceholders")
                || lower.equals("sqlplaceholders");
    }

    private static final java.util.regex.Pattern PLACEHOLDER_OR_SEPARATOR_ONLY =
            java.util.regex.Pattern.compile("[?,()\\s]*(?:(?i:in)[?,()\\s]*)*");

    // A single `IN (?)` membership: `IN` (whole word) + `(` + a single `?` + `)`, whitespace tolerant.
    private static final java.util.regex.Pattern SINGLE_QMARK_IN =
            java.util.regex.Pattern.compile("(?i)\\bIN\\s*\\(\\s*\\?\\s*\\)");
}
