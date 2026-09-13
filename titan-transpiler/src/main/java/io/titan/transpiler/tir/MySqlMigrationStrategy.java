package io.titan.transpiler.tir;

final class MySqlMigrationStrategy implements MigrationStrategy {
    @Override
    public String routineDropStatement(String schema, String name, RoutineKind kind) {
        String qualified = (schema == null || schema.isBlank()) ? name : schema + "." + name;
        return "DROP " + kind.name() + " IF EXISTS " + qualified + ";";
    }
}
