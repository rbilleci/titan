package io.titan.transpiler.tir.generative.mutation;

import java.util.List;

final class BasicMutationSeedCorpus {

    record CuratedMutationSeed(long seed, MutationCaseModel.MutatorId mutatorId, String note) {
    }

    private BasicMutationSeedCorpus() {
    }

    static List<CuratedMutationSeed> curatedSeeds() {
        return List.of(
                new CuratedMutationSeed(8100L, MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                        "Keeps the safe-conjunct mutation replay in the routine Phase D guardrail set because it exercises stable predicate extension."),
                new CuratedMutationSeed(8101L, MutationCaseModel.MutatorId.ADD_SAFE_DISJUNCT,
                        "Keeps the safe-disjunct mutation replay in the routine Phase D guardrail set because it exercises stable predicate broadening."),
                new CuratedMutationSeed(8102L, MutationCaseModel.MutatorId.DUPLICATE_PROJECTION,
                        "Keeps duplicate-projection replay in the routine Phase D guardrail set because silent projection-shape drift is easy to miss elsewhere."));
    }
}
