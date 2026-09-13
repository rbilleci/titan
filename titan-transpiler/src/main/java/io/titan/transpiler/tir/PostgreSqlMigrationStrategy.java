package io.titan.transpiler.tir;

final class PostgreSqlMigrationStrategy implements MigrationStrategy {
    @Override
    public String routineDropStatement(String schema, String name, RoutineKind kind) {
        return "-- PostgreSQL uses CREATE OR REPLACE for " + kind.name().toLowerCase();
    }
}
