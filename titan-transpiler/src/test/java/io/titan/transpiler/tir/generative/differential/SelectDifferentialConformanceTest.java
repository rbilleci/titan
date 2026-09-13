package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void selectDifferentialCuratedSeedsMatchReferenceAndWriteArtifacts() throws Exception {
        SelectDifferentialHarness harness = new SelectDifferentialHarness();

        for (long seed : SelectDifferentialSeedCorpus.curatedBasicSelectSeeds()) {
            var run = harness.run(SelectDifferentialProfile.BASIC_SELECT, seed, tempDir);
            assertNull(run.mismatchSummary(), "Mismatch for seed " + seed + " artifacts=" + run.artifactDir());
            assertNotNull(run.sql(), "Missing SQL for seed " + seed);
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("case.json")), "Missing case artifact for seed " + seed);
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("postgres-select.sql")), "Missing SQL artifact for seed " + seed);
        }
    }
}
