package io.titan.transpiler.diagnostics;

/**
 * Structured diagnostic with stable code, location, suggestion, and doc reference.
 */
public record TitanDiagnostic(
        TitanErrorCode code,
        String message,
        String location,
        String suggestion,
        String docReference
) {
    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append(code.code())
                .append(": ")
                .append(message);
        if (location != null && !location.isBlank()) {
            builder.append(" at ").append(location);
        }
        builder.append(".");

        if (suggestion != null && !suggestion.isBlank()) {
            builder.append(" Suggestion: ").append(suggestion).append(".");
        }
        if (docReference != null && !docReference.isBlank()) {
            builder.append(" Doc: ").append(docReference).append(".");
        }

        return builder.toString();
    }
}
