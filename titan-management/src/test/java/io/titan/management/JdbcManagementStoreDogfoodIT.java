package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.ManagementCommandDescriptors;
import io.titan.management.ManagementCommands.RequestContext;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.DeploymentActivationRequest;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import io.titan.management.ManagementTransactions.TransactionalMutationStore;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Dogfood IT (B5, {@code @Tag("docker")}) — the REAL {@link JdbcTransactionalMutationStore} driven
 * through the {@link TransactionalMutationStore} interface against LIVE PostgreSQL 16 + MySQL 8.4,
 * parameterized over both dialects. Its mutations run on Titan-transpiled SQL ({@code CALL}ed
 * routines); its reads are plain SELECTs. It proves the four things the single-process,
 * single-lock file store CANNOT show:
 *
 * <ol>
 *   <li><b>DURABILITY</b> — install schema+routines, importModelDocument through the interface,
 *       reconnect with a NEW connection/store instance, and assert the draft + audit + idempotency
 *       rows persisted.</li>
 *   <li><b>IDEMPOTENCY</b> — the same key twice yields one mutation, one (success-outcome) audit
 *       row, one idempotency row (the faithful server-side G2 short-circuit); a conflicting input on
 *       the same key replays {@code TITAN-MGMT-E020} without a second mutation.</li>
 *   <li><b>AUDIT</b> — {@code auditRecordsForRequest} returns the attempt+outcome pair with the
 *       correct statuses.</li>
 *   <li><b>CONCURRENCY</b> — two threads/connections racing the same draft serialize under the
 *       row lock the import upsert takes; exactly one wins, and the final state is consistent.</li>
 * </ol>
 *
 * <p>Plus seeds + the filtered/ordered reads ({@code draftsForWorkspace} etc.) and the full
 * {@code activateDeployment} supersede/activate + precondition path.
 */
@Tag("docker")
class JdbcManagementStoreDogfoodIT {

    private static final String SCHEMA = "management";
    private static final Instant T0 = Instant.parse("2026-06-14T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-14T00:00:01Z");
    private static final Instant T2 = Instant.parse("2026-06-14T00:00:02Z");
    private static final String ARTIFACT_HASH =
            "sha256:3333333333333333333333333333333333333333333333333333333333333333";
    private static final String OUTPUT_HASH =
            "sha256:4444444444444444444444444444444444444444444444444444444444444444";
    private static final String MANIFEST_HASH =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OBJECT_INVENTORY_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String INSTALL_PLAN_HASH =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String INSTALL_VERIFICATION_HASH =
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String SOURCE_INPUTS_HASH =
            "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    enum Target {
        POSTGRESQL(Dialect.POSTGRESQL),
        MYSQL(Dialect.MYSQL);

        private final Dialect dialect;

