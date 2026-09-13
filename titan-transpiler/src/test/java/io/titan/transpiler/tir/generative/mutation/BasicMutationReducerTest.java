package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BasicMutationReducerTest {

    @Test
    void reducerCanMinimizeSafeConjunctCaseBackToBaseWhilePreservingClassification() {
        MutationCaseModel.MutationCase mutationCase = new BasicMutationGenerator()
                .generate(MutationProfile.BASIC, 8100L, MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT);
        MutationCaseModel.MutationCase interestingFailure = MutationCaseModel.mutationCase(
                mutationCase.profileId(),
                mutationCase.baseProfileId(),
                mutationCase.baseSeed(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseCase(),
                mutationCase.mutatedCase(),
                MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH,
                List.of());

        MutationCaseModel.MutationCase reduced = new BasicMutationReducer().reduce(interestingFailure);

        assertEquals(MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH, reduced.classification());
        assertEquals(1, reduced.reductionSteps().size());
        assertEquals("remove-added-conjunct", reduced.reductionSteps().getFirst().operation());
        assertTrue(reduced.reductionSteps().getFirst().preservedClassification());
        assertSame(interestingFailure.baseCase(), reduced.mutatedCase());
    }

    @Test
    void reducerCanMinimizeDuplicateProjectionCaseBackToBaseWhilePreservingClassification() {
        MutationCaseModel.MutationCase mutationCase = new BasicMutationGenerator()
                .generate(MutationProfile.BASIC, 8102L, MutationCaseModel.MutatorId.DUPLICATE_PROJECTION);
        MutationCaseModel.MutationCase interestingFailure = MutationCaseModel.mutationCase(
                mutationCase.profileId(),
                mutationCase.baseProfileId(),
                mutationCase.baseSeed(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseCase(),
                mutationCase.mutatedCase(),
                MutationCaseModel.MutationClassification.SQL_EXECUTION_FAILURE,
                List.of());

        MutationCaseModel.MutationCase reduced = new BasicMutationReducer().reduce(interestingFailure);

        assertEquals(MutationCaseModel.MutationClassification.SQL_EXECUTION_FAILURE, reduced.classification());
        assertEquals("remove-duplicate-projection", reduced.reductionSteps().getFirst().operation());
        assertSame(interestingFailure.baseCase(), reduced.mutatedCase());
    }
}
