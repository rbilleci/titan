package io.titan.transpiler.tir;

import java.util.List;

public sealed interface TirType permits TIntType, TBigintType, TTextType, TBooleanType, TNumericType,
        TDateType, TTimeType, TTimestampType, TTimestampTzType, TDurationType, TPeriodType, TArrayType, TJsonType,
        TCompositeType, TRecordType, TUuidType, TBytesType, TVoidType {
}

record TIntType() implements TirType {}
record TBigintType() implements TirType {}
record TTextType() implements TirType {}
record TBooleanType() implements TirType {}
record TNumericType(int precision, int scale) implements TirType {}
record TDateType() implements TirType {}
record TTimeType() implements TirType {}
record TTimestampType() implements TirType {}
record TTimestampTzType() implements TirType {}
record TDurationType() implements TirType {}
record TPeriodType() implements TirType {}
record TArrayType(TirType elementType) implements TirType {}
record TJsonType() implements TirType {}
record TCompositeType(List<TCompositeField> fields) implements TirType {}
record TRecordType(String schema, String recordName) implements TirType {
    TRecordType(String recordName) {
        this(null, recordName);
    }
}
// UUID: native `uuid` on PostgreSQL; `CHAR(36)` (canonical 36-char string) on MySQL, which has no
// native UUID type. Values are always bound, never text-spliced (runtime stringifies on MySQL).
record TUuidType() implements TirType {}
// Binary payload (Java `byte[]`): PostgreSQL `BYTEA`, MySQL `LONGBLOB`. Bound as bytes, never spliced.
// Distinct from TArrayType(TIntType): a `byte[]` is an opaque blob, not an array of integers.
record TBytesType() implements TirType {}
record TVoidType() implements TirType {}

record TCompositeField(String name, TirType type) {}
