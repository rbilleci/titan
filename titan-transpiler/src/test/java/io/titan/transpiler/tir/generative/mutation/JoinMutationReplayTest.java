package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JoinMutationReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedJoinMutationSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phased.profile",
                "titan.phased.seed",
                "titan.phased.join.profile",
                "titan.phased.join.seed",
                JoinMutationProfile.JOIN_FORMS.id()::equals,
                "Replay requested only when normalized or legacy Phase D replay properties are set for the join-mutation profile");

        JoinMutationProfile profile = JoinMutationProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new JoinMutationHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase D join-mutation replay mismatch artifacts=" + run.artifactDir());
    }
}
