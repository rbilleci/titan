package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS-C Phase 3 Rung 5 — the security-critical <b>no-false-accept</b> lock for the unknown-shape result
 * carrier (design contract §8 posture). It drives {@link JdbcDynamicResultRecognizer#recognize}
 * directly, asserting a {@link JdbcDynamicResultRecognizer.CarrierPlan} is returned ONLY for the PURE
 * metadata-driven generic reader (a {@code while (rs.next())} loop that does NOTHING but copy every
 * column into a per-row {@code Map<String,Object>} and add it to a returned {@code
 * List<Map<String,Object>>}), and refuses ({@link Optional#empty()}) the moment the reader does any
 * business logic on the unknown-shape rows — the Tier-3/Tier-4 fail-safe. A false-accept would silently
 * drop the Java's per-row logic (a correctness/semantics defect), so in any doubt it must NOT carrier-lower.
 */
class JdbcDynamicResultRecognizerTest {

    @TempDir
    Path tempDir;

    /** Wraps a method body as a @StoredFunction returning List<Map<String,Object>> (the carrier shape). */
    private static String wrap(String methodBody) {
        return """
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredFunction
                    public static List<Map<String,Object>> run(Connection c, String kind) throws SQLException {
                """ + methodBody + """
                    }
                }
                """;
    }

    private Optional<JdbcDynamicResultRecognizer.CarrierPlan> recognize(String methodBody) throws Exception {
        return recognizeFullSource(wrap(methodBody), "run");
    }

    private Optional<JdbcDynamicResultRecognizer.CarrierPlan> recognizeFullSource(
            String source, String methodName) throws Exception {
        Path sourceFile = tempDir.resolve("Fixture.java");
        Files.writeString(sourceFile, source);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        CompilationUnitTree unit = parsed.compilationUnits().getFirst();

        BlockTree[] body = {null};
        TreePath[] bodyPath = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (node.getName().contentEquals(methodName) && node.getBody() != null && body[0] == null) {
                    body[0] = node.getBody();
                    bodyPath[0] = new TreePath(getCurrentPath(), node.getBody());
                }
                return super.visitMethod(node, unused);
            }
        }.scan(unit, null);

        JdbcTypeOracle oracle = new JdbcTypeOracle(parsed);
        JdbcShapes shapes = new JdbcShapes(parsed, oracle);
        return new JdbcDynamicResultRecognizer(parsed, shapes, oracle).recognize(body[0], bodyPath[0]);
    }

    // ===== positive recognition ==================================================================

    @Test
    void recognizesCanonicalGenericReader() throws Exception {
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets ORDER BY id");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertTrue(plan.isPresent(), "the canonical pure metadata-driven generic reader must be recognized");
    }

    @Test
    void recognizesHashMapAndPreIncrementVariants() throws Exception {
        // HashMap instead of LinkedHashMap; ++i instead of i++ — still pure metadata-driven marshalling
        // (keyed by getColumnLabel, the only admitted key call).
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT * FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new HashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); ++i) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertTrue(plan.isPresent(), "HashMap + getColumnLabel + ++i is still the pure marshalling carrier");
    }

    @Test
    void recognizesInlineStatementExecuteQuery() throws Exception {
        // st.executeQuery(SQL) inline form (no separate prepareStatement) — still a generic reader.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT id FROM widgets");
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertTrue(plan.isPresent(), "the inline st.executeQuery(SQL) generic reader must be recognized");
        assertTrue(plan.get().sqlArg() != null, "the carrier must capture the SQL source");
    }

    // ===== no-false-accept: ANY business logic on the unknown-shape rows REFUSES ==================

    @Test
    void refusesBranchOnColumnValue() throws Exception {
        // Filters rows in Java (an if on a column) — Tier-4, no server-side form.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, active FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    if (rs.getObject(2) != null) {
                        rows.add(row);
                    }
                }
                return rows;
                """);
        assertFalse(plan.isPresent(), "a Java-side row filter (if on a column) must NOT carrier-lower");
    }

    @Test
    void refusesTypedTransformInLoop() throws Exception {
        // A typed read + transform per column (rs.getString(i).toUpperCase()) — not faithful marshalling.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getString(i).toUpperCase());
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(), "a typed transform per column must NOT carrier-lower (not getObject)");
    }

    @Test
    void refusesExtraStatementInLoopBody() throws Exception {
        // A fourth statement in the loop body (a per-row method call) — beyond pure marshalling.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    row.put("extra", "x");
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(), "an extra per-row statement must NOT carrier-lower");
    }

    @Test
    void refusesPostProcessedReturn() throws Exception {
        // Returns rows.get(0), not rows — the carrier would silently change the result.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredFunction
                    public static Map<String,Object> run(Connection c) throws SQLException {
                        List<Map<String,Object>> rows = new ArrayList<>();
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM widgets");
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String,Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                row.put(md.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                        return rows.get(0);
                    }
                }
                """, "run");
        assertFalse(plan.isPresent(), "returning rows.get(0) (not rows) must NOT carrier-lower");
    }

    @Test
    void refusesSecondUseOfRows() throws Exception {
        // rows is also passed to a sink (an escape) — the carrier would drop that use.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredFunction
                    public static List<Map<String,Object>> run(Connection c) throws SQLException {
                        List<Map<String,Object>> rows = new ArrayList<>();
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM widgets");
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String,Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                row.put(md.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                        sink(rows);
                        return rows;
                    }
                    static void sink(List<Map<String,Object>> v) { }
                }
                """, "run");
        assertFalse(plan.isPresent(), "a second use of rows (escape) must NOT carrier-lower");
    }

    @Test
    void refusesAccumulatorSeededFromCopy() throws Exception {
        // new ArrayList<>(seed) copies a pre-existing collection — the carrier would drop the seed rows.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredFunction
                    public static List<Map<String,Object>> run(Connection c, List<Map<String,Object>> seed) throws SQLException {
                        List<Map<String,Object>> rows = new ArrayList<>(seed);
                        PreparedStatement ps = c.prepareStatement("SELECT id FROM widgets");
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String,Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                row.put(md.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                        return rows;
                    }
                }
                """, "run");
        assertFalse(plan.isPresent(), "a copy-constructed accumulator (new ArrayList<>(seed)) must NOT carrier-lower");
    }

    @Test
    void refusesTypedFixedColumnRead() throws Exception {
        // A typed fixed-shape read (rs.getLong("id")) is NOT a generic reader — no ResultSetMetaData walk.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognizeFullSource("""
                import titan.dsl.*;
                import java.sql.*;
                import java.util.*;

                class Fixture {
                    @StoredFunction
                    public static List<Map<String,Object>> run(Connection c) throws SQLException {
                        List<Map<String,Object>> rows = new ArrayList<>();
                        PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String,Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getLong("id"));
                            row.put("label", rs.getString("label"));
                            rows.add(row);
                        }
                        return rows;
                    }
                }
                """, "run");
        assertFalse(plan.isPresent(),
                "a typed fixed-column read (no ResultSetMetaData walk) is not the unknown-shape carrier");
    }

    @Test
    void refusesForLoopBoundNotColumnCount() throws Exception {
        // The for-loop is bounded by a fixed literal, not md.getColumnCount() — not metadata-driven.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT a, b, c FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= 3; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(),
                "a fixed-bound column loop (not md.getColumnCount()) is not the metadata-driven carrier");
    }

    @Test
    void refusesMismatchedColumnIndexBetweenKeyAndValue() throws Exception {
        // The key uses md.getColumnLabel(i) but the value reads a CONSTANT column rs.getObject(1) — the
        // marshalling is not the faithful "every column by its own index", so refuse.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(1));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(),
                "a value read by a constant index (not the loop counter) is not faithful marshalling");
    }

    @Test
    void refusesGetColumnNameKeyedReaderOverAliasedSelect() throws Exception {
        // The reader is otherwise canonical but keys by md.getColumnName(i) (the BASE column name) over an
        // ALIASED SELECT. The carrier keys every map by the SQL OUTPUT LABEL (PG to_jsonb derived-table
        // name / MySQL result-set header = the alias), so getColumnName ("id") and the carrier's key
        // ("widget_id") would DIVERGE. The recognizer cannot tell an aliased from an unaliased SELECT
        // without parsing it, so getColumnName is rejected outright (fail-safe → Tier-4) — only
        // getColumnLabel carrier-lowers.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id AS widget_id, label AS widget_label FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(),
                "a getColumnName-keyed reader (base column name) has no faithful label-keyed carrier — must REJECT");
    }

    @Test
    void refusesGetColumnNameKeyedReaderEvenWithoutAlias() throws Exception {
        // Even over a no-alias SELECT, getColumnName is rejected: the recognizer does not parse the SQL to
        // tell whether a column is aliased, so it fail-safe-rejects EVERY getColumnName reader (the
        // unaliased case happens to coincide with the label, but the recognizer cannot prove that).
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
                """);
        assertFalse(plan.isPresent(), "getColumnName is rejected regardless of aliasing — only getColumnLabel lowers");
    }

    @Test
    void refusesResidualMetaDataUseAfterLoop() throws Exception {
        // The marshalling loop is canonical, but AFTER it the method reads md.getColumnCount() into a local
        // — a residual use of the unknown-shape metadata that has no server-side form. The use-and-escape
        // proof must catch this (md is referenced outside the recognized carrier pieces) and REFUSE, so the
        // lowerer's Tier-4 fail-safe fires instead of a misleading "no SQL lowering for getColumnCount".
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                int n = md.getColumnCount();
                return rows;
                """);
        assertFalse(plan.isPresent(), "a residual md.getColumnCount() after the loop is residual logic — must REFUSE");
    }

    @Test
    void refusesResidualResultSetUseAfterLoop() throws Exception {
        // Same fail-safe for the ResultSet: a residual rs.getRow() after the marshalling loop is a use of
        // the unknown-shape result with no server-side form. The escape proof must REFUSE.
        Optional<JdbcDynamicResultRecognizer.CarrierPlan> plan = recognize("""
                List<Map<String,Object>> rows = new ArrayList<>();
                PreparedStatement ps = c.prepareStatement("SELECT id, label FROM widgets");
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                int last = rs.getRow();
                return rows;
                """);
        assertFalse(plan.isPresent(), "a residual rs.getRow() after the loop is residual logic — must REFUSE");
    }
}
