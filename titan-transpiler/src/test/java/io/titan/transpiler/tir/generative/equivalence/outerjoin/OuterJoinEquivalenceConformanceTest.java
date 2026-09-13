package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class OuterJoinEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void outerJoinFormsCuratedSeedsRemainEquivalent() throws Exception {
        OuterJoinEquivalenceHarness harness = new OuterJoinEquivalenceHarness();
        for (long seed : OuterJoinEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(OuterJoinEquivalenceProfile.OUTER_JOIN_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Outer-join equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
