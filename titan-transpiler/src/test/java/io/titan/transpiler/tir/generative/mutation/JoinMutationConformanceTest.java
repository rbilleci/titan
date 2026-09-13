package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JoinMutationConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void joinMutationCuratedSeedsRemainStable() throws Exception {
        JoinMutationHarness harness = new JoinMutationHarness();
        for (long seed : JoinMutationSeedCorpus.curatedSeeds()) {
            var run = harness.run(JoinMutationProfile.JOIN_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Phase D join-mutation mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
