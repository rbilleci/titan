package io.titan.runtime.jdbc;

import java.time.Duration;

/**
 * Observability SPI for {@link JdbcExecutor} (audit R-6/R-7).
 *
 * <p>The executor reports every statement execution — queries, DML, batches, streaming fetches —
 * with the SQL <em>as prepared</em>, the wall-clock duration, and the outcome. The SQL passed to
 * {@link #onExecute} contains {@code ?} placeholders for every bound value; <b>bound parameter
 * values are never part of the event</b> (audit R-7: no runtime values into logs/telemetry).
 * Raw-SQL convenience paths ({@code fetchSql}/{@code executeSql}) report the caller-supplied
 * text verbatim — callers of those paths own placeholder discipline.</p>
 *
 * <p>Listener failures are never swallowed: if the listener throws while the statement
 * succeeded, the listener's exception propagates; if the statement itself failed, the listener's
 * exception is attached as suppressed to the primary failure.</p>
 */
@FunctionalInterface
public interface TitanExecutionListener {

    /** Statement execution outcome. */
    enum Outcome {
        SUCCESS,
        FAILURE
    }

    /**
     * Called after every statement execution.
     *
     * @param sqlWithPlaceholders the executed SQL with {@code ?} placeholders — never bound values
     * @param duration            wall-clock execution duration
     * @param outcome             whether the execution succeeded
     */
    void onExecute(String sqlWithPlaceholders, Duration duration, Outcome outcome);

    /** The default listener: does nothing. */
    static TitanExecutionListener noop() {
        return (sql, duration, outcome) -> {
        };
    }
}
