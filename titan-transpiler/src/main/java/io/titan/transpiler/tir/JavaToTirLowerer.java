package io.titan.transpiler.tir;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.DiagnosticSink;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/**
 * P0 lowering pass: converts validated entry-point Java AST into baseline TIR.
 *
 * <p>Public facade over the package-private lowering classes: {@link StatementLowerer}
 * (statements and control flow), {@link ExpressionLowerer} (expressions), and
 * {@link DslQueryLowerer} (Titan DSL query chains), with shared helpers in
 * {@link LowererSupport}.
 */
public final class JavaToTirLowerer {

    public Map<String, Block> lower(ParsedSources parsedSources, List<DiscoveredEntryPoint> entryPoints) {
        DiagnosticSink sink = new DiagnosticSink();
        Map<String, Block> lowered = lower(parsedSources, entryPoints, sink);
        sink.failIfErrors();
        return lowered;
    }

    /**
     * Lowers every discoverable entry point, recording per-entry-point failures on the sink
     * instead of aborting at the first one. Entry points that fail to lower are absent from
     * the returned map; callers decide when to fail via {@link DiagnosticSink#failIfErrors()}.
     */
    public Map<String, Block> lower(ParsedSources parsedSources, List<DiscoveredEntryPoint> entryPoints, DiagnosticSink sink) {
        return lower(parsedSources, entryPoints, sink, false);
    }

    /**
     * @param strictWraparound enables {@link EmulationInsertionPass}'s 32-bit wraparound rewrite
     *                         of int-typed arithmetic (plan 2.4, audit E-11); integer
     *                         division/modulo parity is always on regardless of this flag
     */
    public Map<String, Block> lower(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            DiagnosticSink sink,
            boolean strictWraparound
    ) {
        // Secure-by-default for the bare overloads (used pervasively by tests): the JDBC sqlSafety gate
        // resolves to STRICT unless a method/class/build @SqlSafety says otherwise, and the target
        // dialect set is treated as "all dialects" (null) so MySQL-specific lowering rejects still fire.
        return lower(parsedSources, entryPoints, sink, strictWraparound, io.titan.transpiler.jdbc.SqlSafetyMode.STRICT, null);
    }

    /**
     * Lowers every entry point, threading the WS-C Phase 2b JDBC lowering gate inputs.
     *
     * @param sqlSafetyDefault build-level raw-SQL safety default for the JDBC lowering gate
     *                         (narrowest-scope {@code @SqlSafety} wins); {@code null} ⇒ STRICT
     * @param targetDialects   transpile target dialects, so the lowerer can apply dialect-specific
     *                         rejects (MySQL non-constant cursor, decision 1); {@code null} ⇒ treat
     *                         every dialect as a target (conservative)
     */
    public Map<String, Block> lower(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            DiagnosticSink sink,
            boolean strictWraparound,
            io.titan.transpiler.jdbc.SqlSafetyMode sqlSafetyDefault,
            List<DialectId> targetDialects
    ) {
        return lower(parsedSources, entryPoints, sink, strictWraparound, sqlSafetyDefault, targetDialects, null);
    }

    /**
     * Lowers every entry point, additionally threading the introspected {@link io.titan.introspect.SchemaModel}
     * to the JDBC lowerer so I-7 generated-key recovery (§6.3) resolves the {@code INSERT … RETURNING
     * <key-col>} column from the Catalog (the JDBC ordinal {@code 1} does not name a column).
     *
     * @param schemaModel the deployment-schema catalog (table/column DDL incl.
     *                    {@code ColumnMeta.autoIncrement}); {@code null} when no catalog is available
     *                    (the bare/test overloads) — I-7 then rejects cleanly (I-R8) rather than guessing
     *                    a key column, and every non-JDBC / DSL / {@code @SQL} / null-model path is
     *                    unaffected
     */
    public Map<String, Block> lower(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            DiagnosticSink sink,
            boolean strictWraparound,
            io.titan.transpiler.jdbc.SqlSafetyMode sqlSafetyDefault,
            List<DialectId> targetDialects,
            io.titan.introspect.SchemaModel schemaModel
    ) {
        return lower(parsedSources, entryPoints, sink, strictWraparound, sqlSafetyDefault, targetDialects,
                schemaModel, null);
    }

