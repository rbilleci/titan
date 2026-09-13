package io.titan.transpiler.tir.generative.mutation;

import java.util.List;

final class JoinMutationSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private JoinMutationSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(8500L, "Keeps the active-filter inner-join helper-form mutation replay in the routine Phase D guardrail set."),
                new CuratedSeed(8501L, "Keeps the paid-filter inner-join helper-form mutation replay in the routine Phase D guardrail set."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
