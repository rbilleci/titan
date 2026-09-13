package io.titan.transpiler.tir.generative.equivalence.subquery;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SubqueryEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedSubqueryEquivalenceSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phasec.subquery.profile",
                "titan.phasec.subquery.seed",
                SubqueryEquivalenceProfile.SUBQUERY_FORMS.id()::equals,
                "Replay requested only when normalized or legacy Phase C replay properties are set for the subquery profile");

        SubqueryEquivalenceProfile profile = SubqueryEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new SubqueryEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.mismatchSummary(), "Phase C subquery replay mismatch artifacts=" + run.artifactDir());
    }
}
