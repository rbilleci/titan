package io.titan.transpiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;

/**
 * Builds a call graph across discovered entry points, computes topological creation order,
 * and reports cyclic dependencies.
 */
public final class EntryPointDependencyGraphBuilder {

    public EntryPointDependencyGraph build(ParsedSources parsedSources, List<DiscoveredEntryPoint> entryPoints) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        if (entryPoints == null) {
            throw new IllegalArgumentException("entryPoints must not be null");
        }

        Map<String, DiscoveredEntryPoint> entryPointByKey = new LinkedHashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            entryPointByKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        Map<DiscoveredEntryPoint, Set<DiscoveredEntryPoint>> mutableGraph = new LinkedHashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            mutableGraph.put(entryPoint, new LinkedHashSet<>());
        }

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                private DiscoveredEntryPoint currentCaller;

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    DiscoveredEntryPoint previous = currentCaller;

                    Element element = parsedSources.trees().getElement(getCurrentPath());
                    if (element != null && element.getKind() == ElementKind.METHOD) {
                        ExecutableElement method = (ExecutableElement) element;
                        currentCaller = entryPointByKey.get(methodSignatureKey(method));
                    } else {
                        currentCaller = null;
                    }

                    try {
                        return super.visitMethod(node, unused);
                    } finally {
                        currentCaller = previous;
                    }
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    if (currentCaller != null) {
                        TreePath invocationPath = getCurrentPath();
                        Element called = parsedSources.trees().getElement(invocationPath);
                        if (called != null && called.getKind() == ElementKind.METHOD) {
                            ExecutableElement calledMethod = (ExecutableElement) called;
                            DiscoveredEntryPoint callee = entryPointByKey.get(methodSignatureKey(calledMethod));
                            if (callee != null) {
                                mutableGraph.get(currentCaller).add(callee);
                            }
                        }
                    }
                    return super.visitMethodInvocation(node, unused);
                }
            }.scan(unit, null);
        }

        Map<DiscoveredEntryPoint, List<DiscoveredEntryPoint>> graph = new LinkedHashMap<>();
        for (Map.Entry<DiscoveredEntryPoint, Set<DiscoveredEntryPoint>> entry : mutableGraph.entrySet()) {
            graph.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        TopologyResult topologyResult = topologicalSort(graph);
        return new EntryPointDependencyGraph(Map.copyOf(graph), topologyResult.order(), topologyResult.cycles());
    }

    private static TopologyResult topologicalSort(Map<DiscoveredEntryPoint, List<DiscoveredEntryPoint>> graph) {
        Map<DiscoveredEntryPoint, VisitState> states = new LinkedHashMap<>();
        for (DiscoveredEntryPoint node : graph.keySet()) {
            states.put(node, VisitState.UNVISITED);
        }

        List<DiscoveredEntryPoint> reverseOrder = new ArrayList<>();
        Deque<DiscoveredEntryPoint> path = new ArrayDeque<>();
        Set<String> cycles = new LinkedHashSet<>();

        for (DiscoveredEntryPoint node : graph.keySet()) {
            if (states.get(node) == VisitState.UNVISITED) {
                dfs(node, graph, states, reverseOrder, path, cycles);
            }
        }

        return new TopologyResult(List.copyOf(reverseOrder), List.copyOf(cycles));
    }

    private static void dfs(
            DiscoveredEntryPoint node,
            Map<DiscoveredEntryPoint, List<DiscoveredEntryPoint>> graph,
            Map<DiscoveredEntryPoint, VisitState> states,
            List<DiscoveredEntryPoint> reverseOrder,
            Deque<DiscoveredEntryPoint> path,
            Set<String> cycles
    ) {
        states.put(node, VisitState.VISITING);
        path.addLast(node);

        for (DiscoveredEntryPoint dependency : graph.getOrDefault(node, List.of())) {
            VisitState depState = states.get(dependency);
            if (depState == VisitState.VISITING) {
                cycles.add(renderCycle(path, dependency));
                continue;
            }
            if (depState == VisitState.UNVISITED) {
                dfs(dependency, graph, states, reverseOrder, path, cycles);
            }
        }

        path.removeLast();
        states.put(node, VisitState.VISITED);
        reverseOrder.add(node);
    }

    private static String renderCycle(Deque<DiscoveredEntryPoint> path, DiscoveredEntryPoint cycleStart) {
        List<DiscoveredEntryPoint> cycle = new ArrayList<>();
        boolean collecting = false;
        for (DiscoveredEntryPoint node : path) {
            if (node.equals(cycleStart)) {
                collecting = true;
            }
            if (collecting) {
                cycle.add(node);
            }
        }
        cycle.add(cycleStart);

        return cycle.stream()
                .map(EntryPointDependencyGraphBuilder::format)
                .reduce((left, right) -> left + " -> " + right)
                .orElse("<unknown>");
    }

    private static String methodSignatureKey(ExecutableElement method) {
        return MethodSignatureKeys.of(method);
    }

    private static String format(DiscoveredEntryPoint entryPoint) {
        String params = String.join(",", entryPoint.parameterTypes());
        return entryPoint.className() + "." + entryPoint.methodName() + "(" + params + ")"
                + " [" + entryPoint.sourceFile() + ":" + entryPoint.line() + "]";
    }

    private record TopologyResult(List<DiscoveredEntryPoint> order, List<String> cycles) {
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }
}
