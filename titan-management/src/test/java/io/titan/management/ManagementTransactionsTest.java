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
import io.titan.management.ManagementTransactions.DeploymentActivationRequest;
import io.titan.management.ManagementTransactions.FileTransactionalMutationStore;
import io.titan.management.ManagementTransactions.TransactionContract;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementTransactionsTest {
    private static final Instant T0 = Instant.parse("2026-06-08T18:01:00Z");
    private static final Instant T1 = Instant.parse("2026-06-08T18:01:01Z");
    private static final Instant T2 = Instant.parse("2026-06-08T18:01:02Z");
    private static final Instant T3 = Instant.parse("2026-06-08T18:01:03Z");
    private static final String DOCUMENT_HASH =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";
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

    @TempDir
    Path tempDir;

    @Test
    void commitsDomainIdempotencyAndAuditOutcomeInOneTransaction() {
        Path transactionLog = tempDir.resolve("success-transaction.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft(T0));

        TransactionalCommandExecution execution = store.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> {
                    Draft draft = transaction.draft("draft-demo-blog-001").orElseThrow();
                    transaction.putArtifactRef(artifactRef());
                    transaction.putDraft(new Draft(
                            draft.id(),
                            draft.workspaceId(),
                            draft.modelId(),
                            draft.version(),
                            DraftStatus.ARCHIVED,
                            draft.documentHash(),
                            draft.createdAt(),
                            T1,
                            Map.of()));
                    return TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001");
                },
                T0,
                T1);

        assertTrue(execution.success());
        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);
        assertEquals(DraftStatus.ARCHIVED, reloaded.draft("draft-demo-blog-001").orElseThrow().status());
        assertTrue(reloaded.artifactRef("artifact-demo-blog-001").isPresent());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                reloaded.auditRecords().stream().map(AuditRecord::status).toList());
        assertEquals(1, reloaded.idempotencyRecords().size());
        assertEquals(OutcomeStatus.SUCCESS, reloaded.idempotencyRecords().get(0).outcomeStatus());
        assertEquals("artifact-demo-blog-001", reloaded.idempotencyRecords().get(0).resultRef());
    }

    @Test
    void rollsBackStagedDomainWritesWhenMutationFailsAfterPartialWork() {
        Path transactionLog = tempDir.resolve("rollback-transaction.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft(T0));

        TransactionalCommandExecution execution = store.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> {
                    Draft draft = transaction.draft("draft-demo-blog-001").orElseThrow();
                    transaction.putArtifactRef(artifactRef());
                    transaction.putDraft(new Draft(
                            draft.id(),
                            draft.workspaceId(),
                            draft.modelId(),
                            draft.version(),
                            DraftStatus.ARCHIVED,
                            draft.documentHash(),
                            draft.createdAt(),
                            T1,
                            Map.of()));
                    throw new IllegalStateException("artifact writer unavailable");
                },
                T0,
                T1);

        assertFalse(execution.success());
        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);
        assertEquals(DraftStatus.VALIDATED, reloaded.draft("draft-demo-blog-001").orElseThrow().status());
        assertTrue(reloaded.artifactRef("artifact-demo-blog-001").isEmpty());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                reloaded.auditRecords().stream().map(AuditRecord::status).toList());
        assertEquals("TITAN-MGMT-E011", reloaded.auditRecords().get(1).errorCode());

        List<IdempotencyRecord> idempotencyRecords = reloaded.idempotencyRecords();
        assertEquals(1, idempotencyRecords.size());
        assertEquals(OutcomeStatus.FAILURE, idempotencyRecords.get(0).outcomeStatus());
        assertEquals("TITAN-MGMT-E011", idempotencyRecords.get(0).errorCode());
    }

    @Test
    void replaysCommittedOutcomeWithoutRepeatingStateTransition() {
        Path transactionLog = tempDir.resolve("replay-transaction.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft(T0));
        AtomicInteger stateTransitions = new AtomicInteger();

        TransactionalCommandExecution first = store.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> {
                    stateTransitions.incrementAndGet();
                    transaction.putArtifactRef(artifactRef());
                    return TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001");
                },
                T0,
                T1);
        TransactionalCommandExecution second = store.execute(
                validImportInvocation("request-import-demo-blog-002", "model: demo-blog"),
                (invocation, transaction) -> {
                    stateTransitions.incrementAndGet();
                    return TransactionalCommandResult.success(
                            "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                            "artifact-demo-blog-002");
                },
                T2,
                T3);

        assertTrue(first.success());
        assertTrue(second.success());
        assertTrue(second.replayed());
        assertEquals(1, stateTransitions.get());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                store.auditRecords().stream().map(AuditRecord::status).toList());
    }

    @Test
    void rejectsConflictingIdempotencyInputWithoutOverwritingOriginalOutcome() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("conflict-transaction.log"));
        store.seedDraft(validatedDraft(T0));

        store.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001"),
                T0,
                T1);
        TransactionalCommandExecution conflict = store.execute(
                validImportInvocation("request-import-demo-blog-002", "model: changed-blog"),
                (invocation, transaction) -> TransactionalCommandResult.success(
                        "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                        "artifact-demo-blog-002"),
                T2,
                T3);

        assertFalse(conflict.success());
        assertTrue(conflict.conflict());
        assertEquals("TITAN-MGMT-E020", conflict.result().errorCode());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(OutcomeStatus.SUCCESS, store.idempotencyRecords().get(0).outcomeStatus());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                store.auditRecords().stream().map(AuditRecord::status).toList());
    }

    @Test
    void documentsTransactionScopeAndDialectDifferences() {
        TransactionContract contract = ManagementTransactions.importModelDocumentTransactionContract();

        assertEquals("management.importModelDocument", contract.commandName());
        assertEquals("required", contract.transactionMode());
        assertTrue(contract.atomicWrites().contains("management_drafts"));
        assertTrue(contract.atomicWrites().contains("management_artifact_refs"));
        assertTrue(contract.atomicWrites().contains("management_idempotency"));
        assertTrue(contract.atomicWrites().contains("management_audit_outcomes"));
        assertTrue(contract.dialectNotes().get("postgresql").contains("row locks"));
        assertTrue(contract.dialectNotes().get("mysql").contains("InnoDB"));
    }

    @Test
    void ignoresTornTransactionBatchWithoutExposingPartialDomainState() throws Exception {
        Path transactionLog = tempDir.resolve("torn-transaction.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft(T0));
        Files.writeString(
                transactionLog,
                String.join(System.lineSeparator(),
                        "BATCH_BEGIN",
                        encodedArtifactLine(),
                        encodedIdempotencyLine(),
                        encodedSuccessAuditLine()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);

        assertEquals(DraftStatus.VALIDATED, reloaded.draft("draft-demo-blog-001").orElseThrow().status());
        assertTrue(reloaded.artifactRef("artifact-demo-blog-001").isEmpty());
        assertTrue(reloaded.idempotencyRecords().isEmpty());
        assertTrue(reloaded.auditRecords().isEmpty());
    }

    @Test
    void emptyAndMissingDurableManagementReadsAreDeterministic() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(tempDir.resolve("empty-reads.log"));

        assertTrue(store.draft("draft-missing").isEmpty());
        assertTrue(store.artifactRef("artifact-missing").isEmpty());
        assertTrue(store.deployment("deployment-missing").isEmpty());
        assertTrue(store.drafts().isEmpty());
        assertTrue(store.draftsForWorkspace("workspace-001").isEmpty());
        assertTrue(store.draftsForModel("workspace-001", "model-demo-blog").isEmpty());
        assertTrue(store.artifactRefs().isEmpty());
        assertTrue(store.deployments().isEmpty());
        assertTrue(store.deploymentsForWorkspace("workspace-001").isEmpty());
        assertTrue(store.auditRecordsForRequest("request-missing").isEmpty());
    }

    @Test
    void durableManagementReadsPreserveStableOrderingAcrossReloads() {
        Path transactionLog = tempDir.resolve("ordered-reads.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(draft("draft-zeta-002", "workspace-002", "model-zeta", 1, T1));
        store.seedDraft(draft("draft-demo-blog-002", "workspace-001", "model-demo-blog", 2, T2));
        store.seedDraft(draft("draft-demo-blog-001", "workspace-001", "model-demo-blog", 1, T0));
        store.seedArtifactRef(artifactRef("artifact-zeta-001"));
        store.seedArtifactRef(artifactRef("artifact-demo-blog-001"));
        store.seedDeployment(deployment("deployment-demo-blog-stage", "staging", T1));
        store.seedDeployment(deployment("deployment-demo-blog-prod", "prod", T0));

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);

        assertEquals(
                List.of("draft-demo-blog-001", "draft-demo-blog-002", "draft-zeta-002"),
                reloaded.drafts().stream().map(Draft::id).toList());
        assertEquals(
                List.of("draft-demo-blog-001", "draft-demo-blog-002"),
                reloaded.draftsForModel("workspace-001", "model-demo-blog").stream().map(Draft::id).toList());
        assertEquals(
                List.of("artifact-demo-blog-001", "artifact-zeta-001"),
                reloaded.artifactRefs().stream().map(ArtifactRef::id).toList());
        assertEquals(
                List.of("deployment-demo-blog-prod", "deployment-demo-blog-stage"),
                reloaded.deploymentsForWorkspace("workspace-001").stream().map(Deployment::id).toList());
    }

    @Test
    void draftMetadataRoundTripsThroughTheTransactionLog() {
        // Regression for the G-11 metadata-loss defect: draft metadata was dropped on encode
        // and silently replayed as Map.of().
        Path transactionLog = tempDir.resolve("draft-metadata.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        Map<String, String> metadata = Map.of(
                "source", "java-mode-import",
                "scope", "workspace-management");
        store.seedDraft(new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "model-demo-blog",
                1,
                DraftStatus.VALIDATED,
                DOCUMENT_HASH,
                T0,
                T1,
                metadata));

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);

        Draft replayed = reloaded.draft("draft-demo-blog-001").orElseThrow();
        assertEquals(metadata, replayed.metadata());

        // Empty metadata still round-trips as empty.
        store.seedDraft(draft("draft-demo-blog-002", "workspace-001", "model-demo-blog", 2, T2));
        assertEquals(
                Map.of(),
                new FileTransactionalMutationStore(transactionLog)
                        .draft("draft-demo-blog-002")
                        .orElseThrow()
                        .metadata());
    }

    @Test
    void durableManagementReadsObserveCommittedMutationStateAndAuditByRequest() {
        Path transactionLog = tempDir.resolve("read-after-write.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft(T0));

        store.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> {
                    transaction.putArtifactRef(artifactRef());
                    transaction.putDraft(new Draft(
                            "draft-demo-blog-001",
                            "workspace-001",
                            "model-demo-blog",
                            2,
                            DraftStatus.ARCHIVED,
                            DOCUMENT_HASH,
                            T0,
                            T1,
                            Map.of()));
                    return TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001");
                },
                T0,
                T1);

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);

        assertEquals(DraftStatus.ARCHIVED, reloaded.draft("draft-demo-blog-001").orElseThrow().status());
        assertEquals(
                List.of("draft-demo-blog-001"),
                reloaded.draftsForWorkspace("workspace-001").stream().map(Draft::id).toList());
        assertEquals(
                List.of("artifact-demo-blog-001"),
                reloaded.artifactRefs().stream().map(ArtifactRef::id).toList());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                reloaded.auditRecordsForRequest("request-import-demo-blog-001").stream()
                        .map(AuditRecord::status)
                        .toList());
    }

    @Test
    void activatesDeploymentOnlyForGap005VerifiedArtifactAndSupersedesPreviousActiveDeployment() {
        Path transactionLog = tempDir.resolve("deployment-activation.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedArtifactRef(artifactRef());
        store.seedDeployment(new Deployment(
                "deployment-demo-blog-old-prod",
                "workspace-001",
                "artifact-demo-blog-001",
                "prod",
                DeploymentStatus.ACTIVE,
                T0,
                T0));

        DeploymentActivationExecution execution = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T1,
                T2);

        assertTrue(execution.success());
        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);
        assertEquals(DeploymentStatus.SUPERSEDED,
                reloaded.deployment("deployment-demo-blog-old-prod").orElseThrow().status());
        assertEquals(DeploymentStatus.ACTIVE,
                reloaded.deployment("deployment-demo-blog-prod").orElseThrow().status());
        assertEquals(T2, reloaded.deployment("deployment-demo-blog-prod").orElseThrow().activatedAt());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                reloaded.auditRecordsForRequest("request-deploy-demo-blog-001").stream()
                        .map(AuditRecord::status)
                        .toList());
        assertEquals("management.activateDeployment", reloaded.idempotencyRecords().get(0).commandName());
        assertEquals("deployment-demo-blog-prod", reloaded.idempotencyRecords().get(0).resultRef());
    }

    @Test
    void rejectsDeploymentActivationWhenArtifactVerificationHasNotPassed() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-verification-failure.log"));
        store.seedArtifactRef(artifactRef("artifact-demo-blog-001", VerificationStatus.PENDING));

        DeploymentActivationExecution execution = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T1,
                T2);

        assertFalse(execution.success());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
        assertEquals("TITAN-MGMT-E033", execution.outcomeRecord().errorCode());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                store.auditRecordsForRequest("request-deploy-demo-blog-001").stream()
                        .map(AuditRecord::status)
                        .toList());
    }

    @Test
    void rejectsDeploymentActivationWhenExpectedArtifactHashDiffers() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-artifact-hash-mismatch.log"));
        store.seedArtifactRef(artifactRef());

        DeploymentActivationExecution execution = store.activateDeployment(
                new DeploymentActivationRequest(
                        "actor-platform-001",
                        "platform",
                        "workspace-001",
                        "request-deploy-demo-blog-001",
                        "deploy:prod:artifact-demo-blog-001",
                        "deployment-demo-blog-prod",
                        "artifact-demo-blog-001",
                        "prod",
                        "sha256:9999999999999999999999999999999999999999999999999999999999999999",
                        "migration",
                        "postgresql",
                        MANIFEST_HASH,
                        OBJECT_INVENTORY_HASH,
                        INSTALL_PLAN_HASH,
                        INSTALL_VERIFICATION_HASH,
                        SOURCE_INPUTS_HASH),
                T1,
                T2);

        assertFalse(execution.success());
        assertEquals("TITAN-MGMT-E031", execution.outcomeRecord().errorCode());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
    }

    @Test
    void rejectsDeploymentActivationWhenArtifactRefDoesNotPointAtGap005MetadataSet() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-artifact-metadata-failure.log"));
        store.seedArtifactRef(new ArtifactRef(
                "artifact-demo-blog-001",
                "artifact-demo-blog",
                ARTIFACT_HASH,
                "generated/demo-blog/artifact.json",
                "generated/demo-blog/titan-object-inventory.json",
                "generated/demo-blog/titan-install-plan.json",
                "generated/demo-blog/titan-install-verification.json",
                VerificationStatus.PASSED,
                "migration",
                "postgresql",
                MANIFEST_HASH,
                OBJECT_INVENTORY_HASH,
                INSTALL_PLAN_HASH,
                INSTALL_VERIFICATION_HASH,
                SOURCE_INPUTS_HASH));

        DeploymentActivationExecution execution = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T1,
                T2);

        assertFalse(execution.success());
        assertEquals("TITAN-MGMT-E032", execution.outcomeRecord().errorCode());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
    }

    @Test
    void rejectsDeploymentActivationWhenGap005EvidenceDiffers() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-artifact-evidence-mismatch.log"));
        store.seedArtifactRef(artifactRef());

        DeploymentActivationExecution execution = store.activateDeployment(
                new DeploymentActivationRequest(
                        "actor-platform-001",
                        "platform",
                        "workspace-001",
                        "request-deploy-demo-blog-001",
                        "deploy:prod:artifact-demo-blog-001",
                        "deployment-demo-blog-prod",
                        "artifact-demo-blog-001",
                        "prod",
                        ARTIFACT_HASH,
                        "migration",
                        "mysql",
                        MANIFEST_HASH,
                        OBJECT_INVENTORY_HASH,
                        INSTALL_PLAN_HASH,
                        INSTALL_VERIFICATION_HASH,
                        SOURCE_INPUTS_HASH),
                T1,
                T2);

        assertFalse(execution.success());
        assertEquals("TITAN-MGMT-E036", execution.outcomeRecord().errorCode());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
    }

    @Test
    void rejectsDeploymentActivationForUnauthorizedActorRole() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-activation-unauthorized.log"));
        store.seedArtifactRef(artifactRef());

        DeploymentActivationExecution execution = store.activateDeployment(
                new DeploymentActivationRequest(
                        "actor-viewer-001",
                        "viewer",
                        "workspace-001",
                        "request-deploy-demo-blog-001",
                        "deploy:prod:artifact-demo-blog-001",
                        "deployment-demo-blog-prod",
                        "artifact-demo-blog-001",
                        "prod",
                        ARTIFACT_HASH,
                        "migration",
                        "postgresql",
                        MANIFEST_HASH,
                        OBJECT_INVENTORY_HASH,
                        INSTALL_PLAN_HASH,
                        INSTALL_VERIFICATION_HASH,
                        SOURCE_INPUTS_HASH),
                T1,
                T2);

        assertFalse(execution.success());
        assertEquals("TITAN-MGMT-E035", execution.outcomeRecord().errorCode());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
    }

    @Test
    void replaysDeploymentActivationWithoutRepeatingStatusTransition() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-activation-replay.log"));
        store.seedArtifactRef(artifactRef());

        DeploymentActivationExecution first = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T0,
                T1);
        DeploymentActivationExecution second = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-002", "deploy:prod:artifact-demo-blog-001"),
                T2,
                T3);

        assertTrue(first.success());
        assertTrue(second.success());
        assertTrue(second.replayed());
        assertEquals(1, store.deployments().size());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                store.auditRecords().stream().map(AuditRecord::status).toList());
    }

    @Test
    void rejectsConflictingDeploymentActivationIdempotencyInput() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-activation-conflict.log"));
        store.seedArtifactRef(artifactRef());
        store.seedArtifactRef(artifactRef("artifact-demo-blog-002"));

        store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T0,
                T1);
        DeploymentActivationExecution conflict = store.activateDeployment(
                new DeploymentActivationRequest(
                        "actor-platform-001",
                        "platform",
                        "workspace-001",
                        "request-deploy-demo-blog-002",
                        "deploy:prod:artifact-demo-blog-001",
                        "deployment-demo-blog-prod-v2",
                        "artifact-demo-blog-002",
                        "prod",
                        ARTIFACT_HASH,
                        "migration",
                        "postgresql",
                        MANIFEST_HASH,
                        OBJECT_INVENTORY_HASH,
                        INSTALL_PLAN_HASH,
                        INSTALL_VERIFICATION_HASH,
                        SOURCE_INPUTS_HASH),
                T2,
                T3);

        assertFalse(conflict.success());
        assertTrue(conflict.conflict());
        assertEquals("TITAN-MGMT-E020", conflict.outcomeRecord().errorCode());
        assertEquals(1, store.idempotencyRecords().size());
        assertEquals(1, store.deployments().size());
    }

    @Test
    void replaysDeploymentActivationValidationFailureEvenIfArtifactLaterBecomesVerified() {
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(
                tempDir.resolve("deployment-activation-failure-replay.log"));
        store.seedArtifactRef(artifactRef("artifact-demo-blog-001", VerificationStatus.PENDING));

        DeploymentActivationExecution first = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-001", "deploy:prod:artifact-demo-blog-001"),
                T0,
                T1);
        store.seedArtifactRef(artifactRef());
        DeploymentActivationExecution second = store.activateDeployment(
                activationRequest("request-deploy-demo-blog-002", "deploy:prod:artifact-demo-blog-001"),
                T2,
                T3);

        assertFalse(first.success());
        assertFalse(second.success());
        assertTrue(second.replayed());
        assertEquals("TITAN-MGMT-E033", second.outcomeRecord().errorCode());
        assertTrue(store.deployment("deployment-demo-blog-prod").isEmpty());
        assertEquals(1, store.idempotencyRecords().size());
    }

    @Test
    void rejectsNonTitanOwnedAtomicWriteTargets() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TransactionContract(
                        "management.importModelDocument",
                        "required",
                        List.of(
                                "management_drafts",
                                "management_artifact_refs",
                                "management_idempotency",
                                "management_audit_outcomes",
                                "admin_preview_urls"),
                        Map.of(
                                "postgresql", "single transaction with row locks",
                                "mysql", "single InnoDB transaction")));

        assertTrue(error.getMessage().contains("Titan-owned atomic writes"));
    }

    private static CommandInvocation validImportInvocation(String requestId, String sourceText) {
        return new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                new RequestContext(requestId, "import:workspace-001:demo-blog:v1"),
                Map.of(
                        "workspaceId", "workspace-001",
                        "sourceFormat", "yaml",
                        "sourceText", sourceText));
    }

    private static Draft validatedDraft(Instant updatedAt) {
        return new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "model-demo-blog",
                1,
                DraftStatus.VALIDATED,
                DOCUMENT_HASH,
                T0,
                updatedAt,
                Map.of());
    }

    private static Draft draft(String id, String workspaceId, String modelId, long version, Instant updatedAt) {
        return new Draft(
                id,
                workspaceId,
                modelId,
                version,
                DraftStatus.VALIDATED,
                DOCUMENT_HASH,
                T0,
                updatedAt,
                Map.of());
    }

    private static ArtifactRef artifactRef() {
        return artifactRef("artifact-demo-blog-001");
    }

    private static ArtifactRef artifactRef(String id) {
        return artifactRef(id, VerificationStatus.PASSED);
    }

    private static ArtifactRef artifactRef(String id, VerificationStatus verificationStatus) {
        return new ArtifactRef(
                id,
                id.replace("-001", ""),
                ARTIFACT_HASH,
                "generated/demo-blog/titan-artifact.json",
                "generated/demo-blog/titan-object-inventory.json",
                "generated/demo-blog/titan-install-plan.json",
                "generated/demo-blog/titan-install-verification.json",
                verificationStatus,
                "migration",
                "postgresql",
                MANIFEST_HASH,
                OBJECT_INVENTORY_HASH,
                INSTALL_PLAN_HASH,
                INSTALL_VERIFICATION_HASH,
                SOURCE_INPUTS_HASH);
    }

    private static DeploymentActivationRequest activationRequest(String requestId, String idempotencyKey) {
        return new DeploymentActivationRequest(
                "actor-platform-001",
                "platform",
                "workspace-001",
                requestId,
                idempotencyKey,
                "deployment-demo-blog-prod",
                "artifact-demo-blog-001",
                "prod",
                ARTIFACT_HASH,
                "migration",
                "postgresql",
                MANIFEST_HASH,
                OBJECT_INVENTORY_HASH,
                INSTALL_PLAN_HASH,
                INSTALL_VERIFICATION_HASH,
                SOURCE_INPUTS_HASH);
    }

    private static Deployment deployment(String id, String environment, Instant requestedAt) {
        return new Deployment(
                id,
                "workspace-001",
                "artifact-demo-blog-001",
                environment,
                DeploymentStatus.PENDING_ACTIVATION,
                requestedAt,
                null);
    }

    private static String encodedArtifactLine() {
        return String.join(
                "\t",
                "ARTIFACT",
                encoded("artifact-demo-blog-001"),
                encoded("artifact-demo-blog"),
                encoded(ARTIFACT_HASH),
                encoded("generated/demo-blog/titan-artifact.json"),
                encoded("generated/demo-blog/titan-object-inventory.json"),
                encoded("generated/demo-blog/titan-install-plan.json"),
                encoded("generated/demo-blog/titan-install-verification.json"),
                encoded("PASSED"));
    }

    private static String encodedIdempotencyLine() {
        return String.join(
                "\t",
                "IDEMPOTENCY",
                encoded("management.importModelDocument"),
                encoded("workspace-001"),
                encoded("import:workspace-001:demo-blog:v1"),
                encoded("sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b"),
                encoded("SUCCESS"),
                encoded(OUTPUT_HASH),
                encoded("artifact-demo-blog-001"),
                "-",
                "-",
                encoded(T1.toString()));
    }

    private static String encodedSuccessAuditLine() {
        return String.join(
                "\t",
                "AUDIT",
                encoded("audit-000001"),
                "1",
                encoded("management.importModelDocument"),
                encoded("SUCCESS"),
                encoded("actor-platform-001"),
                encoded("platform"),
                encoded("workspace-001"),
                encoded("request-import-demo-blog-001"),
                encoded("import:workspace-001:demo-blog:v1"),
                encoded("sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b"),
                encoded(OUTPUT_HASH),
                "-",
                "-",
                encoded(T1.toString()));
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
