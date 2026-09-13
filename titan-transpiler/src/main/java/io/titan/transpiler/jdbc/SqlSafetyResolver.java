package io.titan.transpiler.jdbc;

import java.util.Locale;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Resolves the effective raw-SQL safety mode for a method scope with
 * <b>narrowest-scope-wins</b> precedence ({@code docs/transpilable-jdbc-subset.md} §4, precedence
 * rule): the method's own {@code @SqlSafety} overrides its nearest-enclosing class's
 * {@code @SqlSafety}, which overrides the build-level default. A TYPE annotation does <b>not</b>
 * transitively cover nested classes unless they (or an enclosing class) carry one — so the walk
 * climbs the enclosing-type chain and stops at the first annotated scope.
 *
 * <p>{@code @SqlSafety} is {@code @Retention(SOURCE)}, so the mode is read off the {@link Element}'s
 * annotation mirrors by FQN ({@code titan.dsl.SqlSafety}) and its {@code value()} enum constant
 * name — the same source-element idiom {@code EntryPointDiscovery} uses for {@code @Trigger}.</p>
 */
public final class SqlSafetyResolver {

    private static final String SQL_SAFETY_ANNOTATION = "titan.dsl.SqlSafety";

    private final SqlSafetyMode buildLevelDefault;

    public SqlSafetyResolver(SqlSafetyMode buildLevelDefault) {
        this.buildLevelDefault = buildLevelDefault == null ? SqlSafetyMode.STRICT : buildLevelDefault;
    }

    /**
     * Parses the build-level {@code sqlSafety} string (e.g. from the {@code titan{}} config).
     * Anything other than a case-insensitive {@code "permissive"} resolves to {@link
     * SqlSafetyMode#STRICT} (secure by default), so a blank/null/typo'd value stays injection-proof.
     */
    public static SqlSafetyMode parseBuildLevel(String raw) {
        if (raw != null && "permissive".equals(raw.trim().toLowerCase(Locale.ROOT))) {
            return SqlSafetyMode.PERMISSIVE;
        }
        return SqlSafetyMode.STRICT;
    }

    public SqlSafetyMode buildLevelDefault() {
        return buildLevelDefault;
    }

    /**
     * Where a resolved {@link SqlSafetyMode} came from in the narrowest-scope-wins walk. This is the
     * <b>audit</b> dimension the "effective permissive scopes" build report (§11.1) needs: a method
     * relaxed by {@link #CLASS_ANNOTATION} or {@link #BUILD_FLAG} carries <i>no</i> per-method
     * {@code @SqlSafety} to grep for, so the report must surface the source to make those
     * non-greppable relaxations discoverable.
     */
    public enum RelaxationSource {
        /** The method's own {@code @SqlSafety} decided the mode. */
        METHOD_ANNOTATION,
        /** A {@code @SqlSafety} on the method's nearest-enclosing (annotated) class decided it. */
        CLASS_ANNOTATION,
        /** No annotation applied; the build-level {@code sqlSafety} setting decided it. */
        BUILD_FLAG
    }

    /**
     * The effective mode for a scope plus the {@link RelaxationSource} that decided it — the single
     * result of one narrowest-scope-wins walk.
     */
    public record Resolution(SqlSafetyMode mode, RelaxationSource source) {}

    /**
     * Resolves the effective mode for {@code method} <b>and</b> the {@link RelaxationSource} that
     * decided it, in a single narrowest-scope-wins walk (method annotation, else nearest-enclosing
     * class annotation walking outward, else the build-level default). This is the single source of
     * truth for both the mode and its origin; {@link #effectiveMode(ExecutableElement)} delegates
     * here so precedence is never re-derived elsewhere.
     */
    public Resolution resolve(ExecutableElement method) {
        if (method != null) {
            SqlSafetyMode methodMode = modeOf(method);
            if (methodMode != null) {
                return new Resolution(methodMode, RelaxationSource.METHOD_ANNOTATION);
            }
            Element enclosing = method.getEnclosingElement();
            while (enclosing instanceof TypeElement type) {
                SqlSafetyMode classMode = modeOf(type);
                if (classMode != null) {
                    return new Resolution(classMode, RelaxationSource.CLASS_ANNOTATION);
                }
                enclosing = type.getEnclosingElement();
            }
        }
        return new Resolution(buildLevelDefault, RelaxationSource.BUILD_FLAG);
    }

    /**
     * Effective mode for {@code method}: method annotation, else nearest-enclosing class
     * annotation (walking outward), else the build-level default. Delegates to {@link
     * #resolve(ExecutableElement)} so the precedence walk lives in exactly one place.
     */
    public SqlSafetyMode effectiveMode(ExecutableElement method) {
        return resolve(method).mode();
    }

    /** The {@code @SqlSafety} mode declared directly on {@code element}, or {@code null} if absent. */
    private static SqlSafetyMode modeOf(Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals(SQL_SAFETY_ANNOTATION)) {
                continue;
            }
            for (var entry : annotation.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    return parseEnumValue(entry.getValue());
                }
            }
            // value() has no default; a malformed annotation without it is treated as unset.
            return null;
        }
        return null;
    }

    private static SqlSafetyMode parseEnumValue(AnnotationValue value) {
        String text = String.valueOf(value.getValue());
        int dot = text.lastIndexOf('.');
        String simple = dot >= 0 ? text.substring(dot + 1) : text;
        try {
            return SqlSafetyMode.valueOf(simple);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
