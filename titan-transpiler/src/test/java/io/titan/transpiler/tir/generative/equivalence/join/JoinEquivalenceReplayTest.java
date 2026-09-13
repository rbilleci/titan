package io.titan.transpiler.tir.generative.equivalence.join;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JoinEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedJoinEquivalenceSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phasec.join.profile",
                "titan.phasec.join.seed",
                JoinEquivalenceProfile.JOIN_FORMS.id()::equals,
                "Replay requested only when normalized or legacy Phase C replay properties are set for the join profile");

        JoinEquivalenceProfile profile = JoinEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new JoinEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase C join-equivalence replay mismatch artifacts=" + run.artifactDir());
    }
}
