package io.titan.transpiler.jdbc;

/**
 * Per-usage and per-method verdict for the JDBC compatibility recognizer
 * (WS-C Phase 1; {@code docs/transpilable-jdbc-subset.md}).
 *
 * <ul>
 *   <li>{@link #TRANSPILABLE} — a recognized in-scope idiom a later phase can lower to TIR.</li>
 *   <li>{@link #PASSTHROUGH} — accepted only as a dialect-pinned {@code RawSql} passthrough
 *       (a permissive-scope, non-result-shaped raw-SQL execute).</li>
 *   <li>{@link #REJECTED} — a hard compile error ({@code TITAN-E001}/{@code TITAN-E004}); the
 *       shape has no faithful lowering or violates the strict raw-SQL guarantee.</li>
 * </ul>
 *
 * <p>The method-level rollup is {@link #REJECTED} if any usage is rejected, else
 * {@link #PASSTHROUGH} if any usage is passthrough, else {@link #TRANSPILABLE}.</p>
 */
public enum JdbcClassification {
    TRANSPILABLE,
    PASSTHROUGH,
    REJECTED
}
