package io.titan.transpiler.tir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Neutral home for SQL-name shaping shared by the lowerer, the pipeline and the emitters,
 * consolidating the previously triplicated (and drifted) snake-case implementations.
 *
 * <h2>Record/enum SQL object naming (B-2 / TG-BLK-005)</h2>
 *
 * <p>Generated record and enum object names are built from the declaration's <em>source-local
 * qualified name</em> — the dotted enclosing-type chain without the package, e.g.
 * {@code ProjectionRetrieval.SortPath} for a record nested in {@code ProjectionRetrieval}, or
 * plain {@code SortPath} for a top-level record (so top-level declarations keep their historic
 * names). The qualified name is snake-cased as one string, which turns the dot qualifier join
 * into a single {@code _}:</p>
 *
 * <pre>
 *   type/table name   = "__record_"|"__enum_" + snake(qualifiedTypeName)
 *   member function   = type/table name + "__" + memberSqlName
 * </pre>
 *
 * <p>The member join is the double underscore {@code "__"} — a separator
 * {@link #toSnakeCase(String, String)} can <em>never</em> produce: snake-casing collapses every
 * underscore run to a single {@code _} and strips leading/trailing underscores, so no
 * snake-cased type or member fragment contains {@code __}. Consequently a type-level name
 * contains no {@code __} after its kind prefix while a member-level name (constructor
 * {@code new}, record component accessor, enum field/method accessor) contains exactly one —
 * the historic ambiguity where {@code record SortPath}'s type name and {@code record Sort}'s
 * {@code path} accessor both rendered as {@code __record_sort_path} is structurally impossible.
 * Residual same-kind collisions (two declarations whose qualified names snake-case identically)
 * are rejected at transpile time by the pipeline's collision validation with a positioned
 * TITAN-E007 naming both declarations.</p>
 *
 * <h2>Identifier length limits (TG-BLK-011)</h2>
 *
 * <p>Qualified naming composes user-controlled fragments, so generated names can exceed the
 * dialects' identifier ceilings ({@code DialectCapabilities.NamingRules.identifierMaxLength}:
 * PostgreSQL 63 = NAMEDATALEN-1, MySQL 64). PostgreSQL <em>silently</em> truncates to 63 bytes
 * — member accessors of a long record type all collapse to the same function name (last body
 * wins, or "cannot change return type" on CREATE OR REPLACE) — and MySQL rejects the CREATE
 * outright with {@code ER_TOO_LONG_IDENT}. Every name produced here is therefore passed through
 * {@link #fitWithinDialectLimits(String)}: names within the limit are returned byte-identical
 * (zero churn for existing consumers); longer names keep a prefix of the full composed name and
 * gain a deterministic 8-hex-char SHA-256 suffix <em>of the full untruncated name</em>, so two
 * long names that differ anywhere — including past the truncation point — truncate to distinct
 * results by construction. The limit is the minimum {@code identifierMaxLength} across all
 * registered dialects, which keeps generated names dialect-independent (the lowerer bakes enum
 * accessor names into dialect-neutral TIR before per-target emission) while satisfying every
 * dialect's ceiling.</p>
 *
 * <p>This is the <em>single authority</em> for record/enum SQL object names: emission
 * (both emitters), describe/inventory, call-site rendering (lowerer + emitters), the pipeline's
 * E007 collision gate, and — through the inventory — install plans, rollback scripts and the
 * install verifier all obtain names from the four {@code *SqlBaseName}/{@code *MemberName}
 * methods below. Member-level names are composed from the <em>raw</em> (untruncated) base name
 * and then truncated as a whole, so a member name never embeds an already-truncated base.
 * {@code __titan_internal_*} helper routine names get the same treatment in
 * {@code TranspilationPipeline#toSqlRoutineName} (plan 3.4), where the per-target dialect is in
 * scope.</p>
 */
final class SqlNames {

    /** Kind prefix for generated record composite types / JSON constructors. */
    static final String RECORD_PREFIX = "__record_";

    /** Kind prefix for generated enum lookup tables. */
    static final String ENUM_PREFIX = "__enum_";

    /**
     * Join between a record/enum type-level name and a member-level suffix. Snake-cased
     * fragments can never contain a double underscore (see class javadoc), which is what makes
     * type-vs-member names provably collision-free.
     */
    static final String MEMBER_JOIN = "__";

    private SqlNames() {
    }

    /**
     * Base SQL name of a record model: {@code __record_} + snake of the source-local qualified
     * record name ({@code Outer.SortPath} → {@code __record_outer_sort_path}). On PostgreSQL
     * this is the composite type name; on MySQL the shared prefix of the JSON helper functions.
     */
    static String recordSqlBaseName(String qualifiedRecordName) {
        return fitWithinDialectLimits(rawRecordSqlBaseName(qualifiedRecordName));
    }

    /**
     * Member-level record function name: constructor ({@code memberSqlName = "new"}) or
     * component accessor. {@code memberSqlName} must already be sanitized/snake-cased.
     * Composed from the raw base name and length-limited as a whole (see class javadoc).
     */
    static String recordMemberName(String qualifiedRecordName, String memberSqlName) {
        return fitWithinDialectLimits(rawRecordSqlBaseName(qualifiedRecordName) + MEMBER_JOIN + memberSqlName);
    }

    /**
     * Base SQL name of an enum lookup table: {@code __enum_} + snake of the source-local
     * qualified enum name.
     */
    static String enumSqlBaseName(String qualifiedEnumName) {
        return fitWithinDialectLimits(rawEnumSqlBaseName(qualifiedEnumName));
    }

    /**
     * Enum field/method accessor function name. {@code memberSqlName} must already be
     * sanitized/snake-cased. Composed from the raw base name and length-limited as a whole
     * (see class javadoc).
     */
    static String enumMemberName(String qualifiedEnumName, String memberSqlName) {
        return fitWithinDialectLimits(rawEnumSqlBaseName(qualifiedEnumName) + MEMBER_JOIN + memberSqlName);
    }

    private static String rawRecordSqlBaseName(String qualifiedRecordName) {
        return RECORD_PREFIX + toSnakeCase(qualifiedRecordName, "record");
    }

    private static String rawEnumSqlBaseName(String qualifiedEnumName) {
        return ENUM_PREFIX + toSnakeCase(qualifiedEnumName, "enum_lookup");
    }

    /**
     * TG-BLK-011: deterministic hash-suffix-preserving truncation to the strictest registered
     * dialect's {@code NamingRules.identifierMaxLength}. Names within the limit are returned
     * unchanged; longer names become {@code prefix + "_" + sha256hex8(fullName)} where the hash
     * covers the <em>full untruncated</em> name — names differing only past the truncation
     * point therefore stay distinct by construction. Deterministic across JVMs and builds.
     */
    static String fitWithinDialectLimits(String name) {
        int maxLength = Limits.GENERATED_NAME_MAX_LENGTH;
        if (name.length() <= maxLength) {
            return name;
        }
        String suffix = "_" + fullNameHash(name);
        return name.substring(0, maxLength - suffix.length()) + suffix;
    }

    /** Strictest identifier ceiling across all registered dialects (PostgreSQL 63, MySQL 64). */
    static int generatedNameMaxLength() {
        return Limits.GENERATED_NAME_MAX_LENGTH;
    }

    private static String fullNameHash(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable for SQL name truncation", exception);
        }
    }

    /**
     * Lazy holder: the limit is the minimum {@code identifierMaxLength} over every registered
     * dialect's {@code NamingRules}, so generated record/enum names are dialect-independent
     * (TIR carries them before per-target emission) and a future dialect's ceiling participates
     * automatically through its capability descriptor.
     */
    private static final class Limits {
        private static final int GENERATED_NAME_MAX_LENGTH = computeGeneratedNameMaxLength();

        private static int computeGeneratedNameMaxLength() {
            int min = Integer.MAX_VALUE;
            for (DialectId dialect : DialectId.values()) {
                min = Math.min(min,
                        DialectCapabilities.forDialect(dialect).namingRules().identifierMaxLength());
            }
            return min;
        }
    }

    /**
     * The single snake-casing authority for emitted SQL names (audit S2 drift fix).
     * {@link AbstractSqlEmitter#toSnakeCase(String)} delegates here; the lowerer and the
     * pipeline call it directly, so the same Java name maps to the same SQL name on every
     * code path. Callers supply their historical blank-input fallback.
     */
    static String toSnakeCase(String value, String fallbackIfBlank) {
        if (value == null || value.isBlank()) {
            return fallbackIfBlank;
        }
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .toLowerCase()
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * Conservative cleanup for identifiers spliced into synthesized SQL variable names.
     * Deliberately <em>not</em> snake-casing: it preserves case so existing emitted names
     * (for example {@code __titan_cursor_open_myCursor}) stay byte-identical.
     */
    static String sanitizeIdentifier(String identifier, String fallbackIfBlank) {
        if (identifier == null || identifier.isBlank()) {
            return fallbackIfBlank;
        }
        return identifier.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
