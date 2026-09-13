package io.titan.transpiler.tir.generative.shared;

import java.util.Objects;

public class ReferenceResultComparison {

    public String summarizeMismatch(
            ReferenceResultModels.ReferenceResult expected,
            ReferenceResultModels.ReferenceResult actual
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");

        if (expected.toStableJson().equals(actual.toStableJson())) {
            return null;
        }
        if (expected.rows().size() != actual.rows().size()) {
            return "row-count mismatch: expected=%d actual=%d"
                    .formatted(expected.rows().size(), actual.rows().size());
        }
        for (int rowIndex = 0; rowIndex < expected.rows().size(); rowIndex++) {
            var expectedRow = expected.rows().get(rowIndex);
            var actualRow = actual.rows().get(rowIndex);
            if (!expectedRow.toStableJson().equals(actualRow.toStableJson())) {
                return "row mismatch at index %d\nexpected=%s\nactual=%s"
                        .formatted(rowIndex, expectedRow.toStableJson(), actualRow.toStableJson());
            }
        }
        return "result mismatch\nexpected=%s\nactual=%s".formatted(expected.toStableJson(), actual.toStableJson());
    }
}
