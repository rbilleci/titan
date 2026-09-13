package io.titan.transpiler.tir.generative.equivalence.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CompositionEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllCompositionFamilies() {
        CompositionEquivalenceGenerator generator = new CompositionEquivalenceGenerator();
        EnumSet<CompositionEquivalenceCaseModel.Family> covered = EnumSet.noneOf(CompositionEquivalenceCaseModel.Family.class);
        for (long seed : CompositionEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(CompositionEquivalenceCaseModel.Family.class), covered);
    }
}
