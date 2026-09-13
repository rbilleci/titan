package io.titan.transpiler.tir.generative.mutation;

import java.util.List;

final class TwoHopJoinMutationSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private TwoHopJoinMutationSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(8700L, "Keeps the baseline inner two-hop helper-form mutation replay in the routine Phase D guardrail set."),
                new CuratedSeed(8701L, "Keeps the baseline left two-hop null-extension helper-form mutation replay in the routine Phase D guardrail set."),
                new CuratedSeed(8702L, "Retained as provenance for the filtered second-hop left-join helper-form surface even after promotion into Phase C."),
                new CuratedSeed(8703L, "Retained as provenance for the filtered second-hop inner two-hop helper-form surface even after promotion into Phase C."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
