package io.titan.transpiler.tir.generative.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class ReferenceResultModels {

    private ReferenceResultModels() {
    }

    public record ReferenceResult(List<ResultRow> rows) {
        public ReferenceResult {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        }

        public String toStableJson() {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (ResultRow row : rows) {
                joiner.add(row.toStableJson());
            }
            return joiner.toString();
        }
    }

    public record ResultRow(Map<String, Object> values) {
        public ResultRow {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }

        public String toStableJson() {
            StringJoiner entries = new StringJoiner(", ", "{", "}");
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> entries.add("\"" + jsonEscape(entry.getKey()) + "\": " + jsonValue(entry.getValue())));
            return entries.toString();
        }
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + jsonEscape(s) + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static String jsonEscape(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
