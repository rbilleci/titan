package io.titan.transpiler.tir;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import io.titan.transpiler.LoweringContext;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Shared helpers for the Java-to-TIR lowering classes: positioned diagnostics
 * ({@code unsupportedFeature}/{@code loweringError}), tree-path and element resolution,
 * and the Java-to-TIR type mapping entry point.
 */
final class LowererSupport {

    private LowererSupport() {
    }

    static String invocationName(MethodInvocationTree invocation) {
        ExpressionTree select = invocation.getMethodSelect();
        if (select instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (select instanceof com.sun.source.tree.MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return select.toString();
    }

    private static String sourceLocationSuffix(Tree tree, TreePath contextPath, ParsedSources parsedSources) {
        if (tree == null || contextPath == null || parsedSources == null) {
            return "";
        }
        CompilationUnitTree unit = contextPath.getCompilationUnit();
        long position = parsedSources.trees().getSourcePositions().getStartPosition(unit, tree);
        if (position < 0) {
            return "";
        }
        long line = unit.getLineMap().getLineNumber(position);
        return " at " + unit.getSourceFile().getName() + ":" + line;
    }

    static TitanDiagnosticException unsupportedFeature(String detail, Tree tree, ParsedSources parsedSources) {
        return unsupportedFeature(detail, tree, parsedSources, null);
    }

    static TitanDiagnosticException unsupportedFeature(String detail, Tree tree, ParsedSources parsedSources, String suggestion) {
        return loweringError(TitanErrorCode.E001, "Unsupported feature. " + detail, tree, parsedSources, suggestion);
    }

    static TitanDiagnosticException loweringError(TitanErrorCode code, String message, Tree tree, ParsedSources parsedSources, String suggestion) {
        return new TitanDiagnosticException(new TitanDiagnostic(code, message, sourceLocation(tree, parsedSources), suggestion, null));
    }

    private static String sourceLocation(Tree tree, ParsedSources parsedSources) {
        if (tree == null || parsedSources == null) {
            return null;
        }
        TreePath path = resolveTreePath(parsedSources, tree);
        if (path == null) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        long position = parsedSources.trees().getSourcePositions().getStartPosition(unit, tree);
        if (position < 0) {
            return unit.getSourceFile().getName();
        }
        return unit.getSourceFile().getName() + ":" + unit.getLineMap().getLineNumber(position);
    }

    static ExpressionTree unwrapParenthesized(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parenthesizedTree) {
            current = parenthesizedTree.getExpression();
        }
        return current;
    }

    static String resolvedMethodSignatureKey(TreePath invocationPath, ParsedSources parsedSources) {
        ExecutableElement method = resolvedMethodElement(invocationPath, parsedSources);
        if (method == null) {
            return null;
        }
        return method.getEnclosingElement() + "#" + method.getSimpleName()
                + "(" + method.getParameters().stream().map(p -> p.asType().toString()).reduce((a, b) -> a + "," + b).orElse("") + ")";
    }

    static ExecutableElement resolvedMethodElement(TreePath invocationPath, ParsedSources parsedSources) {
        if (invocationPath == null) {
            return null;
        }
        Element called = parsedSources.trees().getElement(invocationPath);
        if (!(called instanceof ExecutableElement method)) {
            return null;
        }
        return method;
    }

    static TreePath resolveTreePath(ParsedSources parsedSources, Tree tree) {
        return LoweringContext.forSources(parsedSources).pathFor(tree);
    }

    static TirType mapType(TypeMirror typeMirror, String context) {
        return JavaTypeToTirMapper.map(typeMirror, context);
    }

    /**
     * Source-local qualified name of a type: the dotted enclosing-type chain without the
     * package ({@code Outer.SortPath}; plain {@code SortPath} for a top-level type). This is
     * the single identity record/enum SQL names derive from (B-2 / TG-BLK-005) — it must match
     * what {@code RecordDefinitionDiscovery}/{@code EnumDefinitionDiscovery} compute from the
     * source tree for the same declaration.
     */
    static String sourceLocalTypeName(TypeElement type) {
        StringBuilder qualified = new StringBuilder(type.getSimpleName());
        Element enclosing = type.getEnclosingElement();
        while (enclosing instanceof TypeElement enclosingType) {
            CharSequence simpleName = enclosingType.getSimpleName();
            if (!simpleName.isEmpty()) {
                qualified.insert(0, ".").insert(0, simpleName);
            }
            enclosing = enclosingType.getEnclosingElement();
        }
        return qualified.toString();
    }
}
