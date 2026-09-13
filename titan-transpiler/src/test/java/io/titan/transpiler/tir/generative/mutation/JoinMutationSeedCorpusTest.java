package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class JoinMutationSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllJoinMutationFamilies() {
        JoinMutationGenerator generator = new JoinMutationGenerator();
        EnumSet<JoinMutationCaseModel.Family> covered = EnumSet.noneOf(JoinMutationCaseModel.Family.class);
        for (long seed : JoinMutationSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(JoinMutationCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (JoinMutationSeedCorpus.CuratedSeed seed : JoinMutationSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
