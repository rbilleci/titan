package io.titan.transpiler.tir;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreePath;
import io.titan.transpiler.LoweringContext;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.ArrayType;

/**
 * Expression lowering for the Java-to-TIR pass: literals, unary/binary operators,
 * method invocations (including the receiver-gated known-method dispatch), member selects,
 * identifiers, records, arrays, conditionals, and switch expressions.
 */
final class ExpressionLowerer {

    private ExpressionLowerer() {
    }

    /**
     * Active JDBC per-row read rewrite (WS-C Phase 2 I-5): while lowering a {@link RawCursorStatement}
     * body, an in-loop {@code rs.getX(col)} resolves to the {@link VariableRefExpression} of the row
     * local FETCHed for that column. {@code null} outside a JDBC cursor body. Set/cleared by
     * {@link JdbcStatementLowerer} around the body lowering and restored in a finally to nest cleanly.
     *
     * <p>Held in a {@link ThreadLocal} (rather than a bare static field) so that even if lowering is
     * ever parallelized or re-entered across threads, one method's cursor-body rewrite can never leak
     * into another's — the per-pass swap+restore stays correct regardless (WS-C Phase 2b polish).</p>
     */
    interface JdbcRowReadResolver {
        /** The row-local name for this {@code rs.getX(col)} invocation, or {@code null} if not one. */
        String rowLocalFor(MethodInvocationTree invocation, ParsedSources parsedSources);
    }

    private static final ThreadLocal<JdbcRowReadResolver> JDBC_ROW_READS = new ThreadLocal<>();

    /** Compile-time substitution used while a JDBC binder helper is inlined into its caller. */
    interface InlineVariableResolver {
        ExpressionNode resolve(Element element);
    }

    private static final ThreadLocal<InlineVariableResolver> INLINE_VARIABLES = new ThreadLocal<>();

    static JdbcRowReadResolver swapJdbcRowReads(JdbcRowReadResolver resolver) {
        JdbcRowReadResolver previous = JDBC_ROW_READS.get();
        if (resolver == null) {
            JDBC_ROW_READS.remove();
        } else {
            JDBC_ROW_READS.set(resolver);
        }
        return previous;
    }

    static InlineVariableResolver swapInlineVariables(InlineVariableResolver resolver) {
        InlineVariableResolver previous = INLINE_VARIABLES.get();
        if (resolver == null) {
            INLINE_VARIABLES.remove();
        } else {
            INLINE_VARIABLES.set(resolver);
        }
        return previous;
    }

