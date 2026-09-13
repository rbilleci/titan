package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Precedence: method @SqlSafety > nearest-enclosing class @SqlSafety > build-level default. */
class SqlSafetyResolverTest {

    @TempDir
    Path tempDir;

    private Map<String, ExecutableElement> methodsOf(String className, String source) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        Map<String, ExecutableElement> result = new HashMap<>();
        for (var unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    Element element = parsed.trees().getElement(getCurrentPath());
                    if (element instanceof ExecutableElement method && !"<init>".contentEquals(method.getSimpleName())) {
                        // Key as enclosingSimpleName.methodName so nested-class methods stay distinct.
                        result.put(method.getEnclosingElement().getSimpleName() + "." + method.getSimpleName(), method);
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    @Test
    void methodAnnotationOverridesClassAndBuildLevel() throws Exception {
        var methods = methodsOf("Prec", """
                import titan.dsl.*;

                @SqlSafety(SqlSafetyMode.STRICT)
                class Prec {
                    @SqlSafety(SqlSafetyMode.PERMISSIVE)
                    void m() {}
                    void n() {}
                }
                """);
        SqlSafetyResolver resolver = new SqlSafetyResolver(SqlSafetyMode.STRICT);
        // method-level PERMISSIVE wins over class-level STRICT.
        assertEquals(SqlSafetyMode.PERMISSIVE, resolver.effectiveMode(methods.get("Prec.m")));
        // n() inherits the class-level STRICT.
        assertEquals(SqlSafetyMode.STRICT, resolver.effectiveMode(methods.get("Prec.n")));
    }

    @Test
    void classAnnotationOverridesBuildLevel() throws Exception {
        var methods = methodsOf("ClassPerm", """
                import titan.dsl.*;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class ClassPerm {
                    void m() {}
                }
                """);
        // Build-level STRICT, class-level PERMISSIVE -> class wins.
        assertEquals(SqlSafetyMode.PERMISSIVE, new SqlSafetyResolver(SqlSafetyMode.STRICT).effectiveMode(methods.get("ClassPerm.m")));
    }

    @Test
    void buildLevelDefaultAppliesWhenUnannotated() throws Exception {
        var methods = methodsOf("Bare", """
                import titan.dsl.*;

                class Bare {
                    void m() {}
                }
                """);
        assertEquals(SqlSafetyMode.STRICT, new SqlSafetyResolver(SqlSafetyMode.STRICT).effectiveMode(methods.get("Bare.m")));
        assertEquals(SqlSafetyMode.PERMISSIVE, new SqlSafetyResolver(SqlSafetyMode.PERMISSIVE).effectiveMode(methods.get("Bare.m")));
    }

    @Test
    void nestedClassResolvesToNearestEnclosingAnnotation() throws Exception {
        var methods = methodsOf("Outer", """
                import titan.dsl.*;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class Outer {
                    void outerMethod() {}

                    @SqlSafety(SqlSafetyMode.STRICT)
                    static class Inner {
                        void innerMethod() {}
                    }

                    static class Plain {
                        void plainMethod() {}
                    }
                }
                """);
        SqlSafetyResolver resolver = new SqlSafetyResolver(SqlSafetyMode.STRICT);
        // Outer is PERMISSIVE.
        assertEquals(SqlSafetyMode.PERMISSIVE, resolver.effectiveMode(methods.get("Outer.outerMethod")));
        // Inner has its own STRICT annotation; the outer PERMISSIVE does NOT transitively cover it.
        assertEquals(SqlSafetyMode.STRICT, resolver.effectiveMode(methods.get("Inner.innerMethod")));
        // Plain (nested, unannotated): nearest enclosing annotation is Outer's PERMISSIVE.
        assertEquals(SqlSafetyMode.PERMISSIVE, resolver.effectiveMode(methods.get("Plain.plainMethod")));
    }

    @Test
    void resolveReportsTheSourceThatDecidedTheModeFromOneWalk() throws Exception {
        // resolve() returns mode + RelaxationSource from the SAME narrowest-scope-wins walk that
        // effectiveMode() uses, so the "effective permissive scopes" report can attribute the
        // non-greppable class-/build-level cases without re-deriving precedence.
        var methods = methodsOf("Src", """
                import titan.dsl.*;

                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                class Src {
                    @SqlSafety(SqlSafetyMode.STRICT)
                    void methodAnnotated() {}
                    void classInherited() {}

                    @SqlSafety(SqlSafetyMode.STRICT)
                    static class Inner {
                        void innerOwn() {}
                    }
                    static class Plain {
                        void plainInherited() {}
                    }
                }
                """);
        SqlSafetyResolver resolver = new SqlSafetyResolver(SqlSafetyMode.PERMISSIVE);

        // Method's own annotation decided it.
        var methodRes = resolver.resolve(methods.get("Src.methodAnnotated"));
        assertEquals(SqlSafetyMode.STRICT, methodRes.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.METHOD_ANNOTATION, methodRes.source());

        // The enclosing class annotation decided it.
        var classRes = resolver.resolve(methods.get("Src.classInherited"));
        assertEquals(SqlSafetyMode.PERMISSIVE, classRes.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.CLASS_ANNOTATION, classRes.source());

        // The inner class's own annotation decided it (outer PERMISSIVE does not reach in).
        var innerRes = resolver.resolve(methods.get("Inner.innerOwn"));
        assertEquals(SqlSafetyMode.STRICT, innerRes.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.CLASS_ANNOTATION, innerRes.source());

        // The nearest enclosing (outer) annotation decided the nested-unannotated method.
        var plainRes = resolver.resolve(methods.get("Plain.plainInherited"));
        assertEquals(SqlSafetyMode.PERMISSIVE, plainRes.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.CLASS_ANNOTATION, plainRes.source());
    }

    @Test
    void resolveAttributesUnannotatedScopesToTheBuildFlag() throws Exception {
        var methods = methodsOf("NoAnno", """
                import titan.dsl.*;

                class NoAnno {
                    void m() {}
                }
                """);
        var permissive = new SqlSafetyResolver(SqlSafetyMode.PERMISSIVE).resolve(methods.get("NoAnno.m"));
        assertEquals(SqlSafetyMode.PERMISSIVE, permissive.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.BUILD_FLAG, permissive.source());

        var strict = new SqlSafetyResolver(SqlSafetyMode.STRICT).resolve(methods.get("NoAnno.m"));
        assertEquals(SqlSafetyMode.STRICT, strict.mode());
        assertEquals(SqlSafetyResolver.RelaxationSource.BUILD_FLAG, strict.source());
    }

    @Test
    void parseBuildLevelIsSecureByDefault() {
        assertEquals(SqlSafetyMode.PERMISSIVE, SqlSafetyResolver.parseBuildLevel("permissive"));
        assertEquals(SqlSafetyMode.PERMISSIVE, SqlSafetyResolver.parseBuildLevel("  PERMISSIVE  "));
        assertEquals(SqlSafetyMode.STRICT, SqlSafetyResolver.parseBuildLevel("strict"));
        assertEquals(SqlSafetyMode.STRICT, SqlSafetyResolver.parseBuildLevel(null));
        assertEquals(SqlSafetyMode.STRICT, SqlSafetyResolver.parseBuildLevel(""));
        assertEquals(SqlSafetyMode.STRICT, SqlSafetyResolver.parseBuildLevel("nonsense"));
    }
}
