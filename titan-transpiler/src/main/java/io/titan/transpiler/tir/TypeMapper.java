package io.titan.transpiler.tir;

import io.titan.transpiler.tir.TirType;

public interface TypeMapper {
    String toSqlType(TirType type);
}
