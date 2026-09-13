package io.titan.transpiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;

public final class EnumDefinitionDiscovery {

    public List<DiscoveredEnumDefinition> discover(ParsedSources parsed) {
        List<DiscoveredEnumDefinition> discovered = new ArrayList<>();

        for (CompilationUnitTree unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                // Source-local enclosing-type chain (no package), innermost last. The qualified
                // enum identity derives from it (B-2 / TG-BLK-005): generated SQL names must
                // distinguish same-simple-name enums declared in different enclosing types.
                private final List<String> enclosingTypes = new ArrayList<>();

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String simpleName = node.getSimpleName().toString();
                    boolean named = !simpleName.isBlank();
                    if (named) {
                        enclosingTypes.add(simpleName);
                    }
                    try {
                        if (node.getKind() == Tree.Kind.ENUM) {
                            discovered.add(discoverEnum(node, unit, parsed));
                        }
                        return super.visitClass(node, unused);
                    } finally {
                        if (named) {
                            enclosingTypes.remove(enclosingTypes.size() - 1);
                        }
                    }
                }

                private DiscoveredEnumDefinition discoverEnum(ClassTree node, CompilationUnitTree unit, ParsedSources parsed) {
                    List<DiscoveredEnumDefinition.EnumField> fields = new ArrayList<>();
                    List<DiscoveredEnumDefinition.EnumMethod> methods = new ArrayList<>();
                    List<DiscoveredEnumDefinition.EnumConstant> constants = new ArrayList<>();

                    for (Tree member : node.getMembers()) {
                        if (member instanceof VariableTree variableTree) {
                            TreePath memberPath = new TreePath(getCurrentPath(), variableTree);
                            Element element = parsed.trees().getElement(memberPath);
                            if (!(element instanceof VariableElement variableElement)) {
                                continue;
                            }

                            if (variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
                                constants.add(new DiscoveredEnumDefinition.EnumConstant(
                                        variableTree.getName().toString(),
                                        extractConstantArguments(variableTree.getInitializer())));
                            } else if (!variableElement.getModifiers().contains(Modifier.STATIC)) {
                                fields.add(new DiscoveredEnumDefinition.EnumField(
                                        variableTree.getName().toString(),
                                        variableTree.getType() == null ? variableElement.asType().toString() : variableTree.getType().toString()));
                            }
                            continue;
                        }

                        if (member instanceof MethodTree methodTree) {
                            TreePath memberPath = new TreePath(getCurrentPath(), methodTree);
                            Element element = parsed.trees().getElement(memberPath);
                            if (!(element instanceof ExecutableElement executableElement)) {
                                continue;
                            }
                            if (executableElement.getModifiers().contains(Modifier.STATIC)
                                    || executableElement.getParameters().size() != 0
                                    || executableElement.getReturnType().getKind().name().equals("VOID")) {
                                continue;
                            }
                            methods.add(new DiscoveredEnumDefinition.EnumMethod(
                                    methodTree.getName().toString(),
                                    executableElement.getReturnType().toString()));
                        }
                    }

                    long pos = parsed.trees().getSourcePositions().getStartPosition(unit, node);
                    int line = (int) unit.getLineMap().getLineNumber(pos);

                    // The chain already ends with this enum's own name (visitClass pushes
                    // before dispatching), so the join is the full source-local qualified name.
                    return new DiscoveredEnumDefinition(
                            String.join(".", enclosingTypes),
                            node.getSimpleName().toString(),
                            List.copyOf(fields),
                            List.copyOf(methods),
                            List.copyOf(constants),
                            unit.getSourceFile().getName(),
                            line);
                }
            }.scan(unit, null);
        }

        return List.copyOf(discovered);
    }

    private static List<String> extractConstantArguments(ExpressionTree initializer) {
        if (!(initializer instanceof NewClassTree newClassTree)) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (ExpressionTree arg : newClassTree.getArguments()) {
            if (arg instanceof LiteralTree literal) {
                Object value = literal.getValue();
                args.add(value == null ? "null" : String.valueOf(value));
            } else {
                args.add(arg.toString());
            }
        }
        return List.copyOf(args);
    }
}