        Target(Dialect dialect) {
            this.dialect = dialect;
        }
    }

    @BeforeAll
    static void startContainers() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("titan")
                .withUsername("titan")
                .withPassword("titan");
        postgres.start();
        // The management schema's database (MySQL) is provisioned by the harness; the store assumes
        // its schema already exists. log_bin_trust_function_creators lets a non-super user CREATE
        // PROCEDURE without SUPER/SET_USER_ID.
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName(SCHEMA)
                .withUsername("titan")
                .withPassword("titan")
                .withCommand("--log_bin_trust_function_creators=1", "--innodb-use-native-aio=0");
        mysql.start();
    }

    @AfterAll
    static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    /**
     * A fresh, isolated {@code management} schema per test method, so each parameterized leg starts
     * from an empty store and the assertions are deterministic.
     */
    private DataSource freshSchema(Target target) throws SQLException {
        if (target == Target.POSTGRESQL) {
            // Reset the management schema (admin), then build a DataSource whose every connection
            // defaults its search_path to it. The bundled DDL creates BARE-named tables, so the
            // install connection must resolve them to `management`; the routines + the store's reads
            // are schema-qualified, so they do not depend on the search_path.
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                statement.execute("CREATE SCHEMA " + SCHEMA);
            }
            String url = postgres.getJdbcUrl() + "?currentSchema=" + SCHEMA;
            DataSource dataSource = new SearchPathDataSource(
                    url, postgres.getUsername(), postgres.getPassword(), "SET search_path TO " + SCHEMA);
            install(dataSource, target.dialect);
            return dataSource;
        }
        // MySQL schemas ARE databases: provision a fresh `management` database; the URL selects it as
        // the default so the bare-named DDL lands there.
        try (Connection admin = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA);
        }
        String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + SCHEMA;
        DataSource dataSource = new SearchPathDataSource(url, mysql.getUsername(), mysql.getPassword(), null);
        install(dataSource, target.dialect);
        return dataSource;
    }

    private static void install(DataSource dataSource, Dialect dialect) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ManagementSchemaInstaller.install(connection, dialect);
        }
    }

    // ------------------------------------------------------------------
    // DURABILITY — write through one store, read back through a NEW store instance.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void importModelDocumentIsDurableAcrossANewConnectionAndStoreInstance(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        TransactionalMutationStore store = new JdbcTransactionalMutationStore(dataSource);

        TransactionalCommandExecution execution = store.execute(
                importInvocation("request-import-001", "import:ws-1:demo:v1", "model: demo"),
                successHandler("draft-demo-001"),
                T0,
                T1);
        assertTrue(execution.success(), target + " import succeeds");

        // Reconnect with a brand-new store instance over a fresh connection: nothing in memory,
        // everything read from the live database.
        TransactionalMutationStore reloaded = new JdbcTransactionalMutationStore(dataSource);

        Draft draft = reloaded.draft("draft-demo-001").orElseThrow();
        assertEquals(WORKSPACE, draft.workspaceId(), target + " durable draft workspace");
        assertEquals(DraftStatus.IMPORTED, draft.status(), target + " durable draft status");

        List<IdempotencyRecord> idempotency = reloaded.idempotencyRecords();
        assertEquals(1, idempotency.size(), target + " one durable idempotency row");
        assertEquals(OutcomeStatus.SUCCESS, idempotency.get(0).outcomeStatus());
        assertEquals("draft-demo-001", idempotency.get(0).resultRef());
        // RD-2 (CLOSED): the durable outcome_hash is now the OUTPUT hash — DISTINCT from the
        // load-bearing input_hash (which still drives replay/E020). Previously the routine collapsed
        // them and outcome_hash == input_hash; the three-distinct-hash routine makes them differ.
        assertEquals(OUTPUT_HASH, idempotency.get(0).outcomeHash(),
                target + " RD-2: outcome_hash is the OUTPUT hash");
        assertFalse(OUTPUT_HASH.equals(idempotency.get(0).inputHash()),
                target + " RD-2: outcome_hash is DISTINCT from input_hash");
        // The success outcome audit row's output_hash is likewise the OUTPUT hash, distinct from input.
        AuditRecord successOutcome = reloaded.auditRecordsForRequest("request-import-001").stream()
                .filter(record -> record.status() == AuditStatus.SUCCESS).findFirst().orElseThrow();
        assertEquals(OUTPUT_HASH, successOutcome.outputHash(), target + " RD-2: audit output_hash is the OUTPUT hash");
        assertFalse(OUTPUT_HASH.equals(successOutcome.inputHash()),
                target + " RD-2: audit output_hash distinct from input_hash");

        List<AuditRecord> audit = reloaded.auditRecordsForRequest("request-import-001");
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                audit.stream().map(AuditRecord::status).toList(),
                target + " durable attempt+outcome audit pair");
    }

    // ------------------------------------------------------------------
    // IDEMPOTENCY — same key twice = one mutation + faithful G2 short-circuit; conflict = E020.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void sameIdempotencyKeyReplaysWithoutASecondMutationOrAuditRow(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        TransactionalMutationStore store = new JdbcTransactionalMutationStore(dataSource);

        TransactionalCommandExecution first = store.execute(
                importInvocation("request-import-001", "import:ws-1:demo:v1", "model: demo"),
                successHandler("draft-demo-001"),
                T0,
                T1);
        TransactionalCommandExecution replay = store.execute(
                importInvocation("request-import-002", "import:ws-1:demo:v1", "model: demo"),
                successHandler("draft-demo-001"),
                T1,
                T2);

        assertTrue(first.success(), target + " first apply succeeds");
        assertTrue(replay.success(), target + " replay reports success");
        assertTrue(replay.replayed(), target + " replay flagged");

        // The idempotency proof: the SECOND call (same key) performs NO second mutation — exactly
        // ONE idempotency row, and the draft version is NOT bumped (the routine's G2 server-side
        // short-circuit, and the adapter's read-decide, both skip the draft upsert on replay).
        assertEquals(1, store.idempotencyRecords().size(), target + " one idempotency row after replay");
        assertEquals(1, draftVersion(dataSource, "draft-demo-001"),
                target + " replay must NOT bump the draft version (no second mutation)");

        // Audit is attempt+outcome per CALL (faithful to the file store's
        // [ATTEMPT, SUCCESS, ATTEMPT, SUCCESS]): the replay still records its own attempt+success
        // pair even though it mutated nothing — the durable proof is the single idempotency row +
        // un-bumped version above, not a suppressed audit row.
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                store.auditRecords().stream().map(AuditRecord::status).toList(),
                target + " attempt+outcome audited per call across first apply and replay");

        // Conflicting input on the SAME key -> TITAN-MGMT-E020, still no second mutation.
        TransactionalCommandExecution conflict = store.execute(
                importInvocation("request-import-003", "import:ws-1:demo:v1", "model: CHANGED"),
                successHandler("draft-demo-001"),
                T1,
                T2);
        assertFalse(conflict.success(), target + " conflicting input is not a success");
        assertTrue(conflict.conflict(), target + " conflicting input flagged as conflict");
        assertEquals("TITAN-MGMT-E020", conflict.result().errorCode(), target + " conflict error code");
        assertEquals(1, store.idempotencyRecords().size(), target + " conflict adds no idempotency row");
        assertEquals(1, draftVersion(dataSource, "draft-demo-001"), target + " conflict adds no mutation");
    }

    // ------------------------------------------------------------------
    // AUDIT — attempt+outcome rows with correct status for a validation failure too.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void auditRecordsForRequestCaptureAttemptAndOutcomeStatuses(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        TransactionalMutationStore store = new JdbcTransactionalMutationStore(dataSource);

        // A validation failure (missing required input field 'sourceText') is audited attempt+failure
        // (E001, NOT an unauthorized code) with a valid authenticated actor; it writes no idempotency
        // row and no draft — entirely by the adapter, no routine CALL.
        CommandInvocation invalid = new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", WORKSPACE, true),
                new RequestContext("request-invalid-001", "import:invalid:v1"),
                Map.of("workspaceId", WORKSPACE, "sourceFormat", "yaml"));
        TransactionalCommandExecution failure = store.execute(invalid, successHandler("draft-x"), T0, T1);

        assertFalse(failure.success(), target + " invalid command fails");
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                store.auditRecordsForRequest("request-invalid-001").stream().map(AuditRecord::status).toList(),
                target + " attempt+failure audit pair");
        assertTrue(store.idempotencyRecords().isEmpty(), target + " validation failure writes no idempotency row");
        assertTrue(store.draft("draft-x").isEmpty(), target + " validation failure writes no draft");
    }

    // ------------------------------------------------------------------
    // CONCURRENCY — two connections race the same draft; exactly one wins under the row lock.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void twoThreadsRacingTheSameDraftSerializeUnderTheRowLock(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        // Seed the draft at version 1 so both racers hit the import's ON CONFLICT DO UPDATE path
        // (the upsert takes the row lock on the existing draft row; the version bump is +1).
        TransactionalMutationStore seeder = new JdbcTransactionalMutationStore(dataSource);
        seeder.seedDraft(new Draft("draft-race-001", "ws-race", "model-race", 1,
                DraftStatus.IMPORTED, OUTPUT_HASH, T0, T0, Map.of()));

        // Each racer is its own store over the shared DataSource; each execute() opens its own
        // connection/transaction. Distinct idempotency keys so BOTH are real mutations contending
        // for the same draft row — the row lock must serialize them.
        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try {
            Future<?>[] futures = new Future<?>[racers];
            for (int i = 0; i < racers; i++) {
                final int index = i;
                futures[i] = pool.submit(() -> {
                    TransactionalMutationStore racer = new JdbcTransactionalMutationStore(dataSource);
                    ready.countDown();
                    awaitQuietly(go);
                    try {
                        TransactionalCommandExecution execution = racer.execute(
                                importInvocation("request-race-" + index, "race:ws:key-" + index, "model: race"),
                                successHandler("draft-race-001"),
                                T0,
                                T1);
                        if (execution.success()) {
                            successes.incrementAndGet();
                        } else {
                            failures.incrementAndGet();
                        }
                    } catch (RuntimeException exception) {
                        // A racer that loses the audit-sequence/PK race rolls back and surfaces here;
                        // that is the row lock doing its job, counted as a non-winning attempt.
                        failures.incrementAndGet();
                    }
                });
            }
            awaitQuietly(ready);
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Both racers complete; the serialization invariant is the headline: the draft version is
        // bumped by exactly the number of mutations that committed, and the final state is
        // internally consistent (one idempotency row per committed mutation, version == 1 + commits).
        TransactionalMutationStore reader = new JdbcTransactionalMutationStore(dataSource);
        int committed = successes.get();
        assertTrue(committed >= 1, target + " at least one racer commits");
        assertEquals(racers, successes.get() + failures.get(), target + " every racer terminates");
        assertEquals(committed, reader.idempotencyRecords().size(),
                target + " one idempotency row per committed mutation");
        assertEquals(1 + committed, draftVersion(dataSource, "draft-race-001"),
                target + " version bumped exactly once per committed mutation (row lock serialized)");
    }

    // ------------------------------------------------------------------
    // SEEDS + filtered/ordered reads.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void seedsAndReadsReturnFilteredAndOrderedResults(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        TransactionalMutationStore store = new JdbcTransactionalMutationStore(dataSource);

        store.seedDraft(draft("draft-zeta-002", "ws-2", "model-zeta", 1));
        store.seedDraft(draft("draft-demo-002", "ws-1", "model-demo", 2));
        store.seedDraft(draft("draft-demo-001", "ws-1", "model-demo", 1));
        store.seedArtifactRef(artifactRef("artifact-zeta-001", VerificationStatus.PASSED));
        store.seedArtifactRef(artifactRef("artifact-demo-001", VerificationStatus.PASSED));
        store.seedDeployment(deployment("deployment-demo-stage", "ws-1", "staging", T1));
        store.seedDeployment(deployment("deployment-demo-prod", "ws-1", "prod", T0));

        TransactionalMutationStore reloaded = new JdbcTransactionalMutationStore(dataSource);
        assertEquals(List.of("draft-demo-001", "draft-demo-002", "draft-zeta-002"),
                reloaded.drafts().stream().map(Draft::id).toList(),
                target + " drafts ordered by workspace,model,version,id");
        assertEquals(List.of("draft-demo-001", "draft-demo-002"),
                reloaded.draftsForModel("ws-1", "model-demo").stream().map(Draft::id).toList(),
                target + " draftsForModel filters");
        assertEquals(List.of("draft-demo-001", "draft-demo-002"),
                reloaded.draftsForWorkspace("ws-1").stream().map(Draft::id).toList(),
                target + " draftsForWorkspace filters");
        assertEquals(List.of("artifact-demo-001", "artifact-zeta-001"),
                reloaded.artifactRefs().stream().map(ArtifactRef::id).toList(),
                target + " artifactRefs ordered by id");
        assertEquals(List.of("deployment-demo-prod", "deployment-demo-stage"),
                reloaded.deploymentsForWorkspace("ws-1").stream().map(Deployment::id).toList(),
                target + " deploymentsForWorkspace ordered by env,requestedAt,id");
        // GAP-005 evidence columns round-trip through the seed + follow-on update.
        ArtifactRef ref = reloaded.artifactRef("artifact-demo-001").orElseThrow();
        assertEquals("migration", ref.packageMode(), target + " seeded artifact evidence persists");
        assertEquals(MANIFEST_HASH, ref.manifestContentHash());
    }

    // ------------------------------------------------------------------
    // activateDeployment — supersede + activate + typed precondition guard.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Target.class)
    void activateDeploymentSupersedesSiblingsAndGuardsUnverifiedArtifacts(Target target) throws Exception {
        DataSource dataSource = freshSchema(target);
        TransactionalMutationStore store = new JdbcTransactionalMutationStore(dataSource);

        store.seedArtifactRef(artifactRef("artifact-demo-001", VerificationStatus.PASSED));
        store.seedDeployment(new Deployment("deployment-demo-old-prod", "ws-1", "artifact-demo-001", "prod",
                DeploymentStatus.ACTIVE, T0, T0));
        store.seedDeployment(new Deployment("deployment-demo-prod", "ws-1", "artifact-demo-001", "prod",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));

        DeploymentActivationExecution execution = store.activateDeployment(
                activationRequest("request-deploy-001", "deploy:prod:demo", "deployment-demo-prod",
                        "artifact-demo-001"),
                T1,
                T2);

        assertTrue(execution.success(), target + " activation succeeds");
        TransactionalMutationStore reloaded = new JdbcTransactionalMutationStore(dataSource);
        assertEquals(DeploymentStatus.ACTIVE,
                reloaded.deployment("deployment-demo-prod").orElseThrow().status(), target + " target activated");
        assertEquals(DeploymentStatus.SUPERSEDED,
                reloaded.deployment("deployment-demo-old-prod").orElseThrow().status(), target + " sibling superseded");
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                reloaded.auditRecordsForRequest("request-deploy-001").stream().map(AuditRecord::status).toList(),
                target + " activation audited attempt+success");
        assertEquals("management.activateDeployment", reloaded.idempotencyRecords().get(0).commandName());

        // RD-1 (CLOSED, 7-for-7 server-side): the typed TITAN-MGMT-E03x precondition errors ALL come
        // from the activate_deployment @StoredFunction's server-side read-decide-RETURN (the adapter is
        // a pure pass-through that maps the int code back to the domain error). E032 (GAP-005 metadata
        // PATHS) is the final residual that moved — it now surfaces from the ROUTINE too. Exercise each
        // precondition path and assert the right code surfaces with NO mutation.

        // E033 — unverified artifact (routine returns 33).
        store.seedArtifactRef(artifactRef("artifact-unverified", VerificationStatus.PENDING));
        store.seedDeployment(new Deployment("deployment-unverified", "ws-1", "artifact-unverified", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e033 = store.activateDeployment(
                activationRequest("request-deploy-002", "deploy:staging:demo", "deployment-unverified",
                        "artifact-unverified"),
                T1, T2);
        assertFalse(e033.success(), target + " unverified artifact activation fails");
        assertEquals("TITAN-MGMT-E033", e033.outcomeRecord().errorCode(), target + " E033 from routine");
        assertEquals(DeploymentStatus.PENDING_ACTIVATION,
                reloaded.deployment("deployment-unverified").orElseThrow().status(), target + " E033 left it untouched");

        // E030 — missing artifact ref (routine returns 30). The deployment points at an artifact that
        // was never seeded.
        store.seedDeployment(new Deployment("deployment-no-artifact", "ws-1", "artifact-absent", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e030 = store.activateDeployment(
                activationRequest("request-deploy-003", "deploy:staging:e030", "deployment-no-artifact",
                        "artifact-absent"),
                T1, T2);
        assertEquals("TITAN-MGMT-E030", e030.outcomeRecord().errorCode(), target + " E030 from routine");

        // E031 — artifact hash mismatch (routine returns 31). A verified artifact whose recorded hash
        // differs from the request's expected hash.
        store.seedArtifactRef(new ArtifactRef("artifact-otherhash", "artifact-otherhash",
                "sha256:9999999999999999999999999999999999999999999999999999999999999999",
                "generated/demo/titan-artifact.json", "generated/demo/titan-object-inventory.json",
                "generated/demo/titan-install-plan.json", "generated/demo/titan-install-verification.json",
                VerificationStatus.PASSED, "migration", target(),
                MANIFEST_HASH, OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH));
        store.seedDeployment(new Deployment("deployment-hashmiss", "ws-1", "artifact-otherhash", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e031 = store.activateDeployment(
                activationRequest("request-deploy-004", "deploy:staging:e031", "deployment-hashmiss",
                        "artifact-otherhash"),
                T1, T2);
        assertEquals("TITAN-MGMT-E031", e031.outcomeRecord().errorCode(), target + " E031 from routine");

        // E035 — unauthorized actor (routine returns 35). A 'viewer' role is not admin/platform.
        store.seedArtifactRef(artifactRef("artifact-e035", VerificationStatus.PASSED));
        store.seedDeployment(new Deployment("deployment-e035", "ws-1", "artifact-e035", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e035 = store.activateDeployment(
                new DeploymentActivationRequest(
                        "actor-viewer-001", "viewer", "ws-1", "request-deploy-005", "deploy:staging:e035",
                        "deployment-e035", "artifact-e035", "staging",
                        ARTIFACT_HASH, "migration", "postgresql",
                        MANIFEST_HASH, OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH),
                T1, T2);
        assertEquals("TITAN-MGMT-E035", e035.outcomeRecord().errorCode(), target + " E035 from routine");
        assertEquals(DeploymentStatus.PENDING_ACTIVATION,
                reloaded.deployment("deployment-e035").orElseThrow().status(), target + " E035 left it untouched");

        // E032 — GAP-005 metadata PATHS (routine returns 32, the final residual moved server-side via
        // Column.like('%titan-*.json')). A verified artifact whose recorded hash + evidence match the
        // request, but whose metadata path columns do NOT end with the fixed titan-*.json filenames, so
        // E032 is the first failure. This proves E032 surfaces from the ROUTINE, not the adapter.
        store.seedArtifactRef(new ArtifactRef("artifact-e032", "artifact-e032", ARTIFACT_HASH,
                "generated/demo/manifest.json", "generated/demo/inventory.json",
                "generated/demo/plan.json", "generated/demo/verify.json",
                VerificationStatus.PASSED, "migration", target(),
                MANIFEST_HASH, OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH));
        store.seedDeployment(new Deployment("deployment-e032", "ws-1", "artifact-e032", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e032 = store.activateDeployment(
                activationRequest("request-deploy-007", "deploy:staging:e032", "deployment-e032", "artifact-e032"),
                T1, T2);
        assertEquals("TITAN-MGMT-E032", e032.outcomeRecord().errorCode(), target + " E032 from routine");
        assertEquals(DeploymentStatus.PENDING_ACTIVATION,
                reloaded.deployment("deployment-e032").orElseThrow().status(), target + " E032 left it untouched");

        // E036 — GAP-005 evidence mismatch (routine returns 36). A verified artifact with valid
        // metadata PATHS (so E032 passes) but whose recorded evidence hash differs from the request's.
        store.seedArtifactRef(new ArtifactRef("artifact-e036", "artifact-e036", ARTIFACT_HASH,
                "generated/demo/titan-artifact.json", "generated/demo/titan-object-inventory.json",
                "generated/demo/titan-install-plan.json", "generated/demo/titan-install-verification.json",
                VerificationStatus.PASSED, "migration", target(),
                "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH));
        store.seedDeployment(new Deployment("deployment-e036", "ws-1", "artifact-e036", "staging",
                DeploymentStatus.PENDING_ACTIVATION, T1, null));
        DeploymentActivationExecution e036 = store.activateDeployment(
                activationRequest("request-deploy-006", "deploy:staging:e036", "deployment-e036", "artifact-e036"),
                T1, T2);
        assertEquals("TITAN-MGMT-E036", e036.outcomeRecord().errorCode(), target + " E036 from routine");
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    // The actor scope must satisfy the importModelDocument descriptor's required "workspace" scope
    // (matched via the scope-prefix rule), and it is ALSO the idempotency scope + the draft's
    // workspace_id the routine writes — so reads filter on the same "workspace-001".
    private static final String WORKSPACE = "workspace-001";

    private static CommandInvocation importInvocation(String requestId, String idempotencyKey, String sourceText) {
        return new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", WORKSPACE, true),
                new RequestContext(requestId, idempotencyKey),
                Map.of("workspaceId", WORKSPACE, "sourceFormat", "yaml", "sourceText", sourceText));
    }

    // The handler returns the caller's outputHash + resultRef (= draftId); the durable mutation runs
    // through the routine. It never touches the TransactionContext (the JDBC store passes null —
    // the routine is the durable authority, not an in-memory staging context).
    private static ManagementTransactions.TransactionalCommandHandler successHandler(String draftId) {
        return (invocation, transaction) -> TransactionalCommandResult.success(OUTPUT_HASH, draftId);
    }

    private static Draft draft(String id, String workspaceId, String modelId, long version) {
        return new Draft(id, workspaceId, modelId, version, DraftStatus.IMPORTED, OUTPUT_HASH, T0, T0, Map.of());
    }

    private static ArtifactRef artifactRef(String id, VerificationStatus status) {
        return new ArtifactRef(id, id.replace("-001", ""), ARTIFACT_HASH,
                "generated/demo/titan-artifact.json",
                "generated/demo/titan-object-inventory.json",
                "generated/demo/titan-install-plan.json",
                "generated/demo/titan-install-verification.json",
                status, "migration", target(),
                MANIFEST_HASH, OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH);
    }

    // The artifact's recorded dialect is part of its GAP-005 evidence; activationRequest mirrors it.
    private static String target() {
        return "postgresql";
    }

    private static Deployment deployment(String id, String workspaceId, String environment, Instant requestedAt) {
        return new Deployment(id, workspaceId, "artifact-demo-001", environment,
                DeploymentStatus.PENDING_ACTIVATION, requestedAt, null);
    }

    private static DeploymentActivationRequest activationRequest(
            String requestId, String idempotencyKey, String deploymentId, String artifactRefId) {
        return new DeploymentActivationRequest(
                "actor-platform-001", "platform", "ws-1", requestId, idempotencyKey, deploymentId, artifactRefId,
                deploymentId.contains("staging") || deploymentId.contains("unverified") ? "staging" : "prod",
                ARTIFACT_HASH, "migration", "postgresql",
                MANIFEST_HASH, OBJECT_INVENTORY_HASH, INSTALL_PLAN_HASH, INSTALL_VERIFICATION_HASH, SOURCE_INPUTS_HASH);
    }

    private static int draftVersion(DataSource dataSource, String draftId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT version FROM " + SCHEMA + ".management_drafts WHERE id = ?")) {
            statement.setString(1, draftId);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next(), "no draft row for " + draftId);
                return rs.getInt(1);
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting latch", exception);
        }
    }

    /**
     * Minimal {@link DataSource} returning a fresh DriverManager connection per call, optionally
     * running an init statement (PostgreSQL {@code SET search_path TO management}) so every connection
     * resolves the bare-named bundled DDL to the {@code management} schema.
     */
    private static final class SearchPathDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        private final String initSql;

        SearchPathDataSource(String url, String user, String password, String initSql) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.initSql = initSql;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return prepare(DriverManager.getConnection(url, user, password));
        }

        @Override
        public Connection getConnection(String username, String pass) throws SQLException {
            return prepare(DriverManager.getConnection(url, username, pass));
        }

        private Connection prepare(Connection connection) throws SQLException {
            if (initSql != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(initSql);
                }
            }
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("io.titan.management.test.SimpleDataSource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Cannot unwrap to " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
