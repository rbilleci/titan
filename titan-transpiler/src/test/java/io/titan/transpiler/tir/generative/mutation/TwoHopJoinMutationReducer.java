package io.titan.transpiler.tir.generative.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TwoHopJoinMutationReducer {

    TwoHopJoinMutationCaseModel.TwoHopJoinMutationCase reduce(TwoHopJoinMutationCaseModel.TwoHopJoinMutationCase mutationCase) {
        Objects.requireNonNull(mutationCase, "mutationCase");
        List<MutationCaseModel.ReductionStep> steps = new ArrayList<>(mutationCase.reductionSteps());
        steps.add(reductionStepFor(mutationCase.family()));
        return TwoHopJoinMutationCaseModel.of(
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

    private static MutationCaseModel.ReductionStep reductionStepFor(TwoHopJoinMutationCaseModel.Family family) {
        return switch (family) {
            case TWO_HOP_ACTIVE_SECOND_HOP_EQCOLUMN_TO_PAIR -> MutationCaseModel.reductionStep(
                    "revert-inner-second-hop-pair-to-eqcolumn-base",
                    true,
                    "reduced inner two-hop second-hop pair shorthand back to the bounded eqColumn base form while preserving classification");
            case LEFT_TWO_HOP_NULL_EXTENSION_SECOND_HOP_EQCOLUMN_TO_PAIR -> MutationCaseModel.reductionStep(
                    "revert-left-second-hop-pair-to-eqcolumn-base",
                    true,
                    "reduced left two-hop second-hop pair shorthand back to the bounded eqColumn base form while preserving null-extension classification");
            case LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR -> MutationCaseModel.reductionStep(
                    "revert-left-filtered-second-hop-pair-to-eqcolumn-base",
                    true,
                    "reduced left two-hop filtered second-hop pair shorthand back to the bounded filtered eqColumn base form while preserving null-extension/filter classification");
            case TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR -> MutationCaseModel.reductionStep(
                    "revert-filtered-second-hop-pair-to-eqcolumn-base",
                    true,
                    "reduced inner two-hop filtered second-hop pair shorthand back to the bounded filtered eqColumn base form while preserving classification");
        };
    }
}
