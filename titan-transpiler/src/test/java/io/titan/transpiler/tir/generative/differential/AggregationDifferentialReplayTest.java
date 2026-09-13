package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class AggregationDifferentialReplayTest {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedAggregationSeedFromSystemProperties() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                "titan.phaseb.aggregation.profile",
                "titan.phaseb.aggregation.seed",
                AggregationDifferentialProfile.AGGREGATION_BASIC.id()::equals,
                "Replay requested only when normalized or legacy Phase B replay properties are set for the aggregation profile");

        AggregationDifferentialProfile profile = AggregationDifferentialProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var run = new AggregationDifferentialPostgresSqlHarness().run(
                    connection,
                    switch (profile) {
                        case AGGREGATION_BASIC -> new AggregationDifferentialGenerator().generate(seed);
                    },
                    FixtureCatalog.accountsFixture(),
                    tempDir);
            assertNotNull(run.sql());
            assertNull(run.mismatchSummary(), "Aggregation replay mismatch artifacts=" + run.artifactDir());
        }
    }
}
