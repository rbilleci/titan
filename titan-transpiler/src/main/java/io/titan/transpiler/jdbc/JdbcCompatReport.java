package io.titan.transpiler.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The aggregate JDBC compatibility result: the per-method reports plus rolled-up totals, rendered
 * to deterministic JSON ({@code build/reports/titan/jdbc-compat-report.json}) and a console
 * {@code toText()}. The JSON shape is pinned by {@code JdbcCompatReportGoldenTest}.
 *
 * <p>Schema: {@code { schemaVersion, sqlSafety, totals{methods,transpilable,passthrough,rejected,
 * usageTranspilable,usagePassthrough,usageRejected}, byIdiom{}, byCode{}, methods[ {class,method,
 * file,line,kind,rollup,usages[{idiom,classification,location,code,reason}]} ] } }.</p>
 */
public record JdbcCompatReport(SqlSafetyMode sqlSafety, List<MethodJdbcReport> methods) {

    /** Bumped when the JSON shape changes (golden tests pin it). */
    public static final int SCHEMA_VERSION = 1;

    public JdbcCompatReport {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public int methodCount() {
        return methods.size();
    }

    public long methodsWith(JdbcClassification rollup) {
        return methods.stream().filter(m -> m.rollup() == rollup).count();
    }

    public boolean hasRejected() {
        return methods.stream().anyMatch(m -> m.rollup() == JdbcClassification.REJECTED);
    }

    private long usagesWith(JdbcClassification classification) {
        return methods.stream()
                .flatMap(m -> m.usages().stream())
                .filter(u -> u.classification() == classification)
                .count();
    }

    /** idiom name -> usage count, sorted by name for determinism. */
    private Map<String, Long> byIdiom() {
        Map<String, Long> counts = new TreeMap<>();
        for (MethodJdbcReport method : methods) {
            for (JdbcUsage usage : method.usages()) {
                counts.merge(usage.idiom().name(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** diagnostic code -> usage count (usages with a code), sorted by code for determinism. */
    private Map<String, Long> byCode() {
        Map<String, Long> counts = new TreeMap<>();
        for (MethodJdbcReport method : methods) {
            for (JdbcUsage usage : method.usages()) {
                if (usage.code() != null) {
                    counts.merge(usage.code().code(), 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        out.append("  \"sqlSafety\": \"").append(sqlSafety.name().toLowerCase()).append("\",\n");

        out.append("  \"totals\": {\n");
        out.append("    \"methods\": ").append(methodCount()).append(",\n");
        out.append("    \"transpilable\": ").append(methodsWith(JdbcClassification.TRANSPILABLE)).append(",\n");
        out.append("    \"passthrough\": ").append(methodsWith(JdbcClassification.PASSTHROUGH)).append(",\n");
        out.append("    \"rejected\": ").append(methodsWith(JdbcClassification.REJECTED)).append(",\n");
        out.append("    \"usageTranspilable\": ").append(usagesWith(JdbcClassification.TRANSPILABLE)).append(",\n");
        out.append("    \"usagePassthrough\": ").append(usagesWith(JdbcClassification.PASSTHROUGH)).append(",\n");
        out.append("    \"usageRejected\": ").append(usagesWith(JdbcClassification.REJECTED)).append("\n");
        out.append("  },\n");

        out.append("  \"byIdiom\": ").append(jsonStringLongMap(byIdiom(), "  ")).append(",\n");
        out.append("  \"byCode\": ").append(jsonStringLongMap(byCode(), "  ")).append(",\n");

        out.append("  \"methods\": [");
        List<MethodJdbcReport> sorted = sortedMethods();
        if (sorted.isEmpty()) {
            out.append("]\n");
        } else {
            out.append("\n");
            for (int i = 0; i < sorted.size(); i++) {
                appendMethod(out, sorted.get(i));
                out.append(i < sorted.size() - 1 ? ",\n" : "\n");
            }
            out.append("  ]\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private List<MethodJdbcReport> sortedMethods() {
        return methods.stream()
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

    private void appendMethod(StringBuilder out, MethodJdbcReport method) {
        out.append("    {\n");
        out.append("      \"class\": \"").append(escape(method.className())).append("\",\n");
        out.append("      \"method\": \"").append(escape(method.method())).append("\",\n");
        out.append("      \"file\": \"").append(escape(method.file())).append("\",\n");
        out.append("      \"line\": ").append(method.line()).append(",\n");
        out.append("      \"kind\": \"").append(method.kind().name()).append("\",\n");
        out.append("      \"rollup\": \"").append(method.rollup().name()).append("\",\n");
        out.append("      \"usages\": [");
        List<JdbcUsage> usages = method.usages();
        if (usages.isEmpty()) {
            out.append("]\n");
        } else {
            out.append("\n");
            for (int i = 0; i < usages.size(); i++) {
                appendUsage(out, usages.get(i));
                out.append(i < usages.size() - 1 ? ",\n" : "\n");
            }
            out.append("      ]\n");
        }
        out.append("    }");
    }

    private void appendUsage(StringBuilder out, JdbcUsage usage) {
        out.append("        {\n");
        out.append("          \"idiom\": \"").append(usage.idiom().name()).append("\",\n");
        out.append("          \"classification\": \"").append(usage.classification().name()).append("\",\n");
        out.append("          \"location\": \"").append(escape(usage.location())).append("\",\n");
        out.append("          \"code\": ").append(usage.code() == null ? "null" : "\"" + usage.code().code() + "\"").append(",\n");
        out.append("          \"reason\": ").append(usage.reason() == null ? "null" : "\"" + escape(usage.reason()) + "\"").append("\n");
        out.append("        }");
    }

    private static String jsonStringLongMap(Map<String, Long> map, String indent) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringBuilder out = new StringBuilder("{\n");
        List<Map.Entry<String, Long>> entries = new java.util.ArrayList<>(new LinkedHashMap<>(map).entrySet());
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
        out.append("Titan JDBC compatibility report (sqlSafety=").append(sqlSafety.name().toLowerCase()).append(")\n");
        out.append("  methods: ").append(methodCount())
                .append("  transpilable=").append(methodsWith(JdbcClassification.TRANSPILABLE))
                .append("  passthrough=").append(methodsWith(JdbcClassification.PASSTHROUGH))
                .append("  rejected=").append(methodsWith(JdbcClassification.REJECTED))
                .append("\n");
        for (MethodJdbcReport method : sortedMethods()) {
            out.append("  ").append(method.rollup().name()).append("  ")
                    .append(method.className()).append('#').append(method.method())
                    .append("  (").append(method.file()).append(':').append(method.line()).append(")\n");
            for (JdbcUsage usage : method.usages()) {
                out.append("      ").append(usage.classification().name())
                        .append("  ").append(usage.idiom().name())
                        .append("  ").append(usage.location());
                if (usage.code() != null) {
                    out.append("  [").append(usage.code().code()).append("]");
                }
                if (usage.reason() != null) {
                    out.append("  ").append(usage.reason());
                }
                out.append("\n");
            }
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
