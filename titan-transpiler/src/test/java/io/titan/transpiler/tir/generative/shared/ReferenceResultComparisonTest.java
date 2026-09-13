package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferenceResultComparisonTest {

    private final ReferenceResultComparison comparison = new ReferenceResultComparison();

    @Test
    void returnsNullWhenResultsMatch() {
        var result = new ReferenceResult(List.of(
                new ResultRow(Map.of("id", 1, "email", "ada@titan.dev"))));

        assertNull(comparison.summarizeMismatch(result, result));
    }

    @Test
    void reportsRowCountMismatch() {
        var expected = new ReferenceResult(List.of(
                new ResultRow(Map.of("id", 1))));
        var actual = new ReferenceResult(List.of(
                new ResultRow(Map.of("id", 1)),
                new ResultRow(Map.of("id", 2))));

        assertEquals("row-count mismatch: expected=1 actual=2", comparison.summarizeMismatch(expected, actual));
    }

    @Test
    void reportsFirstRowMismatch() {
        var expected = new ReferenceResult(List.of(
                new ResultRow(Map.of("id", 1, "email", "ada@titan.dev"))));
        var actual = new ReferenceResult(List.of(
                new ResultRow(Map.of("id", 1, "email", "wrong@titan.dev"))));

        String summary = comparison.summarizeMismatch(expected, actual);

        assertTrue(summary.contains("row mismatch at index 0"), summary);
        assertTrue(summary.contains("ada@titan.dev"), summary);
        assertTrue(summary.contains("wrong@titan.dev"), summary);
    }
}
