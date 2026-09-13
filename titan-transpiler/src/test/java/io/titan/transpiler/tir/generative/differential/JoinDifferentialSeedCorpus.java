package io.titan.transpiler.tir.generative.differential;

import java.util.List;

final class JoinDifferentialSeedCorpus {

    record CuratedSeed(long seed, String note) {
    }

    private JoinDifferentialSeedCorpus() {
    }

    static List<CuratedSeed> curatedSeedEntries() {
        return List.of(
                new CuratedSeed(6000L, "Inner-join active-filter baseline for the maintained join corpus."),
                new CuratedSeed(6001L, "Inner-join paid-filter baseline for the maintained join corpus."),
                new CuratedSeed(6002L, "One-hop duplicate row multiplication baseline."),
                new CuratedSeed(6003L, "Left-join null-extension baseline."),
                new CuratedSeed(6004L, "Left-join ON-filter null-preservation baseline."),
                new CuratedSeed(6005L, "Left-join WHERE-filter null-collapse semantic guardrail."),
                new CuratedSeed(6006L, "Two-hop baseline chain retained for stable replay coverage."),
                new CuratedSeed(6007L, "Two-hop duplicate second-hop multiplication semantic guardrail."),
                new CuratedSeed(6008L, "Two-hop second-hop ON-filter semantic guardrail."),
                new CuratedSeed(6009L, "Left-join duplicate/null-extension interaction baseline."),
                new CuratedSeed(6010L, "Left two-hop null-extension baseline."),
                new CuratedSeed(6011L, "Left two-hop second-hop ON-filter null-preservation guardrail."),
                new CuratedSeed(6012L, "Left two-hop second-hop WHERE-filter null-collapse semantic guardrail."),
                new CuratedSeed(6013L, "Second-cycle inner-join active seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(6014L, "Second-cycle inner-join paid seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(6015L, "Second-cycle duplicate one-hop seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(6016L, "Second-cycle left-null-extension seed kept to preserve corpus stability after modulo expansion."),
                new CuratedSeed(6017L, "Second-cycle left-join ON-filter seed kept to preserve corpus stability after modulo expansion."));
    }

    static List<Long> curatedSeeds() {
        return curatedSeedEntries().stream().map(CuratedSeed::seed).toList();
    }
}
