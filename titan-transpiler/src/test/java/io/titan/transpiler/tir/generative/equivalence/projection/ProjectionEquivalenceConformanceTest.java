package io.titan.transpiler.tir.generative.equivalence.projection;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class ProjectionEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void projectionReorderCuratedSeedsRemainEquivalent() throws Exception {
        ProjectionEquivalenceHarness harness = new ProjectionEquivalenceHarness();
        for (long seed : ProjectionEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(ProjectionEquivalenceProfile.PROJECTION_REORDER, seed, tempDir);
            assertNull(run.mismatchSummary(), "Projection-equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
