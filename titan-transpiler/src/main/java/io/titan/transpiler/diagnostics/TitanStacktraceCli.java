package io.titan.transpiler.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves generated SQL line numbers back to Java source markers emitted by Titan.
 */
public final class TitanStacktraceCli {
    private static final String SOURCE_PREFIX = "-- titan:source:";

    private TitanStacktraceCli() {}

    public static void main(String[] args) throws IOException {
        Arguments parsed = Arguments.parse(args);
        List<String> lines = Files.readAllLines(parsed.sqlFile());
        List<SourceMarker> markers = markersUpTo(lines, parsed.line());

        System.out.println("Titan Source Map:");
        if (markers.isEmpty()) {
            System.out.println("(no titan:source markers found up to SQL line " + parsed.line() + ")");
            return;
        }

        for (int i = markers.size() - 1; i >= 0; i--) {
            SourceMarker marker = markers.get(i);
            System.out.println("at " + marker.file() + ":" + marker.line());
        }
    }

    static List<SourceMarker> markersUpTo(List<String> sqlLines, int sqlLine) {
        int max = Math.min(sqlLine, sqlLines.size());
        List<SourceMarker> markers = new ArrayList<>();
        SourceMarker previous = null;
        for (int i = 0; i < max; i++) {
            SourceMarker marker = parseSourceMarker(sqlLines.get(i));
            if (marker == null) {
                continue;
            }
            if (!marker.equals(previous)) {
                markers.add(marker);
                previous = marker;
            }
        }
        return markers;
    }

    static SourceMarker parseSourceMarker(String line) {
        if (line == null || !line.startsWith(SOURCE_PREFIX)) {
            return null;
        }
        String payload = line.substring(SOURCE_PREFIX.length());
        int split = payload.lastIndexOf(':');
        if (split <= 0 || split == payload.length() - 1) {
            return null;
        }
        String file = payload.substring(0, split);
        try {
            int lineNo = Integer.parseInt(payload.substring(split + 1));
            if (lineNo <= 0) {
                return null;
            }
            return new SourceMarker(file, lineNo);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record SourceMarker(String file, int line) {}

    record Arguments(Path sqlFile, int line) {
        static Arguments parse(String[] args) {
            Path sqlFile = null;
            Integer line = null;
            for (int i = 0; i < args.length; i++) {
                if ("--sql-file".equals(args[i]) && i + 1 < args.length) {
                    sqlFile = Path.of(args[++i]);
                } else if ("--line".equals(args[i]) && i + 1 < args.length) {
                    line = Integer.parseInt(args[++i]);
                }
            }
            if (sqlFile == null || line == null || line <= 0) {
                throw new IllegalArgumentException("Usage: titan-stacktrace --sql-file <path> --line <line>");
            }
            return new Arguments(sqlFile, line);
        }
    }
}
