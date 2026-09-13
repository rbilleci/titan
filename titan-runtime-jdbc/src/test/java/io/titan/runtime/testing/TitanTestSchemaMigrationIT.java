package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TitanTest(
        targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL},
        schemaSql = {
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id INT PRIMARY KEY,
                    email VARCHAR(255) NOT NULL
                );
                """
        }
)
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class TitanTestSchemaMigrationIT {

    @Test
    void appliesSchemaSqlBeforeTestsForAllTargets(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            try (ResultSet rs = context.connection(target)
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM accounts")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
