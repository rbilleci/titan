package io.titan.gradle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record TitanArtifactManifest(
        String schemaVersion,
        String artifactId,
        String titanVersion,
        String packageMode,
        List<String> dialects,
        List<SourceInput> sourceInputs,
        List<EntryPoint> entryPoints,
        List<GeneratedObjectReference> generatedObjects,
        List<RollbackScript> rollbackScripts,
        ManifestHashes hashes,
        Validation validation
) {
    // Schema version stays "titan.artifact.v1": the {@code rollbackScripts} array (B-5,
    // TG-BLK-008) is a purely additive field. No existing field is renamed or removed and the
    // string identifier is unchanged, so a v1 reader that does not know the field ignores it
    // (titan-graphql's GAP-005 adapter requires exactly "titan.artifact.v1" and reads only the
    // fields it knows). The canonical content hash now covers the new field, which is correct:
    // tampering with the rollback ref must change the manifest hash.
    public static final String CURRENT_SCHEMA_VERSION = "titan.artifact.v1";
    public static final String MANIFEST_CONTENT_HASH_PLACEHOLDER =
            "0000000000000000000000000000000000000000000000000000000000000000";

    public TitanArtifactManifest {
        dialects = sortedDistinctStrings(dialects, "dialects");
        sourceInputs = sortedCopy(sourceInputs, Comparator.comparing(SourceInput::dialect)
                .thenComparing(SourceInput::path));
        entryPoints = sortedCopy(entryPoints, Comparator.comparing(EntryPoint::id));
        generatedObjects = sortedCopy(generatedObjects, Comparator.comparing(GeneratedObjectReference::id));
        rollbackScripts = sortedCopy(rollbackScripts, Comparator.comparing(RollbackScript::dialect));
        Objects.requireNonNull(hashes, "hashes");
        Objects.requireNonNull(validation, "validation");
        validateRequiredFields(
                schemaVersion,
                artifactId,
                titanVersion,
                packageMode,
                dialects,
                sourceInputs,
                hashes,
                validation);
    }

    public String toJson() {
        return toJson(hashes.manifestContentSha256());
    }

    public String canonicalContentHash() {
        return sha256Hex(toJson(MANIFEST_CONTENT_HASH_PLACEHOLDER).getBytes(StandardCharsets.UTF_8));
    }

    public TitanArtifactManifest withManifestContentSha256(String manifestContentSha256) {
        return new TitanArtifactManifest(
                schemaVersion,
                artifactId,
                titanVersion,
                packageMode,
                dialects,
                sourceInputs,
                entryPoints,
                generatedObjects,
                rollbackScripts,
                new ManifestHashes(hashes.sourceInputsSha256(), manifestContentSha256),
                validation);
    }

    private String toJson(String manifestContentSha256) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        field(builder, 1, "schemaVersion", schemaVersion, true);
        field(builder, 1, "artifactId", artifactId, true);
        field(builder, 1, "titanVersion", titanVersion, true);
        field(builder, 1, "packageMode", packageMode, true);
        stringArrayField(builder, 1, "dialects", dialects, true);
        sourceInputsField(builder, 1, true);
        entryPointsField(builder, 1, true);
        generatedObjectsField(builder, 1, true);
        rollbackScriptsField(builder, 1, true);
        hashesField(builder, 1, manifestContentSha256, true);
        validationField(builder, 1, false);
        builder.append("}\n");
        return builder.toString();
    }

    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public record SourceInput(String dialect, String path, String sha256) {
        public SourceInput {
            requireText(dialect, "source input dialect");
            requireText(path, "source input path");
            requireHexSha256(sha256, "source input sha256");
        }
    }

    public record EntryPoint(
            String id,
            JavaEntryPoint java,
            List<SqlEntryPoint> sql,
            String securityMode
    ) {
        public EntryPoint {
            requireText(id, "entry point id");
            Objects.requireNonNull(java, "java entry point");
            sql = sortedCopy(sql, Comparator.comparing(SqlEntryPoint::dialect)
                    .thenComparing(SqlEntryPoint::routineName)
                    .thenComparing(SqlEntryPoint::objectId));
            if (sql.isEmpty()) {
                throw new IllegalArgumentException("artifact manifest requires entry point SQL metadata");
            }
            requireText(securityMode, "entry point security mode");
        }
    }

    public record JavaEntryPoint(
            String className,
            String methodName,
            List<String> parameterTypes,
            String annotation,
            SourceLocation sourceLocation
    ) {
        public JavaEntryPoint {
            requireText(className, "Java entry point class name");
            requireText(methodName, "Java entry point method name");
            parameterTypes = sortedParameterTypes(parameterTypes);
            requireText(annotation, "Java entry point annotation");
            Objects.requireNonNull(sourceLocation, "Java entry point source location");
        }
    }

    public record SourceLocation(String path, long line) {
        public SourceLocation {
            requireText(path, "source location path");
            if (line < 0) {
                throw new IllegalArgumentException("artifact manifest requires non-negative source location line");
            }
        }
    }

    public record SqlEntryPoint(
            String dialect,
            String objectId,
            String routineName,
            String routineKind,
            List<SqlParameter> parameters,
            String returnType,
            String sourceInputPath
    ) {
        public SqlEntryPoint {
            requireText(dialect, "SQL entry point dialect");
            requireText(objectId, "SQL entry point object id");
            requireText(routineName, "SQL entry point routine name");
            requireText(routineKind, "SQL entry point routine kind");
            parameters = sortedCopy(parameters, Comparator.comparing(SqlParameter::ordinal));
            requireText(returnType, "SQL entry point return type");
            requireText(sourceInputPath, "SQL entry point source input path");
        }
    }

    public record SqlParameter(int ordinal, String name, String type) {
        public SqlParameter {
            if (ordinal < 0) {
                throw new IllegalArgumentException("artifact manifest requires non-negative SQL parameter ordinal");
            }
            requireText(name, "SQL parameter name");
            requireText(type, "SQL parameter type");
        }
    }

    public record GeneratedObjectReference(String id, String status) {
        public GeneratedObjectReference {
            requireText(id, "generated object id");
            requireText(status, "generated object status");
        }
    }

    /**
     * Integrity link to the executable rollback artifact emitted by {@code titanPackage}
     * (B-5, TG-BLK-008): one per dialect, referenced by reproducible relative {@code path}
     * (like the source-input entries), with the {@code sha256} of the script's raw bytes and
     * the number of {@code DROP} statements it contains. Consumers verify the script's integrity
     * against this entry instead of trusting the {@code titan-rollback.<dialect>.sql} filename
     * convention; {@code titanVerifyInstall} fails if the on-disk script's hash diverges.
     */
    public record RollbackScript(String dialect, String path, int statementCount, String sha256) {
        public RollbackScript {
            requireText(dialect, "rollback script dialect");
            requireText(path, "rollback script path");
            if (statementCount < 0) {
                throw new IllegalArgumentException("artifact manifest requires non-negative rollback statement count");
            }
            requireHexSha256(sha256, "rollback script sha256");
        }
    }

    public record ManifestHashes(String sourceInputsSha256, String manifestContentSha256) {
        public ManifestHashes {
            requireHexSha256(sourceInputsSha256, "source inputs sha256");
            requireHexSha256(manifestContentSha256, "manifest content sha256");
        }
    }

    public record Validation(String status, List<String> warnings) {
        public Validation {
            requireText(status, "validation status");
            warnings = sortedDistinctStrings(warnings, "validation warnings");
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void sourceInputsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"sourceInputs\": [");
        if (sourceInputs.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < sourceInputs.size(); index++) {
            SourceInput input = sourceInputs.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", input.dialect(), true);
            field(builder, depth + 2, "path", input.path(), true);
            field(builder, depth + 2, "sha256", input.sha256(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < sourceInputs.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void entryPointsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"entryPoints\": [");
        if (entryPoints.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < entryPoints.size(); index++) {
            EntryPoint entryPoint = entryPoints.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "id", entryPoint.id(), true);
            javaEntryPointField(builder, depth + 2, entryPoint.java(), true);
            sqlEntryPointsField(builder, depth + 2, entryPoint.sql(), true);
            field(builder, depth + 2, "securityMode", entryPoint.securityMode(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < entryPoints.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void javaEntryPointField(StringBuilder builder, int depth, JavaEntryPoint java, boolean comma) {
        indent(builder, depth).append("\"java\": {\n");
        field(builder, depth + 1, "className", java.className(), true);
        field(builder, depth + 1, "methodName", java.methodName(), true);
        stringArrayField(builder, depth + 1, "parameterTypes", java.parameterTypes(), true);
        field(builder, depth + 1, "annotation", java.annotation(), true);
        sourceLocationField(builder, depth + 1, java.sourceLocation(), false);
        indent(builder, depth).append("}");
        commaAndNewline(builder, comma);
    }

    private void sourceLocationField(StringBuilder builder, int depth, SourceLocation sourceLocation, boolean comma) {
        indent(builder, depth).append("\"sourceLocation\": {\n");
        field(builder, depth + 1, "path", sourceLocation.path(), true);
        longField(builder, depth + 1, "line", sourceLocation.line(), false);
        indent(builder, depth).append("}");
        commaAndNewline(builder, comma);
    }

    private void sqlEntryPointsField(StringBuilder builder, int depth, List<SqlEntryPoint> sqlEntries, boolean comma) {
        indent(builder, depth).append("\"sql\": [\n");
        for (int index = 0; index < sqlEntries.size(); index++) {
            SqlEntryPoint sql = sqlEntries.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", sql.dialect(), true);
            field(builder, depth + 2, "objectId", sql.objectId(), true);
            field(builder, depth + 2, "routineName", sql.routineName(), true);
            field(builder, depth + 2, "routineKind", sql.routineKind(), true);
            sqlParametersField(builder, depth + 2, sql.parameters(), true);
            field(builder, depth + 2, "returnType", sql.returnType(), true);
            field(builder, depth + 2, "sourceInputPath", sql.sourceInputPath(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < sqlEntries.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void sqlParametersField(StringBuilder builder, int depth, List<SqlParameter> parameters, boolean comma) {
        indent(builder, depth).append("\"parameters\": [");
        if (parameters.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < parameters.size(); index++) {
            SqlParameter parameter = parameters.get(index);
            indent(builder, depth + 1).append("{\n");
            longField(builder, depth + 2, "ordinal", parameter.ordinal(), true);
            field(builder, depth + 2, "name", parameter.name(), true);
            field(builder, depth + 2, "type", parameter.type(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < parameters.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void generatedObjectsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"generatedObjects\": [");
        if (generatedObjects.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < generatedObjects.size(); index++) {
            GeneratedObjectReference object = generatedObjects.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "id", object.id(), true);
            field(builder, depth + 2, "status", object.status(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < generatedObjects.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void rollbackScriptsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"rollbackScripts\": [");
        if (rollbackScripts.isEmpty()) {
            builder.append("]");
            commaAndNewline(builder, comma);
            return;
        }
        builder.append("\n");
        for (int index = 0; index < rollbackScripts.size(); index++) {
            RollbackScript script = rollbackScripts.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", script.dialect(), true);
            field(builder, depth + 2, "path", script.path(), true);
            longField(builder, depth + 2, "statementCount", script.statementCount(), true);
            field(builder, depth + 2, "sha256", script.sha256(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < rollbackScripts.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private void hashesField(StringBuilder builder, int depth, String manifestContentSha256, boolean comma) {
        indent(builder, depth).append("\"hashes\": {\n");
        field(builder, depth + 1, "sourceInputsSha256", hashes.sourceInputsSha256(), true);
        field(builder, depth + 1, "manifestContentSha256", manifestContentSha256, false);
        indent(builder, depth).append("}");
        commaAndNewline(builder, comma);
    }

    private void validationField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"validation\": {\n");
        field(builder, depth + 1, "status", validation.status(), true);
        stringArrayField(builder, depth + 1, "warnings", validation.warnings(), false);
        indent(builder, depth).append("}");
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

    private static void longField(StringBuilder builder, int depth, String name, long value, boolean comma) {
        indent(builder, depth).append("\"").append(escape(name)).append("\": ").append(value);
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

    private static List<String> sortedParameterTypes(List<String> values) {
        Objects.requireNonNull(values, "Java entry point parameter types");
        for (String value : values) {
            requireText(value, "Java entry point parameter type");
        }
        return List.copyOf(values);
    }

    private static void validateRequiredFields(
            String schemaVersion,
            String artifactId,
            String titanVersion,
            String packageMode,
            List<String> dialects,
            List<SourceInput> sourceInputs,
            ManifestHashes hashes,
            Validation validation
    ) {
        requireText(schemaVersion, "schema version");
        requireText(artifactId, "artifact id");
        requireText(titanVersion, "Titan version");
        requireText(packageMode, "package mode");
        if (dialects.isEmpty()) {
            throw new IllegalArgumentException("artifact manifest requires at least one dialect");
        }
        if (sourceInputs.isEmpty()) {
            throw new IllegalArgumentException("artifact manifest requires at least one source input");
        }
        if (!"generated".equals(validation.status())) {
            throw new IllegalArgumentException("artifact manifest validation status must be 'generated'");
        }
        if (hashes.sourceInputsSha256().equals(hashes.manifestContentSha256())) {
            throw new IllegalArgumentException("manifest content hash must be distinct from source input hash");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifact manifest requires " + fieldName);
        }
    }

    private static void requireHexSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact manifest requires 64-character lowercase hex " + fieldName);
        }
    }
}
