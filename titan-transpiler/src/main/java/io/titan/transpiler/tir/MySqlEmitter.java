package io.titan.transpiler.tir;

import io.titan.transpiler.NamingConventionEngine;
import io.titan.transpiler.emit.CodeBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Baseline MySQL emitter for P0 TIR statements.
 */
public final class MySqlEmitter extends AbstractSqlEmitter {
    private static final String RUNTIME_FUNCTION_PREFIX = new MySqlRuntimeStrategy().runtimeNamespace() + "_";

    // TODO(plan 1.3): the telemetry table lives in the fixed `titan_runtime` schema, which is not
    // derivable from MySqlRuntimeStrategy.runtimeNamespace() ("titan_rt" — the routine-name
    // prefix); route it through RuntimeStrategy once the strategy exposes the runtime schema
    // separately from the routine prefix.
    private static final String TELEMETRY_TABLE = "titan_runtime.telemetry";

    // E-8 (plan 3.1): every procedure/function pins the session to UTC ("SET time_zone =
    // '+00:00'") for deterministic temporal semantics, so the caller's zone must be restored on
    // every exit path. Mechanism: the caller's @@session.time_zone is saved into the DECLAREd
    // routine-local variable below (a local, not a session @variable, so nested generated
    // routines cannot clobber each other's saved value) and restored (a) at the routine tail,
    // (b) before every RETURN — the return value is staged into __titan_return_value first so
    // the expression still evaluates under UTC — and (c) inside the single routine-level EXIT
    // handler, which every procedure/function now declares, before its RESIGNAL.
    private static final String SAVED_TIME_ZONE_VARIABLE = "__titan_saved_time_zone";
    private static final String RETURN_VALUE_VARIABLE = "__titan_return_value";

    private final Map<String, String> temporalAmountUnits = new HashMap<>();
    private final Map<String, TirType> temporalAmountTypes = new HashMap<>();
    private TirType currentFunctionReturnType = null;
    private boolean debugLogEnabled = false;
    /** True while emitting a procedure/function body (which pins and must restore time_zone). */
    private boolean sessionTimeZoneScoped = false;
    /**
     * Label of the enclosing void-procedure body block, or {@code null} when not in one. MySQL
     * stored PROCEDUREs do not support {@code RETURN} (it is function-only), so an early
     * {@code return;} in a procedure must {@code LEAVE} this labeled body block and fall through to
     * the routine epilogue (time-zone restore / static resets / telemetry), matching PL/pgSQL's
     * RETURN-runs-the-epilogue semantics. Only set when the body actually contains an early void
     * return, so procedures without one keep their previous unlabeled BEGIN (no golden churn).
     */
    private String procedureExitLabel = null;

    // MySQL LEAVE/ITERATE always require a loop label, so every loop construct emits one and
    // unlabeled BreakStatement/ContinueStatement bind to the innermost enclosing loop's label.
    // Labels are allocated from a per-routine counter in emission order, which is deterministic
    // for a given TIR input. MySQL labels live in their own namespace, so they cannot collide
    // with variables; the counter keeps them unique among nested labels.
    private final java.util.ArrayDeque<String> loopLabelStack = new java.util.ArrayDeque<>();
    private int loopLabelSequence = 0;

    private String pushLoopLabel(String explicitLabel) {
        String label = (explicitLabel == null || explicitLabel.isBlank())
                ? "titan_loop_" + (++loopLabelSequence)
                : explicitLabel;
        loopLabelStack.push(label);
        return label;
    }

    private void popLoopLabel() {
        loopLabelStack.pop();
    }

    private void resetLoopLabels() {
        loopLabelStack.clear();
        loopLabelSequence = 0;
    }

    public MySqlEmitter() {
        this(new NamingConventionEngine());
    }

    public MySqlEmitter(NamingConventionEngine namingConventionEngine) {
        super(namingConventionEngine);
    }

    private static String runtimeFunction(String name) {
        return RUNTIME_FUNCTION_PREFIX + name;
    }

    @Override
    public String emitProcedure(
            String schema,
            String name,
            SecurityMode securityMode,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability,
            List<String> sensitiveColumnsAccessed
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        out = new CodeBuffer();
        rawSqlCounter = 0;
        temporalAmountUnits.clear();
        temporalAmountTypes.clear();
        resetLoopLabels();
        currentFunctionReturnType = null;
        observabilityEnabled = observability;
        staticResetKeys = collectStaticResetKeys(body);
        this.routineParameters = List.copyOf(routineParameters == null ? List.of() : routineParameters);
        debugLogEnabled = containsDebugPrint(body);
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                this.routineParameters,
                body,
                reservedRoutineIdentifiers(observability)
        );
        this.routineParameters = routineSqlNameAllocator.routineParameters();
        registerRoutineTemporalAmountTypes(this.routineParameters);
        validateMySqlRoutineBoundaryTypes(name, this.routineParameters, null);
        sessionTimeZoneScoped = true;
        // MySQL procedures cannot RETURN; an early void return must LEAVE a labeled body block so
        // it falls through to the epilogue (static resets / debug cleanup / telemetry / time-zone
        // restore). Only allocate the label when the body actually has an early void return, so
        // procedures without one keep their previous unlabeled BEGIN (no golden churn).
        procedureExitLabel = containsVoidReturn(body.statements())
                ? routineSqlNameAllocator.allocateGeneratedName("proc_body")
                : null;
        String qualified = qualify(schema, name);