    private static TypeMirror typeMirror(ParsedSources parsedSources, Tree tree) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, tree);
        return path == null ? null : parsedSources.trees().getTypeMirror(path);
    }

    static ExpressionNode lowerExpression(ExpressionTree expression, ParsedSources parsedSources) {
        return switch (expression.getKind()) {
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL,
                    BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, NULL_LITERAL -> lowerLiteral((LiteralTree) expression);
            case IDENTIFIER -> lowerIdentifierExpression((IdentifierTree) expression, parsedSources);
            case PARENTHESIZED -> lowerExpression(((ParenthesizedTree) expression).getExpression(), parsedSources);
            case CONDITIONAL_EXPRESSION -> lowerConditional((ConditionalExpressionTree) expression, parsedSources);
            case UNARY_PLUS, UNARY_MINUS, LOGICAL_COMPLEMENT -> lowerUnary((UnaryTree) expression, parsedSources);
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER,
                    EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL,
                    CONDITIONAL_AND, CONDITIONAL_OR -> lowerBinary((BinaryTree) expression, parsedSources);
            case METHOD_INVOCATION -> lowerMethodInvocationExpression((MethodInvocationTree) expression, parsedSources);
            case NEW_CLASS -> lowerNewClassExpression((NewClassTree) expression, parsedSources);
            case NEW_ARRAY -> lowerNewArrayExpression((NewArrayTree) expression, parsedSources);
            case ARRAY_ACCESS -> lowerArrayAccessExpression((ArrayAccessTree) expression, parsedSources);
            case MEMBER_SELECT -> lowerMemberSelectExpression((MemberSelectTree) expression, parsedSources);
            case SWITCH_EXPRESSION -> lowerSwitchExpression((SwitchExpressionTree) expression, parsedSources);
            case TYPE_CAST -> lowerTypeCast((TypeCastTree) expression, parsedSources);
            default -> throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported expression in P0 lowering: " + expression.getKind(),
                    expression,
                    parsedSources,
                    null);
        };
    }

    /**
     * Lowers a Java cast per the {@link JavaCastClassifier} decision table (plan 2.2, F-9):
     * identity casts lower to the bare operand, numeric widening to a plain SQL CAST, and
     * fractional-to-integral narrowing to a truncating CAST reproducing Java's
     * truncate-toward-zero; everything else is rejected with a positioned diagnostic.
     */
    private static ExpressionNode lowerTypeCast(TypeCastTree cast, ParsedSources parsedSources) {
        TypeMirror sourceType = typeMirror(parsedSources, cast.getExpression());
        TypeMirror targetType = typeMirror(parsedSources, cast.getType());
        JavaCastClassifier.Classification classification =
                JavaCastClassifier.classify(sourceType, targetType, parsedSources.types());
        return switch (classification.kind()) {
            case IDENTITY -> lowerExpression(cast.getExpression(), parsedSources);
            case WIDENING -> new CastExpression(
                    lowerExpression(cast.getExpression(), parsedSources),
                    classification.targetTirType());
            case FRACTIONAL_TRUNCATION -> new CastExpression(
                    lowerExpression(cast.getExpression(), parsedSources),
                    classification.targetTirType(),
                    true);
            case UNSUPPORTED -> throw LowererSupport.unsupportedFeature(
                    classification.rejectionDetail() + ": '" + cast + "'",
                    cast,
                    parsedSources,
                    classification.suggestion());
        };
    }

    private static ExpressionNode lowerMemberSelectExpression(MemberSelectTree memberSelect, ParsedSources parsedSources) {
        if ("length".contentEquals(memberSelect.getIdentifier())) {
            TreePath expressionPath = LowererSupport.resolveTreePath(parsedSources, memberSelect.getExpression());
            TypeMirror expressionType = expressionPath == null ? null : parsedSources.trees().getTypeMirror(expressionPath);
            if (expressionType != null && expressionType.getKind() == TypeKind.ARRAY) {
                return new ArrayLengthExpression(lowerExpression(memberSelect.getExpression(), parsedSources));
            }
        }

        TreePath memberPath = LowererSupport.resolveTreePath(parsedSources, memberSelect);
        if (memberPath != null) {
            Element element = parsedSources.trees().getElement(memberPath);
            if (element != null && element.getKind() == ElementKind.ENUM_CONSTANT) {
                return new LiteralExpression(element.getSimpleName().toString(), new TTextType());
            }
            if (element != null && element.getKind() == ElementKind.FIELD
                    && element.getEnclosingElement() instanceof TypeElement enumType
                    && enumType.getKind() == ElementKind.ENUM) {
                // B-2 (TG-BLK-005): the helper name is built from the enum's source-local
                // qualified name with the SqlNames member join, mirroring emitEnumLookup.
                String helperName = SqlNames.enumMemberName(
                        LowererSupport.sourceLocalTypeName(enumType),
                        toSnakeCase(element.getSimpleName().toString()));
                ExpressionNode receiver = lowerExpression(memberSelect.getExpression(), parsedSources);
                return new FunctionCallExpression(helperName, List.of(receiver), null);
            }
        }

        ExpressionNode foldedConstant = foldStaticFinalCompileTimeConstant(memberSelect, parsedSources);
        if (foldedConstant != null) {
            return foldedConstant;
        }

        ExpressionNode foldedBigDecimalConstant = foldBigDecimalNamedConstant(memberSelect, parsedSources);
        if (foldedBigDecimalConstant != null) {
            return foldedBigDecimalConstant;
        }

        String staticFieldKey = resolveStaticFieldKey(memberSelect, parsedSources);
        if (staticFieldKey != null) {
            return new FunctionCallExpression("__titan_static_get", List.of(
                    new LiteralExpression(staticFieldKey, new TTextType())
            ), null);
        }

        return new VariableRefExpression(memberSelect.toString());
    }

    private static ExpressionNode lowerNewArrayExpression(NewArrayTree newArray, ParsedSources parsedSources) {
        TypeMirror arrayType = typeMirror(parsedSources, newArray);
        TirType elementType = new TTextType();
        if (arrayType instanceof ArrayType resolvedArrayType) {
            elementType = LowererSupport.mapType(resolvedArrayType.getComponentType(), "array creation '" + newArray + "'");
        } else if (newArray.getType() != null) {
            TypeMirror declaredType = typeMirror(parsedSources, newArray.getType());
            if (declaredType != null) {
                elementType = LowererSupport.mapType(declaredType, "array creation '" + newArray + "'");
            }
        }
        List<ExpressionNode> elements = newArray.getInitializers() == null
                ? List.of()
                : newArray.getInitializers().stream()
                        .map(initializer -> lowerExpression(initializer, parsedSources))
                        .toList();
        return new ArrayConstructExpression(elementType, elements);
    }

    private static ExpressionNode lowerArrayAccessExpression(ArrayAccessTree arrayAccess, ParsedSources parsedSources) {
        TirType elementType = arrayElementType(
                typeMirror(parsedSources, arrayAccess.getExpression()),
                "array access '" + arrayAccess + "'");
        return new ArrayGetExpression(
                lowerExpression(arrayAccess.getExpression(), parsedSources),
                lowerExpression(arrayAccess.getIndex(), parsedSources),
                elementType);
    }

    private static TirType arrayElementType(TypeMirror arrayLikeType, String context) {
        if (arrayLikeType instanceof ArrayType arrayType) {
            return LowererSupport.mapType(arrayType.getComponentType(), context);
        }
        if (arrayLikeType instanceof DeclaredType declaredType
                && declaredType.asElement() instanceof TypeElement typeElement
                && "java.util.List".contentEquals(typeElement.getQualifiedName())
                && declaredType.getTypeArguments().size() == 1) {
            return LowererSupport.mapType(declaredType.getTypeArguments().getFirst(), context);
        }
        return new TTextType();
    }

    private static boolean isJavaUtilListOwner(ExpressionTree ownerExpression, ParsedSources parsedSources) {
        TreePath ownerPath = LowererSupport.resolveTreePath(parsedSources, ownerExpression);
        Element ownerElement = ownerPath == null ? null : parsedSources.trees().getElement(ownerPath);
        if (ownerElement instanceof TypeElement typeElement) {
            return "java.util.List".contentEquals(typeElement.getQualifiedName());
        }
        String rendered = ownerExpression.toString();
        return "List".equals(rendered) || "java.util.List".equals(rendered);
    }

    private static TirType listFactoryElementType(MethodInvocationTree invocation, ParsedSources parsedSources) {
        String context = "List factory call '" + invocation + "'";
        TypeMirror returnType = typeMirror(parsedSources, invocation);
        if (returnType instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()) {
            return LowererSupport.mapType(declaredType.getTypeArguments().getFirst(), context);
        }
        if (!invocation.getArguments().isEmpty()) {
            TypeMirror firstArgumentType = typeMirror(parsedSources, invocation.getArguments().getFirst());
            if (firstArgumentType != null) {
                return LowererSupport.mapType(firstArgumentType, context);
            }
        }
        return new TTextType();
    }

    private static ExpressionNode lowerMethodInvocationExpression(
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        TreePath invocationPath = LowererSupport.resolveTreePath(parsedSources, invocation);
        String resolvedSignature = LowererSupport.resolvedMethodSignatureKey(invocationPath, parsedSources);
        ExecutableElement resolvedMethod = LowererSupport.resolvedMethodElement(invocationPath, parsedSources);

        ExpressionNode triggerRowRead = tryLowerTriggerRowRead(invocation, parsedSources);
        if (triggerRowRead != null) {
            return triggerRowRead;
        }

        // WS-C Phase 2 (I-5): inside a JDBC cursor body, an in-loop rs.getX(col) reads the per-row
        // local FETCHed for that column (a VariableRefExpression), not a SQL function call.
        JdbcRowReadResolver rowReads = JDBC_ROW_READS.get();
        if (rowReads != null) {
            String rowLocal = rowReads.rowLocalFor(invocation, parsedSources);
            if (rowLocal != null) {
                return new VariableRefExpression(rowLocal);
            }
        }

        if (invocation.getMethodSelect() instanceof MemberSelectTree select
                && select.getExpression() instanceof IdentifierTree owner
                && "Math".contentEquals(owner.getName())
                && (resolvedMethod == null || isMethodOwnedBy(resolvedMethod, "java.lang.Math"))) {
            List<ExpressionNode> args = invocation.getArguments().stream()
                    .map(arg -> lowerExpression(arg, parsedSources))
                    .toList();
            return switch (select.getIdentifier().toString()) {
                case "abs" -> new FunctionCallExpression("ABS", args, null);
                case "max" -> new FunctionCallExpression("GREATEST", args, null);
                case "min" -> new FunctionCallExpression("LEAST", args, null);
                case "floor" -> new FunctionCallExpression("FLOOR", args, null);
                case "ceil" -> new FunctionCallExpression("CEIL", args, null);
                case "pow" -> new FunctionCallExpression("POWER", args, null);
                case "sqrt" -> new FunctionCallExpression("SQRT", args, null);
                case "log" -> new FunctionCallExpression("LN", args, null);
                case "log10" -> new FunctionCallExpression("__titan_math_log10", args, null);
                case "random" -> new FunctionCallExpression("__titan_math_random", List.of(), null);
                case "round" -> new FunctionCallExpression("__titan_math_round", args, null);
                // B-1 (TG-BLK-007): an unmapped Math method used to fall through to a bare SQL
                // call of the same name, which deploys fine and fails only when executed.
                default -> throw unknownMethodOnSupportedReceiver(
                        "Math", select.getIdentifier().toString(), SUPPORTED_MATH_METHODS, invocation, parsedSources);
            };
        }

        if (invocation.getMethodSelect() instanceof MemberSelectTree select
                && "valueOf".contentEquals(select.getIdentifier())
                && isStringTypeSelectOwner(select.getExpression())
                && (resolvedMethod == null || isMethodOwnedBy(resolvedMethod, "java.lang.String"))
                && invocation.getArguments().size() == 1) {
            return new CastExpression(lowerExpression(invocation.getArguments().getFirst(), parsedSources), new TTextType());
        }

        if (invocation.getMethodSelect() instanceof MemberSelectTree select
                && "format".contentEquals(select.getIdentifier())
                && isStringTypeSelectOwner(select.getExpression())
                && (resolvedMethod == null || isMethodOwnedBy(resolvedMethod, "java.lang.String"))) {
            return new FunctionCallExpression("__titan_str_format", invocation.getArguments().stream()
                    .map(arg -> lowerExpression(arg, parsedSources))
                    .toList(), null);
        }

        if (invocation.getMethodSelect() instanceof MemberSelectTree select
                && isTitanDslTextOwner(select.getExpression().toString(), resolvedMethod)
                && ("base64UrlEncodeUtf8".contentEquals(select.getIdentifier())
                        || "base64UrlDecodeUtf8".contentEquals(select.getIdentifier())
                        || "base64UrlAlphabetIndex".contentEquals(select.getIdentifier()))) {
            List<ExpressionNode> args = invocation.getArguments().stream()
                    .map(arg -> lowerExpression(arg, parsedSources))
                    .toList();
            return switch (select.getIdentifier().toString()) {
                case "base64UrlEncodeUtf8" -> staticSingleArgFunction(
                        "__titan_text_base64url_encode_utf8", args, "Text.base64UrlEncodeUtf8");
                case "base64UrlDecodeUtf8" -> staticSingleArgFunction(
                        "__titan_text_base64url_decode_utf8", args, "Text.base64UrlDecodeUtf8");
                case "base64UrlAlphabetIndex" -> staticSingleArgFunction(
                        "__titan_text_base64url_alphabet_index", args, "Text.base64UrlAlphabetIndex");
                // Internal invariant: the enclosing name guard admits exactly these three methods.
                default -> throw new IllegalStateException("internal: unreachable Titan text intrinsic");
            };
        }

        if (invocation.getMethodSelect() instanceof MemberSelectTree select) {
            String method = select.getIdentifier().toString();
            String owner = select.getExpression().toString();
            if ("of".equals(method) && isJavaUtilListOwner(select.getExpression(), parsedSources)) {
                TirType elementType = listFactoryElementType(invocation, parsedSources);
                return new ArrayConstructExpression(
                        elementType,
                        invocation.getArguments().stream()
                                .map(arg -> lowerExpression(arg, parsedSources))
                                .toList());
            }
            ExpressionNode recordAccessor = lowerRecordAccessorInvocation(invocation, select, parsedSources);
            if (recordAccessor != null) {
                return recordAccessor;
            }
            if (resolvedSignature != null && isSourceLocalConcreteInstanceHelper(resolvedMethod, parsedSources)) {
                return new FunctionCallExpression(
                        resolvedSignature,
                        lowerRoutineArguments(invocation, resolvedMethod, parsedSources),
                        null);
            }
            if ("now".equals(method) && (resolvedMethod == null || isJavaTimeMethod(resolvedMethod))) {
                if ("LocalDate".equals(owner)) {
                    return new FunctionCallExpression("__titan_time_localdate_now", List.of(), null);
                }
                if ("LocalTime".equals(owner)) {
                    return new FunctionCallExpression("__titan_time_localtime_now", List.of(), null);
                }
                if ("LocalDateTime".equals(owner)) {
                    return new FunctionCallExpression("__titan_time_localdatetime_now", List.of(), null);
                }
                if ("Instant".equals(owner)) {
                    return new FunctionCallExpression("__titan_time_instant_now", List.of(), null);
                }
                if ("ZonedDateTime".equals(owner)) {
                    return new FunctionCallExpression("__titan_time_zoneddatetime_now", List.of(), null);
                }
            }
            if ("ofEpochMilli".equals(method) && "Instant".equals(owner)
                    && (resolvedMethod == null || isJavaTimeMethod(resolvedMethod))) {
                List<ExpressionNode> args = invocation.getArguments().stream()
                        .map(arg -> lowerExpression(arg, parsedSources))
                        .toList();
                return staticSingleArgFunction("__titan_time_instant_of_epoch_millis", args,
                        "Instant.ofEpochMilli");
            }
            if ("zero".equals(method) && (resolvedMethod == null || isJavaTimeMethod(resolvedMethod))) {
                if (isDurationOwner(owner)) {
                    return new FunctionCallExpression("__titan_time_duration_zero", List.of(), null);
                }
                if (isPeriodOwner(owner)) {
                    return new FunctionCallExpression("__titan_time_period_zero", List.of(), null);
                }
            }

            if (isDurationOwner(owner) && (resolvedMethod == null || isJavaTimeMethod(resolvedMethod))) {
                List<ExpressionNode> args = invocation.getArguments().stream()
                        .map(arg -> lowerExpression(arg, parsedSources))
                        .toList();
                switch (method) {
                    case "ofDays":
                        return staticSingleArgFunction("__titan_time_duration_of_days", args, method);
                    case "ofHours":
                        return staticSingleArgFunction("__titan_time_duration_of_hours", args, method);
                    case "ofMinutes":
                        return staticSingleArgFunction("__titan_time_duration_of_minutes", args, method);
                    case "ofSeconds":
                        return staticSingleArgFunction("__titan_time_duration_of_seconds", args, method);
                    default:
                        break;
                }
            }

            if (isPeriodOwner(owner) && (resolvedMethod == null || isJavaTimeMethod(resolvedMethod))) {
                List<ExpressionNode> args = invocation.getArguments().stream()
                        .map(arg -> lowerExpression(arg, parsedSources))
                        .toList();
                switch (method) {
                    case "ofDays":
                        return staticSingleArgFunction("__titan_time_period_of_days", args, method);
                    case "ofMonths":
                        return staticSingleArgFunction("__titan_time_period_of_months", args, method);
                    case "ofYears":
                        return staticSingleArgFunction("__titan_time_period_of_years", args, method);
                    default:
                        break;
                }
            }

            ExpressionNode receiver = lowerExpression(select.getExpression(), parsedSources);
            if (isOptionalMethod(resolvedMethod, owner)) {
                return lowerOptionalInvocation(method, receiver, invocation, parsedSources);
            }

            List<ExpressionNode> args = invocation.getArguments().stream()
                    .map(arg -> lowerExpression(arg, parsedSources))
                    .toList();

            if (args.isEmpty()
                    && ("name".equals(method) || "toString".equals(method))
                    && isMethodOwnedBy(resolvedMethod, "java.lang.Enum")) {
                // Enum values lower as their constant-name strings, so java.lang.Enum's
                // name()/default toString() are identity (an enum-declared toString override
                // resolves to the enum itself and goes through the accessor path instead).
                // Before B-1 these fell through to a bare `name(...)` SQL call.
                return receiver;
            }

            ExpressionNode enumHelperCall = lowerEnumMethodAsHelperCall(resolvedMethod, method, receiver, args, invocation, parsedSources);
            if (enumHelperCall != null) {
                return enumHelperCall;
            }

            if ("equals".equals(method) && isStringEqualsMethod(resolvedMethod, owner)) {
                return lowerStringEquals(receiver, args);
            }

            ExpressionNode knownInstanceMethod = lowerKnownInstanceMethod(method, receiver, args, resolvedMethod, invocation, parsedSources);
            if (knownInstanceMethod != null) {
                return knownInstanceMethod;
            }
            if (resolvedSignature != null && resolvedMethod != null
                    && resolvedMethod.getModifiers().contains(Modifier.STATIC)) {
                return new FunctionCallExpression(
                        resolvedSignature,
                        lowerRoutineArguments(invocation, resolvedMethod, parsedSources),
                        null);
            }
            if ("equals".equals(method) && args.size() == 1) {
                // Null-safe equality pseudo-intrinsic: both emitters rewrite a two-argument
                // "equals" call to dialect SQL (IS NOT DISTINCT FROM / <=>). String.equals is
                // already handled above with Java's null-argument semantics; this branch keeps
                // equals on the remaining JDK scalar and enum receivers lowering as before.
                return new FunctionCallExpression(method, prepend(receiver, args), null);
            }
            // B-1 (TG-BLK-007): every other instance method used to fall through to a bare SQL
            // call named after the Java method (`equalsIgnoreCase(a, b)`), which deploys fine
            // and fails only when that code path executes. Reject at lowering time instead.
            throw unknownInstanceMethod(method, resolvedMethod, invocation, parsedSources);
        }

        if (resolvedSignature == null) {
            // B-1 (TG-BLK-007): an unresolved identifier-style call used to fall through to a
            // bare SQL call named after the Java method. Resolved calls lower to their
            // signature key, which the pipeline either remaps to a generated routine or
            // rejects loudly (remapRoutineName); unresolved ones must fail here, positioned.
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Method call '" + LowererSupport.invocationName(invocation)
                            + "' does not resolve to a transpilable method and has no SQL lowering",
                    invocation,
                    parsedSources,
                    "Declare the helper in the transpiled source set (static, or an instance method "
                            + "of a final class), or replace the call with a supported SQL/DSL construct");
        }
        return new FunctionCallExpression(
                resolvedSignature,
                lowerRoutineArguments(invocation, resolvedMethod, parsedSources),
                null);
    }

    /**
     * Lowers the data arguments of a call to another emitted routine.
     *
     * <p>{@code Connection} and {@code DataSource} parameters are Java/JDBC infrastructure anchors:
     * {@link TranspilationPipeline#routineParametersFor} omits them from the emitted SQL routine
     * signature because the called routine executes in the same database session. Calls must apply
     * the identical positional omission or the SQL invocation and declaration arities diverge.</p>
     */
    private static List<ExpressionNode> lowerRoutineArguments(
            MethodInvocationTree invocation,
            ExecutableElement resolvedMethod,
            ParsedSources parsedSources
    ) {
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        List<? extends VariableElement> parameters = resolvedMethod == null
                ? List.of()
                : resolvedMethod.getParameters();
        List<ExpressionNode> lowered = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            if (i < parameters.size() && isRoutineInfrastructureParameter(parameters.get(i))) {
                continue;
            }
            lowered.add(lowerExpression(arguments.get(i), parsedSources));
        }
        return List.copyOf(lowered);
    }

    private static boolean isRoutineInfrastructureParameter(VariableElement parameter) {
        if (!(parameter.asType() instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            return false;
        }
        String qualifiedName = typeElement.getQualifiedName().toString();
        return "java.sql.Connection".equals(qualifiedName)
                || "javax.sql.DataSource".equals(qualifiedName);
    }

    // ------------------------------------------------------------------
    // B-1 (TG-BLK-007): unknown methods on supported receivers are rejected with a positioned
    // TITAN-E001 naming the method and the supported alternatives for that receiver type,
    // mirroring the unresolved-helper path — never emitted as bare SQL calls.
    // ------------------------------------------------------------------

    private static final String SUPPORTED_MATH_METHODS =
            "abs, max, min, floor, ceil, pow, sqrt, log, log10, random, round";

    private static final String SUPPORTED_STRING_METHODS =
            "length, toUpperCase, toLowerCase, trim, strip, replace, isEmpty, contains, "
                    + "startsWith, endsWith, indexOf, charAt, substring, split, matches, format, "
                    + "equals, compareTo, valueOf";

    private static final String SUPPORTED_BIG_NUMBER_METHODS =
            "add, subtract, multiply, compareTo, equals, setScale (BigDecimal, HALF_UP)";

    private static final String SUPPORTED_TIME_METHODS =
            "now, plusDays, minusDays, plusHours, minusHours, plus, minus, toLocalDate, "
                    + "toLocalTime, toInstant, Instant.ofEpochMilli, compareTo, equals, and the Duration/Period factories "
                    + "(zero, ofDays, ofHours, ofMinutes, ofSeconds, ofMonths, ofYears)";

    private static final String SUPPORTED_OPTIONAL_METHODS =
            "isPresent, get, orElse, orElseThrow()";

    private static TitanDiagnosticException unknownMethodOnSupportedReceiver(
            String receiverLabel,
            String method,
            String supportedMethods,
            Tree invocation,
            ParsedSources parsedSources
    ) {
        return LowererSupport.loweringError(
                TitanErrorCode.E001,
                receiverLabel + " method '" + method + "' has no SQL lowering; supported "
                        + receiverLabel + " methods: " + supportedMethods,
                invocation,
                parsedSources,
                "Rewrite the expression using a supported " + receiverLabel
                        + " method or move the logic into a transpilable helper");
    }

    private static TitanDiagnosticException unknownInstanceMethod(
            String method,
            ExecutableElement resolvedMethod,
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        if (isMethodOwnedBy(resolvedMethod, "java.lang.String")) {
            return unknownMethodOnSupportedReceiver("String", method, SUPPORTED_STRING_METHODS, invocation, parsedSources);
        }
        if (isMethodOwnedBy(resolvedMethod, "java.math.BigDecimal", "java.math.BigInteger")) {
            String receiverLabel = resolvedMethod.getEnclosingElement().getSimpleName().toString();
            return unknownMethodOnSupportedReceiver(receiverLabel, method, SUPPORTED_BIG_NUMBER_METHODS, invocation, parsedSources);
        }
        if (isJavaTimeMethod(resolvedMethod)) {
            String receiverLabel = resolvedMethod.getEnclosingElement().getSimpleName().toString();
            return unknownMethodOnSupportedReceiver(receiverLabel, method, SUPPORTED_TIME_METHODS, invocation, parsedSources);
        }
        if (resolvedMethod != null && resolvedMethod.getEnclosingElement() != null
                && resolvedMethod.getEnclosingElement().getKind() == ElementKind.ENUM) {
            return LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Enum method '" + method + "' with arguments has no SQL lowering; only "
                            + "no-argument accessor methods backed by an enum field are supported",
                    invocation,
                    parsedSources,
                    "Expose the value through a no-argument field-backed accessor, or move the logic "
                            + "into a transpilable helper taking the enum as a parameter");
        }
        if (resolvedMethod != null && resolvedMethod.getEnclosingElement() != null) {
            return LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Method '" + method + "' on receiver type '"
                            + resolvedMethod.getEnclosingElement() + "' has no SQL lowering",
                    invocation,
                    parsedSources,
                    "Titan lowers calls to source-local static helpers, instance methods of "
                            + "source-local final classes, annotated entry points, and the supported "
                            + "JDK String/BigDecimal/java.time/Optional subsets");
        }
        return LowererSupport.loweringError(
                TitanErrorCode.E001,
                "Method call '" + method + "' does not resolve to a transpilable method and has no SQL lowering",
                invocation,
                parsedSources,
                "Declare the helper in the transpiled source set (static, or an instance method "
                        + "of a final class), or replace the call with a supported SQL/DSL construct");
    }

    private static ExpressionNode lowerKnownInstanceMethod(
            String method,
            ExpressionNode receiver,
            List<ExpressionNode> args,
            ExecutableElement resolvedMethod,
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        boolean stringMethod = isMethodOwnedBy(resolvedMethod, "java.lang.String");
        boolean bigNumberMethod = isMethodOwnedBy(resolvedMethod, "java.math.BigDecimal", "java.math.BigInteger");
        boolean timeMethod = isJavaTimeMethod(resolvedMethod);
        return switch (method) {
            case "length" -> stringMethod ? new FunctionCallExpression("CHAR_LENGTH", List.of(receiver), null) : null;
            case "toUpperCase" -> stringMethod ? new FunctionCallExpression("UPPER", List.of(receiver), null) : null;
            case "toLowerCase" -> stringMethod ? new FunctionCallExpression("LOWER", List.of(receiver), null) : null;
            case "trim", "strip" -> stringMethod ? new FunctionCallExpression("TRIM", List.of(receiver), null) : null;
            case "replace" -> stringMethod ? new FunctionCallExpression("REPLACE", prepend(receiver, args), null) : null;
            case "isEmpty" -> stringMethod
                    ? new BinaryOpExpression(receiver, BinaryOperator.EQUAL, new LiteralExpression("", new TTextType()))
                    : null;
            case "contains" -> stringMethod ? new FunctionCallExpression("__titan_str_contains", prepend(receiver, args), null) : null;
            case "startsWith" -> stringMethod ? new FunctionCallExpression("__titan_str_starts_with", prepend(receiver, args), null) : null;
            case "endsWith" -> stringMethod ? new FunctionCallExpression("__titan_str_ends_with", prepend(receiver, args), null) : null;
            case "indexOf" -> stringMethod ? new FunctionCallExpression("__titan_str_index_of", prepend(receiver, args), null) : null;
            case "charAt" -> stringMethod ? new FunctionCallExpression("__titan_str_char_at", prepend(receiver, args), null) : null;
            case "substring" -> stringMethod ? new FunctionCallExpression("__titan_str_substring", prepend(receiver, args), null) : null;
            case "split" -> stringMethod ? new FunctionCallExpression("__titan_str_split", prepend(receiver, args), null) : null;
            case "matches" -> stringMethod ? new FunctionCallExpression("__titan_str_matches", prepend(receiver, args), null) : null;
            case "format" -> stringMethod ? new FunctionCallExpression("__titan_str_format", prepend(receiver, args), null) : null;
            case "add" -> bigNumberMethod ? singleArgBinary(receiver, args, BinaryOperator.ADD, method) : null;
            case "subtract" -> bigNumberMethod ? singleArgBinary(receiver, args, BinaryOperator.SUBTRACT, method) : null;
            case "multiply" -> bigNumberMethod ? singleArgBinary(receiver, args, BinaryOperator.MULTIPLY, method) : null;
            case "compareTo" -> isJdkScalarMethod(resolvedMethod) ? lowerCompareTo(receiver, args) : null;
            case "setScale" -> isMethodOwnedBy(resolvedMethod, "java.math.BigDecimal") ? lowerSetScale(receiver, args, invocation, parsedSources) : null;
            case "plusDays" -> timeMethod ? singleArgFunction("__titan_time_plus_days", receiver, args, method) : null;
            case "minusDays" -> timeMethod ? singleArgFunction("__titan_time_minus_days", receiver, args, method) : null;
            case "plusHours" -> timeMethod ? singleArgFunction("__titan_time_plus_hours", receiver, args, method) : null;
            case "minusHours" -> timeMethod ? singleArgFunction("__titan_time_minus_hours", receiver, args, method) : null;
            case "toLocalDate" -> timeMethod ? noArgFunction("__titan_time_to_local_date", receiver, args, method) : null;
            case "toLocalTime" -> timeMethod ? noArgFunction("__titan_time_to_local_time", receiver, args, method) : null;
            case "toInstant" -> timeMethod ? noArgFunction("__titan_time_to_instant", receiver, args, method) : null;
            case "plus" -> timeMethod ? singleArgFunction("__titan_time_plus_amount", receiver, args, method) : null;
            case "minus" -> timeMethod ? singleArgFunction("__titan_time_minus_amount", receiver, args, method) : null;
            default -> null;
        };
    }

    private static boolean isMethodOwnedBy(ExecutableElement resolvedMethod, String... qualifiedOwners) {
        if (resolvedMethod == null || resolvedMethod.getEnclosingElement() == null) {
            return false;
        }
        String owner = resolvedMethod.getEnclosingElement().toString();
        for (String qualifiedOwner : qualifiedOwners) {
            if (qualifiedOwner.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJavaTimeMethod(ExecutableElement resolvedMethod) {
        return resolvedMethod != null
                && resolvedMethod.getEnclosingElement() != null
                && resolvedMethod.getEnclosingElement().toString().startsWith("java.time.");
    }

    private static boolean isJdkScalarMethod(ExecutableElement resolvedMethod) {
        if (resolvedMethod == null || resolvedMethod.getEnclosingElement() == null) {
            return false;
        }
        String owner = resolvedMethod.getEnclosingElement().toString();
        return owner.startsWith("java.lang.") || owner.startsWith("java.math.") || owner.startsWith("java.time.");
    }

    private static boolean isSourceLocalConcreteInstanceHelper(ExecutableElement method, ParsedSources parsedSources) {
        if (method == null
                || method.getModifiers().contains(Modifier.STATIC)
                || !(method.getEnclosingElement() instanceof TypeElement owner)
                || owner.getKind() != ElementKind.CLASS
                || !owner.getModifiers().contains(Modifier.FINAL)) {
            return false;
        }
        return LoweringContext.forSources(parsedSources).sourceMethodTree(method) != null;
    }

    private static ExpressionNode lowerNewClassExpression(NewClassTree newClass, ParsedSources parsedSources) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, newClass);
        Element element = path == null ? null : parsedSources.trees().getElement(path);
        if (!(element instanceof ExecutableElement constructor)
                || !(constructor.getEnclosingElement() instanceof TypeElement owner)
                || owner.getKind() != ElementKind.RECORD) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported expression in P0 lowering: NEW_CLASS",
                    newClass,
                    parsedSources,
                    null);
        }
        // B-2 (TG-BLK-005): record identity is the source-local qualified name, never the bare
        // simple name — same-simple-name records in different enclosing types stay distinct.
        return new RecordConstructExpression(
                LowererSupport.sourceLocalTypeName(owner),
                newClass.getArguments().stream().map(arg -> lowerExpression(arg, parsedSources)).toList());
    }

    private static ExpressionNode lowerRecordAccessorInvocation(
            MethodInvocationTree invocation,
            MemberSelectTree select,
            ParsedSources parsedSources
    ) {
        if (!invocation.getArguments().isEmpty()) {
            return null;
        }
        TreePath invocationPath = LowererSupport.resolveTreePath(parsedSources, invocation);
        Element method = invocationPath == null ? null : parsedSources.trees().getElement(invocationPath);
        if (!(method instanceof ExecutableElement executable)
                || !(executable.getEnclosingElement() instanceof TypeElement owner)
                || owner.getKind() != ElementKind.RECORD) {
            return null;
        }
        String fieldName = select.getIdentifier().toString();
        boolean componentAccessor = owner.getRecordComponents().stream()
                .anyMatch(component -> component.getSimpleName().contentEquals(fieldName));
        if (!componentAccessor) {
            return null;
        }
        // B-2 (TG-BLK-005): qualified record identity (see lowerNewClassExpression).
        return new RecordFieldExpression(
                lowerExpression(select.getExpression(), parsedSources),
                LowererSupport.sourceLocalTypeName(owner),
                fieldName);
    }

    private static ExpressionNode tryLowerTriggerRowRead(MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !"get".contentEquals(select.getIdentifier())
                || invocation.getArguments().size() != 1
                || !(select.getExpression() instanceof MethodInvocationTree receiverInvocation)) {
            return null;
        }

        String receiverMethod = LowererSupport.invocationName(receiverInvocation);
        if ("newRow".equals(receiverMethod)) {
            return new ColumnRefExpression("NEW", StatementLowerer.triggerRowColumnName(invocation.getArguments().getFirst(), parsedSources));
        }
        if ("oldRow".equals(receiverMethod)) {
            return new ColumnRefExpression("OLD", StatementLowerer.triggerRowColumnName(invocation.getArguments().getFirst(), parsedSources));
        }
        return null;
    }

    private static List<ExpressionNode> prepend(ExpressionNode first, List<ExpressionNode> rest) {
        List<ExpressionNode> combined = new ArrayList<>(1 + rest.size());
        combined.add(first);
        combined.addAll(rest);
        return List.copyOf(combined);
    }

    private static ExpressionNode lowerEnumMethodAsHelperCall(
            ExecutableElement resolvedMethod,
            String methodName,
            ExpressionNode receiver,
            List<ExpressionNode> args,
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        if (resolvedMethod == null || !args.isEmpty()) {
            return null;
        }
        if (!(resolvedMethod.getEnclosingElement() instanceof TypeElement enumType)
                || enumType.getKind() != ElementKind.ENUM) {
            return null;
        }
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        String enumName = LowererSupport.sourceLocalTypeName(enumType);
        if (!enumMethodHasBackingField(resolvedMethod, methodName)) {
            // B-1 (TG-BLK-007): the emitters only create an accessor function for enum methods
            // that resolve to a backing field; lowering a computed method to a helper call would
            // reference a function that is never created — deploys fine, fails at execution.
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Enum method '" + enumName + "." + methodName + "' has no SQL lowering: it does "
                            + "not resolve to a backing enum field, and only field-backed accessor "
                            + "methods are emitted as enum lookup functions",
                    invocation,
                    parsedSources,
                    "Back the method by an enum field (constructor-assigned), or move the computation "
                            + "into a transpilable helper taking the enum as a parameter");
        }
        // B-2 (TG-BLK-005): qualified enum name + SqlNames member join, mirroring emitEnumLookup.
        String helperName = SqlNames.enumMemberName(enumName, toSnakeCase(methodName));
        return new FunctionCallExpression(helperName, List.of(receiver), null);
    }

    /**
     * Mirrors the emitters' {@code enumBackingFieldForMethod} resolution (strip a get/is prefix,
     * snake-case, match a non-static enum field) against the resolved enum element, so the
     * lowerer rejects exactly the methods the emitters would skip.
     */
    private static boolean enumMethodHasBackingField(ExecutableElement resolvedMethod, String methodName) {
        String candidate = methodName;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            candidate = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            candidate = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        String snakeCandidate = toSnakeCase(candidate);
        for (Element enclosed : resolvedMethod.getEnclosingElement().getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD
                    || enclosed.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (toSnakeCase(enclosed.getSimpleName().toString()).equals(snakeCandidate)) {
                return true;
            }
        }
        return false;
    }

    static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) {
            return "value";
        }
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .toLowerCase()
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static ExpressionNode lowerIdentifierExpression(IdentifierTree identifierTree, ParsedSources parsedSources) {
        TreePath identifierPath = LowererSupport.resolveTreePath(parsedSources, identifierTree);
        Element element = null;
        if (identifierPath != null) {
            element = parsedSources.trees().getElement(identifierPath);
            if (element != null && element.getKind() == ElementKind.ENUM_CONSTANT) {
                return new LiteralExpression(element.getSimpleName().toString(), new TTextType());
            }
        }
        InlineVariableResolver inlineVariables = INLINE_VARIABLES.get();
        if (inlineVariables != null && element != null) {
            ExpressionNode replacement = inlineVariables.resolve(element);
            if (replacement != null) {
                return replacement;
            }
        }
        ExpressionNode foldedConstant = foldStaticFinalCompileTimeConstant(identifierTree, parsedSources);
        if (foldedConstant != null) {
            return foldedConstant;
        }
        String staticFieldKey = resolveStaticFieldKey(identifierTree, parsedSources);
        if (staticFieldKey != null) {
            return new FunctionCallExpression("__titan_static_get", List.of(
                    new LiteralExpression(staticFieldKey, new TTextType())
            ), null);
        }
        ColumnRefExpression fieldRef = DslQueryLowerer.resolveDslFieldReference(identifierTree, parsedSources);
        if (fieldRef != null) {
            return fieldRef;
        }
        return new VariableRefExpression(identifierTree.getName().toString());
    }

    static String resolveStaticFieldKey(Tree expressionTree, ParsedSources parsedSources) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expressionTree);
        if (path == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(path);
        if (element == null || element.getKind() != ElementKind.FIELD || !element.getModifiers().contains(Modifier.STATIC)) {
            return null;
        }
        if (element.getEnclosingElement() == null) {
            return null;
        }
        return element.getEnclosingElement() + "#" + element.getSimpleName();
    }

    /**
     * Constant-folds a reference to a {@code static final} field whose initializer is a JLS
     * compile-time constant (String or primitive) to its literal value (G5, spike B5).
     *
     * <p>The discriminator is {@link VariableElement#getConstantValue()}: the JLS sets it to a
     * non-null boxed value ({@code String}/{@code Integer}/{@code Long}/{@code Float}/
     * {@code Double}/{@code Boolean}/{@code Character}/{@code Short}/{@code Byte}) exactly when the
     * field is a compile-time constant — a {@code static final} of a primitive or {@code String}
     * type initialized by a constant expression. It is {@code null} for every mutable or
     * non-constant static field (non-{@code final}, or {@code final} but computed, e.g.
     * {@code static final BigDecimal} or {@code static final Foo = new Foo()}), so those continue
     * to route through the {@code __titan_static_get} runtime-state machinery via
     * {@link #resolveStaticFieldKey}. This is the same constant model
     * {@link RawSqlConstantRule#isCompileTimeConstantSqlText} and {@code FeatureValidator}'s
     * {@code compilerKnownString} use, so the raw-SQL and DSL-value-position folds stay consistent.</p>
     *
     * <p>Returns {@code null} when the reference is not a compile-time-constant static field, so
     * the caller falls through to {@code static_get} / DSL field / variable handling.</p>
     */
    static ExpressionNode foldStaticFinalCompileTimeConstant(Tree expressionTree, ParsedSources parsedSources) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expressionTree);
        if (path == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variable)
                || element.getKind() != ElementKind.FIELD
                || !element.getModifiers().contains(Modifier.STATIC)
                || !element.getModifiers().contains(Modifier.FINAL)) {
            return null;
        }
        Object constantValue = variable.getConstantValue();
        if (constantValue == null) {
            return null;
        }
        return new LiteralExpression(constantValue, literalValueType(constantValue));
    }

    /**
     * Folds a reference to one of {@code java.math.BigDecimal}'s immutable named singletons
     * ({@code ZERO}/{@code ONE}/{@code TEN}) to its literal numeric value. These are {@code public
     * static final BigDecimal} fields with defined constant values, but — being {@code BigDecimal}, not
     * a JLS primitive/String — the compiler does NOT set {@link VariableElement#getConstantValue()} for
     * them, so {@link #foldStaticFinalCompileTimeConstant} skips them and they would otherwise route
     * through the {@code __titan_static_get} runtime-state machinery (G5). That is both unnecessary
     * (the value is constant and known) and, on MySQL, broken: the static-state store keys on the
     * field's fully-qualified name {@code java.math.BigDecimal#ZERO}, and the helper builds a JSON path
     * {@code $.java.math.BigDecimal#ZERO} that is an invalid JSON path expression. Folding to a numeric
     * {@link LiteralExpression} emits the value inline ({@code 0}/{@code 1}/{@code 10}, NUMERIC-typed),
     * matching the spec §6.2 {@code DEFAULT 0} accumulator seed and pulling in no runtime dependency —
     * the same "constant inlines as a literal" property the {@code static final String} fold already
     * gives strings.
     *
     * <p>Returns {@code null} for any other field so the caller falls through to the static-field /
     * variable handling unchanged.</p>
     */
    static ExpressionNode foldBigDecimalNamedConstant(Tree expressionTree, ParsedSources parsedSources) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expressionTree);
        if (path == null) {
            return null;
        }
        Element element = parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variable)
                || element.getKind() != ElementKind.FIELD
                || !element.getModifiers().contains(Modifier.STATIC)
                || !element.getModifiers().contains(Modifier.FINAL)) {
            return null;
        }
        if (!(variable.getEnclosingElement() instanceof TypeElement owner)
                || !"java.math.BigDecimal".contentEquals(owner.getQualifiedName())) {
            return null;
        }
        BigDecimal value = switch (variable.getSimpleName().toString()) {
            case "ZERO" -> BigDecimal.ZERO;
            case "ONE" -> BigDecimal.ONE;
            case "TEN" -> BigDecimal.TEN;
            default -> null;
        };
        if (value == null) {
            return null;
        }
        return new LiteralExpression(value, new TNumericType(38, 10));
    }

    private static boolean isDurationOwner(String owner) {
        return "Duration".equals(owner) || "java.time.Duration".equals(owner);
    }

    private static boolean isPeriodOwner(String owner) {
        return "Period".equals(owner) || "java.time.Period".equals(owner);
    }

    private static boolean isStringTypeSelectOwner(ExpressionTree ownerExpression) {
        if (ownerExpression instanceof IdentifierTree identifierTree) {
            return "String".contentEquals(identifierTree.getName());
        }
        String rendered = ownerExpression == null ? "" : ownerExpression.toString();
        return "java.lang.String".equals(rendered);
    }

    /** Recognizes the deliberately small portable text-intrinsic surface from titan-dsl. */
    private static boolean isTitanDslTextOwner(String ownerText, ExecutableElement resolvedMethod) {
        if (isMethodOwnedBy(resolvedMethod, "titan.dsl.Text")) {
            return true;
        }
        // Source-only lowering tests can intentionally omit the DSL classpath.  Keep that
        // behavior deterministic for the unambiguous qualified/imported owner spellings while
        // resolved production calls remain constrained to titan.dsl.Text above.
        return resolvedMethod == null && ("Text".equals(ownerText) || "titan.dsl.Text".equals(ownerText));
    }

    private static boolean isOptionalMethod(ExecutableElement resolvedMethod, String ownerText) {
        if (resolvedMethod != null) {
            String owner = resolvedMethod.getEnclosingElement().toString();
            if ("java.util.Optional".equals(owner)) {
                return true;
            }
        }
        return ownerText.endsWith("Optional") || ownerText.contains("Optional<");
    }

    private static ExpressionNode lowerOptionalInvocation(
            String method,
            ExpressionNode receiver,
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        return switch (method) {
            case "isPresent" -> new IsNotNullExpression(receiver);
            case "get" -> new CaseWhenExpression(
                    List.of(new CaseBranch(
                            new IsNullExpression(receiver),
                            new FunctionCallExpression("__titan_optional_throw", List.of(), null)
                    )),
                    receiver
            );
            case "orElse" -> {
                if (invocation.getArguments().size() != 1) {
                    throw LowererSupport.loweringError(
                            TitanErrorCode.E001,
                            "Optional.orElse requires exactly one argument",
                            invocation,
                            parsedSources,
                            null);
                }
                yield new CoalesceExpression(List.of(
                        receiver,
                        lowerExpression(invocation.getArguments().getFirst(), parsedSources)
                ));
            }
            // D8 (plan 2.2): Optional.map takes a Function and Optional.orElseThrow(...) takes
            // a Supplier — lambda/method-reference arguments used to be stringified into the
            // SQL via source text. There is no SQL representation for a function value, so
            // these shapes are rejected with positioned diagnostics; the null-rewrite-safe
            // Optional subset (isPresent/get/orElse/no-arg orElseThrow) stays supported.
            case "map" -> throw LowererSupport.unsupportedFeature(
                    "Optional.map (its Function argument has no SQL representation)",
                    invocation,
                    parsedSources,
                    "Rewrite as a conditional on the value: check isPresent()/orElse(...) and apply the mapping expression directly");
            case "orElseThrow" -> {
                if (!invocation.getArguments().isEmpty()) {
                    throw LowererSupport.unsupportedFeature(
                            "Optional.orElseThrow with a Supplier argument (the exception-supplier lambda has no SQL representation)",
                            invocation,
                            parsedSources,
                            "Use the no-argument orElseThrow(), or check isPresent() and throw the exception explicitly");
                }
                yield new CaseWhenExpression(
                        List.of(new CaseBranch(
                                new IsNullExpression(receiver),
                                new FunctionCallExpression("__titan_optional_throw", List.of(), null))),
                        receiver
                );
            }
            // B-1 (TG-BLK-007): an unmapped Optional method (including the static factories
            // of/ofNullable/empty) used to fall through to a bare SQL call of the same name.
            default -> throw unknownMethodOnSupportedReceiver(
                    "Optional", method, SUPPORTED_OPTIONAL_METHODS, invocation, parsedSources);
        };
    }

    private static ExpressionNode singleArgBinary(
            ExpressionNode receiver,
            List<ExpressionNode> args,
            BinaryOperator operator,
            String methodName
    ) {
        if (args.size() != 1) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method '" + methodName + "': " + args.size(),
                    null,
                    null,
                    null);
        }
        return new BinaryOpExpression(receiver, operator, args.getFirst());
    }

    private static ExpressionNode lowerStringEquals(ExpressionNode receiver, List<ExpressionNode> args) {
        // Java String.equals is a byte/code-point exact comparison.  A plain SQL text `=`
        // inherits the database collation, which can make "Query" equal "query" under
        // MySQL's common case-insensitive collations.  Keep it as a distinct intrinsic so each
        // dialect can compare the encoded text bytes rather than silently weakening Java
        // semantics.  The surrounding null handling intentionally preserves the existing
        // lowered contract: a null argument or nullable receiver produces false here.
        ExpressionNode comparison = new FunctionCallExpression(
                "__titan_str_equals", prepend(receiver, args), null);
        ExpressionNode nullAwareComparison = new CaseWhenExpression(
                List.of(new CaseBranch(new IsNullExpression(args.getFirst()), new LiteralExpression(false, new TBooleanType()))),
                comparison
        );
        return new CoalesceExpression(List.of(nullAwareComparison, new LiteralExpression(false, new TBooleanType())));
    }

    private static boolean isStringEqualsMethod(ExecutableElement resolvedMethod, String ownerText) {
        if (resolvedMethod != null && resolvedMethod.getEnclosingElement() != null) {
            return "java.lang.String".equals(resolvedMethod.getEnclosingElement().toString());
        }
        return "String".equals(ownerText) || "java.lang.String".equals(ownerText);
    }

    /**
     * Lowers {@code a.compareTo(b)} to the {@code __titan_compare_to} marker call. Java throws
     * NullPointerException when either operand is null, while the old direct CASE lowering
     * silently returned 0 ("equal") for NULL operands (audit N2). {@link NullAnalysisPass} is the
     * single home of the null semantics: it inserts the dialect-consistent null guards and
     * rewrites the marker to the comparison CASE; the emitters reject the marker outright, so a
     * pipeline that skips the pass fails loudly instead of emitting corrupted comparisons.
     */
    private static ExpressionNode lowerCompareTo(ExpressionNode receiver, List<ExpressionNode> args) {
        if (args.size() != 1) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method 'compareTo': " + args.size(),
                    null,
                    null,
                    null);
        }

        return new FunctionCallExpression(
                NullAnalysisPass.COMPARE_TO_MARKER,
                List.of(receiver, args.getFirst()),
                null);
    }

    private static ExpressionNode lowerSetScale(ExpressionNode receiver, List<ExpressionNode> args, MethodInvocationTree invocation, ParsedSources parsedSources) {
        if (args.size() != 1 && args.size() != 2) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method 'setScale': " + args.size(),
                    invocation,
                    parsedSources,
                    null);
        }

        if (args.size() == 2) {
            String roundingArg = invocation.getArguments().get(1).toString();
            boolean supportedHalfUp = roundingArg.endsWith("HALF_UP") || roundingArg.endsWith("ROUND_HALF_UP");
            if (!supportedHalfUp) {
                throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Unsupported BigDecimal.setScale rounding mode: " + roundingArg,
                        invocation,
                        parsedSources,
                        "Use HALF_UP/ROUND_HALF_UP rounding");
            }
        }

        return new FunctionCallExpression("ROUND", List.of(receiver, args.getFirst()), null);
    }

    private static ExpressionNode singleArgFunction(String functionName, ExpressionNode receiver, List<ExpressionNode> args, String methodName) {
        if (args.size() != 1) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method '" + methodName + "': " + args.size(),
                    null,
                    null,
                    null);
        }
        return new FunctionCallExpression(functionName, prepend(receiver, args), null);
    }

    private static ExpressionNode staticSingleArgFunction(String functionName, List<ExpressionNode> args, String methodName) {
        if (args.size() != 1) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method '" + methodName + "': " + args.size(),
                    null,
                    null,
                    null);
        }
        return new FunctionCallExpression(functionName, args, null);
    }

    private static ExpressionNode noArgFunction(String functionName, ExpressionNode receiver, List<ExpressionNode> args, String methodName) {
        if (!args.isEmpty()) {
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported argument arity for method '" + methodName + "': " + args.size(),
                    null,
                    null,
                    null);
        }
        return new FunctionCallExpression(functionName, List.of(receiver), null);
    }

    private static ExpressionNode lowerSwitchExpression(SwitchExpressionTree switchExpression, ParsedSources parsedSources) {
        ExpressionNode selector = lowerExpression(switchExpression.getExpression(), parsedSources);

        List<CaseBranch> branches = new ArrayList<>();
        ExpressionNode elseValue = null;

        for (CaseTree caseTree : switchExpression.getCases()) {
            if (caseTree.getCaseKind() != CaseTree.CaseKind.RULE) {
                throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Only arrow-style switch expression cases are supported",
                        caseTree,
                        parsedSources,
                        "Rewrite the switch expression using arrow-style (case X ->) rules");
            }

            ExpressionNode result = lowerSwitchCaseResult(caseTree, parsedSources);
            if (isDefaultCase(caseTree)) {
                elseValue = result;
                continue;
            }

            ExpressionNode labelCondition = null;
            for (ExpressionTree labelExpression : caseTree.getExpressions()) {
                ExpressionNode equals = new BinaryOpExpression(
                        selector,
                        BinaryOperator.EQUAL,
                        lowerExpression(labelExpression, parsedSources)
                );
                labelCondition = labelCondition == null
                        ? equals
                        : new BinaryOpExpression(labelCondition, BinaryOperator.OR, equals);
            }

            if (labelCondition == null) {
                throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Switch expression case has no labels",
                        caseTree,
                        parsedSources,
                        null);
            }
            branches.add(new CaseBranch(labelCondition, result));
        }

        if (elseValue == null) {
            if (isExhaustiveEnumSwitch(switchExpression, parsedSources)) {
                elseValue = new LiteralExpression(null, new TJsonType());
            } else {
                throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Switch expression must include a default case or be exhaustive for enum constants",
                        switchExpression,
                        parsedSources,
                        "Add a default -> branch or cover every enum constant");
            }
        }

        return new CaseWhenExpression(List.copyOf(branches), elseValue);
    }

    private static boolean isExhaustiveEnumSwitch(SwitchExpressionTree switchExpression, ParsedSources parsedSources) {
        TreePath selectorPath = LowererSupport.resolveTreePath(parsedSources, switchExpression.getExpression());
        if (selectorPath == null) {
            return false;
        }

        TypeMirror selectorType = parsedSources.trees().getTypeMirror(selectorPath);
        if (selectorType == null || selectorType.getKind() != TypeKind.DECLARED) {
            return false;
        }

        Element selectorElement = parsedSources.types().asElement(selectorType);
        if (selectorElement == null || selectorElement.getKind() != ElementKind.ENUM) {
            return false;
        }

        java.util.Set<String> enumConstants = new java.util.LinkedHashSet<>();
        for (Element enclosed : selectorElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                enumConstants.add(enclosed.getSimpleName().toString());
            }
        }
        if (enumConstants.isEmpty()) {
            return false;
        }

        java.util.Set<String> coveredLabels = new java.util.LinkedHashSet<>();
        for (CaseTree caseTree : switchExpression.getCases()) {
            if (isDefaultCase(caseTree)) {
                continue;
            }
            for (ExpressionTree labelExpression : caseTree.getExpressions()) {
                String labelName = null;
                if (labelExpression instanceof IdentifierTree identifierTree) {
                    labelName = identifierTree.getName().toString();
                } else if (labelExpression instanceof MemberSelectTree memberSelectTree) {
                    labelName = memberSelectTree.getIdentifier().toString();
                }
                if (labelName == null || labelName.isBlank()) {
                    return false;
                }
                coveredLabels.add(labelName);
            }
        }

        return coveredLabels.containsAll(enumConstants);
    }

    private static ExpressionNode lowerSwitchCaseResult(CaseTree caseTree, ParsedSources parsedSources) {
        Tree body = caseTree.getBody();
        if (body instanceof ExpressionTree expressionTree) {
            return lowerExpression(expressionTree, parsedSources);
        }
        if (body instanceof BlockTree blockTree) {
            YieldTree yieldTree = null;
            for (StatementTree statement : blockTree.getStatements()) {
                if (statement instanceof YieldTree candidate) {
                    if (yieldTree != null) {
                        throw LowererSupport.loweringError(
                                TitanErrorCode.E001,
                                "Unsupported switch expression case body: multiple yield statements are not supported",
                                statement,
                                parsedSources,
                                null);
                    }
                    yieldTree = candidate;
                    continue;
                }
                if (!(statement instanceof ExpressionStatementTree)) {
                    throw LowererSupport.loweringError(
                            TitanErrorCode.E001,
                            "Unsupported switch expression case body: only expression statements before yield are supported",
                            statement,
                            parsedSources,
                            null);
                }
            }
            if (yieldTree != null) {
                return lowerExpression(yieldTree.getValue(), parsedSources);
            }
            throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported switch expression case body: block must contain a yield statement",
                    blockTree,
                    parsedSources,
                    null);
        }

        throw LowererSupport.loweringError(
                TitanErrorCode.E001,
                "Unsupported switch expression case body: " + (body == null ? "null" : body.getKind()),
                body == null ? caseTree : body,
                parsedSources,
                null);
    }

    private static boolean isDefaultCase(CaseTree caseTree) {
        if (caseTree.getLabels() == null || caseTree.getLabels().isEmpty()) {
            return false;
        }

        for (Tree label : caseTree.getLabels()) {
            if (label != null && (label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL
                    || "default".equals(label.toString()))) {
                return true;
            }
        }
        return false;
    }

    private static ExpressionNode lowerConditional(ConditionalExpressionTree conditional, ParsedSources parsedSources) {
        return new CaseWhenExpression(
                List.of(new CaseBranch(
                        lowerExpression(conditional.getCondition(), parsedSources),
                        lowerExpression(conditional.getTrueExpression(), parsedSources)
                )),
                lowerExpression(conditional.getFalseExpression(), parsedSources)
        );
    }

    private static ExpressionNode lowerUnary(UnaryTree unaryTree, ParsedSources parsedSources) {
        ExpressionNode value = lowerExpression(unaryTree.getExpression(), parsedSources);
        return switch (unaryTree.getKind()) {
            case UNARY_PLUS -> value;
            case UNARY_MINUS -> new BinaryOpExpression(
                    new LiteralExpression(0, new TIntType()),
                    BinaryOperator.SUBTRACT,
                    value
            );
            case LOGICAL_COMPLEMENT -> new NotExpression(value);
            default -> throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported unary operator in P0 lowering: " + unaryTree.getKind(),
                    unaryTree,
                    parsedSources,
                    null);
        };
    }

    private static ExpressionNode lowerBinary(BinaryTree binaryTree, ParsedSources parsedSources) {
        BinaryOperator operator = switch (binaryTree.getKind()) {
            case PLUS -> BinaryOperator.ADD;
            case MINUS -> BinaryOperator.SUBTRACT;
            case MULTIPLY -> BinaryOperator.MULTIPLY;
            case DIVIDE -> BinaryOperator.DIVIDE;
            case REMAINDER -> BinaryOperator.MODULO;
            case EQUAL_TO -> BinaryOperator.EQUAL;
            case NOT_EQUAL_TO -> BinaryOperator.NOT_EQUAL;
            case LESS_THAN -> BinaryOperator.LESS_THAN;
            case LESS_THAN_EQUAL -> BinaryOperator.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> BinaryOperator.GREATER_THAN;
            case GREATER_THAN_EQUAL -> BinaryOperator.GREATER_THAN_OR_EQUAL;
            case CONDITIONAL_AND -> BinaryOperator.AND;
            case CONDITIONAL_OR -> BinaryOperator.OR;
            default -> throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported binary operator in P0 lowering: " + binaryTree.getKind(),
                    binaryTree,
                    parsedSources,
                    null);
        };

        ExpressionNode left = lowerExpression(binaryTree.getLeftOperand(), parsedSources);
        ExpressionNode right = lowerExpression(binaryTree.getRightOperand(), parsedSources);

        if (operator == BinaryOperator.ADD && isStringTypedExpression(binaryTree, parsedSources)) {
            return new FunctionCallExpression("__titan_str_concat", List.of(left, right), null);
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            ExpressionNode nullAware = lowerNullComparison(left, operator, right);
            if (nullAware != null) {
                return nullAware;
            }
        }
        if (isArithmeticOperator(operator)) {
            left = lowerCharArithmeticOperand(binaryTree.getLeftOperand(), left, parsedSources);
            right = lowerCharArithmeticOperand(binaryTree.getRightOperand(), right, parsedSources);
        }

        ExpressionNode folded = tryFoldNumericLiterals(left, operator, right);
        if (folded != null) {
            return folded;
        }

        return new BinaryOpExpression(left, operator, right);
    }

    private static boolean isArithmeticOperator(BinaryOperator operator) {
        return operator == BinaryOperator.ADD
                || operator == BinaryOperator.SUBTRACT
                || operator == BinaryOperator.MULTIPLY
                || operator == BinaryOperator.DIVIDE
                || operator == BinaryOperator.MODULO;
    }

    private static ExpressionNode lowerCharArithmeticOperand(
            ExpressionTree sourceExpression,
            ExpressionNode loweredExpression,
            ParsedSources parsedSources
    ) {
        TypeMirror type = resolveTreeType(sourceExpression, parsedSources);
        if (type == null || type.getKind() != TypeKind.CHAR) {
            return loweredExpression;
        }
        return new FunctionCallExpression("__titan_char_code", List.of(loweredExpression), null);
    }

    private static ExpressionNode lowerNullComparison(ExpressionNode left, BinaryOperator operator, ExpressionNode right) {
        if (isNullLiteral(left)) {
            return operator == BinaryOperator.EQUAL
                    ? new IsNullExpression(right)
                    : new IsNotNullExpression(right);
        }
        if (isNullLiteral(right)) {
            return operator == BinaryOperator.EQUAL
                    ? new IsNullExpression(left)
                    : new IsNotNullExpression(left);
        }
        return null;
    }

    private static boolean isNullLiteral(ExpressionNode expression) {
        return expression instanceof LiteralExpression literal && literal.value() == null;
    }

    static boolean isStringTypedExpression(Tree tree, ParsedSources parsedSources) {
        TypeMirror expressionType = resolveTreeType(tree, parsedSources);
        if (expressionType == null || expressionType.getKind() == TypeKind.ERROR) {
            return false;
        }
        return "java.lang.String".contentEquals(parsedSources.types().erasure(expressionType).toString());
    }

    private static TypeMirror resolveTreeType(Tree tree, ParsedSources parsedSources) {
        return typeMirror(parsedSources, tree);
    }

    private static ExpressionNode tryFoldNumericLiterals(ExpressionNode left, BinaryOperator operator, ExpressionNode right) {
        if (!(left instanceof LiteralExpression leftLiteral) || !(right instanceof LiteralExpression rightLiteral)) {
            return null;
        }
        if (!(leftLiteral.value() instanceof Number leftNumber) || !(rightLiteral.value() instanceof Number rightNumber)) {
            return null;
        }

        boolean leftIntegral = leftNumber instanceof Long || leftNumber instanceof Integer
                || leftNumber instanceof Short || leftNumber instanceof Byte;
        boolean rightIntegral = rightNumber instanceof Long || rightNumber instanceof Integer
                || rightNumber instanceof Short || rightNumber instanceof Byte;
        if (!leftIntegral || !rightIntegral) {
            return null;
        }
        boolean isLong = leftNumber instanceof Long || rightNumber instanceof Long;

        long leftValue = leftNumber.longValue();
        long rightValue = rightNumber.longValue();
        return switch (operator) {
            case ADD -> numericLiteral(leftValue + rightValue, isLong);
            case SUBTRACT -> numericLiteral(leftValue - rightValue, isLong);
            case MULTIPLY -> numericLiteral(leftValue * rightValue, isLong);
            case DIVIDE -> rightValue == 0 ? null : numericLiteral(leftValue / rightValue, isLong);
            case MODULO -> rightValue == 0 ? null : numericLiteral(leftValue % rightValue, isLong);
            default -> null;
        };
    }

    private static LiteralExpression numericLiteral(long value, boolean isLong) {
        if (isLong) {
            return new LiteralExpression(value, new TBigintType());
        }
        return new LiteralExpression((int) value, new TIntType());
    }

    static ExpressionNode lowerLiteral(LiteralTree literalTree) {
        return new LiteralExpression(literalTree.getValue(), literalValueType(literalTree.getValue()));
    }

    /**
     * Maps a Java literal/constant value (the boxed result of a {@code LiteralTree.getValue()}
     * or {@link VariableElement#getConstantValue()}) to its TIR type. Shared by literal lowering
     * and the {@code static final} compile-time-constant fold (G5) so a folded constant carries
     * the same TIR type a written-out literal would.
     */
    private static TirType literalValueType(Object value) {
        if (value == null) {
            return new TJsonType();
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return new TIntType();
        }
        if (value instanceof Long) {
            return new TBigintType();
        }
        if (value instanceof Float || value instanceof Double) {
            return new TDoubleType();
        }
        if (value instanceof Boolean) {
            return new TBooleanType();
        }
        return new TTextType();
    }
}
