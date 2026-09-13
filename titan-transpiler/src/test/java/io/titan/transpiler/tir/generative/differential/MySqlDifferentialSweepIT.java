package io.titan.transpiler.tir.generative.differential;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.JoinMySqlSqlHarness;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

/**
 * Bulk sweep driver for the MySQL differential leg (plan 5.3): runs a configurable number of
 * generated seeds per differential profile in a single JVM against the singleton MySQL
 * container — the in-process equivalent of the CI replay loops, sized by the same sweep-tier
 * counts (fast=10, medium=50, large=100 per profile):
 *
 * <pre>{@code
 * ./gradlew :titan-transpiler:integrationTest \
 *     --tests io.titan.transpiler.tir.generative.differential.MySqlDifferentialSweepIT \
 *     -Dtitan.phaseb.mysql.sweep.validPerProfile=50
 * }</pre>
 *
 * Every divergence is collected (the sweep does not stop at the first) and reported with its
 * artifact directory so a failing seed can be replayed via the per-family
 * {@code *DifferentialMySqlReplayTest}. Capability-rejected cases (TITAN-E001) count as skips.
 */
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class MySqlDifferentialSweepIT {

    static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    @TempDir
    Path tempDir;

    @Test
    void mySqlLegMatchesJavaReferenceAcrossGeneratedSweep() throws Exception {
        int validPerProfile = Integer.getInteger("titan.phaseb.mysql.sweep.validPerProfile", 10);
        long baseSeed = Long.getLong("titan.phaseb.mysql.sweep.baseSeed", 30_000L);

        List<String> divergences = new ArrayList<>();
        int skips = 0;

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            SelectDifferentialGenerator selectGenerator = new SelectDifferentialGenerator();
            var selectHarness = new io.titan.transpiler.tir.generative.shared.SelectMySqlSqlHarness();
            for (int i = 0; i < validPerProfile; i++) {
                long seed = baseSeed + i;
                var run = selectHarness.runSelectCase(
                        connection,
                        selectGenerator.generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("select-seed-" + seed));
                if (run.capabilitySkipped()) {
                    skips++;
                } else if (run.mismatchSummary() != null) {
                    divergences.add("select seed=" + seed + ": " + run.mismatchSummary()
                            + " artifacts=" + run.artifactDir());
                }
            }

            AggregationDifferentialGenerator aggregationGenerator = new AggregationDifferentialGenerator();
            var aggregationHarness = new AggregationDifferentialMySqlHarness();
            for (int i = 0; i < validPerProfile; i++) {
                long seed = baseSeed + 10_000L + i;
                var run = aggregationHarness.run(
                        connection,
                        aggregationGenerator.generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("aggregation-seed-" + seed));
                if (run.capabilitySkipped()) {
                    skips++;
                } else if (run.mismatchSummary() != null) {
                    divergences.add("aggregation seed=" + seed + ": " + run.mismatchSummary()
                            + " artifacts=" + run.artifactDir());
                }
            }

            SubqueryDifferentialGenerator subqueryGenerator = new SubqueryDifferentialGenerator();
            var subqueryHarness = new SubqueryDifferentialMySqlHarness();
            for (int i = 0; i < validPerProfile; i++) {
                long seed = baseSeed + 20_000L + i;
                var run = subqueryHarness.run(
                        connection,
                        subqueryGenerator.generate(seed),
                        FixtureCatalog.accountsFixture(),
                        tempDir.resolve("subquery-seed-" + seed));
                if (run.capabilitySkipped()) {
                    skips++;
                } else if (run.mismatchSummary() != null) {
                    divergences.add("subquery seed=" + seed + ": " + run.mismatchSummary()
                            + " artifacts=" + run.artifactDir());
                }
            }

            JoinDifferentialGenerator joinGenerator = new JoinDifferentialGenerator();
            var joinHarness = new JoinMySqlSqlHarness();
            for (int i = 0; i < validPerProfile; i++) {
                long seed = baseSeed + 30_000L + i;
                var run = joinHarness.runJoinCase(
                        connection,
                        joinGenerator.generate(seed),
                        tempDir.resolve("join-seed-" + seed));
                if (run.capabilitySkipped()) {
                    skips++;
                } else if (run.mismatchSummary() != null) {
                    divergences.add("join seed=" + seed + ": " + run.mismatchSummary()
                            + " artifacts=" + run.artifactDir());
                }
            }
        }

        System.out.println("MySQL differential sweep: " + (4L * validPerProfile) + " cases, "
                + skips + " capability skips, " + divergences.size() + " divergences");
        assertTrue(divergences.isEmpty(),
                "MySQL differential sweep found " + divergences.size() + " divergence(s):\n"
                        + String.join("\n", divergences));
    }
}
