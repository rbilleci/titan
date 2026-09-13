package io.titan.test;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * JVM-singleton Testcontainers databases for the transpiler integration tests (audit R-4).
 *
 * <p>{@link #postgres()} and {@link #mysql()} return one shared, already-started container per
 * JVM with a random Docker-mapped host port — connect via {@code getJdbcUrl()}/mapped ports,
 * never a fixed port (the previous helper probed a free port with a {@code ServerSocket} and
 * then raced for it via the deprecated {@code addFixedExposedPort}). Do <b>not</b> annotate the
 * returned containers with {@code @Container}: the JUnit extension would stop the shared
 * instance after the first class. Configuration calls ({@code withDatabaseName(...)}) are
 * impossible on the started singleton by construction.</p>
 *
 * <p>Cross-class isolation: classes that fully reset the objects they touch can work directly
 * on the singleton's default database. Classes that assert over whole schemas should take a
 * private database from {@link #freshPostgresDatabase(String)}/{@link #freshMysqlDatabase(String)}
 * (dropped and recreated at the call).</p>
 *
 * <p>Cleanup is registered <em>before</em> any container starts: a shutdown hook stops whatever
 * came up even if a later container fails to boot, with Testcontainers' Ryuk reaper as the
 * backstop for hard JVM death.</p>
 */
public final class TestContainers {

    private static final Object LOCK = new Object();
    private static boolean cleanupRegistered;
    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    private TestContainers() {
    }

    /** The shared, started PostgreSQL 16 container for this JVM. */
    public static PostgreSQLContainer<?> postgres() {
        synchronized (LOCK) {
            if (postgres == null) {
                registerCleanup();
                PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16")
                        .withStartupTimeout(Duration.ofMinutes(5));
                postgres = container; // assigned before start: shutdown hook can stop a partial start
                container.start();
            }
            return postgres;
        }
    }

    /** The shared, started MySQL 8.4 container for this JVM. */
    public static MySQLContainer<?> mysql() {
        synchronized (LOCK) {
            if (mysql == null) {
                registerCleanup();
                MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                        .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0")
                        .withStartupTimeout(Duration.ofMinutes(5));
                mysql = container; // assigned before start: shutdown hook can stop a partial start
                container.start();
            }
            return mysql;
        }
    }

    /** Connection coordinates for a private database on a shared container. */
    public record SharedDatabase(String jdbcUrl, String username, String password, String databaseName) {
    }

    /**
     * Drops and recreates {@code databaseName} on the shared PostgreSQL container and returns
     * its coordinates. The container user is a superuser, so the returned credentials can do
     * everything the old per-class containers could.
     */
    public static SharedDatabase freshPostgresDatabase(String databaseName) {
        requireSafeName(databaseName);
        PostgreSQLContainer<?> container = postgres();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + databaseName);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision PostgreSQL test database " + databaseName, ex);
        }
        String jdbcUrl = "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + databaseName;
        return new SharedDatabase(jdbcUrl, container.getUsername(), container.getPassword(), databaseName);
    }

    /**
     * Drops and recreates {@code databaseName} on the shared MySQL container and returns root
     * coordinates for it (the scoped default user cannot create databases; root's password is
     * the container password).
     */
    public static SharedDatabase freshMysqlDatabase(String databaseName) {
        requireSafeName(databaseName);
        MySQLContainer<?> container = mysql();
        String serverUrl = "jdbc:mysql://" + container.getHost() + ":" + container.getMappedPort(3306) + "/";
        try (Connection connection = DriverManager.getConnection(serverUrl, "root", container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName);
            statement.execute("CREATE DATABASE " + databaseName);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision MySQL test database " + databaseName, ex);
        }
        return new SharedDatabase(serverUrl + databaseName, "root", container.getPassword(), databaseName);
    }

    private static void requireSafeName(String databaseName) {
        if (databaseName == null || !databaseName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe test database name: " + databaseName);
        }
    }

    private static void registerCleanup() {
        if (cleanupRegistered) {
            return;
        }
        cleanupRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOCK) {
                if (postgres != null) {
                    postgres.stop();
                }
                if (mysql != null) {
                    mysql.stop();
                }
            }
        }, "titan-testcontainers-shutdown"));
    }
}
