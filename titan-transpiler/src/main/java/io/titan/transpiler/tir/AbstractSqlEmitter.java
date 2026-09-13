package io.titan.transpiler.tir;

import io.titan.transpiler.NamingConventionEngine;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import io.titan.transpiler.emit.CodeBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared base class for the dialect SQL emitters (plan 1.3a, audit S2).
 *
 * <p>Hosts every member that is identical between {@link PostgreSqlEmitter} and
 * {@link MySqlEmitter}, or identical up to a small dialect difference captured by one of the
 * protected hook methods below. This extraction is purely structural: for any given TIR input the
 * emitted SQL is byte-identical to the pre-extraction emitters.</p>
 */
public abstract class AbstractSqlEmitter implements TirVisitor<String>, ArtifactDescriber {
    protected final NamingConventionEngine namingConventionEngine;
    protected CodeBuffer out = new CodeBuffer();
    protected int rawSqlCounter = 0;
    protected List<String> staticResetKeys = List.of();
    protected List<RoutineParameter> routineParameters = List.of();
    protected RoutineSqlNameAllocator routineSqlNameAllocator = RoutineSqlNameAllocator.empty();
    // B-10: SQL routine name -> return type for every generated routine in this dialect bundle, so
    // a FunctionCallExpression to a boolean-returning routine is recognized as boolean and coerced
    // to 'true'/'false' when it reaches a text context. Empty until the pipeline registers it.
    protected Map<String, TirType> routineReturnTypesBySqlName = Map.of();
    protected boolean observabilityEnabled = false;

    protected AbstractSqlEmitter(NamingConventionEngine namingConventionEngine) {
        this.namingConventionEngine = Objects.requireNonNull(namingConventionEngine, "namingConventionEngine");
    }

    /**
     * Registers the generated routines' return types by SQL name (B-10). The pipeline calls this
     * once per dialect before emitting any body so {@link #isBooleanExpression(ExpressionNode)} can
     * classify a call to a boolean-returning routine — the path by which the consumer's
     * {@code pageInfo.hasNextPage} / {@code isRepeatable} booleans reach the JSON writer.
     */
    public void useRoutineReturnTypes(Map<String, TirType> routineReturnTypesBySqlName) {
        this.routineReturnTypesBySqlName = routineReturnTypesBySqlName == null
                ? Map.of()
                : Map.copyOf(routineReturnTypesBySqlName);
    }

    // ------------------------------------------------------------------
    // Dialect hooks — the genuine differences between the two emitters.
    // ------------------------------------------------------------------

    /** Maps a TIR type to this dialect's SQL type name. */
    protected abstract String sqlType(TirType type);

    /** Escapes a value for inclusion in a single-quoted string literal of this dialect. */
    protected abstract String escape(String value);

    /**
     * Renders a complete string-literal expression for this dialect. B-3 (TG-BLK-006): control
     * characters must never reach the SQL as raw bytes — {@code indentMultiline} treats an
     * embedded LF as a line break and "indents" it, silently changing the literal's value
     * ({@code '\n'} became a five-character literal). Dialects override to emit
     * dialect-appropriate escapes (PostgreSQL {@code E'\n'}, MySQL backslash escapes /
     * {@code CHAR(n USING utf8mb4)}); the default covers control-character-free values.
     */
    protected String stringLiteral(String value) {
        return "'" + escape(value) + "'";
    }

    /** Whether the value contains an ASCII control character (0x00-0x1F or 0x7F). */
    protected static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** Join keyword for {@code FULL OUTER JOIN}; MySQL has no equivalent and throws. */
    protected abstract String fullOuterJoinKeyword();

    /** Keyword opening an else-if branch: {@code ELSIF} in PL/pgSQL, {@code ELSEIF} in MySQL. */
    protected abstract String elseIfKeyword();

    /** Renders a character literal: PostgreSQL quotes it, MySQL uses {@code CHAR(n USING utf8mb4)}. */
    protected abstract String characterLiteral(char value);

    /** Placeholder substituted for the n-th (1-based) named parameter: {@code $n} vs {@code ?}. */
    protected abstract String parameterPlaceholder(int oneBasedIndex);

    /** Whether {@code $tag$...$tag$} dollar-quoted strings must be skipped when scanning raw SQL. */
    protected abstract boolean supportsDollarQuotedStrings();

    /**
     * Whether a backslash escapes the next character inside string/identifier literals when scanning
     * raw SQL. MySQL (with backslash-escapes on, which {@link #escape} assumes) treats {@code \"} as
     * an escaped quote that does not close a double-quoted literal; standard SQL / PostgreSQL does
     * not (a backslash is an ordinary character in a quoted identifier, and {@code ""} is the only
     * escape), so this defaults to {@code false}. Used by {@link #rewritePositionalPlaceholders} so a
     * {@code ?} after an escaped quote is classified correctly per dialect.
     */
    protected boolean supportsBackslashStringEscapes() {
        return false;
    }

    /** Statement resetting one static-state key (PERFORM ... vs SET @... = titan_rt_...). */
    protected abstract String staticResetCallSql(String key);

    /**
     * Statement writing one static-state key/value pair. {@code valueSql} is already coerced to
     * text by {@link #coerceToTextSql(ExpressionNode)} at the call site (B-10), so booleans arrive
     * as {@code 'true'}/{@code 'false'} on both dialects.
     */
    protected abstract String staticSetCallSql(String keySql, String valueSql);

    /** Telemetry success UPDATE emitted before RETURN when observability is enabled. */
    protected abstract String telemetrySuccessSql();

    /** Statement raising the NullPointerException-equivalent error inside a null guard. */
    protected abstract String nullPointerSignalSql(String location);

    /** Identifiers reserved by the emitter scaffolding, never usable for user variables. */
    protected abstract List<String> reservedRoutineIdentifiers(boolean observability);

    /** Renders the body of a nested block; declaration handling differs per dialect. */
    protected abstract String blockBody(Block block);

    /**
     * Quotes one SQL identifier for this dialect (plan 1.3b): PostgreSQL double-quotes the
     * identifier and doubles embedded double quotes; MySQL backtick-quotes it and doubles
     * embedded backticks. Routing every schema-derived identifier (tables, columns, aliases,
     * routine/trigger/event/CTE names, record fields) through this hook retires the
     * reserved-word problem wholesale instead of maintaining per-dialect word lists.
     *
     * <p>Deliberately <em>not</em> quoted (each exclusion is documented at its emission site):
     * routine-local variables and parameters (allocator-generated, {@code p_}/{@code v_}/
     * {@code __titan_} prefixed — and MySQL does not accept quoted identifiers uniformly across
     * DECLARE/FETCH-INTO positions), the {@code NEW}/{@code OLD} trigger pseudo-rows, the
     * {@code *} projection, built-in function names (which share the
     * {@link FunctionCallExpression} channel with generated routine names and must stay bare:
     * {@code "COUNT"(x)} fails on PostgreSQL case-folding and {@code `COUNT`(x)} is parsed as a
     * stored-function reference by MySQL), and the fixed runtime-namespace references
     * ({@code titan_runtime.*} / {@code titan_rt_*}).</p>
     *
     * @throws IllegalStateException for null, blank or NUL-containing identifiers (an
     *         internal invariant: lowering never produces such identifiers)
     */
    protected abstract String quoteIdentifier(String identifier);

