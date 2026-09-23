package io.titan.transpiler;

import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnostics;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import io.titan.transpiler.tir.DialectCapabilities;
import io.titan.transpiler.tir.DialectId;
import io.titan.transpiler.tir.JavaCastClassifier;
import io.titan.transpiler.tir.RawSqlConstantRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import javax.lang.model.type.TypeVariable;

/**
 * Validates discovered entry points against the currently supported P0 subset.
 */
public final class FeatureValidator {

    public void validate(ParsedSources parsedSources, List<DiscoveredEntryPoint> entryPoints) {
        validate(parsedSources, entryPoints, List.of(), new DiagnosticSink());
    }

    /**
     * Target-dialect-aware validation (plan 3.1, audit E-3/E-6/E-9): in addition to the
     * dialect-independent subset rules, each construct is checked against the
     * {@link DialectCapabilities} of every transpile target. An unsupported construct/dialect
     * combination is rejected with a positioned {@code TITAN-E001} naming the dialect and a
     * rewrite suggestion; a version-gated combination (e.g. INTERSECT/EXCEPT on MySQL &lt;
     * 8.0.31) emits a positioned {@code TITAN-W005} warning naming the version floor through
     * {@code warningSink}.
     */
    public void validate(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            List<DialectId> targetDialects,
            DiagnosticSink warningSink
    ) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        if (entryPoints == null) {
            throw new IllegalArgumentException("entryPoints must not be null");
        }
        if (targetDialects == null) {
            throw new IllegalArgumentException("targetDialects must not be null");
        }
        if (warningSink == null) {
            throw new IllegalArgumentException("warningSink must not be null");
        }
        List<DialectCapabilities> targetCapabilities = targetDialects.stream()
                .distinct()
                .map(DialectCapabilities::forDialect)
                .toList();

        Set<String> entryPointKeys = new HashSet<>();
        java.util.Map<String, DiscoveredEntryPoint> entryPointByKey = new java.util.HashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            entryPointKeys.add(entryPoint.methodSignatureKey());
            entryPointByKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        List<String> errors = new ArrayList<>();
        LoweringContext context = LoweringContext.forSources(parsedSources);
        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath path = getCurrentPath();
                    Element element = parsedSources.trees().getElement(path);
                    if (element == null || element.getKind() != ElementKind.METHOD) {
                        return super.visitMethod(node, unused);
                    }

                    ExecutableElement method = (ExecutableElement) element;
                    String key = MethodSignatureKeys.of(method);
                    if (!entryPointKeys.contains(key)) {
                        return super.visitMethod(node, unused);
                    }

                    // WS-C Phase 3 Rung 4 (§3.6 form 2): a List<E> entry-point parameter that is ARRAY-BOUND
                    // (the whole list drives a later IN, bound as one bigint[]/int[]/text[] (PG) / JSON
                    // (MySQL) parameter) is supported — its List<E> signature maps to a native array param.
                    // Exempt EXACTLY those array-bound parameters from the Iterable-param reject; any other
                    // List/Iterable parameter still rejects ("use array parameters instead").
                    java.util.Set<Element> arrayBoundCollectionParams =
                            arrayBoundCollectionParameterElements(node, path, parsedSources);
                    for (int i = 0; i < method.getParameters().size() && i < node.getParameters().size(); i++) {
                        TypeMirror parameterType = method.getParameters().get(i).asType();
                        if (isUnsupportedIterableEntryPointParameter(parameterType, parsedSources)
                                && !arrayBoundCollectionParams.contains(method.getParameters().get(i))) {
                            long line = unit.getLineMap().getLineNumber(
                                    parsedSources.trees().getSourcePositions().getStartPosition(unit, node.getParameters().get(i))
                            );
                            String location = unit.getSourceFile().getName() + ":" + line;
                            errors.add(TitanDiagnostics
                                    .unsupportedFeature(
                                            "entry-point Iterable parameter type '" + parameterType + "' (use array parameters instead)",
                                            location)
                                    .render());
                        }
                        if (isUnsupportedArrayEntryPointParameter(parameterType, parsedSources)) {
                            long line = unit.getLineMap().getLineNumber(
                                    parsedSources.trees().getSourcePositions().getStartPosition(unit, node.getParameters().get(i))
                            );
                            String location = unit.getSourceFile().getName() + ":" + line;
                            errors.add(TitanDiagnostics
                                    .unsupportedFeature(
                                            "entry-point array parameter element type '" + parameterType + "' (only scalar or record elements are supported)",
                                            location)
                                    .render());
                        }
                    }

