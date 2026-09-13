package io.titan.transpiler.tir.generative.differential;

import java.util.List;

final class SubqueryDifferentialSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private SubqueryDifferentialSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(3000L, "Uncorrelated EXISTS baseline for the maintained subquery corpus."),
                new CuratedSeed(3001L, "Uncorrelated NOT EXISTS baseline for the maintained subquery corpus."),
                new CuratedSeed(3002L, "Correlated EXISTS baseline for the maintained subquery corpus."),
                new CuratedSeed(3003L, "Correlated NOT EXISTS baseline for the maintained subquery corpus."),
                new CuratedSeed(3004L, "Correlated scalar equality baseline retained for stable replay coverage."),
                new CuratedSeed(3005L, "Promoted correlated scalar NULL/absence `.isNull()` semantics from the Phase D audit into routine Phase B replay coverage."),
                new CuratedSeed(3006L, "Uncorrelated scalar lookup baseline retained for direct replayability."),
                new CuratedSeed(3007L, "Null-sensitive uncorrelated scalar subquery returning NULL."),
                new CuratedSeed(3008L, "CTE baseline retained for maintained subquery/CTE replay coverage."),
                new CuratedSeed(3009L, "Second-cycle EXISTS seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(3010L, "Second-cycle NOT EXISTS seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(3011L, "Second-cycle correlated EXISTS seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(3012L, "Second-cycle correlated NOT EXISTS seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(3013L, "Second-cycle correlated scalar equality seed kept to preserve corpus stability after modulo expansion."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