    /**
     * Validation shared by the dialect {@link #quoteIdentifier(String)} implementations:
     * rejects null, blank and NUL-containing identifiers (NUL is not representable inside a
     * quoted identifier on either dialect and is a classic smuggling vector).
     *
     * <p>Internal-invariant assertion: user-written identifiers are validated with positioned
     * diagnostics during discovery/lowering, so an invalid identifier reaching the emitter
     * means a lowering bug, not a user error. Enforced by ExceptionDisciplineEnforcementTest.</p>
     */
    protected static String requireQuotableIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalStateException(
                    "internal: SQL identifier must be non-null and non-blank, got: "
                            + (identifier == null ? "null" : "'" + identifier + "'"));
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalStateException(
                    "internal: SQL identifier must not contain NUL characters: '"
                            + identifier.replace("\0", "\\0") + "'");
        }
        return identifier;
    }

    /**
     * Builds the emit-time dialect-capability diagnostic (plan 1.3, audit invariant §10.4 #1):
     * the construct is valid Titan but cannot be represented on this dialect. Thrown as a
     * {@link TitanDiagnosticException} (code {@code TITAN-E001}) so callers see a structured
     * diagnostic rather than a raw {@code IllegalArgumentException}; no source position is
     * available at emission time, so the message must carry the routine/expression context.
     */
    protected static TitanDiagnosticException unsupportedOnDialect(String message) {
        return new TitanDiagnosticException(
                new TitanDiagnostic(TitanErrorCode.E001, message, null, null, null));
    }

    /**
     * Quotes a possibly dot-qualified relation or routine name part by part
     * ({@code schema.table} → {@code "schema"."table"}, bare {@code table} → {@code "table"}).
     *
     * <p>Catalogued formats (plan 1.3b): {@code SelectSql.from}, {@code JoinSpec.target},
     * DML {@code table} fields and {@code CallStatement.procedureName} carry either a bare
     * relation/routine name (from {@code DslQueryLowerer.tableName} — physical-table annotation
     * value, table-descriptor literal, CTE/inline-view name, or identifier) or a dot-qualified
     * {@code schema.table} (from {@code RowMaterializationRuntimePlan.tableName(TableRef)},
     * which joins schema and table with {@code '.'}). Alias suffixes ({@code "t a"} /
     * {@code "t AS a"}) are never produced — lateral aliases are structured on
     * {@link LateralSubquery#alias()} — so dots always separate qualifier parts and never occur
     * inside a single identifier, and no {@code AS}-splitting helper is needed.</p>
     */
    protected final String quoteQualifiedName(String qualifiedName) {
        requireQuotableIdentifier(qualifiedName);
        String[] parts = qualifiedName.split("\\.", -1);
        StringBuilder quoted = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                quoted.append('.');
            }
            quoted.append(quoteIdentifier(parts[i]));
        }
        return quoted.toString();
    }

    /**
     * Renders the table qualifier of a column reference. {@code NEW} and {@code OLD} are the
     * trigger pseudo-rows produced by trigger lowering (a plpgsql record variable / MySQL
     * transition row, not a schema object); quoting them ({@code "NEW".col}) breaks plpgsql
     * variable resolution, so they stay bare. Every other qualifier (table, CTE name, or
     * dot-qualified {@code schema.table}) is quoted part by part.
     */
    protected final String columnQualifierSql(String qualifier) {
        if ("NEW".equals(qualifier) || "OLD".equals(qualifier)) {
            return qualifier;
        }
        return quoteQualifiedName(qualifier);
    }

    /** Renders a projected/referenced column name; the {@code *} projection stays bare. */
    protected final String columnSql(String column) {
        if ("*".equals(column)) {
            return column;
        }
        return quoteIdentifier(column);
    }

    /** Type name used inside {@code CAST(... AS ...)}; MySQL casts text as {@code CHAR} and integers as {@code SIGNED}. */
    protected String castTypeSql(TirType type) {
        return sqlType(type);
    }

    /**
     * Renders {@code operand} and coerces it to text, the single chokepoint every boolean-to-text
     * site routes through (B-10 / TG-BLK-012). The semantic reference is Java's
     * {@link Boolean#toString(boolean)} — {@code 'true'}/{@code 'false'} — which PostgreSQL matches
     * natively ({@code boolean::text}) but MySQL does not: its {@code BOOLEAN} is {@code TINYINT},
     * so a bare {@code CAST(<boolean> AS CHAR)} yields {@code '1'}/{@code '0'} and the consumer's
     * JSON output diverged at boolean positions ({@code pageInfo.hasNextPage}, {@code isRepeatable})
     * on MySQL only. Boolean operands go through {@link #booleanToTextSql(String)} (dialect-correct
     * {@code true}/{@code false}); every other type keeps the plain text cast both dialects already
     * render identically.
     *
     * <p>"Boolean operand" is decided by {@link #isBooleanExpression(ExpressionNode)} on the lowered
     * TIR — the boolean-producing shapes (comparisons, {@code NOT}, {@code IS [NOT] NULL},
     * {@code EXISTS}, {@code IN}, boolean literals, {@code CASE}/{@code COALESCE} over boolean arms),
     * plus boolean-typed parameter/local references and calls to boolean-returning generated
     * routines (the consumer's {@code pageInfo.hasNextPage} reaches the JSON writer as a routine
     * call) — exactly the values that reach a text context as a boolean.</p>
     */
    protected final String coerceToTextSql(ExpressionNode operand) {
        String operandSql = operand.accept(this);
        if (isBooleanExpression(operand)) {
            return booleanToTextSql(operandSql);
        }
        return "CAST(" + operandSql + " AS " + castTypeSql(new TTextType()) + ")";
    }

    /**
     * Converts an already-rendered boolean operand to its {@code 'true'}/{@code 'false'} text form
     * (B-10). PostgreSQL's {@code boolean::text} already renders {@code true}/{@code false}, so the
     * default is the plain text cast; MySQL overrides this with an {@code IF}/{@code CASE} so its
     * {@code TINYINT}-backed booleans do not surface as {@code '1'}/{@code '0'}.
     */
    protected String booleanToTextSql(String operandSql) {
        return "CAST(" + operandSql + " AS " + castTypeSql(new TTextType()) + ")";
    }

    /**
     * Whether {@code expression} is a boolean-valued TIR node (B-10). Used to decide boolean-to-text
     * coercion ({@link #coerceToTextSql(ExpressionNode)}): covers the predicate-shaped nodes the
     * lowerer produces for boolean values, boolean literals, a {@code VariableRefExpression} whose
     * declared parameter/local type is boolean (resolved through the current routine's
     * {@link RoutineSqlNameAllocator}), and — crucially for the consumer's
     * {@code pageInfo.hasNextPage} / {@code isRepeatable} case — a {@code FunctionCallExpression} to
     * a generated routine whose registered return type is boolean
     * ({@link #routineReturnTypesBySqlName}). Recurses through the value-preserving
     * {@code CASE}/{@code COALESCE}/{@code CAST}-to-boolean carriers. Conservative by design: an
     * unrecognized node, or a variable with no recorded type, is treated as non-boolean and keeps
     * the plain text cast — correct for every non-boolean type on both dialects.
     */
    protected boolean isBooleanExpression(ExpressionNode expression) {
        return switch (expression) {
            case LiteralExpression literal ->
                    literal.type() instanceof TBooleanType || literal.value() instanceof Boolean;
            case NotExpression ignored -> true;
            case IsNullExpression ignored -> true;
            case IsNotNullExpression ignored -> true;
            case ExistsExpression ignored -> true;
            case InListExpression ignored -> true;
            case BinaryOpExpression binary -> isBooleanOperator(binary.operator());
            case CastExpression cast -> cast.targetType() instanceof TBooleanType;
            case VariableRefExpression variable ->
                    routineSqlNameAllocator.variableType(variable.name()) instanceof TBooleanType;
            case FunctionCallExpression call ->
                    routineReturnTypesBySqlName.get(call.name()) instanceof TBooleanType;
            case CaseWhenExpression caseWhen ->
                    caseWhen.elseValue() != null
                            && isBooleanExpression(caseWhen.elseValue())
                            && caseWhen.conditions().stream().allMatch(b -> isBooleanExpression(b.value()));
            case CoalesceExpression coalesce ->
                    !coalesce.expressions().isEmpty()
                            && coalesce.expressions().stream().allMatch(this::isBooleanExpression);
            default -> false;
        };
    }

    private static boolean isBooleanOperator(BinaryOperator operator) {
        return switch (operator) {
            case EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL,
                    AND, OR, LIKE -> true;
            default -> false;
        };
    }

    /**
     * Wraps an operand so the surrounding integral CAST truncates toward zero like a Java
     * fractional-to-integral cast: PostgreSQL {@code TRUNC(x)}, MySQL {@code TRUNCATE(x, 0)}.
     */
    protected abstract String truncateTowardZeroSql(String operandSql);

    /**
     * Shared {@link RaiseStatement} message rendering: a string-literal message renders exactly
     * as the historical inline literal; any other expression is wrapped in
     * {@code COALESCE(<expr>, 'Java throw')} because neither PG {@code RAISE ... MESSAGE} nor
     * MySQL {@code SIGNAL ... MESSAGE_TEXT} accepts a NULL message value (plan 2.2, F-10).
     */
    protected final String raiseMessageSql(ExpressionNode message) {
        if (message == null) {
            return "''";
        }
        if (message instanceof LiteralExpression literal && literal.value() instanceof String text) {
            return stringLiteral(text);
        }
        return "COALESCE(" + message.accept(this) + ", 'Java throw')";
    }

    /** Whether the raise message can be emitted inline as a single string literal. */
    protected final boolean isStringLiteralMessage(ExpressionNode message) {
        return message instanceof LiteralExpression literal && literal.value() instanceof String;
    }

    /** Renders the {@code RETURN ...;} statement itself; MySQL adds duration conversions. */
    protected String returnResultSql(ReturnStatement node) {
        return node.expression() == null ? "RETURN;" : "RETURN " + node.expression().accept(this) + ";";
    }

    /**
     * Whether a declaration keeps its initializer inline; MySQL additionally forces initializers
     * for period-typed declarations.
     */
    protected boolean shouldIncludeDeclarationInitializer(DeclarationNode declaration, List<StatementNode> statements) {
        return !(declaration instanceof DeclareVariable variable && hasOrderedInitializerAssignment(variable, statements));
    }

    // ------------------------------------------------------------------
    // Entry-point overload ladders (shared; the deepest overloads are dialect-specific).
    // ------------------------------------------------------------------

    public String emitProcedure(String schema, String name, SecurityMode securityMode, Block body) {
        return emitProcedure(schema, name, securityMode, body, inferRoutineParameters(body.declarations()), false);
    }

    public String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            List<RoutineParameter> routineParameters
    ) {
        return emitProcedure(schema, name, securityMode, body, routineParameters, false);
    }

    public String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, boolean observability) {
        return emitProcedure(schema, name, securityMode, body, inferRoutineParameters(body.declarations()), observability, List.of());
    }

    public String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitProcedure(schema, name, securityMode, body, routineParameters, observability, List.of());
    }

    public String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, boolean observability, List<String> sensitiveColumnsAccessed) {
        return emitProcedure(schema, name, securityMode, body, inferRoutineParameters(body.declarations()), observability, sensitiveColumnsAccessed);
    }

    public abstract String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability,
            List<String> sensitiveColumnsAccessed
    );

    public String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body) {
        return emitFunction(schema, name, securityMode, returnType, body, inferRoutineParameters(body.declarations()), false);
    }

    public String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            List<RoutineParameter> routineParameters
    ) {
        return emitFunction(schema, name, securityMode, returnType, body, routineParameters, false);
    }

    public String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability) {
        return emitFunction(schema, name, securityMode, returnType, body, inferRoutineParameters(body.declarations()), observability, List.of());
    }

    public String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitFunction(schema, name, securityMode, returnType, body, routineParameters, observability, List.of());
    }

    public String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability, List<String> sensitiveColumnsAccessed) {
        return emitFunction(schema, name, securityMode, returnType, body, inferRoutineParameters(body.declarations()), observability, sensitiveColumnsAccessed);
    }

    public abstract String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability,
            List<String> sensitiveColumnsAccessed
    );

    public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body) {
        return emitScheduledJob(schema, name, securityMode, scheduledJob, body, false);
    }

    public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability) {
        return emitScheduledJob(schema, name, securityMode, scheduledJob, body, observability, List.of());
    }

    public abstract String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, List<String> sensitiveColumnsAccessed);

    public abstract String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body);

    public abstract String emitView(String schema, String name, String sqlBody);

    public abstract String emitEnumLookup(String schema, EnumLookupSpec enumLookupSpec);

    public abstract String emitRecordModel(String schema, RecordModelSpec recordModelSpec);

    // ------------------------------------------------------------------
    // Shared routine helpers
    // ------------------------------------------------------------------

    protected final List<RoutineParameter> inferRoutineParameters(List<DeclarationNode> declarations) {
        return declarations.stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .filter(declaration -> declaration.name() != null && declaration.name().startsWith("p_"))
                .map(declaration -> new RoutineParameter(declaration.name(), declaration.name(), declaration.type()))
                .toList();
    }

    protected final boolean isRoutineParameter(DeclareVariable declaration) {
        return declaration.name() != null && routineSqlNameAllocator.isRoutineParameterDeclaration(declaration.name());
    }

    protected final String emittedVariableName(String name) {
        return routineSqlNameAllocator.emittedName(name);
    }

    private boolean hasOrderedInitializerAssignment(DeclareVariable variable, List<StatementNode> statements) {
        if (variable.initializer() == null) {
            return false;
        }
        for (StatementNode statement : statements) {
            if (statement instanceof Assign assign
                    && assign.target() instanceof VariableRefExpression target
                    && Objects.equals(target.name(), variable.name())
                    && Objects.equals(assign.expression(), variable.initializer())) {
                return true;
            }
        }
        return false;
    }

    protected final void emitStatements(List<StatementNode> statements) {
        for (StatementNode statement : statements) {
            String emitted = statement.accept(this);
            if (!emitted.isBlank()) {
                for (String line : emitted.split("\\n")) {
                    out.line(line);
                }
            }
        }
    }

    /** Quoted, optionally schema-qualified name for emitted objects (routines, tables, types). */
    protected final String qualify(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(name);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    /**
     * Member-level record function (constructor/accessor): the record's qualified base name
     * plus the {@link SqlNames#MEMBER_JOIN} suffix join. The join rule (why {@code __} is
     * collision-free against snake-cased type names) is documented on {@link SqlNames}.
     */
    protected final String qualifyRecordRoutine(String schema, String recordName, String suffix) {
        return qualify(schema, SqlNames.recordMemberName(recordName, suffix));
    }

    // ------------------------------------------------------------------
    // Shared statement visitors
    // ------------------------------------------------------------------

    @Override
    public String visitDeclareCursor(DeclareCursor node) {
        return "DECLARE " + emittedVariableName(node.name()) + " CURSOR FOR " + node.query().accept(this) + ";";
    }

    @Override
    public String visitIfStatement(IfStatement node) {
        StringBuilder sql = new StringBuilder("IF ")
                .append(node.condition().accept(this))
                .append(" THEN\n")
                .append(indentMultiline(blockBody(node.thenBlock())));

        for (ElseIfClause elseIf : node.elseIfClauses()) {
            sql.append('\n')
                    .append(elseIfKeyword())
                    .append(' ')
                    .append(elseIf.condition().accept(this))
                    .append(" THEN\n")
                    .append(indentMultiline(blockBody(elseIf.block())));
        }

        if (node.elseBlock() != null) {
            sql.append("\nELSE\n")
                    .append(indentMultiline(blockBody(node.elseBlock())));
        }

        sql.append("\nEND IF;");
        return sql.toString();
    }

    @Override
    public String visitReturnStatement(ReturnStatement node) {
        String result = returnResultSql(node);
        List<String> preReturnStatements = new ArrayList<>();
        if (!staticResetKeys.isEmpty()) {
            preReturnStatements.add(staticResetKeys.stream()
                    .map(this::staticResetCallSql)
                    .collect(Collectors.joining("\n")));
        }
        if (observabilityEnabled) {
            preReturnStatements.add(telemetrySuccessSql());
        }
        if (preReturnStatements.isEmpty()) {
            return result;
        }
        return String.join("\n", preReturnStatements) + "\n" + result;
    }

    @Override
    public String visitCallStatement(CallStatement node) {
        if ("__titan_static_set".equals(node.procedureName()) && node.arguments().size() == 2) {
            String key = node.arguments().get(0).accept(this);
            // Static state is stored as text; coerce the value through the single boolean-to-text
            // chokepoint (B-10) so a boolean key persists as 'true'/'false' on both dialects.
            String value = coerceToTextSql(node.arguments().get(1));
            return staticSetCallSql(key, value);
        }
        String args = node.arguments().stream().map(arg -> arg.accept(this)).collect(Collectors.joining(", "));
        // CALL targets are always generated stored-procedure names (never built-ins), so the
        // possibly dot-qualified name is quoted part by part.
        return "CALL " + quoteQualifiedName(node.procedureName()) + "(" + args + ");";
    }

    @Override
    public String visitExecuteSqlStatement(ExecuteSqlStatement node) {
        return node.sqlNode().accept(this) + ";";
    }

    @Override
    public String visitNullGuardStatement(NullGuardStatement node) {
        String location = node.sourceLocation() == null ? "unknown" : node.sourceLocation();
        String sourceComment = sourceCommentForLocation(location);
        String guardSql = "IF " + emittedVariableName(node.variableName()) + " IS NULL THEN "
                + nullPointerSignalSql(location) + " END IF;";
        return sourceComment == null ? guardSql : sourceComment + "\n" + guardSql;
    }

    @Override
    public String visitCloseCursorStatement(CloseCursorStatement node) {
        return "CLOSE " + emittedVariableName(node.cursorName()) + ";";
    }

    /**
     * Transaction control (JDBC I-10, WS-C Phase 2 §3.4). {@code COMMIT;}/{@code ROLLBACK;} are
     * spelled identically on PostgreSQL (plpgsql, in a procedure invoked by {@code CALL}) and MySQL
     * (a stored procedure), so the leaf renders once in the shared base. The emitter only renders the
     * statement; the mandatory outer-atomic {@code TITAN-W005} portability warning (and the
     * {@code TITAN-E001} reject in a function/trigger context) is owned by the recognizer gate that
     * runs before lowering — see {@code JdbcUsageRecognizer.recognizeTransactionControl} — because
     * this visitor has no positioned diagnostic sink.
     */
    @Override
    public String visitTransactionControlStatement(TransactionControlStatement node) {
        return switch (node.action()) {
            case COMMIT -> "COMMIT;";
            case ROLLBACK -> "ROLLBACK;";
        };
    }

    /**
     * Rewrites JDBC {@code ?} positional placeholders in opaque raw SQL into the dialect placeholder
     * ({@code $n} on PostgreSQL, {@code ?} on MySQL), one per ordinal in left-to-right order, while
     * skipping {@code ?} characters inside string literals, quoted identifiers, line/block comments
     * and (where supported) dollar-quoted strings. This is the {@code ?}-oriented sibling of
     * {@link #rewriteNamedParameters} (which handles {@code :name} only and is not reused for
     * {@code ?}), used by the native single-dialect raw read/cursor emission (WS-C Phase 2 §4).
     */
    protected final String rewritePositionalPlaceholders(String sql) {
        if (sql == null || sql.isEmpty() || sql.indexOf('?') < 0) {
            return sql;
        }

        StringBuilder rewritten = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        String dollarQuoteTag = null;
        int ordinal = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                rewritten.append(c);
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                rewritten.append(c);
                if (c == '*' && next == '/') {
                    rewritten.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (inSingleQuote) {
                rewritten.append(c);
                if (c == '\\' && supportsBackslashStringEscapes()) {
                    // MySQL backslash-escape: \' (or any \x) does not close the literal.
                    if (next != '\0') {
                        rewritten.append(next);
                        i++;
                    }
                    continue;
                }
                if (c == '\'' && next == '\'') {
                    rewritten.append(next);
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (dollarQuoteTag != null) {
                rewritten.append(c);
                if (c == '$' && sql.startsWith(dollarQuoteTag, i)) {
                    for (int k = 1; k < dollarQuoteTag.length(); k++) {
                        rewritten.append(sql.charAt(i + k));
                    }
                    i += dollarQuoteTag.length() - 1;
                    dollarQuoteTag = null;
                }
                continue;
            }
            if (inDoubleQuote) {
                rewritten.append(c);
                if (c == '\\' && supportsBackslashStringEscapes()) {
                    // MySQL backslash-escape: \" (or any \x) does not close the literal. Standard SQL
                    // / PostgreSQL never reaches this branch (flag is false) — a backslash is literal
                    // inside a quoted identifier there and "" remains the only escape.
                    if (next != '\0') {
                        rewritten.append(next);
                        i++;
                    }
                    continue;
                }
                if (c == '"' && next == '"') {
                    rewritten.append(next);
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                rewritten.append(c);
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                rewritten.append(c);
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                rewritten.append(c).append(next);
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                rewritten.append(c).append(next);
                i++;
                continue;
            }
            if (c == '$' && supportsDollarQuotedStrings()) {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd >= 0) {
                    String candidateTag = sql.substring(i, tagEnd + 1);
                    if (isDollarQuoteTag(candidateTag)) {
                        dollarQuoteTag = candidateTag;
                        rewritten.append(candidateTag);
                        i = tagEnd;
                        continue;
                    }
                }
            }
            if (c == '?') {
                rewritten.append(parameterPlaceholder(++ordinal));
                continue;
            }
            rewritten.append(c);
        }
        return rewritten.toString();
    }

    private static boolean isDollarQuoteTag(String candidate) {
        if (candidate.length() < 2 || candidate.charAt(0) != '$'
                || candidate.charAt(candidate.length() - 1) != '$') {
            return false;
        }
        for (int i = 1; i < candidate.length() - 1; i++) {
            char c = candidate.charAt(i);
            if (!(c == '_' || Character.isLetterOrDigit(c))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Shared SQL node visitors
    // ------------------------------------------------------------------

    @Override
    public String visitSelectSql(SelectSql node) {
        return renderSelect(node, null);
    }

    /**
     * Phase A4 / G2: binds a scalar/EXISTS query result to a routine local via {@code SELECT ...
     * INTO <var>}. Both PostgreSQL (plpgsql) and MySQL use this identical syntax with the INTO
     * target sitting between the select list and FROM, so it is rendered once in the shared base.
     *
     * <p>No-row behavior is dialect-documented at the {@link SelectIntoStatement} node: a scalar
     * SELECT INTO that matches no row leaves the local NULL on both dialects; an EXISTS INTO always
     * binds a boolean (false when empty). The local's name is remapped through the routine name
     * allocator like every other variable position.</p>
     */
    @Override
    public String visitSelectIntoStatement(SelectIntoStatement node) {
        return renderSelect(node.query(), emittedVariableName(node.variableName())) + ";";
    }

    private String renderSelect(SelectSql node, String intoVariable) {
        if (node.existsWrapper()) {
            SelectSql inner = new SelectSql(
                    node.columns(),
                    node.distinct(),
                    node.from(),
                    node.fromAlias(),
                    node.joins(),
                    node.where(),
                    node.groupBy(),
                    node.having(),
                    List.of(),
                    null,
                    null,
                    false,
                    null,
                    node.ctes());
            String existsSelect = "SELECT EXISTS (" + visitSelectSql(inner) + ")";
            return intoVariable == null ? existsSelect : existsSelect + " INTO " + intoVariable;
        }

        StringBuilder sql = new StringBuilder();

        if (node.ctes() != null && !node.ctes().isEmpty()) {
            boolean hasRecursive = node.ctes().stream().anyMatch(CteSpec::recursive);
            String ctesSql = node.ctes().stream().map(this::emitCte).collect(Collectors.joining(", "));
            sql.append("WITH ");
            if (hasRecursive) {
                sql.append("RECURSIVE ");
            }
            sql.append(ctesSql).append(' ');
        }

        sql.append(node.distinct() ? "SELECT DISTINCT " : "SELECT ");
        if (node.columns() == null || node.columns().isEmpty()) {
            sql.append('*');
        } else {
            sql.append(node.columns().stream().map(this::emitSelectColumn).collect(Collectors.joining(", ")));
        }
        if (intoVariable != null) {
            sql.append(" INTO ").append(intoVariable);
        }
        if (node.from() != null && !node.from().isBlank()) {
            sql.append(" FROM ").append(quoteQualifiedName(node.from()));
            if (node.fromAlias() != null && !node.fromAlias().isBlank()) {
                sql.append(" AS ").append(quoteIdentifier(node.fromAlias()));
            }
        }
        if (node.joins() != null) {
            for (JoinSpec join : node.joins()) {
                String target = join.lateralTarget() != null
                        ? join.lateralTarget().accept(this)
                        : quoteQualifiedName(join.target());
                sql.append(' ').append(joinKeyword(join.joinType())).append(' ').append(target);
                if (join.lateralTarget() == null && join.targetAlias() != null && !join.targetAlias().isBlank()) {
                    sql.append(" AS ").append(quoteIdentifier(join.targetAlias()));
                }
                if (join.condition() != null) {
                    sql.append(" ON ").append(join.condition().accept(this));
                }
            }
        }
        if (node.where() != null) {
            sql.append(" WHERE ").append(node.where().accept(this));
        }
        if (node.groupBy() != null && !node.groupBy().isEmpty()) {
            sql.append(' ').append(groupByClauseSql(node.groupBy()));
        }
        if (node.having() != null) {
            sql.append(" HAVING ").append(node.having().accept(this));
        }
        if (node.orderBy() != null && !node.orderBy().isEmpty()) {
            sql.append(" ORDER BY ")
                    .append(node.orderBy().stream().map(this::emitOrderBy).collect(Collectors.joining(", ")));
        }
        if (node.limit() != null) {
            sql.append(" LIMIT ").append(node.limit());
        }
        if (node.offset() != null) {
            sql.append(" OFFSET ").append(node.offset());
        }
        if (node.locking() != null) {
            if (node.locking().forUpdate()) {
                sql.append(" FOR UPDATE");
            } else if (node.locking().forShare()) {
                sql.append(" FOR SHARE");
            }
            if (node.locking().skipLocked()) {
                sql.append(" SKIP LOCKED");
            }
            if (node.locking().noWait()) {
                sql.append(" NOWAIT");
            }
        }
        return sql.toString();
    }

    @Override
    public String visitLateralSubquery(LateralSubquery node) {
        return "(" + node.subquery().accept(this) + ") AS " + quoteIdentifier(node.alias());
    }

    @Override
    public String visitUnionSql(UnionSql node) {
        return "(" + node.left().accept(this) + ") " + (node.all() ? "UNION ALL" : "UNION")
                + " (" + node.right().accept(this) + ")";
    }

    @Override
    public String visitIntersectSql(IntersectSql node) {
        return "(" + node.left().accept(this) + ") INTERSECT (" + node.right().accept(this) + ")";
    }

    @Override
    public String visitExceptSql(ExceptSql node) {
        return "(" + node.left().accept(this) + ") EXCEPT (" + node.right().accept(this) + ")";
    }

    // ------------------------------------------------------------------
    // Shared expression visitors
    // ------------------------------------------------------------------

    @Override
    public String visitColumnRefExpression(ColumnRefExpression node) {
        if (node.table() == null || node.table().isBlank()) {
            return columnSql(node.column());
        }
        return columnQualifierSql(node.table()) + "." + columnSql(node.column());
    }

    @Override
    public String visitVariableRefExpression(VariableRefExpression node) {
        // Routine-local variables/parameters stay unquoted (plan 1.3b exclusion): names are
        // allocator-generated (p_/v_/__titan_ prefixed, lowercase, sanitized) so they can never
        // collide with reserved words, MySQL does not accept quoted identifiers uniformly across
        // local-variable positions, and quoting would make plpgsql variable substitution inside
        // embedded SQL ambiguous with column references.
        return emittedVariableName(node.name());
    }

    @Override
    public String visitLiteralExpression(LiteralExpression node) {
        if (node.value() == null) {
            return "NULL";
        }
        if (node.value() instanceof String s) {
            return stringLiteral(s);
        }
        if (node.value() instanceof Character c) {
            return characterLiteral(c);
        }
        if (node.value() instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return String.valueOf(node.value());
    }

    @Override
    public String visitBinaryOpExpression(BinaryOpExpression node) {
        return "(" + node.left().accept(this) + " " + binaryOperator(node.operator()) + " " + node.right().accept(this)
                + ")";
    }

    @Override
    public String visitRecordConstructExpression(RecordConstructExpression node) {
        String args = node.arguments().stream().map(arg -> arg.accept(this)).collect(Collectors.joining(", "));
        return qualifyRecordRoutine(node.schema(), node.recordName(), "new") + "(" + args + ")";
    }

    @Override
    public String visitRecordFieldExpression(RecordFieldExpression node) {
        return qualifyRecordRoutine(node.schema(), node.recordName(), toSnakeCase(node.fieldName()))
                + "(" + node.record().accept(this) + ")";
    }

    @Override
    public String visitCaseWhenExpression(CaseWhenExpression node) {
        StringBuilder sql = new StringBuilder("CASE");
        for (CaseBranch branch : node.conditions()) {
            sql.append(" WHEN ").append(branch.condition().accept(this))
                    .append(" THEN ").append(branch.value().accept(this));
        }
        if (node.elseValue() != null) {
            sql.append(" ELSE ").append(node.elseValue().accept(this));
        }
        sql.append(" END");
        return sql.toString();
    }

    @Override
    public String visitSubqueryExpression(SubqueryExpression node) {
        return "(" + node.select().accept(this) + ")";
    }

    @Override
    public String visitIsNullExpression(IsNullExpression node) {
        return node.expression().accept(this) + " IS NULL";
    }

    @Override
    public String visitIsNotNullExpression(IsNotNullExpression node) {
        return node.expression().accept(this) + " IS NOT NULL";
    }

    @Override
    public String visitNotExpression(NotExpression node) {
        return "(NOT " + node.expression().accept(this) + ")";
    }

    @Override
    public String visitCastExpression(CastExpression node) {
        // B-10 (TG-BLK-012): a cast to text is the lowered form of String.valueOf(boolean); route
        // it through the single boolean-to-text chokepoint so MySQL renders 'true'/'false', not
        // '1'/'0'. (Non-text and truncating casts are unaffected — coerceToTextSql falls back to
        // the same plain CAST for non-boolean operands.)
        if (node.targetType() instanceof TTextType && !node.truncating()) {
            return coerceToTextSql(node.expression());
        }
        String operand = node.expression().accept(this);
        if (node.truncating()) {
            // Java fractional-to-integral casts truncate toward zero; a bare SQL CAST rounds.
            // Compose the dialect truncation function so (long) 7.9 emits 7, not 8 (plan 2.2, F-9).
            operand = truncateTowardZeroSql(operand);
        }
        return "CAST(" + operand + " AS " + castTypeSql(node.targetType()) + ")";
    }

    @Override
    public String visitCoalesceExpression(CoalesceExpression node) {
        String args = node.expressions().stream().map(expr -> expr.accept(this)).collect(Collectors.joining(", "));
        return "COALESCE(" + args + ")";
    }

    @Override
    public String visitExistsExpression(ExistsExpression node) {
        String exists = "EXISTS (" + node.subquery().accept(this) + ")";
        return node.negated() ? "NOT (" + exists + ")" : exists;
    }

    @Override
    public String visitInListExpression(InListExpression node) {
        String operator = node.negated() ? " NOT IN (" : " IN (";
        String operand = node.value().accept(this);
        if (node.subquery() != null) {
            return operand + operator + node.subquery().accept(this) + ")";
        }
        String items = node.items().stream().map(item -> item.accept(this)).collect(Collectors.joining(", "));
        return operand + operator + items + ")";
    }

    @Override
    public String visitWindowFunctionExpression(WindowFunctionExpression node) {
        String args = node.arguments().stream().map(arg -> arg.accept(this)).collect(Collectors.joining(", "));
        // Window function names are SQL built-ins (ROW_NUMBER, RANK, SUM, ...) and stay bare:
        // quoting them defeats PostgreSQL case-folding and MySQL built-in parsing (plan 1.3b).
        return node.function() + "(" + args + ") OVER (" + windowSpecSql(node.spec()) + ")";
    }

    @Override
    public String visitGroupingSetSpec(GroupingSetSpec node) {
        return switch (node.kind()) {
            case GROUPING_SETS -> "GROUPING SETS (" + node.sets().stream()
                    .map(this::groupingSetElementSql)
                    .collect(Collectors.joining(", ")) + ")";
            case ROLLUP -> "ROLLUP" + groupingSetElementSql(node.sets().getFirst());
            case CUBE -> "CUBE" + groupingSetElementSql(node.sets().getFirst());
            case SET -> groupingSetElementSql(node.sets().getFirst());
        };
    }

    protected final String groupingSetElementSql(List<ExpressionNode> expressions) {
        return "(" + expressions.stream().map(expr -> expr.accept(this)).collect(Collectors.joining(", ")) + ")";
    }

    /** Renders the {@code GROUP BY ...} clause; MySQL overrides for {@code WITH ROLLUP}. */
    protected String groupByClauseSql(List<ExpressionNode> groupBy) {
        return "GROUP BY " + groupBy.stream().map(expr -> expr.accept(this)).collect(Collectors.joining(", "));
    }

    private String windowSpecSql(WindowSpec spec) {
        List<String> fragments = new ArrayList<>();
        if (spec.partitionBy() != null && !spec.partitionBy().isEmpty()) {
            fragments.add("PARTITION BY " + spec.partitionBy().stream()
                    .map(expr -> expr.accept(this))
                    .collect(Collectors.joining(", ")));
        }
        if (spec.orderBy() != null && !spec.orderBy().isEmpty()) {
            fragments.add("ORDER BY " + spec.orderBy().stream()
                    .map(this::emitOrderBy)
                    .collect(Collectors.joining(", ")));
        }
        if (spec.frame() != null) {
            fragments.add(windowFrameUnitKeyword(spec.frame().unit()) + " BETWEEN "
                    + windowFrameBoundSql(spec.frame().start()) + " AND " + windowFrameBoundSql(spec.frame().end()));
        }
        return String.join(" ", fragments);
    }

    /** Keyword for a window frame unit; MySQL rejects {@code GROUPS}. */
    protected String windowFrameUnitKeyword(WindowFrameUnit unit) {
        return unit.name();
    }

    private String windowFrameBoundSql(WindowFrameBound bound) {
        return switch (bound.kind()) {
            case UNBOUNDED_PRECEDING -> "UNBOUNDED PRECEDING";
            case PRECEDING -> bound.offset().accept(this) + " PRECEDING";
            case CURRENT_ROW -> "CURRENT ROW";
            case FOLLOWING -> bound.offset().accept(this) + " FOLLOWING";
            case UNBOUNDED_FOLLOWING -> "UNBOUNDED FOLLOWING";
        };
    }

    /** Default rendering of a function call ({@code [schema.]name(args)}); shared fallthrough. */
    protected final String renderDefaultFunctionCall(FunctionCallExpression node) {
        String args = node.arguments().stream().map(arg -> arg.accept(this)).collect(Collectors.joining(", "));
        if (node.schema() == null || node.schema().isBlank()) {
            // Unqualified call names share one channel between SQL built-ins (COUNT, UPPER, ...)
            // and generated helper routines, so they stay bare (plan 1.3b exclusion): "COUNT"(x)
            // fails PostgreSQL case-folding and `COUNT`(x) is parsed as a stored-function
            // reference by MySQL. Generated helper names are snake_cased/sanitized lowercase.
            return node.name() + "(" + args + ")";
        }
        // Schema-qualified calls always target generated routines; quote both parts.
        return quoteIdentifier(node.schema()) + "." + quoteIdentifier(node.name()) + "(" + args + ")";
    }

    // ------------------------------------------------------------------
    // Shared rendering helpers
    // ------------------------------------------------------------------

    private String emitCte(CteSpec cte) {
        return quoteIdentifier(cte.name()) + " AS (" + cte.query().accept(this) + ")";
    }

    private String emitSelectColumn(SelectColumn column) {
        String expression = column.expression().accept(this);
        if (column.alias() == null || column.alias().isBlank()) {
            return expression;
        }
        return expression + " AS " + quoteIdentifier(column.alias());
    }

    /**
     * Renders one ORDER BY element. The default emits SQL-standard {@code NULLS FIRST/LAST}
     * for an explicit {@link NullsOrder} (PostgreSQL); MySQL overrides with an
     * {@code (expr IS NULL)} emulation key because it does not support the standard syntax.
     */
    protected String emitOrderBy(OrderBySpec spec) {
        String rendered = spec.expression().accept(this) + " " + spec.direction().name();
        if (spec.nulls() == null) {
            return rendered;
        }
        return rendered + (spec.nulls() == NullsOrder.FIRST ? " NULLS FIRST" : " NULLS LAST");
    }

    protected final String joinKeyword(JoinType joinType) {
        return switch (joinType) {
            case INNER -> "JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL_OUTER -> fullOuterJoinKeyword();
            case CROSS -> "CROSS JOIN";
            case LATERAL -> "JOIN LATERAL";
        };
    }

    private String binaryOperator(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            case LIKE -> "LIKE";
            case EQUAL -> "=";
            case NOT_EQUAL -> "<>";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case AND -> "AND";
            case OR -> "OR";
        };
    }

    protected final String indentMultiline(String text) {
        return indentMultiline(text, 1);
    }

    protected final String indentMultiline(String text, int levels) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String prefix = "    ".repeat(Math.max(1, levels));
        return text.lines().map(line -> prefix + line).collect(Collectors.joining("\n"));
    }

    protected final String oneBasedIndexSql(ExpressionNode zeroBasedIndex) {
        Integer constantIndex = integerLiteralValue(zeroBasedIndex);
        if (constantIndex != null) {
            return Integer.toString(constantIndex + 1);
        }
        return "(" + zeroBasedIndex.accept(this) + " + 1)";
    }

    protected final String substringLengthSql(ExpressionNode startInclusive, ExpressionNode endExclusive) {
        Integer start = integerLiteralValue(startInclusive);
        Integer end = integerLiteralValue(endExclusive);
        if (start != null && end != null) {
            return Integer.toString(end - start);
        }
        return "(" + endExclusive.accept(this) + " - " + startInclusive.accept(this) + ")";
    }

    private Integer integerLiteralValue(ExpressionNode expression) {
        if (expression instanceof LiteralExpression literal && literal.value() instanceof Number number) {
            return number.intValue();
        }
        if (expression instanceof BinaryOpExpression binary) {
            Integer left = integerLiteralValue(binary.left());
            Integer right = integerLiteralValue(binary.right());
            if (left == null || right == null) {
                return null;
            }
            return switch (binary.operator()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case DIVIDE -> right == 0 ? null : left / right;
                case MODULO -> right == 0 ? null : left % right;
                default -> null;
            };
        }
        return null;
    }

    /**
     * Rewrites {@code :name} parameter references in raw SQL into dialect placeholders, skipping
     * string literals, quoted identifiers, comments and — when the dialect supports them —
     * dollar-quoted strings.
     */
    protected final String rewriteNamedParameters(String sql, List<String> parameters) {
        if (sql == null || sql.isBlank() || parameters == null || parameters.isEmpty()) {
            return sql;
        }

        Map<String, String> placeholders = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            placeholders.put(parameters.get(i), parameterPlaceholder(i + 1));
        }

        StringBuilder rewritten = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        String dollarQuoteTag = null;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                rewritten.append(c);
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                rewritten.append(c);
                if (c == '*' && next == '/') {
                    rewritten.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (inSingleQuote) {
                rewritten.append(c);
                if (c == '\'' && next == '\'') {
                    rewritten.append(next);
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (dollarQuoteTag != null) {
                rewritten.append(c);
                if (c == '$' && sql.startsWith(dollarQuoteTag, i)) {
                    for (int k = 1; k < dollarQuoteTag.length(); k++) {
                        rewritten.append(sql.charAt(i + k));
                    }
                    i += dollarQuoteTag.length() - 1;
                    dollarQuoteTag = null;
                }
                continue;
            }

            if (inDoubleQuote) {
                rewritten.append(c);
                if (c == '"' && next == '"') {
                    rewritten.append(next);
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                rewritten.append(c).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                rewritten.append(c).append(next);
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '$' && supportsDollarQuotedStrings()) {
                String tag = readDollarQuoteTag(sql, i);
                if (tag != null) {
                    rewritten.append(tag);
                    i += tag.length() - 1;
                    dollarQuoteTag = tag;
                    continue;
                }
            }
            if (c == '\'') {
                rewritten.append(c);
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                rewritten.append(c);
                inDoubleQuote = true;
                continue;
            }

            if (c == ':' && (i == 0 || sql.charAt(i - 1) != ':')
                    && i + 1 < sql.length()
                    && isIdentifierStart(sql.charAt(i + 1))) {
                int j = i + 2;
                while (j < sql.length() && isIdentifierPart(sql.charAt(j))) {
                    j++;
                }
                String name = sql.substring(i + 1, j);
                String replacement = placeholders.get(name);
                if (replacement != null) {
                    rewritten.append(replacement);
                    i = j - 1;
                    continue;
                }
            }

            rewritten.append(c);
        }

        return rewritten.toString();
    }

    private static String readDollarQuoteTag(String sql, int startIndex) {
        if (sql == null || startIndex < 0 || startIndex >= sql.length() || sql.charAt(startIndex) != '$') {
            return null;
        }
        int i = startIndex + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '$') {
                return sql.substring(startIndex, i + 1);
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
            i++;
        }
        return null;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private String sourceCommentForLocation(String sourceLocation) {
        int split = sourceLocation.lastIndexOf(':');
        if (split <= 0 || split == sourceLocation.length() - 1) {
            return null;
        }
        String sourceFile = sourceLocation.substring(0, split);
        String linePart = sourceLocation.substring(split + 1);
        try {
            int sourceLine = Integer.parseInt(linePart);
            if (sourceLine <= 0) {
                return null;
            }
            return "-- titan:source:" + sourceFile + ":" + sourceLine;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Shared enum-lookup helpers
    // ------------------------------------------------------------------

    protected final String renderEnumTuple(int ordinal, EnumLookupSpec.EnumValue value, List<EnumLookupSpec.EnumField> fields) {
        StringBuilder tuple = new StringBuilder().append(ordinal).append(", ").append(stringLiteral(value.key()));
        for (int i = 0; i < value.fieldValues().size(); i++) {
            String fieldValue = value.fieldValues().get(i);
            EnumLookupSpec.EnumField field = i < fields.size() ? fields.get(i) : null;
            tuple.append(", ").append(toSqlLiteral(fieldValue, field == null ? null : field.typeName()));
        }
        return tuple.toString();
    }

    private String toSqlLiteral(String value, String javaType) {
        if (value == null || "null".equalsIgnoreCase(value)) {
            return "NULL";
        }
        if (isBooleanType(javaType)) {
            return "true".equalsIgnoreCase(value) ? "TRUE" : "FALSE";
        }
        if (isNumericType(javaType)) {
            return value;
        }
        return stringLiteral(value);
    }

    private boolean isBooleanType(String javaType) {
        return "boolean".equals(javaType) || "Boolean".equals(javaType) || "java.lang.Boolean".equals(javaType);
    }

    private boolean isNumericType(String javaType) {
        if (javaType == null) {
            return false;
        }
        return switch (javaType) {
            case "byte", "Byte", "java.lang.Byte",
                 "short", "Short", "java.lang.Short",
                 "int", "Integer", "java.lang.Integer",
                 "long", "Long", "java.lang.Long",
                 "float", "Float", "java.lang.Float",
                 "double", "Double", "java.lang.Double",
                 "BigDecimal", "java.math.BigDecimal" -> true;
            default -> false;
        };
    }

    protected final String enumBackingFieldForMethod(String methodName, List<EnumLookupSpec.EnumField> fields) {
        String candidate = methodName;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            candidate = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            candidate = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        String snakeCandidate = toSnakeCase(candidate);
        for (EnumLookupSpec.EnumField field : fields) {
            if (field.name().equals(snakeCandidate)) {
                return field.name();
            }
        }
        return null;
    }

    /**
     * The single snake-casing authority for emitted SQL names. Type mappers must use this exact
     * implementation too: the non-alphanumeric cleanup below means a record named {@code My$Record}
     * maps to the same SQL type name on every code path (audit S2 drift fix).
     */
    static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) {
            return "enum_lookup";
        }
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .toLowerCase()
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    // ------------------------------------------------------------------
    // Artifact object descriptors (plan 4.4, audit G-10)
    // ------------------------------------------------------------------

    @Override
    public List<SqlObject> describeProcedure(String schema, String name, List<RoutineParameter> parameters) {
        return List.of(new SqlObject(
                SqlObject.Kind.PROCEDURE,
                schema,
                name,
                describeRoutineParameters(parameters),
                "",
                ""));
    }

    @Override
    public List<SqlObject> describeFunction(String schema, String name, TirType returnType, List<RoutineParameter> parameters) {
        return List.of(new SqlObject(
                SqlObject.Kind.FUNCTION,
                schema,
                name,
                describeRoutineParameters(parameters),
                returnType == null ? "" : sqlType(returnType),
                ""));
    }

    /**
     * Typed parameter descriptors from the same {@link RoutineParameter} list and
     * {@link #sqlType(TirType)} mapping the emit methods use. {@code NUMERIC(10,2)} and friends
     * survive as one parameter — nothing is re-split on commas downstream.
     */
    protected final List<SqlObject.Parameter> describeRoutineParameters(List<RoutineParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        return parameters.stream()
                .map(parameter -> new SqlObject.Parameter(parameter.sqlName(), sqlType(parameter.type())))
                .toList();
    }

    /** Table a trigger attaches to, schema-qualified the same way the emit methods qualify it. */
    protected final String describedTriggerTable(String schema, TriggerSpec trigger) {
        if (trigger.table().contains(".")) {
            return trigger.table();
        }
        return (schema == null || schema.isBlank()) ? trigger.table() : schema + "." + trigger.table();
    }

    // ------------------------------------------------------------------
    // Shared static-state analysis
    // ------------------------------------------------------------------

    protected final List<String> collectStaticResetKeys(Block body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        collectStaticResetKeysFromStatements(body.statements(), keys);
        return List.copyOf(keys);
    }

    private void collectStaticResetKeysFromStatements(List<StatementNode> statements, Set<String> keys) {
        for (StatementNode statement : statements) {
            switch (statement) {
                case Assign assign -> {
                    collectStaticResetKeysFromExpression(assign.target(), keys);
                    collectStaticResetKeysFromExpression(assign.expression(), keys);
                }
                case IfStatement ifStatement -> {
                    collectStaticResetKeysFromExpression(ifStatement.condition(), keys);
                    collectStaticResetKeysFromStatements(ifStatement.thenBlock().statements(), keys);
                    if (ifStatement.elseBlock() != null) {
                        collectStaticResetKeysFromStatements(ifStatement.elseBlock().statements(), keys);
                    }
                    for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                        collectStaticResetKeysFromExpression(clause.condition(), keys);
                        collectStaticResetKeysFromStatements(clause.block().statements(), keys);
                    }
                }
                case WhileStatement whileStatement -> {
                    collectStaticResetKeysFromExpression(whileStatement.condition(), keys);
                    collectStaticResetKeysFromStatements(whileStatement.body().statements(), keys);
                }
                case ForCursorStatement forCursor -> collectStaticResetKeysFromStatements(forCursor.body().statements(), keys);
                case ForEachStatement forEach -> {
                    collectStaticResetKeysFromExpression(forEach.iterable(), keys);
                    collectStaticResetKeysFromStatements(forEach.body().statements(), keys);
                }
                case ForRangeStatement forRange -> {
                    collectStaticResetKeysFromExpression(forRange.start(), keys);
                    collectStaticResetKeysFromExpression(forRange.end(), keys);
                    collectStaticResetKeysFromStatements(forRange.body().statements(), keys);
                }
                case ReturnStatement returnStatement -> collectStaticResetKeysFromExpression(returnStatement.expression(), keys);
                case CallStatement call -> {
                    if ("__titan_static_set".equals(call.procedureName()) && !call.arguments().isEmpty()) {
                        extractStaticKey(call.arguments().getFirst(), keys);
                    }
                    call.arguments().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
                }
                case ExecuteSqlStatement ignored -> {
                }
                case LoopStatement loop -> collectStaticResetKeysFromStatements(loop.body().statements(), keys);
                case TryCatchFinallyStatement tryCatchFinally -> {
                    collectStaticResetKeysFromStatements(tryCatchFinally.tryBlock().statements(), keys);
                    for (CatchClause catchClause : tryCatchFinally.catches()) {
                        collectStaticResetKeysFromStatements(catchClause.body().statements(), keys);
                    }
                    if (tryCatchFinally.finallyBlock() != null) {
                        collectStaticResetKeysFromStatements(tryCatchFinally.finallyBlock().statements(), keys);
                    }
                }
                case Block block -> collectStaticResetKeysFromStatements(block.statements(), keys);
                default -> {
                }
            }
        }
    }

    private void collectStaticResetKeysFromExpression(ExpressionNode expression, Set<String> keys) {
        if (expression == null) {
            return;
        }
        switch (expression) {
            case FunctionCallExpression functionCall -> {
                if ("__titan_static_get".equals(functionCall.name()) && !functionCall.arguments().isEmpty()) {
                    extractStaticKey(functionCall.arguments().getFirst(), keys);
                }
                functionCall.arguments().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
            }
            case RecordConstructExpression recordConstruct ->
                    recordConstruct.arguments().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
            case RecordFieldExpression recordField ->
                    collectStaticResetKeysFromExpression(recordField.record(), keys);
            case ArrayConstructExpression arrayConstruct ->
                    arrayConstruct.elements().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
            case ArrayLengthExpression arrayLength ->
                    collectStaticResetKeysFromExpression(arrayLength.array(), keys);
            case ArrayGetExpression arrayGet -> {
                collectStaticResetKeysFromExpression(arrayGet.array(), keys);
                collectStaticResetKeysFromExpression(arrayGet.index(), keys);
            }
            case BinaryOpExpression binary -> {
                collectStaticResetKeysFromExpression(binary.left(), keys);
                collectStaticResetKeysFromExpression(binary.right(), keys);
            }
            case CaseWhenExpression caseWhen -> {
                caseWhen.conditions().forEach(branch -> {
                    collectStaticResetKeysFromExpression(branch.condition(), keys);
                    collectStaticResetKeysFromExpression(branch.value(), keys);
                });
                collectStaticResetKeysFromExpression(caseWhen.elseValue(), keys);
            }
            case IsNullExpression isNull -> collectStaticResetKeysFromExpression(isNull.expression(), keys);
            case IsNotNullExpression isNotNull -> collectStaticResetKeysFromExpression(isNotNull.expression(), keys);
            case CastExpression cast -> collectStaticResetKeysFromExpression(cast.expression(), keys);
            case CoalesceExpression coalesce -> coalesce.expressions().forEach(value -> collectStaticResetKeysFromExpression(value, keys));
            case SubqueryExpression ignored -> {
            }
            case ExistsExpression ignored -> {
            }
            case WindowFunctionExpression window -> {
                window.arguments().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
                if (window.spec() != null) {
                    window.spec().partitionBy().forEach(arg -> collectStaticResetKeysFromExpression(arg, keys));
                    window.spec().orderBy().forEach(orderBy -> collectStaticResetKeysFromExpression(orderBy.expression(), keys));
                }
            }
            case GroupingSetSpec groupingSet -> groupingSet.sets()
                    .forEach(set -> set.forEach(expr -> collectStaticResetKeysFromExpression(expr, keys)));
            default -> {
            }
        }
    }

    private void extractStaticKey(ExpressionNode keyExpression, Set<String> keys) {
        if (keyExpression instanceof LiteralExpression literal && literal.value() instanceof String key && !key.isBlank()) {
            keys.add(key);
        }
    }
}
