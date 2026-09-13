package io.titan.transpiler.tir.generative.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilerGenerativeReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedGenerativeSeedFromSystemProperties() throws Exception {
        String rawProfile = System.getProperty("titan.generative.profile");
        String rawSeed = System.getProperty("titan.generative.seed");
        Assumptions.assumeTrue(rawProfile != null && rawSeed != null,
                "Replay requested only when -Dtitan.generative.profile and -Dtitan.generative.seed are set");

        TranspilerGenerativeProfile profile = TranspilerGenerativeProfile.fromId(rawProfile);
        long seed = Long.parseUnsignedLong(rawSeed);
        TranspilerGenerativeHarness.HarnessResult result = new TranspilerGenerativeHarness().run(profile, seed, tempDir);

        assertNotNull(result.generatedCase());
        if (result.generatedCase().shouldFail()) {
            assertTrue(result.generatedSql().isEmpty());
            assertNotNull(result.errorMessage());
            for (String fragment : result.generatedCase().expectedFragments()) {
                assertTrue(result.errorMessage().contains(fragment),
                        "Expected diagnostic fragment '" + fragment + "' in replayed error. Artifacts=" + result.artifactDir());
            }
        } else {
            assertFalse(result.generatedSql().isEmpty(), "Expected generated SQL. Artifacts=" + result.artifactDir());
        }
    }
}
