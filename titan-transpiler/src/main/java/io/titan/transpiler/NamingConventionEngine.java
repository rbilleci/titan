package io.titan.transpiler;

import java.util.Locale;
import java.util.Objects;

/**
 * Maps Java identifiers to SQL-friendly identifiers using configurable prefixes.
 */
public final class NamingConventionEngine {
    public static final String DEFAULT_PARAMETER_PREFIX = "p_";
    public static final String DEFAULT_LOCAL_PREFIX = "v_";
    public static final String DEFAULT_CONSTANT_PREFIX = "c_";

    private final String parameterPrefix;
    private final String localPrefix;
    private final String constantPrefix;

    public NamingConventionEngine() {
        this(DEFAULT_PARAMETER_PREFIX, DEFAULT_LOCAL_PREFIX, DEFAULT_CONSTANT_PREFIX);
    }

    public NamingConventionEngine(String parameterPrefix, String localPrefix, String constantPrefix) {
        this.parameterPrefix = normalizePrefix(parameterPrefix, DEFAULT_PARAMETER_PREFIX);
        this.localPrefix = normalizePrefix(localPrefix, DEFAULT_LOCAL_PREFIX);
        this.constantPrefix = normalizePrefix(constantPrefix, DEFAULT_CONSTANT_PREFIX);
    }

    public String toSqlIdentifier(String javaIdentifier) {
        Objects.requireNonNull(javaIdentifier, "javaIdentifier");
        if (javaIdentifier.isBlank()) {
            throw new IllegalArgumentException("javaIdentifier cannot be blank");
        }

        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < javaIdentifier.length(); i++) {
            char current = javaIdentifier.charAt(i);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                current = '_';
            }

            boolean boundaryBeforeUpper = Character.isUpperCase(current)
                    && i > 0
                    && previous != '_'
                    && (Character.isLowerCase(previous)
                            || (Character.isUpperCase(previous)
                                    && i + 1 < javaIdentifier.length()
                                    && Character.isLowerCase(javaIdentifier.charAt(i + 1))));

            if (boundaryBeforeUpper) {
                out.append('_');
            }

            out.append(Character.toLowerCase(current));
            previous = current;
        }

        String normalized = out.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "value";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            normalized = "v_" + normalized;
        }
        return normalized;
    }

    public String parameterName(String javaIdentifier) {
        return parameterPrefix + toSqlIdentifier(javaIdentifier);
    }

    public String localVariableName(String javaIdentifier) {
        return localPrefix + toSqlIdentifier(javaIdentifier);
    }

    public String constantName(String javaIdentifier) {
        return constantPrefix + toSqlIdentifier(javaIdentifier);
    }

    private String normalizePrefix(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String prefix = value.toLowerCase(Locale.ROOT);
        return prefix.endsWith("_") ? prefix : prefix + "_";
    }
}
