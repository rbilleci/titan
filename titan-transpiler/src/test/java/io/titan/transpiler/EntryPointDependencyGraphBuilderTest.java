package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryPointDependencyGraphBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsEntryPointGraphAndTopologicalOrder() throws Exception {
        Path sourceFile = tempDir.resolve("DependencyDemo.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class DependencyDemo {
                    @StoredProcedure
                    public static void root() {
                        middle();
                    }

                    @StoredProcedure
                    public static void middle() {
                        leaf();
                    }

                    @StoredProcedure
                    public static void leaf() {
                    }

                    static void helper() {
                        leaf(); // non-entry-point caller should be ignored as a node
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        EntryPointDependencyGraph graph = new EntryPointDependencyGraphBuilder().build(parsed, entryPoints);

        DiscoveredEntryPoint root = find(entryPoints, "root");
        DiscoveredEntryPoint middle = find(entryPoints, "middle");
        DiscoveredEntryPoint leaf = find(entryPoints, "leaf");

        assertEquals(List.of(middle), graph.dependencies().get(root));
        assertEquals(List.of(leaf), graph.dependencies().get(middle));
        assertEquals(List.of(), graph.dependencies().get(leaf));

        assertEquals(List.of(leaf, middle, root), graph.topologicalOrder());
    }

    @Test
    void detectsCyclesAcrossEntryPoints() throws Exception {
        Path sourceFile = tempDir.resolve("CycleDemo.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredProcedure;

                class CycleDemo {
                    @StoredProcedure
                    public static void a() {
                        b();
                    }

                    @StoredProcedure
                    public static void b() {
                        a();
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        EntryPointDependencyGraph graph = new EntryPointDependencyGraphBuilder().build(parsed, entryPoints);

        assertFalse(graph.cycles().isEmpty());
        String cycle = graph.cycles().getFirst();
        assertTrue(cycle.contains("CycleDemo.a()"));
        assertTrue(cycle.contains("CycleDemo.b()"));
        assertTrue(cycle.contains("CycleDemo.java:"), cycle);
    }

    @Test
    void rendersOverloadedCyclePathsWithParameterTypesAndLocations() throws Exception {
        Path sourceFile = tempDir.resolve("OverloadedCycleDemo.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;

                class OverloadedCycleDemo {
                    @StoredFunction
                    public static int run(int value) {
                        return step(value);
                    }

                    static int step(int value) {
                        return step(Integer.toString(value));
                    }

                    static int step(String value) {
                        return step(value.length());
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> publicEntryPoints = new EntryPointDiscovery().discover(parsed);
        List<DiscoveredEntryPoint> entryPoints = new java.util.ArrayList<>(publicEntryPoints);
        entryPoints.addAll(new InternalHelperDiscovery().discover(parsed, publicEntryPoints));

        EntryPointDependencyGraph graph = new EntryPointDependencyGraphBuilder().build(parsed, entryPoints);

        String cycle = graph.cycles().getFirst();
        assertTrue(cycle.contains("OverloadedCycleDemo.step(int)"));
        assertTrue(cycle.contains("OverloadedCycleDemo.step(java.lang.String)"));
        assertTrue(cycle.contains("OverloadedCycleDemo.java:9"));
        assertTrue(cycle.contains("OverloadedCycleDemo.java:13"));
    }

    private static DiscoveredEntryPoint find(List<DiscoveredEntryPoint> entryPoints, String methodName) {
        return entryPoints.stream()
                .filter(entryPoint -> entryPoint.methodName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
