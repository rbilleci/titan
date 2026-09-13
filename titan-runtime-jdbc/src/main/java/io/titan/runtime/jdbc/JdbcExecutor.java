package io.titan.runtime.jdbc;

import titan.dsl.BindValue;
import titan.dsl.DeleteBuilder;
import titan.dsl.InsertBuilder;
import titan.dsl.ParameterizedSql;
import titan.dsl.SelectBuilder;
import titan.dsl.SqlDialect;
import titan.dsl.UpdateBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Optional runtime adapter for executing Titan DSL-generated SQL over JDBC.
 *
 * <p>DSL statements are rendered in parameterized mode and values are bound via
 * {@link PreparedStatement#setObject} (audit findings D-1/R-1). The target dialect is resolved
 * once at construction — either passed explicitly or sniffed from connection metadata by
 * {@link #create(DataSource)} — and threaded through every builder execute path (audit finding
 * R-2).</p>
 *
 * <p>Production features (audit R-6): {@link #inTransaction(Function)} for multi-statement
 * atomicity on one pinned connection, {@link #executeBatch(List)} for multi-row inserts,
 * {@link #fetchStreaming} for cursor-based reads of large result sets,
 * {@link #withQueryTimeout(Duration)} for per-statement timeouts, and
 * {@link #withListener(TitanExecutionListener)} for execution observability (placeholder SQL
 * only — never bound values, audit R-7).</p>
 */
public final class JdbcExecutor {

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final TitanExecutionListener listener;
    private final int queryTimeoutSeconds;

    public JdbcExecutor(DataSource dataSource, SqlDialect dialect) {
        this(dataSource, dialect, TitanExecutionListener.noop(), 0);
    }

    private JdbcExecutor(DataSource dataSource, SqlDialect dialect,
                         TitanExecutionListener listener, int queryTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * Metadata-sniffing factory: opens one connection to read the database product name and
     * resolves the dialect once for the executor's lifetime.
     */
    public static JdbcExecutor create(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return new JdbcExecutor(dataSource, sniffDialect(connection));
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to resolve SQL dialect from connection metadata", ex, null);
        }
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * Returns a copy of this executor that reports every execution to {@code listener}
     * ({@link JdbcTelemetrySink} writes them to {@code titan_runtime.telemetry}).
     */
    public JdbcExecutor withListener(TitanExecutionListener listener) {
        return new JdbcExecutor(dataSource, dialect, listener, queryTimeoutSeconds);
    }

    /**
     * Returns a copy of this executor that applies {@code timeout} (rounded up to whole seconds,
     * the JDBC granularity) to every statement via {@link Statement#setQueryTimeout(int)}.
     * {@link Duration#ZERO} disables the timeout.
     */
    public JdbcExecutor withQueryTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Query timeout must not be negative: " + timeout);
        }
        long seconds = timeout.getSeconds() + (timeout.getNano() > 0 ? 1 : 0);
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Query timeout too large: " + timeout);
        }
        return new JdbcExecutor(dataSource, dialect, listener, (int) seconds);
    }

    /**
     * Runs {@code work} inside one database transaction on a single pinned connection.
     *
     * <p>The executor handed to {@code work} routes every statement through that connection
     * (its {@code close()} is a no-op for the duration). If {@code work} returns normally the
     * transaction commits; if it throws, the transaction rolls back and the exception is
     * rethrown (rollback/restore failures ride along as suppressed exceptions — never
     * swallowed). The connection's original auto-commit mode is restored before it is
     * released.</p>
     */
    public <T> T inTransaction(Function<JdbcExecutor, T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            T result;
            try {
                result = work.apply(new JdbcExecutor(
                        new SingleConnectionDataSource(connection), dialect, listener, queryTimeoutSeconds));
                connection.commit();
            } catch (RuntimeException | Error ex) {
                rollbackAndRestoreSuppressing(connection, originalAutoCommit, ex);
                throw ex;
            } catch (SQLException commitFailure) {
                JdbcExecutionException failure =
                        new JdbcExecutionException("Failed to commit transaction", commitFailure, null);
                rollbackAndRestoreSuppressing(connection, originalAutoCommit, failure);
                throw failure;
            }
            connection.setAutoCommit(originalAutoCommit);
            return result;
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to execute transactional work", ex, null);
        }
    }

    private static void rollbackAndRestoreSuppressing(
            Connection connection, boolean originalAutoCommit, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException restoreFailure) {
            primary.addSuppressed(restoreFailure);
        }
    }

    public <T> List<T> fetch(SelectBuilder selectBuilder, RowMapper<T> rowMapper) {
        Objects.requireNonNull(selectBuilder, "selectBuilder");
        Objects.requireNonNull(rowMapper, "rowMapper");
        ParameterizedSql rendered = selectBuilder.render(dialect);
        String sql = rendered.sql();
        return observed(sql, "Failed to execute query SQL", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyQueryTimeout(statement);
                bind(statement, rendered.parameters());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return mapRows(resultSet, rowMapper);
                }
            }
        });
    }

    /**
     * Streaming fetch with an explicit lifecycle: rows are pushed to {@code rowConsumer} one at
     * a time as the driver fetches them, and every JDBC resource is released before this method
     * returns. Use for result sets too large to materialize via {@link #fetch}.
     *
     * <p><b>Dialect notes.</b> PostgreSQL only streams when the statement runs inside a
     * transaction with a positive fetch size, so when the connection is in auto-commit mode this
     * method wraps the read in a short transaction (committed on success) — server-side cursors
     * do not survive commits, so the cursor lives exactly as long as this call. MySQL
     * Connector/J ignores positive fetch sizes unless the JDBC URL sets
     * {@code useCursorFetch=true}; without it, pass {@link Integer#MIN_VALUE} as
     * {@code fetchSize} to enable the driver's row-by-row streaming mode. In all cases results
     * are correct — the fetch size only governs memory behavior.</p>
     *
     * @param fetchSize rows per driver round-trip; must be positive, or
     *                  {@link Integer#MIN_VALUE} for MySQL row-streaming mode
     */
    public <T> void fetchStreaming(SelectBuilder selectBuilder, int fetchSize,
                                   RowMapper<T> rowMapper, Consumer<? super T> rowConsumer) {
        Objects.requireNonNull(selectBuilder, "selectBuilder");
        Objects.requireNonNull(rowMapper, "rowMapper");
        Objects.requireNonNull(rowConsumer, "rowConsumer");
        if (fetchSize <= 0 && fetchSize != Integer.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "fetchSize must be positive (or Integer.MIN_VALUE for MySQL row streaming): " + fetchSize);
        }
        ParameterizedSql rendered = selectBuilder.render(dialect);
        String sql = rendered.sql();
        observed(sql, "Failed to execute streaming query SQL", () -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean cursorTransaction = dialect == SqlDialect.POSTGRESQL && connection.getAutoCommit();
                if (!cursorTransaction) {
                    streamRows(connection, rendered, fetchSize, rowMapper, rowConsumer);
                    return null;
                }
                connection.setAutoCommit(false);
                try {
                    streamRows(connection, rendered, fetchSize, rowMapper, rowConsumer);
                    connection.commit();
                } catch (SQLException | RuntimeException ex) {
                    rollbackAndRestoreSuppressing(connection, true, ex);
                    throw ex;
                }
                connection.setAutoCommit(true);
                return null;
            }
        });
    }

    private <T> void streamRows(Connection connection, ParameterizedSql rendered, int fetchSize,
                                RowMapper<T> rowMapper, Consumer<? super T> rowConsumer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(rendered.sql())) {
            applyQueryTimeout(statement);
            statement.setFetchSize(fetchSize);
            bind(statement, rendered.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rowConsumer.accept(rowMapper.map(resultSet));
                }
            }
        }
    }

    public <T> List<T> fetchSql(String sql, RowMapper<T> rowMapper) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(rowMapper, "rowMapper");
        return observed(sql, "Failed to execute query SQL", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyQueryTimeout(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return mapRows(resultSet, rowMapper);
                }
            }
        });
    }

    public int execute(InsertBuilder insertBuilder) {
        Objects.requireNonNull(insertBuilder, "insertBuilder");
        ParameterizedSql rendered = insertBuilder.render(dialect);
        return executeRendered(rendered);
    }

    /**
     * Executes the same insert statement shape for every builder in one JDBC batch
     * ({@code addBatch}/{@code executeBatch}) — the multi-row insert path (audit R-6).
     *
     * <p>All builders must render to the identical SQL text (same table, same column set in the
     * same order); only the bound values may differ. A heterogeneous list is rejected before
     * anything executes.</p>
     *
     * @return per-row update counts as reported by the driver
     */
    public int[] executeBatch(List<InsertBuilder> insertBuilders) {
        Objects.requireNonNull(insertBuilders, "insertBuilders");
        if (insertBuilders.isEmpty()) {
            return new int[0];
        }
        List<ParameterizedSql> rendered = new ArrayList<>(insertBuilders.size());
        for (InsertBuilder insertBuilder : insertBuilders) {
            rendered.add(Objects.requireNonNull(insertBuilder, "insertBuilders element").render(dialect));
        }
        String sql = rendered.get(0).sql();
        for (int i = 1; i < rendered.size(); i++) {
            if (!sql.equals(rendered.get(i).sql())) {
                throw new IllegalArgumentException(
                        "Batch inserts must share one statement shape (same table and column set):"
                                + "\n  insert 0 renders: " + sql
                                + "\n  insert " + i + " renders: " + rendered.get(i).sql());
            }
        }
        return observed(sql, "Failed to execute batch insert SQL", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyQueryTimeout(statement);
                for (ParameterizedSql row : rendered) {
                    bind(statement, row.parameters());
                    statement.addBatch();
                }
                return statement.executeBatch();
            }
        });
    }

    /**
     * Executes an insert with {@code returning(...)} columns and maps the returned rows.
     *
     * <p>On PostgreSQL the statement carries a {@code RETURNING} clause and is executed as a
     * query. On MySQL the generated key is retrieved via
     * {@link Statement#RETURN_GENERATED_KEYS} — no second statement is spliced into the SQL
     * text (audit finding D-4).</p>
     */
    public <T> List<T> executeReturning(InsertBuilder insertBuilder, RowMapper<T> rowMapper) {
        Objects.requireNonNull(insertBuilder, "insertBuilder");
        Objects.requireNonNull(rowMapper, "rowMapper");
        if (insertBuilder.returningColumns().isEmpty()) {
            throw new IllegalArgumentException("executeReturning requires returning(...) columns on the insert");
        }
        ParameterizedSql rendered = insertBuilder.render(dialect);
        String sql = rendered.sql();
        return observed(sql, "Failed to execute insert returning SQL", () -> {
            try (Connection connection = dataSource.getConnection()) {
                if (dialect == SqlDialect.MYSQL) {
                    try (PreparedStatement statement =
                                 connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        applyQueryTimeout(statement);
                        bind(statement, rendered.parameters());
                        statement.executeUpdate();
                        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                            return mapRows(generatedKeys, rowMapper);
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    applyQueryTimeout(statement);
                    bind(statement, rendered.parameters());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return mapRows(resultSet, rowMapper);
                    }
                }
            }
        });
    }

    public int execute(UpdateBuilder updateBuilder) {
        Objects.requireNonNull(updateBuilder, "updateBuilder");
        ParameterizedSql rendered = updateBuilder.render(dialect);
        return executeRendered(rendered);
    }

    public int execute(DeleteBuilder deleteBuilder) {
        Objects.requireNonNull(deleteBuilder, "deleteBuilder");
        ParameterizedSql rendered = deleteBuilder.render(dialect);
        return executeRendered(rendered);
    }

    public int executeSql(String sql) {
        Objects.requireNonNull(sql, "sql");
        return observed(sql, "Failed to execute update SQL", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyQueryTimeout(statement);
                return statement.executeUpdate();
            }
        });
    }

    private int executeRendered(ParameterizedSql rendered) {
        String sql = rendered.sql();
        return observed(sql, "Failed to execute update SQL", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                applyQueryTimeout(statement);
                bind(statement, rendered.parameters());
                return statement.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    /**
     * Runs one statement-shaped action, timing it and reporting the outcome to the listener.
     * Listener failures are never swallowed: they propagate when the action succeeded and are
     * attached as suppressed exceptions when the action itself failed (audit R-6/R-7).
     */
    private <T> T observed(String sqlWithPlaceholders, String failureMessage, SqlAction<T> action) {
        long startNanos = System.nanoTime();
        T result;
        try {
            result = action.run();
        } catch (SQLException ex) {
            JdbcExecutionException failure = new JdbcExecutionException(failureMessage, ex, sqlWithPlaceholders);
            notify(sqlWithPlaceholders, startNanos, TitanExecutionListener.Outcome.FAILURE, failure);
            throw failure;
        } catch (RuntimeException ex) {
            notify(sqlWithPlaceholders, startNanos, TitanExecutionListener.Outcome.FAILURE, ex);
            throw ex;
        }
        // Outside the try: a listener failure on the success path must propagate as itself,
        // never re-enter the failure handling as its own "primary" exception.
        notify(sqlWithPlaceholders, startNanos, TitanExecutionListener.Outcome.SUCCESS, null);
        return result;
    }

    private void notify(String sqlWithPlaceholders, long startNanos,
                        TitanExecutionListener.Outcome outcome, Throwable primaryFailure) {
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        try {
            listener.onExecute(sqlWithPlaceholders, duration, outcome);
        } catch (RuntimeException listenerFailure) {
            if (primaryFailure == null) {
                throw listenerFailure;
            }
            primaryFailure.addSuppressed(listenerFailure);
        }
    }

    private void applyQueryTimeout(Statement statement) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            statement.setQueryTimeout(queryTimeoutSeconds);
        }
    }

    private static <T> List<T> mapRows(ResultSet resultSet, RowMapper<T> rowMapper) throws SQLException {
        List<T> rows = new ArrayList<>();
        while (resultSet.next()) {
            rows.add(rowMapper.map(resultSet));
        }
        return rows;
    }

    private void bind(PreparedStatement statement, List<BindValue> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, toJdbcValue(parameters.get(i).value()));
        }
    }

    /**
     * Normalizes captured values to types both drivers bind reliably; everything else passes
     * through to {@code setObject} untouched.
     */
    private Object toJdbcValue(Object value) {
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return Timestamp.from(offsetDateTime.toInstant());
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return Timestamp.from(zonedDateTime.toInstant());
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof UUID uuid && dialect == SqlDialect.MYSQL) {
            return uuid.toString();
        }
        return value;
    }

    private static SqlDialect sniffDialect(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        if (productName != null && productName.toLowerCase().contains("mysql")) {
            return SqlDialect.MYSQL;
        }
        return SqlDialect.POSTGRESQL;
    }
}
