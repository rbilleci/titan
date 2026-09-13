package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class AggregationDifferentialPostgresSqlPathIT {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    @Test
    void postgresAggregationSqlPathMatchesReferenceForCountByPlanCode() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "plan_code",
                "row_count");
        assertAggregationMatches(aggregationCase);
    }

    @Test
    void postgresAggregationSqlPathMatchesReferenceForSumByActive() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                AggregationDifferentialCaseModel.AggregateKind.SUM_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "active",
                "login_sum");
        assertAggregationMatches(aggregationCase);
    }

    @Test
    void postgresAggregationSqlPathMatchesReferenceForCountHavingCase() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                AggregationDifferentialCaseModel.HavingKind.COUNT_GT_ONE,
                "plan_code",
                "row_count");
        assertAggregationMatches(aggregationCase);
    }

    @Test
    void postgresAggregationSqlPathMatchesReferenceForSumHavingCase() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                AggregationDifferentialCaseModel.AggregateKind.SUM_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.SUM_GT_TEN,
                "active",
                "login_sum");
        assertAggregationMatches(aggregationCase);
    }

    @Test
    void postgresAggregationSqlPathMatchesReferenceForMinGroupedAggregate() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.MIN_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "plan_code",
                "login_min");
        assertAggregationMatches(aggregationCase);
    }

    @Test
    void postgresAggregationSqlPathMatchesReferenceForMaxGroupedAggregate() throws Exception {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                AggregationDifferentialCaseModel.AggregateKind.MAX_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "active",
                "login_max");
        assertAggregationMatches(aggregationCase);
    }

    private void assertAggregationMatches(AggregationDifferentialCaseModel.AggregationCase aggregationCase) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var run = new AggregationDifferentialPostgresSqlHarness().run(
                    connection,
                    aggregationCase,
                    FixtureCatalog.accountsFixture(),
                    tempDir);
            assertTrue(run.sql().contains("GROUP BY"), run.sql());
            assertEquals(run.expected().toStableJson(), run.actual().toStableJson());
            assertNull(run.mismatchSummary(), run.sql());
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("case.json")));
        }
    }
}
