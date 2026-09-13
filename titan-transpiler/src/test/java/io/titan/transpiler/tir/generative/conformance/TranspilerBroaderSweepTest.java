package io.titan.transpiler.tir.generative.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilerBroaderSweepTest {

    @TempDir
    Path tempDir;

    @Test
    void runsConfiguredBroaderSeedSweep() throws Exception {
        int validSeedsPerProfile = Integer.getInteger("titan.generative.sweep.validPerProfile", 10);
        int invalidSeedsPerProfile = Integer.getInteger("titan.generative.sweep.invalidPerProfile", 8);
        long baseSeed = Long.getLong("titan.generative.sweep.baseSeed", 10_000L);

        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        runSweep(harness, TranspilerGenerativeProfile.BASIC_SELECT, baseSeed, validSeedsPerProfile);
        runSweep(harness, TranspilerGenerativeProfile.SUBQUERY_CTE, baseSeed + 10_000L, validSeedsPerProfile);
        runSweep(harness, TranspilerGenerativeProfile.AGGREGATION, baseSeed + 20_000L, validSeedsPerProfile);
        runSweep(harness, TranspilerGenerativeProfile.JOIN_COMPOSITION, baseSeed + 30_000L, validSeedsPerProfile);
        runSweep(harness, TranspilerGenerativeProfile.INVALID, baseSeed + 40_000L, invalidSeedsPerProfile);
        runSweep(harness, TranspilerGenerativeProfile.INVALID_COMPOSITION, baseSeed + 50_000L, invalidSeedsPerProfile);
    }

    private void runSweep(TranspilerGenerativeHarness harness, TranspilerGenerativeProfile profile, long startSeed, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            long seed = startSeed + i;
            var result = harness.run(profile, seed, tempDir);
            assertNotNull(result.generatedCase(), "Missing generated case for profile=" + profile + ", seed=" + seed);
            if (result.generatedCase().shouldFail()) {
                assertTrue(result.generatedSql().isEmpty(), "Expected failure for profile=" + profile + ", seed=" + seed + ", artifacts=" + result.artifactDir());
                assertNotNull(result.errorMessage(), "Expected error message for profile=" + profile + ", seed=" + seed + ", artifacts=" + result.artifactDir());
                for (String fragment : result.generatedCase().expectedFragments()) {
                    assertTrue(result.errorMessage().contains(fragment), "Missing fragment '" + fragment + "' for profile=" + profile + ", seed=" + seed + ", artifacts=" + result.artifactDir());
                }
            } else {
                assertFalse(result.generatedSql().isEmpty(), "Expected generated SQL for profile=" + profile + ", seed=" + seed
                        + ", family=" + result.generatedCase().family()
                        + ", artifacts=" + result.artifactDir()
                        + ", error=" + result.errorMessage());
            }
        }
    }
}
