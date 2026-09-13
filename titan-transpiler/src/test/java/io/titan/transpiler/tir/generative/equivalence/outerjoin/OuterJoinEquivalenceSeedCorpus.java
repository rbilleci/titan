package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import java.util.List;

final class OuterJoinEquivalenceSeedCorpus {

    record CuratedSeed(long seed, String note, String originPhase, Long originSeed) {
    }

    private OuterJoinEquivalenceSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(7600L, "Keeps the baseline left-join null-extension helper-vs-explicit shape in the curated Phase C corpus.", null, null),
                new CuratedSeed(7601L, "Keeps the left-join duplicate/null-extension helper-vs-explicit shape in the curated Phase C corpus.", null, null),
                new CuratedSeed(7602L, "Promoted from the Phase D filtered second-hop left-join mutation slice so the durable truth now lives in Phase C equivalence.", "Phase D", 8702L));
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
