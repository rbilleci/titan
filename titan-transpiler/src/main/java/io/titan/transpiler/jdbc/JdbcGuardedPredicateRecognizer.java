package io.titan.transpiler.jdbc;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
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
 * Recognizer + <b>control-flow / bind-correlation proof</b> for design contract <b>D4</b> — the
 * pervasive <i>optional-filter / search-builder</i> idiom (WS-C Phase 3 follow-up). It identifies the
 * shape where a base CONSTANT query is extended by a finite, statically-enumerable sequence of
 * <i>optional clauses</i>, each guarded on a {@code param != null} NULL-check, and lowers it to ONE
 * <b>static</b> query using <b>guarded predicates</b> — every value bound, no dynamic {@code EXECUTE}:
 *
 * <pre>{@code
 *   StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
 *   if (status != null)     { sql.append(" AND status = ?"); }
 *   if (minBalance != null) { sql.append(" AND balance >= ?"); }
 *   PreparedStatement ps = c.prepareStatement(sql.toString());
 *   int i = 1;
 *   if (status != null)     ps.setString(i++, status);
 *   if (minBalance != null) ps.setBigDecimal(i++, minBalance);
 * }</pre>
 *
 * <p>lowers to the single static query (planner-friendly, no dynamic SQL, all values bound):</p>
 *
 * <pre>{@code
 *   SELECT id FROM accounts WHERE active = true
 *     AND (? IS NULL OR status = ?)        -- ? = p_status (bound twice)
 *     AND (? IS NULL OR balance >= ?)      -- ? = p_min_balance (bound twice)
 * }</pre>
 *
 * <h2>The ordered / paginated search-builder extension</h2>
 * The same shape is also recognized when the gated optional-clause appends are followed by a TRAILING run
 * of <b>unconditional</b> CONSTANT appends — the "tail" of an ordered/paginated builder:
 *
 * <pre>{@code
 *   StringBuilder sql = new StringBuilder("SELECT id FROM accounts WHERE active = true");
 *   if (tier != null)   sql.append(" AND tier = ?");
 *   if (minBal != null) sql.append(" AND balance >= ?");
 *   sql.append(" ORDER BY id DESC");        // trailing UNCONDITIONAL constant append, AFTER the filters
 * }</pre>
 *
 * <p>lowers to the single static query with the guards at the WHERE boundary and the constant tail after
 * them (the {@code ORDER BY}/{@code GROUP BY} does not change the filter logic — it applies the same
 * ordering/grouping the conditional builder did, after the same guarded predicate):</p>
 *
 * <pre>{@code
 *   SELECT id FROM accounts WHERE active = true
 *     AND (? IS NULL OR tier = ?) AND (? IS NULL OR balance >= ?)
 *     ORDER BY id DESC
 * }</pre>
 *
 * <p>The base STAYS WHERE-terminal (the guards attach to the base WHERE; the tail follows them). A trailing
 * append is admitted ONLY when it is an ordering/grouping/pagination suffix whose only {@code ?}s sit in a
 * pagination COUNT/OFFSET position ({@code " ORDER BY id DESC LIMIT ?, ?"}, {@code " FETCH FIRST ? ROWS
 * ONLY"}) — each such {@code ?} is a safe VALUE bind, correlated 1:1 to a strictly-trailing run of
 * UNCONDITIONAL {@code ps.setXxx(<ord>, <expr>)} pagination binds in tail-{@code ?} text order. A {@code ?}
 * ANYWHERE ELSE in the tail (an {@code ORDER BY ?} no-op sort, a {@code FOR UPDATE} object) is OUT of scope —
 * fail-safe fall-through. The tail is NOT itself a predicate ({@code " AND …"} / {@code " OR …"} is a clause,
 * not a tail) and comes STRICTLY AFTER every gated append (a tail interspersed with, or before, the gated
 * appends defeats). Because input is single-dialect-native and the tail splices VERBATIM into the SAME-dialect
 * output procedure, there is NO cross-dialect translation: a MySQL build {@code LIMIT ?, ?} lands in a MySQL
 * procedure (valid); a PG build {@code FETCH FIRST ? ROWS ONLY} in a PG procedure (valid). When in doubt the
 * tail is not recognized and the whole builder falls through to the existing strict/Rung behavior.
 *
 * <p>Each optional clause becomes an <b>always-present guarded predicate</b> {@code (p IS NULL OR <col>
 * <OP> p)}: when the param is NULL the guard is TRUE (a no-op, exactly the omitted clause); when it is
 * non-null the guard reduces to {@code <col> <OP> p} (exactly the appended clause). The param is bound
 * (never spliced); on PostgreSQL and MySQL the guarded predicate is identical SQL the DB optimizer
 * plans. The String-concat (<code>+=</code>) form and a separate-if bind sequence are accepted too.</p>
 *
 * <h2>Semantic equivalence (the #1 risk — rigorously preserved)</h2>
 * For EVERY param combination and EVERY supported operator, {@code AND (p IS NULL OR col OP p)} returns
 * the SAME rows as the conditional builder (which omits the clause when {@code p} is null):
 * <ul>
 *   <li><b>p null</b> → {@code (NULL IS NULL OR …)} = {@code (TRUE OR …)} = TRUE → no-op == omitted clause;</li>
 *   <li><b>p non-null</b> → {@code (FALSE OR col OP p)} = {@code col OP p} == the appended clause;</li>
 *   <li><b>a row whose {@code col} IS NULL</b> (p non-null) → {@code col OP p} is NULL (under
 *       {@code =}/{@code <>}/{@code <}/…/{@code LIKE} three-valued logic) → the row is not matched —
 *       identical to the original {@code WHERE … AND col OP ?} (the guard adds nothing when p is
 *       non-null, so NULL-column semantics are unchanged);</li>
 *   <li><b>an all-null call</b> (no filters) → every guard is TRUE → the base predicate alone.</li>
 * </ul>
 * Only the comparison operators whose three-valued logic is preserved by the guard are accepted
 * ({@code =}, {@code <>}/{@code !=}, {@code <}, {@code <=}, {@code >}, {@code >=}, {@code LIKE}); any
 * other clause shape is a FAIL-SAFE reject (not mis-lowered).
 *
 * <h2>The fail-safe contract — never mis-recognize</h2>
 * This recognizer <b>never rewrites anything</b>: it returns a {@link GuardedPredicatePlan} the {@code
 * JdbcStatementLowerer} consumes, or {@link java.util.Optional#empty()} when the canonical shape is not
 * proven — in which case the lowerer keeps the existing Rung-1/Rung-3/strict-gate behavior unchanged.
 * It refuses (returns empty) the moment ANY of the following holds (mirroring {@link JdbcSubqueryFusion}'s
 * never-false-accept posture):
 * <ul>
 *   <li>a non-null-check guard condition ({@code if (x > 0)}, {@code isEmpty()}, a compound
 *       {@code &&}/{@code ||});</li>
 *   <li>an {@code else} branch on an optional-clause {@code if};</li>
 *   <li>an appended clause that is not exactly {@code " AND <col> <OP> ?"} (an {@code OR}, a subquery, a
 *       raw fragment, an identifier hole, two {@code ?}, a non-constant text);</li>
 *   <li>a {@code ?} bound (in the correlated bind {@code if}) to a DIFFERENT param than the gate;</li>
 *   <li>a loop building clauses, a non-constant base, or an optional clause whose bind is unconditional
 *       or whose gate has no matching bind;</li>
 *   <li>anything it cannot prove maps to exactly one guarded predicate.</li>
 * </ul>
 */
public final class JdbcGuardedPredicateRecognizer {

    private final ParsedSources parsed;
    private final JdbcShapes shapes;
    private final JdbcTypeOracle oracle;

    public JdbcGuardedPredicateRecognizer(ParsedSources parsed, JdbcShapes shapes, JdbcTypeOracle oracle) {
        if (parsed == null || shapes == null || oracle == null) {
            throw new IllegalArgumentException("parsed/shapes/oracle must not be null");
        }
        this.parsed = parsed;
        this.shapes = shapes;
        this.oracle = oracle;
    }

    /** A supported comparison operator (its three-valued logic is preserved by the {@code IS NULL OR} guard). */
    public enum Op {
        EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="), LIKE("LIKE");

        private final String sql;

        Op(String sql) {
            this.sql = sql;
        }

        /** The canonical SQL spelling of this operator (e.g. {@code <>} for both {@code <>} and {@code !=}). */
        public String sql() {
            return sql;
        }
    }

    /**
     * One proven optional clause {@code if (param != null) { sql.append(" AND <col> <OP> ?"); }} with its
     * correlated bind {@code if (param != null) ps.setXxx(<ord>, param)}. The lowerer emits it as the
     * always-present guarded predicate {@code AND (? IS NULL OR <col> <OP> ?)} where both {@code ?} bind
     * {@link #paramExpr} (the SAME param the gate null-checks and the bind binds).
     */
    public record OptionalClause(String column, Op op, ExpressionTree paramExpr, Element paramElement) {
    }

    /**
     * A proven guarded-predicate lowering of an optional-filter search builder. The lowerer:
     * <ul>
     *   <li>elides the SQL-builder statements ({@link #builderDeclIndex} — the {@code StringBuilder
     *       sql = …} / base-{@code String} declaration — and each {@link #appendStatementIndices append
     *       {@code if}}), the bind-counter declaration ({@link #bindCounterDeclIndex}, the {@code int i =
     *       1}, if present), each correlated clause bind {@code if} ({@link #bindStatementIndices}), and
     *       each unconditional pagination bind ({@link #tailBindStatementIndices});</li>
     *   <li>replaces statement B (at {@link #consumingStatementIndex}, the {@code prepareStatement(sql
     *       .toString())} that consumes the built text) with a {@code RawSql} whose SQL is {@link #baseSql}
     *       {@code +} one {@code AND (? IS NULL OR <col> <OP> ?)} per {@link #clauses} {@code +} the
     *       {@link #tailSql} (an ORDERED/PAGINATED builder's trailing {@code ORDER BY}/{@code GROUP BY}/…
     *       + {@code LIMIT}/{@code OFFSET}/{@code FETCH} pagination, captured from the unconditional appends
     *       after the filters), all values bound — including the pagination {@code ?}s, bound LAST.</li>
     * </ul>
     *
     * <p>{@link #tailSql} is the concatenation (in source order) of the trailing UNCONDITIONAL constant
     * appends, or the empty string for a no-tail builder (the original D4 shape, unchanged); {@link
     * #tailStatementIndices} are those appends' statement indices (also elided). The tail is constant
     * EXCEPT for {@code ?} placeholders sitting in a pagination count/offset position ({@code LIMIT ?},
     * {@code OFFSET ?}, {@code LIMIT ?, ?}, {@code FETCH FIRST ? ROWS ONLY}, …) — those are safe VALUE binds
     * that the recognizer correlated to a strictly-trailing run of UNCONDITIONAL {@code ps.setXxx(<ord>,
     * <expr>)} binds. {@link #tailParamExprs} are those bound expressions, in tail-{@code ?} TEXT order
     * (which is source-bind order — the recognizer proved the binds run in tail-{@code ?} order); the
     * lowerer binds them AFTER the clause binds (the clauses, being always present, are textually first;
     * the pagination {@code ?}s are textually last). A no-pagination tail (a pure {@code ORDER BY}/{@code
     * GROUP BY}/constant-{@code LIMIT} suffix) has an empty {@link #tailParamExprs} — unchanged from the
     * constant-tail D4 shape.</p>
     */
    public record GuardedPredicatePlan(
            int builderDeclIndex,
            int bindCounterDeclIndex,
            int consumingStatementIndex,
            StatementTree consumingStatement,
            String baseSql,
            List<OptionalClause> clauses,
            String tailSql,
            List<ExpressionTree> tailParamExprs,
            List<Integer> appendStatementIndices,
            List<Integer> tailStatementIndices,
            List<Integer> bindStatementIndices,
            List<Integer> tailBindStatementIndices,
            List<StatementTree> elidedStatements) {

        public GuardedPredicatePlan {
            clauses = List.copyOf(clauses);
            tailSql = tailSql == null ? "" : tailSql;
            tailParamExprs = List.copyOf(tailParamExprs);
            appendStatementIndices = List.copyOf(appendStatementIndices);
            tailStatementIndices = List.copyOf(tailStatementIndices);
            bindStatementIndices = List.copyOf(bindStatementIndices);
            tailBindStatementIndices = List.copyOf(tailBindStatementIndices);
            elidedStatements = List.copyOf(elidedStatements);
        }

        /**
         * The Java AST nodes a proven guarded-predicate lowering <b>subsumes</b> — every node inside an
         * elided statement (the {@code StringBuilder sql = new StringBuilder(base)} / base-{@code String}
         * declaration, every {@code if (param != null) sql.append(" AND …")} append guard, each trailing
         * unconditional {@code sql.append(" ORDER BY … LIMIT ?")} tail append, the {@code int i = 1} bind
         * counter, every {@code if (param != null) ps.setXxx(…, param)} clause bind guard, and each
         * unconditional {@code ps.setXxx(<ord>, <expr>)} pagination bind). The
         * {@code FeatureValidator}/{@code InternalHelperDiscovery} consult this set (by tree identity) to
         * exempt exactly the {@code new StringBuilder}/{@code sql.append}/{@code sql.toString()}
         * builder constructs the lowering removes — never any other construct, so the boundary is precise.
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
            return subsumed;
        }

        /**
         * The full guarded-predicate SQL text: {@link #baseSql} followed by one {@code AND (? IS NULL OR
         * <col> <OP> ?)} per clause (each {@code ?} bound to the clause's param, in clause order), then the
         * {@link #tailSql} (the ordered/paginated builder's trailing {@code ORDER BY}/{@code GROUP BY}/…
         * + {@code LIMIT}/{@code OFFSET}/{@code FETCH} pagination — empty for a no-tail builder) appended
         * verbatim AFTER the guards. The guards sit at the WHERE boundary; the tail follows. The splice is
         * verbatim whether the tail is constant (a pure ordering suffix) or carries pagination {@code ?}s
         * (the recognizer proved each tail {@code ?} is a count/offset operand and correlated its
         * unconditional bind) — the pagination {@code ?}s, being textually LAST, bind LAST. Static,
         * value-bound, single-dialect-native (a {@code LIMIT ?, ?} lands in a MySQL procedure, a {@code
         * FETCH FIRST ? ROWS ONLY} in a PostgreSQL one — each valid on its own engine).
         */
        public String guardedSql() {
            StringBuilder sql = new StringBuilder(baseSql);
            for (OptionalClause clause : clauses) {
                sql.append(" AND (? IS NULL OR ").append(clause.column()).append(' ')
                        .append(clause.op().sql()).append(" ?)");
            }
            sql.append(tailSql);
            return sql.toString();
        }
    }

    /**
     * Recognizes the first guarded-predicate search builder in {@code body} (a block's statements).
     * Returns the proven {@link GuardedPredicatePlan}, or empty when the canonical shape is not present
     * or the control-flow / bind-correlation proof refuses (fail-safe). {@code blockPath} is the block's
     * path (for type/element resolution); {@code methodBodyPath} is the whole method body, over which the
     * builder's use-and-escape proof scans (a use of the builder anywhere beyond the appends defeats it).
     */
    public java.util.Optional<GuardedPredicatePlan> recognize(
            List<? extends StatementTree> body, TreePath blockPath, TreePath methodBodyPath) {
        if (methodBodyPath == null) {
            return java.util.Optional.empty();
        }
        for (int declIndex = 0; declIndex < body.size(); declIndex++) {
            java.util.Optional<GuardedPredicatePlan> plan =
                    tryRecognizeAt(body, declIndex, blockPath, methodBodyPath);
            if (plan.isPresent()) {
                return plan;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Recognizes <b>every</b> guarded-predicate search builder reachable in {@code methodBody} — scanning
     * each nested block with the whole method body as the proof scope (the same scope the lowerer
     * recognizes each block under). Used by the {@code FeatureValidator}/{@code InternalHelperDiscovery}
     * to exempt exactly the builder constructs every proven lowering subsumes, method-wide. Empty when no
     * guarded-predicate builder is provable.
     */
    public List<GuardedPredicatePlan> recognizeAll(BlockTree methodBody, TreePath methodBodyPath) {
        if (methodBody == null || methodBodyPath == null) {
            return List.of();
        }
        List<GuardedPredicatePlan> plans = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitBlock(BlockTree node, Void unused) {
                recognize(node.getStatements(), getCurrentPath(), methodBodyPath).ifPresent(plans::add);
                return super.visitBlock(node, unused);
            }
        }.scan(methodBodyPath, null);
        return plans;
    }

    private java.util.Optional<GuardedPredicatePlan> tryRecognizeAt(
            List<? extends StatementTree> body, int declIndex, TreePath blockPath, TreePath methodBodyPath) {
        StatementTree declStatement = body.get(declIndex);
        if (!(declStatement instanceof VariableTree builderDecl)) {
            return java.util.Optional.empty();
        }
        // (1) The base text holder: either `StringBuilder sql = new StringBuilder("<const base>")`
        //     (canonical) or `String sql = "<const base>"` (the += form). The base must be CONSTANT.
        BuilderStart start = recognizeBuilderStart(builderDecl, new TreePath(blockPath, builderDecl));
        if (start == null) {
            return java.util.Optional.empty();
        }
        Element builder = parsed.trees().getElement(new TreePath(blockPath, builderDecl));
        if (builder == null || !isLocalVariable(builder)) {
            return java.util.Optional.empty();
        }

        // (2) Find the consuming `PreparedStatement ps = c.prepareStatement(sql.toString())` (StringBuilder)
        //     / `prepareStatement(sql)` (String) — query B. Its handle binds the optional params.
        ConsumingPrepare consuming = findConsumingPrepare(body, declIndex, builder, start.builderKind(), blockPath);
        if (consuming == null) {
            return java.util.Optional.empty();
        }

        // (3) Between the builder decl and the consuming prepare: FIRST a run of proven optional-clause
        //     append guards `if (param != null) { sql.append(" AND col OP ?"); }` (or the += form), THEN an
        //     OPTIONAL strictly-trailing run of UNCONDITIONAL appends — the ordered/paginated builder's "tail"
        //     (` ORDER BY id`, ` GROUP BY x`, ` ORDER BY id DESC LIMIT ?, ?`, several concatenated). The two
        //     runs must not interleave: once a tail append is seen, a later gated append DEFEATS (interspersed).
        //     Any other statement (an else, a non-null-check, a loop, a non-ordering tail append, a tail `?`
        //     not in a pagination count/offset position, ...) DEFEATS — the clause set must be finite &
        //     statically enumerable and the tail an ordering/grouping/pagination suffix the guards can safely
        //     precede. Tail `?`s (count/offset operands) are counted here, then correlated 1:1 in step 5.
        List<Integer> appendIndices = new ArrayList<>();
        List<AppendClause> appendClauses = new ArrayList<>();
        List<Integer> tailIndices = new ArrayList<>();
        StringBuilder tail = new StringBuilder();
        int tailPlaceholderCount = 0;
        boolean inTail = false;
        for (int i = declIndex + 1; i < consuming.prepareIndex(); i++) {
            StatementTree between = body.get(i);
            TreePath betweenPath = new TreePath(blockPath, between);
            AppendClause clause = inTail ? null
                    : recognizeAppendGuard(between, builder, start.builderKind(), betweenPath);
            if (clause != null) {
                appendIndices.add(i);
                appendClauses.add(clause);
                continue;
            }
            // Not a gated append (or we are already in the tail). It must be a trailing unconditional tail
            // append `sql.append(" ORDER BY … LIMIT ?")` / `sql += " ORDER BY …"` to be admitted — a gated
            // append AFTER a tail append began is interspersed (recognizeAppendGuard is skipped once inTail,
            // so an `if (...)` here is not a clause and fails the tail-append check below → reject).
            String tailText = recognizeUnconditionalTailAppend(between, builder, start.builderKind(), betweenPath);
            // The tail fragment must be a pure ordering/grouping/pagination suffix; tailPaginationPlaceholders
            // returns the count of `?`s it carries (each PROVEN to sit in a pagination count/offset position —
            // LIMIT/OFFSET/FETCH FIRST/NEXT operand or the MySQL `LIMIT ?, ?` comma form) or REJECT (-1) for a
            // non-pagination `?` (ORDER BY ?, a FOR-UPDATE object ?), a quote/comment/`;`, a leading non-tail
            // token, or a query-reopening top-level keyword/boolean. The pagination `?`s are value binds we
            // correlate 1:1 to a strictly-trailing run of unconditional setXxx (matchCorrelatedBinds, step 5).
            int fragmentPlaceholders = tailText == null ? REJECT_TAIL : tailPaginationPlaceholders(tailText);
            if (fragmentPlaceholders == REJECT_TAIL) {
                return java.util.Optional.empty(); // a non-clause, non-(pagination tail) statement between decl/prepare.
            }
            inTail = true;
            tailIndices.add(i);
            tail.append(tailText);
            tailPlaceholderCount += fragmentPlaceholders;
        }
        if (appendClauses.isEmpty()) {
            return java.util.Optional.empty(); // no optional clauses — not the search-builder idiom.
        }
        String tailSql = tail.toString();

        // (4) The base text must be lexically clean for splicing (not inside an open quote/comment) AND its
        //     WHERE must be a GUARD-ATTACHABLE terminal top-level WHERE — because every guarded predicate is
        //     ALWAYS present (` AND (? IS NULL OR …)`) and is appended at the END of the base string, the
        //     base needs a TOP-LEVEL WHERE predicate that is the LAST clause, so the trailing ` AND` attaches
        //     to it as a boolean conjunct. This guarantees the all-null case stays valid: the original omits
        //     all clauses → the bare base (with its terminal WHERE) is valid, and our guarded form is
        //     `<base … WHERE …> AND TRUE AND …` — equally valid. We FAIL-SAFE reject when the WHERE is not
        //     top-level (only inside a subquery → ` AND` would land after a `)`/table ref) or is followed by
        //     any top-level tail clause (ORDER BY / GROUP BY / HAVING / LIMIT / OFFSET / FETCH / FOR / WINDOW
        //     / UNION / INTERSECT / EXCEPT / RETURNING) — in those cases the appended ` AND (…)` lands after
        //     the tail (e.g. `LIMIT 2 AND (…)`), malforming the all-null query that the conditional builder
        //     emits validly. A WHERE-less base is likewise rejected (no predicate context to attach to).
        if (!JdbcShapes.spliceLexicalStateIsClean(start.baseSql()) || !baseWhereIsGuardAttachable(start.baseSql())) {
            return java.util.Optional.empty();
        }

        // (5) The correlated binds: FIRST, for EACH optional clause, exactly one `if (param != null)
        //     ps.setXxx(<ord>, param)` over the consuming statement handle, binding the SAME param the clause
        //     gates on, in append order (so the Nth `?` binds the Nth clause's param). THEN, a strictly-
        //     trailing run of EXACTLY tailPlaceholderCount UNCONDITIONAL `ps.setXxx(<ord>, <expr>)` binds —
        //     the pagination value binds — correlated 1:1 in source order to the tail `?`s in TEXT order.
        //     Any unconditional bind of an optional clause's param, a missing/extra/mis-ordered clause bind,
        //     a bind of a DIFFERENT param, a count mismatch between the trailing run and the tail `?`s, or an
        //     interspersed/gated pagination bind defeats. (The guarded form is fully positional — the clauses
        //     are always present and textually first, the pagination `?`s textually last — so the runtime
        //     ordinal is NOT interpreted; correlation is by SOURCE ORDER + position, exactly as for clauses.)
        List<Integer> bindIndices = new ArrayList<>();
        List<Integer> tailBindIndices = new ArrayList<>();
        List<ExpressionTree> tailParamExprs = new ArrayList<>();
        int bindCounterDeclIndex = matchCorrelatedBinds(
                body, consuming, appendClauses, tailPlaceholderCount, blockPath,
                bindIndices, tailBindIndices, tailParamExprs);
        if (bindCounterDeclIndex == NO_BIND_MATCH) {
            return java.util.Optional.empty();
        }
        // The consuming statement handle must be bound by EXACTLY the matched binds — the correlated clause
        // binds AND the trailing pagination binds, no OTHER setXxx anywhere (a stray bind with no matching
        // clause/tail-`?`, an extra unconditional bind, a bind after the matched run). An extra bind means
        // the correlation is not 1:1, so the guarded predicate would not faithfully reproduce the source's
        // runtime ordinals. Fail-safe: refuse.
        java.util.Set<Tree> matchedBindCalls = collectMatchedBindCalls(body, bindIndices);
        matchedBindCalls.addAll(collectTailBindCalls(body, tailBindIndices));
        if (!handleBoundOnlyByMatchedBinds(consuming.statementHandle(), matchedBindCalls, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        // (6) THE USE PROOF: the builder is consumed ONLY inside the proven append guards and the consuming
        //     prepare — no other read/escape anywhere (a second prepare, a length()/append after the
        //     prepare, logging, passing it to a method, returning it). Each optional param is used ONLY by
        //     its gate null-check and its correlated bind — no value-position use elsewhere. Fail-safe.
        java.util.Set<Tree> provenBuilderStatements =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i : appendIndices) {
            provenBuilderStatements.add(body.get(i));
        }
        for (int i : tailIndices) {
            provenBuilderStatements.add(body.get(i)); // the trailing unconditional tail appends are proven uses too.
        }
        provenBuilderStatements.add(body.get(consuming.prepareIndex()));
        if (!builderConsumedOnlyByAppends(builder, provenBuilderStatements, methodBodyPath)) {
            return java.util.Optional.empty();
        }
        if (!optionalParamsUsedOnlyByGuardAndBind(appendClauses, methodBodyPath)) {
            return java.util.Optional.empty();
        }

        List<OptionalClause> clauses = new ArrayList<>();
        for (AppendClause clause : appendClauses) {
            clauses.add(new OptionalClause(clause.column(), clause.op(), clause.paramExpr(), clause.paramElement()));
        }

        List<StatementTree> elided = new ArrayList<>();
        elided.add(declStatement);
        for (int i : appendIndices) {
            elided.add(body.get(i));
        }
        for (int i : tailIndices) {
            elided.add(body.get(i));
        }
        if (bindCounterDeclIndex >= 0) {
            elided.add(body.get(bindCounterDeclIndex));
        }
        for (int i : bindIndices) {
            elided.add(body.get(i));
        }
        for (int i : tailBindIndices) {
            elided.add(body.get(i)); // the unconditional pagination binds are subsumed too.
        }

        return java.util.Optional.of(new GuardedPredicatePlan(
                declIndex,
                bindCounterDeclIndex,
                consuming.prepareIndex(),
                body.get(consuming.prepareIndex()),
                start.baseSql(),
                clauses,
                tailSql,
                tailParamExprs,
                appendIndices,
                tailIndices,
                bindIndices,
                tailBindIndices,
                elided));
    }

    // ---- (1) the base text holder -----------------------------------------------------------------

    private enum BuilderKind { STRING_BUILDER, STRING }

    private record BuilderStart(BuilderKind builderKind, String baseSql) {
    }

    /**
     * Recognizes the base-text declaration: {@code StringBuilder sql = new StringBuilder("<const>")} (or
     * {@code new StringBuilder()} with an empty base) or {@code String sql = "<const>"}. The base text
     * must be a compile-time constant (so no runtime value reaches the recovered text). Returns the
     * builder kind + the constant base SQL, or {@code null} for any other declaration shape.
     */
    private BuilderStart recognizeBuilderStart(VariableTree decl, TreePath declPath) {
        TypeMirror type = parsed.trees().getTypeMirror(declPath);
        if (type == null) {
            return null;
        }
        ExpressionTree initializer = JdbcShapes.unwrap(decl.getInitializer());
        if (initializer == null) {
            return null;
        }
        String typeName = parsed.types().erasure(type).toString();
        if ("java.lang.StringBuilder".equals(typeName) || "java.lang.StringBuffer".equals(typeName)) {
            // The declared type already resolved to StringBuilder/StringBuffer; require the initializer to
            // be a `new StringBuilder(...)` (so the base is the constructor seed, not an aliased reference).
            if (!(initializer instanceof NewClassTree newClass)
                    || !isStringBuilderConstruction(newClass)) {
                return null;
            }
            // new StringBuilder()  -> empty base; new StringBuilder("<const>") -> that constant.
            if (newClass.getArguments().isEmpty()) {
                return new BuilderStart(BuilderKind.STRING_BUILDER, "");
            }
            if (newClass.getArguments().size() != 1) {
                return null;
            }
            String base = constantStringValue(newClass.getArguments().getFirst(), declPath);
            // A new StringBuilder(int capacity) has a non-String arg -> base is null -> reject (only the
            // String-seeded / no-arg forms are the search-builder base).
            return base == null ? null : new BuilderStart(BuilderKind.STRING_BUILDER, base);
        }
        if ("java.lang.String".equals(typeName)) {
            String base = constantStringValue(initializer, declPath);
            return base == null ? null : new BuilderStart(BuilderKind.STRING, base);
        }
        return null;
    }

    /** Whether {@code newClass} is {@code new StringBuilder(...)} / {@code new StringBuffer(...)}. */
    private static boolean isStringBuilderConstruction(NewClassTree newClass) {
        ExpressionTree id = newClass.getIdentifier();
        String name = id instanceof IdentifierTree identifier ? identifier.getName().toString()
                : id instanceof MemberSelectTree select ? select.getIdentifier().toString() : null;
        return "StringBuilder".equals(name) || "StringBuffer".equals(name);
    }

    // ---- (2) the consuming prepareStatement -------------------------------------------------------

    private record ConsumingPrepare(int prepareIndex, Element statementHandle) {
    }

    /**
     * Finds the consuming {@code PreparedStatement ps = c.prepareStatement(<built text>)} after the
     * builder declaration: for a {@link BuilderKind#STRING_BUILDER} the arg must be {@code
     * sql.toString()}; for a {@link BuilderKind#STRING} it must be the {@code sql} reference itself. The
     * statement is query B — its handle binds the optional params. Returns {@code null} unless exactly the
     * recognized prepare consuming THIS builder is present.
     */
    private ConsumingPrepare findConsumingPrepare(
            List<? extends StatementTree> body, int declIndex, Element builder, BuilderKind builderKind,
            TreePath blockPath) {
        for (int i = declIndex + 1; i < body.size(); i++) {
            StatementTree statement = body.get(i);
            if (!(statement instanceof VariableTree variable)) {
                continue;
            }
            TreePath variablePath = new TreePath(blockPath, variable);
            TypeMirror type = parsed.trees().getTypeMirror(variablePath);
            if (type == null || !oracle.isPreparedStatement(type)) {
                continue;
            }
            ExpressionTree initializer = JdbcShapes.unwrap(variable.getInitializer());
            if (!(initializer instanceof MethodInvocationTree invocation)
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                    || !select.getIdentifier().contentEquals("prepareStatement")
                    || invocation.getArguments().isEmpty()) {
                continue;
            }
            if (!consumesBuiltText(invocation.getArguments().getFirst(), builder, builderKind, variablePath)) {
                continue;
            }
            Element handle = parsed.trees().getElement(variablePath);
            return handle == null ? null : new ConsumingPrepare(i, handle);
        }
        return null;
    }

    /** Whether {@code arg} is the built text of {@code builder}: {@code sql.toString()} / the {@code sql} ref. */
    private boolean consumesBuiltText(
            ExpressionTree arg, Element builder, BuilderKind builderKind, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(arg);
        if (builderKind == BuilderKind.STRING_BUILDER) {
            if (expr instanceof MethodInvocationTree invocation
                    && invocation.getMethodSelect() instanceof MemberSelectTree select
                    && select.getIdentifier().contentEquals("toString")
                    && invocation.getArguments().isEmpty()) {
                Element receiver = shapes.elementOf(select.getExpression(), path);
                return receiver != null && receiver.equals(builder);
            }
            return false;
        }
        // STRING: the built text is the String local itself.
        Element ref = shapes.elementOf(expr, path);
        return ref != null && ref.equals(builder);
    }

    // ---- (3) the optional-clause append guard -----------------------------------------------------

    private record AppendClause(
            String column, Op op, ExpressionTree paramExpr, Element paramElement) {
    }

    /**
     * Recognizes one optional-clause append guard {@code if (param != null) { sql.append(" AND <col>
     * <OP> ?"); }} (StringBuilder) or {@code if (param != null) { sql += " AND <col> <OP> ?"; }} /
     * {@code if (param != null) { sql = sql + " AND <col> <OP> ?"; }} (String). Returns the recognized
     * clause (its column, operator, and the gating param), or {@code null} for any non-canonical shape:
     * <ul>
     *   <li>the condition is not exactly {@code param != null} on a method param/local (a {@code != 0},
     *       an {@code isEmpty()}, a compound {@code &&}/{@code ||}, a {@code == null}, a {@code .equals})
     *       — anything that is not a single bare NULL-check on a single variable;</li>
     *   <li>an {@code else} branch is present;</li>
     *   <li>the then-body is not exactly one append of a CONSTANT {@code " AND <col> <OP> ?"} clause;</li>
     *   <li>the appended clause is not a single-{@code ?} comparison on a constant column (an {@code OR},
     *       two {@code ?}, a subquery, a raw fragment, an identifier hole).</li>
     * </ul>
     */
    private AppendClause recognizeAppendGuard(
            StatementTree statement, Element builder, BuilderKind builderKind, TreePath statementPath) {
        if (!(statement instanceof IfTree ifTree) || ifTree.getElseStatement() != null) {
            return null; // an else branch on an optional-clause if defeats (semantics not enumerable).
        }
        ParamNullCheck gate = recognizeParamNotNullCondition(ifTree.getCondition(), statementPath);
        if (gate == null) {
            return null;
        }
        String clauseText = soleAppendClauseText(ifTree.getThenStatement(), builder, builderKind,
                new TreePath(statementPath, ifTree));
        if (clauseText == null) {
            return null;
        }
        ClauseShape shape = parseAndClause(clauseText);
        if (shape == null) {
            return null;
        }
        return new AppendClause(shape.column(), shape.op(), gate.paramExpr(), gate.paramElement());
    }

    /**
     * Recognizes a TRAILING UNCONDITIONAL constant tail append onto {@code builder}: a bare statement {@code
     * sql.append("<const>")} (StringBuilder) or {@code sql += "<const>"} / {@code sql = sql + "<const>"}
     * (String) — NO {@code if} gate (it is part of the unconditional tail, appended for every call). Returns
     * the appended constant text (e.g. {@code " ORDER BY id DESC"}), or {@code null} for anything that is not
     * exactly one such constant append over {@code builder}: an {@code if}/loop/declaration (not a bare
     * expression statement), an append onto a different receiver, a non-constant appended text (a runtime
     * splice), a {@code String} compound-assign that is not {@code sql = sql + <const>}, a {@code
     * length()}/{@code toString()}/other method call. The text's <i>shape</i> (an ordering/grouping/
     * pagination suffix whose only {@code ?}s are count/offset operands) is then validated by {@link
     * #tailPaginationPlaceholders} — this method only proves the statement is a single constant append
     * onto the builder.
     */
    private String recognizeUnconditionalTailAppend(
            StatementTree statement, Element builder, BuilderKind builderKind, TreePath statementPath) {
        if (!(statement instanceof ExpressionStatementTree expressionStatement)) {
            return null; // an if/loop/declaration is not an unconditional tail append.
        }
        ExpressionTree expression = expressionStatement.getExpression();
        if (builderKind == BuilderKind.STRING_BUILDER) {
            return stringBuilderAppendText(expression, builder, statementPath);
        }
        return stringConcatAppendText(expression, builder, statementPath);
    }

    /**
     * The ONLY top-level keywords a constant tail append is allowed to BEGIN with — the
     * ordering / grouping / pagination / locking suffixes of an ordered or paginated builder. The tail is
     * spliced VERBATIM after the always-present guards, so it must be a genuine trailing clause that the
     * guarded WHERE precedes, never something that re-opens or extends the query: any append whose first
     * top-level word is not one of these (a {@code UNION}/{@code SELECT}/second {@code WHERE}, a leading
     * {@code AND}/{@code OR} predicate, a leading {@code ,}/{@code (}/{@code )}, an {@code INSERT}/{@code
     * UPDATE}/{@code …}) is NOT an ordering tail and falls through (fail-safe). {@code FOR} covers {@code
     * FOR UPDATE}/{@code FOR SHARE}; {@code FETCH} the ANSI {@code FETCH FIRST … ROWS}.
     */
    private static final String[] TAIL_LEADING_KEYWORDS = {
            "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "FETCH", "FOR", "WINDOW"
    };

    /**
     * The top-level keywords that must NOT appear ANYWHERE at the top level of a constant tail — they would
     * re-open or extend the query (a set operation, a second query, a second predicate context) rather than
     * order/group/paginate the SAME row set the guarded WHERE produced. A top-level {@code UNION}/{@code
     * INTERSECT}/{@code EXCEPT} starts a second SELECT (the guards would only filter the FIRST one — a
     * different, larger result); a top-level {@code WHERE} re-opens the predicate context (a second WHERE);
     * a top-level {@code SELECT} starts a second/standalone query. Encountering any of these at depth 0
     * (after the leading clause keyword) is a fail-safe reject. (DML keywords such as {@code INSERT}/{@code
     * UPDATE}/{@code DELETE} are intentionally NOT here: a second statement would need a {@code ;}, already
     * rejected by {@link #containsQuoteOrCommentOrTerminator}, and {@code UPDATE}/{@code SHARE} appear
     * legitimately as the object of the {@code FOR UPDATE}/{@code FOR SHARE} locking tail.)
     */
    private static final String[] TAIL_FORBIDDEN_TOP_LEVEL_KEYWORDS = {
            "UNION", "INTERSECT", "EXCEPT", "WHERE", "SELECT"
    };

    /** {@link #tailPaginationPlaceholders} sentinel: the fragment is NOT an admissible pagination tail. */
    private static final int REJECT_TAIL = -1;

    /**
     * The count of pagination {@code ?} placeholders {@code tailText} carries when it is an admissible
     * guard-following ordered / paginated tail (an ordered builder's {@code ORDER BY}/{@code GROUP BY}/{@code
     * LIMIT}/{@code OFFSET}/{@code FETCH}/… suffix), or {@link #REJECT_TAIL} ({@code -1}) when it is not.
     * FAIL-SAFE — the tail is spliced VERBATIM after the always-present guards ({@code <base … WHERE …> AND
     * (? IS NULL OR …)… <tail>}), so it must be a genuine trailing ordering/grouping/pagination clause that
     * the guarded WHERE simply precedes; anything that re-opens or extends the query is rejected. Returns
     * {@code -1} unless, after trimming, the text is a non-empty suffix that:
     * <ul>
     *   <li>carries NO quote / comment / {@code ;} terminator (same lexical hygiene as a clause) — a quoted
     *       literal / comment / statement terminator in the tail is unsafe to splice (it could hide a
     *       placeholder or close the statement);</li>
     *   <li><b>BEGINS</b> (at the top level, after leading whitespace) with one of {@link
     *       #TAIL_LEADING_KEYWORDS} ({@code ORDER}/{@code GROUP}/{@code HAVING}/{@code LIMIT}/{@code
     *       OFFSET}/{@code FETCH}/{@code FOR}/{@code WINDOW}). A WHITELIST, not a blacklist of the single
     *       leading token: a leading {@code AND}/{@code OR} (a predicate), a leading {@code ,}/{@code (}/{@code
     *       )} (a list/paren extension), a leading {@code UNION}/{@code SELECT}/{@code WHERE} (a second query)
     *       all FAIL — only a real ordering/grouping/pagination clause is admitted;</li>
     *   <li>contains, at the top level (paren depth 0, outside quotes), NO forbidden keyword ({@link
     *       #TAIL_FORBIDDEN_TOP_LEVEL_KEYWORDS} — {@code UNION}/{@code INTERSECT}/{@code EXCEPT}/{@code
     *       WHERE}/{@code SELECT}/…) and NO top-level boolean connective {@code AND}/{@code OR}. This catches
     *       a clause that STARTS with a whitelisted keyword but then re-opens or branches the query — {@code
     *       ORDER BY id UNION SELECT …}, {@code GROUP BY tier HAVING count(*) > 0 OR tier = 9} (the {@code
     *       HAVING} body's top-level {@code OR}), {@code LIMIT 5 OR 1=1} (the {@code OR} that parses as {@code
     *       LIMIT (5 OR 1=1)}). A boolean connective at depth 0 in the tail is conservatively a non-ordering
     *       construct — fall through;</li>
     *   <li>contains a {@code ?} ONLY in a pagination COUNT/OFFSET position — the operand of {@code LIMIT
     *       <?>}, {@code OFFSET <?>}, the MySQL {@code LIMIT <?>, <?>} comma form, or {@code FETCH FIRST/NEXT
     *       <?> ROWS ONLY}. A {@code ?} ANYWHERE ELSE (an {@code ORDER BY ?} / {@code GROUP BY ?} no-op sort,
     *       the object of {@code FOR UPDATE ?}/{@code FOR SHARE ?}) is REJECTED — it is not a safe value bind
     *       in the pagination sense (a bound constant is a no-op sort, not pagination). Each pagination {@code
     *       ?} is value-bound (the recognizer correlates it to an unconditional {@code setXxx}); the count is
     *       returned so step 5 correlates EXACTLY that many trailing unconditional binds.</li>
     * </ul>
     * Everything admitted here is appended VERBATIM after the guards, so it must be exactly what the
     * conditional builder appended unconditionally — the guarded WHERE returns the same rows; the tail then
     * applies the same ordering/grouping/pagination. (An {@code ORDER BY} on a NULLABLE column may order NULLs
     * per the dialect default — PG {@code NULLS LAST}, MySQL {@code NULLS FIRST} — exactly as the original
     * JDBC {@code ORDER BY} would; that is faithful reproduction. The pagination forms are single-dialect-
     * native: a {@code LIMIT ?, ?} lands in a MySQL procedure, a {@code FETCH FIRST ? ROWS ONLY} in a PG one.)
     */
    static int tailPaginationPlaceholders(String tailText) {
        if (tailText == null) {
            return REJECT_TAIL;
        }
        String trimmed = tailText.strip();
        if (trimmed.isEmpty()) {
            return REJECT_TAIL; // an empty / whitespace-only append carries no tail.
        }
        if (containsQuoteOrCommentOrTerminator(trimmed)) {
            return REJECT_TAIL; // a quoted literal / comment / `;` in the tail is unsafe to splice.
        }
        return tailOrderingClausePlaceholderCount(trimmed.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * The pagination-{@code ?} count of the (already quote/comment/{@code ;}-free, upper-cased) tail {@code
     * upper} when it is a pure ordering / grouping / pagination clause, or {@link #REJECT_TAIL}. The tail
     * BEGINS with a {@link #TAIL_LEADING_KEYWORDS whitelisted leading keyword}, contains at the top level
     * (paren depth 0, outside quoted regions) no {@link #TAIL_FORBIDDEN_TOP_LEVEL_KEYWORDS forbidden keyword}
     * and no boolean connective {@code AND}/{@code OR}, and every top-level {@code ?} sits in a pagination
     * count/offset position. Parser-free, paren-depth + quote + previous-token aware — the same lexical
     * scanner family {@link #baseWhereIsGuardAttachable} uses, so a keyword spelled inside a quoted
     * identifier/literal or a parenthesised sub-expression (e.g. an {@code IN (SELECT …)} list under an
     * {@code ORDER BY}) never trips the top-level checks.
     *
     * <p>A top-level {@code ?} is admitted ONLY when the immediately-preceding top-level significant token is
     * a pagination introducer: {@code LIMIT} ({@code LIMIT ?}, the first {@code ?} of {@code LIMIT ? OFFSET ?}
     * / {@code LIMIT ?, ?}), {@code OFFSET} ({@code OFFSET ?}, {@code OFFSET ? ROWS}), {@code FIRST}/{@code
     * NEXT} ({@code FETCH FIRST/NEXT ? ROWS ONLY}), or a {@code ,} WHILE inside a {@code LIMIT} clause (the
     * MySQL {@code LIMIT ?, ?} comma's second operand). A {@code ?} after {@code BY} ({@code ORDER BY ?} /
     * {@code GROUP BY ?}) or after a {@code FOR}-locking object is NOT a pagination operand → reject. Tracks
     * the most-recent top-level clause keyword so the comma form only counts under {@code LIMIT}, never under
     * {@code ORDER BY a, ?}. (A {@code ?} inside a paren (depth &gt; 0) is also rejected — pagination operands
     * are never parenthesised in the supported native forms; a {@code ?} in a sub-expression is unexpected.)
     */
    private static int tailOrderingClausePlaceholderCount(String upper) {
        int depth = 0;
        int i = 0;
        int n = upper.length();
        int placeholderCount = 0;
        boolean sawLeadingKeyword = false;
        String prevTopLevelToken = null; // the previous significant top-level token (a word, or "," / etc.).
        String currentClauseKeyword = null; // the most-recent top-level clause keyword (LIMIT/OFFSET/ORDER/…).
        int limitClauseCommaCount = 0; // top-level commas seen since the CURRENT LIMIT clause keyword.
        while (i < n) {
            char c = upper.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(upper, i, c);
                continue;
            }
            // Until the leading ordering keyword is seen, the FIRST top-level non-whitespace token MUST be the
            // start of a word keyword. A leading `(` / `)` / `,` / `?` / operator means the tail does not BEGIN
            // with an ordering/grouping clause (it extends a list, re-opens/re-closes a paren, or is a bare
            // placeholder) — fail-safe reject. (This precedes the paren/`?` handling so a leading one cannot
            // slip past it.)
            if (depth == 0 && !sawLeadingKeyword && !Character.isWhitespace(c) && !isKeywordAt(upper, i)) {
                return REJECT_TAIL;
            }
            if (c == '?') {
                // A pagination `?` is admitted ONLY at the top level, immediately after a pagination introducer
                // (the previous significant top-level token is LIMIT / OFFSET / FIRST / NEXT, or a `,` while in
                // a LIMIT clause). Anywhere else (depth > 0, after BY, after a FOR object, …) → reject.
                boolean afterPaginationKeyword = "LIMIT".equals(prevTopLevelToken)
                        || "OFFSET".equals(prevTopLevelToken)
                        || "FIRST".equals(prevTopLevelToken)
                        || "NEXT".equals(prevTopLevelToken);
                // The MySQL comma form is `LIMIT offset, count` — EXACTLY ONE comma. A `?` after a SECOND (or
                // later) comma under LIMIT (`LIMIT a, b, ?`) is a malformed 3+-operand LIMIT (a syntax error in
                // BOTH engines) — reject so we never emit a non-deployable artifact (fail-safe, not malformed).
                boolean afterLimitComma = ",".equals(prevTopLevelToken)
                        && "LIMIT".equals(currentClauseKeyword) && limitClauseCommaCount == 1;
                if (depth != 0 || !(afterPaginationKeyword || afterLimitComma)) {
                    return REJECT_TAIL; // a `?` that is not a pagination count/offset operand.
                }
                placeholderCount++;
                prevTopLevelToken = "?";
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (c == ',') {
                if (depth == 0) {
                    prevTopLevelToken = ",";
                    if ("LIMIT".equals(currentClauseKeyword)) {
                        limitClauseCommaCount++; // count commas under the current LIMIT clause (cap form is 1).
                    }
                }
                i++;
                continue;
            }
            if (isKeywordAt(upper, i)) {
                int wordLen = keywordLengthAt(upper, i);
                String word = upper.substring(i, i + wordLen);
                if (depth == 0) {
                    if (!sawLeadingKeyword) {
                        // The FIRST top-level word must be a whitelisted ordering/grouping/pagination keyword.
                        if (!isTailLeadingKeyword(word)) {
                            return REJECT_TAIL;
                        }
                        sawLeadingKeyword = true;
                    } else if (isTailForbiddenTopLevelKeyword(word)
                            || word.equals("AND") || word.equals("OR")) {
                        // A second query / set-op / predicate context, or a top-level boolean connective —
                        // not a pure ordering/grouping tail. Fail-safe reject.
                        return REJECT_TAIL;
                    }
                    // Track the most-recent top-level clause keyword so the `LIMIT ?, ?` comma form's second
                    // operand counts only under LIMIT (a `,` under ORDER BY does not introduce a pagination ?).
                    // Reset the per-LIMIT comma count at every clause boundary so an ORDER BY's commas never
                    // bleed into a following LIMIT, and a fresh LIMIT clause starts its single-comma budget clean.
                    if (isTailLeadingKeyword(word)) {
                        currentClauseKeyword = word;
                        limitClauseCommaCount = 0;
                    }
                    prevTopLevelToken = word;
                }
                i += wordLen;
                continue;
            }
            // Any other top-level char (an operator, `=`, etc.) is a significant token that BREAKS the
            // pagination-keyword→`?` adjacency: clear prevTopLevelToken so a `?` is admitted ONLY when the
            // pagination keyword (or LIMIT-comma) IMMEDIATELY precedes it (modulo whitespace). This keeps a
            // contrived `LIMIT = ?` from counting the `?` as a LIMIT operand (fail-safe tightening).
            if (depth == 0 && !Character.isWhitespace(c)) {
                prevTopLevelToken = null;
            }
            i++;
        }
        return sawLeadingKeyword ? placeholderCount : REJECT_TAIL;
    }

    private static boolean isTailLeadingKeyword(String upperWord) {
        for (String keyword : TAIL_LEADING_KEYWORDS) {
            if (keyword.equals(upperWord)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTailForbiddenTopLevelKeyword(String upperWord) {
        for (String keyword : TAIL_FORBIDDEN_TOP_LEVEL_KEYWORDS) {
            if (keyword.equals(upperWord)) {
                return true;
            }
        }
        return false;
    }

    private record ParamNullCheck(ExpressionTree paramExpr, Element paramElement) {
    }

    /**
     * Recognizes a condition that is <b>exactly</b> {@code param != null} where {@code param} is a method
     * parameter or local variable (not a field, not an expression). Returns the param expression +
     * element, or {@code null} for anything else — a {@code null != param} (reversed) is also accepted
     * (same meaning); a {@code param == null}, a {@code param != other}, a compound condition, a method
     * call, or a non-variable operand defeats. This is the fail-safe gate discriminator: only a single
     * bare NULL-check maps to {@code (p IS NULL OR …)}.
     */
    private ParamNullCheck recognizeParamNotNullCondition(ExpressionTree condition, TreePath path) {
        ExpressionTree expr = JdbcShapes.unwrap(condition);
        if (!(expr instanceof BinaryTree binary) || binary.getKind() != Tree.Kind.NOT_EQUAL_TO) {
            return null;
        }
        ExpressionTree left = JdbcShapes.unwrap(binary.getLeftOperand());
        ExpressionTree right = JdbcShapes.unwrap(binary.getRightOperand());
        ExpressionTree paramSide;
        if (isNullLiteral(right)) {
            paramSide = left;
        } else if (isNullLiteral(left)) {
            paramSide = right;
        } else {
            return null; // not a `x != null` shape.
        }
        Element element = shapes.elementOf(paramSide, path);
        if (element == null || !isParamOrLocal(element)) {
            return null;
        }
        // The param side must be a plain variable reference (identifier/this.field is rejected — a field
        // is not a routine param; a method call / arithmetic is not a bindable gate). Only an IdentifierTree.
        if (!(paramSide instanceof IdentifierTree)) {
            return null;
        }
        return new ParamNullCheck(paramSide, element);
    }

    /**
     * The constant clause text of the then-body when it is exactly one append onto {@code builder}:
     * {@code sql.append("<const>")} (StringBuilder) or {@code sql += "<const>"} / {@code sql = sql +
     * "<const>"} (String). Returns the appended constant, or {@code null} for any other body (more than
     * one statement, a non-append statement, a non-constant appended text, an append onto a different
     * receiver, or a {@code String} compound-assign that is not {@code sql = sql + <const>}).
     */
    private String soleAppendClauseText(
            StatementTree thenStatement, Element builder, BuilderKind builderKind, TreePath ifPath) {
        StatementTree only = thenStatement;
        TreePath bodyPath = ifPath;
        if (thenStatement instanceof BlockTree block) {
            if (block.getStatements().size() != 1) {
                return null; // the guard body must do nothing but the single append.
            }
            only = block.getStatements().getFirst();
            bodyPath = new TreePath(ifPath, block);
        }
        if (!(only instanceof ExpressionStatementTree expressionStatement)) {
            return null;
        }
        TreePath statementPath = new TreePath(bodyPath, only);
        ExpressionTree expression = expressionStatement.getExpression();
        if (builderKind == BuilderKind.STRING_BUILDER) {
            return stringBuilderAppendText(expression, builder, statementPath);
        }
        return stringConcatAppendText(expression, builder, statementPath);
    }

    /** The constant text of {@code sql.append("<const>")} over {@code builder}, or {@code null}. */
    private String stringBuilderAppendText(ExpressionTree expression, Element builder, TreePath path) {
        if (!(expression instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !select.getIdentifier().contentEquals("append")
                || invocation.getArguments().size() != 1) {
            return null;
        }
        Element receiver = shapes.elementOf(select.getExpression(), path);
        if (receiver == null || !receiver.equals(builder)) {
            return null;
        }
        return constantStringValue(invocation.getArguments().getFirst(), path);
    }

    /**
     * The constant text of a String-concat append over {@code builder}: {@code sql += "<const>"} (a
     * {@code PLUS_ASSIGNMENT}) or {@code sql = sql + "<const>"} (an assignment of {@code sql + const}),
     * or {@code null}. Both forms append a constant suffix to {@code sql}; any other LHS/RHS shape
     * defeats.
     */
    private String stringConcatAppendText(ExpressionTree expression, Element builder, TreePath path) {
        if (expression instanceof CompoundAssignmentTree compound
                && compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT) {
            Element target = shapes.elementOf(JdbcShapes.unwrap(compound.getVariable()), path);
            if (target == null || !target.equals(builder)) {
                return null;
            }
            return constantStringValue(compound.getExpression(), path);
        }
        if (expression instanceof AssignmentTree assignment) {
            Element target = shapes.elementOf(JdbcShapes.unwrap(assignment.getVariable()), path);
            if (target == null || !target.equals(builder)) {
                return null;
            }
            // RHS must be `sql + "<const>"` (the self-append form). The left operand of the + must be the
            // builder itself; the right operand the constant clause.
            ExpressionTree rhs = JdbcShapes.unwrap(assignment.getExpression());
            if (!(rhs instanceof BinaryTree binary) || binary.getKind() != Tree.Kind.PLUS) {
                return null;
            }
            Element leftRef = shapes.elementOf(JdbcShapes.unwrap(binary.getLeftOperand()), path);
            if (leftRef == null || !leftRef.equals(builder)) {
                return null;
            }
            return constantStringValue(binary.getRightOperand(), path);
        }
        return null;
    }

    private record ClauseShape(String column, Op op) {
    }

    /**
     * Parses an optional-clause constant {@code " AND <col> <OP> ?"} into its column + operator. FAIL-SAFE
     * — returns {@code null} unless the text is <b>exactly</b> a leading {@code AND}, a single constant
     * column identifier, a supported comparison operator, and a single trailing {@code ?} (no second
     * {@code ?}, no {@code OR}, no subquery {@code (}, no raw fragment, no extra tokens, no string
     * literal). The single source of truth for "this clause maps to exactly one guarded predicate".
     */
    static ClauseShape parseAndClause(String clauseText) {
        if (clauseText == null) {
            return null;
        }
        String trimmed = clauseText.strip();
        // A trailing semicolon / comment / open quote anywhere makes the clause unsafe to nest — reject.
        if (containsQuoteOrCommentOrTerminator(trimmed)) {
            return null;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!startsWithWord(upper, "AND")) {
            return null;
        }
        String afterAnd = trimmed.substring(3).strip();
        if (afterAnd.isEmpty()) {
            return null;
        }
        // The clause must end with a single trailing `?` and contain exactly one `?` total.
        if (countChar(afterAnd, '?') != 1 || afterAnd.charAt(afterAnd.length() - 1) != '?') {
            return null;
        }
        // No parenthesis (a subquery / function / grouped predicate is not a single bound comparison).
        if (afterAnd.indexOf('(') >= 0 || afterAnd.indexOf(')') >= 0) {
            return null;
        }
        // No comma (a multi-column / IN-list form is not a single guarded predicate).
        if (afterAnd.indexOf(',') >= 0) {
            return null;
        }
        String betweenColAndQmark = afterAnd.substring(0, afterAnd.length() - 1).strip();
        // Split `<col> <OP>` — the operator is the LAST operator token; the column is everything before.
        // Recognize the operator by scanning the supported set, longest-first (so `<=` beats `<`).
        OpMatch match = matchTrailingOperator(betweenColAndQmark);
        if (match == null) {
            return null;
        }
        String column = match.beforeOp().strip();
        if (!isSingleColumnIdentifier(column)) {
            return null;
        }
        return new ClauseShape(column, match.op());
    }

    private record OpMatch(Op op, String beforeOp) {
    }

    /**
     * Matches the trailing comparison operator of {@code text} (the part between the column and the
     * {@code ?}). Recognizes {@code =}, {@code <>}, {@code !=}, {@code <=}, {@code >=}, {@code <},
     * {@code >} (symbol operators must be the immediate trailing token) and the word operator {@code LIKE}
     * (whole-word, case-insensitive). Symbol operators are tried longest-first so {@code <=} is not
     * misread as {@code <}. Returns the operator + the text before it (the column), or {@code null}.
     */
    private static OpMatch matchTrailingOperator(String text) {
        String trimmed = text.stripTrailing();
        // Word operator: LIKE / NOT LIKE? -> only plain LIKE is supported (NOT LIKE has different NULL
        // semantics under the guard for some rows; conservatively reject). Whole-word at the end.
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (endsWithWord(upper, "LIKE")) {
            String before = trimmed.substring(0, trimmed.length() - "LIKE".length()).stripTrailing();
            // Reject `NOT LIKE` (and any qualified form): the column must be the only thing before LIKE.
            if (before.toUpperCase(java.util.Locale.ROOT).endsWith("NOT")) {
                return null;
            }
            return new OpMatch(Op.LIKE, before);
        }
        // Symbol operators, longest-first.
        String[][] symbols = {{"<>", "NE"}, {"!=", "NE"}, {"<=", "LE"}, {">=", "GE"},
                {"=", "EQ"}, {"<", "LT"}, {">", "GT"}};
        for (String[] sym : symbols) {
            if (trimmed.endsWith(sym[0])) {
                String before = trimmed.substring(0, trimmed.length() - sym[0].length()).stripTrailing();
                return new OpMatch(Op.valueOf(sym[1]), before);
            }
        }
        return null;
    }

    /** Whether {@code column} is a single bare column identifier (letters/digits/_/. — possibly qualified). */
    private static boolean isSingleColumnIdentifier(String column) {
        if (column.isEmpty()) {
            return false;
        }
        for (int i = 0; i < column.length(); i++) {
            char c = column.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) {
                return false; // whitespace / operator / quote inside the column -> not a single identifier.
            }
        }
        // The first char must be a letter / _ (not a digit / dot) — a real column name.
        char first = column.charAt(0);
        return Character.isLetter(first) || first == '_';
    }

    // ---- (5) the correlated binds -----------------------------------------------------------------

    private static final int NO_BIND_MATCH = Integer.MIN_VALUE;

    /**
     * Matches each optional clause's correlated bind {@code if (param != null) ps.setXxx(<ord>, param)}
     * over the consuming statement handle, in the SAME order as the appends, THEN a strictly-trailing run
     * of EXACTLY {@code tailPlaceholderCount} UNCONDITIONAL pagination binds {@code ps.setXxx(<ord>, <expr>)}
     * over the SAME handle. Recognizes an optional {@code int i = 1} bind-counter declaration (returned as
     * the index to elide, or {@code -1} when absent) before the binds, requires each clause bind guard to
     * gate on (and bind) the SAME param as the corresponding append clause, and requires the pagination
     * binds to be UNCONDITIONAL (bare {@code ps.setXxx}, no {@code if} gate), contiguous, and immediately
     * after the clause binds. Populates {@code bindIndices} (clause binds, append order), {@code
     * tailBindIndices} and {@code tailParamExprs} (pagination binds, source order = tail-{@code ?} text
     * order), and returns the bind-counter decl index, or {@link #NO_BIND_MATCH} when the binds do not line
     * up exactly (a missing/extra/mis-ordered clause bind, a clause bind of a different param, an
     * unconditional clause bind, a GATED pagination bind {@code if (x != null) ps.setInt(i, x)} — NOT the
     * unconditional tail shape, a count mismatch between the trailing run and the tail {@code ?}s, a bind
     * over a different statement handle).
     *
     * <p>The bind ordinal ({@code i++} / a literal / {@code i}) is INTENTIONALLY not interpreted — the
     * guarded-predicate lowering produces its own static text with its own bind order (each clause param
     * bound twice in clause order, then each pagination param once in tail-{@code ?} order), and the guarded
     * form is fully POSITIONAL (the clauses are always present, the pagination {@code ?}s always last), so
     * the runtime counter is pure scaffolding. The SAME reasoning that lets us correlate the clause binds by
     * source order + gating param extends to the pagination binds: correlate by SOURCE ORDER + position, not
     * by interpreting the literal ordinal. (A literal-ordinal pagination bind mixed with optional filters —
     * {@code ps.setInt(3, pageSize)} when the fired-filter count varies — cannot be proven correct, so the
     * source-order/contiguity requirement here is what makes the trailing-run reproduction sound.)</p>
     */
    private int matchCorrelatedBinds(
            List<? extends StatementTree> body, ConsumingPrepare consuming, List<AppendClause> appendClauses,
            int tailPlaceholderCount, TreePath blockPath, List<Integer> bindIndices,
            List<Integer> tailBindIndices, List<ExpressionTree> tailParamExprs) {
        int cursor = consuming.prepareIndex() + 1;
        // An optional `int i = 1;` (or `int i = 0;`) bind counter immediately after the prepare.
        int bindCounterDeclIndex = -1;
        if (cursor < body.size() && isBindCounterDeclaration(body.get(cursor), blockPath)) {
            bindCounterDeclIndex = cursor;
            cursor++;
        }
        // Then exactly one GATED bind guard per clause, in order.
        for (AppendClause clause : appendClauses) {
            if (cursor >= body.size()) {
                return NO_BIND_MATCH; // a missing bind for this clause.
            }
            if (!isCorrelatedBindGuard(body.get(cursor), consuming.statementHandle(), clause, blockPath)) {
                return NO_BIND_MATCH; // a non-matching / mis-ordered / different-param bind.
            }
            bindIndices.add(cursor);
            cursor++;
        }
        // THEN exactly tailPlaceholderCount UNCONDITIONAL pagination binds, contiguous, in tail-`?` text order.
        // A GATED bind here (`if (x != null) ps.setInt(i, x)`) is NOT the unconditional-tail shape — it fails
        // the bare-statement check in unconditionalBindParamExpr → NO_BIND_MATCH (fall through). A count
        // mismatch (the run runs out, or there are extra trailing binds caught later by
        // handleBoundOnlyByMatchedBinds) likewise refuses.
        for (int p = 0; p < tailPlaceholderCount; p++) {
            if (cursor >= body.size()) {
                return NO_BIND_MATCH; // fewer unconditional binds than tail `?`s.
            }
            ExpressionTree paramExpr =
                    unconditionalBindParamExpr(body.get(cursor), consuming.statementHandle(), blockPath);
            if (paramExpr == null) {
                return NO_BIND_MATCH; // not an unconditional setXxx over the handle (a gate, a different handle, …).
            }
            tailBindIndices.add(cursor);
            tailParamExprs.add(paramExpr);
            cursor++;
        }
        return bindCounterDeclIndex;
    }

    /**
     * The bound VALUE expression (the 2nd arg) of an UNCONDITIONAL pagination bind {@code ps.setXxx(<ord>,
     * <expr>)} — a BARE expression statement (NO {@code if} gate), a {@code setXxx(ordinal, value)} call over
     * {@code statementHandle} whose ORDINAL is the self-adjusting bind counter (a unary increment {@code i++}/
     * {@code ++i} of a local, or a bare local-variable reference {@code i}) — or {@code null} for anything else
     * (a gated bind {@code if (…) ps.setXxx(…)}, a non-{@code set} call, a call over a different handle, a
     * wrong-arity call, a declaration/loop, OR a FIXED-ordinal bind).
     *
     * <p>Why a FIXED ordinal defeats (the named fail-safe case): an UNCONDITIONAL pagination bind follows a
     * VARYING number of GATED clause binds, so the runtime ordinal of its {@code ?} depends on how many
     * filters fired. The self-adjusting counter form ({@code ps.setInt(i++, pageSize)}) is provably correct —
     * it lands on the textually-last {@code ?} for every fired-filter count — but any FIXED ordinal that does
     * not move with the fired-filter count is correct only for ONE specific count, and is a latent source bug
     * for the others (the original would throw an out-of-range ordinal). We refuse to "bless" it by reproducing
     * one interpretation. The fixed forms ALL slip through a mere "not a bare int literal" probe yet are
     * runtime-constant ordinals, so we require the POSITIVE counter shape instead: a bare int LITERAL ({@code
     * 3}), a compile-time constant EXPRESSION ({@code 1 + 2}), and a named constant ({@code POS}, a {@code
     * static final int} — a field/non-local identifier) are ALL rejected; only {@code i++}/{@code ++i}/a plain
     * local-int reference is admitted. (This does NOT interpret the ordinal's VALUE for correlation —
     * correlation is by SOURCE ORDER + tail-{@code ?} position — it admits only the FORM that is provably
     * count-tracking; contrast the GATED clause binds, where a fixed ordinal IS sound because the bind fires
     * iff its clause does.) The pagination operand itself (the 2nd arg) may be any expression (a parameter
     * {@code offset}, a local, a literal {@code 10}); it is captured verbatim and lowered to a bind via the
     * same {@code bindNameFor} seam the clause binds use.
     */
    private ExpressionTree unconditionalBindParamExpr(
            StatementTree statement, Element statementHandle, TreePath blockPath) {
        if (!(statement instanceof ExpressionStatementTree expressionStatement)) {
            return null; // an `if`-gated bind, a declaration, or a loop is not the unconditional tail shape.
        }
        if (!(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return null;
        }
        TreePath statementPath = new TreePath(blockPath, statement);
        if (!select.getIdentifier().toString().startsWith("set") || invocation.getArguments().size() != 2) {
            return null; // a JDBC setXxx(ordinal, value) bind exactly.
        }
        Element receiver = shapes.elementOf(select.getExpression(), statementPath);
        if (receiver == null || !receiver.equals(statementHandle)) {
            return null; // a bind over a different statement handle.
        }
        // The ordinal on an UNCONDITIONAL bind must be the self-adjusting counter (`i++`/`++i`/`i`) — only that
        // tracks the varying fired-filter count (see method doc). A FIXED ordinal (a literal `3`, a const
        // expression `1 + 2`, a named `static final int POS`) cannot be proven correct across counts → refuse.
        if (!ordinalIsSelfAdjustingCounter(invocation.getArguments().getFirst(), statementPath)) {
            return null;
        }
        return invocation.getArguments().get(1); // the pagination value (captured for bindNameFor).
    }

    /**
     * Whether {@code ordinal} (the 1st arg of a {@code setXxx}) is the self-adjusting bind counter — a unary
     * increment {@code i++}/{@code ++i} of a local variable, or a bare local-variable reference {@code i}.
     * Any FIXED ordinal that does not move with the fired-filter count is rejected: a bare int literal, a
     * compile-time constant expression ({@code 1 + 2}, any {@code BinaryTree}), and a named/qualified constant
     * ({@code POS} / {@code Cls.POS} — a field or non-local identifier, which resolves to a non-local element).
     * This is the positive-whitelist replacement for the old "not a bare int literal" probe, which leaked
     * constant-expression and named-{@code static final} ordinals (both runtime-fixed). FAIL-SAFE: anything not
     * provably the counter form is refused.
     */
    private boolean ordinalIsSelfAdjustingCounter(ExpressionTree ordinal, TreePath statementPath) {
        ExpressionTree expr = JdbcShapes.unwrap(ordinal);
        if (expr instanceof UnaryTree unary
                && (unary.getKind() == Tree.Kind.POSTFIX_INCREMENT
                        || unary.getKind() == Tree.Kind.PREFIX_INCREMENT)) {
            expr = JdbcShapes.unwrap(unary.getExpression()); // the `i` operand of `i++` / `++i`.
        }
        // The remaining expression must be a bare local-variable reference (the counter `i`). A literal, a
        // BinaryTree (`1 + 2`), a method call, or a field/qualified name (`POS` / `Cls.POS`) all fail: an
        // identifier that resolves to a non-LOCAL element (a field/constant) is NOT the counter.
        if (!(expr instanceof IdentifierTree)) {
            return false;
        }
        Element element = shapes.elementOf(expr, statementPath);
        return element != null && isLocalVariable(element);
    }

    /**
     * The {@code setXxx} call expressions of the unconditional pagination binds (by tree identity) — added to
     * the allowed-binds set so {@link #handleBoundOnlyByMatchedBinds} admits them (alongside the clause binds)
     * and still defeats any OTHER {@code setXxx} over the handle.
     */
    private java.util.Set<Tree> collectTailBindCalls(
            List<? extends StatementTree> body, List<Integer> tailBindIndices) {
        java.util.Set<Tree> calls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int index : tailBindIndices) {
            if (body.get(index) instanceof ExpressionStatementTree expressionStatement
                    && expressionStatement.getExpression() instanceof MethodInvocationTree invocation) {
                calls.add(invocation);
            }
        }
        return calls;
    }

    /**
     * The {@code setXxx} call expressions of the matched correlated bind guards (by tree identity) — the
     * single allowed binds over the consuming statement handle. Collected so {@link
     * #handleBoundOnlyByMatchedBinds} can defeat any OTHER {@code setXxx} over the handle.
     */
    private java.util.Set<Tree> collectMatchedBindCalls(
            List<? extends StatementTree> body, List<Integer> bindIndices) {
        java.util.Set<Tree> calls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int index : bindIndices) {
            StatementTree statement = body.get(index);
            if (!(statement instanceof IfTree ifTree)) {
                continue;
            }
            StatementTree only = ifTree.getThenStatement();
            if (only instanceof BlockTree block && block.getStatements().size() == 1) {
                only = block.getStatements().getFirst();
            }
            if (only instanceof ExpressionStatementTree expressionStatement
                    && expressionStatement.getExpression() instanceof MethodInvocationTree invocation) {
                calls.add(invocation);
            }
        }
        return calls;
    }

    /**
     * Proves the consuming statement {@code handle} is bound by <b>only</b> the matched correlated bind
     * calls — refuses (returns {@code false}) on any OTHER {@code handle.setXxx(...)} anywhere in the
     * method (an extra gated bind with no matching clause, an unconditional bind, a bind after the matched
     * run, a {@code clearParameters}/re-bind). This guarantees the bind/clause correlation is 1:1, so the
     * static guarded predicate faithfully reproduces the source's per-param binds. Fail-safe: in doubt
     * (any unrecognized {@code setXxx} on the handle), refuse.
     */
    private boolean handleBoundOnlyByMatchedBinds(
            Element handle, java.util.Set<Tree> matchedBindCalls, TreePath methodBodyPath) {
        boolean[] defeated = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree select
                        && select.getIdentifier().toString().startsWith("set")) {
                    Element receiver = parsed.trees().getElement(
                            new TreePath(getCurrentPath(), select.getExpression()));
                    if (receiver != null && receiver.equals(handle) && !matchedBindCalls.contains(node)) {
                        defeated[0] = true; // an extra setXxx over the consuming handle.
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(methodBodyPath, null);
        return !defeated[0];
    }

    /** Whether {@code statement} is an {@code int i = <0|1>} bind-counter declaration (a local int seed). */
    private boolean isBindCounterDeclaration(StatementTree statement, TreePath blockPath) {
        if (!(statement instanceof VariableTree variable)) {
            return false;
        }
        TreePath path = new TreePath(blockPath, variable);
        TypeMirror type = parsed.trees().getTypeMirror(path);
        if (type == null || type.getKind() != javax.lang.model.type.TypeKind.INT) {
            return false;
        }
        Integer seed = JdbcShapes.constantIntValue(variable.getInitializer());
        return seed != null && (seed == 0 || seed == 1);
    }

    /**
     * Whether {@code statement} is the correlated bind guard {@code if (param != null) ps.setXxx(<ord>,
     * param)} over {@code statementHandle}, gating on and binding {@code clause.paramElement()}. The
     * gate must be {@code clause.param != null}; the then-body must be exactly one {@code
     * statementHandle.setXxx(<anyOrdinal>, <clause.param>)} call. Returns {@code false} otherwise (an
     * else branch, a non-null-check, a different param gated/bound, a different statement handle, a
     * multi-statement body).
     */
    private boolean isCorrelatedBindGuard(
            StatementTree statement, Element statementHandle, AppendClause clause, TreePath blockPath) {
        if (!(statement instanceof IfTree ifTree) || ifTree.getElseStatement() != null) {
            return false;
        }
        TreePath ifPath = new TreePath(blockPath, ifTree);
        ParamNullCheck gate = recognizeParamNotNullCondition(ifTree.getCondition(), ifPath);
        if (gate == null || !gate.paramElement().equals(clause.paramElement())) {
            return false; // the bind must gate on the SAME param as its clause.
        }
        StatementTree only = ifTree.getThenStatement();
        TreePath bodyPath = ifPath;
        if (only instanceof BlockTree block) {
            if (block.getStatements().size() != 1) {
                return false;
            }
            only = block.getStatements().getFirst();
            bodyPath = new TreePath(ifPath, block);
        }
        if (!(only instanceof ExpressionStatementTree expressionStatement)
                || !(expressionStatement.getExpression() instanceof MethodInvocationTree invocation)
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
            return false;
        }
        TreePath statementPath = new TreePath(bodyPath, only);
        if (!select.getIdentifier().toString().startsWith("set") || invocation.getArguments().size() != 2) {
            return false; // a JDBC setXxx(ordinal, value) bind exactly.
        }
        Element receiver = shapes.elementOf(select.getExpression(), statementPath);
        if (receiver == null || !receiver.equals(statementHandle)) {
            return false; // a bind over a different statement handle.
        }
        // The bound VALUE (2nd arg) must be the SAME param the gate null-checks (so the `?` binds the
        // param the guard guards). A bind of a different value defeats — the guarded predicate would
        // otherwise compare the wrong value.
        Element boundValue = shapes.elementOf(JdbcShapes.unwrap(invocation.getArguments().get(1)), statementPath);
        return boundValue != null && boundValue.equals(clause.paramElement());
    }

    // ---- (6) the use proofs -----------------------------------------------------------------------

    /**
     * Proves the {@code builder} is consumed <b>only</b> within the {@code provenBuilderStatements} (the
     * proven append guards + the consuming prepare). Scans the whole method body and refuses (returns
     * {@code false}) on any reference to {@code builder} whose path does NOT pass through one of those
     * proven statements — a second prepare over it, a {@code length()}/{@code append} after the prepare,
     * logging, passing it to a method, returning it, any read/escape. Fail-safe: a builder mention outside
     * the proven statements is a defeating use. (The builder's own declaration name is a {@code Name}, not
     * an {@code IdentifierTree}, so the declaration never reaches this scan.)
     */
    private boolean builderConsumedOnlyByAppends(
            Element builder, java.util.Set<Tree> provenBuilderStatements, TreePath methodBodyPath) {
        boolean[] defeated = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element != null && element.equals(builder) && !pathPassesThrough(getCurrentPath())) {
                    defeated[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            /** Whether {@code path} (a builder reference) lies inside one of the proven builder statements. */
            private boolean pathPassesThrough(TreePath path) {
                for (TreePath p = path; p != null; p = p.getParentPath()) {
                    if (provenBuilderStatements.contains(p.getLeaf())) {
                        return true;
                    }
                }
                return false;
            }
        }.scan(methodBodyPath, null);
        return !defeated[0];
    }

    /**
     * Proves each optional param is used <b>only</b> by its gate null-check and its correlated bind — no
     * value-position use elsewhere in the method. Scans the whole method and refuses (returns {@code
     * false}) on any reference to a clause param other than inside a proven append guard's condition or
     * its correlated bind guard. A param used in a value position elsewhere (e.g. read into a computed
     * column, logged, returned, passed to another method, or bound a second time) means the guarded
     * predicate would not faithfully reproduce the source — defeat (fail-safe). The gate/bind references
     * are recognized structurally: a param identifier is admitted only when it is one operand of a
     * {@code != null} comparison or the 2nd argument of a {@code setXxx} call.
     */
    private boolean optionalParamsUsedOnlyByGuardAndBind(
            List<AppendClause> appendClauses, TreePath methodBodyPath) {
        java.util.Set<Element> clauseParams = new java.util.HashSet<>();
        for (AppendClause clause : appendClauses) {
            clauseParams.add(clause.paramElement());
        }
        boolean[] defeated = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = parsed.trees().getElement(getCurrentPath());
                if (element != null && clauseParams.contains(element) && !isAllowedParamUse(getCurrentPath())) {
                    defeated[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            /**
             * Whether a clause-param identifier at {@code path} is an allowed use: an operand of a {@code
             * != null} comparison (the gate) or the 2nd argument of a {@code setXxx(ordinal, param)} call
             * (the bind). Anything else (a value-position read, an arithmetic operand, a method argument
             * other than a setXxx value, a different comparison) defeats.
             */
            private boolean isAllowedParamUse(TreePath path) {
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                if (parent instanceof BinaryTree binary && binary.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                    ExpressionTree other = JdbcShapes.unwrap(binary.getLeftOperand()) == path.getLeaf()
                            ? binary.getRightOperand() : binary.getLeftOperand();
                    return isNullLiteral(JdbcShapes.unwrap(other)); // `param != null` (either side).
                }
                if (parent instanceof MethodInvocationTree invocation
                        && invocation.getMethodSelect() instanceof MemberSelectTree select
                        && select.getIdentifier().toString().startsWith("set")
                        && invocation.getArguments().size() == 2
                        && JdbcShapes.unwrap(invocation.getArguments().get(1)) == path.getLeaf()) {
                    return true; // the 2nd arg of a setXxx(ordinal, param) bind.
                }
                return false;
            }
        }.scan(methodBodyPath, null);
        return !defeated[0];
    }

    // ---- small shared helpers ---------------------------------------------------------------------

    private static boolean isNullLiteral(ExpressionTree expr) {
        return expr instanceof LiteralTree literal && literal.getValue() == null
                && expr.getKind() == Tree.Kind.NULL_LITERAL;
    }

    private static boolean isLocalVariable(Element element) {
        return element.getKind() == ElementKind.LOCAL_VARIABLE;
    }

    private static boolean isParamOrLocal(Element element) {
        return element.getKind() == ElementKind.PARAMETER || element.getKind() == ElementKind.LOCAL_VARIABLE;
    }

    /**
     * The compile-time-constant String value of {@code expression} (a string literal, a {@code static
     * final String} JLS constant, or a {@code +} concatenation of those), or {@code null}. Mirrors {@code
     * RawSqlConstantRule}/{@code JdbcStatementLowerer.constantStringValue} (the D7 shared predicate): the
     * base and each clause text must be constant so no runtime value reaches the recovered text.
     */
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

    /**
     * The top-level clause keywords that, appearing AFTER the WHERE at query top level, TERMINATE the
     * WHERE-predicate context — so the always-present guarded predicate ({@code AND (? IS NULL OR …)}),
     * which is appended at the very END of the base, would land after them (e.g. {@code … LIMIT 2 AND (…)},
     * parsed as {@code LIMIT (2 AND …)}) rather than as a conjunct of the WHERE. Any of these at depth 0
     * after the WHERE means the WHERE is NOT the terminal predicate context → fail-safe reject. {@code AND}
     * itself is of course NOT here (it extends the predicate). Single tokens ({@code HAVING}, {@code LIMIT},
     * …) and the first token of two-word clauses ({@code ORDER BY}, {@code GROUP BY}, {@code FOR UPDATE}) —
     * matching the first word is sufficient to reject. {@code FETCH} covers ANSI {@code FETCH FIRST … ROWS}.
     */
    private static final String[] WHERE_TERMINATING_TOP_LEVEL_KEYWORDS = {
            "ORDER", "GROUP", "HAVING", "WINDOW", "LIMIT", "OFFSET", "FETCH",
            "FOR", "UNION", "INTERSECT", "EXCEPT", "RETURNING"
    };

    /**
     * Whether {@code baseSql} has a <b>guard-attachable</b> WHERE: a {@code WHERE} keyword at the query
     * <b>top level</b> (paren depth 0, outside any quoted literal/identifier) that is the <b>terminal</b>
     * predicate clause — i.e. NO top-level clause keyword that terminates the WHERE predicate ({@link
     * #WHERE_TERMINATING_TOP_LEVEL_KEYWORDS} — ORDER/GROUP/HAVING/LIMIT/OFFSET/FETCH/FOR/WINDOW/UNION/…)
     * appears after it. This is the predicate context the always-present guarded predicate {@code AND (? IS
     * NULL OR …)}, appended at the END of the base, attaches to as a boolean conjunct.
     *
     * <p>FAIL-SAFE rejects (returns {@code false}) for every base whose trailing {@code AND (…)} cannot be
     * proven to attach to a boolean WHERE position:
     * <ul>
     *   <li>NO {@code WHERE} at all (no predicate context — the original's bare base is valid, ours diverges);</li>
     *   <li>a {@code WHERE} only inside a subquery / derived table (depth &gt; 0) — the outer query has no
     *       top-level WHERE, so {@code … sub AND (…)} / {@code … ) AND (…)} is malformed;</li>
     *   <li>a real top-level {@code WHERE} followed by a top-level tail clause ({@code ORDER BY}, {@code
     *       GROUP BY}, {@code HAVING}, {@code LIMIT}, {@code OFFSET}, {@code FETCH}, {@code FOR UPDATE/SHARE},
     *       {@code WINDOW}, {@code UNION}/{@code INTERSECT}/{@code EXCEPT}, {@code RETURNING}) — the appended
     *       {@code AND (…)} lands after the tail (e.g. {@code … ORDER BY id AND (…)}), malformed;</li>
     *   <li>a base that ends at paren depth &gt; 0 — an unbalanced open paren (an unclosed subquery / derived
     *       table whose matching {@code )} is appended later, in a gated clause or the tail) leaves the
     *       recovered top-level WHERE ambiguous with the inner subquery's WHERE, so the guard {@code AND (…)}
     *       appended at end-of-base would land INSIDE the still-open subquery, not the outer query's WHERE.
     *       The whole base must be paren-BALANCED ({@code depth == 0} at end of scan).</li>
     * </ul>
     *
     * <p>Parser-free, paren-depth + quote aware: single-quoted string literals (with {@code ''} escape) and
     * double-quoted / back-quoted identifiers are skipped as opaque, so a column or literal spelling a
     * keyword (e.g. {@code 'WHERE'}, {@code "order"}, {@code `limit`}) never counts. The canonical D4 search
     * base is a single top-level terminal {@code WHERE} (optionally with {@code 1=1}); everything else is
     * routed to the existing strict/Rung paths rather than mis-lowered.
     */
    static boolean baseWhereIsGuardAttachable(String baseSql) {
        if (baseSql == null) {
            return false;
        }
        String upper = baseSql.toUpperCase(java.util.Locale.ROOT);
        boolean sawTopLevelWhere = false;
        int depth = 0;
        int i = 0;
        int n = upper.length();
        while (i < n) {
            char c = upper.charAt(i);
            // Skip an opaque quoted region (string literal or quoted identifier) — a keyword spelled inside
            // it is data/an identifier, never a clause. Single quote honors the SQL `''` doubling escape.
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(upper, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth == 0 && isKeywordAt(upper, i)) {
                int wordLen = keywordLengthAt(upper, i);
                String word = upper.substring(i, i + wordLen);
                if (!sawTopLevelWhere) {
                    if (word.equals("WHERE")) {
                        sawTopLevelWhere = true;
                    }
                } else if (isWhereTerminatingKeyword(word)) {
                    // A top-level tail clause after the WHERE — the WHERE is not the terminal predicate.
                    return false;
                }
                i += wordLen;
                continue;
            }
            i++;
        }
        // The base must end paren-BALANCED (depth == 0): a base that ends inside an open paren (an unclosed
        // subquery / derived table) leaves the recovered top-level WHERE ambiguous with the inner WHERE, and
        // the always-present guard `AND (…)`, appended at the end of the base, would land INSIDE the still-open
        // subquery rather than as a conjunct of the OUTER query's WHERE. Fail-safe reject (the guard's
        // attachment point is not provably the top-level WHERE). A balanced base with a top-level terminal
        // WHERE is the only shape we admit.
        return sawTopLevelWhere && depth == 0;
    }

    /** Advances past a quoted region opened at {@code open} by {@code quote}; honors {@code ''} in '-strings. */
    private static int skipQuoted(String text, int open, char quote) {
        int i = open + 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == quote) {
                // A SQL single-quoted literal escapes a quote by doubling it ('') — stay inside the literal.
                if (quote == '\'' && i + 1 < n && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1; // the closing quote consumed.
            }
            i++;
        }
        return n; // unterminated — consume to the end (spliceLexicalStateIsClean already guards this case).
    }

    /** Whether a whole-word keyword token begins at {@code i} (a letter run not preceded by a word char). */
    private static boolean isKeywordAt(String upper, int i) {
        char here = upper.charAt(i);
        if (!isWordChar(here)) {
            return false;
        }
        char before = i == 0 ? ' ' : upper.charAt(i - 1);
        return !isWordChar(before); // the token must start on a word boundary.
    }

    /** The length of the maximal word (letters/digits/_/$) token starting at {@code i}. */
    private static int keywordLengthAt(String upper, int i) {
        int j = i;
        while (j < upper.length() && isWordChar(upper.charAt(j))) {
            j++;
        }
        return j - i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isWhereTerminatingKeyword(String upperWord) {
        for (String keyword : WHERE_TERMINATING_TOP_LEVEL_KEYWORDS) {
            if (keyword.equals(upperWord)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithWord(String upperText, String word) {
        if (!upperText.startsWith(word)) {
            return false;
        }
        if (upperText.length() == word.length()) {
            return true;
        }
        char after = upperText.charAt(word.length());
        return !(Character.isLetterOrDigit(after) || after == '_');
    }

    private static boolean endsWithWord(String upperText, String word) {
        String t = upperText.stripTrailing();
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

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether {@code clause} carries a single/double/back quote, a {@code --}/{@code /*}/{@code #} comment, or
     * a {@code ;} terminator — any of which makes it unsafe to nest as a standalone {@code AND (…)} predicate or
     * to splice as a verbatim tail (a quoted literal could hide the {@code ?} or another operator; a
     * comment/terminator could swallow the guard wrapper or comment-out a {@code ?} the recognizer counted). The
     * {@code #} form is a MySQL line comment: on a single-dialect-native MySQL build the tail splices VERBATIM
     * into a MySQL procedure, so a {@code #} would comment out everything after it on that line — desyncing the
     * recognizer's tail-{@code ?} count from the {@code ?}s MySQL actually sees. FAIL-SAFE: the recognized
     * clause is the simple {@code AND col OP ?} and a supported pagination tail never contains these; their
     * presence means a non-canonical clause/tail we refuse.
     */
    private static boolean containsQuoteOrCommentOrTerminator(String clause) {
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            char next = i + 1 < clause.length() ? clause.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"' || c == ';' || c == '`' || c == '#') {
                return true;
            }
            if (c == '-' && next == '-') {
                return true;
            }
            if (c == '/' && next == '*') {
                return true;
            }
        }
        return false;
    }
}
