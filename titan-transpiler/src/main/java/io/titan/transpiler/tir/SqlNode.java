package io.titan.transpiler.tir;

public sealed interface SqlNode extends TirNode permits SelectSql, InsertSql, UpdateSql, DeleteSql, UnionSql, IntersectSql, ExceptSql, RawSql, LateralSubquery {
}
