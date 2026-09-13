package io.titan.transpiler.tir;

import io.titan.transpiler.tir.Block;
import io.titan.transpiler.tir.TirType;

public interface SqlEmitter {
    /**
     * Registers the generated routines' return types by SQL name so the emitter can coerce a
     * boolean-returning routine call to 'true'/'false' text (B-10 / TG-BLK-012). Default no-op for
     * emitters that do not track call-site types; {@code AbstractSqlEmitter} overrides it.
     */
    default void useRoutineReturnTypes(java.util.Map<String, TirType> routineReturnTypesBySqlName) {
    }

    String emitProcedure(String schema, String name, SecurityMode securityMode, Block body);

    default String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, java.util.List<RoutineParameter> routineParameters) {
        return emitProcedure(schema, name, securityMode, body);
    }

    default String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, boolean observability) {
        return emitProcedure(schema, name, securityMode, body);
    }

    default String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitProcedure(schema, name, securityMode, body, routineParameters);
    }

    default String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return emitProcedure(schema, name, securityMode, body, observability);
    }

    default String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability,
            java.util.List<String> sensitiveColumnsAccessed
    ) {
        return emitProcedure(schema, name, securityMode, body, routineParameters, observability);
    }

    String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body);

    default String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters
    ) {
        return emitFunction(schema, name, securityMode, returnType, body);
    }

    default String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability) {
        return emitFunction(schema, name, securityMode, returnType, body);
    }

    default String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitFunction(schema, name, securityMode, returnType, body, routineParameters);
    }

    default String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return emitFunction(schema, name, securityMode, returnType, body, observability);
    }

    default String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability,
            java.util.List<String> sensitiveColumnsAccessed
    ) {
        return emitFunction(schema, name, securityMode, returnType, body, routineParameters, observability);
    }

    String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body);

    default String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body, java.util.List<String> sensitiveColumnsAccessed) {
        return emitTrigger(schema, name, securityMode, trigger, body);
    }

    default String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body) {
        return emitProcedure(schema, name, securityMode, body);
    }

    default String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability) {
        return emitScheduledJob(schema, name, securityMode, scheduledJob, body);
    }

    default String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return emitScheduledJob(schema, name, securityMode, scheduledJob, body, observability);
    }

    /**
     * Plan 3.4: lossy scheduled-job cron approximation, surfaced as a compile-time warning.
     *
     * <p>Dialects whose scheduler cannot represent the given cron expression natively return the
     * approximation they will emit ({@code message} names the original cron and the
     * approximation; {@code suggestion} names the natively representable shapes). Dialects that
     * schedule the exact cron return {@link java.util.Optional#empty()} (the default).</p>
     */
    default java.util.Optional<CronApproximation> scheduledJobCronApproximation(String cron) {
        return java.util.Optional.empty();
    }

    /** A lossy cron approximation: warning text plus the dialect's rewrite suggestion. */
    record CronApproximation(String message, String suggestion) {}

    default String emitView(String schema, String name, String sqlBody) {
        throw new UnsupportedOperationException("View emission is not implemented for this dialect.");
    }

    default String emitEnumLookup(String schema, EnumLookupSpec enumLookupSpec) {
        throw new UnsupportedOperationException("Enum lookup emission is not implemented for this dialect.");
    }

    default String emitRecordModel(String schema, RecordModelSpec recordModelSpec) {
        throw new UnsupportedOperationException("Record model emission is not implemented for this dialect.");
    }
}
