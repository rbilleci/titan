package io.titan.transpiler.tir;

import java.util.List;

/**
 * @param enumName the enum's source-local qualified name ({@code Order.Status}; plain
 *        {@code Status} for a top-level enum) — SQL object names derive from it via
 *        {@link SqlNames} (B-2 / TG-BLK-005)
 */
public record EnumLookupSpec(
        String enumName,
        List<EnumField> fields,
        List<EnumMethod> methods,
        List<EnumValue> values
) {
    public record EnumField(String name, String typeName) {}

    public record EnumMethod(String name, String returnTypeName) {}

    public record EnumValue(String key, List<String> fieldValues) {}
}
