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
class SubqueryDifferentialPostgresSqlPathIT {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    @Test
    void postgresSubquerySqlPathMatchesReferenceForExists() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.EXISTS_ENTERPRISE));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForNotExists() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.NOT_EXISTS_VIP_ACTIVE));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForCorrelatedExists() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_EXISTS_ACTIVE_PLAN));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForCorrelatedNotExists() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_NOT_EXISTS_NULL_PLAN));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForCorrelatedScalar() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_EMAIL_BY_PLAN));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForCorrelatedScalarNullOrAbsent() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForScalarLookup() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.SCALAR_EMAIL_LOOKUP));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForScalarNullLookup() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.SCALAR_NULL_EMAIL_LOOKUP));
    }

    @Test
    void postgresSubquerySqlPathMatchesReferenceForCteRows() throws Exception {
        assertMatches(SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CTE_ACTIVE_ROWS));
    }

    private void assertMatches(SubqueryDifferentialCaseModel.Case subqueryCase) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var run = new SubqueryDifferentialPostgresSqlHarness().run(
                    connection,
                    subqueryCase,
                    FixtureCatalog.accountsFixture(),
                    tempDir);
            assertTrue(run.sql().contains("SELECT"), run.sql());
            assertEquals(run.expected().toStableJson(), run.actual().toStableJson());
            assertNull(run.mismatchSummary(), run.sql());
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("case.json")));
        }
    }
}
