package io.titan.transpiler.tir.generative.equivalence;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class FormEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void helperAndExplicitCuratedSeedsRemainEquivalent() throws Exception {
        FormEquivalenceHarness harness = new FormEquivalenceHarness();
        for (long seed : FormEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(FormEquivalenceProfile.HELPER_EXPLICIT, seed, tempDir);
            assertNull(run.mismatchSummary(), "Form-equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
