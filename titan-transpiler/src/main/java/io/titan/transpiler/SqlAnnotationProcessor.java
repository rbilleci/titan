package io.titan.transpiler;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

/**
 * Processes {@code @SQL} annotations and extracts named SQL parameters.
 */
public final class SqlAnnotationProcessor {

    private static final Pattern PARAM_PATTERN = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    // Plan 3.4: derived from the DialectId registry so a new dialect is accepted in @SQL
    // annotations without touching this class.
    private static final Set<String> SUPPORTED_DIALECTS = java.util.Arrays.stream(io.titan.transpiler.tir.DialectId.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    private static final List<String> VENDOR_SPECIFIC_TOKENS = List.of(
            "@>", "@@", "#>", "#>>", "~", "\\bts_rank\\b", "\\bplainto_tsquery\\b",
            "\\bto_tsvector\\b", "\\bto_tsquery\\b", "\\bwebsearch_to_tsquery\\b", "\\bphraseto_tsquery\\b",
            "\\btsvector\\b", "\\btsquery\\b", "\\bgen_random_uuid\\b", "\\bpg_advisory_lock\\b",
            "\\bjsonpath\\b", "\\bltree\\b", "\\bgenerate_series\\s*\\(",
            "\\barray_agg\\s*\\([^)]*\\border\\s+by\\b", "\\bmatch\\s*\\(", "\\bagainst\\s*\\(",
            "\\bget_lock\\s*\\(", "\\buse\\s+index\\b", "\\bforce\\s+index\\b", "\\bpg_cron\\b",
            "\\btablesample\\b", "\\blisten\\b", "\\bnotify\\b");

    public record ProcessedSqlAnnotation(
            String dialect,
            String sql,
            List<String> parameterNames,
            boolean dslPromotionWarning,
            String warningMessage,
            String sourceFile,
            long sourceLine
    ) {}

    public Map<String, List<ProcessedSqlAnnotation>> process(ParsedSources parsedSources, List<DiscoveredEntryPoint> entryPoints) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        if (entryPoints == null) {
            throw new IllegalArgumentException("entryPoints must not be null");
        }

        Map<String, DiscoveredEntryPoint> byKey = new HashMap<>();
        for (DiscoveredEntryPoint entryPoint : entryPoints) {
            byKey.put(entryPoint.methodSignatureKey(), entryPoint);
        }

        Map<String, List<ProcessedSqlAnnotation>> out = new HashMap<>();

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree methodTree, Void unused) {
                    TreePath methodPath = getCurrentPath();
                    Element element = parsedSources.trees().getElement(methodPath);
                    if (element == null || element.getKind() != ElementKind.METHOD) {
                        return super.visitMethod(methodTree, unused);
                    }

                    ExecutableElement method = (ExecutableElement) element;
                    String key = method.getEnclosingElement() + "#" + method.getSimpleName()
                            + "(" + method.getParameters().stream().map(p -> p.asType().toString()).reduce((a, b) -> a + "," + b).orElse("") + ")";
                    if (!byKey.containsKey(key)) {
                        return super.visitMethod(methodTree, unused);
                    }

                    Set<String> methodParameterNames = collectMethodParameterNames(methodTree);
                    List<ProcessedSqlAnnotation> found = new ArrayList<>();

                    for (AnnotationTree annotationTree : methodTree.getModifiers().getAnnotations()) {
                        TreePath annotationPath = new TreePath(methodPath, annotationTree);
                        ProcessedSqlAnnotation parsed = parseSqlAnnotation(
                                annotationTree,
                                collectInScopeVariablesAtAnnotation(annotationPath, parsedSources, methodParameterNames),
                                parsedSources,
                                unit
                        );
                        if (parsed != null) {
                            found.add(parsed);
                        }
                    }

                    if (methodTree.getBody() != null) {
                        new com.sun.source.util.TreeScanner<Void, Void>() {
                            @Override
                            public Void visitVariable(VariableTree variableTree, Void innerUnused) {
                                for (AnnotationTree annotationTree : variableTree.getModifiers().getAnnotations()) {
                                    TreePath annotationPath = new TreePath(getCurrentPath(), annotationTree);
                                    ProcessedSqlAnnotation parsed = parseSqlAnnotation(
                                            annotationTree,
                                            collectInScopeVariablesAtAnnotation(annotationPath, parsedSources, methodParameterNames),
                                            parsedSources,
                                            unit
                                    );
                                    if (parsed != null) {
                                        found.add(parsed);
                                    }
                                }
                                return super.visitVariable(variableTree, innerUnused);
                            }
                        }.scan(methodTree.getBody(), null);
                    }

                    if (!found.isEmpty()) {
                        out.put(key, List.copyOf(found));
                    }

                    return super.visitMethod(methodTree, unused);
                }
            }.scan(unit, null);
        }

        return Map.copyOf(out);
    }

    private static Set<String> collectMethodParameterNames(MethodTree methodTree) {
        Set<String> names = new HashSet<>();
        for (VariableTree parameter : methodTree.getParameters()) {
            names.add(parameter.getName().toString());
        }
        return names;
    }

    private static Set<String> collectInScopeVariablesAtAnnotation(
            TreePath annotationPath,
            ParsedSources parsedSources,
            Set<String> fallbackNames
    ) {
        Set<String> names = new HashSet<>(fallbackNames);
        if (annotationPath == null) {
            return names;
        }

        Scope scope = parsedSources.trees().getScope(annotationPath);
        while (scope != null) {
            for (Element local : scope.getLocalElements()) {
                if (local.getKind() == ElementKind.PARAMETER || local.getKind() == ElementKind.LOCAL_VARIABLE) {
                    names.add(local.getSimpleName().toString());
                }
            }
            scope = scope.getEnclosingScope();
        }
        return names;
    }

    private static ProcessedSqlAnnotation parseSqlAnnotation(
            AnnotationTree annotationTree,
            Set<String> inScope,
            ParsedSources parsedSources,
            CompilationUnitTree unit
    ) {
        if (!isSqlAnnotation(annotationTree)) {
            return null;
        }

        String dialect = null;
        String sql = null;
        SourceRef sourceRef = resolveSourceRef(annotationTree, parsedSources, unit);

        for (ExpressionTree argument : annotationTree.getArguments()) {
            if (argument instanceof AssignmentTree assignment) {
                String name = assignment.getVariable().toString();
                if ("dialect".equals(name)) {
                    dialect = enumConstantName(assignment.getExpression());
                } else if ("value".equals(name)) {
                    sql = resolveSqlValue(assignment.getExpression(), parsedSources, unit, sourceRef);
                }
            } else if (sql == null) {
                // @SQL supports single-element shorthand where value is provided positionally.
                // Example: @SQL(dialect = POSTGRESQL, "SELECT ...")
                sql = resolveSqlValue(argument, parsedSources, unit, sourceRef);
            }
        }

        if (dialect == null || dialect.isBlank()) {
            throw new IllegalArgumentException("@SQL annotation requires non-empty dialect attribute");
        }
        String normalizedDialect = dialect.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_DIALECTS.contains(normalizedDialect)) {
            throw new IllegalArgumentException("@SQL annotation dialect must be one of " + SUPPORTED_DIALECTS + ": " + dialect);
        }
        if (sql == null) {
            throw new IllegalArgumentException("@SQL annotation requires SQL string value");
        }

        List<String> parameters = extractParameters(sql);
        for (String parameter : parameters) {
            if (!inScope.contains(parameter)) {
                throw new IllegalArgumentException("@SQL parameter :" + parameter + " is not bound to a variable in scope");
            }
        }

        boolean promotable = isBestEffortParseable(sql) && !containsVendorSpecificSyntax(sql);
        String warningLocation = sourceRef.fileName() + (sourceRef.lineNumber() > 0 ? ":" + sourceRef.lineNumber() : "");
        String warningMessage = promotable
                ? "TITAN-W001: @SQL at " + warningLocation + " contains no vendor-specific syntax and could be expressed through the Titan DSL. "
                + "Using the DSL provides type safety, schema validation, and dual-dialect support. "
                + "Consider rewriting with the DSL."
                : null;

        return new ProcessedSqlAnnotation(
                normalizedDialect,
                sql,
                parameters,
                promotable,
                warningMessage,
                sourceRef.fileName(),
                sourceRef.lineNumber());
    }

    private record SourceRef(String fileName, long lineNumber) {
    }

    private static SourceRef resolveSourceRef(
            AnnotationTree annotationTree,
            ParsedSources parsedSources,
            CompilationUnitTree unit
    ) {
        if (annotationTree == null || parsedSources == null || unit == null) {
            return new SourceRef("unknown", -1);
        }

        SourcePositions sourcePositions = parsedSources.trees().getSourcePositions();
        long start = sourcePositions.getStartPosition(unit, annotationTree);
        String fileName = unit.getSourceFile() == null ? "unknown" : unit.getSourceFile().getName();
        if (start == Diagnostic.NOPOS) {
            return new SourceRef(fileName, -1);
        }
        long line = unit.getLineMap() == null ? -1 : unit.getLineMap().getLineNumber(start);
        return new SourceRef(fileName, line);
    }

    private static boolean isSqlAnnotation(AnnotationTree annotationTree) {
        String annotationType = annotationTree.getAnnotationType().toString();
        return "SQL".equals(annotationType) || annotationType.endsWith(".SQL");
    }

    private static String enumConstantName(ExpressionTree expressionTree) {
        String rendered = expressionTree.toString();
        int idx = rendered.lastIndexOf('.');
        return idx >= 0 ? rendered.substring(idx + 1) : rendered;
    }

    private static String resolveSqlValue(
            ExpressionTree expressionTree,
            ParsedSources parsedSources,
            CompilationUnitTree unit,
            SourceRef sourceRef
    ) {
        if (expressionTree instanceof LiteralTree literalTree && literalTree.getValue() instanceof String text) {
            return text;
        }
        TreePath expressionPath = parsedSources.trees().getPath(unit, expressionTree);
        if (containsStringConcatenation(expressionTree)
                || referencesConcatenatedStringConstant(expressionTree, parsedSources, expressionPath, new HashSet<>())) {
            String location = sourceRef == null
                    ? "unknown"
                    : sourceRef.fileName() + (sourceRef.lineNumber() > 0 ? ":" + sourceRef.lineNumber() : "");
            throw new IllegalArgumentException(
                    "TITAN-E004: SQL injection risk at " + location
                            + ". @SQL value must be a compile-time constant and cannot be string concatenation");
        }

        if (expressionPath != null) {
            Element element = parsedSources.trees().getElement(expressionPath);
            if (element instanceof VariableElement variableElement) {
                Object constantValue = variableElement.getConstantValue();
                if (constantValue instanceof String constantText) {
                    return constantText;
                }
            }
        }

        throw new IllegalArgumentException("@SQL value must be a compile-time string constant");
    }

    private static boolean referencesConcatenatedStringConstant(
            ExpressionTree expressionTree,
            ParsedSources parsedSources,
            TreePath expressionPath,
            Set<String> visiting
    ) {
        if (expressionTree == null || parsedSources == null || expressionPath == null) {
            return false;
        }

        Element element = parsedSources.trees().getElement(expressionPath);
        if (!(element instanceof VariableElement variableElement)) {
            return false;
        }

        Object constantValue = variableElement.getConstantValue();
        if (!(constantValue instanceof String)) {
            return false;
        }

        String variableKey = variableElement.toString() + "@" + variableElement.getEnclosingElement();
        if (!visiting.add(variableKey)) {
            return false;
        }

        try {
            TreePath declarationPath = parsedSources.trees().getPath(variableElement);
            if (declarationPath == null || !(declarationPath.getLeaf() instanceof VariableTree variableTree)) {
                return hasConcatenatedAssignmentInSources(variableElement.getSimpleName().toString(), parsedSources);
            }

            ExpressionTree initializer = variableTree.getInitializer();
            if (initializer == null) {
                return false;
            }
            if (containsStringConcatenation(initializer)) {
                return true;
            }

            TreePath initializerPath = new TreePath(declarationPath, initializer);
            return referencesConcatenatedStringConstant(initializer, parsedSources, initializerPath, visiting)
                    || hasConcatenatedAssignmentInSources(variableElement.getSimpleName().toString(), parsedSources);
        } finally {
            visiting.remove(variableKey);
        }
    }

    private static boolean containsStringConcatenation(ExpressionTree expressionTree) {
        if (expressionTree == null) {
            return false;
        }
        if (expressionTree instanceof BinaryTree) {
            return true;
        }
        if (expressionTree instanceof ParenthesizedTree parenthesizedTree) {
            return containsStringConcatenation(parenthesizedTree.getExpression());
        }
        if (expressionTree instanceof TypeCastTree typeCastTree) {
            return containsStringConcatenation(typeCastTree.getExpression());
        }
        return false;
    }

    private static boolean hasConcatenatedAssignmentInSources(String variableName, ParsedSources parsedSources) {
        if (variableName == null || variableName.isBlank() || parsedSources == null) {
            return false;
        }
        Pattern assignmentWithConcat = Pattern.compile("\\b" + Pattern.quote(variableName)
                + "\\b\\s*=\\s*[^;]*\\+[^;]*;", Pattern.DOTALL);

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            if (unit == null || unit.getSourceFile() == null) {
                continue;
            }
            try {
                CharSequence source = unit.getSourceFile().getCharContent(true);
                String text = source == null ? "" : source.toString();
                if (assignmentWithConcat.matcher(text).find()) {
                    return true;
                }
            } catch (Exception ignored) {
                // best effort: ignore source read failures
            }
        }
        return false;
    }

    private static List<String> extractParameters(String sql) {
        LinkedHashSet<String> parameters = new LinkedHashSet<>();
        StringBuilder visibleSql = new StringBuilder(sql.length());

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktickQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        String inDollarQuoteTag = null;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                visibleSql.append(' ');
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                visibleSql.append(' ');
                if (c == '*' && next == '/') {
                    visibleSql.append(' ');
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (inSingleQuote) {
                visibleSql.append(' ');
                if (c == '\'' && next == '\'') {
                    visibleSql.append(' ');
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDollarQuoteTag != null) {
                visibleSql.append(' ');
                if (c == '$' && sql.startsWith(inDollarQuoteTag, i)) {
                    for (int k = 1; k < inDollarQuoteTag.length(); k++) {
                        visibleSql.append(' ');
                    }
                    i += inDollarQuoteTag.length() - 1;
                    inDollarQuoteTag = null;
                }
                continue;
            }

            if (inDoubleQuote) {
                visibleSql.append(' ');
                if (c == '"' && next == '"') {
                    visibleSql.append(' ');
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (inBacktickQuote) {
                visibleSql.append(' ');
                if (c == '`') {
                    inBacktickQuote = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                visibleSql.append(' ').append(' ');
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                visibleSql.append(' ').append(' ');
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '$') {
                String dollarTag = readDollarQuoteTag(sql, i);
                if (dollarTag != null) {
                    for (int k = 0; k < dollarTag.length(); k++) {
                        visibleSql.append(' ');
                    }
                    i += dollarTag.length() - 1;
                    inDollarQuoteTag = dollarTag;
                    continue;
                }
            }
            if (c == '\'') {
                visibleSql.append(' ');
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                visibleSql.append(' ');
                inDoubleQuote = true;
                continue;
            }
            if (c == '`') {
                visibleSql.append(' ');
                inBacktickQuote = true;
                continue;
            }

            visibleSql.append(c);
        }

        Matcher matcher = PARAM_PATTERN.matcher(visibleSql);
        while (matcher.find()) {
            parameters.add(matcher.group(1));
        }
        return List.copyOf(parameters);
    }

    private static String readDollarQuoteTag(String sql, int startIndex) {
        if (sql == null || startIndex < 0 || startIndex >= sql.length() || sql.charAt(startIndex) != '$') {
            return null;
        }
        int i = startIndex + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '$') {
                return sql.substring(startIndex, i + 1);
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
            i++;
        }
        return null;
    }

    private static boolean isBestEffortParseable(String sql) {
        String normalized = sql == null ? "" : sql.strip();
        if (normalized.isEmpty()) {
            return false;
        }

        if (!hasBalancedSqlDelimiters(normalized)) {
            return false;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("select")) {
            return lower.contains(" from ");
        }
        if (lower.startsWith("insert")) {
            if (!lower.contains(" into ")) {
                return false;
            }
            return lower.contains(" values ")
                    || lower.contains(" default values")
                    || lower.contains(") select ")
                    || lower.contains(" select ");
        }
        if (lower.startsWith("update")) {
            return lower.contains(" set ");
        }
        if (lower.startsWith("delete")) {
            return lower.contains(" from ");
        }
        if (lower.startsWith("with")) {
            return lower.contains(" as (")
                    && (lower.contains(") select ")
                    || lower.contains(") insert ")
                    || lower.contains(") update ")
                    || lower.contains(") delete "));
        }
        return false;
    }

    private static boolean hasBalancedSqlDelimiters(String sql) {
        int parenDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inSingleQuote) {
                if (c == '\'' && next == '\'') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                if (c == '"' && next == '"') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }

            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
                if (parenDepth < 0) {
                    return false;
                }
            }
        }

        return !inSingleQuote && !inDoubleQuote && parenDepth == 0;
    }

    private static boolean containsVendorSpecificSyntax(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        for (String tokenPattern : VENDOR_SPECIFIC_TOKENS) {
            if (Pattern.compile(tokenPattern).matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }
}
