package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class JoinDifferentialSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllJoinFamilies() {
        JoinDifferentialGenerator generator = new JoinDifferentialGenerator();
        EnumSet<JoinCaseModel.Family> covered = EnumSet.noneOf(JoinCaseModel.Family.class);
        for (long seed : JoinDifferentialSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(JoinCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryIntentNotes() {
        for (JoinDifferentialSeedCorpus.CuratedSeed seed : JoinDifferentialSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
