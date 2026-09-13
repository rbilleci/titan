package io.titan.transpiler.tir;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record StructuredPayloadRuntimePlan(
        StructuredPayloadEnvelopePlan envelope,
        RowMaterializationRuntimePlan rows
) {
    public StructuredPayloadRuntimePlan {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(rows, "rows");
        StructuredOutputRuntimePlan.from(envelope.toStructuredOutputPlan(), rows);
    }

    public static StructuredPayloadRuntimePlan success(
            StructuredOutputPlan.Value data,
            RowMaterializationRuntimePlan rows
    ) {
        return new StructuredPayloadRuntimePlan(StructuredPayloadEnvelopePlan.success(data), rows);
    }

    public StructuredOutputPlan toStructuredOutputPlan() {
        return envelope.toStructuredOutputPlan();
    }

    public Map<String, Object> assemble(
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows
    ) {
        return runtime().assemble(rootRow, relationRows);
    }

    public Map<String, Object> assembleOrCardinalityError(
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows,
            CardinalityErrorContract errorContract
    ) {
        Objects.requireNonNull(errorContract, "errorContract");
        try {
            return assemble(rootRow, relationRows);
        } catch (StructuredOutputRuntimePlan.RelationCardinalityViolationException exception) {
            return cardinalityErrorPayload(exception, errorContract);
        }
    }

    public List<Map<String, Object>> assembleBatch(
            List<? extends Map<String, ?>> rootRows,
            List<? extends Map<String, ?>> relationRows
    ) {
        return runtime().assembleBatch(rootRows, relationRows);
    }

    public List<Map<String, Object>> assembleBatchOrCardinalityError(
            List<? extends Map<String, ?>> rootRows,
            List<? extends Map<String, ?>> relationRows,
            CardinalityErrorContract errorContract
    ) {
        Objects.requireNonNull(errorContract, "errorContract");
        try {
            return assembleBatch(rootRows, relationRows);
        } catch (StructuredOutputRuntimePlan.RelationCardinalityViolationException exception) {
            return List.of(cardinalityErrorPayload(exception, errorContract));
        }
    }

    public List<Map<String, Object>> assembleNestedBatch(
            List<? extends Map<String, ?>> rootRows,
            Map<String, ? extends List<? extends Map<String, ?>>> relationRowsByName
    ) {
        return runtime().assembleNestedBatch(rootRows, relationRowsByName);
    }

    public List<Map<String, Object>> assembleNestedBatchOrCardinalityError(
            List<? extends Map<String, ?>> rootRows,
            Map<String, ? extends List<? extends Map<String, ?>>> relationRowsByName,
            CardinalityErrorContract errorContract
    ) {
        Objects.requireNonNull(errorContract, "errorContract");
        try {
            return assembleNestedBatch(rootRows, relationRowsByName);
        } catch (StructuredOutputRuntimePlan.RelationCardinalityViolationException exception) {
            return List.of(cardinalityErrorPayload(exception, errorContract));
        }
    }

    private StructuredOutputRuntimePlan runtime() {
        return StructuredOutputRuntimePlan.from(envelope.toStructuredOutputPlan(), rows);
    }

    private Map<String, Object> cardinalityErrorPayload(
            StructuredOutputRuntimePlan.RelationCardinalityViolationException exception,
            CardinalityErrorContract errorContract
    ) {
        StructuredPayloadErrorPlan error = new StructuredPayloadErrorPlan(
                new StructuredOutputPlan.TextLiteralValue(errorContract.code()),
                new StructuredOutputPlan.TextLiteralValue(
                        "relation '" + exception.relationName() + "' expected "
                                + exception.cardinality() + " cardinality but received "
                                + exception.rowCount() + " row(s)"),
                Optional.of(new StructuredOutputPlan.TextLiteralValue(errorContract.path())),
                Optional.empty(),
                Optional.empty(),
                List.of(
                        StructuredPayloadErrorPlan.Field.CODE,
                        StructuredPayloadErrorPlan.Field.MESSAGE,
                        StructuredPayloadErrorPlan.Field.PATH));
        RowMaterializationRuntimePlan rootOnlyRows =
                new RowMaterializationRuntimePlan(rows.rootQuery(), List.of());
        return StructuredOutputRuntimePlan.from(
                        StructuredPayloadEnvelopePlan.failureFromErrors(List.of(error)).toStructuredOutputPlan(),
                        rootOnlyRows)
                .assemble(Map.of(), List.of());
    }

    public record CardinalityErrorContract(String code, String path) {
        public CardinalityErrorContract {
            code = requireName(code, "code");
            path = requireName(path, "path");
        }

        public static CardinalityErrorContract defaultForPath(String path) {
            return new CardinalityErrorContract("CARDINALITY_VIOLATION", path);
        }
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured payload runtime " + field + " must not be blank.");
        }
        return value;
    }
}
