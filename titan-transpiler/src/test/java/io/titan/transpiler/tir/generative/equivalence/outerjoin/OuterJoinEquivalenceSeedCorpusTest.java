package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class OuterJoinEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllOuterJoinFamilies() {
        OuterJoinEquivalenceGenerator generator = new OuterJoinEquivalenceGenerator();
        EnumSet<OuterJoinEquivalenceCaseModel.Family> covered = EnumSet.noneOf(OuterJoinEquivalenceCaseModel.Family.class);
        for (long seed : OuterJoinEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(OuterJoinEquivalenceCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (OuterJoinEquivalenceSeedCorpus.CuratedSeed seed : OuterJoinEquivalenceSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }

    @Test
    void promotedLeftTwoHopSeedCarriesOriginPhaseAndSeed() {
        var promoted = OuterJoinEquivalenceSeedCorpus.findBySeed(7602L);
        assertNotNull(promoted);
        assertEquals("Phase D", promoted.originPhase());
        assertEquals(8702L, promoted.originSeed());
    }
}
