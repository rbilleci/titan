package io.titan.transpiler.tir.generative.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class PredicateReorderEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllPredicateReorderFamilies() {
        PredicateReorderEquivalenceGenerator generator = new PredicateReorderEquivalenceGenerator();
        EnumSet<PredicateReorderEquivalenceCaseModel.Family> covered = EnumSet.noneOf(PredicateReorderEquivalenceCaseModel.Family.class);
        for (long seed : PredicateReorderEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(PredicateReorderEquivalenceCaseModel.Family.class), covered);
    }
}
