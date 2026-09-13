package io.titan.transpiler.tir.generative.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class OuterJoinMutationReducer {

    OuterJoinMutationCaseModel.OuterJoinMutationCase reduce(OuterJoinMutationCaseModel.OuterJoinMutationCase mutationCase) {
        Objects.requireNonNull(mutationCase, "mutationCase");
        List<MutationCaseModel.ReductionStep> steps = new ArrayList<>(mutationCase.reductionSteps());
        steps.add(MutationCaseModel.reductionStep(
                "revert-left-join-pair-to-eqcolumn-base",
                true,
                "reduced outer-join mutation back to the bounded eqColumn base form while preserving classification"));
        return OuterJoinMutationCaseModel.of(
                mutationCase.profileId(),
                mutationCase.family(),
                mutationCase.baseCase(),
                mutationCase.mutatorId(),
                mutationCase.mutationParameters(),
                mutationCase.baseJavaSource(),
                mutationCase.baseJavaSource(),
                mutationCase.classification(),
                steps);
    }
}
