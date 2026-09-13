package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.jdbc.JdbcSqlSkeleton.Hole;
import io.titan.transpiler.jdbc.JdbcSqlSkeleton.HoleKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the WS-C Phase 3 Rung 1 skeleton recognizer's hole classification — the security-critical
 * surface (design contract §8). The <b>no-false-accept</b> tests are MANDATORY and must never be
 * weakened: a value spliced via {@code +}, an identifier splice, a raw-fragment splice, a {@code
 * String.format} of a value, a {@code StringBuilder} appending a value, and a {@code String.join} over
 * runtime values MUST NOT be classified all-value-bindable (so the lowerer rejects them under strict
 * and defers them under permissive — a raw value/identifier never reaches the emitted SQL text). The
 * <b>accept</b> tests prove the recovered skeleton + recovered {@code ?}-text are correct for the cases
 * Rung 1 transpiles: a placeholder run, and a value-splice auto-bind.
 */
class JdbcSqlSkeletonRecognizerTest {

    @TempDir
    Path tempDir;

    /**
     * Parses the right-hand sides of a sequence of {@code String x = <expr>;} declarations in a fixture
     * with realistic locals/params and a recognized {@code placeholders(int)} helper, so the recognizer
     * sees genuine AST shapes (not synthetic nodes).
     */
    private List<ExpressionTree> initializers(String... exprs) throws Exception {
        StringBuilder body = new StringBuilder("""
                import java.util.Collections;
                import java.util.List;
                class Fixtures {
                  static String placeholders(int n) {
                    return String.join(",", Collections.nCopies(n, "?"));
                  }
                  void m(long id, String tableName, String status, int count, List<Long> ids) {
                """);
        for (int i = 0; i < exprs.length; i++) {
            body.append("    String s").append(i).append(" = ").append(exprs[i]).append(";\n");
        }
        body.append("  }\n}\n");
        Path sourceFile = tempDir.resolve("Fixtures.java");
        Files.writeString(sourceFile, body.toString());
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<ExpressionTree> result = new ArrayList<>();
        for (var unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    // Only the s0/s1/... declarations in m(...) — skip the helper's locals/params.
                    if (node.getInitializer() != null && node.getName().toString().startsWith("s")) {
                        result.add(node.getInitializer());
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return result;
    }

    private JdbcSqlSkeleton recover(String expr) throws Exception {
        Optional<JdbcSqlSkeleton> skeleton = JdbcSqlSkeletonRecognizer.recover(initializers(expr).getFirst());
        assertTrue(skeleton.isPresent(), "expected a recovered skeleton for: " + expr);
        return skeleton.get();
    }

    private boolean isAllValueBindable(String expr) throws Exception {
        JdbcSqlSkeleton skeleton = recover(expr);
        return skeleton.hasHole() && skeleton.allHolesValueBindable();
    }

    private List<Hole> holes(JdbcSqlSkeleton skeleton) {
        List<Hole> holes = new ArrayList<>();
        for (var fragment : skeleton.fragments()) {
            if (fragment instanceof Hole hole) {
                holes.add(hole);
            }
        }
        return holes;
    }

    // ---------------------------------------------------------------------------------------------
    // NO-FALSE-ACCEPT (security-critical, MANDATORY — never weaken).
    // A value/identifier/raw-fragment splice must NEVER be classified as "all value-bindable" unless it
    // is a true bindable VALUE (the value-splice case is value-binding BY CONSTRUCTION — it becomes a
    // synthesized '?' bind, never spliced text). "All value-bindable" here is the gate the lowerer keys
    // ACCEPT on; an IDENTIFIER or RAW_FRAGMENT hole MUST make it false.
    // ---------------------------------------------------------------------------------------------

    @Test
    void identifierSpliceViaPlusIsNotAllValueBindable() throws Exception {
        // "SELECT * FROM " + tableName — a runtime IDENTIFIER reaches the text. Not value-bindable.
        assertFalse(isAllValueBindable("\"SELECT * FROM \" + tableName"));
        JdbcSqlSkeleton skeleton = recover("\"SELECT * FROM \" + tableName");
        assertEquals(HoleKind.IDENTIFIER, holes(skeleton).getFirst().kind());
    }

    @Test
    void orderByIdentifierSpliceIsNotAllValueBindable() throws Exception {
        // WS-C Phase 3 Rung 3 audit fix (Findings 1): an ORDER BY name is a sort EXPRESSION (it may carry a
        // direction "col DESC"), so it is RAW_FRAGMENT (spliced verbatim under permissive, faithful to plain
        // JDBC) rather than IDENTIFIER (which %I would corrupt into 'column "col DESC" does not exist'). Still
        // NOT value-bindable, so strict still rejects (the security invariant this test name asserts).
        assertFalse(isAllValueBindable("\"SELECT id FROM t ORDER BY \" + tableName"));
        assertEquals(HoleKind.RAW_FRAGMENT, holes(recover("\"SELECT id FROM t ORDER BY \" + tableName")).getFirst().kind());
    }

    @Test
    void rawFragmentSpliceIsNotAllValueBindable() throws Exception {
        // A spliced WHERE-clause fragment in a non-value, non-identifier position (after "WHERE ") is a
        // raw predicate fragment — opaque, could carry anything. MUST be RAW_FRAGMENT, not value-bindable.
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE \" + status"));
        assertEquals(HoleKind.RAW_FRAGMENT, holes(recover("\"SELECT * FROM t WHERE \" + status")).getFirst().kind());
    }

