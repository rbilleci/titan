package io.titan.transpiler.jdbc;

import io.titan.transpiler.jdbc.SqlSafetyResolver.RelaxationSource;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The "effective permissive scopes" audit report (NEW WORK; {@code docs/design-document.md} §11.1,
 * {@code docs/transpilable-jdbc-subset.md} §5.3): every entry-point method whose <b>effective</b>
 * {@code sqlSafety} resolves to {@link SqlSafetyMode#PERMISSIVE}, <i>including methods that carry no
 * {@code @SqlSafety} annotation</i> because they are covered only by a class-level
 * {@code @SqlSafety(PERMISSIVE)} or the build-level {@code sqlSafety = permissive} flag. Those
 * class-/build-level cases are exactly the <b>non-greppable</b> relaxations the report exists to
 * surface — catching them is the whole value, so a permissive method the report misses is a security
 * blind spot.
 *
 * <p>The effective mode and its {@link RelaxationSource} both come from {@link SqlSafetyResolver}
 * (the single source of truth for narrowest-scope-wins precedence); this report never re-derives
 * precedence. It is rendered to deterministic JSON
 * ({@code build/reports/titan/permissive-scopes.json}) and a console {@code toText()}.</p>
 *
 * <p>Schema: {@code { schemaVersion, sqlSafety, totals{methods, effectivelyPermissive},
 * bySource{METHOD_ANNOTATION, CLASS_ANNOTATION, BUILD_FLAG}, byMethod[ {class, method, file, line,
 * effectiveMode, source} ] } }. {@code byMethod} lists only the effectively-permissive methods,
 * sorted by class/method/line for determinism; {@code bySource} histograms those same methods.</p>
 *
 * @param sqlSafety   the build-level mode the report was computed under
 * @param methodCount the total number of entry-point methods examined (the audit denominator)
 * @param permissive  the effectively-permissive entry points (the audit subject)
 */
public record PermissiveScopeReport(
        SqlSafetyMode sqlSafety,
        int methodCount,
        List<PermissiveScopeEntry> permissive
) {

    /** Bumped when the JSON shape changes. */
    public static final int SCHEMA_VERSION = 1;

    public PermissiveScopeReport {
        permissive = permissive == null ? List.of() : List.copyOf(permissive);
    }

    /** One effectively-permissive entry-point method and the source of its relaxation. */
    public record PermissiveScopeEntry(
            String className,
            String method,
            String file,
            long line,
            SqlSafetyMode effectiveMode,
            RelaxationSource source
    ) {
        public PermissiveScopeEntry {
            if (effectiveMode != SqlSafetyMode.PERMISSIVE) {
                // The report only ever lists permissive scopes; a non-permissive entry is a bug in
                // the engine (a false positive), so fail loudly rather than silently mislead an audit.
                throw new IllegalArgumentException(
                        "PermissiveScopeEntry must be PERMISSIVE, got " + effectiveMode
                                + " for " + className + "#" + method);
            }
            if (source == null) {
                throw new IllegalArgumentException("source must not be null");
            }
        }
    }

    /** The number of entry-point methods whose effective mode is permissive. */
    public int effectivelyPermissiveCount() {
        return permissive.size();
    }

    /** source -> count over the effectively-permissive methods, sorted by source name. */
    private Map<String, Long> bySource() {
        Map<String, Long> counts = new TreeMap<>();
        for (PermissiveScopeEntry entry : permissive) {
            counts.merge(entry.source().name(), 1L, Long::sum);
        }
        return counts;
    }

    private List<PermissiveScopeEntry> sorted() {
        return permissive.stream()
                .sorted((a, b) -> {
                    int byClass = a.className().compareTo(b.className());
                    if (byClass != 0) {
                        return byClass;
                    }
                    int byMethod = a.method().compareTo(b.method());
                    if (byMethod != 0) {
                        return byMethod;
                    }
                    return Long.compare(a.line(), b.line());
                })
                .toList();
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        out.append("  \"sqlSafety\": \"").append(sqlSafety.name().toLowerCase()).append("\",\n");

        out.append("  \"totals\": {\n");
        out.append("    \"methods\": ").append(methodCount).append(",\n");
        out.append("    \"effectivelyPermissive\": ").append(effectivelyPermissiveCount()).append("\n");
        out.append("  },\n");

        out.append("  \"bySource\": ").append(jsonStringLongMap(bySource(), "  ")).append(",\n");

        out.append("  \"byMethod\": [");
        List<PermissiveScopeEntry> sorted = sorted();
        if (sorted.isEmpty()) {
            out.append("]\n");
        } else {
            out.append("\n");
            for (int i = 0; i < sorted.size(); i++) {
                appendEntry(out, sorted.get(i));
                out.append(i < sorted.size() - 1 ? ",\n" : "\n");
            }
            out.append("  ]\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private void appendEntry(StringBuilder out, PermissiveScopeEntry entry) {
        out.append("    {\n");
        out.append("      \"class\": \"").append(escape(entry.className())).append("\",\n");
        out.append("      \"method\": \"").append(escape(entry.method())).append("\",\n");
        out.append("      \"file\": \"").append(escape(entry.file())).append("\",\n");
        out.append("      \"line\": ").append(entry.line()).append(",\n");
        out.append("      \"effectiveMode\": \"").append(entry.effectiveMode().name().toLowerCase()).append("\",\n");
        out.append("      \"source\": \"").append(entry.source().name()).append("\"\n");
        out.append("    }");
    }

    private static String jsonStringLongMap(Map<String, Long> map, String indent) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringBuilder out = new StringBuilder("{\n");
        List<Map.Entry<String, Long>> entries = List.copyOf(map.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            out.append(indent).append("  \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            out.append(i < entries.size() - 1 ? ",\n" : "\n");
        }
        out.append(indent).append("}");
        return out.toString();
    }

    /** Human-readable console rendering. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append("Titan effective permissive scopes report (sqlSafety=")
                .append(sqlSafety.name().toLowerCase()).append(")\n");
        out.append("  methods: ").append(methodCount)
                .append("  effectivelyPermissive=").append(effectivelyPermissiveCount())
                .append("\n");
        Map<String, Long> bySource = bySource();
        if (!bySource.isEmpty()) {
            out.append("  bySource:");
            for (Map.Entry<String, Long> entry : bySource.entrySet()) {
                out.append("  ").append(entry.getKey()).append('=').append(entry.getValue());
            }
            out.append("\n");
        }
        for (PermissiveScopeEntry entry : sorted()) {
            out.append("  PERMISSIVE  ")
                    .append(entry.className()).append('#').append(entry.method())
                    .append("  (").append(entry.file()).append(':').append(entry.line()).append(")")
                    .append("  source=").append(entry.source().name())
                    .append("\n");
        }
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
