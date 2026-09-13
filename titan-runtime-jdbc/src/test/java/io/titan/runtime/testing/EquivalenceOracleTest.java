package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the equivalence oracle (audit R-5): every normalization rule, plus
 * false-alarm regressions for the failure modes of the old order-sensitive
 * {@code Objects.equals} comparison.
 */
class EquivalenceOracleTest {

    // ---------------------------------------------------------------- numeric normalization

    @Test
    void integerOneEqualsLongOne_falseAlarmRegression() {
        // The old oracle alarmed on Integer 1 vs Long 1L. Integral types widen to long.
        assertTrue(EquivalenceOracle.compareScalars(1, 1L).isEmpty());
        assertTrue(EquivalenceOracle.compareRows(List.of(1), List.of(1L), ComparisonMode.ORDERED).isEmpty());
    }

    @Test
    void allIntegralTypesWidenToOneFamily() {
        assertTrue(EquivalenceOracle.compareScalars((byte) 7, (short) 7).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars((short) 7, 7).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(7, BigInteger.valueOf(7)).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(7L, BigDecimal.valueOf(7)).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(7, 8).isPresent());
    }

    @Test
    void bigDecimalComparesScaleInsensitively_falseAlarmRegression() {
        // The old oracle used BigDecimal.equals: 1.0 != 1.00. The compareTo contract applies.
        assertTrue(EquivalenceOracle.compareScalars(new BigDecimal("1.0"), new BigDecimal("1.00")).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(new BigDecimal("1000"), new BigDecimal("1E+3")).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(new BigDecimal("0.00"), BigDecimal.ZERO).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(new BigDecimal("1.01"), new BigDecimal("1.0")).isPresent());
    }

    @Test
    void floatWidensToDouble() {
        assertTrue(EquivalenceOracle.compareScalars(1.5f, 1.5d).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(2.5f, 1.5d).isPresent());
    }

    @Test
    void binaryFloatsDoNotSilentlyUnifyWithExactNumerics() {
        // Crossing the binary/exact boundary silently would manufacture false confidence.
        assertTrue(EquivalenceOracle.compareScalars(1.0d, new BigDecimal("1.0")).isPresent());
        assertTrue(EquivalenceOracle.compareScalars(1.0d, 1L).isPresent());
    }

    // ---------------------------------------------------------------- temporal normalization

    @Test
    void sqlTimestampEqualsLocalDateTime() {
        LocalDateTime wallClock = LocalDateTime.of(2024, 6, 9, 10, 30, 0);
        assertTrue(EquivalenceOracle.compareScalars(Timestamp.valueOf(wallClock), wallClock).isEmpty());
    }

    @Test
    void zoneCarryingTemporalsNormalizeToTheSameInstant() {
        Instant instant = Instant.parse("2024-06-09T08:00:00Z");
        OffsetDateTime sameMomentElsewhere = instant.atOffset(ZoneOffset.ofHours(2));
        assertTrue(EquivalenceOracle.compareScalars(sameMomentElsewhere, instant).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(
                sameMomentElsewhere.atZoneSameInstant(ZoneOffset.ofHours(-5)), instant).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(instant.plusSeconds(1), instant).isPresent());
    }

    @Test
    void sqlDateAndTimeNormalizeToJavaTime() {
        assertTrue(EquivalenceOracle.compareScalars(
                java.sql.Date.valueOf("2024-06-09"), java.time.LocalDate.of(2024, 6, 9)).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(
                java.sql.Time.valueOf("10:30:00"), java.time.LocalTime.of(10, 30)).isEmpty());
    }

    // ---------------------------------------------------------------- text and binary

    @Test
    void characterUnifiesWithSingleCharString() {
        assertTrue(EquivalenceOracle.compareScalars('a', "a").isEmpty());
        assertTrue(EquivalenceOracle.compareScalars('a', "b").isPresent());
    }

    @Test
    void byteArraysCompareByContent() {
        assertTrue(EquivalenceOracle.compareScalars(new byte[]{1, 2}, new byte[]{1, 2}).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(new byte[]{1, 2}, new byte[]{2, 1}).isPresent());
    }

    // ---------------------------------------------------------------- ordering modes

    @Test
    void reorderedRowsEqualInUnorderedMode_falseAlarmRegression() {
        List<List<Object>> first = List.of(List.of(1, "a"), List.of(2, "b"));
        List<List<Object>> second = List.of(List.of(2, "b"), List.of(1, "a"));
        assertTrue(EquivalenceOracle.compareRows(first, second, ComparisonMode.UNORDERED).isEmpty());
    }

    @Test
    void reorderedRowsDivergeInOrderedMode() {
        List<List<Object>> first = List.of(List.of(1, "a"), List.of(2, "b"));
        List<List<Object>> second = List.of(List.of(2, "b"), List.of(1, "a"));
        Optional<EquivalenceOracle.Divergence> divergence =
                EquivalenceOracle.compareRows(first, second, ComparisonMode.ORDERED);
        assertTrue(divergence.isPresent());
        assertEquals("row 0", divergence.get().where());
    }

