package io.titan.transpiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;

/**
 * Finds unannotated source-local helpers reachable from Titan entry points.
 */
public final class InternalHelperDiscovery {

    public List<DiscoveredEntryPoint> discover(ParsedSources parsedSources, List<DiscoveredEntryPoint> publicEntryPoints) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        if (publicEntryPoints == null) {
            throw new IllegalArgumentException("publicEntryPoints must not be null");
        }

        Map<String, MethodLocation> sourceMethods = sourceMethods(parsedSources);
        Set<String> publicKeys = new LinkedHashSet<>();
        for (DiscoveredEntryPoint entryPoint : publicEntryPoints) {
            publicKeys.add(entryPoint.methodSignatureKey());
        }

        Map<String, DiscoveredEntryPoint> helpersByKey = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> scanned = new LinkedHashSet<>();
        for (DiscoveredEntryPoint entryPoint : publicEntryPoints) {
            queue.addLast(entryPoint.methodSignatureKey());
        }

        while (!queue.isEmpty()) {
            String callerKey = queue.removeFirst();
            if (!scanned.add(callerKey)) {
                continue;
            }
            MethodLocation caller = sourceMethods.get(callerKey);
            if (caller == null) {
                continue;
            }
            for (String calledKey : calledSourceMethodKeys(parsedSources, caller)) {
                if (publicKeys.contains(calledKey)) {
                    queue.addLast(calledKey);
                    continue;
                }
                MethodLocation helper = sourceMethods.get(calledKey);
                if (helper == null || helpersByKey.containsKey(calledKey)) {
                    continue;
                }
                if (helper.methodTree().getBody() == null || isEnumMethod(helper.method())) {
                    continue;
                }
                // A source-local static void helper carrying a JDBC handle is a compile-time binder
                // fragment. JdbcStatementLowerer inlines it into the owning statement state; emitting
                // it as an SQL routine would expose an infrastructure handle as a routine parameter
                // and sever prepare/bind/execute provenance.
                if (isInlineJdbcHelper(helper.method())) {
                    // The binder itself is not emitted, but calls in its body become direct calls in
                    // the owning routine after inlining. Scan its dependency closure so those ordinary
                    // scalar helpers are still discovered and emitted.
                    queue.addLast(calledKey);
                    continue;
                }
                DiscoveredEntryPoint helperEntryPoint = toInternalEntryPoint(parsedSources, helper);
                helpersByKey.put(calledKey, helperEntryPoint);
                queue.addLast(calledKey);
            }
        }

