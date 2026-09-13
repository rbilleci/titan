package io.titan.transpiler.tir;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import io.titan.transpiler.ParsedSources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Single walker for DSL method chains (select/insert/update/delete builders).
 *
 * <p>Walks a chain once, from the outermost invocation back to the chain root, and produces an
 * ordered {@link Chain} of classified {@link Step}s. Call sites interpret the steps; this class
 * owns the chain grammar (which step shapes are recognizable) and the Phase-0 default-deny:
 * a step that is not recognized for the requested step set, on a chain rooted at a DSL entry
 * (select/selectFrom/insertInto/update/deleteFrom/with/withRecursive), fails lowering with a
 * positioned diagnostic instead of being silently dropped.</p>
 */
final class DslChainParser {

    /** Classified DSL chain step shapes. */
    enum StepKind {
        SELECT,
        DISTINCT,
        FROM,
        WHERE,
        GROUP_BY,
        HAVING,
        LIMIT,
        ORDER_BY,
        JOIN_ON,
        CROSS_JOIN,
        LATERAL_AS,
        SET,
        ON_CONFLICT,
        FOR_UPDATE,
        FOR_SHARE,
        SKIP_LOCKED,
        NO_WAIT,
        SET_OPERATION,
        /** Steps that carry no clause of their own (join targets, fetch terminals, doUpdate). */
        PASS_THROUGH,
        /** Anything else: a default-deny candidate on DSL-rooted chains. */
        UNRECOGNIZED
    }

    /** Steps recognized by select-builder chains (set-operation arguments, CTE bodies, subqueries). */
    static final Set<StepKind> SELECT_BUILDER_STEPS = Collections.unmodifiableSet(EnumSet.of(
            StepKind.SELECT,
            StepKind.DISTINCT,
            StepKind.FROM,
            StepKind.WHERE,
            StepKind.GROUP_BY,
            StepKind.HAVING,
            StepKind.LIMIT,
            StepKind.ORDER_BY,
            StepKind.JOIN_ON,
            StepKind.CROSS_JOIN,
            StepKind.LATERAL_AS,
            StepKind.FOR_UPDATE,
            StepKind.FOR_SHARE,
            StepKind.SKIP_LOCKED,
            StepKind.NO_WAIT,
            StepKind.PASS_THROUGH));

    /** Steps recognized by full statement chains: select-builder steps plus DML and set operations. */
    static final Set<StepKind> STATEMENT_STEPS;

    static {
        EnumSet<StepKind> statementSteps = EnumSet.copyOf(SELECT_BUILDER_STEPS);
        statementSteps.add(StepKind.SET);
        statementSteps.add(StepKind.ON_CONFLICT);
        statementSteps.add(StepKind.SET_OPERATION);
        STATEMENT_STEPS = Collections.unmodifiableSet(statementSteps);
    }

    private DslChainParser() {
    }

    /**
     * One chained invocation between the chain root (exclusive) and the outermost invocation
     * (inclusive). {@code receiver} is the invocation the step was called on.
     */
    record Step(StepKind kind, String name, MethodInvocationTree invocation, MethodInvocationTree receiver) {
        List<? extends ExpressionTree> arguments() {
            return invocation.getArguments();
        }
    }

    /**
     * Parsed chain: the root invocation plus all steps above it, ordered from the outermost
     * invocation down to the invocation directly on the root (the original walk order).
     */
    record Chain(MethodInvocationTree root, List<Step> steps) {
        String rootName() {
            return LowererSupport.invocationName(root);
        }
    }

    /** Walks the chain without applying the default-deny. */
    static Chain parse(MethodInvocationTree outermost) {
        List<Step> steps = new ArrayList<>();
        MethodInvocationTree cursor = outermost;
        while (cursor.getMethodSelect() instanceof MemberSelectTree memberSelect
                && memberSelect.getExpression() instanceof MethodInvocationTree previous) {
            String name = memberSelect.getIdentifier().toString();
            steps.add(new Step(classify(name, cursor, previous), name, cursor, previous));
            cursor = previous;
        }
        return new Chain(cursor, List.copyOf(steps));
    }

