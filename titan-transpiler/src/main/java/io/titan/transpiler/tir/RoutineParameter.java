package io.titan.transpiler.tir;

import java.util.Objects;

public record RoutineParameter(String javaName, String sqlName, TirType type) {
    public RoutineParameter {
        Objects.requireNonNull(javaName, "javaName");
        Objects.requireNonNull(sqlName, "sqlName");
        Objects.requireNonNull(type, "type");
    }
}
