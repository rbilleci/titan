package io.titan.runtime.testing;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * JVM-singleton database containers for {@link TitanTestExtension} (audit R-4).
 *
 * <p>One PostgreSQL and one MySQL container serve every {@code @TitanTest} class in the JVM:
 * containers start lazily on first use and are stopped exactly once when the JUnit run ends.
 * Host ports are random Docker-mapped ports — the previous fixed-port scheme probed a free port
 * with a {@code ServerSocket} and then raced anything else on the machine for it (TOCTOU) via
 * the deprecated {@code addFixedExposedPort}.</p>
 *
 * <p><b>Leak-proofing:</b> the holder is registered in the JUnit root store as a closeable
 * resource <em>before</em> any container starts, and each container field is assigned before
 * {@code start()} is invoked — if startup fails partway (e.g. PostgreSQL up, MySQL boot fails),
 * {@link #close()} still stops whatever came up. Testcontainers' Ryuk reaper remains the
 * backstop for hard JVM death.</p>
 */
final class SharedContainers implements ExtensionContext.Store.CloseableResource {

    static final String POSTGRES_HOST_PORT_ENV = "TITAN_TEST_POSTGRES_HOST_PORT";
    static final String MYSQL_HOST_PORT_ENV = "TITAN_TEST_MYSQL_HOST_PORT";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SharedContainers.class);
    private static final String STORE_KEY = "titan-shared-containers";

    private PostgreSQLContainer<?> postgres;
    private MySQLContainer<?> mysql;

    private SharedContainers() {
    }

    /**
     * Returns the JVM-wide holder, registering it for cleanup in the JUnit root store before
     * any container has started.
     */
    static SharedContainers fromRootStore(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(STORE_KEY, key -> new SharedContainers(), SharedContainers.class);
    }

    synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null || !postgres.isRunning()) {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16")
                    .withStartupTimeout(Duration.ofMinutes(5));
            applyOptionalHostPort(container, POSTGRES_HOST_PORT_ENV, 5432);
            postgres = container; // assigned before start: close() can stop a partial start
            container.start();
        }
        return postgres;
    }

    synchronized MySQLContainer<?> mysql() {
        if (mysql == null || !mysql.isRunning()) {
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                    // Generated, package-bound routines are intentionally deployed as complete
                    // CREATE PROCEDURE statements. Keep the shared test server above the legacy
                    // 1 MiB packet default and the former 16 MiB test ceiling so current package
                    // closures are exercised rather than rejected by the JDBC transport before
                    // MySQL parses them. Production installation still uses the package-sized
                    // preflight and fails before SQL when its server is configured too low.
                    .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0",
                            "--max_allowed_packet=64M")
                    .withStartupTimeout(Duration.ofMinutes(5));
            applyOptionalHostPort(container, MYSQL_HOST_PORT_ENV, 3306);
            mysql = container; // assigned before start: close() can stop a partial start
            container.start();
            provisionMysqlRuntimeSchema(container);
        }
        return mysql;
    }

    /**
     * Allows a caller to avoid Docker's random-port allocator when the host's ephemeral source
     * ports are under sustained load. The normal path remains Docker-assigned; an override is
     * deliberately explicit because the caller is then responsible for choosing a free port.
     */
    private static void applyOptionalHostPort(
            GenericContainer<?> container,
            String environmentVariable,
            int containerPort
    ) {
        String configured = System.getenv(environmentVariable);
        if (configured == null || configured.isBlank()) {
            return;
        }
        int hostPort;
        try {
            hostPort = Integer.parseInt(configured);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(environmentVariable + " must be an integer TCP port", ex);
        }
        if (hostPort < 1 || hostPort > 65_535) {
            throw new IllegalArgumentException(environmentVariable + " must be between 1 and 65535");
        }
        container.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", hostPort),
                        ExposedPort.tcp(containerPort))));
    }

    /**
     * MySQL schemas are databases, so the fixed {@code titan_runtime} schema (which hosts the
     * telemetry table referenced by generated SQL and the runtime migrations) is server-global.
     * Create it once at container start; per-test isolation for its telemetry table is handled
     * by {@link TitanTestExtension} (truncated during each test's provisioning).
     */
    private static void provisionMysqlRuntimeSchema(MySQLContainer<?> container) {
        try (Connection connection = openMysqlRoot(container, "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS titan_runtime");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision MySQL titan_runtime schema", ex);
        }
    }

    String postgresUrl(String databaseName) {
        PostgreSQLContainer<?> container = postgres();
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + databaseName;
    }

    String mysqlUrl(String databaseName) {
        MySQLContainer<?> container = mysql();
        return "jdbc:mysql://" + container.getHost() + ":"
                + container.getMappedPort(3306) + "/" + databaseName;
    }

    /** Admin connection to the PostgreSQL maintenance database (CREATE/DROP DATABASE). */
    Connection openPostgresAdmin() throws SQLException {
        PostgreSQLContainer<?> container = postgres();
        return DriverManager.getConnection(
                postgresUrl(container.getDatabaseName()), container.getUsername(), container.getPassword());
    }

    /** Per-test-database PostgreSQL connection (the container user is a superuser). */
    Connection openPostgres(String databaseName) throws SQLException {
        PostgreSQLContainer<?> container = postgres();
        return DriverManager.getConnection(
                postgresUrl(databaseName), container.getUsername(), container.getPassword());
    }

    /**
     * Root MySQL connection ({@code MYSQL_ROOT_PASSWORD} equals the container password): test
     * databases are created/dropped per test, which the default scoped-grant user cannot do.
     */
    Connection openMysqlRoot(String databaseName) throws SQLException {
        return openMysqlRoot(mysql(), databaseName);
    }

    private static Connection openMysqlRoot(MySQLContainer<?> container, String databaseName) throws SQLException {
        String url = "jdbc:mysql://" + container.getHost() + ":"
                + container.getMappedPort(3306) + "/" + databaseName;
        return DriverManager.getConnection(url, "root", container.getPassword());
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        if (postgres != null) {
            try {
                postgres.stop();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            postgres = null;
        }
        if (mysql != null) {
            try {
                mysql.stop();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            mysql = null;
        }
        if (failure != null) {
            throw failure;
        }
    }
}
