package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class OuterJoinMutationSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllOuterJoinMutationFamilies() {
        OuterJoinMutationGenerator generator = new OuterJoinMutationGenerator();
        EnumSet<OuterJoinMutationCaseModel.Family> covered = EnumSet.noneOf(OuterJoinMutationCaseModel.Family.class);
        for (long seed : OuterJoinMutationSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(OuterJoinMutationCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (OuterJoinMutationSeedCorpus.CuratedSeed seed : OuterJoinMutationSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
