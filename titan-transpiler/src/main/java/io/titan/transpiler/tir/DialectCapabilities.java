package io.titan.transpiler.tir;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-dialect capability descriptor (plan 1.3 / 3.1 / 3.4, audit E-3, E-6, E-9).
 *
 * <p>Declares, per {@link DialectId}, whether a Titan construct can be emitted at all and — for
 * constructs the dialect only gained in a later server release — the minimum server version that
 * accepts the emitted SQL. {@code FeatureValidator} consumes this model with the transpile
 * targets wired in by {@code TranspilationPipeline}, so an unsupported construct/dialect
 * combination becomes a positioned <em>compile-time</em> {@code TITAN-E001} (with the dialect
 * named and a rewrite suggestion) instead of an emit-time failure; a version-gated combination
 * becomes a {@code TITAN-W005} warning naming the version floor. The Phase-0 emit-time throws in
 * the emitters stay as defense-in-depth for TIR that reaches emission without source positions
 * (directly constructed TIR, future lowering paths).</p>
 *
 * <p>Plan 3.4: the descriptor also carries the dialect's {@link NamingRules} (identifier length
 * ceiling, native routine overloading) and lossy-type caveats, so {@code TranspilationPipeline}
 * has no inline {@code dialect == DialectId.X} branches left. The canonical descriptor for each
 * dialect lives on its {@link DialectProvider#capabilities()} — {@link #forDialect(DialectId)}
 * resolves through the default {@link DialectProviders} registry, so adding a dialect does not
 * extend any switch here.</p>
 */
public record DialectCapabilities(
        DialectId dialect,
        String displayName,
        Capability updateDeleteReturning,
        Capability fullOuterJoin,
        Capability stringFormat,
        Capability intersectExcept,
        Capability securityInvokerViews,
        NamingRules namingRules,
        TimestampTzRangeCaveat timestampTzRangeCaveat
) {

    public DialectCapabilities {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(updateDeleteReturning, "updateDeleteReturning");
        Objects.requireNonNull(fullOuterJoin, "fullOuterJoin");
        Objects.requireNonNull(stringFormat, "stringFormat");
        Objects.requireNonNull(intersectExcept, "intersectExcept");
        Objects.requireNonNull(securityInvokerViews, "securityInvokerViews");
        Objects.requireNonNull(namingRules, "namingRules");
        // timestampTzRangeCaveat is nullable: null means the dialect maps TIMESTAMPTZ losslessly.
    }

    /** Lossy-TIMESTAMPTZ caveat if the dialect narrows the representable instant range. */
    public Optional<TimestampTzRangeCaveat> timestampTzCaveat() {
        return Optional.ofNullable(timestampTzRangeCaveat);
    }

    /**
     * Dialect naming rules consumed by routine-name allocation in {@code TranspilationPipeline}
     * (plan 3.4 — formerly inline {@code dialect ==} branches there).
     *
     * @param identifierMaxLength hard identifier-length ceiling (PostgreSQL 63 = NAMEDATALEN-1,
     *        MySQL 64); generated internal-helper names are truncated suffix-preservingly to fit
     * @param supportsRoutineOverloading whether the dialect natively overloads routines by
     *        signature; dialects without overloading get parameter-type-mangled routine names
     *        for overloaded Java methods
     */
    public record NamingRules(int identifierMaxLength, boolean supportsRoutineOverloading) {
        public NamingRules {
            if (identifierMaxLength <= 0) {
                throw new IllegalArgumentException("identifierMaxLength must be positive");
            }
        }
    }

    /**
     * E-13 caveat: the dialect's TIMESTAMPTZ mapping cannot represent the full Java instant
     * range, so signatures/records using Instant-like types draw a compile-time W005 warning.
     *
     * @param message warning prefix (the pipeline appends the affected subject in parentheses)
     * @param suggestion rewrite suggestion naming the dialect's lossless alternative
     */
    public record TimestampTzRangeCaveat(String message, String suggestion) {
        public TimestampTzRangeCaveat {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(suggestion, "suggestion");
        }
    }

    /**
     * One capability cell: fully supported, supported only from a minimum server version
     * onward (the {@code minServerVersion} concept — deploys to older servers fail at CREATE
     * time, so targeting the dialect emits a warning naming the floor), or unsupported on any
     * version (carrying the rewrite suggestion surfaced in the rejection diagnostic).
     */
    public record Capability(Support support, String minServerVersion, String suggestion) {

        public enum Support {
            SUPPORTED,
            SUPPORTED_SINCE,
            UNSUPPORTED
        }

        public Capability {
            Objects.requireNonNull(support, "support");
            if (support == Support.SUPPORTED_SINCE && (minServerVersion == null || minServerVersion.isBlank())) {
                throw new IllegalArgumentException("SUPPORTED_SINCE requires a minServerVersion");
            }
            if (support == Support.UNSUPPORTED && (suggestion == null || suggestion.isBlank())) {
                throw new IllegalArgumentException("UNSUPPORTED requires a rewrite suggestion");
            }
        }

        public static Capability supported() {
            return new Capability(Support.SUPPORTED, null, null);
        }

        public static Capability supportedSince(String minServerVersion) {
            return new Capability(Support.SUPPORTED_SINCE, minServerVersion, null);
        }

        public static Capability unsupported(String suggestion) {
            return new Capability(Support.UNSUPPORTED, null, suggestion);
        }

        public boolean isUnsupported() {
            return support == Support.UNSUPPORTED;
        }

        public boolean isVersionGated() {
            return support == Support.SUPPORTED_SINCE;
        }
    }

    /**
     * Canonical capability descriptor for a dialect, resolved through the default
     * {@link DialectProviders} registry (each provider owns its descriptor).
     */
    public static DialectCapabilities forDialect(DialectId dialect) {
        return Registry.PROVIDERS.require(dialect).capabilities();
    }

    /** Lazy holder so {@code forDialect} resolves the registry once, on first use. */
    private static final class Registry {
        private static final DialectProviders PROVIDERS = new DialectProviders();
    }
}
