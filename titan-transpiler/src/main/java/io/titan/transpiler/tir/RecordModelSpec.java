package io.titan.transpiler.tir;

import java.util.List;

/**
 * @param recordName the record's source-local qualified name ({@code Outer.SortPath}; plain
 *        {@code SortPath} for a top-level record) — SQL object names derive from it via
 *        {@link SqlNames} (B-2 / TG-BLK-005)
 */
public record RecordModelSpec(
        String recordName,
        List<RecordField> fields
) {
    public record RecordField(String name, String typeName) {}
}
