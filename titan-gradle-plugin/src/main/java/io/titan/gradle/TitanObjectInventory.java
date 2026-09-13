package io.titan.gradle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TitanObjectInventory(
        String schemaVersion,
        String artifactId,
        List<GeneratedObject> objects,
        InventoryHashes hashes
) {
    public static final String CURRENT_SCHEMA_VERSION = "titan.object-inventory.v1";
    public static final String INVENTORY_CONTENT_HASH_PLACEHOLDER =
            "0000000000000000000000000000000000000000000000000000000000000000";

    public TitanObjectInventory {
        requireText(schemaVersion, "schema version");
        requireText(artifactId, "artifact id");
        objects = sortedCopy(objects, Comparator.comparingInt(GeneratedObject::createOrder)
                .thenComparing(GeneratedObject::id));
        requireUniqueObjectIds(objects);
        Objects.requireNonNull(hashes, "hashes");
    }

    public String toJson() {
        return toJson(hashes.inventoryContentSha256());
    }

    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public String canonicalContentHash() {
        return TitanArtifactManifest.sha256Hex(
                toJson(INVENTORY_CONTENT_HASH_PLACEHOLDER).getBytes(StandardCharsets.UTF_8));
    }

    public TitanObjectInventory withInventoryContentSha256(String inventoryContentSha256) {
        return new TitanObjectInventory(
                schemaVersion,
                artifactId,
                objects,
                new InventoryHashes(inventoryContentSha256));
    }

    private String toJson(String inventoryContentSha256) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        field(builder, 1, "schemaVersion", schemaVersion, true);
        field(builder, 1, "artifactId", artifactId, true);
        objectsField(builder, 1, true);
        indent(builder, 1).append("\"hashes\": {\n");
        field(builder, 2, "inventoryContentSha256", inventoryContentSha256, false);
        indent(builder, 1).append("}\n");
        builder.append("}\n");
        return builder.toString();
    }

    public record GeneratedObject(
            String id,
            String dialect,
            String kind,
            String schema,
            String name,
            String signature,
            String sourceInputPath,
            String sourceEntryPoint,
            String securityMode,
            int createOrder,
            List<String> dependsOn,
            String sqlHash
    ) {
        public GeneratedObject {
            requireText(id, "generated object id");
            requireText(dialect, "generated object dialect");
            requireText(kind, "generated object kind");
            requireText(schema, "generated object schema");
            requireText(name, "generated object name");
            requireText(signature, "generated object signature");
            requireText(sourceInputPath, "generated object source input path");
            requireText(sourceEntryPoint, "generated object source entry point");
            requireText(securityMode, "generated object security mode");
            if (createOrder < 0) {
                throw new IllegalArgumentException("object inventory requires non-negative create order");
            }
            dependsOn = sortedDistinctStrings(dependsOn, "generated object dependencies");
            requireHexSha256(sqlHash, "generated object SQL hash");
        }
    }

    public record InventoryHashes(String inventoryContentSha256) {
        public InventoryHashes {
            requireHexSha256(inventoryContentSha256, "inventory content sha256");
        }
    }

    private void objectsField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"objects\": [\n");
        for (int index = 0; index < objects.size(); index++) {
            GeneratedObject object = objects.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "id", object.id(), true);
            field(builder, depth + 2, "dialect", object.dialect(), true);
            field(builder, depth + 2, "kind", object.kind(), true);
            field(builder, depth + 2, "schema", object.schema(), true);
            field(builder, depth + 2, "name", object.name(), true);
            field(builder, depth + 2, "signature", object.signature(), true);
            field(builder, depth + 2, "sourceInputPath", object.sourceInputPath(), true);
            field(builder, depth + 2, "sourceEntryPoint", object.sourceEntryPoint(), true);
            field(builder, depth + 2, "securityMode", object.securityMode(), true);
            intField(builder, depth + 2, "createOrder", object.createOrder(), true);
            stringArrayField(builder, depth + 2, "dependsOn", object.dependsOn(), true);
            field(builder, depth + 2, "sqlHash", object.sqlHash(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < objects.size() - 1);
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

    private static void intField(StringBuilder builder, int depth, String name, int value, boolean comma) {
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

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("object inventory requires " + fieldName);
        }
    }

    private static void requireUniqueObjectIds(List<GeneratedObject> objects) {
        Set<String> ids = new HashSet<>();
        for (GeneratedObject object : objects) {
            if (!ids.add(object.id())) {
                throw new IllegalArgumentException("object inventory requires unique generated object ids: " + object.id());
            }
        }
    }

    private static void requireHexSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("object inventory requires 64-character lowercase hex " + fieldName);
        }
    }
}
