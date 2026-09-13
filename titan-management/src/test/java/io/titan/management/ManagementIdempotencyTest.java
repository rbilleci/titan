package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementAudit.AuditRecord;
import io.titan.management.ManagementAudit.AuditStatus;
import io.titan.management.ManagementAudit.FileAuditStore;
import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.ManagementCommandDescriptors;
import io.titan.management.ManagementCommands.RequestContext;
import io.titan.management.ManagementIdempotency.FileIdempotencyStore;
import io.titan.management.ManagementIdempotency.IdempotencyRecord;
import io.titan.management.ManagementIdempotency.IdempotentCommandExecution;
import io.titan.management.ManagementIdempotency.IdempotentCommandResult;
import io.titan.management.ManagementIdempotency.OutcomeStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementIdempotencyTest {
    private static final Instant T0 = Instant.parse("2026-06-08T17:01:00Z");
    private static final Instant T1 = Instant.parse("2026-06-08T17:01:01Z");
    private static final Instant T2 = Instant.parse("2026-06-08T17:01:02Z");
    private static final Instant T3 = Instant.parse("2026-06-08T17:01:03Z");
    private static final String INPUT_HASH =
            "sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b";
    private static final String OUTPUT_HASH =
            "sha256:4444444444444444444444444444444444444444444444444444444444444444";

    @TempDir
    Path tempDir;

    @Test
    void replaysSameKeyAndSameInputWithoutRepeatingStateTransition() {
        FileAuditStore auditStore = new FileAuditStore(tempDir.resolve("same-input-audit.log"));
        Path idempotencyLog = tempDir.resolve("same-input-idempotency.log");
        FileIdempotencyStore idempotencyStore = new FileIdempotencyStore(idempotencyLog);
        AtomicInteger stateTransitions = new AtomicInteger();

        IdempotentCommandExecution first = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    stateTransitions.incrementAndGet();
                    return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-001");
                },
                T0,
                T1);
        IdempotentCommandExecution second = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-002", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    stateTransitions.incrementAndGet();
                    return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-002");
                },
                T2,
                T3);

        assertTrue(first.success());
        assertTrue(second.success());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(1, stateTransitions.get());
        assertEquals("draft-demo-blog-001", second.idempotencyRecord().resultRef());
        assertEquals(OUTPUT_HASH, second.auditedExecution().result().outputHash());

        List<AuditRecord> auditRecords = auditStore.readAll();
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.SUCCESS),
                auditRecords.stream().map(AuditRecord::status).toList());
        assertEquals(List.of(
                        "request-import-demo-blog-001",
                        "request-import-demo-blog-001",
                        "request-import-demo-blog-002",
                        "request-import-demo-blog-002"),
                auditRecords.stream().map(AuditRecord::requestId).toList());
        assertEquals(List.of(INPUT_HASH, INPUT_HASH, INPUT_HASH, INPUT_HASH),
                auditRecords.stream().map(AuditRecord::inputHash).toList());

        List<IdempotencyRecord> reloaded = new FileIdempotencyStore(idempotencyLog).readAll();
        assertEquals(1, reloaded.size());
        assertEquals(OutcomeStatus.SUCCESS, reloaded.get(0).outcomeStatus());
        assertEquals(
                "{\"commandName\":\"management.importModelDocument\","
                        + "\"scope\":\"workspace-001\","
                        + "\"idempotencyKey\":\"import:workspace-001:demo-blog:v1\","
                        + "\"inputHash\":\"" + INPUT_HASH + "\","
                        + "\"outcomeStatus\":\"success\","
                        + "\"outcomeHash\":\"" + OUTPUT_HASH + "\","
                        + "\"resultRef\":\"draft-demo-blog-001\","
                        + "\"errorCode\":null,\"errorMessage\":null,"
                        + "\"createdAt\":\"2026-06-08T17:01:01Z\"}",
                reloaded.get(0).stableJson());
    }

    @Test
    void rejectsSameKeyWithDifferentInputAndAuditsTheAttempt() {
        FileAuditStore auditStore = new FileAuditStore(tempDir.resolve("conflict-audit.log"));
        FileIdempotencyStore idempotencyStore = new FileIdempotencyStore(tempDir.resolve("conflict-idempotency.log"));
        AtomicInteger stateTransitions = new AtomicInteger();

        ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    stateTransitions.incrementAndGet();
                    return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-001");
                },
                T0,
                T1);
        IdempotentCommandExecution conflict = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-002", "model: changed-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    stateTransitions.incrementAndGet();
                    return IdempotentCommandResult.success(
                            "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                            "draft-demo-blog-002");
                },
                T2,
                T3);

        assertFalse(conflict.success());
        assertTrue(conflict.conflict());
        assertEquals(1, stateTransitions.get());
        assertEquals("TITAN-MGMT-E020", conflict.auditedExecution().result().errorCode());
        assertEquals("idempotency input mismatch", conflict.auditedExecution().result().errorMessage());
        assertEquals(1, idempotencyStore.readAll().size());

        List<AuditRecord> auditRecords = auditStore.readAll();
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.SUCCESS, AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                auditRecords.stream().map(AuditRecord::status).toList());
        assertEquals("TITAN-MGMT-E020", auditRecords.get(3).errorCode());
        assertEquals("idempotency input mismatch", auditRecords.get(3).errorMessage());
    }

    @Test
    void replaysStoredFailureOutcomeForSameInput() {
        FileAuditStore auditStore = new FileAuditStore(tempDir.resolve("failure-replay-audit.log"));
        FileIdempotencyStore idempotencyStore = new FileIdempotencyStore(tempDir.resolve("failure-replay-idempotency.log"));
        AtomicInteger handlerCalls = new AtomicInteger();

        IdempotentCommandExecution first = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    handlerCalls.incrementAndGet();
                    return IdempotentCommandResult.failure(
                            "TITAN-MGMT-E010",
                            "model document failed validation");
                },
                T0,
                T1);
        IdempotentCommandExecution second = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-002", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    handlerCalls.incrementAndGet();
                    return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-001");
                },
                T2,
                T3);

        assertFalse(first.success());
        assertFalse(second.success());
        assertTrue(second.replayed());
        assertEquals(1, handlerCalls.get());
        assertEquals("TITAN-MGMT-E010", second.auditedExecution().result().errorCode());
        assertEquals(OutcomeStatus.FAILURE, idempotencyStore.readAll().get(0).outcomeStatus());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE, AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                auditStore.readAll().stream().map(AuditRecord::status).toList());
    }

    @Test
    void storesThrownHandlerFailureForDurableReplay() {
        FileAuditStore auditStore = new FileAuditStore(tempDir.resolve("thrown-replay-audit.log"));
        FileIdempotencyStore idempotencyStore = new FileIdempotencyStore(tempDir.resolve("thrown-replay-idempotency.log"));
        AtomicInteger handlerCalls = new AtomicInteger();

        IdempotentCommandExecution first = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    handlerCalls.incrementAndGet();
                    throw new IllegalStateException("draft writer unavailable");
                },
                T0,
                T1);
        IdempotentCommandExecution second = ManagementIdempotency.execute(
                validImportInvocation("request-import-demo-blog-002", "model: demo-blog"),
                auditStore,
                idempotencyStore,
                invocation -> {
                    handlerCalls.incrementAndGet();
                    return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-001");
                },
                T2,
                T3);

        assertFalse(first.success());
        assertFalse(second.success());
        assertTrue(second.replayed());
        assertEquals(1, handlerCalls.get());
        assertEquals("TITAN-MGMT-E011", second.auditedExecution().result().errorCode());
        assertEquals("command handler failed: IllegalStateException", second.auditedExecution().result().errorMessage());
        assertEquals(OutcomeStatus.FAILURE, idempotencyStore.readAll().get(0).outcomeStatus());
        assertEquals(
                List.of(AuditStatus.ATTEMPT, AuditStatus.FAILURE, AuditStatus.ATTEMPT, AuditStatus.FAILURE),
                auditStore.readAll().stream().map(AuditRecord::status).toList());
    }

    @Test
    void serializesConcurrentSameKeyAttemptsAcrossStoreInstances() throws Exception {
        FileAuditStore firstAuditStore = new FileAuditStore(tempDir.resolve("concurrent-audit-1.log"));
        FileAuditStore secondAuditStore = new FileAuditStore(tempDir.resolve("concurrent-audit-2.log"));
        Path idempotencyLog = tempDir.resolve("concurrent-idempotency.log");
        AtomicInteger stateTransitions = new AtomicInteger();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentCommandExecution> first = executor.submit(() -> ManagementIdempotency.execute(
                    validImportInvocation("request-import-demo-blog-001", "model: demo-blog"),
                    firstAuditStore,
                    new FileIdempotencyStore(idempotencyLog),
                    invocation -> {
                        stateTransitions.incrementAndGet();
                        handlerEntered.countDown();
                        await(releaseHandler);
                        return IdempotentCommandResult.success(OUTPUT_HASH, "draft-demo-blog-001");
                    },
                    T0,
                    T1));
            handlerEntered.await();
            Future<IdempotentCommandExecution> second = executor.submit(() -> ManagementIdempotency.execute(
                    validImportInvocation("request-import-demo-blog-002", "model: demo-blog"),
                    secondAuditStore,
                    new FileIdempotencyStore(idempotencyLog),
                    invocation -> {
                        stateTransitions.incrementAndGet();
                        return IdempotentCommandResult.success(
                                "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                                "draft-demo-blog-002");
                    },
                    T2,
                    T3));
            releaseHandler.countDown();

            List<IdempotentCommandExecution> results = List.of(first.get(), second.get());

            assertEquals(1, stateTransitions.get());
            assertEquals(1, new FileIdempotencyStore(idempotencyLog).readAll().size());
            assertEquals(2, results.stream().filter(IdempotentCommandExecution::success).count());
            assertEquals(1, results.stream().filter(IdempotentCommandExecution::replayed).count());
            List<AuditRecord> auditRecords = new java.util.ArrayList<>();
            auditRecords.addAll(firstAuditStore.readAll());
            auditRecords.addAll(secondAuditStore.readAll());
            assertEquals(4, auditRecords.size());
            assertEquals(2, auditRecords.stream()
                    .filter(record -> record.status() == AuditStatus.ATTEMPT)
                    .count());
            assertEquals(2, auditRecords.stream()
                    .filter(record -> record.status() == AuditStatus.SUCCESS)
                    .count());
        } finally {
            executor.shutdownNow();
        }
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test latch", exception);
        }
    }
}
