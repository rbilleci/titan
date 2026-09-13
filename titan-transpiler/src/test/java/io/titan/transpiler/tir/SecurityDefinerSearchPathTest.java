package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ATG-017 (Part 1): a SECURITY DEFINER routine must pin {@code search_path} to its own schema (+
 * {@code pg_temp}) — the classic hardening against a caller shadowing the routine's
 * functions/operators/types. An INVOKER routine runs with the caller's own privileges and is NOT
 * pinned. MySQL has no {@code search_path} concept, so the clause is PostgreSQL-only.
 */
class SecurityDefinerSearchPathTest {

    @TempDir
    Path tempDir;

    private static final String DEFINER_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SecurityDefiner;
            import java.sql.*;

            class FlagOne {
                @StoredProcedure
                @SecurityDefiner
                public static void flagOne(Connection c, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    private static final String INVOKER_PROC = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class FlagOne {
                @StoredProcedure
                public static void flagOne(Connection c, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    private String transpile(String fileName, String source, String dialect) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return new TranspilationPipeline().transpile(
                        List.of(file), List.of(), List.of(dialect), List.of("billing"), true).stream()
                .filter(artifact -> dialect.equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void securityDefinerRoutinePinsSearchPathOnPostgres() throws Exception {
        String sql = transpile("FlagOne.java", DEFINER_PROC, "postgresql");
        assertTrue(sql.contains("SECURITY DEFINER"), sql);
        assertTrue(sql.contains("SET search_path = \"billing\", pg_temp"),
                "a SECURITY DEFINER routine must pin search_path to its schema; was:\n" + sql);
    }

    @Test
    void invokerRoutineDoesNotPinSearchPathOnPostgres() throws Exception {
        String sql = transpile("FlagOne.java", INVOKER_PROC, "postgresql");
        assertTrue(sql.contains("SECURITY INVOKER"), sql);
        assertFalse(sql.contains("SET search_path"),
                "an INVOKER routine runs with the caller's privileges and must not pin search_path; was:\n" + sql);
    }

    @Test
    void mysqlHasNoSearchPathClause() throws Exception {
        String sql = transpile("FlagOne.java", DEFINER_PROC, "mysql");
        assertFalse(sql.contains("search_path"), "MySQL has no search_path concept; was:\n" + sql);
    }

    // ===== ATG-017b: owner/grant/revoke privilege-policy artifacts =====

    private static final String POLICY_PROC = """
            import titan.dsl.StoredProcedure;
            import titan.dsl.SecurityDefiner;
            import java.sql.*;

            class FlagOne {
                @StoredProcedure
                @SecurityDefiner(ownerRole = "titan_owner", executeRoles = {"titan_app"}, revokePublic = true)
                public static void flagOne(Connection c, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("UPDATE accounts SET flagged = 1 WHERE id = ?");
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            """;

    private List<TranspilationPipeline.GeneratedSql> transpileAll(String fileName, String source, String dialect)
            throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return new TranspilationPipeline().transpile(
                        List.of(file), List.of(), List.of(dialect), List.of("billing"), true).stream()
                .filter(artifact -> dialect.equals(artifact.target()))
                .toList();
    }

    private static String policyArtifact(List<TranspilationPipeline.GeneratedSql> artifacts) {
        return artifacts.stream()
                .filter(a -> "SecurityPolicy".equals(a.annotationKind()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void securityDefinerPolicyEmitsSeparateOwnerRevokeGrantArtifactOnPostgres() throws Exception {
        List<TranspilationPipeline.GeneratedSql> artifacts = transpileAll("FlagOne.java", POLICY_PROC, "postgresql");
        String policy = policyArtifact(artifacts);
        assertTrue(policy.contains("ALTER PROCEDURE \"billing\".\"flag_one\"(BIGINT) OWNER TO \"titan_owner\";"), policy);
        assertTrue(policy.contains("REVOKE ALL ON PROCEDURE \"billing\".\"flag_one\"(BIGINT) FROM PUBLIC;"), policy);
        assertTrue(policy.contains("GRANT EXECUTE ON PROCEDURE \"billing\".\"flag_one\"(BIGINT) TO \"titan_app\";"), policy);

        // The policy is a SEPARATE artifact — not appended to the routine body.
        String routine = artifacts.stream()
                .filter(a -> !"SecurityPolicy".equals(a.annotationKind()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst().orElseThrow();
        assertFalse(routine.contains("GRANT EXECUTE"),
                "the privilege policy must be a separate artifact, not in the routine body:\n" + routine);
    }

    @Test
    void bareSecurityDefinerEmitsNoPolicyArtifact() throws Exception {
        List<TranspilationPipeline.GeneratedSql> artifacts = transpileAll("FlagOne.java", DEFINER_PROC, "postgresql");
        assertFalse(artifacts.stream().anyMatch(a -> "SecurityPolicy".equals(a.annotationKind())),
                "no privilege attributes set → no policy artifact (opt-in, non-breaking)");
    }

    @Test
    void securityDefinerPolicyArtifactIsPostgresOnly() throws Exception {
        List<TranspilationPipeline.GeneratedSql> artifacts = transpileAll("FlagOne.java", POLICY_PROC, "mysql");
        assertFalse(artifacts.stream().anyMatch(a -> "SecurityPolicy".equals(a.annotationKind())),
                "the privilege policy artifact is PostgreSQL-only for now");
    }
}
