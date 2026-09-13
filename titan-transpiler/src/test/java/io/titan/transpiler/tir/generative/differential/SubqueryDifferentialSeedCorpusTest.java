package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SubqueryDifferentialSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllSubqueryFamilies() {
        SubqueryDifferentialGenerator generator = new SubqueryDifferentialGenerator();
        EnumSet<SubqueryDifferentialCaseModel.Family> covered = EnumSet.noneOf(SubqueryDifferentialCaseModel.Family.class);
        for (long seed : SubqueryDifferentialSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(SubqueryDifferentialCaseModel.Family.class), covered);
    }

    @Test
    void curatedSeedsCarryIntentNotes() {
        for (SubqueryDifferentialSeedCorpus.CuratedSeed seed : SubqueryDifferentialSeedCorpus.curatedSeedEntries()) {
            assertFalse(seed.note().isBlank(), "Seed note must not be blank for " + seed.seed());
        }
    }
}
