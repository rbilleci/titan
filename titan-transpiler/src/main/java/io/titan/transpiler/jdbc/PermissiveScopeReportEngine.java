package io.titan.transpiler.jdbc;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.MethodSignatureKeys;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.jdbc.PermissiveScopeReport.PermissiveScopeEntry;
import io.titan.transpiler.jdbc.SqlSafetyResolver.Resolution;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;

/**
 * Engine for the {@code titanPermissiveScopesReport} task: parses the Java sources (reusing
 * {@link JavaSourceParser}/{@link ParsedSources} — never a second parse), discovers entry points
 * with {@link EntryPointDiscovery}, and computes — for <b>every</b> entry-point method — its
 * <i>effective</i> {@code sqlSafety} via {@link SqlSafetyResolver} (passing the build-level setting),
 * listing every method whose effective mode is {@link SqlSafetyMode#PERMISSIVE} together with the
 * {@link SqlSafetyResolver.RelaxationSource} that decided it.
 *
 * <p>This mirrors {@link JdbcCompatLinter}'s shape (parse → discover → walk the shared parse →
 * aggregate). The load-bearing reuse is two-fold: (1) it correlates each {@link DiscoveredEntryPoint}
 * back to its {@link ExecutableElement} by the {@link MethodSignatureKeys} key — the same bridge
 * {@link JdbcUsageRecognizer} uses — so the resolver can be consulted; and (2) it calls
 * {@link SqlSafetyResolver#resolve(ExecutableElement)} for both the effective mode and its source,
 * <b>never re-deriving precedence</b>. Surfacing the {@code CLASS_ANNOTATION}/{@code BUILD_FLAG}
 * sources is the whole point: those methods carry no {@code @SqlSafety} to grep for, so a broad
 * relaxation cannot quietly cover a forgotten method.</p>
 *
 * <p>Report-only: it computes an audit artifact and never throws / never fails a build.</p>
 */
public final class PermissiveScopeReportEngine {

    /**
     * Builds the report over the given sources.
     *
     * @param sourceFiles      Java source files to analyze (the same set the transpiler would parse)
     * @param classpathEntries compile classpath (so {@code titan.dsl}/{@code java.sql} resolve)
     * @param sqlSafety        the build-level mode string ({@code "strict"} default | {@code "permissive"})
     */
    public PermissiveScopeReport report(List<Path> sourceFiles, List<Path> classpathEntries, String sqlSafety) {
        SqlSafetyMode buildLevel = SqlSafetyResolver.parseBuildLevel(sqlSafety);
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new PermissiveScopeReport(buildLevel, 0, List.of());
        }
        ParsedSources parsed = new JavaSourceParser().parse(sourceFiles, classpathEntries);
        return report(parsed, buildLevel);
    }

    /** Builds the report over an already-parsed source set (the path the unit tests use directly). */
    public PermissiveScopeReport report(ParsedSources parsed, SqlSafetyMode buildLevel) {
        if (parsed == null) {
            throw new IllegalArgumentException("parsed must not be null");
        }
        SqlSafetyMode mode = buildLevel == null ? SqlSafetyMode.STRICT : buildLevel;
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        SqlSafetyResolver resolver = new SqlSafetyResolver(mode);

        Map<String, DiscoveredEntryPoint> byKey = new HashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            byKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        // Walk the shared parse, re-resolving each entry point's ExecutableElement (the resolver
        // needs the element, which DiscoveredEntryPoint does not carry) by its signature key.
        int[] methodCount = {0};
        List<PermissiveScopeEntry> permissive = new ArrayList<>();
        for (CompilationUnitTree unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath path = getCurrentPath();
                    Element element = parsed.trees().getElement(path);
                    if (!(element instanceof ExecutableElement method)) {
                        return super.visitMethod(node, unused);
                    }
                    DiscoveredEntryPoint entryPoint = byKey.get(MethodSignatureKeys.of(method));
                    if (entryPoint == null) {
                        return super.visitMethod(node, unused);
                    }
                    methodCount[0]++;
                    Resolution resolution = resolver.resolve(method);
                    if (resolution.mode() == SqlSafetyMode.PERMISSIVE) {
                        permissive.add(new PermissiveScopeEntry(
                                entryPoint.className(),
                                entryPoint.methodName(),
                                entryPoint.sourceFile(),
                                entryPoint.line(),
                                resolution.mode(),
                                resolution.source()));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }

        return new PermissiveScopeReport(mode, methodCount[0], permissive);
    }
}
