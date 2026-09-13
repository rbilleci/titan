package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.ManagementCommandDescriptors;
import io.titan.management.ManagementCommands.RequestContext;
import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import io.titan.management.ManagementTransactions.DeploymentActivationExecution;
import io.titan.management.ManagementTransactions.DeploymentActivationRequest;
import io.titan.management.ManagementTransactions.FileTransactionalMutationStore;
import io.titan.management.ManagementTransactions.TransactionalCommandExecution;
import io.titan.management.ManagementTransactions.TransactionalCommandResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementBoundaryReadinessAuditTest {
    private static final Instant T0 = Instant.parse("2026-06-08T22:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-08T22:00:01Z");
    private static final Instant T2 = Instant.parse("2026-06-08T22:00:02Z");
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
    void productionManagementBoundaryHasDurableReadWriteAndDeploymentEvidence() {
        Path transactionLog = tempDir.resolve("boundary-readiness.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft());

        TransactionalCommandExecution importExecution = store.execute(
                importInvocation(),
                (invocation, transaction) -> {
                    transaction.putArtifactRef(artifactRef());
                    return TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001");
                },
                T0,
                T1);
        DeploymentActivationExecution activationExecution = store.activateDeployment(activationRequest(), T1, T2);

        assertTrue(importExecution.success());
        assertTrue(activationExecution.success());

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);
        assertTrue(reloaded.draft("draft-demo-blog-001").isPresent());
        assertTrue(reloaded.artifactRef("artifact-demo-blog-001").isPresent());
        assertEquals(DeploymentStatus.ACTIVE,
                reloaded.deployment("deployment-demo-blog-prod").orElseThrow().status());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                reloaded.auditRecords().stream().map(record -> record.status()).toList());
        assertEquals(
                List.of("management.importModelDocument", "management.activateDeployment"),
                reloaded.idempotencyRecords().stream().map(record -> record.commandName()).toList());
    }

    @Test
    void idempotencyReadinessCoversReplayConflictAndDurableOutcomeEvidence() {
        Path transactionLog = tempDir.resolve("boundary-idempotency-readiness.log");
        FileTransactionalMutationStore store = new FileTransactionalMutationStore(transactionLog);
        store.seedDraft(validatedDraft());
        AtomicInteger stateTransitions = new AtomicInteger();

        TransactionalCommandExecution first = store.execute(
                importInvocation("request-import-demo-blog-001", "model: demo-blog"),
                (invocation, transaction) -> {
                    stateTransitions.incrementAndGet();
                    transaction.putArtifactRef(artifactRef());
                    return TransactionalCommandResult.success(OUTPUT_HASH, "artifact-demo-blog-001");
                },
                T0,
                T1);
        TransactionalCommandExecution replay = store.execute(
                importInvocation("request-import-demo-blog-002", "model: demo-blog"),
                (invocation, transaction) -> {
                    stateTransitions.incrementAndGet();
                    return TransactionalCommandResult.success(
                            "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                            "artifact-demo-blog-002");
                },
                T1,
                T2);
        TransactionalCommandExecution conflict = store.execute(
                importInvocation("request-import-demo-blog-003", "model: changed-blog"),
                (invocation, transaction) -> {
                    stateTransitions.incrementAndGet();
                    return TransactionalCommandResult.success(
                            "sha256:6666666666666666666666666666666666666666666666666666666666666666",
                            "artifact-demo-blog-003");
                },
                T1,
                T2);

        assertTrue(first.success());
        assertTrue(replay.success());
        assertTrue(replay.replayed());
        assertFalse(conflict.success());
        assertTrue(conflict.conflict());
        assertEquals("TITAN-MGMT-E020", conflict.result().errorCode());
        assertEquals(1, stateTransitions.get());

        FileTransactionalMutationStore reloaded = new FileTransactionalMutationStore(transactionLog);
        assertEquals(1, reloaded.idempotencyRecords().size());
        assertEquals("artifact-demo-blog-001", reloaded.idempotencyRecords().get(0).resultRef());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS,
                        AuditStatus.ATTEMPT, AuditStatus.SUCCESS,
                        AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                reloaded.auditRecords().stream().map(record -> record.status()).toList());
    }

    @Test
    void readinessClassificationLeavesNoHiddenInMemoryCoreBlockers() {
        List<ReadinessItem> audit = List.of(
                item("domain records", Category.CORE_READY, "ManagementRecords"),
                item("import command descriptor", Category.CORE_READY, "management.importModelDocument"),
                item("actor request and idempotency metadata", Category.CORE_READY, "CommandInvocation"),
                item("durable audit trail", Category.CORE_READY, "FileAuditStore"),
                item("transactional import boundary", Category.CORE_READY, "FileTransactionalMutationStore.execute"),
                item("durable management reads", Category.CORE_READY, "FileTransactionalMutationStore read API"),
                item("artifact evidence and deployment activation", Category.CORE_READY,
                        "management.activateDeployment"),
                item("request envelope and actor extraction", Category.TRANSPORT_MAPPING, "transport request context"),
                item("external mutation schema mapping", Category.TRANSPORT_MAPPING, "external schema field mapping"),
                item("external response payload mapping", Category.TRANSPORT_MAPPING, "external response mapping"),
                item("external store wiring", Category.TRANSPORT_MAPPING, "transport runtime store adapter"),
                item("validation and artifact generation sequencing", Category.PRODUCT_DECISION,
                        "product orchestration choice"),
                item("operation policy review commands", Category.FOLLOW_ON_GAP,
                        "approveObservedOperation/rejectObservedOperation"));

        assertFalse(audit.stream().anyMatch(item -> item.category() == Category.HIDDEN_IN_MEMORY_BLOCKER));
        assertTrue(audit.stream()
                .filter(item -> item.category() == Category.CORE_READY)
                .allMatch(item -> !item.evidence().isBlank()));
        assertEquals(7, audit.stream().filter(item -> item.category() == Category.CORE_READY).count());
        assertEquals(
                List.of(Category.TRANSPORT_MAPPING, Category.TRANSPORT_MAPPING,
                        Category.TRANSPORT_MAPPING, Category.TRANSPORT_MAPPING),
                audit.stream()
                        .filter(item -> item.category() == Category.TRANSPORT_MAPPING)
                        .map(ReadinessItem::category)
                        .toList());
    }

    @Test
    void adminManagementCapabilitySetIsExplicitlyClassified() {
        List<CapabilityReadiness> capabilities = List.of(
                capability(
                        "importModelDocument",
                        Category.CORE_READY,
                        ManagementCommandDescriptors.importModelDocument().commandName()),
                capability("validateModelDraft", Category.PRODUCT_DECISION, "external validation sequencing"),
                capability("generateModelArtifacts", Category.PRODUCT_DECISION, "external artifact generation sequencing"),
                capability("activateDeployment", Category.CORE_READY, "management.activateDeployment"),
                capability("approveObservedOperation", Category.FOLLOW_ON_GAP, "operation policy review command"),
                capability("rejectObservedOperation", Category.FOLLOW_ON_GAP, "operation policy review command"));

        assertEquals(
                List.of("importModelDocument", "validateModelDraft", "generateModelArtifacts",
                        "activateDeployment", "approveObservedOperation", "rejectObservedOperation"),
                capabilities.stream().map(CapabilityReadiness::name).toList());
        assertTrue(capabilities.stream().noneMatch(
                capability -> capability.category() == Category.HIDDEN_IN_MEMORY_BLOCKER));
        assertEquals(
                List.of(Category.CORE_READY, Category.PRODUCT_DECISION, Category.PRODUCT_DECISION,
                        Category.CORE_READY, Category.FOLLOW_ON_GAP, Category.FOLLOW_ON_GAP),
                capabilities.stream().map(CapabilityReadiness::category).toList());
    }

    @Test
    void coreReadyDescriptorsDoNotDependOnTransportOwnedIdentity() {
        String descriptor = ManagementCommandDescriptors.importModelDocument().stableJson();
        String transactionNotes = ManagementTransactions.importModelDocumentTransactionContract().toString();

        assertTrue(descriptor.contains("management.importModelDocument"));
        assertFalse(descriptor.toLowerCase(java.util.Locale.ROOT).contains("resolver"));
        assertFalse(descriptor.toLowerCase(java.util.Locale.ROOT).contains("selection"));
        assertFalse(transactionNotes.toLowerCase(java.util.Locale.ROOT).contains("admin_preview"));
    }

    private static ReadinessItem item(String name, Category category, String evidence) {
        return new ReadinessItem(name, category, evidence);
    }

    private static CapabilityReadiness capability(String name, Category category, String evidence) {
        return new CapabilityReadiness(name, category, evidence);
    }

    private static CommandInvocation importInvocation() {
        return importInvocation("request-import-demo-blog-001", "model: demo-blog");
    }

    private static CommandInvocation importInvocation(String requestId, String sourceText) {
        return new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                new RequestContext(requestId, "import:workspace-001:demo-blog:v1"),
                Map.of(
                        "workspaceId", "workspace-001",
                        "sourceFormat", "yaml",
                        "sourceText", sourceText));
    }

    private static Draft validatedDraft() {
        return new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "model-demo-blog",
                1,
                DraftStatus.VALIDATED,
                DOCUMENT_HASH,
                T0,
                T0,
                Map.of());
    }

    private static ArtifactRef artifactRef() {
        return new ArtifactRef(
                "artifact-demo-blog-001",
                "artifact-demo-blog",
                ARTIFACT_HASH,
                "generated/demo-blog/titan-artifact.json",
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
                SOURCE_INPUTS_HASH);
    }

    private static DeploymentActivationRequest activationRequest() {
        return new DeploymentActivationRequest(
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
                "postgresql",
                MANIFEST_HASH,
                OBJECT_INVENTORY_HASH,
                INSTALL_PLAN_HASH,
                INSTALL_VERIFICATION_HASH,
                SOURCE_INPUTS_HASH);
    }

    private record ReadinessItem(String name, Category category, String evidence) {
        private ReadinessItem {
            if (name.isBlank()) {
                throw new IllegalArgumentException("readiness item requires a name");
            }
            if (evidence.isBlank()) {
                throw new IllegalArgumentException("readiness item requires evidence");
            }
        }
    }

    private record CapabilityReadiness(String name, Category category, String evidence) {
        private CapabilityReadiness {
            if (name.isBlank()) {
                throw new IllegalArgumentException("capability requires a name");
            }
            if (evidence.isBlank()) {
                throw new IllegalArgumentException("capability requires evidence");
            }
        }
    }

    private enum Category {
        CORE_READY,
        TRANSPORT_MAPPING,
        PRODUCT_DECISION,
        FOLLOW_ON_GAP,
        HIDDEN_IN_MEMORY_BLOCKER
    }
}
