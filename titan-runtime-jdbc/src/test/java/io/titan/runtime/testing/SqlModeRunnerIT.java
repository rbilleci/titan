package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SqlModeRunnerIT {

    @Test
    void executesRoutineCallsAgainstGeneratedSqlTargets(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            context.sqlMode(target).deploySql(createRoutineScript(target));

            context.sqlMode(target).call("insert_account", 11, "bob@example.com");
            int count = context.sqlMode(target)
                    .fetchSql("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1))
                    .get(0);

            assertEquals(1, count);
        }
    }

    private static String createRoutineScript(DatabaseTarget target) {
        if (target == DatabaseTarget.POSTGRESQL) {
            return """
                    DROP TABLE IF EXISTS accounts;
                    CREATE TABLE accounts(id INT PRIMARY KEY, email TEXT NOT NULL);
                    DROP PROCEDURE IF EXISTS insert_account(INT, TEXT);
                    CREATE PROCEDURE insert_account(p_id INT, p_email TEXT)
                    LANGUAGE SQL
                    AS $$
                    INSERT INTO accounts(id, email) VALUES (p_id, p_email)
                    $$;
                    """;
        }

        return """
                DROP TABLE IF EXISTS accounts;
                CREATE TABLE accounts(id INT PRIMARY KEY, email VARCHAR(255) NOT NULL);
                DELIMITER $$
                DROP PROCEDURE IF EXISTS insert_account$$
                CREATE PROCEDURE insert_account(IN p_id INT, IN p_email VARCHAR(255))
                BEGIN
                    INSERT INTO accounts(id, email) VALUES (p_id, p_email);
                END$$
                DELIMITER ;
                """;
    }
}
