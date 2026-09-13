package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StructuredPayloadEnvelopePlan(
        StructuredOutputPlan.Value data,
        List<StructuredOutputPlan.ObjectValue> errors,
        Optional<StructuredOutputPlan.ObjectValue> extensions,
        List<Field> fieldOrder
) {
    private static final List<Field> DEFAULT_FIELD_ORDER = List.of(Field.DATA, Field.ERRORS, Field.EXTENSIONS);

    public StructuredPayloadEnvelopePlan {
        Objects.requireNonNull(data, "data");
        errors = List.copyOf(errors);
        for (StructuredOutputPlan.ObjectValue error : errors) {
            Objects.requireNonNull(error, "error");
        }
        Objects.requireNonNull(extensions, "extensions");
        fieldOrder = normalizeFieldOrder(fieldOrder);
        if (extensions.isPresent() && !fieldOrder.contains(Field.EXTENSIONS)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload envelope field order must include extensions"
                            + " when extensions are supplied.");
        }
    }

    public static StructuredPayloadEnvelopePlan success(StructuredOutputPlan.Value data) {
        return new StructuredPayloadEnvelopePlan(data, List.of(), Optional.empty(), DEFAULT_FIELD_ORDER);
    }

    public static StructuredPayloadEnvelopePlan failure(List<StructuredOutputPlan.ObjectValue> errors) {
        return new StructuredPayloadEnvelopePlan(
                new StructuredOutputPlan.JsonNullValue(),
                errors,
                Optional.empty(),
                DEFAULT_FIELD_ORDER);
    }

    public static StructuredPayloadEnvelopePlan failureFromErrors(List<StructuredPayloadErrorPlan> errors) {
        Objects.requireNonNull(errors, "errors");
        ArrayList<StructuredOutputPlan.ObjectValue> values = new ArrayList<>();
        for (StructuredPayloadErrorPlan error : errors) {
            values.add(Objects.requireNonNull(error, "error").toObjectValue());
        }
        return failure(values);
    }

    public StructuredOutputPlan toStructuredOutputPlan() {
        ArrayList<StructuredOutputPlan.Entry> entries = new ArrayList<>();
        for (Field field : fieldOrder) {
            switch (field) {
                case DATA -> entries.add(entry("data", data));
                case ERRORS -> entries.add(entry("errors", new StructuredOutputPlan.ArrayLiteralValue(errorValues())));
                case EXTENSIONS -> extensions.ifPresent(value -> entries.add(entry("extensions", value)));
            }
        }
        return new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(entries));
    }

    private static List<Field> normalizeFieldOrder(List<Field> fields) {
        List<Field> order = fields == null || fields.isEmpty() ? DEFAULT_FIELD_ORDER : List.copyOf(fields);
        LinkedHashSet<Field> unique = new LinkedHashSet<>(order);
        if (unique.size() != order.size()) {
            throw new IllegalArgumentException("TITAN-E001 structured payload envelope field order must be unique.");
        }
        if (!unique.contains(Field.DATA) || !unique.contains(Field.ERRORS)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload envelope field order must include data and errors.");
        }
        return List.copyOf(order);
    }

    private List<StructuredOutputPlan.Value> errorValues() {
        return new ArrayList<>(errors);
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
    }

    public enum Field {
        DATA,
        ERRORS,
        EXTENSIONS
    }
}
