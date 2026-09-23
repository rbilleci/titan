package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents per-expression whole-compilation-unit scans in the lowering hot path. */
class TreePathLookupDisciplineTest {

    @Test
    void expressionLoweringUsesTheSharedIdentityPathIndex() throws Exception {
        Path classesDirectory = Path.of(TreePathLookupDisciplineTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path moduleDirectory = classesDirectory;
        while (moduleDirectory != null && !Files.exists(moduleDirectory.resolve("build.gradle.kts"))) {
            moduleDirectory = moduleDirectory.getParent();
        }
        if (moduleDirectory == null) {
            throw new IllegalStateException(
                    "Unable to locate titan-transpiler module directory from " + classesDirectory);
        }

        String source = Files.readString(moduleDirectory.resolve(Path.of(
                "src", "main", "java", "io", "titan", "transpiler", "tir", "ExpressionLowerer.java")));
        assertFalse(source.contains("TreePath.getPath("),
                "ExpressionLowerer must use LowererSupport.resolveTreePath's shared identity index; "
                        + "TreePath.getPath rescans a complete compilation unit for every expression");
    }
}
