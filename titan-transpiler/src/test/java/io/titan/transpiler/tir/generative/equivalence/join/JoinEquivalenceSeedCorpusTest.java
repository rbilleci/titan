package io.titan.transpiler.tir.generative.equivalence.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class JoinEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllJoinFamilies() {
        JoinEquivalenceGenerator generator = new JoinEquivalenceGenerator();
        EnumSet<JoinEquivalenceCaseModel.Family> covered = EnumSet.noneOf(JoinEquivalenceCaseModel.Family.class);
        for (long seed : JoinEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(JoinEquivalenceCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (JoinEquivalenceSeedCorpus.CuratedSeed seed : JoinEquivalenceSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }

    @Test
    void promotedTwoHopSeedCarriesOriginPhaseAndSeed() {
        var promoted = JoinEquivalenceSeedCorpus.findBySeed(7502L);
        assertNotNull(promoted);
        assertEquals("Phase D", promoted.originPhase());
        assertEquals(8703L, promoted.originSeed());
    }
}
