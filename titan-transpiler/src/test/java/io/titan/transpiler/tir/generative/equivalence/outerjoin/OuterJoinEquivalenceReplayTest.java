package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class OuterJoinEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedOuterJoinEquivalenceSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phasec.outer.profile",
                "titan.phasec.outer.seed",
                OuterJoinEquivalenceProfile.OUTER_JOIN_FORMS.id()::equals,
                "Replay requested only when normalized or legacy Phase C replay properties are set for the outer-join profile");

        OuterJoinEquivalenceProfile profile = OuterJoinEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new OuterJoinEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase C outer-join replay mismatch artifacts=" + run.artifactDir());
    }
}
