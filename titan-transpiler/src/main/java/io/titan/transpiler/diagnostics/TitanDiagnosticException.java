package io.titan.transpiler.diagnostics;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Failure carrying one or more structured {@link TitanDiagnostic}s.
 *
 * <p>Extends {@link IllegalArgumentException} so existing callers that catch the
 * transpiler's historical exception type keep working; the exception message renders
 * every carried diagnostic, one per line.</p>
 */
public class TitanDiagnosticException extends IllegalArgumentException {

    private final transient List<TitanDiagnostic> diagnostics;

    public TitanDiagnosticException(TitanDiagnostic diagnostic) {
        this(List.of(diagnostic));
    }

    public TitanDiagnosticException(List<TitanDiagnostic> diagnostics) {
        super(renderAll(diagnostics));
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("diagnostics must not be empty");
        }
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<TitanDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String renderAll(List<TitanDiagnostic> diagnostics) {
        return diagnostics.stream()
                .map(TitanDiagnostic::render)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
