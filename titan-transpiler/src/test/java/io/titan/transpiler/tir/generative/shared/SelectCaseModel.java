package io.titan.transpiler.tir.generative.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class SelectCaseModel {

    public enum SourceTable {
        ACCOUNTS_FIXTURE("accounts_fixture");

        private final String id;

        SourceTable(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum ResultShape {
        ROW_SET("row-set");

        private final String id;

        ResultShape(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum ValueType {
        INTEGER("integer"),
        BOOLEAN("boolean"),
        TEXT("text");

        private final String id;

        ValueType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum ComparisonOperator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">=");

        private final String sql;

        ComparisonOperator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    public enum LogicalOperator {
        AND("and"),
        OR("or");

        private final String id;

        LogicalOperator(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum NullCheckKind {
        IS_NULL("is-null"),
        IS_NOT_NULL("is-not-null");

        private final String id;

        NullCheckKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum SortDirection {
        ASC("asc"),
        DESC("desc");

        private final String id;

        SortDirection(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record SelectCase(
            String profileId,
            SourceTable sourceTable,
            List<Projection> projections,
            Predicate filter,
            List<Ordering> ordering,
            ResultShape resultShape
    ) {
        public SelectCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(sourceTable, "sourceTable");
            projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
            if (projections.isEmpty()) {
                throw new IllegalArgumentException("projections must not be empty");
            }
            if (projections.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("projections must not contain null");
            }
            ordering = List.copyOf(Objects.requireNonNull(ordering, "ordering"));
            if (ordering.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("ordering must not contain null");
            }
            Objects.requireNonNull(resultShape, "resultShape");
        }

        public String toStableJson() {
            StringJoiner projectionsJson = new StringJoiner(", ", "[", "]");
            for (Projection projection : projections) {
                projectionsJson.add(projection.toStableJson());
            }
            StringJoiner orderingJson = new StringJoiner(", ", "[", "]");
            for (Ordering item : ordering) {
                orderingJson.add(item.toStableJson());
            }
            return """
                    {
                      "profileId": "%s",
                      "sourceTable": "%s",
                      "projections": %s,
                      "filter": %s,
                      "ordering": %s,
                      "resultShape": "%s"
                    }
                    """.formatted(
                    jsonEscape(profileId),
                    sourceTable.id(),
                    projectionsJson,
                    filter == null ? "null" : filter.toStableJson(),
                    orderingJson,
                    resultShape.id());
        }
    }

    public record Projection(String alias, Expression expression) {
        public Projection {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("projection alias must not be blank");
            }
            Objects.requireNonNull(expression, "expression");
        }

        public String toStableJson() {
            return """
                    {
                      "alias": "%s",
                      "expression": %s
                    }
                    """.formatted(jsonEscape(alias), expression.toStableJson());
        }
    }

    public record Ordering(ColumnRef column, SortDirection direction, NullsPlacement nulls) {
        public Ordering {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(nulls, "nulls");
        }

        /** Dialect-default NULL placement (no explicit NULLS FIRST/LAST in the query). */
        public Ordering(ColumnRef column, SortDirection direction) {
            this(column, direction, NullsPlacement.DIALECT_DEFAULT);
        }

        public String toStableJson() {
            return """
                    {
                      "column": %s,
                      "direction": "%s",
                      "nulls": "%s"
                    }
                    """.formatted(column.toStableJson(), direction.id(), nulls.id());
        }
    }

    /**
     * Explicit NULL placement for an ordering (plan 5.3, dialect-divergence-sensitive cases).
     * {@code DIALECT_DEFAULT} emits a bare ASC/DESC — fine while no generated case exposes NULL
     * values to such an ordering (PostgreSQL defaults to NULLS LAST on ASC, MySQL to NULLS
     * first — they disagree). Cases whose ordered column can be NULL in the result must pick
     * FIRST or LAST so the query pins the placement: PostgreSQL emits native
     * {@code NULLS FIRST/LAST}, MySQL emits the {@code (expr IS NULL)} leading-sort-key
     * emulation, and the reference evaluator honors the same placement — running these under
     * generated load is what proves the MySQL emulation compensates correctly.
     */
    public enum NullsPlacement {
        DIALECT_DEFAULT("dialect-default"),
        FIRST("first"),
        LAST("last");

        private final String id;

        NullsPlacement(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public sealed interface Expression permits ColumnRef, IntLiteral, BooleanLiteral, TextLiteral, ArithmeticExpression {
        public String toStableJson();
        ValueType valueType();
    }

    public record ColumnRef(String columnName, ValueType valueType) implements Expression {
        public ColumnRef {
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            Objects.requireNonNull(valueType, "valueType");
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "column",
                      "columnName": "%s",
                      "valueType": "%s"
                    }
                    """.formatted(jsonEscape(columnName), valueType.id());
        }
    }

    public record IntLiteral(int value) implements Expression {
        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "int-literal",
                      "value": %s,
                      "valueType": "integer"
                    }
                    """.formatted(value);
        }

        @Override
        public ValueType valueType() {
            return ValueType.INTEGER;
        }
    }

    public record BooleanLiteral(boolean value) implements Expression {
        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "boolean-literal",
                      "value": %s,
                      "valueType": "boolean"
                    }
                    """.formatted(value);
        }

        @Override
        public ValueType valueType() {
            return ValueType.BOOLEAN;
        }
    }

    public record TextLiteral(String value) implements Expression {
        public TextLiteral {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "text-literal",
                      "value": "%s",
                      "valueType": "text"
                    }
                    """.formatted(jsonEscape(value));
        }

        @Override
        public ValueType valueType() {
            return ValueType.TEXT;
        }
    }

    public record ArithmeticExpression(String operator, Expression left, Expression right) implements Expression {
        public ArithmeticExpression {
            if (operator == null || operator.isBlank()) {
                throw new IllegalArgumentException("operator must not be blank");
            }
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (left.valueType() != ValueType.INTEGER || right.valueType() != ValueType.INTEGER) {
                throw new IllegalArgumentException("arithmetic expressions currently require integer operands");
            }
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "arithmetic",
                      "operator": "%s",
                      "left": %s,
                      "right": %s,
                      "valueType": "integer"
                    }
                    """.formatted(jsonEscape(operator), left.toStableJson(), right.toStableJson());
        }

        @Override
        public ValueType valueType() {
            return ValueType.INTEGER;
        }
    }

    public sealed interface Predicate permits ComparisonPredicate, NullCheckPredicate, LogicalPredicate, NotPredicate {
        public String toStableJson();
    }

    public record ComparisonPredicate(Expression left, ComparisonOperator operator, Expression right) implements Predicate {
        public ComparisonPredicate {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
            if (left.valueType() != right.valueType()) {
                throw new IllegalArgumentException("comparison operands must share the same valueType");
            }
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "comparison",
                      "operator": "%s",
                      "left": %s,
                      "right": %s
                    }
                    """.formatted(operator.sql(), left.toStableJson(), right.toStableJson());
        }
    }

    public record NullCheckPredicate(Expression expression, NullCheckKind kind) implements Predicate {
        public NullCheckPredicate {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "null-check",
                      "check": "%s",
                      "expression": %s
                    }
                    """.formatted(kind.id(), expression.toStableJson());
        }
    }

    public record LogicalPredicate(LogicalOperator operator, List<Predicate> predicates) implements Predicate {
        public LogicalPredicate {
            Objects.requireNonNull(operator, "operator");
            predicates = List.copyOf(Objects.requireNonNull(predicates, "predicates"));
            if (predicates.size() < 2) {
                throw new IllegalArgumentException("logical predicates require at least 2 children");
            }
            if (predicates.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("logical predicates must not contain null");
            }
        }

        @Override
        public String toStableJson() {
            StringJoiner joined = new StringJoiner(", ", "[", "]");
            for (Predicate predicate : predicates) {
                joined.add(predicate.toStableJson());
            }
            return """
                    {
                      "kind": "logical",
                      "operator": "%s",
                      "predicates": %s
                    }
                    """.formatted(operator.id(), joined);
        }
    }

    public record NotPredicate(Predicate predicate) implements Predicate {
        public NotPredicate {
            Objects.requireNonNull(predicate, "predicate");
        }

        @Override
        public String toStableJson() {
            return """
                    {
                      "kind": "not",
                      "predicate": %s
                    }
                    """.formatted(predicate.toStableJson());
        }
    }

    public static SelectCase selectCase(
            String profileId,
            SourceTable sourceTable,
            List<Projection> projections,
            Predicate filter,
            List<Ordering> ordering,
            ResultShape resultShape
    ) {
        return new SelectCase(profileId, sourceTable, projections, filter, ordering, resultShape);
    }

    public static Projection projection(String alias, Expression expression) {
        return new Projection(alias, expression);
    }

    public static ColumnRef column(String columnName, ValueType valueType) {
        return new ColumnRef(columnName, valueType);
    }

    public static IntLiteral intLiteral(int value) {
        return new IntLiteral(value);
    }

    public static BooleanLiteral boolLiteral(boolean value) {
        return new BooleanLiteral(value);
    }

    public static TextLiteral textLiteral(String value) {
        return new TextLiteral(value);
    }

    public static ArithmeticExpression add(Expression left, Expression right) {
        return new ArithmeticExpression("+", left, right);
    }

    public static ArithmeticExpression subtract(Expression left, Expression right) {
        return new ArithmeticExpression("-", left, right);
    }

    public static ArithmeticExpression multiply(Expression left, Expression right) {
        return new ArithmeticExpression("*", left, right);
    }

    public static ComparisonPredicate compare(Expression left, ComparisonOperator operator, Expression right) {
        return new ComparisonPredicate(left, operator, right);
    }

    public static NullCheckPredicate nullCheck(Expression expression, NullCheckKind kind) {
        return new NullCheckPredicate(expression, kind);
    }

    public static LogicalPredicate and(Predicate... predicates) {
        return new LogicalPredicate(LogicalOperator.AND, List.of(predicates));
    }

    public static LogicalPredicate or(Predicate... predicates) {
        return new LogicalPredicate(LogicalOperator.OR, List.of(predicates));
    }

    public static NotPredicate not(Predicate predicate) {
        return new NotPredicate(predicate);
    }

    public static Ordering orderBy(String columnName, ValueType valueType, SortDirection direction) {
        return new Ordering(column(columnName, valueType), direction);
    }

    public static Ordering orderBy(String columnName, ValueType valueType, SortDirection direction, NullsPlacement nulls) {
        return new Ordering(column(columnName, valueType), direction, nulls);
    }

    private static String jsonEscape(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
