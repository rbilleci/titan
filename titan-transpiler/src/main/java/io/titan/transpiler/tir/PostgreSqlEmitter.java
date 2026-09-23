package io.titan.transpiler.tir;

import io.titan.transpiler.NamingConventionEngine;
import io.titan.transpiler.emit.CodeBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Baseline PL/pgSQL emitter for P0 TIR statements.
 */
public final class PostgreSqlEmitter extends AbstractSqlEmitter {
    private static final String RUNTIME_NAMESPACE = new PostgreSqlRuntimeStrategy().runtimeNamespace();

    public PostgreSqlEmitter() {
        this(new NamingConventionEngine());
    }

    public PostgreSqlEmitter(NamingConventionEngine namingConventionEngine) {
        super(namingConventionEngine);
    }

    private static String runtimeQualified(String name) {
        return RUNTIME_NAMESPACE + "." + name;
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
        observabilityEnabled = observability;
        staticResetKeys = collectStaticResetKeys(body);
        this.routineParameters = List.copyOf(routineParameters == null ? List.of() : routineParameters);
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                this.routineParameters,
                body,
                reservedRoutineIdentifiers(observability)
        );
        this.routineParameters = routineSqlNameAllocator.routineParameters();
        String qualified = qualify(schema, name);

        out.line("CREATE OR REPLACE PROCEDURE " + qualified + "(" + routineParameterSignature() + ")");
        out.line("LANGUAGE plpgsql");
        out.line("SECURITY " + toPostgresSecurityKeyword(securityMode));
        emitPostgresDefinerSearchPath(securityMode, schema);
        out.line("AS $$");
        emitPostgresDeclarationSection(body, observability);
        out.line("BEGIN");
        out.indent();
        if (observability) {
            emitPostgresTelemetryStart(name, body, sensitiveColumnsAccessed);
        }
        emitStatements(body.statements());
        emitPostgresStaticResets();
        if (observability) {
            emitPostgresTelemetrySuccess();
            emitPostgresTelemetryException();
        } else if (!staticResetKeys.isEmpty()) {
            emitPostgresStaticResetException();
        }
        out.dedent();
        out.line("END;");
        out.line("$$;");
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
        observabilityEnabled = observability;
        this.routineParameters = List.copyOf(routineParameters == null ? List.of() : routineParameters);
        staticResetKeys = collectStaticResetKeys(body);
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                this.routineParameters,
                body,
                reservedRoutineIdentifiers(observability)
        );
        this.routineParameters = routineSqlNameAllocator.routineParameters();
        String qualified = qualify(schema, name);

        out.line("CREATE OR REPLACE FUNCTION " + qualified + "(" + routineParameterSignature() + ")");
        out.line("RETURNS " + sqlType(returnType));
        out.line("LANGUAGE plpgsql");
        out.line("SECURITY " + toPostgresSecurityKeyword(securityMode));
        emitPostgresDefinerSearchPath(securityMode, schema);
        out.line("AS $$");
        emitPostgresDeclarationSection(body, observability);
        out.line("BEGIN");
        out.indent();
        if (observability) {
            emitPostgresTelemetryStart(name, body, sensitiveColumnsAccessed);
        }
        emitStatements(body.statements());
        emitPostgresStaticResets();
        if (observability) {
            emitPostgresTelemetrySuccess();
            emitPostgresTelemetryException();
        } else if (!staticResetKeys.isEmpty()) {
            emitPostgresStaticResetException();
        }
        out.dedent();
        out.line("END;");
        out.line("$$;");
        return out.toString();
    }

    @Override
    public String emitTrigger(String schema, String name, SecurityMode securityMode, TriggerSpec trigger, Block body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(body, "body");

        // TG-BLK-011: the _fn/_trg decorations can push an at-the-limit routine name over the
        // dialect identifier ceiling; describeTrigger composes the same truncated names.
        String functionName = SqlNames.fitWithinDialectLimits(name + "_fn");
        String triggerName = quoteIdentifier(SqlNames.fitWithinDialectLimits(name + "_trg"));
        String qualifiedFunction = qualify(schema, functionName);
        // trigger.table() may arrive pre-qualified as "schema.table"; quote part by part.
        String qualifiedTable = trigger.table().contains(".")
                ? quoteQualifiedName(trigger.table())
                : qualify(schema, trigger.table());
        String events = String.join(" OR ", trigger.events());

        out = new CodeBuffer();
        rawSqlCounter = 0;
        observabilityEnabled = false;
        staticResetKeys = collectStaticResetKeys(body);
        routineParameters = List.of();
        routineSqlNameAllocator = new RoutineSqlNameAllocator(
                namingConventionEngine,
                routineParameters,
                body,
                reservedRoutineIdentifiers(false)
        );
        out.line("CREATE OR REPLACE FUNCTION " + qualifiedFunction + "()");
        out.line("RETURNS TRIGGER");
        out.line("LANGUAGE plpgsql");
        out.line("SECURITY " + toPostgresSecurityKeyword(securityMode));
        emitPostgresDefinerSearchPath(securityMode, schema);
        out.line("AS $$");
        emitPostgresDeclarationSection(body, false);
        out.line("BEGIN");
        out.indent();
        emitStatements(body.statements());
        emitPostgresStaticResets();
        if (!staticResetKeys.isEmpty()) {
            emitPostgresStaticResetException();
        }
        emitPostgresTriggerReturn(trigger.events());
        out.dedent();
        out.line("END;");
        out.line("$$;");
        out.blankLine();
        out.line("DROP TRIGGER IF EXISTS " + triggerName + " ON " + qualifiedTable + ";");
        out.line("CREATE TRIGGER " + triggerName);
        out.line(trigger.timing() + " " + events + " ON " + qualifiedTable);
        out.line("FOR EACH " + trigger.forEach());
        out.line("EXECUTE FUNCTION " + qualifiedFunction + "();");
        return out.toString();
    }

    @Override
    public String emitScheduledJob(String schema, String name, SecurityMode securityMode, ScheduledJobSpec scheduledJob, Block body, boolean observability, List<String> sensitiveColumnsAccessed) {
        CronExpressionValidator.validateStandardCron(scheduledJob.cron());
        String procedureSql = emitProcedure(schema, name, securityMode, body, observability, sensitiveColumnsAccessed);
        String jobName = (scheduledJob.name() == null || scheduledJob.name().isBlank()) ? name + "_job" : scheduledJob.name();
        String qualifiedProcedure = qualify(schema, name);
        String escapedJobName = escape(jobName);
        String escapedCron = escape(scheduledJob.cron());
        String escapedCall = escape("CALL " + qualifiedProcedure + "()");
        return procedureSql
                + "\n\nDO $$\n"
                + "BEGIN\n"
                + "    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN\n"
                + "        PERFORM cron.schedule('" + escapedJobName + "', '" + escapedCron + "', '" + escapedCall + "');\n"
                + "    ELSE\n"
                + "        RAISE WARNING 'Titan scheduled job % requires pg_cron extension; procedure % was generated but not scheduled.', '"
                + escapedJobName + "', '" + escape(qualifiedProcedure) + "';\n"
                + "    END IF;\n"
                + "END;\n"
                + "$$;";
    }

    @Override
    public String emitView(String schema, String name, String sqlBody) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sqlBody, "sqlBody");
        return "CREATE OR REPLACE VIEW " + qualify(schema, name)
                + " WITH (security_invoker = true) AS\n"
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
        sql.append("    \"ordinal\" INTEGER PRIMARY KEY,\n");
        sql.append("    \"name\" TEXT UNIQUE NOT NULL");
        for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
            sql.append(",\n    ").append(quoteIdentifier(field.name())).append(" ").append(toPostgresRecordFieldType(field.typeName()));
        }
        sql.append("\n);");

        if (!enumLookupSpec.values().isEmpty()) {
            sql.append("\n\nINSERT INTO ").append(tableName).append(" (");
            sql.append("\"ordinal\", \"name\"");
            for (EnumLookupSpec.EnumField field : enumLookupSpec.fields()) {
                sql.append(", ").append(quoteIdentifier(field.name()));
            }
            sql.append(") VALUES\n");
            List<String> tuples = new ArrayList<>();
            for (int i = 0; i < enumLookupSpec.values().size(); i++) {
                tuples.add("    (" + renderEnumTuple(i, enumLookupSpec.values().get(i), enumLookupSpec.fields()) + ")");
            }
            sql.append(String.join(",\n", tuples));
            // EXCLUDED is the ON CONFLICT pseudo-row keyword and stays bare; only its column
            // part is a real (user-derived) identifier.
            sql.append("\nON CONFLICT (\"ordinal\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"");
            if (!enumLookupSpec.fields().isEmpty()) {
                sql.append(", ");
                sql.append(enumLookupSpec.fields().stream()
                        .map(field -> quoteIdentifier(field.name()) + " = EXCLUDED." + quoteIdentifier(field.name()))
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
            appendEnumFieldAccessor(sql, tableName, qualify(schema, accessorId), field.name(), toPostgresRecordFieldType(field.typeName()));
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
            appendEnumFieldAccessor(sql, tableName, qualify(schema, accessorId), backingField, toPostgresRecordFieldType(method.returnTypeName()));
        }

        return sql.toString();
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
        String typeName = qualify(schema, SqlNames.recordSqlBaseName(recordModelSpec.recordName()));
        String constructorName = qualify(schema, SqlNames.recordMemberName(recordModelSpec.recordName(), "new"));

        StringBuilder sql = new StringBuilder();
        sql.append("DROP TYPE IF EXISTS ").append(typeName).append(" CASCADE;\n");
        sql.append("CREATE TYPE ").append(typeName).append(" AS (");
        if (recordModelSpec.fields().isEmpty()) {
            sql.append("__placeholder TEXT");
        } else {
            sql.append("\n");
            sql.append(recordModelSpec.fields().stream()
                    .map(field -> "    " + quoteIdentifier(field.name()) + " " + toPostgresRecordFieldType(field.typeName()))
                    .collect(Collectors.joining(",\n")));
            sql.append("\n");
        }
        sql.append(");\n\n");
        sql.append("CREATE OR REPLACE FUNCTION ").append(constructorName).append("(");
        sql.append(recordModelSpec.fields().stream()
                .map(field -> namingConventionEngine.parameterName(field.name()) + " " + toPostgresRecordFieldType(field.typeName()))
                .collect(Collectors.joining(", ")));
        sql.append(") RETURNS ").append(typeName).append("\n");
        sql.append("SECURITY INVOKER\n");
        sql.append("AS $$\n");
        sql.append("BEGIN\n");
        if (recordModelSpec.fields().isEmpty()) {
            sql.append("    RETURN ROW(NULL)::").append(typeName).append(";\n");
        } else {
            sql.append("    RETURN ROW(");
            sql.append(recordModelSpec.fields().stream()
                    .map(f -> namingConventionEngine.parameterName(f.name()))
                    .collect(Collectors.joining(", ")));
            sql.append(")::").append(typeName).append(";\n");
        }
        sql.append("END;\n");
        sql.append("$$ LANGUAGE plpgsql;");
        for (RecordModelSpec.RecordField field : recordModelSpec.fields()) {
            String fnName = qualify(schema, SqlNames.recordMemberName(recordModelSpec.recordName(), field.name()));
            String recordParameter = namingConventionEngine.parameterName("record");
            String returnType = toPostgresRecordFieldType(field.typeName());
            sql.append("\n\nCREATE OR REPLACE FUNCTION ").append(fnName)
                    .append("(").append(recordParameter).append(" ").append(typeName).append(")")
                    .append("\nRETURNS ").append(returnType)
                    .append("\nLANGUAGE plpgsql")
                    .append("\nSECURITY INVOKER")
                    .append("\nAS $$")
                    .append("\nBEGIN")
                    .append("\n    RETURN (").append(recordParameter).append(").")
                    .append(quoteIdentifier(field.name())).append(";")
                    .append("\nEND;")
                    .append("\n$$;");
        }
        return sql.toString();
    }

    private void appendEnumFieldAccessor(StringBuilder sql, String tableName, String fnName, String selectedField, String returnType) {
        // Parameter and local-variable names stay unquoted (plan 1.3b exclusion): they are
        // namingConventionEngine-generated (p_/v_ prefixed, lowercase) and quoting plpgsql
        // declarations adds no safety while complicating variable substitution.
        String enumKeyParam = namingConventionEngine.parameterName("enumKey");
        String valueVar = namingConventionEngine.localVariableName("value");
        sql.append("\n\nCREATE OR REPLACE FUNCTION ").append(fnName).append("(").append(enumKeyParam).append(" TEXT)");
        sql.append("\nRETURNS ").append(returnType);
        sql.append("\nLANGUAGE plpgsql");
        sql.append("\nSECURITY INVOKER");
        sql.append("\nAS $$");
        sql.append("\nDECLARE ").append(valueVar).append(" ").append(returnType).append(";");
        sql.append("\nBEGIN");
        sql.append("\n    SELECT ").append(quoteIdentifier(selectedField)).append(" INTO ").append(valueVar).append(" FROM ").append(tableName)
                .append(" WHERE \"name\" = ").append(enumKeyParam).append(";");
        sql.append("\n    RETURN ").append(valueVar).append(";");
        sql.append("\nEND;");
        sql.append("\n$$;");
    }

    private void emitPostgresTriggerReturn(List<String> events) {
        boolean hasDelete = events.stream().anyMatch(event -> "DELETE".equalsIgnoreCase(event));
        boolean hasNonDelete = events.stream().anyMatch(event -> !"DELETE".equalsIgnoreCase(event));

        if (hasDelete && hasNonDelete) {
            out.line("IF TG_OP = 'DELETE' THEN");
            out.indent();
            out.line("RETURN OLD;");
            out.dedent();
            out.line("END IF;");
            out.line("RETURN NEW;");
            return;
        }

        out.line(hasDelete ? "RETURN OLD;" : "RETURN NEW;");
    }

    // ------------------------------------------------------------------
    // Artifact object descriptors (plan 4.4, audit G-10) — kept beside the emit methods so the
    // described names/types and the rendered SQL come from the same dialect decisions.
    // ------------------------------------------------------------------

    @Override
    public List<SqlObject> describeTrigger(String schema, String name, TriggerSpec trigger) {
        // Mirrors emitTrigger: a trigger function plus the CREATE TRIGGER attaching it.
        String onTable = describedTriggerTable(schema, trigger);
        return List.of(
                new SqlObject(SqlObject.Kind.FUNCTION, schema,
                        SqlNames.fitWithinDialectLimits(name + "_fn"), List.of(), "trigger", ""),
                new SqlObject(SqlObject.Kind.TRIGGER, schema,
                        SqlNames.fitWithinDialectLimits(name + "_trg"), List.of(), "", onTable));
    }

    @Override
    public List<SqlObject> describeScheduledJob(String schema, String name, ScheduledJobSpec scheduledJob, List<RoutineParameter> parameters) {
        // Mirrors emitScheduledJob: the procedure is the only catalog object; the pg_cron
        // registration is a guarded DO block, not a droppable object.
        return describeProcedure(schema, name, parameters);
    }

    @Override
    public List<SqlObject> describeEnumLookup(String schema, EnumLookupSpec enumLookupSpec) {
        String baseName = SqlNames.enumSqlBaseName(enumLookupSpec.enumName());
        List<SqlObject> objects = new ArrayList<>();
        objects.add(SqlObject.of(SqlObject.Kind.TABLE, schema, baseName));
        String enumKeyParam = namingConventionEngine.parameterName("enumKey");
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
                    List.of(new SqlObject.Parameter(enumKeyParam, "TEXT")),
                    toPostgresRecordFieldType(field.typeName()),
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
                    List.of(new SqlObject.Parameter(enumKeyParam, "TEXT")),
                    toPostgresRecordFieldType(method.returnTypeName()),
                    ""));
        }
        return List.copyOf(objects);
    }

    @Override
    public List<SqlObject> describeRecordModel(String schema, RecordModelSpec recordModelSpec) {
        String baseName = SqlNames.recordSqlBaseName(recordModelSpec.recordName());
        String typeName = (schema == null || schema.isBlank()) ? baseName : schema + "." + baseName;
        List<SqlObject> objects = new ArrayList<>();
        objects.add(SqlObject.of(SqlObject.Kind.TYPE, schema, baseName));
        objects.add(new SqlObject(
                SqlObject.Kind.FUNCTION,
                schema,
                SqlNames.recordMemberName(recordModelSpec.recordName(), "new"),
                recordModelSpec.fields().stream()
                        .map(field -> new SqlObject.Parameter(
                                namingConventionEngine.parameterName(field.name()),
                                toPostgresRecordFieldType(field.typeName())))
                        .toList(),
                typeName,
                ""));
        String recordParameter = namingConventionEngine.parameterName("record");
        for (RecordModelSpec.RecordField field : recordModelSpec.fields()) {
            objects.add(new SqlObject(
                    SqlObject.Kind.FUNCTION,
                    schema,
                    SqlNames.recordMemberName(recordModelSpec.recordName(), field.name()),
                    List.of(new SqlObject.Parameter(recordParameter, typeName)),
                    toPostgresRecordFieldType(field.typeName()),
                    ""));
        }
        return List.copyOf(objects);
    }

    private String toPostgresRecordFieldType(String javaType) {
        if (javaType == null) return "TEXT";
        String t = javaType.trim();
        return switch (t) {
            case "int", "Integer" -> "INTEGER";
            case "long", "Long" -> "BIGINT";
            case "boolean", "Boolean" -> "BOOLEAN";
            case "double", "Double" -> "DOUBLE PRECISION";
            case "float", "Float" -> "REAL";
            case "java.math.BigDecimal", "BigDecimal" -> "NUMERIC";
            case "java.time.LocalDate", "LocalDate" -> "DATE";
            case "java.time.LocalDateTime", "LocalDateTime" -> "TIMESTAMP";
            case "java.time.Instant", "Instant", "java.time.ZonedDateTime", "ZonedDateTime", "java.time.OffsetDateTime", "OffsetDateTime" -> "TIMESTAMPTZ";
            // A UUID record field uses PostgreSQL's native uuid (must match the scalar TUuidType -> "UUID"
            // mapping; otherwise a record's UUID field would silently round-trip as TEXT).
            case "java.util.UUID", "UUID" -> "UUID";
            default -> "TEXT";
        };
    }

    /**
     * ATG-017: pin {@code search_path} on a SECURITY DEFINER routine to its own schema (+ {@code pg_temp}).
     * A DEFINER routine runs with the owner's privileges, so an unpinned {@code search_path} lets a caller
     * shadow the routine's functions/operators/types and escalate — the classic SECURITY DEFINER attack.
     * INVOKER routines run with the caller's own privileges and are not pinned.
     */
    private void emitPostgresDefinerSearchPath(SecurityMode securityMode, String schema) {
        if (securityMode == SecurityMode.DEFINER) {
            out.line("SET search_path = " + quoteIdentifier(schema) + ", pg_temp");
        }
    }

    private static String toPostgresSecurityKeyword(SecurityMode securityMode) {
        return securityMode == SecurityMode.DEFINER ? "DEFINER" : "INVOKER";
    }

    private void emitPostgresTelemetryStart(String routineName, Block body, List<String> sensitiveColumnsAccessed) {
        out.line("INSERT INTO " + runtimeQualified("telemetry") + "(procedure_name, started_at, parameters)");
        out.line("VALUES ('" + escape(routineName) + "', __titan_started_at, '" + telemetryPayloadJson(body, sensitiveColumnsAccessed) + "'::jsonb)");
        out.line("RETURNING id INTO __titan_telemetry_id;");
    }

    private void emitPostgresTelemetrySuccess() {
        for (String line : telemetrySuccessSql().split("\\n")) {
            out.line(line);
        }
    }

    @Override
    protected String telemetrySuccessSql() {
        return String.join("\n",
                "UPDATE " + runtimeQualified("telemetry") + " SET",
                "  status = 'success', finished_at = clock_timestamp(),",
                "  duration_ms = EXTRACT(EPOCH FROM clock_timestamp() - __titan_started_at) * 1000",
                "WHERE id = __titan_telemetry_id;");
    }

    private void emitPostgresTelemetryException() {
        out.line("EXCEPTION WHEN OTHERS THEN");
        out.indent();
        emitPostgresStaticResets();
        out.line("UPDATE " + runtimeQualified("telemetry") + " SET");
        out.line("  status = 'error', finished_at = clock_timestamp(),");
        out.line("  error_sqlstate = SQLSTATE, error_message = SQLERRM");
        out.line("WHERE id = __titan_telemetry_id;");
        out.line("RAISE;");
        out.dedent();
    }

    private void emitPostgresStaticResets() {
        for (String key : staticResetKeys) {
            out.line(staticResetCallSql(key));
        }
    }

    private void emitPostgresStaticResetException() {
        out.line("EXCEPTION WHEN OTHERS THEN");
        out.indent();
        emitPostgresStaticResets();
        out.line("RAISE;");
        out.dedent();
    }

    @Override
    protected String staticResetCallSql(String key) {
        return "PERFORM " + runtimeQualified("static_reset") + "('" + escape(key) + "');";
    }

    @Override
    protected String staticSetCallSql(String keySql, String valueSql) {
        // valueSql is already text-coerced by coerceToTextSql at the call site (B-10) — for a
        // non-boolean value that is exactly the CAST(... AS TEXT) this hook used to apply, and for
        // a boolean it is IF(..., 'true', 'false'); pass it through verbatim (no double cast).
        return "PERFORM " + runtimeQualified("static_set") + "(" + keySql + ", " + valueSql + ");";
    }

    @Override
    protected String nullPointerSignalSql(String location) {
        return "RAISE EXCEPTION 'NullPointerException at " + escape(location) + "';";
    }

    @Override
    protected String fullOuterJoinKeyword() {
        return "FULL OUTER JOIN";
    }

    @Override
    protected String elseIfKeyword() {
        return "ELSIF";
    }

    @Override
    protected String emptyBranchNoOpSql() {
        // NULL is PL/pgSQL's statement-level no-op and is valid in every IF arm.
        return "NULL;";
    }

    @Override
    protected String truncateTowardZeroSql(String operandSql) {
        return "TRUNC(" + operandSql + ")";
    }

    @Override
    protected String characterLiteral(char value) {
        // B-3 (TG-BLK-006): control characters route through the E'' escape rendering so no raw
        // control byte reaches the SQL (where indentMultiline would corrupt it); printable
        // characters keep the historical plain-literal rendering.
        return stringLiteral(String.valueOf(value));
    }

    /**
     * B-3 (TG-BLK-006): a string literal containing control characters is emitted as a
     * PostgreSQL escape string ({@code E'...'}) with backslash escapes, never as raw control
     * bytes that {@code indentMultiline} would silently "indent" into the literal's value.
     * Control-character-free values keep the historical {@code '...'} rendering (escape() quote
     * doubling), so existing artifacts are byte-identical.
     */
    @Override
    protected String stringLiteral(String value) {
        if (!containsControlCharacter(value)) {
            return "'" + escape(value) + "'";
        }
        StringBuilder literal = new StringBuilder("E'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> literal.append("\\\\");
                case '\'' -> literal.append("''");
                case '\n' -> literal.append("\\n");
                case '\t' -> literal.append("\\t");
                case '\r' -> literal.append("\\r");
                case '\b' -> literal.append("\\b");
                case '\f' -> literal.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        // Always two hex digits: E'\x9' followed by a literal hex digit would
                        // otherwise be consumed into the escape.
                        literal.append("\\x").append("%02x".formatted((int) c));
                    } else {
                        literal.append(c);
                    }
                }
            }
        }
        return literal.append("'").toString();
    }

    @Override
    protected String parameterPlaceholder(int oneBasedIndex) {
        return "$" + oneBasedIndex;
    }

    @Override
    protected boolean supportsDollarQuotedStrings() {
        return true;
    }

    private String routineParameterSignature() {
        // Parameter names stay unquoted (plan 1.3b exclusion): RoutineSqlNameAllocator emits
        // p_-prefixed lowercase names, and body references are emitted unquoted to keep plpgsql
        // parameter substitution unambiguous.
        return routineParameters.stream()
                .map(parameter -> parameter.sqlName() + " " + sqlType(parameter.type()))
                .collect(Collectors.joining(", "));
    }

    @Override
    protected List<String> reservedRoutineIdentifiers(boolean observability) {
        List<String> reserved = new ArrayList<>(List.of(
                "__titan_saved_state",
                "__titan_saved_message"
        ));
        if (observability) {
            reserved.add("__titan_telemetry_id");
            reserved.add("__titan_started_at");
        }
        return List.copyOf(reserved);
    }

    // plpgsql DECLARE names stay unquoted (plan 1.3b exclusion): they are allocator-generated
    // (p_/v_/__titan_ prefixed, lowercase, sanitized), so they can never be reserved words, and
    // quoting declared variables would force quoting every reference, including the plpgsql
    // variable substitutions inside embedded SQL where a quoted name shadows column resolution.
    private void emitPostgresDeclarationSection(Block body, boolean observability) {
        List<String> declarationLines = new ArrayList<>();
        if (observability) {
            declarationLines.add("__titan_telemetry_id BIGINT;");
            declarationLines.add("__titan_started_at TIMESTAMPTZ := clock_timestamp();");
        }
        for (DeclarationNode declaration : body.declarations()) {
            if (declaration instanceof DeclareVariable variable && isRoutineParameter(variable)) {
                continue;
            }
            declarationLines.add(postgresDeclarationLine(
                    declaration,
                    shouldIncludeDeclarationInitializer(declaration, body.statements())));
        }
        if (declarationLines.isEmpty()) {
            return;
        }
        out.line("DECLARE");
        out.indent();
        for (String line : declarationLines) {
            out.line(line);
        }
        out.dedent();
    }

    private String postgresDeclarationLine(DeclarationNode declaration) {
        return postgresDeclarationLine(declaration, true);
    }

    private String postgresDeclarationLine(DeclarationNode declaration, boolean includeInitializer) {
        if (declaration instanceof DeclareVariable variable) {
            StringBuilder sql = new StringBuilder()
                    .append(emittedVariableName(variable.name()))
                    .append(' ')
                    .append(sqlType(variable.type()));
            if (includeInitializer && variable.initializer() != null) {
                sql.append(" := ").append(variable.initializer().accept(this));
            }
            sql.append(';');
            return sql.toString();
        }
        String sql = declaration.accept(this);
        return sql.startsWith("DECLARE ") ? sql.substring("DECLARE ".length()) : sql;
    }

    @Override
    public String visitDeclareVariable(DeclareVariable node) {
        StringBuilder sql = new StringBuilder("DECLARE ")
                .append(emittedVariableName(node.name()))
                .append(' ')
                .append(sqlType(node.type()));
        if (node.initializer() != null) {
            sql.append(" := ").append(node.initializer().accept(this));
        }
        sql.append(';');
        return sql.toString();
    }

    @Override
    public String visitDeclareHandler(DeclareHandler node) {
        // PostgreSQL emission models exception flow with EXCEPTION blocks, never handler
        // declarations; a DeclareHandler reaching this emitter means the front end produced a
        // construct PostgreSQL emission does not model (plan 0.11 / E-10).
        throw new UnsupportedOperationException(
                "TITAN-E001: DECLARE ... HANDLER has no PostgreSQL emission mapping; "
                        + "the front end should lower exception handling to try/catch constructs for PostgreSQL.");
    }

    @Override
    public String visitBlock(Block node) {
        StringBuilder sql = new StringBuilder(blockBody(node));
        if (sql.length() > 0 && !sql.toString().startsWith("DECLARE\n")) {
            sql.insert(0, "BEGIN\n");
            sql.append("\nEND;");
        }
        return sql.toString();
    }

    @Override
    public String visitAssign(Assign node) {
        return node.target().accept(this) + " := " + node.expression().accept(this) + ";";
    }

    @Override
    public String visitWhileStatement(WhileStatement node) {
        return "WHILE " + node.condition().accept(this) + " LOOP\n"
                + indentMultiline(blockBody(node.body()))
                + "\nEND LOOP;";
    }

    @Override
    public String visitLoopStatement(LoopStatement node) {
        StringBuilder sql = new StringBuilder("LOOP\n")
                .append(indentMultiline(blockBody(node.body())));
        if (node.exitCondition() != null) {
            sql.append("\n    EXIT WHEN ").append(node.exitCondition().accept(this)).append(';');
        }
        sql.append("\nEND LOOP;");
        return sql.toString();
    }

    @Override
    public String visitForCursorStatement(ForCursorStatement node) {
        return "FOR " + emittedVariableName(node.variableName()) + " IN " + node.query().accept(this) + " LOOP\n"
                + indentMultiline(blockBody(node.body()))
                + "\nEND LOOP;";
    }

    @Override
    public String visitForEachStatement(ForEachStatement node) {
        return "FOREACH " + emittedVariableName(node.variableName()) + " IN ARRAY " + node.iterable().accept(this) + " LOOP\n"
                + indentMultiline(blockBody(node.body()))
                + "\nEND LOOP;";
    }

    /**
     * Native counting-loop emission ({@code FOR i IN a..b LOOP}, inclusive bounds). PL/pgSQL
     * evaluates the range once on entry and the FOR variable shadows the block-level
     * declaration the lowering keeps for the MySQL desugar; the lowering only produces
     * ForRangeStatement for loop-invariant bounds, so the once-evaluated range matches Java's
     * per-iteration condition check. Unlabeled EXIT/CONTINUE bind to the innermost loop
     * natively, so no label is needed (unlike MySQL).
     */
    @Override
    public String visitForRangeStatement(ForRangeStatement node) {
        return "FOR " + emittedVariableName(node.variableName()) + " IN " + node.start().accept(this) + ".." + node.end().accept(this)
                + " LOOP\n"
                + indentMultiline(blockBody(node.body()))
                + "\nEND LOOP;";
    }

    @Override
    public String visitBreakStatement(BreakStatement node) {
        return node.label() == null ? "EXIT;" : "EXIT " + node.label() + ";";
    }

    @Override
    public String visitContinueStatement(ContinueStatement node) {
        return node.label() == null ? "CONTINUE;" : "CONTINUE " + node.label() + ";";
    }

    @Override
    public String visitRaiseStatement(RaiseStatement node) {
        // F-10 (plan 2.2): the message is a real expression. String literals render inline
        // (byte-identical to the historical form); dynamic messages go through the USING
        // pattern, which accepts arbitrary expressions — never through the RAISE format
        // string, where '%' is a placeholder and would break or misrender.
        String messageSql = raiseMessageSql(node.message());
        if (node.sqlstate() == null || node.sqlstate().isBlank()) {
            return "RAISE EXCEPTION USING MESSAGE = " + messageSql + ";";
        }
        return "RAISE EXCEPTION USING ERRCODE = '" + escape(node.sqlstate()) + "', MESSAGE = "
                + messageSql + ";";
    }

    @Override
    public String visitDebugPrintStatement(DebugPrintStatement node) {
        String message = node.message() == null ? "''" : node.message().accept(this);
        return "RAISE NOTICE '%', " + message + ";";
    }

    @Override
    public String visitTryCatchFinallyStatement(TryCatchFinallyStatement node) {
        String savedState = "__titan_saved_state";
        String savedMessage = "__titan_saved_message";
        StringBuilder sql = new StringBuilder();
        sql.append("DECLARE ").append(savedState).append(" TEXT := NULL;\n");
        sql.append("DECLARE ").append(savedMessage).append(" TEXT := NULL;\n");
        if (node.catches() != null) {
            java.util.LinkedHashSet<String> catchVariables = new java.util.LinkedHashSet<>();
            for (CatchClause catchClause : node.catches()) {
                if (catchClause.exceptionVariable() == null || catchClause.exceptionVariable().isBlank()) {
                    continue;
                }
                catchVariables.add(catchClause.exceptionVariable());
            }
            for (String catchVariable : catchVariables) {
                sql.append("DECLARE ").append(emittedVariableName(catchVariable)).append(" TEXT := NULL;\n");
            }
        }
        sql.append("BEGIN\n");
        sql.append("    BEGIN\n");
        sql.append(indentMultiline(blockBody(node.tryBlock()))).append('\n');
        if (node.catches() != null && !node.catches().isEmpty()) {
            sql.append("    EXCEPTION\n");
            for (CatchClause catchClause : node.catches()) {
                sql.append("        WHEN ").append(renderSqlStateWhenClause(catchClause.sqlStates())).append(" THEN\n");
                sql.append("            ").append(emittedVariableName(catchClause.exceptionVariable())).append(" := SQLERRM;\n");
                sql.append(indentMultiline(blockBody(catchClause.body()), 3)).append('\n');
            }
            sql.append("        WHEN OTHERS THEN\n");
        } else {
            sql.append("    EXCEPTION WHEN OTHERS THEN\n");
        }
        sql.append("        ").append(savedState).append(" := SQLSTATE;\n");
        sql.append("        ").append(savedMessage).append(" := SQLERRM;\n");
        sql.append("    END;\n");
        if (node.finallyBlock() != null) {
            sql.append(indentMultiline(blockBody(node.finallyBlock()))).append('\n');
        }
        sql.append("    IF ").append(savedState).append(" IS NOT NULL THEN\n");
        sql.append("        RAISE EXCEPTION USING ERRCODE = ").append(savedState)
                .append(", MESSAGE = ").append(savedMessage).append(";\n");
        sql.append("    END IF;\n");
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
            sql.append(" DEFAULT VALUES");
        }
        if (node.onConflict() != null && node.onConflict().columns() != null && !node.onConflict().columns().isEmpty()) {
            sql.append(" ON CONFLICT (")
                    .append(node.onConflict().columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
                    .append(')');
            if (node.onConflict().updates() == null || node.onConflict().updates().isEmpty()) {
                sql.append(" DO NOTHING");
            } else {
                sql.append(" DO UPDATE SET ")
                        .append(node.onConflict().updates().stream()
                                .map(update -> quoteIdentifier(update.column()) + " = " + update.value().accept(this))
                                .collect(Collectors.joining(", ")));
            }
        }
        if (node.returning() != null && !node.returning().isEmpty()) {
            sql.append(" RETURNING ").append(returningSql(node.returning()));
        }
        return sql.toString();
    }

    @Override
    public String visitUpdateSql(UpdateSql node) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(quoteQualifiedName(node.table())).append(" SET ");
        sql.append(node.sets().stream()
                .map(set -> quoteIdentifier(set.column()) + " = " + set.value().accept(this))
                .collect(Collectors.joining(", ")));
        if (node.where() != null) {
            sql.append(" WHERE ").append(node.where().accept(this));
        }
        if (node.returning() != null && !node.returning().isEmpty()) {
            sql.append(" RETURNING ").append(returningSql(node.returning()));
        }
        return sql.toString();
    }

    @Override
    public String visitDeleteSql(DeleteSql node) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quoteQualifiedName(node.table()));
        if (node.where() != null) {
            sql.append(" WHERE ").append(node.where().accept(this));
        }
        if (node.returning() != null && !node.returning().isEmpty()) {
            sql.append(" RETURNING ").append(returningSql(node.returning()));
        }
        return sql.toString();
    }

    /**
     * G1 (spike B1): a query whose result is discarded as a routine statement must be issued with
     * {@code PERFORM} in PL/pgSQL — a bare {@code SELECT ...;} raises
     * {@code "query has no destination for result data / use PERFORM instead"} at execute time
     * (it CREATEs fine, since CREATE PROCEDURE does not validate the body, and fails only on CALL).
     *
     * <p>Only an {@link ExecuteSqlStatement} that wraps a result-bearing query node reaches this
     * branch: a discarded DSL {@code select(...).…fetch()} (including {@code .forUpdate()}) lowers
     * to {@code ExecuteSqlStatement(SelectSql)} (StatementLowerer), and set operations to
     * {@code Union/Intersect/ExceptSql}. Result-USING selects never take this path — they lower to
     * {@code ReturnStatement}, {@code SubqueryExpression}, cursor queries, or {@code SELECT … INTO}
     * shapes, none of which route through {@code visitExecuteSqlStatement}. INSERT/UPDATE/DELETE
     * (with or without {@code RETURNING}) and {@code RawSql} stay as plain statements: only
     * row-returning queries need {@code PERFORM}. MySQL accepts a bare {@code SELECT} statement in a
     * procedure body, so it keeps the base rendering (documented dialect asymmetry).</p>
     */
    @Override
    public String visitExecuteSqlStatement(ExecuteSqlStatement node) {
        if (isResultBearingQuery(node.sqlNode())) {
            return "PERFORM " + stripLeadingSelectKeyword(node.sqlNode().accept(this)) + ";";
        }
        return super.visitExecuteSqlStatement(node);
    }

    private static boolean isResultBearingQuery(SqlNode sqlNode) {
        return sqlNode instanceof SelectSql
                || sqlNode instanceof UnionSql
                || sqlNode instanceof IntersectSql
                || sqlNode instanceof ExceptSql;
    }

    /**
     * Turns a rendered query into the operand {@code PERFORM} expects. PL/pgSQL's {@code PERFORM}
     * substitutes for the leading {@code SELECT} keyword of a simple query, so the plain case drops
     * exactly that keyword ({@code SELECT …} / {@code SELECT DISTINCT …}). A query that does not
     * begin with {@code SELECT} — a leading {@code WITH} CTE or a parenthesised set operation —
     * cannot have its keyword swapped, so it is wrapped as a derived table
     * ({@code * FROM (<query>) AS __titan_discarded}), which {@code PERFORM} accepts and which
     * discards the result identically.
     */
    private static String stripLeadingSelectKeyword(String renderedQuery) {
        if (renderedQuery.startsWith("SELECT DISTINCT ")) {
            return renderedQuery.substring("SELECT DISTINCT ".length());
        }
        if (renderedQuery.startsWith("SELECT ")) {
            return renderedQuery.substring("SELECT ".length());
        }
        return "* FROM (" + renderedQuery + ") AS __titan_discarded";
    }

    /** RETURNING entries are plain column names (catalogued 1.3b); {@code *} stays bare. */
    private String returningSql(List<String> returning) {
        return returning.stream().map(this::columnSql).collect(Collectors.joining(", "));
    }

    /**
     * WS-C Phase 3 Rung 4 (§3.6 form 2) — expands each array-bind membership marker in a {@link RawSql}'s
     * text into PostgreSQL's native form {@code <lhs> = ANY(?)}. The {@code ?} is bound (by {@code USING}
     * order, like any other placeholder) to the whole collection — a native {@code bigint[]}/{@code
     * int[]}/{@code text[]} parameter — so no element ever reaches the SQL text. {@code = ANY('{}')} over
     * an empty array matches nothing, exactly as an empty {@code IN ()} would. Returns the text unchanged
     * when there are no array binds (the common scalar path).
     */
    private static String expandArrayBindMarkers(RawSql node) {
        String sql = node.sql();
        for (ArrayBind bind : node.arrayBinds()) {
            sql = sql.replace(RawSql.arrayBindMarker(bind.markerId()), bind.lhs() + " = ANY(?)");
        }
        return sql;
    }

    /**
     * The SQL-text expression that follows {@code EXECUTE} / {@code OPEN … FOR EXECUTE} for a {@link
     * RawSql}. For the common case (no identifier/raw-fragment splice) it is the quoted literal {@code
     * '<escaped sql>'}. WS-C Phase 3 Rung 3 ({@code permissive} only): when {@code node} carries
     * {@link RawSql#spliceBinds()}, the text is assembled at <b>runtime</b> via PostgreSQL {@code
     * format(...)} — each splice marker becomes a {@code %I} (identifier: runtime identifier quoting,
     * safe from quote-breaking) or {@code %s} (raw fragment: spliced verbatim), and the splice params are
     * passed as {@code format} arguments in their text position. Any literal {@code %} in the SQL is first
     * doubled to {@code %%} (so {@code format} passes it through), then the markers are inserted, then the
     * {@code ?} value placeholders are rewritten to {@code $n} (bound by {@code USING}, NOT by {@code
     * format} — so values stay bound while only the identifier/fragment is spliced). {@code $n} passes
     * through {@code format} untouched. The single source of truth for every dynamic-SQL emit site (the
     * generic {@code visitRawSql}, the single-row read, and the cursor), so identifier/fragment reads and
     * statements assemble identically.
     */
    private String executeSqlText(RawSql node) {
        String expanded = rewritePositionalPlaceholders(
                rewriteNamedParameters(expandArrayBindMarkers(node), node.parameters()));
        if (!node.hasSpliceBinds()) {
            return "'" + escape(expanded) + "'";
        }
        // Double every literal '%' so format() passes it through, THEN insert the %I/%s for each marker
        // (the markers carry no '%', so they are unaffected by the doubling). The splice params become
        // format() args in marker order (= text order, since markers are numbered left-to-right).
        String template = expanded.replace("%", "%%");
        List<String> formatArgs = new ArrayList<>();
        for (SpliceBind splice : node.spliceBinds()) {
            // RAW_FRAGMENT splices verbatim (%s). IDENTIFIER quotes the runtime name — and does so
            // qualified-name-aware: WS-C Phase 3 Rung 3 audit fix (Findings 2) a bare format('%I', x) quotes
            // a dotted "schema.table" as ONE identifier ("schema.table") and fails ("relation does not
            // exist"). The marker becomes %s with a runtime expression that splits the value on '.' and
            // quote_ident()s each segment, joining with '.' — exactly the compile-time quoteQualifiedName
            // behavior. A bare name (no dot) yields a single quoted segment = the old %I result, so the
            // reserved-word / embedded-quote identifier cases are unchanged.
            String conversion;
            if (splice.kind() == SpliceBind.Kind.IDENTIFIER) {
                conversion = "%s";
                formatArgs.add(qualifiedIdentifierQuoteExpr(emittedVariableName(splice.paramName())));
            } else {
                conversion = "%s";
                formatArgs.add(emittedVariableName(splice.paramName()));
            }
            template = template.replace(RawSql.spliceMarker(splice.markerId()), conversion);
        }
        return "format('" + escape(template) + "', " + String.join(", ", formatArgs) + ")";
    }

    /**
     * The PostgreSQL runtime expression that quotes a possibly schema-qualified identifier held in {@code
     * varExpr} (WS-C Phase 3 Rung 3, Findings 2). Splits the value on {@code '.'}, {@code quote_ident()}s
     * each segment (so each part is safely quoted, case-preserved, and injection-neutralised exactly as
     * {@code %I}), and re-joins with {@code '.'} — turning {@code billing.accounts} into {@code
     * "billing"."accounts"} and a bare {@code accounts} into {@code "accounts"} (identical to {@code
     * format('%I', accounts)} for the unqualified case). Mirrors the compile-time {@link
     * io.titan.transpiler.tir.AbstractSqlEmitter#quoteQualifiedName} split-on-dot rule, so dynamic and
     * static qualified names quote identically.
     */
    private static String qualifiedIdentifierQuoteExpr(String varExpr) {
        return "(SELECT string_agg(quote_ident(__titan_idpart), '.') "
                + "FROM unnest(string_to_array(" + varExpr + ", '.')) AS __titan_idpart)";
    }

    @Override
    public String visitRawSql(RawSql node) {
        rawSqlCounter++;
        // WS-C Phase 2: a JDBC-input RawSql carries `?` positional placeholders bound by USING order;
        // rewrite them to `$n` (the `:name` rewriter handles the @SQL named-parameter path and leaves
        // `?` untouched, so both are applied — `@SQL` text has no bare `?`). WS-C Phase 3 Rung 4: a
        // collection bind's membership marker is first expanded to `<lhs> = ANY(?)` (its `?` then numbered
        // with the others), binding the whole collection as ONE bigint[]/int[]/text[] array parameter.
        // WS-C Phase 3 Rung 3 (permissive only): an identifier/raw-fragment splice makes executeSqlText
        // return a runtime format('… %I/%s …', <splice params>) instead of a static '<sql>' literal — the
        // value binds below still bind via USING (only the identifier/fragment is spliced into the text).
        String sqlText = executeSqlText(node);

        if (node.parameters() == null || node.parameters().isEmpty()) {
            return "EXECUTE " + sqlText;
        }

        String usingArgs = node.parameters().stream().map(this::emittedVariableName).collect(Collectors.joining(", "));
        return "EXECUTE " + sqlText + " USING " + usingArgs;
    }

    /**
     * Native single-dialect single-row read over opaque source SQL (JDBC I-4 unparsed, WS-C Phase 2
     * §4 step 3). Reuses the {@link #visitRawSql} dynamic-EXECUTE body and inserts the multi-target
     * {@code INTO <vars>} between {@code EXECUTE '<sql>'} and {@code USING <params>} — PostgreSQL's
     * dynamic {@code EXECUTE … INTO} supports multiple targets natively, and assigns NULL to every
     * target when the query returns no row. The {@code ?} placeholders are rewritten to {@code $n}
     * (bound by {@code USING} order); the parameters are spliced as bound values, never text.
     */
    @Override
    public String visitRawReadIntoStatement(RawReadIntoStatement node) {
        rawSqlCounter++;
        // WS-C Phase 3 Rung 3: executeSqlText returns a runtime format('… %I …', …) for an
        // identifier/raw-fragment splice (permissive), else the static '<sql>' literal.
        String sqlText = executeSqlText(node.query());
        String intoTargets = node.variableNames().stream()
                .map(this::emittedVariableName)
                .collect(Collectors.joining(", "));

        StringBuilder execute = new StringBuilder("EXECUTE ")
                .append(sqlText)
                .append(" INTO ")
                .append(intoTargets);
        List<String> parameters = node.query().parameters();
        if (parameters != null && !parameters.isEmpty()) {
            execute.append(" USING ")
                    .append(parameters.stream().map(this::emittedVariableName).collect(Collectors.joining(", ")));
        }
        execute.append(';');

        if (node.notFoundRaise() == null) {
            return execute.toString();
        }

        // I-4 §6.1 early-return guard: the no-row test is ROW_COUNT-based, NOT a "first INTO target IS
        // NULL" proxy (an existing row with a NULL first column must not trip it). Critically it is NOT
        // FOUND-based either: PL/pgSQL's dynamic EXECUTE ... INTO does NOT set FOUND (verified on
        // PostgreSQL 16 — FOUND stays false even when a row is assigned), so an IF NOT FOUND guard would
        // raise on EVERY call. GET DIAGNOSTICS ... = ROW_COUNT is set by the dynamic EXECUTE and is the
        // reliable signal. The row-count local is declared in a self-contained DECLARE … BEGIN … END
        // sub-block (plpgsql requires DECLARE at block start — the same shape the try/cursor emitters
        // use), so no routine-level DECLARE is needed; the INTO targets are routine-level and assigned
        // across the sub-block boundary as usual.
        String rowCountLocal = routineSqlNameAllocator.allocateGeneratedName("titan_row_count");
        return "DECLARE\n"
                + "    " + rowCountLocal + " INTEGER;\n"
                + "BEGIN\n"
                + "    " + execute + "\n"
                + "    GET DIAGNOSTICS " + rowCountLocal + " = ROW_COUNT;\n"
                + "    IF " + rowCountLocal + " = 0 THEN\n"
                + indentMultiline(visitRaiseStatement(node.notFoundRaise()), 2) + "\n"
                + "    END IF;\n"
                + "END;";
    }

    /**
     * Native single-dialect insert-with-generated-key recovery (JDBC I-7 §6.3 / I-R8). Emits a single
     * dynamic {@code EXECUTE '<insert> RETURNING <keyColumn>' INTO <keyLocal> USING <params>;} — this is
     * the PostgreSQL JDBC driver's {@code getGeneratedKeys()} behavior made faithful: PostgreSQL's
     * dynamic {@code EXECUTE … INTO} reads the <b>actual inserted row's</b> auto-increment/identity
     * column ({@code keyColumn}, resolved from the Catalog — the JDBC ordinal {@code 1} does not name a
     * column), so it is <b>immune to an {@code AFTER INSERT} trigger</b> that advances any other
     * sequence. (A session {@code lastval()} read would return the trigger's sequence value here — the
     * wrong-key-value defect this form closes.) The {@code RETURNING} column is double-quoted so a
     * mixed-case/reserved-word identifier is safe; the {@code ?} placeholders are rewritten to
     * {@code $n} bound by {@code USING}, never spliced as text.
     */
    @Override
    public String visitGeneratedKeyReadStatement(GeneratedKeyReadStatement node) {
        rawSqlCounter++;
        String insertSql = stripTrailingSemicolon(rewritePositionalPlaceholders(node.insert().sql()));
        String returningSql = insertSql + " RETURNING " + quoteIdentifier(node.keyColumn());

        StringBuilder execute = new StringBuilder("EXECUTE '")
                .append(escape(returningSql))
                .append("' INTO ")
                .append(emittedVariableName(node.keyLocal()));
        List<String> parameters = node.insert().parameters();
        if (parameters != null && !parameters.isEmpty()) {
            execute.append(" USING ")
                    .append(parameters.stream().map(this::emittedVariableName).collect(Collectors.joining(", ")));
        }
        execute.append(';');
        return execute.toString();
    }

    /** Strips a single trailing {@code ;} (and surrounding whitespace) from opaque source INSERT text. */
    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.stripTrailing();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return sql;
    }

    /**
     * Native single-dialect multi-row read over opaque source SQL (JDBC I-5 unparsed, WS-C Phase 2
     * §4 step 3): an explicit dynamic refcursor opened over the source text, FETCHing each row's
     * columns into scalar locals and running {@code body} once per row. The refcursor is DECLAREd at
     * the top of a self-contained {@code DECLARE … BEGIN … END} sub-block (plpgsql requires DECLARE
     * at block start — the same self-contained-block shape the try/finally emitter uses), so the
     * statement is valid mid-routine. {@code body} emits through the ordinary statement path; the
     * {@code ?} placeholders are rewritten to {@code $n} bound by {@code USING}.
     */
    @Override
    public String visitRawCursorStatement(RawCursorStatement node) {
        rawSqlCounter++;
        String cursorName = routineSqlNameAllocator.allocateGeneratedName("titan_cur");
        // WS-C Phase 3 Rung 3: executeSqlText returns a runtime format('… %I …', …) for an
        // identifier/raw-fragment splice (permissive) — e.g. a dynamic ORDER BY over a cursor read — else
        // the static '<sql>' literal.
        String sqlText = executeSqlText(node.query());
        String fetchTargets = node.variableNames().stream()
                .map(this::emittedVariableName)
                .collect(Collectors.joining(", "));

        StringBuilder open = new StringBuilder("OPEN ")
                .append(cursorName)
                .append(" FOR EXECUTE ")
                .append(sqlText);
        List<String> parameters = node.query().parameters();
        if (parameters != null && !parameters.isEmpty()) {
            open.append(" USING ")
                    .append(parameters.stream().map(this::emittedVariableName).collect(Collectors.joining(", ")));
        }
        open.append(';');

        // plpgsql requires every DECLARE at the start of the enclosing block, and the FETCH runs at THIS
        // block's scope — so the per-row FETCH-target locals (attached to node.body().declarations() with
        // the same names as node.variableNames()) MUST be DECLAREd up here beside the refcursor, NOT left
        // inside the loop-body sub-block. Declaring them only in the body (the default blockBody nesting)
        // puts them out of scope at the `FETCH … INTO <targets>` ("__titan_row_… is not a known variable"
        // at CREATE). Hoist them and render the body with them removed so they are not double-declared.
        // (Mirrors MySqlEmitter's hoistedTargetDeclares.) WS-C Phase 3 Rung 2 deploy fix.
        Set<String> fetchTargetNames = new LinkedHashSet<>(node.variableNames());
        StringBuilder hoistedTargetDeclares = new StringBuilder();
        for (DeclarationNode declaration : node.body().declarations()) {
            if (declaration instanceof DeclareVariable variable && fetchTargetNames.contains(variable.name())) {
                hoistedTargetDeclares.append("    ")
                        .append(postgresDeclarationLine(variable, false))
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

        String bodySql = indentMultiline(blockBody(bodyWithoutFetchTargets), 2);

        StringBuilder sql = new StringBuilder("DECLARE ")
                .append(cursorName).append(" refcursor;\n")
                .append(hoistedTargetDeclares)
                .append("BEGIN\n")
                .append("    ").append(open).append('\n')
                .append("    LOOP\n")
                .append("        FETCH ").append(cursorName).append(" INTO ").append(fetchTargets).append(";\n")
                .append("        EXIT WHEN NOT FOUND;\n");
        if (!bodySql.isBlank()) {
            sql.append(bodySql).append('\n');
        }
        sql.append("    END LOOP;\n")
                .append("    CLOSE ").append(cursorName).append(";\n")
                .append("END;");
        return sql.toString();
    }

    /**
     * Native single-dialect unknown-shape result carrier (JDBC Tier-3, WS-C Phase 3 Rung 5; {@code
     * docs/transpilable-jdbc-subset.md} §3.6 / design contract D5). The pure metadata-driven generic
     * reader ({@code while (rs.next()) { row.put(md.getColumnLabel(i), rs.getObject(i)); ... }} →
     * {@code List<Map<String,Object>>}) lowers to a {@code jsonb}-returning function whose body runs the
     * dynamic SELECT once and aggregates every row into a JSON array of JSON objects:
     *
     * <pre>{@code
     *   EXECUTE 'SELECT COALESCE(jsonb_agg(to_jsonb(t)), ''[]''::jsonb) FROM (<sql>) t'
     *     INTO <result> USING <binds>;
     *   RETURN <result>;
     * }</pre>
     *
     * <p>{@code to_jsonb(t)} turns each result row into a JSON object keyed by its column LABELs (the
     * native, shape-agnostic row→object operation — the {@code Map<String,Object>} the Java built keyed by
     * {@code getColumnLabel}, independent of the static shape), {@code jsonb_agg} collects them into the
     * array (the {@code List}), and {@code COALESCE(…, '[]'::jsonb)} makes an empty result the empty array
     * {@code []} (matching {@code new ArrayList<>()} over no rows). The inner {@code <sql>} is the
     * carrier's {@link RawSql} text — a constant {@code '…'} literal under strict, or a runtime {@code
     * format(…)} assembly for a permissive splice (the {@code ?} value binds still bind via {@code
     * USING}); it is spliced as the derived-table source (the trailing {@code ;} is stripped at lowering,
     * so a {@code "SELECT … ;"} source does not break the {@code FROM (…) t} wrap). The {@code result}
     * local is declared in a self-contained {@code DECLARE … BEGIN … END} sub-block (plpgsql requires
     * DECLARE at block start), and the {@code RETURN} inside it returns from the function.</p>
     *
     * <h4>Fidelity of the jsonb carrier (WS-C Phase 3 Rung 5 audit — documented caveats)</h4>
     * The carrier returns the same column NAMES (labels), the same values, and the empty-case faithfully.
     * Two representation differences are inherent to the {@code jsonb} return type and are accepted (a
     * {@code Map<String,Object>} consumer indexes by KEY, so neither changes which data a key resolves to):
     * <ul>
     *   <li><b>Object key order is jsonb-normalized (length, then bytewise), NOT the SELECT/insertion
     *       order.</b> A source {@code LinkedHashMap} iterates in column order, but the recognizer also
     *       admits {@code HashMap} (hash order) and {@code TreeMap} (sorted), so a single server-side form
     *       cannot honor one specific iteration order; the faithful {@code Map} contract is {keys present,
     *       value per key}, which jsonb preserves exactly. ({@code jsonb} is deliberately chosen over
     *       order-preserving {@code json}/{@code row_to_json} because jsonb also collapses a DUPLICATE
     *       output label last-wins — matching {@code Map.put} — whereas {@code json} would keep both
     *       copies, diverging from the Java map.)</li>
     *   <li><b>Values are JSON-typed, not the source Java runtime types.</b> A {@code timestamp}/{@code
     *       date}/{@code bytea}/{@code interval} serializes to a JSON string, {@code numeric} to a JSON
     *       number, {@code boolean} to a JSON bool, an array to a JSON array, and a {@code timestamptz} is
     *       UTC-normalized — recoverable, but not {@code java.sql.Timestamp}/{@code BigDecimal}/{@code
     *       byte[]}. {@code to_jsonb} has a universal text fallback, so every built-in column type
     *       serializes (no runtime error); a consumer decodes the JSON scalar accordingly.</li>
     * </ul>
     */
    @Override
    public String visitDynamicResultStatement(DynamicResultStatement node) {
        rawSqlCounter++;
        String resultLocal = routineSqlNameAllocator.allocateGeneratedName("titan_result");
        // The inner SQL-text expression: a quoted '<sql>' literal (strict), or a runtime format(...)
        // (permissive splice). Either way it is a `text` expression; concatenating the jsonb-aggregation
        // wrapper around it with || keeps the value binds (the ? placeholders) untouched — they bind via
        // USING below, never reach the text.
        String innerSqlText = executeSqlText(node.query());
        String wrappedSqlText = "'SELECT COALESCE(jsonb_agg(to_jsonb(t)), ''[]''::jsonb) FROM (' || "
                + innerSqlText + " || ') t'";

        StringBuilder execute = new StringBuilder("EXECUTE ")
                .append(wrappedSqlText)
                .append(" INTO ")
                .append(emittedVariableName(resultLocal));
        List<String> parameters = node.query().parameters();
        if (parameters != null && !parameters.isEmpty()) {
            execute.append(" USING ")
                    .append(parameters.stream().map(this::emittedVariableName).collect(Collectors.joining(", ")));
        }
        execute.append(';');

        return "DECLARE\n"
                + "    " + emittedVariableName(resultLocal) + " jsonb;\n"
                + "BEGIN\n"
                + "    " + execute + "\n"
                + "    RETURN " + emittedVariableName(resultLocal) + ";\n"
                + "END;";
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
            return "(" + key + " = ANY(" + carrier + "))";
        }

        if ("__titan_is_null".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().getFirst().accept(this) + " IS NULL)";
        }

        if ("equals".equals(node.name()) && node.arguments().size() == 2) {
            return "(" + node.arguments().get(0).accept(this) + " IS NOT DISTINCT FROM "
                    + node.arguments().get(1).accept(this) + ")";
        }

        if ("__titan_str_equals".equals(node.name()) && node.arguments().size() == 2) {
            // Text equality must not inherit an ICU/database collation: Java String.equals is
            // exact.  UTF-8 byte comparison preserves valid Java string code-point sequences.
            return "(CONVERT_TO(" + node.arguments().get(0).accept(this) + ", 'UTF8') = CONVERT_TO("
                    + node.arguments().get(1).accept(this) + ", 'UTF8'))";
        }

        if ("__titan_str_concat".equals(node.name()) && node.arguments().size() == 2) {
            // B-10 (TG-BLK-012): route operands through the shared text coercion. PostgreSQL's
            // boolean::text already renders 'true'/'false', so booleanToTextSql keeps the plain
            // CAST AS TEXT here — emission is byte-identical to before for every operand type.
            String left = coerceToTextSql(node.arguments().get(0));
            String right = coerceToTextSql(node.arguments().get(1));
            return "(COALESCE(" + left + ", 'null') || COALESCE(" + right + ", 'null'))";
        }

        if ("__titan_char_code".equals(node.name()) && node.arguments().size() == 1) {
            return "ASCII(" + node.arguments().get(0).accept(this) + ")";
        }

        if ("__titan_text_base64url_encode_utf8".equals(node.name()) && node.arguments().size() == 1) {
            String value = node.arguments().getFirst().accept(this);
            return "TRANSLATE(RTRIM(ENCODE(CONVERT_TO(" + value
                    + ", 'UTF8'), 'base64'), '='), '+/', '-_')";
        }

        if ("__titan_text_base64url_decode_utf8".equals(node.name()) && node.arguments().size() == 1) {
            String value = node.arguments().getFirst().accept(this);
            return "CONVERT_FROM(DECODE(TRANSLATE(" + value
                    + ", '-_', '+/') || REPEAT('=', (4 - (CHAR_LENGTH(" + value
                    + ") % 4)) % 4), 'base64'), 'UTF8')";
        }

        if ("__titan_text_base64url_alphabet_index".equals(node.name()) && node.arguments().size() == 1) {
            String value = node.arguments().getFirst().accept(this);
            // The alphabet and accepted values are ASCII, so bytea POSITION both guarantees a
            // case-exact result and retains the Java index for every accepted character.  Text
            // STRPOS can otherwise inherit a nondeterministic ICU collation.
            return "(POSITION(CONVERT_TO(" + value + ", 'UTF8') IN CONVERT_TO("
                    + "'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_', 'UTF8')) - 1)";
        }

        if ("__titan_str_index_of".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            return "(STRPOS(" + target + ", " + needle + ") - 1)";
        }
        if ("__titan_str_index_of".equals(node.name()) && node.arguments().size() == 3) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            String fromIndex = node.arguments().get(2).accept(this);
            String normalizedFromIndex = "GREATEST(" + fromIndex + ", 0)";
            String searchRegion = "SUBSTRING(" + target + " FROM (" + normalizedFromIndex + " + 1))";
            String relativePosition = "STRPOS(" + searchRegion + ", " + needle + ")";
            return "(CASE"
                    + " WHEN " + needle + " = '' THEN LEAST(" + normalizedFromIndex + ", CHAR_LENGTH(" + target + "))"
                    + " WHEN " + normalizedFromIndex + " > CHAR_LENGTH(" + target + ") THEN -1"
                    + " WHEN " + relativePosition + " = 0 THEN -1"
                    + " ELSE (" + normalizedFromIndex + " + " + relativePosition + " - 1)"
                    + " END)";
        }
        if ("__titan_str_contains".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String needle = node.arguments().get(1).accept(this);
            return "(STRPOS(" + target + ", " + needle + ") > 0)";
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
                    + " ELSE SUBSTRING(" + target + " FROM (" + offset + " + 1) FOR CHAR_LENGTH(" + prefix + ")) = " + prefix
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
            return "SUBSTRING(" + target + " FROM " + oneBasedIndex + " FOR 1)";
        }
        if ("__titan_str_substring".equals(node.name()) && node.arguments().size() >= 2) {
            String target = node.arguments().get(0).accept(this);
            ExpressionNode startExpression = node.arguments().get(1);
            String oneBasedStart = oneBasedIndexSql(startExpression);
            if (node.arguments().size() == 2) {
                return "SUBSTRING(" + target + " FROM " + oneBasedStart + ")";
            }
            ExpressionNode endExpression = node.arguments().get(2);
            String length = substringLengthSql(startExpression, endExpression);
            return "SUBSTRING(" + target + " FROM " + oneBasedStart + " FOR " + length + ")";
        }
        if ("__titan_str_split".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String delimiter = node.arguments().get(1).accept(this);
            return "STRING_TO_ARRAY(" + target + ", " + delimiter + ")";
        }
        if ("__titan_str_matches".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String pattern = node.arguments().get(1).accept(this);
            return "(" + target + " ~ " + pattern + ")";
        }
        if ("__titan_str_format".equals(node.name()) && !node.arguments().isEmpty()) {
            String args = node.arguments().stream().map(arg -> arg.accept(this)).collect(Collectors.joining(", "));
            return "FORMAT(" + args + ")";
        }
        if ("__titan_math_random".equals(node.name()) && node.arguments().isEmpty()) {
            return "RANDOM()";
        }
        if ("__titan_math_log10".equals(node.name()) && node.arguments().size() == 1) {
            return "LOG(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_math_round".equals(node.name()) && node.arguments().size() == 1) {
            return runtimeQualified("java_round") + "(" + node.arguments().get(0).accept(this) + ", 0)";
        }
        // EmulationInsertionPass markers (plan 2.4, E-7): native PostgreSQL integer division and
        // modulo already match Java exactly — truncation toward zero, dividend-sign remainder,
        // SQLSTATE 22012 on a zero divisor — so the markers render as the plain operators
        // (byte-identical to the pre-pass output). MySQL needs runtime helpers instead.
        if (EmulationInsertionPass.INT_DIV_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return "(" + node.arguments().get(0).accept(this) + " / " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_MOD_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return "(" + node.arguments().get(0).accept(this) + " % " + node.arguments().get(1).accept(this) + ")";
        }
        // EmulationInsertionPass strict-wraparound markers (plan 2.4, E-11): 32-bit Java int
        // wraparound via the deployed titan_runtime helpers.
        if (EmulationInsertionPass.INT_ADD_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeQualified("java_int_add") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_SUB_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeQualified("java_int_sub") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if (EmulationInsertionPass.INT_MUL_MARKER.equals(node.name()) && node.arguments().size() == 2) {
            return runtimeQualified("java_int_mul") + "(" + node.arguments().get(0).accept(this)
                    + ", " + node.arguments().get(1).accept(this) + ")";
        }
        if ("__titan_static_get".equals(node.name()) && node.arguments().size() == 1) {
            return runtimeQualified("static_get") + "(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_time_localdate_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_DATE";
        }
        if ("__titan_time_localtime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "LOCALTIME";
        }
        if ("__titan_time_localdatetime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_TIMESTAMP";
        }
        if ("__titan_time_instant_now".equals(node.name()) && node.arguments().isEmpty()) {
            // CURRENT_TIMESTAMP is fixed at PostgreSQL transaction start. Instant.now() must
            // observe the database wall clock, including when a transaction has spent time in
            // earlier generated work before evaluating this expression.
            return "CLOCK_TIMESTAMP()";
        }
        if ("__titan_time_instant_of_epoch_millis".equals(node.name()) && node.arguments().size() == 1) {
            return "TO_TIMESTAMP((" + node.arguments().get(0).accept(this) + ") / 1000.0)";
        }
        if ("__titan_time_zoneddatetime_now".equals(node.name()) && node.arguments().isEmpty()) {
            return "CURRENT_TIMESTAMP";
        }
        if ("__titan_time_plus_days".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String days = node.arguments().get(1).accept(this);
            return "(" + target + " + (" + days + " * INTERVAL '1 day'))";
        }
        if ("__titan_time_minus_days".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String days = node.arguments().get(1).accept(this);
            return "(" + target + " - (" + days + " * INTERVAL '1 day'))";
        }
        if ("__titan_time_plus_hours".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String hours = node.arguments().get(1).accept(this);
            return "(" + target + " + (" + hours + " * INTERVAL '1 hour'))";
        }
        if ("__titan_time_minus_hours".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String hours = node.arguments().get(1).accept(this);
            return "(" + target + " - (" + hours + " * INTERVAL '1 hour'))";
        }
        if ("__titan_time_to_local_date".equals(node.name()) && node.arguments().size() == 1) {
            return "DATE(" + node.arguments().get(0).accept(this) + ")";
        }
        if ("__titan_time_to_local_time".equals(node.name()) && node.arguments().size() == 1) {
            return "CAST(" + node.arguments().get(0).accept(this) + " AS TIME)";
        }
        if ("__titan_time_to_instant".equals(node.name()) && node.arguments().size() == 1) {
            return "CAST(" + node.arguments().get(0).accept(this) + " AS TIMESTAMPTZ)";
        }
        if ("__titan_time_duration_zero".equals(node.name()) && node.arguments().isEmpty()) {
            return "INTERVAL '0 second'";
        }
        if ("__titan_time_period_zero".equals(node.name()) && node.arguments().isEmpty()) {
            return "INTERVAL '0 day'";
        }
        if ("__titan_time_duration_of_days".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 day')";
        }
        if ("__titan_time_duration_of_hours".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 hour')";
        }
        if ("__titan_time_duration_of_minutes".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 minute')";
        }
        if ("__titan_time_duration_of_seconds".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 second')";
        }
        if ("__titan_time_period_of_days".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 day')";
        }
        if ("__titan_time_period_of_months".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 month')";
        }
        if ("__titan_time_period_of_years".equals(node.name()) && node.arguments().size() == 1) {
            return "(" + node.arguments().get(0).accept(this) + " * INTERVAL '1 year')";
        }
        if ("__titan_time_plus_amount".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String amount = renderTemporalAmountPostgres(node.arguments().get(1));
            return "(" + target + " + " + amount + ")";
        }
        if ("__titan_time_minus_amount".equals(node.name()) && node.arguments().size() == 2) {
            String target = node.arguments().get(0).accept(this);
            String amount = renderTemporalAmountPostgres(node.arguments().get(1));
            return "(" + target + " - " + amount + ")";
        }

        if (node.name() != null && node.name().startsWith("__titan_") && !node.name().startsWith("__titan_internal_")) {
            // B-1 defense-in-depth (mirrors the MySQL 0.11 guard): an unrecognized intrinsic
            // would otherwise render as a literal call to a function that does not exist,
            // failing at runtime instead of emit time. __titan_internal_* names are exempt:
            // they are generated helper routines deployed by the pipeline itself.
            throw new UnsupportedOperationException(
                    "TITAN-E001: Titan intrinsic '" + node.name() + "' with " + node.arguments().size()
                            + " argument(s) has no PostgreSQL emission mapping; "
                            + "this construct cannot be transpiled for the PostgreSQL dialect yet.");
        }

        return renderDefaultFunctionCall(node);
    }

    @Override
    public String visitArrayConstructExpression(ArrayConstructExpression node) {
        String elements = node.elements().stream().map(element -> element.accept(this)).collect(Collectors.joining(", "));
        return "ARRAY[" + elements + "]::" + sqlType(new TArrayType(node.elementType()));
    }

    @Override
    public String visitArrayLengthExpression(ArrayLengthExpression node) {
        return "COALESCE(array_length(" + node.array().accept(this) + ", 1), 0)";
    }

    @Override
    public String visitArrayGetExpression(ArrayGetExpression node) {
        return "(" + node.array().accept(this) + ")[(" + node.index().accept(this) + ") + 1]";
    }

    private String renderTemporalAmountPostgres(ExpressionNode expression) {
        if (expression instanceof VariableRefExpression variableRef) {
            return variableRef.accept(this);
        }
        if (expression instanceof FunctionCallExpression functionCall) {
            return functionCall.accept(this);
        }
        throw unsupportedOnDialect("Unsupported temporal amount expression for PostgreSQL emission: " + expression);
    }

    @Override
    protected String blockBody(Block block) {
        if (!block.declarations().isEmpty()) {
            StringBuilder nested = new StringBuilder("DECLARE\n");
            for (DeclarationNode declaration : block.declarations()) {
                nested.append(indentMultiline(postgresDeclarationLine(
                        declaration,
                        shouldIncludeDeclarationInitializer(declaration, block.statements())))).append('\n');
            }
            nested.append("BEGIN\n");
            for (StatementNode statement : block.statements()) {
                nested.append(indentMultiline(statement.accept(this))).append('\n');
            }
            nested.append("END;");
            return nested.toString();
        }

        StringBuilder sql = new StringBuilder();
        for (StatementNode statement : block.statements()) {
            sql.append(statement.accept(this)).append('\n');
        }
        if (sql.length() > 0) {
            sql.setLength(sql.length() - 1);
        }
        return sql.toString();
    }

    private String renderSqlStateWhenClause(List<String> sqlStates) {
        if (sqlStates == null || sqlStates.isEmpty()) {
            return "OTHERS";
        }
        return sqlStates.stream()
                .map(state -> "SQLSTATE '" + escape(state) + "'")
                .collect(Collectors.joining(" OR "));
    }

    @Override
    protected String sqlType(TirType type) {
        return switch (type) {
            case TIntType ignored -> "INTEGER";
            case TBigintType ignored -> "BIGINT";
            case TBooleanType ignored -> "BOOLEAN";
            case TTextType ignored -> "TEXT";
            case TNumericType t -> "NUMERIC(" + t.precision() + "," + t.scale() + ")";
            case TDoubleType ignored -> "DOUBLE PRECISION";
            case TDateType ignored -> "DATE";
            case TTimeType ignored -> "TIME";
            case TTimestampType ignored -> "TIMESTAMP";
            case TTimestampTzType ignored -> "TIMESTAMPTZ";
            case TDurationType ignored -> "INTERVAL";
            case TPeriodType ignored -> "INTERVAL";
            case TArrayType t -> sqlType(t.elementType()) + "[]";
            case TJsonType ignored -> "JSONB";
            case TCompositeType ignored -> "RECORD";
            case TRecordType t -> qualifyRecordType(t.schema(), t.recordName());
            case TUuidType ignored -> "UUID";
            case TBytesType ignored -> "BYTEA";
            case TVoidType ignored -> "VOID";
        };
    }

    private String qualifyRecordType(String schema, String recordName) {
        return qualify(schema, SqlNames.recordSqlBaseName(recordName));
    }

    private String telemetryPayloadJson(Block body, List<String> sensitiveColumnsAccessed) {
        List<String> parameterEntries = routineParameters.stream()
                .map(parameter -> "\"" + parameter.sqlName().replace("\"", "\\\"") + "\":\""
                        + sqlType(parameter.type()).replace("\"", "\\\"") + "\"")
                .toList();

        StringBuilder payload = new StringBuilder("{");
        boolean hasField = false;

        if (!parameterEntries.isEmpty()) {
            payload.append("\"parameterTypes\":{")
                    .append(String.join(",", parameterEntries))
                    .append("}");
            hasField = true;
        }

        if (sensitiveColumnsAccessed != null && !sensitiveColumnsAccessed.isEmpty()) {
            if (hasField) {
                payload.append(",");
            }
            String values = sensitiveColumnsAccessed.stream()
                    .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(","));
            payload.append("\"sensitiveColumns\":[").append(values).append("]");
        }

        payload.append("}");
        return payload.toString();
    }

    /**
     * Escapes a value for inclusion in a single-quoted PostgreSQL string literal. Doubling quotes
     * is sufficient here (unlike MySQL): with standard_conforming_strings (on by default since
     * PostgreSQL 9.1), backslashes have no special meaning inside '...' literals.
     */
    @Override
    protected String escape(String value) {
        return value.replace("'", "''");
    }

    /**
     * PostgreSQL identifier quoting: double quotes with embedded double quotes doubled
     * (plan 1.3b). Rejects null/blank/NUL identifiers.
     */
    @Override
    protected String quoteIdentifier(String identifier) {
        requireQuotableIdentifier(identifier);
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