    @Test
    void trailingConcatWithNoValueEdgeIsRawFragment() throws Exception {
        // "SELECT * FROM t " + status — the fragment ends in whitespace after a table; neither a proven
        // value nor identifier position. Fail-safe: RAW_FRAGMENT, never a silently-bound value.
        assertFalse(isAllValueBindable("\"SELECT * FROM t \" + status"));
    }

    @Test
    void stringFormatOfAnIdentifierIsNotAllValueBindable() throws Exception {
        // String.format("SELECT * FROM %s", tableName) — %s in an identifier position. Not value-bindable.
        assertFalse(isAllValueBindable("String.format(\"SELECT * FROM %s\", tableName)"));
        assertEquals(HoleKind.IDENTIFIER,
                holes(recover("String.format(\"SELECT * FROM %s\", tableName)")).getFirst().kind());
    }

    @Test
    void stringFormatRawFragmentPositionIsNotAllValueBindable() throws Exception {
        // %s right after "WHERE " is a raw predicate fragment position — opaque. RAW_FRAGMENT.
        assertFalse(isAllValueBindable("String.format(\"SELECT * FROM t WHERE %s\", status)"));
    }

    @Test
    void stringFormatWithUnmodeledConversionIsRawFragment() throws Exception {
        // %n (platform newline) is not a value conversion; the whole format is RAW_FRAGMENT (fail-safe),
        // never a partially-recovered skeleton that drops the literal.
        JdbcSqlSkeleton skeleton = recover("String.format(\"SELECT 1%n WHERE id = %s\", id)");
        assertFalse(skeleton.allHolesValueBindable());
        assertEquals(HoleKind.RAW_FRAGMENT, holes(skeleton).getFirst().kind());
    }

    @Test
    void stringBuilderAppendingAnIdentifierIsNotAllValueBindable() throws Exception {
        assertFalse(isAllValueBindable(
                "new StringBuilder(\"SELECT * FROM \").append(tableName).toString()"));
        assertEquals(HoleKind.IDENTIFIER, holes(recover(
                "new StringBuilder(\"SELECT * FROM \").append(tableName).toString()")).getFirst().kind());
    }

    @Test
    void stringJoinOverRuntimeValuesIsNotAllValueBindable() throws Exception {
        // String.join(",", ids ...) joins runtime VALUES into the text (NOT "?"s). There is no way to
        // prove those are placeholders, so the whole join is a RAW_FRAGMENT — never value-bindable.
        // ids is a List<Long>; map to strings so it type-checks as String.join arg.
        assertFalse(isAllValueBindable(
                "\"SELECT * FROM t WHERE id IN (\" + String.join(\",\", ids.stream().map(String::valueOf).toList()) + \")\""));
    }

    @Test
    void opaqueHelperResultInValuePositionWithoutEdgeIsRawFragment() throws Exception {
        // A method call that is NOT a recognized placeholder helper, in a non-value position, is opaque.
        assertFalse(isAllValueBindable("\"SELECT * FROM t \" + status.trim()"));
    }

