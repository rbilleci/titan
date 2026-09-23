package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StructuredOutputPlan(ObjectValue root) {
    public StructuredOutputPlan {
        Objects.requireNonNull(root, "root");
    }

    public static StructuredOutputPlan fromRowMaterialization(RowMaterializationPlan rows) {
        Objects.requireNonNull(rows, "rows");
        Map<String, ArrayList<RowMaterializationPlan.FieldBinding>> fieldsByAlias = new LinkedHashMap<>();
        fieldsByAlias.put(rows.root().alias(), new ArrayList<>(rows.rootFields()));
        Map<String, ArrayList<RowMaterializationPlan.RelationRows>> relationsByParentAlias = new LinkedHashMap<>();
        for (RowMaterializationPlan.RelationRows relation : rows.relations()) {
            fieldsByAlias.computeIfAbsent(relation.childTable().alias(), ignored -> new ArrayList<>())
                    .addAll(relation.fields());
            relationsByParentAlias.computeIfAbsent(relation.parentKey().column().tableAlias(), ignored -> new ArrayList<>())
                    .add(relation);
        }
        return new StructuredOutputPlan(relationObject(
                rows.root().alias(),
                fieldsByAlias,
                relationsByParentAlias,
                new LinkedHashSet<>()));
    }

    private static ObjectValue relationObject(
            String tableAlias,
            Map<String, ArrayList<RowMaterializationPlan.FieldBinding>> fieldsByAlias,
            Map<String, ArrayList<RowMaterializationPlan.RelationRows>> relationsByParentAlias,
            LinkedHashSet<String> path
    ) {
        if (!path.add(tableAlias)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output relation graph cycle detected at alias '" + tableAlias + "'.");
        }
        ArrayList<Entry> entries = new ArrayList<>();
        for (RowMaterializationPlan.FieldBinding field : fieldsByAlias.getOrDefault(tableAlias, new ArrayList<>())) {
            entries.add(new Entry(OutputKey.compilerKnown(field.fieldName()), ScalarValue.from(field)));
        }
        for (RowMaterializationPlan.RelationRows relation : relationsByParentAlias.getOrDefault(tableAlias, new ArrayList<>())) {
            ObjectValue child = relationObject(
                    relation.childTable().alias(),
                    fieldsByAlias,
                    relationsByParentAlias,
                    path);
            Value value = switch (relation.cardinality()) {
                case MANY -> new ArrayValue(child);
                case ZERO_OR_ONE, ONE -> child;
            };
            entries.add(new Entry(OutputKey.compilerKnown(relation.name()), value));
        }
        path.remove(tableAlias);
        return new ObjectValue(entries);
    }

    public sealed interface Value permits ObjectValue, ArrayValue, ArrayLiteralValue, ScalarValue, NullValue,
            JsonNullValue, TextLiteralValue, IntegerLiteralValue, BooleanLiteralValue {
    }

    public record ObjectValue(List<Entry> entries) implements Value {
        public ObjectValue {
            entries = List.copyOf(entries);
            LinkedHashSet<OutputKey> keys = new LinkedHashSet<>();
            for (Entry entry : entries) {
                if (!keys.add(entry.key())) {
                    throw new IllegalArgumentException("TITAN-E001 structured output object key '"
                            + entry.key().value() + "' must be unique within an object");
                }
                validateNoDirectArrayNesting(entry.value());
            }
        }
    }

    public record Entry(OutputKey key, Value value) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    public record ArrayValue(Value element) implements Value {
        public ArrayValue {
            Objects.requireNonNull(element, "element");
            if (isArrayValue(element)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output arrays cannot directly contain nested arrays yet");
            }
        }
    }

    public record ArrayLiteralValue(List<Value> elements) implements Value {
        public ArrayLiteralValue {
            elements = List.copyOf(elements);
            for (Value element : elements) {
                Objects.requireNonNull(element, "element");
                if (isArrayValue(element)) {
                    throw new IllegalArgumentException(
                            "TITAN-E001 structured output arrays cannot directly contain nested arrays yet");
                }
            }
        }
    }

    public record ScalarValue(String fieldName, QueryTemplatePlan.ColumnRef column, TirType type) implements Value {
        public ScalarValue {
            fieldName = requireName(fieldName, "field name");
            Objects.requireNonNull(column, "column");
            type = requireSupportedScalar(type);
        }

        public static ScalarValue from(RowMaterializationPlan.FieldBinding field) {
            Objects.requireNonNull(field, "field");
            return new ScalarValue(field.fieldName(), field.column(), field.type());
        }
    }

    public record NullValue(TirType type) implements Value {
        public NullValue {
            type = requireSupportedScalar(type);
        }
    }

    public record JsonNullValue() implements Value {
    }

    public record TextLiteralValue(String value) implements Value {
        public TextLiteralValue {
            Objects.requireNonNull(value, "value");
        }
    }

    public record IntegerLiteralValue(int value) implements Value {
    }

    public record BooleanLiteralValue(boolean value) implements Value {
    }

    public static final class OutputKey {
        private final String value;
        private final KeyOrigin origin;

        private OutputKey(String value, KeyOrigin origin) {
            this.value = requireName(value, "key");
            this.origin = Objects.requireNonNull(origin, "origin");
        }

        public static OutputKey compilerKnown(String value) {
            return new OutputKey(value, KeyOrigin.COMPILER_KNOWN);
        }

        public static OutputKey runtimeValue(String value) {
            requireName(value, "key");
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output key must be compiler-known, but was runtime value");
        }

        public String value() {
            return value;
        }

        public KeyOrigin origin() {
            return origin;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OutputKey that)) {
                return false;
            }
            return value.equals(that.value) && origin == that.origin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, origin);
        }
    }

    public enum KeyOrigin {
        COMPILER_KNOWN,
        RUNTIME_VALUE
    }

    private static void validateNoDirectArrayNesting(Value value) {
        if (value instanceof ObjectValue object) {
            for (Entry entry : object.entries()) {
                validateNoDirectArrayNesting(entry.value());
            }
        } else if (value instanceof ArrayValue array && isArrayValue(array.element())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output arrays cannot directly contain nested arrays yet");
        } else if (value instanceof ArrayLiteralValue array) {
            for (Value element : array.elements()) {
                validateNoDirectArrayNesting(element);
            }
        }
    }

    private static boolean isArrayValue(Value value) {
        return value instanceof ArrayValue || value instanceof ArrayLiteralValue;
    }

    private static TirType requireSupportedScalar(TirType type) {
        Objects.requireNonNull(type, "type");
        if (type instanceof TIntType
                || type instanceof TBigintType
                || type instanceof TTextType
                || type instanceof TBooleanType
                || type instanceof TNumericType
                || type instanceof TDoubleType
                || type instanceof TDateType
                || type instanceof TTimeType
                || type instanceof TTimestampType
                || type instanceof TTimestampTzType
                || type instanceof TUuidType) {
            return type;
        }
        throw new IllegalArgumentException("TITAN-E001 structured output scalar value type '"
                + type.getClass().getSimpleName() + "' is not supported yet");
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Structured output " + field + " must not be blank");
        }
        return value;
    }
}