    /**
     * Lowers every entry point, additionally threading the deployment schema name so I-7 generated-key
     * recovery resolves a <b>bare</b> {@code INSERT INTO <table>} against the deploy schema's table (not a
     * same-named table in a different schema in {@code schemaModel}).
     *
     * @param defaultSchema the deployment schema (the transpile's first {@code --schema}, or
     *                      {@code public}); {@code null} (the bare/test overloads) preserves the prior
     *                      name-only bare-table match — still fail-safe, an unmatched/ambiguous table
     *                      rejects (I-R8) rather than emitting a wrong key
     */
    public Map<String, Block> lower(
            ParsedSources parsedSources,
            List<DiscoveredEntryPoint> entryPoints,
            DiagnosticSink sink,
            boolean strictWraparound,
            io.titan.transpiler.jdbc.SqlSafetyMode sqlSafetyDefault,
            List<DialectId> targetDialects,
            io.titan.introspect.SchemaModel schemaModel,
            String defaultSchema
    ) {
        // Internal-invariant assertions (programmer error in the calling pipeline, not a user
        // diagnostic): there is no source tree to attach a positioned TitanDiagnostic to because
        // the inputs themselves are missing. Enforced by ExceptionDisciplineEnforcementTest.
        if (parsedSources == null) {
            throw new IllegalStateException("internal: parsedSources must not be null");
        }
        if (entryPoints == null) {
            throw new IllegalStateException("internal: entryPoints must not be null");
        }
        if (sink == null) {
            throw new IllegalStateException("internal: sink must not be null");
        }

        Map<String, DiscoveredEntryPoint> byKey = new HashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            byKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        // WS-C Phase 2b: the JDBC lowering gate resolves per-method effective sqlSafety with the SAME
        // authority the recognizer uses (no bypass), and rejects the MySQL non-constant cursor when
        // MySQL is (or may be) a target. A null target set ⇒ conservatively assume MySQL is targeted.
        io.titan.transpiler.jdbc.SqlSafetyResolver safetyResolver =
                new io.titan.transpiler.jdbc.SqlSafetyResolver(sqlSafetyDefault);
        boolean mysqlTargeted = targetDialects == null || targetDialects.contains(DialectId.MYSQL);

        Map<String, Block> lowered = new HashMap<>();

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath path = getCurrentPath();
                    Element element = parsedSources.trees().getElement(path);
                    if (element == null || element.getKind() != ElementKind.METHOD) {
                        return super.visitMethod(node, unused);
                    }

                    ExecutableElement method = (ExecutableElement) element;
                    String key = method.getEnclosingElement() + "#" + method.getSimpleName()
                            + "(" + method.getParameters().stream().map(p -> p.asType().toString()).reduce((a, b) -> a + "," + b).orElse("") + ")";
                    DiscoveredEntryPoint entryPoint = byKey.get(key);
                    if (entryPoint == null) {
                        return super.visitMethod(node, unused);
                    }

                    if (node.getBody() == null) {
                        sink.error(new TitanDiagnostic(
                                TitanErrorCode.E002,
                                "Entry point has no body: " + key,
                                entryPointLocation(entryPoint),
                                null,
                                null));
                        return super.visitMethod(node, unused);
                    }

                    try {
                        Block loweredBlock = lowerBody(node, path, parsedSources, entryPoint, sink,
                                safetyResolver.effectiveMode(method), mysqlTargeted, schemaModel, defaultSchema);
                        // The entry-point location feeds NullGuardStatement.sourceLocation() and,
                        // via withEntryPointContext, positions E003 diagnostics. TODO(plan 1.1):
                        // switch to per-statement SourceSpans once TIR nodes carry them.
                        Block nullAnalyzed = new NullAnalysisPass(entryPointLocation(entryPoint)).analyze(loweredBlock);
                        // Emulation insertion runs after null analysis (plan 2.4): the null pass
                        // must see the raw arithmetic shapes it guards, and the emulation pass
                        // understands the null pass's rewrites (CASE/COALESCE) but not vice versa.
                        Block emulated = new EmulationInsertionPass(strictWraparound, parameterTirTypes(method, key))
                                .apply(nullAnalyzed);
                        lowered.put(key, emulated);
                    } catch (TitanDiagnosticException exception) {
                        for (TitanDiagnostic diagnostic : exception.diagnostics()) {
                            sink.error(withEntryPointContext(diagnostic, entryPoint, key));
                        }
                    } catch (IllegalArgumentException exception) {
                        sink.error(withEntryPointContext(
                                new TitanDiagnostic(TitanErrorCode.E001, exception.getMessage(), null, null, null),
                                entryPoint,
                                key));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }

        return Map.copyOf(lowered);
    }