    @Test
    void unorderedModeIsMultisetSensitive_noFalseConfidenceOnMultiplicities() {
        List<Object> first = List.of("a", "a", "b");
        List<Object> second = List.of("a", "b", "b");
        assertTrue(EquivalenceOracle.compareRows(first, second, ComparisonMode.UNORDERED).isPresent());
    }

    @Test
    void normalizationAppliesInsideUnorderedMatching() {
        List<List<Object>> javaRows = List.of(List.of(1, new BigDecimal("2.50")));
        List<List<Object>> sqlRows = List.of(List.of(1L, new BigDecimal("2.5")));
        assertTrue(EquivalenceOracle.compareRows(javaRows, sqlRows, ComparisonMode.UNORDERED).isEmpty());
    }

    // ---------------------------------------------------------------- divergence reporting

    @Test
    void orderedDivergenceReportsRowColumnBothValuesAndBothTypes() {
        List<List<Object>> javaRows = List.of(List.of(1, "alice"), List.of(2, "bob"));
        List<List<Object>> sqlRows = List.of(List.of(1, "alice"), List.of(2, "carol"));

        EquivalenceOracle.Divergence divergence =
                EquivalenceOracle.compareRows(javaRows, sqlRows, ComparisonMode.ORDERED).orElseThrow();

        assertEquals("row 1", divergence.where());
        assertEquals("column 2", divergence.column());
        assertEquals("bob", divergence.javaValue());
        assertEquals("carol", divergence.sqlValue());
        String message = divergence.describe();
        assertTrue(message.contains("row 1"), message);
        assertTrue(message.contains("column 2"), message);
        assertTrue(message.contains("bob"), message);
        assertTrue(message.contains("carol"), message);
        assertTrue(message.contains("java.lang.String"), message);
    }

    @Test
    void typeMismatchWithSameRenderingIsDistinguishableInTheDiff() {
        EquivalenceOracle.Divergence divergence =
                EquivalenceOracle.compareScalars(1.0d, BigDecimal.ONE).orElseThrow();
        String message = divergence.describe();
        assertTrue(message.contains("java.lang.Double"), message);
        assertTrue(message.contains("java.math.BigDecimal"), message);
    }

    @Test
    void rowCountMismatchReportsBothCounts() {
        EquivalenceOracle.Divergence divergence = EquivalenceOracle.compareRows(
                List.of(1, 2, 3), List.of(1, 2), ComparisonMode.UNORDERED).orElseThrow();
        assertEquals("row count", divergence.where());
        assertEquals(3, divergence.javaValue());
        assertEquals(2, divergence.sqlValue());
    }

    @Test
    void unmatchedRowInUnorderedModeNamesTheOffendingColumnAgainstTheClosestCandidate() {
        List<Map<String, Object>> javaRows = List.of(
                Map.of("id", 1, "email", "alice@example.com"),
                Map.of("id", 2, "email", "bob@example.com"));
        List<Map<String, Object>> sqlRows = List.of(
                Map.of("id", 2, "email", "bob@example.com"),
                Map.of("id", 1, "email", "ALICE@example.com"));

        EquivalenceOracle.Divergence divergence =
                EquivalenceOracle.compareRows(javaRows, sqlRows, ComparisonMode.UNORDERED).orElseThrow();

        assertTrue(divergence.where().contains("row 0"), divergence.where());
        assertEquals("email", divergence.column());
        assertEquals("alice@example.com", divergence.javaValue());
        assertEquals("ALICE@example.com", divergence.sqlValue());
    }

    // ---------------------------------------------------------------- row shapes

    @Test
    void mapRowsCompareByColumnName() {
        assertTrue(EquivalenceOracle.compareRows(
                List.of(Map.of("id", 1, "name", "a")),
                List.of(Map.of("id", 1L, "name", "a")),
                ComparisonMode.UNORDERED).isEmpty());
    }

    record AccountRow(int id, BigDecimal balance) {
    }

    @Test
    void recordRowsCompareByComponentWithNormalization() {
        assertTrue(EquivalenceOracle.compareRows(
                List.of(new AccountRow(1, new BigDecimal("10.0"))),
                List.of(new AccountRow(1, new BigDecimal("10.00"))),
                ComparisonMode.ORDERED).isEmpty());

        EquivalenceOracle.Divergence divergence = EquivalenceOracle.compareRows(
                List.of(new AccountRow(1, new BigDecimal("10.0"))),
                List.of(new AccountRow(1, new BigDecimal("10.50"))),
                ComparisonMode.ORDERED).orElseThrow();
        assertEquals("balance", divergence.column());
    }

    @Test
    void objectArrayRowsCompareLikeListRows() {
        assertTrue(EquivalenceOracle.compareRows(
                List.of((Object) new Object[]{1, "x"}),
                List.of(List.of(1L, "x")),
                ComparisonMode.ORDERED).isEmpty());
    }

    @Test
    void nullsCompareEqualAndNullVsValueDiverges() {
        assertTrue(EquivalenceOracle.compareScalars(null, null).isEmpty());
        assertTrue(EquivalenceOracle.compareScalars(null, 1).isPresent());
        String message = EquivalenceOracle.compareScalars(null, 1).orElseThrow().describe();
        assertTrue(message.contains("NULL"), message);
    }
}
