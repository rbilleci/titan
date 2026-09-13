package io.titan.transpiler.tir;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.SecurityPolicy;
import io.titan.transpiler.DiscoveredEnumDefinition;
import io.titan.transpiler.DiscoveredRecordDefinition;
import io.titan.transpiler.DiscoveredViewDefinition;
import io.titan.transpiler.EntryPointDependencyGraph;
import io.titan.transpiler.EntryPointDependencyGraphBuilder;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.EntryPointKind;
import io.titan.transpiler.EnumDefinitionDiscovery;
import io.titan.transpiler.FeatureValidator;
import io.titan.transpiler.InternalHelperDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.NamingConventionEngine;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.RecordDefinitionDiscovery;
import io.titan.transpiler.ScheduledJobDefinition;
import io.titan.transpiler.SqlAnnotationProcessor;
import io.titan.transpiler.TriggerDefinition;
import io.titan.transpiler.ViewDefinitionDiscovery;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public final class TranspilationPipeline {

    private DiagnosticSink diagnostics = new DiagnosticSink();

    /**
     * Warnings recorded by the most recent {@link #transpile} run.
     */
    public List<TitanDiagnostic> warnings() {
        return diagnostics.warnings();
    }

    /**
     * One generated SQL artifact plus its structured metadata (plan 4.4, audit G-10):
     * {@code artifactKind} is the logical artifact kind ({@code procedure}, {@code function},
     * {@code trigger}, {@code scheduled-job}, {@code view}, {@code enum-lookup},
     * {@code record-type}), {@code sqlObjects} lists every catalog object the artifact creates
     * (typed signatures included), and {@code dependsOn} carries the outgoing dependency edges
     * resolved from the entry-point call graph and the typed TIR — packaging never re-parses
     * the SQL text.
     */
    public record GeneratedSql(
            String target,
            String className,
            String methodName,
            String artifactName,
            String annotationKind,
            List<String> parameterNames,
            List<String> parameterTypes,
            String returnType,
            String sourceFile,
            long sourceLine,
            String securityMode,
            String artifactKind,
            String schemaName,
            String sqlName,
            List<SqlObject> sqlObjects,
            List<SqlObjectRef> dependsOn,
            String sql
    ) {
        public GeneratedSql(String target, String className, String methodName, String sql) {
            this(target, className, methodName, methodName, sql);
        }

        public GeneratedSql(String target, String className, String methodName, String artifactName, String sql) {
            this(target, className, methodName, artifactName, "", List.of(), List.of(), "", "", 0, "",
                    "", "", "", List.of(), List.of(), sql);
        }

        public GeneratedSql(
                String target,
                String className,
                String methodName,
                String artifactName,
                String annotationKind,
                List<String> parameterNames,
                List<String> parameterTypes,
                String returnType,
                String sourceFile,
                long sourceLine,
                String securityMode,
                String sql
        ) {
            this(target, className, methodName, artifactName, annotationKind, parameterNames, parameterTypes,
                    returnType, sourceFile, sourceLine, securityMode, "", "", "", List.of(), List.of(), sql);
        }
    }

    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode
    ) {
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, List.of(), false, List.of(), false);
    }

    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews
    ) {
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, schemaDefinedViews, false, List.of(), false);
    }

    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews,
            boolean observability,
            List<String> sensitiveColumns
    ) {
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, schemaDefinedViews, observability, sensitiveColumns, false);
    }

    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews,
            boolean observability,
            List<String> sensitiveColumns,
            boolean debugMode
    ) {
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, schemaDefinedViews,
                observability, sensitiveColumns, debugMode, false);
    }

    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews,
            boolean observability,
            List<String> sensitiveColumns,
            boolean debugMode,
            boolean strictWraparound
    ) {
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, schemaDefinedViews,
                observability, sensitiveColumns, debugMode, strictWraparound, null);
    }

    /**
     * @param strictWraparound enables {@link EmulationInsertionPass}'s 32-bit wraparound mode
     *                         (plan 2.4, audit E-11): int-typed {@code +}/{@code -}/{@code *} on
     *                         provably-int operands are rewritten to the
     *                         {@code java_int_add/sub/mul} runtime helpers so Java's silent
     *                         32-bit overflow wrap is reproduced bit-exactly. Off by default
     *                         ("fail-loud parity": overflow raises on both dialects instead of
     *                         silently wrapping — see the pass javadoc). Integer division/modulo
     *                         Java parity (E-7) is always on regardless of this flag.
     * @param schemaModel      the introspected database catalog (full {@link io.titan.introspect.SchemaModel}:
     *                         table/column DDL incl. {@code ColumnMeta.autoIncrement}) for the
     *                         deployment schema, threaded to the JDBC lowerer so I-7 generated-key
     *                         recovery (§6.3) resolves the {@code INSERT … RETURNING <key-col>} column
     *                         from the Catalog (the JDBC ordinal {@code 1} does not name a column).
     *                         {@code null} when no catalog is available (the bare/test overloads): I-7
     *                         then rejects cleanly (I-R8) rather than guessing a key column, and every
     *                         non-JDBC / DSL / {@code @SQL} / null-model path keeps today's behavior
     *                         unchanged.
     */
    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews,
            boolean observability,
            List<String> sensitiveColumns,
            boolean debugMode,
            boolean strictWraparound,
            io.titan.introspect.SchemaModel schemaModel
    ) {
        // Backward-compatible 11-arg entry (no build-level sqlSafety) -> STRICT, secure by default.
        // Every existing caller (incl. tests) keeps STRICT behavior; the build-flag wire is the 12-arg
        // overload below (WS-C Phase 3 D6).
        return transpile(sourceFiles, classpathEntries, targets, schemas, strictMode, schemaDefinedViews,
                observability, sensitiveColumns, debugMode, strictWraparound, schemaModel, null);
    }

    /**
     * Fullest overload: adds the build-level {@code sqlSafety} setting ({@code "strict"} default |
     * {@code "permissive"}), parsed by {@link io.titan.transpiler.jdbc.SqlSafetyResolver#parseBuildLevel}
     * into the build-level default the JDBC lowerer's {@code SqlSafetyResolver} resolves against — a
     * method/class {@code @SqlSafety} still overrides it (narrowest-scope-wins). {@code null}/unknown
     * -> STRICT. WS-C Phase 3 D6: the build-flag -> codegen wire (this position previously hardcoded
     * STRICT, so {@code sqlSafety = permissive} in {@code titan{}} changed only the reports, never
     * codegen).
     */
    public List<GeneratedSql> transpile(
            List<Path> sourceFiles,
            List<Path> classpathEntries,
            List<String> targets,
            List<String> schemas,
            boolean strictMode,
            List<String> schemaDefinedViews,
            boolean observability,
            List<String> sensitiveColumns,
            boolean debugMode,
            boolean strictWraparound,
            io.titan.introspect.SchemaModel schemaModel,
            String sqlSafety
    ) {
        DiagnosticSink sink = new DiagnosticSink();
        this.diagnostics = sink;

        var parser = new JavaSourceParser();
        var parsed = parser.parse(sourceFiles, classpathEntries);
        validateSourcesCompile(parsed);

        List<DiscoveredViewDefinition> discoveredViews = new ViewDefinitionDiscovery().discover(parsed);
        List<DiscoveredEnumDefinition> discoveredEnums = new EnumDefinitionDiscovery().discover(parsed);
        List<DiscoveredRecordDefinition> discoveredRecords = new RecordDefinitionDiscovery().discover(parsed);
        // B-2 (TG-BLK-005): records/enums are identified by their source-local qualified name
        // everywhere downstream — keying by simple name silently collapsed same-simple-name
        // declarations (last-wins) while routines kept calling the never-created SQL objects.
        Set<String> discoveredRecordNames = discoveredRecords.stream()
                .map(DiscoveredRecordDefinition::qualifiedName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> discoveredEnumNames = discoveredEnums.stream()
                .map(DiscoveredEnumDefinition::qualifiedName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateNoGeneratedSqlNameCollisions(discoveredEnums, discoveredRecords, sink);
        String schema = schemas == null || schemas.isEmpty() ? "public" : schemas.getFirst();
        validateNoSchemaViewConflicts(discoveredViews, schemaDefinedViews, schema);

        List<DiscoveredEntryPoint> publicEntryPoints = new EntryPointDiscovery().discover(parsed);
        List<DiscoveredEntryPoint> entryPoints = new ArrayList<>(publicEntryPoints);
        entryPoints.addAll(new InternalHelperDiscovery().discover(parsed, publicEntryPoints));

        // Plan 3.1: dialect feasibility is validated at compile time — the validator receives the
        // parsed transpile targets so unsupported construct/dialect combinations fail here with a
        // positioned TITAN-E001 (and version-gated combinations warn) instead of at emit time.
        List<DialectId> targetDialects = targets.stream()
                .map(TranspilationPipeline::parseDialect)
                .distinct()
                .toList();
        new FeatureValidator().validate(parsed, entryPoints, targetDialects, sink);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processedSqlAnnotations =
                new SqlAnnotationProcessor().process(parsed, entryPoints);
        Map<String, Long> overloadCountsByMethod = entryPoints.stream()
                .collect(Collectors.groupingBy(
                        ep -> ep.className() + "#" + ep.methodName(),
                        Collectors.counting()));
        EntryPointDependencyGraph dependencyGraph = new EntryPointDependencyGraphBuilder().build(parsed, entryPoints);
        validateNoDependencyCycles(dependencyGraph);
        List<DiscoveredEntryPoint> ordered = dependencyGraph.topologicalOrder();

        emitDslPromotionWarnings(processedSqlAnnotations, sink);
        emitSecurityDefinerWarnings(entryPoints, sink);
        emitInternalHelperWarnings(entryPoints, sink);

        // WS-C Phase 2b: the JDBC lowering gate runs IN the transpile pipeline (not only the opt-in
        // compat linter). The build-level sqlSafety threads in here (STRICT default, secure-by-default);
        // a method/class @SqlSafety can still relax to PERMISSIVE via the resolver. The parsed transpile targets are threaded so
        // the lowerer can reject the MySQL non-constant cursor (decision 1) when MySQL is a target. The
        // SchemaModel (§6.3) lets the JDBC lowerer resolve the I-7 RETURNING key column from the Catalog;
        // null preserves the pre-Catalog behavior (I-7 rejects cleanly). The deployment schema is threaded
        // as the resolver's defaultSchema so a bare INSERT INTO <table> resolves against the deploy
        // schema's table, never a same-named table in a different schema in the model.
        var lowered = new JavaToTirLowerer().lower(
                parsed, entryPoints, sink, strictWraparound,
                io.titan.transpiler.jdbc.SqlSafetyResolver.parseBuildLevel(sqlSafety), targetDialects,
                schemaModel, schema);

        var emitter = new DialectDispatchingEmitter();
        Set<String> normalizedSensitiveColumns = normalizeSensitiveColumns(sensitiveColumns);

        Map<String, GeneratedSql> generated = new LinkedHashMap<>();
        for (String target : targets) {
            DialectId dialect = parseDialect(target);
            // Plan 3.4: naming rules and lossy-type caveats come from the dialect's capability
            // descriptor — the pipeline carries no per-dialect branches of its own.
            DialectCapabilities capabilities = DialectCapabilities.forDialect(dialect);
            Map<String, String> routineNameBySignature = ordered.stream()
                    .collect(Collectors.toMap(
                            DiscoveredEntryPoint::methodSignatureKey,
                            entryPoint -> toSqlRoutineName(entryPoint, capabilities.namingRules(), overloadCountsByMethod),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            // B-10 (TG-BLK-012): SQL routine name -> return type, so the emitter can tell that a
            // FunctionCallExpression to a generated routine yields a boolean and coerce it to
            // 'true'/'false' text (the consumer's pageInfo.hasNextPage / isRepeatable values reach
            // the JSON writer as boolean-returning routine calls, not bare parameters). Built for
            // the whole dialect up front because a routine can call a routine emitted later.
            Map<String, TirType> routineReturnTypeBySqlName = new LinkedHashMap<>();
            for (DiscoveredEntryPoint entryPoint : ordered) {
                if (entryPoint.kind() != EntryPointKind.STORED_FUNCTION) {
                    continue;
                }
                String sqlName = routineNameBySignature.getOrDefault(
                        entryPoint.methodSignatureKey(),
                        toSqlRoutineName(entryPoint, capabilities.namingRules(), overloadCountsByMethod));
                // WS-C Phase 3 Rung 5 — the unknown-shape carrier: a @StoredFunction whose body is the
                // pure metadata-driven generic reader returns a jsonb carrier (PostgreSQL), NOT the Java
                // List<Map<String,Object>> (which has no plain TIR mapping). Map it to TJsonType so the
                // return-type map (used for cross-routine call coercion) is consistent; the per-entry-point
                // emission below uses the same carrier-aware return type. (On MySQL the carrier is emitted
                // as a procedure, so this return type is unused there.)
                TirType returnType = isDynamicCarrierBody(lowered.get(entryPoint.methodSignatureKey()))
                        ? new TJsonType()
                        : mapFunctionReturnType(
                                entryPoint.returnType(), discoveredRecordNames, discoveredEnumNames, schema,
                                "return type of " + entryPoint.methodSignatureKey());
                routineReturnTypeBySqlName.put(sqlName, returnType);
            }
            emitter.useRoutineReturnTypes(dialect, routineReturnTypeBySqlName);

            ArtifactDescriber describer = emitter.describer(dialect);
            Map<String, SqlObjectRef> recordRefByName = new LinkedHashMap<>();
            Map<String, SqlObjectRef> enumRefByAccessorName = new LinkedHashMap<>();
            Map<String, SqlObjectRef> viewRefByNormalizedName = new LinkedHashMap<>();
            Map<String, SqlObjectRef> routineRefBySignature = routinePrimaryRefs(
                    ordered, routineNameBySignature, describer, schema);

            for (DiscoveredEnumDefinition discoveredEnum : discoveredEnums) {
                if (discoveredEnum.constants().isEmpty()) {
                    continue;
                }
                EnumLookupSpec enumLookupSpec = toEnumLookupSpec(discoveredEnum);
                String sql = emitter.emitEnumLookup(dialect, schema, enumLookupSpec);
                List<SqlObject> objects = describer.describeEnumLookup(schema, enumLookupSpec);
                // TG-BLK-011: dependency edges match accessor calls by exact described name —
                // a length-truncated accessor no longer starts with its (separately truncated)
                // table name plus the member join, so prefix matching cannot work here.
                for (int i = 1; i < objects.size(); i++) {
                    enumRefByAccessorName.put(objects.get(i).name(), objects.get(0).ref());
                }
                // B-2 (TG-BLK-005): keyed by qualified name — same-simple-name enums in
                // different enclosing types must both emit instead of last-wins collapsing.
                String unique = target + ":enum:" + discoveredEnum.qualifiedName();
                generated.put(unique, new GeneratedSql(
                        target,
                        discoveredEnum.qualifiedName(),
                        discoveredEnum.enumName(),
                        discoveredEnum.enumName(),
                        "",
                        List.of(),
                        List.of(),
                        "",
                        "",
                        0,
                        "invoker",
                        "enum-lookup",
                        objects.get(0).schema(),
                        objects.get(0).name(),
                        objects,
                        List.of(),
                        sql));
            }

            for (DiscoveredRecordDefinition discoveredRecord : discoveredRecords) {
                RecordModelSpec recordModelSpec = toRecordModelSpec(discoveredRecord);
                capabilities.timestampTzCaveat().ifPresent(caveat ->
                        warnRecordTimestampTzRange(discoveredRecord, caveat, sink));
                String sql = emitter.emitRecordModel(dialect, schema, recordModelSpec);
                List<SqlObject> objects = describer.describeRecordModel(schema, recordModelSpec);
                recordRefByName.put(discoveredRecord.qualifiedName(), objects.get(0).ref());
                // B-2 (TG-BLK-005): keyed by qualified name — same-simple-name records in
                // different enclosing types must both emit instead of last-wins collapsing.
                String unique = target + ":record:" + discoveredRecord.qualifiedName();
                generated.put(unique, new GeneratedSql(
                        target,
                        discoveredRecord.qualifiedName(),
                        discoveredRecord.recordName(),
                        discoveredRecord.recordName(),
                        "",
                        List.of(),
                        List.of(),
                        "",
                        "",
                        0,
                        "invoker",
                        "record-type",
                        objects.get(0).schema(),
                        objects.get(0).name(),
                        objects,
                        List.of(),
                        sql));
            }

            SharedViewOrder sharedViewOrder = orderSharedViewsByDependencies(discoveredViews, schema);
            for (DiscoveredViewDefinition view : sharedViewOrder.ordered()) {
                SqlObjectRef viewRef = viewRef(view.viewName(), schema);
                String normalized = normalizeViewName(view.viewName(), schema);
                viewRefByNormalizedName.put(normalized, viewRef);
                viewRefByNormalizedName.putIfAbsent(viewRef.name().toLowerCase(Locale.ROOT), viewRef);
            }
            for (DiscoveredViewDefinition view : sharedViewOrder.ordered()) {
                if (view.sqlBody() == null || view.sqlBody().isBlank()) {
                    throw new TitanDiagnosticException(new TitanDiagnostic(
                            TitanErrorCode.E005,
                            "@ViewDefinition requires a SQL string initializer for shared view '" + view.viewName() + "'",
                            view.sourceFile() + ":" + view.sourceLine(),
                            "Initialize the @ViewDefinition field with the view's SQL string",
                            null));
                }
                // Compatibility floor (plan Phase 6): PostgreSQL views are emitted with
                // WITH (security_invoker = true), which only exists from PG 15 onward — a
                // version-gated capability surfaces as a positioned TITAN-W005 naming the floor,
                // mirroring the MySQL 8.0.31 INTERSECT/EXCEPT gate.
                warnSecurityInvokerViewFloor(view, capabilities, sink);
                String sql = emitter.emitView(dialect, schema, view.viewName(), view.sqlBody());
                String normalized = normalizeViewName(view.viewName(), schema);
                SqlObjectRef viewRef = viewRefByNormalizedName.get(normalized);
                List<SqlObjectRef> viewDependencies = sharedViewOrder.dependencies()
                        .getOrDefault(normalized, Set.of()).stream()
                        .map(viewRefByNormalizedName::get)
                        .filter(Objects::nonNull)
                        .toList();
                String unique = target + ":view:" + normalized;
                generated.put(unique, new GeneratedSql(
                        target,
                        view.className(),
                        view.fieldName(),
                        view.fieldName(),
                        "",
                        List.of(),
                        List.of(),
                        "",
                        "",
                        0,
                        "invoker",
                        "view",
                        viewRef.schema(),
                        viewRef.name(),
                        List.of(SqlObject.of(SqlObject.Kind.VIEW, viewRef.schema(), viewRef.name())),
                        viewDependencies,
                        sql));
            }

            for (DiscoveredEntryPoint entryPoint : ordered) {
                String key = entryPoint.methodSignatureKey();
                var body = lowered.get(key);
                if (body == null) {
                    continue;
                }
                try {
                    Block remappedBody = remapRoutineReferences(body, routineNameBySignature, schema);
                    List<StatementNode> rawSqlStatements = toRawSqlStatements(
                            processedSqlAnnotations.getOrDefault(key, List.of()),
                            dialect
                    );
                    Block emittedBody = rawSqlStatements.isEmpty() ? remappedBody : withAppendedStatements(remappedBody, rawSqlStatements);
                    Block debugFilteredBody = filterDebugPrints(emittedBody, debugMode);
                    List<String> accessedSensitiveColumns = collectSensitiveColumnAccesses(debugFilteredBody, normalizedSensitiveColumns);
                    validateNoSensitiveDebugOutput(entryPoint, debugFilteredBody, accessedSensitiveColumns);
                    List<RoutineParameter> routineParameters = routineParametersFor(entryPoint, discoveredRecordNames, discoveredEnumNames, schema);
                    // WS-C Phase 3 Rung 5 — the unknown-shape carrier. A @StoredFunction whose body is the
                    // pure metadata-driven generic reader returns a carrier, not the Java List<Map> (which
                    // has no plain TIR mapping): jsonb on PostgreSQL, a native open RESULT SET on MySQL.
                    // MySQL forbids dynamic SQL in a FUNCTION (ERROR 1336 — §9 invariant 1), so a carrier
                    // function is emitted as a PROCEDURE there (its result set streams to the client; the
                    // regenerated caller deserializes). On PostgreSQL it stays a jsonb-returning function.
                    boolean dynamicCarrier = entryPoint.kind() == EntryPointKind.STORED_FUNCTION
                            && isDynamicCarrierBody(debugFilteredBody);
                    boolean emitCarrierAsProcedure = dynamicCarrier && dialect == DialectId.MYSQL;
                    EntryPointKind emittedKind = emitCarrierAsProcedure ? EntryPointKind.STORED_PROCEDURE : entryPoint.kind();
                    TirType functionReturnType = emittedKind == EntryPointKind.STORED_FUNCTION
                            ? (dynamicCarrier
                                    ? new TJsonType()
                                    : mapFunctionReturnType(entryPoint.returnType(), discoveredRecordNames, discoveredEnumNames, schema, "return type of " + entryPoint.methodSignatureKey()))
                            : null;
                    capabilities.timestampTzCaveat().ifPresent(caveat ->
                            warnSignatureTimestampTzRange(entryPoint, routineParameters, functionReturnType, caveat, sink));

                    String routineName = routineNameBySignature.getOrDefault(key, toSqlRoutineName(entryPoint, capabilities.namingRules(), overloadCountsByMethod));
                    SecurityMode securityMode = entryPoint.securityDefiner() ? SecurityMode.DEFINER : SecurityMode.INVOKER;
                    String sql = switch (emittedKind) {
                        case STORED_PROCEDURE -> emitter.emitProcedure(dialect, schema, routineName, securityMode, debugFilteredBody, routineParameters, observability, accessedSensitiveColumns);
                        case STORED_FUNCTION -> emitter.emitFunction(dialect, schema, routineName, securityMode, functionReturnType, debugFilteredBody, routineParameters, observability, accessedSensitiveColumns);
                        case TRIGGER -> emitTrigger(entryPoint, emitter, dialect, schema, routineName, securityMode, debugFilteredBody, accessedSensitiveColumns);
                        case SCHEDULED_JOB -> emitScheduledJob(entryPoint, emitter, dialect, schema, routineName, securityMode, debugFilteredBody, observability, accessedSensitiveColumns, sink);
                    };
                    List<SqlObject> objects = switch (emittedKind) {
                        case STORED_PROCEDURE -> describer.describeProcedure(schema, routineName, routineParameters);
                        case STORED_FUNCTION -> describer.describeFunction(schema, routineName, functionReturnType, routineParameters);
                        case TRIGGER -> describer.describeTrigger(schema, routineName, triggerSpec(entryPoint));
                        case SCHEDULED_JOB -> describer.describeScheduledJob(schema, routineName, scheduledJobSpec(entryPoint), routineParameters);
                    };
                    List<SqlObjectRef> dependsOn = routineDependsOn(
                            entryPoint,
                            debugFilteredBody,
                            routineParameters,
                            functionReturnType,
                            dependencyGraph,
                            routineRefBySignature,
                            recordRefByName,
                            enumRefByAccessorName,
                            viewRefByNormalizedName,
                            objects.get(0).ref());
                    String unique = target + ":" + entryPoint.methodSignatureKey();
                    generated.put(unique, new GeneratedSql(
                            target,
                            entryPoint.className(),
                            entryPoint.methodName(),
                            artifactName(entryPoint, overloadCountsByMethod),
                            entryPoint.internalHelper() ? "" : annotationKind(entryPoint.kind()),
                            entryPoint.parameterNames(),
                            entryPoint.parameterTypes(),
                            entryPoint.returnType(),
                            entryPoint.sourceFile(),
                            entryPoint.line(),
                            securityMode.name().toLowerCase(Locale.ROOT),
                            artifactKindFor(entryPoint.kind()),
                            objects.get(0).schema(),
                            objects.get(0).name(),
                            objects,
                            dependsOn,
                            sql));
                    // ATG-017b: emit the reviewable privilege policy (owner/revoke/grant) for a
                    // @SecurityDefiner routine as a SEPARATE PostgreSQL artifact, ordered right after the
                    // routine (map-insertion order = deploy order) so consumers can apply it under their
                    // own migration policy. MySQL's role/grant model differs and is a follow-up.
                    if (target.equals("postgresql")
                            && entryPoint.securityDefiner()
                            && entryPoint.securityPolicy().hasAny()
                            && (emittedKind == EntryPointKind.STORED_FUNCTION
                                    || emittedKind == EntryPointKind.STORED_PROCEDURE)) {
                        String policySql = postgresSecurityPolicySql(
                                schema, routineName, emittedKind == EntryPointKind.STORED_FUNCTION,
                                routineParameters, entryPoint.securityPolicy());
                        generated.put(unique + ":policy", new GeneratedSql(
                                target,
                                entryPoint.className(),
                                entryPoint.methodName(),
                                artifactName(entryPoint, overloadCountsByMethod) + "-policy",
                                "SecurityPolicy",
                                entryPoint.parameterNames(),
                                entryPoint.parameterTypes(),
                                entryPoint.returnType(),
                                entryPoint.sourceFile(),
                                entryPoint.line(),
                                securityMode.name().toLowerCase(Locale.ROOT),
                                policySql));
                    }
                } catch (TitanDiagnosticException exception) {
                    for (TitanDiagnostic diagnostic : exception.diagnostics()) {
                        sink.error(withEntryPointFallback(diagnostic, entryPoint));
                    }
                } catch (UnsupportedOperationException | IllegalArgumentException | IllegalStateException exception) {
                    sink.error(new TitanDiagnostic(
                            TitanErrorCode.E001,
                            exception.getMessage() + " [entry point " + entryPoint.methodSignatureKey()
                                    + ", target " + target + "]",
                            entryPointLocation(entryPoint),
                            null,
                            null));
                }
            }
        }

        sink.failIfErrors();
        return List.copyOf(generated.values());
    }

    private static TitanDiagnostic withEntryPointFallback(TitanDiagnostic diagnostic, DiscoveredEntryPoint entryPoint) {
        if (diagnostic.location() != null && !diagnostic.location().isBlank()) {
            return diagnostic;
        }
        return new TitanDiagnostic(
                diagnostic.code(),
                diagnostic.message() + " [entry point " + entryPoint.methodSignatureKey() + "]",
                entryPointLocation(entryPoint),
                diagnostic.suggestion(),
                diagnostic.docReference());
    }

    private static String entryPointLocation(DiscoveredEntryPoint entryPoint) {
        if (entryPoint == null || entryPoint.sourceFile() == null || entryPoint.sourceFile().isBlank()) {
            return null;
        }
        return entryPoint.sourceFile() + ":" + entryPoint.line();
    }

    private static void validateSourcesCompile(ParsedSources parsed) {
        List<String> compileErrors = parsed.diagnostics().getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(TranspilationPipeline::renderCompileError)
                .toList();
        if (compileErrors.isEmpty()) {
            return;
        }
        throw new TitanDiagnosticException(new TitanDiagnostic(
                TitanErrorCode.E001,
                "Java sources must compile before transpilation. javac reported "
                        + compileErrors.size() + " error(s):"
                        + System.lineSeparator()
                        + String.join(System.lineSeparator(), compileErrors),
                null,
                "Fix the reported Java compile errors before running the Titan transpiler",
                null));
    }

    private static String renderCompileError(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "<unknown source>" : diagnostic.getSource().getName();
        return source + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(Locale.ROOT);
    }

    private static void validateNoSchemaViewConflicts(
            List<DiscoveredViewDefinition> discoveredViews,
            List<String> schemaDefinedViews,
            String defaultSchema
    ) {
        if (discoveredViews == null || discoveredViews.isEmpty() || schemaDefinedViews == null || schemaDefinedViews.isEmpty()) {
            return;
        }

        Set<String> normalizedSchemaViews = new HashSet<>();
        for (String schemaView : schemaDefinedViews) {
            if (schemaView == null || schemaView.isBlank()) {
                continue;
            }
            normalizedSchemaViews.add(normalizeViewName(schemaView, defaultSchema));
        }

        for (DiscoveredViewDefinition view : discoveredViews) {
            if (!view.shared()) {
                continue;
            }
            String normalizedCodeView = normalizeViewName(view.viewName(), defaultSchema);
            if (normalizedSchemaViews.contains(normalizedCodeView)) {
                throw new TitanDiagnosticException(new TitanDiagnostic(
                        TitanErrorCode.E005,
                        "View '" + view.viewName() + "' is defined both in the database schema and in code. Schema-defined views take precedence",
                        view.sourceFile() + ":" + view.sourceLine(),
                        "Remove the @ViewDefinition or rename it",
                        null));
            }
        }
    }

    private static String normalizeViewName(String rawName, String defaultSchema) {
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(".")) {
            return normalized;
        }
        return defaultSchema.toLowerCase(Locale.ROOT) + "." + normalized;
    }

    /** Shared views in creation order plus the view-to-view dependency edges (normalized names). */
    private record SharedViewOrder(
            List<DiscoveredViewDefinition> ordered,
            Map<String, Set<String>> dependencies
    ) {
    }

    private static SharedViewOrder orderSharedViewsByDependencies(
            List<DiscoveredViewDefinition> discoveredViews,
            String defaultSchema
    ) {
        List<DiscoveredViewDefinition> sharedViews = discoveredViews.stream()
                .filter(DiscoveredViewDefinition::shared)
                .toList();
        if (sharedViews.isEmpty()) {
            return new SharedViewOrder(List.of(), Map.of());
        }

        Map<String, DiscoveredViewDefinition> byNormalizedName = sharedViews.stream()
                .collect(Collectors.toMap(
                        view -> normalizeViewName(view.viewName(), defaultSchema),
                        view -> view,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        byNormalizedName.forEach((normalizedName, view) -> {
            Set<String> deps = new HashSet<>();
            String sql = view.sqlBody() == null ? "" : view.sqlBody().toLowerCase(Locale.ROOT);
            for (String candidate : byNormalizedName.keySet()) {
                if (candidate.equals(normalizedName)) {
                    continue;
                }
                String simple = candidate.substring(candidate.indexOf('.') + 1);
                if (containsWholeIdentifier(sql, candidate) || containsWholeIdentifier(sql, simple)) {
                    deps.add(candidate);
                }
            }
            dependencies.put(normalizedName, deps);
        });

        List<DiscoveredViewDefinition> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String viewName : byNormalizedName.keySet()) {
            visitViewDependency(viewName, byNormalizedName, dependencies, visited, visiting, ordered);
        }
        return new SharedViewOrder(List.copyOf(ordered), Map.copyOf(dependencies));
    }

    private static void visitViewDependency(
            String viewName,
            Map<String, DiscoveredViewDefinition> byNormalizedName,
            Map<String, Set<String>> dependencies,
            Set<String> visited,
            Set<String> visiting,
            List<DiscoveredViewDefinition> ordered
    ) {
        if (visited.contains(viewName)) {
            return;
        }
        if (!visiting.add(viewName)) {
            DiscoveredViewDefinition view = byNormalizedName.get(viewName);
            throw new TitanDiagnosticException(new TitanDiagnostic(
                    TitanErrorCode.E005,
                    "Cycle detected in shared @ViewDefinition dependencies involving '" + viewName + "'",
                    view == null ? null : view.sourceFile() + ":" + view.sourceLine(),
                    "Break the cyclic view reference so shared views form an acyclic dependency graph",
                    null));
        }
        for (String dep : dependencies.getOrDefault(viewName, Set.of())) {
            visitViewDependency(dep, byNormalizedName, dependencies, visited, visiting, ordered);
        }
        visiting.remove(viewName);
        visited.add(viewName);
        ordered.add(byNormalizedName.get(viewName));
    }

    private static boolean containsWholeIdentifier(String sql, String identifier) {
        String pattern = "(?<![a-z0-9_])" + Pattern.quote(identifier) + "(?![a-z0-9_])";
        return Pattern.compile(pattern).matcher(sql).find();
    }

    /**
     * B-2 (TG-BLK-005): transpile-time gate for generated record/enum SQL name collisions.
     * Qualified naming (see {@link SqlNames}) makes same-simple-name declarations distinct, and
     * the {@code "__"} member join makes type-vs-accessor collisions structurally impossible —
     * what remains is two declarations whose qualified names snake-case identically (e.g. two
     * top-level {@code record SortPath} in different packages, or {@code Outer.SortPath} vs a
     * top-level {@code OuterSortPath}), or record members whose sanitized SQL names collide
     * within one record. Both are rejected here with positioned TITAN-E007 diagnostics naming
     * every involved declaration, instead of last-wins emission and a late, sourceless
     * "name collision" failure from the install-plan gate.
     */
    private static void validateNoGeneratedSqlNameCollisions(
            List<DiscoveredEnumDefinition> discoveredEnums,
            List<DiscoveredRecordDefinition> discoveredRecords,
            DiagnosticSink sink
    ) {
        Map<String, DiscoveredEnumDefinition> enumBySqlName = new LinkedHashMap<>();
        for (DiscoveredEnumDefinition discoveredEnum : discoveredEnums) {
            if (discoveredEnum.constants().isEmpty()) {
                continue; // not emitted (mirrors the emission loop's skip)
            }
            String sqlName = SqlNames.enumSqlBaseName(discoveredEnum.qualifiedName());
            DiscoveredEnumDefinition previous = enumBySqlName.putIfAbsent(sqlName, discoveredEnum);
            if (previous != null) {
                sink.error(new TitanDiagnostic(
                        TitanErrorCode.E007,
                        "Enum '" + previous.qualifiedName() + "' ("
                                + declarationLocation(previous.sourceFile(), previous.sourceLine())
                                + ") and enum '" + discoveredEnum.qualifiedName() + "' ("
                                + declarationLocation(discoveredEnum.sourceFile(), discoveredEnum.sourceLine())
                                + ") both map to the generated SQL name '" + sqlName + "'",
                        declarationLocation(discoveredEnum.sourceFile(), discoveredEnum.sourceLine()),
                        "Rename one of the enums (or one of their enclosing types) so their "
                                + "snake-cased qualified names differ",
                        null));
            }
        }

        Map<String, DiscoveredRecordDefinition> recordBySqlName = new LinkedHashMap<>();
        for (DiscoveredRecordDefinition discoveredRecord : discoveredRecords) {
            String sqlName = SqlNames.recordSqlBaseName(discoveredRecord.qualifiedName());
            DiscoveredRecordDefinition previous = recordBySqlName.putIfAbsent(sqlName, discoveredRecord);
            if (previous != null) {
                sink.error(new TitanDiagnostic(
                        TitanErrorCode.E007,
                        "Record '" + previous.qualifiedName() + "' ("
                                + declarationLocation(previous.sourceFile(), previous.sourceLine())
                                + ") and record '" + discoveredRecord.qualifiedName() + "' ("
                                + declarationLocation(discoveredRecord.sourceFile(), discoveredRecord.sourceLine())
                                + ") both map to the generated SQL type name '" + sqlName + "'",
                        declarationLocation(discoveredRecord.sourceFile(), discoveredRecord.sourceLine()),
                        "Rename one of the records (or one of their enclosing types) so their "
                                + "snake-cased qualified names differ",
                        null));
            }

            Map<String, String> memberBySqlName = new LinkedHashMap<>();
            memberBySqlName.put("new", "the generated constructor");
            for (DiscoveredRecordDefinition.RecordComponentDef component : discoveredRecord.components()) {
                String memberSqlName = sanitizeSqlIdentifier(component.name());
                String previousMember = memberBySqlName.putIfAbsent(memberSqlName, "component '" + component.name() + "'");
                if (previousMember != null) {
                    sink.error(new TitanDiagnostic(
                            TitanErrorCode.E007,
                            "Component '" + component.name() + "' of record '" + discoveredRecord.qualifiedName()
                                    + "' collides with " + previousMember + ": both map to the generated SQL function '"
                                    + SqlNames.recordMemberName(discoveredRecord.qualifiedName(), memberSqlName) + "'",
                            declarationLocation(discoveredRecord.sourceFile(), discoveredRecord.sourceLine()),
                            "Rename the component so its sanitized SQL name is unique within the record",
                            null));
                }
            }
        }
    }

    private static String declarationLocation(String sourceFile, int sourceLine) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return "<unknown source>";
        }
        return sourceFile + ":" + sourceLine;
    }

    private static EnumLookupSpec toEnumLookupSpec(DiscoveredEnumDefinition discoveredEnum) {
        List<EnumLookupSpec.EnumField> fields = discoveredEnum.fields().stream()
                .map(field -> new EnumLookupSpec.EnumField(
                        sanitizeSqlIdentifier(field.name()),
                        field.typeName()))
                .toList();

        List<EnumLookupSpec.EnumMethod> methods = discoveredEnum.methods().stream()
                .map(method -> new EnumLookupSpec.EnumMethod(
                        sanitizeSqlIdentifier(method.name()),
                        method.returnTypeName()))
                .toList();

        List<EnumLookupSpec.EnumValue> values = discoveredEnum.constants().stream()
                .map(constant -> new EnumLookupSpec.EnumValue(
                        constant.name(),
                        alignedFieldValues(constant.constructorArguments(), fields.size())))
                .toList();

        return new EnumLookupSpec(discoveredEnum.qualifiedName(), fields, methods, values);
    }

    private static RecordModelSpec toRecordModelSpec(DiscoveredRecordDefinition discoveredRecord) {
        List<RecordModelSpec.RecordField> fields = discoveredRecord.components().stream()
                .map(component -> new RecordModelSpec.RecordField(
                        sanitizeSqlIdentifier(component.name()),
                        component.typeName()))
                .toList();
        return new RecordModelSpec(discoveredRecord.qualifiedName(), fields);
    }

    private static List<String> alignedFieldValues(List<String> constructorArguments, int fieldCount) {
        List<String> values = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            if (constructorArguments != null && i < constructorArguments.size()) {
                values.add(constructorArguments.get(i));
            } else {
                values.add("null");
            }
        }
        return List.copyOf(values);
    }

    private static String sanitizeSqlIdentifier(String identifier) {
        String normalized = identifier == null ? "field" : identifier.trim();
        normalized = normalized.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        normalized = normalized.replaceAll("[^a-zA-Z0-9_]+", "_").toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "field";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "f_" + normalized;
        }
        return normalized;
    }

    private static void emitDslPromotionWarnings(
            Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processedSqlAnnotations,
            DiagnosticSink sink
    ) {
        processedSqlAnnotations.forEach((entryPoint, annotations) -> {
            for (SqlAnnotationProcessor.ProcessedSqlAnnotation annotation : annotations) {
                if (annotation.dslPromotionWarning()) {
                    String location = annotation.sourceFile() == null || annotation.sourceFile().isBlank()
                            ? entryPoint
                            : annotation.sourceFile() + ":" + annotation.sourceLine();
                    sink.warning(new TitanDiagnostic(
                            TitanErrorCode.W001,
                            stripDiagnosticDecorations(annotation.warningMessage(), TitanErrorCode.W001),
                            location,
                            null,
                            null));
                }
            }
        });
    }

    private static void emitSecurityDefinerWarnings(List<DiscoveredEntryPoint> entryPoints, DiagnosticSink sink) {
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            if (entryPoint.securityDefiner()) {
                sink.warning(new TitanDiagnostic(
                        TitanErrorCode.W002,
                        "@SecurityDefiner elevates routine privileges; review least-privilege grants. ["
                                + entryPoint.methodSignatureKey() + "]",
                        entryPointLocation(entryPoint),
                        null,
                        null));
            }
        }
    }

    private static void emitInternalHelperWarnings(List<DiscoveredEntryPoint> entryPoints, DiagnosticSink sink) {
        long helperCount = entryPoints.stream().filter(DiscoveredEntryPoint::internalHelper).count();
        if (helperCount > 0) {
            sink.warning(new TitanDiagnostic(
                    TitanErrorCode.W004,
                    "Emitting " + helperCount
                            + " source-local helper routine(s) reachable from annotated Titan entry points",
                    null,
                    null,
                    null));
        }
    }

    private static String stripDiagnosticDecorations(String message, TitanErrorCode code) {
        String stripped = message == null ? "" : message.trim();
        String prefix = code.code() + ": ";
        if (stripped.startsWith(prefix)) {
            stripped = stripped.substring(prefix.length());
        }
        if (stripped.endsWith(".")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static void validateNoDependencyCycles(EntryPointDependencyGraph dependencyGraph) {
        if (dependencyGraph.cycles().isEmpty()) {
            return;
        }
        String cycles = String.join("; ", dependencyGraph.cycles());
        throw new TitanDiagnosticException(new TitanDiagnostic(
                TitanErrorCode.E001,
                "Unsupported recursive helper call cycle. "
                        + "Titan requires bounded loop or traversal-state lowering for nested helpers instead of recursive Java helper lowering. "
                        + "Cycle: " + cycles,
                null,
                "rewrite the recursive helper path as an iterative scanner with scalar depth/index state "
                        + "or a compiler-known acyclic descriptor traversal",
                null));
    }

    private static List<StatementNode> toRawSqlStatements(
            List<SqlAnnotationProcessor.ProcessedSqlAnnotation> annotations,
            DialectId dialect
    ) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }

        return annotations.stream()
                // @SQL dialect constants are the DialectId enum names (validated upstream by
                // SqlAnnotationProcessor), so the match is name-based — no per-dialect cases.
                .filter(annotation -> dialect.name().equalsIgnoreCase(annotation.dialect()))
                .map(annotation -> (StatementNode) new ExecuteSqlStatement(
                        new RawSql(annotation.sql(), annotation.parameterNames(), annotation.dialect())))
                .toList();
    }

    private static Block withAppendedStatements(Block block, List<StatementNode> appendedStatements) {
        List<StatementNode> mergedStatements = new ArrayList<>(block.statements());
        mergedStatements.addAll(appendedStatements);
        return new Block(block.declarations(), List.copyOf(mergedStatements), block.exceptionHandlers());
    }

    private static Block filterDebugPrints(Block block, boolean debugMode) {
        if (debugMode) {
            return block;
        }
        List<StatementNode> statements = block.statements().stream()
                .map(TranspilationPipeline::filterDebugPrints)
                .filter(statement -> !(statement instanceof DebugPrintStatement))
                .toList();
        return new Block(block.declarations(), statements, block.exceptionHandlers());
    }

    private static StatementNode filterDebugPrints(StatementNode statement) {
        return switch (statement) {
            case Block nested -> filterDebugPrints(nested, false);
            case IfStatement ifStatement -> new IfStatement(
                    ifStatement.condition(),
                    filterDebugPrints(ifStatement.thenBlock(), false),
                    ifStatement.elseIfClauses().stream()
                            .map(clause -> new ElseIfClause(clause.condition(), filterDebugPrints(clause.block(), false)))
                            .toList(),
                    ifStatement.elseBlock() == null ? null : filterDebugPrints(ifStatement.elseBlock(), false));
            case WhileStatement whileStatement -> new WhileStatement(
                    whileStatement.condition(),
                    filterDebugPrints(whileStatement.body(), false),
                    whileStatement.label());
            case LoopStatement loopStatement -> new LoopStatement(
                    filterDebugPrints(loopStatement.body(), false),
                    loopStatement.exitCondition(),
                    loopStatement.label());
            case ForCursorStatement forCursorStatement -> new ForCursorStatement(
                    forCursorStatement.variableName(),
                    forCursorStatement.query(),
                    filterDebugPrints(forCursorStatement.body(), false),
                    forCursorStatement.label());
            case RawCursorStatement rawCursorStatement -> new RawCursorStatement(
                    rawCursorStatement.variableNames(),
                    rawCursorStatement.query(),
                    filterDebugPrints(rawCursorStatement.body(), false),
                    rawCursorStatement.label());
            case ForEachStatement forEachStatement -> new ForEachStatement(
                    forEachStatement.variableName(),
                    forEachStatement.variableType(),
                    forEachStatement.iterable(),
                    filterDebugPrints(forEachStatement.body(), false),
                    forEachStatement.label());
            case ForRangeStatement forRangeStatement -> new ForRangeStatement(
                    forRangeStatement.variableName(),
                    forRangeStatement.start(),
                    forRangeStatement.end(),
                    filterDebugPrints(forRangeStatement.body(), false),
                    forRangeStatement.label());
            case TryCatchFinallyStatement tryCatchFinallyStatement -> new TryCatchFinallyStatement(
                    filterDebugPrints(tryCatchFinallyStatement.tryBlock(), false),
                    tryCatchFinallyStatement.catches().stream()
                            .map(catchClause -> new CatchClause(
                                    catchClause.exceptionVariable(),
                                    catchClause.exceptionType(),
                                    catchClause.sqlStates(),
                                    filterDebugPrints(catchClause.body(), false)))
                            .toList(),
                    tryCatchFinallyStatement.finallyBlock() == null
                            ? null
                            : filterDebugPrints(tryCatchFinallyStatement.finallyBlock(), false));
            default -> statement;
        };
    }

    private static Block remapRoutineReferences(Block block, Map<String, String> routineNameBySignature, String schema) {
        List<DeclarationNode> declarations = block.declarations().stream()
                .map(declaration -> remapDeclaration(declaration, routineNameBySignature, schema))
                .toList();
        List<StatementNode> statements = block.statements().stream()
                .map(statement -> remapStatement(statement, routineNameBySignature, schema))
                .toList();
        List<DeclarationNode> exceptionHandlers = block.exceptionHandlers().stream()
                .map(declaration -> remapDeclaration(declaration, routineNameBySignature, schema))
                .toList();
        return new Block(declarations, statements, exceptionHandlers);
    }

    private static DeclarationNode remapDeclaration(DeclarationNode declaration, Map<String, String> routineNameBySignature, String schema) {
        return switch (declaration) {
            case DeclareVariable variable -> new DeclareVariable(
                    variable.name(),
                    qualifyRecordType(variable.type(), schema),
                    variable.nullable(),
                    remapExpression(variable.initializer(), routineNameBySignature, schema));
            case DeclareCursor cursor -> new DeclareCursor(
                    cursor.name(),
                    (SelectSql) remapSql(cursor.query(), routineNameBySignature, schema));
            case DeclareHandler ignored -> declaration;
        };
    }

    private static StatementNode remapStatement(StatementNode statement, Map<String, String> routineNameBySignature, String schema) {
        return switch (statement) {
            case Block block -> remapRoutineReferences(block, routineNameBySignature, schema);
            case Assign assign -> new Assign(
                    remapExpression(assign.target(), routineNameBySignature, schema),
                    remapExpression(assign.expression(), routineNameBySignature, schema));
            case IfStatement ifStatement -> new IfStatement(
                    remapExpression(ifStatement.condition(), routineNameBySignature, schema),
                    remapRoutineReferences(ifStatement.thenBlock(), routineNameBySignature, schema),
                    ifStatement.elseIfClauses().stream()
                            .map(clause -> new ElseIfClause(
                                    remapExpression(clause.condition(), routineNameBySignature, schema),
                                    remapRoutineReferences(clause.block(), routineNameBySignature, schema)))
                            .toList(),
                    ifStatement.elseBlock() == null ? null : remapRoutineReferences(ifStatement.elseBlock(), routineNameBySignature, schema));
            case WhileStatement whileStatement -> new WhileStatement(
                    remapExpression(whileStatement.condition(), routineNameBySignature, schema),
                    remapRoutineReferences(whileStatement.body(), routineNameBySignature, schema),
                    whileStatement.label());
            case LoopStatement loopStatement -> new LoopStatement(
                    remapRoutineReferences(loopStatement.body(), routineNameBySignature, schema),
                    remapExpression(loopStatement.exitCondition(), routineNameBySignature, schema),
                    loopStatement.label());
            case ForCursorStatement forCursor -> new ForCursorStatement(
                    forCursor.variableName(),
                    (SelectSql) remapSql(forCursor.query(), routineNameBySignature, schema),
                    remapRoutineReferences(forCursor.body(), routineNameBySignature, schema),
                    forCursor.label());
            case ForEachStatement forEach -> new ForEachStatement(
                    forEach.variableName(),
                    qualifyRecordType(forEach.variableType(), schema),
                    remapExpression(forEach.iterable(), routineNameBySignature, schema),
                    remapRoutineReferences(forEach.body(), routineNameBySignature, schema),
                    forEach.label());
            case ForRangeStatement forRange -> new ForRangeStatement(
                    forRange.variableName(),
                    remapExpression(forRange.start(), routineNameBySignature, schema),
                    remapExpression(forRange.end(), routineNameBySignature, schema),
                    remapRoutineReferences(forRange.body(), routineNameBySignature, schema),
                    forRange.label());
            case ReturnStatement ret -> new ReturnStatement(remapExpression(ret.expression(), routineNameBySignature, schema));
            case BreakStatement ignored -> statement;
            case ContinueStatement ignored -> statement;
            case RaiseStatement raise -> new RaiseStatement(
                    raise.sqlstate(),
                    remapExpression(raise.message(), routineNameBySignature, schema),
                    raise.details().stream()
                            .map(expr -> remapExpression(expr, routineNameBySignature, schema)).toList());
            case CallStatement call -> new CallStatement(
                    remapRoutineName(call.procedureName(), routineNameBySignature),
                    call.arguments().stream().map(arg -> remapExpression(arg, routineNameBySignature, schema)).toList());
            case DebugPrintStatement debugPrint -> new DebugPrintStatement(
                    remapExpression(debugPrint.message(), routineNameBySignature, schema));
            case ExecuteSqlStatement execute -> new ExecuteSqlStatement(remapSql(execute.sqlNode(), routineNameBySignature, schema));
            case SelectIntoStatement selectInto -> new SelectIntoStatement(
                    selectInto.variableName(),
                    (SelectSql) remapSql(selectInto.query(), routineNameBySignature, schema));
            // Raw read carries an opaque RawSql (no routine refs) over plain locals — nothing to
            // remap. The raw cursor's body is ordinary statements and must still be walked.
            case RawReadIntoStatement ignored -> statement;
            case RawCursorStatement rawCursor -> new RawCursorStatement(
                    rawCursor.variableNames(),
                    rawCursor.query(),
                    remapRoutineReferences(rawCursor.body(), routineNameBySignature, schema),
                    rawCursor.label());
            // The unknown-shape carrier (Rung 5) carries an opaque RawSql dynamic SELECT (no routine
            // refs) and returns it as a PG jsonb / MySQL result set — nothing to remap.
            case DynamicResultStatement ignored -> statement;
            // Insert-with-generated-key recovery carries an opaque RawSql INSERT (no routine refs)
            // and a scalar key local — nothing to remap (JDBC I-7 §6.3).
            case GeneratedKeyReadStatement ignored -> statement;
            case TransactionControlStatement ignored -> statement;
            case NullGuardStatement ignored -> statement;
            case CloseCursorStatement ignored -> statement;
            case TryCatchFinallyStatement tryCatchFinally -> new TryCatchFinallyStatement(
                    remapRoutineReferences(tryCatchFinally.tryBlock(), routineNameBySignature, schema),
                    tryCatchFinally.catches().stream()
                            .map(catchClause -> new CatchClause(
                                    catchClause.exceptionVariable(),
                                    catchClause.exceptionType(),
                                    catchClause.sqlStates(),
                                    remapRoutineReferences(catchClause.body(), routineNameBySignature, schema)))
                            .toList(),
                    tryCatchFinally.finallyBlock() == null
                            ? null
                            : remapRoutineReferences(tryCatchFinally.finallyBlock(), routineNameBySignature, schema));
        };
    }

    private static SqlNode remapSql(SqlNode sqlNode, Map<String, String> routineNameBySignature, String schema) {
        if (sqlNode == null) {
            return null;
        }
        return switch (sqlNode) {
            case SelectSql select -> new SelectSql(
                    select.columns().stream().map(col -> new SelectColumn(
                            remapExpression(col.expression(), routineNameBySignature, schema),
                            col.alias())).toList(),
                    select.distinct(),
                    select.from(),
                    select.fromAlias(),
                    select.joins() == null ? null : select.joins().stream()
                            .map(join -> new JoinSpec(
                                    join.joinType(),
                                    join.target(),
                                    join.targetAlias(),
                                    join.lateralTarget() == null
                                            ? null
                                            : (LateralSubquery) remapSql(join.lateralTarget(), routineNameBySignature, schema),
                                    remapExpression(join.condition(), routineNameBySignature, schema)))
                            .toList(),
                    remapExpression(select.where(), routineNameBySignature, schema),
                    select.groupBy().stream().map(expr -> remapExpression(expr, routineNameBySignature, schema)).toList(),
                    remapExpression(select.having(), routineNameBySignature, schema),
                    select.orderBy(),
                    select.limit(),
                    select.offset(),
                    select.existsWrapper(),
                    select.locking(),
                    select.ctes());
            case InsertSql insert -> new InsertSql(
                    insert.table(),
                    insert.columns(),
                    insert.values().stream().map(expr -> remapExpression(expr, routineNameBySignature, schema)).toList(),
                    insert.selectSource() == null ? null : (SelectSql) remapSql(insert.selectSource(), routineNameBySignature, schema),
                    insert.onConflict(),
                    insert.returning());
            case UpdateSql update -> new UpdateSql(
                    update.table(),
                    update.sets().stream().map(set -> new SetClause(set.column(), remapExpression(set.value(), routineNameBySignature, schema))).toList(),
                    remapExpression(update.where(), routineNameBySignature, schema),
                    update.returning());
            case DeleteSql delete -> new DeleteSql(
                    delete.table(),
                    remapExpression(delete.where(), routineNameBySignature, schema),
                    delete.returning());
            case UnionSql union -> new UnionSql(
                    remapSql(union.left(), routineNameBySignature, schema),
                    remapSql(union.right(), routineNameBySignature, schema),
                    union.all());
            case IntersectSql intersect -> new IntersectSql(
                    remapSql(intersect.left(), routineNameBySignature, schema),
                    remapSql(intersect.right(), routineNameBySignature, schema));
            case ExceptSql except -> new ExceptSql(
                    remapSql(except.left(), routineNameBySignature, schema),
                    remapSql(except.right(), routineNameBySignature, schema));
            case RawSql rawSql -> rawSql;
            case LateralSubquery lateral -> new LateralSubquery(
                    (SelectSql) remapSql(lateral.subquery(), routineNameBySignature, schema),
                    lateral.alias());
        };
    }

    private static ExpressionNode remapExpression(ExpressionNode expression, Map<String, String> routineNameBySignature, String schema) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case BinaryOpExpression binary -> new BinaryOpExpression(
                    remapExpression(binary.left(), routineNameBySignature, schema),
                    binary.operator(),
                    remapExpression(binary.right(), routineNameBySignature, schema));
            case FunctionCallExpression functionCall -> new FunctionCallExpression(
                    remapRoutineName(functionCall.name(), routineNameBySignature),
                    functionCall.arguments().stream().map(arg -> remapExpression(arg, routineNameBySignature, schema)).toList(),
                    functionCall.schema());
            case RecordConstructExpression recordConstruct -> new RecordConstructExpression(
                    schema,
                    recordConstruct.recordName(),
                    recordConstruct.arguments().stream().map(arg -> remapExpression(arg, routineNameBySignature, schema)).toList());
            case RecordFieldExpression recordField -> new RecordFieldExpression(
                    remapExpression(recordField.record(), routineNameBySignature, schema),
                    schema,
                    recordField.recordName(),
                    recordField.fieldName());
            case ArrayConstructExpression arrayConstruct -> new ArrayConstructExpression(
                    qualifyRecordType(arrayConstruct.elementType(), schema),
                    arrayConstruct.elements().stream()
                            .map(element -> remapExpression(element, routineNameBySignature, schema))
                            .toList());
            case ArrayLengthExpression arrayLength -> new ArrayLengthExpression(
                    remapExpression(arrayLength.array(), routineNameBySignature, schema));
            case ArrayGetExpression arrayGet -> new ArrayGetExpression(
                    remapExpression(arrayGet.array(), routineNameBySignature, schema),
                    remapExpression(arrayGet.index(), routineNameBySignature, schema),
                    qualifyRecordType(arrayGet.elementType(), schema));
            case CaseWhenExpression caseWhen -> new CaseWhenExpression(
                    caseWhen.conditions().stream().map(branch -> new CaseBranch(
                            remapExpression(branch.condition(), routineNameBySignature, schema),
                            remapExpression(branch.value(), routineNameBySignature, schema))).toList(),
                    remapExpression(caseWhen.elseValue(), routineNameBySignature, schema));
            case SubqueryExpression subquery -> new SubqueryExpression((SelectSql) remapSql(subquery.select(), routineNameBySignature, schema));
            case IsNullExpression isNull -> new IsNullExpression(remapExpression(isNull.expression(), routineNameBySignature, schema));
            case IsNotNullExpression isNotNull -> new IsNotNullExpression(remapExpression(isNotNull.expression(), routineNameBySignature, schema));
            case CastExpression cast -> new CastExpression(remapExpression(cast.expression(), routineNameBySignature, schema), qualifyRecordType(cast.targetType(), schema), cast.truncating());
            case CoalesceExpression coalesce -> new CoalesceExpression(coalesce.expressions().stream()
                    .map(expr -> remapExpression(expr, routineNameBySignature, schema)).toList());
            case ExistsExpression exists -> new ExistsExpression(
                    (SelectSql) remapSql(exists.subquery(), routineNameBySignature, schema),
                    exists.negated());
            case WindowFunctionExpression window -> new WindowFunctionExpression(
                    window.function(),
                    window.arguments().stream().map(arg -> remapExpression(arg, routineNameBySignature, schema)).toList(),
                    window.spec() == null ? null : new WindowSpec(
                            window.spec().partitionBy().stream()
                                    .map(arg -> remapExpression(arg, routineNameBySignature, schema)).toList(),
                            window.spec().orderBy().stream()
                                    .map(orderBy -> new OrderBySpec(
                                            remapExpression(orderBy.expression(), routineNameBySignature, schema),
                                            orderBy.direction()))
                                    .toList(),
                            window.spec().frame()));
            case GroupingSetSpec groupingSet -> new GroupingSetSpec(
                    groupingSet.kind(),
                    groupingSet.sets().stream()
                            .map(set -> set.stream()
                                    .map(expr -> remapExpression(expr, routineNameBySignature, schema))
                                    .toList())
                            .toList());
            default -> expression;
        };
    }

    private static TirType qualifyRecordType(TirType type, String schema) {
        if (type instanceof TRecordType recordType && (recordType.schema() == null || recordType.schema().isBlank())) {
            return new TRecordType(schema, recordType.recordName());
        }
        if (type instanceof TArrayType arrayType) {
            return new TArrayType(qualifyRecordType(arrayType.elementType(), schema));
        }
        return type;
    }

    private static String remapRoutineName(String routineName, Map<String, String> routineNameBySignature) {
        String mapped = routineNameBySignature.get(routineName);
        if (mapped != null) {
            return mapped;
        }
        if (isUnresolvedJavaMethodSignature(routineName)) {
            throw new TitanDiagnosticException(new TitanDiagnostic(
                    TitanErrorCode.E001,
                    "Unsupported helper method call inside Titan entry point: " + routineName
                            + ". Titan can only emit calls to source-local static helpers, annotated entry points, "
                            + "or built-in SQL/DSL functions today",
                    null,
                    "Move the helper into the transpiled source set or replace the call with a supported SQL/DSL construct",
                    null));
        }
        return routineName;
    }

    private static boolean isUnresolvedJavaMethodSignature(String routineName) {
        return routineName != null
                && routineName.contains("#")
                && routineName.contains("(")
                && routineName.endsWith(")");
    }

    private static String emitTrigger(
            DiscoveredEntryPoint entryPoint,
            DialectDispatchingEmitter emitter,
            DialectId dialect,
            String schema,
            String routineName,
            SecurityMode securityMode,
            Block body,
            List<String> accessedSensitiveColumns
    ) {
        return emitter.emitTrigger(
                dialect,
                schema,
                routineName,
                securityMode,
                triggerSpec(entryPoint),
                body,
                accessedSensitiveColumns
        );
    }

    private static TriggerSpec triggerSpec(DiscoveredEntryPoint entryPoint) {
        TriggerDefinition trigger = entryPoint.triggerDefinition();
        if (trigger == null) {
            throw new TitanDiagnosticException(new TitanDiagnostic(
                    TitanErrorCode.E002,
                    "Missing trigger definition for entry point: " + entryPoint.methodSignatureKey(),
                    entryPointLocation(entryPoint),
                    null,
                    null));
        }
        return new TriggerSpec(trigger.table(), trigger.timing(), trigger.events(), trigger.forEach());
    }

    private static ScheduledJobSpec scheduledJobSpec(DiscoveredEntryPoint entryPoint) {
        ScheduledJobDefinition scheduledJob = entryPoint.scheduledJobDefinition();
        if (scheduledJob == null) {
            throw new TitanDiagnosticException(new TitanDiagnostic(
                    TitanErrorCode.E002,
                    "Missing scheduled job definition for entry point: " + entryPoint.methodSignatureKey(),
                    entryPointLocation(entryPoint),
                    null,
                    null));
        }
        return new ScheduledJobSpec(scheduledJob.cron(), scheduledJob.name());
    }

    /**
     * Primary SQL object each routine artifact creates, keyed by method signature — the target
     * of call-graph dependency edges (plan 4.4, audit G-10).
     */
    private static Map<String, SqlObjectRef> routinePrimaryRefs(
            List<DiscoveredEntryPoint> ordered,
            Map<String, String> routineNameBySignature,
            ArtifactDescriber describer,
            String schema
    ) {
        Map<String, SqlObjectRef> refs = new LinkedHashMap<>();
        for (DiscoveredEntryPoint entryPoint : ordered) {
            String routineName = routineNameBySignature.get(entryPoint.methodSignatureKey());
            if (routineName == null) {
                continue;
            }
            SqlObjectRef ref = switch (entryPoint.kind()) {
                case STORED_PROCEDURE, SCHEDULED_JOB -> new SqlObjectRef(SqlObject.Kind.PROCEDURE, schema, routineName);
                case STORED_FUNCTION -> new SqlObjectRef(SqlObject.Kind.FUNCTION, schema, routineName);
                case TRIGGER -> entryPoint.triggerDefinition() == null
                        ? new SqlObjectRef(SqlObject.Kind.FUNCTION, schema, routineName)
                        : describer.describeTrigger(schema, routineName, triggerSpec(entryPoint)).get(0).ref();
            };
            refs.put(entryPoint.methodSignatureKey(), ref);
        }
        return refs;
    }

    private static SqlObjectRef viewRef(String viewName, String defaultSchema) {
        String trimmed = viewName.trim();
        int separator = trimmed.lastIndexOf('.');
        if (separator < 0) {
            return new SqlObjectRef(SqlObject.Kind.VIEW, defaultSchema, trimmed);
        }
        return new SqlObjectRef(
                SqlObject.Kind.VIEW,
                trimmed.substring(0, separator),
                trimmed.substring(separator + 1));
    }

    private static String artifactKindFor(EntryPointKind kind) {
        return switch (kind) {
            case STORED_PROCEDURE -> "procedure";
            case STORED_FUNCTION -> "function";
            case TRIGGER -> "trigger";
            case SCHEDULED_JOB -> "scheduled-job";
        };
    }

    /**
     * Outgoing dependency edges for one routine artifact (plan 4.4, audit G-10): call-graph
     * callees, record types used in the signature or body, enum lookup helpers invoked, and
     * shared views read or written — all resolved from typed structures. Name-substring
     * matching over SQL text is gone: {@code get_user} inside {@code get_user_orders} is not
     * an edge.
     */
    private static List<SqlObjectRef> routineDependsOn(
            DiscoveredEntryPoint entryPoint,
            Block body,
            List<RoutineParameter> routineParameters,
            TirType functionReturnType,
            EntryPointDependencyGraph dependencyGraph,
            Map<String, SqlObjectRef> routineRefBySignature,
            Map<String, SqlObjectRef> recordRefByName,
            Map<String, SqlObjectRef> enumRefByAccessorName,
            Map<String, SqlObjectRef> viewRefByNormalizedName,
            SqlObjectRef self
    ) {
        LinkedHashSet<SqlObjectRef> refs = new LinkedHashSet<>();
        for (DiscoveredEntryPoint callee : dependencyGraph.dependencies().getOrDefault(entryPoint, List.of())) {
            SqlObjectRef ref = routineRefBySignature.get(callee.methodSignatureKey());
            if (ref != null) {
                refs.add(ref);
            }
        }

        TirReferenceCollector references = TirReferenceCollector.collect(body);

        Set<String> recordNames = new LinkedHashSet<>(references.recordNames());
        for (RoutineParameter parameter : routineParameters) {
            collectRecordTypeNames(parameter.type(), recordNames);
        }
        collectRecordTypeNames(functionReturnType, recordNames);
        for (String recordName : recordNames) {
            SqlObjectRef ref = recordRefByName.get(recordName);
            if (ref != null) {
                refs.add(ref);
            }
        }

        for (String called : references.calledRoutineNames()) {
            // TG-BLK-011: exact-name lookup against the described accessor functions (each
            // mapped to its lookup table's ref). The former <table> + MEMBER_JOIN prefix match
            // breaks for length-truncated names, and exact matching also keeps __enum_a from
            // ever claiming __enum_a_b__x's accessors.
            SqlObjectRef ref = enumRefByAccessorName.get(called);
            if (ref != null) {
                refs.add(ref);
            }
        }

        for (String table : references.tableNames()) {
            SqlObjectRef ref = viewRefByNormalizedName.get(table);
            if (ref != null) {
                refs.add(ref);
            }
        }

        refs.remove(self);
        return List.copyOf(refs);
    }

    private static void collectRecordTypeNames(TirType type, Set<String> recordNames) {
        if (type instanceof TRecordType recordType) {
            recordNames.add(recordType.recordName());
        } else if (type instanceof TArrayType arrayType) {
            collectRecordTypeNames(arrayType.elementType(), recordNames);
        }
    }

    private static String emitScheduledJob(
            DiscoveredEntryPoint entryPoint,
            DialectDispatchingEmitter emitter,
            DialectId dialect,
            String schema,
            String routineName,
            SecurityMode securityMode,
            Block body,
            boolean observability,
            List<String> accessedSensitiveColumns,
            DiagnosticSink sink
    ) {
        ScheduledJobSpec scheduledJobSpec = scheduledJobSpec(entryPoint);
        String sql = emitter.emitScheduledJob(
                dialect,
                schema,
                routineName,
                securityMode,
                scheduledJobSpec,
                body,
                observability,
                accessedSensitiveColumns
        );
        // Plan 3.1/3.4: a lossy cron approximation is a compile-time warning through the sink
        // (naming the original cron and the approximation), not just a comment inside the
        // generated artifact — the comment stays as defense-in-depth for artifact readers.
        // Whether (and how) the dialect approximates is the emitter's call, not the pipeline's.
        emitter.scheduledJobCronApproximation(dialect, scheduledJobSpec.cron()).ifPresent(approximation ->
                sink.warning(new TitanDiagnostic(
                        TitanErrorCode.W005,
                        approximation.message() + " [scheduled job " + entryPoint.methodSignatureKey() + "]",
                        entryPointLocation(entryPoint),
                        approximation.suggestion(),
                        null)));
        return sql;
    }

    /**
     * Compatibility-floor gate (plan Phase 6): the dialect's view emission depends on a
     * security-invoker clause that only exists from a minimum server version onward
     * (PostgreSQL 15 for {@code WITH (security_invoker = true)}). A version-gated capability
     * draws a positioned {@code TITAN-W005} per shared view so the floor is surfaced at compile
     * time instead of failing at CREATE VIEW time on older servers.
     */
    private static void warnSecurityInvokerViewFloor(
            DiscoveredViewDefinition view,
            DialectCapabilities capabilities,
            DiagnosticSink sink
    ) {
        DialectCapabilities.Capability capability = capabilities.securityInvokerViews();
        if (!capability.isVersionGated()) {
            return;
        }
        String location = view.sourceFile() == null
                ? null
                : view.sourceFile() + ":" + view.sourceLine();
        sink.warning(new TitanDiagnostic(
                TitanErrorCode.W005,
                "security_invoker views require " + capabilities.displayName() + " >= "
                        + capability.minServerVersion()
                        + "; the generated CREATE VIEW fails on older " + capabilities.displayName()
                        + " servers (view '" + view.viewName() + "')",
                location,
                "Ensure every deployment target runs " + capabilities.displayName() + " "
                        + capability.minServerVersion() + " or newer",
                null));
    }

    /**
     * E-13 residual caveat (plan 3.1): Instant/ZonedDateTime/OffsetDateTime map to MySQL
     * TIMESTAMP(6), whose representable range is 1970-01-01 00:00:01 .. 2038-01-19 03:14:07 UTC.
     * Values outside that range fail or clamp at runtime, so targeting MySQL with a
     * TIMESTAMPTZ-typed routine signature emits a positioned warning.
     */
    private static void warnSignatureTimestampTzRange(
            DiscoveredEntryPoint entryPoint,
            List<RoutineParameter> routineParameters,
            TirType functionReturnType,
            DialectCapabilities.TimestampTzRangeCaveat caveat,
            DiagnosticSink sink
    ) {
        List<String> affected = new ArrayList<>();
        for (RoutineParameter parameter : routineParameters) {
            if (parameter.type() instanceof TTimestampTzType) {
                affected.add("parameter '" + parameter.javaName() + "'");
            }
        }
        if (functionReturnType instanceof TTimestampTzType) {
            affected.add("return value");
        }
        if (affected.isEmpty()) {
            return;
        }
        sink.warning(timestampTzRangeWarning(
                caveat,
                String.join(", ", affected) + " of " + entryPoint.methodSignatureKey(),
                entryPointLocation(entryPoint)));
    }

    /** Record-field variant of the E-13 range warning (record fields store the lossy type). */
    private static void warnRecordTimestampTzRange(
            DiscoveredRecordDefinition discoveredRecord,
            DialectCapabilities.TimestampTzRangeCaveat caveat,
            DiagnosticSink sink
    ) {
        List<String> affected = new ArrayList<>();
        for (DiscoveredRecordDefinition.RecordComponentDef component : discoveredRecord.components()) {
            if (isTimestampTzTypeName(component.typeName())) {
                affected.add("component '" + component.name() + "'");
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        String location = discoveredRecord.sourceFile() == null
                ? null
                : discoveredRecord.sourceFile() + ":" + discoveredRecord.sourceLine();
        sink.warning(timestampTzRangeWarning(
                caveat,
                String.join(", ", affected) + " of record " + discoveredRecord.qualifiedName(),
                location));
    }

    private static TitanDiagnostic timestampTzRangeWarning(
            DialectCapabilities.TimestampTzRangeCaveat caveat,
            String subject,
            String location
    ) {
        return new TitanDiagnostic(
                TitanErrorCode.W005,
                caveat.message() + " (" + subject + ")",
                location,
                caveat.suggestion(),
                null);
    }

    private static boolean isTimestampTzTypeName(String typeName) {
        if (typeName == null) {
            return false;
        }
        return switch (typeName.trim()) {
            case "java.time.Instant", "Instant",
                 "java.time.ZonedDateTime", "ZonedDateTime",
                 "java.time.OffsetDateTime", "OffsetDateTime" -> true;
            default -> false;
        };
    }

    private static Set<String> normalizeSensitiveColumns(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Set.of();
        }
        return configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static List<String> collectSensitiveColumnAccesses(Block block, Set<String> sensitiveColumns) {
        if (block == null || sensitiveColumns.isEmpty()) {
            return List.of();
        }
        Set<String> found = new HashSet<>();
        collectSensitiveFromStatements(block.statements(), sensitiveColumns, found);
        return found.stream().sorted().toList();
    }

    private static void collectSensitiveFromStatements(List<StatementNode> statements, Set<String> sensitiveColumns, Set<String> found) {
        for (StatementNode statement : statements) {
            switch (statement) {
                case Block nested -> collectSensitiveFromStatements(nested.statements(), sensitiveColumns, found);
                case IfStatement ifStatement -> {
                    collectSensitiveFromExpression(ifStatement.condition(), sensitiveColumns, found);
                    collectSensitiveFromStatements(ifStatement.thenBlock().statements(), sensitiveColumns, found);
                    if (ifStatement.elseBlock() != null) {
                        collectSensitiveFromStatements(ifStatement.elseBlock().statements(), sensitiveColumns, found);
                    }
                    for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                        collectSensitiveFromExpression(clause.condition(), sensitiveColumns, found);
                        collectSensitiveFromStatements(clause.block().statements(), sensitiveColumns, found);
                    }
                }
                case WhileStatement whileStatement -> {
                    collectSensitiveFromExpression(whileStatement.condition(), sensitiveColumns, found);
                    collectSensitiveFromStatements(whileStatement.body().statements(), sensitiveColumns, found);
                }
                case ForCursorStatement forCursorStatement -> {
                    collectSensitiveFromSql(forCursorStatement.query(), sensitiveColumns, found);
                    collectSensitiveFromStatements(forCursorStatement.body().statements(), sensitiveColumns, found);
                }
                case ForEachStatement forEachStatement -> {
                    collectSensitiveFromExpression(forEachStatement.iterable(), sensitiveColumns, found);
                    collectSensitiveFromStatements(forEachStatement.body().statements(), sensitiveColumns, found);
                }
                case ForRangeStatement forRangeStatement -> {
                    collectSensitiveFromExpression(forRangeStatement.start(), sensitiveColumns, found);
                    collectSensitiveFromExpression(forRangeStatement.end(), sensitiveColumns, found);
                    collectSensitiveFromStatements(forRangeStatement.body().statements(), sensitiveColumns, found);
                }
                case Assign assign -> {
                    collectSensitiveFromExpression(assign.target(), sensitiveColumns, found);
                    collectSensitiveFromExpression(assign.expression(), sensitiveColumns, found);
                }
                case ReturnStatement returnStatement -> collectSensitiveFromExpression(returnStatement.expression(), sensitiveColumns, found);
                case RaiseStatement raiseStatement -> {
                    collectSensitiveFromExpression(raiseStatement.message(), sensitiveColumns, found);
                    raiseStatement.details().forEach(detail -> collectSensitiveFromExpression(detail, sensitiveColumns, found));
                }
                case CallStatement callStatement -> callStatement.arguments().forEach(argument -> collectSensitiveFromExpression(argument, sensitiveColumns, found));
                case ExecuteSqlStatement executeSqlStatement -> collectSensitiveFromSql(executeSqlStatement.sqlNode(), sensitiveColumns, found);
                case SelectIntoStatement selectIntoStatement -> collectSensitiveFromSql(selectIntoStatement.query(), sensitiveColumns, found);
                case LoopStatement loopStatement -> collectSensitiveFromStatements(loopStatement.body().statements(), sensitiveColumns, found);
                case TryCatchFinallyStatement tryCatchFinallyStatement -> {
                    collectSensitiveFromStatements(tryCatchFinallyStatement.tryBlock().statements(), sensitiveColumns, found);
                    for (CatchClause catchClause : tryCatchFinallyStatement.catches()) {
                        collectSensitiveFromStatements(catchClause.body().statements(), sensitiveColumns, found);
                    }
                    if (tryCatchFinallyStatement.finallyBlock() != null) {
                        collectSensitiveFromStatements(tryCatchFinallyStatement.finallyBlock().statements(), sensitiveColumns, found);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void collectSensitiveFromSql(SqlNode sqlNode, Set<String> sensitiveColumns, Set<String> found) {
        switch (sqlNode) {
            case SelectSql selectSql -> {
                selectSql.columns().forEach(column -> collectSensitiveFromExpression(column.expression(), sensitiveColumns, found));
                if (selectSql.joins() != null) {
                    selectSql.joins().stream()
                            .map(JoinSpec::lateralTarget)
                            .filter(lateral -> lateral != null)
                            .forEach(lateral -> collectSensitiveFromSql(lateral, sensitiveColumns, found));
                }
                collectSensitiveFromExpression(selectSql.where(), sensitiveColumns, found);
                selectSql.groupBy().forEach(groupByExpression -> collectSensitiveFromExpression(groupByExpression, sensitiveColumns, found));
                collectSensitiveFromExpression(selectSql.having(), sensitiveColumns, found);
            }
            case InsertSql insertSql -> insertSql.values().forEach(value -> collectSensitiveFromExpression(value, sensitiveColumns, found));
            case UpdateSql updateSql -> {
                updateSql.sets().forEach(setClause -> collectSensitiveFromExpression(setClause.value(), sensitiveColumns, found));
                collectSensitiveFromExpression(updateSql.where(), sensitiveColumns, found);
            }
            case DeleteSql deleteSql -> collectSensitiveFromExpression(deleteSql.where(), sensitiveColumns, found);
            case UnionSql unionSql -> {
                collectSensitiveFromSql(unionSql.left(), sensitiveColumns, found);
                collectSensitiveFromSql(unionSql.right(), sensitiveColumns, found);
            }
            case IntersectSql intersectSql -> {
                collectSensitiveFromSql(intersectSql.left(), sensitiveColumns, found);
                collectSensitiveFromSql(intersectSql.right(), sensitiveColumns, found);
            }
            case ExceptSql exceptSql -> {
                collectSensitiveFromSql(exceptSql.left(), sensitiveColumns, found);
                collectSensitiveFromSql(exceptSql.right(), sensitiveColumns, found);
            }
            case RawSql ignored -> {
            }
            case LateralSubquery lateralSubquery -> collectSensitiveFromSql(lateralSubquery.subquery(), sensitiveColumns, found);
            case null -> {
            }
        }
    }

    private static void collectSensitiveFromExpression(ExpressionNode expression, Set<String> sensitiveColumns, Set<String> found) {
        if (expression == null) {
            return;
        }
        switch (expression) {
            case ColumnRefExpression columnRefExpression -> {
                String fq = (columnRefExpression.table() + "." + columnRefExpression.column()).toLowerCase(Locale.ROOT);
                if (sensitiveColumns.contains(fq)) {
                    found.add(fq);
                }
            }
            case BinaryOpExpression binaryOpExpression -> {
                collectSensitiveFromExpression(binaryOpExpression.left(), sensitiveColumns, found);
                collectSensitiveFromExpression(binaryOpExpression.right(), sensitiveColumns, found);
            }
            case FunctionCallExpression functionCallExpression -> functionCallExpression.arguments().forEach(argument -> collectSensitiveFromExpression(argument, sensitiveColumns, found));
            case RecordConstructExpression recordConstructExpression -> recordConstructExpression.arguments().forEach(argument -> collectSensitiveFromExpression(argument, sensitiveColumns, found));
            case RecordFieldExpression recordFieldExpression -> collectSensitiveFromExpression(recordFieldExpression.record(), sensitiveColumns, found);
            case ArrayConstructExpression arrayConstructExpression -> arrayConstructExpression.elements().forEach(element -> collectSensitiveFromExpression(element, sensitiveColumns, found));
            case ArrayLengthExpression arrayLengthExpression -> collectSensitiveFromExpression(arrayLengthExpression.array(), sensitiveColumns, found);
            case ArrayGetExpression arrayGetExpression -> {
                collectSensitiveFromExpression(arrayGetExpression.array(), sensitiveColumns, found);
                collectSensitiveFromExpression(arrayGetExpression.index(), sensitiveColumns, found);
            }
            case CaseWhenExpression caseWhenExpression -> {
                caseWhenExpression.conditions().forEach(condition -> {
                    collectSensitiveFromExpression(condition.condition(), sensitiveColumns, found);
                    collectSensitiveFromExpression(condition.value(), sensitiveColumns, found);
                });
                collectSensitiveFromExpression(caseWhenExpression.elseValue(), sensitiveColumns, found);
            }
            case SubqueryExpression subqueryExpression -> collectSensitiveFromSql(subqueryExpression.select(), sensitiveColumns, found);
            case IsNullExpression isNullExpression -> collectSensitiveFromExpression(isNullExpression.expression(), sensitiveColumns, found);
            case IsNotNullExpression isNotNullExpression -> collectSensitiveFromExpression(isNotNullExpression.expression(), sensitiveColumns, found);
            case CastExpression castExpression -> collectSensitiveFromExpression(castExpression.expression(), sensitiveColumns, found);
            case CoalesceExpression coalesceExpression -> coalesceExpression.expressions().forEach(value -> collectSensitiveFromExpression(value, sensitiveColumns, found));
            case ExistsExpression existsExpression -> collectSensitiveFromSql(existsExpression.subquery(), sensitiveColumns, found);
            case WindowFunctionExpression windowFunctionExpression -> {
                windowFunctionExpression.arguments().forEach(argument -> collectSensitiveFromExpression(argument, sensitiveColumns, found));
                if (windowFunctionExpression.spec() != null) {
                    windowFunctionExpression.spec().partitionBy().forEach(argument -> collectSensitiveFromExpression(argument, sensitiveColumns, found));
                    windowFunctionExpression.spec().orderBy().forEach(orderBy -> collectSensitiveFromExpression(orderBy.expression(), sensitiveColumns, found));
                }
            }
            case GroupingSetSpec groupingSetSpec -> groupingSetSpec.sets()
                    .forEach(set -> set.forEach(value -> collectSensitiveFromExpression(value, sensitiveColumns, found)));
            default -> {
            }
        }
    }

    private static void validateNoSensitiveDebugOutput(
            DiscoveredEntryPoint entryPoint,
            Block block,
            List<String> sensitiveColumnsAccessed
    ) {
        if (sensitiveColumnsAccessed.isEmpty()) {
            return;
        }
        if (containsDebugOrRaiseOutput(block)) {
            throw new TitanDiagnosticException(new TitanDiagnostic(
                    TitanErrorCode.E006,
                    "Sensitive column usage detected in a routine containing debug/raise output; avoid emitting sensitive values. ["
                            + entryPoint.methodSignatureKey() + "] columns=" + String.join(",", sensitiveColumnsAccessed),
                    entryPointLocation(entryPoint),
                    "Remove debug/raise output from routines that access sensitive columns",
                    null));
        }
    }

    private static boolean containsDebugOrRaiseOutput(Block block) {
        for (StatementNode statement : block.statements()) {
            switch (statement) {
                case RaiseStatement ignored -> {
                    return true;
                }
                case DebugPrintStatement ignored -> {
                    return true;
                }
                case Block nested -> {
                    if (containsDebugOrRaiseOutput(nested)) {
                        return true;
                    }
                }
                case IfStatement ifStatement -> {
                    if (containsDebugOrRaiseOutput(ifStatement.thenBlock())) {
                        return true;
                    }
                    if (ifStatement.elseBlock() != null && containsDebugOrRaiseOutput(ifStatement.elseBlock())) {
                        return true;
                    }
                    for (ElseIfClause clause : ifStatement.elseIfClauses()) {
                        if (containsDebugOrRaiseOutput(clause.block())) {
                            return true;
                        }
                    }
                }
                case WhileStatement whileStatement -> {
                    if (containsDebugOrRaiseOutput(whileStatement.body())) {
                        return true;
                    }
                }
                case ForCursorStatement forCursorStatement -> {
                    if (containsDebugOrRaiseOutput(forCursorStatement.body())) {
                        return true;
                    }
                }
                case ForEachStatement forEachStatement -> {
                    if (containsDebugOrRaiseOutput(forEachStatement.body())) {
                        return true;
                    }
                }
                case ForRangeStatement forRangeStatement -> {
                    if (containsDebugOrRaiseOutput(forRangeStatement.body())) {
                        return true;
                    }
                }
                case LoopStatement loopStatement -> {
                    if (containsDebugOrRaiseOutput(loopStatement.body())) {
                        return true;
                    }
                }
                case TryCatchFinallyStatement tryCatchFinallyStatement -> {
                    if (containsDebugOrRaiseOutput(tryCatchFinallyStatement.tryBlock())) {
                        return true;
                    }
                    for (CatchClause catchClause : tryCatchFinallyStatement.catches()) {
                        if (containsDebugOrRaiseOutput(catchClause.body())) {
                            return true;
                        }
                    }
                    if (tryCatchFinallyStatement.finallyBlock() != null && containsDebugOrRaiseOutput(tryCatchFinallyStatement.finallyBlock())) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static TirType mapFunctionReturnType(
            String javaReturnType,
            Set<String> discoveredRecordNames,
            Set<String> discoveredEnumNames,
            String schema,
            String context
    ) {
        return JavaTypeToTirMapper.map(javaReturnType, discoveredRecordNames, discoveredEnumNames, schema, context);
    }

    /**
     * WS-C Phase 3 Rung 5 — whether {@code body} is an unknown-shape carrier (a top-level {@link
     * DynamicResultStatement}). The JDBC front-end produces a {@code DynamicResultStatement} ONLY for the
     * pure metadata-driven generic reader ({@link io.titan.transpiler.jdbc.JdbcDynamicResultRecognizer}),
     * so its presence is the carrier marker — the pipeline then returns a jsonb carrier (PostgreSQL) and,
     * on MySQL, emits the routine as a procedure (the native open result set). {@code null}/empty body →
     * not a carrier.
     */
    private static boolean isDynamicCarrierBody(Block body) {
        if (body == null) {
            return false;
        }
        for (StatementNode statement : body.statements()) {
            if (statement instanceof DynamicResultStatement) {
                return true;
            }
        }
        return false;
    }

    /**
     * ATG-017b: the reviewable PostgreSQL privilege-policy SQL for a {@code @SecurityDefiner} routine —
     * {@code ALTER … OWNER TO}, {@code REVOKE ALL … FROM PUBLIC}, {@code GRANT EXECUTE … TO} — targeting
     * the routine by its full {@code schema.name(argtypes)} identity (so it is unambiguous under
     * overloading). Emitted separate from the routine body.
     */
    private static String postgresSecurityPolicySql(
            String schema,
            String routineName,
            boolean isFunction,
            List<RoutineParameter> routineParameters,
            SecurityPolicy policy
    ) {
        PostgreSqlTypeMapper typeMapper = new PostgreSqlTypeMapper();
        String argTypes = routineParameters.stream()
                .map(parameter -> typeMapper.toSqlType(parameter.type()))
                .collect(java.util.stream.Collectors.joining(", "));
        String routineTarget = (isFunction ? "FUNCTION " : "PROCEDURE ")
                + pgQuoteIdentifier(schema) + "." + pgQuoteIdentifier(routineName) + "(" + argTypes + ")";
        StringBuilder sql = new StringBuilder();
        if (!policy.ownerRole().isBlank()) {
            sql.append("ALTER ").append(routineTarget)
                    .append(" OWNER TO ").append(pgQuoteIdentifier(policy.ownerRole())).append(";\n");
        }
        if (policy.revokePublic()) {
            sql.append("REVOKE ALL ON ").append(routineTarget).append(" FROM PUBLIC;\n");
        }
        if (!policy.executeRoles().isEmpty()) {
            String roles = policy.executeRoles().stream()
                    .map(TranspilationPipeline::pgQuoteIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));
            sql.append("GRANT EXECUTE ON ").append(routineTarget).append(" TO ").append(roles).append(";\n");
        }
        return sql.toString().trim();
    }

    private static String pgQuoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static List<RoutineParameter> routineParametersFor(
            DiscoveredEntryPoint entryPoint,
            Set<String> discoveredRecordNames,
            Set<String> discoveredEnumNames,
            String schema
    ) {
        if (entryPoint == null || entryPoint.parameterNames() == null || entryPoint.parameterNames().isEmpty()) {
            return List.of();
        }
        NamingConventionEngine namingConventionEngine = new NamingConventionEngine();
        List<RoutineParameter> parameters = new ArrayList<>();
        for (int i = 0; i < entryPoint.parameterNames().size(); i++) {
            String javaName = entryPoint.parameterNames().get(i);
            String javaType = i < entryPoint.parameterTypes().size()
                    ? entryPoint.parameterTypes().get(i)
                    : "java.lang.String";
            // WS-C Phase 2 (§2.2): a JDBC Connection/DataSource is infrastructure, not data — it has no
            // in-DB runtime form, so it is dropped from the emitted routine signature (the un-transpiled
            // method still compiles and runs as ordinary JDBC). All other parameters map normally.
            if (isConnectionOrDataSourceType(javaType)) {
                continue;
            }
            parameters.add(new RoutineParameter(
                    javaName,
                    namingConventionEngine.parameterName(javaName),
                    mapFunctionReturnType(javaType, discoveredRecordNames, discoveredEnumNames, schema,
                            "parameter '" + javaName + "' of " + entryPoint.methodSignatureKey())
            ));
        }
        return List.copyOf(parameters);
    }

    /** True for {@code java.sql.Connection} / {@code javax.sql.DataSource} parameter type names (§2.2). */
    private static boolean isConnectionOrDataSourceType(String javaType) {
        if (javaType == null) {
            return false;
        }
        String erased = javaType;
        int generic = erased.indexOf('<');
        if (generic >= 0) {
            erased = erased.substring(0, generic);
        }
        erased = erased.trim();
        return erased.equals("java.sql.Connection") || erased.equals("javax.sql.DataSource");
    }

    private static DialectId parseDialect(String value) {
        // Plan 3.4: alias resolution lives on the DialectId constants — registering a new
        // dialect's spellings happens on the enum declaration, not in a switch here.
        return DialectId.parse(value).orElseThrow(() -> new TitanDiagnosticException(new TitanDiagnostic(
                TitanErrorCode.E001,
                "Unsupported Titan transpile target: " + value,
                null,
                "Use one of: " + DialectId.supportedTargets(),
                null)));
    }

    private static String toSqlRoutineName(
            DiscoveredEntryPoint entryPoint,
            DialectCapabilities.NamingRules naming,
            Map<String, Long> overloadCountsByMethod
    ) {
        String base = snakeCase(entryPoint.methodName());
        if (entryPoint.internalHelper()) {
            String suffix = "_" + helperSignatureHash(entryPoint.methodSignatureKey());
            String routineName = "__titan_internal_" + sanitizeSqlIdentifier(simpleClassName(entryPoint.className()))
                    + "_" + base + suffix;
            return truncateIdentifierPreservingSuffix(routineName, suffix, naming.identifierMaxLength());
        }
        // TG-BLK-011: public routine names compose user method names (plus the overload mangle
        // on non-overloading dialects) and must respect the identifier ceiling too. SqlNames'
        // hash-of-the-full-name truncation keeps distinct overload mangles distinct; on
        // overloading dialects equal long names stay equal (overloads share the SQL name by
        // design). Names within the limit are returned byte-identical.
        if (naming.supportsRoutineOverloading()) {
            return SqlNames.fitWithinDialectLimits(base);
        }

        String key = entryPoint.className() + "#" + entryPoint.methodName();
        long overloadCount = overloadCountsByMethod.getOrDefault(key, 0L);
        if (overloadCount <= 1) {
            return SqlNames.fitWithinDialectLimits(base);
        }

        String suffix = entryPoint.parameterTypes().stream()
                .map(TranspilationPipeline::mangleTypeToken)
                .collect(Collectors.joining("_"));
        return SqlNames.fitWithinDialectLimits(suffix.isBlank() ? base : base + "__" + suffix);
    }

    private static String annotationKind(EntryPointKind kind) {
        return switch (kind) {
            case STORED_PROCEDURE -> "StoredProcedure";
            case STORED_FUNCTION -> "StoredFunction";
            case TRIGGER -> "Trigger";
            case SCHEDULED_JOB -> "ScheduledJob";
        };
    }

    private static String snakeCase(String methodName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "helper";
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private static String helperSignatureHash(String signatureKey) {
        return Integer.toHexString(signatureKey == null ? 0 : signatureKey.hashCode());
    }

    private static String artifactName(
            DiscoveredEntryPoint entryPoint,
            Map<String, Long> overloadCountsByMethod
    ) {
        String key = entryPoint.className() + "#" + entryPoint.methodName();
        if (overloadCountsByMethod.getOrDefault(key, 0L) <= 1) {
            return entryPoint.methodName();
        }
        return entryPoint.methodName() + "__" + helperSignatureHash(entryPoint.methodSignatureKey());
    }

    private static String truncateIdentifierPreservingSuffix(String identifier, String suffix, int maxLength) {
        if (identifier.length() <= maxLength) {
            return identifier;
        }
        int prefixLength = Math.max(1, maxLength - suffix.length());
        return identifier.substring(0, prefixLength) + suffix;
    }

    private static String mangleTypeToken(String type) {
        return type.toLowerCase()
                .replace("[]", "_arr")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
