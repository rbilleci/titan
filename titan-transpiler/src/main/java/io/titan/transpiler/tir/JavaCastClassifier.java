package io.titan.transpiler.tir;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Single authority for which Java cast expressions are transpilable (plan 2.2, F-9 follow-up).
 * Shared by {@link io.titan.transpiler.FeatureValidator} (pre-screen) and
 * {@link ExpressionLowerer} (lowering) so the two can never drift.
 *
 * <p><b>Per-cast-kind decisions</b> (types per {@link JavaTypeToTirMapper}: byte/short/int →
 * INT, long → BIGINT, float/double/BigDecimal → NUMERIC(38,10), String → TEXT):</p>
 * <ul>
 *   <li><b>Identity / value-preserving</b> — supported as a no-op (no SQL CAST): box/unbox
 *       casts of the same primitive ({@code (Integer) i}, {@code (int) boxed}),
 *       {@code (String)} on a String expression, {@code (boolean)} on a boolean,
 *       byte/short → int (Java widening, exact), and float ↔ double (both map to
 *       NUMERIC(38,10) under the existing type-mapper policy, so the Java rounding of
 *       {@code (float) d} does not occur — the SQL value keeps full NUMERIC precision).</li>
 *   <li><b>Numeric widening</b> — supported as {@code CAST(x AS <type>)}: int → long,
 *       int/long → double/float. Always value-preserving in SQL; where Java would round
 *       (long → float/double beyond 2^53), NUMERIC keeps the exact value — SQL is strictly
 *       more precise, documented divergence.</li>
 *   <li><b>Fractional → integral narrowing</b> ({@code (int)}/{@code (long)} on
 *       double/float) — supported with explicit truncation toward zero, reproducing the Java
 *       semantics SQL CAST would violate by rounding: PostgreSQL
 *       {@code CAST(TRUNC(x) AS INTEGER|BIGINT)}, MySQL
 *       {@code CAST(TRUNCATE(x, 0) AS SIGNED)}. Out-of-range values raise a SQL error where
 *       Java silently clamps to MIN/MAX — fail-loud divergence, documented.</li>
 *   <li><b>long → int narrowing</b> — rejected: Java silently keeps the low 32 bits
 *       (wraparound) while SQL CAST range-errors; there is no clean dialect-portable
 *       reproduction, and code relying on that wrap is almost certainly a bug.</li>
 *   <li><b>byte/short/char targets</b> — rejected: Java truncates to 8/16 bits; TIR maps
 *       byte/short to INT and char to TEXT, so a SQL cast cannot reproduce the truncation.</li>
 *   <li><b>char source</b> — rejected: char is represented as single-character TEXT; Java's
 *       char ↔ int casts are UTF-16 code-unit conversions with no SQL CAST equivalent
 *       (char arithmetic is separately supported through the char-code helper).</li>
 *   <li><b>{@code (Object)} casts and all other reference casts</b> (including
 *       {@code (String) object} and casts to user types) — rejected: a runtime checkcast has
 *       no SQL equivalent. Note that numeric ↔ String conversions are not expressible as
 *       casts in Java at all; {@code String.valueOf(x)} is the supported spelling and already
 *       lowers to {@code CAST(x AS TEXT/CHAR)}.</li>
 * </ul>
 */
public final class JavaCastClassifier {

    private JavaCastClassifier() {
    }

    public enum Kind {
        /** Value-preserving under the TIR type mapping: lower the operand, no SQL CAST. */
        IDENTITY,
        /** Numeric widening: plain SQL CAST to the target type. */
        WIDENING,
        /** Java fractional-to-integral truncation: SQL CAST composed with TRUNC/TRUNCATE. */
        FRACTIONAL_TRUNCATION,
        /** No clean SQL equivalent: rejected with a positioned diagnostic. */
        UNSUPPORTED
    }

    /**
     * @param targetTirType set for supported kinds that emit a CAST ({@code WIDENING},
     *        {@code FRACTIONAL_TRUNCATION}); {@code null} otherwise
     * @param rejectionDetail human-readable reason, set only for {@code UNSUPPORTED}
     * @param suggestion remediation hint, set only for {@code UNSUPPORTED}
     */
    public record Classification(Kind kind, TirType targetTirType, String rejectionDetail, String suggestion) {
        private static Classification identity() {
            return new Classification(Kind.IDENTITY, null, null, null);
        }

        private static Classification widening(TirType target) {
            return new Classification(Kind.WIDENING, target, null, null);
        }

        private static Classification truncating(TirType target) {
            return new Classification(Kind.FRACTIONAL_TRUNCATION, target, null, null);
        }

        private static Classification unsupported(String detail, String suggestion) {
            return new Classification(Kind.UNSUPPORTED, null, detail, suggestion);
        }
    }

