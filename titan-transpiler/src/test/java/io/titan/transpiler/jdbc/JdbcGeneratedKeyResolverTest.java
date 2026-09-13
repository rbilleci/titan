package io.titan.transpiler.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.introspect.SchemaModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the parser-free INSERT-target scanner and Catalog key-column resolver behind
 * I-7 generated-key recovery (§6.3). The scanner must never name the wrong table, and the resolver
 * must reject (empty) on any ambiguity — these are the correctness guarantees the deploy ITs rely on.
 */
class JdbcGeneratedKeyResolverTest {

    private static SchemaModel.ColumnMeta autoInc(String name) {
        return new SchemaModel.ColumnMeta(name, "BIGINT", false, null, null, null, null, null, List.of(), true);
    }

    private static SchemaModel.ColumnMeta plain(String name) {
        return new SchemaModel.ColumnMeta(name, "BIGINT", false, null, null, null);
    }

    private static SchemaModel oneTable(String schema, String name, SchemaModel.ColumnMeta... columns) {
        return new SchemaModel(List.of(new SchemaModel.TableMeta(schema, name, List.of(columns))));
    }

    // ---- the scanner names the right (or no) table ------------------------------------------

    @Test
    void scansBareTable() {
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO invoices (a) VALUES (?)"));
    }

    @Test
    void scansSchemaQualifiedTable() {
        assertEquals(Optional.of("billing.invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO billing.invoices (a) VALUES (?)"));
    }

    @Test
    void scansQuotedAndBacktickedIdentifiers() {
        assertEquals(Optional.of("billing.invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO \"billing\".\"invoices\" (a) VALUES (?)"));
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO `invoices` (`a`) VALUES (?)"));
    }

    @Test
    void scansThroughMysqlInsertModifierAndComments() {
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT IGNORE INTO invoices (a) VALUES (?)"));
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT /* hi */ INTO invoices /* c */ (a) VALUES (?)"));
    }

