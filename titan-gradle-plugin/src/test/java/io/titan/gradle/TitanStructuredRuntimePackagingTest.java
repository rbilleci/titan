package io.titan.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TitanStructuredRuntimePackagingTest {

    @TempDir
    Path tempDir;

    @Test
    void packagesSecondModelStructuredDescriptorEntryPointThroughTitanTasks() throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks()
                .create("gap001StructuredRuntimeTranspile", TitanTranspileTask.class);
        TitanPackageTask packageTask = project.getTasks()
                .create("gap001StructuredRuntimePackage", TitanPackageTask.class);

        SourceFixture source = writeSource("packaged", """
                package demo;

                import titan.dsl.StoredFunction;

                record CourseField(String name, String tableName, String columnName, String outputKey) {}

                class CourseRuntimePackage {
                    @StoredFunction
                    public static String courseOutputKey(String selectedField) {
                        CourseField[] fields = new CourseField[] {
                            new CourseField("id", "courses", "id", "id"),
                            new CourseField("title", "courses", "title", "displayTitle"),
                            new CourseField("summary", "lessons", "summary", "lessonSummary")
                        };
                        for (CourseField field : fields) {
                            if (field.name().equals(selectedField)) {
                                return field.outputKey();
                            }
                        }
                        return "error:unknown-field";
                    }
                }
                """);

        Path generatedSql = tempDir.resolve("generated-sql");
        transpile.getSourceFiles().from(source.annotationSource().toFile(), source.entryPointSource().toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(generatedSql.toFile());
        transpile.run();

        assertTrue(Files.exists(generatedSql.resolve("postgresql")));
        assertTrue(Files.exists(generatedSql.resolve("mysql")));
        assertTrue(hasSqlFileContaining(generatedSql.resolve("postgresql"), "course_output_key"));
        assertTrue(hasSqlFileContaining(generatedSql.resolve("mysql"), "course_output_key"));

        Path packageOutput = tempDir.resolve("packaged-sql");
        packageTask.getSqlInputDir().set(project.getLayout().dir(project.provider(() -> generatedSql.toFile())));
        packageTask.getMode().set("migration");
        packageTask.getTitanVersion().set("gap001-m10.4");
        packageTask.getOutputDir().set(project.getLayout().dir(project.provider(() -> packageOutput.toFile())));
        packageTask.run();

        assertTrue(hasMigrationContaining(packageOutput.resolve("postgresql"), "course_output_key"));
        assertTrue(hasMigrationContaining(packageOutput.resolve("mysql"), "course_output_key"));
        assertTrue(hasMigrationContaining(packageOutput.resolve("postgresql"), "-- titan-runtime-version:gap001-m10.4"));
        assertTrue(hasMigrationContaining(packageOutput.resolve("mysql"), "-- titan-runtime-version:gap001-m10.4"));
    }

    @Test
    void documentsPackagingBoundaryForStructuredRuntimeContractObjects() throws Exception {
        Project project = ProjectBuilder.builder().build();
        TitanTranspileTask transpile = project.getTasks()
                .create("gap001StructuredRuntimeBoundaryTranspile", TitanTranspileTask.class);

        SourceFixture source = writeSource("boundary", """
                package demo;

                import titan.dsl.StoredFunction;

                final class CourseRuntimePlan {
                    private final String rootTable;

                    CourseRuntimePlan(String rootTable) {
                        this.rootTable = rootTable;
                    }

                }

                class CourseRuntimePackageBoundary {
                    @StoredFunction
                    public static String coursePayload(int courseId, String viewerRole) {
                        CourseRuntimePlan plan = new CourseRuntimePlan("courses");
                        return "pending-runtime-package:" + courseId + ":" + viewerRole;
                    }
                }
                """);

        transpile.getSourceFiles().from(source.annotationSource().toFile(), source.entryPointSource().toFile());
        transpile.getTargets().set(List.of("postgresql", "mysql"));
        transpile.getOutputDir().set(tempDir.resolve("boundary-generated-sql").toFile());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, transpile::run);
        String message = exception.getMessage();
        assertTrue(message.contains("TITAN-E001"), message);
        assertTrue(message.contains("object or record construction in transpiled code"), message);
        assertTrue(message.contains("requires structured value support"), message);
        assertFalse(Files.exists(tempDir.resolve("boundary-generated-sql/postgresql")));
        assertFalse(Files.exists(tempDir.resolve("boundary-generated-sql/mysql")));
    }

    private SourceFixture writeSource(String name, String entryPointSource) throws Exception {
        Path sourceDir = tempDir.resolve(name + "-src");
        Files.createDirectories(sourceDir.resolve("titan/dsl"));
        Files.createDirectories(sourceDir.resolve("demo"));

        Path annotationSource = sourceDir.resolve("titan/dsl/StoredFunction.java");
        Files.writeString(annotationSource, """
                package titan.dsl;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredFunction {}
                """, StandardCharsets.UTF_8);

        Path entryPoint = sourceDir.resolve("demo/CourseRuntimePackage.java");
        Files.writeString(entryPoint, entryPointSource, StandardCharsets.UTF_8);
        return new SourceFixture(annotationSource, entryPoint);
    }

    private static boolean hasSqlFileContaining(Path dialectDir, String fragment) throws Exception {
        if (!Files.exists(dialectDir)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dialectDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .anyMatch(path -> fileContains(path, fragment));
        }
    }

    private static boolean hasMigrationContaining(Path dialectDir, String fragment) throws Exception {
        return hasSqlFileContaining(dialectDir, fragment);
    }

    private static boolean fileContains(Path path, String fragment) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(fragment);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SourceFixture(Path annotationSource, Path entryPointSource) {
    }
}
