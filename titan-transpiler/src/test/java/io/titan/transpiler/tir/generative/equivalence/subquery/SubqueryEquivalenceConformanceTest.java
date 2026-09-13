package io.titan.transpiler.tir.generative.equivalence.subquery;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SubqueryEquivalenceConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void subqueryFormsCuratedSeedsRemainEquivalent() throws Exception {
        SubqueryEquivalenceHarness harness = new SubqueryEquivalenceHarness();
        for (long seed : SubqueryEquivalenceSeedCorpus.curatedSeeds()) {
            var run = harness.run(SubqueryEquivalenceProfile.SUBQUERY_FORMS, seed, tempDir);
            assertNull(run.mismatchSummary(), "Subquery-equivalence mismatch for seed " + seed + " artifacts=" + run.artifactDir());
        }
    }
}
