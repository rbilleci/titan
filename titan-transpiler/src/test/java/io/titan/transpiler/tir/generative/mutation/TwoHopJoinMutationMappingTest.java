package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TwoHopJoinMutationMappingTest {

    @Test
    void twoHopFamiliesMapToExpectedBoundedReferenceFamilies() {
        TwoHopJoinMutationGenerator generator = new TwoHopJoinMutationGenerator();

        assertEquals(
                JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY,
                generator.generate(8700L).baseCase().family());
        assertEquals(
                JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION,
                generator.generate(8701L).baseCase().family());
        assertEquals(
                JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                generator.generate(8702L).baseCase().family());
        assertEquals(
                JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER,
                generator.generate(8703L).baseCase().family());
    }
}
