package io.titan.transpiler.tir;

final class PostgreSqlTypeMapper implements TypeMapper {
    @Override
    public String toSqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "INTEGER";
            case TBigintType ignored -> "BIGINT";
            case TBooleanType ignored -> "BOOLEAN";
            case TTextType ignored -> "TEXT";
            case TNumericType t -> "NUMERIC(" + t.precision() + "," + t.scale() + ")";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            case TTimestampType ignored -> "TIMESTAMP";
            case TTimestampTzType ignored -> "TIMESTAMPTZ";
            case TDurationType ignored -> "INTERVAL";
            case TPeriodType ignored -> "INTERVAL";
            case TArrayType t -> toSqlType(t.elementType()) + "[]";
            case TJsonType ignored -> "JSONB";
            case TCompositeType ignored -> "RECORD";
            // Naming must go through SqlNames (audit S2; B-2/TG-BLK-005): record type names
            // derive from the record's source-local qualified name on every code path.
            case TRecordType t -> qualify(t.schema(), SqlNames.recordSqlBaseName(t.recordName()));
            case TUuidType ignored -> "UUID";
            case TBytesType ignored -> "BYTEA";
            case TVoidType ignored -> "VOID";
        };
    }

    // Mirrors AbstractSqlEmitter.qualify (plan 1.3b): record type names must render identically
    // on the mapper and emitter code paths, including identifier quoting.
    private static String qualify(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(name);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
