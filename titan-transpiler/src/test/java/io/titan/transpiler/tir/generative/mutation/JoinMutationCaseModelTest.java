package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JoinMutationCaseModelTest {

    @Test
    void joinMutationCaseSerializesToStableJson() {
        JoinMutationCaseModel.JoinMutationCase mutationCase = new JoinMutationGenerator().generate(8500L);

        String stableJson = mutationCase.toStableJson();

        assertEquals(stableJson, mutationCase.toStableJson(), "serialization should be deterministic");
        assertTrue(stableJson.contains("\"profileId\": \"transpiler-mutation-joins\""));
        assertTrue(stableJson.contains("\"family\": \"inner-join-active-eqcolumn-to-pair\""));
        assertTrue(stableJson.contains("\"mutatorId\": \"add-safe-conjunct\""));
    }

    @Test
    void profileRejectsUnknownIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JoinMutationProfile.fromId("nope"));

        assertEquals("Unknown Phase D join-mutation profile: nope", ex.getMessage());
    }
}
