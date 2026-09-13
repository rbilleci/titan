package io.titan.transpiler.tir;

import io.titan.transpiler.tir.Block;
import io.titan.transpiler.tir.TirType;
import java.util.Objects;

public final class DialectDispatchingEmitter {
    private final DialectProviders providers;

    public DialectDispatchingEmitter() {
        this(new DialectProviders());
    }

    public DialectDispatchingEmitter(DialectProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /** Structured artifact descriptors for one dialect (plan 4.4, audit G-10). */
    public ArtifactDescriber describer(DialectId dialectId) {
        return providers.require(dialectId).artifactDescriber();
    }

    /**
     * Registers the SQL routine name -> return type map for one dialect's emitter (B-10): lets the
     * emitter recognize that a FunctionCallExpression to a generated routine yields a boolean and
     * coerce it to 'true'/'false' text. Set before any body is emitted so a routine that calls a
     * later-emitted routine still resolves the callee's type.
     */
    public void useRoutineReturnTypes(DialectId dialectId, java.util.Map<String, TirType> routineReturnTypesBySqlName) {
        providers.require(dialectId).emitter().useRoutineReturnTypes(routineReturnTypesBySqlName);
    }

    public String emitProcedure(DialectId dialectId, String schema, String name, SecurityMode securityMode, Block body) {
        return emitProcedure(dialectId, schema, name, securityMode, body, false);
    }

    public String emitProcedure(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            java.util.List<RoutineParameter> routineParameters
    ) {
        return emitProcedure(dialectId, schema, name, securityMode, body, routineParameters, false);
    }

    public String emitProcedure(DialectId dialectId, String schema, String name, SecurityMode securityMode, Block body, boolean observability) {
        return emitProcedure(dialectId, schema, name, securityMode, body, observability, java.util.List.of());
    }

    public String emitProcedure(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitProcedure(dialectId, schema, name, securityMode, body, routineParameters, observability, java.util.List.of());
    }

    public String emitProcedure(DialectId dialectId, String schema, String name, SecurityMode securityMode, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return providers.require(dialectId).emitter().emitProcedure(schema, name, securityMode, body, observability, sensitiveColumnsAccessed);
    }

    public String emitProcedure(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability,
            java.util.List<String> sensitiveColumnsAccessed
    ) {
        return providers.require(dialectId).emitter().emitProcedure(schema, name, securityMode, body, routineParameters, observability, sensitiveColumnsAccessed);
    }

    public String emitFunction(DialectId dialectId, String schema, String name, SecurityMode securityMode, TirType returnType, Block body) {
        return emitFunction(dialectId, schema, name, securityMode, returnType, body, false);
    }

    public String emitFunction(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters
    ) {
        return emitFunction(dialectId, schema, name, securityMode, returnType, body, routineParameters, false);
    }

    public String emitFunction(DialectId dialectId, String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability) {
        return emitFunction(dialectId, schema, name, securityMode, returnType, body, observability, java.util.List.of());
    }

    public String emitFunction(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability
    ) {
        return emitFunction(dialectId, schema, name, securityMode, returnType, body, routineParameters, observability, java.util.List.of());
    }

    public String emitFunction(DialectId dialectId, String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return providers.require(dialectId).emitter().emitFunction(schema, name, securityMode, returnType, body, observability, sensitiveColumnsAccessed);
    }

    public String emitFunction(
            DialectId dialectId,
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            java.util.List<RoutineParameter> routineParameters,
            boolean observability,
            java.util.List<String> sensitiveColumnsAccessed
    ) {
        return providers.require(dialectId).emitter().emitFunction(schema, name, securityMode, returnType, body, routineParameters, observability, sensitiveColumnsAccessed);
    }

    public String emitTrigger(DialectId dialectId, String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body) {
        return emitTrigger(dialectId, schema, name, securityMode, trigger, body, java.util.List.of());
    }

    public String emitTrigger(DialectId dialectId, String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body, java.util.List<String> sensitiveColumnsAccessed) {
        return providers.require(dialectId).emitter().emitTrigger(schema, name, securityMode, trigger, body, sensitiveColumnsAccessed);
    }

    public String emitScheduledJob(DialectId dialectId, String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body) {
        return emitScheduledJob(dialectId, schema, name, securityMode, scheduledJob, body, false);
    }

    public String emitScheduledJob(DialectId dialectId, String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability) {
        return emitScheduledJob(dialectId, schema, name, securityMode, scheduledJob, body, observability, java.util.List.of());
    }

    public String emitScheduledJob(DialectId dialectId, String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
        return providers.require(dialectId).emitter().emitScheduledJob(schema, name, securityMode, scheduledJob, body, observability, sensitiveColumnsAccessed);
    }

    public java.util.Optional<SqlEmitter.CronApproximation> scheduledJobCronApproximation(DialectId dialectId, String cron) {
        return providers.require(dialectId).emitter().scheduledJobCronApproximation(cron);
    }

    public String emitView(DialectId dialectId, String schema, String name, String sqlBody) {
        return providers.require(dialectId).emitter().emitView(schema, name, sqlBody);
    }

    public String emitEnumLookup(DialectId dialectId, String schema, EnumLookupSpec enumLookupSpec) {
        return providers.require(dialectId).emitter().emitEnumLookup(schema, enumLookupSpec);
    }

    public String emitRecordModel(DialectId dialectId, String schema, RecordModelSpec recordModelSpec) {
        return providers.require(dialectId).emitter().emitRecordModel(schema, recordModelSpec);
    }
}
