package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class PredicateReorderEquivalenceReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedPredicateReorderEquivalenceSeed() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phasec.profile",
                "titan.phasec.seed",
                "titan.phaseb.metamorphic.profile",
                "titan.phaseb.metamorphic.seed",
                PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id()::equals,
                "Replay requested only when normalized Phase C or legacy predicate-reorder replay properties are set for the predicate-reordering profile");

        PredicateReorderEquivalenceProfile profile = PredicateReorderEquivalenceProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        var run = new PredicateReorderEquivalenceHarness().run(profile, seed, tempDir);
        assertNull(run.equivalenceMismatch(), "Predicate-reorder equivalence replay mismatch artifacts=" + run.artifactDir());
    }
}
