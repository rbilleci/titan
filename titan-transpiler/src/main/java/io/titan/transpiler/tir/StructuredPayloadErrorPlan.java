package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StructuredPayloadErrorPlan(
        StructuredOutputPlan.Value code,
        StructuredOutputPlan.Value message,
        Optional<StructuredOutputPlan.Value> path,
        Optional<StructuredOutputPlan.ObjectValue> location,
        Optional<StructuredOutputPlan.ObjectValue> metadata,
        List<Field> fieldOrder
) {
    private static final List<Field> DEFAULT_FIELD_ORDER =
            List.of(Field.CODE, Field.MESSAGE, Field.PATH, Field.LOCATION, Field.METADATA);

    public StructuredPayloadErrorPlan {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(metadata, "metadata");
        fieldOrder = normalizeFieldOrder(fieldOrder);
        requireConfigured(Field.PATH, path.isPresent(), fieldOrder);
        requireConfigured(Field.LOCATION, location.isPresent(), fieldOrder);
        requireConfigured(Field.METADATA, metadata.isPresent(), fieldOrder);
    }

    public static StructuredPayloadErrorPlan validation(String code, String message, String path) {
        return new StructuredPayloadErrorPlan(
                new StructuredOutputPlan.TextLiteralValue(code),
                new StructuredOutputPlan.TextLiteralValue(message),
                Optional.of(new StructuredOutputPlan.TextLiteralValue(path)),
                Optional.empty(),
                Optional.empty(),
                DEFAULT_FIELD_ORDER);
    }

    public StructuredOutputPlan.ObjectValue toObjectValue() {
        ArrayList<StructuredOutputPlan.Entry> entries = new ArrayList<>();
        for (Field field : fieldOrder) {
            switch (field) {
                case CODE -> entries.add(entry("code", code));
                case MESSAGE -> entries.add(entry("message", message));
                case PATH -> path.ifPresent(value -> entries.add(entry("path", value)));
                case LOCATION -> location.ifPresent(value -> entries.add(entry("location", value)));
                case METADATA -> metadata.ifPresent(value -> entries.add(entry("metadata", value)));
            }
        }
        return new StructuredOutputPlan.ObjectValue(entries);
    }

    private static List<Field> normalizeFieldOrder(List<Field> fields) {
        List<Field> order = fields == null || fields.isEmpty() ? DEFAULT_FIELD_ORDER : List.copyOf(fields);
        LinkedHashSet<Field> unique = new LinkedHashSet<>(order);
        if (unique.size() != order.size()) {
            throw new IllegalArgumentException("TITAN-E001 structured payload error field order must be unique.");
        }
        if (!unique.contains(Field.CODE) || !unique.contains(Field.MESSAGE)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload error field order must include code and message.");
        }
        return List.copyOf(order);
    }

    private static void requireConfigured(Field field, boolean present, List<Field> fieldOrder) {
        if (present && !fieldOrder.contains(field)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload error field order must include "
                            + field.key() + " when " + field.key() + " is supplied.");
        }
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
    }

    public enum Field {
        CODE("code"),
        MESSAGE("message"),
        PATH("path"),
        LOCATION("location"),
        METADATA("metadata");

        private final String key;

        Field(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }
}
