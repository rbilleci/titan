package io.titan.transpiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Parses Java source files into compiler ASTs and performs symbol/type attribution.
 */
public final class JavaSourceParser {

    public ParsedSources parse(List<Path> sourceFiles, List<Path> classpathEntries) {
        return parse(sourceFiles, classpathEntries, Integer.toString(Runtime.version().feature()), false);
    }

    public ParsedSources parse(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            String sourceLevel,
            boolean enablePreview
    ) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("sourceFiles must not be empty");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available. Ensure a JDK is used.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("--release");
            options.add(sourceLevel);

            if (enablePreview) {
                options.add("--enable-preview");
            }

            if (classpathEntries != null && !classpathEntries.isEmpty()) {
                options.add("-classpath");
                options.add(joinClasspath(classpathEntries));
            }

            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits
            );

            List<CompilationUnitTree> parsedUnits = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsedUnits.add(unit);
            }
            task.analyze();

            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            return new ParsedSources(
                    Collections.unmodifiableList(parsedUnits),
                    diagnostics,
                    trees,
                    elements,
                    types
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Java sources", e);
        }
    }

    private static String joinClasspath(List<Path> classpathEntries) {
        List<String> normalized = classpathEntries.stream()
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .toList();
        return String.join(System.getProperty("path.separator"), normalized);
    }
}
