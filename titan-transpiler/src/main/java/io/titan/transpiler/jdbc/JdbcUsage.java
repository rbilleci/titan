package io.titan.transpiler.jdbc;

import io.titan.transpiler.diagnostics.TitanErrorCode;

/**
 * One recognized JDBC usage inside a method: the idiom matched, its verdict, where it occurred,
 * and (for non-TRANSPILABLE verdicts) the diagnostic code and a human reason.
 *
 * @param idiom          the matched {@link JdbcIdiom}
 * @param classification the verdict for this single usage
 * @param location       {@code file:line} of the driving AST node (never null/blank)
 * @param code           the diagnostic code for a reject/passthrough, or {@code null} when the
 *                       usage is cleanly TRANSPILABLE (no diagnostic)
 * @param reason         a short human explanation for a reject/passthrough, or {@code null}
 */
public record JdbcUsage(
        JdbcIdiom idiom,
        JdbcClassification classification,
        String location,
        TitanErrorCode code,
        String reason
) {
    public JdbcUsage {
        if (idiom == null) {
            throw new IllegalArgumentException("idiom must not be null");
        }
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location must not be null/blank");
        }
    }

    /** A clean TRANSPILABLE usage with no diagnostic. */
    public static JdbcUsage transpilable(JdbcIdiom idiom, String location) {
        return new JdbcUsage(idiom, JdbcClassification.TRANSPILABLE, location, null, null);
    }

    /** A PASSTHROUGH usage (permissive-scope raw SQL). */
    public static JdbcUsage passthrough(JdbcIdiom idiom, String location, TitanErrorCode code, String reason) {
        return new JdbcUsage(idiom, JdbcClassification.PASSTHROUGH, location, code, reason);
    }

    /** A REJECTED usage carrying its diagnostic code and reason. */
    public static JdbcUsage rejected(JdbcIdiom idiom, String location, TitanErrorCode code, String reason) {
        return new JdbcUsage(idiom, JdbcClassification.REJECTED, location, code, reason);
    }
}
