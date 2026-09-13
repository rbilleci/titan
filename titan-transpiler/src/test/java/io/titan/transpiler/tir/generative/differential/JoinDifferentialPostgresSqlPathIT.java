package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import io.titan.transpiler.tir.generative.shared.JoinPostgresSqlHarness;
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
class JoinDifferentialPostgresSqlPathIT {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    @Test
    void postgresJoinSqlPathMatchesReferenceForActiveAccounts() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_ACTIVE_ACCOUNTS));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForPaidPlans() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_PAID_PLANS));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForLeftJoinPaidWhereFilterCollapse() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForTwoJoinChain() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForTwoJoinDuplicateSecondHopMultiplication() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForLeftJoinDuplicateNullExtension() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForLeftTwoJoinNullExtension() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForLeftTwoJoinSecondHopFilter() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER));
    }

    @Test
    void postgresJoinSqlPathMatchesReferenceForLeftTwoJoinSecondHopWhereCollapse() throws Exception {
        assertMatches(JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION));
    }

    private void assertMatches(JoinCaseModel.JoinCase joinCase) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var run = new JoinPostgresSqlHarness().runJoinCase(connection, joinCase, tempDir);
            assertTrue(run.sql().contains("JOIN"), run.sql());
            assertEquals(run.expected().toStableJson(), run.actual().toStableJson());
            assertNull(run.mismatchSummary(), run.sql());
        }
    }
}
