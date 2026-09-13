package io.titan.transpiler.tir;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Identity of a transpilation target dialect (plan 3.4).
 *
 * <p>Each constant carries the transpile-target spellings it answers to, so registering a new
 * dialect's parse aliases happens on the constant declaration itself — no separate
 * string-switch to extend. The first alias is the canonical spelling surfaced in diagnostics.</p>
 */
public enum DialectId {
    POSTGRESQL("postgresql", "postgres", "pg"),
    MYSQL("mysql");

    private final List<String> aliases;

    DialectId(String... aliases) {
        if (aliases.length == 0) {
            throw new IllegalArgumentException("A dialect needs at least one transpile-target alias");
        }
        this.aliases = List.of(aliases);
    }

    /** Transpile-target spellings accepted for this dialect; the first is canonical. */
    public List<String> aliases() {
        return aliases;
    }

    /** Canonical lowercase transpile-target spelling (the first alias). */
    public String canonicalTarget() {
        return aliases.getFirst();
    }

    /** Resolves a transpile-target string (case-insensitive alias) to its dialect. */
    public static Optional<DialectId> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (DialectId dialect : values()) {
            if (dialect.aliases.contains(normalized)) {
                return Optional.of(dialect);
            }
        }
        return Optional.empty();
    }

    /** Comma-separated canonical target spellings for "use one of: ..." diagnostics. */
    public static String supportedTargets() {
        return java.util.Arrays.stream(values())
                .map(DialectId::canonicalTarget)
                .collect(Collectors.joining(", "));
    }
}
