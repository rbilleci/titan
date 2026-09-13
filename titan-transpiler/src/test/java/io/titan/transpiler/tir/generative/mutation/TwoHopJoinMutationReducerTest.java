package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TwoHopJoinMutationReducerTest {

    @Test
    void reducerCanMinimizeTwoHopJoinMutationBackToBaseWhilePreservingClassification() {
        var mutationCase = new TwoHopJoinMutationGenerator().generate(8700L);
        var interestingFailure = TwoHopJoinMutationCaseModel.of(
                mutationCase.profileId(),
                mutationCase.family(),
                mutationCase.baseCase(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseJavaSource(),
                mutationCase.mutatedJavaSource(),
                MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH,
                List.of());

        var reduced = new TwoHopJoinMutationReducer().reduce(interestingFailure);

        assertEquals(MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH, reduced.classification());
        assertEquals(1, reduced.reductionSteps().size());
        assertEquals("revert-inner-second-hop-pair-to-eqcolumn-base", reduced.reductionSteps().getFirst().operation());
        assertTrue(reduced.reductionSteps().getFirst().preservedClassification());
        assertEquals(interestingFailure.baseJavaSource(), reduced.mutatedJavaSource());
    }

    @Test
    void reducerUsesFamilySpecificOperationForFilteredLeftTwoHopCases() {
        var mutationCase = new TwoHopJoinMutationGenerator().generate(8702L);
        var interestingFailure = TwoHopJoinMutationCaseModel.of(
                mutationCase.profileId(),
                mutationCase.family(),
                mutationCase.baseCase(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseJavaSource(),
                mutationCase.mutatedJavaSource(),
                MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH,
                List.of());

        var reduced = new TwoHopJoinMutationReducer().reduce(interestingFailure);

        assertEquals("revert-left-filtered-second-hop-pair-to-eqcolumn-base", reduced.reductionSteps().getFirst().operation());
        assertTrue(reduced.reductionSteps().getFirst().note().contains("null-extension/filter"));
    }
}
