package io.titan.transpiler.tir;

public final class PostgreSqlDialectProvider implements DialectProvider {

    private static final SqlEmitter EMITTER = new SqlEmitter() {
        // B-10: per-bundle routine return types, seeded into every fresh PostgreSqlEmitter so the
        // boolean-to-text predicate is symmetric with MySQL (PostgreSQL's coercion is a no-op —
        // boolean::text already renders 'true'/'false' — but the classification must match).
        private java.util.Map<String, TirType> routineReturnTypes = java.util.Map.of();

        @Override
        public void useRoutineReturnTypes(java.util.Map<String, TirType> routineReturnTypesBySqlName) {
            this.routineReturnTypes = routineReturnTypesBySqlName == null
                    ? java.util.Map.of()
                    : java.util.Map.copyOf(routineReturnTypesBySqlName);
        }

        private PostgreSqlEmitter newEmitter() {
            PostgreSqlEmitter emitter = new PostgreSqlEmitter();
            emitter.useRoutineReturnTypes(routineReturnTypes);
            return emitter;
        }

        @Override
        public String emitProcedure(String schema, String name, SecurityMode securityMode, Block body) {
            return newEmitter().emitProcedure(schema, name, securityMode, body);
        }

        @Override
        public String emitProcedure(
                String schema,
                String name,
                SecurityMode securityMode,
                Block body,
                java.util.List<RoutineParameter> routineParameters
        ) {
            return newEmitter().emitProcedure(schema, name, securityMode, body, routineParameters);
        }

        @Override
        public String emitProcedure(String schema, String name, SecurityMode securityMode, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
            return newEmitter().emitProcedure(schema, name, securityMode, body, observability, sensitiveColumnsAccessed);
        }

        @Override
        public String emitProcedure(
                String schema,
                String name,
                SecurityMode securityMode,
                Block body,
                java.util.List<RoutineParameter> routineParameters,
                boolean observability,
                java.util.List<String> sensitiveColumnsAccessed
        ) {
            return newEmitter().emitProcedure(schema, name, securityMode, body, routineParameters, observability, sensitiveColumnsAccessed);
        }

        @Override
        public String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body) {
            return newEmitter().emitFunction(schema, name, securityMode, returnType, body);
        }

        @Override
        public String emitFunction(
                String schema,
                String name,
                SecurityMode securityMode,
                TirType returnType,
                Block body,
                java.util.List<RoutineParameter> routineParameters
        ) {
            return newEmitter().emitFunction(schema, name, securityMode, returnType, body, routineParameters);
        }

        @Override
        public String emitFunction(String schema, String name, SecurityMode securityMode, TirType returnType, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
            return newEmitter().emitFunction(schema, name, securityMode, returnType, body, observability, sensitiveColumnsAccessed);
        }

        @Override
        public String emitFunction(
                String schema,
                String name,
                SecurityMode securityMode,
                TirType returnType,
                Block body,
                java.util.List<RoutineParameter> routineParameters,
                boolean observability,
                java.util.List<String> sensitiveColumnsAccessed
        ) {
            return newEmitter().emitFunction(schema, name, securityMode, returnType, body, routineParameters, observability, sensitiveColumnsAccessed);
        }

        @Override
        public String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body) {
            return newEmitter().emitTrigger(schema, name, securityMode, trigger, body);
        }

        @Override
        public String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body, java.util.List<String> sensitiveColumnsAccessed) {
            return newEmitter().emitTrigger(schema, name, securityMode, trigger, body);
        }

        @Override
        public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body) {
            return newEmitter().emitScheduledJob(schema, name, securityMode, scheduledJob, body);
        }

        @Override
        public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, java.util.List<String> sensitiveColumnsAccessed) {
            return newEmitter().emitScheduledJob(schema, name, securityMode, scheduledJob, body, observability, sensitiveColumnsAccessed);
        }

        @Override
        public String emitView(String schema, String name, String sqlBody) {
            return newEmitter().emitView(schema, name, sqlBody);
        }

        @Override
        public String emitEnumLookup(String schema, EnumLookupSpec enumLookupSpec) {
            return newEmitter().emitEnumLookup(schema, enumLookupSpec);
        }

        @Override
        public String emitRecordModel(String schema, RecordModelSpec recordModelSpec) {
            return newEmitter().emitRecordModel(schema, recordModelSpec);
        }
    };

    private static final TypeMapper TYPE_MAPPER = new PostgreSqlTypeMapper();
    private static final RuntimeStrategy RUNTIME = new PostgreSqlRuntimeStrategy();
    private static final MigrationStrategy MIGRATION = new PostgreSqlMigrationStrategy();

    private static final DialectCapabilities CAPABILITIES = new DialectCapabilities(
            DialectId.POSTGRESQL,
            "PostgreSQL",
            DialectCapabilities.Capability.supported(),
            DialectCapabilities.Capability.supported(),
            DialectCapabilities.Capability.supported(),
            DialectCapabilities.Capability.supported(),
            // Every emitted view carries WITH (security_invoker = true), a view option that
            // exists only from PostgreSQL 15 onward — the documented compatibility floor.
            // Deploys to PG <= 14 fail at CREATE VIEW time, so targeting postgresql with
            // @ViewDefinition artifacts draws a compile-time TITAN-W005 naming the floor.
            DialectCapabilities.Capability.supportedSince("15"),
            // NAMEDATALEN-1; PostgreSQL overloads routines natively, so no name mangling.
            new DialectCapabilities.NamingRules(63, true),
            // TIMESTAMPTZ covers the full Java instant range: no caveat.
            null);

    @Override
    public DialectId id() {
        return DialectId.POSTGRESQL;
    }

    @Override
    public DialectCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public SqlEmitter emitter() {
        return EMITTER;
    }

    @Override
    public ArtifactDescriber artifactDescriber() {
        // The emitter class is the describer so descriptors and rendered SQL cannot drift;
        // a fresh instance because emitters are stateful.
        return new PostgreSqlEmitter();
    }

    @Override
    public TypeMapper typeMapper() {
        return TYPE_MAPPER;
    }

    @Override
    public RuntimeStrategy runtimeStrategy() {
        return RUNTIME;
    }

    @Override
    public MigrationStrategy migrationStrategy() {
        return MIGRATION;
    }
}
