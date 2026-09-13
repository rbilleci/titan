package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialSeedCorpusTest {

    @TempDir
    Path tempDir;

    @Test
    void curatedSelectDifferentialSeedCorpusReplaysWithRationaleAndArtifacts() throws Exception {
        SelectDifferentialHarness harness = new SelectDifferentialHarness();

        for (SelectDifferentialCuratedSeed curated : SelectDifferentialSeedCorpus.curatedCorpus()) {
            var run = harness.run(curated.profile(), curated.seed(), tempDir);
            assertNotNull(curated.rationale(), "Missing rationale for curated seed " + curated);
            assertFalse(curated.rationale().isBlank(), "Blank rationale for curated seed " + curated);
            assertNotNull(run.artifactDir(), "Missing artifact dir for curated seed " + curated);
            assertNull(run.mismatchSummary(), "Mismatch for curated seed " + curated + " artifacts=" + run.artifactDir());
        }
    }
}
