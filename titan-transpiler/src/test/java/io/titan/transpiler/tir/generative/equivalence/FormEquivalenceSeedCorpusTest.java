package io.titan.transpiler.tir.generative.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class FormEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllHelperExplicitFamilies() {
        FormEquivalenceGenerator generator = new FormEquivalenceGenerator();
        EnumSet<FormEquivalenceCaseModel.Family> covered = EnumSet.noneOf(FormEquivalenceCaseModel.Family.class);
        for (long seed : FormEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(FormEquivalenceCaseModel.Family.class), covered);
    }
}
