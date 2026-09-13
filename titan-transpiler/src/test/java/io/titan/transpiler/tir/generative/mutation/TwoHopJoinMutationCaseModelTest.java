package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TwoHopJoinMutationCaseModelTest {

    @Test
    void twoHopJoinMutationCaseSerializesToStableJson() {
        var mutationCase = new TwoHopJoinMutationGenerator().generate(8700L);

        String stableJson = mutationCase.toStableJson();

        assertEquals(stableJson, mutationCase.toStableJson(), "serialization should be deterministic");
        assertTrue(stableJson.contains("\"profileId\": \"transpiler-mutation-two-hop-joins\""));
        assertTrue(stableJson.contains("\"family\": \"two-hop-active-second-hop-eqcolumn-to-pair\""));
        assertTrue(stableJson.contains("\"mutatorId\": \"add-safe-conjunct\""));
    }

    @Test
    void profileRejectsUnknownIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TwoHopJoinMutationProfile.fromId("nope"));

        assertEquals("Unknown Phase D two-hop join mutation profile: nope", ex.getMessage());
    }
}