    @Test
    void scansInsertSelectAndSetForms() {
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO invoices (a) SELECT x FROM t"));
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget("INSERT INTO invoices SET a = ?"));
    }

    @Test
    void rejectsNonInsertOrUnscannableText() {
        assertTrue(JdbcGeneratedKeyResolver.debugScanInsertTarget("UPDATE invoices SET a = ?").isEmpty(),
                "an UPDATE is not an INSERT");
        assertTrue(JdbcGeneratedKeyResolver.debugScanInsertTarget(
                        "WITH x AS (SELECT 1) INSERT INTO invoices (a) VALUES (?)").isEmpty(),
                "a CTE-prefixed INSERT is out of scope (the text does not begin with INSERT)");
        assertTrue(JdbcGeneratedKeyResolver.debugScanInsertTarget(
                        "INSERT INTO db.billing.invoices (a) VALUES (?)").isEmpty(),
                "a three-part name is outside the catalog model");
    }

    @Test
    void doesNotMistakeIntoInsideAStringLiteralForTheTable() {
        // The word INTO appears inside a string literal in the VALUES; the scanner must still pick the
        // real table (invoices), not mis-scan from the literal.
        assertEquals(Optional.of("invoices"),
                JdbcGeneratedKeyResolver.debugScanInsertTarget(
                        "INSERT INTO invoices (note) VALUES ('INSERT INTO x')"));
    }

    // ---- the resolver reduces to a single auto-increment column, else empty -----------------

    @Test
    void resolvesSingleAutoIncrementColumn() {
        SchemaModel model = oneTable("billing", "invoices", autoInc("id"), plain("customer_id"));
        assertEquals(Optional.of("id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", null));
        // Schema-qualified reference also resolves.
        assertEquals(Optional.of("id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO billing.invoices (customer_id) VALUES (?)", null));
    }

    @Test
    void unquotedNameMatchesCaseInsensitively() {
        SchemaModel model = oneTable("billing", "invoices", autoInc("id"));
        assertEquals(Optional.of("id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO INVOICES (customer_id) VALUES (?)", null));
    }

    @Test
    void rejectsWhenTableHasNoAutoIncrementColumn() {
        SchemaModel model = oneTable("billing", "invoices", plain("id"), plain("customer_id"));
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", null));
    }

    @Test
    void rejectsWhenTableHasMoreThanOneAutoIncrementColumn() {
        SchemaModel model = oneTable("billing", "invoices", autoInc("id"), autoInc("seq"));
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", null),
                ">1 auto-increment column is ambiguous — reject");
    }

    @Test
    void rejectsWhenTableNotInModel() {
        SchemaModel model = oneTable("billing", "invoices", autoInc("id"));
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO orders (customer_id) VALUES (?)", null));
    }

    @Test
    void rejectsWhenTwoSchemasHaveTheSameTableAndReferenceDoesNotDisambiguate() {
        SchemaModel model = new SchemaModel(List.of(
                new SchemaModel.TableMeta("billing", "invoices", List.of(autoInc("id"))),
                new SchemaModel.TableMeta("archive", "invoices", List.of(autoInc("id")))));
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", null),
                "two same-named tables in different schemas — ambiguous, reject");
        // A schema-qualified reference disambiguates.
        assertEquals(Optional.of("id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO billing.invoices (customer_id) VALUES (?)", null));
    }

    @Test
    void deploySchemaDisambiguatesBareTableAcrossSchemasWithoutAQualifier() {
        // The exact edge the deploy-schema threading closes (TranspilationPipeline ->
        // JavaToTirLowerer -> JdbcStatementLowerer -> resolver, where defaultSchema = schemas.getFirst()).
        // A bare `INSERT INTO invoices` against a catalog holding the table in TWO schemas is ambiguous
        // with no default (the case above), but with the deploy schema set it resolves to the deploy
        // schema's table — the cross-schema same-named `archive.invoices` is then NOT matched.
        SchemaModel model = new SchemaModel(List.of(
                new SchemaModel.TableMeta("billing", "invoices", List.of(autoInc("id"))),
                new SchemaModel.TableMeta("archive", "invoices", List.of(autoInc("archive_id")))));
        assertEquals(Optional.of("id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", "billing"),
                "the deploy schema selects billing.invoices; archive.invoices is not matched");
        // Pointing the deploy schema at the other schema selects that schema's table instead (and its
        // own auto-increment column), proving the match is genuinely schema-directed — never the wrong key.
        assertEquals(Optional.of("archive_id"), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", "archive"));
    }

    @Test
    void deploySchemaRejectsBareTableThatLivesOnlyInADifferentSchema() {
        // A same-named table that exists ONLY in a non-deploy schema must NOT be matched for a bare
        // INSERT under the deploy schema: rejecting (empty) is the fail-safe outcome — a cross-schema
        // table is never used to emit a key for an INSERT that does not target it.
        SchemaModel model = oneTable("archive", "invoices", autoInc("id"));
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                model, "INSERT INTO invoices (customer_id) VALUES (?)", "billing"),
                "the deploy schema has no invoices table; the archive.invoices table must not be matched");
    }

    @Test
    void rejectsWhenNoCatalog() {
        assertEquals(Optional.empty(), JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                null, "INSERT INTO invoices (a) VALUES (?)", null));
    }

    @Test
    void quotedReferenceIsCaseSensitive() {
        // A double-quoted reference is case-sensitive: "INVOICES" must not match the lower-case catalog
        // name `invoices` (so it cannot resolve — reject rather than match the wrong-cased table).
        SchemaModel model = oneTable("billing", "invoices", autoInc("id"));
        assertFalse(JdbcGeneratedKeyResolver.resolveAutoIncrementKeyColumn(
                        model, "INSERT INTO \"INVOICES\" (a) VALUES (?)", null).isPresent(),
                "a quoted identifier is case-sensitive and must not fold to a differently-cased catalog name");
    }
}
