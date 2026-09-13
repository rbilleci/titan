package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OuterJoinMutationReducerTest {

    @Test
    void reducerCanMinimizeOuterJoinMutationBackToBaseWhilePreservingClassification() {
        OuterJoinMutationCaseModel.OuterJoinMutationCase mutationCase = new OuterJoinMutationGenerator().generate(8600L);
        OuterJoinMutationCaseModel.OuterJoinMutationCase interestingFailure = OuterJoinMutationCaseModel.of(
                mutationCase.profileId(),
                mutationCase.family(),
                mutationCase.baseCase(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseJavaSource(),
                mutationCase.mutatedJavaSource(),
                MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH,
                List.of());

        OuterJoinMutationCaseModel.OuterJoinMutationCase reduced = new OuterJoinMutationReducer().reduce(interestingFailure);

        assertEquals(MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH, reduced.classification());
        assertEquals(1, reduced.reductionSteps().size());
        assertEquals("revert-left-join-pair-to-eqcolumn-base", reduced.reductionSteps().getFirst().operation());
        assertTrue(reduced.reductionSteps().getFirst().preservedClassification());
        assertEquals(interestingFailure.baseJavaSource(), reduced.mutatedJavaSource());
    }
}
