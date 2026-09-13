package io.titan.management.routines;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * No-Docker transpile inspection (Phase B2 verification). Asserts the gradle-emitted routine SQL
 * carries the FAITHFUL shapes the Phase-A capability work unlocked — the proof that the dogfooded
 * store advances Titan rather than routing around its gaps:
 *
 * <ul>
 *   <li>G2 read-decide-branch: {@code SELECT EXISTS (...) INTO} a boolean local + {@code IF}.</li>
 *   <li>MySQL early void return -> {@code LEAVE} of the routine label (the A4 LEAVE lowering).</li>
 *   <li>G3 atomic bump: {@code version = (... + 1)} in the conflict update.</li>
 *   <li>G4 native JSON: {@code CAST(... AS JSONB)} (PG) / {@code CAST(... AS JSON)} (MySQL).</li>
 *   <li>G1 PERFORM: the discarded row-lock pre-read in activateDeployment.</li>
 *   <li>G5: no {@code static_get} runtime machinery — command/status literals are folded.</li>
 * </ul>
 *
 * <p>The emitted SQL is read from the in-build transpile bundle (the same artifacts
 * {@code transpileManagementRoutines} packages into the jar); this test depends on that task having
 * run. The generator transpiles against the IN-BUILD titan-transpiler — no plugin, no mavenLocal.
 */
class ManagementRoutinesTranspileTest {

    // The generator writes the bundle under <sql.dir>/io/titan/management/sql/{dialect}/, with each
    // routine emitted individually under routines/<methodName>.sql.
    private static final Path BUNDLE_DIR = Path.of(
            System.getProperty("titan.management.routines.sql.dir", "build/generated/management-sql"))
            .resolve("io/titan/management/sql");

    private static String pg(String routine) throws IOException {
        return read(BUNDLE_DIR.resolve("postgresql").resolve("routines").resolve(routine + ".sql"));
    }

