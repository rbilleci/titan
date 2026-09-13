package io.titan.transpiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import java.util.List;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

public record ParsedSources(
        List<CompilationUnitTree> compilationUnits,
        DiagnosticCollector<JavaFileObject> diagnostics,
        Trees trees,
        Elements elements,
        Types types
) {
}
