package io.titan.runtime.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-test handle to the provisioned database targets.
 *
 * <p>Connections are per-test and exposed through non-closing proxies — closing one in a test
 * is a no-op; the extension owns the real lifecycle and drops the per-test database afterwards
 * (audit R-3).</p>
 */
public final class TitanTestContext {
    private final Map<DatabaseTarget, Connection> connections;
    private final AdditionalConnectionProvider additionalConnectionProvider;

    @FunctionalInterface
    interface AdditionalConnectionProvider {
        Connection open(DatabaseTarget target) throws SQLException;
    }

    TitanTestContext(
            Map<DatabaseTarget, Connection> connections,
            AdditionalConnectionProvider additionalConnectionProvider
    ) {
        this.connections = new EnumMap<>(connections);
        this.additionalConnectionProvider = additionalConnectionProvider;
    }

    public Connection connection(DatabaseTarget target) {
        Connection connection = connections.get(target);
        if (connection == null) {
            throw new IllegalStateException("No connection configured for target " + target);
        }
        return connection;
    }

    public Map<DatabaseTarget, Connection> allConnections() {
        return Map.copyOf(connections);
    }

    public Connection openAdditionalConnection(DatabaseTarget target) throws SQLException {
        connection(target);
        return additionalConnectionProvider.open(target);
    }

    public JavaModeRunner javaMode(DatabaseTarget target) {
        return new JavaModeRunner(connection(target));
    }

    public SqlModeRunner sqlMode(DatabaseTarget target) {
        return new SqlModeRunner(connection(target));
    }

    public EquivalenceModeRunner equivalenceMode(DatabaseTarget target) {
        return new EquivalenceModeRunner(javaMode(target), sqlMode(target));
    }
}
