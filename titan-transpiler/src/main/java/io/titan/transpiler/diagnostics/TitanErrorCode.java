package io.titan.transpiler.diagnostics;

/**
 * Stable transpiler diagnostic codes.
 */
public enum TitanErrorCode {
    E001("TITAN-E001", "Unsupported feature"),
    E002("TITAN-E002", "Entry-point signature mismatch"),
    E003("TITAN-E003", "Null-safety violation"),
    E004("TITAN-E004", "Security violation"),
    E005("TITAN-E005", "View definition conflict"),
    E006("TITAN-E006", "Sensitive data exposure risk"),
    E007("TITAN-E007", "Generated SQL name collision"),
    W001("TITAN-W001", "Raw SQL promotable to DSL"),
    W002("TITAN-W002", "Security definer privilege elevation"),
    W004("TITAN-W004", "Internal helper routine emission"),
    W005("TITAN-W005", "Dialect portability");

    private final String code;
    private final String title;

    TitanErrorCode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }
}
