package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class BasicMutationConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void curatedMutationSeedsRemainStable() throws Exception {
        BasicMutationHarness harness = new BasicMutationHarness();
        for (BasicMutationSeedCorpus.CuratedMutationSeed seedCase : BasicMutationSeedCorpus.curatedSeeds()) {
            var run = harness.run(MutationProfile.BASIC, seedCase.seed(), seedCase.mutatorId(), tempDir);
            assertEquals(MutationCaseModel.MutationClassification.STABLE_PASS, run.mutationCase().classification(),
                    "Unexpected classification for seed " + seedCase.seed() + " mutator=" + seedCase.mutatorId().id());
            assertNull(run.selectRun().mismatchSummary(),
                    "Phase D mismatch for seed " + seedCase.seed() + " mutator=" + seedCase.mutatorId().id()
                            + " artifacts=" + run.artifactDir());
        }
    }
}
