package io.titan.runtime.testing;

import io.titan.runtime.jdbc.NonClosingConnection;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JUnit extension behind {@link TitanTest} (audit R-3/R-4 rework).
 *
 * <p><b>Containers</b> are JVM singletons with random Docker-mapped ports
 * ({@link SharedContainers}), started lazily and registered for cleanup in the JUnit root store
 * before they start — no per-class container boots, no fixed-port races, no leaks on partial
 * startup.</p>
 *
 * <p><b>Isolation</b> is per test, commit-proof, and identical for both dialects: every test
 * gets a freshly created database per target (schema SQL plus the Titan runtime migration
 * applied), and the database is dropped after the test. Unlike the previous rollback-only
 * scheme, DDL or explicit commits inside a test — which MySQL auto-commits — cannot leak into
 * the next test. The fixed server-global MySQL {@code titan_runtime.telemetry} table is
 * truncated during each test's provisioning.</p>
 *
 * <p><b>Connections</b> are opened per test and injected through non-closing proxies, so test
 * code calling {@code close()} cannot break the harness. Provisioning and teardown failures
 * propagate — a reset error fails the test instead of silently corrupting later ones.</p>
 */
public final class TitanTestExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TitanTestExtension.class);
    private static final String ENVIRONMENT_KEY = "titan-test-environment";
    private static final AtomicLong DATABASE_COUNTER = new AtomicLong();

    @Override
    public void beforeAll(ExtensionContext context) {
        if (annotation(context) == null) {
            throw new IllegalStateException("@TitanTest is required for TitanTestExtension");
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        TitanTest annotation = annotation(context);
        SharedContainers containers = SharedContainers.fromRootStore(context);
        PerTestEnvironment environment =
                PerTestEnvironment.provision(containers, annotation.targets(), annotation.schemaSql());
        context.getStore(NAMESPACE).put(ENVIRONMENT_KEY, environment);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        PerTestEnvironment environment =
                context.getStore(NAMESPACE).remove(ENVIRONMENT_KEY, PerTestEnvironment.class);
        if (environment != null) {
            environment.close(); // fails loudly: a broken reset must not poison later tests
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type.equals(TitanTestContext.class) || type.equals(Connection.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        PerTestEnvironment environment = environment(extensionContext);
        if (type.equals(TitanTestContext.class)) {
            return environment.context();
        }
        if (type.equals(Connection.class)) {
            if (environment.context().allConnections().size() != 1) {
                throw new ParameterResolutionException(
                        "Connection injection requires a single database target; inject TitanTestContext for multi-target tests.");
            }
            return environment.context().allConnections().values().iterator().next();
        }
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }

    private static TitanTest annotation(ExtensionContext context) {
        return context.getRequiredTestClass().getAnnotation(TitanTest.class);
    }

    private PerTestEnvironment environment(ExtensionContext context) {
        PerTestEnvironment environment =
                context.getStore(NAMESPACE).get(ENVIRONMENT_KEY, PerTestEnvironment.class);
        if (environment == null) {
            throw new IllegalStateException("Titan test environment was not initialized");
        }
        return environment;
    }

    /** Per-test databases and connections for the targets a test class declares. */
    private static final class PerTestEnvironment {
        private final SharedContainers containers;
        private final Map<DatabaseTarget, String> databaseNames = new EnumMap<>(DatabaseTarget.class);
        private final Map<DatabaseTarget, Connection> realConnections = new EnumMap<>(DatabaseTarget.class);
        private TitanTestContext context;

        private PerTestEnvironment(SharedContainers containers) {
            this.containers = containers;
        }

        static PerTestEnvironment provision(
                SharedContainers containers, DatabaseTarget[] targets, String[] schemaSql) throws SQLException {
            PerTestEnvironment environment = new PerTestEnvironment(containers);
            try {
                for (DatabaseTarget target : targets) {
                    environment.provisionTarget(target, schemaSql);
                }
            } catch (SQLException | RuntimeException ex) {
                try {
                    environment.close();
                } catch (Exception cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
                throw ex;
            }
            Map<DatabaseTarget, Connection> proxies = new EnumMap<>(DatabaseTarget.class);
            environment.realConnections.forEach(
                    (target, connection) -> proxies.put(target, NonClosingConnection.wrap(connection)));
            environment.context = new TitanTestContext(proxies);
            return environment;
        }

        TitanTestContext context() {
            return context;
        }

        private void provisionTarget(DatabaseTarget target, String[] schemaSql) throws SQLException {
            String databaseName = "titan_test_" + DATABASE_COUNTER.incrementAndGet();
            Connection connection;
            if (target == DatabaseTarget.POSTGRESQL) {
                try (Connection admin = containers.openPostgresAdmin();
                     Statement statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE " + databaseName);
                }
                databaseNames.put(target, databaseName);
                connection = containers.openPostgres(databaseName);
            } else {
                try (Connection admin = containers.openMysqlRoot("");
                     Statement statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE " + databaseName);
                }
                databaseNames.put(target, databaseName);
                connection = containers.openMysqlRoot(databaseName);
            }
            realConnections.put(target, connection);
            applyBaseSchemaAndRuntime(connection, target, schemaSql);
        }

        private static void applyBaseSchemaAndRuntime(
                Connection connection, DatabaseTarget target, String[] schemaSqlScripts) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS titan_test_probe(id INT PRIMARY KEY)");
            }
            for (String schemaSql : schemaSqlScripts) {
                for (String sql : SqlScripts.splitStatements(schemaSql)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    } catch (SQLException ex) {
                        throw new SQLException("Failed schema migration statement:\n" + sql, ex);
                    }
                }
            }
            for (String sql : SqlScripts.splitStatements(RuntimeMigrations.forTarget(target))) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException ex) {
                    throw new SQLException("Failed runtime migration statement:\n" + sql, ex);
                }
            }
            if (target == DatabaseTarget.MYSQL) {
                // The MySQL titan_runtime schema is server-global (schemas are databases):
                // truncate its telemetry table so telemetry from earlier tests never leaks in.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("TRUNCATE TABLE titan_runtime.telemetry");
                }
            }
        }

        /**
         * Closes the per-test connections and drops the per-test databases. Failures propagate
         * (first failure thrown, the rest suppressed) — never swallowed.
         */
        void close() throws SQLException {
            SQLException failure = null;
            for (Map.Entry<DatabaseTarget, Connection> entry : realConnections.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (SQLException ex) {
                    failure = chain(failure, ex);
                }
            }
            realConnections.clear();
            for (Map.Entry<DatabaseTarget, String> entry : databaseNames.entrySet()) {
                try {
                    dropDatabase(entry.getKey(), entry.getValue());
                } catch (SQLException ex) {
                    failure = chain(failure, ex);
                }
            }
            databaseNames.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private void dropDatabase(DatabaseTarget target, String databaseName) throws SQLException {
            if (target == DatabaseTarget.POSTGRESQL) {
                try (Connection admin = containers.openPostgresAdmin();
                     Statement statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
                }
            } else {
                try (Connection admin = containers.openMysqlRoot("");
                     Statement statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS " + databaseName);
                }
            }
        }

        private static SQLException chain(SQLException failure, SQLException next) {
            if (failure == null) {
                return next;
            }
            failure.addSuppressed(next);
            return failure;
        }
    }
}
