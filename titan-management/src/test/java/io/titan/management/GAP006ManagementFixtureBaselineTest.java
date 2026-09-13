package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GAP006ManagementFixtureBaselineTest {
    @Test
    void representsImportModelDocumentBaselineWithoutTransportOwnedState() {
        ManagementFixture fixture = ManagementFixture.demoBlogImport();

        assertEquals("draft-demo-blog-001", fixture.draft().id());
        assertEquals("workspace-001", fixture.draft().workspaceId());
        assertEquals("demo-blog", fixture.draft().modelId());
        assertEquals("imported", fixture.draft().status());
        assertEquals("sha256:model-document-demo-blog-v1", fixture.draft().documentHash());

        assertEquals("validation-draft-demo-blog-001", fixture.validationReport().id());
        assertEquals("draft-demo-blog-001", fixture.validationReport().draftId());
        assertEquals("passed", fixture.validationReport().status());

        assertEquals("artifact-demo-blog-001", fixture.artifactRef().id());
        assertEquals("titan-artifact.json", fixture.artifactRef().manifestPath());
        assertEquals("titan-object-inventory.json", fixture.artifactRef().objectInventoryPath());
        assertEquals("titan-install-plan.json", fixture.artifactRef().installPlanPath());
        assertEquals("titan-install-verification.json", fixture.artifactRef().installVerificationPath());

        assertEquals("deployment-demo-blog-prod", fixture.deployment().id());
        assertEquals("artifact-demo-blog-001", fixture.deployment().artifactRefId());
        assertEquals("production", fixture.deployment().environment());
        assertEquals("pendingActivation", fixture.deployment().status());

        assertEquals("audit-import-attempt-001", fixture.auditEvent().id());
        assertEquals("importModelDocument", fixture.auditEvent().commandName());
        assertEquals("actor-admin-001", fixture.auditEvent().actorId());
        assertEquals("request-import-demo-blog-001", fixture.auditEvent().requestId());
        assertEquals("import:workspace-001:demo-blog:v1", fixture.auditEvent().idempotencyKey());
        assertEquals("attempted", fixture.auditEvent().outcome());

        assertTrue(fixture.transportOwnedMetadata().isEmpty());
        assertFalse(fixture.containsTransportOwnedTerms());
    }

    @Test
    void capturesCurrentDurableStorageAndMutationGapsAsStableDiagnostics() {
        ManagementFixture fixture = ManagementFixture.demoBlogImport();
        List<ManagementGapDiagnostic> diagnostics = fixture.currentGapDiagnostics();

        assertEquals(
                List.of(
                        "TITAN-GAP006-DURABLE-STORE-MISSING",
                        "TITAN-GAP006-READ-AFTER-WRITE-NOT-DURABLE",
                        "TITAN-GAP006-IDEMPOTENCY-NOT-DURABLE",
                        "TITAN-GAP006-AUDIT-NOT-DURABLE",
                        "TITAN-GAP006-ARTIFACT-VERIFICATION-NOT-ENFORCED"),
                diagnostics.stream().map(ManagementGapDiagnostic::code).toList());
        assertTrue(diagnostics.stream()
                .allMatch(diagnostic -> diagnostic.message().startsWith("GAP-006 baseline: ")));
    }

    @Test
    void distinguishesIdempotentReplayFromConflictingInputBeforeStoreExists() {
        ManagementFixture fixture = ManagementFixture.demoBlogImport();
        IdempotentMutationExample original = fixture.idempotentMutation();

        assertEquals(
                "replayExpectedAfterDurableBoundaryExists",
                original.compareTo(original.withInputHash("sha256:import-demo-blog-v1")).expectation());
        assertEquals(
                "conflictExpectedAfterDurableBoundaryExists",
                original.compareTo(original.withInputHash("sha256:import-demo-blog-v2")).expectation());
        assertEquals(
                "TITAN-GAP006-IDEMPOTENCY-NOT-DURABLE",
                original.compareTo(original.withInputHash("sha256:import-demo-blog-v2")).currentGapCode());
    }

    @Test
    void rejectsTransportOwnedMetadataInCoreFixtureRecords() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ManagementDraft.create(
                        "draft-demo-blog-001",
                        "workspace-001",
                        "demo-blog",
                        "imported",
                        "sha256:model-document-demo-blog-v1",
                        Map.of("previewUrl", "/admin/preview/demo-blog")));

        assertTrue(exception.getMessage().contains("TITAN-GAP006-TRANSPORT-METADATA"));
        assertTrue(exception.getMessage().contains("previewUrl"));
    }

    private record ManagementFixture(
            ManagementDraft draft,
            ManagementValidationReport validationReport,
            ManagementArtifactRef artifactRef,
            ManagementDeployment deployment,
            ManagementAuditEvent auditEvent,
            IdempotentMutationExample idempotentMutation,
            List<ManagementGapDiagnostic> currentGapDiagnostics,
            Map<String, String> transportOwnedMetadata) {
        static ManagementFixture demoBlogImport() {
            Instant occurredAt = Instant.parse("2026-06-08T17:00:00Z");
            return new ManagementFixture(
                    ManagementDraft.create(
                            "draft-demo-blog-001",
                            "workspace-001",
                            "demo-blog",
                            "imported",
                            "sha256:model-document-demo-blog-v1",
                            Map.of()),
                    new ManagementValidationReport(
                            "validation-draft-demo-blog-001",
                            "draft-demo-blog-001",
                            "passed",
                            List.of()),
                    new ManagementArtifactRef(
                            "artifact-demo-blog-001",
                            "sha256:artifact-demo-blog-001",
                            "titan-artifact.json",
                            "titan-object-inventory.json",
                            "titan-install-plan.json",
                            "titan-install-verification.json"),
                    new ManagementDeployment(
                            "deployment-demo-blog-prod",
                            "workspace-001",
                            "artifact-demo-blog-001",
                            "production",
                            "pendingActivation"),
                    new ManagementAuditEvent(
                            "audit-import-attempt-001",
                            "importModelDocument",
                            "actor-admin-001",
                            "request-import-demo-blog-001",
                            "import:workspace-001:demo-blog:v1",
                            "sha256:import-demo-blog-v1",
                            "attempted",
                            occurredAt),
                    new IdempotentMutationExample(
                            "importModelDocument",
                            "import:workspace-001:demo-blog:v1",
                            "sha256:import-demo-blog-v1"),
                    List.of(
                            new ManagementGapDiagnostic(
                                    "TITAN-GAP006-DURABLE-STORE-MISSING",
                                    "GAP-006 baseline: no SQL or service-backed management store is registered."),
                            new ManagementGapDiagnostic(
                                    "TITAN-GAP006-READ-AFTER-WRITE-NOT-DURABLE",
                                    "GAP-006 baseline: read-after-write state is not durable across runtime instances."),
                            new ManagementGapDiagnostic(
                                    "TITAN-GAP006-IDEMPOTENCY-NOT-DURABLE",
                                    "GAP-006 baseline: duplicate mutation keys are not persisted or constrained."),
                            new ManagementGapDiagnostic(
                                    "TITAN-GAP006-AUDIT-NOT-DURABLE",
                                    "GAP-006 baseline: mutation attempts and outcomes are not durably recorded."),
                            new ManagementGapDiagnostic(
                                    "TITAN-GAP006-ARTIFACT-VERIFICATION-NOT-ENFORCED",
                                    "GAP-006 baseline: deployment records do not enforce GAP-005 verification.")),
                    Map.of());
        }

        boolean containsTransportOwnedTerms() {
            String joined = String.join(
                    " ",
                    draft.id(),
                    draft.workspaceId(),
                    draft.modelId(),
                    draft.status(),
                    validationReport.id(),
                    artifactRef.id(),
                    deployment.id(),
                    auditEvent.commandName(),
                    idempotentMutation.commandName());
            return TransportOwnedMetadata.containsForbiddenKey(joined);
        }
    }

    private record ManagementDraft(
            String id,
            String workspaceId,
            String modelId,
            String status,
            String documentHash,
            Map<String, String> metadata) {
        static ManagementDraft create(
                String id,
                String workspaceId,
                String modelId,
                String status,
                String documentHash,
                Map<String, String> metadata) {
            TransportOwnedMetadata.reject(metadata.keySet());
            return new ManagementDraft(id, workspaceId, modelId, status, documentHash, Map.copyOf(metadata));
        }
    }

    private record ManagementValidationReport(
            String id,
            String draftId,
            String status,
            List<String> problems) {
    }

    private record ManagementArtifactRef(
            String id,
            String artifactHash,
            String manifestPath,
            String objectInventoryPath,
            String installPlanPath,
            String installVerificationPath) {
    }

    private record ManagementDeployment(
            String id,
            String workspaceId,
            String artifactRefId,
            String environment,
            String status) {
    }

    private record ManagementAuditEvent(
            String id,
            String commandName,
            String actorId,
            String requestId,
            String idempotencyKey,
            String inputHash,
            String outcome,
            Instant occurredAt) {
    }

    private record IdempotentMutationExample(
            String commandName,
            String idempotencyKey,
            String inputHash) {
        IdempotentMutationExample withInputHash(String replacementInputHash) {
            return new IdempotentMutationExample(commandName, idempotencyKey, replacementInputHash);
        }

        IdempotencyComparison compareTo(IdempotentMutationExample laterAttempt) {
            if (idempotencyKey.equals(laterAttempt.idempotencyKey()) && inputHash.equals(laterAttempt.inputHash())) {
                return new IdempotencyComparison(
                        "replayExpectedAfterDurableBoundaryExists",
                        "TITAN-GAP006-IDEMPOTENCY-NOT-DURABLE");
            }
            if (idempotencyKey.equals(laterAttempt.idempotencyKey())) {
                return new IdempotencyComparison(
                        "conflictExpectedAfterDurableBoundaryExists",
                        "TITAN-GAP006-IDEMPOTENCY-NOT-DURABLE");
            }
            return new IdempotencyComparison(
                    "newMutationExpectedAfterDurableBoundaryExists",
                    "TITAN-GAP006-DURABLE-STORE-MISSING");
        }
    }

    private record IdempotencyComparison(String expectation, String currentGapCode) {
    }

    private record ManagementGapDiagnostic(String code, String message) {
    }

    private static final class TransportOwnedMetadata {
        private static final Set<String> FORBIDDEN_KEYS = Set.of(
                "adminUiState",
                "fieldName",
                "previewUrl",
                "resolverPath",
                "selectionSet",
                "transportRequest");

        private TransportOwnedMetadata() {
        }

        static void reject(Set<String> keys) {
            for (String key : keys) {
                if (containsForbiddenKey(key)) {
                    throw new IllegalArgumentException(
                            "TITAN-GAP006-TRANSPORT-METADATA: management fixture metadata is transport-owned: "
                                    + key);
                }
            }
        }

        static boolean containsForbiddenKey(String value) {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            return FORBIDDEN_KEYS.stream()
                    .map(key -> key.toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(lower::contains);
        }
    }
}
