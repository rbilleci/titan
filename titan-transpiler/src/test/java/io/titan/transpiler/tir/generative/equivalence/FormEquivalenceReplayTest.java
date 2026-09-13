package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class FormEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedHelperExplicitSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phasec.forms.profile",
                "titan.phasec.forms.seed",
                FormEquivalenceProfile.HELPER_EXPLICIT.id()::equals,
                "Replay requested only when normalized or legacy Phase C replay properties are set for the form-equivalence profile");

        FormEquivalenceProfile profile = FormEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new FormEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase C form-equivalence replay mismatch artifacts=" + run.artifactDir());
    }
}
