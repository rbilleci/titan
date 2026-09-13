package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class TwoHopJoinMutationConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void twoHopJoinMutationCuratedSeedsRemainStable() throws Exception {
        TwoHopJoinMutationHarness harness = new TwoHopJoinMutationHarness();
        for (long seed : TwoHopJoinMutationSeedCorpus.curatedSeeds()) {
            var run = harness.run(TwoHopJoinMutationProfile.TWO_HOP_JOIN_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Phase D two-hop join mutation mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
