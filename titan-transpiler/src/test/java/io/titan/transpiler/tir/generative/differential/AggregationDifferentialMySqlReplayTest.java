package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.ReplayPropertySupport;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL twin of {@link AggregationDifferentialReplayTest} (plan 5.3); same replay property
 * protocol, the leg is selected by test class.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class AggregationDifferentialMySqlReplayTest {

    static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedAggregationSeedOnMySqlFromSystemProperties() throws Exception {
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
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            var run = new AggregationDifferentialMySqlHarness().run(
                    connection,
                    switch (profile) {
                        case AGGREGATION_BASIC -> new AggregationDifferentialGenerator().generate(seed);
                    },
                    FixtureCatalog.accountsFixture(),
                    tempDir);
            assertNotNull(run.artifactDir());
            if (run.capabilitySkipped()) {
                return;
            }
            assertNotNull(run.sql());
            assertNull(run.mismatchSummary(), "MySQL aggregation replay mismatch artifacts=" + run.artifactDir());
        }
    }
}
