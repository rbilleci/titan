package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedSelectDifferentialSeedFromSystemProperties() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                SelectDifferentialProfile.BASIC_SELECT.id()::equals,
                "Replay requested only when normalized or legacy Phase B replay properties are set for the differential profile");

        SelectDifferentialProfile profile = SelectDifferentialProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new SelectDifferentialHarness().run(profile, seed, tempDir);

        assertNotNull(run.artifactDir());
        assertNotNull(run.sql());
        assertNull(run.mismatchSummary(), "Replay mismatch artifacts=" + run.artifactDir());
    }
}