    /**
     * Lowers an entry-point body, gating on the WS-C Phase 2 JDBC lowerer (WS-C Phase 2 2b): a method
     * whose body bears {@code java.sql}/{@code javax.sql} handles is lowered through
     * {@link JdbcStatementLowerer} (which elides the handles and produces the native single-dialect
     * read/cursor/execute/transaction nodes); every other method (DSL / {@code @SQL} / plain Java) is
     * lowered through the stock {@link StatementLowerer#lowerBlock} unchanged. The JDBC lowerer's I-10
     * verdict (procedure → mandatory outer-atomic W005; function/trigger → hard E001) is surfaced on
     * the shared {@code sink}, positioned at the entry point, exactly like the recognizer's verdict.
     * The resolved {@code sqlSafetyMode} (the same authority the recognizer uses) and the
     * {@code mysqlTargeted} flag are threaded so the lowerer enforces the constant-SQL gate
     * (strict → {@code E004}) and the MySQL non-constant cursor reject (decision 1) in-pipeline.
     */
    private static Block lowerBody(
            MethodTree node,
            TreePath methodPath,
            ParsedSources parsedSources,
            DiscoveredEntryPoint entryPoint,
            DiagnosticSink sink,
            io.titan.transpiler.jdbc.SqlSafetyMode sqlSafetyMode,
            boolean mysqlTargeted,
            io.titan.introspect.SchemaModel schemaModel,
            String defaultSchema
    ) {
        TreePath bodyPath = new TreePath(methodPath, node.getBody());
        if (!JdbcStatementLowerer.methodBodyBearsJdbcHandles(bodyPath, parsedSources)) {
            return StatementLowerer.lowerBlock(bodyPath, parsedSources);
        }
        io.titan.transpiler.EntryPointKind entryKind = entryPoint.kind();
        boolean storedProcedure =
                entryKind == io.titan.transpiler.EntryPointKind.STORED_PROCEDURE;
        String location = entryPointLocation(entryPoint);
        JdbcStatementLowerer.DiagnosticReporter reporter = new JdbcStatementLowerer.DiagnosticReporter() {
            @Override
            public void warn(String message) {
                sink.warning(new TitanDiagnostic(TitanErrorCode.W005, message, location, null, null));
            }

            @Override
            public void error(String message) {
                sink.error(new TitanDiagnostic(TitanErrorCode.E001, message, location, null, null));
            }

            @Override
            public void sqlSafetyError(String message) {
                // The constant-SQL safety gate is TITAN-E004 (the recognizer's gate code), distinct
                // from the E001 shape rejects.
                sink.error(new TitanDiagnostic(TitanErrorCode.E004, message, location, null, null));
            }
        };
        return JdbcStatementLowerer.lowerMethodBody(
                bodyPath, parsedSources, storedProcedure, entryKind, reporter, sqlSafetyMode, mysqlTargeted,
                schemaModel, defaultSchema);
    }

    /**
     * TIR types of the entry point's parameters by Java parameter name, feeding
     * {@link EmulationInsertionPass}'s conservative operand typing. Parameters whose Java type
     * has no TIR mapping are simply absent (treated as unknown — never rewritten); they would
     * already have been rejected by signature mapping if actually emitted.
     */
    private static Map<String, TirType> parameterTirTypes(ExecutableElement method, String key) {
        Map<String, TirType> types = new LinkedHashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            try {
                types.put(
                        parameter.getSimpleName().toString(),
                        JavaTypeToTirMapper.map(parameter.asType(), "parameter '" + parameter.getSimpleName() + "' of " + key));
            } catch (IllegalArgumentException ignored) {
                // Unknown-typed parameter: conservatively untyped for emulation purposes.
            }
        }
        return types;
    }

    private static TitanDiagnostic withEntryPointContext(TitanDiagnostic diagnostic, DiscoveredEntryPoint entryPoint, String key) {
        String message = entryPoint.internalHelper()
                ? "Unsupported internal helper method body: " + key + ". " + diagnostic.message()
                : diagnostic.message();
        String location = diagnostic.location() == null || diagnostic.location().isBlank()
                ? entryPointLocation(entryPoint)
                : diagnostic.location();
        return new TitanDiagnostic(diagnostic.code(), message, location, diagnostic.suggestion(), diagnostic.docReference());
    }

    private static String entryPointLocation(DiscoveredEntryPoint entryPoint) {
        if (entryPoint == null || entryPoint.sourceFile() == null || entryPoint.sourceFile().isBlank()) {
            return null;
        }
        return entryPoint.sourceFile() + ":" + entryPoint.line();
    }
}
