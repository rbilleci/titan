package io.titan.transpiler.tir.generative.equivalence.join;

import java.util.List;

final class JoinEquivalenceSeedCorpus {

    record CuratedSeed(long seed, String note, String originPhase, Long originSeed) {
    }

    private JoinEquivalenceSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(7500L, "Keeps the baseline inner-join helper-vs-explicit active-filter shape in the curated Phase C corpus.", null, null),
                new CuratedSeed(7501L, "Keeps the inner-join paid-filter helper-vs-explicit shape in the curated Phase C corpus.", null, null),
                new CuratedSeed(7502L, "Promoted from Phase D two-hop filtered second-hop helper-form work so the durable truth now lives in Phase C equivalence.", "Phase D", 8703L));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }

    static CuratedSeed findBySeed(long seed) {
        return curatedSeedEntries().stream()
                .filter(entry -> entry.seed() == seed)
                .findFirst()
                .orElse(null);
    }
}
