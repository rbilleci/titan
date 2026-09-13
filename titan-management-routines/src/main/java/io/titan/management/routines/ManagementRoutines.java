package io.titan.management.routines;

import titan.dsl.Column;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.StoredFunction;
import titan.dsl.StoredProcedure;
import titan.dsl.Table;

import static titan.dsl.DSL.*;

/**
 * The dogfooded management store's MUTATION ROUTINES (B-8 / GAP-006, Phase B2). These are authored
 * against hand-written titan-dsl {@link Table} definitions and transpiled to PostgreSQL + MySQL by
 * the in-build titan-transpiler (the {@code transpileManagementRoutines} Gradle task drives
 * {@code TranspilationPipeline.transpile} directly — no plugin, no mavenLocal); the emitted SQL is
 * packaged as an ordered bundle (schema DDL first, then routines) that titan-management ships and
 * {@code CALL}s from its JDBC transaction envelope (Phase B4).
 *
 * <p>READS are NOT routines — the JDBC adapter runs {@code draft(id)} / {@code drafts()} / … as
 * plain parameterized SELECTs. Only the MUTATIONS live here.
 *
 * <p>The routines have no {@code BEGIN/COMMIT}; the transaction envelope is the caller's (the JDBC
 * adapter opens the transaction around the {@code CALL}).
 *
 * <p>Phase-A gaps exercised (all five closed before this module was written):
 * <ul>
 *   <li><b>G2</b> (read-into-local + branch): {@link #importModelDocument} reads the idempotency
 *       key into a boolean via {@code fetchExistsValue()} and early-returns on replay; on MySQL the
 *       early void return lowers to a {@code LEAVE} of the routine label. {@link #activateDeployment}
 *       is a value-returning {@code @StoredFunction} (RD-1) that chains scalar/existence reads into
 *       locals and {@code return <E03x code>;} for each failed precondition — read-decide-RETURN
 *       entirely server-side.</li>
 *   <li><b>G3</b> (expression-valued set): the atomic {@code version = version + 1} bump in the
 *       draft ON CONFLICT DO UPDATE — {@code set(VERSION, VERSION.add(1))}.</li>
 *   <li><b>G4</b> (JSON cast): {@code DOCUMENT}/{@code METADATA} are {@link SQLType#JSON} columns,
 *       so the String payload is cast to {@code ::jsonb} (PG) / {@code CAST(… AS JSON)} (MySQL).</li>
 *   <li><b>G1</b> (PERFORM): a discarded {@code SELECT … FOR UPDATE} lowers to {@code PERFORM} on PG
 *       (proven by the spike). {@link #activateDeployment} no longer uses it — as a value-returning
 *       {@code @StoredFunction} it relies on lock-via-mutation instead (a MySQL FUNCTION may not emit
 *       a client result set), but the lowering remains available to procedures.</li>
 *   <li><b>G5</b> (constant folding): the command-name / status / outcome literals are referenced
 *       as {@code static final String} constants and fold to literals (no {@code static_get}).</li>
 * </ul>
 */
public final class ManagementRoutines {

    // Package-private (NOT private): the lowerer resolves select(TABLE.COLUMN) references through
    // the field/column declarations, and private static table fields are not resolvable as DSL
    // table sources (matches the spike's and GoldenSqlCorpus's package-private convention).
    static final DraftsTable DRAFTS = new DraftsTable();
    static final ArtifactRefsTable ARTIFACT_REFS = new ArtifactRefsTable();
    static final DeploymentsTable DEPLOYMENTS = new DeploymentsTable();
    static final IdempotencyTable IDEMPOTENCY = new IdempotencyTable();
    static final AuditTable AUDIT = new AuditTable();

    // G5: constant-folded to SQL literals in routine bodies (no titan_runtime static_get).
    // Package-private (not private) for the same lowerer-resolution reason as the table fields.
    static final String COMMAND_IMPORT = "management.importModelDocument";
    static final String AUDIT_STATUS_SUCCESS = "success";
    static final String DRAFT_STATUS_IMPORTED = "imported";
    static final String OUTCOME_SUCCESS = "success";
    static final String VERIFICATION_PASSED = "passed";
    static final String DEPLOYMENT_ACTIVE = "active";
    static final String DEPLOYMENT_SUPERSEDED = "superseded";
    static final String DEPLOYMENT_PENDING_ACTIVATION = "pendingActivation";
    static final String ACTOR_ROLE_ADMIN = "admin";
    static final String ACTOR_ROLE_PLATFORM = "platform";

