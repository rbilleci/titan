package io.titan.management;

import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.CommandValidation;
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
import java.util.regex.Pattern;

public final class ManagementAudit {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[-_.:][a-z0-9]+)*");
    private static final Pattern COMMAND_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[._:-][A-Za-z0-9]+)*");
    private static final Pattern SHA256_IDENTITY =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIAGNOSTIC_CODE =
            Pattern.compile("TITAN-[A-Z0-9-]+");
    private static final String NULL_FIELD = "-";

    private ManagementAudit() {
    }

    public record AuditRecord(
            String id,
            long sequence,
            String commandName,
            AuditStatus status,
            String actorId,
            String actorRole,
            String actorScope,
            String requestId,
            String idempotencyKey,
            String inputHash,
            String outputHash,
            String errorCode,
            String errorMessage,
            Instant occurredAt
    ) implements StableAuditRecord {
        public AuditRecord {
            requireStableId(id, "audit id");
            if (sequence <= 0) {
                throw new IllegalArgumentException("TITAN-GAP006-AUDIT: audit sequence must be positive");
            }
            requireCommandName(commandName, "audit command name");
            Objects.requireNonNull(status, "audit status");
            actorId = normalizeOptionalStableId(actorId, "audit actor id");
            actorRole = normalizeOptionalStableId(actorRole, "audit actor role");
            actorScope = normalizeOptionalStableId(actorScope, "audit actor scope");
            requestId = normalizeOptionalStableId(requestId, "audit request id");
            idempotencyKey = normalizeOptionalStableId(idempotencyKey, "audit idempotency key");
            requireHash(inputHash, "audit input hash");
            outputHash = normalizeOptionalHash(outputHash, "audit output hash");
            errorCode = normalizeOptionalDiagnosticCode(errorCode, "audit error code");
            errorMessage = normalizeOptionalText(errorMessage, "audit error message");
            requireInstant(occurredAt, "audit occurredAt");
            if (status == AuditStatus.ATTEMPT && (outputHash != null || errorCode != null || errorMessage != null)) {
                throw new IllegalArgumentException("TITAN-GAP006-AUDIT: attempt records must not carry outcomes");
            }
            if (status == AuditStatus.SUCCESS && outputHash == null) {
                throw new IllegalArgumentException("TITAN-GAP006-AUDIT: success records require output hash");
            }
            if ((status == AuditStatus.FAILURE || status == AuditStatus.UNAUTHORIZED)
                    && (errorCode == null || errorMessage == null)) {
                throw new IllegalArgumentException("TITAN-GAP006-AUDIT: failed records require error code and message");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("id", id)
                    .field("sequence", sequence)
                    .field("commandName", commandName)
                    .field("status", status.id())
                    .nullableField("actorId", actorId)
                    .nullableField("actorRole", actorRole)
                    .nullableField("actorScope", actorScope)
                    .nullableField("requestId", requestId)
                    .nullableField("idempotencyKey", idempotencyKey)
                    .field("inputHash", inputHash)
                    .nullableField("outputHash", outputHash)
                    .nullableField("errorCode", errorCode)
                    .nullableField("errorMessage", errorMessage)
                    .field("occurredAt", occurredAt.toString())
                    .toJson();
        }
    }

    public enum AuditStatus {
        ATTEMPT("attempt"),
        SUCCESS("success"),
        FAILURE("failure"),
        UNAUTHORIZED("unauthorized");

        private final String id;

        AuditStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public interface StableAuditRecord {
        String stableJson();
    }

    public interface AuditStore {
        AuditRecord append(AuditRecord record);

        List<AuditRecord> readAll();
    }

    public static final class FileAuditStore implements AuditStore {
        private final Path logPath;

        public FileAuditStore(Path logPath) {
            this.logPath = Objects.requireNonNull(logPath, "audit log path");
        }

        @Override
        public synchronized AuditRecord append(AuditRecord record) {
            List<AuditRecord> existing = readAll();
            long expectedSequence = existing.size() + 1L;
            if (record.sequence() != expectedSequence) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-AUDIT: audit sequence must be append-only; expected " + expectedSequence);
            }
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
                throw new IllegalStateException("TITAN-GAP006-AUDIT: failed to append audit record", exception);
            }
            return record;
        }

        @Override
        public synchronized List<AuditRecord> readAll() {
            if (!Files.exists(logPath)) {
                return List.of();
            }
            try {
                List<AuditRecord> records = new ArrayList<>();
                for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        records.add(decode(line));
                    }
                }
                records.sort(Comparator.comparingLong(AuditRecord::sequence));
                for (int index = 0; index < records.size(); index++) {
                    long expected = index + 1L;
                    if (records.get(index).sequence() != expected) {
                        throw new IllegalStateException(
                                "TITAN-GAP006-AUDIT: audit log sequence is not contiguous at " + expected);
                    }
                }
                return List.copyOf(records);
            } catch (IOException exception) {
                throw new IllegalStateException("TITAN-GAP006-AUDIT: failed to read audit records", exception);
            }
        }
    }

    public interface AuditedCommandHandler {
        AuditedCommandResult execute(CommandInvocation invocation);
    }

    public record AuditedCommandResult(
            boolean success,
            String outputHash,
            String errorCode,
            String errorMessage
    ) {
        public AuditedCommandResult {
            if (success) {
                requireHash(outputHash, "command output hash");
                if (errorCode != null || errorMessage != null) {
                    throw new IllegalArgumentException("TITAN-GAP006-AUDIT: successful command result must not carry error");
                }
            } else {
                outputHash = normalizeOptionalHash(outputHash, "command output hash");
                requireDiagnosticCode(errorCode, "command error code");
                requireText(errorMessage, "command error message");
            }
        }

        public static AuditedCommandResult success(String outputHash) {
            return new AuditedCommandResult(true, outputHash, null, null);
        }

        public static AuditedCommandResult failure(String errorCode, String errorMessage) {
            return new AuditedCommandResult(false, null, errorCode, errorMessage);
        }
    }

    public record AuditedCommandExecution(
            CommandValidation validation,
            AuditRecord attemptRecord,
            AuditRecord outcomeRecord,
            AuditedCommandResult result
    ) {
        public AuditedCommandExecution {
            Objects.requireNonNull(validation, "command validation");
            Objects.requireNonNull(attemptRecord, "attempt audit record");
            Objects.requireNonNull(outcomeRecord, "outcome audit record");
        }

        public boolean success() {
            return result != null && result.success();
        }
    }

    public static AuditedCommandExecution execute(
            CommandInvocation invocation,
            AuditStore auditStore,
            AuditedCommandHandler handler,
            Instant attemptAt,
            Instant outcomeAt
    ) {
        Objects.requireNonNull(invocation, "command invocation");
        Objects.requireNonNull(auditStore, "audit store");
        Objects.requireNonNull(handler, "audited command handler");
        CommandValidation validation = invocation.validate();
        AuditRecord attempt = auditStore.append(recordFromInvocation(
                invocation,
                validation,
                auditStore.readAll().size() + 1L,
                AuditStatus.ATTEMPT,
                null,
                null,
                null,
                attemptAt));
        if (!validation.valid()) {
            AuditStatus status = unauthorized(validation) ? AuditStatus.UNAUTHORIZED : AuditStatus.FAILURE;
            AuditRecord outcome = auditStore.append(recordFromInvocation(
                    invocation,
                    validation,
                    auditStore.readAll().size() + 1L,
                    status,
                    null,
                    firstErrorCode(validation),
                    firstErrorMessage(validation),
                    outcomeAt));
            return new AuditedCommandExecution(validation, attempt, outcome, null);
        }
        AuditedCommandResult result;
        try {
            result = Objects.requireNonNull(handler.execute(invocation), "audited command result");
        } catch (RuntimeException exception) {
            result = AuditedCommandResult.failure(
                    "TITAN-MGMT-E011",
                    "command handler failed: " + exception.getClass().getSimpleName());
        }
        AuditRecord outcome = auditStore.append(recordFromInvocation(
                invocation,
                validation,
                auditStore.readAll().size() + 1L,
                result.success() ? AuditStatus.SUCCESS : AuditStatus.FAILURE,
                result.outputHash(),
                result.errorCode(),
                result.errorMessage(),
                outcomeAt));
        return new AuditedCommandExecution(validation, attempt, outcome, result);
    }

    private static AuditRecord recordFromInvocation(
            CommandInvocation invocation,
            CommandValidation validation,
            long sequence,
            AuditStatus status,
            String outputHash,
            String errorCode,
            String errorMessage,
            Instant occurredAt
    ) {
        ManagementCommands.ActorContext actor = invocation.actor();
        ManagementCommands.RequestContext request = invocation.request();
        return new AuditRecord(
                "audit-" + String.format("%06d", sequence),
                sequence,
                invocation.descriptor().commandName(),
                status,
                actor == null ? null : actor.actorId(),
                actor == null ? null : actor.role(),
                actor == null ? null : actor.scope(),
                request == null ? null : request.requestId(),
                request == null ? null : request.idempotencyKey(),
                validation.inputHash(),
                outputHash,
                errorCode,
                errorMessage,
                occurredAt);
    }

    private static boolean unauthorized(CommandValidation validation) {
        return validation.errors().stream().anyMatch(error ->
                error.startsWith("TITAN-MGMT-E002 ")
                        || error.startsWith("TITAN-MGMT-E005 ")
                        || error.startsWith("TITAN-MGMT-E008 "));
    }

    private static String firstErrorCode(CommandValidation validation) {
        String firstError = validation.errors().isEmpty() ? "TITAN-MGMT-E010" : validation.errors().get(0);
        int separator = firstError.indexOf(' ');
        return separator < 0 ? firstError : firstError.substring(0, separator);
    }

    private static String firstErrorMessage(CommandValidation validation) {
        return validation.errors().isEmpty()
                ? "command failed"
                : validation.errors().get(0);
    }

    private static String encode(AuditRecord record) {
        return String.join(
                "\t",
                encodeField(record.id()),
                Long.toString(record.sequence()),
                encodeField(record.commandName()),
                encodeField(record.status().name()),
                encodeField(record.actorId()),
                encodeField(record.actorRole()),
                encodeField(record.actorScope()),
                encodeField(record.requestId()),
                encodeField(record.idempotencyKey()),
                encodeField(record.inputHash()),
                encodeField(record.outputHash()),
                encodeField(record.errorCode()),
                encodeField(record.errorMessage()),
                encodeField(record.occurredAt().toString()));
    }

    private static AuditRecord decode(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 14) {
            throw new IllegalStateException("TITAN-GAP006-AUDIT: malformed audit record");
        }
        return new AuditRecord(
                decodeField(fields[0]),
                Long.parseLong(fields[1]),
                decodeField(fields[2]),
                AuditStatus.valueOf(decodeField(fields[3])),
                decodeField(fields[4]),
                decodeField(fields[5]),
                decodeField(fields[6]),
                decodeField(fields[7]),
                decodeField(fields[8]),
                decodeField(fields[9]),
                decodeField(fields[10]),
                decodeField(fields[11]),
                decodeField(fields[12]),
                Instant.parse(Objects.requireNonNull(decodeField(fields[13]))));
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

    private static String normalizeOptionalHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireHash(value, field);
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
            throw new IllegalArgumentException("TITAN-GAP006-AUDIT: audit record requires " + field);
        }
    }

    private static void requireStableId(String value, String field) {
        requireText(value, field);
        if (!STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-AUDIT: " + field + " must be a stable id");
        }
    }

    private static void requireCommandName(String value, String field) {
        requireText(value, field);
        if (!COMMAND_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-AUDIT: " + field + " must be a stable command name");
        }
    }

    private static void requireHash(String value, String field) {
        requireText(value, field);
        if (!SHA256_IDENTITY.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-AUDIT: " + field + " must use sha256 identity");
        }
    }

    private static void requireDiagnosticCode(String value, String field) {
        requireText(value, field);
        if (!DIAGNOSTIC_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-AUDIT: " + field + " must be a Titan diagnostic code");
        }
    }

    private static void requireInstant(Instant value, String field) {
        Objects.requireNonNull(value, field);
    }

    private static final class JsonObject {
        private final List<String> fields = new ArrayList<>();

        JsonObject field(String key, String value) {
            fields.add(quoted(key) + ":" + quoted(value));
            return this;
        }

        JsonObject field(String key, long value) {
            fields.add(quoted(key) + ":" + value);
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
