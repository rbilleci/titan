package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementRecords.ArtifactRef;
import io.titan.management.ManagementRecords.Deployment;
import io.titan.management.ManagementRecords.DeploymentStatus;
import io.titan.management.ManagementRecords.Draft;
import io.titan.management.ManagementRecords.DraftStatus;
import io.titan.management.ManagementRecords.OperationRegistryEntry;
import io.titan.management.ManagementRecords.RegistryStatus;
import io.titan.management.ManagementRecords.UsageReport;
import io.titan.management.ManagementRecords.ValidationProblem;
import io.titan.management.ManagementRecords.ValidationReport;
import io.titan.management.ManagementRecords.ValidationStatus;
import io.titan.management.ManagementRecords.VerificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ManagementRecordsTest {
    private static final Instant T0 = Instant.parse("2026-06-08T17:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-08T17:05:00Z");
    private static final String DOCUMENT_HASH =
            "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String ARTIFACT_HASH =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    @Test
    void representsManagementGraphWithoutTransportOwnedState() {
        Draft draft = demoDraft();
        ValidationReport report = new ValidationReport(
                "validation-draft-demo-blog-001",
                draft.id(),
                ValidationStatus.PASSED,
                draft.documentHash(),
                T1,
                List.of());
        ArtifactRef artifactRef = demoArtifactRef();
        Deployment deployment = new Deployment(
                "deployment-demo-blog-prod",
                draft.workspaceId(),
                artifactRef.id(),
                "production",
                DeploymentStatus.PENDING_ACTIVATION,
                T1,
                null);
        OperationRegistryEntry registryEntry = new OperationRegistryEntry(
                "operation-demo-blog-import",
                draft.workspaceId(),
                "importModelDocument",
                artifactRef.id(),
                RegistryStatus.APPROVED,
                T1);
        UsageReport usageReport = new UsageReport(
                "usage-demo-blog-import-20260608",
                draft.workspaceId(),
                registryEntry.operationName(),
                T0,
                T1,
                3,
                T1);
        assertEquals("draft-demo-blog-001", draft.id());
        assertEquals("passed", report.status().id());
        assertEquals("artifact-demo-blog-001", artifactRef.id());
        assertEquals("pendingActivation", deployment.status().id());
        assertEquals("approved", registryEntry.status().id());
        assertEquals(3, usageReport.invocationCount());
        // Audit events live exclusively in ManagementAudit.AuditRecord since the G-11
        // reconciliation deleted the duplicate ManagementRecords.AuditEvent model.
    }

    @Test
    void rendersDeterministicJsonWithSortedMetadataAndProblems() {
        Draft draft = new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                7,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of("source", "java-mode-import", "scope", "workspace-management"));
        ValidationReport report = new ValidationReport(
                "validation-draft-demo-blog-001",
                draft.id(),
                ValidationStatus.FAILED,
                draft.documentHash(),
                T1,
                List.of(
                        new ValidationProblem("model.slug", "missing-slug", "Model slug is required"),
                        new ValidationProblem("artifact.hash", "missing-artifact", "Artifact hash is required")));

        assertEquals(
                "{\"id\":\"draft-demo-blog-001\",\"workspaceId\":\"workspace-001\",\"modelId\":\"demo-blog\","
                        + "\"version\":7,\"status\":\"imported\","
                        + "\"documentHash\":\"" + DOCUMENT_HASH + "\","
                        + "\"createdAt\":\"2026-06-08T17:00:00Z\","
                        + "\"updatedAt\":\"2026-06-08T17:05:00Z\","
                        + "\"metadata\":{\"scope\":\"workspace-management\",\"source\":\"java-mode-import\"}}",
                draft.stableJson());
        assertEquals(
                "{\"id\":\"artifact-demo-blog-001\",\"artifactId\":\"demo-blog-artifact\","
                        + "\"artifactHash\":\"" + ARTIFACT_HASH + "\","
                        + "\"manifestPath\":\"titan-artifact.json\","
                        + "\"objectInventoryPath\":\"titan-object-inventory.json\","
                        + "\"installPlanPath\":\"titan-install-plan.json\","
                        + "\"installVerificationPath\":\"titan-install-verification.json\","
                        + "\"verificationStatus\":\"passed\"}",
                demoArtifactRef().stableJson());
        assertEquals(
                "{\"id\":\"deployment-demo-blog-prod\",\"workspaceId\":\"workspace-001\","
                        + "\"artifactRefId\":\"artifact-demo-blog-001\","
                        + "\"environment\":\"production\",\"status\":\"active\","
                        + "\"requestedAt\":\"2026-06-08T17:00:00Z\","
                        + "\"activatedAt\":\"2026-06-08T17:05:00Z\"}",
                new Deployment(
                                "deployment-demo-blog-prod",
                                "workspace-001",
                                "artifact-demo-blog-001",
                                "production",
                                DeploymentStatus.ACTIVE,
                                T0,
                                T1)
                        .stableJson());
        assertEquals(
                "{\"id\":\"operation-demo-blog-import\",\"workspaceId\":\"workspace-001\","
                        + "\"operationName\":\"importModelDocument\","
                        + "\"artifactRefId\":\"artifact-demo-blog-001\","
                        + "\"status\":\"approved\",\"registeredAt\":\"2026-06-08T17:05:00Z\"}",
                new OperationRegistryEntry(
                                "operation-demo-blog-import",
                                "workspace-001",
                                "importModelDocument",
                                "artifact-demo-blog-001",
                                RegistryStatus.APPROVED,
                                T1)
                        .stableJson());
        assertEquals(
                "{\"id\":\"usage-demo-blog-import-20260608\",\"workspaceId\":\"workspace-001\","
                        + "\"operationName\":\"importModelDocument\","
                        + "\"periodStart\":\"2026-06-08T17:00:00Z\","
                        + "\"periodEnd\":\"2026-06-08T17:05:00Z\","
                        + "\"invocationCount\":3,\"generatedAt\":\"2026-06-08T17:05:00Z\"}",
                new UsageReport(
                                "usage-demo-blog-import-20260608",
                                "workspace-001",
                                "importModelDocument",
                                T0,
                                T1,
                                3,
                                T1)
                        .stableJson());
        assertEquals(report.stableJson(), report.stableJson());
        assertTrue(report.stableJson().indexOf("artifact.hash") < report.stableJson().indexOf("model.slug"));
    }

    @Test
    void validatesRequiredIdsHashesTimestampsAndStateTransitions() {
        assertDomainError(() -> new Draft(
                "Draft Demo Blog 001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of()), "draft id must be a stable id");
        assertDomainError(() -> new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                0,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of()), "draft version must be positive");
        assertDomainError(() -> new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                "sha256:not-a-real-hash",
                T0,
                T1,
                Map.of()), "draft document hash must use sha256 identity");
        assertDomainError(() -> new ArtifactRef(
                "artifact-demo-blog-001",
                "demo-blog-artifact",
                ARTIFACT_HASH,
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json",
                VerificationStatus.PASSED,
                "migration",
                null,
                null,
                null,
                null,
                null,
                null), "artifact GAP-005 evidence must be complete or absent");
        assertDomainError(() -> new Deployment(
                "deployment-demo-blog-prod",
                "workspace-001",
                "artifact-demo-blog-001",
                "production",
                DeploymentStatus.ACTIVE,
                T0,
                null), "active deployment requires activatedAt");
    }

    @Test
    void acceptsIdsThatMerelyContainBlockedSubstrings() {
        // Regression for the G-11 substring false-positive: 'curl-team' contains 'url' but is
        // not a transport-owned token; exact-token matching must accept it.
        Draft draft = new Draft(
                "draft-demo-blog-001",
                "curl-team",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of("owner", "curl-team"));
        assertEquals("curl-team", draft.workspaceId());

        // Other near-misses that substring matching used to reject.
        assertEquals("purled-yarn", new Draft(
                "draft-demo-blog-002",
                "purled-yarn",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of()).workspaceId());
    }

    @Test
    void rejectsTransportAndProductOwnedFieldsAtTheCoreBoundary() {
        assertTransportError(() -> new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of("previewUrl", "/admin/preview/demo-blog")), "previewUrl");
        assertTransportError(() -> new OperationRegistryEntry(
                "operation-demo-blog-import",
                "workspace-001",
                "resolverPath",
                "artifact-demo-blog-001",
                RegistryStatus.OBSERVED,
                T0), "resolverPath");
        assertTransportError(() -> new ValidationProblem(
                "selectionSet.demoBlog.title",
                "unsupported-path",
                "Selection metadata belongs outside Titan core"), "selectionSet");
        assertTransportError(() -> new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of("graphqlSchema", "model-v1")), "graphqlSchema");
        assertTransportError(() -> new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of("adminState", "open")), "adminState");
        assertTransportError(() -> new Deployment(
                "preview-build-001",
                "workspace-001",
                "artifact-demo-blog-001",
                "production",
                DeploymentStatus.PENDING_ACTIVATION,
                T0,
                null), "preview-build-001");
        assertTransportError(() -> new OperationRegistryEntry(
                "operation-demo-blog-import",
                "workspace-001",
                "productWorkflow",
                "artifact-demo-blog-001",
                RegistryStatus.OBSERVED,
                T0), "productWorkflow");
    }

    private static Draft demoDraft() {
        return new Draft(
                "draft-demo-blog-001",
                "workspace-001",
                "demo-blog",
                1,
                DraftStatus.IMPORTED,
                DOCUMENT_HASH,
                T0,
                T1,
                Map.of());
    }

    private static ArtifactRef demoArtifactRef() {
        return new ArtifactRef(
                "artifact-demo-blog-001",
                "demo-blog-artifact",
                ARTIFACT_HASH,
                "titan-artifact.json",
                "titan-object-inventory.json",
                "titan-install-plan.json",
                "titan-install-verification.json",
                VerificationStatus.PASSED);
    }

    private static void assertDomainError(Executable runnable, String messagePart) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, runnable);
        assertTrue(exception.getMessage().contains("TITAN-GAP006-DOMAIN"));
        assertTrue(exception.getMessage().contains(messagePart), exception.getMessage());
    }

    private static void assertTransportError(Executable runnable, String messagePart) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, runnable);
        assertTrue(exception.getMessage().contains("TITAN-GAP006-TRANSPORT-METADATA"));
        assertTrue(exception.getMessage().contains(messagePart), exception.getMessage());
    }
}
