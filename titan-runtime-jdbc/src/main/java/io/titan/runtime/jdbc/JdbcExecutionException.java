package io.titan.runtime.jdbc;

/**
 * Wraps {@link java.sql.SQLException}s raised while executing Titan DSL SQL.
 *
 * <p>The SQL text that actually failed is available via {@link #sql()} but is deliberately kept
 * out of {@link #getMessage()} so statement text (which may embed sensitive values) does not leak
 * into logs by default (audit finding R-7).</p>
 */
public final class JdbcExecutionException extends RuntimeException {

    private final transient String sql;

    public JdbcExecutionException(String message, Throwable cause, String sql) {
        super(message, cause);
        this.sql = sql;
    }

    /** The SQL text that was being executed when the failure occurred. */
    public String sql() {
        return sql;
    }
}