    // ------------------------------------------------------------------
    // activateDeployment status codes (RD-1) — the SINGLE SOURCE OF TRUTH for the int code <-> the
    // TITAN-MGMT-E03x domain error mapping. The {@link #activateDeployment} @StoredFunction returns
    // one of these ints; the JDBC adapter maps each back to its TITAN-MGMT-E03x DeploymentActivation
    // failure. These are same-class {@code static final int} constants, so G5 folds each reference in
    // the routine body to its int LITERAL in the emitted SQL (no static_get runtime dependency).
    //
    // The mirror in titan-management's {@code DeploymentActivationStatus} MUST stay in lockstep (it
    // cannot {@code import} this class — titan-management has no compile edge to titan-management-
    // routines by design, TG-BLK-009 — so the values are duplicated there and guarded by a test).
    // ------------------------------------------------------------------
    static final int ACTIVATION_OK = 0;                  // all preconditions pass -> supersede + activate
    static final int ACTIVATION_E030_MISSING_ARTIFACT = 30;
    static final int ACTIVATION_E031_HASH_MISMATCH = 31;
    // E032 (GAP-005 metadata PATHS) is now SERVER-SIDE too: each of the four metadata path columns
    // must end with a fixed titan-*.json filename — a path-SUFFIX match expressed faithfully via the
    // DSL's Column.like('%titan-*.json') (the '.' is a literal in SQL LIKE; only % and _ are
    // wildcards, so '%titan-artifact.json' is exactly endsWith). All 7 activation preconditions now
    // live in the routine; the adapter is a pure pass-through. See activateDeployment's javadoc.
    static final int ACTIVATION_E032_METADATA_PATHS = 32;
    static final int ACTIVATION_E033_NOT_VERIFIED = 33;
    static final int ACTIVATION_E034_TERMINAL = 34;
    static final int ACTIVATION_E035_UNAUTHORIZED = 35;
    static final int ACTIVATION_E036_EVIDENCE_MISMATCH = 36;

    private ManagementRoutines() {
    }

    // ------------------------------------------------------------------
    // importModelDocument — the must-have FAITHFUL idempotent-replay command.
    // ------------------------------------------------------------------

