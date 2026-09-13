package io.titan.transpiler.tir.generative.equivalence;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class PredicateReorderEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void predicateReorderCuratedSeedsRemainEquivalent() throws Exception {
        PredicateReorderEquivalenceHarness harness = new PredicateReorderEquivalenceHarness();
        for (long seed : PredicateReorderEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(PredicateReorderEquivalenceProfile.PREDICATE_REORDER, seed, tempDir);
            assertNull(run.equivalenceMismatch(), "Predicate-reorder equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
