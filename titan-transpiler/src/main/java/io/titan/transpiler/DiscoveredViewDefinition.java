package io.titan.transpiler;

public record DiscoveredViewDefinition(
        String className,
        String fieldName,
        String viewName,
        boolean shared,
        String sqlBody,
        String sourceFile,
        int sourceLine
) {
}
