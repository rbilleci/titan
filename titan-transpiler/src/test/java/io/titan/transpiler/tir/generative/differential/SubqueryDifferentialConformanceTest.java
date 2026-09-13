package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SubqueryDifferentialConformanceTest {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    @Test
    void curatedSubquerySeedsMatchReference() throws Exception {
        SubqueryDifferentialGenerator generator = new SubqueryDifferentialGenerator();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            for (long seed : SubqueryDifferentialSeedCorpus.curatedSeeds()) {
                var run = new SubqueryDifferentialPostgresSqlHarness().run(
                        connection,
                        generator.generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("seed-" + seed));
                assertNull(run.mismatchSummary(), "Mismatch for seed " + seed + " artifacts=" + run.artifactDir());
            }
        }
    }
}