        return List.copyOf(helpersByKey.values());
    }

    private static boolean isInlineJdbcHelper(ExecutableElement method) {
        if (method == null || method.getReturnType().getKind() != TypeKind.VOID
                || !method.getModifiers().contains(Modifier.STATIC)) {
            return false;
        }
        for (VariableElement parameter : method.getParameters()) {
            String type = parameter.asType().toString();
            if (type.equals("java.sql.PreparedStatement") || type.equals("java.sql.CallableStatement")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, MethodLocation> sourceMethods(ParsedSources parsedSources) {
        Map<String, MethodLocation> methods = new LinkedHashMap<>();
        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath path = getCurrentPath();
                    Element element = parsedSources.trees().getElement(path);
                    if (element instanceof ExecutableElement method) {
                        String key = methodSignatureKey(method);
                        long line = unit.getLineMap().getLineNumber(
                                parsedSources.trees().getSourcePositions().getStartPosition(unit, node));
                        methods.put(key, new MethodLocation(method, node, path, unit.getSourceFile().getName(), line));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }
        return Map.copyOf(methods);
    }

    private static List<String> calledSourceMethodKeys(ParsedSources parsedSources, MethodLocation caller) {
        if (caller.methodTree().getBody() == null) {
            return List.of();
        }
        // WS-C Phase 3 Rung 4 (§3.6 form 2): a runtime-sized List-driven IN that the JDBC front-end
        // ARRAY-BINDS elides its placeholder-run construction — including a recognized run helper such as
        // `placeholders(coll.size())`. The lowerer never emits that call, so its callee is NOT a reachable
        // helper to transpile. Skip every invocation a proven collection-IN array-bind subsumes (its
        // run-operand / bind-loop subtree), so helper discovery agrees with the recognizer + lowerer +
        // FeatureValidator on exactly what the array-bind removes. (Any OTHER call still pulls its callee
        // in; an un-array-bound `placeholders(...)` is still discovered and transpiled.)
        Set<com.sun.source.tree.Tree> subsumed = collectionInSubsumedTrees(parsedSources, caller);
        List<String> calledKeys = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (subsumed.contains(node)) {
                    return null; // an array-bind-subsumed call (and its subsumed argument calls) — elided.
                }
                Element called = parsedSources.trees().getElement(getCurrentPath());
                if (called != null && called.getKind() == ElementKind.METHOD) {
                    calledKeys.add(methodSignatureKey((ExecutableElement) called));
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(new TreePath(caller.path(), caller.methodTree().getBody()), null);
        return List.copyOf(calledKeys);
    }

    /**
     * The Java AST nodes every proven §3.6-form-2 collection-IN array-bind subsumes in {@code caller}'s
     * body (its run-operand — e.g. {@code placeholders(coll.size())} — and per-element bind loop /
     * {@code setArray}). Empty unless the caller's body bears JDBC handles and a collection-IN use proof
     * holds; built with the same {@link io.titan.transpiler.jdbc.JdbcCollectionInRecognizer} the lowerer
     * uses, so discovery elides exactly what the lowerer elides.
     */
    private static Set<com.sun.source.tree.Tree> collectionInSubsumedTrees(
            ParsedSources parsedSources, MethodLocation caller) {
        if (caller.methodTree().getBody() == null) {
            return Set.of();
        }
        io.titan.transpiler.jdbc.JdbcTypeOracle oracle =
                new io.titan.transpiler.jdbc.JdbcTypeOracle(parsedSources);
        io.titan.transpiler.jdbc.JdbcShapes shapes =
                new io.titan.transpiler.jdbc.JdbcShapes(parsedSources, oracle);
        TreePath bodyPath = new TreePath(caller.path(), caller.methodTree().getBody());
        Set<com.sun.source.tree.Tree> subsumed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (io.titan.transpiler.jdbc.JdbcCollectionInRecognizer.CollectionInPlan plan
                : new io.titan.transpiler.jdbc.JdbcCollectionInRecognizer(parsedSources, shapes, oracle)
                        .recognizeAll(caller.methodTree().getBody(), bodyPath)) {
            subsumed.addAll(plan.subsumedTrees());
        }
        return subsumed;
    }

    private static DiscoveredEntryPoint toInternalEntryPoint(ParsedSources parsedSources, MethodLocation helper) {
        ExecutableElement method = helper.method();
        String methodName = method.getEnclosingElement() + "." + method.getSimpleName();
        String location = helper.sourceFile() + ":" + helper.line();
        if (!method.getModifiers().contains(Modifier.STATIC) && usesInstanceState(parsedSources, helper, method)) {
            throw new IllegalArgumentException("Titan internal instance helper method uses receiver state: " + methodName + " (" + location + ").");
        }
        if (helper.methodTree().getBody() == null) {
            throw new IllegalArgumentException("Titan internal helper method has no body: " + methodName + " (" + location + ").");
        }

        EntryPointKind kind = method.getReturnType().getKind() == TypeKind.VOID
                ? EntryPointKind.STORED_PROCEDURE
                : EntryPointKind.STORED_FUNCTION;
        return new DiscoveredEntryPoint(
                kind,
                method.getEnclosingElement().toString(),
                method.getSimpleName().toString(),
                method.getParameters().stream().map(p -> p.getSimpleName().toString()).toList(),
                method.getParameters().stream().map(p -> p.asType().toString()).toList(),
                method.getReturnType().toString(),
                helper.sourceFile(),
                helper.line(),
                null,
                null,
                false,
                SecurityPolicy.NONE,
                true);
    }

    private static boolean usesInstanceState(ParsedSources parsedSources, MethodLocation helper, ExecutableElement method) {
        if (helper.methodTree().getBody() == null) {
            return true;
        }
        final boolean[] usesState = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(com.sun.source.tree.IdentifierTree node, Void unused) {
                String name = node.getName().toString();
                if ("this".equals(name) || "super".equals(name)) {
                    usesState[0] = true;
                    return super.visitIdentifier(node, unused);
                }
                Element element = parsedSources.trees().getElement(getCurrentPath());
                if (isInstanceField(element, method)) {
                    usesState[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree node, Void unused) {
                String receiver = node.getExpression().toString();
                if ("this".equals(receiver) || "super".equals(receiver)) {
                    usesState[0] = true;
                }
                Element element = parsedSources.trees().getElement(getCurrentPath());
                if (isInstanceField(element, method)) {
                    usesState[0] = true;
                }
                return super.visitMemberSelect(node, unused);
            }
        }.scan(new TreePath(helper.path(), helper.methodTree().getBody()), null);
        return usesState[0];
    }

    private static boolean isInstanceField(Element element, ExecutableElement method) {
        return element instanceof VariableElement variable
                && variable.getKind() == ElementKind.FIELD
                && variable.getEnclosingElement().equals(method.getEnclosingElement())
                && !variable.getModifiers().contains(Modifier.STATIC);
    }

    private static boolean isEnumMethod(ExecutableElement method) {
        return method.getEnclosingElement() != null
                && method.getEnclosingElement().getKind() == ElementKind.ENUM;
    }

    private static String methodSignatureKey(ExecutableElement method) {
        return MethodSignatureKeys.of(method);
    }

    private record MethodLocation(
            ExecutableElement method,
            MethodTree methodTree,
            TreePath path,
            String sourceFile,
            long line
    ) {
    }
}
