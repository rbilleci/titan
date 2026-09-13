package io.titan.runtime.testing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonicalizes JDBC/Java values before equivalence comparison so that representational type
 * skew between java-mode and sql-mode results does not produce false alarms (audit R-5).
 *
 * <p>Normalization rules:</p>
 * <ul>
 *   <li><b>Exact numerics</b> ({@code Byte}, {@code Short}, {@code Integer}, {@code Long},
 *       {@code BigInteger}, {@code BigDecimal}) compare by numeric value: integrals widen to
 *       {@code long}, and {@code BigDecimal}s compare scale-insensitively (the
 *       {@code compareTo} contract — {@code 1.0 == 1.00}). The two representations are unified
 *       in one canonical family, so a routine returning {@code DECIMAL} matches a DSL path
 *       returning {@code BIGINT} when the values are numerically identical.</li>
 *   <li><b>Binary floating point</b>: {@code Float} widens to {@code Double}; doubles compare
 *       exactly. Binary floats are deliberately <em>not</em> unified with exact numerics —
 *       crossing that boundary silently would manufacture false confidence.</li>
 *   <li><b>Temporals</b> normalize to a common type/zone: zone-carrying values
 *       ({@code OffsetDateTime}, {@code ZonedDateTime}) normalize to {@link Instant} (UTC
 *       timeline); {@code java.sql.Timestamp}/{@code Date}/{@code Time} normalize to their
 *       wall-clock {@code java.time} equivalents ({@code LocalDateTime}/{@code LocalDate}/
 *       {@code LocalTime}).</li>
 *   <li><b>Text</b>: {@code Character} unifies with single-character {@code String}.</li>
 *   <li><b>byte[]</b> wraps in a content-equal holder (raw arrays compare by identity).</li>
 *   <li>{@code List}/{@code Object[]}/{@code Map} values normalize element-wise.</li>
 * </ul>
 *
 * <p>Everything else passes through untouched and compares with {@link Objects#equals}.</p>
 */
final class ValueNormalizer {

    private ValueNormalizer() {
    }

    static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new ExactNumber(BigDecimal.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger bigInteger) {
            return new ExactNumber(new BigDecimal(bigInteger));
        }
        if (value instanceof BigDecimal bigDecimal) {
            return new ExactNumber(bigDecimal);
        }
        if (value instanceof Float floatValue) {
            return Double.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof byte[] bytes) {
            return new Bytes(bytes.clone());
        }
        if (value instanceof Object[] array) {
            return normalizeList(Arrays.asList(array));
        }
        if (value instanceof List<?> list) {
            return normalizeList(list);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(entry.getKey(), normalize(entry.getValue()));
            }
            return normalized;
        }
        return value;
    }

    private static List<Object> normalizeList(List<?> list) {
        List<Object> normalized = new ArrayList<>(list.size());
        for (Object element : list) {
            normalized.add(normalize(element));
        }
        return normalized;
    }

    /**
     * Canonical exact-numeric value: equality and hashing follow the {@code BigDecimal.compareTo}
     * contract (scale-insensitive) via {@code stripTrailingZeros}.
     */
    record ExactNumber(BigDecimal canonical) {
        ExactNumber(BigDecimal canonical) {
            this.canonical = canonical.stripTrailingZeros();
        }

        @Override
        public String toString() {
            return canonical.toPlainString();
        }
    }

    /** Content-equal byte[] holder ({@code byte[].equals} is identity equality). */
    static final class Bytes {
        private final byte[] content;

        Bytes(byte[] content) {
            this.content = content;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes bytes && Arrays.equals(content, bytes.content);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "bytes[" + content.length + "]";
        }
    }
}
