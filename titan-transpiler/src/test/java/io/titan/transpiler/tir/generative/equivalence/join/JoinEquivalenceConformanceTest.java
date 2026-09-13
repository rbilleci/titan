package io.titan.transpiler.tir.generative.equivalence.join;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JoinEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void joinFormsCuratedSeedsRemainEquivalent() throws Exception {
        JoinEquivalenceHarness harness = new JoinEquivalenceHarness();
        for (long seed : JoinEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(JoinEquivalenceProfile.JOIN_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Join-equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
