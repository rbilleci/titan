package io.titan.gradle;

import io.titan.transpiler.tir.DialectId;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Scratch verification databases for {@code titanVerifyInstall} (plan 4.4): one throwaway
 * container per packaged dialect, driven as a generic container (mirroring the container-backed
 * DDL introspection from audit G-6/G-7) with JDBC drivers supplied by the caller — the plugin
 * resolves them from the {@code titanJdbc} configuration, tests fall back to drivers already
 * registered with {@code DriverManager}.
 */
final class TitanScratchDatabases {

    private static final String POSTGRES_IMAGE = "postgres:16";
    private static final String MYSQL_IMAGE = "mysql:8.4";
    private static final String SCRATCH_USER = "titan";
    private static final String SCRATCH_PASSWORD = "titan";
    private static final String SCRATCH_DATABASE = "titan";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration CONNECT_RETRY_INTERVAL = Duration.ofMillis(500);

    private TitanScratchDatabases() {
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static String dockerUnavailableMessage() {
        return "titanVerifyInstall in scratch-container mode requires a working Docker environment, but none "
                + "was detected.\nEither start Docker (or point DOCKER_HOST/Testcontainers at a reachable "
                + "daemon), or verify against a configured database instead:\n\n"
                + "    titan {\n"
                + "        verification.jdbcUrl = \"jdbc:postgresql://...\"\n"
                + "        verification.dialect = \"postgresql\"\n"
                + "    }\n";
    }

    /** A running scratch container plus an open JDBC session; closing releases both. */
    static final class Scratch implements AutoCloseable {
        private final GenericContainer<?> container;
        private final TitanJdbcConnections.JdbcSession session;
        private final String dialect;
        private final String version;

        private Scratch(GenericContainer<?> container, TitanJdbcConnections.JdbcSession session, String dialect, String version) {
            this.container = container;
            this.session = session;
            this.dialect = dialect;
            this.version = version;
        }

        Connection connection() {
            return session.connection();
        }

        String dialect() {
            return dialect;
        }

        String version() {
            return version;
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (Exception ignored) {
                // The container is discarded right after; nothing actionable.
            } finally {
                container.stop();
            }
        }
    }

    static Scratch start(String dialect, Collection<File> driverClasspath) throws Exception {
        DialectId dialectId = DialectId.parse(dialect)
                .orElseThrow(() -> new IllegalArgumentException("No scratch container image for dialect: " + dialect));
        GenericContainer<?> container = createContainer(dialectId);
        container.start();
        try {
            String jdbcUrl = jdbcUrl(dialectId, container);
            String username = dialectId == DialectId.MYSQL ? "root" : SCRATCH_USER;
            TitanJdbcConnections.JdbcSession session = openWithRetry(driverClasspath, jdbcUrl, username);
            String version = dialectId == DialectId.POSTGRESQL ? POSTGRES_IMAGE : MYSQL_IMAGE;
            return new Scratch(container, session, dialect, version);
        } catch (Exception e) {
            container.stop();
            throw e;
        }
    }

    /**
     * Creates the schemas (PostgreSQL) / databases (MySQL) the inventory's objects install
     * into. Scratch provisioning only — against a real verification target the install plan's
     * {@code schemaExists} preflight is the operator's responsibility.
     */
    static void prepareSchemas(Connection connection, String dialect, List<String> schemas) throws SQLException {
        boolean mysql = DialectId.parse(dialect).orElse(null) == DialectId.MYSQL;
        try (Statement statement = connection.createStatement()) {
            for (String schema : schemas) {
                if (mysql) {
                    statement.execute("CREATE DATABASE IF NOT EXISTS `" + schema.replace("`", "``") + "`");
                } else {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema.replace("\"", "\"\"") + "\"");
                }
            }
        }
    }

    private static GenericContainer<?> createContainer(DialectId dialectId) {
        return switch (dialectId) {
            case POSTGRESQL -> new GenericContainer<>(POSTGRES_IMAGE)
                    .withEnv("POSTGRES_USER", SCRATCH_USER)
                    .withEnv("POSTGRES_PASSWORD", SCRATCH_PASSWORD)
                    .withEnv("POSTGRES_DB", SCRATCH_DATABASE)
                    .withExposedPorts(5432)
                    .withStartupTimeout(STARTUP_TIMEOUT);
            case MYSQL -> new GenericContainer<>(MYSQL_IMAGE)
                    // Root so additional databases (= MySQL schemas) can be created; routine
                    // creation without SUPER needs log_bin_trust_function_creators.
                    .withEnv("MYSQL_ROOT_PASSWORD", SCRATCH_PASSWORD)
                    .withEnv("MYSQL_DATABASE", SCRATCH_DATABASE)
                    .withCommand("--log_bin_trust_function_creators=1")
                    .withExposedPorts(3306)
                    .withStartupTimeout(STARTUP_TIMEOUT);
        };
    }

    private static String jdbcUrl(DialectId dialectId, GenericContainer<?> container) {
        String host = container.getHost();
        return switch (dialectId) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + container.getMappedPort(5432) + "/" + SCRATCH_DATABASE;
            case MYSQL -> "jdbc:mysql://" + host + ":" + container.getMappedPort(3306) + "/" + SCRATCH_DATABASE;
        };
    }

    private static TitanJdbcConnections.JdbcSession openWithRetry(
            Collection<File> driverClasspath,
            String jdbcUrl,
            String username
    ) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        Exception lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return TitanJdbcConnections.open(driverClasspath, jdbcUrl, username, SCRATCH_PASSWORD);
            } catch (SQLException e) {
                // "Database not ready yet" — a missing driver surfaces as a GradleException and
                // aborts immediately instead of being retried.
                lastFailure = e;
                Thread.sleep(CONNECT_RETRY_INTERVAL.toMillis());
            }
        }
        throw new IllegalStateException(
                "Scratch verification container did not accept connections within " + STARTUP_TIMEOUT
                        + " (" + TitanJdbcConnections.redactedJdbcUrl(jdbcUrl) + ")", lastFailure);
    }
}
