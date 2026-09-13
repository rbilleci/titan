package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TwoHopJoinMutationSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllTwoHopJoinMutationFamilies() {
        TwoHopJoinMutationGenerator generator = new TwoHopJoinMutationGenerator();
        EnumSet<TwoHopJoinMutationCaseModel.Family> covered = EnumSet.noneOf(TwoHopJoinMutationCaseModel.Family.class);
        for (long seed : TwoHopJoinMutationSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(TwoHopJoinMutationCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (TwoHopJoinMutationSeedCorpus.CuratedSeed seed : TwoHopJoinMutationSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