        out.line("DELIMITER $$");
        out.line("DROP PROCEDURE IF EXISTS " + qualified + "$$");
        out.line("CREATE PROCEDURE " + qualified + "(" + routineParameterSignature(false) + ")");
        out.line("SQL SECURITY " + toMySqlSecurityKeyword(securityMode));
        out.line("BEGIN");
        out.indent();
        emitRoutineDeclarations(body, observability, null);
        out.line("SET time_zone = '+00:00';");
        if (debugLogEnabled) {
            out.line("CREATE TEMPORARY TABLE IF NOT EXISTS __debug_log(message TEXT);");
        }
        if (observability) {
            out.line("SET __titan_started_at = CURRENT_TIMESTAMP(6);");
            out.line("INSERT INTO " + TELEMETRY_TABLE + "(procedure_name, started_at, parameters)");
            out.line("VALUES ('" + escape(name) + "', __titan_started_at, " + telemetryPayloadSql(body, sensitiveColumnsAccessed) + ");");
            out.line("SET __titan_telemetry_id = LAST_INSERT_ID();");
        }
        if (procedureExitLabel != null) {
            // A labeled BEGIN...END the early void return LEAVEs; the epilogue runs afterward.
            out.line(procedureExitLabel + ": BEGIN");
            out.indent();
            emitStatements(body.statements());
            out.dedent();
            out.line("END;");
        } else {
            emitStatements(body.statements());
        }
        emitMySqlStaticResets();
        emitMySqlDebugLogCleanup();
        if (observability) {
            out.line("UPDATE " + TELEMETRY_TABLE + " SET");
            out.line("  status = 'success', finished_at = CURRENT_TIMESTAMP(6),");
            out.line("  duration_ms = TIMESTAMPDIFF(MICROSECOND, __titan_started_at, CURRENT_TIMESTAMP(6)) / 1000.0");
            out.line("WHERE id = __titan_telemetry_id;");
        }
        out.line(restoreTimeZoneSql());
        out.dedent();
        out.line("END$$");
        out.line("DELIMITER ;");
        return out.toString();
    }

    @Override
    public String emitFunction(
            String schema,
            String name,
            SecurityMode securityMode,
            TirType returnType,
            Block body,
            List<RoutineParameter> routineParameters,
            boolean observability,
            List<String> sensitiveColumnsAccessed
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(body, "body");
        out = new CodeBuffer();
        rawSqlCounter = 0;
        temporalAmountUnits.clear();
        temporalAmountTypes.clear();
        resetLoopLabels();
        observabilityEnabled = observability;
        staticResetKeys = collectStaticResetKeys(body);
        this.routineParameters = List.copyOf(routineParameters == null ? List.of() : routineParameters);
        debugLogEnabled = containsDebugPrint(body);
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                this.routineParameters,
                body,
                reservedRoutineIdentifiers(observability)
        );
        this.routineParameters = routineSqlNameAllocator.routineParameters();
        registerRoutineTemporalAmountTypes(this.routineParameters);
        currentFunctionReturnType = returnType;
        validateMySqlRoutineBoundaryTypes(name, this.routineParameters, returnType);
        sessionTimeZoneScoped = true;
        // Functions legitimately use RETURN (including void-less early returns of the result), so
        // they never take the procedure LEAVE-a-label path.
        procedureExitLabel = null;
        String qualified = qualify(schema, name);

        out.line("DELIMITER $$");
        out.line("DROP FUNCTION IF EXISTS " + qualified + "$$");
        out.line("CREATE FUNCTION " + qualified + "(" + routineParameterSignature(true) + ")");
        out.line("RETURNS " + sqlType(returnType));
        out.line("SQL SECURITY " + toMySqlSecurityKeyword(securityMode));
        out.line("BEGIN");
        out.indent();
        emitRoutineDeclarations(body, observability, returnType);
        out.line("SET time_zone = '+00:00';");
        if (debugLogEnabled) {
            out.line("CREATE TEMPORARY TABLE IF NOT EXISTS __debug_log(message TEXT);");
        }
        if (observability) {
            out.line("SET __titan_started_at = CURRENT_TIMESTAMP(6);");
            out.line("INSERT INTO " + TELEMETRY_TABLE + "(procedure_name, started_at, parameters)");
            out.line("VALUES ('" + escape(name) + "', __titan_started_at, " + telemetryPayloadSql(body, sensitiveColumnsAccessed) + ");");
            out.line("SET __titan_telemetry_id = LAST_INSERT_ID();");
        }
        emitStatements(body.statements());
        emitMySqlStaticResets();
        emitMySqlDebugLogCleanup();
        if (observability) {
            out.line("UPDATE " + TELEMETRY_TABLE + " SET");
            out.line("  status = 'success', finished_at = CURRENT_TIMESTAMP(6),");
            out.line("  duration_ms = TIMESTAMPDIFF(MICROSECOND, __titan_started_at, CURRENT_TIMESTAMP(6)) / 1000.0");
            out.line("WHERE id = __titan_telemetry_id;");
        }
        out.line(restoreTimeZoneSql());
        out.dedent();
        out.line("END$$");
        out.line("DELIMITER ;");
        return out.toString();
    }

    @Override
    public String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(body, "body");

        // trigger.table() may arrive pre-qualified as "schema.table"; quote part by part.
        String qualifiedTable = trigger.table().contains(".")
                ? quoteQualifiedName(trigger.table())
                : qualify(schema, trigger.table());

        out = new CodeBuffer();
        rawSqlCounter = 0;
        temporalAmountUnits.clear();
        temporalAmountTypes.clear();
        resetLoopLabels();
        observabilityEnabled = false;
        staticResetKeys = collectStaticResetKeys(body);
        routineParameters = List.of();
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                routineParameters,
                body,
                reservedRoutineIdentifiers(false)
        );
        currentFunctionReturnType = null;
        debugLogEnabled = false;
        // Triggers never pin the session time zone (no SET time_zone preamble), so they have no
        // saved-zone variable to restore (E-8 scope: procedures and functions only).
        sessionTimeZoneScoped = false;
        procedureExitLabel = null;
        out.line("DELIMITER $$");

        if (securityMode == SecurityMode.INVOKER) {
            out.line("-- TITAN-W004: MySQL triggers do not support SQL SECURITY INVOKER; emitting DEFINER trigger owned by CURRENT_USER.");
        }

        for (String event : trigger.events()) {
            // TG-BLK-011: the _<event>_trg decoration can push an at-the-limit routine name
            // over the dialect identifier ceiling; describeTrigger composes the same names.
            String triggerName = quoteIdentifier(
                    SqlNames.fitWithinDialectLimits(name + "_" + event.toLowerCase() + "_trg"));
            out.line("DROP TRIGGER IF EXISTS " + triggerName + "$$");
            out.line("CREATE DEFINER = CURRENT_USER TRIGGER " + triggerName);
            out.line(trigger.timing() + " " + event + " ON " + qualifiedTable);
            out.line("FOR EACH " + trigger.forEach());
            out.line("BEGIN");
            out.indent();
            emitDeclarations(body);
            if (!staticResetKeys.isEmpty()) {
                emitMySqlStaticResetExceptionHandler();
            }
            emitStatements(body.statements());
            emitMySqlStaticResets();
            out.dedent();
            out.line("END$$");
        }

        out.line("DELIMITER ;");
        return out.toString();
    }

    @Override
    public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, List<String> sensitiveColumnsAccessed) {
        CronExpressionValidator.validateStandardCron(scheduledJob.cron());
        String procedureSql = emitProcedure(schema, name, securityMode, body, observability, sensitiveColumnsAccessed);
        // TG-BLK-011: the generated _event default is length-limited like every composed name;
        // an explicitly user-specified event name is the user's own identifier and stays as-is.
        String eventName = quoteIdentifier(
                (scheduledJob.name() == null || scheduledJob.name().isBlank())
                        ? SqlNames.fitWithinDialectLimits(name + "_event")
                        : scheduledJob.name());
        String qualifiedProcedure = qualify(schema, name);
        MySqlEventSchedule schedule = mysqlScheduleFromCron(scheduledJob.cron());

        StringBuilder sql = new StringBuilder(procedureSql)
                .append("\n\nDROP EVENT IF EXISTS ").append(eventName).append(";\n");
        if (schedule.warningComment() != null) {
            sql.append("-- ").append(schedule.warningComment()).append("\n");
        }
        sql.append("CREATE EVENT ").append(eventName).append("\n")
                .append("ON SCHEDULE EVERY ").append(schedule.everyExpression()).append(" STARTS ")
                .append(schedule.startsExpression()).append("\n")
                .append("DO CALL ").append(qualifiedProcedure).append("();");
        return sql.toString();
    }

    @Override
    public String emitView(String schema, String name, String sqlBody) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sqlBody, "sqlBody");
        String qualified = qualify(schema, name);
        return "DROP VIEW IF EXISTS " + qualified + ";\n"
                + "CREATE SQL SECURITY INVOKER VIEW " + qualified + " AS\n"
                + sqlBody.trim() + ";";
    }

    @Override
    public String emitEnumLookup(String schema, EnumLookupSpec enumLookupSpec) {
        Objects.requireNonNull(enumLookupSpec, "enumLookupSpec");
        // B-2 (TG-BLK-005): names derive from the enum's source-local qualified name through
        // SqlNames (qualifier join + collision-free "__" member join — rule documented there).
        String tableName = qualify(schema, SqlNames.enumSqlBaseName(enumLookupSpec.enumName()));

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
        sql.append("    `ordinal` INT PRIMARY KEY,\n");
        sql.append("    `name` VARCHAR(191) NOT NULL,\n");
        sql.append("    UNIQUE KEY `uq_enum_name` (`name`)");
        for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
            sql.append(",\n    ").append(quoteIdentifier(field.name())).append(" ").append(toMySqlRecordFieldType(field.typeName()));
        }
        sql.append("\n);");

        if (!enumLookupSpec.values().isEmpty()) {
            sql.append("\n\nINSERT INTO ").append(tableName).append(" (");
            sql.append("`ordinal`, `name`");
            for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
                sql.append(", ").append(quoteIdentifier(field.name()));
            }
            sql.append(") VALUES\n");
            List<String> tuples = new ArrayList<>();
            for (int i = 0; i < enumLookupSpec.values().size(); i++) {
                tuples.add("    (" + renderEnumTuple(i, enumLookupSpec.values().get(i), enumLookupSpec.fields()) + ")");
            }
            sql.append(String.join(",\n", tuples));
            // VALUES(...) is the duplicate-key pseudo-function and stays bare; only its column
            // argument is a real (user-derived) identifier.
            sql.append("\nON DUPLICATE KEY UPDATE `name` = VALUES(`name`)");
            if (!enumLookupSpec.fields().isEmpty()) {
                sql.append(", ");
                sql.append(enumLookupSpec.fields().stream()
                        .map(field -> quoteIdentifier(field.name()) + " = VALUES(" + quoteIdentifier(field.name()) + ")")
                        .collect(Collectors.joining(", ")));
            }
            sql.append(";");
        }

        // B-4 (TG-BLK-004): accessors are deduplicated by id — a no-arg method named exactly
        // after its backing field (the record-style accessor default) used to emit a
        // byte-identical duplicate of the field-derived function, which the inventory's
        // uniqueness gate then failed at package time. describeEnumLookup mirrors this dedupe.
        Set<String> emittedAccessors = new LinkedHashSet<>();
        for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
            String accessorId = SqlNames.enumMemberName(enumLookupSpec.enumName(), field.name());
            if (!emittedAccessors.add(accessorId)) {
                continue;
            }
            appendEnumFieldAccessor(sql, tableName, qualify(schema, accessorId), field.name(), toMySqlRecordFieldType(field.typeName()));
        }

        for (EnumLookupSpec.EnumMethod method : enumLookupSpec.methods()) {
            String backingField = enumBackingFieldForMethod(method.name(), enumLookupSpec.fields());
            if (backingField == null) {
                continue;
            }
            String accessorId = SqlNames.enumMemberName(enumLookupSpec.enumName(), method.name());
            if (!emittedAccessors.add(accessorId)) {
                continue;
            }
            appendEnumFieldAccessor(sql, tableName, qualify(schema, accessorId), backingField, toMySqlRecordFieldType(method.returnTypeName()));
        }

        return sql.toString();
    }

    private void appendEnumFieldAccessor(StringBuilder sql, String tableName, String fnName, String selectedField, String returnType) {
        // Parameter and DECLARE names stay unquoted (plan 1.3b exclusion): they are
        // namingConventionEngine-generated (p_/v_ prefixed, lowercase) so they can never be
        // reserved words, and MySQL does not accept backtick-quoted identifiers uniformly
        // across local-variable positions (DECLARE/SELECT ... INTO).
        String enumKeyParameter = namingConventionEngine.parameterName("enum_key");
        String valueVariable = namingConventionEngine.localVariableName("value");

        sql.append("\n\nDELIMITER $$\n");
        sql.append("DROP FUNCTION IF EXISTS ").append(fnName).append("$$\n");
        sql.append("CREATE FUNCTION ").append(fnName).append("(").append(enumKeyParameter).append(" VARCHAR(191)) RETURNS ").append(returnType).append("\n");
        sql.append("SQL SECURITY INVOKER\n");
        sql.append("BEGIN\n");
        sql.append("    DECLARE ").append(valueVariable).append(" ").append(returnType).append(";\n");
        sql.append("    SELECT ").append(quoteIdentifier(selectedField)).append(" INTO ").append(valueVariable).append(" FROM ").append(tableName)
                .append(" WHERE `name` = ").append(enumKeyParameter).append(" LIMIT 1;\n");
        sql.append("    RETURN ").append(valueVariable).append(";\n");
        sql.append("END$$\n");
        sql.append("DELIMITER ;");
    }

    @Override
    public String emitRecordModel(String schema, RecordModelSpec recordModelSpec) {
        Objects.requireNonNull(recordModelSpec, "recordModelSpec");
        // The routine names are built unquoted first ("__record_x__new"), then quoted whole via
        // qualify: appending a suffix to an already-quoted name would split the identifier.
        // B-2 (TG-BLK-005): names derive from the record's source-local qualified name through
        // SqlNames (qualifier join + collision-free "__" member join — rule documented there).
        // TG-BLK-011: member names are composed by SqlNames from the raw base so length
        // truncation applies to the full name, never to an already-truncated base + suffix.
        String constructorName = qualify(schema, SqlNames.recordMemberName(recordModelSpec.recordName(), "new"));

        StringBuilder sql = new StringBuilder();
        sql.append("DELIMITER $$\n");
        sql.append("DROP FUNCTION IF EXISTS ").append(constructorName).append("$$\n");
        sql.append("CREATE FUNCTION ").append(constructorName).append("(");
        sql.append(recordModelSpec.fields().stream()
                .map(field -> namingConventionEngine.parameterName(field.name()) + " " + toMySqlRecordFieldType(field.typeName()))
                .collect(Collectors.joining(", ")));
        sql.append(") RETURNS JSON\n");
        sql.append("SQL SECURITY INVOKER\n");
        sql.append("BEGIN\n");
        sql.append("    RETURN JSON_OBJECT(");
        sql.append(recordModelSpec.fields().stream()
                .map(field -> "'" + field.name() + "', " + namingConventionEngine.parameterName(field.name()))
                .collect(Collectors.joining(", ")));
        sql.append(");\n");
        sql.append("END$$\n");
        sql.append("DELIMITER ;\n\n");

        for (RecordModelSpec.RecordField field : recordModelSpec.fields()) {
            String accessorName = qualify(schema, SqlNames.recordMemberName(recordModelSpec.recordName(), field.name()));
            sql.append("DELIMITER $$\n");
            sql.append("DROP FUNCTION IF EXISTS ").append(accessorName).append("$$\n");
            String recordParameter = namingConventionEngine.parameterName("record");
            String returnType = toMySqlRecordFieldType(field.typeName());
            sql.append("CREATE FUNCTION ").append(accessorName)
                    .append("(").append(recordParameter).append(" JSON) RETURNS ").append(returnType).append("\n");
            sql.append("SQL SECURITY INVOKER\n");
            sql.append("BEGIN\n");
            sql.append("    RETURN ")
                    .append(recordFieldAccessorExpression(recordParameter, field.name(), returnType))
                    .append(";\n");
            sql.append("END$$\n");
            sql.append("DELIMITER ;\n\n");
        }

        return sql.toString().trim();
    }

    // ------------------------------------------------------------------
    // Artifact object descriptors (plan 4.4, audit G-10) — kept beside the emit methods so the
    // described names/types and the rendered SQL come from the same dialect decisions.
    // ------------------------------------------------------------------

    @Override
    public List<SqlObject> describeTrigger(String schema, String name, TriggerSpec trigger) {
        // Mirrors emitTrigger: MySQL has no trigger functions — one trigger per event.
        String onTable = describedTriggerTable(schema, trigger);
        return trigger.events().stream()
                .map(event -> new SqlObject(
                        SqlObject.Kind.TRIGGER,
                        schema,
                        SqlNames.fitWithinDialectLimits(name + "_" + event.toLowerCase(Locale.ROOT) + "_trg"),
                        List.of(),
                        "",
                        onTable))
                .toList();
    }

    @Override
    public List<SqlObject> describeScheduledJob(String schema, String name, ScheduledJobSpec scheduledJob, List<RoutineParameter> parameters) {
        // Mirrors emitScheduledJob: the procedure plus the CREATE EVENT that schedules it. The
        // event name is emitted unqualified, so it lands in the connection's default database —
        // the descriptor carries no schema (drops must stay unqualified too).
        String eventName = (scheduledJob.name() == null || scheduledJob.name().isBlank())
                ? SqlNames.fitWithinDialectLimits(name + "_event")
                : scheduledJob.name();
        List<SqlObject> objects = new ArrayList<>(describeProcedure(schema, name, parameters));
        objects.add(SqlObject.of(SqlObject.Kind.EVENT, "", eventName));
        return List.copyOf(objects);
    }

    @Override
    public List<SqlObject> describeEnumLookup(String schema, EnumLookupSpec enumLookupSpec) {
        String baseName = SqlNames.enumSqlBaseName(enumLookupSpec.enumName());
        List<SqlObject> objects = new ArrayList<>();
        objects.add(SqlObject.of(SqlObject.Kind.TABLE, schema, baseName));
        String enumKeyParameter = namingConventionEngine.parameterName("enum_key");
        // B-4 (TG-BLK-004): mirrors emitEnumLookup's dedupe — a field-named accessor method
        // must not describe a second object with the same id (the inventory uniqueness gate
        // fails the package otherwise).
        Set<String> describedAccessors = new LinkedHashSet<>();
        for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
            String accessorId = SqlNames.enumMemberName(enumLookupSpec.enumName(), field.name());
            if (!describedAccessors.add(accessorId)) {
                continue;
            }
            objects.add(new SqlObject(
                    SqlObject.Kind.FUNCTION,
                    schema,
                    accessorId,
                    List.of(new SqlObject.Parameter(enumKeyParameter, "VARCHAR(191)")),
                    toMySqlRecordFieldType(field.typeName()),
                    ""));
        }
        for (EnumLookupSpec.EnumMethod method : enumLookupSpec.methods()) {
            if (enumBackingFieldForMethod(method.name(), enumLookupSpec.fields()) == null) {
                continue;
            }
            String accessorId = SqlNames.enumMemberName(enumLookupSpec.enumName(), method.name());
            if (!describedAccessors.add(accessorId)) {
                continue;
            }
            objects.add(new SqlObject(
                    SqlObject.Kind.FUNCTION,
                    schema,
                    accessorId,
                    List.of(new SqlObject.Parameter(enumKeyParameter, "VARCHAR(191)")),
                    toMySqlRecordFieldType(method.returnTypeName()),
                    ""));
        }
        return List.copyOf(objects);
    }

    @Override
    public List<SqlObject> describeRecordModel(String schema, RecordModelSpec recordModelSpec) {
        // Mirrors emitRecordModel: MySQL has no composite types — JSON constructor + accessors.
        List<SqlObject> objects = new ArrayList<>();
        objects.add(new SqlObject(
                SqlObject.Kind.FUNCTION,
                schema,
                SqlNames.recordMemberName(recordModelSpec.recordName(), "new"),
                recordModelSpec.fields().stream()
                        .map(field -> new SqlObject.Parameter(
                                namingConventionEngine.parameterName(field.name()),
                                toMySqlRecordFieldType(field.typeName())))
                        .toList(),
                "JSON",
                ""));
        String recordParameter = namingConventionEngine.parameterName("record");
        for (RecordModelSpec.RecordField field : recordModelSpec.fields()) {
            objects.add(new SqlObject(
                    SqlObject.Kind.FUNCTION,
                    schema,
                    SqlNames.recordMemberName(recordModelSpec.recordName(), field.name()),
                    List.of(new SqlObject.Parameter(recordParameter, "JSON")),
                    toMySqlRecordFieldType(field.typeName()),
                    ""));
        }
        return List.copyOf(objects);
    }

    private String toMySqlRecordFieldType(String javaType) {
        if (javaType == null) return "TEXT";
        String t = javaType.trim();
        return switch (t) {
            case "int", "Integer" -> "INT";
            case "long", "Long" -> "BIGINT";
            case "boolean", "Boolean" -> "BOOLEAN";
            case "double", "Double", "float", "Float" -> "DOUBLE";
            case "java.math.BigDecimal", "BigDecimal" -> "DECIMAL(38,9)";
            case "java.time.LocalDate", "LocalDate" -> "DATE";
            // E-13 (plan 3.1): (6) preserves microsecond precision in record field storage.
            case "java.time.LocalDateTime", "LocalDateTime" -> "DATETIME(6)";
            case "java.time.Instant", "Instant", "java.time.ZonedDateTime", "ZonedDateTime", "java.time.OffsetDateTime", "OffsetDateTime" -> "TIMESTAMP(6)";
            // No native UUID on MySQL: CHAR(36) canonical string (must match the scalar TUuidType mapping).
            // CHAR(36) is text-like, so the JSON_UNQUOTE record-field accessor still returns the string.
            case "java.util.UUID", "UUID" -> "CHAR(36)";
            default -> "TEXT";
        };
    }

    private String recordFieldAccessorExpression(String recordParameter, String fieldName, String returnType) {
        String jsonExtract = "JSON_EXTRACT(" + recordParameter + ", '$." + fieldName + "')";
        String unquoted = "JSON_UNQUOTE(" + jsonExtract + ")";

        if (isTextLikeRecordType(returnType)) {
            return unquoted;
        }
        if ("BOOLEAN".equals(returnType)) {
            return "CAST(" + unquoted + " AS UNSIGNED)";
        }
        // MySQL CAST accepts DATETIME but not TIMESTAMP as a target type (verified on 8.4:
        // "CAST(x AS TIMESTAMP)" is a syntax error), so TIMESTAMP(6)-typed record fields are
        // cast through DATETIME(6); the function's RETURNS TIMESTAMP(6) clause coerces the
        // result, preserving the declared type and the (6) fractional precision (E-13).
        if ("TIMESTAMP(6)".equals(returnType)) {
            return "CAST(" + unquoted + " AS DATETIME(6))";
        }
        // MySQL CAST has no INT/BIGINT target (only SIGNED [INTEGER]); the function's RETURNS
        // clause coerces the SIGNED value to the declared integer type. Surfaced by the plan 4.4
        // install-verification IT: CAST(x AS BIGINT) is a syntax error on MySQL 8.
        if ("INT".equals(returnType) || "BIGINT".equals(returnType)) {
            return "CAST(" + unquoted + " AS SIGNED)";
        }
        return "CAST(" + unquoted + " AS " + returnType + ")";
    }

    private boolean isTextLikeRecordType(String returnType) {
        return "TEXT".equals(returnType) || "CHAR".equals(returnType) || "VARCHAR".equals(returnType);
    }

    private static String toMySqlSecurityKeyword(SecurityMode securityMode) {
        return securityMode == SecurityMode.DEFINER ? "DEFINER" : "INVOKER";
    }

    /**
     * Emits the routine-level DECLARE section in the order MySQL requires — variables and
     * conditions first, then cursors, then handlers (audit E-12: with observability enabled the
     * telemetry DECLAREs used to follow the body declarations, so a body cursor declaration made
     * CREATE fail with "Variable or condition declaration after cursor or handler declaration").
     *
     * <p>Order within the section: body variables, telemetry scaffold variables (observability),
     * the E-8 saved-time-zone variable and the function return staging variable, body cursors,
     * body handlers, and finally the single routine-level EXIT handler (telemetry-aware when
     * observability is on, otherwise the static-reset/time-zone-restore handler).</p>
     */
    private void emitRoutineDeclarations(Block body, boolean observability, TirType functionReturnType) {
        List<DeclarationNode> variables = new ArrayList<>();
        List<DeclarationNode> cursors = new ArrayList<>();
        List<DeclarationNode> handlers = new ArrayList<>();
        for (DeclarationNode declaration : body.declarations()) {
            if (declaration instanceof DeclareVariable variable && isRoutineParameter(variable)) {
                continue;
            }
            switch (declaration) {
                case DeclareVariable ignored -> variables.add(declaration);
                case DeclareCursor ignored -> cursors.add(declaration);
                case DeclareHandler ignored -> handlers.add(declaration);
            }
        }
        for (DeclarationNode declaration : variables) {
            out.line(mySqlDeclarationLine(
                    declaration,
                    shouldIncludeDeclarationInitializer(declaration, body.statements())));
        }
        if (observability) {
            emitMySqlTelemetryScaffoldVariables();
        }
        out.line("DECLARE " + SAVED_TIME_ZONE_VARIABLE + " VARCHAR(64) DEFAULT @@session.time_zone;");
        if (functionReturnType != null && !(functionReturnType instanceof TVoidType)) {
            out.line("DECLARE " + RETURN_VALUE_VARIABLE + " " + sqlType(functionReturnType) + ";");
        }
        for (DeclarationNode declaration : cursors) {
            out.line(declaration.accept(this));
        }
        for (DeclarationNode declaration : handlers) {
            out.line(declaration.accept(this));
        }
        if (observability) {
            emitMySqlTelemetryScaffoldHandler();
        } else {
            emitMySqlRoutineExceptionHandler();
        }
    }

    private void emitMySqlTelemetryScaffoldVariables() {
        out.line("DECLARE __titan_telemetry_id BIGINT DEFAULT NULL;");
        out.line("DECLARE __titan_started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6);");
        out.line("DECLARE __titan_err_state CHAR(5);");
        out.line("DECLARE __titan_err_message TEXT;");
    }

    private void emitMySqlTelemetryScaffoldHandler() {
        out.line("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        out.line("BEGIN");
        out.indent();
        out.line("GET DIAGNOSTICS CONDITION 1 __titan_err_state = RETURNED_SQLSTATE, __titan_err_message = MESSAGE_TEXT;");
        emitMySqlStaticResets();
        emitMySqlDebugLogCleanup();
        out.line("IF __titan_telemetry_id IS NOT NULL THEN");
        out.indent();
        out.line("UPDATE " + TELEMETRY_TABLE + " SET");
        out.line("  status = 'error', finished_at = CURRENT_TIMESTAMP(6),");
        out.line("  error_sqlstate = __titan_err_state, error_message = __titan_err_message");
        out.line("WHERE id = __titan_telemetry_id;");
        out.dedent();
        out.line("END IF;");
        out.line(restoreTimeZoneSql());
        out.line("RESIGNAL;");
        out.dedent();
        out.line("END;");
    }

    /**
     * The single routine-level EXIT handler for non-observability procedures/functions: runs the
     * static resets and debug-log cleanup (when present), restores the caller's session time
     * zone (E-8 — this handler is emitted unconditionally so exception exits cannot leak the
     * pinned UTC zone), and re-raises the original condition unchanged via RESIGNAL.
     */
    private void emitMySqlRoutineExceptionHandler() {
        out.line("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        out.line("BEGIN");
        out.indent();
        emitMySqlStaticResets();
        emitMySqlDebugLogCleanup();
        out.line(restoreTimeZoneSql());
        out.line("RESIGNAL;");
        out.dedent();
        out.line("END;");
    }

    /** Trigger-scope exception handler (no pinned time zone to restore — see emitTrigger). */
    private void emitMySqlStaticResetExceptionHandler() {
        out.line("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        out.line("BEGIN");
        out.indent();
        emitMySqlStaticResets();
        emitMySqlDebugLogCleanup();
        out.line("RESIGNAL;");
        out.dedent();
        out.line("END;");
    }

    private static String restoreTimeZoneSql() {
        return "SET time_zone = " + SAVED_TIME_ZONE_VARIABLE + ";";
    }

    private void emitMySqlDebugLogCleanup() {
        if (debugLogEnabled) {
            out.line("DROP TEMPORARY TABLE IF EXISTS __debug_log;");
        }
    }

    @Override
    protected String telemetrySuccessSql() {
        return String.join("\n",
                "UPDATE " + TELEMETRY_TABLE + " SET",
                "  status = 'success', finished_at = CURRENT_TIMESTAMP(6),",
                "  duration_ms = TIMESTAMPDIFF(MICROSECOND, __titan_started_at, CURRENT_TIMESTAMP(6)) / 1000.0",
                "WHERE id = __titan_telemetry_id;");
    }

    private void emitMySqlStaticResets() {
        for (String key : staticResetKeys) {
            out.line(staticResetCallSql(key));
        }
    }

    @Override
    protected String staticResetCallSql(String key) {
        return "SET @__titan_static_reset = " + runtimeFunction("static_reset") + "('" + escape(key) + "');";
    }

    @Override
    protected String staticSetCallSql(String keySql, String valueSql) {
        // valueSql is already text-coerced by coerceToTextSql at the call site (B-10) — for a
        // non-boolean value that is exactly the CAST(... AS CHAR) this hook used to apply, and for
        // a boolean it is the 'true'/'false' CASE; pass it through verbatim (no double cast).
        return "SET @__titan_static_write = " + runtimeFunction("static_set") + "(" + keySql + ", " + valueSql + ");";
    }

    @Override
    protected String nullPointerSignalSql(String location) {
        // MySQL hard-limits SIGNAL MESSAGE_TEXT to 128 characters (ER_COND_ITEM_TOO_LONG at
        // runtime otherwise). Null guards carry real source locations since plan 2.3, so long
        // absolute paths are truncated from the left, keeping the file:line tail.
        String message = "NullPointerException at " + location;
        if (message.length() > 128) {
            String prefix = "NullPointerException at ...";
            message = prefix + message.substring(message.length() - (128 - prefix.length()));
        }
        return "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + escape(message) + "';";
    }

    @Override
    protected String fullOuterJoinKeyword() {
        throw new UnsupportedOperationException(
                "TITAN-E001: FULL OUTER JOIN is not supported by MySQL (any version); "
                        + "rewrite the query as a UNION of LEFT JOIN and RIGHT JOIN results.");
    }

    /**
     * MySQL has no {@code NULLS FIRST/LAST} syntax (audit D-7): an explicit NULL placement is
     * emulated with a leading {@code (expr IS NULL)} sort key — {@code DESC} floats NULL rows
     * to the front (IS NULL is 1 for them), {@code ASC} sinks them to the back — followed by
     * the requested direction on the expression itself.
     */
    @Override
    protected String emitOrderBy(OrderBySpec spec) {
        if (spec.nulls() == null) {
            return super.emitOrderBy(spec);
        }
        String operand = spec.expression().accept(this);
        String nullsKey = "(" + operand + " IS NULL) " + (spec.nulls() == NullsOrder.FIRST ? "DESC" : "ASC");
        return nullsKey + ", " + operand + " " + spec.direction().name();
    }

    @Override
    protected String groupByClauseSql(List<ExpressionNode> groupBy) {
        // MySQL has no GROUP BY ROLLUP(...) function syntax; the equivalent is
        // "GROUP BY <columns> WITH ROLLUP", expressible only when ROLLUP covers the whole list.
        if (groupBy.size() == 1
                && groupBy.getFirst() instanceof GroupingSetSpec spec
                && spec.kind() == GroupingSetKind.ROLLUP) {
            return "GROUP BY " + spec.sets().getFirst().stream()
                    .map(expr -> expr.accept(this))
                    .collect(Collectors.joining(", ")) + " WITH ROLLUP";
        }
        return super.groupByClauseSql(groupBy);
    }

    @Override
    public String visitGroupingSetSpec(GroupingSetSpec node) {
        return switch (node.kind()) {
            case GROUPING_SETS -> throw new UnsupportedOperationException(
                    "TITAN-E001: GROUP BY GROUPING SETS is not supported by MySQL (any version); "
                            + "rewrite the query as a UNION ALL of one grouped query per grouping set.");
            case CUBE -> throw new UnsupportedOperationException(
                    "TITAN-E001: GROUP BY CUBE is not supported by MySQL (any version); "
                            + "rewrite the query as a UNION ALL of one grouped query per grouping combination.");
            case ROLLUP -> throw new UnsupportedOperationException(
                    "TITAN-E001: MySQL supports ROLLUP only as 'GROUP BY ... WITH ROLLUP' over the entire "
                            + "grouping list; rollup(...) cannot be combined with other GROUP BY elements.");
            case SET -> super.visitGroupingSetSpec(node);
        };
    }

    @Override
    protected String windowFrameUnitKeyword(WindowFrameUnit unit) {
        if (unit == WindowFrameUnit.GROUPS) {
            throw new UnsupportedOperationException(
                    "TITAN-E001: GROUPS window frames are not supported by MySQL (any version); "
                            + "use a ROWS or RANGE frame instead.");
        }
        return super.windowFrameUnitKeyword(unit);
    }

    @Override
    protected String elseIfKeyword() {
        return "ELSEIF";
    }

    @Override
    protected String characterLiteral(char value) {
        return "CHAR(" + (int) value + " USING utf8mb4)";
    }

    /**
     * B-3 (TG-BLK-006): a string literal containing control characters is emitted with MySQL
     * backslash escapes ({@code \n}, {@code \t}, ...), never as raw control bytes that
     * {@code indentMultiline} would silently "indent" into the literal's value. Control
     * characters MySQL has no named escape for (form feed, vertical tab, ...) are composed via
     * {@code CONCAT(..., CHAR(n USING utf8mb4), ...)} — MySQL's {@code \f} is just {@code f},
     * so a backslash spelling would corrupt the value. Control-character-free values keep the
     * historical {@code '...'} rendering (escape() backslash+quote doubling), so existing
     * artifacts are byte-identical.
     */
    @Override
    protected String stringLiteral(String value) {
        if (!containsControlCharacter(value)) {
            return "'" + escape(value) + "'";
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder literal = new StringBuilder("'");
        boolean literalEmpty = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String namedEscape = switch (c) {
                case '\\' -> "\\\\";
                case '\'' -> "''";
                case '\n' -> "\\n";
                case '\t' -> "\\t";
                case '\r' -> "\\r";
                case '\b' -> "\\b";
                case '\u0000' -> "\\0";
                case '\u001A' -> "\\Z";
                default -> null;
            };
            if (namedEscape != null) {
                literal.append(namedEscape);
                literalEmpty = false;
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                if (!literalEmpty) {
                    pieces.add(literal.append("'").toString());
                    literal = new StringBuilder("'");
                    literalEmpty = true;
                }
                pieces.add("CHAR(" + (int) c + " USING utf8mb4)");
                continue;
            }
            literal.append(c);
            literalEmpty = false;
        }
        if (!literalEmpty || pieces.isEmpty()) {
            pieces.add(literal.append("'").toString());
        }
        if (pieces.size() == 1) {
            return pieces.get(0);
        }
        return "CONCAT(" + String.join(", ", pieces) + ")";
    }

    @Override
    protected String parameterPlaceholder(int oneBasedIndex) {
        return "?";
    }

    @Override
    protected boolean supportsDollarQuotedStrings() {
        return false;
    }

    @Override
    protected boolean supportsBackslashStringEscapes() {
        return true;
    }

    /**
     * Lossy-cron analysis shared with {@code TranspilationPipeline} (plan 3.1): when the cron
     * expression has no native MySQL event-scheduler representation, returns the warning text
     * naming the original expression and the approximation actually emitted. The emitter embeds
     * the same text as a SQL comment above the CREATE EVENT (defense-in-depth for anyone reading
     * the artifact); the pipeline additionally routes it through the {@code DiagnosticSink} as a
     * positioned {@code TITAN-W005} warning so lossy schedules surface at compile time instead
     * of only inside the generated file.
     */
    public static java.util.Optional<String> cronApproximationWarning(String cron) {
        return java.util.Optional.ofNullable(mysqlScheduleFromCron(cron).warningComment());
    }

    private static MySqlEventSchedule mysqlScheduleFromCron(String cron) {
        String[] parts = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (parts.length != 5) {
            return new MySqlEventSchedule("1 DAY", "CURRENT_TIMESTAMP",
                    "Cron expression '" + cron + "' is not a recognized 5-field cron for the MySQL event scheduler; "
                            + "approximated as EVERY 1 DAY starting CURRENT_TIMESTAMP.");
        }

        String minute = parts[0];
        String hour = parts[1];
        String dayOfMonth = parts[2];
        String month = parts[3];
        String dayOfWeek = parts[4];

        if ("*".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth) && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule("1 MINUTE", "CURRENT_TIMESTAMP", null);
        }

        Integer minuteStep = parseWildcardStep(minute);
        if (minuteStep != null && "*".equals(hour) && "*".equals(dayOfMonth) && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule(minuteStep + " MINUTE", "CURRENT_TIMESTAMP", null);
        }

        if (minute.matches("\\d{1,2}") && "*".equals(hour) && "*".equals(dayOfMonth) && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule(
                    "1 HOUR",
                    "TIMESTAMP(CURRENT_DATE, MAKETIME(HOUR(CURRENT_TIME), " + minute + ", 0))",
                    null);
        }

        if (minute.matches("\\d{1,2}") && hour.matches("\\d{1,2}") && "*".equals(dayOfMonth) && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule(
                    "1 DAY",
                    "TIMESTAMP(CURRENT_DATE, MAKETIME(" + hour + ", " + minute + ", 0))",
                    null);
        }

        if (minute.matches("\\d{1,2}") && hour.matches("\\d{1,2}") && dayOfMonth.matches("\\d{1,2}") && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule(
                    "1 MONTH",
                    "TIMESTAMP(DATE_ADD(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'), INTERVAL " + dayOfMonth + " - 1 DAY), MAKETIME(" + hour + ", " + minute + ", 0))",
                    null);
        }

        return new MySqlEventSchedule(
                "1 DAY",
                "CURRENT_TIMESTAMP",
                "Cron expression '" + cron + "' cannot be represented natively by MySQL events; "
                        + "approximated as EVERY 1 DAY starting CURRENT_TIMESTAMP "
                        + "(day-of-week/day-of-month/month constraints are not enforced).");
    }

    private static Integer parseWildcardStep(String field) {
        if (field == null) {
            return null;
        }
        if (!field.startsWith("*/")) {
            return null;
        }
        String rawStep = field.substring(2);
        if (!rawStep.matches("\\d+")) {
            return null;
        }
        int step = Integer.parseInt(rawStep);
        return step > 0 ? step : null;
    }

    private record MySqlEventSchedule(String everyExpression, String startsExpression, String warningComment) {}

    private String routineParameterSignature(boolean function) {
        // Parameter names stay unquoted (plan 1.3b exclusion): RoutineSqlNameAllocator emits
        // p_-prefixed lowercase names that can never be reserved words, and MySQL does not
        // accept backtick-quoted identifiers uniformly across routine-variable positions.
        return routineParameters.stream()
                .map(parameter -> (function ? "" : "IN ") + parameter.sqlName() + " " + sqlType(parameter.type()))
                .collect(Collectors.joining(", "));
    }

    @Override
    protected List<String> reservedRoutineIdentifiers(boolean observability) {
        List<String> reserved = new ArrayList<>(List.of(
                "__titan_saved_exception",
                "__titan_saved_state",
                "__titan_saved_message",
                SAVED_TIME_ZONE_VARIABLE,
                RETURN_VALUE_VARIABLE
        ));
        if (observability) {
            reserved.add("__titan_telemetry_id");
            reserved.add("__titan_started_at");
            reserved.add("__titan_err_state");
            reserved.add("__titan_err_message");
        }
        return List.copyOf(reserved);
    }

    private void emitDeclarations(Block body) {
        for (DeclarationNode declaration : body.declarations()) {
            if (declaration instanceof DeclareVariable variable && isRoutineParameter(variable)) {
                continue;
            }
            out.line(mySqlDeclarationLine(
                    declaration,
                    shouldIncludeDeclarationInitializer(declaration, body.statements())));
        }
    }

    private String mySqlDeclarationLine(DeclarationNode declaration, boolean includeInitializer) {
        if (!includeInitializer && declaration instanceof DeclareVariable variable) {
            return new DeclareVariable(variable.name(), variable.type(), variable.nullable(), null).accept(this);
        }
        return declaration.accept(this);
    }

    @Override
    protected boolean shouldIncludeDeclarationInitializer(DeclarationNode declaration, List<StatementNode> statements) {
        if (declaration instanceof DeclareVariable variable && variable.type() instanceof TPeriodType) {
            return true;
        }
        return super.shouldIncludeDeclarationInitializer(declaration, statements);
    }

    private void validateMySqlRoutineBoundaryTypes(String routineName, List<RoutineParameter> parameters, TirType returnType) {
        for (RoutineParameter parameter : parameters) {
            if (parameter.type() instanceof TPeriodType) {
                throw unsupportedOnDialect(
                        "MySQL routine '" + routineName + "' cannot expose period-like parameter '"
                                + parameter.sqlName()
                                + "' because Period units are not preserved across MySQL routine boundaries");
            }
        }
        if (returnType instanceof TPeriodType) {
            throw unsupportedOnDialect(
                    "MySQL routine '" + routineName
                            + "' cannot return a period-like value because Period units are not preserved across MySQL routine boundaries");
        }
    }

    private boolean isTemporalAmountType(TirType type) {
        return type instanceof TDurationType || type instanceof TPeriodType;
    }

    private void registerRoutineTemporalAmountTypes(List<RoutineParameter> parameters) {
        for (RoutineParameter parameter : parameters) {
            if (isTemporalAmountType(parameter.type())) {
                temporalAmountTypes.put(parameter.javaName(), parameter.type());
            }
        }
    }

    // MySQL DECLARE names stay unquoted (plan 1.3b exclusion): local-variable names are
    // allocator-generated (p_/v_/__titan_ prefixed, lowercase, sanitized) so they can never be
    // reserved words, and MySQL does not accept backtick-quoted identifiers uniformly across
    // the positions a local variable appears in (DECLARE, FETCH ... INTO, SELECT ... INTO,
    // handler SET targets), so quoting only some references would desynchronize the name.
    @Override
    public String visitDeclareVariable(DeclareVariable node) {
        if (isTemporalAmountType(node.type())) {
            temporalAmountTypes.put(node.name(), node.type());
        }
        if (node.type() instanceof TDurationType) {
            TemporalAmountLiteral temporalAmount = temporalAmountLiteral(node.initializer());
            if (temporalAmount != null) {
                temporalAmountUnits.put(node.name(), temporalAmount.unitKeyword());
                return "DECLARE " + emittedVariableName(node.name()) + " BIGINT DEFAULT " + temporalAmount.magnitudeSql() + ";";
            }
            if (node.initializer() != null) {
                String secondsSql = durationSecondsSql(node.initializer());
                if (secondsSql != null) {
                    return "DECLARE " + emittedVariableName(node.name()) + " BIGINT DEFAULT " + secondsSql + ";";
                }
            }
        }
        if (node.type() instanceof TPeriodType) {
            TemporalAmountLiteral temporalAmount = temporalAmountLiteral(node.initializer());
            if (temporalAmount != null) {
                temporalAmountUnits.put(node.name(), temporalAmount.unitKeyword());
                return "DECLARE " + emittedVariableName(node.name()) + " BIGINT DEFAULT " + temporalAmount.magnitudeSql() + ";";
            }
        }
        StringBuilder sql = new StringBuilder("DECLARE ")
                .append(emittedVariableName(node.name()))
                .append(' ')
                .append(sqlType(node.type()));
        if (node.initializer() != null) {
            sql.append(" DEFAULT ").append(node.initializer().accept(this));
        }
        sql.append(';');
        return sql.toString();
    }

    @Override
    public String visitDeclareHandler(DeclareHandler node) {
        String action = node.action() == HandlerAction.EXIT ? "EXIT" : "CONTINUE";
        String conditions = (node.conditions() == null || node.conditions().isEmpty())
                ? "SQLEXCEPTION"
                : String.join(", ", node.conditions());
        if (node.flagVariable() == null || node.flagVariable().isBlank()) {
            return "DECLARE " + action + " HANDLER FOR " + conditions + " BEGIN END;";
        }
        return "DECLARE " + action + " HANDLER FOR " + conditions + " SET " + emittedVariableName(node.flagVariable()) + " = TRUE;";
    }

    @Override
    public String visitBlock(Block node) {
        StringBuilder sql = new StringBuilder();
        sql.append("BEGIN\n");
        for (DeclarationNode declaration : node.declarations()) {
            sql.append(mySqlDeclarationLine(
                    declaration,
                    shouldIncludeDeclarationInitializer(declaration, node.statements()))).append('\n');
        }
        for (StatementNode statement : node.statements()) {
            sql.append(statement.accept(this)).append('\n');
        }
        sql.append("END;");
        return sql.toString();
    }

    @Override
    public String visitAssign(Assign node) {
        if (node.target() instanceof VariableRefExpression variableRef
                && temporalAmountTypes.get(variableRef.name()) instanceof TDurationType) {
            String secondsSql = durationSecondsSql(node.expression());
            if (secondsSql == null) {
                throw unsupportedOnDialect("Unsupported duration assignment for MySQL emission: " + node.expression());
            }
            TemporalAmountLiteral temporalAmount = temporalAmountLiteral(node.expression());
            String expectedUnit = temporalAmountUnits.get(variableRef.name());
            if (expectedUnit == null) {
                return "SET " + emittedVariableName(variableRef.name()) + " = " + secondsSql + ";";
            }
            if (temporalAmount != null && expectedUnit.equals(temporalAmount.unitKeyword())) {
                return "SET " + emittedVariableName(variableRef.name()) + " = " + temporalAmount.magnitudeSql() + ";";
            }
            temporalAmountUnits.remove(variableRef.name());
            return "SET " + emittedVariableName(variableRef.name()) + " = " + secondsSql + ";";
        }
        if (node.target() instanceof VariableRefExpression variableRef && temporalAmountUnits.containsKey(variableRef.name())) {
            TemporalAmountLiteral temporalAmount = temporalAmountLiteral(node.expression());
            if (temporalAmount == null) {
                throw unsupportedOnDialect("Unsupported temporal amount assignment for MySQL emission: " + node.expression());
            }
            String expectedUnit = temporalAmountUnits.get(variableRef.name());
            if (!expectedUnit.equals(temporalAmount.unitKeyword())) {
                throw unsupportedOnDialect(
                        "MySQL temporal amount variable '" + variableRef.name() + "' changed unit from " + expectedUnit + " to " + temporalAmount.unitKeyword());
            }
            return "SET " + emittedVariableName(variableRef.name()) + " = " + temporalAmount.magnitudeSql() + ";";
        }
        return "SET " + node.target().accept(this) + " = " + node.expression().accept(this) + ";";
    }

    @Override
    public String visitWhileStatement(WhileStatement node) {
        String loopLabel = pushLoopLabel(node.label());
        try {
            return loopLabel + ": WHILE " + node.condition().accept(this) + " DO\n"
                    + indentMultiline(blockBody(node.body()))
                    + "\nEND WHILE " + loopLabel + ';';
        } finally {
            popLoopLabel();
        }
    }

    @Override
    public String visitLoopStatement(LoopStatement node) {
        String loopLabel = pushLoopLabel(node.label());
        try {
            if (node.exitCondition() != null) {
                return loopLabel + ": REPEAT\n"
                        + indentMultiline(blockBody(node.body()))
                        + "\nUNTIL " + node.exitCondition().accept(this)
                        + " END REPEAT " + loopLabel + ';';
            }

            return loopLabel + ": LOOP\n"
                    + indentMultiline(blockBody(node.body()))
                    + "\nEND LOOP " + loopLabel + ';';
        } finally {
            popLoopLabel();
        }
    }

    @Override
    public String visitForCursorStatement(ForCursorStatement node) {
        String variableName = emittedVariableName(node.variableName());
        String base = sanitizeIdentifier(variableName);
        String cursorName = routineSqlNameAllocator.allocateGeneratedName("cur_" + base);
        String doneFlag = routineSqlNameAllocator.allocateGeneratedName("done_" + base);
        String cursorOpenFlag = routineSqlNameAllocator.allocateGeneratedName("cursor_open_" + base);
        String loopLabel = pushLoopLabel((node.label() == null || node.label().isBlank())
                ? routineSqlNameAllocator.allocateGeneratedName("read_" + base)
                : node.label());
        String bodySql;
        try {
            bodySql = indentMultiline(blockBody(node.body()));
        } finally {
            popLoopLabel();
        }

        return "BEGIN\n"
                + "    DECLARE " + doneFlag + " BOOLEAN DEFAULT FALSE;\n"
                + "    DECLARE " + cursorOpenFlag + " BOOLEAN DEFAULT FALSE;\n"
                + "    DECLARE " + cursorName + " CURSOR FOR " + node.query().accept(this) + ";\n"
                + "    DECLARE EXIT HANDLER FOR SQLEXCEPTION\n"
                + "    BEGIN\n"
                + "        IF " + cursorOpenFlag + " THEN CLOSE " + cursorName + "; END IF;\n"
                + "        RESIGNAL;\n"
                + "    END;\n"
                + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET " + doneFlag + " = TRUE;\n"
                + "    OPEN " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = TRUE;\n"
                + "    " + loopLabel + ": LOOP\n"
                + "        FETCH " + cursorName + " INTO " + variableName + ";\n"
                + "        IF " + doneFlag + " THEN LEAVE " + loopLabel + "; END IF;\n"
                + bodySql + "\n"
                + "    END LOOP " + loopLabel + ";\n"
                + "    CLOSE " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = FALSE;\n"
                + "END;";
    }

    @Override
    public String visitForEachStatement(ForEachStatement node) {
        String variableName = emittedVariableName(node.variableName());
        String base = sanitizeIdentifier(variableName);
        String tempTable = routineSqlNameAllocator.allocateGeneratedName("titan_iter_" + base);
        String cursorName = routineSqlNameAllocator.allocateGeneratedName("cur_" + base);
        String doneFlag = routineSqlNameAllocator.allocateGeneratedName("done_" + base);
        String cursorOpenFlag = routineSqlNameAllocator.allocateGeneratedName("cursor_open_" + base);
        String loopLabel = pushLoopLabel((node.label() == null || node.label().isBlank())
                ? routineSqlNameAllocator.allocateGeneratedName("iter_" + base)
                : node.label());
        String bodySql;
        try {
            bodySql = indentMultiline(blockBody(node.body()));
        } finally {
            popLoopLabel();
        }
        String valueType = sqlType(node.variableType());

        return "BEGIN\n"
                + "    DECLARE " + doneFlag + " BOOLEAN DEFAULT FALSE;\n"
                + "    DECLARE " + cursorOpenFlag + " BOOLEAN DEFAULT FALSE;\n"
                + "    DECLARE " + cursorName + " CURSOR FOR SELECT value FROM " + tempTable + ";\n"
                + "    DECLARE EXIT HANDLER FOR SQLEXCEPTION\n"
                + "    BEGIN\n"
                + "        IF " + cursorOpenFlag + " THEN CLOSE " + cursorName + "; END IF;\n"
                + "        DROP TEMPORARY TABLE IF EXISTS " + tempTable + ";\n"
                + "        RESIGNAL;\n"
                + "    END;\n"
                + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET " + doneFlag + " = TRUE;\n"
                + "    DROP TEMPORARY TABLE IF EXISTS " + tempTable + ";\n"
                + "    CREATE TEMPORARY TABLE " + tempTable + " (value " + valueType + ");\n"
                + "    INSERT INTO " + tempTable + "(value) "
                + "SELECT jt.value FROM JSON_TABLE(" + node.iterable().accept(this)
                + ", '$[*]' COLUMNS (value " + valueType + " PATH '$')) jt;\n"
                + "    OPEN " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = TRUE;\n"
                + "    " + loopLabel + ": LOOP\n"
                + "        FETCH " + cursorName + " INTO " + variableName + ";\n"
                + "        IF " + doneFlag + " THEN LEAVE " + loopLabel + "; END IF;\n"
                + bodySql + "\n"
                + "    END LOOP " + loopLabel + ";\n"
                + "    CLOSE " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = FALSE;\n"
                + "    DROP TEMPORARY TABLE IF EXISTS " + tempTable + ";\n"
                + "END;";
    }

    /**
     * MySQL has no FOR loop, so the range loop desugars to a labeled LOOP that increments the
     * variable and tests the inclusive end bound at the TOP of the body (per-dialect divergence
     * from PostgreSQL's native {@code FOR i IN a..b LOOP}, chosen for correctness): ITERATE jumps
     * to the start of a LOOP body, so an unlabeled {@code continue} re-runs increment-then-test —
     * exactly Java's for-loop update/condition order. A trailing-increment WHILE desugar would
     * skip the increment on ITERATE and loop forever.
     */
    @Override
    public String visitForRangeStatement(ForRangeStatement node) {
        String variableName = emittedVariableName(node.variableName());
        String loopLabel = pushLoopLabel(node.label());
        try {
            return "SET " + variableName + " = " + node.start().accept(this) + " - 1;\n"
                    + loopLabel + ": LOOP\n"
                    + "    SET " + variableName + " = " + variableName + " + 1;\n"
                    + "    IF " + variableName + " > " + node.end().accept(this) + " THEN LEAVE " + loopLabel + "; END IF;\n"
                    + indentMultiline(blockBody(node.body())) + "\n"
                    + "END LOOP " + loopLabel + ";";
        } finally {
            popLoopLabel();
        }
    }

    @Override
    protected String returnResultSql(ReturnStatement node) {
        String valueSql;
        if (node.expression() == null) {
            valueSql = null;
        } else if (currentFunctionReturnType instanceof TDurationType) {
            String secondsSql = durationSecondsSql(node.expression());
            if (secondsSql == null) {
                throw unsupportedOnDialect("Unsupported duration return for MySQL emission: " + node.expression());
            }
            valueSql = secondsSql;
        } else {
            valueSql = node.expression().accept(this);
        }
        // MySQL procedures cannot RETURN — an early void return LEAVEs the labeled body block and
        // falls through to the epilogue (which restores the time zone), so no inline restore here.
        if (procedureExitLabel != null && valueSql == null) {
            return "LEAVE " + procedureExitLabel + ";";
        }
        if (!sessionTimeZoneScoped) {
            return valueSql == null ? "RETURN;" : "RETURN " + valueSql + ";";
        }
        if (valueSql == null) {
            return restoreTimeZoneSql() + "\nRETURN;";
        }
        if (currentFunctionReturnType == null || currentFunctionReturnType instanceof TVoidType) {
            // Defensive: only functions declare the staging variable; a value-returning
            // statement outside a function context keeps the historical inline form.
            return "RETURN " + valueSql + ";";
        }
        // E-8: evaluate the return expression while the session is still pinned to UTC (it may
        // read TIMESTAMP columns or call CONVERT_TZ against @@session.time_zone), stage it into
        // the typed local, restore the caller's zone, then return the staged value.
        return "SET " + RETURN_VALUE_VARIABLE + " = " + valueSql + ";\n"
                + restoreTimeZoneSql() + "\n"
                + "RETURN " + RETURN_VALUE_VARIABLE + ";";
    }

    @Override
    public String visitBreakStatement(BreakStatement node) {
        return "LEAVE " + breakContinueTargetLabel(node.label(), "break") + ";";
    }

    @Override
    public String visitContinueStatement(ContinueStatement node) {
        return "ITERATE " + breakContinueTargetLabel(node.label(), "continue") + ";";
    }

    private String breakContinueTargetLabel(String explicitLabel, String statementName) {
        if (explicitLabel != null && !explicitLabel.isBlank()) {
            return explicitLabel;
        }
        String innermost = loopLabelStack.peek();
        if (innermost == null) {
            throw unsupportedOnDialect(
                    "Unlabeled " + statementName + " outside of a loop has no MySQL emission; "
                            + "the front end should reject it before emission");
        }
        return innermost;
    }

    @Override
    public String visitRaiseStatement(RaiseStatement node) {
        String state = (node.sqlstate() == null || node.sqlstate().isBlank()) ? "45000" : node.sqlstate();
        // F-10 (plan 2.2): string-literal messages render inline, byte-identical to the
        // historical form. MySQL only accepts a simple value (literal or variable) after
        // SET MESSAGE_TEXT, so dynamic messages are staged through a session variable first —
        // the same staged pattern as the E-1 unhandled-rethrow tail (which stages through the
        // DECLAREd __titan_saved_message; a standalone RAISE has no enclosing DECLARE scope,
        // hence the session variable).
        if (isStringLiteralMessage(node.message())) {
            return "SIGNAL SQLSTATE '" + escape(state) + "' SET MESSAGE_TEXT = "
                    + raiseMessageSql(node.message()) + ";";
        }
        return "SET @__titan_raise_message = " + raiseMessageSql(node.message()) + ";\n"
                + "SIGNAL SQLSTATE '" + escape(state) + "' SET MESSAGE_TEXT = @__titan_raise_message;";
    }

    @Override
    public String visitDebugPrintStatement(DebugPrintStatement node) {
        String message = node.message() == null ? "''" : node.message().accept(this);
        return "INSERT INTO __debug_log(message) VALUES (" + message + ");";
    }

    @Override
    public String visitTryCatchFinallyStatement(TryCatchFinallyStatement node) {
        StringBuilder sql = new StringBuilder();
        sql.append("BEGIN\n");
        sql.append("    DECLARE __titan_saved_exception BOOLEAN DEFAULT FALSE;\n");
        sql.append("    DECLARE __titan_saved_state CHAR(5) DEFAULT NULL;\n");
        sql.append("    DECLARE __titan_saved_message TEXT DEFAULT NULL;\n");

        java.util.LinkedHashSet<String> catchVariables = new java.util.LinkedHashSet<>();
        if (node.catches() != null) {
            for (CatchClause catchClause : node.catches()) {
                if (catchClause.exceptionVariable() == null || catchClause.exceptionVariable().isBlank()) {
                    continue;
                }
                catchVariables.add(catchClause.exceptionVariable());
            }
        }
        for (String catchVariable : catchVariables) {
            sql.append("    DECLARE ").append(emittedVariableName(catchVariable)).append(" TEXT DEFAULT NULL;\n");
        }

        // Plan 2.2 (F-11 hardening, discovered by EmitterDeployabilityIT): the handlers live in
        // an inner block that contains ONLY the try body. A MySQL handler is in scope for every
        // statement of the block it is declared in — declared at the wrapper level it would also
        // intercept the unhandled-rethrow SIGNAL at the tail below, silently swallowing every
        // exception the catch dispatch did not handle. Scoping the handlers to the inner block
        // leaves dispatch, finally and the rethrow tail outside any active handler.
        //
        // Plan 3.1 (E-5, proven live by EmitterDeployabilityIT#tryBlockStatementsAfterAFailure-
        // MustNotExecute): the handlers are EXIT handlers, not CONTINUE. A CONTINUE handler
        // resumes at the NEXT try-body statement after intercepting the error, so statements
        // after the failing one still executed — diverging from Java (which aborts the rest of
        // the try block) and from PostgreSQL's nested-block semantics. An EXIT handler runs the
        // save-diagnostics body and then terminates this inner block, resuming at the catch
        // dispatch below — exactly Java's abort-then-dispatch order, while preserving the 2.2
        // GET-DIAGNOSTICS-first and rethrow-tail-outside-handler-scope invariants.
        sql.append("    BEGIN\n");
        if (node.catches() != null && !node.catches().isEmpty()) {
            java.util.LinkedHashSet<String> handledStates = new java.util.LinkedHashSet<>();
            for (CatchClause catchClause : node.catches()) {
                if (catchClause.sqlStates() == null) {
                    continue;
                }
                for (String sqlState : catchClause.sqlStates()) {
                    if (sqlState == null || sqlState.isBlank()) {
                        continue;
                    }
                    if (handledStates.add(sqlState)) {
                        sql.append("    DECLARE EXIT HANDLER FOR SQLSTATE '")
                                .append(escape(sqlState))
                                .append("'\n");
                        sql.append("    BEGIN\n");
                        // GET DIAGNOSTICS must run before any other statement: every
                        // non-diagnostic statement (including SET) replaces the current
                        // diagnostics area, after which MESSAGE_TEXT is unavailable
                        // (plan 2.2; surfaced by EmitterDeployabilityIT message assertions).
                        sql.append("        GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;\n");
                        sql.append("        SET __titan_saved_exception = TRUE;\n");
                        sql.append("        SET __titan_saved_state = '")
                                .append(escape(sqlState))
                                .append("';\n");
                        for (String catchVariable : catchVariables) {
                            sql.append("        SET ").append(emittedVariableName(catchVariable)).append(" = __titan_saved_message;\n");
                        }
                        sql.append("    END;\n");
                    }
                }
            }
        }

        sql.append("    DECLARE EXIT HANDLER FOR SQLEXCEPTION\n");
        sql.append("    BEGIN\n");
        // GET DIAGNOSTICS first — see the per-SQLSTATE handler comment above.
        sql.append("        GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;\n");
        sql.append("        SET __titan_saved_exception = TRUE;\n");
        for (String catchVariable : catchVariables) {
            sql.append("        SET ").append(emittedVariableName(catchVariable)).append(" = __titan_saved_message;\n");
        }
        sql.append("    END;\n");
        sql.append(indentMultiline(blockBody(node.tryBlock()))).append('\n');
        sql.append("    END;\n");
        if (node.catches() != null && !node.catches().isEmpty()) {
            sql.append("    IF __titan_saved_exception THEN\n");
            for (CatchClause catchClause : node.catches()) {
                sql.append("        IF ").append(renderSqlStateMembership("__titan_saved_state", catchClause.sqlStates())).append(" THEN\n");
                sql.append(indentMultiline(blockBody(catchClause.body()), 3)).append('\n');
                sql.append("            SET __titan_saved_exception = FALSE;\n");
                sql.append("            SET __titan_saved_state = NULL;\n");
                sql.append("        END IF;\n");
            }
            sql.append("    END IF;\n");
        }
        if (node.finallyBlock() != null) {
            sql.append(indentMultiline(blockBody(node.finallyBlock()))).append('\n');
        }
        // TITAN E-1 (plan 0.9): MySQL requires a string literal after SIGNAL SQLSTATE and only a
        // simple value (variable or literal, not an expression) for MESSAGE_TEXT, so the saved
        // dynamic SQLSTATE cannot be re-signaled here. RESIGNAL is not usable either: this rethrow
        // runs after the catch-dispatch IF-chain — outside any active handler now that the
        // handlers are scoped to the inner try-body block above (plan 2.2) — and re-signaling
        // from inside the CONTINUE handler would skip finally blocks. We therefore rethrow with
        // the fixed SQLSTATE '45000', preserving the original message via __titan_saved_message.
        sql.append("    IF __titan_saved_exception THEN\n");
        sql.append("        SET __titan_saved_message = IFNULL(__titan_saved_message, 'Unhandled exception in try block');\n");
        sql.append("        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message;\n");
        sql.append("    END IF;\n");
        // The wrapper is a nested compound statement inside the routine body; like every other
        // statement it must carry its own terminator or any following statement (including the
        // routine's RETURN) is a CREATE-time syntax error. Caught by EmitterDeployabilityIT.
        sql.append("END;");
        return sql.toString();
    }

    @Override
    public String visitInsertSql(InsertSql node) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quoteQualifiedName(node.table()));
        if (node.columns() != null && !node.columns().isEmpty()) {
            sql.append(" (")
                    .append(node.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
                    .append(')');
        }
        if (node.values() != null && !node.values().isEmpty()) {
            sql.append(" VALUES (")
                    .append(node.values().stream().map(v -> v.accept(this)).collect(Collectors.joining(", ")))
                    .append(')');
        } else if (node.selectSource() != null) {
            sql.append(' ').append(node.selectSource().accept(this));
        } else {
            // An all-defaults insert: bare "INSERT INTO t" is a MySQL syntax error; "VALUES ()"
            // inserts a row of column defaults (plan 0.11 / E-3).
            sql.append(" VALUES ()");
        }

        if (node.onConflict() != null) {
            if (node.onConflict().updates() != null && !node.onConflict().updates().isEmpty()) {
                sql.append(" ON DUPLICATE KEY UPDATE ");
                sql.append(node.onConflict().updates().stream()
                        .map(update -> quoteIdentifier(update.column()) + " = " + update.value().accept(this))
                        .collect(Collectors.joining(", ")));
            } else {
                // ON CONFLICT DO NOTHING has no direct MySQL equivalent; the closest semantic no-op
                // is "ON DUPLICATE KEY UPDATE <column> = <column>", which suppresses the
                // duplicate-key error without modifying the conflicting row (plan 0.11 / E-3).
                String noOpColumn = null;
                if (node.onConflict().columns() != null && !node.onConflict().columns().isEmpty()) {
                    noOpColumn = node.onConflict().columns().get(0);
                } else if (node.columns() != null && !node.columns().isEmpty()) {
                    noOpColumn = node.columns().get(0);
                }
                if (noOpColumn == null) {
                    throw new UnsupportedOperationException(
                            "TITAN-E001: ON CONFLICT DO NOTHING on table '" + node.table()
                                    + "' cannot be emitted for MySQL without a conflict target or insert column list; "
                                    + "specify the conflict column(s) so a no-op ON DUPLICATE KEY UPDATE can be generated.");
                }
                sql.append(" ON DUPLICATE KEY UPDATE ")
                        .append(quoteIdentifier(noOpColumn)).append(" = ").append(quoteIdentifier(noOpColumn));
            }
        }

        if (node.returning() != null && !node.returning().isEmpty()) {
            String returningAlias = node.returning().get(0);
            sql.append("; SELECT LAST_INSERT_ID() AS ").append(quoteIdentifier(returningAlias));
        }
        return sql.toString();
    }

    @Override
    public String visitUpdateSql(UpdateSql node) {
        if (node.returning() != null && !node.returning().isEmpty()) {
            throw new UnsupportedOperationException(
                    "TITAN-E001: UPDATE ... RETURNING on table '" + node.table()
                            + "' is not supported by MySQL; re-read the affected rows with a separate SELECT.");
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(quoteQualifiedName(node.table())).append(" SET ");
        sql.append(node.sets().stream()
                .map(set -> quoteIdentifier(set.column()) + " = " + set.value().accept(this))
                .collect(Collectors.joining(", ")));
        if (node.where() != null) {
            sql.append(" WHERE ").append(node.where().accept(this));
        }
        return sql.toString();
    }

    @Override
    public String visitDeleteSql(DeleteSql node) {
        if (node.returning() != null && !node.returning().isEmpty()) {
            throw new UnsupportedOperationException(
                    "TITAN-E001: DELETE ... RETURNING on table '" + node.table()
                            + "' is not supported by MySQL; select the rows before deleting them instead.");
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quoteQualifiedName(node.table()));
        if (node.where() != null) {
            sql.append(" WHERE ").append(node.where().accept(this));
        }
        return sql.toString();
    }

    /**
     * WS-C Phase 3 Rung 4 (§3.6 form 2) — expands each array-bind membership marker in a {@link RawSql}'s
     * text into MySQL's native form {@code <lhs> IN (SELECT v FROM JSON_TABLE(?, '$[*]' COLUMNS (v
     * <sqlType> PATH '$')) t)}. The {@code ?} is bound to the whole collection as ONE JSON-array string
     * parameter (the caller serializes the list to a JSON array); {@code JSON_TABLE} explodes it into a
     * derived table of typed scalars the {@code IN} matches against. {@code JSON_TABLE('[]', …)} yields no
     * rows → matches nothing, exactly as an empty {@code IN ()} would. The {@code COLUMNS} column type comes
     * from the element type ({@code BIGINT}/{@code INT}/{@code LONGTEXT} — see {@link #jsonTableColumnType}),
     * so {@code Long}→{@code BIGINT} is exact (no lossy cast) and a {@code String} keeps its full length.
     * Returns the text unchanged when there are no array binds (the scalar path).
     */
    private String expandArrayBindMarkers(RawSql node) {
        String sql = node.sql();
        for (ArrayBind bind : node.arrayBinds()) {
            String columnType = jsonTableColumnType(bind.elementType());
            String membership = bind.lhs() + " IN (SELECT v FROM JSON_TABLE(?, '$[*]' COLUMNS (v "
                    + columnType + " PATH '$')) t)";
            sql = sql.replace(RawSql.arrayBindMarker(bind.markerId()), membership);
        }
        return sql;
    }

    /**
     * The MySQL {@code PREPARE … FROM} source for a {@link RawSql}: a {@link #prelude} (statements emitted
     * before the {@code PREPARE}) and the {@link #fromToken} the {@code PREPARE … FROM} reads.
     */
    private record DynamicSqlSource(String prelude, String fromToken) {}

    /**
     * Builds the {@link DynamicSqlSource} for {@code rewrittenSql} (already {@code ?}-kept, {@code
     * INTO}-injected). With no identifier/raw-fragment splice it is {@code ("", '<escaped sql>')} — the
     * unchanged static literal. WS-C Phase 3 Rung 3 ({@code permissive} only): the statement text is
     * assembled at <b>runtime</b> with {@code CONCAT(...)} — each literal segment between splice markers is
     * a quoted {@code '<escaped>'} operand (its {@code ?} placeholders kept, bound later via {@code USING
     * @p}), and each splice marker becomes:
     * <ul>
     *   <li>{@link SpliceBind.Kind#IDENTIFIER}: {@code CONCAT('`', REPLACE(<param>, '`', '``'), '`')} —
     *       a runtime backtick-quote that doubles embedded backticks (the contract's catalog-validated
     *       backtick; the safest faithful MySQL identifier quoting, since {@code %I} has no equivalent);</li>
     *   <li>{@link SpliceBind.Kind#RAW_FRAGMENT}: the bare {@code <param>} operand — spliced verbatim
     *       (the source's own exposure faithfully reproduced).</li>
     * </ul>
     * Because MySQL {@code PREPARE … FROM} accepts only a string literal or a USER VARIABLE (never a {@code
     * CONCAT} expression), the assembled {@code CONCAT} is staged into {@code @titan_dsql_<n>} (the prelude)
     * and the token is that variable. The {@code ?} value placeholders stay in the literal segments and
     * bind via {@code EXECUTE … USING @p} (only the identifier/fragment is spliced into the text — values
     * stay bound). Empty edge segments are dropped so {@code CONCAT} has no spurious {@code ''} operands.
     */
    private DynamicSqlSource dynamicSqlSource(String rewrittenSql, RawSql node, int counter) {
        if (!node.hasSpliceBinds()) {
            return new DynamicSqlSource("", "'" + escape(rewrittenSql) + "'");
        }
        Map<String, SpliceBind> byMarker = new HashMap<>();
        for (SpliceBind splice : node.spliceBinds()) {
            byMarker.put(RawSql.spliceMarker(splice.markerId()), splice);
        }
        List<String> operands = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < rewrittenSql.length()) {
            SpliceBind matched = null;
            String matchedMarker = null;
            for (Map.Entry<String, SpliceBind> entry : byMarker.entrySet()) {
                if (rewrittenSql.startsWith(entry.getKey(), i)) {
                    matched = entry.getValue();
                    matchedMarker = entry.getKey();
                    break;
                }
            }
            if (matched == null) {
                literal.append(rewrittenSql.charAt(i));
                i++;
                continue;
            }
            if (literal.length() > 0) {
                operands.add("'" + escape(literal.toString()) + "'");
                literal.setLength(0);
            }
            operands.add(spliceConcatOperand(matched));
            i += matchedMarker.length();
        }
        if (literal.length() > 0) {
            operands.add("'" + escape(literal.toString()) + "'");
        }
        // MySQL PREPARE … FROM accepts only a string literal or a USER VARIABLE, never a CONCAT expression
        // — so stage the assembled CONCAT into @titan_dsql_<n> (the prelude) and PREPARE from that variable.
        String dynVar = "@titan_dsql_" + counter;
        String prelude = "SET " + dynVar + " = CONCAT(" + String.join(", ", operands) + "); ";
        return new DynamicSqlSource(prelude, dynVar);
    }

    /**
     * One {@code CONCAT} operand for an identifier / raw-fragment splice (WS-C Phase 3 Rung 3, MySQL). An
     * IDENTIFIER is backtick-quoted at runtime with embedded backticks doubled; a RAW_FRAGMENT is the bare
     * routine-local value (verbatim).
     *
     * <p>The IDENTIFIER quoting is <b>qualified-name aware</b> (WS-C Phase 3 Rung 3 audit fix, Findings 2):
     * a bare {@code CONCAT('`', REPLACE(v,'`','``'), '`')} backtick-quotes a dotted {@code billing.accounts}
     * as ONE identifier ({@code `billing.accounts`}) and fails ("Table 'db.billing.accounts' doesn't
     * exist"). The fix doubles embedded backticks FIRST, then rewrites every {@code .} separator to {@code
     * `.`} before wrapping — turning {@code billing.accounts} into {@code `billing`.`accounts`} and a bare
     * {@code accounts} into {@code `accounts`} (the unqualified case is byte-identical to the old form).
     * This mirrors the compile-time split-on-dot {@code quoteQualifiedName}, for any number of segments.</p>
     */
    private String spliceConcatOperand(SpliceBind splice) {
        String param = emittedVariableName(splice.paramName());
        if (splice.kind() == SpliceBind.Kind.IDENTIFIER) {
            // REPLACE backticks (-> doubled) BEFORE rewriting '.' separators, so an embedded backtick in a
            // segment is escaped but the segment-separating dots become `.` boundaries.
            return "CONCAT('`', REPLACE(REPLACE(" + param + ", '`', '``'), '.', '`.`'), '`')";
        }
        return param;
    }

    /**
     * The MySQL {@code JSON_TABLE} {@code COLUMNS} declared column type for a collection element type.
     * These are <b>column-type spellings</b>, NOT {@code CAST(... AS ...)} target keywords: a {@code
     * COLUMNS} clause declares each extracted value with a real column type, so {@code SIGNED} (a CAST
     * target, rejected with ERROR 1064) and {@code CHAR(1000)} (a CHAR column exceeds the 255-char cap,
     * ERROR 1074) are both invalid here — the values must use {@code INT}/{@code BIGINT}/{@code LONGTEXT}.
     * This matches the existing JSON_TABLE iteration path ({@link #sqlType}: {@code INT}/{@code BIGINT}/
     * {@code TEXT}), but a text element uses {@code LONGTEXT} (not {@code TEXT}) so that an element longer
     * than {@code TEXT}'s 65 535-byte cap is never truncated to NULL — keeping the membership
     * row-equivalent to PostgreSQL's unbounded {@code text[]} {@code = ANY($1)} and to the original
     * {@code IN (?,…)} for any string length. {@code BIGINT} preserves 64-bit ids exactly.
     */
    private static String jsonTableColumnType(TirType elementType) {
        return switch (elementType) {
            case TBigintType ignored -> "BIGINT";
            case TIntType ignored -> "INT";
            case TTextType ignored -> "LONGTEXT";
            // UUID elements are the canonical 36-char string; CHAR(36) is well under the 255-char CHAR
            // cap, so it is a valid JSON_TABLE COLUMNS type and matches the scalar UUID spelling.
            case TUuidType ignored -> "CHAR(36)";
            // The recognizer admits only BIGINT/INT/TEXT/UUID element types today; a future scalar element
            // type must map to a column type valid in a JSON_TABLE COLUMNS clause (LONGTEXT is the safe,
            // truncation-free default — NOT CHAR(1000), which is rejected as too wide).
            default -> "LONGTEXT";
        };
    }

    @Override
    public String visitRawSql(RawSql node) {
        String statementName = "titan_stmt_" + (++rawSqlCounter);
        // WS-C Phase 2: a JDBC-input RawSql carries `?` positional placeholders; MySQL PREPARE keeps
        // `?` (bound by USING @p), so this rewrite is identity on MySQL — applied for symmetry with the
        // PostgreSQL path; `@SQL` named-parameter text (`:name`) is handled by rewriteNamedParameters.
        // WS-C Phase 3 Rung 4: a collection bind's membership marker is first expanded to the native
        // JSON_TABLE form (its `?` then bound as ONE JSON-array string parameter via USING @p).
        String rewrittenSql = rewritePositionalPlaceholders(
                rewriteNamedParameters(expandArrayBindMarkers(node), node.parameters()));
        // WS-C Phase 3 Rung 3 (permissive only): an identifier/raw-fragment splice assembles the statement
        // text at runtime via CONCAT — which MySQL PREPARE cannot read directly (PREPARE … FROM accepts only
        // a string literal or a USER VARIABLE, not an expression/local). So the CONCAT is STAGED into a
        // @titan_dsql_n session variable first; the FROM token is that variable. The static path keeps the
        // plain '<sql>' literal. The value binds below still bind via USING @p (only the identifier/fragment
        // is spliced into the text).
        DynamicSqlSource source = dynamicSqlSource(rewrittenSql, node, rawSqlCounter);

        if (node.parameters() == null || node.parameters().isEmpty()) {
            return source.prelude() + "PREPARE " + statementName + " FROM " + source.fromToken()
                    + "; EXECUTE " + statementName + "; DEALLOCATE PREPARE " + statementName;
        }

        List<String> userVariables = new java.util.ArrayList<>();
        List<String> assignments = new java.util.ArrayList<>();
        for (int i = 0; i < node.parameters().size(); i++) {
            String userVar = "@titan_p" + (i + 1);
            String parameter = emittedVariableName(node.parameters().get(i));
            userVariables.add(userVar);
            assignments.add("SET " + userVar + " = " + parameter + ";");
        }

        return source.prelude() + String.join(" ", assignments)
                + " PREPARE " + statementName + " FROM " + source.fromToken() + "; EXECUTE " + statementName
                + " USING " + String.join(", ", userVariables)
                + "; DEALLOCATE PREPARE " + statementName;
    }

    /**
     * Native single-dialect insert-with-generated-key recovery (JDBC I-7 §6.3 / I-R8). MySQL has no
     * {@code RETURNING}, so this emits the INSERT immediately followed by
     * {@code SET <keyLocal> = LAST_INSERT_ID();} — exactly what the MySQL JDBC driver returns for
     * {@code getGeneratedKeys()}. {@code LAST_INSERT_ID()} is the connection's last AUTO_INCREMENT value
     * for the most recent INSERT on <i>this</i> session; an {@code AFTER INSERT} trigger's own
     * inserts/sequences do not perturb it, so the recovered key is the actual inserted row's PK. The
     * {@code keyColumn} is resolved from the Catalog by the lowerer (the JDBC ordinal {@code 1} does not
     * name a column) but is not needed in the emitted SQL — {@code LAST_INSERT_ID()} names no column.
     *
     * <p>The INSERT is emitted as a <b>static</b> statement (NOT the dynamic {@code PREPARE}/{@code
     * EXECUTE} of {@link #visitRawSql}): MySQL forbids dynamic SQL inside a stored FUNCTION, and §6.3
     * {@code create_invoice} is a function (it returns the recovered key). The constant INSERT's
     * {@code ?} binds are spliced as the bound routine-local <b>identifiers</b> (via
     * {@link #inlineCursorBinds}) — typed parameter references, never literals (injection-safe) — the
     * same static-binding the MySQL static cursor uses. No {@code FROM DUAL} hack, no {@code INTO}-target
     * injection.</p>
     */
    @Override
    public String visitGeneratedKeyReadStatement(GeneratedKeyReadStatement node) {
        String insert = inlineCursorBinds(node.insert());
        return insert + "; SET " + emittedVariableName(node.keyLocal()) + " = LAST_INSERT_ID();";
    }

    /**
     * Native single-dialect single-row read over opaque source SQL (JDBC I-4 unparsed, WS-C Phase 2
     * §4 step 3). MySQL has no dynamic {@code EXECUTE … INTO}, so the result columns are captured by
     * embedding {@code INTO @titan_r1, …} inside the PREPARE text and staging through session
     * variables, then copied to the routine locals after DEALLOCATE:
     *
     * <pre>
     * SET @titan_r1 = NULL; …          -- pre-init every target (decision 4: NULL-on-no-row)
     * SET @titan_p1 = &lt;var&gt;; …         -- stage bind values
     * PREPARE s FROM 'SELECT … INTO @titan_r1,… FROM …';
     * EXECUTE s USING @titan_p1, …;    -- INTO is in the text, NOT on EXECUTE
     * DEALLOCATE PREPARE s;
     * SET v_a = @titan_r1; …           -- copy session vars to locals
     * </pre>
     *
     * <p>The {@code INTO} is injected before the first top-level {@code ' FROM '} of a <b>simple</b>
     * plain projection only (a {@code SELECT}-list then {@code FROM}, decision 2); any shape that could mis-fire (a subquery
     * in the select list, {@code EXTRACT(.. FROM ..)}, a {@code FROM} inside a string literal, or no
     * top-level FROM) is a hard {@code TITAN-E001} — never mis-emitted.</p>
     */
    @Override
    public String visitRawReadIntoStatement(RawReadIntoStatement node) {
        String statementName = "titan_stmt_" + (++rawSqlCounter);
        // Input hygiene: a single trailing ';' in the opaque source text would otherwise survive into
        // the PREPARE literal as an internal statement terminator. 2b normally strips it; defend here
        // too so the 2a emitter is self-contained. WS-C Phase 3 Rung 4: expand a collection-IN membership
        // marker to the JSON_TABLE form first (its nested FROM sits at depth>0 inside the JSON_TABLE
        // subquery, so injectIntoTargets still finds the outer FROM and injects INTO before it).
        String rewrittenSql = rewritePositionalPlaceholders(
                stripTrailingSemicolon(expandArrayBindMarkers(node.query())));

        List<String> resultVars = new ArrayList<>();
        for (int i = 0; i < node.variableNames().size(); i++) {
            resultVars.add("@titan_r" + (i + 1));
        }
        String injectedSql = injectIntoTargets(rewrittenSql, resultVars);

        List<String> lines = new ArrayList<>();
        // Pre-init every result session var to NULL so an unmatched row yields NULL (decision 4).
        for (String resultVar : resultVars) {
            lines.add("SET " + resultVar + " = NULL;");
        }

        List<String> parameters = node.query().parameters();
        List<String> userVariables = new ArrayList<>();
        if (parameters != null) {
            for (int i = 0; i < parameters.size(); i++) {
                String userVar = "@titan_p" + (i + 1);
                userVariables.add(userVar);
                lines.add("SET " + userVar + " = " + emittedVariableName(parameters.get(i)) + ";");
            }
        }

        // WS-C Phase 3 Rung 3: dynamicSqlSource stages a runtime CONCAT('… ', <quoted/raw expr>, ' …') into
        // @titan_dsql_<n> for an identifier/raw-fragment splice (permissive) — MySQL PREPARE cannot read a
        // CONCAT directly — then PREPAREs from that variable; else it is the static '<sql>' literal with no
        // prelude. The INTO @r targets are already injected into injectedSql (before the FROM, ahead of any
        // splice marker).
        DynamicSqlSource readSource = dynamicSqlSource(injectedSql, node.query(), rawSqlCounter);
        if (!readSource.prelude().isBlank()) {
            lines.add(readSource.prelude().strip());
        }
        lines.add("PREPARE " + statementName + " FROM " + readSource.fromToken() + ";");
        if (userVariables.isEmpty()) {
            lines.add("EXECUTE " + statementName + ";");
        } else {
            lines.add("EXECUTE " + statementName + " USING " + String.join(", ", userVariables) + ";");
        }
        lines.add("DEALLOCATE PREPARE " + statementName + ";");

        // Copy the staged session vars into the routine locals after the prepared statement is gone.
        for (int i = 0; i < node.variableNames().size(); i++) {
            lines.add("SET " + emittedVariableName(node.variableNames().get(i)) + " = " + resultVars.get(i) + ";");
        }
        String body = String.join("\n", lines);
        if (node.notFoundRaise() == null) {
            return body;
        }
        // I-4 §6.1 early-return guard: FOUND-based, NOT a "first INTO target IS NULL" proxy. MySQL
        // SELECT ... INTO does not set a usable ROW_COUNT()/FOUND flag, so a dedicated found flag is
        // driven by a CONTINUE HANDLER FOR NOT FOUND (the same scaffold the static cursor uses): the
        // handler fires only when the embedded-INTO read matched no row, flipping the flag to FALSE.
        // An existing row with a NULL first column therefore does NOT trip the guard (audit fix).
        String foundFlag = routineSqlNameAllocator.allocateGeneratedName("titan_found");
        return "BEGIN\n"
                + "    DECLARE " + foundFlag + " BOOLEAN DEFAULT TRUE;\n"
                + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET " + foundFlag + " = FALSE;\n"
                + indentMultiline(body) + "\n"
                + "    IF NOT " + foundFlag + " THEN\n"
                + indentMultiline(visitRaiseStatement(node.notFoundRaise()), 2) + "\n"
                + "    END IF;\n"
                + "END;";
    }

    /**
     * Injects {@code INTO @titan_r1, …} before the first top-level {@code FROM} of a simple
     * {@code SELECT <list> FROM …} (WS-C Phase 2 §4, decision 2 / riskiest change). Parser-free but
     * defensive: scans char-by-char skipping string literals, backtick identifiers and comments,
     * tracking parenthesis depth. The injection point is the first {@code FROM} keyword at depth 0;
     * if a {@code FROM} or {@code SELECT} keyword is seen at depth &gt; 0 before it (a select-list
     * subquery or {@code EXTRACT(.. FROM ..)}), if the text does not begin with {@code SELECT}, or
     * if no top-level {@code FROM} exists, the read is rejected with {@code TITAN-E001} rather than
     * mis-emitting.
     *
     * <p>After the injection point is located the depth-0 scan continues to the end of the text: a
     * depth-0 set-operation keyword ({@code UNION}/{@code INTERSECT}/{@code EXCEPT}) or a second
     * depth-0 {@code SELECT} means the statement is not a single {@code SELECT <list> FROM …} block
     * (e.g. {@code SELECT a FROM t UNION SELECT b FROM u}). Splicing {@code INTO} before the first
     * {@code FROM} would put it in a non-final {@code UNION} leg, which is a MySQL syntax error, so
     * the shape is rejected with {@code TITAN-E001} rather than silently mis-emitted — this is the
     * documented emitter safety boundary (decision 2: simple-shape-only, reject otherwise).</p>
     */
    private String injectIntoTargets(String sql, List<String> resultVars) {
        String into = "INTO " + String.join(", ", resultVars);
        String trimmedLeading = sql.stripLeading();
        if (!startsWithKeyword(trimmedLeading, "SELECT")) {
            throw new UnsupportedOperationException(
                    "TITAN-E001: MySQL single-row read requires a simple 'SELECT <list> FROM ...' shape to "
                            + "inject 'INTO' without a SQL parser, but the statement does not begin with SELECT: '"
                            + sql + "'. Rewrite it as a plain projection, or move the logic into a typed DSL/@SQL read.");
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int depth = 0;
        int injectionPoint = -1;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"' && next == '"') {
                    i++;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }

            switch (c) {
                case '\'' -> inSingleQuote = true;
                case '"' -> inDoubleQuote = true;
                case '`' -> inBacktick = true;
                case '(' -> depth++;
                case ')' -> depth--;
                case '-' -> {
                    if (next == '-') {
                        inLineComment = true;
                        i++;
                    }
                }
                case '/' -> {
                    if (next == '*') {
                        inBlockComment = true;
                        i++;
                    }
                }
                case 'f', 'F' -> {
                    if (isKeywordAt(sql, i, "FROM")) {
                        if (depth == 0) {
                            if (injectionPoint < 0) {
                                // First top-level FROM: remember where INTO goes, but keep scanning
                                // the rest of the depth-0 text to reject any set-operation tail.
                                injectionPoint = i;
                            }
                            // A later depth-0 FROM belongs to a second SELECT block (e.g. a UNION
                            // leg); the depth-0 set-op / SELECT guards below reject that shape first.
                        } else if (injectionPoint < 0) {
                            // A FROM nested in parentheses before any top-level FROM — EXTRACT(.. FROM ..)
                            // or a select-list subquery. Cannot safely splice; reject hard.
                            throw rejectComplexShape(sql);
                        }
                    }
                }
                case 's', 'S' -> {
                    if (isKeywordAt(sql, i, "SELECT")) {
                        if (depth > 0 && injectionPoint < 0) {
                            // A subquery opened inside the select list before the top-level FROM.
                            throw rejectComplexShape(sql);
                        }
                        if (depth == 0 && injectionPoint >= 0) {
                            // A second top-level SELECT after the first block — a set-operation tail
                            // (UNION/INTERSECT/EXCEPT SELECT …) or a malformed multi-statement.
                            throw rejectSetOperation(sql);
                        }
                    }
                }
                case 'u', 'U', 'i', 'I', 'e', 'E' -> {
                    if (depth == 0 && injectionPoint >= 0
                            && (isKeywordAt(sql, i, "UNION")
                                    || isKeywordAt(sql, i, "INTERSECT")
                                    || isKeywordAt(sql, i, "EXCEPT"))) {
                        // A depth-0 set operation after the first SELECT...FROM block: injecting INTO
                        // before the first FROM would place it in a non-final UNION leg (MySQL syntax
                        // error). Outside the simple single-block shape (decision 2) — reject hard.
                        throw rejectSetOperation(sql);
                    }
                }
                default -> {
                    // ordinary character
                }
            }
        }

        if (injectionPoint >= 0) {
            return sql.substring(0, injectionPoint) + into + " " + sql.substring(injectionPoint);
        }

        throw new UnsupportedOperationException(
                "TITAN-E001: MySQL single-row read requires a top-level ' FROM ' to inject 'INTO', but none was "
                        + "found in '" + sql + "'. A simple 'SELECT <list> FROM <table> ...' is required; rewrite the "
                        + "statement or use a typed DSL/@SQL read.");
    }

    private UnsupportedOperationException rejectSetOperation(String sql) {
        return new UnsupportedOperationException(
                "TITAN-E001: MySQL single-row read could not safely inject 'INTO' into '" + sql + "': the statement "
                        + "is a set operation (UNION/INTERSECT/EXCEPT) or spans more than one SELECT block, so 'INTO' "
                        + "would land in a non-final query leg (a MySQL syntax error). Only a simple 'SELECT <list> "
                        + "FROM <table> ...' is supported without a SQL parser; rewrite it as a plain projection or use "
                        + "a typed DSL/@SQL read.");
    }

    /**
     * Trims a single trailing {@code ;} (and the whitespace around it) from opaque source SQL so the
     * MySQL raw read/cursor emitters never embed an internal statement terminator in the {@code PREPARE}
     * literal or {@code DECLARE … CURSOR FOR} text. Only one terminator is stripped (a {@code ;;} or a
     * {@code ;} mid-text is left alone — that is a genuinely multi-statement / malformed input, not
     * trailing punctuation). Pure input hygiene mirroring what 2b lowering normally does.
     */
    private static String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.stripTrailing();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return sql;
    }

    private UnsupportedOperationException rejectComplexShape(String sql) {
        return new UnsupportedOperationException(
                "TITAN-E001: MySQL single-row read could not safely inject 'INTO' into '" + sql + "': the select "
                        + "list contains a nested subquery or a FROM inside parentheses (e.g. EXTRACT(.. FROM ..)). "
                        + "Only a simple 'SELECT <list> FROM <table> ...' is supported without a SQL parser; rewrite "
                        + "it as a plain projection or use a typed DSL/@SQL read.");
    }

    /** True if {@code keyword} occurs at {@code index} as a whole word (not a prefix of an identifier). */
    private static boolean isKeywordAt(String sql, int index, String keyword) {
        if (!sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        if (index > 0 && isIdentifierChar(sql.charAt(index - 1))) {
            return false;
        }
        int after = index + keyword.length();
        return after >= sql.length() || !isIdentifierChar(sql.charAt(after));
    }

    private static boolean startsWithKeyword(String sql, String keyword) {
        return isKeywordAt(sql, 0, keyword);
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    /**
     * Native single-dialect multi-row read over <b>constant</b> source SQL (JDBC I-5 unparsed, WS-C
     * Phase 2 §4 step 3, decision 1). MySQL static cursors cannot iterate runtime-built text, so the
     * constant SQL is inlined directly into a static {@code DECLARE <cur> CURSOR FOR <text>}, reusing
     * the cursor safety scaffold copied from {@link #visitForCursorStatement} (done flag, open flag,
     * EXIT HANDLER FOR SQLEXCEPTION that closes-if-open and RESIGNALs, CONTINUE HANDLER FOR NOT FOUND,
     * labeled LOOP with {@code FETCH … INTO <vars>} and {@code IF done LEAVE}). The {@code ?}
     * placeholders in the cursor text are rewritten to the bound routine-local <b>names</b> spliced
     * as identifier references (a typed column/local reference, never a value — injection-safe).
     */
    @Override
    public String visitRawCursorStatement(RawCursorStatement node) {
        String fetchTargets = node.variableNames().stream()
                .map(this::emittedVariableName)
                .collect(Collectors.joining(", "));
        String base = sanitizeIdentifier(node.variableNames().isEmpty()
                ? "row"
                : emittedVariableName(node.variableNames().getFirst()));
        String cursorName = routineSqlNameAllocator.allocateGeneratedName("cur_" + base);
        String doneFlag = routineSqlNameAllocator.allocateGeneratedName("done_" + base);
        String cursorOpenFlag = routineSqlNameAllocator.allocateGeneratedName("cursor_open_" + base);
        String loopLabel = pushLoopLabel((node.label() == null || node.label().isBlank())
                ? routineSqlNameAllocator.allocateGeneratedName("read_" + base)
                : node.label());

        // MySQL requires every DECLARE at the start of the enclosing BEGIN ... END, and the FETCH runs
        // at THIS block's scope — so the per-row FETCH-target locals (which the JDBC lowerer attaches to
        // node.body().declarations(), the same names as node.variableNames()) MUST be DECLAREd up here
        // beside the cursor/handler/flags, NOT inside the loop-body sub-block. Declaring them only in
        // the body would put them out of scope at the FETCH (MySQL "Undeclared variable" at CREATE). We
        // therefore hoist those declarations and render the body with them removed so they are not
        // double-declared. (PostgreSQL has no analogue: its FETCH targets are routine-level DECLAREs.)
        Set<String> fetchTargetNames = new LinkedHashSet<>(node.variableNames());
        StringBuilder hoistedTargetDeclares = new StringBuilder();
        for (DeclarationNode declaration : node.body().declarations()) {
            if (declaration instanceof DeclareVariable variable && fetchTargetNames.contains(variable.name())) {
                hoistedTargetDeclares.append("    ")
                        .append(mySqlDeclarationLine(variable, false))
                        .append('\n');
            }
        }
        Block bodyWithoutFetchTargets = new Block(
                node.body().declarations().stream()
                        .filter(declaration -> !(declaration instanceof DeclareVariable variable
                                && fetchTargetNames.contains(variable.name())))
                        .toList(),
                node.body().statements(),
                node.body().exceptionHandlers());

        String bodySql;
        try {
            bodySql = indentMultiline(blockBody(bodyWithoutFetchTargets));
        } finally {
            popLoopLabel();
        }

        String cursorText = inlineCursorBinds(node.query());

        return "BEGIN\n"
                + "    DECLARE " + doneFlag + " BOOLEAN DEFAULT FALSE;\n"
                + "    DECLARE " + cursorOpenFlag + " BOOLEAN DEFAULT FALSE;\n"
                + hoistedTargetDeclares
                + "    DECLARE " + cursorName + " CURSOR FOR " + cursorText + ";\n"
                + "    DECLARE EXIT HANDLER FOR SQLEXCEPTION\n"
                + "    BEGIN\n"
                + "        IF " + cursorOpenFlag + " THEN CLOSE " + cursorName + "; END IF;\n"
                + "        RESIGNAL;\n"
                + "    END;\n"
                + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET " + doneFlag + " = TRUE;\n"
                + "    OPEN " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = TRUE;\n"
                + "    " + loopLabel + ": LOOP\n"
                + "        FETCH " + cursorName + " INTO " + fetchTargets + ";\n"
                + "        IF " + doneFlag + " THEN LEAVE " + loopLabel + "; END IF;\n"
                + bodySql + "\n"
                + "    END LOOP " + loopLabel + ";\n"
                + "    CLOSE " + cursorName + ";\n"
                + "    SET " + cursorOpenFlag + " = FALSE;\n"
                + "END;";
    }

    /**
     * Native single-dialect unknown-shape result carrier (JDBC Tier-3, WS-C Phase 3 Rung 5; {@code
     * docs/transpilable-jdbc-subset.md} §3.6 / design contract D5). The pure metadata-driven generic
     * reader ({@code while (rs.next()) { row.put(md.getColumnLabel(i), rs.getObject(i)); ... }} →
     * {@code List<Map<String,Object>>}) lowers, on MySQL, to a <b>native open result set</b>: the dynamic
     * {@code SELECT} is {@code PREPARE}/{@code EXECUTE}d and the result set is left OPEN — no {@code INTO},
     * no aggregation — so MySQL streams the rows directly to the client (the regenerated caller reads them
     * as a {@code ResultSet}, deserializing the {@code List<Map>}). This is exactly the {@link
     * #visitRawSql} {@code PREPARE … EXECUTE … DEALLOCATE} body: the {@code EXECUTE} of a {@code SELECT}
     * statement emits its result set to the client (the subsequent {@code DEALLOCATE} only frees the
     * prepared handle — the rows have already been sent). The {@code ?} value binds bind via {@code @p}
     * exactly as for any other dynamic statement (constant text under strict, runtime {@code CONCAT} for a
     * permissive splice).
     *
     * <p>A dynamic-return MySQL routine MUST be a {@code @StoredProcedure} (MySQL forbids dynamic SQL in a
     * {@code FUNCTION}, ERROR 1336 — §9 invariant 1); the pipeline emits a carrier {@code @StoredFunction}
     * as a procedure on MySQL so this open result set is legal.</p>
     *
     * <p><b>Duplicate output labels (WS-C Phase 3 Rung 5 audit — documented divergence).</b> A SELECT with
     * two same-named output columns ({@code SELECT a.id, b.id, …}) streams BOTH columns in the result set,
     * so the MySQL carrier surfaces more columns than the source Java {@code Map.put} would (which
     * collapses a duplicate key last-wins to one entry). This diverges from the PostgreSQL jsonb carrier
     * ({@code to_jsonb} collapses the duplicate field last-wins, matching {@code Map.put}) and from the
     * {@code List<Map>} the carrier reproduces. It is an unusual generic-reader input (and the regenerated
     * MySQL caller consumes a {@code ResultSet}, not a {@code Map}, so its de-dup is the caller's by
     * construction); a future tightening could reject duplicate-labelled carriers. The common, single-name
     * case is faithful on both dialects.</p>
     */
    @Override
    public String visitDynamicResultStatement(DynamicResultStatement node) {
        // The result set streams from EXECUTE; no INTO injection (that is the single-row read path),
        // no cursor — exactly the dynamic PREPARE/EXECUTE/DEALLOCATE of a bare statement. The terminating
        // ';' is the emitter's per-statement terminator (visitRawSql's compound text is ';'-separated
        // internally but not ';'-terminated, exactly as visitExecuteSqlStatement appends it).
        return visitRawSql(node.query()) + ";";
    }

    /**
     * Inlines the {@code ?} binds of a static MySQL cursor's constant text as the bound routine-local
     * <b>identifiers</b> (never values): each {@code ?} becomes the emitted name of the corresponding
     * {@code query.parameters()} entry, spliced as a typed column/local reference. This is safe (the
     * value never reaches the text as a literal) and is the only way a MySQL static cursor can carry a
     * bind, since a static {@code DECLARE … CURSOR FOR} cannot be parameterized.
     */
    private String inlineCursorBinds(RawSql query) {
        // WS-C Phase 3 Rung 3: a MySQL multi-row read lowers to a STATIC `DECLARE … CURSOR FOR <sql>`,
        // whose text is fixed at CREATE time and cannot be assembled at runtime — so a runtime
        // identifier/raw-fragment splice (a permissive `while(rs.next())` with a dynamic ORDER BY/column)
        // has no faithful MySQL cursor form (unlike PostgreSQL's `OPEN … FOR EXECUTE format(...)`). Reject
        // clearly rather than emit a corrupted static cursor (the splice marker would survive into the
        // CURSOR text). The honest migration steer: a single-row read / UPDATE (PREPARE/EXECUTE supports
        // the splice), or PostgreSQL. (Strict never reaches here — it rejects the splice at lowering.)
        if (query.hasSpliceBinds()) {
            throw new UnsupportedOperationException(
                    "TITAN-E001: MySQL cannot splice a runtime identifier/raw fragment into a multi-row "
                            + "cursor read — a static 'DECLARE … CURSOR FOR' is fixed at CREATE time and "
                            + "cannot be assembled at runtime. Use a single-row read or an UPDATE (which "
                            + "lower to PREPARE/EXECUTE and support the splice), validate the identifier "
                            + "against a known set, or target PostgreSQL (OPEN … FOR EXECUTE format('%I', …)).");
        }
        // Input hygiene: a single trailing ';' would emit 'DECLARE ... CURSOR FOR SELECT ...;;'
        // (double terminator). 2b normally strips it; defend here so the 2a static-cursor emitter is
        // self-contained. Applied before the no-bind early return so a static cursor is trimmed too.
        // WS-C Phase 3 Rung 4: expand a collection-IN membership marker to the JSON_TABLE form first; the
        // JSON-array param `?` it introduces is then spliced as the JSON routine-local identifier (a
        // static cursor cannot parameterize, but JSON_TABLE(<json_local>, …) reads the JSON value directly).
        String sql = stripTrailingSemicolon(expandArrayBindMarkers(query));
        List<String> parameters = query.parameters();
        if (parameters == null || parameters.isEmpty() || sql == null || sql.indexOf('?') < 0) {
            return sql;
        }
        StringBuilder rewritten = new StringBuilder(sql.length());
        int ordinal = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inSingleQuote) {
                rewritten.append(c);
                if (c == '\\') {
                    if (next != '\0') {
                        rewritten.append(next);
                        i++;
                    }
                } else if (c == '\'' && next == '\'') {
                    rewritten.append(next);
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                // Symmetric with the single-quote branch above: MySQL backslash-escapes are on (the
                // escape() helper doubles backslashes), so a \" does not close the literal, and a
                // doubled "" is an escaped quote, not a close — otherwise a '?' inside a double-quoted
                // literal could be mis-rewritten as an identifier (or a real bind '?' skipped).
                rewritten.append(c);
                if (c == '\\') {
                    if (next != '\0') {
                        rewritten.append(next);
                        i++;
                    }
                } else if (c == '"' && next == '"') {
                    rewritten.append(next);
                    i++;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                rewritten.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            switch (c) {
                case '\'' -> inSingleQuote = true;
                case '"' -> inDoubleQuote = true;
                case '`' -> inBacktick = true;
                default -> {
                    // fall through
                }
            }
            if (c == '?') {
                if (ordinal >= parameters.size()) {
                    throw new UnsupportedOperationException(
                            "TITAN-E001: MySQL static cursor text has more '?' placeholders than bound parameters "
                                    + "in '" + sql + "'.");
                }
                rewritten.append(emittedVariableName(parameters.get(ordinal++)));
            } else {
                rewritten.append(c);
            }
        }
        return rewritten.toString();
    }

    @Override
    public String visitBinaryOpExpression(BinaryOpExpression node) {
        if (node.operator() == BinaryOperator.ADD && (isStringLike(node.left()) || isStringLike(node.right()))) {
            // B-10 (TG-BLK-012): only a boolean operand needs coercion to render 'true'/'false';
            // every other operand keeps its existing bare rendering (no golden churn).
            return "CONCAT(" + concatOperandSql(node.left()) + ", " + concatOperandSql(node.right()) + ")";
        }
        return super.visitBinaryOpExpression(node);
    }

    @Override
    public String visitFunctionCallExpression(FunctionCallExpression node) {
        if (NullAnalysisPass.COMPARE_TO_MARKER.equals(node.name())) {
            // N2 invariant: the compareTo marker must be rewritten (and null-guarded) by
            // NullAnalysisPass before emission; emitting it would silently change semantics.
            throw new IllegalStateException(
                    "internal: " + NullAnalysisPass.COMPARE_TO_MARKER
                            + " marker reached the emitter; NullAnalysisPass must run before emission");
        }
        if ("__titan_parent_key_contains".equals(node.name()) && node.arguments().size() == 2) {
            String carrier = node.arguments().get(0).accept(this);
            String key = node.arguments().get(1).accept(this);
            return "(" + key + " MEMBER OF(" + carrier + "))";
        }

        if ("__titan_is_null".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().getFirst().accept(this) + " IS NULL)";
        }

        if ("equals".equals(node.name()) && node.arguments().size() == 2) {
            // Null-safe equality: MySQL's <=> matches PostgreSQL's IS NOT DISTINCT FROM semantics.
            return "(" + node.arguments().get(0).accept(this) + " <=> "
                    + node.arguments().get(1).accept(this) + ")";
        }

        if ("__titan_str_concat".equals(node.name()) && node.arguments().size() == 2) {
            // B-10 (TG-BLK-012): coerceToTextSql renders a boolean operand as 'true'/'false'
            // (a CASE) rather than the TINYINT '1'/'0' a bare CAST AS CHAR would produce, matching
            // Java's String + boolean and PostgreSQL.
            String left = coerceToTextSql(node.arguments().get(0));
            String right = coerceToTextSql(node.arguments().get(1));
            return "CONCAT(COALESCE(" + left + ", 'null'), COALESCE(" + right + ", 'null'))";
        }

        if ("__titan_char_code".equals(node.name()) && node.arguments().size() == 1) {
            return "ASCII(" + node.arguments().get(0).accept(this) + ")";
        }

        if ("__titan_str_index_of".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            return "(LOCATE(" + needle + ", " + target + ") - 1)";
        }
        if ("__titan_str_index_of".equals(node.name()) && node.arguments().size() == 3) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            String fromIndex = node.arguments().get(2).accept(this);
            String normalizedFromIndex = "GREATEST(" + fromIndex + ", 0)";
            String position = "LOCATE(" + needle + ", " + target + ", " + normalizedFromIndex + " + 1)";
            return "(CASE"
                    + " WHEN " + needle + " = '' THEN LEAST(" + normalizedFromIndex + ", CHAR_LENGTH(" + target + "))"
                    + " WHEN " + normalizedFromIndex + " > CHAR_LENGTH(" + target + ") THEN -1"
                    + " WHEN " + position + " = 0 THEN -1"
                    + " ELSE (" + position + " - 1)"
                    + " END)";
        }
        if ("__titan_str_contains".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            return "(LOCATE(" + needle + ", " + target + ") > 0)";
        }
        if ("__titan_str_starts_with".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String prefix = node.arguments().get(1).accept(this);
            return "(LEFT(" + target + ", CHAR_LENGTH(" + prefix + ")) = " + prefix + ")";
        }
        if ("__titan_str_starts_with".equals(node.name()) && node.arguments().size() == 3) {
            String target = node.arguments().get(0).accept(this);
            String prefix = node.arguments().get(1).accept(this);
            String offset = node.arguments().get(2).accept(this);
            return "(CASE"
                    + " WHEN " + offset + " < 0 THEN FALSE"
                    + " WHEN (" + offset + " + CHAR_LENGTH(" + prefix + ")) > CHAR_LENGTH(" + target + ") THEN FALSE"
                    + " ELSE SUBSTRING(" + target + ", " + offset + " + 1, CHAR_LENGTH(" + prefix + ")) = " + prefix
                    + " END)";
        }
        if ("__titan_str_ends_with".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String suffix = node.arguments().get(1).accept(this);
            return "(RIGHT(" + target + ", CHAR_LENGTH(" + suffix + ")) = " + suffix + ")";
        }
        if ("__titan_str_char_at".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String oneBasedIndex = oneBasedIndexSql(node.arguments().get(1));
            return "SUBSTRING(" + target + ", " + oneBasedIndex + ", 1)";
        }
        if ("__titan_str_substring".equals(node.name()) && node.arguments().size() >= 2) {
            String target = node.arguments().get(0).accept(this);
            ExpressionNode startExpression = node.arguments().get(1);
            String oneBasedStart = oneBasedIndexSql(startExpression);
            if (node.arguments().size() == 2) {
                return "SUBSTRING(" + target + ", " + oneBasedStart + ")";
            }
            ExpressionNode endExpression = node.arguments().get(2);
            String length = substringLengthSql(startExpression, endExpression);
            return "SUBSTRING(" + target + ", " + oneBasedStart + ", " + length + ")";
        }
        if ("__titan_str_split".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String delimiter = node.arguments().get(1).accept(this);
            return "JSON_EXTRACT(CONCAT('[\"', REPLACE(" + target + ", " + delimiter + ", '\",\"'), '\"]'), '$')";
        }
        if ("__titan_str_matches".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String pattern = node.arguments().get(1).accept(this);
            return "(" + target + " REGEXP " + pattern + ")";
        }
        if ("__titan_str_format".equals(node.name())) {
            // The previous CONCAT(template, args...) mapping performed no placeholder substitution
            // and produced silently wrong output (plan 0.11 / E-9).
            throw new UnsupportedOperationException(
                    "TITAN-E001: String.format (__titan_str_format) is not supported on MySQL; "
                            + "MySQL has no placeholder-substituting FORMAT function. "
                            + "Build the string with concatenation instead.");
        }
        if ("__titan_math_random".equals(node.name()) && node.arguments().isEmpty()) {
            return "RAND()";
        }
        if ("__titan_math_log10".equals(node.name()) && node.arguments().size() == 1) {
            return "LOG10(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_math_round".equals(node.name()) && node.arguments().size() == 1) {
            return runtimeFunction("java_round") + "(" + node.arguments().get(0).accept(this) + ", 0)";
        }
        // EmulationInsertionPass markers (plan 2.4, E-7): native MySQL INT/INT division yields
        // DECIMAL (5/2 = 2.5) and a zero divisor yields NULL even under the default strict
        // sql_mode, both diverging from Java/PostgreSQL. The runtime helpers truncate with DIV /
        // take the dividend's sign with MOD, and SIGNAL SQLSTATE '22012' on a zero divisor
        // (explicit SQL over session state, per the E-1/E-8 precedent).
        if (EmulationInsertionPass.INT_DIV_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeFunction("java_int_div") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_MOD_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeFunction("java_mod") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        // EmulationInsertionPass strict-wraparound markers (plan 2.4, E-11): 32-bit Java int
        // wraparound via the deployed titan_rt_* helpers.
        if (EmulationInsertionPass.INT_ADD_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeFunction("java_int_add") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_SUB_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeFunction("java_int_sub") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_MUL_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeFunction("java_int_mul") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if ("__titan_static_get".equals(node.name()) && node.arguments().size() == 1) {
            return runtimeFunction("static_get") + "(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_time_localdate_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_DATE";
        }
        if ("__titan_time_localtime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_TIME";
        }
        if ("__titan_time_localdatetime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_TIMESTAMP";
        }
        if ("__titan_time_instant_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "UTC_TIMESTAMP()";
        }
        if ("__titan_time_zoneddatetime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', @@session.time_zone)";
        }
        if ("__titan_time_plus_days".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String days = node.arguments().get(1).accept(this);
            return "DATE_ADD(" + target + ", INTERVAL " + days + " DAY)";
        }
        if ("__titan_time_minus_days".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String days = node.arguments().get(1).accept(this);
            return "DATE_SUB(" + target + ", INTERVAL " + days + " DAY)";
        }
        if ("__titan_time_plus_hours".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String hours = node.arguments().get(1).accept(this);
            return "DATE_ADD(" + target + ", INTERVAL " + hours + " HOUR)";
        }
        if ("__titan_time_minus_hours".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String hours = node.arguments().get(1).accept(this);
            return "DATE_SUB(" + target + ", INTERVAL " + hours + " HOUR)";
        }
        if ("__titan_time_to_local_date".equals(node.name()) && node.arguments().size() == 1) {
            return "DATE(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_time_to_local_time".equals(node.name()) && node.arguments().size() == 1) {
            return "TIME(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_time_to_instant".equals(node.name()) && node.arguments().size() == 1) {
            return "CONVERT_TZ(" + node.arguments().get(0).accept(this) + ", @@session.time_zone, '+00:00')";
        }
        if ("__titan_time_duration_zero".equals(node.name()) && node.arguments().isEmpty()) {
            return "INTERVAL 0 SECOND";
        }
        if ("__titan_time_period_zero".equals(node.name()) && node.arguments().isEmpty()) {
            return "INTERVAL 0 DAY";
        }
        if ("__titan_time_duration_of_days".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " DAY";
        }
        if ("__titan_time_duration_of_hours".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " HOUR";
        }
        if ("__titan_time_duration_of_minutes".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " MINUTE";
        }
        if ("__titan_time_duration_of_seconds".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " SECOND";
        }
        if ("__titan_time_period_of_days".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " DAY";
        }
        if ("__titan_time_period_of_months".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " MONTH";
        }
        if ("__titan_time_period_of_years".equals(node.name()) && node.arguments().size() == 1) {
            return "INTERVAL " + node.arguments().get(0).accept(this) + " YEAR";
        }
        if ("__titan_time_plus_amount".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String amount = renderTemporalAmountMySql(node.arguments().get(1));
            return "DATE_ADD(" + target + ", " + amount + ")";
        }
        if ("__titan_time_minus_amount".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String amount = renderTemporalAmountMySql(node.arguments().get(1));
            return "DATE_SUB(" + target + ", " + amount + ")";
        }

        if (node.name() != null && node.name().startsWith("__titan_") && !node.name().startsWith("__titan_internal_")) {
            // An unrecognized intrinsic would otherwise render as a literal call to a function that
            // does not exist, failing at runtime instead of emit time (plan 0.11 / E-9).
            // __titan_internal_* names are exempt: they are generated helper routines deployed by
            // the pipeline itself, not compiler intrinsics.
            throw new UnsupportedOperationException(
                    "TITAN-E001: Titan intrinsic '" + node.name() + "' with " + node.arguments().size()
                            + " argument(s) has no MySQL emission mapping; "
                            + "this construct cannot be transpiled for the MySQL dialect yet.");
        }

        return renderDefaultFunctionCall(node);
    }

    @Override
    public String visitArrayConstructExpression(ArrayConstructExpression node) {
        String elements = node.elements().stream().map(element -> element.accept(this)).collect(Collectors.joining(", "));
        return "JSON_ARRAY(" + elements + ")";
    }

    @Override
    public String visitArrayLengthExpression(ArrayLengthExpression node) {
        return "COALESCE(JSON_LENGTH(" + node.array().accept(this) + "), 0)";
    }

    @Override
    public String visitArrayGetExpression(ArrayGetExpression node) {
        String extracted = "JSON_EXTRACT(" + node.array().accept(this) + ", CONCAT('$[', " + node.index().accept(this) + ", ']'))";
        if (node.elementType() instanceof TRecordType) {
            return extracted;
        }
        return "JSON_UNQUOTE(" + extracted + ")";
    }

    private String renderTemporalAmountMySql(ExpressionNode expression) {
        if (expression instanceof VariableRefExpression variableRef) {
            String unitKeyword = temporalAmountUnits.get(variableRef.name());
            if (unitKeyword != null) {
                return "INTERVAL " + emittedVariableName(variableRef.name()) + " " + unitKeyword;
            }
            if (temporalAmountTypes.get(variableRef.name()) instanceof TDurationType) {
                return "INTERVAL " + emittedVariableName(variableRef.name()) + " SECOND";
            }
            throw unsupportedOnDialect("Unsupported temporal amount variable for MySQL emission: " + variableRef.name());
        }
        if (expression instanceof FunctionCallExpression functionCall) {
            TemporalAmountLiteral temporalAmount = temporalAmountLiteral(functionCall);
            if (temporalAmount != null) {
                return temporalAmount.toIntervalSql();
            }
        }
        throw unsupportedOnDialect("Unsupported temporal amount expression for MySQL emission: " + expression);
    }

    private TemporalAmountLiteral temporalAmountLiteral(ExpressionNode expression) {
        if (!(expression instanceof FunctionCallExpression functionCall)) {
            return null;
        }
        return switch (functionCall.name()) {
            case "__titan_time_duration_zero" -> new TemporalAmountLiteral("0", "SECOND");
            case "__titan_time_period_zero" -> new TemporalAmountLiteral("0", "DAY");
            case "__titan_time_duration_of_days", "__titan_time_period_of_days" -> temporalAmountLiteral(functionCall, "DAY");
            case "__titan_time_duration_of_hours" -> temporalAmountLiteral(functionCall, "HOUR");
            case "__titan_time_duration_of_minutes" -> temporalAmountLiteral(functionCall, "MINUTE");
            case "__titan_time_duration_of_seconds" -> temporalAmountLiteral(functionCall, "SECOND");
            case "__titan_time_period_of_months" -> temporalAmountLiteral(functionCall, "MONTH");
            case "__titan_time_period_of_years" -> temporalAmountLiteral(functionCall, "YEAR");
            default -> null;
        };
    }

    private TemporalAmountLiteral temporalAmountLiteral(FunctionCallExpression functionCall, String unitKeyword) {
        if (functionCall.arguments().size() != 1) {
            return null;
        }
        return new TemporalAmountLiteral(functionCall.arguments().get(0).accept(this), unitKeyword);
    }

    private String durationSecondsSql(ExpressionNode expression) {
        if (expression instanceof VariableRefExpression variableRef) {
            if (!(temporalAmountTypes.get(variableRef.name()) instanceof TDurationType)) {
                return null;
            }
            String unitKeyword = temporalAmountUnits.get(variableRef.name());
            if (unitKeyword == null) {
                return emittedVariableName(variableRef.name());
            }
            return convertMagnitudeToSecondsSql(emittedVariableName(variableRef.name()), unitKeyword);
        }
        if (!(expression instanceof FunctionCallExpression functionCall)) {
            return null;
        }
        return switch (functionCall.name()) {
            case "__titan_time_duration_zero" -> "0";
            case "__titan_time_duration_of_seconds" -> singleDurationArgumentSql(functionCall);
            case "__titan_time_duration_of_minutes" -> scaledDurationArgumentSql(functionCall, 60);
            case "__titan_time_duration_of_hours" -> scaledDurationArgumentSql(functionCall, 3600);
            case "__titan_time_duration_of_days" -> scaledDurationArgumentSql(functionCall, 86400);
            default -> null;
        };
    }

    private String singleDurationArgumentSql(FunctionCallExpression functionCall) {
        if (functionCall.arguments().size() != 1) {
            return null;
        }
        return functionCall.arguments().get(0).accept(this);
    }

    private String scaledDurationArgumentSql(FunctionCallExpression functionCall, int multiplier) {
        String argumentSql = singleDurationArgumentSql(functionCall);
        if (argumentSql == null) {
            return null;
        }
        return convertMagnitudeToSecondsSql(argumentSql, multiplier);
    }

    private String convertMagnitudeToSecondsSql(String magnitudeSql, String unitKeyword) {
        return switch (unitKeyword) {
            case "SECOND" -> magnitudeSql;
            case "MINUTE" -> convertMagnitudeToSecondsSql(magnitudeSql, 60);
            case "HOUR" -> convertMagnitudeToSecondsSql(magnitudeSql, 3600);
            case "DAY" -> convertMagnitudeToSecondsSql(magnitudeSql, 86400);
            // Internal-invariant assertion: unit keywords originate exclusively from
            // temporalAmountLiteral/durationSecondsSql above, which only produce the four
            // duration units handled here. Enforced by ExceptionDisciplineEnforcementTest.
            default -> throw new IllegalStateException("internal: unsupported duration unit for MySQL seconds conversion: " + unitKeyword);
        };
    }

    private String convertMagnitudeToSecondsSql(String magnitudeSql, int multiplier) {
        return "(" + magnitudeSql + " * " + multiplier + ")";
    }

    private record TemporalAmountLiteral(String magnitudeSql, String unitKeyword) {
        private String toIntervalSql() {
            return "INTERVAL " + magnitudeSql + " " + unitKeyword;
        }
    }

    @Override
    protected String castTypeSql(TirType type) {
        // MySQL CAST accepts a closed keyword list, not column type names: text casts as CHAR,
        // integer casts as SIGNED (CAST(x AS INT)/AS BIGINT are syntax errors), and TIMESTAMP
        // is not a valid CAST target at all (verified on 8.4) — timestamptz casts go through
        // DATETIME(6) and rely on assignment coercion at the typed destination (E-13).
        return switch (type) {
            case TTextType ignored -> "CHAR";
            case TIntType ignored -> "SIGNED";
            case TBigintType ignored -> "SIGNED";
            case TTimestampTzType ignored -> "DATETIME(6)";
            // UUID is a CHAR(36) string on MySQL; CAST targets take the bare CHAR keyword (CHAR(36) as a
            // CAST target is accepted but we follow the text convention), not the column-type spelling.
            case TUuidType ignored -> "CHAR";
            // Binary casts use the BINARY keyword; CAST(x AS LONGBLOB) (the column-type spelling) is invalid.
            case TBytesType ignored -> "BINARY";
            default -> sqlType(type);
        };
    }

    @Override
    protected String booleanToTextSql(String operandSql) {
        // B-10 (TG-BLK-012): MySQL's BOOLEAN is TINYINT, so CAST(<boolean> AS CHAR) yields '1'/'0'.
        // Reproduce Java's Boolean.toString() and PostgreSQL's boolean::text ('true'/'false').
        // The NULL arm is load-bearing: MySQL IF(NULL, 'true', 'false') returns 'false', but Java
        // ("" + (Boolean) null => "null") and PostgreSQL (CAST(NULL AS TEXT) => NULL, then
        // COALESCE(..., 'null')) both yield 'null' for a NULL boolean — so the coercion must return
        // NULL on a NULL operand to let the surrounding COALESCE(..., 'null') fire identically.
        return "(CASE WHEN " + operandSql + " IS NULL THEN NULL WHEN " + operandSql + " THEN 'true' ELSE 'false' END)";
    }

    @Override
    protected String truncateTowardZeroSql(String operandSql) {
        return "TRUNCATE(" + operandSql + ", 0)";
    }

    @Override
    protected String blockBody(Block block) {
        // MySQL only allows DECLARE at the start of a BEGIN ... END compound statement, so a
        // block with declarations used as a loop/IF body must open its own scope (the
        // PostgreSQL emitter does the same with DECLARE ... BEGIN ... END). LEAVE/ITERATE of
        // enclosing loop labels remain legal from inside the nested block.
        boolean needsScope = !block.declarations().isEmpty();
        StringBuilder sql = new StringBuilder();
        if (needsScope) {
            sql.append("BEGIN\n");
        }
        for (DeclarationNode declaration : block.declarations()) {
            sql.append(mySqlDeclarationLine(
                    declaration,
                    shouldIncludeDeclarationInitializer(declaration, block.statements()))).append('\n');
        }
        for (StatementNode statement : block.statements()) {
            sql.append(statement.accept(this)).append('\n');
        }
        if (needsScope) {
            sql.append("END;");
            return sql.toString();
        }
        if (sql.length() > 0) {
            sql.setLength(sql.length() - 1);
        }
        return sql.toString();
    }

    private String renderSqlStateMembership(String variable, List<String> sqlStates) {
        if (sqlStates == null || sqlStates.isEmpty()) {
            return "FALSE";
        }
        return sqlStates.stream()
                .map(state -> variable + " = '" + escape(state) + "'")
                .collect(Collectors.joining(" OR "));
    }

    private boolean isStringLike(ExpressionNode expression) {
        if (expression instanceof LiteralExpression literal) {
            return literal.value() instanceof String || literal.type() instanceof TTextType;
        }
        return false;
    }

    /**
     * Renders a CONCAT operand (B-10): a boolean is coerced to 'true'/'false' via
     * {@link #coerceToTextSql(ExpressionNode)}; every other operand keeps its bare rendering so
     * non-boolean concatenations are byte-identical to before.
     */
    private String concatOperandSql(ExpressionNode operand) {
        if (isBooleanExpression(operand)) {
            return coerceToTextSql(operand);
        }
        return operand.accept(this);
    }

    private String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return namingConventionEngine.toSqlIdentifier("row");
        }
        return namingConventionEngine.toSqlIdentifier(raw);
    }

    @Override
    protected String sqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "INT";
            case TBigintType ignored -> "BIGINT";
            case TBooleanType ignored -> "BOOLEAN";
            case TTextType ignored -> "TEXT";
            case TNumericType t -> "DECIMAL(" + t.precision() + "," + t.scale() + ")";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            // E-13 (plan 3.1): (6) preserves Java's microsecond precision (bare DATETIME/
            // TIMESTAMP truncate fractional seconds); must match MySqlTypeMapper.
            case TTimestampType ignored -> "DATETIME(6)";
            case TTimestampTzType ignored -> "TIMESTAMP(6)";
            case TDurationType ignored -> "BIGINT";
            case TPeriodType ignored -> "BIGINT";
            case TArrayType ignored -> "JSON";
            case TJsonType ignored -> "JSON";
            case TCompositeType ignored -> "JSON";
            case TRecordType ignored -> "JSON";
            // No native UUID on MySQL: canonical 36-char string (must match MySqlTypeMapper).
            case TUuidType ignored -> "CHAR(36)";
            case TBytesType ignored -> "LONGBLOB";
            case TVoidType ignored -> "VOID";
        };
    }

    private String telemetryPayloadSql(Block body, List<String> sensitiveColumnsAccessed) {
        List<String> parameterEntries = routineParameters.stream()
                .map(parameter -> "'" + escape(parameter.sqlName()) + "', '" + escape(sqlType(parameter.type())) + "'")
                .toList();

        String parameterObject = parameterEntries.isEmpty()
                ? "JSON_OBJECT()"
                : "JSON_OBJECT(" + String.join(", ", parameterEntries) + ")";

        if (sensitiveColumnsAccessed == null || sensitiveColumnsAccessed.isEmpty()) {
            return "JSON_OBJECT('parameterTypes', " + parameterObject + ")";
        }
        String values = sensitiveColumnsAccessed.stream()
                .map(value -> "'" + escape(value) + "'")
                .collect(Collectors.joining(", "));
        return "JSON_OBJECT('parameterTypes', " + parameterObject + ", 'sensitiveColumns', JSON_ARRAY(" + values + "))";
    }

    private boolean containsDebugPrint(Block body) {
        return containsDebugPrint(body.statements());
    }

    private boolean containsDebugPrint(List<StatementNode> statements) {
        for (StatementNode statement : statements) {
            switch (statement) {
                case DebugPrintStatement ignored -> {
                    return true;
                }
                case IfStatement ifStatement -> {
                    if (containsDebugPrint(ifStatement.thenBlock().statements())) {
                        return true;
                    }
                    if (ifStatement.elseBlock() != null && containsDebugPrint(ifStatement.elseBlock().statements())) {
                        return true;
                    }
                    for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                        if (containsDebugPrint(clause.block().statements())) {
                            return true;
                        }
                    }
                }
                case WhileStatement whileStatement -> {
                    if (containsDebugPrint(whileStatement.body().statements())) {
                        return true;
                    }
                }
                case ForCursorStatement forCursor -> {
                    if (containsDebugPrint(forCursor.body().statements())) {
                        return true;
                    }
                }
                case ForEachStatement forEach -> {
                    if (containsDebugPrint(forEach.body().statements())) {
                        return true;
                    }
                }
                case ForRangeStatement forRange -> {
                    if (containsDebugPrint(forRange.body().statements())) {
                        return true;
                    }
                }
                case LoopStatement loop -> {
                    if (containsDebugPrint(loop.body().statements())) {
                        return true;
                    }
                }
                case TryCatchFinallyStatement tryCatchFinally -> {
                    if (containsDebugPrint(tryCatchFinally.tryBlock().statements())) {
                        return true;
                    }
                    for (CatchClause catchClause : tryCatchFinally.catches()) {
                        if (containsDebugPrint(catchClause.body().statements())) {
                            return true;
                        }
                    }
                    if (tryCatchFinally.finallyBlock() != null && containsDebugPrint(tryCatchFinally.finallyBlock().statements())) {
                        return true;
                    }
                }
                case Block block -> {
                    if (containsDebugPrint(block.statements())) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * Whether the statement tree contains an early void return ({@code return;} with no value).
     * MySQL stored PROCEDUREs cannot {@code RETURN}, so such a return must be emitted as a
     * {@code LEAVE} of a labeled body block; this drives whether {@code emitProcedure} allocates
     * that label. A nested void return inside a labeled loop block still validly LEAVEs the outer
     * procedure label, so every nesting level is inspected.
     */
    private boolean containsVoidReturn(List<StatementNode> statements) {
        for (StatementNode statement : statements) {
            switch (statement) {
                case ReturnStatement returnStatement -> {
                    if (returnStatement.expression() == null) {
                        return true;
                    }
                }
                case IfStatement ifStatement -> {
                    if (containsVoidReturn(ifStatement.thenBlock().statements())) {
                        return true;
                    }
                    if (ifStatement.elseBlock() != null && containsVoidReturn(ifStatement.elseBlock().statements())) {
                        return true;
                    }
                    for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                        if (containsVoidReturn(clause.block().statements())) {
                            return true;
                        }
                    }
                }
                case WhileStatement whileStatement -> {
                    if (containsVoidReturn(whileStatement.body().statements())) {
                        return true;
                    }
                }
                case ForCursorStatement forCursor -> {
                    if (containsVoidReturn(forCursor.body().statements())) {
                        return true;
                    }
                }
                case ForEachStatement forEach -> {
                    if (containsVoidReturn(forEach.body().statements())) {
                        return true;
                    }
                }
                case ForRangeStatement forRange -> {
                    if (containsVoidReturn(forRange.body().statements())) {
                        return true;
                    }
                }
                case LoopStatement loop -> {
                    if (containsVoidReturn(loop.body().statements())) {
                        return true;
                    }
                }
                case TryCatchFinallyStatement tryCatchFinally -> {
                    if (containsVoidReturn(tryCatchFinally.tryBlock().statements())) {
                        return true;
                    }
                    for (CatchClause catchClause : tryCatchFinally.catches()) {
                        if (containsVoidReturn(catchClause.body().statements())) {
                            return true;
                        }
                    }
                    if (tryCatchFinally.finallyBlock() != null && containsVoidReturn(tryCatchFinally.finallyBlock().statements())) {
                        return true;
                    }
                }
                case Block block -> {
                    if (containsVoidReturn(block.statements())) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * Escapes a value for inclusion in a single-quoted MySQL string literal. Backslashes must be
     * doubled in addition to quotes (TITAN E-2, plan 0.10): under the default sql_mode (without
     * NO_BACKSLASH_ESCAPES) a trailing backslash would otherwise escape the closing quote and allow
     * source constants to inject SQL into the generated DDL.
     */
    @Override
    protected String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * MySQL identifier quoting: backticks with embedded backticks doubled (plan 1.3b).
     * Rejects null/blank/NUL identifiers.
     */
    @Override
    protected String quoteIdentifier(String identifier) {
        requireQuotableIdentifier(identifier);
        return "`" + identifier.replace("`", "``") + "`";
    }
}
