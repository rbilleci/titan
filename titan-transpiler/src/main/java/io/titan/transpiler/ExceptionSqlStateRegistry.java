package io.titan.transpiler;

import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Maps Java exception types to SQLSTATE codes for dialect-independent lowering.
 *
 * <p><b>Exception identity model (plan 2.2, fixes F-11).</b> Identity is keyed on the fully
 * qualified name obtained from the resolved {@link TypeMirror} at the throw/catch site — never
 * on source-text name matching (which let {@code com.acme.IllegalStateException} masquerade as
 * {@code java.lang.IllegalStateException}, and collapsed every custom exception to 45000 so the
 * wrong catch block could intercept it).</p>
 *
 * <ul>
 *   <li><b>JDK exceptions</b> keep the fixed mappings below ({@code IllegalArgumentException}
 *       → 22023, ...). JDK types without an explicit mapping stay at the default 45000.</li>
 *   <li><b>User exceptions</b> (source-local classes not in the JDK table) each get a distinct
 *       SQLSTATE allocated per transpilation run: {@code 45002, 45003, ...} in first-seen
 *       source order (a deterministic pre-scan of every {@code throw} site; 45000 is the
 *       generic default and 45001 is owned by {@code NullPointerException}, so allocation skips
 *       any state already used by the JDK table). Allocation is per <em>run</em> rather than
 *       per entry point so that an exception thrown in an internal helper routine carries the
 *       same SQLSTATE the caller's catch dispatch expects, and so a catch of a supertype can
 *       enumerate every user exception type that may be in flight.</li>
 *   <li><b>Catch dispatch</b> derives its SQLSTATE set from the resolved type hierarchy: a
 *       catch of {@code T} matches the JDK states whose types are subtypes of {@code T} plus
 *       the allocated state of every thrown user exception assignable to {@code T} (via
 *       {@link javax.lang.model.util.Types#isAssignable}). A catch of a user exception type
 *       therefore matches only that exception (and its thrown subtypes) — never a plain
 *       {@code RuntimeException} raise, and vice versa.</li>
 *   <li><b>External non-JDK exceptions</b> (from dependencies, not source-local) fall back to
 *       the default 45000, as before.</li>
 * </ul>
 */
public final class ExceptionSqlStateRegistry {

    private static final String DEFAULT_SQLSTATE = "45000";

    /** First user-exception allocation candidate; {@code 45000+n}, skipping JDK-owned states. */
    private static final int USER_STATE_BASE = 45000;
    private static final int USER_STATE_LIMIT = 45999;

    private static final Map<String, String> DIRECT = Map.ofEntries(
            Map.entry("java.lang.RuntimeException", "45000"),
            Map.entry("java.lang.IllegalArgumentException", "22023"),
            Map.entry("java.lang.IllegalStateException", "55000"),
            Map.entry("java.lang.ArithmeticException", "22012"),
            Map.entry("java.lang.NullPointerException", "45001"),
            Map.entry("java.lang.IndexOutOfBoundsException", "2202E"),
            Map.entry("java.lang.UnsupportedOperationException", "0A000"),
            Map.entry("java.sql.SQLException", "HY000"),
            Map.entry("java.lang.Exception", "45000"),
            Map.entry("java.lang.Throwable", "45000")
    );

    private static final Map<String, String> PARENTS = Map.ofEntries(
            Map.entry("java.lang.IllegalArgumentException", "java.lang.RuntimeException"),
            Map.entry("java.lang.IllegalStateException", "java.lang.RuntimeException"),
            Map.entry("java.lang.ArithmeticException", "java.lang.RuntimeException"),
            Map.entry("java.lang.NullPointerException", "java.lang.RuntimeException"),
            Map.entry("java.lang.IndexOutOfBoundsException", "java.lang.RuntimeException"),
            Map.entry("java.lang.UnsupportedOperationException", "java.lang.RuntimeException"),
            Map.entry("java.lang.RuntimeException", "java.lang.Exception"),
            Map.entry("java.sql.SQLException", "java.lang.Exception"),
            Map.entry("java.lang.Exception", "java.lang.Throwable")
    );

    private static final List<String> HIERARCHY_ORDER = List.of(
            "java.lang.IllegalArgumentException",
            "java.lang.IllegalStateException",
            "java.lang.ArithmeticException",
            "java.lang.NullPointerException",
            "java.lang.IndexOutOfBoundsException",
            "java.lang.UnsupportedOperationException",
            "java.lang.RuntimeException",
            "java.sql.SQLException",
            "java.lang.Exception",
            "java.lang.Throwable"
    );

    /**
     * Per-run registries memoized weakly on {@link ParsedSources}, mirroring
     * {@link LoweringContext#forSources}: every lowering call site of one run shares the same
     * user-exception allocations without threading a new parameter through the call graph.
     */
    private static final Map<ParsedSources, java.lang.ref.WeakReference<ExceptionSqlStateRegistry>> SHARED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static ExceptionSqlStateRegistry forSources(ParsedSources parsedSources) {
        synchronized (SHARED) {
            ExceptionSqlStateRegistry registry =
                    SHARED.get(parsedSources) == null ? null : SHARED.get(parsedSources).get();
            if (registry == null) {
                registry = new ExceptionSqlStateRegistry(parsedSources);
                SHARED.put(parsedSources, new java.lang.ref.WeakReference<>(registry));
            }
            return registry;
        }
    }

    private record UserException(String fqn, TypeMirror type, String sqlState) {
    }

    private final ParsedSources parsedSources;
    /** FQN → allocation, in deterministic first-seen order (pre-scanned throw sites first). */
    private final Map<String, UserException> userExceptions = new LinkedHashMap<>();
    private final Set<String> usedStates = new LinkedHashSet<>(DIRECT.values());

    /** JDK-only registry (no user-exception allocation); kept for string-keyed fallback use. */
    public ExceptionSqlStateRegistry() {
        this.parsedSources = null;
    }

    private ExceptionSqlStateRegistry(ParsedSources parsedSources) {
        if (parsedSources == null) {
            throw new IllegalStateException("internal: parsedSources must not be null");
        }
        this.parsedSources = parsedSources;
        prescanThrowSites();
    }

    /**
     * Deterministic allocation seed: every {@code throw} site in the parsed sources, in
     * compilation-unit and source order. Enumerating the full universe up front is what lets a
     * catch clause lowered <em>before</em> a throw site (e.g. a caller lowered before its
     * helper) still include the helper's exception state in its dispatch set.
     */
    private void prescanThrowSites() {
        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitThrow(ThrowTree node, Void unused) {
                    if (node.getExpression() != null) {
                        TypeMirror thrownType = parsedSources.trees().getTypeMirror(
                                new TreePath(getCurrentPath(), node.getExpression()));
                        TypeElement element = asSourceLocalUserException(thrownType);
                        if (element != null) {
                            allocate(element.getQualifiedName().toString(), thrownType);
                        }
                    }
                    return super.visitThrow(node, unused);
                }
            }.scan(unit, null);
        }
    }

    /**
     * The element of a source-local user exception type, or {@code null} for JDK-mapped types,
     * unresolved types, and external library types.
     */
    private TypeElement asSourceLocalUserException(TypeMirror type) {
        if (parsedSources == null || type == null || type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        if (!(parsedSources.types().asElement(type) instanceof TypeElement element)) {
            return null;
        }
        String fqn = element.getQualifiedName().toString();
        if (DIRECT.containsKey(fqn)) {
            return null;
        }
        return LoweringContext.forSources(parsedSources).isSourceLocal(element) ? element : null;
    }

    private String allocate(String fqn, TypeMirror type) {
        UserException existing = userExceptions.get(fqn);
        if (existing != null) {
            return existing.sqlState();
        }
        for (int candidate = USER_STATE_BASE + 1; candidate <= USER_STATE_LIMIT; candidate++) {
            String state = String.valueOf(candidate);
            if (usedStates.add(state)) {
                userExceptions.put(fqn, new UserException(fqn, type, state));
                return state;
            }
        }
        throw new TitanDiagnosticException(new TitanDiagnostic(
                TitanErrorCode.E001,
                "Too many distinct user exception types: no SQLSTATE left in the 45xxx class for '"
                        + fqn + "'",
                null,
                "Reduce the number of distinct exception classes thrown from transpiled code",
                null));
    }

    /**
     * SQLSTATE signalled by a {@code throw} of the resolved type: the fixed JDK mapping, the
     * per-run allocated state for source-local user exceptions, or the 45000 default.
     */
    public String sqlStateForThrow(TypeMirror thrownType) {
        if (thrownType == null || thrownType.getKind() != TypeKind.DECLARED) {
            return DEFAULT_SQLSTATE;
        }
        if (!(parsedSources != null && parsedSources.types().asElement(thrownType) instanceof TypeElement element)) {
            return DEFAULT_SQLSTATE;
        }
        String fqn = element.getQualifiedName().toString();
        String direct = DIRECT.get(fqn);
        if (direct != null) {
            return direct;
        }
        TypeElement userException = asSourceLocalUserException(thrownType);
        if (userException != null) {
            return allocate(fqn, thrownType);
        }
        return DEFAULT_SQLSTATE;
    }

    /**
     * SQLSTATE dispatch set for a catch of the resolved type: JDK states whose types are
     * subtypes of the caught type, plus the allocated state of every thrown user exception
     * assignable to it (catch-of-supertype semantics, e.g. a catch of
     * {@code RuntimeException} also catches user exceptions extending it).
     *
     * @param fallbackTypeText source text of the catch parameter type, used only when the
     *        type did not resolve (string-matching fallback, the pre-F-11 behavior)
     */
    public List<String> sqlStatesForCatch(TypeMirror caughtType, String fallbackTypeText) {
        TypeElement element = caughtType == null || caughtType.getKind() != TypeKind.DECLARED
                || parsedSources == null
                || !(parsedSources.types().asElement(caughtType) instanceof TypeElement typeElement)
                ? null
                : typeElement;
        if (element == null) {
            return sqlStatesFor(fallbackTypeText);
        }

        String fqn = element.getQualifiedName().toString();
        Set<String> states = new LinkedHashSet<>();
        for (String candidate : HIERARCHY_ORDER) {
            if (isSubtypeOf(candidate, fqn)) {
                states.add(DIRECT.get(candidate));
            }
        }

        TypeMirror erasedCaught = parsedSources.types().erasure(caughtType);
        for (UserException userException : userExceptions.values()) {
            if (parsedSources.types().isAssignable(
                    parsedSources.types().erasure(userException.type()), erasedCaught)) {
                states.add(userException.sqlState());
            }
        }

        if (states.isEmpty()) {
            TypeElement userException = asSourceLocalUserException(caughtType);
            if (userException != null) {
                // A user exception that is caught but never thrown anywhere in the sources:
                // allocate its own state so the handler clause stays valid (it is dead code in
                // SQL terms — nothing in this run can signal that state).
                states.add(allocate(fqn, caughtType));
            } else {
                states.add(DEFAULT_SQLSTATE);
            }
        }
        return List.copyOf(states);
    }

    /**
     * String-keyed catch fallback (pre-F-11 behavior): used only when the catch parameter type
     * cannot be resolved to a {@link TypeMirror}.
     */
    public List<String> sqlStatesFor(String exceptionType) {
        String normalized = normalize(exceptionType);
        if (normalized == null) {
            return List.of(DEFAULT_SQLSTATE);
        }

        Set<String> states = new LinkedHashSet<>();
        for (String candidate : HIERARCHY_ORDER) {
            if (isSubtypeOf(candidate, normalized)) {
                states.add(DIRECT.get(candidate));
            }
        }

        if (states.isEmpty()) {
            states.add(DEFAULT_SQLSTATE);
        }
        return List.copyOf(states);
    }

    /**
     * String-keyed throw fallback (pre-F-11 behavior): used only when the thrown expression
     * type cannot be resolved to a {@link TypeMirror}.
     */
    public String sqlStateForThrow(String thrownType) {
        String normalized = normalize(thrownType);
        if (normalized == null) {
            return DEFAULT_SQLSTATE;
        }
        return DIRECT.getOrDefault(normalized, DEFAULT_SQLSTATE);
    }

    private static String normalize(String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return null;
        }

        String normalized = exceptionType.trim();
        if (!normalized.contains(".") && (normalized.endsWith("Exception") || normalized.endsWith("Throwable"))) {
            normalized = "java.lang." + normalized;
        }
        return normalized;
    }

    private static boolean isSubtypeOf(String candidate, String target) {
        String current = candidate;
        while (current != null) {
            if (current.equals(target)) {
                return true;
            }
            current = PARENTS.get(current);
        }
        return false;
    }
}
