package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.InternalHelperDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Locks the compile-time boundary for source-local PreparedStatement binder helpers. */
class JdbcInlineBinderHelperTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = """
            import titan.dsl.StoredProcedure;
            import java.sql.*;

            class InlineBinder {
                @StoredProcedure
                public static void update(Connection connection, long id, String label) throws SQLException {
                    PreparedStatement statement = connection.prepareStatement(
                            "UPDATE accounts SET label = ? WHERE id = ?");
                    bind(statement, id, label);
                    statement.executeUpdate();
                }

                private static void bind(PreparedStatement statement, long id, String label)
                        throws SQLException {
                    long normalized = normalize(id);
                    statement.setString(1, label);
                    statement.setLong(2, normalized);
                }

                private static long normalize(long value) {
                    return value + 1L;
                }
            }
            """;

    private ParsedSources parse() throws Exception {
        Path sourceFile = tempDir.resolve("InlineBinder.java");
        Files.writeString(sourceFile, SOURCE);
        return new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
    }

    @Test
    void binderIsInlinedWhileItsScalarDependencyRemainsDiscoverable() throws Exception {
        ParsedSources parsed = parse();
        List<DiscoveredEntryPoint> publicEntries = new EntryPointDiscovery().discover(parsed);
        List<DiscoveredEntryPoint> helpers = new InternalHelperDiscovery().discover(parsed, publicEntries);

        assertFalse(helpers.stream().anyMatch(entry -> entry.methodName().equals("bind")),
                "a JDBC handle helper must not become an SQL routine");
        assertTrue(helpers.stream().anyMatch(entry -> entry.methodName().equals("normalize")),
                "ordinary scalar dependencies called by the inlined body must remain reachable");

        List<DiscoveredEntryPoint> allEntries = new ArrayList<>(publicEntries);
        allEntries.addAll(helpers);
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = new JavaToTirLowerer().lower(parsed, allEntries, sink);
        assertTrue(sink.errors().isEmpty(), "expected clean inline lowering; errors=" + sink.errors());

        Block update = lowered.entrySet().stream()
                .filter(entry -> entry.getKey().contains("#update("))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        ExecuteSqlStatement execute = update.statements().stream()
                .filter(ExecuteSqlStatement.class::isInstance)
                .map(ExecuteSqlStatement.class::cast)
                .findFirst()
                .orElseThrow();
        RawSql sql = (RawSql) execute.sqlNode();
        assertEquals(List.of("__titan_p1", "__titan_p2"), sql.parameters());
        assertTrue(update.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .anyMatch(declaration -> declaration.name().equals("__titan_inline_0_normalized")));
        assertTrue(update.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .anyMatch(declaration -> declaration.name().equals("__titan_p1")));
        assertTrue(update.declarations().stream()
                .filter(DeclareVariable.class::isInstance)
                .map(DeclareVariable.class::cast)
                .anyMatch(declaration -> declaration.name().equals("__titan_p2")));
    }
}
