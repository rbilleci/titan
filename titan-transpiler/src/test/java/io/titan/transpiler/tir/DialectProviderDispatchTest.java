package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.Block;
import io.titan.transpiler.tir.ReturnStatement;
import io.titan.transpiler.tir.TIntType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialectProviderDispatchTest {

    @Test
    void dispatchesToPostgresAndMysqlEmittersThroughProviderRegistry() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());
        DialectDispatchingEmitter dispatchingEmitter = new DialectDispatchingEmitter();

        String pgSql = dispatchingEmitter.emitProcedure(DialectId.POSTGRESQL, "app", "noop", SecurityMode.INVOKER, body);
        String mysqlSql = dispatchingEmitter.emitProcedure(DialectId.MYSQL, "app", "noop", SecurityMode.INVOKER, body);

        assertTrue(pgSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"noop\"()"));
        assertTrue(pgSql.contains("LANGUAGE plpgsql"));
        assertTrue(mysqlSql.contains("DELIMITER $$"));
        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`noop`()"));
    }

    @Test
    void exposesDialectSpecificTypeRuntimeAndMigrationStrategies() {
        DialectProviders providers = new DialectProviders();
        DialectProvider pg = providers.require(DialectId.POSTGRESQL);
        DialectProvider mysql = providers.require(DialectId.MYSQL);

        assertEquals("INTEGER", pg.typeMapper().toSqlType(new TIntType()));
        assertEquals("INT", mysql.typeMapper().toSqlType(new TIntType()));
        assertEquals("titan_runtime", pg.runtimeStrategy().runtimeNamespace());
        assertEquals("titan_rt", mysql.runtimeStrategy().runtimeNamespace());
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.java_mod"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.java_int_add"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.list_add"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.set_add"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.static_get"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.static_set"));
        assertTrue(pg.runtimeStrategy().runtimeMigrationSql().contains("titan_runtime.static_reset"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_java_mod"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_java_int_div"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_java_int_add"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_java_int_sub"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_java_int_mul"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_list_add"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_list_temp_init"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_list_temp_add"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_set_add"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_set_temp_init"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("UNIQUE KEY uq_titan_rt_set_tmp_value (set_id, item_hash)"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("item_hash CHAR(64) NOT NULL"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_static_get"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_static_set"));
        assertTrue(mysql.runtimeStrategy().runtimeMigrationSql().contains("titan_rt_static_reset"));
        assertEquals(
                "DROP PROCEDURE IF EXISTS app.noop;",
                mysql.migrationStrategy().routineDropStatement("app", "noop", MigrationStrategy.RoutineKind.PROCEDURE));
        assertTrue(pg.migrationStrategy()
                .routineDropStatement("app", "noop", MigrationStrategy.RoutineKind.FUNCTION)
                .contains("CREATE OR REPLACE"));
    }
}
