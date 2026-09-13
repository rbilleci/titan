package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class BasicMutationReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedPhaseDSeedFromSystemProperties() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phased.profile",
                "titan.phased.seed",
                "titan.phased.profile",
                "titan.phased.seed",
                MutationProfile.BASIC.id()::equals,
                "Replay requested only when normalized Phase D replay properties are set for the basic mutation profile");
        String rawMutator = ReplayPropertySupport.resolveOptional("titan.phased.mutator", "titan.phased.mutator");

        MutationProfile profile = MutationProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        MutationCaseModel.MutatorId mutator = rawMutator == null
                ? null
                : MutationCaseModel.MutatorId.valueOf(rawMutator.replace('-', '_').toUpperCase());
        var run = new BasicMutationHarness().run(profile, seed, mutator, tempDir);

        assertNotNull(run.artifactDir());
        assertNotNull(run.selectRun().sql());
        assertNotNull(run.mutationCase());
        assertEquals(MutationCaseModel.MutationClassification.STABLE_PASS, run.mutationCase().classification());
        assertNull(run.selectRun().mismatchSummary(), "Replay mismatch artifacts=" + run.artifactDir());
    }
}
