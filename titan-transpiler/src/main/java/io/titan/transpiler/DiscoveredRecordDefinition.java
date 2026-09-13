package io.titan.transpiler;

import java.util.List;

/**
 * @param qualifiedName source-local qualified name: the dotted enclosing-type chain including
 *        the record itself, without the package ({@code Outer.SortPath}; plain {@code SortPath}
 *        for a top-level record). Generated SQL object names and artifact identities derive from
 *        this — never from the bare simple name — so same-simple-name records in different
 *        enclosing types cannot collide silently (B-2 / TG-BLK-005).
 * @param recordName the record's simple name
 */
public record DiscoveredRecordDefinition(
        String qualifiedName,
        String recordName,
        List<RecordComponentDef> components,
        String sourceFile,
        int sourceLine
) {
    public record RecordComponentDef(String name, String typeName) {}
}