    @Test
    void quotedStringValueSpliceIsNotValueBindable() throws Exception {
        // "... WHERE name = '" + status + "'" — the classic quoted-string splice. The preceding constant
        // ends in a single quote, NOT a clean value position; Rung 1 will NOT strip the quotes and bind
        // (that is subtle/escaping-prone). FAIL-SAFE: RAW_FRAGMENT, so it is rejected, never spliced.
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE name = '\" + status + \"'\""));
    }

    @Test
    void identifierSpliceViaStringFormatAfterUpdateIsNotValueBindable() throws Exception {
        // String.format("UPDATE %s SET x = 1", tableName) — %s in an identifier position after UPDATE.
        assertFalse(isAllValueBindable("String.format(\"UPDATE %s SET x = 1\", tableName)"));
        assertEquals(HoleKind.IDENTIFIER,
                holes(recover("String.format(\"UPDATE %s SET x = 1\", tableName)")).getFirst().kind());
    }

    @Test
    void stringFormatWithMoreArgsThanConversionsIsRawFragment() throws Exception {
        // A dropped argument (2 args, 1 conversion) cannot be faithfully modeled -> whole RAW_FRAGMENT.
        JdbcSqlSkeleton skeleton = recover("String.format(\"WHERE id = %d\", id, status)");
        assertFalse(skeleton.allHolesValueBindable());
    }

    @Test
    void opaqueStringBuilderVariableToStringIsNotValueBindable() throws Exception {
        // sb.toString() where sb is an opaque StringBuilder local (prior appends not visible here) — its
        // content cannot be proven, so it is RAW_FRAGMENT, never bound as a scalar value.
        Optional<JdbcSqlSkeleton> skeleton = JdbcSqlSkeletonRecognizer.recover(opaqueBuilderInitializer());
        assertTrue(skeleton.isPresent());
        assertFalse(skeleton.get().allHolesValueBindable());
    }

    /** Parses {@code "SELECT " + sb.toString()} where {@code sb} is an opaque StringBuilder local. */
    private ExpressionTree opaqueBuilderInitializer() throws Exception {
        String body = """
                class OpaqueSb {
                  void m() {
                    StringBuilder sb = new StringBuilder();
                    sb.append("anything");
                    String s = "SELECT * FROM t WHERE x = " + sb.toString();
                  }
                }
                """;
        Path sourceFile = tempDir.resolve("OpaqueSb.java");
        Files.writeString(sourceFile, body);
        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        ExpressionTree[] found = new ExpressionTree[1];
        for (var unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getName().toString().equals("s") && node.getInitializer() != null) {
                        found[0] = node.getInitializer();
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return found[0];
    }

    // ---- WS-C Phase 3 Rung 1 FIX: identifier-list / comma+paren splices (security finding, high) ----
    // An identifier spliced after a ',' or '(' that CONTINUES an identifier list (ORDER BY a, / GROUP BY
    // a, / SELECT a, / INSERT INTO t () must NOT be swallowed as a bound VALUE (which silently neuters the
    // dynamic sort/column and breaks D2's identifier-reject). It is an IDENTIFIER hole (not value-bindable).

    @Test
    void identifierSpliceAfterOrderByCommaIsNotValueBindable() throws Exception {
        // The security invariant (NOT value-bindable) holds. WS-C Phase 3 Rung 3 audit fix (Findings 1): a
        // later ORDER BY-list element is a sort EXPRESSION (it may carry "col DESC"), so it is RAW_FRAGMENT
        // (verbatim under permissive, faithful), NOT IDENTIFIER (which %I would corrupt) — strict still rejects.
        assertFalse(isAllValueBindable("\"SELECT id FROM t ORDER BY name, \" + tableName"));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("\"SELECT id FROM t ORDER BY name, \" + tableName")).getFirst().kind());
    }

    @Test
    void identifierSpliceAfterGroupByCommaIsNotValueBindable() throws Exception {
        // As above: a later GROUP BY-list element is a grouping expression -> RAW_FRAGMENT (not IDENTIFIER),
        // still not value-bindable (the security invariant the test name asserts).
        assertFalse(isAllValueBindable("\"SELECT count(*) FROM t GROUP BY a, \" + tableName"));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("\"SELECT count(*) FROM t GROUP BY a, \" + tableName")).getFirst().kind());
    }

    @Test
    void identifierSpliceInSelectListCommaIsNotValueBindable() throws Exception {
        assertFalse(isAllValueBindable("\"SELECT a, \" + tableName + \" FROM t\""));
        assertEquals(HoleKind.IDENTIFIER,
                holes(recover("\"SELECT a, \" + tableName + \" FROM t\"")).getFirst().kind());
    }

    @Test
    void identifierSpliceInInsertColumnListParenIsNotValueBindable() throws Exception {
        // INSERT INTO t (" + col + ") VALUES (?) — the '(' opens the COLUMN list, an identifier position.
        assertFalse(isAllValueBindable("\"INSERT INTO accounts (\" + tableName + \") VALUES (?)\""));
        assertEquals(HoleKind.IDENTIFIER,
                holes(recover("\"INSERT INTO accounts (\" + tableName + \") VALUES (?)\"")).getFirst().kind());
    }

    @Test
    void identifierSpliceAfterOrderByCommaViaStringFormatIsNotValueBindable() throws Exception {
        // String.format("... ORDER BY a, %s", col) — the %s after the ORDER BY-list comma is a sort
        // expression: RAW_FRAGMENT (Findings 1), not value-bindable (the security invariant the name asserts).
        assertFalse(isAllValueBindable("String.format(\"SELECT id FROM t ORDER BY a, %s\", tableName)"));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("String.format(\"SELECT id FROM t ORDER BY a, %s\", tableName)")).getFirst().kind());
    }

    @Test
    void valueSpliceInsideValuesRowCommaStillBinds() throws Exception {
        // GUARD against over-correction: a ',' INSIDE a VALUES (...) row is still a VALUE position — the
        // identifier-list refinement must not steal it. "INSERT INTO t (a, b) VALUES (1, " + id + ")".
        JdbcSqlSkeleton skeleton = recover("\"INSERT INTO t (a, b) VALUES (1, \" + id + \")\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals(HoleKind.VALUE, holes(skeleton).getFirst().kind());
    }

    // ---- WS-C Phase 3 Rung 1 FIX: open-quote / comment splices (security + correctness, LIKE/comment) ----
    // A splice while a string literal or comment is still OPEN is not a real value position — the recovered
    // '?' would land inside the literal/comment and desync the bind. FAIL-SAFE RAW_FRAGMENT.

    @Test
    void likeWildcardValueSpliceInsideOpenQuoteIsNotValueBindable() throws Exception {
        // "... WHERE name LIKE '%" + status — the '?' would land inside the open '...' literal (LIKE '%?').
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE name LIKE '%\" + status"));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("\"SELECT * FROM t WHERE name LIKE '%\" + status")).getFirst().kind());
    }

    @Test
    void likeWildcardValueSpliceWrappedInQuotesIsNotValueBindable() throws Exception {
        // "... LIKE '%" + frag + "%'" — splice inside an open single-quoted literal. RAW_FRAGMENT.
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE tier LIKE '%\" + status + \"%'\""));
    }

    @Test
    void valueSpliceInsideLineCommentIsNotValueBindable() throws Exception {
        // "SELECT * FROM t WHERE x=1 --" + status — the '?' would land in the '--' line comment.
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE x=1 --\" + status"));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("\"SELECT * FROM t WHERE x=1 --\" + status")).getFirst().kind());
    }

    @Test
    void valueSpliceInsideBlockCommentIsNotValueBindable() throws Exception {
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE x=1 /* \" + status"));
    }

    // ---- WS-C Phase 3 Rung 1 FIX: placeholder fuses with following constant (correctness, critical) ----
    // A recovered '?' immediately followed by a digit/identifier char fuses to '$100'/'$1x' once rewritten
    // to $n — an invalid/wrong positional parameter. FAIL-SAFE RAW_FRAGMENT (so it never emits desynced).

    @Test
    void valueSpliceFollowedByDigitConstantIsNotValueBindable() throws Exception {
        // "... WHERE id = " + count + "00" — recovered '?00' -> '$100' on PG ("there is no parameter $100").
        assertFalse(isAllValueBindable("\"SELECT id FROM t WHERE id = \" + count + \"00\""));
        assertEquals(HoleKind.RAW_FRAGMENT,
                holes(recover("\"SELECT id FROM t WHERE id = \" + count + \"00\"")).getFirst().kind());
    }

    @Test
    void valueSpliceFollowedByIdentifierCharIsNotValueBindable() throws Exception {
        // "... WHERE a = " + id + "x" -> '$1x' (malformed). And the missing-space "AND" follower.
        assertFalse(isAllValueBindable("\"SELECT id FROM t WHERE a = \" + id + \"x\""));
        assertFalse(isAllValueBindable("\"SELECT id FROM t WHERE a = \" + id + \"AND b = 1\""));
    }

    @Test
    void valueSpliceFollowedBySeparatorStillBinds() throws Exception {
        // GUARD: a following constant that begins with a NON-fusing char (a space/paren/operator) is fine.
        JdbcSqlSkeleton skeleton = recover("\"SELECT id FROM t WHERE id = \" + id + \" AND b = 1\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT id FROM t WHERE id = ? AND b = 1", skeleton.recoveredSql());
    }

    // ---- WS-C Phase 3 Rung 1 FIX: empty placeholder run -> IN () (correctness, high) ----
    // A zero-length placeholder run renders an invalid 'IN ()'. FAIL-SAFE RAW_FRAGMENT.

    @Test
    void emptyPlaceholderRunViaHelperIsNotValueBindable() throws Exception {
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE id IN (\" + placeholders(0) + \")\""));
    }

    @Test
    void emptyPlaceholderRunViaRepeatIsNotValueBindable() throws Exception {
        assertFalse(isAllValueBindable("\"SELECT * FROM t WHERE id IN (\" + \"?,\".repeat(0) + \")\""));
    }

    @Test
    void emptyPlaceholderRunViaJoinNCopiesIsNotValueBindable() throws Exception {
        assertFalse(isAllValueBindable(
                "\"SELECT * FROM t WHERE id IN (\" + String.join(\",\", java.util.Collections.nCopies(0, \"?\")) + \")\""));
    }

    @Test
    void twoAdjacentPlaceholderRunsThatWouldFuseAreNotValueBindable() throws Exception {
        // "(" + placeholders(2) + placeholders(3) + ")" -> recovered "(?,??,?,?)" -> "$2$3" fused on PG.
        // The trailing '?' of the first run abuts the leading '?' of the second; harden rejects it.
        assertFalse(isAllValueBindable(
                "\"SELECT * FROM t WHERE id IN (\" + placeholders(2) + placeholders(3) + \")\""));
    }

    @Test
    void plainConstantConcatHasNoHoleSoIsNotAFlippedAcceptCase() throws Exception {
        // javac folds "WHERE id IN (" + "?,?" + ")" into one String literal -> recover() returns empty
        // (the constant path owns it). Not a skeleton accept; the constant rule transpiles it directly.
        Optional<JdbcSqlSkeleton> skeleton =
                JdbcSqlSkeletonRecognizer.recover(initializers("\"WHERE id IN (\" + \"?,?\" + \")\"").getFirst());
        assertTrue(skeleton.isEmpty(), "an all-literal (folded) concatenation is the constant path, not a skeleton");
    }

    // ---------------------------------------------------------------------------------------------
    // ACCEPT cases — the skeleton recovers correctly and is all value-bindable.
    // ---------------------------------------------------------------------------------------------

    @Test
    void placeholderRunViaRecognizedHelperRecoversInListText() throws Exception {
        // "... IN (" + placeholders(3) + ")" -> recovered "... IN (?,?,?)", all value-bindable
        // (the '?'s bind by the statement's setXxx ordinals; no value reaches the text).
        JdbcSqlSkeleton skeleton = recover("\"SELECT * FROM t WHERE id IN (\" + placeholders(3) + \")\"");
        assertTrue(skeleton.hasHole());
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id IN (?,?,?)", skeleton.recoveredSql());
        assertEquals(HoleKind.PLACEHOLDER, holes(skeleton).getFirst().kind());
    }

    @Test
    void placeholderRunViaStringJoinNCopiesRecovers() throws Exception {
        JdbcSqlSkeleton skeleton = recover(
                "\"SELECT * FROM t WHERE id IN (\" + String.join(\",\", java.util.Collections.nCopies(2, \"?\")) + \")\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id IN (?,?)", skeleton.recoveredSql());
    }

    @Test
    void placeholderRunViaRepeatRecovers() throws Exception {
        // "?,".repeat(3) -> "?,?,?," then trailing handling: the run is "?,?,?," and the SQL closes with ")".
        // We model a clean "IN (?,?,?)" via a recognized helper above; repeat with a trailing comma is
        // still value-bindable (3 placeholders, bound by setXxx) and recovered byte-for-byte.
        JdbcSqlSkeleton skeleton = recover("\"... IN (\" + \"?,\".repeat(2) + \"?)\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("... IN (?,?,?)", skeleton.recoveredSql());
    }

    @Test
    void valueSpliceViaPlusRecoversAsValueHole() throws Exception {
        // "... WHERE id = " + id — a VALUE hole (design contract D3). Recovered "... WHERE id = ?",
        // the value becomes a synthesized bind; all value-bindable.
        JdbcSqlSkeleton skeleton = recover("\"SELECT * FROM t WHERE id = \" + id");
        assertTrue(skeleton.hasHole());
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id = ?", skeleton.recoveredSql());
        Hole hole = holes(skeleton).getFirst();
        assertEquals(HoleKind.VALUE, hole.kind());
    }

    @Test
    void valueSpliceViaStringFormatRecoversAsValueHole() throws Exception {
        // String.format("... WHERE id = %d", id) -> "... WHERE id = ?", a VALUE hole.
        JdbcSqlSkeleton skeleton = recover("String.format(\"SELECT * FROM t WHERE id = %d\", id)");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id = ?", skeleton.recoveredSql());
        assertEquals(HoleKind.VALUE, holes(skeleton).getFirst().kind());
    }

    @Test
    void valueSpliceViaStringBuilderRecoversAsValueHole() throws Exception {
        JdbcSqlSkeleton skeleton = recover(
                "new StringBuilder(\"SELECT * FROM t WHERE id = \").append(id).toString()");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id = ?", skeleton.recoveredSql());
        assertEquals(HoleKind.VALUE, holes(skeleton).getFirst().kind());
    }

    @Test
    void mixedPlaceholderRunAndValueSpliceRecoversBothHolesInOrder() throws Exception {
        // A realistic mix: a value splice (status) AND a placeholder run (the IN list). Both are
        // value-bindable; the recovered text interleaves the '?'s in source order.
        JdbcSqlSkeleton skeleton = recover(
                "\"SELECT * FROM t WHERE status = \" + status + \" AND id IN (\" + placeholders(2) + \")\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE status = ? AND id IN (?,?)", skeleton.recoveredSql());
        List<Hole> holes = holes(skeleton);
        assertEquals(HoleKind.VALUE, holes.get(0).kind());
        assertEquals(HoleKind.PLACEHOLDER, holes.get(1).kind());
    }

    @Test
    void valueSpliceViaStringFormatPrecededByConcatRecovers() throws Exception {
        // "WHERE id = " + String.format("%d", id) — the format's first %d must be edge-classified against
        // the PRECEDING '+' constant ("... = "), recovering a VALUE hole, not an unclassified RAW_FRAGMENT.
        JdbcSqlSkeleton skeleton = recover("\"SELECT * FROM t WHERE id = \" + String.format(\"%d\", id)");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("SELECT * FROM t WHERE id = ?", skeleton.recoveredSql());
        assertEquals(HoleKind.VALUE, holes(skeleton).getFirst().kind());
    }

    @Test
    void identifierSpliceViaStringFormatPrecededByConcatIsNotValueBindable() throws Exception {
        // "SELECT * " + String.format("FROM %s", tableName) — the preceding+internal text ("... FROM ")
        // puts %s in an identifier position. Must be IDENTIFIER (not value-bindable), even across the '+'.
        assertFalse(isAllValueBindable("\"SELECT * \" + String.format(\"FROM %s\", tableName)"));
    }

    @Test
    void valueSpliceInInsertValuesPositionRecovers() throws Exception {
        // INSERT ... VALUES (" + id + ", ?) — the spliced id is in a value position (after "(").
        JdbcSqlSkeleton skeleton = recover("\"INSERT INTO t (a, b) VALUES (\" + id + \", ?)\"");
        assertTrue(skeleton.allHolesValueBindable());
        assertEquals("INSERT INTO t (a, b) VALUES (?, ?)", skeleton.recoveredSql());
    }
}
