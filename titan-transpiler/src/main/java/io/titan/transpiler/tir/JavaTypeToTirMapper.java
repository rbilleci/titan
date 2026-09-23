package io.titan.transpiler.tir;

import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Single authority for mapping Java types to TIR types (plan 1.2, audit finding S1).
 *
 * <p>Both lowering ({@link JavaToTirLowerer}) and signature mapping ({@link TranspilationPipeline})
 * delegate here. The two historic mappers disagreed in several places; each reconciliation is
 * documented inline at the relevant case. Unknown types are never silently mapped to TEXT:
 * an unrecognized type raises a TITAN-E001 diagnostic naming the Java type and the
 * method/variable context supplied by the caller.</p>
 */
public final class JavaTypeToTirMapper {

    private JavaTypeToTirMapper() {
    }

    /**
     * Maps a resolved Java {@link TypeMirror} to its TIR type.
     *
     * @param context method/variable context used in the diagnostic for unrecognized types
     */
    public static TirType map(TypeMirror typeMirror, String context) {
        if (typeMirror == null) {
            throw unmappable("<unresolved>", context);
        }
        return switch (typeMirror.getKind()) {
            case BOOLEAN -> new TBooleanType();
            case BYTE, SHORT, INT -> new TIntType();
            case LONG -> new TBigintType();
            // char policy: a Java char is represented as single-character TEXT. Both historic
            // mappers only reached this via the silent TEXT fallback; now explicit.
            case CHAR -> new TTextType();
            // Java floating-point values keep IEEE-754 range/rounding rather than being
            // silently narrowed to fixed-scale NUMERIC. BigDecimal remains TNumericType.
            case FLOAT, DOUBLE -> new TDoubleType();
            case VOID -> new TVoidType();
            // byte[] is an opaque binary payload (BYTEA/LONGBLOB), NOT an array of integers.
            case ARRAY -> ((ArrayType) typeMirror).getComponentType().getKind() == javax.lang.model.type.TypeKind.BYTE
                    ? new TBytesType()
                    : new TArrayType(map(((ArrayType) typeMirror).getComponentType(), context));
            case DECLARED -> mapDeclared((DeclaredType) typeMirror, context);
            default -> throw unmappable(typeMirror.toString(), context);
        };
    }

    /**
     * Maps a rendered Java type name (as produced by {@code TypeMirror.toString()}) to its TIR
     * type. Used where only discovery-time strings are available (entry-point signatures).
     *
     * @param recordNames source-local qualified names of source-discovered records
     *        ({@code Outer.SortPath}; plain {@code SortPath} when top-level); matches map to
     *        {@code TRecordType} carrying the qualified name (B-2 / TG-BLK-005)
     * @param enumNames source-local qualified names of source-discovered enums; matches map to
     *        TEXT (enum values are represented by their constant names)
     * @param schema schema used to qualify record types
     * @param context method/variable context used in the diagnostic for unrecognized types
     */
    public static TirType map(
            String javaTypeName,
            Set<String> recordNames,
            Set<String> enumNames,
            String schema,
            String context
    ) {
        if (javaTypeName == null || javaTypeName.isBlank()) {
            throw unmappable("<missing>", context);
        }
        String normalized = javaTypeName.trim();
        if (normalized.equals("byte[]")) {
            // byte[] is an opaque binary payload (BYTEA/LONGBLOB), NOT an array of integers.
            return new TBytesType();
        }
        if (normalized.endsWith("[]")) {
            return new TArrayType(map(
                    normalized.substring(0, normalized.length() - 2),
                    recordNames, enumNames, schema, context));
        }
        // Reconciliation: the lowerer mapped List<T> to TArrayType while the pipeline's string
        // mapper fell back to TEXT. The lowerer's mapping is more precise and wins.
        if (normalized.endsWith(">")
                && (normalized.startsWith("java.util.List<") || normalized.startsWith("List<"))) {
            return new TArrayType(map(
                    normalized.substring(normalized.indexOf('<') + 1, normalized.length() - 1),
                    recordNames, enumNames, schema, context));
        }
        if (recordNames.contains(normalized)) {
            return new TRecordType(schema, normalized);
        }
        if (enumNames.contains(normalized)) {
            return new TTextType();
        }
        TirType named = mapNamedType(normalized);
        if (named != null) {
            return named;
        }
        // Source-local types render with their package (com.app.Outer.SortPath) or, in the
        // default package, as their source-local qualified name (Outer.SortPath). Resolve to
        // the discovered qualified name so the TRecordType identity is the qualified one —
        // bare simple-name matching collapsed same-simple-name records (B-2 / TG-BLK-005).
        String recordMatch = matchSourceLocalType(normalized, recordNames, "record", context);
        if (recordMatch != null) {
            return new TRecordType(schema, recordMatch);
        }
        String enumMatch = matchSourceLocalType(normalized, enumNames, "enum", context);
        if (enumMatch != null) {
            return new TTextType();
        }
        throw unmappable(normalized, context);
    }

