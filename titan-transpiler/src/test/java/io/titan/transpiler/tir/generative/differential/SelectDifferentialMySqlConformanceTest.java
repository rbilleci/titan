package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MySQL leg of the select differential conformance suite (plan 5.3): the same curated seed
 * corpus the PostgreSQL leg runs ({@link SelectDifferentialConformanceTest}), transpiled for
 * MySQL and executed on the singleton MySQL container against the same Java reference
 * evaluation. Capability-rejected cases (TITAN-E001) are recorded as skips, never mismatches.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialMySqlConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void selectDifferentialCuratedSeedsMatchReferenceOnMySqlAndWriteArtifacts() throws Exception {
        SelectDifferentialMySqlHarness harness = new SelectDifferentialMySqlHarness();

        for (long seed : SelectDifferentialSeedCorpus.curatedBasicSelectSeeds()) {
            var run = harness.run(SelectDifferentialProfile.BASIC_SELECT, seed, tempDir);
            if (run.capabilitySkipped()) {
                assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("capability-skip.txt")),
                        "Missing capability-skip artifact for seed " + seed);
                continue;
            }
            assertNull(run.mismatchSummary(), "MySQL mismatch for seed " + seed + " artifacts=" + run.artifactDir());
            assertNotNull(run.sql(), "Missing SQL for seed " + seed);
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("case.json")), "Missing case artifact for seed " + seed);
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("mysql-select.sql")), "Missing SQL artifact for seed " + seed);
        }
    }
}
