package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilationPipelineUnresolvedHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsSourceLocalStaticHelperAsInternalRoutine() throws Exception {
        Path source = tempDir.resolve("InternalHelperFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class InternalHelperFunction {
                    @StoredFunction
                    static int run(int value) {
                        return helper(value);
                    }

                    static int helper(int value) {
                        return value + 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        String runSql = generated.stream()
                .filter(sql -> sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String helperSql = generated.stream()
                .filter(sql -> sql.methodName().equals("helper"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(runSql.contains("__titan_internal_internal_helper_function_helper_"));
        assertTrue(!runSql.contains("InternalHelperFunction#helper"));
        assertTrue(helperSql.contains("CREATE OR REPLACE FUNCTION \"public\".\"__titan_internal_internal_helper_function_helper_"));
    }

    @Test
    void emitsNestedSourceLocalStaticHelpersRecursively() throws Exception {
        Path source = tempDir.resolve("NestedInternalHelperFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class NestedInternalHelperFunction {
                    @StoredFunction
                    static int run(int value) {
                        return helper(value);
                    }

                    static int helper(int value) {
                        return leaf(value) + 1;
                    }

                    static int leaf(int value) {
                        return value + 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        assertTrue(generated.stream().anyMatch(sql -> sql.methodName().equals("run")));
        assertTrue(generated.stream().anyMatch(sql -> sql.methodName().equals("helper")));
        assertTrue(generated.stream().anyMatch(sql -> sql.methodName().equals("leaf")));
        String helperSql = generated.stream()
                .filter(sql -> sql.methodName().equals("helper"))
                .findFirst()
                .orElseThrow()
                .sql();
        assertTrue(helperSql.contains("__titan_internal_nested_internal_helper_function_leaf_"));
    }

    @Test
    void keepsPostgresInternalHelperNamesUniqueWithinIdentifierLimit() throws Exception {
        Path source = tempDir.resolve("VeryLongInternalHelperFunctionNames.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class VeryLongInternalHelperFunctionNames {
                    @StoredFunction
                    static int run(int value) {
                        return generatedArticleScalarFilterMatches(value)
                                + generatedArticleScalarValueEquals(value);
                    }

                    static int generatedArticleScalarFilterMatches(int value) {
                        return value + 1;
                    }

                    static int generatedArticleScalarValueEquals(int value) {
                        return value + 2;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        Set<String> helperNames = new HashSet<>();
        for (TranspilationPipeline.GeneratedSql sql : generated.stream()
                .filter(sql -> sql.sql().contains("CREATE OR REPLACE FUNCTION public.__titan_internal_"))
                .toList()) {
            String createPrefix = "CREATE OR REPLACE FUNCTION public.";
            String routineName = sql.sql().substring(
                    sql.sql().indexOf(createPrefix) + createPrefix.length(),
                    sql.sql().indexOf('('));
            assertTrue(routineName.length() <= 63, routineName);
            assertTrue(helperNames.add(routineName), "duplicate helper name: " + routineName);
        }
    }

    @Test
    void stillAllowsCallsBetweenAnnotatedEntryPoints() throws Exception {
        Path source = tempDir.resolve("AnnotatedHelperFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class AnnotatedHelperFunction {
                    @StoredFunction
                    static int run(int value) {
                        return helper(value);
                    }

                    @StoredFunction
                    static int helper(int value) {
                        return value + 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        String runSql = generated.stream()
                .filter(sql -> sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        assertTrue(runSql.contains("helper(p_value)"));
        assertTrue(!runSql.contains("AnnotatedHelperFunction#helper"));
    }

    @Test
    void internalHelperCallsAnnotatedEntryPointAsReusableStoredFunction() throws Exception {
        Path source = tempDir.resolve("InternalHelperCallingAnnotatedFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class InternalHelperCallingAnnotatedFunction {
                    @StoredFunction
                    static int run(int value) {
                        return helper(value);
                    }

                    static int helper(int value) {
                        return normalize(value) + 1;
                    }

                    @StoredFunction
                    static int normalize(int value) {
                        return value * 2;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        String runSql = generated.stream()
                .filter(sql -> sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String helperSql = generated.stream()
                .filter(sql -> sql.methodName().equals("helper"))
                .findFirst()
                .orElseThrow()
                .sql();
        String normalizeSql = generated.stream()
                .filter(sql -> sql.methodName().equals("normalize"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(runSql.contains("__titan_internal_"));
        assertTrue(runSql.contains("_helper_"));
        assertTrue(!runSql.contains("InternalHelperCallingAnnotatedFunction#helper"));
        assertTrue(helperSql.contains("normalize(p_value)"));
        assertTrue(!helperSql.contains("InternalHelperCallingAnnotatedFunction#normalize"));
        assertTrue(!helperSql.contains("__titan_internal_internal_helper_calling_annotated_function_normalize_"));
        assertTrue(normalizeSql.contains("CREATE OR REPLACE FUNCTION \"public\".\"normalize\""));
    }

    @Test
    void emitsStatelessSourceLocalInstanceHelperAsInternalRoutine() throws Exception {
        Path source = tempDir.resolve("InstanceHelperFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                final class InstanceFormatter {
                    int helper(int value) {
                        return value + 1;
                    }
                }

                class InstanceHelperFunction {
                    static final InstanceFormatter FORMATTER = new InstanceFormatter();

                    @StoredFunction
                    static int run(int value) {
                        InstanceFormatter selected = FORMATTER;
                        return selected.helper(value);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        String runSql = generated.stream()
                .filter(sql -> sql.className().equals("InstanceHelperFunction"))
                .findFirst()
                .orElseThrow()
                .sql();
        String helperSql = generated.stream()
                .filter(sql -> sql.className().equals("InstanceFormatter"))
                .findFirst()
                .orElseThrow()
                .sql();
        assertTrue(runSql.contains("__titan_internal_instance_formatter_helper_"));
        assertTrue(!runSql.contains("FORMATTER"));
        assertTrue(helperSql.contains("p_value + 1"));
    }

    @Test
    void rejectsSourceLocalInstanceHelperReceiverState() throws Exception {
        Path source = tempDir.resolve("InstanceHelperFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                final class InstanceFormatter {
                    private final int offset = 1;

                    int helper(int value) {
                        return value + offset;
                    }
                }

                class InstanceHelperFunction {
                    static final InstanceFormatter FORMATTER = new InstanceFormatter();

                    @StoredFunction
                    static int run(int value) {
                        return FORMATTER.helper(value);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true)
        );

        assertTrue(exception.getMessage().contains("Titan internal instance helper method uses receiver state"));
        assertTrue(exception.getMessage().contains("InstanceFormatter.helper"));
    }
}
