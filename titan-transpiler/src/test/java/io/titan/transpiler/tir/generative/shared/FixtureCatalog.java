package io.titan.transpiler.tir.generative.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class FixtureCatalog {

    public record FixtureColumn(String name, String valueTypeId, boolean nullable) {
        public FixtureColumn {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("fixture column name must not be blank");
            }
            Objects.requireNonNull(valueTypeId, "valueTypeId");
        }

        String toStableJson() {
            return """
                    {
                      "name": "%s",
                      "valueType": "%s",
                      "nullable": %s
                    }
                    """.formatted(jsonEscape(name), valueTypeId, nullable);
        }
    }

    public record FixtureRow(Map<String, Object> values) {
        public FixtureRow {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        }

        String toStableJson() {
            StringJoiner entries = new StringJoiner(", ", "{", "}");
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> entries.add("\"" + jsonEscape(entry.getKey()) + "\": " + jsonValue(entry.getValue())));
            return entries.toString();
        }
    }

    public record FixtureTable(
            String sourceTableId,
            List<FixtureColumn> columns,
            List<FixtureRow> rows
    ) {
        public FixtureTable {
            Objects.requireNonNull(sourceTableId, "sourceTableId");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("fixture columns must not be empty");
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("fixture rows must not be empty");
            }
        }

        public String toStableJson() {
            StringJoiner columnsJson = new StringJoiner(", ", "[", "]");
            for (FixtureColumn column : columns) {
                columnsJson.add(column.toStableJson());
            }
            StringJoiner rowsJson = new StringJoiner(", ", "[", "]");
            for (FixtureRow row : rows) {
                rowsJson.add(row.toStableJson());
            }
            return """
                    {
                      "sourceTable": "%s",
                      "columns": %s,
                      "rows": %s
                    }
                    """.formatted(sourceTableId, columnsJson, rowsJson);
        }
    }

    private FixtureCatalog() {
    }

    public static FixtureTable accountsFixture() {
        return new FixtureTable(
                "accounts_fixture",
                List.of(
                        new FixtureColumn("id", "integer", false),
                        new FixtureColumn("email", "text", true),
                        new FixtureColumn("active", "boolean", true),
                        new FixtureColumn("plan_code", "text", true),
                        new FixtureColumn("login_count", "integer", true)),
                List.of(
                        accountRow(1, "ada@titan.dev", true, "free", 12),
                        accountRow(2, "bram@titan.dev", false, "pro", 1),
                        accountRow(3, null, true, null, 7),
                        accountRow(4, "cora@titan.dev", null, "pro", null),
                        accountRow(5, "drew@titan.dev", true, "enterprise", 12),
                        accountRow(6, null, false, "free", 0))
        );
    }

    public static FixtureTable plansFixture() {
        return new FixtureTable(
                "accounts_fixture",
                List.of(
                        new FixtureColumn("code", "text", false),
                        new FixtureColumn("name", "text", false),
                        new FixtureColumn("paid", "boolean", false),
                        new FixtureColumn("family_code", "text", false)),
                List.of(
                        planRow("free", "Free", false, "starter"),
                        planRow("pro", "Pro", true, "growth"),
                        planRow("enterprise", "Enterprise", true, "growth"),
                        planRow("legacy", "Legacy", false, "starter"))
        );
    }

    public static FixtureTable plansFixtureWithDuplicateFree() {
        return new FixtureTable(
                "accounts_fixture",
                List.of(
                        new FixtureColumn("code", "text", false),
                        new FixtureColumn("name", "text", false),
                        new FixtureColumn("paid", "boolean", false),
                        new FixtureColumn("family_code", "text", false)),
                List.of(
                        planRow("free", "Free", false, "starter"),
                        planRow("free", "Free Plus", false, "starter"),
                        planRow("pro", "Pro", true, "growth"),
                        planRow("enterprise", "Enterprise", true, "growth"),
                        planRow("legacy", "Legacy", false, "starter"))
        );
    }

    public static FixtureTable planFamiliesFixture() {
        return new FixtureTable(
                "accounts_fixture",
                List.of(
                        new FixtureColumn("code", "text", false),
                        new FixtureColumn("label", "text", false)),
                List.of(
                        planFamilyRow("starter", "Starter"),
                        planFamilyRow("growth", "Growth"))
        );
    }

    public static FixtureTable planFamiliesFixtureWithDuplicateGrowth() {
        return new FixtureTable(
                "accounts_fixture",
                List.of(
                        new FixtureColumn("code", "text", false),
                        new FixtureColumn("label", "text", false)),
                List.of(
                        planFamilyRow("starter", "Starter"),
                        planFamilyRow("growth", "Growth"),
                        planFamilyRow("growth", "Growth Plus"))
        );
    }

    private static FixtureRow accountRow(Integer id, String email, Boolean active, String planCode, Integer loginCount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("email", email);
        values.put("active", active);
        values.put("plan_code", planCode);
        values.put("login_count", loginCount);
        return new FixtureRow(values);
    }

    private static FixtureRow planRow(String code, String name, Boolean paid, String familyCode) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", code);
        values.put("name", name);
        values.put("paid", paid);
        values.put("family_code", familyCode);
        return new FixtureRow(values);
    }

    private static FixtureRow planFamilyRow(String code, String label) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", code);
        values.put("label", label);
        return new FixtureRow(values);
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
        throw new IllegalArgumentException("Unsupported fixture JSON value: " + value.getClass().getName());
    }

    private static String jsonEscape(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
