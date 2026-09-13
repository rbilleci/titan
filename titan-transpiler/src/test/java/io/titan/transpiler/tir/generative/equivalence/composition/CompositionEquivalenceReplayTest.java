package io.titan.transpiler.tir.generative.equivalence.composition;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class CompositionEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedCteInlineViewSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phasec.composition.profile",
                "titan.phasec.composition.seed",
                CompositionEquivalenceProfile.CTE_INLINE_VIEW.id()::equals,
                "Replay requested only when normalized or legacy Phase C replay properties are set for the composition profile");

        CompositionEquivalenceProfile profile = CompositionEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new CompositionEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase C composition replay mismatch artifacts=" + run.artifactDir());
    }
}
