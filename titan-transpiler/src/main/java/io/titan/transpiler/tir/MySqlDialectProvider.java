package io.titan.transpiler.tir;

public final class MySqlDialectProvider implements DialectProvider {

    private static final SqlEmitter EMITTER = new SqlEmitter() {
        // B-10: the registry is per-bundle state held on this facade; every fresh MySqlEmitter
        // created below is seeded with it so a boolean-returning routine call coerces to text.
        private java.util.Map<String, TirType> routineReturnTypes = java.util.Map.of();

        @Override
        public void useRoutineReturnTypes(java.util.Map<String, TirType> routineReturnTypesBySqlName) {
            this.routineReturnTypes = routineReturnTypesBySqlName == null
                    ? java.util.Map.of()
                    : java.util.Map.copyOf(routineReturnTypesBySqlName);
        }

        private MySqlEmitter newEmitter() {
            MySqlEmitter emitter = new MySqlEmitter();
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

        @Override
        public java.util.Optional<CronApproximation> scheduledJobCronApproximation(String cron) {
            return MySqlEmitter.cronApproximationWarning(cron).map(message -> new CronApproximation(
                    message,
                    "Use a cron shape the MySQL event scheduler represents natively "
                            + "(every minute, */N minutes, hourly at :MM, daily at HH:MM, "
                            + "monthly on day D at HH:MM) or schedule the job externally"));
        }
    };

    private static final TypeMapper TYPE_MAPPER = new MySqlTypeMapper();
    private static final RuntimeStrategy RUNTIME = new MySqlRuntimeStrategy();
    private static final MigrationStrategy MIGRATION = new MySqlMigrationStrategy();

    private static final DialectCapabilities CAPABILITIES = new DialectCapabilities(
            DialectId.MYSQL,
            "MySQL",
            DialectCapabilities.Capability.unsupported(
                    "Re-read the affected rows with a separate SELECT instead of RETURNING "
                            + "(emulating RETURNING on MySQL would change locking semantics), "
                            + "or remove mysql from the transpile targets"),
            DialectCapabilities.Capability.unsupported(
                    "Rewrite the query as a UNION of the LEFT JOIN and RIGHT JOIN results, "
                            + "or remove mysql from the transpile targets"),
            DialectCapabilities.Capability.unsupported(
                    "Build the string with '+' concatenation instead of String.format "
                            + "(MySQL has no placeholder-substituting FORMAT function), "
                            + "or remove mysql from the transpile targets"),
            DialectCapabilities.Capability.supportedSince("8.0.31"),
            // Views are emitted as CREATE SQL SECURITY INVOKER VIEW — supported on every MySQL
            // version Titan targets (the clause predates MySQL 8.0), so no version gate.
            DialectCapabilities.Capability.supported(),
            // MySQL identifiers cap at 64 chars; routines cannot overload, so overloaded Java
            // methods get parameter-type-mangled SQL names.
            new DialectCapabilities.NamingRules(64, false),
            // E-13: Instant-like types map to TIMESTAMP(6) with its 1970..2038 range.
            new DialectCapabilities.TimestampTzRangeCaveat(
                    "Instant/ZonedDateTime/OffsetDateTime maps to MySQL TIMESTAMP(6), which only represents "
                            + "1970-01-01 00:00:01 .. 2038-01-19 03:14:07 UTC",
                    "Use LocalDateTime (MySQL DATETIME(6), range 1000-01-01 .. 9999-12-31) if values outside "
                            + "the TIMESTAMP range can occur"));

    @Override
    public DialectId id() {
        return DialectId.MYSQL;
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
        return new MySqlEmitter();
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
