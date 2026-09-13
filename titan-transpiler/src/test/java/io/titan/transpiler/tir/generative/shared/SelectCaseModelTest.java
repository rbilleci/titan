package io.titan.transpiler.tir.generative.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectCaseModelTest {

    @Test
    void selectCaseSerializesToStableJson() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_count_plus_one",
                                SelectCaseModel.add(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.and(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.boolLiteral(true)),
                        SelectCaseModel.not(
                                SelectCaseModel.nullCheck(
                                        SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                        SelectCaseModel.NullCheckKind.IS_NULL))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        String stableJson = selectCase.toStableJson();

        assertEquals(stableJson, selectCase.toStableJson(), "serialization should be deterministic");
        assertTrue(stableJson.contains("\"profileId\": \"transpiler-diff-basic-select\""));
        assertTrue(stableJson.contains("\"sourceTable\": \"accounts_fixture\""));
        assertTrue(stableJson.contains("\"operator\": \"and\""));
        assertTrue(stableJson.contains("\"operator\": \"+\""));
        assertTrue(stableJson.contains("\"resultShape\": \"row-set\""));
    }

    @Test
    void selectCaseRejectsEmptyProjectionList() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(),
                null,
                List.of(),
                SelectCaseModel.ResultShape.ROW_SET));

        assertEquals("projections must not be empty", ex.getMessage());
    }

    @Test
    void comparisonRejectsMixedValueTypes() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> SelectCaseModel.compare(
                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                SelectCaseModel.ComparisonOperator.EQ,
                SelectCaseModel.textLiteral("1")));

        assertEquals("comparison operands must share the same valueType", ex.getMessage());
    }
}
