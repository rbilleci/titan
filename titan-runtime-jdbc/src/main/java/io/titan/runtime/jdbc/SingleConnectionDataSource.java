package io.titan.runtime.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A {@link DataSource} pinned to one externally-owned {@link Connection}.
 *
 * <p>{@link #getConnection()} hands out a {@linkplain NonClosingConnection non-closing} view of
 * the pinned connection, so executor code written against the
 * acquire–use–close idiom works unchanged while the true lifecycle stays with the owner
 * ({@link JdbcExecutor#inTransaction}, the test harness's per-test connections).</p>
 */
public final class SingleConnectionDataSource implements DataSource {

    private final Connection connection;

    public SingleConnectionDataSource(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Connection getConnection() {
        return NonClosingConnection.wrap(connection);
    }

    @Override
    public Connection getConnection(String username, String password) {
        return NonClosingConnection.wrap(connection);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
