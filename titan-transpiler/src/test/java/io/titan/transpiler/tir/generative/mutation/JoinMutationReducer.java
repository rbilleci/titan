package io.titan.transpiler.tir.generative.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class JoinMutationReducer {

    JoinMutationCaseModel.JoinMutationCase reduce(JoinMutationCaseModel.JoinMutationCase mutationCase) {
        Objects.requireNonNull(mutationCase, "mutationCase");
        List<MutationCaseModel.ReductionStep> steps = new ArrayList<>(mutationCase.reductionSteps());
        steps.add(MutationCaseModel.reductionStep(
                "revert-join-pair-to-eqcolumn-base",
                true,
                "reduced join mutation back to the bounded eqColumn base form while preserving classification"));
        return JoinMutationCaseModel.of(
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
