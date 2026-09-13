package io.titan.management;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bootstrap/test deploy path for the dogfooded management store (B3).
 *
 * <p>Loads the SQL bundle that {@code titan-management-routines} ships as a classpath resource —
 * {@code io/titan/management/sql/{dialect}/schema.sql} (the hand-authored DDL) followed by
 * {@code .../routines.sql} (the Titan-transpiled mutation routines) — splits each into executable
 * statements with the {@code DELIMITER}/dollar-quote/comment-aware {@link SqlScripts} splitter, and
 * applies them in order (DDL first, then routines) on a caller-supplied {@link Connection}. The
 * routines reference the schema's tables and their {@code ON CONFLICT} targets, so the DDL must be
 * in place before the routine bundle is applied.
 *
 * <p><b>Production bootstrap is out-of-band.</b> Titan transpiles the mutation routines, not the base
 * tables: the store that records deployments cannot deploy itself before it exists. In production the
 * schema (and routine) bundle is applied by a migration/deploy step the operator owns; the
 * {@link JdbcTransactionalMutationStore} and the JDBC idempotency/audit stores assume their schema
 * and routines already exist. This installer is the convenience used by the dogfood IT and by any
 * caller bootstrapping a scratch database; it is not the production deployment mechanism.
 *
 * <p>The installer does not own a transaction: PostgreSQL can run DDL transactionally but MySQL DDL
 * auto-commits, so {@code install} applies statements on the connection as-is and lets the caller
 * decide the surrounding autocommit/transaction mode. The dogfood IT installs once on a dedicated
 * connection before exercising the store.
 */
public final class ManagementSchemaInstaller {

    /** The SQL dialects the bundle ships, matching the resource directory names. */
    public enum Dialect {
        POSTGRESQL("postgresql"),
        MYSQL("mysql");

        private final String resourceName;

        Dialect(String resourceName) {
            this.resourceName = resourceName;
        }

        public String resourceName() {
            return resourceName;
        }

        public static Dialect fromId(String id) {
            Objects.requireNonNull(id, "dialect id");
            for (Dialect dialect : values()) {
                if (dialect.resourceName.equals(id.toLowerCase(Locale.ROOT))) {
                    return dialect;
                }
            }
            throw new IllegalArgumentException("TITAN-MGMT-INSTALL: unsupported dialect: " + id);
        }
    }

    private static final String RESOURCE_ROOT = "io/titan/management/sql/";

    private ManagementSchemaInstaller() {
    }

    /**
     * Applies the schema DDL then the routine bundle for {@code dialect} on {@code connection}.
     * The connection's autocommit/transaction mode is left to the caller.
     */
    public static void install(Connection connection, Dialect dialect) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(dialect, "dialect");
        applyScript(connection, loadSchema(dialect));
        applyScript(connection, loadRoutines(dialect));
    }

    /** Loads the hand-authored schema DDL for {@code dialect} from the bundled resource. */
    public static String loadSchema(Dialect dialect) {
        return loadResource(resourcePath(dialect, "schema.sql"));
    }

    /** Loads the transpiled routine bundle for {@code dialect} from the bundled resource. */
    public static String loadRoutines(Dialect dialect) {
        return loadResource(resourcePath(dialect, "routines.sql"));
    }

    private static String resourcePath(Dialect dialect, String fileName) {
        return RESOURCE_ROOT + dialect.resourceName() + "/" + fileName;
    }

    private static String loadResource(String resourcePath) {
        ClassLoader loader = ManagementSchemaInstaller.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "TITAN-MGMT-INSTALL: management SQL bundle resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "TITAN-MGMT-INSTALL: failed to read management SQL bundle resource: " + resourcePath, exception);
        }
    }

    private static void applyScript(Connection connection, String script) throws SQLException {
        for (String statementSql : SqlScripts.split(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            } catch (SQLException exception) {
                throw new SQLException(
                        "TITAN-MGMT-INSTALL: failed to apply management SQL statement:\n" + statementSql, exception);
            }
        }
    }
}
