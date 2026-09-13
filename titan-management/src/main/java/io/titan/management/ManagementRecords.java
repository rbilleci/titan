package io.titan.management;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class ManagementRecords {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[-_.:][a-z0-9]+)*");
    private static final Pattern COMMAND_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[._:-][A-Za-z0-9]+)*");
    private static final Pattern SHA256_IDENTITY =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> TRANSPORT_OWNED_TERMS = Set.of(
            "admin",
            "fieldname",
            "graphql",
            "mutationfield",
            "preview",
            "productworkflow",
            "resolver",
            "selection",
            "transport",
            "uistate",
            "url",
            "workflow");

    private ManagementRecords() {
    }

    public record Draft(
            String id,
            String workspaceId,
            String modelId,
            long version,
            DraftStatus status,
            String documentHash,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> metadata
    ) implements StableManagementRecord {
        public Draft {
            requireStableId(id, "draft id");
            requireStableId(workspaceId, "workspace id");
            requireStableId(modelId, "model id");
            requirePositive(version, "draft version");
            Objects.requireNonNull(status, "draft status");
            requireHash(documentHash, "draft document hash");
            requireInstant(createdAt, "draft createdAt");
            requireInstant(updatedAt, "draft updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: draft updatedAt must not precede createdAt");
            }
            metadata = immutableSortedMetadata(metadata, "draft metadata");
        }

        @Override
        public String stableJson() {
            JsonObject json = new JsonObject()
                    .field("id", id)
                    .field("workspaceId", workspaceId)
                    .field("modelId", modelId)
                    .field("version", version)
                    .field("status", status.id())
                    .field("documentHash", documentHash)
                    .field("createdAt", createdAt.toString())
                    .field("updatedAt", updatedAt.toString());
            metadataField(json, metadata);
            return json.toJson();
        }
    }

    public record ValidationReport(
            String id,
            String draftId,
            ValidationStatus status,
            String documentHash,
            Instant checkedAt,
            List<ValidationProblem> problems
    ) implements StableManagementRecord {
        public ValidationReport {
            requireStableId(id, "validation report id");
            requireStableId(draftId, "validation report draft id");
            Objects.requireNonNull(status, "validation report status");
            requireHash(documentHash, "validation report document hash");
            requireInstant(checkedAt, "validation report checkedAt");
            problems = sortedCopy(problems, Comparator.comparing(ValidationProblem::path)
                    .thenComparing(ValidationProblem::code)
                    .thenComparing(ValidationProblem::message));
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("id", id)
                    .field("draftId", draftId)
                    .field("status", status.id())
                    .field("documentHash", documentHash)
                    .field("checkedAt", checkedAt.toString())
                    .rawField("problems", jsonArray(problems.stream()
                            .map(ValidationProblem::stableJson)
                            .toList()))
                    .toJson();
        }
    }

    public record ValidationProblem(String path, String code, String message) implements StableManagementRecord {
        public ValidationProblem {
            requireText(path, "validation problem path");
            requireStableId(code, "validation problem code");
            requireText(message, "validation problem message");
            rejectTransportOwned(path, "validation problem path");
            rejectTransportOwned(code, "validation problem code");
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("path", path)
                    .field("code", code)
                    .field("message", message)
                    .toJson();
        }
    }

    public record ArtifactRef(
            String id,
            String artifactId,
            String artifactHash,
            String manifestPath,
            String objectInventoryPath,
            String installPlanPath,
            String installVerificationPath,
            VerificationStatus verificationStatus,
            String packageMode,
            String dialect,
            String manifestContentHash,
            String objectInventoryHash,
            String installPlanHash,
            String installVerificationHash,
            String sourceInputsHash
    ) implements StableManagementRecord {
        public ArtifactRef(
                String id,
                String artifactId,
                String artifactHash,
                String manifestPath,
                String objectInventoryPath,
                String installPlanPath,
                String installVerificationPath,
                VerificationStatus verificationStatus
        ) {
            this(
                    id,
                    artifactId,
                    artifactHash,
                    manifestPath,
                    objectInventoryPath,
                    installPlanPath,
                    installVerificationPath,
                    verificationStatus,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public ArtifactRef {
            requireStableId(id, "artifact ref id");
            requireStableId(artifactId, "artifact id");
            requireHash(artifactHash, "artifact hash");
            requirePath(manifestPath, "manifest path");
            requirePath(objectInventoryPath, "object inventory path");
            requirePath(installPlanPath, "install plan path");
            requirePath(installVerificationPath, "install verification path");
            Objects.requireNonNull(verificationStatus, "artifact verification status");
            packageMode = normalizeOptionalStableId(packageMode, "artifact package mode");
            dialect = normalizeOptionalStableId(dialect, "artifact dialect");
            manifestContentHash = normalizeOptionalHash(manifestContentHash, "manifest content hash");
            objectInventoryHash = normalizeOptionalHash(objectInventoryHash, "object inventory hash");
            installPlanHash = normalizeOptionalHash(installPlanHash, "install plan hash");
            installVerificationHash = normalizeOptionalHash(installVerificationHash, "install verification hash");
            sourceInputsHash = normalizeOptionalHash(sourceInputsHash, "source inputs hash");
            long evidenceFields = java.util.stream.Stream.of(
                            packageMode,
                            dialect,
                            manifestContentHash,
                            objectInventoryHash,
                            installPlanHash,
                            installVerificationHash,
                            sourceInputsHash)
                    .filter(Objects::nonNull)
                    .count();
            if (evidenceFields != 0 && evidenceFields != 7) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-DOMAIN: artifact GAP-005 evidence must be complete or absent");
            }
        }

        @Override
        public String stableJson() {
            JsonObject json = new JsonObject()
                    .field("id", id)
                    .field("artifactId", artifactId)
                    .field("artifactHash", artifactHash)
                    .field("manifestPath", manifestPath)
                    .field("objectInventoryPath", objectInventoryPath)
                    .field("installPlanPath", installPlanPath)
                    .field("installVerificationPath", installVerificationPath)
                    .field("verificationStatus", verificationStatus.id());
            if (packageMode != null) {
                json.field("packageMode", packageMode)
                        .field("dialect", dialect)
                        .field("manifestContentHash", manifestContentHash)
                        .field("objectInventoryHash", objectInventoryHash)
                        .field("installPlanHash", installPlanHash)
                        .field("installVerificationHash", installVerificationHash)
                        .field("sourceInputsHash", sourceInputsHash);
            }
            return json.toJson();
        }
    }

    public record Deployment(
            String id,
            String workspaceId,
            String artifactRefId,
            String environment,
            DeploymentStatus status,
            Instant requestedAt,
            Instant activatedAt
    ) implements StableManagementRecord {
        public Deployment {
            requireStableId(id, "deployment id");
            requireStableId(workspaceId, "deployment workspace id");
            requireStableId(artifactRefId, "deployment artifact ref id");
            requireStableId(environment, "deployment environment");
            Objects.requireNonNull(status, "deployment status");
            requireInstant(requestedAt, "deployment requestedAt");
            if (activatedAt != null && activatedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-DOMAIN: deployment activatedAt must not precede requestedAt");
            }
            if (status == DeploymentStatus.ACTIVE && activatedAt == null) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-DOMAIN: active deployment requires activatedAt");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("id", id)
                    .field("workspaceId", workspaceId)
                    .field("artifactRefId", artifactRefId)
                    .field("environment", environment)
                    .field("status", status.id())
                    .field("requestedAt", requestedAt.toString())
                    .nullableField("activatedAt", activatedAt == null ? null : activatedAt.toString())
                    .toJson();
        }
    }

    public record OperationRegistryEntry(
            String id,
            String workspaceId,
            String operationName,
            String artifactRefId,
            RegistryStatus status,
            Instant registeredAt
    ) implements StableManagementRecord {
        public OperationRegistryEntry {
            requireStableId(id, "operation registry entry id");
            requireStableId(workspaceId, "operation registry workspace id");
            requireCommandName(operationName, "operation name");
            requireStableId(artifactRefId, "operation registry artifact ref id");
            Objects.requireNonNull(status, "operation registry status");
            requireInstant(registeredAt, "operation registry registeredAt");
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("id", id)
                    .field("workspaceId", workspaceId)
                    .field("operationName", operationName)
                    .field("artifactRefId", artifactRefId)
                    .field("status", status.id())
                    .field("registeredAt", registeredAt.toString())
                    .toJson();
        }
    }

    public record UsageReport(
            String id,
            String workspaceId,
            String operationName,
            Instant periodStart,
            Instant periodEnd,
            long invocationCount,
            Instant generatedAt
    ) implements StableManagementRecord {
        public UsageReport {
            requireStableId(id, "usage report id");
            requireStableId(workspaceId, "usage report workspace id");
            requireCommandName(operationName, "usage report operation name");
            requireInstant(periodStart, "usage report periodStart");
            requireInstant(periodEnd, "usage report periodEnd");
            requireNonNegative(invocationCount, "usage report invocationCount");
            requireInstant(generatedAt, "usage report generatedAt");
            if (periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: usage report periodEnd must not precede periodStart");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("id", id)
                    .field("workspaceId", workspaceId)
                    .field("operationName", operationName)
                    .field("periodStart", periodStart.toString())
                    .field("periodEnd", periodEnd.toString())
                    .field("invocationCount", invocationCount)
                    .field("generatedAt", generatedAt.toString())
                    .toJson();
        }
    }

    // NOTE (audit G-11 reconciliation): this class previously declared a second audit-event
    // model (AuditEvent + AuditOutcome) alongside ManagementAudit.AuditRecord. AuditRecord is
    // the model every store and transaction path actually uses; the unused AuditEvent model was
    // deleted to remove the contradiction. Use ManagementAudit.AuditRecord.

    public interface StableManagementRecord {
        String stableJson();
    }

    public enum DraftStatus {
        IMPORTED("imported"),
        VALIDATED("validated"),
        ARCHIVED("archived");

        private final String id;

        DraftStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum ValidationStatus {
        PASSED("passed"),
        FAILED("failed");

        private final String id;

        ValidationStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum VerificationStatus {
        PENDING("pending"),
        PASSED("passed"),
        FAILED("failed");

        private final String id;

        VerificationStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum DeploymentStatus {
        PENDING_ACTIVATION("pendingActivation"),
        ACTIVE("active"),
        SUPERSEDED("superseded"),
        ROLLED_BACK("rolledBack"),
        FAILED("failed");

        private final String id;

        DeploymentStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum RegistryStatus {
        OBSERVED("observed"),
        APPROVED("approved"),
        REJECTED("rejected");

        private final String id;

        RegistryStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: management record requires " + field);
        }
    }

    private static void requireStableId(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must be a stable id");
        }
    }

    private static String normalizeOptionalStableId(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireStableId(value, field);
        return value;
    }

    private static void requireCommandName(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!COMMAND_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must be a stable command name");
        }
    }

    private static String normalizeOptionalHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireHash(value, field);
        return value;
    }

    private static void requireHash(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!SHA256_IDENTITY.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must use sha256 identity");
        }
    }

    private static void requirePath(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must be a relative artifact path");
        }
    }

    private static void requireInstant(Instant value, String field) {
        Objects.requireNonNull(value, field);
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException("TITAN-GAP006-DOMAIN: " + field + " must be non-negative");
        }
    }

    private static Map<String, String> immutableSortedMetadata(Map<String, String> metadata, String field) {
        Objects.requireNonNull(metadata, field);
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            requireStableId(entry.getKey(), field + " key");
            requireText(entry.getValue(), field + " value");
            rejectTransportOwned(entry.getValue(), field + " value");
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(sorted);
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<T> comparator) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> copy = new ArrayList<>(values);
        for (T value : copy) {
            Objects.requireNonNull(value, "value");
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    // Exact-token matching (audit G-11 defect fix): substring matching rejected legitimate ids
    // such as 'curl-team' (contains 'url'). See TransportOwnedTerms.
    private static void rejectTransportOwned(String value, String field) {
        if (TransportOwnedTerms.containsTerm(TRANSPORT_OWNED_TERMS, value)) {
            throw new IllegalArgumentException(
                    "TITAN-GAP006-TRANSPORT-METADATA: " + field + " is transport-owned: " + value);
        }
    }

    private static void metadataField(JsonObject json, Map<String, String> metadata) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(metadata).entrySet()) {
            object.field(entry.getKey(), entry.getValue());
        }
        json.rawField("metadata", object.toJson());
    }

    private static String jsonArray(List<String> jsonValues) {
        return "[" + String.join(",", jsonValues) + "]";
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

        JsonObject rawField(String key, String jsonValue) {
            fields.add(quoted(key) + ":" + jsonValue);
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
