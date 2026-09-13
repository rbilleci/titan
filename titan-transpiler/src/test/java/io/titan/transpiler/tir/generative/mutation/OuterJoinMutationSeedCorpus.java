package io.titan.transpiler.tir.generative.mutation;

import java.util.List;

final class OuterJoinMutationSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private OuterJoinMutationSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(8600L, "Keeps the baseline left-join null-extension helper-form mutation replay in the routine Phase D guardrail set."),
                new CuratedSeed(8601L, "Keeps the duplicate/null-extension left-join helper-form mutation replay in the routine Phase D guardrail set."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
