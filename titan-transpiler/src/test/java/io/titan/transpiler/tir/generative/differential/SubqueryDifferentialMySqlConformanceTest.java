package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL leg of the subquery differential conformance suite (plan 5.3): same curated seeds as
 * {@link SubqueryDifferentialConformanceTest}, executed on the singleton MySQL container.
 * Capability-rejected cases become skips.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SubqueryDifferentialMySqlConformanceTest {

    static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void curatedSubquerySeedsMatchReferenceOnMySql() throws Exception {
        SubqueryDifferentialGenerator generator = new SubqueryDifferentialGenerator();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            for (long seed : SubqueryDifferentialSeedCorpus.curatedSeeds()) {
                var run = new SubqueryDifferentialMySqlHarness().run(
                        connection,
                        generator.generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("seed-" + seed));
                if (run.capabilitySkipped()) {
                    assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("capability-skip.txt")),
                            "Missing capability-skip artifact for seed " + seed);
                    continue;
                }
                assertNull(run.mismatchSummary(), "MySQL mismatch for seed " + seed + " artifacts=" + run.artifactDir());
            }
        }
    }
}
