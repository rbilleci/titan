package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps the README's worked examples tied to real source and current emitter output. */
class ReadmeBusinessExamplesTest {
    @TempDir
    Path generatedDir;

    @Test
    void readmeJavaAndSqlMatchTheRunnableExamples() throws Exception {
        Path source = Path.of("../examples/quickstart/src/main/java/example/BusinessRoutines.java");
        String java = normalize(Files.readString(source));
        String readme = Files.readString(Path.of("../README.md"));
        String sql = normalize(ReadmeBusinessExampleSupport.transpile("postgresql", generatedDir)
                .stream().map(TranspilationPipeline.GeneratedSql::sql).collect(Collectors.joining("\n")));

        for (String example : List.of("inventory", "pricing")) {
            assertTrue(java.contains(normalize(snippet(readme, example + ":java"))),
                    example + " Java snippet must match the runnable example");
            assertTrue(sql.contains(normalize(snippet(readme, example + ":postgresql"))),
                    example + " SQL snippet must match actual PostgreSQL output");
        }
        assertTrue(java.contains(normalize(snippet(readme, "inventory-jdbc:java"))),
                "JDBC alternative must match the runnable source");
    }

    private static String snippet(String readme, String id) {
        var matcher = Pattern.compile("<!-- example:" + Pattern.quote(id)
                + " -->\\s*```(?:java|sql)\\R([\\s\\S]*?)\\R```").matcher(readme);
        assertTrue(matcher.find(), "Missing README snippet " + id);
        return matcher.group(1);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
