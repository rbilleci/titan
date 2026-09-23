package io.titan.transpiler.tir;

final class MySqlTypeMapper implements TypeMapper {
    @Override
    public String toSqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "INT";
            case TBigintType ignored -> "BIGINT";
            case TBooleanType ignored -> "BOOLEAN";
            case TTextType ignored -> "TEXT";
            case TNumericType t -> "DECIMAL(" + t.precision() + "," + t.scale() + ")";
            case TDoubleType ignored -> "DOUBLE";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            // E-13 (plan 3.1): bare DATETIME/TIMESTAMP have second precision on MySQL — Java's
            // LocalDateTime/Instant carry microseconds, so fractional seconds were silently
            // truncated. (6) is MySQL's maximum fractional-seconds precision. Note the residual
            // caveat: TIMESTAMP(6) is range-limited to 1970-01-01..2038-01-19 UTC — the pipeline
            // emits a TITAN-W005 warning when a TIMESTAMPTZ-typed signature targets MySQL.
            case TTimestampType ignored -> "DATETIME(6)";
            case TTimestampTzType ignored -> "TIMESTAMP(6)";
            case TDurationType ignored -> "BIGINT";
            case TPeriodType ignored -> "BIGINT";
            case TArrayType ignored -> "JSON";
            case TJsonType ignored -> "JSON";
            case TCompositeType ignored -> "JSON";
            case TRecordType ignored -> "JSON";
            // MySQL has no native UUID type: store/emit the canonical 36-char string (charter §1.1a
            // decision). Matches the runtime's MySQL uuid.toString() bind path.
            case TUuidType ignored -> "CHAR(36)";
            // No length-free VARBINARY; LONGBLOB holds an arbitrary binary payload without truncation.
            case TBytesType ignored -> "LONGBLOB";
            case TVoidType ignored -> "VOID";
        };
    }
}
