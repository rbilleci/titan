package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.differential.SelectDifferentialGenerator;
import io.titan.transpiler.tir.generative.differential.SelectDifferentialProfile;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MutationCaseModelTest {

    @Test
    void mutationCaseSerializesToStableJson() {
        SelectCaseModel.SelectCase baseCase = new SelectDifferentialGenerator().generate(8100);
        SelectCaseModel.SelectCase mutatedCase = SelectCaseModel.selectCase(
                MutationProfile.BASIC.id(),
                baseCase.sourceTable(),
                baseCase.projections(),
                SelectCaseModel.and(
                        baseCase.filter(),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.GE,
                                SelectCaseModel.intLiteral(1))),
                baseCase.ordering(),
                baseCase.resultShape());

        MutationCaseModel.MutationCase mutationCase = MutationCaseModel.mutationCase(
                MutationProfile.BASIC.id(),
                SelectDifferentialProfile.BASIC_SELECT.id(),
                8100,
                MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("addedColumn", "id"),
                        MutationCaseModel.parameter("operator", ">="),
                        MutationCaseModel.parameter("literal", "1")),
                baseCase,
                mutatedCase,
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of(MutationCaseModel.reductionStep("remove-added-conjunct", true, "classification preserved")));

        String stableJson = mutationCase.toStableJson();

        assertEquals(stableJson, mutationCase.toStableJson(), "serialization should be deterministic");
        assertTrue(stableJson.contains("\"profileId\": \"transpiler-mutation-basic\""));
        assertTrue(stableJson.contains("\"baseProfileId\": \"transpiler-diff-basic-select\""));
        assertTrue(stableJson.contains("\"mutatorId\": \"add-safe-conjunct\""));
        assertTrue(stableJson.contains("\"classification\": \"stable-pass\""));
        assertTrue(stableJson.contains("\"reductionSteps\""));
    }

    @Test
    void mutationParameterRejectsBlankKey() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MutationCaseModel.parameter(" ", "value"));

        assertEquals("mutation parameter key must not be blank", ex.getMessage());
    }

    @Test
    void profileRejectsUnknownIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MutationProfile.fromId("nope"));

        assertEquals("Unknown mutation profile: nope", ex.getMessage());
    }
}
