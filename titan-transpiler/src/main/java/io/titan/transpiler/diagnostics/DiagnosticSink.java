package io.titan.transpiler.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects structured diagnostics across transpiler phases so independent failures can be
 * reported together instead of aborting at the first error.
 */
public final class DiagnosticSink {

    public enum Severity {
        ERROR,
        WARNING
    }

    private final List<TitanDiagnostic> errors = new ArrayList<>();
    private final List<TitanDiagnostic> warnings = new ArrayList<>();

    public void error(TitanDiagnostic diagnostic) {
        add(Severity.ERROR, diagnostic);
    }

    public void warning(TitanDiagnostic diagnostic) {
        add(Severity.WARNING, diagnostic);
    }

    public void add(Severity severity, TitanDiagnostic diagnostic) {
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (diagnostic == null) {
            throw new IllegalArgumentException("diagnostic must not be null");
        }
        (severity == Severity.ERROR ? errors : warnings).add(diagnostic);
    }

    public List<TitanDiagnostic> errors() {
        return List.copyOf(errors);
    }

    public List<TitanDiagnostic> warnings() {
        return List.copyOf(warnings);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * Throws one aggregated {@link TitanDiagnosticException} rendering every collected error.
     * No-op when no errors were recorded.
     */
    public void failIfErrors() {
        if (hasErrors()) {
            throw new TitanDiagnosticException(errors());
        }
    }
}
