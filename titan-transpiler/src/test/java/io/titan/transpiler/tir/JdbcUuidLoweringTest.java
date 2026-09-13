package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UUID lowering through the full pipeline (Docker-free): a {@code java.util.UUID} parameter and a
 * {@code List<UUID>} collection-IN must transpile to native, value-bound SQL on each dialect —
 * PostgreSQL's native {@code uuid}/{@code uuid[]} and MySQL's {@code CHAR(36)} (no native UUID type;
 * canonical 36-char string per charter §1.1a). The live round-trip is proven by
 * {@link JdbcEmitterDeployabilityIT}; this pins the emitted shapes fast.
 */
class JdbcUuidLoweringTest {

    @TempDir
    Path tempDir;

    private static final String FLAG_BY_EXT_ID = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.UUID;

            class FlagByExtId {
                @StoredProcedure
                public static void flagByExtId(Connection c, UUID extId) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE ext_id = ?");
                    ps.setObject(1, extId);
                    ps.executeUpdate();
                }
            }
            """;

    private static final String FLAG_BY_EXT_ID_LIST = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.util.*;

            class FlagByExtIdList {
                @StoredProcedure
                public static void flagByExtIdList(Connection c, List<UUID> extIds) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE accounts SET flagged = 1 WHERE ext_id IN ("
                                    + String.join(",", Collections.nCopies(extIds.size(), "?")) + ")");
                    for (int i = 0; i < extIds.size(); i++) {
                        ps.setObject(i + 1, extIds.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            """;

    private static final String READ_EXT_ID = """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.util.UUID;

            class GetExtId {
                @StoredFunction
                public static UUID getExtId(Connection c, long id) throws SQLException {
                    UUID result = null;
                    PreparedStatement ps = c.prepareStatement("SELECT ext_id FROM accounts WHERE id = ?");
                    ps.setLong(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        result = rs.getObject(1, UUID.class);
                    }
                    return result;
                }
            }
            """;

    private String transpile(String className, String source, String dialect) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile), List.of(), List.of(dialect), List.of("billing"), true);
        return generated.stream()
                .filter(artifact -> dialect.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
    }

    // ===== scalar UUID parameter =================================================================

    @Test
    void scalarUuidParameterEmitsNativeUuidOnPostgres() throws Exception {
        String sql = transpile("FlagByExtId", FLAG_BY_EXT_ID, "postgresql").toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("UUID"), "the UUID parameter must declare PostgreSQL's native UUID type; was:\n" + sql);
        assertTrue(sql.contains("EXT_ID"), "the comparison must reference the ext_id column; was:\n" + sql);
    }

    @Test
    void scalarUuidParameterEmitsCharThirtySixOnMysql() throws Exception {
        String sql = transpile("FlagByExtId", FLAG_BY_EXT_ID, "mysql").toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("CHAR(36)"),
                "MySQL has no native UUID type: the parameter must declare CHAR(36); was:\n" + sql);
    }

    // ===== List<UUID> collection-IN ==============================================================

    @Test
    void uuidListEmitsNativeUuidArrayOnPostgres() throws Exception {
        String sql = transpile("FlagByExtIdList", FLAG_BY_EXT_ID_LIST, "postgresql");
        assertTrue(sql.contains("ext_id = ANY($1)"),
                "PostgreSQL must bind the whole UUID list as one array via = ANY($1); was:\n" + sql);
        assertTrue(sql.toUpperCase(Locale.ROOT).contains("UUID[]"),
                "the list parameter must be a native uuid[] array; was:\n" + sql);
    }

    // ===== UUID ResultSet read via the typed getObject(col, UUID.class) =========================

    @Test
    void uuidReadViaTypedGetObjectEmitsNonEmptyIntoTargetOnPostgres() throws Exception {
        // Regression: there is no rs.getUuid, so getObject(col, UUID.class) is the only idiomatic UUID
        // read. A recognizer/lowerer divergence on the 2-arg getter previously dropped the INTO target,
        // emitting an invalid `... INTO  USING ...` that never deploys. The read must INTO the result var.
        String sql = transpile("GetExtId", READ_EXT_ID, "postgresql");
        assertTrue(sql.contains("v_result"),
                "the UUID column read must assign the result variable (non-empty INTO); was:\n" + sql);
        assertFalse(sql.matches("(?s).*INTO\\s+USING.*"),
                "the INTO target must not be empty (the typed-getObject divergence bug); was:\n" + sql);
        assertTrue(sql.toUpperCase(Locale.ROOT).contains("UUID"),
                "the function must read/return the native PostgreSQL uuid type; was:\n" + sql);
    }

    @Test
    void uuidListEmitsCharThirtySixJsonTableOnMysql() throws Exception {
        String sql = transpile("FlagByExtIdList", FLAG_BY_EXT_ID_LIST, "mysql");
        // The membership is emitted inside a PREPARE '...' literal, so single quotes are doubled.
        assertTrue(sql.contains("JSON_TABLE(?, ''$[*]'' COLUMNS (v CHAR(36) PATH ''$''))"),
                "MySQL must extract UUID elements as CHAR(36) from the bound JSON array; was:\n" + sql);
        assertTrue(sql.contains("ext_id IN (SELECT v FROM JSON_TABLE("),
                "MySQL must keep the membership as IN (SELECT v FROM JSON_TABLE(...)); was:\n" + sql);
    }
}
