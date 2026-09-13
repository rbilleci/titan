package io.titan.transpiler.tir.generative.differential;

import java.util.List;

final class AggregationDifferentialSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private AggregationDifferentialSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(2001L, "Baseline grouped-count coverage for the maintained aggregation corpus."),
                new CuratedSeed(2002L, "Grouped-sum coverage for the maintained aggregation corpus."),
                new CuratedSeed(2003L, "Grouped-average coverage for the maintained aggregation corpus."),
                new CuratedSeed(2004L, "Count plus having-threshold coverage used in routine replay validation."),
                new CuratedSeed(2005L, "Sum plus having-threshold coverage used in routine replay validation."),
                new CuratedSeed(2006L, "Average plus having-threshold coverage used in routine replay validation."),
                new CuratedSeed(2007L, "Final maintained aggregation seed closing kind/having coverage without widening the corpus unnecessarily."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
