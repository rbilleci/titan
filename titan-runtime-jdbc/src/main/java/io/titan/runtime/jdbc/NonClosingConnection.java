package io.titan.runtime.jdbc;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Objects;

/**
 * Wraps a {@link Connection} in a proxy whose {@code close()} is a no-op.
 *
 * <p>Used wherever a connection's lifecycle is owned by someone other than the code receiving
 * it: {@link JdbcExecutor#inTransaction} pins one connection across the transactional work,
 * and the Titan test harness injects connections into tests that must not be able to kill the
 * harness's per-test connection (audit R-3). The owner closes the underlying connection.</p>
 */
public final class NonClosingConnection {

    private NonClosingConnection() {
    }

    public static Connection wrap(Connection delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }
}
