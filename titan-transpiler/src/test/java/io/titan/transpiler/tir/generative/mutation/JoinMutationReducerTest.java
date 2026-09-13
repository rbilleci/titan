package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JoinMutationReducerTest {

    @Test
    void reducerCanMinimizeJoinMutationBackToBaseWhilePreservingClassification() {
        JoinMutationCaseModel.JoinMutationCase mutationCase = new JoinMutationGenerator().generate(8500L);
        JoinMutationCaseModel.JoinMutationCase interestingFailure = JoinMutationCaseModel.of(
                mutationCase.profileId(),
                mutationCase.family(),
                mutationCase.baseCase(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseJavaSource(),
                mutationCase.mutatedJavaSource(),
                MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH,
                List.of());

        JoinMutationCaseModel.JoinMutationCase reduced = new JoinMutationReducer().reduce(interestingFailure);

        assertEquals(MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH, reduced.classification());
        assertEquals(1, reduced.reductionSteps().size());
        assertEquals("revert-join-pair-to-eqcolumn-base", reduced.reductionSteps().getFirst().operation());
        assertTrue(reduced.reductionSteps().getFirst().preservedClassification());
        assertEquals(interestingFailure.baseJavaSource(), reduced.mutatedJavaSource());
    }
}
