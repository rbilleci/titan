package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL leg of the aggregation differential conformance suite (plan 5.3): same curated seeds as
 * {@link AggregationDifferentialConformanceTest}, executed on the singleton MySQL container —
 * GROUP BY under ONLY_FULL_GROUP_BY, MySQL's DECIMAL SUM widening, and TINYINT boolean group
 * keys all run under generated load here. Capability-rejected cases become skips.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class AggregationDifferentialMySqlConformanceTest {

    static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void aggregationDifferentialCuratedSeedsMatchReferenceOnMySql() throws Exception {
        AggregationDifferentialMySqlHarness harness = new AggregationDifferentialMySqlHarness();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            for (long seed : AggregationDifferentialSeedCorpus.curatedSeeds()) {
                var run = harness.run(
                        connection,
                        new AggregationDifferentialGenerator().generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("seed-" + seed));
                if (run.capabilitySkipped()) {
                    assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("capability-skip.txt")),
                            "Missing capability-skip artifact for seed " + seed);
                    continue;
                }
                assertNull(run.mismatchSummary(), "MySQL mismatch for seed " + seed + " artifacts=" + run.artifactDir());
                assertNotNull(run.sql(), "Missing SQL for seed " + seed);
            }
        }
    }
}
