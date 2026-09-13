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
 * MySQL twin of {@link SubqueryDifferentialReplayTest} (plan 5.3); same replay property
 * protocol, the leg is selected by test class.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SubqueryDifferentialMySqlReplayTest {

    static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void replaysRequestedSubquerySeedOnMySqlFromSystemProperties() throws Exception {
        ReplayPropertySupport.ReplayRequest request = ReplayPropertySupport.resolve(
                "titan.phaseb.profile",
                "titan.phaseb.seed",
                "titan.phaseb.subquery.profile",
                "titan.phaseb.subquery.seed",
                SubqueryDifferentialProfile.SUBQUERY_BASIC.id()::equals,
                "Replay requested only when normalized or legacy Phase B replay properties are set for the subquery profile");

        SubqueryDifferentialProfile profile = SubqueryDifferentialProfile.fromId(request.profile());
        long seed = Long.parseUnsignedLong(request.seed());
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            var run = new SubqueryDifferentialMySqlHarness().run(
                    connection,
                    switch (profile) {
                        case SUBQUERY_BASIC -> new SubqueryDifferentialGenerator().generate(seed);
                    },
                    FixtureCatalog.accountsFixture(),
                    tempDir);
            assertNotNull(run.artifactDir());
            if (run.capabilitySkipped()) {
                return;
            }
            assertNotNull(run.sql());
            assertNull(run.mismatchSummary(), "MySQL subquery replay mismatch artifacts=" + run.artifactDir());
        }
    }
}
