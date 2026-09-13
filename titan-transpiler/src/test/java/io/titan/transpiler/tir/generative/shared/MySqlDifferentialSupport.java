package io.titan.transpiler.tir.generative.shared;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Shared pieces of the MySQL differential leg (plan 5.3): fixture installation on the
 * singleton MySQL container and the capability-skip protocol.
 *
 * <p><b>Fixture installation.</b> Mirrors the PostgreSQL installers
 * ({@link SelectPostgresSqlHarness#installFixture}, {@code JoinPostgresSqlHarness}) with MySQL
 * DDL. Tables are created unqualified in the connection's current database — exactly where the
 * transpiled SELECT resolves its unqualified table references (MySQL has no search_path; the
 * PostgreSQL leg relies on {@code public} being on the default search_path the same way).</p>
 *
 * <p><b>Capability skips.</b> The generated corpus is dialect-agnostic; a case whose lowered
 * query hits a construct MySQL cannot express (FULL OUTER JOIN, GROUPING SETS, ...) is rejected
 * by the transpiler with a positioned {@code TITAN-E001} capability diagnostic. For the MySQL
 * leg such cases are <em>skipped</em>, not failed — the same way the invalid-case protocol
 * treats expected-invalid generations as their own bucket instead of mismatches. The skip
 * reason is recorded in the run artifacts ({@code capability-skip.txt}) so a skipped seed is
 * always auditable. A capability rejection on the MySQL leg is correct transpiler behavior;
 * silent acceptance of an inexpressible construct would be the bug.</p>
 */
public final class MySqlDifferentialSupport {

    /** Diagnostic id used by FeatureValidator/emitters for dialect-capability rejections. */
    private static final String CAPABILITY_DIAGNOSTIC_ID = "TITAN-E001";

    private MySqlDifferentialSupport() {
    }

    /**
     * Returns the capability-rejection reason if {@code failure} (or any cause) is a
     * {@code TITAN-E001} dialect-capability rejection, or {@code null} when the failure is a
     * real error that must propagate.
     */
    public static String capabilitySkipReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(CAPABILITY_DIAGNOSTIC_ID)) {
                return message;
            }
        }
        return null;
    }

    /** Drops and recreates the accounts fixture table in the connection's current database. */
    public static void installAccountsFixture(Connection connection, FixtureCatalog.FixtureTable fixtureTable)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(fixtureTable, "fixtureTable");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS accounts");
            stmt.execute("""
                    CREATE TABLE accounts (
                      id INTEGER NOT NULL,
                      email TEXT NULL,
                      active BOOLEAN NULL,
                      plan_code TEXT NULL,
                      login_count INTEGER NULL
                    )
                    """);
            for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                stmt.execute("INSERT INTO accounts (id, email, active, plan_code, login_count) VALUES ("
                        + sqlLiteral(row.values().get("id")) + ", "
                        + sqlLiteral(row.values().get("email")) + ", "
                        + sqlLiteral(row.values().get("active")) + ", "
                        + sqlLiteral(row.values().get("plan_code")) + ", "
                        + sqlLiteral(row.values().get("login_count")) + ")");
            }
        }
    }

    /** Drops and recreates the accounts/plans/plan_families join fixtures (MySQL twin). */
    public static void installJoinFixtures(
            Connection connection,
            FixtureCatalog.FixtureTable accounts,
            FixtureCatalog.FixtureTable plans,
            FixtureCatalog.FixtureTable planFamilies
    ) throws SQLException {
        installAccountsFixture(connection, accounts);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS plan_families");
            stmt.execute("DROP TABLE IF EXISTS plans");
            stmt.execute("""
                    CREATE TABLE plans (
                      code TEXT NOT NULL,
                      name TEXT NOT NULL,
                      paid BOOLEAN NOT NULL,
                      family_code TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE plan_families (
                      code TEXT NOT NULL,
                      label TEXT NOT NULL
                    )
                    """);
            for (FixtureCatalog.FixtureRow row : plans.rows()) {
                stmt.execute("INSERT INTO plans (code, name, paid, family_code) VALUES ("
                        + sqlLiteral(row.values().get("code")) + ", "
                        + sqlLiteral(row.values().get("name")) + ", "
                        + sqlLiteral(row.values().get("paid")) + ", "
                        + sqlLiteral(row.values().get("family_code")) + ")");
            }
            for (FixtureCatalog.FixtureRow row : planFamilies.rows()) {
                stmt.execute("INSERT INTO plan_families (code, label) VALUES ("
                        + sqlLiteral(row.values().get("code")) + ", "
                        + sqlLiteral(row.values().get("label")) + ")");
            }
        }
    }

    static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("Unsupported fixture SQL literal: " + value.getClass().getName());
    }
}
