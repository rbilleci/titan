package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3, Rung 0 — proves the build-level {@code sqlSafety} setting now threads through the
 * transpile pipeline to the JDBC lowerer's build-level default. Previously {@code TranspilationPipeline}
 * hardcoded {@code SqlSafetyMode.STRICT} at the {@code lower(...)} call, so {@code sqlSafety = permissive}
 * in {@code titan{}} changed only the reports, never codegen.
 *
 * <p>The proof is a mode-dependent divergence on the <em>same</em> non-constant-SQL JDBC method:
 * {@code permissive} reaches the lowerer's PERMISSIVE branch, while {@code strict}/default reaches the
 * splice gate (TITAN-E004). Before Rung 0 both produced E004. The method's runtime fragment is an
 * open-quote splice ({@code = '" + status + "'} — a RAW_FRAGMENT, not a bindable value); as of WS-C
 * Phase 3 Rung 3 the permissive branch now <em>emits</em> it (assembling the SQL text at runtime), so
 * the divergence is "permissive transpiles cleanly vs strict fails E004" — which still proves the
 * build-level flag reaches codegen (strict never relaxes).</p>
 */
class JdbcSqlSafetyBuildFlagTest {

    @TempDir
    Path tempDir;

    private static final String NON_CONSTANT_SQL_JDBC = """
            import titan.dsl.StoredProcedure;
            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            class DynUpd {
                @StoredProcedure
                public static void run(Connection c, String status) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE orders SET flag = 1 WHERE status = '" + status + "'");
                    ps.executeUpdate();
                }
            }
            """;

    private Path writeSource() throws Exception {
        Path source = tempDir.resolve("DynUpd.java");
        Files.writeString(source, NON_CONSTANT_SQL_JDBC);
        return source;
    }

    @Test
    void buildLevelPermissiveReachesTheLowererPermissiveBranch() throws Exception {
        Path source = writeSource();
        // WS-C Phase 3 Rung 3: under a build-level permissive flag the open-quote raw-fragment splice now
        // EMITS (assembles the SQL text at runtime), so the pipeline transpiles cleanly — no exception.
        // That it does NOT fail (while the strict run below DOES, E004) proves the flag reached codegen.
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source), List.of(), List.of("postgresql"), List.of("public"),
                false, List.of(), false, List.of(), false, false, null, "permissive");
        String pg = generated.stream()
                .filter(artifact -> "postgresql".equals(artifact.target()))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst().orElseThrow();
        // The runtime-assembled form: PostgreSQL format(...) splices the fragment verbatim (%s), bound
        // values (none here) stay on USING. The fragment reaching the text IS the permissive exposure.
        assertTrue(pg.contains("EXECUTE format("),
                "permissive build flag must reach codegen and emit the runtime-assembled splice; got:\n" + pg);
    }

    @Test
    void buildLevelStrictReachesTheSpliceGate() throws Exception {
        Path source = writeSource();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source), List.of(), List.of("postgresql"), List.of("public"),
                        false, List.of(), false, List.of(), false, false, null, "strict"));
        assertTrue(ex.getMessage().contains("TITAN-E004"),
                "strict build flag must reach the E004 splice gate; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("cannot yet be reconstructed"), ex.getMessage());
    }

    @Test
    void defaultIsStrict_nullSqlSafetyAndLegacyOverloadBothGateE004() throws Exception {
        Path source = writeSource();
        // 12-arg overload with null sqlSafety -> STRICT (secure by default).
        RuntimeException nullArg = assertThrows(RuntimeException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source), List.of(), List.of("postgresql"), List.of("public"),
                        false, List.of(), false, List.of(), false, false, null, null));
        assertTrue(nullArg.getMessage().contains("TITAN-E004"), nullArg.getMessage());
        // Legacy 11-arg overload (no sqlSafety) stays backward-compatible STRICT.
        RuntimeException legacy = assertThrows(RuntimeException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source), List.of(), List.of("postgresql"), List.of("public"),
                        false, List.of(), false, List.of(), false, false, null));
        assertTrue(legacy.getMessage().contains("TITAN-E004"), legacy.getMessage());
    }
}
