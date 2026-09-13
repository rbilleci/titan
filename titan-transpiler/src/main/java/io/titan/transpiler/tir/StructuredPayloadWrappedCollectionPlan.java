package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

public record StructuredPayloadWrappedCollectionPlan(
        StructuredOutputPlan.OutputKey collectionKey,
        StructuredOutputPlan.OutputKey cursorKey,
        StructuredOutputPlan.OutputKey itemKey,
        StructuredOutputPlan.OutputKey pageMetadataKey,
        List<Field> fieldOrder,
        List<ItemField> itemFieldOrder
) {
    private static final List<Field> DEFAULT_FIELD_ORDER = List.of(Field.ITEMS, Field.PAGE_METADATA);
    private static final List<ItemField> DEFAULT_ITEM_FIELD_ORDER = List.of(ItemField.CURSOR, ItemField.ITEM);

    public StructuredPayloadWrappedCollectionPlan {
        collectionKey = requireKey(collectionKey, "collection key");
        cursorKey = requireKey(cursorKey, "cursor key");
        itemKey = requireKey(itemKey, "item key");
        pageMetadataKey = requireKey(pageMetadataKey, "page metadata key");
        fieldOrder = normalizeFields(fieldOrder);
        itemFieldOrder = normalizeItemFields(itemFieldOrder);
    }

    public static StructuredPayloadWrappedCollectionPlan defaultItems() {
        return new StructuredPayloadWrappedCollectionPlan(
                StructuredOutputPlan.OutputKey.compilerKnown("items"),
                StructuredOutputPlan.OutputKey.compilerKnown("cursor"),
                StructuredOutputPlan.OutputKey.compilerKnown("item"),
                StructuredOutputPlan.OutputKey.compilerKnown("page"),
                DEFAULT_FIELD_ORDER,
                DEFAULT_ITEM_FIELD_ORDER);
    }

    public StructuredOutputPlan toStructuredOutputPlan(
            StructuredOutputPlan.ObjectValue itemPayload,
            StructuredOutputPlan.Value itemCursor,
            StructuredOutputPlan.ObjectValue pageMetadata
    ) {
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(itemCursor, "itemCursor");
        Objects.requireNonNull(pageMetadata, "pageMetadata");
        validateRootItemPayload(itemPayload);
        validateItemCursor(itemCursor);
        return new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(rootEntries(
                new StructuredOutputPlan.ArrayValue(itemWrapper(itemPayload, itemCursor)),
                pageMetadata)));
    }

    public Map<String, Object> assemble(
            RowMaterializationRuntimePlan rows,
            List<? extends Map<String, ?>> rootRows,
            StructuredOutputPlan.ObjectValue itemPayload,
            StructuredPayloadPageMetadataPlan pageMetadata,
            Map<String, ?> runtimeValues,
            OptionalInt totalCount
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(pageMetadata, "pageMetadata");
        Objects.requireNonNull(runtimeValues, "runtimeValues");
        Objects.requireNonNull(totalCount, "totalCount");
        validateRootItemPayload(itemPayload);
        if (!rows.relationQueries().isEmpty()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection relation-backed items require relation rows.");
        }
        String cursorAlias = rows.rootQuery().windowFacts()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection requires bounded list-root window facts."))
                .cursorAlias()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection item cursors require a row-runtime cursor alias."));

        ArrayList<Map<String, Object>> wrappers = new ArrayList<>();
        for (Map<String, ?> rootRow : rootRows) {
            if (!rootRow.containsKey(cursorAlias)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection cursor alias '"
                                + cursorAlias + "' is missing from materialized root rows.");
            }
            wrappers.add(assembleItemWrapper(rootRow, itemPayload, rootRow.get(cursorAlias)));
        }

        Map<String, Object> page = pageMetadata.assemble(rows, rootRows, runtimeValues, totalCount);
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        for (Field field : fieldOrder) {
            switch (field) {
                case ITEMS -> output.put(collectionKey.value(), List.copyOf(wrappers));
                case PAGE_METADATA -> output.put(pageMetadataKey.value(), page);
            }
        }
        return Collections.unmodifiableMap(output);
    }

    public Map<String, Object> assemble(
            RowMaterializationRuntimePlan rows,
            List<? extends Map<String, ?>> rootRows,
            List<? extends Map<String, ?>> relationRows,
            StructuredOutputPlan.ObjectValue itemPayload,
            StructuredPayloadPageMetadataPlan pageMetadata,
            Map<String, ?> runtimeValues,
            OptionalInt totalCount
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(relationRows, "relationRows");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(pageMetadata, "pageMetadata");
        Objects.requireNonNull(runtimeValues, "runtimeValues");
        Objects.requireNonNull(totalCount, "totalCount");
        if (rows.relationQueries().isEmpty()) {
            if (!relationRows.isEmpty()) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection received relation rows for a root-only item payload.");
            }
            return assemble(rows, rootRows, itemPayload, pageMetadata, runtimeValues, totalCount);
        }
        if (rows.relationQueries().size() > 1) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection relation-backed items support one relation level.");
        }
        String cursorAlias = rows.rootQuery().windowFacts()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection requires bounded list-root window facts."))
                .cursorAlias()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection item cursors require a row-runtime cursor alias."));
        List<Map<String, Object>> itemPayloads = StructuredOutputRuntimePlan.from(
                        new StructuredOutputPlan(itemPayload),
                        rows)
                .assembleBatch(rootRows, relationRows);

        ArrayList<Map<String, Object>> wrappers = new ArrayList<>();
        for (int index = 0; index < rootRows.size(); index++) {
            Map<String, ?> rootRow = rootRows.get(index);
            if (!rootRow.containsKey(cursorAlias)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection cursor alias '"
                                + cursorAlias + "' is missing from materialized root rows.");
            }
            wrappers.add(assembleItemWrapper(itemPayloads.get(index), rootRow.get(cursorAlias)));
        }

        Map<String, Object> page = pageMetadata.assemble(rows, rootRows, runtimeValues, totalCount);
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        for (Field field : fieldOrder) {
            switch (field) {
                case ITEMS -> output.put(collectionKey.value(), List.copyOf(wrappers));
                case PAGE_METADATA -> output.put(pageMetadataKey.value(), page);
            }
        }
        return Collections.unmodifiableMap(output);
    }

    private List<StructuredOutputPlan.Entry> rootEntries(
            StructuredOutputPlan.ArrayValue items,
            StructuredOutputPlan.ObjectValue pageMetadata
    ) {
        ArrayList<StructuredOutputPlan.Entry> entries = new ArrayList<>();
        for (Field field : fieldOrder) {
            switch (field) {
                case ITEMS -> entries.add(new StructuredOutputPlan.Entry(collectionKey, items));
                case PAGE_METADATA -> entries.add(new StructuredOutputPlan.Entry(pageMetadataKey, pageMetadata));
            }
        }
        return entries;
    }

    private StructuredOutputPlan.ObjectValue itemWrapper(
            StructuredOutputPlan.ObjectValue itemPayload,
            StructuredOutputPlan.Value itemCursor
    ) {
        ArrayList<StructuredOutputPlan.Entry> entries = new ArrayList<>();
        for (ItemField field : itemFieldOrder) {
            switch (field) {
                case CURSOR -> entries.add(new StructuredOutputPlan.Entry(cursorKey, itemCursor));
                case ITEM -> entries.add(new StructuredOutputPlan.Entry(itemKey, itemPayload));
            }
        }
        return new StructuredOutputPlan.ObjectValue(entries);
    }

    private Map<String, Object> assembleItemWrapper(
            Map<String, ?> rootRow,
            StructuredOutputPlan.ObjectValue itemPayload,
            Object cursor
    ) {
        LinkedHashMap<String, Object> wrapper = new LinkedHashMap<>();
        for (ItemField field : itemFieldOrder) {
            switch (field) {
                case CURSOR -> wrapper.put(cursorKey.value(), cursor);
                case ITEM -> wrapper.put(itemKey.value(), assembleObject(itemPayload, rootRow));
            }
        }
        return wrapper;
    }

    private Map<String, Object> assembleItemWrapper(
            Map<String, Object> itemPayload,
            Object cursor
    ) {
        LinkedHashMap<String, Object> wrapper = new LinkedHashMap<>();
        for (ItemField field : itemFieldOrder) {
            switch (field) {
                case CURSOR -> wrapper.put(cursorKey.value(), cursor);
                case ITEM -> wrapper.put(itemKey.value(), itemPayload);
            }
        }
        return wrapper;
    }

    private Map<String, Object> assembleObject(
            StructuredOutputPlan.ObjectValue object,
            Map<String, ?> row
    ) {
        LinkedHashMap<String, Object> assembled = new LinkedHashMap<>();
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            assembled.put(entry.key().value(), assembleValue(entry.value(), row));
        }
        return assembled;
    }

    private Object assembleValue(
            StructuredOutputPlan.Value value,
            Map<String, ?> row
    ) {
        if (value instanceof StructuredOutputPlan.ScalarValue scalar) {
            if (!row.containsKey(scalar.fieldName())) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured payload wrapped collection item field '"
                                + scalar.fieldName() + "' is missing from materialized root rows.");
            }
            return row.get(scalar.fieldName());
        }
        if (value instanceof StructuredOutputPlan.NullValue || value instanceof StructuredOutputPlan.JsonNullValue) {
            return null;
        }
        if (value instanceof StructuredOutputPlan.TextLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.IntegerLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.BooleanLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.ObjectValue object) {
            return assembleObject(object, row);
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured payload wrapped collection item payloads support object, scalar, literal,"
                        + " and null values only.");
    }

    private static List<Field> normalizeFields(List<Field> fields) {
        List<Field> order = fields == null || fields.isEmpty() ? DEFAULT_FIELD_ORDER : List.copyOf(fields);
        LinkedHashSet<Field> unique = new LinkedHashSet<>(order);
        if (unique.size() != order.size()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection fields must be unique.");
        }
        if (!unique.contains(Field.ITEMS) || !unique.contains(Field.PAGE_METADATA)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection fields must include items and page metadata.");
        }
        return List.copyOf(order);
    }

    private static List<ItemField> normalizeItemFields(List<ItemField> fields) {
        List<ItemField> order = fields == null || fields.isEmpty() ? DEFAULT_ITEM_FIELD_ORDER : List.copyOf(fields);
        LinkedHashSet<ItemField> unique = new LinkedHashSet<>(order);
        if (unique.size() != order.size()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection item fields must be unique.");
        }
        if (!unique.contains(ItemField.CURSOR) || !unique.contains(ItemField.ITEM)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection item fields must include cursor and item.");
        }
        return List.copyOf(order);
    }

    private static void validateRootItemPayload(StructuredOutputPlan.ObjectValue itemPayload) {
        validateRootItemValue(itemPayload);
    }

    private static void validateItemCursor(StructuredOutputPlan.Value itemCursor) {
        if (itemCursor instanceof StructuredOutputPlan.ScalarValue
                || itemCursor instanceof StructuredOutputPlan.NullValue
                || itemCursor instanceof StructuredOutputPlan.JsonNullValue
                || itemCursor instanceof StructuredOutputPlan.TextLiteralValue
                || itemCursor instanceof StructuredOutputPlan.IntegerLiteralValue
                || itemCursor instanceof StructuredOutputPlan.BooleanLiteralValue) {
            return;
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured payload wrapped collection item cursors must be scalar, literal, or null.");
    }

    private static void validateRootItemValue(StructuredOutputPlan.Value value) {
        if (value instanceof StructuredOutputPlan.ArrayValue || value instanceof StructuredOutputPlan.ArrayLiteralValue) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection item payloads cannot contain relation"
                            + " or nested collection arrays until row-to-payload binding supports them.");
        }
        if (value instanceof StructuredOutputPlan.ObjectValue object) {
            for (StructuredOutputPlan.Entry entry : object.entries()) {
                validateRootItemValue(entry.value());
            }
        }
    }

    private static StructuredOutputPlan.OutputKey requireKey(StructuredOutputPlan.OutputKey value, String field) {
        Objects.requireNonNull(value, field);
        if (value.origin() != StructuredOutputPlan.KeyOrigin.COMPILER_KNOWN) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload wrapped collection " + field + " must be compiler-known.");
        }
        return value;
    }

    public enum Field {
        ITEMS,
        PAGE_METADATA
    }

    public enum ItemField {
        CURSOR,
        ITEM
    }
}
