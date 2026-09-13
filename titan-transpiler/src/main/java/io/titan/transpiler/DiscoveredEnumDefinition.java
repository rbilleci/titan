package io.titan.transpiler;

import java.util.List;

/**
 * @param qualifiedName source-local qualified name: the dotted enclosing-type chain including
 *        the enum itself, without the package ({@code Order.Status}; plain {@code Status} for a
 *        top-level enum). Generated SQL object names and artifact identities derive from this —
 *        never from the bare simple name (B-2 / TG-BLK-005).
 * @param enumName the enum's simple name
 */
public record DiscoveredEnumDefinition(
        String qualifiedName,
        String enumName,
        List<EnumField> fields,
        List<EnumMethod> methods,
        List<EnumConstant> constants,
        String sourceFile,
        int sourceLine
) {
    public record EnumField(String name, String typeName) {}

    public record EnumMethod(String name, String returnTypeName) {}

    public record EnumConstant(String name, List<String> constructorArguments) {}
}
