package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte-compares {@link JdbcCompatReport#toJson()} for a mixed-verdict corpus against a checked-in
 * golden, mirroring {@code GoldenSqlTest}. Regenerate with {@code -Dtitan.jdbccompat.update=true}
 * and review the {@code git diff} of {@code src/test/resources/jdbc-compat/}.
 *
 * <p>The fixture is written to a stable module-relative path (not a per-run {@code @TempDir}) so the
 * source-file name embedded in every {@code location}/{@code file} field is deterministic.</p>
 */
class JdbcCompatReportGoldenTest {

    private static final String GOLDEN_ID = "corpus";

    // A mixed corpus: a clean transpilable read, a passthrough (permissive-scope non-constant SQL),
    // a strict reject (value splice), and a structural reject (CallableStatement).
    private static final String CORPUS_SOURCE = """
            import titan.dsl.*;
            import java.sql.*;

            class Corpus {
                @StoredProcedure
                public static void cleanRead(Connection c, long id) throws SQLException {
                    PreparedStatement ps = c.prepareStatement("SELECT tier FROM accounts WHERE id = ?");
                    ps.setLong(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        String tier = rs.getString("tier");
                    }
                }

                @StoredProcedure
                @SqlSafety(SqlSafetyMode.PERMISSIVE)
                public static void legacyDynamic(Connection c, long customerId) throws SQLException {
                    String sql = "SELECT * FROM orders WHERE customer_id = " + customerId;
                    PreparedStatement ps = c.prepareStatement(sql);
                    ps.executeQuery();
                }

                @StoredProcedure
                public static void strictSplice(Connection c, long customerId) throws SQLException {
                    String sql = "SELECT * FROM orders WHERE customer_id = " + customerId;
                    PreparedStatement ps = c.prepareStatement(sql);
                    ps.executeQuery();
                }

                @StoredProcedure
                public static void callsProc(Connection c) throws SQLException {
                    CallableStatement cs = c.prepareCall("{call do_it(?)}");
                    cs.execute();
                }
            }
            """;

    @Test
    void reportJsonMatchesGolden() throws Exception {
        boolean update = Boolean.getBoolean("titan.jdbccompat.update");

        Path fixtureDir = Files.createDirectories(Path.of("build", "jdbc-compat-fixtures"));
        Path sourceFile = fixtureDir.resolve("Corpus.java");
        Files.writeString(sourceFile, CORPUS_SOURCE);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        // Build-level strict; the @SqlSafety(PERMISSIVE) method opts itself into passthrough.
        JdbcCompatReport report = new JdbcCompatLinter().lint(parsed, SqlSafetyMode.STRICT);
        String json = report.toJson();

        Path goldenFile = goldenRoot().resolve(GOLDEN_ID + ".json");
        if (update) {
            Files.createDirectories(goldenFile.getParent());
            Files.writeString(goldenFile, json);
            return;
        }

        assertTrue(Files.exists(goldenFile),
                "missing golden " + goldenFile + " — generate it with -Dtitan.jdbccompat.update=true");
        String expected = Files.readString(goldenFile);
        assertEquals(expected, json,
                "JDBC compat report drifted from the golden; regenerate with -Dtitan.jdbccompat.update=true "
                        + "and review the diff");

        // Spot-check the rollups embedded in the golden so the corpus stays meaningful.
        assertEquals(4, report.methodCount());
        assertEquals(1, report.methodsWith(JdbcClassification.TRANSPILABLE));
        assertEquals(1, report.methodsWith(JdbcClassification.PASSTHROUGH));
        assertEquals(2, report.methodsWith(JdbcClassification.REJECTED));
    }

    private static Path goldenRoot() {
        Path moduleRelative = Path.of("src", "test", "resources", "jdbc-compat");
        if (Files.isDirectory(moduleRelative.getParent().getParent())) {
            return moduleRelative;
        }
        return Path.of("titan-transpiler").resolve(moduleRelative);
    }
}