    /**
     * Resolves a rendered type name against the discovered source-local qualified names:
     * longest qualified-suffix match first ({@code com.app.Outer.SortPath} ends with
     * {@code .Outer.SortPath}), then the simple-name tail when it is unambiguous. A simple-name
     * tail matching several discovered declarations is rejected with a diagnostic naming the
     * candidates instead of silently picking one.
     */
    private static String matchSourceLocalType(
            String normalized,
            Set<String> qualifiedNames,
            String kind,
            String context
    ) {
        String longestSuffixMatch = null;
        for (String qualifiedName : qualifiedNames) {
            if (normalized.endsWith("." + qualifiedName)
                    && (longestSuffixMatch == null || qualifiedName.length() > longestSuffixMatch.length())) {
                longestSuffixMatch = qualifiedName;
            }
        }
        if (longestSuffixMatch != null) {
            return longestSuffixMatch;
        }
        String simpleName = normalized.substring(normalized.lastIndexOf('.') + 1);
        List<String> tailMatches = qualifiedNames.stream()
                .filter(qualifiedName -> simpleName.equals(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)))
                .toList();
        if (tailMatches.size() > 1) {
            throw new IllegalArgumentException(
                    "TITAN-E001: Ambiguous " + kind + " type '" + normalized + "' in " + context
                            + ": it matches several source-discovered declarations ("
                            + String.join(", ", tailMatches)
                            + "). Reference the " + kind + " through its enclosing type to disambiguate.");
        }
        return tailMatches.isEmpty() ? null : tailMatches.getFirst();
    }

    private static TirType mapDeclared(DeclaredType declaredType, String context) {
        if (declaredType.asElement() instanceof TypeElement typeElement) {
            if ("java.util.List".contentEquals(typeElement.getQualifiedName())
                    && declaredType.getTypeArguments().size() == 1) {
                return new TArrayType(map(declaredType.getTypeArguments().getFirst(), context));
            }
            if (typeElement.getKind() == ElementKind.RECORD) {
                // Records stay schema-unqualified here; TranspilationPipeline qualifies them later.
                // The identity is the source-local qualified name (Outer.SortPath), never the bare
                // simple name — same-simple-name records must stay distinct (B-2 / TG-BLK-005).
                return new TRecordType(LowererSupport.sourceLocalTypeName(typeElement));
            }
            if (typeElement.getKind() == ElementKind.ENUM) {
                // Enum values are represented by their constant names; both call sites already
                // lowered enum-typed declarations and enum constants as TEXT.
                return new TTextType();
            }
            TirType named = mapNamedType(typeElement.getQualifiedName().toString());
            if (named != null) {
                return named;
            }
        }
        throw unmappable(declaredType.toString(), context);
    }

    private static TirType mapNamedType(String typeName) {
        return switch (typeName) {
            case "boolean", "java.lang.Boolean", "Boolean" -> new TBooleanType();
            case "byte", "java.lang.Byte", "Byte",
                    "short", "java.lang.Short", "Short",
                    "int", "java.lang.Integer", "Integer" -> new TIntType();
            case "long", "java.lang.Long", "Long" -> new TBigintType();
            // char policy: a Java char is represented as single-character TEXT (see map(TypeMirror)).
            case "char", "java.lang.Character", "Character" -> new TTextType();
            case "float", "java.lang.Float", "Float",
                    "double", "java.lang.Double", "Double" -> new TDoubleType();
            case
                    "java.math.BigDecimal", "BigDecimal",
                    "java.math.BigInteger", "BigInteger" -> new TNumericType(38, 10);
            case "java.lang.String", "String" -> new TTextType();
            case "java.time.LocalDate", "LocalDate" -> new TDateType();
            case "java.time.LocalTime", "LocalTime" -> new TTimeType();
            case "java.time.LocalDateTime", "LocalDateTime" -> new TTimestampType();
            // Reconciliation: the pipeline's string mapper lacked OffsetDateTime and fell back to
            // TEXT; the lowerer mapped it to TIMESTAMPTZ. The lowerer's mapping wins.
            case "java.time.Instant", "Instant",
                    "java.time.ZonedDateTime", "ZonedDateTime",
                    "java.time.OffsetDateTime", "OffsetDateTime" -> new TTimestampTzType();
            case "java.time.Duration", "Duration" -> new TDurationType();
            case "java.time.Period", "Period" -> new TPeriodType();
            // UUID: native `uuid` on PostgreSQL, `CHAR(36)` (canonical string) on MySQL. Values are
            // always bound; on MySQL the runtime stringifies before binding (JdbcExecutor).
            case "java.util.UUID", "UUID" -> new TUuidType();
            // Reconciliation: the lowerer mapped void to TVoidType while the pipeline's string
            // mapper fell back to TEXT. The lowerer's mapping wins.
            case "void" -> new TVoidType();
            default -> null;
        };
    }

    private static IllegalArgumentException unmappable(String javaTypeName, String context) {
        return new IllegalArgumentException(
                "TITAN-E001: Unsupported feature. No SQL type mapping for Java type '" + javaTypeName
                        + "' in " + context + ". Supported types: primitives, boxed primitives, String,"
                        + " BigDecimal/BigInteger, java.time date/time types, Duration/Period, UUID, arrays,"
                        + " List<T>, source-local records, and enums.");
    }
}