    /**
     * Walks the chain and applies the Phase-0 default-deny: the first step (in walk order) whose
     * kind is not in {@code recognizedKinds} fails lowering when the chain is DSL-rooted.
     */
    static Chain parse(MethodInvocationTree outermost, Set<StepKind> recognizedKinds, ParsedSources parsedSources) {
        Chain chain = parse(outermost);
        Step unrecognized = chain.steps().stream()
                .filter(step -> !recognizedKinds.contains(step.kind()))
                .findFirst()
                .orElse(null);
        if (unrecognized != null && isDslChainRoot(chain.rootName())) {
            throw LowererSupport.unsupportedFeature(
                    "DSL chain step '" + unrecognized.name() + "(...)' is not supported in lowered DSL chains",
                    unrecognized.invocation(),
                    parsedSources);
        }
        return chain;
    }

    private static StepKind classify(String name, MethodInvocationTree invocation, MethodInvocationTree receiver) {
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        return switch (name) {
            case "select" -> StepKind.SELECT;
            case "distinct" -> arguments.isEmpty() ? StepKind.DISTINCT : fallbackKind(name);
            case "from" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.FROM;
            case "where" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.WHERE;
            case "groupBy" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.GROUP_BY;
            case "having" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.HAVING;
            case "limit" -> !arguments.isEmpty()
                    && arguments.getFirst() instanceof LiteralTree literal
                    && literal.getValue() instanceof Integer
                    ? StepKind.LIMIT
                    : fallbackKind(name);
            case "orderBy" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.ORDER_BY;
            case "on" -> !arguments.isEmpty() && isJoinInvocation(receiver)
                    ? StepKind.JOIN_ON
                    : fallbackKind(name);
            case "crossJoin" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.CROSS_JOIN;
            case "as" -> !arguments.isEmpty()
                    && "lateralJoin".equals(LowererSupport.invocationName(receiver))
                    && !receiver.getArguments().isEmpty()
                    ? StepKind.LATERAL_AS
                    : fallbackKind(name);
            case "set" -> arguments.size() == 2 ? StepKind.SET : fallbackKind(name);
            case "onConflict" -> arguments.isEmpty() ? fallbackKind(name) : StepKind.ON_CONFLICT;
            case "forUpdate" -> StepKind.FOR_UPDATE;
            case "forShare" -> StepKind.FOR_SHARE;
            case "skipLocked" -> StepKind.SKIP_LOCKED;
            case "noWait" -> StepKind.NO_WAIT;
            case "union", "unionAll", "intersect", "except" ->
                    arguments.isEmpty() ? fallbackKind(name) : StepKind.SET_OPERATION;
            default -> fallbackKind(name);
        };
    }

    private static StepKind fallbackKind(String name) {
        return isPassThroughDslChainStep(name) ? StepKind.PASS_THROUGH : StepKind.UNRECOGNIZED;
    }

    static JoinType toJoinType(String methodName) {
        return switch (methodName) {
            case "join" -> JoinType.INNER;
            case "leftJoin" -> JoinType.LEFT;
            case "rightJoin" -> JoinType.RIGHT;
            case "fullOuterJoin" -> JoinType.FULL_OUTER;
            case "crossJoin" -> JoinType.CROSS;
            case "lateralJoin" -> JoinType.LATERAL;
            default -> null;
        };
    }

    private static boolean isJoinInvocation(MethodInvocationTree invocation) {
        return toJoinType(LowererSupport.invocationName(invocation)) != null;
    }

    private static boolean isDslChainRoot(String rootName) {
        return "select".equals(rootName)
                || "selectFrom".equals(rootName)
                || "insertInto".equals(rootName)
                || "update".equals(rootName)
                || "deleteFrom".equals(rootName)
                || "with".equals(rootName)
                || "withRecursive".equals(rootName);
    }

    private static boolean isPassThroughDslChainStep(String step) {
        return toJoinType(step) != null
                || List.of("fetch", "fetchOne", "fetchInto", "fetchCount", "fetchExists",
                        // Phase A4 / G2: typed read-into-local terminals (scalar value / EXISTS
                        // boolean bound to a routine local via SELECT ... INTO).
                        "fetchScalar", "fetchExistsValue",
                        "forEach", "execute", "doUpdate").contains(step);
    }
}
