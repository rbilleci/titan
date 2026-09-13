package io.titan.transpiler.tir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emulation-insertion pass over lowered TIR (audit E-7/E-11, plan 2.4): the design-documented
 * TIR→TIR transform that rewrites Java arithmetic whose semantics have no faithful native SQL
 * operator into dialect-neutral marker calls, which the emitters map to native SQL or to the
 * deployed {@code titan_runtime}/{@code titan_rt_*} helper functions.
 *
 * <p>Like {@link NullAnalysisPass}, the pass walks the complete sealed TIR hierarchy with
 * exhaustive {@code switch} expressions and no {@code default} branches, so adding a new
 * {@link StatementNode}/{@link ExpressionNode} kind fails compilation here until its emulation
 * semantics are decided explicitly. It runs in {@link JavaToTirLowerer} directly after
 * {@link NullAnalysisPass}.
 *
 * <h2>Always-on rewrites (E-7): integer division and modulo</h2>
 *
 * <p>{@code BinaryOperator.DIVIDE}/{@code MODULO} whose operands are <em>provably</em> integral
 * (Java {@code byte/short/int/long} — TIR {@link TIntType}/{@link TBigintType}) are rewritten to
 * {@link #INT_DIV_MARKER}/{@link #INT_MOD_MARKER}:
 * <ul>
 *   <li><b>PostgreSQL</b> emits native {@code (a / b)} and {@code (a % b)}: PostgreSQL integer
 *       division truncates toward zero ({@code -7/2 = -3}), {@code %} takes the dividend's sign
 *       ({@code -7%3 = -1}), and both raise SQLSTATE 22012 on a zero divisor — all three match
 *       Java exactly, so no runtime function is needed. This is also why the long-deployed
 *       {@code titan_runtime.java_mod} stays <em>unwired</em> on PostgreSQL: its TRUNC-based
 *       formula returns the same value as native {@code %} for every integral input (it was
 *       written from the design document's generic emulation list, not for an actual PostgreSQL
 *       divergence).</li>
 *   <li><b>MySQL</b> emits {@code titan_rt_java_int_div(a, b)} / {@code titan_rt_java_mod(a, b)}.
 *       Native MySQL {@code INT/INT} division yields DECIMAL ({@code 5/2 = 2.5}) and a zero
 *       divisor yields {@code NULL} with a warning even under the default strict
 *       {@code sql_mode} ({@code ERROR_FOR_DIVISION_BY_ZERO} only errors inside
 *       INSERT/UPDATE), silently diverging from Java/PostgreSQL. The runtime functions use
 *       {@code DIV} (truncating) / {@code MOD} (dividend-sign) and explicitly
 *       {@code SIGNAL SQLSTATE '22012'} on a zero divisor — following the E-1/E-8 precedent of
 *       explicit SQL over session-state ({@code sql_mode}) dependence.</li>
 * </ul>
 *
 * <h2>Policy-gated rewrites (E-11): 32-bit wraparound arithmetic</h2>
 *
 * <p>Java {@code int} {@code +}/{@code -}/{@code *} silently wraps at 32 bits; PostgreSQL
 * {@code INTEGER} arithmetic raises {@code integer out of range} and MySQL raises an
 * out-of-range/BIGINT-range error under its default strict mode. Two modes:
 * <ul>
 *   <li><b>Default ("fail-loud parity")</b>: {@code ADD}/{@code SUBTRACT}/{@code MULTIPLY} keep
 *       the native operators. <em>Documented divergence:</em> overflow raises on both dialects
 *       instead of silently wrapping like Java. Raising is closer to safety than reproducing the
 *       wrap, and wholesale rewriting of every {@code +} would wreck the readability and
 *       performance of generated SQL for the overwhelming majority of non-overflowing code.</li>
 *   <li><b>Strict-wraparound</b> (pipeline option {@code strictWraparound}): int-typed
 *       {@code +}/{@code -}/{@code *} on provably {@link TIntType} operands are rewritten to
 *       {@link #INT_ADD_MARKER}/{@link #INT_SUB_MARKER}/{@link #INT_MUL_MARKER}, emitted as
 *       {@code titan_runtime.java_int_add(a, b)} (PostgreSQL) / {@code titan_rt_java_int_add(a, b)}
 *       (MySQL) etc., which compute in 64-bit and reduce with the
 *       {@code ((x + 2^31) mod 2^32) - 2^31} double-mod pattern (correct for negative operands
 *       on both dialects because {@code %} takes the dividend's sign there).</li>
 * </ul>
 *
 * <h2>Documented limits (conservative by construction)</h2>
 * <ul>
 *   <li><b>Provability:</b> operand types are derived from declared variable/parameter TIR types,
 *       literals, casts, and this pass's own markers. Column references, helper-call results,
 *       record fields and other unknown-typed expressions are <em>not</em> rewritten — they keep
 *       native SQL operators (SQL semantics), exactly as before this pass existed.</li>
 *   <li><b>SQL-node interiors</b> (DSL queries, {@code ExecuteSqlStatement}, cursor queries) are
 *       deliberately untouched: that surface is written against SQL semantics, and its column
 *       types are unknown to the transpiler anyway.</li>
 *   <li><b>64-bit wraparound is not emulated</b> even in strict mode: the runtime helpers are
 *       32-bit ({@code java_int_*}); Java {@code long} overflow raises on both dialects
 *       (documented divergence, same fail-loud rationale).</li>
 *   <li><b>{@code Integer.MIN_VALUE / -1}</b>: Java wraps to {@code Integer.MIN_VALUE};
 *       PostgreSQL raises {@code integer out of range} and the MySQL helper returns the
 *       mathematical quotient {@code 2^31} (BIGINT). Documented divergence on this single input
 *       pair; division is otherwise overflow-free.</li>
 * </ul>
 */
public final class EmulationInsertionPass {

    /** Integer division marker: PG {@code (a / b)}, MySQL {@code titan_rt_java_int_div(a, b)}. */
    static final String INT_DIV_MARKER = "__titan_int_div";

    /** Integer modulo marker: PG {@code (a % b)}, MySQL {@code titan_rt_java_mod(a, b)}. */
    static final String INT_MOD_MARKER = "__titan_int_mod";

    /** 32-bit wraparound addition marker (strict-wraparound mode only). */
    static final String INT_ADD_MARKER = "__titan_int_add";

    /** 32-bit wraparound subtraction marker (strict-wraparound mode only). */
    static final String INT_SUB_MARKER = "__titan_int_sub";

    /** 32-bit wraparound multiplication marker (strict-wraparound mode only). */
    static final String INT_MUL_MARKER = "__titan_int_mul";

    private final boolean strictWraparound;
    private final Map<String, TirType> parameterTypes;

    /**
     * @param strictWraparound enables the 32-bit wraparound rewrite of int-typed
     *                         {@code +}/{@code -}/{@code *} (see class javadoc)
     * @param parameterTypes   TIR types of the routine's parameters by Java parameter name;
     *                         variables absent from scope and this map are treated as
     *                         unknown-typed (never rewritten)
     */
    public EmulationInsertionPass(boolean strictWraparound, Map<String, TirType> parameterTypes) {
        this.strictWraparound = strictWraparound;
        this.parameterTypes = parameterTypes == null ? Map.of() : Map.copyOf(parameterTypes);
    }

    /** Rewrites the block; the input is never mutated. */
    public Block apply(Block input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        return rewriteBlock(input, new HashMap<>(parameterTypes));
    }

    // ------------------------------------------------------------------
    // Blocks and scoping
    // ------------------------------------------------------------------

    /**
     * Rewrites {@code block} in a child type scope of {@code outerScope}. Types are static
     * (Java variables cannot change type), so unlike {@link NullAnalysisPass} no flow joins or
     * loop fixpoints are needed — only lexical scoping with shadowing.
     */
    private Block rewriteBlock(Block block, Map<String, TirType> outerScope) {
        Map<String, TirType> scope = new HashMap<>(outerScope);
        for (DeclarationNode declaration : block.declarations()) {
            if (declaration instanceof DeclareVariable variable) {
                scope.put(variable.name(), variable.type());
            }
        }

        List<DeclarationNode> declarations = block.declarations().stream()
                .map(declaration -> rewriteDeclaration(declaration, scope))
                .toList();
        List<StatementNode> statements = block.statements().stream()
                .map(statement -> rewriteStatement(statement, scope))
                .toList();
        List<DeclarationNode> handlers = block.exceptionHandlers().stream()
                .map(declaration -> rewriteDeclaration(declaration, scope))
                .toList();
        return new Block(declarations, statements, handlers);
    }

    private DeclarationNode rewriteDeclaration(DeclarationNode declaration, Map<String, TirType> scope) {
        return switch (declaration) {
            // Initializers must be rewritten exactly like their mirrored ordered Assign statement
            // (StatementLowerer adds one for every initialized local) so the emitters keep
            // recognizing the pair and suppress the inline DECLARE initializer.
            case DeclareVariable variable -> new DeclareVariable(
                    variable.name(),
                    variable.type(),
                    variable.nullable(),
                    variable.initializer() == null ? null : rewriteExpression(variable.initializer(), scope));
            // Cursor queries are SQL surface: untouched by design (see class javadoc).
            case DeclareCursor cursor -> cursor;
            case DeclareHandler handler -> handler;
        };
    }

    // ------------------------------------------------------------------
    // Statements (exhaustive over the sealed StatementNode hierarchy)
    // ------------------------------------------------------------------

    private StatementNode rewriteStatement(StatementNode statement, Map<String, TirType> scope) {
        return switch (statement) {
            case Block nested -> rewriteBlock(nested, scope);
            case Assign assign -> new Assign(
                    rewriteExpression(assign.target(), scope),
                    rewriteExpression(assign.expression(), scope));
            case IfStatement ifStatement -> new IfStatement(
                    rewriteExpression(ifStatement.condition(), scope),
                    rewriteBlock(ifStatement.thenBlock(), scope),
                    ifStatement.elseIfClauses().stream()
                            .map(clause -> new ElseIfClause(
                                    rewriteExpression(clause.condition(), scope),
                                    rewriteBlock(clause.block(), scope)))
                            .toList(),
                    ifStatement.elseBlock() == null ? null : rewriteBlock(ifStatement.elseBlock(), scope));
            case WhileStatement whileStatement -> new WhileStatement(
                    rewriteExpression(whileStatement.condition(), scope),
                    rewriteBlock(whileStatement.body(), scope),
                    whileStatement.label());
            case LoopStatement loopStatement -> new LoopStatement(
                    rewriteBlock(loopStatement.body(), scope),
                    loopStatement.exitCondition() == null
                            ? null
                            : rewriteExpression(loopStatement.exitCondition(), scope),
                    loopStatement.label());
            // The cursor query is SQL surface (untouched); the row variable's fields are
            // unknown-typed, so the body simply sees no binding for it.
            case ForCursorStatement forCursor -> new ForCursorStatement(
                    forCursor.variableName(),
                    forCursor.query(),
                    rewriteBlock(forCursor.body(), scope),
                    forCursor.label());
            case ForEachStatement forEach -> {
                Map<String, TirType> bodyScope = new HashMap<>(scope);
                bodyScope.put(forEach.variableName(), forEach.variableType());
                yield new ForEachStatement(
                        forEach.variableName(),
                        forEach.variableType(),
                        rewriteExpression(forEach.iterable(), scope),
                        rewriteBlock(forEach.body(), bodyScope),
                        forEach.label());
            }
            case ForRangeStatement forRange -> {
                // The range variable is a Java int by construction (counting-loop recognition).
                Map<String, TirType> bodyScope = new HashMap<>(scope);
                bodyScope.put(forRange.variableName(), new TIntType());
                yield new ForRangeStatement(
                        forRange.variableName(),
                        rewriteExpression(forRange.start(), scope),
                        rewriteExpression(forRange.end(), scope),
                        rewriteBlock(forRange.body(), bodyScope),
                        forRange.label());
            }
            case ReturnStatement returnStatement -> returnStatement.expression() == null
                    ? returnStatement
                    : new ReturnStatement(rewriteExpression(returnStatement.expression(), scope));
            case BreakStatement breakStatement -> breakStatement;
            case ContinueStatement continueStatement -> continueStatement;
            case RaiseStatement raise -> new RaiseStatement(
                    raise.sqlstate(),
                    raise.message() == null ? null : rewriteExpression(raise.message(), scope),
                    raise.details().stream().map(detail -> rewriteExpression(detail, scope)).toList());
            case CallStatement call -> new CallStatement(
                    call.procedureName(),
                    call.arguments().stream().map(argument -> rewriteExpression(argument, scope)).toList());
            case DebugPrintStatement debugPrint -> new DebugPrintStatement(
                    rewriteExpression(debugPrint.message(), scope));
            // SQL surface: untouched by design (see class javadoc).
            case ExecuteSqlStatement executeSql -> executeSql;
            case SelectIntoStatement selectInto -> selectInto;
            // Raw read is SQL surface (opaque text); the cursor body is ordinary statements that
            // must still be walked. Transaction control is a bare leaf.
            case RawReadIntoStatement rawRead -> rawRead;
            case RawCursorStatement rawCursor -> new RawCursorStatement(
                    rawCursor.variableNames(),
                    rawCursor.query(),
                    rewriteBlock(rawCursor.body(), scope),
                    rawCursor.label());
            // Unknown-shape carrier (JDBC Tier-3): SQL surface (opaque dynamic SELECT text); no
            // arithmetic to rewrite (there are no typed row locals — the shape is metadata-driven).
            case DynamicResultStatement dynamicResult -> dynamicResult;
            // Insert-with-generated-key recovery is SQL surface (opaque INSERT text + a scalar key
            // bind); no arithmetic to rewrite.
            case GeneratedKeyReadStatement genKeyRead -> genKeyRead;
            case TransactionControlStatement transactionControl -> transactionControl;
            case NullGuardStatement nullGuard -> nullGuard;
            case CloseCursorStatement closeCursor -> closeCursor;
            case TryCatchFinallyStatement tryCatch -> new TryCatchFinallyStatement(
                    rewriteBlock(tryCatch.tryBlock(), scope),
                    tryCatch.catches().stream()
                            .map(catchClause -> {
                                // The catch variable carries the exception message (TEXT).
                                Map<String, TirType> catchScope = new HashMap<>(scope);
                                if (catchClause.exceptionVariable() != null) {
                                    catchScope.put(catchClause.exceptionVariable(), new TTextType());
                                }
                                return new CatchClause(
                                        catchClause.exceptionVariable(),
                                        catchClause.exceptionType(),
                                        catchClause.sqlStates(),
                                        rewriteBlock(catchClause.body(), catchScope));
                            })
                            .toList(),
                    tryCatch.finallyBlock() == null ? null : rewriteBlock(tryCatch.finallyBlock(), scope));
        };
    }

    // ------------------------------------------------------------------
    // Expressions (exhaustive over the sealed ExpressionNode hierarchy)
    // ------------------------------------------------------------------

    private ExpressionNode rewriteExpression(ExpressionNode expression, Map<String, TirType> scope) {
        return switch (expression) {
            case ColumnRefExpression column -> column;
            case VariableRefExpression variable -> variable;
            case LiteralExpression literal -> literal;
            case BinaryOpExpression binaryOp -> rewriteBinaryOp(binaryOp, scope);
            case FunctionCallExpression functionCall -> new FunctionCallExpression(
                    functionCall.name(),
                    functionCall.arguments().stream().map(argument -> rewriteExpression(argument, scope)).toList(),
                    functionCall.schema());
            case RecordConstructExpression recordConstruct -> new RecordConstructExpression(
                    recordConstruct.schema(),
                    recordConstruct.recordName(),
                    recordConstruct.arguments().stream().map(argument -> rewriteExpression(argument, scope)).toList());
            case RecordFieldExpression recordField -> new RecordFieldExpression(
                    rewriteExpression(recordField.record(), scope),
                    recordField.schema(),
                    recordField.recordName(),
                    recordField.fieldName());
            case ArrayConstructExpression arrayConstruct -> new ArrayConstructExpression(
                    arrayConstruct.elementType(),
                    arrayConstruct.elements().stream().map(element -> rewriteExpression(element, scope)).toList());
            case ArrayLengthExpression arrayLength -> new ArrayLengthExpression(
                    rewriteExpression(arrayLength.array(), scope));
            case ArrayGetExpression arrayGet -> new ArrayGetExpression(
                    rewriteExpression(arrayGet.array(), scope),
                    rewriteExpression(arrayGet.index(), scope),
                    arrayGet.elementType());
            case CaseWhenExpression caseWhen -> new CaseWhenExpression(
                    caseWhen.conditions().stream()
                            .map(branch -> new CaseBranch(
                                    rewriteExpression(branch.condition(), scope),
                                    rewriteExpression(branch.value(), scope)))
                            .toList(),
                    caseWhen.elseValue() == null ? null : rewriteExpression(caseWhen.elseValue(), scope));
            // SQL surface: untouched by design (see class javadoc).
            case SubqueryExpression subquery -> subquery;
            case IsNullExpression isNull -> new IsNullExpression(rewriteExpression(isNull.expression(), scope));
            case IsNotNullExpression isNotNull -> new IsNotNullExpression(rewriteExpression(isNotNull.expression(), scope));
            case NotExpression not -> new NotExpression(rewriteExpression(not.expression(), scope));
            case CastExpression cast -> new CastExpression(
                    rewriteExpression(cast.expression(), scope),
                    cast.targetType(),
                    cast.truncating());
            case CoalesceExpression coalesce -> new CoalesceExpression(
                    coalesce.expressions().stream().map(item -> rewriteExpression(item, scope)).toList());
            // SQL surface: untouched by design (see class javadoc).
            case ExistsExpression exists -> exists;
            case InListExpression inList -> inList;
            case WindowFunctionExpression windowFunction -> windowFunction;
            case GroupingSetSpec groupingSet -> groupingSet;
        };
    }

    private ExpressionNode rewriteBinaryOp(BinaryOpExpression binaryOp, Map<String, TirType> scope) {
        ExpressionNode left = rewriteExpression(binaryOp.left(), scope);
        ExpressionNode right = rewriteExpression(binaryOp.right(), scope);
        TirType leftType = typeOf(left, scope);
        TirType rightType = typeOf(right, scope);

        switch (binaryOp.operator()) {
            case DIVIDE -> {
                if (isIntegral(leftType) && isIntegral(rightType)) {
                    return new FunctionCallExpression(INT_DIV_MARKER, List.of(left, right), null);
                }
            }
            case MODULO -> {
                if (isIntegral(leftType) && isIntegral(rightType)) {
                    return new FunctionCallExpression(INT_MOD_MARKER, List.of(left, right), null);
                }
            }
            case ADD, SUBTRACT, MULTIPLY -> {
                if (strictWraparound && isInt32(leftType) && isInt32(rightType)) {
                    String marker = switch (binaryOp.operator()) {
                        case ADD -> INT_ADD_MARKER;
                        case SUBTRACT -> INT_SUB_MARKER;
                        case MULTIPLY -> INT_MUL_MARKER;
                        default -> throw new IllegalStateException("unreachable");
                    };
                    return new FunctionCallExpression(marker, List.of(left, right), null);
                }
            }
            default -> {
                // Comparison/logical/LIKE operators have no emulation concern.
            }
        }
        return new BinaryOpExpression(left, binaryOp.operator(), right);
    }

    // ------------------------------------------------------------------
    // Conservative static typing (null = unknown, never rewritten)
    // ------------------------------------------------------------------

    /**
     * Static TIR type of an expression, or {@code null} when unknown. Exhaustive over the sealed
     * hierarchy; deliberately conservative — an unknown type disables rewriting, never enables it.
     */
    private TirType typeOf(ExpressionNode expression, Map<String, TirType> scope) {
        return switch (expression) {
            case VariableRefExpression variable -> scope.get(variable.name());
            // The lowerer types the null literal as TJsonType; treat it as unknown here.
            case LiteralExpression literal -> literal.value() == null ? null : literal.type();
            case CastExpression cast -> cast.targetType();
            case BinaryOpExpression binaryOp -> switch (binaryOp.operator()) {
                case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO -> joinNumeric(
                        typeOf(binaryOp.left(), scope),
                        typeOf(binaryOp.right(), scope));
                case LIKE, EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL,
                        GREATER_THAN, GREATER_THAN_OR_EQUAL, AND, OR -> new TBooleanType();
            };
            case FunctionCallExpression functionCall -> switch (functionCall.name()) {
                // This pass's own markers: division/modulo preserve the integral join of their
                // operands; the wraparound helpers are 32-bit by definition.
                case INT_DIV_MARKER, INT_MOD_MARKER -> joinNumeric(
                        typeOf(functionCall.arguments().get(0), scope),
                        typeOf(functionCall.arguments().get(1), scope));
                case INT_ADD_MARKER, INT_SUB_MARKER, INT_MUL_MARKER -> new TIntType();
                // The lowerer's char-code marker yields a Java int (char promoted for arithmetic).
                case "__titan_char_code" -> new TIntType();
                default -> null;
            };
            case CaseWhenExpression caseWhen -> {
                TirType joined = null;
                for (CaseBranch branch : caseWhen.conditions()) {
                    joined = joined == null
                            ? typeOf(branch.value(), scope)
                            : joinNumeric(joined, typeOf(branch.value(), scope));
                    if (joined == null) {
                        yield null;
                    }
                }
                if (caseWhen.elseValue() == null) {
                    yield joined;
                }
                yield joined == null
                        ? typeOf(caseWhen.elseValue(), scope)
                        : joinNumeric(joined, typeOf(caseWhen.elseValue(), scope));
            }
            case CoalesceExpression coalesce -> {
                TirType joined = null;
                for (ExpressionNode item : coalesce.expressions()) {
                    joined = joined == null ? typeOf(item, scope) : joinNumeric(joined, typeOf(item, scope));
                    if (joined == null) {
                        yield null;
                    }
                }
                yield joined;
            }
            case ArrayLengthExpression ignored -> new TIntType();
            case ArrayGetExpression arrayGet -> arrayGet.elementType();
            case ArrayConstructExpression arrayConstruct -> new TArrayType(arrayConstruct.elementType());
            case IsNullExpression ignored -> new TBooleanType();
            case IsNotNullExpression ignored -> new TBooleanType();
            case NotExpression ignored -> new TBooleanType();
            case ExistsExpression ignored -> new TBooleanType();
            case InListExpression ignored -> new TBooleanType();
            // Unknown by design: SQL-side values and structures the transpiler cannot type.
            case ColumnRefExpression ignored -> null;
            case SubqueryExpression ignored -> null;
            case RecordFieldExpression ignored -> null;
            case RecordConstructExpression ignored -> null;
            case WindowFunctionExpression ignored -> null;
            case GroupingSetSpec ignored -> null;
        };
    }

    /**
     * Numeric join used both for arithmetic result typing and operand provability:
     * int⊕int → int, integral⊕bigint → bigint, anything-with-numeric → numeric,
     * anything unknown/non-numeric → unknown.
     */
    private TirType joinNumeric(TirType left, TirType right) {
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof TNumericType || right instanceof TNumericType) {
            return left instanceof TNumericType ? left : right;
        }
        if (!isIntegral(left) || !isIntegral(right)) {
            return null;
        }
        if (left instanceof TBigintType || right instanceof TBigintType) {
            return new TBigintType();
        }
        return new TIntType();
    }

    private boolean isIntegral(TirType type) {
        return type instanceof TIntType || type instanceof TBigintType;
    }

    private boolean isInt32(TirType type) {
        return type instanceof TIntType;
    }
}
