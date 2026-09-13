package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class SelectReferenceEvaluator {

    public ReferenceResult evaluate(
            SelectCaseModel.SelectCase selectCase,
            FixtureCatalog.FixtureTable fixtureTable
    ) {
        Objects.requireNonNull(selectCase, "selectCase");
        Objects.requireNonNull(fixtureTable, "fixtureTable");
        if (selectCase.sourceTable().id().equals(fixtureTable.sourceTableId()) == false) {
            throw new IllegalArgumentException("fixture table does not match select case sourceTable");
        }

        List<ResultRow> rows = new ArrayList<>();
        for (FixtureCatalog.FixtureRow fixtureRow : fixtureTable.rows()) {
            if (selectCase.filter() != null && !Boolean.TRUE.equals(evaluatePredicate(selectCase.filter(), fixtureRow.values()))) {
                continue;
            }
            Map<String, Object> projected = new LinkedHashMap<>();
            for (SelectCaseModel.Projection projection : selectCase.projections()) {
                projected.put(projection.alias(), evaluateExpression(projection.expression(), fixtureRow.values()));
            }
            rows.add(new ResultRow(projected));
        }

        List<ResultRow> normalized = normalizeRows(rows, selectCase.ordering());
        return new ReferenceResult(normalized);
    }

    private static List<ResultRow> normalizeRows(
            List<ResultRow> rows,
            List<SelectCaseModel.Ordering> ordering
    ) {
        List<ResultRow> normalized = new ArrayList<>(rows);
        if (ordering.isEmpty()) {
            normalized.sort(Comparator.comparing(ResultRow::toStableJson));
            return List.copyOf(normalized);
        }

        Comparator<ResultRow> comparator = null;
        for (SelectCaseModel.Ordering item : ordering) {
            Comparator<ResultRow> next = switch (item.nulls()) {
                // Bare ASC/DESC: keep the historical reference semantics (NULL smallest). Only
                // sound while no case exposes NULL values to such an ordering — the dialects
                // disagree on the default placement (see SelectCaseModel.NullsPlacement).
                case DIALECT_DEFAULT -> {
                    Comparator<ResultRow> base = (left, right) -> compareNullableComparable(
                            comparableValue(left.values().get(item.column().columnName())),
                            comparableValue(right.values().get(item.column().columnName())));
                    yield item.direction() == SelectCaseModel.SortDirection.DESC ? base.reversed() : base;
                }
                // Explicit placement: NULLs are pinned FIRST/LAST independently of the
                // direction, which applies to the non-null values only — exactly the SQL
                // NULLS FIRST/LAST semantics both dialect legs execute.
                case FIRST, LAST -> {
                    Comparator<Comparable<?>> values = (left, right) -> compareNullableComparable(left, right);
                    if (item.direction() == SelectCaseModel.SortDirection.DESC) {
                        values = values.reversed();
                    }
                    Comparator<Comparable<?>> withNulls = item.nulls() == SelectCaseModel.NullsPlacement.FIRST
                            ? Comparator.nullsFirst(values)
                            : Comparator.nullsLast(values);
                    yield Comparator.comparing(
                            row -> comparableValue(row.values().get(item.column().columnName())),
                            withNulls);
                }
            };
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        normalized.sort(comparator.thenComparing(ResultRow::toStableJson));
        return List.copyOf(normalized);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNullableComparable(Comparable<?> left, Comparable<?> right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return ((Comparable) left).compareTo(right);
    }

    private static Boolean evaluatePredicate(SelectCaseModel.Predicate predicate, Map<String, Object> row) {
        return switch (predicate) {
            case SelectCaseModel.ComparisonPredicate comparison -> evaluateComparison(comparison, row);
            case SelectCaseModel.NullCheckPredicate nullCheck -> evaluateNullCheck(nullCheck, row);
            case SelectCaseModel.LogicalPredicate logical -> evaluateLogical(logical, row);
            case SelectCaseModel.NotPredicate not -> evaluateNot(not, row);
        };
    }

    private static Boolean evaluateComparison(SelectCaseModel.ComparisonPredicate comparison, Map<String, Object> row) {
        Object left = evaluateExpression(comparison.left(), row);
        Object right = evaluateExpression(comparison.right(), row);
        if (left == null || right == null) {
            return null;
        }
        int compared = compareValues(left, right);
        return switch (comparison.operator()) {
            case EQ -> compared == 0;
            case NE -> compared != 0;
            case LT -> compared < 0;
            case LE -> compared <= 0;
            case GT -> compared > 0;
            case GE -> compared >= 0;
        };
    }

    private static Boolean evaluateNullCheck(SelectCaseModel.NullCheckPredicate nullCheck, Map<String, Object> row) {
        Object value = evaluateExpression(nullCheck.expression(), row);
        return switch (nullCheck.kind()) {
            case IS_NULL -> value == null;
            case IS_NOT_NULL -> value != null;
        };
    }

    private static Boolean evaluateLogical(SelectCaseModel.LogicalPredicate logical, Map<String, Object> row) {
        return switch (logical.operator()) {
            case AND -> evaluateAnd(logical, row);
            case OR -> evaluateOr(logical, row);
        };
    }

    private static Boolean evaluateNot(SelectCaseModel.NotPredicate not, Map<String, Object> row) {
        Boolean inner = evaluatePredicate(not.predicate(), row);
        return inner == null ? null : !inner;
    }

    private static Boolean evaluateAnd(SelectCaseModel.LogicalPredicate logical, Map<String, Object> row) {
        boolean sawNull = false;
        for (SelectCaseModel.Predicate predicate : logical.predicates()) {
            Boolean value = evaluatePredicate(predicate, row);
            if (Boolean.FALSE.equals(value)) {
                return false;
            }
            if (value == null) {
                sawNull = true;
            }
        }
        return sawNull ? null : true;
    }

    private static Boolean evaluateOr(SelectCaseModel.LogicalPredicate logical, Map<String, Object> row) {
        boolean sawNull = false;
        for (SelectCaseModel.Predicate predicate : logical.predicates()) {
            Boolean value = evaluatePredicate(predicate, row);
            if (Boolean.TRUE.equals(value)) {
                return true;
            }
            if (value == null) {
                sawNull = true;
            }
        }
        return sawNull ? null : false;
    }

    private static Object evaluateExpression(SelectCaseModel.Expression expression, Map<String, Object> row) {
        return switch (expression) {
            case SelectCaseModel.ColumnRef column -> row.get(column.columnName());
            case SelectCaseModel.IntLiteral literal -> literal.value();
            case SelectCaseModel.BooleanLiteral literal -> literal.value();
            case SelectCaseModel.TextLiteral literal -> literal.value();
            case SelectCaseModel.ArithmeticExpression arithmetic -> evaluateArithmetic(arithmetic, row);
        };
    }

    private static Object evaluateArithmetic(SelectCaseModel.ArithmeticExpression arithmetic, Map<String, Object> row) {
        Object left = evaluateExpression(arithmetic.left(), row);
        Object right = evaluateExpression(arithmetic.right(), row);
        if (left == null || right == null) {
            return null;
        }
        return switch (arithmetic.operator()) {
            case "+" -> ((Number) left).intValue() + ((Number) right).intValue();
            case "-" -> ((Number) left).intValue() - ((Number) right).intValue();
            case "*" -> ((Number) left).intValue() * ((Number) right).intValue();
            default -> throw new IllegalArgumentException("Unsupported arithmetic operator: " + arithmetic.operator());
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Integer.compare(leftNumber.intValue(), rightNumber.intValue());
        }
        if (left instanceof Comparable comparableLeft && right instanceof Comparable comparableRight) {
            return comparableLeft.compareTo(comparableRight);
        }
        throw new IllegalArgumentException("Values are not comparable: " + left + " vs " + right);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable<?> comparableValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Comparable comparable) {
            return comparable;
        }
        throw new IllegalArgumentException("Value is not comparable for ordering: " + value);
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
