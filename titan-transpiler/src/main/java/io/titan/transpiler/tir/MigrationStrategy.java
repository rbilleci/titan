package io.titan.transpiler.tir;

public interface MigrationStrategy {

    enum RoutineKind {
        PROCEDURE,
        FUNCTION
    }

    String routineDropStatement(String schema, String name, RoutineKind kind);
}
