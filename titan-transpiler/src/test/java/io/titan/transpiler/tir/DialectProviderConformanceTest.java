package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.Block;
import io.titan.transpiler.tir.ReturnStatement;
import io.titan.transpiler.tir.TIntType;
import io.titan.transpiler.tir.TNumericType;
import io.titan.transpiler.tir.TTextType;
import io.titan.transpiler.tir.TTimestampType;
import io.titan.transpiler.tir.TVoidType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DialectProviderConformanceTest {

    @ParameterizedTest(name = "{0} provider exposes required strategy surfaces")
    @MethodSource("dialectProviders")
    void providerExposesCoreContracts(DialectId dialectId, DialectProvider provider) {
        assertEquals(dialectId, provider.id());
        assertNotNull(provider.emitter());
        assertNotNull(provider.typeMapper());
        assertNotNull(provider.runtimeStrategy());
        assertNotNull(provider.migrationStrategy());

        assertFalse(provider.runtimeStrategy().runtimeNamespace().isBlank());
        assertFalse(provider.runtimeStrategy().runtimeMigrationSql().isBlank());
    }

    @ParameterizedTest(name = "{0} provider emits procedure/function wrappers")
    @MethodSource("dialectProviders")
    void providerEmitterProducesRoutineSql(DialectId dialectId, DialectProvider provider) {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String procedureSql = provider.emitter().emitProcedure("app", "noop", SecurityMode.INVOKER, body);
        String functionSql = provider.emitter().emitFunction("app", "identity", SecurityMode.INVOKER, new TIntType(), body);

        if (dialectId == DialectId.POSTGRESQL) {
            assertTrue(procedureSql.contains("\"app\".\"noop\""));
            assertTrue(functionSql.contains("\"app\".\"identity\""));
        } else {
            assertTrue(procedureSql.contains("`app`.`noop`"));
            assertTrue(functionSql.contains("`app`.`identity`"));
        }

        if (dialectId == DialectId.POSTGRESQL) {
            assertTrue(procedureSql.contains("CREATE OR REPLACE PROCEDURE"));
            assertTrue(functionSql.contains("RETURNS INTEGER"));
            assertTrue(functionSql.contains("LANGUAGE plpgsql"));
        } else {
            assertTrue(procedureSql.contains("CREATE PROCEDURE"));
            assertTrue(functionSql.contains("CREATE FUNCTION"));
            assertTrue(functionSql.contains("RETURNS INT"));
            assertTrue(procedureSql.contains("DELIMITER $$"));
        }
    }

    @ParameterizedTest(name = "{0} provider type mapping is dialect-compatible")
    @MethodSource("dialectProviders")
    void providerTypeMapperSupportsCommonTirTypes(DialectId dialectId, DialectProvider provider) {
        String intType = provider.typeMapper().toSqlType(new TIntType());
        String textType = provider.typeMapper().toSqlType(new TTextType());
        String numericType = provider.typeMapper().toSqlType(new TNumericType(12, 2));
        String timestampType = provider.typeMapper().toSqlType(new TTimestampType());
        String voidType = provider.typeMapper().toSqlType(new TVoidType());

        assertFalse(intType.isBlank());
        assertFalse(textType.isBlank());
        assertFalse(numericType.isBlank());
        assertFalse(timestampType.isBlank());
        assertEquals("VOID", voidType);

        if (dialectId == DialectId.POSTGRESQL) {
            assertEquals("INTEGER", intType);
            assertEquals("NUMERIC(12,2)", numericType);
            assertEquals("TIMESTAMP", timestampType);
        } else {
            assertEquals("INT", intType);
            assertEquals("DECIMAL(12,2)", numericType);
            // E-13: (6) fractional precision so Java microseconds survive the mapping.
            assertEquals("DATETIME(6)", timestampType);
        }
    }

    @ParameterizedTest(name = "{0} provider migration/runtime semantics are consistent")
    @MethodSource("dialectProviders")
    void providerRuntimeAndMigrationSemanticsAreConsistent(DialectId dialectId, DialectProvider provider) {
        String dropProcedure = provider.migrationStrategy()
                .routineDropStatement("app", "job", MigrationStrategy.RoutineKind.PROCEDURE);
        assertNotNull(dropProcedure);
        assertFalse(dropProcedure.isBlank());

        String runtimeMigrationSql = provider.runtimeStrategy().runtimeMigrationSql();
        if (dialectId == DialectId.POSTGRESQL) {
            assertTrue(provider.runtimeStrategy().supportsNativeArrays());
            assertEquals("titan_runtime", provider.runtimeStrategy().runtimeNamespace());
            assertTrue(dropProcedure.contains("CREATE OR REPLACE"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.java_mod"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.java_round"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.java_int_add"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.java_int_sub"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.java_int_mul"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.list_add"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.list_get"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.list_size"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.list_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.list_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_put"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_get"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_contains_key"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_keys"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.map_size"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.set_add"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.set_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.set_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.set_size"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.static_get"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.static_set"));
            assertTrue(runtimeMigrationSql.contains("titan_runtime.static_reset"));
        } else {
            assertFalse(provider.runtimeStrategy().supportsNativeArrays());
            assertEquals("titan_rt", provider.runtimeStrategy().runtimeNamespace());
            assertEquals("DROP PROCEDURE IF EXISTS app.job;", dropProcedure);
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_mod"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_int_div"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_int_add"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_int_sub"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_int_mul"));
            assertTrue(runtimeMigrationSql.contains("SIGNAL SQLSTATE '22012'"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_java_round"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_add"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_get"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_size"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_init"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_add"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_get"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_size"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_list_temp_cleanup"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_put"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_get"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_contains_key"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_keys"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_map_size"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_add"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_size"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_init"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_add"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_contains"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_remove"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_size"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_set_temp_cleanup"));
            assertTrue(runtimeMigrationSql.contains("UNIQUE KEY uq_titan_rt_set_tmp_value (set_id, item_hash)"));
            assertTrue(runtimeMigrationSql.contains("item_hash CHAR(64) NOT NULL"));
            assertTrue(runtimeMigrationSql.contains("SHA2(element_value, 256)"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_static_get"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_static_set"));
            assertTrue(runtimeMigrationSql.contains("titan_rt_static_reset"));
        }
    }

    @ParameterizedTest(name = "{0} provider emits scheduled jobs with dialect-specific scheduling syntax")
    @MethodSource("dialectProviders")
    void providerEmitterSupportsScheduledJobContracts(DialectId dialectId, DialectProvider provider) {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());
        ScheduledJobSpec scheduledJob = new ScheduledJobSpec("0 2 * * *", "nightly_cleanup");

        String sql = provider.emitter().emitScheduledJob("app", "nightly_cleanup", SecurityMode.INVOKER, scheduledJob, body);

        if (dialectId == DialectId.POSTGRESQL) {
            assertTrue(sql.contains("\"app\".\"nightly_cleanup\""));
            assertTrue(sql.contains("cron.schedule"));
            assertTrue(sql.contains("CALL \"app\".\"nightly_cleanup\"()"));
        } else {
            assertTrue(sql.contains("`app`.`nightly_cleanup`"));
            assertTrue(sql.contains("CREATE EVENT `nightly_cleanup`"));
            assertTrue(sql.contains("DO CALL `app`.`nightly_cleanup`()"));
        }
    }

    @ParameterizedTest(name = "{0} provider supports shared object emission contracts")
    @MethodSource("dialectProviders")
    void providerEmitterSupportsSharedObjectContracts(DialectId dialectId, DialectProvider provider) {
        String viewSql = provider.emitter().emitView("app", "active_accounts", "SELECT * FROM accounts WHERE active = TRUE");
        String enumSql = provider.emitter().emitEnumLookup(
                "app",
                new EnumLookupSpec(
                        "AccountState",
                        List.of(new EnumLookupSpec.EnumField("display_name", "String")),
                        List.of(new EnumLookupSpec.EnumMethod("getDisplayName", "String")),
                        List.of(new EnumLookupSpec.EnumValue("ACTIVE", List.of("Active")))));
        String recordSql = provider.emitter().emitRecordModel(
                "app",
                new RecordModelSpec(
                        "AccountSnapshot",
                        List.of(new RecordModelSpec.RecordField("id", "long"), new RecordModelSpec.RecordField("email", "String"))));

        assertTrue(viewSql.contains("active_accounts"));
        assertTrue(enumSql.contains("__enum_account_state"));
        assertTrue(recordSql.contains("__record_account_snapshot"));

        if (dialectId == DialectId.POSTGRESQL) {
            assertTrue(viewSql.contains("CREATE OR REPLACE VIEW"));
            assertTrue(enumSql.contains("ON CONFLICT (\"ordinal\") DO UPDATE"));
            assertTrue(recordSql.contains("CREATE TYPE"));
        } else {
            assertTrue(viewSql.contains("DROP VIEW IF EXISTS"));
            assertTrue(enumSql.contains("ON DUPLICATE KEY UPDATE"));
            assertTrue(recordSql.contains("RETURNS JSON"));
        }
    }

    @Test
    void recordWithUuidFieldEmitsNativeUuidOnPostgresAndCharThirtySixOnMysql() {
        // Regression: the record-field mappers are string-keyed and separate from the scalar TIR mappers;
        // without a UUID case a record's UUID field silently emitted TEXT (audit round-2 finding). It must
        // match the scalar decision — PG native uuid, MySQL CHAR(36).
        DialectProviders providers = new DialectProviders();
        RecordModelSpec spec = new RecordModelSpec(
                "AccountRef",
                List.of(new RecordModelSpec.RecordField("extId", "UUID"),
                        new RecordModelSpec.RecordField("id", "long")));

        String pgSql = providers.require(DialectId.POSTGRESQL).emitter().emitRecordModel("app", spec);
        assertTrue(pgSql.toUpperCase(java.util.Locale.ROOT).contains("UUID"),
                "a UUID record field must emit PostgreSQL's native uuid, not TEXT; was:\n" + pgSql);

        String mySql = providers.require(DialectId.MYSQL).emitter().emitRecordModel("app", spec);
        assertTrue(mySql.contains("CHAR(36)"),
                "a UUID record field must emit CHAR(36) on MySQL, not TEXT; was:\n" + mySql);
    }

    private static Stream<Arguments> dialectProviders() {
        DialectProviders providers = new DialectProviders();
        return Stream.of(
                Arguments.of(DialectId.POSTGRESQL, providers.require(DialectId.POSTGRESQL)),
                Arguments.of(DialectId.MYSQL, providers.require(DialectId.MYSQL)));
    }
}
