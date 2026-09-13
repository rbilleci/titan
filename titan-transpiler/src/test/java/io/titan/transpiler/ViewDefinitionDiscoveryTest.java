package io.titan.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewDefinitionDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversSharedViewDefinitionsFromAnnotatedFields() throws Exception {
        Path sourceFile = tempDir.resolve("SharedViews.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ViewDefinition;

                class SharedViews {
                    @ViewDefinition(name = "high_value_accounts", shared = true)
                    public static final String HIGH_VALUE = "SELECT id FROM accounts";
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredViewDefinition> views = new ViewDefinitionDiscovery().discover(parsed);

        assertEquals(1, views.size());
        DiscoveredViewDefinition view = views.getFirst();
        assertEquals("SharedViews", view.className());
        assertEquals("HIGH_VALUE", view.fieldName());
        assertEquals("high_value_accounts", view.viewName());
        assertEquals("SELECT id FROM accounts", view.sqlBody());
    }

    @Test
    void rejectsDuplicateViewNamesInCodeDefinitions() throws Exception {
        Path sourceFile = tempDir.resolve("DuplicateViews.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ViewDefinition;

                class DuplicateViews {
                    @ViewDefinition(name = "conflict_view", shared = true)
                    static final Object ONE = null;

                    @ViewDefinition(name = "conflict_view", shared = true)
                    static final Object TWO = null;
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ViewDefinitionDiscovery().discover(parsed));
        assertTrue(error.getMessage().contains("duplicate view name"));
    }

    @Test
    void rejectsDuplicateViewNamesDifferingOnlyByCaseOrWhitespace() throws Exception {
        Path sourceFile = tempDir.resolve("DuplicateCaseViews.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ViewDefinition;

                class DuplicateCaseViews {
                    @ViewDefinition(name = " Reporting_View ", shared = true)
                    static final Object ONE = null;

                    @ViewDefinition(name = "reporting_view", shared = true)
                    static final Object TWO = null;
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ViewDefinitionDiscovery().discover(parsed));
        assertTrue(error.getMessage().contains("duplicate view name"));
    }
}