    /** Scalar categories after unboxing, in numeric widening order where applicable. */
    private enum Category { BOOLEAN, INT, LONG, FRACTIONAL, CHAR, SMALL_INTEGRAL, STRING, OTHER }

    /**
     * Classifies the cast {@code (target) source}. Both mirrors come from the resolved trees
     * at the cast site; either may be {@code null} when resolution failed, which classifies
     * as {@code UNSUPPORTED}.
     */
    public static Classification classify(TypeMirror sourceType, TypeMirror targetType, Types types) {
        if (sourceType == null || targetType == null) {
            return Classification.unsupported(
                    "cast expression with unresolved types",
                    "Ensure both the cast target type and the operand resolve to supported scalar types");
        }

        Category target = categoryOf(targetType, types, true);
        Category source = categoryOf(sourceType, types, false);

        if (target == Category.OTHER || source == Category.OTHER) {
            return Classification.unsupported(
                    "reference cast expression (no SQL equivalent for a runtime checkcast)",
                    "Casts are supported only between numeric scalar types; "
                            + "use String.valueOf(x) for numeric-to-text conversion");
        }
        if (target == Category.SMALL_INTEGRAL) {
            return Classification.unsupported(
                    "narrowing cast expression to byte/short (Java truncates to 8/16 bits; "
                            + "SQL has no equivalent type to reproduce the wraparound)",
                    "Keep the value as int or long");
        }
        if (target == Category.CHAR || source == Category.CHAR) {
            return Classification.unsupported(
                    "char cast expression (char is represented as single-character TEXT; "
                            + "Java char casts are UTF-16 code-unit conversions with no SQL CAST equivalent)",
                    "Use the char in an arithmetic context (e.g. c - 'a'), which converts via the char-code helper");
        }
        if (target == Category.BOOLEAN || source == Category.BOOLEAN) {
            return target == source
                    ? Classification.identity()
                    : Classification.unsupported(
                            "boolean cast expression",
                            "Boolean values cannot be cast to or from other types");
        }
        if (target == Category.STRING || source == Category.STRING) {
            return target == source
                    ? Classification.identity()
                    : Classification.unsupported(
                            "cast expression between String and a non-String type",
                            "Use String.valueOf(x) for numeric-to-text conversion");
        }

        // Both are numeric categories: INT < LONG < FRACTIONAL.
        if (source == target) {
            return Classification.identity();
        }
        int sourceRank = numericRank(source);
        int targetRank = numericRank(target);
        if (sourceRank < targetRank) {
            return Classification.widening(tirTypeOf(target));
        }
        if (source == Category.FRACTIONAL) {
            return Classification.truncating(tirTypeOf(target));
        }
        // long -> int is the only remaining narrowing.
        return Classification.unsupported(
                "narrowing cast expression from long to int (SQL CAST range-errors where Java "
                        + "silently wraps to the low 32 bits)",
                "Keep the value as a long, or guard the range explicitly before narrowing");
    }

    private static int numericRank(Category category) {
        return switch (category) {
            case INT -> 0;
            case LONG -> 1;
            case FRACTIONAL -> 2;
            // Internal-invariant assertion: numericRank is only called for the three numeric
            // categories after every other category has been dispatched above.
            default -> throw new IllegalStateException("internal: non-numeric category " + category);
        };
    }

    private static TirType tirTypeOf(Category category) {
        return switch (category) {
            case INT -> new TIntType();
            case LONG -> new TBigintType();
            case FRACTIONAL -> new TDoubleType();
            // Internal-invariant assertion: only numeric categories emit a CAST.
            default -> throw new IllegalStateException("internal: no cast TIR type for category " + category);
        };
    }

    private static Category categoryOf(TypeMirror type, Types types, boolean isTarget) {
        TypeKind kind = unboxedKind(type, types);
        return switch (kind) {
            case BOOLEAN -> Category.BOOLEAN;
            // byte/short sources widen exactly to INT; as targets they require 8/16-bit
            // truncation that TIR cannot represent.
            case BYTE, SHORT -> isTarget ? Category.SMALL_INTEGRAL : Category.INT;
            case INT -> Category.INT;
            case LONG -> Category.LONG;
            case FLOAT, DOUBLE -> Category.FRACTIONAL;
            case CHAR -> Category.CHAR;
            case DECLARED -> isStringType(type, types) ? Category.STRING : Category.OTHER;
            default -> Category.OTHER;
        };
    }

    private static TypeKind unboxedKind(TypeMirror type, Types types) {
        if (type.getKind().isPrimitive()) {
            return type.getKind();
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return type.getKind();
        }
        try {
            return types.unboxedType(type).getKind();
        } catch (IllegalArgumentException notABoxedType) {
            return TypeKind.DECLARED;
        }
    }

    private static boolean isStringType(TypeMirror type, Types types) {
        return types.asElement(type) instanceof TypeElement element
                && "java.lang.String".contentEquals(element.getQualifiedName());
    }
}
