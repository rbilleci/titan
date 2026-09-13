package io.titan.transpiler.tir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

public record StructuredPayloadPageMetadataPlan(List<Field> fieldOrder) {
    private static final List<Field> DEFAULT_FIELD_ORDER = List.of(
            Field.HAS_NEXT,
            Field.HAS_PREVIOUS,
            Field.START_CURSOR,
            Field.END_CURSOR,
            Field.TOTAL_COUNT);

    public StructuredPayloadPageMetadataPlan {
        fieldOrder = normalizeFieldOrder(fieldOrder);
    }

    public static StructuredPayloadPageMetadataPlan defaultWindow() {
        return new StructuredPayloadPageMetadataPlan(DEFAULT_FIELD_ORDER);
    }

    public Map<String, Object> assemble(
            RowMaterializationRuntimePlan rows,
            List<? extends Map<String, ?>> rootRows,
            Map<String, ?> runtimeValues,
            OptionalInt totalCount
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(runtimeValues, "runtimeValues");
        Objects.requireNonNull(totalCount, "totalCount");
        RowMaterializationRuntimePlan.RootWindowFacts facts = rows.rootQuery().windowFacts()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload page metadata requires bounded list-root window facts."));
        if (rootRows.size() > facts.limit()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload page metadata received more root rows than the bounded window.");
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        for (Field field : fieldOrder) {
            metadata.put(field.key(), switch (field) {
                case HAS_NEXT -> hasNext(facts, rootRows, runtimeValues, totalCount);
                case HAS_PREVIOUS -> hasPrevious(facts, runtimeValues);
                case START_CURSOR -> cursorValue(facts, rootRows, true);
                case END_CURSOR -> cursorValue(facts, rootRows, false);
                case TOTAL_COUNT -> totalCount.orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload page metadata total count requires row-runtime count input."));
            });
        }
        return Collections.unmodifiableMap(metadata);
    }

    private static boolean hasNext(
            RowMaterializationRuntimePlan.RootWindowFacts facts,
            List<? extends Map<String, ?>> rootRows,
            Map<String, ?> runtimeValues,
            OptionalInt totalCount
    ) {
        if (totalCount.isPresent() && !hasPreviousCursorValue(facts, runtimeValues)) {
            return totalCount.getAsInt() > rootRows.size();
        }
        if (hasPreviousCursorValue(facts, runtimeValues) && rootRows.size() == facts.limit()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload page metadata hasNext for a full cursor page requires "
                            + "row-runtime overfetch or remaining-row metadata.");
        }
        return rootRows.size() == facts.limit();
    }

    private static boolean hasPrevious(
            RowMaterializationRuntimePlan.RootWindowFacts facts,
            Map<String, ?> runtimeValues
    ) {
        if (facts.cursorParameter().isEmpty()) {
            return false;
        }
        return hasPreviousCursorValue(facts, runtimeValues);
    }

    private static boolean hasPreviousCursorValue(
            RowMaterializationRuntimePlan.RootWindowFacts facts,
            Map<String, ?> runtimeValues
    ) {
        if (facts.cursorParameter().isEmpty()) {
            return false;
        }
        String parameter = facts.cursorParameter().get();
        if (!runtimeValues.containsKey(parameter)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload page metadata requires cursor runtime value '"
                            + parameter + "'.");
        }
        return runtimeValues.get(parameter) != null;
    }

    private static Object cursorValue(
            RowMaterializationRuntimePlan.RootWindowFacts facts,
            List<? extends Map<String, ?>> rootRows,
            boolean start
    ) {
        if (rootRows.isEmpty()) {
            return null;
        }
        String alias = facts.cursorAlias().orElseThrow(() -> new IllegalArgumentException(
                "TITAN-E001 structured payload page metadata cursor fields require a row-runtime cursor alias."));
        Map<String, ?> row = start ? rootRows.getFirst() : rootRows.getLast();
        if (!row.containsKey(alias)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload page metadata cursor alias '"
                            + alias + "' is missing from materialized root rows.");
        }
        return row.get(alias);
    }

    private static List<Field> normalizeFieldOrder(List<Field> fields) {
        List<Field> order = fields == null || fields.isEmpty() ? DEFAULT_FIELD_ORDER : List.copyOf(fields);
        LinkedHashSet<Field> unique = new LinkedHashSet<>(order);
        if (unique.size() != order.size()) {
            throw new IllegalArgumentException("TITAN-E001 structured payload page metadata fields must be unique.");
        }
        return List.copyOf(order);
    }

    public enum Field {
        HAS_NEXT("hasNext"),
        HAS_PREVIOUS("hasPrevious"),
        START_CURSOR("startCursor"),
        END_CURSOR("endCursor"),
        TOTAL_COUNT("totalCount");

        private final String key;

        Field(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }
}
