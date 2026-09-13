package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementAudit.AuditedCommandExecution;
import io.titan.management.ManagementAudit.AuditedCommandResult;
import io.titan.management.ManagementAudit.FileAuditStore;
import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.ManagementCommandDescriptors;
import io.titan.management.ManagementCommands.RequestContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementAuditTest {
    private static final Instant T0 = Instant.parse("2026-06-08T17:01:00Z");
    private static final Instant T1 = Instant.parse("2026-06-08T17:01:01Z");
    private static final String INPUT_HASH =
            "sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b";
    private static final String OUTPUT_HASH =
            "sha256:4444444444444444444444444444444444444444444444444444444444444444";

    @TempDir
    Path tempDir;

    @Test
    void persistsAttemptAndSuccessAuditRecordsAcrossStoreInstances() {
        Path auditLog = tempDir.resolve("management-audit.log");
        FileAuditStore store = new FileAuditStore(auditLog);

        AuditedCommandExecution execution = ManagementAudit.execute(
                validImportInvocation(),
                store,
                invocation -> AuditedCommandResult.success(OUTPUT_HASH),
                T0,
                T1);

        assertTrue(execution.success());
        assertEquals(AuditStatus.ATTEMPT, execution.attemptRecord().status());
        assertEquals(AuditStatus.SUCCESS, execution.outcomeRecord().status());
        assertEquals(1, execution.attemptRecord().sequence());
        assertEquals(2, execution.outcomeRecord().sequence());

        List<AuditRecord> reloaded = new FileAuditStore(auditLog).readAll();
        assertEquals(List.of("audit-000001", "audit-000002"), reloaded.stream().map(AuditRecord::id).toList());
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS), reloaded.stream().map(AuditRecord::status).toList());
        assertEquals(List.of("actor-platform-001", "actor-platform-001"), reloaded.stream().map(AuditRecord::actorId).toList());
        assertEquals(List.of("request-import-demo-blog-001", "request-import-demo-blog-001"),
                reloaded.stream().map(AuditRecord::requestId).toList());
        assertEquals(List.of("import:workspace-001:demo-blog:v1", "import:workspace-001:demo-blog:v1"),
                reloaded.stream().map(AuditRecord::idempotencyKey).toList());
        assertEquals(OUTPUT_HASH, reloaded.get(1).outputHash());
        assertEquals(
                "{\"id\":\"audit-000002\",\"sequence\":2,"
                        + "\"commandName\":\"management.importModelDocument\","
                        + "\"status\":\"success\","
                        + "\"actorId\":\"actor-platform-001\","
                        + "\"actorRole\":\"platform\","
                        + "\"actorScope\":\"workspace-001\","
                        + "\"requestId\":\"request-import-demo-blog-001\","
                        + "\"idempotencyKey\":\"import:workspace-001:demo-blog:v1\","
                        + "\"inputHash\":\"" + INPUT_HASH + "\","
                        + "\"outputHash\":\"" + OUTPUT_HASH + "\","
                        + "\"errorCode\":null,\"errorMessage\":null,"
                        + "\"occurredAt\":\"2026-06-08T17:01:01Z\"}",
                reloaded.get(1).stableJson());
    }

    @Test
    void auditsFailedCommandOutcomeWithoutDiscardingAttempt() {
        FileAuditStore store = new FileAuditStore(tempDir.resolve("failure-audit.log"));

        AuditedCommandExecution execution = ManagementAudit.execute(
                validImportInvocation(),
                store,
                invocation -> AuditedCommandResult.failure(
                        "TITAN-MGMT-E010",
                        "model document failed validation"),
                T0,
                T1);

        assertFalse(execution.success());
        List<AuditRecord> records = store.readAll();
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE), records.stream().map(AuditRecord::status).toList());
        assertEquals("TITAN-MGMT-E010", records.get(1).errorCode());
        assertEquals("model document failed validation", records.get(1).errorMessage());
        assertEquals(INPUT_HASH, records.get(0).inputHash());
        assertEquals(INPUT_HASH, records.get(1).inputHash());
    }

    @Test
    void auditsThrownCommandFailureAsOutcomeRecord() {
        FileAuditStore store = new FileAuditStore(tempDir.resolve("thrown-failure-audit.log"));

        AuditedCommandExecution execution = ManagementAudit.execute(
                validImportInvocation(),
                store,
                invocation -> {
                    throw new IllegalStateException("validation service unavailable");
                },
                T0,
                T1);

        assertFalse(execution.success());
        List<AuditRecord> records = store.readAll();
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE), records.stream().map(AuditRecord::status).toList());
        assertEquals("TITAN-MGMT-E011", records.get(1).errorCode());
        assertEquals("command handler failed: IllegalStateException", records.get(1).errorMessage());
    }

    @Test
    void auditsUnauthorizedCommandBeforeRejectingExecution() {
        FileAuditStore store = new FileAuditStore(tempDir.resolve("unauthorized-audit.log"));
        CommandInvocation invocation = new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-viewer-001", "viewer", "workspace-001", true),
                new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                importInput());

        AuditedCommandExecution execution = ManagementAudit.execute(
                invocation,
                store,
                handlerInvocation -> {
                    throw new AssertionError("unauthorized command must not execute handler");
                },
                T0,
                T1);

        assertFalse(execution.validation().valid());
        assertFalse(execution.success());
        List<AuditRecord> records = store.readAll();
        assertEquals(List.of(AuditStatus.ATTEMPT, AuditStatus.UNAUTHORIZED),
                records.stream().map(AuditRecord::status).toList());
        assertEquals("TITAN-MGMT-E005", records.get(1).errorCode());
        assertTrue(records.get(1).errorMessage().contains("actor role 'viewer' is not allowed"));
    }

    @Test
    void auditsInputValidationFailureAsCommandFailure() {
        FileAuditStore store = new FileAuditStore(tempDir.resolve("validation-audit.log"));
        CommandInvocation invocation = new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                Map.of(
                        "workspaceId", "workspace-001",
                        "sourceFormat", "xml",
                        "sourceText", "model: demo-blog"));

        AuditedCommandExecution execution = ManagementAudit.execute(
                invocation,
                store,
                handlerInvocation -> {
                    throw new AssertionError("invalid command must not execute handler");
                },
                T0,
                T1);

        assertFalse(execution.validation().valid());
        assertEquals(AuditStatus.FAILURE, execution.outcomeRecord().status());
        assertEquals("TITAN-MGMT-E006", execution.outcomeRecord().errorCode());
    }

    @Test
    void preservesAppendOnlyOrderingAndRejectsInvalidOutcomeShapes() {
        FileAuditStore store = new FileAuditStore(tempDir.resolve("manual-audit.log"));
        store.append(new AuditRecord(
                "audit-000001",
                1,
                "management.importModelDocument",
                AuditStatus.ATTEMPT,
                "actor-platform-001",
                "platform",
                "workspace-001",
                "request-import-demo-blog-001",
                "import:workspace-001:demo-blog:v1",
                INPUT_HASH,
                null,
                null,
                null,
                T0));

        IllegalArgumentException sequenceError = assertThrows(
                IllegalArgumentException.class,
                () -> store.append(new AuditRecord(
                        "audit-000003",
                        3,
                        "management.importModelDocument",
                        AuditStatus.SUCCESS,
                        "actor-platform-001",
                        "platform",
                        "workspace-001",
                        "request-import-demo-blog-001",
                        "import:workspace-001:demo-blog:v1",
                        INPUT_HASH,
                        OUTPUT_HASH,
                        null,
                        null,
                        T1)));
        assertTrue(sequenceError.getMessage().contains("append-only"));

        IllegalArgumentException outputError = assertThrows(
                IllegalArgumentException.class,
                () -> new AuditRecord(
                        "audit-000002",
                        2,
                        "management.importModelDocument",
                        AuditStatus.SUCCESS,
                        "actor-platform-001",
                        "platform",
                        "workspace-001",
                        "request-import-demo-blog-001",
                        "import:workspace-001:demo-blog:v1",
                        INPUT_HASH,
                        null,
                        null,
                        null,
                        T1));
        assertTrue(outputError.getMessage().contains("success records require output hash"));
    }

    private static CommandInvocation validImportInvocation() {
        return new CommandInvocation(
                ManagementCommandDescriptors.importModelDocument(),
                new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                importInput());
    }

    private static Map<String, String> importInput() {
        return Map.of(
                "workspaceId", "workspace-001",
                "sourceFormat", "yaml",
                "sourceText", "model: demo-blog");
    }
}