    /**
     * FAITHFUL idempotent replay (the keystone, upgraded with G2): read the idempotency key into a
     * boolean; if it is already present, RETURN without mutating (server-side short-circuit — on
     * MySQL the early void return lowers to {@code LEAVE}). Otherwise upsert the draft (native JSON
     * document via G4; atomic {@code version = version + 1} via G3 in the conflict update), append
     * the audit row (NO {@code ON CONFLICT} — so a non-short-circuited replay would double-write,
     * which is exactly what proves the branch fired), then record the idempotency key.
     *
     * <p>RD-2 (CLOSED): the three contract hashes are now DISTINCT parameters, each written to its own
     * column — the draft {@code document_hash} receives {@code documentHash}; the audit
     * {@code input_hash}/{@code output_hash} receive {@code inputHash}/{@code outputHash}; the idempotency
     * {@code input_hash}/{@code outcome_hash} receive {@code inputHash}/{@code outputHash}. The idempotency
     * {@code input_hash} remains the CANONICAL INPUT hash (load-bearing for replay / E020 mismatch
     * detection), and the durable {@code outcome_hash} can now differ from it (it is the OUTPUT hash),
     * so the dogfooded store is byte-faithful to the file store on hashes.
     */
    @StoredProcedure
    public static void importModelDocument(
            String draftId,
            String workspaceId,
            String modelId,
            int version,
            String idempotencyKey,
            String document,
            String inputHash,
            String outputHash,
            String documentHash,
            String createdAt,
            String updatedAt,
            int auditSequence,
            String auditId,
            String requestId,
            String occurredAt) {
        // (b) G2: read-decide-branch. If this key was already applied, replay is a no-op — return
        // BEFORE any mutation. The audit append below has no ON CONFLICT guard, so reaching it on a
        // replay would add a second audit row; one-audit-row-per-key proves the short-circuit.
        boolean alreadyApplied = select(IDEMPOTENCY.IDEMPOTENCY_KEY)
                .from(IDEMPOTENCY)
                .where(IDEMPOTENCY.COMMAND_NAME.eq(COMMAND_IMPORT)
                        .and(IDEMPOTENCY.SCOPE.eq(workspaceId))
                        .and(IDEMPOTENCY.IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .fetchExistsValue();
        if (alreadyApplied) {
            return;
        }

        // (c) upsert the draft. The row lock is acquired implicitly by this upsert of the draft
        // row. G4: DOCUMENT/METADATA are JSON columns (String payload -> ::jsonb / CAST AS JSON).
        // G3: ON CONFLICT DO UPDATE bumps version = version + 1 server-side.
        insertInto(DRAFTS)
                .set(DRAFTS.ID, draftId)
                .set(DRAFTS.WORKSPACE_ID, workspaceId)
                .set(DRAFTS.MODEL_ID, modelId)
                .set(DRAFTS.VERSION, version)
                .set(DRAFTS.STATUS, DRAFT_STATUS_IMPORTED)
                .set(DRAFTS.DOCUMENT, document)
                .set(DRAFTS.DOCUMENT_HASH, documentHash)
                .set(DRAFTS.CREATED_AT, createdAt)
                .set(DRAFTS.UPDATED_AT, updatedAt)
                .set(DRAFTS.METADATA, "{}")
                .onConflict(DRAFTS.ID)
                .doUpdate()
                .set(DRAFTS.VERSION, DRAFTS.VERSION.add(1))
                .set(DRAFTS.STATUS, DRAFT_STATUS_IMPORTED)
                .set(DRAFTS.DOCUMENT, document)
                .set(DRAFTS.DOCUMENT_HASH, documentHash)
                .set(DRAFTS.UPDATED_AT, updatedAt)
                .execute();

        // (d) append the audit outcome. NO ON CONFLICT: a replay that failed to short-circuit would
        // double-write here. Reached exactly once per key precisely because (b) returned on replay.
        insertInto(AUDIT)
                .set(AUDIT.ID, auditId)
                .set(AUDIT.SEQUENCE, auditSequence)
                .set(AUDIT.COMMAND_NAME, COMMAND_IMPORT)
                .set(AUDIT.STATUS, AUDIT_STATUS_SUCCESS)
                .set(AUDIT.REQUEST_ID, requestId)
                .set(AUDIT.IDEMPOTENCY_KEY, idempotencyKey)
                .set(AUDIT.INPUT_HASH, inputHash)
                .set(AUDIT.OUTPUT_HASH, outputHash)
                .set(AUDIT.OCCURRED_AT, occurredAt)
                .execute();

        // (e) record the idempotency key (composite PK target; first-apply only — this row's
        // absence is what (b) tested, so on the happy path it always inserts).
        insertInto(IDEMPOTENCY)
                .set(IDEMPOTENCY.COMMAND_NAME, COMMAND_IMPORT)
                .set(IDEMPOTENCY.SCOPE, workspaceId)
                .set(IDEMPOTENCY.IDEMPOTENCY_KEY, idempotencyKey)
                .set(IDEMPOTENCY.INPUT_HASH, inputHash)
                .set(IDEMPOTENCY.OUTCOME_STATUS, OUTCOME_SUCCESS)
                .set(IDEMPOTENCY.OUTCOME_HASH, outputHash)
                .set(IDEMPOTENCY.RESULT_REF, draftId)
                .set(IDEMPOTENCY.CREATED_AT, createdAt)
                .onConflict(IDEMPOTENCY.COMMAND_NAME, IDEMPOTENCY.SCOPE, IDEMPOTENCY.IDEMPOTENCY_KEY)
                // DO-NOTHING semantics: the DSL has no doNothing(), and an empty conflict update is
                // rejected, so a no-op self-assignment (command_name = command_name, via the G3
                // expression-valued set) renders ON CONFLICT DO UPDATE SET col = col / ON DUPLICATE
                // KEY UPDATE col = col — idempotent on replay on both dialects.
                .doUpdate()
                .set(IDEMPOTENCY.COMMAND_NAME, IDEMPOTENCY.COMMAND_NAME)
                .execute();
    }

    // ------------------------------------------------------------------
    // Seeds — blind INSERT ON CONFLICT DO NOTHING (EASY).
    // ------------------------------------------------------------------

    @StoredProcedure
    public static void seedDraft(
            String draftId,
            String workspaceId,
            String modelId,
            int version,
            String status,
            String document,
            String documentHash,
            String createdAt,
            String updatedAt) {
        insertInto(DRAFTS)
                .set(DRAFTS.ID, draftId)
                .set(DRAFTS.WORKSPACE_ID, workspaceId)
                .set(DRAFTS.MODEL_ID, modelId)
                .set(DRAFTS.VERSION, version)
                .set(DRAFTS.STATUS, status)
                .set(DRAFTS.DOCUMENT, document)
                .set(DRAFTS.DOCUMENT_HASH, documentHash)
                .set(DRAFTS.CREATED_AT, createdAt)
                .set(DRAFTS.UPDATED_AT, updatedAt)
                .set(DRAFTS.METADATA, "{}")
                .onConflict(DRAFTS.ID)
                // DO-NOTHING via no-op self-assignment (see importModelDocument note).
                .doUpdate()
                .set(DRAFTS.ID, DRAFTS.ID)
                .execute();
    }

    @StoredProcedure
    public static void seedArtifactRef(
            String artifactRefId,
            String artifactId,
            String artifactHash,
            String manifestPath,
            String objectInventoryPath,
            String installPlanPath,
            String installVerificationPath,
            String verificationStatus) {
        insertInto(ARTIFACT_REFS)
                .set(ARTIFACT_REFS.ID, artifactRefId)
                .set(ARTIFACT_REFS.ARTIFACT_ID, artifactId)
                .set(ARTIFACT_REFS.ARTIFACT_HASH, artifactHash)
                .set(ARTIFACT_REFS.MANIFEST_PATH, manifestPath)
                .set(ARTIFACT_REFS.OBJECT_INVENTORY_PATH, objectInventoryPath)
                .set(ARTIFACT_REFS.INSTALL_PLAN_PATH, installPlanPath)
                .set(ARTIFACT_REFS.INSTALL_VERIFICATION_PATH, installVerificationPath)
                .set(ARTIFACT_REFS.VERIFICATION_STATUS, verificationStatus)
                .onConflict(ARTIFACT_REFS.ID)
                .doUpdate()
                .set(ARTIFACT_REFS.ID, ARTIFACT_REFS.ID)
                .execute();
    }

    @StoredProcedure
    public static void seedDeployment(
            String deploymentId,
            String workspaceId,
            String artifactRefId,
            String environment,
            String status,
            String requestedAt) {
        insertInto(DEPLOYMENTS)
                .set(DEPLOYMENTS.ID, deploymentId)
                .set(DEPLOYMENTS.WORKSPACE_ID, workspaceId)
                .set(DEPLOYMENTS.ARTIFACT_REF_ID, artifactRefId)
                .set(DEPLOYMENTS.ENVIRONMENT, environment)
                .set(DEPLOYMENTS.STATUS, status)
                .set(DEPLOYMENTS.REQUESTED_AT, requestedAt)
                .onConflict(DEPLOYMENTS.ID)
                .doUpdate()
                .set(DEPLOYMENTS.ID, DEPLOYMENTS.ID)
                .execute();
    }

    // ------------------------------------------------------------------
    // Status transitions — parameterized UPDATEs (EASY).
    // ------------------------------------------------------------------

    @StoredProcedure
    public static void transitionDraftStatus(String draftId, String status, String updatedAt) {
        update(DRAFTS)
                .set(DRAFTS.STATUS, status)
                .set(DRAFTS.UPDATED_AT, updatedAt)
                .where(DRAFTS.ID.eq(draftId))
                .execute();
    }

    @StoredProcedure
    public static void transitionDeploymentStatus(String deploymentId, String status) {
        update(DEPLOYMENTS)
                .set(DEPLOYMENTS.STATUS, status)
                .where(DEPLOYMENTS.ID.eq(deploymentId))
                .execute();
    }

    // ------------------------------------------------------------------
    // activateDeployment — the flagged-risk routine (read-decide-branch + supersede + activate).
    // ------------------------------------------------------------------

    /**
     * activateDeployment (RD-1 CLOSED): a value-returning {@code @StoredFunction} that performs the
     * FULL precondition read-decide-RETURN chain server-side and returns a typed {@code int} status
     * code (0 = success, or an {@code ACTIVATION_E03x} code for the first failed precondition). The
     * JDBC adapter maps the code back to the matching {@code TITAN-MGMT-E03x} domain error, so the
     * read-decide-return lives in ONE place (the routine) instead of being re-implemented caller-side.
     *
     * <p>Uses the RD-1 spike's proven Shape-3 multi-branch shape (docs/archived/rd1-function-return-spike.md):
     * each precondition reads a scalar/existence into a local (G2 {@code fetchExistsValue()} /
     * {@code fetchScalar()}) and {@code if (failCond) return <code>;} returns a distinct code, then
     * the success path does the supersede + activate UPDATEs and {@code return ACTIVATION_OK;}.
     *
     * <p>Checks evaluated SERVER-SIDE, in the file store's order (all SEVEN now live here — the
     * adapter is a pure pass-through):
     * <ol>
     *   <li><b>E035</b> unauthorized actor — {@code actorRole} not in {admin, platform}.</li>
     *   <li><b>E030</b> missing artifact ref — no {@code management_artifact_refs} row for the id.</li>
     *   <li><b>E031</b> artifact hash mismatch — {@code artifact_hash != expectedArtifactHash}.</li>
     *   <li><b>E032</b> GAP-005 metadata PATHS — each of the four metadata path columns must end with
     *       its fixed {@code titan-*.json} filename.</li>
     *   <li><b>E036</b> GAP-005 evidence mismatch — the seven evidence columns vs. the expected.</li>
     *   <li><b>E033</b> verification not passed — {@code verification_status != 'passed'}.</li>
     *   <li><b>E034</b> terminal deployment — existing row whose status is neither
     *       {@code pendingActivation} nor {@code active}.</li>
     * </ol>
     *
     * <p><b>E032 is now SERVER-SIDE too</b> (the final residual that moved): the file store's E032 is a
     * PATH-SUFFIX match — each of the four metadata path columns must {@code endsWith} a fixed
     * {@code titan-*.json} filename. The DSL expresses this faithfully via
     * {@code Column.like("%titan-*.json")}: the suffix is a constant with NO LIKE metacharacters (the
     * {@code '.'} is a literal in SQL {@code LIKE}; only {@code %} and {@code _} are wildcards), so
     * {@code path LIKE '%titan-artifact.json'} is exactly {@code endsWith}-equivalent on both
     * dialects. A single existence read AND-chains all four {@code LIKE} conditions; if no matching
     * row exists (the artifact row is known to exist by the E030 check above), the metadata paths are
     * wrong and the function returns E032. Every E03x precondition is now data the routine reads from
     * its inputs + the {@code management_artifact_refs}/{@code management_deployments} columns.
     */
    @StoredFunction
    public static int activateDeployment(
            String deploymentId,
            String workspaceId,
            String artifactRefId,
            String environment,
            String activatedAt,
            String actorRole,
            String expectedArtifactHash,
            String expectedPackageMode,
            String expectedDialect,
            String expectedManifestContentHash,
            String expectedObjectInventoryHash,
            String expectedInstallPlanHash,
            String expectedInstallVerificationHash,
            String expectedSourceInputsHash) {
        // No explicit advisory row-lock pre-read here: a bare discarded SELECT ... FOR UPDATE is
        // legal in a PROCEDURE but a MySQL FUNCTION may not emit a client result set (ER 1415), so we
        // rely on lock-via-mutation — the supersede + activate UPDATEs on the success path take the
        // row locks, exactly as importModelDocument's upsert does. The precondition reads below are
        // SELECT ... INTO locals (no result set), legal in both dialects' functions.

        // (E035) unauthorized actor: the role must be admin or platform (pure input compare, no read).
        boolean authorized = actorRole.equals(ACTOR_ROLE_ADMIN) || actorRole.equals(ACTOR_ROLE_PLATFORM);
        if (!authorized) {
            return ACTIVATION_E035_UNAUTHORIZED;
        }

        // (E030) the artifact ref must exist.
        boolean artifactExists = select(ARTIFACT_REFS.ID)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchExistsValue();
        if (!artifactExists) {
            return ACTIVATION_E030_MISSING_ARTIFACT;
        }

        // (E031) the recorded artifact hash must match the caller's expected hash.
        String artifactHash = select(ARTIFACT_REFS.ARTIFACT_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedArtifactHash.equals(artifactHash)) {
            return ACTIVATION_E031_HASH_MISMATCH;
        }

        // (E032) GAP-005 metadata PATHS: each of the four metadata path columns must end with its
        // fixed titan-*.json filename. The suffix is a constant with NO LIKE metacharacters (the '.'
        // is a literal in SQL LIKE; only % and _ are wildcards), so `path LIKE '%titan-artifact.json'`
        // is exactly endsWith-equivalent on both dialects. AND-chain the four LIKE conditions into one
        // existence read; the artifact row is known to exist (E030 above), so a missing match means the
        // paths are wrong -> E032. (This is the final residual that moved server-side; the adapter is
        // now a pure pass-through.)
        boolean metadataPathsValid = select(ARTIFACT_REFS.ID)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId)
                        .and(ARTIFACT_REFS.MANIFEST_PATH.like("%titan-artifact.json"))
                        .and(ARTIFACT_REFS.OBJECT_INVENTORY_PATH.like("%titan-object-inventory.json"))
                        .and(ARTIFACT_REFS.INSTALL_PLAN_PATH.like("%titan-install-plan.json"))
                        .and(ARTIFACT_REFS.INSTALL_VERIFICATION_PATH.like("%titan-install-verification.json")))
                .fetchExistsValue();
        if (!metadataPathsValid) {
            return ACTIVATION_E032_METADATA_PATHS;
        }

        // (E036) GAP-005 evidence: every recorded evidence column must match the caller's expected
        // value. Read each column into a local and compare; the first mismatch returns E036.
        String packageMode = select(ARTIFACT_REFS.PACKAGE_MODE)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedPackageMode.equals(packageMode)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String dialect = select(ARTIFACT_REFS.DIALECT)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedDialect.equals(dialect)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String manifestContentHash = select(ARTIFACT_REFS.MANIFEST_CONTENT_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedManifestContentHash.equals(manifestContentHash)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String objectInventoryHash = select(ARTIFACT_REFS.OBJECT_INVENTORY_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedObjectInventoryHash.equals(objectInventoryHash)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String installPlanHash = select(ARTIFACT_REFS.INSTALL_PLAN_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedInstallPlanHash.equals(installPlanHash)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String installVerificationHash = select(ARTIFACT_REFS.INSTALL_VERIFICATION_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedInstallVerificationHash.equals(installVerificationHash)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }
        String sourceInputsHash = select(ARTIFACT_REFS.SOURCE_INPUTS_HASH)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!expectedSourceInputsHash.equals(sourceInputsHash)) {
            return ACTIVATION_E036_EVIDENCE_MISMATCH;
        }

        // (E033) the artifact's install verification must have passed.
        String verificationStatus = select(ARTIFACT_REFS.VERIFICATION_STATUS)
                .from(ARTIFACT_REFS)
                .where(ARTIFACT_REFS.ID.eq(artifactRefId))
                .fetchScalar();
        if (!verificationStatus.equals(VERIFICATION_PASSED)) {
            return ACTIVATION_E033_NOT_VERIFIED;
        }

        // (E034) a terminal deployment cannot be activated: an existing row whose status is neither
        // pendingActivation nor active is terminal. Read whether a NON-terminal row exists for the id;
        // if the row exists (it does on this path — the adapter only activates known deployments) but
        // is NOT in a startable state, reject. We test the inverse existence: "does a startable row
        // exist for this id?" — if not, and a row does exist, it is terminal.
        boolean startableRowExists = select(DEPLOYMENTS.ID)
                .from(DEPLOYMENTS)
                .where(DEPLOYMENTS.ID.eq(deploymentId)
                        .and(DEPLOYMENTS.STATUS.eq(DEPLOYMENT_PENDING_ACTIVATION)
                                .or(DEPLOYMENTS.STATUS.eq(DEPLOYMENT_ACTIVE))))
                .fetchExistsValue();
        boolean anyRowExists = select(DEPLOYMENTS.ID)
                .from(DEPLOYMENTS)
                .where(DEPLOYMENTS.ID.eq(deploymentId))
                .fetchExistsValue();
        if (anyRowExists && !startableRowExists) {
            return ACTIVATION_E034_TERMINAL;
        }

        // All preconditions passed -> supersede sibling ACTIVE deployments in this workspace +
        // environment (conditional bulk UPDATE), then activate the target.
        update(DEPLOYMENTS)
                .set(DEPLOYMENTS.STATUS, DEPLOYMENT_SUPERSEDED)
                .where(DEPLOYMENTS.WORKSPACE_ID.eq(workspaceId)
                        .and(DEPLOYMENTS.ENVIRONMENT.eq(environment))
                        .and(DEPLOYMENTS.STATUS.eq(DEPLOYMENT_ACTIVE)))
                .execute();

        update(DEPLOYMENTS)
                .set(DEPLOYMENTS.STATUS, DEPLOYMENT_ACTIVE)
                .set(DEPLOYMENTS.ACTIVATED_AT, activatedAt)
                .where(DEPLOYMENTS.ID.eq(deploymentId))
                .execute();

        return ACTIVATION_OK;
    }

    // ------------------------------------------------------------------
    // Hand-authored DSL table definitions (the emitter reads these for column shapes; DDL is the
    // hand-authored bootstrap seam in src/main/resources/ddl, NOT emitted from these).
    // ------------------------------------------------------------------

    static final class DraftsTable extends Table<Object> {
        final Column<String> ID = column("id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> WORKSPACE_ID = column("workspace_id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> MODEL_ID = column("model_id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<Integer> VERSION = column("version", SQLType.INTEGER, Nullability.NOT_NULL);
        final Column<String> STATUS = column("status", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> DOCUMENT = column("document", SQLType.JSON, Nullability.NOT_NULL);
        final Column<String> DOCUMENT_HASH = column("document_hash", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> CREATED_AT = column("created_at", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> UPDATED_AT = column("updated_at", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> METADATA = column("metadata", SQLType.JSON, Nullability.NOT_NULL);

        DraftsTable() {
            super("management_drafts", "management");
        }
    }

    static final class ArtifactRefsTable extends Table<Object> {
        final Column<String> ID = column("id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> ARTIFACT_ID = column("artifact_id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> ARTIFACT_HASH = column("artifact_hash", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> MANIFEST_PATH = column("manifest_path", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> OBJECT_INVENTORY_PATH = column("object_inventory_path", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> INSTALL_PLAN_PATH = column("install_plan_path", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> INSTALL_VERIFICATION_PATH = column("install_verification_path", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> VERIFICATION_STATUS = column("verification_status", SQLType.TEXT, Nullability.NOT_NULL);
        // GAP-005 evidence columns (nullable on a freshly-seeded ref; the activate function's E036
        // check reads them and compares against the caller's expected values).
        final Column<String> PACKAGE_MODE = column("package_mode", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> DIALECT = column("dialect", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> MANIFEST_CONTENT_HASH = column("manifest_content_hash", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> OBJECT_INVENTORY_HASH = column("object_inventory_hash", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> INSTALL_PLAN_HASH = column("install_plan_hash", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> INSTALL_VERIFICATION_HASH = column("install_verification_hash", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> SOURCE_INPUTS_HASH = column("source_inputs_hash", SQLType.TEXT, Nullability.NULLABLE);

        ArtifactRefsTable() {
            super("management_artifact_refs", "management");
        }
    }

    static final class DeploymentsTable extends Table<Object> {
        final Column<String> ID = column("id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> WORKSPACE_ID = column("workspace_id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> ARTIFACT_REF_ID = column("artifact_ref_id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> ENVIRONMENT = column("environment", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> STATUS = column("status", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> REQUESTED_AT = column("requested_at", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> ACTIVATED_AT = column("activated_at", SQLType.TEXT, Nullability.NULLABLE);

        DeploymentsTable() {
            super("management_deployments", "management");
        }
    }

    static final class IdempotencyTable extends Table<Object> {
        final Column<String> COMMAND_NAME = column("command_name", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> SCOPE = column("scope", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> IDEMPOTENCY_KEY = column("idempotency_key", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> INPUT_HASH = column("input_hash", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> OUTCOME_STATUS = column("outcome_status", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> OUTCOME_HASH = column("outcome_hash", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> RESULT_REF = column("result_ref", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> CREATED_AT = column("created_at", SQLType.TEXT, Nullability.NOT_NULL);

        IdempotencyTable() {
            super("management_idempotency", "management");
        }
    }

    static final class AuditTable extends Table<Object> {
        final Column<String> ID = column("id", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<Integer> SEQUENCE = column("sequence", SQLType.INTEGER, Nullability.NOT_NULL);
        final Column<String> COMMAND_NAME = column("command_name", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> STATUS = column("status", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> REQUEST_ID = column("request_id", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> IDEMPOTENCY_KEY = column("idempotency_key", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> INPUT_HASH = column("input_hash", SQLType.TEXT, Nullability.NOT_NULL);
        final Column<String> OUTPUT_HASH = column("output_hash", SQLType.TEXT, Nullability.NULLABLE);
        final Column<String> OCCURRED_AT = column("occurred_at", SQLType.TEXT, Nullability.NOT_NULL);

        AuditTable() {
            super("management_audit_outcomes", "management");
        }
    }
}
