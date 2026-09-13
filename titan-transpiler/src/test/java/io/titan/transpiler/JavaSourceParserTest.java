package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSourceParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesAstForModernJavaSyntax() throws Exception {
        Path sourceFile = tempDir.resolve("Demo.java");
        Files.writeString(sourceFile, """
                class Demo {
                    String classify(int value) {
                        return switch (value) {
                            case 0 -> \"zero\";
                            default -> \"other\";
                        };
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        assertEquals(1, parsed.compilationUnits().size());
        CompilationUnitTree unit = parsed.compilationUnits().getFirst();
        String sourceText = unit.toString();
        assertTrue(sourceText.contains("switch"));
        assertFalse(parsed.diagnostics().getDiagnostics().stream().anyMatch(d -> d.getKind().name().equals("ERROR")));
    }

    @Test
    void resolvesTypesFromCompiledClasspath() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Test requires JDK compiler");

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        Path supportSource = tempDir.resolve("Support.java");
        Files.writeString(supportSource, """
                public class Support {
                    public static String greet() {
                        return \"hi\";
                    }
                }
                """);

        int compileExit = compiler.run(null, null, null,
                "-d", classesDir.toString(),
                supportSource.toString());
        assertEquals(0, compileExit, "Support class should compile");

        Path mainSource = tempDir.resolve("Main.java");
        Files.writeString(mainSource, """
                class Main {
                    void run() {
                        var msg = Support.greet();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(mainSource), List.of(classesDir), "21", false);

        CompilationUnitTree unit = parsed.compilationUnits().getFirst();
        AtomicReference<TreePath> supportCallPath = new AtomicReference<>();
        AtomicReference<TreePath> varPath = new AtomicReference<>();

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect().toString().equals("Support.greet")) {
                    supportCallPath.set(getCurrentPath());
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getName().contentEquals("msg")) {
                    varPath.set(getCurrentPath());
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit, null);

        assertNotNull(supportCallPath.get());
        assertNotNull(varPath.get());
        assertEquals("java.lang.String", parsed.trees().getTypeMirror(supportCallPath.get()).toString());
        assertEquals(Tree.Kind.VARIABLE, varPath.get().getLeaf().getKind());
        assertEquals("java.lang.String", parsed.trees().getTypeMirror(varPath.get()).toString());
    }
}