                    validateMethodBody(node, path, unit, context.parsedSources(), errors,
                            entryPointByKey.get(key), targetCapabilities, warningSink);
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }
    }

    private static boolean isUnsupportedIterableEntryPointParameter(TypeMirror parameterType, ParsedSources parsedSources) {
        if (parameterType == null || parameterType.getKind() == TypeKind.ERROR || parameterType.getKind() == TypeKind.ARRAY) {
            return false;
        }
        if (!(parameterType instanceof DeclaredType declaredType)) {
            return false;
        }
        TypeElement iterableElement = parsedSources.elements().getTypeElement("java.lang.Iterable");
        if (iterableElement == null) {
            return false;
        }
        return parsedSources.types().isAssignable(
                parsedSources.types().erasure(declaredType),
                parsedSources.types().erasure(iterableElement.asType()));
    }

    private static boolean isUnsupportedArrayEntryPointParameter(TypeMirror parameterType, ParsedSources parsedSources) {
        if (!(parameterType instanceof javax.lang.model.type.ArrayType arrayType)) {
            return false;
        }
        return !isSupportedArrayEntryPointElementType(arrayType.getComponentType(), parsedSources);
    }

    private static boolean isSupportedArrayEntryPointElementType(TypeMirror type, ParsedSources parsedSources) {
        if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.ARRAY) {
            return false;
        }
        if (type.getKind().isPrimitive()) {
            return type.getKind() != TypeKind.VOID;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = parsedSources.types().asElement(type);
        if (!(element instanceof TypeElement typeElement)) {
            return false;
        }
        String qualifiedName = typeElement.getQualifiedName().toString();
        if ("java.lang.String".equals(qualifiedName)
                || "java.lang.Boolean".equals(qualifiedName)
                || "java.lang.Byte".equals(qualifiedName)
                || "java.lang.Short".equals(qualifiedName)
                || "java.lang.Integer".equals(qualifiedName)
                || "java.lang.Long".equals(qualifiedName)
                || "java.lang.Float".equals(qualifiedName)
                || "java.lang.Double".equals(qualifiedName)
                || "java.math.BigDecimal".equals(qualifiedName)
                || "java.math.BigInteger".equals(qualifiedName)
                || "java.time.LocalDate".equals(qualifiedName)
                || "java.time.LocalTime".equals(qualifiedName)
                || "java.time.LocalDateTime".equals(qualifiedName)
                || "java.time.Instant".equals(qualifiedName)
                || "java.time.ZonedDateTime".equals(qualifiedName)) {
            return true;
        }
        if (typeElement.getKind() != ElementKind.RECORD) {
            return false;
        }
        for (Element component : typeElement.getRecordComponents()) {
            if (!isSupportedEntryPointRecordComponentType(component.asType(), parsedSources)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedEntryPointRecordComponentType(TypeMirror type, ParsedSources parsedSources) {
        if (type == null || type.getKind() == TypeKind.ERROR) {
            return false;
        }
        if (type.getKind().isPrimitive()) {
            return type.getKind() != TypeKind.VOID;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = parsedSources.types().asElement(type);
        if (!(element instanceof TypeElement typeElement)) {
            return false;
        }
        String qualifiedName = typeElement.getQualifiedName().toString();
        return "java.lang.String".equals(qualifiedName)
                || "java.lang.Boolean".equals(qualifiedName)
                || "java.lang.Byte".equals(qualifiedName)
                || "java.lang.Short".equals(qualifiedName)
                || "java.lang.Integer".equals(qualifiedName)
                || "java.lang.Long".equals(qualifiedName)
                || "java.lang.Float".equals(qualifiedName)
                || "java.lang.Double".equals(qualifiedName)
                || "java.math.BigDecimal".equals(qualifiedName)
                || "java.math.BigInteger".equals(qualifiedName)
                || "java.time.LocalDate".equals(qualifiedName)
                || "java.time.LocalTime".equals(qualifiedName)
                || "java.time.LocalDateTime".equals(qualifiedName)
                || "java.time.Instant".equals(qualifiedName)
                || "java.time.ZonedDateTime".equals(qualifiedName);
    }

    /**
     * The Java AST nodes a proven §3.6-form-1 subquery fusion subsumes in {@code method} — the
     * intermediate-collection constructs ({@code new ArrayList}, the {@code ids.add(...)} accumulation,
     * and B's whole placeholder-run operand sized by {@code ids.size()}) the JDBC front-end elides when
     * fusing a prior query into a later IN-subquery. Empty unless the method bears JDBC handles AND the
     * fusion dataflow proof holds (the same {@link io.titan.transpiler.jdbc.JdbcSubqueryFusion} the
     * lowerer uses), so the validator exempts exactly what the lowerer removes — never any other
     * unsupported construct.
     */
    private static java.util.Set<com.sun.source.tree.Tree> fusionSubsumedTrees(
            MethodTree method,
            TreePath methodPath,
            ParsedSources parsedSources,
            io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle,
            boolean methodBearsJdbcHandles
    ) {
        if (!methodBearsJdbcHandles || method.getBody() == null) {
            return java.util.Set.of();
        }
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        io.titan.transpiler.jdbc.JdbcShapes shapes =
                new io.titan.transpiler.jdbc.JdbcShapes(parsedSources, jdbcOracle);
        return new io.titan.transpiler.jdbc.JdbcSubqueryFusion(parsedSources, shapes, jdbcOracle)
                .recognize(method.getBody().getStatements(), bodyPath, bodyPath)
                .map(io.titan.transpiler.jdbc.JdbcSubqueryFusion.FusionPlan::subsumedTrees)
                .orElseGet(java.util.Set::of);
    }

    /**
     * The proven §3.6-form-2 collection-IN array-bind plans in {@code method} (WS-C Phase 3 Rung 4) — the
     * runtime-sized {@code List}-driven {@code IN}s whose collection is bound as ONE array/JSON parameter,
     * eliding the per-element bind loop / {@code setArray} and the {@code coll.size()}/{@code coll.get(i)}/
     * {@code coll.toArray()} the bind subsumes. Empty unless the method bears JDBC handles AND a use proof
     * holds (the same {@link io.titan.transpiler.jdbc.JdbcCollectionInRecognizer} the lowerer uses), so the
     * validator exempts exactly what the lowerer removes — never any other collection op.
     */
    private static List<io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan>
            collectionInPlans(
            MethodTree method,
            TreePath methodPath,
            ParsedSources parsedSources,
            io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle,
            boolean methodBearsJdbcHandles) {
        if (!methodBearsJdbcHandles || method.getBody() == null) {
            return List.of();
        }
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        io.titan.transpiler.jdbc.JdbcShapes shapes =
                new io.titan.transpiler.jdbc.JdbcShapes(parsedSources, jdbcOracle);
        return new io.titan.transpiler.jdbc.JdbcCollectionInRecognizer(parsedSources, shapes, jdbcOracle)
                .recognizeAll(method.getBody(), bodyPath);
    }

    /**
     * The Java AST nodes a proven D4 optional-filter guarded-predicate lowering subsumes in {@code method}
     * — the search-builder constructs the JDBC front-end elides when collapsing a conditional builder into
     * one static guarded-predicate query: the {@code new StringBuilder(base)} (or base-{@code String})
     * declaration, every {@code if (param != null) sql.append(" AND col OP ?")} append guard, the {@code
     * int i = 1} bind counter, and every {@code if (param != null) ps.setXxx(…, param)} bind guard. Empty
     * unless the method bears JDBC handles AND the control-flow / bind-correlation proof holds (the same
     * {@link io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer} the lowerer uses), so the validator
     * exempts exactly what the lowerer removes — never any other object construction / collection op.
     */
    private static java.util.Set<com.sun.source.tree.Tree> guardedPredicateSubsumedTrees(
            MethodTree method,
            TreePath methodPath,
            ParsedSources parsedSources,
            io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle,
            boolean methodBearsJdbcHandles
    ) {
        if (!methodBearsJdbcHandles || method.getBody() == null) {
            return java.util.Set.of();
        }
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        io.titan.transpiler.jdbc.JdbcShapes shapes =
                new io.titan.transpiler.jdbc.JdbcShapes(parsedSources, jdbcOracle);
        java.util.Set<com.sun.source.tree.Tree> subsumed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer.GuardedPredicatePlan plan
                : new io.titan.transpiler.jdbc.JdbcGuardedPredicateRecognizer(parsedSources, shapes, jdbcOracle)
                        .recognizeAll(method.getBody(), bodyPath)) {
            subsumed.addAll(plan.subsumedTrees());
        }
        return subsumed;
    }

    /**
     * The entry-point parameter {@link Element}s of {@code method} that a proven §3.6-form-2 collection-IN
     * array-binds (WS-C Phase 3 Rung 4) — i.e. {@code List<E>} parameters bound as one native array/JSON
     * parameter. Empty unless the method bears JDBC handles AND a collection-IN use proof holds for a
     * parameter (a local collection has no entry-point parameter to exempt). The validator uses this to
     * exempt exactly those parameters from the Iterable-param reject — never any other Iterable parameter.
     */
    private static java.util.Set<Element> arrayBoundCollectionParameterElements(
            MethodTree method, TreePath methodPath, ParsedSources parsedSources) {
        io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle =
                new io.titan.transpiler.jdbc.JdbcTypeOracle(parsedSources);
        boolean methodBearsJdbcHandles =
                methodBodyBearsJdbcHandles(method, methodPath, parsedSources, jdbcOracle);
        java.util.Set<Element> params = new java.util.HashSet<>();
        for (io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan plan
                : collectionInPlans(method, methodPath, parsedSources, jdbcOracle, methodBearsJdbcHandles)) {
            if (plan.collection() != null
                    && plan.collection().getKind() == ElementKind.PARAMETER) {
                params.add(plan.collection());
            }
        }
        return params;
    }

    /**
     * The Java AST nodes a proven unknown-shape carrier subsumes in {@code method} (WS-C Phase 3 Rung 5)
     * — the generic-reader marshalling scaffolding the JDBC front-end replaces with a single carrier
     * ({@code new ArrayList<>()} accumulator, the {@code new …Map<>()} per-row map, the {@code
     * row.put(md.getColumnLabel(i), rs.getObject(i))} put, the {@code rows.add(row)} accumulation, and
     * the {@code return rows}). Empty unless the method bears JDBC handles AND the carrier proof holds
     * (the same {@link io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer} the lowerer uses), so the
     * validator exempts exactly what the lowerer removes — never any other Map/collection/object-construction op.
     */
    private static java.util.Set<com.sun.source.tree.Tree> carrierSubsumedTrees(
            MethodTree method,
            TreePath methodPath,
            ParsedSources parsedSources,
            io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle,
            boolean methodBearsJdbcHandles
    ) {
        if (!methodBearsJdbcHandles || method.getBody() == null) {
            return java.util.Set.of();
        }
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        io.titan.transpiler.jdbc.JdbcShapes shapes =
                new io.titan.transpiler.jdbc.JdbcShapes(parsedSources, jdbcOracle);
        return new io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer(parsedSources, shapes, jdbcOracle)
                .recognize(method.getBody(), bodyPath)
                .map(io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer.CarrierPlan::subsumedTrees)
                .orElseGet(java.util.Set::of);
    }

    /** Whether {@code method}'s body declares — anywhere — a java.sql/javax.sql handle local/resource. */
    private static boolean methodBodyBearsJdbcHandles(
            MethodTree method,
            TreePath methodPath,
            ParsedSources parsedSources,
            io.titan.transpiler.jdbc.JdbcTypeOracle oracle
    ) {
        if (method.getBody() == null) {
            return false;
        }
        boolean[] found = {false};
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(com.sun.source.tree.VariableTree node, Void unused) {
                if (!found[0]) {
                    TreePath path = getCurrentPath();
                    TypeMirror type = path == null ? null : parsedSources.trees().getTypeMirror(path);
                    if (type != null && oracle.isJdbcHandle(type)) {
                        found[0] = true;
                    }
                }
                return super.visitVariable(node, unused);
            }
        }.scan(bodyPath, null);
        return found[0];
    }

    /**
     * Indexes every node in a method body once. Feature validation performs many type and element
     * lookups; {@link TreePath#getPath(CompilationUnitTree, Tree)} rescans the entire compilation
     * unit for each lookup, which is prohibitively expensive for generated routines. The identity
     * index preserves javac's exact paths while making those lookups constant-time.
     */
    private static Map<Tree, TreePath> methodBodyTreePaths(MethodTree method, TreePath methodPath) {
        if (method.getBody() == null) {
            return Map.of();
        }
        java.util.IdentityHashMap<Tree, TreePath> paths = new java.util.IdentityHashMap<>();
        TreePath bodyPath = new TreePath(methodPath, method.getBody());
        paths.put(method.getBody(), bodyPath);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) {
                    paths.put(tree, new TreePath(getCurrentPath(), tree));
                }
                return super.scan(tree, unused);
            }
        }.scan(bodyPath, null);
        return paths;
    }

    private static void validateMethodBody(
            MethodTree method,
            TreePath methodPath,
            CompilationUnitTree unit,
            ParsedSources parsedSources,
            List<String> errors,
            DiscoveredEntryPoint entryPoint,
            List<DialectCapabilities> targetCapabilities,
            DiagnosticSink warningSink
    ) {
        // WS-C Phase 2: a method whose body bears java.sql/javax.sql handles is lowered by the JDBC
        // front-end (JdbcStatementLowerer), which elides the handles and produces the native nodes.
        // The handle scaffolding calls (prepareStatement/setXxx/executeQuery/rs.getX/...) are therefore
        // NOT P0 violations here — they are exempted from the unsupported-construct rejections below;
        // the JDBC recognizer owns their acceptance, and any non-JDBC statement still lowers (and is
        // rejected if unsupported) through the stock lowering path the JDBC lowerer delegates to.
        io.titan.transpiler.jdbc.JdbcTypeOracle jdbcOracle =
                new io.titan.transpiler.jdbc.JdbcTypeOracle(parsedSources);
        boolean methodBearsJdbcHandles = methodBodyBearsJdbcHandles(method, methodPath, parsedSources, jdbcOracle);
        // WS-C Phase 3 Rung 2 (§3.6 form 1): when the JDBC front-end will FUSE a prior query into a
        // later IN-subquery, it ELIDES the intermediate collection (the `new ArrayList`, the
        // `ids.add(...)` accumulation, and the `ids.size()` sizing the run). Those constructs are
        // therefore not P0 violations for a fusable method — exempt exactly the tree nodes a proven
        // fusion subsumes (and NOTHING else: any other collection op / object construction still
        // rejects, and a non-fusable List method still rejects). The proof is the same JdbcSubqueryFusion
        // the lowerer uses, so the validator and the lowerer agree on what fusion removes.
        java.util.Set<com.sun.source.tree.Tree> fusionSubsumedTrees =
                fusionSubsumedTrees(method, methodPath, parsedSources, jdbcOracle, methodBearsJdbcHandles);
        // WS-C Phase 3 Rung 4 (§3.6 form 2): when the JDBC front-end will ARRAY-BIND a runtime-sized
        // List-driven IN, it ELIDES the per-element bind loop / setArray and the coll.size()/coll.get(i)/
        // coll.toArray() the bind subsumes. Those constructs are therefore not P0 violations for an
        // array-bound method — exempt exactly the tree nodes a proven collection-IN subsumes (and NOTHING
        // else: any other collection op still rejects). The proof is the same JdbcCollectionInRecognizer
        // the lowerer uses, so the validator and the lowerer agree on what the array-bind removes.
        java.util.Set<com.sun.source.tree.Tree> collectionInSubsumedTrees =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan plan
                : collectionInPlans(method, methodPath, parsedSources, jdbcOracle, methodBearsJdbcHandles)) {
            collectionInSubsumedTrees.addAll(plan.subsumedTrees());
        }
        // WS-C Phase 3 Rung 5 (unknown-shape carrier): when the JDBC front-end will replace a PURE
        // metadata-driven generic reader with a single carrier (PG jsonb / MySQL result set), it SUBSUMES
        // the marshalling scaffolding — the `new ArrayList<>()` accumulator, the per-row `new …Map<>()`,
        // the `row.put(md.getColumnLabel(i), rs.getObject(i))`, the `rows.add(row)`, and the `return rows`.
        // Those constructs are therefore not P0 violations for a carrier method — exempt exactly the tree
        // nodes the proven carrier subsumes (and NOTHING else: any other Map/collection/object op still
        // rejects). The proof is the same JdbcDynamicResultRecognizer the lowerer uses, so the validator
        // and the lowerer agree on what the carrier removes. (The md.* metadata calls are exempt
        // separately as ResultSetMetaData JDBC-handle dispatch, like rs.getObject.)
        java.util.Set<com.sun.source.tree.Tree> carrierSubsumedTrees =
                carrierSubsumedTrees(method, methodPath, parsedSources, jdbcOracle, methodBearsJdbcHandles);
        // Design contract D4 (optional-filter guarded predicates): when the JDBC front-end will collapse a
        // conditional search builder into ONE static guarded-predicate query, it SUBSUMES the builder
        // constructs — the `new StringBuilder(base)` (or base-String) declaration, every `if (param !=
        // null) sql.append(" AND col OP ?")` append guard, the `int i = 1` bind counter, and every `if
        // (param != null) ps.setXxx(…, param)` bind guard. Those constructs are therefore not P0 violations
        // for a guarded-predicate method — exempt exactly the tree nodes a proven lowering subsumes (and
        // NOTHING else: any other object construction / collection op still rejects). The proof is the same
        // JdbcGuardedPredicateRecognizer the lowerer uses, so the validator and the lowerer agree.
        java.util.Set<com.sun.source.tree.Tree> guardedPredicateSubsumedTrees =
                guardedPredicateSubsumedTrees(method, methodPath, parsedSources, jdbcOracle, methodBearsJdbcHandles);
        Map<Tree, TreePath> treePaths = methodBodyTreePaths(method, methodPath);
        new TreeScanner<Void, Void>() {
            /**
             * Innermost-first stack of the enclosing constructs that matter for break/continue
             * legality: loops (valid targets), switches (break would target the switch, which
             * lowers to an IF chain with no breakable scope), and try blocks (a loop exit
             * crossing them would skip the emitted finally statements).
             */
            private final Deque<Tree.Kind> controlContext = new ArrayDeque<>();

            private TreePath treePath(Tree tree) {
                return treePaths.get(tree);
            }

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }

                switch (tree.getKind()) {
                    case SYNCHRONIZED -> addUnsupportedError("synchronized block", tree);
                    case LAMBDA_EXPRESSION -> addUnsupportedError("lambda expression", tree);
                    case MEMBER_REFERENCE -> addUnsupportedError("method reference", tree);
                    case CLASS, INTERFACE, ENUM, RECORD -> addUnsupportedError("nested type declaration", tree);
                    case ASSERT -> addUnsupportedError("assert statement", tree);
                    case BREAK -> validateBreak((BreakTree) tree);
                    case CONTINUE -> validateContinue((ContinueTree) tree);
                    case TYPE_CAST -> validateTypeCast((TypeCastTree) tree);
                    case INSTANCE_OF -> addUnsupportedError("instanceof expression", tree);
                    case AND, OR, XOR, LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT, BITWISE_COMPLEMENT,
                            AND_ASSIGNMENT, OR_ASSIGNMENT, XOR_ASSIGNMENT,
                            LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT ->
                            addUnsupportedError("bitwise or shift operator", tree);
                    case NEW_CLASS -> {
                        if (isThrownExceptionConstruction(tree)) {
                            // Supported throw lowering handles exception construction separately.
                        } else if (fusionSubsumedTrees.contains(tree)) {
                            // §3.6 form 1: the `new ArrayList<>()` for the intermediate IN-list a proven
                            // fusion elides — not a P0 violation (the collection never materializes).
                        } else if (carrierSubsumedTrees.contains(tree)) {
                            // Rung 5: the `new ArrayList<>()` accumulator / per-row `new …Map<>()` a proven
                            // unknown-shape carrier subsumes — not a P0 violation (the carrier replaces them).
                        } else if (guardedPredicateSubsumedTrees.contains(tree)) {
                            // D4: the `new StringBuilder(base)` of a proven optional-filter guarded predicate
                            // — not a P0 violation (the static guarded-predicate query replaces the builder).
                        } else if (isRecordConstruction((NewClassTree) tree)) {
                            validateRecordConstruction((NewClassTree) tree);
                        } else {
                            addUnsupportedError(
                                    "object or record construction in transpiled code (requires structured value support)",
                                    tree);
                        }
                    }
                    case ARRAY_ACCESS -> {
                        // Read-only indexed access is part of P1 structured arrays.
                    }
                    case MEMBER_SELECT -> {
                        MemberSelectTree memberSelectTree = (MemberSelectTree) tree;
                        if (isReflectiveClassLiteral(memberSelectTree)) {
                            addUnsupportedError("reflection via class literal in transpiled code", tree);
                        }
                        if (isInstanceFieldAccess(memberSelectTree)) {
                            addUnsupportedError(
                                    "object field access in transpiled code (requires structured value support)",
                                    tree);
                        }
                    }
                    case NEW_ARRAY -> {
                        NewArrayTree newArrayTree = (NewArrayTree) tree;
                        if (newArrayTree.getInitializers() == null) {
                            addUnsupportedError(
                                    "dimensioned array construction in transpiled code (requires default element materialization)",
                                    tree);
                        } else if (!isSupportedArrayConstruction(newArrayTree)) {
                            addUnsupportedError(
                                    "array construction with unsupported element type in transpiled code (structured arrays require scalar or record elements)",
                                    tree);
                        }
                    }
                    default -> {
                        // Supported or deferred for later semantic passes.
                    }
                }

                switch (tree.getKind()) {
                    case WHILE_LOOP, DO_WHILE_LOOP, FOR_LOOP, ENHANCED_FOR_LOOP, SWITCH, SWITCH_EXPRESSION, TRY -> {
                        controlContext.push(tree.getKind());
                        try {
                            return super.scan(tree, unused);
                        } finally {
                            controlContext.pop();
                        }
                    }
                    default -> {
                        return super.scan(tree, unused);
                    }
                }
            }

            /**
             * Plan 2.2 (F-9 follow-up): casts between supported numeric types are allowed —
             * identity/widening/fractional-truncation per {@link JavaCastClassifier} — and
             * everything else (long→int wraparound narrowing, byte/short/char targets,
             * reference and {@code (Object)} casts) keeps a positioned rejection.
             */
            private void validateTypeCast(TypeCastTree node) {
                TreePath sourcePath = treePath(node.getExpression());
                TreePath targetPath = treePath(node.getType());
                JavaCastClassifier.Classification classification = JavaCastClassifier.classify(
                        sourcePath == null ? null : parsedSources.trees().getTypeMirror(sourcePath),
                        targetPath == null ? null : parsedSources.trees().getTypeMirror(targetPath),
                        parsedSources.types());
                if (classification.kind() == JavaCastClassifier.Kind.UNSUPPORTED) {
                    addUnsupportedError(classification.rejectionDetail(), node, classification.suggestion());
                }
            }

            private void validateBreak(BreakTree node) {
                if (node.getLabel() != null) {
                    addUnsupportedError("labeled break statement", node,
                            "Use an unlabeled break (which exits the innermost loop) or restructure the outer loop with a boolean flag");
                    return;
                }
                for (Tree.Kind enclosing : controlContext) {
                    switch (enclosing) {
                        case WHILE_LOOP, DO_WHILE_LOOP, FOR_LOOP, ENHANCED_FOR_LOOP -> {
                            return; // Innermost break target is a loop: supported.
                        }
                        case SWITCH, SWITCH_EXPRESSION -> {
                            addUnsupportedError("break statement targeting a switch", node,
                                    "Titan lowers switches to IF/ELSE chains with no breakable scope; restructure the case body so it does not break out of the switch");
                            return;
                        }
                        case TRY -> {
                            addUnsupportedError("break statement crossing a try/catch/finally boundary", node,
                                    "The emitted loop exit would skip finally statements; move the loop inside the try block or exit via a boolean flag");
                            return;
                        }
                        default -> {
                            // Only the kinds above are pushed.
                        }
                    }
                }
                addUnsupportedError("break statement outside of a loop", node,
                        "break is only supported inside while/do-while/for/for-each loop bodies");
            }

            private void validateContinue(ContinueTree node) {
                if (node.getLabel() != null) {
                    addUnsupportedError("labeled continue statement", node,
                            "Use an unlabeled continue (which targets the innermost loop) or restructure the outer loop with a boolean flag");
                    return;
                }
                for (Tree.Kind enclosing : controlContext) {
                    switch (enclosing) {
                        case WHILE_LOOP, DO_WHILE_LOOP, FOR_LOOP, ENHANCED_FOR_LOOP -> {
                            return; // Innermost continue target is a loop: supported.
                        }
                        case TRY -> {
                            addUnsupportedError("continue statement crossing a try/catch/finally boundary", node,
                                    "The emitted loop re-entry would skip finally statements; move the loop inside the try block or guard the remaining statements with an IF");
                            return;
                        }
                        default -> {
                            // Switches are transparent to continue (it always targets a loop).
                        }
                    }
                }
                addUnsupportedError("continue statement outside of a loop", node,
                        "continue is only supported inside while/do-while/for/for-each loop bodies");
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                if (stripParentheses(node.getVariable()) instanceof ArrayAccessTree) {
                    addUnsupportedError(
                            "mutable array element assignment in transpiled code (structured arrays are immutable)",
                            node.getVariable());
                } else if (isArrayExpression(node.getVariable())) {
                    addUnsupportedError(
                            "array traversal state reassignment in transpiled code (bounded traversal arrays must be constructed in place and read-only)",
                            node.getVariable());
                } else if (isArrayValuedInitializer(node.getExpression())) {
                    addUnsupportedError(
                            "aliased array traversal state in transpiled code (bounded traversal arrays must be constructed in place and read-only)",
                            node.getVariable());
                }
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                if (stripParentheses(node.getVariable()) instanceof ArrayAccessTree) {
                    addUnsupportedError(
                            "mutable array element assignment in transpiled code (structured arrays are immutable)",
                            node.getVariable());
                }
                return super.visitCompoundAssignment(node, unused);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                if ((node.getKind() == Tree.Kind.POSTFIX_INCREMENT
                                || node.getKind() == Tree.Kind.PREFIX_INCREMENT
                                || node.getKind() == Tree.Kind.POSTFIX_DECREMENT
                                || node.getKind() == Tree.Kind.PREFIX_DECREMENT)
                        && stripParentheses(node.getExpression()) instanceof ArrayAccessTree) {
                    addUnsupportedError(
                            "mutable array element assignment in transpiled code (structured arrays are immutable)",
                            node.getExpression());
                }
                return super.visitUnary(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (isArrayExpression(node)
                        && (node.getInitializer() == null
                                || !(stripParentheses(node.getInitializer()) instanceof NewArrayTree))) {
                    addUnsupportedError(
                            "aliased array traversal state in transpiled code (bounded traversal arrays must be constructed in place and read-only)",
                            node);
                } else if (!isArrayExpression(node)
                        && node.getInitializer() != null
                        && isArrayValuedInitializer(node.getInitializer())) {
                    addUnsupportedError(
                            "aliased array traversal state in transpiled code (bounded traversal arrays must be constructed in place and read-only)",
                            node);
                }
                ReferenceGraph referenceGraph = compilerKnownReferenceGraph(node);
                if (referenceGraph != null) {
                    validateReferenceGraph(referenceGraph, node);
                }
                return super.visitVariable(node, unused);
            }

            @Override
            public Void visitWhileLoop(WhileLoopTree node, Void unused) {
                CursorScan cursorScan = cursorBoundedStringScan(node);
                if (cursorScan != null) {
                    if (!hasNonNegativeCursorStateAtLoop(node, cursorScan.cursorName())) {
                        addUnsupportedError(
                                "nested traversal scanner without non-negative cursor initialization",
                                node);
                    }
                    validateCursorScanBody(node.getStatement(), cursorScan, 0, 0, node);
                }
                return super.visitWhileLoop(node, unused);
            }

            @Override
            public Void visitForLoop(ForLoopTree node, Void unused) {
                CursorScan cursorScan = cursorBoundedStringScan(node);
                if (cursorScan != null) {
                    if (!hasNonNegativeForInitializer(node, cursorScan.cursorName())) {
                        addUnsupportedError(
                                "nested traversal scanner without non-negative cursor initialization",
                                node);
                    }
                    int updateIncrements = countCursorIncrements(node.getUpdate(), cursorScan.cursorName());
                    int updateWrites = countCursorWrites(node.getUpdate(), cursorScan.cursorName());
                    validateCursorScanBody(node.getStatement(), cursorScan, updateIncrements, updateWrites, node);
                }
                return super.visitForLoop(node, unused);
            }

            @Override
            public Void visitSwitch(SwitchTree node, Void unused) {
                for (CaseTree caseTree : node.getCases()) {
                    if (caseTree.getCaseKind() != CaseTree.CaseKind.RULE) {
                        addUnsupportedError("switch statement with colon-style cases", caseTree,
                                "Rewrite the switch using arrow-style rules (case X -> ...), which have no fall-through");
                    }
                }
                if (!hasDefaultCase(node) && !isEnumExhaustive(node)) {
                    addUnsupportedError("non-exhaustive switch statement (default case required unless all enum constants are covered)", node);
                }
                return super.visitSwitch(node, unused);
            }

            @Override
            public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
                for (CaseTree caseTree : node.getCases()) {
                    if (caseTree.getCaseKind() != CaseTree.CaseKind.RULE) {
                        addUnsupportedError("switch expression with colon-style cases", caseTree,
                                "Rewrite the switch using arrow-style rules (case X -> ...), which have no fall-through");
                    }
                }
                if (!hasDefaultCase(node) && !isEnumExhaustive(node)) {
                    addUnsupportedError("non-exhaustive switch expression (default case required unless all enum constants are covered)", node);
                }
                return super.visitSwitchExpression(node, unused);
            }

            private boolean hasDefaultCase(SwitchExpressionTree node) {
                return hasDefaultCase(node.getCases());
            }

            private boolean hasDefaultCase(SwitchTree node) {
                return hasDefaultCase(node.getCases());
            }

            private boolean hasDefaultCase(List<? extends CaseTree> cases) {
                for (CaseTree caseTree : cases) {
                    for (var label : caseTree.getLabels()) {
                        if (label.getKind() == com.sun.source.tree.Tree.Kind.DEFAULT_CASE_LABEL) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean isEnumExhaustive(SwitchExpressionTree node) {
                return isEnumExhaustive(node.getExpression(), node.getCases());
            }

            private boolean isEnumExhaustive(SwitchTree node) {
                return isEnumExhaustive(node.getExpression(), node.getCases());
            }

            private boolean isEnumExhaustive(Tree selector, List<? extends CaseTree> cases) {
                TreePath selectorPath = treePath(selector);
                if (selectorPath == null) {
                    return false;
                }

                TypeMirror selectorType = parsedSources.trees().getTypeMirror(selectorPath);
                if (selectorType == null || selectorType.getKind() != TypeKind.DECLARED) {
                    return false;
                }

                Element selectorElement = parsedSources.types().asElement(selectorType);
                if (!(selectorElement instanceof TypeElement typeElement) || typeElement.getKind() != ElementKind.ENUM) {
                    return false;
                }

                Set<String> enumConstants = new HashSet<>();
                for (Element enclosed : typeElement.getEnclosedElements()) {
                    if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                        enumConstants.add(enclosed.getSimpleName().toString());
                    }
                }
                if (enumConstants.isEmpty()) {
                    return false;
                }

                Set<String> covered = new HashSet<>();
                for (CaseTree caseTree : cases) {
                    for (CaseLabelTree label : caseTree.getLabels()) {
                        if (label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) {
                            continue;
                        }
                        String token = label.toString().trim();
                        if (token.isEmpty()) {
                            continue;
                        }
                        int dot = token.lastIndexOf('.');
                        String constant = dot >= 0 ? token.substring(dot + 1) : token;
                        if (enumConstants.contains(constant)) {
                            covered.add(constant);
                        }
                    }
                }

                return covered.containsAll(enumConstants);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String called = extractCalledMethodName(node);

                validateDialectCapabilities(node, called);

                // WS-C Phase 2: exempt java.sql/javax.sql handle dispatch in a JDBC-bearing method from
                // the P0 unsupported-construct rejections (interface/instance/collection). These calls
                // are the JDBC scaffolding the front-end elides/lowers; rejecting them here would block
                // every transpilable JDBC method. Arguments still recurse (an unsafe splice inside a
                // bind, etc. is still validated).
                if (methodBearsJdbcHandles && receiverIsJdbcHandle(node)) {
                    return super.visitMethodInvocation(node, unused);
                }

                if (RawSqlConstantRule.isRawSqlInvocationName(called)
                        && !node.getArguments().isEmpty()
                        && !RawSqlConstantRule.isCompileTimeConstantSqlText(
                                node.getArguments().getFirst(), unit, parsedSources)) {
                    // Dynamic-SQL safety (plan 2.5, audit D14): always-on pre-lowering check;
                    // DslQueryLowerer re-applies the same rule as defense-in-depth.
                    errors.add(new TitanDiagnostic(
                            TitanErrorCode.E004,
                            RawSqlConstantRule.violationMessage(called),
                            sourceLocation(node.getArguments().getFirst()),
                            RawSqlConstantRule.bindParameterSuggestion(),
                            null
                    ).render());
                }

                if (isClassForNameInvocation(node)) {
                    addUnsupportedError("reflection via Class.forName(...) in transpiled code", node);
                }

                if (isGetClassInvocation(node)) {
                    addUnsupportedError("reflection via getClass() in transpiled code", node);
                }

                if (isCollectionInvocation(node)
                        && !fusionSubsumedTrees.contains(node)
                        && !collectionInSubsumedTrees.contains(node)
                        && !carrierSubsumedTrees.contains(node)
                        && !guardedPredicateSubsumedTrees.contains(node)) {
                    // §3.6 form 1: the `ids.add(...)` accumulation and the `ids.size()`/run-construction
                    // sizing the IN-run that a proven fusion elides are exempt; §3.6 form 2: the
                    // `coll.size()`/`coll.get(i)`/`coll.toArray()` binds and run-construction a proven
                    // collection-IN array-bind elides are exempt; Rung 5: the `rows.add(row)`/`row.put(...)`
                    // marshalling a proven unknown-shape carrier subsumes are exempt; any OTHER collection op
                    // still rejects (each subsumed set is exactly what the lowerer removes).
                    addUnsupportedError(
                            "collection operation in transpiled code (requires structured collection support)",
                            node);
                }

                if (isInterfaceReceiverInvocation(node)) {
                    addUnsupportedError("interface dispatch in transpiled code (requires closed-world monomorphic resolution)", node);
                }

                if (isRecordReceiverInvocation(node) && !isDslDeclaredMethodInvocation(node)) {
                    // DSL records (e.g. SortField.nullsFirst()/nullsLast()) are query-construction
                    // surface interpreted by DslQueryLowerer, not runtime record accessors.
                    validateRecordAccessor(node);
                }

                if (isUnsupportedInstanceInvocation(node)) {
                    addUnsupportedError(
                            "instance method call in transpiled code (requires closed-world monomorphic dispatch)",
                            node);
                }

                if ("abortWithError".equals(called)
                        && (entryPoint == null || entryPoint.kind() != EntryPointKind.TRIGGER)) {
                    addUnsupportedError("abortWithError() outside trigger context", node);
                }

                if (entryPoint != null && entryPoint.kind() == EntryPointKind.TRIGGER && entryPoint.triggerDefinition() != null) {
                    List<String> events = entryPoint.triggerDefinition().events();
                    String timing = entryPoint.triggerDefinition().timing();
                    if ("newRow".equals(called) && events.contains("DELETE")) {
                        addUnsupportedError("newRow() in DELETE trigger context", node);
                    }
                    if ("oldRow".equals(called) && events.contains("INSERT")) {
                        addUnsupportedError("oldRow() in INSERT trigger context", node);
                    }

                    if ("set".equals(called)) {
                        String receiver = extractReceiverInvocationName(node);
                        if ("oldRow".equals(receiver)) {
                            addUnsupportedError("oldRow().set(...) in trigger context", node);
                        }
                        if ("newRow".equals(receiver) && "AFTER".equals(timing)) {
                            addUnsupportedError("newRow().set(...) in AFTER trigger context", node);
                        }
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            private String extractCalledMethodName(MethodInvocationTree node) {
                if (node.getMethodSelect() instanceof IdentifierTree identifierTree) {
                    return identifierTree.getName().toString();
                }
                if (node.getMethodSelect() instanceof MemberSelectTree memberSelectTree) {
                    return memberSelectTree.getIdentifier().toString();
                }
                return "";
            }

            /**
             * Dialect-capability gate (plan 3.1, audit E-3/E-6/E-9): rejects — at compile time,
             * with a source position — constructs that one of the transpile targets cannot emit
             * ({@code TITAN-E001} naming the dialect plus the capability's rewrite suggestion),
             * and warns ({@code TITAN-W005}) for constructs gated on a minimum server version.
             * The emit-time throws in the emitters remain as defense-in-depth for directly
             * constructed TIR.
             */
            private void validateDialectCapabilities(MethodInvocationTree node, String called) {
                if (targetCapabilities.isEmpty()) {
                    return;
                }
                switch (called) {
                    case "fullOuterJoin" -> {
                        if (!node.getArguments().isEmpty() && isDslChainInvocation(node)) {
                            for (DialectCapabilities capabilities : targetCapabilities) {
                                rejectOrWarn("FULL OUTER JOIN", capabilities.fullOuterJoin(), capabilities, node);
                            }
                        }
                    }
                    case "returning" -> {
                        String root = chainRootName(node);
                        if ("update".equals(root) || "deleteFrom".equals(root)) {
                            String construct = "update".equals(root) ? "UPDATE ... RETURNING" : "DELETE ... RETURNING";
                            for (DialectCapabilities capabilities : targetCapabilities) {
                                rejectOrWarn(construct, capabilities.updateDeleteReturning(), capabilities, node);
                            }
                        }
                    }
                    case "intersect", "except" -> {
                        if (!node.getArguments().isEmpty() && isDslChainInvocation(node)) {
                            String construct = "intersect".equals(called) ? "INTERSECT" : "EXCEPT";
                            for (DialectCapabilities capabilities : targetCapabilities) {
                                rejectOrWarn(construct, capabilities.intersectExcept(), capabilities, node);
                            }
                        }
                    }
                    case "format" -> {
                        if (isStringFormatInvocation(node)) {
                            for (DialectCapabilities capabilities : targetCapabilities) {
                                rejectOrWarn("String.format", capabilities.stringFormat(), capabilities, node);
                            }
                        }
                    }
                    default -> {
                        // No dialect-gated construct.
                    }
                }
            }

            private void rejectOrWarn(
                    String construct,
                    DialectCapabilities.Capability capability,
                    DialectCapabilities capabilities,
                    Tree tree
            ) {
                if (capability.isUnsupported()) {
                    errors.add(new TitanDiagnostic(
                            TitanErrorCode.E001,
                            construct + " is not supported by " + capabilities.displayName()
                                    + " (any version); this construct cannot be transpiled for the "
                                    + capabilities.dialect().name().toLowerCase(java.util.Locale.ROOT) + " target",
                            sourceLocation(tree),
                            capability.suggestion(),
                            "Section 12.1"
                    ).render());
                    return;
                }
                if (capability.isVersionGated()) {
                    warningSink.warning(new TitanDiagnostic(
                            TitanErrorCode.W005,
                            construct + " requires " + capabilities.displayName() + " >= "
                                    + capability.minServerVersion()
                                    + "; the generated SQL fails at CREATE time on older "
                                    + capabilities.displayName() + " servers",
                            sourceLocation(tree),
                            "Ensure every deployment target runs " + capabilities.displayName() + " "
                                    + capability.minServerVersion() + " or newer",
                            "Section 12.1"
                    ));
                }
            }

            /** Walks the receiver chain to the root invocation and returns its method name. */
            private String chainRootName(MethodInvocationTree node) {
                MethodInvocationTree cursor = node;
                while (cursor.getMethodSelect() instanceof MemberSelectTree memberSelectTree
                        && memberSelectTree.getExpression() instanceof MethodInvocationTree previous) {
                    cursor = previous;
                }
                return extractCalledMethodName(cursor);
            }

            /** Whether the invocation sits on a chain rooted at a Titan DSL entry method. */
            private boolean isDslChainInvocation(MethodInvocationTree node) {
                return switch (chainRootName(node)) {
                    case "select", "selectFrom", "insertInto", "update", "deleteFrom", "with", "withRecursive" -> true;
                    default -> false;
                };
            }

            /** {@code String.format(...)} — resolved against java.lang.String, with a syntactic fallback. */
            private boolean isStringFormatInvocation(MethodInvocationTree node) {
                TreePath invocationPath = treePath(node);
                if (invocationPath != null
                        && parsedSources.trees().getElement(invocationPath) instanceof ExecutableElement method
                        && "format".contentEquals(method.getSimpleName())
                        && method.getEnclosingElement() instanceof TypeElement owner
                        && "java.lang.String".contentEquals(owner.getQualifiedName())) {
                    return true;
                }
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                if (!"format".contentEquals(memberSelectTree.getIdentifier())) {
                    return false;
                }
                String owner = memberSelectTree.getExpression().toString();
                return "String".equals(owner) || "java.lang.String".equals(owner);
            }

            private String extractReceiverInvocationName(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return "";
                }
                if (!(memberSelectTree.getExpression() instanceof MethodInvocationTree receiverInvocation)) {
                    return "";
                }
                return extractCalledMethodName(receiverInvocation);
            }

            private boolean isClassForNameInvocation(MethodInvocationTree node) {
                TreePath invocationPath = treePath(node);
                if (invocationPath != null
                        && parsedSources.trees().getElement(invocationPath) instanceof ExecutableElement method
                        && "forName".contentEquals(method.getSimpleName())
                        && method.getEnclosingElement() instanceof TypeElement owner
                        && "java.lang.Class".contentEquals(owner.getQualifiedName())) {
                    return true;
                }
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                if (!"forName".contentEquals(memberSelectTree.getIdentifier())) {
                    return false;
                }
                String owner = memberSelectTree.getExpression().toString();
                return "Class".equals(owner) || "java.lang.Class".equals(owner);
            }

            /** True when this invocation's receiver resolves to a java.sql/javax.sql handle type. */
            private boolean receiverIsJdbcHandle(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                return receiverType != null && jdbcOracle.isJdbcHandle(receiverType);
            }

            private boolean isInterfaceReceiverInvocation(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                if (isStaticMethodInvocation(node) || isCollectionInvocation(node) || isTriggerRowAccessorInvocation(node)) {
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                if (receiverType == null) {
                    return false;
                }
                return isInterfaceType(receiverType);
            }

            private boolean isUnsupportedInstanceInvocation(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                if (isStaticMethodInvocation(node)
                        || isCollectionInvocation(node)
                        || isInterfaceReceiverInvocation(node)
                        || isRecordReceiverInvocation(node)
                        || isSupportedConcreteInstanceHelperInvocation(node)
                        || isTriggerRowAccessorInvocation(node)
                        || isDslDeclaredMethodInvocation(node)) {
                    // DSL-declared methods called on user subclasses (e.g. Table.as(...) on a
                    // generated table class, audit D-7) are query-construction surface lowered
                    // by DslQueryLowerer, not runtime dispatch.
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                return isUnsupportedInstanceReceiverType(receiverType);
            }

            private boolean isSupportedConcreteInstanceHelperInvocation(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                ExecutableElement method = resolvedExecutable(node);
                if (method == null
                        || method.getModifiers().contains(Modifier.STATIC)
                        || !(method.getEnclosingElement() instanceof TypeElement owner)
                        || owner.getKind() != ElementKind.CLASS
                        || !owner.getModifiers().contains(Modifier.FINAL)
                        || sourceLocalMethodTree(method) == null
                        || instanceHelperUsesReceiverState(method)) {
                    return false;
                }
                return isCompilerKnownConcreteReceiver(memberSelectTree.getExpression(), owner);
            }

            private boolean isCompilerKnownConcreteReceiver(Tree receiver, TypeElement owner) {
                TreePath receiverPath = treePath(receiver);
                Element receiverElement = receiverPath == null ? null : parsedSources.trees().getElement(receiverPath);
                if (!(receiverElement instanceof javax.lang.model.element.VariableElement variable)) {
                    return false;
                }
                if (!sameErasedType(variable.asType(), owner.asType())) {
                    return false;
                }
                if (variable.getKind() == ElementKind.FIELD) {
                    return variable.getModifiers().contains(Modifier.STATIC)
                            && variable.getModifiers().contains(Modifier.FINAL);
                }
                if (variable.getKind() != ElementKind.LOCAL_VARIABLE) {
                    return false;
                }
                VariableTree declaration = sourceLocalVariableTree(variable);
                return declaration != null
                        && declaration.getInitializer() != null
                        && isCompilerKnownConcreteReceiver(declaration.getInitializer(), owner);
            }

            private boolean sameErasedType(TypeMirror left, TypeMirror right) {
                if (left == null || right == null) {
                    return false;
                }
                return parsedSources.types().isSameType(
                        parsedSources.types().erasure(left),
                        parsedSources.types().erasure(right));
            }

            private MethodTree sourceLocalMethodTree(ExecutableElement target) {
                return LoweringContext.forSources(parsedSources).sourceMethodTree(target);
            }

            private VariableTree sourceLocalVariableTree(javax.lang.model.element.VariableElement target) {
                return LoweringContext.forSources(parsedSources).sourceVariableTree(target);
            }

            private boolean instanceHelperUsesReceiverState(ExecutableElement method) {
                TreePath methodPath = sourceLocalMethodPath(method);
                if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
                    return true;
                }
                final boolean[] usesState = {false};
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        String name = node.getName().toString();
                        if ("this".equals(name) || "super".equals(name)) {
                            usesState[0] = true;
                            return super.visitIdentifier(node, unused);
                        }
                        Element element = parsedSources.trees().getElement(getCurrentPath());
                        if (isReceiverField(element, method)) {
                            usesState[0] = true;
                        }
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        String receiver = node.getExpression().toString();
                        if ("this".equals(receiver) || "super".equals(receiver)) {
                            usesState[0] = true;
                        }
                        Element element = parsedSources.trees().getElement(getCurrentPath());
                        if (isReceiverField(element, method)) {
                            usesState[0] = true;
                        }
                        return super.visitMemberSelect(node, unused);
                    }
                }.scan(new TreePath(methodPath, methodTree.getBody()), null);
                return usesState[0];
            }

            private TreePath sourceLocalMethodPath(ExecutableElement target) {
                return LoweringContext.forSources(parsedSources).sourceDeclarationPath(target);
            }

            private boolean isReceiverField(Element element, ExecutableElement method) {
                return element instanceof javax.lang.model.element.VariableElement variable
                        && variable.getKind() == ElementKind.FIELD
                        && variable.getEnclosingElement().equals(method.getEnclosingElement())
                        && !variable.getModifiers().contains(Modifier.STATIC);
            }

            private boolean isRecordReceiverInvocation(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)
                        || isStaticMethodInvocation(node)
                        || isCollectionInvocation(node)
                        || isTriggerRowAccessorInvocation(node)) {
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                if (receiverType == null || receiverType.getKind() != TypeKind.DECLARED) {
                    return false;
                }
                Element receiverElement = parsedSources.types().asElement(receiverType);
                return receiverElement instanceof TypeElement typeElement
                        && typeElement.getKind() == ElementKind.RECORD;
            }

            private void validateRecordConstruction(NewClassTree node) {
                TypeElement recordType = constructedRecordType(node);
                if (recordType == null) {
                    addUnsupportedError(
                            "object or record construction in transpiled code (requires structured value support)",
                            node);
                    return;
                }
                List<? extends Element> components = recordType.getRecordComponents();
                for (int i = 0; i < components.size(); i++) {
                    Element component = components.get(i);
                    if (!isSupportedRecordComponentType(component.asType())) {
                        addUnsupportedError(
                                "record component type '" + component.asType()
                                        + "' in transpiled code (only scalar record fields are supported)",
                                node);
                    }
                    if (i < node.getArguments().size()
                            && isStructuralIdentifierComponent(component.getSimpleName().toString())
                            && !isCompileTimeIdentifierArgument(node.getArguments().get(i))) {
                        addUnsupportedError(
                                "runtime-derived structural identifier record component '"
                                        + component.getSimpleName() + "' (record identifier fields must be compiler-known)",
                                node.getArguments().get(i));
                    }
                }
            }

            private void validateRecordAccessor(MethodInvocationTree node) {
                ExecutableElement accessor = resolvedExecutable(node);
                TypeElement recordType = accessor != null && accessor.getEnclosingElement() instanceof TypeElement owner
                        ? owner
                        : null;
                if (recordType == null) {
                    addUnsupportedError(
                            "record accessor call in transpiled code (requires structured value support)",
                            node);
                    return;
                }
                for (Element component : recordType.getRecordComponents()) {
                    if (component.getSimpleName().contentEquals(accessor.getSimpleName())) {
                        if (!isSupportedRecordComponentType(component.asType())) {
                            addUnsupportedError(
                                    "record accessor for component type '" + component.asType()
                                            + "' in transpiled code (only scalar record fields are supported)",
                                    node);
                        }
                        return;
                    }
                }
                addUnsupportedError(
                        "record method call in transpiled code (only record component accessors are supported)",
                        node);
            }

            private boolean isRecordConstruction(NewClassTree node) {
                return constructedRecordType(node) != null;
            }

            private TypeElement constructedRecordType(NewClassTree node) {
                TreePath path = treePath(node);
                Element element = path == null ? null : parsedSources.trees().getElement(path);
                if (!(element instanceof ExecutableElement constructor)
                        || !(constructor.getEnclosingElement() instanceof TypeElement owner)
                        || owner.getKind() != ElementKind.RECORD) {
                    return null;
                }
                return owner;
            }

            private ExecutableElement resolvedExecutable(MethodInvocationTree node) {
                TreePath path = treePath(node);
                Element element = path == null ? null : parsedSources.trees().getElement(path);
                return element instanceof ExecutableElement executable ? executable : null;
            }

            /** True when the invocation resolves to a method declared on a {@code titan.dsl} type. */
            private boolean isDslDeclaredMethodInvocation(MethodInvocationTree node) {
                ExecutableElement method = resolvedExecutable(node);
                return method != null
                        && method.getEnclosingElement() instanceof TypeElement owner
                        && owner.getQualifiedName().toString().startsWith("titan.dsl.");
            }

            private boolean isSupportedRecordComponentType(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.ARRAY) {
                    return false;
                }
                if (type.getKind().isPrimitive()) {
                    return type.getKind() != TypeKind.VOID;
                }
                if (type.getKind() != TypeKind.DECLARED) {
                    return false;
                }
                Element element = parsedSources.types().asElement(type);
                if (!(element instanceof TypeElement typeElement)) {
                    return false;
                }
                String qualifiedName = typeElement.getQualifiedName().toString();
                return "java.lang.String".equals(qualifiedName)
                        || "java.lang.Boolean".equals(qualifiedName)
                        || "java.lang.Byte".equals(qualifiedName)
                        || "java.lang.Short".equals(qualifiedName)
                        || "java.lang.Integer".equals(qualifiedName)
                        || "java.lang.Long".equals(qualifiedName)
                        || "java.lang.Float".equals(qualifiedName)
                        || "java.lang.Double".equals(qualifiedName)
                        || "java.math.BigDecimal".equals(qualifiedName)
                        || "java.math.BigInteger".equals(qualifiedName)
                        || "java.time.LocalDate".equals(qualifiedName)
                        || "java.time.LocalTime".equals(qualifiedName)
                        || "java.time.LocalDateTime".equals(qualifiedName)
                        || "java.time.Instant".equals(qualifiedName)
                        || "java.time.ZonedDateTime".equals(qualifiedName);
            }

            private boolean isSupportedArrayElementType(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.ARRAY) {
                    return false;
                }
                if (isSupportedRecordComponentType(type)) {
                    return true;
                }
                if (type.getKind() != TypeKind.DECLARED) {
                    return false;
                }
                Element element = parsedSources.types().asElement(type);
                return element instanceof TypeElement typeElement
                        && typeElement.getKind() == ElementKind.RECORD
                        && recordComponentsAreArrayElementSafe(typeElement);
            }

            private boolean recordComponentsAreArrayElementSafe(TypeElement recordType) {
                for (Element component : recordType.getRecordComponents()) {
                    if (!isSupportedRecordComponentType(component.asType())) {
                        return false;
                    }
                }
                return true;
            }

            private boolean isStructuralIdentifierComponent(String componentName) {
                String normalized = componentName == null ? "" : componentName.toLowerCase(java.util.Locale.ROOT);
                return normalized.endsWith("tablename")
                        || normalized.endsWith("columnname")
                        || normalized.endsWith("schemaname")
                        || normalized.endsWith("identifier");
            }

            private boolean isCompileTimeIdentifierArgument(Tree argument) {
                return argument.getKind() == Tree.Kind.STRING_LITERAL
                        || sourceLocalStaticFinalStringConstant(argument) != null;
            }

            private String sourceLocalStaticFinalStringConstant(Tree argument) {
                TreePath argumentPath = treePath(argument);
                if (argumentPath == null) {
                    return null;
                }
                Element element = parsedSources.trees().getElement(argumentPath);
                if (!(element instanceof VariableElement variable)
                        || element.getKind() != ElementKind.FIELD
                        || !element.getModifiers().contains(Modifier.STATIC)
                        || !element.getModifiers().contains(Modifier.FINAL)
                        || !(variable.getConstantValue() instanceof String value)
                        || value.isBlank()) {
                    return null;
                }
                Tree declaration = parsedSources.trees().getTree(variable);
                if (!(declaration instanceof VariableTree variableTree)
                        || !(variableTree.getInitializer() instanceof LiteralTree literal)
                        || !(literal.getValue() instanceof String)) {
                    return null;
                }
                return value;
            }

            private boolean isCollectionInvocation(MethodInvocationTree node) {
                if (isCompileTimeSafeImmutableListFactory(node)) {
                    return false;
                }
                TreePath invocationPath = treePath(node);
                if (invocationPath != null
                        && parsedSources.trees().getElement(invocationPath) instanceof ExecutableElement method) {
                    TypeMirror ownerType = method.getEnclosingElement().asType();
                    return isCollectionLikeType(ownerType) || isCollectionLikeType(method.getReturnType());
                }
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)) {
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                return receiverType != null && isCollectionLikeType(receiverType);
            }

            private boolean isCompileTimeSafeImmutableListFactory(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)
                        || !"of".contentEquals(memberSelectTree.getIdentifier())) {
                    return false;
                }
                TreePath invocationPath = treePath(node);
                if (invocationPath != null
                        && parsedSources.trees().getElement(invocationPath) instanceof ExecutableElement method
                        && method.getEnclosingElement() instanceof TypeElement owner
                        && !"java.util.List".contentEquals(owner.getQualifiedName())) {
                    return false;
                }
                if (node.getArguments().isEmpty()) {
                    return true;
                }
                TypeMirror firstType = typeOf(node.getArguments().getFirst());
                if (!isSupportedArrayElementType(firstType)) {
                    return false;
                }
                for (int i = 1; i < node.getArguments().size(); i++) {
                    TypeMirror nextType = typeOf(node.getArguments().get(i));
                    if (nextType == null || !firstType.toString().equals(nextType.toString())) {
                        return false;
                    }
                }
                return true;
            }

            private boolean isStaticMethodInvocation(MethodInvocationTree node) {
                TreePath invocationPath = treePath(node);
                return invocationPath != null
                        && parsedSources.trees().getElement(invocationPath) instanceof ExecutableElement method
                        && method.getModifiers().contains(Modifier.STATIC);
            }

            private boolean isTriggerRowAccessorInvocation(MethodInvocationTree node) {
                if (entryPoint == null || entryPoint.kind() != EntryPointKind.TRIGGER) {
                    return false;
                }
                String called = extractCalledMethodName(node);
                if (!"get".equals(called) && !"set".equals(called)) {
                    return false;
                }
                if (!(node.getMethodSelect() instanceof MemberSelectTree memberSelectTree)
                        || !(memberSelectTree.getExpression() instanceof MethodInvocationTree receiverInvocation)) {
                    return false;
                }
                TreePath receiverPath = treePath(receiverInvocation);
                if (receiverPath == null
                        || !(parsedSources.trees().getElement(receiverPath) instanceof ExecutableElement receiverMethod)
                        || !(receiverMethod.getEnclosingElement() instanceof TypeElement owner)) {
                    return false;
                }
                String receiverName = receiverMethod.getSimpleName().toString();
                return "titan.dsl.DSL".contentEquals(owner.getQualifiedName())
                        && ("newRow".equals(receiverName) || "oldRow".equals(receiverName));
            }

            private boolean isGetClassInvocation(MethodInvocationTree node) {
                return node.getMethodSelect() instanceof MemberSelectTree memberSelectTree
                        && "getClass".contentEquals(memberSelectTree.getIdentifier());
            }

            private boolean isReflectiveClassLiteral(MemberSelectTree memberSelectTree) {
                if (!"class".contentEquals(memberSelectTree.getIdentifier())) {
                    return false;
                }
                TreePath path = treePath(memberSelectTree);
                return path != null
                        && path.getParentPath() != null
                        && path.getParentPath().getLeaf().getKind() == Tree.Kind.MEMBER_SELECT;
            }

            private boolean isArrayLengthAccess(MemberSelectTree memberSelectTree) {
                if (!"length".contentEquals(memberSelectTree.getIdentifier())) {
                    return false;
                }
                TreePath expressionPath = treePath(memberSelectTree.getExpression());
                if (expressionPath == null) {
                    return false;
                }
                TypeMirror expressionType = parsedSources.trees().getTypeMirror(expressionPath);
                return expressionType != null && expressionType.getKind() == TypeKind.ARRAY;
            }

            private boolean isInstanceFieldAccess(MemberSelectTree memberSelectTree) {
                TreePath memberPath = treePath(memberSelectTree);
                if (memberPath == null) {
                    return false;
                }
                Element field = parsedSources.trees().getElement(memberPath);
                if (field == null
                        || field.getKind() != ElementKind.FIELD
                        || field.getModifiers().contains(Modifier.STATIC)
                        || isDslStructuralField(field.asType())) {
                    return false;
                }
                TreePath receiverPath = treePath(memberSelectTree.getExpression());
                if (receiverPath == null) {
                    return false;
                }
                TypeMirror receiverType = parsedSources.trees().getTypeMirror(receiverPath);
                return isStructuredValueReceiverType(receiverType);
            }

            private boolean isInterfaceType(TypeMirror type) {
                if (type.getKind() == TypeKind.DECLARED) {
                    Element receiverElement = parsedSources.types().asElement(type);
                    return receiverElement instanceof TypeElement typeElement
                            && typeElement.getKind() == ElementKind.INTERFACE;
                }
                if (type.getKind() == TypeKind.TYPEVAR && type instanceof TypeVariable typeVariable) {
                    TypeMirror upperBound = typeVariable.getUpperBound();
                    return upperBound != null && isInterfaceType(upperBound);
                }
                return false;
            }

            private boolean isCollectionLikeType(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR || !(type instanceof DeclaredType declaredType)) {
                    return false;
                }
                TypeElement collectionElement = parsedSources.elements().getTypeElement("java.util.Collection");
                TypeElement mapElement = parsedSources.elements().getTypeElement("java.util.Map");
                TypeMirror erasedType = parsedSources.types().erasure(declaredType);
                return isAssignableTo(erasedType, collectionElement) || isAssignableTo(erasedType, mapElement);
            }

            private boolean isAssignableTo(TypeMirror erasedType, TypeElement targetElement) {
                return targetElement != null
                        && parsedSources.types().isAssignable(
                                erasedType,
                                parsedSources.types().erasure(targetElement.asType()));
            }

            private boolean isUnsupportedInstanceReceiverType(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR) {
                    return false;
                }
                if (type.getKind() == TypeKind.TYPEVAR && type instanceof TypeVariable typeVariable) {
                    return isUnsupportedInstanceReceiverType(typeVariable.getUpperBound());
                }
                if (type.getKind() != TypeKind.DECLARED || isSupportedP0InstanceReceiver(type)) {
                    return false;
                }
                Element receiverElement = parsedSources.types().asElement(type);
                return receiverElement instanceof TypeElement typeElement
                        && typeElement.getKind() == ElementKind.CLASS;
            }

            private boolean isStructuredValueReceiverType(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR) {
                    return false;
                }
                if (type.getKind() == TypeKind.TYPEVAR && type instanceof TypeVariable typeVariable) {
                    return isStructuredValueReceiverType(typeVariable.getUpperBound());
                }
                if (type.getKind() != TypeKind.DECLARED || isSupportedP0InstanceReceiver(type)) {
                    return false;
                }
                Element receiverElement = parsedSources.types().asElement(type);
                return receiverElement instanceof TypeElement typeElement
                        && (typeElement.getKind() == ElementKind.CLASS || typeElement.getKind() == ElementKind.RECORD);
            }

            private boolean isDslStructuralField(TypeMirror type) {
                if (type == null || type.getKind() == TypeKind.ERROR || !(type instanceof DeclaredType declaredType)) {
                    return false;
                }
                Element fieldTypeElement = parsedSources.types().asElement(declaredType);
                return fieldTypeElement instanceof TypeElement typeElement
                        && typeElement.getQualifiedName().toString().startsWith("titan.dsl.");
            }

            private boolean isSupportedP0InstanceReceiver(TypeMirror type) {
                Element receiverElement = parsedSources.types().asElement(type);
                if (!(receiverElement instanceof TypeElement typeElement)) {
                    return false;
                }
                String qualifiedName = typeElement.getQualifiedName().toString();
                return "java.lang.String".equals(qualifiedName)
                        || "java.lang.StringBuilder".equals(qualifiedName)
                        || "java.lang.StringBuffer".equals(qualifiedName)
                        || "java.math.BigDecimal".equals(qualifiedName)
                        || "java.math.BigInteger".equals(qualifiedName)
                        || "java.time.LocalDate".equals(qualifiedName)
                        || "java.time.LocalTime".equals(qualifiedName)
                        || "java.time.LocalDateTime".equals(qualifiedName)
                        || "java.time.Instant".equals(qualifiedName)
                        || "java.time.ZonedDateTime".equals(qualifiedName)
                        || "java.time.Duration".equals(qualifiedName)
                        || "java.time.Period".equals(qualifiedName)
                        || "java.util.Optional".equals(qualifiedName)
                        || "java.io.PrintStream".equals(qualifiedName)
                        || qualifiedName.startsWith("titan.dsl.")
                        || typeElement.getKind() == ElementKind.ENUM;
            }

            private boolean isSupportedArrayConstruction(NewArrayTree node) {
                TypeMirror arrayType = typeOf(node);
                if (arrayType instanceof javax.lang.model.type.ArrayType resolvedArrayType) {
                    return isSupportedArrayElementType(resolvedArrayType.getComponentType());
                }
                if (node.getType() != null) {
                    TypeMirror declaredElementType = typeOf(node.getType());
                    return isSupportedArrayElementType(declaredElementType);
                }
                return false;
            }

            private ReferenceGraph compilerKnownReferenceGraph(VariableTree node) {
                if (!isArrayExpression(node) || node.getInitializer() == null) {
                    return null;
                }
                String variableName = node.getName().toString();
                VariableElement variableElement = variableElement(node);
                if (variableElement == null) {
                    return null;
                }
                Tree initializer = stripParentheses(node.getInitializer());
                if (!(initializer instanceof NewArrayTree newArrayTree)
                        || newArrayTree.getInitializers() == null
                        || newArrayTree.getInitializers().isEmpty()) {
                    return null;
                }
                TypeMirror arrayType = typeOf(node);
                if (!(arrayType instanceof javax.lang.model.type.ArrayType resolvedArrayType)
                        || !(parsedSources.types().asElement(resolvedArrayType.getComponentType()) instanceof TypeElement recordType)
                        || recordType.getKind() != ElementKind.RECORD) {
                    return null;
                }
                int traversalBound = referenceTraversalLoopBoundForArray(method.getBody(), variableElement, recordType);
                if (traversalBound <= 0) {
                    return null;
                }
                int nameIndex = recordComponentIndex(recordType, "name");
                int nextNameIndex = recordComponentIndex(recordType, "nextName");
                if (nameIndex < 0 || nextNameIndex < 0) {
                    return null;
                }

                Map<String, String> edges = new LinkedHashMap<>();
                for (Tree initializerEntry : newArrayTree.getInitializers()) {
                    Tree strippedEntry = stripParentheses(initializerEntry);
                    if (!(strippedEntry instanceof NewClassTree descriptor)
                            || descriptor.getArguments().size() <= Math.max(nameIndex, nextNameIndex)) {
                        return new ReferenceGraph(
                                variableName,
                                edges,
                                "bounded reference graph descriptor array '" + variableName
                                        + "' must contain only compiler-known descriptor records",
                                traversalBound);
                    }
                    TypeElement descriptorType = constructedRecordType(descriptor);
                    if (!recordType.equals(descriptorType)) {
                        return null;
                    }
                    String name = compilerKnownString(descriptor.getArguments().get(nameIndex));
                    String nextName = compilerKnownString(descriptor.getArguments().get(nextNameIndex));
                    if (name == null || name.isBlank() || nextName == null) {
                        return new ReferenceGraph(
                                variableName,
                                edges,
                                "bounded reference graph descriptor array '" + variableName
                                        + "' requires compiler-known non-blank names and compiler-known next references",
                                traversalBound);
                    }
                    if (edges.putIfAbsent(name, nextName) != null) {
                        return new ReferenceGraph(
                                variableName,
                                edges,
                                "bounded reference graph descriptor array '" + variableName
                                        + "' contains duplicate descriptor name '" + name + "'",
                                traversalBound);
                    }
                }
                return new ReferenceGraph(variableName, edges, null, traversalBound);
            }

            private VariableElement variableElement(VariableTree node) {
                TreePath path = treePath(node);
                Element element = path == null ? null : parsedSources.trees().getElement(path);
                return element instanceof VariableElement variable ? variable : null;
            }

            private VariableElement variableElement(Tree tree) {
                TreePath path = treePath(stripParentheses(tree));
                Element element = path == null ? null : parsedSources.trees().getElement(path);
                return element instanceof VariableElement variable ? variable : null;
            }

            private void validateReferenceGraph(ReferenceGraph referenceGraph, Tree diagnosticTree) {
                if (referenceGraph.invalidReason() != null) {
                    addUnsupportedError(referenceGraph.invalidReason(), diagnosticTree);
                    return;
                }
                List<String> unknownPath = findUnknownReferencePath(referenceGraph.edges());
                if (!unknownPath.isEmpty()) {
                    addUnsupportedError(
                            "bounded reference graph descriptor array '" + referenceGraph.variableName()
                                    + "' references unknown descriptor path " + renderPath(unknownPath),
                            diagnosticTree);
                    return;
                }
                List<String> cycle = findReferenceCycle(referenceGraph.edges());
                if (!cycle.isEmpty()) {
                    addUnsupportedError(
                            "bounded reference graph cycle in compiler-known descriptor array '"
                                    + referenceGraph.variableName() + "' (cycle " + renderPath(cycle)
                                    + "; use acyclic compiler-known descriptor traversal or reject cycles before Titan lowering)",
                            diagnosticTree);
                    return;
                }
                int traversalBound = referenceGraph.traversalBound();
                if (traversalBound <= 0) {
                    return;
                }
                List<String> longestPath = longestReferencePath(referenceGraph.edges());
                if (longestPath.size() > traversalBound) {
                    addUnsupportedError(
                            "bounded reference graph depth exceeds traversal bound in compiler-known descriptor array '"
                                    + referenceGraph.variableName() + "' (descriptor path " + renderPath(longestPath)
                                    + " requires " + longestPath.size() + " node visit(s), but loop bound is "
                                    + traversalBound
                                    + "; increase the compiler-known traversal bound or reject over-depth references before Titan lowering)",
                            diagnosticTree);
                }
            }

            private int recordComponentIndex(TypeElement recordType, String componentName) {
                List<? extends Element> components = recordType.getRecordComponents();
                for (int i = 0; i < components.size(); i++) {
                    if (componentName.contentEquals(components.get(i).getSimpleName())) {
                        return i;
                    }
                }
                return -1;
            }

            private String compilerKnownString(Tree tree) {
                Tree stripped = stripParentheses(tree);
                if (stripped instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                    return value;
                }
                return sourceLocalStaticFinalStringConstantAllowBlank(stripped);
            }

            private String sourceLocalStaticFinalStringConstantAllowBlank(Tree argument) {
                TreePath argumentPath = treePath(argument);
                if (argumentPath == null) {
                    return null;
                }
                Element element = parsedSources.trees().getElement(argumentPath);
                if (!(element instanceof VariableElement variable)
                        || element.getKind() != ElementKind.FIELD
                        || !element.getModifiers().contains(Modifier.STATIC)
                        || !element.getModifiers().contains(Modifier.FINAL)
                        || !(variable.getConstantValue() instanceof String value)) {
                    return null;
                }
                Tree declaration = parsedSources.trees().getTree(variable);
                if (!(declaration instanceof VariableTree variableTree)
                        || !(variableTree.getInitializer() instanceof LiteralTree literal)
                        || !(literal.getValue() instanceof String)) {
                    return null;
                }
                return value;
            }

            private List<String> findUnknownReferencePath(Map<String, String> edges) {
                for (String start : edges.keySet()) {
                    List<String> path = new ArrayList<>();
                    String current = start;
                    Set<String> seen = new HashSet<>();
                    while (current != null && !current.isBlank() && seen.add(current)) {
                        path.add(current);
                        String next = edges.get(current);
                        if (next != null && !next.isBlank() && !edges.containsKey(next)) {
                            path.add(next);
                            return path;
                        }
                        current = next;
                    }
                }
                return List.of();
            }

            private List<String> findReferenceCycle(Map<String, String> edges) {
                for (String start : edges.keySet()) {
                    Deque<String> path = new ArrayDeque<>();
                    Set<String> seen = new HashSet<>();
                    String current = start;
                    while (current != null && !current.isBlank()) {
                        if (seen.contains(current)) {
                            List<String> cycle = new ArrayList<>();
                            boolean collecting = false;
                            for (String node : path) {
                                if (node.equals(current)) {
                                    collecting = true;
                                }
                                if (collecting) {
                                    cycle.add(node);
                                }
                            }
                            cycle.add(current);
                            return cycle;
                        }
                        seen.add(current);
                        path.addLast(current);
                        current = edges.get(current);
                    }
                }
                return List.of();
            }

            private List<String> longestReferencePath(Map<String, String> edges) {
                List<String> longest = List.of();
                for (String start : edges.keySet()) {
                    List<String> path = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    String current = start;
                    while (current != null && !current.isBlank() && seen.add(current)) {
                        path.add(current);
                        current = edges.get(current);
                    }
                    if (path.size() > longest.size()) {
                        longest = List.copyOf(path);
                    }
                }
                return longest;
            }

            private int referenceTraversalLoopBoundForArray(
                    BlockTree body,
                    VariableElement arrayVariable,
                    TypeElement recordType
            ) {
                final int[] smallest = {Integer.MAX_VALUE};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitForLoop(ForLoopTree node, Void unused) {
                        int bound = referenceTraversalLoopBound(node, arrayVariable, recordType);
                        if (bound > 0) {
                            smallest[0] = Math.min(smallest[0], bound);
                        }
                        return super.visitForLoop(node, unused);
                    }

                    @Override
                    public Void visitWhileLoop(WhileLoopTree node, Void unused) {
                        int bound = referenceTraversalLoopBound(node, arrayVariable, recordType);
                        if (bound > 0) {
                            smallest[0] = Math.min(smallest[0], bound);
                        }
                        return super.visitWhileLoop(node, unused);
                    }
                }.scan(body, null);
                return smallest[0] == Integer.MAX_VALUE ? -1 : smallest[0];
            }

            private int referenceTraversalLoopBound(ForLoopTree node, VariableElement arrayVariable, TypeElement recordType) {
                if (!usesReferenceGraphTraversal(node.getStatement(), arrayVariable, recordType)) {
                    return -1;
                }
                Tree condition = stripParentheses(node.getCondition());
                String cursorName = condition instanceof BinaryTree binaryTree
                                && binaryTree.getKind() == Tree.Kind.LESS_THAN
                                && stripParentheses(binaryTree.getLeftOperand()) instanceof IdentifierTree cursor
                        ? cursor.getName().toString()
                        : null;
                if (cursorName == null) {
                    return -1;
                }
                Integer cursorStart = null;
                for (StatementTree initializer : node.getInitializer()) {
                    if (initializer instanceof VariableTree variableTree
                            && cursorName.equals(variableTree.getName().toString())
                            && compilerKnownNonNegativeInteger(variableTree.getInitializer()) != null) {
                        cursorStart = compilerKnownNonNegativeInteger(variableTree.getInitializer());
                        break;
                    }
                }
                int updateIncrements = countCursorIncrements(node.getUpdate(), cursorName);
                int bodyIncrements = countCursorIncrements(node.getStatement(), cursorName);
                if (cursorStart == null || updateIncrements + bodyIncrements != 1) {
                    return -1;
                }
                Integer bound = condition instanceof BinaryTree binaryTree
                        ? compilerKnownInteger(binaryTree.getRightOperand())
                        : null;
                if (!(condition instanceof BinaryTree binaryTree)
                        || binaryTree.getKind() != Tree.Kind.LESS_THAN
                        || !isIdentifier(binaryTree.getLeftOperand(), cursorName)
                        || bound == null
                        || bound <= 0) {
                    return -1;
                }
                return Math.max(0, bound - cursorStart);
            }

            private int referenceTraversalLoopBound(WhileLoopTree node, VariableElement arrayVariable, TypeElement recordType) {
                if (!usesReferenceGraphTraversal(node.getStatement(), arrayVariable, recordType)) {
                    return -1;
                }
                Tree condition = stripParentheses(node.getCondition());
                Integer bound = condition instanceof BinaryTree binaryTree
                        ? compilerKnownInteger(binaryTree.getRightOperand())
                        : null;
                if (!(condition instanceof BinaryTree binaryTree)
                        || binaryTree.getKind() != Tree.Kind.LESS_THAN
                        || !(stripParentheses(binaryTree.getLeftOperand()) instanceof IdentifierTree cursor)
                        || bound == null
                        || bound <= 0
                        || countCursorIncrements(node.getStatement(), cursor.getName().toString()) != 1) {
                    return -1;
                }
                Integer cursorStart = compilerKnownCursorStateAtLoop(node, cursor.getName().toString());
                if (cursorStart == null) {
                    return -1;
                }
                return Math.max(0, bound - cursorStart);
            }

            private Integer compilerKnownInteger(Tree tree) {
                Tree stripped = stripParentheses(tree);
                if (stripped instanceof LiteralTree literal && literal.getValue() instanceof Integer value) {
                    return value;
                }
                TreePath argumentPath = treePath(stripped);
                if (argumentPath == null) {
                    return null;
                }
                Element element = parsedSources.trees().getElement(argumentPath);
                if (!(element instanceof VariableElement variable)
                        || element.getKind() != ElementKind.FIELD
                        || !element.getModifiers().contains(Modifier.STATIC)
                        || !element.getModifiers().contains(Modifier.FINAL)
                        || !(variable.getConstantValue() instanceof Integer value)) {
                    return null;
                }
                Tree declaration = parsedSources.trees().getTree(variable);
                if (!(declaration instanceof VariableTree variableTree)
                        || !(variableTree.getInitializer() instanceof LiteralTree literal)
                        || !(literal.getValue() instanceof Integer)) {
                    return null;
                }
                return value;
            }

            private Integer compilerKnownNonNegativeInteger(Tree tree) {
                Integer value = compilerKnownInteger(tree);
                return value != null && value >= 0 ? value : null;
            }

            private Integer compilerKnownCursorStateAtLoop(WhileLoopTree node, String cursorName) {
                TreePath loopPath = treePath(node);
                if (loopPath == null
                        || loopPath.getParentPath() == null
                        || !(loopPath.getParentPath().getLeaf() instanceof BlockTree blockTree)) {
                    return null;
                }
                Integer current = null;
                for (StatementTree statement : blockTree.getStatements()) {
                    if (statement == node) {
                        return current != null && current >= 0 ? current : null;
                    }
                    if (statement instanceof VariableTree variableTree
                            && cursorName.equals(variableTree.getName().toString())) {
                        current = compilerKnownNonNegativeInteger(variableTree.getInitializer());
                    } else if (countCursorWrites(statement, cursorName) > 0) {
                        current = statement instanceof ExpressionStatementTree expressionStatement
                                ? cursorAssignmentCompilerKnownNonNegativeInteger(
                                        expressionStatement.getExpression(),
                                        cursorName)
                                : null;
                    }
                }
                return null;
            }

            private Integer cursorAssignmentCompilerKnownNonNegativeInteger(Tree tree, String cursorName) {
                Tree stripped = stripParentheses(tree);
                if (stripped instanceof AssignmentTree assignment
                        && isIdentifier(assignment.getVariable(), cursorName)) {
                    return compilerKnownNonNegativeInteger(assignment.getExpression());
                }
                return null;
            }

            private boolean usesReferenceGraphTraversal(
                    StatementTree statement,
                    VariableElement arrayVariable,
                    TypeElement recordType
            ) {
                Map<VariableElement, Set<VariableElement>> lookupCursors = new LinkedHashMap<>();
                Map<VariableElement, Set<VariableElement>> nextReferenceCursors = new LinkedHashMap<>();
                return usesReferenceGraphTraversal(statement, arrayVariable, recordType, lookupCursors, nextReferenceCursors);
            }

            private boolean usesReferenceGraphTraversal(
                    StatementTree statement,
                    VariableElement arrayVariable,
                    TypeElement recordType,
                    Map<VariableElement, Set<VariableElement>> lookupCursors,
                    Map<VariableElement, Set<VariableElement>> nextReferenceCursors
            ) {
                if (statement instanceof BlockTree blockTree) {
                    Map<VariableElement, Set<VariableElement>> scopedLookups = copyCursorMap(lookupCursors);
                    Map<VariableElement, Set<VariableElement>> scopedNextReferences = copyCursorMap(nextReferenceCursors);
                    for (StatementTree nested : blockTree.getStatements()) {
                        if (usesReferenceGraphTraversal(
                                nested,
                                arrayVariable,
                                recordType,
                                scopedLookups,
                                scopedNextReferences)) {
                            return true;
                        }
                    }
                    lookupCursors.clear();
                    lookupCursors.putAll(scopedLookups);
                    nextReferenceCursors.clear();
                    nextReferenceCursors.putAll(scopedNextReferences);
                    return false;
                }
                if (statement instanceof IfTree ifTree) {
                    Map<VariableElement, Set<VariableElement>> thenLookups = copyCursorMap(lookupCursors);
                    Map<VariableElement, Set<VariableElement>> thenNextReferences = copyCursorMap(nextReferenceCursors);
                    if (usesReferenceGraphTraversal(
                            ifTree.getThenStatement(),
                            arrayVariable,
                            recordType,
                            thenLookups,
                            thenNextReferences)) {
                        return true;
                    }
                    Map<VariableElement, Set<VariableElement>> elseLookups = copyCursorMap(lookupCursors);
                    Map<VariableElement, Set<VariableElement>> elseNextReferences = copyCursorMap(nextReferenceCursors);
                    if (ifTree.getElseStatement() != null
                            && usesReferenceGraphTraversal(
                            ifTree.getElseStatement(),
                            arrayVariable,
                            recordType,
                            elseLookups,
                            elseNextReferences)) {
                        return true;
                    }
                    mergePossibleBranchState(lookupCursors, thenLookups, elseLookups);
                    mergePossibleBranchState(nextReferenceCursors, thenNextReferences, elseNextReferences);
                    return false;
                }
                if (statement instanceof VariableTree variableTree) {
                    updateReferenceGraphLookupVariable(
                            variableElement(variableTree),
                            typeOf(variableTree),
                            variableTree.getInitializer(),
                            arrayVariable,
                            recordType,
                            lookupCursors,
                            nextReferenceCursors);
                    return updateReferenceGraphNextReferenceVariable(
                            variableElement(variableTree),
                            typeOf(variableTree),
                            variableTree.getInitializer(),
                            lookupCursors,
                            nextReferenceCursors,
                            recordType);
                }
                if (statement instanceof ExpressionStatementTree expressionStatement) {
                    Tree expression = stripParentheses(expressionStatement.getExpression());
                    if (expression instanceof AssignmentTree assignment
                            && stripParentheses(assignment.getVariable()) instanceof IdentifierTree) {
                        updateReferenceGraphLookupVariable(
                                variableElement(assignment.getVariable()),
                                typeOf(assignment.getVariable()),
                                assignment.getExpression(),
                                arrayVariable,
                                recordType,
                                lookupCursors,
                                nextReferenceCursors);
                        if (isReferenceGraphCursorAdvance(
                                assignment.getVariable(),
                                assignment.getExpression(),
                                lookupCursors,
                                nextReferenceCursors,
                                recordType)) {
                            return true;
                        }
                        updateReferenceGraphNextReferenceVariable(
                                variableElement(assignment.getVariable()),
                                typeOf(assignment.getVariable()),
                                assignment.getExpression(),
                                lookupCursors,
                                nextReferenceCursors,
                                recordType);
                    }
                }
                final boolean[] nestedMatch = {false};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitBlock(BlockTree node, Void unused) {
                        if (usesReferenceGraphTraversal(
                                node,
                                arrayVariable,
                                recordType,
                                copyCursorMap(lookupCursors),
                                copyCursorMap(nextReferenceCursors))) {
                            nestedMatch[0] = true;
                        }
                        return null;
                    }
                }.scan(statement, null);
                return nestedMatch[0];
            }

            private void mergePossibleBranchState(
                    Map<VariableElement, Set<VariableElement>> target,
                    Map<VariableElement, Set<VariableElement>> thenState,
                    Map<VariableElement, Set<VariableElement>> elseState
            ) {
                target.clear();
                mergeCursorMap(target, thenState);
                mergeCursorMap(target, elseState);
            }

            private Map<VariableElement, Set<VariableElement>> copyCursorMap(
                    Map<VariableElement, Set<VariableElement>> source
            ) {
                Map<VariableElement, Set<VariableElement>> copy = new LinkedHashMap<>();
                mergeCursorMap(copy, source);
                return copy;
            }

            private void mergeCursorMap(
                    Map<VariableElement, Set<VariableElement>> target,
                    Map<VariableElement, Set<VariableElement>> source
            ) {
                for (Map.Entry<VariableElement, Set<VariableElement>> entry : source.entrySet()) {
                    target.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).addAll(entry.getValue());
                }
            }

            private void updateReferenceGraphLookupVariable(
                    VariableElement local,
                    TypeMirror localType,
                    Tree expression,
                    VariableElement arrayVariable,
                    TypeElement recordType,
                    Map<VariableElement, Set<VariableElement>> lookupCursors,
                    Map<VariableElement, Set<VariableElement>> nextReferenceCursors
            ) {
                if (local == null) {
                    return;
                }
                if (!sameErasedType(localType, recordType.asType())) {
                    return;
                }
                ReferenceGraphLookup lookup = referenceGraphLookup(expression, arrayVariable, recordType);
                if (lookup != null) {
                    lookupCursors.put(local, new HashSet<>(Set.of(lookup.cursor())));
                } else {
                    lookupCursors.remove(local);
                }
                nextReferenceCursors.remove(local);
            }

            private boolean isReferenceGraphLookup(Tree tree, VariableElement arrayVariable, TypeElement recordType) {
                return referenceGraphLookup(tree, arrayVariable, recordType) != null;
            }

            private ReferenceGraphLookup referenceGraphLookup(Tree tree, VariableElement arrayVariable, TypeElement recordType) {
                Tree stripped = stripParentheses(tree);
                if (!(stripped instanceof MethodInvocationTree invocation
                        && !invocation.getArguments().isEmpty()
                        && referencesVariable(invocation.getArguments().getFirst(), arrayVariable)
                        && sameErasedType(typeOf(invocation), recordType.asType()))) {
                    return null;
                }
                if (invocation.getArguments().size() < 2) {
                    return null;
                }
                VariableElement cursor = variableElement(invocation.getArguments().get(1));
                return cursor == null ? null : new ReferenceGraphLookup(cursor);
            }

            private boolean updateReferenceGraphNextReferenceVariable(
                    VariableElement local,
                    TypeMirror localType,
                    Tree expression,
                    Map<VariableElement, Set<VariableElement>> lookupCursors,
                    Map<VariableElement, Set<VariableElement>> nextReferenceCursors,
                    TypeElement recordType
            ) {
                if (local == null || !isStringType(localType)) {
                    return false;
                }
                Set<VariableElement> cursors = referenceGraphNextCursors(expression, lookupCursors, recordType);
                if (!cursors.isEmpty()) {
                    nextReferenceCursors.put(local, cursors);
                    return false;
                }
                nextReferenceCursors.remove(local);
                return referencesNextReferenceCursor(expression, local, nextReferenceCursors);
            }

            private boolean isReferenceGraphCursorAdvance(
                    Tree target,
                    Tree expression,
                    Map<VariableElement, Set<VariableElement>> lookupCursors,
                    Map<VariableElement, Set<VariableElement>> nextReferenceCursors,
                    TypeElement recordType
            ) {
                if (!isStringExpression(target)) {
                    return false;
                }
                VariableElement targetVariable = variableElement(target);
                Set<VariableElement> cursors = referenceGraphNextCursors(expression, lookupCursors, recordType);
                if (!cursors.isEmpty()) {
                    return cursors.contains(targetVariable);
                }
                return referencesNextReferenceCursor(expression, targetVariable, nextReferenceCursors);
            }

            private Set<VariableElement> referenceGraphNextCursors(
                    Tree tree,
                    Map<VariableElement, Set<VariableElement>> lookupCursors,
                    TypeElement recordType
            ) {
                Tree stripped = stripParentheses(tree);
                if (!(stripped instanceof MethodInvocationTree invocation
                        && invocation.getMethodSelect() instanceof MemberSelectTree select
                        && "nextName".contentEquals(select.getIdentifier())
                        && sameErasedType(typeOf(select.getExpression()), recordType.asType()))) {
                    return Set.of();
                }
                VariableElement receiver = variableElement(select.getExpression());
                if (receiver == null || !lookupCursors.containsKey(receiver)) {
                    return Set.of();
                }
                return new HashSet<>(lookupCursors.get(receiver));
            }

            private boolean referencesNextReferenceCursor(
                    Tree expression,
                    VariableElement target,
                    Map<VariableElement, Set<VariableElement>> nextReferenceCursors
            ) {
                VariableElement source = variableElement(expression);
                Set<VariableElement> cursors = source == null ? Set.of() : nextReferenceCursors.getOrDefault(source, Set.of());
                return target != null && cursors.contains(target);
            }

            private boolean referencesVariable(Tree tree, VariableElement variable) {
                TreePath path = treePath(stripParentheses(tree));
                Element element = path == null ? null : parsedSources.trees().getElement(path);
                return variable.equals(element);
            }

            private boolean isStringExpression(Tree tree) {
                TypeMirror type = typeOf(tree);
                return type != null && "java.lang.String".equals(type.toString());
            }

            private boolean isStringType(TypeMirror type) {
                return type != null && "java.lang.String".equals(type.toString());
            }

            private boolean isIdentifier(Tree tree, Set<String> names) {
                return stripParentheses(tree) instanceof IdentifierTree identifierTree
                        && names.contains(identifierTree.getName().toString());
            }

            private String renderPath(List<String> path) {
                return String.join(" -> ", path);
            }

            private boolean isArrayExpression(Tree tree) {
                TypeMirror type = typeOf(tree);
                return type != null && type.getKind() == TypeKind.ARRAY;
            }

            private boolean isArrayValuedInitializer(Tree tree) {
                Tree stripped = stripParenthesesAndCasts(tree);
                return isArrayExpression(stripped);
            }

            private TypeMirror typeOf(Tree tree) {
                TreePath path = treePath(tree);
                return path == null ? null : parsedSources.trees().getTypeMirror(path);
            }

            private CursorScan cursorBoundedStringScan(WhileLoopTree node) {
                return cursorBoundedStringScan(node.getCondition(), node.getStatement());
            }

            private CursorScan cursorBoundedStringScan(ForLoopTree node) {
                return cursorBoundedStringScan(node.getCondition(), node.getStatement());
            }

            private CursorScan cursorBoundedStringScan(Tree conditionTree, StatementTree body) {
                Tree condition = stripParentheses(conditionTree);
                if (!(condition instanceof BinaryTree binaryTree)
                        || binaryTree.getKind() != Tree.Kind.LESS_THAN) {
                    return null;
                }
                Tree left = stripParentheses(binaryTree.getLeftOperand());
                Tree right = stripParentheses(binaryTree.getRightOperand());
                ReceiverKey lengthReceiver = stringLengthReceiver(right);
                if (!(left instanceof IdentifierTree cursorIdentifier)
                        || lengthReceiver == null) {
                    return null;
                }
                String cursorName = cursorIdentifier.getName().toString();
                return usesStringCharAtCursor(body, cursorName)
                        ? new CursorScan(cursorName, lengthReceiver)
                        : null;
            }

            private ReceiverKey stringLengthReceiver(Tree tree) {
                if (!(tree instanceof MethodInvocationTree invocation)
                        || !(invocation.getMethodSelect() instanceof MemberSelectTree select)
                        || !"length".contentEquals(select.getIdentifier())) {
                    return null;
                }
                TreePath receiverPath = treePath(select.getExpression());
                TypeMirror receiverType = receiverPath == null ? null : parsedSources.trees().getTypeMirror(receiverPath);
                return receiverType != null && "java.lang.String".equals(receiverType.toString())
                        ? receiverKey(select.getExpression())
                        : null;
            }

            private void validateCursorScanBody(
                    StatementTree body,
                    CursorScan cursorScan,
                    int updateIncrements,
                    int updateWrites,
                    Tree diagnosticTree
            ) {
                int topLevelBodyIncrements = countTopLevelCursorIncrements(body, cursorScan.cursorName());
                int bodyIncrements = countCursorIncrements(body, cursorScan.cursorName());
                int bodyWrites = countCursorWrites(body, cursorScan.cursorName());
                int totalIncrements = bodyIncrements + updateIncrements;
                int totalWrites = bodyWrites + updateWrites;
                if (totalIncrements != 1
                        || totalWrites != 1
                        || (updateIncrements == 0 && topLevelBodyIncrements != 1)
                        || updateIncrements > 1) {
                    addUnsupportedError(
                            "nested traversal scanner without deterministic cursor increment (use scalar index state advanced exactly once per loop)",
                            diagnosticTree);
                }
                if (!allStringCharAtCursorReceiversMatch(body, cursorScan)) {
                    addUnsupportedError(
                            "nested traversal scanner with mismatched string bounds (charAt cursor receiver must match length receiver)",
                            diagnosticTree);
                }
            }

            private boolean usesStringCharAtCursor(StatementTree statement, String cursorName) {
                final boolean[] found = {false};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        if (isCharAtCursorInvocation(node, cursorName)) {
                            found[0] = true;
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(statement, null);
                return found[0];
            }

            private boolean hasNonNegativeCursorStateAtLoop(WhileLoopTree node, String cursorName) {
                TreePath loopPath = treePath(node);
                if (loopPath == null
                        || loopPath.getParentPath() == null
                        || !(loopPath.getParentPath().getLeaf() instanceof BlockTree blockTree)) {
                    return false;
                }
                boolean nonNegative = false;
                for (StatementTree statement : blockTree.getStatements()) {
                    if (statement == node) {
                        return nonNegative;
                    }
                    if (statement instanceof VariableTree variableTree
                            && cursorName.equals(variableTree.getName().toString())) {
                        nonNegative = isNonNegativeIntegerLiteral(variableTree.getInitializer());
                    } else if (countCursorWrites(statement, cursorName) > 0) {
                        nonNegative = statement instanceof ExpressionStatementTree expressionStatement
                                && isCursorAssignmentToNonNegativeInteger(
                                        expressionStatement.getExpression(),
                                        cursorName);
                    }
                }
                return false;
            }

            private boolean hasNonNegativeForInitializer(ForLoopTree node, String cursorName) {
                for (StatementTree initializer : node.getInitializer()) {
                    if (initializer instanceof VariableTree variableTree
                            && cursorName.equals(variableTree.getName().toString())
                            && isNonNegativeIntegerLiteral(variableTree.getInitializer())) {
                        return true;
                    }
                    if (initializer instanceof ExpressionStatementTree expressionStatement
                            && isCursorAssignmentToNonNegativeInteger(expressionStatement.getExpression(), cursorName)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean allStringCharAtCursorReceiversMatch(StatementTree statement, CursorScan cursorScan) {
                final boolean[] allMatch = {true};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        if (isCharAtCursorInvocation(node, cursorScan.cursorName())
                                && !cursorScan.receiverKey().equals(charAtReceiver(node))) {
                            allMatch[0] = false;
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(statement, null);
                return allMatch[0];
            }

            private boolean isCharAtCursorInvocation(MethodInvocationTree node, String cursorName) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree select)
                        || !"charAt".contentEquals(select.getIdentifier())
                        || node.getArguments().size() != 1
                        || !(stripParentheses(node.getArguments().getFirst()) instanceof IdentifierTree argument)
                        || !cursorName.equals(argument.getName().toString())) {
                    return false;
                }
                TreePath receiverPath = treePath(select.getExpression());
                TypeMirror receiverType = receiverPath == null ? null : parsedSources.trees().getTypeMirror(receiverPath);
                return receiverType != null && "java.lang.String".equals(receiverType.toString());
            }

            private ReceiverKey charAtReceiver(MethodInvocationTree node) {
                if (!(node.getMethodSelect() instanceof MemberSelectTree select)) {
                    return null;
                }
                return receiverKey(select.getExpression());
            }

            private ReceiverKey receiverKey(Tree receiver) {
                TreePath receiverPath = treePath(receiver);
                Element element = receiverPath == null ? null : parsedSources.trees().getElement(receiverPath);
                if (element != null) {
                    return new ReceiverKey("element:" + element);
                }
                TypeMirror type = receiverPath == null ? null : parsedSources.trees().getTypeMirror(receiverPath);
                return new ReceiverKey("tree:" + receiver + ":" + (type == null ? "" : type.toString()));
            }

            private int countTopLevelCursorIncrements(StatementTree statement, String cursorName) {
                if (statement instanceof BlockTree blockTree) {
                    int count = 0;
                    for (StatementTree nested : blockTree.getStatements()) {
                        if (nested instanceof ExpressionStatementTree expressionStatement
                                && isCursorIncrement(expressionStatement.getExpression(), cursorName)) {
                            count++;
                        }
                    }
                    return count;
                }
                return statement instanceof ExpressionStatementTree expressionStatement
                        && isCursorIncrement(expressionStatement.getExpression(), cursorName)
                        ? 1
                        : 0;
            }

            private int countCursorIncrements(List<? extends Tree> trees, String cursorName) {
                int total = 0;
                for (Tree tree : trees) {
                    total += countCursorIncrements(tree, cursorName);
                }
                return total;
            }

            private int countCursorIncrements(Tree tree, String cursorName) {
                final int[] count = {0};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void scan(Tree nested, Void unused) {
                        if (nested != null && isCursorIncrement(nested, cursorName)) {
                            count[0]++;
                        }
                        return super.scan(nested, unused);
                    }
                }.scan(tree, null);
                return count[0];
            }

            private int countCursorWrites(List<? extends Tree> trees, String cursorName) {
                int total = 0;
                for (Tree tree : trees) {
                    total += countCursorWrites(tree, cursorName);
                }
                return total;
            }

            private int countCursorWrites(Tree tree, String cursorName) {
                final int[] count = {0};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void scan(Tree nested, Void unused) {
                        if (nested != null && isCursorWrite(nested, cursorName)) {
                            count[0]++;
                        }
                        return super.scan(nested, unused);
                    }
                }.scan(tree, null);
                return count[0];
            }

            private boolean isCursorIncrement(Tree expression, String cursorName) {
                Tree stripped = stripParentheses(expression);
                if (stripped instanceof UnaryTree unaryTree) {
                    return (unaryTree.getKind() == Tree.Kind.POSTFIX_INCREMENT
                                    || unaryTree.getKind() == Tree.Kind.PREFIX_INCREMENT)
                            && isIdentifier(unaryTree.getExpression(), cursorName);
                }
                if (stripped instanceof CompoundAssignmentTree compoundAssignment) {
                    return compoundAssignment.getKind() == Tree.Kind.PLUS_ASSIGNMENT
                            && isIdentifier(compoundAssignment.getVariable(), cursorName)
                            && isIntegerLiteral(compoundAssignment.getExpression(), 1);
                }
                if (stripped instanceof AssignmentTree assignment) {
                    return isIdentifier(assignment.getVariable(), cursorName)
                            && isCursorPlusOne(assignment.getExpression(), cursorName);
                }
                return false;
            }

            private boolean isCursorWrite(Tree expression, String cursorName) {
                Tree stripped = stripParentheses(expression);
                if (stripped instanceof UnaryTree unaryTree) {
                    return (unaryTree.getKind() == Tree.Kind.POSTFIX_INCREMENT
                                    || unaryTree.getKind() == Tree.Kind.PREFIX_INCREMENT
                                    || unaryTree.getKind() == Tree.Kind.POSTFIX_DECREMENT
                                    || unaryTree.getKind() == Tree.Kind.PREFIX_DECREMENT)
                            && isIdentifier(unaryTree.getExpression(), cursorName);
                }
                if (stripped instanceof CompoundAssignmentTree compoundAssignment) {
                    return isIdentifier(compoundAssignment.getVariable(), cursorName);
                }
                if (stripped instanceof AssignmentTree assignment) {
                    return isIdentifier(assignment.getVariable(), cursorName);
                }
                return false;
            }

            private boolean isCursorPlusOne(Tree expression, String cursorName) {
                Tree stripped = stripParentheses(expression);
                if (!(stripped instanceof BinaryTree binaryTree)
                        || binaryTree.getKind() != Tree.Kind.PLUS) {
                    return false;
                }
                return isIdentifier(binaryTree.getLeftOperand(), cursorName)
                        && isIntegerLiteral(binaryTree.getRightOperand(), 1);
            }

            private boolean isIdentifier(Tree tree, String name) {
                return stripParentheses(tree) instanceof IdentifierTree identifierTree
                        && name.equals(identifierTree.getName().toString());
            }

            private boolean isIntegerLiteral(Tree tree, int value) {
                return stripParentheses(tree) instanceof LiteralTree literalTree
                        && literalTree.getValue() instanceof Integer literal
                        && literal == value;
            }

            private boolean isNonNegativeIntegerLiteral(Tree tree) {
                Tree stripped = stripParentheses(tree);
                if (stripped instanceof LiteralTree literalTree
                        && literalTree.getValue() instanceof Integer literal) {
                    return literal >= 0;
                }
                if (stripped instanceof UnaryTree unaryTree
                        && unaryTree.getKind() == Tree.Kind.UNARY_PLUS
                        && stripParentheses(unaryTree.getExpression()) instanceof LiteralTree literalTree
                        && literalTree.getValue() instanceof Integer literal) {
                    return literal >= 0;
                }
                return false;
            }

            private boolean isCursorAssignmentToNonNegativeInteger(Tree tree, String cursorName) {
                Tree stripped = stripParentheses(tree);
                return stripped instanceof AssignmentTree assignment
                        && isIdentifier(assignment.getVariable(), cursorName)
                        && isNonNegativeIntegerLiteral(assignment.getExpression());
            }

            private Tree stripParentheses(Tree tree) {
                Tree current = tree;
                while (current instanceof ParenthesizedTree parenthesizedTree) {
                    current = parenthesizedTree.getExpression();
                }
                return current;
            }

            private Tree stripParenthesesAndCasts(Tree tree) {
                Tree current = tree;
                boolean changed = true;
                while (changed) {
                    changed = false;
                    while (current instanceof ParenthesizedTree parenthesizedTree) {
                        current = parenthesizedTree.getExpression();
                        changed = true;
                    }
                    if (current instanceof TypeCastTree typeCastTree) {
                        current = typeCastTree.getExpression();
                        changed = true;
                    }
                }
                return current;
            }

            private boolean isThrownExceptionConstruction(Tree tree) {
                TreePath path = treePath(tree);
                return path != null
                        && path.getParentPath() != null
                        && path.getParentPath().getLeaf().getKind() == Tree.Kind.THROW;
            }

            private void addUnsupportedError(String feature, Tree tree) {
                errors.add(TitanDiagnostics
                        .unsupportedFeature(feature, sourceLocation(tree))
                        .render());
            }

            private void addUnsupportedError(String feature, Tree tree, String suggestion) {
                errors.add(new TitanDiagnostic(
                        TitanErrorCode.E001,
                        "Unsupported feature '" + feature + "'",
                        sourceLocation(tree),
                        suggestion,
                        "Section 12.1"
                ).render());
            }

            private String sourceLocation(Tree tree) {
                long line = unit.getLineMap().getLineNumber(
                        parsedSources.trees().getSourcePositions().getStartPosition(unit, tree)
                );
                return unit.getSourceFile().getName() + ":" + line;
            }

            private record CursorScan(String cursorName, ReceiverKey receiverKey) {
            }

            private record ReceiverKey(String value) {
            }

            private record ReferenceGraph(
                    String variableName,
                    Map<String, String> edges,
                    String invalidReason,
                    int traversalBound
            ) {
            }

            private record ReferenceGraphLookup(VariableElement cursor) {
            }
        }.scan(method.getBody(), null);
    }
}
