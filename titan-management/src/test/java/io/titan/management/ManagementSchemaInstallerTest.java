package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementSchemaInstaller.Dialect;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No-Docker B3 coverage: the SQL bundle resource is on titan-management's classpath (shipped by
 * titan-management-routines) and the {@link SqlScripts} splitter cuts it into the expected
 * executable statements. {@code ManagementSchemaInstaller.install(...)} against a live database is
 * exercised by the dogfood IT ({@code JdbcManagementStoreDogfoodIT}); here we only prove the
 * bundle loads and splits deterministically without a connection.
 */
class ManagementSchemaInstallerTest {

    @Test
    void postgresqlBundleLoadsFromClasspathAndSplitsIntoSchemaAndRoutines() {
        String schema = ManagementSchemaInstaller.loadSchema(Dialect.POSTGRESQL);
        String routines = ManagementSchemaInstaller.loadRoutines(Dialect.POSTGRESQL);

        // Schema DDL: five tables + the supersede index, comment-aware split (the prose carries
        // stray `;`/`);` that a naive splitter would mis-cut).
        List<String> schemaStatements = SqlScripts.split(schema);
        assertEquals(6, schemaStatements.size(), "PG schema: 5 CREATE TABLE + 1 CREATE INDEX");
        assertTrue(schemaStatements.stream().anyMatch(s -> s.contains("CREATE TABLE management_drafts")));
        assertTrue(schemaStatements.stream().anyMatch(s -> s.contains("management_deployments_supersede_idx")));

        // Routine bundle: seven routines (dollar-quoted bodies split correctly). RD-1: six are
        // CREATE OR REPLACE PROCEDUREs and activate_deployment is now a CREATE OR REPLACE FUNCTION
        // (the value-returning typed-status routine).
        List<String> routineStatements = SqlScripts.split(routines);
        assertEquals(7, routineStatements.size(), "PG routines: 6 procedures + 1 function");
        assertTrue(routineStatements.stream()
                .allMatch(s -> {
                    String upper = s.toUpperCase(java.util.Locale.ROOT);
                    return upper.contains("CREATE OR REPLACE PROCEDURE")
                            || upper.contains("CREATE OR REPLACE FUNCTION");
                }), "every PG routine is a CREATE OR REPLACE PROCEDURE/FUNCTION");
        assertEquals(6, routineStatements.stream()
                .filter(s -> s.toUpperCase(java.util.Locale.ROOT).contains("CREATE OR REPLACE PROCEDURE")).count(),
                "PG: six procedures");
        assertTrue(routineStatements.stream().anyMatch(s -> s.contains("\"import_model_document\"")));
        assertTrue(routineStatements.stream().anyMatch(s ->
                s.contains("CREATE OR REPLACE FUNCTION \"management\".\"activate_deployment\"")),
                "RD-1: activate_deployment is a FUNCTION");
    }

    @Test
    void mysqlBundleLoadsFromClasspathAndSplitsHonoringDelimiter() {
        String schema = ManagementSchemaInstaller.loadSchema(Dialect.MYSQL);
        String routines = ManagementSchemaInstaller.loadRoutines(Dialect.MYSQL);

        List<String> schemaStatements = SqlScripts.split(schema);
        assertEquals(6, schemaStatements.size(), "MySQL schema: 5 CREATE TABLE + 1 CREATE INDEX");

        // The MySQL routine bundle uses DELIMITER $$; each routine is a DROP + CREATE pair, so the
        // delimiter-aware splitter yields 14 statements for 7 routines (6 procedures + 1 function;
        // RD-1: activate_deployment is now a DROP FUNCTION + CREATE FUNCTION pair).
        List<String> routineStatements = SqlScripts.split(routines);
        assertEquals(14, routineStatements.size(), "MySQL routines: 7 routines x (DROP + CREATE)");
        assertTrue(routineStatements.stream().anyMatch(s -> s.contains("CREATE PROCEDURE `management`.`import_model_document`")));
        assertTrue(routineStatements.stream().anyMatch(s -> s.contains("DROP FUNCTION IF EXISTS `management`.`activate_deployment`")),
                "RD-1: activate_deployment is a FUNCTION (DROP FUNCTION)");
        assertTrue(routineStatements.stream().anyMatch(s -> s.contains("CREATE FUNCTION `management`.`activate_deployment`")),
                "RD-1: activate_deployment CREATE FUNCTION");
        // No statement should still carry the DELIMITER directive or the trailing $$ marker.
        assertTrue(routineStatements.stream().noneMatch(s -> s.contains("DELIMITER")));
        assertFalse(routineStatements.stream().anyMatch(s -> s.endsWith("$$")));
    }

    @Test
    void dialectFromIdAcceptsResourceNamesAndRejectsUnknown() {
        assertEquals(Dialect.POSTGRESQL, Dialect.fromId("postgresql"));
        assertEquals(Dialect.MYSQL, Dialect.fromId("mysql"));
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> Dialect.fromId("oracle"));
        assertTrue(error.getMessage().contains("TITAN-MGMT-INSTALL"));
    }
}