    private static String mysql(String routine) throws IOException {
        return read(BUNDLE_DIR.resolve("mysql").resolve("routines").resolve(routine + ".sql"));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path),
                "expected transpiled SQL at " + path.toAbsolutePath()
                        + " (run :titan-management-routines:transpileManagementRoutines first)");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void importModelDocumentEmitsFaithfulReadDecideBranchOnPostgres() throws IOException {
        String sql = pg("importModelDocument");
        // G2: existence read bound into a local + branch.
        assertTrue(sql.contains("SELECT EXISTS ("), "G2: SELECT EXISTS read");
        assertTrue(sql.matches("(?s).*\\)\\s+INTO\\s+v_already_applied.*"), "G2: ... INTO v_already_applied");
        assertTrue(sql.contains("IF COALESCE(v_already_applied, FALSE) THEN"), "G2: branch on the bound local");
        assertTrue(sql.contains("RETURN;"), "G2: early return short-circuit on replay");
        // G3: atomic server-side version bump in the conflict update.
        assertTrue(sql.contains("\"version\" = (\"management_drafts\".\"version\" + 1)"), "G3: version = version + 1");
        // G4: native JSONB cast for the document + metadata payloads.
        assertTrue(sql.contains("CAST(p_document AS JSONB)"), "G4: document ::jsonb cast");
        // The audit insert has NO ON CONFLICT: a non-short-circuited replay would double-write.
        // ATG-020: the INSERT target carries the declared schema ("management"); column lists stay bare.
        assertTrue(sql.contains("INSERT INTO \"management\".\"management_audit_outcomes\""), "audit insert present");
        assertFalse(auditInsertHasConflictClause(sql, "\"management\".\"management_audit_outcomes\""),
                "audit insert must carry NO conflict clause (replay-double-write is what proves the branch)");
        // G5: command name folded to a literal, no static_get machinery.
        assertTrue(sql.contains("'management.importModelDocument'"), "G5: folded command literal");
        assertFalse(sql.contains("static_get"), "G5: no static_get runtime lookup");

        // RD-2 (CLOSED): three DISTINCT hash parameters, each written to its own column.
        assertTrue(sql.contains("p_input_hash TEXT") && sql.contains("p_output_hash TEXT")
                        && sql.contains("p_document_hash TEXT"),
                "RD-2: separate input/output/document hash params");
        // draft document_hash <- p_document_hash; audit input/output <- p_input/p_output; idempotency
        // input_hash <- p_input_hash (load-bearing for replay/E020), outcome_hash <- p_output_hash.
        assertTrue(sql.contains("\"document_hash\" = p_document_hash"), "RD-2: draft document_hash <- documentHash");
        assertTrue(sql.contains("\"input_hash\", \"output_hash\", \"occurred_at\") VALUES (")
                        && sql.matches("(?s).*p_input_hash, p_output_hash, p_occurred_at.*"),
                "RD-2: audit input_hash<-input, output_hash<-output");
        // idempotency: input_hash is the canonical input; outcome_hash is the distinct OUTPUT hash.
        assertTrue(sql.matches("(?s).*p_idempotency_key, p_input_hash, 'success', p_output_hash, p_draft_id.*"),
                "RD-2: idempotency input_hash<-input (load-bearing), outcome_hash<-output (distinct)");
    }

    @Test
    void importModelDocumentEmitsLeaveForEarlyReturnOnMysql() throws IOException {
        String sql = mysql("importModelDocument");
        // MySQL: the early void return lowers to LEAVE of the labeled routine block.
        assertTrue(sql.contains("proc_body: BEGIN"), "MySQL: labeled routine block");
        assertTrue(sql.contains("LEAVE proc_body;"), "MySQL: early void return -> LEAVE (A4 fix)");
        assertFalse(sql.contains("RETURN;"), "MySQL: no bare RETURN; (the latent bug A4 closed)");
        // G3/G4 parity on MySQL.
        assertTrue(sql.contains("`version` = (`management_drafts`.`version` + 1)"), "G3: version = version + 1");
        assertTrue(sql.contains("CAST(p_document AS JSON)"), "G4: document CAST AS JSON");
        assertTrue(sql.contains("INTO v_already_applied"), "G2: SELECT EXISTS ... INTO local");
        assertFalse(sql.contains("static_get"), "G5: no static_get");
    }

    @Test
    void activateDeploymentEmitsTypedStatusFunctionWithReadDecideReturnChain() throws IOException {
        String pg = pg("activateDeployment");
        // RD-1 (CLOSED): a value-returning @StoredFunction (RETURNS INTEGER), not a void procedure.
        assertTrue(pg.contains("CREATE OR REPLACE FUNCTION \"management\".\"activate_deployment\""),
                "RD-1: activate_deployment is a FUNCTION");
        assertTrue(pg.contains("RETURNS INTEGER"), "RD-1: returns an int status code");
        // No discarded SELECT ... FOR UPDATE pre-read: a MySQL FUNCTION may not return a client result
        // set, so the function relies on lock-via-mutation (the success-path UPDATEs take the locks).
        assertFalse(pg.contains("FOR UPDATE"), "no client-result-set lock read in the function body");
        // RD-1: each precondition is a read-decide-RETURN of a DISTINCT E03x int code, server-side.
        assertTrue(pg.contains("RETURN 35;"), "RD-1: E035 unauthorized actor code");
        assertTrue(pg.contains("RETURN 30;"), "RD-1: E030 missing artifact code");
        assertTrue(pg.contains("RETURN 31;"), "RD-1: E031 hash mismatch code");
        assertTrue(pg.contains("RETURN 32;"), "E032: GAP-005 metadata-PATH suffix code (now server-side)");
        assertTrue(pg.contains("RETURN 36;"), "RD-1: E036 evidence mismatch code");
        assertTrue(pg.contains("RETURN 33;"), "RD-1: E033 not-verified code");
        assertTrue(pg.contains("RETURN 34;"), "RD-1: E034 terminal code");
        assertTrue(pg.contains("RETURN 0;"), "RD-1: success code after mutation");
        // E032 (final residual moved server-side): each metadata path column LIKE its fixed
        // '%titan-*.json' suffix — a constant suffix with no LIKE metacharacters is exactly endsWith.
        // All four AND-chained into ONE existence read; the artifact row is known to exist (E030).
        assertTrue(pg.contains("\"manifest_path\" LIKE '%titan-artifact.json'"), "E032: manifest_path suffix");
        assertTrue(pg.contains("\"object_inventory_path\" LIKE '%titan-object-inventory.json'"),
                "E032: object_inventory_path suffix");
        assertTrue(pg.contains("\"install_plan_path\" LIKE '%titan-install-plan.json'"),
                "E032: install_plan_path suffix");
        assertTrue(pg.contains("\"install_verification_path\" LIKE '%titan-install-verification.json'"),
                "E032: install_verification_path suffix");
        assertTrue(pg.contains("INTO v_metadata_paths_valid"), "E032: 4 ANDed LIKE conditions read into one local");
        // The chain reads scalars/existence into locals (G2) then branches.
        assertTrue(pg.contains("INTO v_artifact_exists"), "G2: existence read into local");
        assertTrue(pg.contains("INTO v_artifact_hash"), "G2: scalar read into local");
        assertTrue(pg.contains("INTO v_verification_status"), "G2: verification status read into local");
        // Supersede sibling ACTIVE deployments, then activate the target (the success path).
        assertTrue(pg.contains("SET \"status\" = 'superseded'"), "supersede siblings");
        assertTrue(pg.contains("SET \"status\" = 'active', \"activated_at\" = p_activated_at"), "activate target");
        assertFalse(pg.contains("static_get"), "G5: no static_get (codes folded to int literals)");

        // MySQL parity: a value-returning function staging path + FOR UPDATE + the same E03x returns.
        String mysql = mysql("activateDeployment");
        assertTrue(mysql.contains("CREATE FUNCTION `management`.`activate_deployment`"), "RD-1: MySQL FUNCTION");
        assertTrue(mysql.contains("RETURNS INT"), "RD-1: MySQL returns int");
        assertFalse(mysql.contains("FOR UPDATE"), "MySQL FUNCTION must not emit a client-result-set lock read");
        assertTrue(mysql.contains("SET __titan_return_value = 35;"), "RD-1: MySQL E035 staged return");
        assertTrue(mysql.contains("SET __titan_return_value = 32;"), "E032: MySQL staged return (server-side)");
        assertTrue(mysql.contains("SET __titan_return_value = 0;"), "RD-1: MySQL success staged return");
        // E032: the same four AND-chained LIKE suffix conditions emit on MySQL (backtick-quoted).
        assertTrue(mysql.contains("`manifest_path` LIKE '%titan-artifact.json'"), "E032: MySQL manifest_path suffix");
        assertTrue(mysql.contains("`install_verification_path` LIKE '%titan-install-verification.json'"),
                "E032: MySQL install_verification_path suffix");
    }

    @Test
    void seedsAndTransitionsEmitWithoutRuntimeDependency() throws IOException {
        for (String routine : new String[] {
                "seedDraft", "seedArtifactRef", "seedDeployment",
                "transitionDraftStatus", "transitionDeploymentStatus"}) {
            String pg = pg(routine);
            String mysql = mysql(routine);
            assertFalse(pg.contains("static_get"), routine + " (pg): no static_get");
            assertFalse(mysql.contains("static_get"), routine + " (mysql): no static_get");
        }
        // The blind upserts use ON CONFLICT / ON DUPLICATE KEY for DO-NOTHING semantics.
        assertTrue(pg("seedDraft").contains("ON CONFLICT (\"id\") DO UPDATE SET \"id\" = \"management_drafts\".\"id\""),
                "seedDraft: no-op self-assignment DO-NOTHING");
        assertTrue(mysql("seedDraft").contains("ON DUPLICATE KEY UPDATE `id` = `management_drafts`.`id`"),
                "seedDraft: MySQL no-op self-assignment");
    }

    // A conflict clause keyed off the audit table would let a replay no-op the audit write — which
    // is exactly what must NOT happen. Detect any ON CONFLICT/ON DUPLICATE KEY following the audit
    // INSERT (before the next INSERT/end).
    private static boolean auditInsertHasConflictClause(String sql, String auditTable) {
        int auditIdx = sql.indexOf("INSERT INTO " + auditTable);
        if (auditIdx < 0) {
            return false;
        }
        int nextInsert = sql.indexOf("INSERT INTO", auditIdx + 1);
        String auditStatement = nextInsert < 0 ? sql.substring(auditIdx) : sql.substring(auditIdx, nextInsert);
        return auditStatement.contains("ON CONFLICT") || auditStatement.contains("ON DUPLICATE KEY");
    }
}
