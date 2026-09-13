package io.titan.gradle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record TitanInstallVerification(
        String schemaVersion,
        String artifactId,
        String manifestContentSha256,
        String installPlanContentSha256,
        String status,
        Database database,
        List<DialectReport> dialectReports,
        List<Drift> drift,
        List<Diagnostic> diagnostics
) {
    public static final String CURRENT_SCHEMA_VERSION = "titan.install-verification.v1";

    public TitanInstallVerification {
        requireText(schemaVersion, "schema version");
        requireText(artifactId, "artifact id");
        requireHexSha256(manifestContentSha256, "manifest content sha256");
        requireHexSha256(installPlanContentSha256, "install plan content sha256");
        requireText(status, "status");
        Objects.requireNonNull(database, "database");
        dialectReports = sortedCopy(dialectReports, Comparator.comparing(DialectReport::dialect));
        drift = sortedCopy(drift, Comparator.comparing(Drift::dialect)
                .thenComparing(Drift::objectId)
                .thenComparing(Drift::check)
                .thenComparing(Drift::expectedHash)
                .thenComparing(Drift::actualHash));
        diagnostics = sortedCopy(diagnostics, Comparator.comparing(Diagnostic::dialect)
                .thenComparing(Diagnostic::stepId)
                .thenComparing(Diagnostic::objectId)
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message));
    }

    public static TitanInstallVerification planned(
            TitanArtifactManifest manifest,
            TitanInstallPlan installPlan
    ) {
        return new TitanInstallVerification(
                CURRENT_SCHEMA_VERSION,
                manifest.artifactId(),
                manifest.hashes().manifestContentSha256(),
                installPlan.hashes().planContentSha256(),
                "pending",
                new Database("scratch-required", "not-run"),
                manifest.dialects().stream()
                        .map(dialect -> new DialectReport(dialect, "pending", List.of()))
                        .toList(),
                List.of(),
                List.of(new Diagnostic(
                        "all",
                        "verification.pending",
                        "",
                        "TITAN-GAP005-VERIFY-PENDING",
                        "Install verification has not been executed for this package; run titanVerifyInstall.")));
    }

    public String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        field(builder, 1, "schemaVersion", schemaVersion, true);
        field(builder, 1, "artifactId", artifactId, true);
        field(builder, 1, "manifestContentSha256", manifestContentSha256, true);
        field(builder, 1, "installPlanContentSha256", installPlanContentSha256, true);
        field(builder, 1, "status", status, true);
        databaseField(builder, 1, true);
        dialectReportsField(builder, 1, true);
        driftField(builder, 1, true);
        diagnosticsField(builder, 1, diagnostics, false);
        builder.append("}\n");
        return builder.toString();
    }

    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public record Database(String kind, String version) {
        public Database {
            requireText(kind, "database kind");
            requireText(version, "database version");
        }
    }

    public record DialectReport(String dialect, String status, List<VerifiedObject> verifiedObjects) {
        public DialectReport {
            requireText(dialect, "dialect");
            requireText(status, "dialect status");
            verifiedObjects = sortedCopy(verifiedObjects, Comparator.comparing(VerifiedObject::objectId));
        }
    }

    public record VerifiedObject(String objectId, List<String> checks) {
        public VerifiedObject {
            requireText(objectId, "verified object id");
            checks = sortedDistinctStrings(checks, "verified object checks");
        }
    }

    public record Drift(String dialect, String objectId, String check, String expectedHash, String actualHash) {
        public Drift {
            requireText(dialect, "drift dialect");
            objectId = objectId == null ? "" : objectId;
            requireText(check, "drift check");
            requireHexSha256(expectedHash, "drift expected hash");
            requireHexSha256(actualHash, "drift actual hash");
        }
    }

    public record Diagnostic(String dialect, String stepId, String objectId, String code, String message) {
        public Diagnostic {
            requireText(dialect, "diagnostic dialect");
            requireText(stepId, "diagnostic step id");
            objectId = objectId == null ? "" : objectId;
            requireText(code, "diagnostic code");
            requireText(message, "diagnostic message");
        }
    }

    private void driftField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"drift\": [");
        if (drift.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < drift.size(); index++) {
            Drift item = drift.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", item.dialect(), true);
            field(builder, depth + 2, "objectId", item.objectId(), true);
            field(builder, depth + 2, "check", item.check(), true);
            field(builder, depth + 2, "expectedHash", item.expectedHash(), true);
            field(builder, depth + 2, "actualHash", item.actualHash(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < drift.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void databaseField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"database\": {\n");
        field(builder, depth + 1, "kind", database.kind(), true);
        field(builder, depth + 1, "version", database.version(), false);
        indent(builder, depth).append("}");
        commaAndNewline(builder, comma);
    }

    private void dialectReportsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"dialectReports\": [");
        if (dialectReports.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < dialectReports.size(); index++) {
            DialectReport report = dialectReports.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", report.dialect(), true);
            field(builder, depth + 2, "status", report.status(), true);
            verifiedObjectsField(builder, depth + 2, report.verifiedObjects(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < dialectReports.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private static void verifiedObjectsField(
            StringBuilder builder,
            int depth,
            List<VerifiedObject> verifiedObjects,
            boolean comma
    ) {
        indent(builder, depth).append("\"verifiedObjects\": [");
        if (verifiedObjects.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < verifiedObjects.size(); index++) {
            VerifiedObject verifiedObject = verifiedObjects.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "objectId", verifiedObject.objectId(), true);
            stringArrayField(builder, depth + 2, "checks", verifiedObject.checks(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < verifiedObjects.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private static void diagnosticsField(
            StringBuilder builder,
            int depth,
            List<Diagnostic> diagnostics,
            boolean comma
    ) {
        indent(builder, depth).append("\"diagnostics\": [");
        if (diagnostics.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < diagnostics.size(); index++) {
            Diagnostic diagnostic = diagnostics.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", diagnostic.dialect(), true);
            field(builder, depth + 2, "stepId", diagnostic.stepId(), true);
            field(builder, depth + 2, "objectId", diagnostic.objectId(), true);
            field(builder, depth + 2, "code", diagnostic.code(), true);
            field(builder, depth + 2, "message", diagnostic.message(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < diagnostics.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private static void field(StringBuilder builder, int depth, String name, String value, boolean comma) {
        indent(builder, depth)
                .append("\"")
                .append(escape(name))
                .append("\": \"")
                .append(escape(value))
                .append("\"");
        commaAndNewline(builder, comma);
    }

    private static void stringArrayField(
            StringBuilder builder,
            int depth,
            String name,
            List<String> values,
            boolean comma
    ) {
        indent(builder, depth).append("\"").append(escape(name)).append("\": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(escape(values.get(index))).append("\"");
        }
        builder.append("]");
        commaAndNewline(builder, comma);
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static StringBuilder indent(StringBuilder builder, int depth) {
        return builder.append("  ".repeat(depth));
    }

    private static void commaAndNewline(StringBuilder builder, boolean comma) {
        if (comma) {
            builder.append(",");
        }
        builder.append("\n");
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<T> comparator) {
        Objects.requireNonNull(values, "values");
        return values.stream().sorted(comparator).toList();
    }

    private static List<String> sortedDistinctStrings(List<String> values, String fieldName) {
        Objects.requireNonNull(values, fieldName);
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            requireText(value, fieldName);
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        normalized.sort(Comparator.naturalOrder());
        return List.copyOf(normalized);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("install verification requires " + fieldName);
        }
    }

    private static void requireHexSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("install verification requires 64-character lowercase hex " + fieldName);
        }
    }
}
