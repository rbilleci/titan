package io.titan.transpiler.tir.generative.equivalence.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ProjectionEquivalenceSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllProjectionFamilies() {
        ProjectionEquivalenceGenerator generator = new ProjectionEquivalenceGenerator();
        EnumSet<ProjectionEquivalenceCaseModel.Family> covered = EnumSet.noneOf(ProjectionEquivalenceCaseModel.Family.class);
        for (long seed : ProjectionEquivalenceSeedCorpus.curatedSeeds()) {
            covered.add(generator.generate(seed).family());
        }
        assertEquals(EnumSet.allOf(ProjectionEquivalenceCaseModel.Family.class), covered);
    }
}
