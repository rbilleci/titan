package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MySQL twin of {@link SelectDifferentialReplayTest} (plan 5.3): replays one generated seed on
 * the MySQL leg. Uses the same {@code titan.phaseb.profile}/{@code titan.phaseb.seed} replay
 * properties as the PostgreSQL twin — the leg is chosen by test-class selection, the seed
 * protocol stays identical so sweep drivers can run both legs over the same seed list.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialMySqlReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedSelectDifferentialSeedOnMySqlFromSystemProperties() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                SelectDifferentialProfile.BASIC_SELECT.id()::equals,
                "Replay requested only when normalized or legacy Phase B replay properties are set for the differential profile");

        SelectDifferentialProfile profile = SelectDifferentialProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new SelectDifferentialMySqlHarness().run(profile, seed, tempDir);

        assertNotNull(run.artifactDir());
        if (run.capabilitySkipped()) {
            return;
        }
        assertNotNull(run.sql());
        assertNull(run.mismatchSummary(), "MySQL replay mismatch artifacts=" + run.artifactDir());
    }
}
