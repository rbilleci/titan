package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class OuterJoinMutationConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void outerJoinMutationCuratedSeedsRemainStable() throws Exception {
        OuterJoinMutationHarness harness = new OuterJoinMutationHarness();
        for (long seed : OuterJoinMutationSeedCorpus.curatedSeeds()) {
            var run = harness.run(OuterJoinMutationProfile.OUTER_JOIN_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Phase D outer-join mutation mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
