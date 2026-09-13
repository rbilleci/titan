package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutor;
import io.titan.runtime.jdbc.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.Objects;

/**
 * Java-mode test helper that executes Titan DSL SQL through JDBC against a selected test target.
 *
 * <p>The runner pins the harness-owned connection through a
 * {@link SingleConnectionDataSource}, whose handed-out connections are non-closing — test code
 * (or executor internals) calling {@code close()} cannot kill the per-test connection
 * (audit R-3).</p>
 */
public final class JavaModeRunner {
    private final JdbcExecutor jdbcExecutor;

    JavaModeRunner(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        this.jdbcExecutor = JdbcExecutor.create(new SingleConnectionDataSource(connection));
    }

    public JdbcExecutor jdbc() {
        return jdbcExecutor;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(JdbcExecutor jdbcExecutor) throws Exception;
    }

    public <T> T run(Work<T> work) {
        Objects.requireNonNull(work, "work");
        try {
            return work.run(jdbcExecutor);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Java-mode execution failed", exception);
        }
    }
}
