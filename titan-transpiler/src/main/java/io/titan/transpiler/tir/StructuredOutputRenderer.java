package io.titan.transpiler.tir;

import java.util.Objects;
import java.util.stream.Collectors;

public final class StructuredOutputRenderer {
    public String renderPostgreSql(StructuredOutputPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return renderPostgreSqlValue(plan.root());
    }

    public String renderMySql(StructuredOutputPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return renderMySqlValue(plan.root());
    }

    private String renderPostgreSqlValue(StructuredOutputPlan.Value value) {
        return switch (value) {
            case StructuredOutputPlan.ObjectValue object -> renderPostgreSqlObject(object);
            case StructuredOutputPlan.ArrayValue array -> "jsonb_build_array(" + renderPostgreSqlValue(array.element()) + ")";
            case StructuredOutputPlan.ArrayLiteralValue array -> renderPostgreSqlArrayLiteral(array);
            case StructuredOutputPlan.ScalarValue scalar -> "to_jsonb(" + postgreSqlColumn(scalar.column()) + ")";
            case StructuredOutputPlan.NullValue nullValue -> "to_jsonb(CAST(NULL AS " + postgreSqlType(nullValue.type()) + "))";
            case StructuredOutputPlan.JsonNullValue ignored -> "'null'::jsonb";
            case StructuredOutputPlan.TextLiteralValue literal -> "to_jsonb(CAST("
                    + postgreSqlLiteral(literal.value()) + " AS TEXT))";
            case StructuredOutputPlan.IntegerLiteralValue literal -> "to_jsonb(" + literal.value() + ")";
            case StructuredOutputPlan.BooleanLiteralValue literal -> "to_jsonb(" + literal.value() + ")";
        };
    }

    private String renderPostgreSqlObject(StructuredOutputPlan.ObjectValue object) {
        return "jsonb_build_object(" + object.entries().stream()
                .map(entry -> postgreSqlLiteral(entry.key().value()) + ", " + renderPostgreSqlValue(entry.value()))
                .collect(Collectors.joining(", ")) + ")";
    }

    private String renderPostgreSqlArrayLiteral(StructuredOutputPlan.ArrayLiteralValue array) {
        return "jsonb_build_array(" + array.elements().stream()
                .map(this::renderPostgreSqlValue)
                .collect(Collectors.joining(", ")) + ")";
    }

    private String renderMySqlValue(StructuredOutputPlan.Value value) {
        return switch (value) {
            case StructuredOutputPlan.ObjectValue object -> renderMySqlObject(object);
            case StructuredOutputPlan.ArrayValue array -> "JSON_ARRAY(" + renderMySqlValue(array.element()) + ")";
            case StructuredOutputPlan.ArrayLiteralValue array -> renderMySqlArrayLiteral(array);
            case StructuredOutputPlan.ScalarValue scalar -> mySqlColumn(scalar.column());
            case StructuredOutputPlan.NullValue nullValue -> "CAST(NULL AS " + mySqlType(nullValue.type()) + ")";
            case StructuredOutputPlan.JsonNullValue ignored -> "CAST(NULL AS CHAR)";
            case StructuredOutputPlan.TextLiteralValue literal -> mySqlLiteral(literal.value());
            case StructuredOutputPlan.IntegerLiteralValue literal -> Integer.toString(literal.value());
            case StructuredOutputPlan.BooleanLiteralValue literal -> literal.value() ? "TRUE" : "FALSE";
        };
    }

    private String renderMySqlObject(StructuredOutputPlan.ObjectValue object) {
        return "JSON_OBJECT(" + object.entries().stream()
                .map(entry -> mySqlLiteral(entry.key().value()) + ", " + renderMySqlValue(entry.value()))
                .collect(Collectors.joining(", ")) + ")";
    }

    private String renderMySqlArrayLiteral(StructuredOutputPlan.ArrayLiteralValue array) {
        return "JSON_ARRAY(" + array.elements().stream()
                .map(this::renderMySqlValue)
                .collect(Collectors.joining(", ")) + ")";
    }

    private String postgreSqlColumn(QueryTemplatePlan.ColumnRef column) {
        return quotePostgreSqlIdentifier(column.tableAlias()) + "." + quotePostgreSqlIdentifier(column.column().value());
    }

    private String mySqlColumn(QueryTemplatePlan.ColumnRef column) {
        return quoteMySqlIdentifier(column.tableAlias()) + "." + quoteMySqlIdentifier(column.column().value());
    }

    private String postgreSqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "INTEGER";
            case TBigintType ignored -> "BIGINT";
            case TTextType ignored -> "TEXT";
            case TBooleanType ignored -> "BOOLEAN";
            case TNumericType ignored -> "NUMERIC";
            case TDoubleType ignored -> "DOUBLE PRECISION";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            case TTimestampType ignored -> "TIMESTAMP";
            case TTimestampTzType ignored -> "TIMESTAMPTZ";
            case TUuidType ignored -> "UUID";
            default -> throw unsupportedType(type);
        };
    }

    private String mySqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "SIGNED";
            case TBigintType ignored -> "SIGNED";
            case TTextType ignored -> "CHAR";
            case TBooleanType ignored -> "SIGNED";
            case TNumericType ignored -> "DECIMAL";
            case TDoubleType ignored -> "DOUBLE";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            case TTimestampType ignored -> "DATETIME";
            case TTimestampTzType ignored -> "DATETIME";
            // UUID is a CHAR(36) string on MySQL; CAST targets take the bare CHAR keyword.
            case TUuidType ignored -> "CHAR";
            default -> throw unsupportedType(type);
        };
    }

    private IllegalArgumentException unsupportedType(TirType type) {
        return new IllegalArgumentException("TITAN-E001 structured output scalar value type '"
                + type.getClass().getSimpleName() + "' is not supported yet");
    }

    private String postgreSqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String mySqlLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String quotePostgreSqlIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String quoteMySqlIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
