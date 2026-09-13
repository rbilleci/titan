package io.titan.transpiler.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitanStacktraceCliTest {

    @Test
    void parsesSourceMarker() {
        TitanStacktraceCli.SourceMarker marker = TitanStacktraceCli.parseSourceMarker("-- titan:source:AccountService.java:42");
        assertEquals("AccountService.java", marker.file());
        assertEquals(42, marker.line());
    }

    @Test
    void ignoresInvalidMarker() {
        assertNull(TitanStacktraceCli.parseSourceMarker("-- titan:source:missing-line"));
        assertNull(TitanStacktraceCli.parseSourceMarker("SELECT 1"));
    }

    @Test
    void collectsMarkersUpToLine() {
        List<TitanStacktraceCli.SourceMarker> markers = TitanStacktraceCli.markersUpTo(List.of(
                "CREATE PROCEDURE app.p()",
                "-- titan:source:AccountService.java:12",
                "IF TRUE THEN",
                "-- titan:source:AccountService.java:18",
                "SET v_total = 1;",
                "-- titan:source:Other.java:7"
        ), 5);

        assertEquals(List.of(
                new TitanStacktraceCli.SourceMarker("AccountService.java", 12),
                new TitanStacktraceCli.SourceMarker("AccountService.java", 18)
        ), markers);
    }

    @Test
    void validatesArguments() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> TitanStacktraceCli.Arguments.parse(new String[] {"--sql-file", "file.sql"}));
        assertEquals("Usage: titan-stacktrace --sql-file <path> --line <line>", ex.getMessage());

        TitanStacktraceCli.Arguments args = TitanStacktraceCli.Arguments.parse(
                new String[] {"--sql-file", "build/out.sql", "--line", "42"});
        assertEquals(Path.of("build/out.sql"), args.sqlFile());
        assertEquals(42, args.line());
    }
}
