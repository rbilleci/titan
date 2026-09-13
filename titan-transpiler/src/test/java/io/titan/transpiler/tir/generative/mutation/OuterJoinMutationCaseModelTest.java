package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OuterJoinMutationCaseModelTest {

    @Test
    void outerJoinMutationCaseSerializesToStableJson() {
        OuterJoinMutationCaseModel.OuterJoinMutationCase mutationCase = new OuterJoinMutationGenerator().generate(8600L);

        String stableJson = mutationCase.toStableJson();

        assertEquals(stableJson, mutationCase.toStableJson(), "serialization should be deterministic");
        assertTrue(stableJson.contains("\"profileId\": \"transpiler-mutation-outer-joins\""));
        assertTrue(stableJson.contains("\"family\": \"left-join-null-extension-eqcolumn-to-pair\""));
        assertTrue(stableJson.contains("\"mutatorId\": \"add-safe-conjunct\""));
    }

    @Test
    void profileRejectsUnknownIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OuterJoinMutationProfile.fromId("nope"));

        assertEquals("Unknown Phase D outer-join mutation profile: nope", ex.getMessage());
    }
}
