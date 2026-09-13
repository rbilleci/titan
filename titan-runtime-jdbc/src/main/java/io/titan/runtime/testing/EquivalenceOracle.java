package io.titan.runtime.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The equivalence oracle: compares java-mode and sql-mode results with type normalization,
 * caller-chosen ordering semantics, and first-divergence diff reporting (audit R-5).
 *
 * <p>The previous oracle compared row lists with order-sensitive {@link Objects#equals} on raw
 * JDBC values — {@code Integer 1} vs {@code Long 1} alarmed falsely, scale-shifted
 * {@code BigDecimal}s alarmed falsely, and ORDER-BY-free queries passed or failed on accidental
 * row order. This oracle normalizes values ({@link ValueNormalizer}), compares row sets either
 * pairwise ({@link ComparisonMode#ORDERED}) or as a multiset ({@link ComparisonMode#UNORDERED}),
 * and reports the first divergence with row position/key, column, and both raw values with their
 * runtime types.</p>
 */
public final class EquivalenceOracle {

    private EquivalenceOracle() {
    }

    /**
     * One divergence between the two result sets — the first one found.
     *
     * @param where     row coordinate of the divergence: {@code "row 3"} (0-based) in ordered
     *                  mode, {@code "row count"} for cardinality mismatches, or the unmatched-row
     *                  description in unordered mode
     * @param column    column coordinate (name for map/record rows, {@code "column N"} for
     *                  positional rows, {@code "value"} for scalars), or {@code null} when the
     *                  divergence is not column-shaped (e.g. row-count mismatch)
     * @param javaValue raw (un-normalized) java-mode value at the divergence
     * @param sqlValue  raw (un-normalized) sql-mode value at the divergence
     */
    public record Divergence(String where, String column, Object javaValue, Object sqlValue) {

        public String describe() {
            StringBuilder message = new StringBuilder("first divergence at ").append(where);
            if (column != null) {
                message.append(", ").append(column);
            }
            message.append(":\n  java-mode value: ").append(render(javaValue))
                    .append("\n  sql-mode value:  ").append(render(sqlValue));
            return message.toString();
        }

        private static String render(Object value) {
            if (value == null) {
                return "NULL";
            }
            return value + " (" + value.getClass().getName() + ")";
        }
    }

    /** Compares two scalar results after normalization. */
    public static Optional<Divergence> compareScalars(Object javaValue, Object sqlValue) {
        if (Objects.equals(ValueNormalizer.normalize(javaValue), ValueNormalizer.normalize(sqlValue))) {
            return Optional.empty();
        }
        return Optional.of(new Divergence("scalar result", "value", javaValue, sqlValue));
    }

    /**
     * Compares two row lists in the given mode and reports the first divergence.
     *
     * <p>Rows may be scalars, {@code Object[]}, {@code List}, {@code Map} (column name to value)
     * or records; columns are extracted accordingly and compared after normalization.</p>
     */
    public static Optional<Divergence> compareRows(List<?> javaRows, List<?> sqlRows, ComparisonMode mode) {
        Objects.requireNonNull(javaRows, "javaRows");
        Objects.requireNonNull(sqlRows, "sqlRows");
        Objects.requireNonNull(mode, "mode");
        if (javaRows.size() != sqlRows.size()) {
            return Optional.of(new Divergence("row count", null, javaRows.size(), sqlRows.size()));
        }
        return mode == ComparisonMode.ORDERED
                ? compareOrdered(javaRows, sqlRows)
                : compareUnordered(javaRows, sqlRows);
    }

    private static Optional<Divergence> compareOrdered(List<?> javaRows, List<?> sqlRows) {
        for (int i = 0; i < javaRows.size(); i++) {
            Optional<Divergence> divergence = compareRow("row " + i, javaRows.get(i), sqlRows.get(i));
            if (divergence.isPresent()) {
                return divergence;
            }
        }
        return Optional.empty();
    }

    private static Optional<Divergence> compareUnordered(List<?> javaRows, List<?> sqlRows) {
        // Multiset match on normalized rows. Track the original sql-mode rows per normalized key
        // so the eventual diff shows raw values, not normalized ones.
        Map<Object, List<Object>> unmatchedSqlRows = new HashMap<>();
        for (Object sqlRow : sqlRows) {
            unmatchedSqlRows.computeIfAbsent(normalizedKey(sqlRow), key -> new ArrayList<>()).add(sqlRow);
        }
        for (int i = 0; i < javaRows.size(); i++) {
            Object javaRow = javaRows.get(i);
            List<Object> candidates = unmatchedSqlRows.get(normalizedKey(javaRow));
            if (candidates == null || candidates.isEmpty()) {
                return Optional.of(firstUnmatchedDivergence(i, javaRow, unmatchedSqlRows));
            }
            candidates.remove(candidates.size() - 1);
        }
        return Optional.empty();
    }

    /**
     * A java-mode row had no multiset match. Diff it column-by-column against the closest
     * remaining sql-mode row (most matching columns) so the report names the offending column.
     */
    private static Divergence firstUnmatchedDivergence(
            int javaRowIndex, Object javaRow, Map<Object, List<Object>> unmatchedSqlRows) {
        Map<String, Object> javaColumns = columnsOf(javaRow);
        Object bestCandidate = null;
        int bestScore = -1;
        for (List<Object> remaining : unmatchedSqlRows.values()) {
            for (Object sqlRow : remaining) {
                int score = matchingColumns(javaColumns, columnsOf(sqlRow));
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = sqlRow;
                }
            }
        }
        String where = "unmatched row (java-mode row " + javaRowIndex + " has no sql-mode match)";
        if (bestCandidate == null) {
            return new Divergence(where, null, javaRow, null);
        }
        return compareRow(where, javaRow, bestCandidate)
                .orElse(new Divergence(where, null, javaRow, bestCandidate));
    }

    private static int matchingColumns(Map<String, Object> javaColumns, Map<String, Object> sqlColumns) {
        int score = 0;
        for (Map.Entry<String, Object> entry : javaColumns.entrySet()) {
            if (sqlColumns.containsKey(entry.getKey())
                    && Objects.equals(
                            ValueNormalizer.normalize(entry.getValue()),
                            ValueNormalizer.normalize(sqlColumns.get(entry.getKey())))) {
                score++;
            }
        }
        return score;
    }

    private static Optional<Divergence> compareRow(String where, Object javaRow, Object sqlRow) {
        Map<String, Object> javaColumns = columnsOf(javaRow);
        Map<String, Object> sqlColumns = columnsOf(sqlRow);
        if (!javaColumns.keySet().equals(sqlColumns.keySet())) {
            return Optional.of(new Divergence(where, "column set", javaColumns.keySet(), sqlColumns.keySet()));
        }
        for (Map.Entry<String, Object> entry : javaColumns.entrySet()) {
            Object javaValue = entry.getValue();
            Object sqlValue = sqlColumns.get(entry.getKey());
            if (!Objects.equals(ValueNormalizer.normalize(javaValue), ValueNormalizer.normalize(sqlValue))) {
                return Optional.of(new Divergence(where, entry.getKey(), javaValue, sqlValue));
            }
        }
        return Optional.empty();
    }

    /** Normalized whole-row key used for multiset matching. */
    private static Object normalizedKey(Object row) {
        Map<String, Object> columns = columnsOf(row);
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            normalized.put(entry.getKey(), ValueNormalizer.normalize(entry.getValue()));
        }
        return normalized;
    }

    /** Decomposes a row into named columns; scalars become a single {@code "value"} column. */
    private static Map<String, Object> columnsOf(Object row) {
        Map<String, Object> columns = new LinkedHashMap<>();
        if (row instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                columns.put("column " + (i + 1), array[i]);
            }
            return columns;
        }
        if (row instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                columns.put("column " + (i + 1), list.get(i));
            }
            return columns;
        }
        if (row instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                columns.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return columns;
        }
        if (row instanceof Record record) {
            for (var component : record.getClass().getRecordComponents()) {
                try {
                    columns.put(component.getName(), component.getAccessor().invoke(record));
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(
                            "internal: failed to read record component " + component.getName(), ex);
                }
            }
            return columns;
        }
        columns.put("value", row);
        return columns;
    }
}
