package io.titan.transpiler;

import java.util.List;
import java.util.Map;

/**
 * Dependency graph between discovered entry points.
 *
 * @param dependencies maps caller -> direct callees
 * @param topologicalOrder entry points ordered so dependencies appear before dependents
 */
public record EntryPointDependencyGraph(
        Map<DiscoveredEntryPoint, List<DiscoveredEntryPoint>> dependencies,
        List<DiscoveredEntryPoint> topologicalOrder,
        List<String> cycles
) {
}
