package io.titan.transpiler.tir.generative.equivalence.subquery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SubqueryEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllSubqueryFamilies() {
        SubqueryEquivalenceGenerator generator = new SubqueryEquivalenceGenerator();
        EnumSet<SubqueryEquivalenceCaseModel.Family> covered = EnumSet.noneOf(SubqueryEquivalenceCaseModel.Family.class);
        for (long seed : SubqueryEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(SubqueryEquivalenceCaseModel.Family.class), covered);
    }
}
