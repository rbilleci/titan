package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AggregationDifferentialSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllAggregationKinds() {
        AggregationDifferentialGenerator generator = new AggregationDifferentialGenerator();
        EnumSet<AggregationDifferentialCaseModel.AggregateKind> covered = EnumSet.noneOf(AggregationDifferentialCaseModel.AggregateKind.class);
        for (long seed : AggregationDifferentialSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).aggregateKind());
        }
        assertEquals(EnumSet.allOf(AggregationDifferentialCaseModel.AggregateKind.class), covered);
    }

    @Test
    void curatedSeedsCoverAllHavingKinds() {
        AggregationDifferentialGenerator generator = new AggregationDifferentialGenerator();
        EnumSet<AggregationDifferentialCaseModel.HavingKind> covered = EnumSet.noneOf(AggregationDifferentialCaseModel.HavingKind.class);
        for (long seed : AggregationDifferentialSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).havingKind());
        }
        assertEquals(EnumSet.allOf(AggregationDifferentialCaseModel.HavingKind.class), covered);
    }

    @Test
    void curatedSeedsCarryIntentNotes() {
        for (AggregationDifferentialSeedCorpus.CuratedSeed seed : AggregationDifferentialSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
