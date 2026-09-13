package io.titan.transpiler.tir.generative.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BasicMutationReducer {

    MutationCaseModel.MutationCase reduce(MutationCaseModel.MutationCase mutationCase) {
        Objects.requireNonNull(mutationCase, "mutationCase");
        return switch (mutationCase.mutatorId()) {
            case ADD_SAFE_CONJUNCT -> reduceToBaseCase(mutationCase, "remove-added-conjunct");
            case ADD_SAFE_DISJUNCT -> reduceToBaseCase(mutationCase, "remove-added-disjunct");
            case DUPLICATE_PROJECTION -> reduceToBaseCase(mutationCase, "remove-duplicate-projection");
            case INJECT_DERIVED_PROJECTION -> reduceToBaseCase(mutationCase, "remove-derived-projection");
        };
    }

    private static MutationCaseModel.MutationCase reduceToBaseCase(
            MutationCaseModel.MutationCase mutationCase,
            String operation
    ) {
        List<MutationCaseModel.ReductionStep> steps = new ArrayList<>(mutationCase.reductionSteps());
        steps.add(MutationCaseModel.reductionStep(
                operation,
                true,
                "reduced mutated case back to the bounded base case while preserving classification"));
        return MutationCaseModel.mutationCase(
                mutationCase.profileId(),
                mutationCase.baseProfileId(),
                mutationCase.baseSeed(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseCase(),
                mutationCase.baseCase(),
                mutationCase.classification(),
                steps);
    }
}
