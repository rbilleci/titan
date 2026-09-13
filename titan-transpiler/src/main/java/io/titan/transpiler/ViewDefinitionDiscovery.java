package io.titan.transpiler;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;

public final class ViewDefinitionDiscovery {

    private static final String VIEW_DEFINITION_ANNOTATION = "titan.dsl.ViewDefinition";

    public List<DiscoveredViewDefinition> discover(ParsedSources parsed) {
        List<DiscoveredViewDefinition> discovered = new ArrayList<>();

        for (CompilationUnitTree unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                private String currentClass;

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String previous = currentClass;
                    currentClass = node.getSimpleName().toString();
                    try {
                        return super.visitClass(node, unused);
                    } finally {
                        currentClass = previous;
                    }
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    TreePath currentPath = getCurrentPath();
                    Element element = parsed.trees().getElement(currentPath);
                    if (!(element instanceof VariableElement variableElement)) {
                        return super.visitVariable(node, unused);
                    }

                    if (!hasViewDefinition(variableElement)) {
                        return super.visitVariable(node, unused);
                    }

                    AnnotationTree annotation = node.getModifiers().getAnnotations().stream()
                            .filter(a -> a.getAnnotationType().toString().endsWith("ViewDefinition"))
                            .findFirst()
                            .orElse(null);
                    if (annotation == null) {
                        return super.visitVariable(node, unused);
                    }

                    String viewName = null;
                    boolean shared = false;

                    for (ExpressionTree arg : annotation.getArguments()) {
                        if (!(arg instanceof com.sun.source.tree.AssignmentTree assignment)) {
                            continue;
                        }
                        String key = assignment.getVariable().toString();
                        ExpressionTree value = assignment.getExpression();
                        if ("name".equals(key) && value instanceof LiteralTree literal && literal.getValue() instanceof String s) {
                            viewName = s;
                        } else if ("shared".equals(key) && value instanceof LiteralTree literal && literal.getValue() instanceof Boolean b) {
                            shared = b;
                        }
                    }

                    if (viewName == null || viewName.isBlank()) {
                        throw new IllegalArgumentException("@ViewDefinition requires non-empty name on field "
                                + node.getName() + " (" + unit.getSourceFile().getName() + ")");
                    }

                    long pos = parsed.trees().getSourcePositions().getStartPosition(unit, node);
                    int line = (int) unit.getLineMap().getLineNumber(pos);

                    String sqlBody = extractSqlBody(node);
                    discovered.add(new DiscoveredViewDefinition(
                            currentClass == null ? "<unknown>" : currentClass,
                            node.getName().toString(),
                            viewName,
                            shared,
                            sqlBody,
                            unit.getSourceFile().getName(),
                            line
                    ));

                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }

        validateUniqueNames(discovered);
        return List.copyOf(discovered);
    }

    private static void validateUniqueNames(List<DiscoveredViewDefinition> discovered) {
        Map<String, DiscoveredViewDefinition> firstByName = new LinkedHashMap<>();
        for (DiscoveredViewDefinition view : discovered) {
            String normalizedName = normalizeViewName(view.viewName());
            DiscoveredViewDefinition previous = firstByName.putIfAbsent(normalizedName, view);
            if (previous != null) {
                throw new IllegalArgumentException("@ViewDefinition duplicate view name '" + view.viewName()
                        + "' in code: " + previous.className() + "." + previous.fieldName()
                        + " and " + view.className() + "." + view.fieldName());
            }
        }
    }

    private static String normalizeViewName(String viewName) {
        return viewName == null ? "" : viewName.trim().toLowerCase(Locale.ROOT);
    }

    private static String extractSqlBody(VariableTree node) {
        ExpressionTree initializer = node.getInitializer();
        if (initializer instanceof LiteralTree literal && literal.getValue() instanceof String sql && !sql.isBlank()) {
            return sql;
        }
        return null;
    }

    private static boolean hasViewDefinition(VariableElement variableElement) {
        return variableElement.getAnnotationMirrors().stream()
                .anyMatch(a -> a.getAnnotationType().toString().equals(VIEW_DEFINITION_ANNOTATION));
    }
}
