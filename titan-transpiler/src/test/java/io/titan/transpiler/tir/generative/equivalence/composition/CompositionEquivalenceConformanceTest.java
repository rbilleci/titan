package io.titan.transpiler.tir.generative.equivalence.composition;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class CompositionEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void namedCteAndInlineViewCuratedSeedsRemainEquivalent() throws Exception {
        CompositionEquivalenceHarness harness = new CompositionEquivalenceHarness();
        for (long seed : CompositionEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(CompositionEquivalenceProfile.CTE_INLINE_VIEW, seed, tempDir);
            assertNull(run.mismatchSummary(), "Composition-equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
