package io.titan.management;

import io.titan.management.ManagementAudit.AuditedCommandHandler;
import io.titan.management.ManagementAudit.AuditedCommandResult;
import io.titan.management.ManagementAudit.AuditedCommandExecution;
import io.titan.management.ManagementCommands.CommandInvocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public final class ManagementIdempotency {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[-_.:][a-z0-9]+)*");
    private static final Pattern COMMAND_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[._:-][A-Za-z0-9]+)*");
    private static final Pattern SHA256_IDENTITY =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIAGNOSTIC_CODE =
            Pattern.compile("TITAN-[A-Z0-9-]+");
    private static final String NULL_FIELD = "-";

    private ManagementIdempotency() {
    }

    public record IdempotencyRecord(
            String commandName,
            String scope,
            String idempotencyKey,
            String inputHash,
            OutcomeStatus outcomeStatus,
            String outcomeHash,
            String resultRef,
            String errorCode,
            String errorMessage,
            Instant createdAt
    ) implements StableIdempotencyRecord {
        public IdempotencyRecord {
            requireCommandName(commandName, "idempotency command name");
            requireStableId(scope, "idempotency scope");
            requireStableId(idempotencyKey, "idempotency key");
            requireHash(inputHash, "idempotency input hash");
            Objects.requireNonNull(outcomeStatus, "idempotency outcome status");
            requireHash(outcomeHash, "idempotency outcome hash");
            resultRef = normalizeOptionalStableId(resultRef, "idempotency result ref");
            errorCode = normalizeOptionalDiagnosticCode(errorCode, "idempotency error code");
            errorMessage = normalizeOptionalText(errorMessage, "idempotency error message");
            requireInstant(createdAt, "idempotency createdAt");
            if (outcomeStatus == OutcomeStatus.SUCCESS && (resultRef == null || errorCode != null || errorMessage != null)) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-IDEMPOTENCY: successful outcomes require result ref and no error");
            }
            if (outcomeStatus == OutcomeStatus.FAILURE && (errorCode == null || errorMessage == null)) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-IDEMPOTENCY: failed outcomes require error code and message");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("commandName", commandName)
                    .field("scope", scope)
                    .field("idempotencyKey", idempotencyKey)
                    .field("inputHash", inputHash)
                    .field("outcomeStatus", outcomeStatus.id())
                    .field("outcomeHash", outcomeHash)
                    .nullableField("resultRef", resultRef)
                    .nullableField("errorCode", errorCode)
                    .nullableField("errorMessage", errorMessage)
                    .field("createdAt", createdAt.toString())
                    .toJson();
        }
    }

    public enum OutcomeStatus {
        SUCCESS("success"),
        FAILURE("failure");

        private final String id;

        OutcomeStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public interface StableIdempotencyRecord {
        String stableJson();
    }

    public interface IdempotencyStore {
        Optional<IdempotencyRecord> find(String commandName, String scope, String idempotencyKey);

        IdempotencyRecord append(IdempotencyRecord record);

        List<IdempotencyRecord> readAll();

        AuditedCommandResult executeOnce(
                CommandInvocation invocation,
                IdempotentCommandHandler handler,
                Instant outcomeAt,
                IdempotencyDecision decision);
    }

    public static final class FileIdempotencyStore implements IdempotencyStore {
        private static final ConcurrentMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();
        private final Path logPath;
        private final Object pathLock;

        public FileIdempotencyStore(Path logPath) {
            this.logPath = Objects.requireNonNull(logPath, "idempotency log path");
            this.pathLock = PATH_LOCKS.computeIfAbsent(
                    logPath.toAbsolutePath().normalize(),
                    ignored -> new Object());
        }

        @Override
        public synchronized Optional<IdempotencyRecord> find(String commandName, String scope, String idempotencyKey) {
            requireCommandName(commandName, "idempotency command name");
            requireStableId(scope, "idempotency scope");
            requireStableId(idempotencyKey, "idempotency key");
            synchronized (pathLock) {
                return findExisting(readAllUnlocked(), commandName, scope, idempotencyKey);
            }
        }

        @Override
        public synchronized IdempotencyRecord append(IdempotencyRecord record) {
            synchronized (pathLock) {
                if (findExisting(
                        readAllUnlocked(),
                        record.commandName(),
                        record.scope(),
                        record.idempotencyKey()).isPresent()) {
                    throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: idempotency record already exists");
                }
                appendUnlocked(record);
                return record;
            }
        }

        @Override
        public synchronized List<IdempotencyRecord> readAll() {
            synchronized (pathLock) {
                return readAllUnlocked();
            }
        }

        @Override
        public AuditedCommandResult executeOnce(
                CommandInvocation invocation,
                IdempotentCommandHandler handler,
                Instant outcomeAt,
                IdempotencyDecision decision
        ) {
            String commandName = invocation.descriptor().commandName();
            String scope = invocation.actor().scope();
            String key = invocation.request().idempotencyKey();
            String inputHash = invocation.canonicalInputHash();
            synchronized (pathLock) {
                Optional<IdempotencyRecord> existing = findExisting(readAllUnlocked(), commandName, scope, key);
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    decision.record = record;
                    if (!record.inputHash().equals(inputHash)) {
                        decision.conflict = true;
                        return AuditedCommandResult.failure(
                                "TITAN-MGMT-E020",
                                "idempotency input mismatch");
                    }
                    decision.replayed = true;
                    return replay(record);
                }

                IdempotentCommandResult result;
                try {
                    result = Objects.requireNonNull(
                            handler.execute(invocation),
                            "idempotent command result");
                } catch (RuntimeException exception) {
                    result = IdempotentCommandResult.failure(
                            "TITAN-MGMT-E011",
                            "command handler failed: " + exception.getClass().getSimpleName());
                }
                IdempotencyRecord record = recordFromResult(invocation, inputHash, result, outcomeAt);
                appendUnlocked(record);
                decision.record = record;
                return result.auditedResult();
            }
        }

        private Optional<IdempotencyRecord> findExisting(
                List<IdempotencyRecord> records,
                String commandName,
                String scope,
                String idempotencyKey
        ) {
            return records.stream()
                    .filter(record -> record.commandName().equals(commandName)
                            && record.scope().equals(scope)
                            && record.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        private void appendUnlocked(IdempotencyRecord record) {
            try {
                Path parent = logPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        logPath,
                        encode(record) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "TITAN-GAP006-IDEMPOTENCY: failed to append idempotency record", exception);
            }
        }

        private List<IdempotencyRecord> readAllUnlocked() {
            if (!Files.exists(logPath)) {
                return List.of();
            }
            try {
                List<IdempotencyRecord> records = new ArrayList<>();
                for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        records.add(decode(line));
                    }
                }
                records.sort(Comparator.comparing(IdempotencyRecord::commandName)
                        .thenComparing(IdempotencyRecord::scope)
                        .thenComparing(IdempotencyRecord::idempotencyKey));
                return List.copyOf(records);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "TITAN-GAP006-IDEMPOTENCY: failed to read idempotency records", exception);
            }
        }
    }

    public record IdempotentCommandExecution(
            AuditedCommandExecution auditedExecution,
            IdempotencyRecord idempotencyRecord,
            boolean replayed,
            boolean conflict
    ) {
        public IdempotentCommandExecution {
            Objects.requireNonNull(auditedExecution, "audited execution");
        }

        public boolean success() {
            return auditedExecution.success();
        }
    }

    public record IdempotentCommandResult(
            AuditedCommandResult auditedResult,
            String resultRef
    ) {
        public IdempotentCommandResult {
            Objects.requireNonNull(auditedResult, "audited result");
            if (auditedResult.success()) {
                requireStableId(resultRef, "idempotent result ref");
            } else if (resultRef != null) {
                requireStableId(resultRef, "idempotent result ref");
            }
        }

        public static IdempotentCommandResult success(String outcomeHash, String resultRef) {
            return new IdempotentCommandResult(AuditedCommandResult.success(outcomeHash), resultRef);
        }

        public static IdempotentCommandResult failure(String errorCode, String errorMessage) {
            return new IdempotentCommandResult(AuditedCommandResult.failure(errorCode, errorMessage), null);
        }
    }

    public interface IdempotentCommandHandler {
        IdempotentCommandResult execute(CommandInvocation invocation);
    }

    public static IdempotentCommandExecution execute(
            CommandInvocation invocation,
            ManagementAudit.AuditStore auditStore,
            IdempotencyStore idempotencyStore,
            IdempotentCommandHandler handler,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        Objects.requireNonNull(invocation, "command invocation");
        Objects.requireNonNull(auditStore, "audit store");
        Objects.requireNonNull(idempotencyStore, "idempotency store");
        Objects.requireNonNull(handler, "idempotent command handler");
        IdempotencyDecision decision = new IdempotencyDecision();
        AuditedCommandHandler auditedHandler = handlerInvocation -> executeIdempotent(
                handlerInvocation,
                idempotencyStore,
                handler,
                outcomeAt,
                decision);
        AuditedCommandExecution auditedExecution = ManagementAudit.execute(
                invocation,
                auditStore,
                auditedHandler,
                attemptAt,
                outcomeAt);
        return new IdempotentCommandExecution(
                auditedExecution,
                decision.record,
                decision.replayed,
                decision.conflict);
    }

    private static AuditedCommandResult executeIdempotent(
            CommandInvocation invocation,
            IdempotencyStore idempotencyStore,
            IdempotentCommandHandler handler,
            Instant outcomeAt,
            IdempotencyDecision decision
    ) {
        return idempotencyStore.executeOnce(invocation, handler, outcomeAt, decision);
    }

    private static AuditedCommandResult replay(IdempotencyRecord record) {
        if (record.outcomeStatus() == OutcomeStatus.SUCCESS) {
            return AuditedCommandResult.success(record.outcomeHash());
        }
        return AuditedCommandResult.failure(record.errorCode(), record.errorMessage());
    }

    private static IdempotencyRecord recordFromResult(
            CommandInvocation invocation,
            String inputHash,
            IdempotentCommandResult result,
            Instant createdAt
    ) {
        AuditedCommandResult auditedResult = result.auditedResult();
        return new IdempotencyRecord(
                invocation.descriptor().commandName(),
                invocation.actor().scope(),
                invocation.request().idempotencyKey(),
                inputHash,
                auditedResult.success() ? OutcomeStatus.SUCCESS : OutcomeStatus.FAILURE,
                auditedResult.success() ? auditedResult.outputHash() : hashFailure(auditedResult),
                result.resultRef(),
                auditedResult.errorCode(),
                auditedResult.errorMessage(),
                createdAt);
    }

    private static String hashFailure(AuditedCommandResult result) {
        return sha256(result.errorCode() + "\n" + result.errorMessage());
    }

    private static String encode(IdempotencyRecord record) {
        return String.join(
                "\t",
                encodeField(record.commandName()),
                encodeField(record.scope()),
                encodeField(record.idempotencyKey()),
                encodeField(record.inputHash()),
                encodeField(record.outcomeStatus().name()),
                encodeField(record.outcomeHash()),
                encodeField(record.resultRef()),
                encodeField(record.errorCode()),
                encodeField(record.errorMessage()),
                encodeField(record.createdAt().toString()));
    }

    private static IdempotencyRecord decode(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 10) {
            throw new IllegalStateException("TITAN-GAP006-IDEMPOTENCY: malformed idempotency record");
        }
        return new IdempotencyRecord(
                decodeField(fields[0]),
                decodeField(fields[1]),
                decodeField(fields[2]),
                decodeField(fields[3]),
                OutcomeStatus.valueOf(decodeField(fields[4])),
                decodeField(fields[5]),
                decodeField(fields[6]),
                decodeField(fields[7]),
                decodeField(fields[8]),
                Instant.parse(Objects.requireNonNull(decodeField(fields[9]))));
    }

    private static String encodeField(String value) {
        if (value == null) {
            return NULL_FIELD;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        if (NULL_FIELD.equals(value)) {
            return null;
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String normalizeOptionalStableId(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireStableId(value, field);
        return value;
    }

    private static String normalizeOptionalDiagnosticCode(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireDiagnosticCode(value, field);
        return value;
    }

    private static String normalizeOptionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireText(value, field);
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: idempotency record requires " + field);
        }
    }

    private static void requireStableId(String value, String field) {
        requireText(value, field);
        if (!STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: " + field + " must be a stable id");
        }
    }

    private static void requireCommandName(String value, String field) {
        requireText(value, field);
        if (!COMMAND_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "TITAN-GAP006-IDEMPOTENCY: " + field + " must be a stable command name");
        }
    }

    private static void requireHash(String value, String field) {
        requireText(value, field);
        if (!SHA256_IDENTITY.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: " + field + " must use sha256 identity");
        }
    }

    private static void requireDiagnosticCode(String value, String field) {
        requireText(value, field);
        if (!DIAGNOSTIC_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-IDEMPOTENCY: " + field + " must be a Titan diagnostic code");
        }
    }

    private static void requireInstant(Instant value, String field) {
        Objects.requireNonNull(value, field);
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class IdempotencyDecision {
        private IdempotencyRecord record;
        private boolean replayed;
        private boolean conflict;
    }

    private static final class JsonObject {
        private final List<String> fields = new ArrayList<>();

        JsonObject field(String key, String value) {
            fields.add(quoted(key) + ":" + quoted(value));
            return this;
        }

        JsonObject nullableField(String key, String value) {
            fields.add(quoted(key) + ":" + (value == null ? "null" : quoted(value)));
            return this;
        }

        String toJson() {
            return "{" + String.join(",", fields) + "}";
        }

        private static String quoted(String value) {
            StringBuilder escaped = new StringBuilder("\"");
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                switch (ch) {
                    case '\\' -> escaped.append("\\\\");
                    case '"' -> escaped.append("\\\"");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> escaped.append(ch);
                }
            }
            escaped.append('"');
            return escaped.toString();
        }
    }
}
