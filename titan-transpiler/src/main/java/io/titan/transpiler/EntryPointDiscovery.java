package io.titan.transpiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.diagnostics.TitanDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/**
 * Discovers Titan entry points (@StoredProcedure, @StoredFunction, @Trigger) and validates
 * baseline signature constraints.
 */
public final class EntryPointDiscovery {

    private static final Set<String> MONTH_NAMES = Set.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    );
    private static final Set<String> DAY_NAMES = Set.of(
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
    );

    private static final String STORED_PROCEDURE_ANNOTATION = "titan.dsl.StoredProcedure";
    private static final String STORED_FUNCTION_ANNOTATION = "titan.dsl.StoredFunction";
    private static final String TRIGGER_ANNOTATION = "titan.dsl.Trigger";
    private static final String SCHEDULED_JOB_ANNOTATION = "titan.dsl.ScheduledJob";
    private static final String SECURITY_DEFINER_ANNOTATION = "titan.dsl.SecurityDefiner";

    public List<DiscoveredEntryPoint> discover(ParsedSources parsedSources) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }

        List<DiscoveredEntryPoint> discovered = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TreePath currentPath = getCurrentPath();
                    Element element = parsedSources.trees().getElement(currentPath);
                    if (element == null || element.getKind() != ElementKind.METHOD) {
                        return super.visitMethod(node, unused);
                    }

                    ExecutableElement method = (ExecutableElement) element;
                    boolean isStoredProcedure = hasAnnotation(method, STORED_PROCEDURE_ANNOTATION);
                    boolean isStoredFunction = hasAnnotation(method, STORED_FUNCTION_ANNOTATION);
                    boolean isTrigger = hasAnnotation(method, TRIGGER_ANNOTATION);
                    boolean isScheduledJob = hasAnnotation(method, SCHEDULED_JOB_ANNOTATION);
                    boolean isSecurityDefiner = hasAnnotation(method, SECURITY_DEFINER_ANNOTATION);

                    int annotationCount = (isStoredProcedure ? 1 : 0)
                            + (isStoredFunction ? 1 : 0)
                            + (isTrigger ? 1 : 0)
                            + (isScheduledJob ? 1 : 0);
                    if (annotationCount == 0) {
                        return super.visitMethod(node, unused);
                    }

                    long line = unit.getLineMap().getLineNumber(
                            parsedSources.trees().getSourcePositions().getStartPosition(unit, node));
                    String location = unit.getSourceFile().getName() + ":" + line;
                    String methodQualifiedName = method.getEnclosingElement() + "." + method.getSimpleName();

                    List<String> methodErrors = new ArrayList<>();
                    if (annotationCount > 1) {
                        methodErrors.add(TitanDiagnostics
                                .entryPointMustDeclareExactlyOneAnnotation(methodQualifiedName, location)
                                .render());
                    }

                    if (!method.getModifiers().contains(Modifier.STATIC)) {
                        methodErrors.add(TitanDiagnostics
                                .entryPointMustBeStatic(methodQualifiedName, location)
                                .render());
                    }

                    if (isStoredProcedure && method.getReturnType().getKind() != TypeKind.VOID) {
                        methodErrors.add(TitanDiagnostics
                                .storedProcedureMustReturnVoid(methodQualifiedName, location)
                                .render());
                    }

                    if (isStoredFunction && method.getReturnType().getKind() == TypeKind.VOID) {
                        methodErrors.add(TitanDiagnostics
                                .storedFunctionMustReturnValue(methodQualifiedName, location)
                                .render());
                    }

                    if (isTrigger && method.getReturnType().getKind() != TypeKind.VOID) {
                        methodErrors.add(TitanDiagnostics
                                .triggerMustReturnVoid(methodQualifiedName, location)
                                .render());
                    }

                    if (isScheduledJob && method.getReturnType().getKind() != TypeKind.VOID) {
                        methodErrors.add(TitanDiagnostics
                                .scheduledJobMustReturnVoid(methodQualifiedName, location)
                                .render());
                    }

                    if (methodErrors.isEmpty()) {
                        EntryPointKind kind = isTrigger
                                ? EntryPointKind.TRIGGER
                                : (isScheduledJob
                                ? EntryPointKind.SCHEDULED_JOB
                                : (isStoredProcedure ? EntryPointKind.STORED_PROCEDURE : EntryPointKind.STORED_FUNCTION));
                        discovered.add(new DiscoveredEntryPoint(
                                kind,
                                method.getEnclosingElement().toString(),
                                method.getSimpleName().toString(),
                                method.getParameters().stream().map(p -> p.getSimpleName().toString()).toList(),
                                method.getParameters().stream().map(p -> p.asType().toString()).toList(),
                                method.getReturnType().toString(),
                                unit.getSourceFile().getName(),
                                line,
                                isTrigger ? extractTriggerDefinition(method, methodQualifiedName, location) : null,
                                isScheduledJob ? extractScheduledJobDefinition(method, methodQualifiedName, location) : null,
                                isSecurityDefiner,
                                isSecurityDefiner ? extractSecurityPolicy(method) : SecurityPolicy.NONE
                        ));
                    } else {
                        errors.addAll(methodErrors);
                    }

                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }

        return List.copyOf(discovered);
    }

    private static boolean hasAnnotation(ExecutableElement method, String annotationFqcn) {
        return method.getAnnotationMirrors().stream()
                .anyMatch(annotation -> annotation.getAnnotationType().toString().equals(annotationFqcn));
    }

    /** ATG-017b: reads the {@code @SecurityDefiner} privilege-policy attributes (defaults when unset). */
    private static SecurityPolicy extractSecurityPolicy(ExecutableElement method) {
        AnnotationMirror annotation = method.getAnnotationMirrors().stream()
                .filter(a -> SECURITY_DEFINER_ANNOTATION.equals(a.getAnnotationType().toString()))
                .findFirst()
                .orElse(null);
        if (annotation == null) {
            return SecurityPolicy.NONE;
        }
        String ownerRole = "";
        List<String> executeRoles = new ArrayList<>();
        boolean revokePublic = false;
        for (var entry : annotation.getElementValues().entrySet()) {
            String attribute = entry.getKey().getSimpleName().toString();
            AnnotationValue value = entry.getValue();
            switch (attribute) {
                case "ownerRole" -> ownerRole = String.valueOf(value.getValue());
                case "revokePublic" -> revokePublic = Boolean.parseBoolean(String.valueOf(value.getValue()));
                case "executeRoles" -> {
                    if (value.getValue() instanceof List<?> items) {
                        for (Object item : items) {
                            if (item instanceof AnnotationValue arrayElement) {
                                executeRoles.add(String.valueOf(arrayElement.getValue()));
                            }
                        }
                    }
                }
                default -> { }
            }
        }
        return new SecurityPolicy(ownerRole, executeRoles, revokePublic);
    }

    private static TriggerDefinition extractTriggerDefinition(
            ExecutableElement method,
            String methodQualifiedName,
            String location
    ) {
        AnnotationMirror triggerAnnotation = method.getAnnotationMirrors().stream()
                .filter(annotation -> annotation.getAnnotationType().toString().equals(TRIGGER_ANNOTATION))
                .findFirst()
                .orElseThrow();

        String table = null;
        String timing = null;
        String forEach = "ROW";
        List<String> events = new ArrayList<>();

        for (var entry : triggerAnnotation.getElementValues().entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            AnnotationValue value = entry.getValue();
            switch (key) {
                case "table" -> table = String.valueOf(value.getValue());
                case "timing" -> timing = enumSimpleName(value);
                case "forEach" -> forEach = enumSimpleName(value);
                case "event" -> events = enumArraySimpleNames(value);
                default -> {
                }
            }
        }

        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException(TitanDiagnostics
                    .triggerMissingTableAttribute(methodQualifiedName, location)
                    .render());
        }
        if (timing == null || timing.isBlank()) {
            throw new IllegalArgumentException(TitanDiagnostics
                    .triggerMissingTimingAttribute(methodQualifiedName, location)
                    .render());
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException(TitanDiagnostics
                    .triggerMissingEventAttribute(methodQualifiedName, location)
                    .render());
        }

        return new TriggerDefinition(table, timing, List.copyOf(events), forEach);
    }

    private static ScheduledJobDefinition extractScheduledJobDefinition(
            ExecutableElement method,
            String methodQualifiedName,
            String location
    ) {
        AnnotationMirror scheduledAnnotation = method.getAnnotationMirrors().stream()
                .filter(annotation -> annotation.getAnnotationType().toString().equals(SCHEDULED_JOB_ANNOTATION))
                .findFirst()
                .orElseThrow();

        String cron = null;
        String name = "";
        for (var entry : scheduledAnnotation.getElementValues().entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            AnnotationValue value = entry.getValue();
            switch (key) {
                case "cron" -> cron = String.valueOf(value.getValue());
                case "name" -> name = String.valueOf(value.getValue());
                default -> {
                }
            }
        }

        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException(TitanDiagnostics
                    .scheduledJobMissingCronAttribute(methodQualifiedName, location)
                    .render());
        }
        if (!isValidCronExpression(cron)) {
            throw new IllegalArgumentException(TitanDiagnostics
                    .scheduledJobInvalidCronExpression(methodQualifiedName, location, cron)
                    .render());
        }

        return new ScheduledJobDefinition(cron, name);
    }

    private static boolean isValidCronExpression(String cron) {
        if (cron == null) {
            return false;
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            return false;
        }

        return isValidCronField(parts[0], 0, 59)
                && isValidCronField(parts[1], 0, 23)
                && isValidCronField(parts[2], 1, 31)
                && isValidCronField(parts[3], 1, 12, MONTH_NAMES, false)
                && isValidCronField(parts[4], 0, 7, DAY_NAMES, true);
    }

    private static boolean isValidCronField(String field, int min, int max) {
        return isValidCronField(field, min, max, Set.of(), false);
    }

    private static boolean isValidCronField(String field, int min, int max, Set<String> symbolicNames, boolean allowSevenAsZero) {
        if (field == null || field.isBlank()) {
            return false;
        }

        String[] segments = field.split(",");
        for (String rawSegment : segments) {
            String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                return false;
            }
            if (!isValidCronSegment(segment, min, max, symbolicNames, allowSevenAsZero)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidCronSegment(
            String segment,
            int min,
            int max,
            Set<String> symbolicNames,
            boolean allowSevenAsZero
    ) {
        if ("*".equals(segment)) {
            return true;
        }

        if (segment.startsWith("*/")) {
            Integer step = parseInt(segment.substring(2));
            if (step == null || step <= 0) {
                return false;
            }
            int span = max - min + 1;
            return step <= span;
        }

        String[] stepSplit = segment.split("/");
        if (stepSplit.length == 2) {
            Integer step = parseInt(stepSplit[1]);
            if (step == null || step <= 0) {
                return false;
            }
            Integer span = rangeSpan(stepSplit[0], min, max, symbolicNames, allowSevenAsZero);
            return span != null && step <= span;
        }

        return isValidBaseRange(segment, min, max, symbolicNames, allowSevenAsZero);
    }

    private static Integer rangeSpan(String base, int min, int max, Set<String> symbolicNames, boolean allowSevenAsZero) {
        if ("*".equals(base)) {
            return max - min + 1;
        }

        String[] range = base.split("-");
        if (range.length == 1) {
            Integer start = parseCronValue(range[0], min, max, symbolicNames, allowSevenAsZero);
            if (start == null) {
                return null;
            }
            return max - start + 1;
        }
        if (range.length == 2) {
            Integer start = parseCronValue(range[0], min, max, symbolicNames, allowSevenAsZero);
            Integer end = parseCronValue(range[1], min, max, symbolicNames, allowSevenAsZero);
            if (start == null || end == null || start > end) {
                return null;
            }
            return end - start + 1;
        }
        return null;
    }

    private static boolean isValidBaseRange(String base, int min, int max, Set<String> symbolicNames, boolean allowSevenAsZero) {
        if ("*".equals(base)) {
            return true;
        }

        String[] range = base.split("-");
        if (range.length == 1) {
            Integer value = parseCronValue(range[0], min, max, symbolicNames, allowSevenAsZero);
            return value != null;
        }
        if (range.length == 2) {
            Integer start = parseCronValue(range[0], min, max, symbolicNames, allowSevenAsZero);
            Integer end = parseCronValue(range[1], min, max, symbolicNames, allowSevenAsZero);
            return start != null && end != null && start <= end;
        }
        return false;
    }

    private static Integer parseCronValue(
            String value,
            int min,
            int max,
            Set<String> symbolicNames,
            boolean allowSevenAsZero
    ) {
        if (value == null) {
            return null;
        }
        String token = value.trim();
        if (token.isEmpty()) {
            return null;
        }

        String upper = token.toUpperCase(Locale.ROOT);
        if (!symbolicNames.isEmpty() && symbolicNames.contains(upper)) {
            Integer symbolic = switch (upper) {
                case "JAN" -> 1;
                case "FEB" -> 2;
                case "MAR" -> 3;
                case "APR" -> 4;
                case "MAY" -> 5;
                case "JUN" -> 6;
                case "JUL" -> 7;
                case "AUG" -> 8;
                case "SEP" -> 9;
                case "OCT" -> 10;
                case "NOV" -> 11;
                case "DEC" -> 12;
                case "SUN" -> 0;
                case "MON" -> 1;
                case "TUE" -> 2;
                case "WED" -> 3;
                case "THU" -> 4;
                case "FRI" -> 5;
                case "SAT" -> 6;
                default -> null;
            };
            if (symbolic == null) {
                return null;
            }
            if (symbolic < min || symbolic > max) {
                return null;
            }
            return symbolic;
        }

        Integer numeric = parseInt(token);
        if (numeric == null) {
            return null;
        }
        if (allowSevenAsZero && numeric == 7) {
            numeric = 0;
        }
        if (numeric < min || numeric > max) {
            return null;
        }
        return numeric;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String enumSimpleName(AnnotationValue value) {
        Object raw = value.getValue();
        String text = String.valueOf(raw);
        int idx = text.lastIndexOf('.');
        return idx >= 0 ? text.substring(idx + 1) : text;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumArraySimpleNames(AnnotationValue value) {
        Object raw = value.getValue();
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof AnnotationValue av) {
                values.add(enumSimpleName(av));
            }
        }
        return values;
    }
}
